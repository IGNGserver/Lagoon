package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.DEFAULT_DSH_LAUNCH_COMMAND
import com.igng.opencode.lagoon.core.RemoteDshEndpoint
import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshShell
import java.net.URI

internal data class RemoteDshProbe(
  val home: String? = null,
  val pid: Int? = null,
  val sameCommand: Boolean = false,
  val launchLine: String? = null
) {
  val reusable: Boolean get() = pid != null && sameCommand && launchLine != null
}

/**
 * Manages Lagoon's `dsh web` under `~/.lagoon-ssh-dsh` with a private inter-client lock.
 * Existing dsh-Go/legacy records under `~/.dsh-app-remote` are read-only and reused only when the
 * recorded process generation and configuration agree. A conflicting or unverifiable live process
 * is never stopped. The lock coordinates Lagoon clients; dsh-Go does not participate in it.
 *
 * The server binds `127.0.0.1` on an OS-assigned port and is reached through the SSH tunnel.
 */
internal class RemoteDshBootstrap(
  private val transport: SshTransport,
  private val launchCommand: String = DEFAULT_DSH_LAUNCH_COMMAND,
  private val workingDirectory: String? = null,
  private val startTimeoutSeconds: Int = 60
) {
  private val command = launchCommand.trim().ifBlank { DEFAULT_DSH_LAUNCH_COMMAND }

  fun ensureRunning(forceRestart: Boolean = false, onDetail: (String) -> Unit = {}): RemoteDshEndpoint {
    if (forceRestart) throw SshFailure(
      SshFailureKind.DSH_START,
      "不能自动重启已有的 DSH 服务",
      "为避免中断其他客户端，请先在电脑上确认并处理已有服务。",
      retryable = false
    )
    onDetail("正在检查远端的 dsh web")
    return start()
  }

  /** Read-only observation. Launch admission is rechecked under the managed lock in [start]. */
  fun probe(): RemoteDshProbe {
    val result = transport.exec(probeScript(), 20_000)
    if (looksLikeMissingShell(result)) {
      throw SshFailure(SshFailureKind.NO_SHELL, "远端没有 POSIX shell，无法启动 dsh web", tail(result), retryable = false)
    }
    if (result.stdout.contains("LAGOON conflict")) throw existingInstanceFailure()
    if (result.exitCode != 0) throw SshFailure(SshFailureKind.DSH_START, "无法检查远端 DSH 服务", retryable = true)
    return parseProbe(result.stdout)
  }

  /** The complete check-and-start transaction runs in one remote shell while holding our lock. */
  fun start(): RemoteDshEndpoint {
    val result = transport.exec(startScript(), (startTimeoutSeconds.coerceIn(5, 600) + 45) * 1_000L)
    if (looksLikeMissingShell(result)) {
      throw SshFailure(SshFailureKind.NO_SHELL, "远端没有 POSIX shell，无法启动 dsh web", tail(result), retryable = false)
    }
    return parseStart(result)
  }

  private fun existingInstanceFailure() = SshFailure(
    SshFailureKind.DSH_START,
    "已有 DSH 服务，但无法安全复用",
    "请在电脑上检查现有服务及其启动配置；Lagoon 不会自动停止它。",
    retryable = false
  )

  // ---------------------------------------------------------------- scripts
  // § stands for a shell dollar so scripts remain readable in Kotlin raw strings.
  private fun shell(script: String): String = script.trimIndent().replace('§', '$') + "\n"

  private fun prelude(): String = buildString {
    appendLine("umask 077")
    appendLine("LC_ALL=C; export LC_ALL")
    appendLine("D=\"\$HOME/.lagoon-ssh-dsh\"")
    appendLine("LEGACY=\"\$HOME/.dsh-app-remote\"")
    appendLine("REQUEST_COMMAND=${SshShell.quote(command)}")
    appendLine("REQUEST_CWD=${SshShell.quote(workingDirectory?.trim().orEmpty())}")
    appendLine("export D")
    append(shell("""
      _st() {
        if [ -r "/proc/§1/stat" ]; then
          S=§(cat "/proc/§1/stat" 2>/dev/null) || return 1
          S=§{S##*) }
          set -f; set -- §S; set +f
          [ "§#" -ge 20 ] || return 1
          shift 19
          printf '%s\n' "§1"
        else
          ps -p "§1" -o lstart= 2>/dev/null
        fi
      }
      # Reject symlinks, foreign owners and group/world-writable metadata before using it.
      _safe() {
        [ ! -L "§1" ] || return 1
        case "§2" in dir) [ -d "§1" ] || return 1;; file) [ -f "§1" ] || return 1;; *) return 1;; esac
        INFO=§(stat -c '%u %a' "§1" 2>/dev/null) || INFO=§(stat -f '%u %Lp' "§1" 2>/dev/null) || return 1
        FILE_OWNER=§{INFO%% *}; MODE=§{INFO#* }
        [ "§FILE_OWNER" = "§(id -u)" ] || return 1
        case "§MODE" in ''|*[!0-7]*) return 1;; esac
        PREFIX=§{MODE%?}; GROUP=§{PREFIX#§{PREFIX%?}}; OTHER=§{MODE#§{MODE%?}}
        case "§GROUP" in ''|2|3|6|7) return 1;; esac
        case "§OTHER" in ''|2|3|6|7) return 1;; esac
      }
      # 0 = verified live candidate, 1 = absent/dead, 2 = present but unsafe to reuse.
      _inspect() {
        SD=§1
        if [ ! -e "§SD" ] && [ ! -L "§SD" ]; then return 1; fi
        _safe "§SD" dir || return 2
        if [ ! -e "§SD/web.pid" ] && [ ! -L "§SD/web.pid" ]; then
          [ ! -e "§SD/web.launching" ] || return 2
          return 1
        fi
        _safe "§SD/web.pid" file || return 2
        P=§(head -c 64 "§SD/web.pid" 2>/dev/null) || return 2
        case "§P" in ''|*[!0-9]*) return 2;; esac
        [ "§{#P}" -le 10 ] && [ "§P" -gt 1 ] 2>/dev/null || return 2
        if ! kill -0 "§P" 2>/dev/null; then
          # A permission-denied or otherwise visible PID is not proof of death.
          V=§(ps -p "§P" -o pid= 2>/dev/null | tr -d ' ')
          [ -z "§V" ] || return 2
          return 1
        fi
        _safe "§SD/web.st" file && [ -s "§SD/web.st" ] || return 2
        B=§(head -c 512 "§SD/web.st" 2>/dev/null) || return 2
        A=§(_st "§P") || return 2
        # BSD ps pads lstart with spaces; older clients may store a trimmed value. Compare the
        # canonical fields so the same process generation remains reusable on macOS.
        A=§(printf '%s\n' "§A" | awk '{§1=§1; print}')
        B=§(printf '%s\n' "§B" | awk '{§1=§1; print}')
        [ -n "§A" ] && [ "§A" = "§B" ] || return 2
        if [ -e "§SD/web.boot" ] || [ -L "§SD/web.boot" ]; then
          _safe "§SD/web.boot" file && [ -s "§SD/web.boot" ] || return 2
          [ -r /proc/sys/kernel/random/boot_id ] || return 2
          B=§(head -c 128 "§SD/web.boot" 2>/dev/null) || return 2
          A=§(head -c 128 /proc/sys/kernel/random/boot_id 2>/dev/null) || return 2
          [ -n "§A" ] && [ "§A" = "§B" ] || return 2
        fi
        _safe "§SD/web.cmd" file || return 2
        C=§(head -c 4096 "§SD/web.cmd" 2>/dev/null) || return 2
        [ "§C" = "§REQUEST_COMMAND" ] || return 2
        if [ -e "§SD/web.cwd" ] || [ -L "§SD/web.cwd" ]; then
          _safe "§SD/web.cwd" file || return 2
          C=§(head -c 4096 "§SD/web.cwd" 2>/dev/null) || return 2
          [ "§C" = "§REQUEST_CWD" ] || return 2
        else
          # Older shared records did not save cwd; do not assert a configured cwd was used.
          [ -z "§REQUEST_CWD" ] || return 2
        fi
        _safe "§SD/web.log" file || return 2
        L=§(tail -c 65536 "§SD/web.log" 2>/dev/null | grep -E 'https?://[^ ]*[?&]token=' | tail -n 1)
        [ -n "§L" ] || return 2
        RESULT_PID=§P; RESULT_URL=§L
        return 0
      }
      _existing() {
        OWN_STATUS=1; LEGACY_STATUS=1
        _inspect "§D"; OWN_STATUS=§?
        if [ "§OWN_STATUS" -eq 0 ]; then OWN_PID=§RESULT_PID; OWN_URL=§RESULT_URL; fi
        _inspect "§LEGACY"; LEGACY_STATUS=§?
        if [ "§LEGACY_STATUS" -eq 0 ]; then LEGACY_PID=§RESULT_PID; LEGACY_URL=§RESULT_URL; fi
        [ "§OWN_STATUS" -ne 2 ] && [ "§LEGACY_STATUS" -ne 2 ] || return 2
        if [ "§OWN_STATUS" -eq 0 ] && [ "§LEGACY_STATUS" -eq 0 ] && [ "§OWN_PID" != "§LEGACY_PID" ]; then return 2; fi
        if [ "§OWN_STATUS" -eq 0 ]; then
          printf 'LAGOON alive %s\nLAGOON samecmd\nLAGOON reused %s\nLAGOON source managed\nLAGOON url %s\n' "§OWN_PID" "§OWN_PID" "§OWN_URL"
          return 0
        fi
        if [ "§LEGACY_STATUS" -eq 0 ]; then
          printf 'LAGOON alive %s\nLAGOON samecmd\nLAGOON reused %s\nLAGOON source legacy\nLAGOON url %s\n' "§LEGACY_PID" "§LEGACY_PID" "§LEGACY_URL"
          return 0
        fi
        return 1
      }
    """))
  }

  private fun dshSearch(required: Boolean): String = buildString {
    appendLine(SshExecutableSearch.prologue)
    if (required) appendLine("if ! command -v dsh >/dev/null 2>&1; then printf 'LAGOON nodsh\\n'; exit 127; fi")
    appendLine("if command -v dsh >/dev/null 2>&1; then printf 'LAGOON dsh %s\\n' \"\$(command -v dsh)\"; fi")
  }

  fun probeScript(): String = buildString {
    append(prelude())
    appendLine("printf 'LAGOON home %s\\n' \"\$HOME\"")
    appendLine("_existing; S=\$?")
    appendLine("if [ \"\$S\" -eq 2 ]; then printf 'LAGOON conflict\\n'; fi")
    appendLine("true")
  }

  fun startScript(): String = buildString {
    append(prelude())
    appendLine("printf 'LAGOON home %s\\n' \"\$HOME\"")
    append(shell("""
      # Lagoon's lock never claims to coordinate non-participating clients such as dsh-Go.
      [ ! -L "§D" ] || { printf 'LAGOON nodir\n'; exit 70; }
      if [ ! -d "§D" ]; then mkdir -m 700 "§D" 2>/dev/null || [ -d "§D" ] || { printf 'LAGOON nodir\n'; exit 70; }; fi
      [ ! -L "§D" ] && [ -d "§D" ] || { printf 'LAGOON nodir\n'; exit 70; }
      OWNER=§(stat -c '%u' "§D" 2>/dev/null) || OWNER=§(stat -f '%u' "§D" 2>/dev/null) || OWNER=''
      [ -n "§OWNER" ] && [ "§OWNER" = "§(id -u)" ] || { printf 'LAGOON nodir\n'; exit 70; }
      chmod 700 "§D" 2>/dev/null || { printf 'LAGOON nodir\n'; exit 70; }
      LOCK="§D/.bootstrap.lock"
      command -v python3 >/dev/null 2>&1 || { printf 'LAGOON nopython\n'; exit 127; }
      [ ! -L "§LOCK" ] && { [ ! -e "§LOCK" ] || [ -f "§LOCK" ]; } || { printf 'LAGOON lockbusy\n'; exit 73; }
      # Keep an OS advisory lock on the shell's open file description. Unlike an existence lock,
      # it is automatically released even if SSH or the bootstrap process is killed abruptly.
      # The persistent file is never removed: unlinking it would split concurrent clients across
      # different inodes. Python is also required for the verified SSH tunnel.
      exec 9>>"§LOCK"
      python3 -c '
import fcntl, os, stat, sys, time
info = os.fstat(9)
if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o022:
    sys.exit(1)
deadline = time.monotonic() + 10
while True:
    try:
        fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)
        break
    except BlockingIOError:
        if time.monotonic() >= deadline: sys.exit(1)
        time.sleep(0.1)
' 2>/dev/null || { printf 'LAGOON lockbusy\n'; exit 73; }
      _existing; STATE=§?
      if [ "§STATE" -eq 0 ]; then exit 0; fi
      if [ "§STATE" -eq 2 ]; then printf 'LAGOON conflict\n'; exit 73; fi
      # Detect other same-user dsh web processes without matching this script's own ancestors.
      ANCESTORS=" §§ "; A=§§; i=0
      while [ "§i" -lt 12 ]; do
        A=§(ps -p "§A" -o ppid= 2>/dev/null | tr -d ' ')
        case "§A" in ''|*[!0-9]*|0|1) break;; esac
        ANCESTORS="§ANCESTORS§A "; i=§((i+1))
      done
      if command -v pgrep >/dev/null 2>&1; then
        CANDIDATES=§(pgrep -u "§(id -u)" -f '[d]sh[^[:space:]]*[[:space:]]+web' 2>/dev/null || true)
      else
        CANDIDATES=§(ps -u "§(id -u)" -o pid= -o args= 2>/dev/null | awk '/[d]sh[^[:space:]]*[[:space:]]+web/ {print §1}')
      fi
      for C in §CANDIDATES; do
        case "§ANCESTORS" in *" §C "*) continue;; esac
        # Another Lagoon bootstrap can be waiting on this lock with the launch command in its
        # own shell argv. It is not a running dsh process and must not block the lock owner.
        ARGS=§(ps -p "§C" -o args= 2>/dev/null)
        case "§ARGS" in *'REQUEST_COMMAND='*'LAGOON source managed'*) continue;; esac
        printf 'LAGOON busy\n'; exit 73
      done
      # Recheck the legacy state immediately before launch. Other clients do not share our lock.
      _existing; STATE=§?
      if [ "§STATE" -eq 0 ]; then exit 0; fi
      if [ "§STATE" -eq 2 ]; then printf 'LAGOON conflict\n'; exit 73; fi
    """))
    append(dshSearch(required = command == DEFAULT_DSH_LAUNCH_COMMAND))
    val cwd = workingDirectory?.trim()
    if (!cwd.isNullOrEmpty()) {
      appendLine("cd ${SshShell.remotePathExpr(cwd)} || { printf 'LAGOON nocwd\\n'; exit 71; }")
    }
    append(shell("""
      # Only our own stale metadata can be replaced; legacy files are never written or removed.
      rm -f "§D/web.pid" "§D/web.st" "§D/web.boot"
      printf '%s' "§REQUEST_COMMAND" > "§D/web.cmd"
      printf '%s' "§REQUEST_CWD" > "§D/web.cwd"
      : > "§D/web.log"
      : > "§D/web.launching"
      NO_COLOR=1; export NO_COLOR
      N=''; if command -v nohup >/dev/null 2>&1; then N=nohup; fi
    """))
    val launch = "$command --host 127.0.0.1 --port 0 --no-open"
    appendLine("LAUNCH=${SshShell.quote(launch)}")
    appendLine("\$N sh -c 'eval \"\$1\" >>\"\$D/web.log\" 2>&1 & echo \$! >\"\$D/web.pid\"; wait' sh \"\$LAUNCH\" >/dev/null 2>&1 9>&- &")
    append(shell("""
      i=0
      while [ ! -s "§D/web.pid" ] && [ "§i" -lt 20 ]; do sleep 0.1; i=§((i+1)); done
      P=§(head -c 64 "§D/web.pid" 2>/dev/null)
      case "§P" in ''|*[!0-9]*) printf 'LAGOON metadata\n'; exit 1;; esac
      [ "§{#P}" -le 10 ] && [ "§P" -gt 1 ] 2>/dev/null || { printf 'LAGOON metadata\n'; exit 1; }
      ST=§(_st "§P" 2>/dev/null)
      [ -n "§ST" ] || { printf 'LAGOON metadata\n'; exit 1; }
      printf '%s\n' "§ST" > "§D/web.st"
      if [ -r /proc/sys/kernel/random/boot_id ]; then
        head -c 128 /proc/sys/kernel/random/boot_id > "§D/web.boot" || { printf 'LAGOON metadata\n'; exit 1; }
      fi
      printf 'LAGOON started %s\nLAGOON source managed\n' "§P"
      i=0
    """))
    appendLine("while [ \"\$i\" -lt ${startTimeoutSeconds.coerceIn(5, 600)} ]; do")
    append(shell("""
        L=§(tail -c 65536 "§D/web.log" 2>/dev/null | grep -E 'https?://[^ ]*[?&]token=' | tail -n 1)
        if [ -n "§L" ]; then
          # A concurrent legacy client cannot be locked here; do not return a route if it appeared.
          _inspect "§LEGACY"; LEGACY_STATUS=§?
          if [ "§LEGACY_STATUS" -ne 1 ]; then printf 'LAGOON conflict\n'; exit 73; fi
          rm -f "§D/web.launching"
          printf 'LAGOON url %s\n' "§L"; exit 0
        fi
        if ! kill -0 "§P" 2>/dev/null; then
          printf 'LAGOON exited\n'
          tail -n 20 "§D/web.log" 2>/dev/null | sed 's/^/LAGOON log /'
          exit 1
        fi
        sleep 1; i=§((i+1))
      done
      printf 'LAGOON timeout\n'
      tail -n 20 "§D/web.log" 2>/dev/null | sed 's/^/LAGOON log /'
      exit 1
    """))
  }

  // ---------------------------------------------------------------- parsing

  private fun parseProbe(stdout: String): RemoteDshProbe {
    var home: String? = null
    var pid: Int? = null
    var same = false
    var url: String? = null
    for ((key, value) in tagged(stdout)) {
      when (key) {
        "home" -> home = value.ifBlank { null }
        "alive" -> pid = value.trim().toIntOrNull()
        "samecmd" -> same = true
        "url" -> url = value.trim().ifBlank { null }
      }
    }
    return RemoteDshProbe(home, pid, same, url)
  }

  private fun parseStart(result: RemoteExecResult): RemoteDshEndpoint {
    var home: String? = null
    var dshPath: String? = null
    var pid: Int? = null
    var url: String? = null
    var noDsh = false
    var noPython = false
    var noCwd = false
    var exited = false
    var timedOut = false
    var conflict = false
    var busy = false
    var lockBusy = false
    var metadataError = false
    var noDirectory = false
    var reused = false
    var ownershipDirectory: String? = null
    val log = ArrayList<String>()
    for ((key, value) in tagged(result.stdout)) {
      when (key) {
        "home" -> home = value.ifBlank { null }
        "dsh" -> dshPath = value.trim().ifBlank { null }
        "started" -> pid = value.trim().toIntOrNull()
        "reused" -> { pid = value.trim().toIntOrNull(); reused = true }
        "source" -> ownershipDirectory = when (value.trim()) {
          "managed" -> ".lagoon-ssh-dsh"
          "legacy" -> ".dsh-app-remote"
          else -> null
        }
        "url" -> url = value.trim()
        "nodsh" -> noDsh = true
        "nopython" -> noPython = true
        "nocwd" -> noCwd = true
        "exited" -> exited = true
        "timeout" -> timedOut = true
        "conflict" -> conflict = true
        "busy" -> busy = true
        "lockbusy" -> lockBusy = true
        "metadata" -> metadataError = true
        "nodir" -> noDirectory = true
        "log" -> log += value
      }
    }
    if (conflict || busy) throw existingInstanceFailure()
    if (noPython) throw SshFailure(
      SshFailureKind.DSH_START, "电脑缺少 Python 3",
      "SSH 模式需要 Python 3 才能安全启动和连接 DSH。请安装后重试。", retryable = true
    )
    if (lockBusy) throw SshFailure(
      SshFailureKind.DSH_START, "另一台设备正在检查或启动 DSH",
      "请稍后重试；若一直出现，请在电脑上检查 Lagoon 的启动锁。", retryable = true
    )
    if (metadataError || noDirectory) throw SshFailure(
      SshFailureKind.DSH_START, "无法确认 DSH 启动状态",
      "请检查电脑上的 Lagoon 状态目录；已有进程不会被自动停止。", retryable = false
    )
    val parsed = url?.let(::parseLaunchLine)
    if (parsed != null && result.exitCode == 0 && pid != null && ownershipDirectory != null)
      return RemoteDshEndpoint(parsed.port, parsed.token, pid, reused = reused, home = home,
        dshPath = dshPath, ownershipStateDirectory = ownershipDirectory)
    if (parsed != null) throw SshFailure(
      SshFailureKind.DSH_START, "无法确认 DSH 服务身份", "现有服务不会被自动停止。", retryable = false
    )
    // 有启动链接但解析不出 loopback 端口：远端 profile/启动命令带了 `--public-url` 之类的配置，
    // 打印的不是 127.0.0.1:<port>。给出可执行的诊断，而不是笼统的「没能启动 dsh web」。
    if (url != null) {
      throw SshFailure(
        SshFailureKind.DSH_START,
        "远端 dsh web 没有监听 loopback 地址，SSH 隧道无法接入",
        listOf(
          "启动行：${SshShell.redactTokens(url)}",
          "SSH 模式需要一个 http://127.0.0.1:<端口> 形态的启动链接。请检查那台机器的 dsh web profile 是否配置了 publicUrl，或在「启动命令」里去掉 --public-url / --public-host。"
        ).joinToString("\n"),
        retryable = false
      )
    }
    val tail = SshShell.redactTokens(log.filter(String::isNotBlank).joinToString("\n")).trim()
    if (noCwd) {
      throw SshFailure(
        SshFailureKind.DSH_START,
        "远端工作目录不存在：${workingDirectory?.trim().orEmpty()}",
        tail,
        retryable = false
      )
    }
    if (noDsh || (exited && looksNotFound(tail))) {
      throw SshFailure(
        SshFailureKind.DSH_NOT_FOUND,
        if (command == DEFAULT_DSH_LAUNCH_COMMAND) "远端没有安装 dsh" else "远端找不到启动命令：$command",
        listOf(
          if (command == DEFAULT_DSH_LAUNCH_COMMAND) "在主机上运行：npm i -g @deepseek-ai/dsh" else "",
          tail
        ).filter(String::isNotBlank).joinToString("\n"),
        retryable = false
      )
    }
    if (timedOut) throw SshFailure(SshFailureKind.DSH_TIMEOUT, "dsh web 启动超时", tail)
    if (exited) throw SshFailure(SshFailureKind.DSH_START, "dsh web 启动后退出了", tail, retryable = false)
    throw SshFailure(SshFailureKind.DSH_START, "没能启动 dsh web", tail)
  }

  private fun tagged(stdout: String): Sequence<Pair<String, String>> = sequence {
    for (raw in stdout.lineSequence()) {
      val line = raw.trimEnd()
      if (!line.startsWith("LAGOON ")) continue
      val rest = line.removePrefix("LAGOON ")
      val space = rest.indexOf(' ')
      if (space < 0) yield(rest to "") else yield(rest.substring(0, space) to rest.substring(space + 1))
    }
  }

  private fun looksLikeMissingShell(result: RemoteExecResult): Boolean =
    !result.stdout.contains("LAGOON") &&
      Regex("'sh' is not recognized|sh: (command )?not found|command not found: sh|No such file or directory.*\\bsh\\b")
        .containsMatchIn(result.stderr)

  private fun tail(result: RemoteExecResult): String =
    listOf(result.stderr.trim(), result.stdout.trim()).filter(String::isNotBlank).joinToString("\n").take(4_000)

  private fun looksNotFound(log: String): Boolean =
    Regex("command not found|not found|No such file or directory|can't open|ENOENT", RegexOption.IGNORE_CASE)
      .containsMatchIn(log)

  internal companion object {
    internal fun parseLaunchLine(line: String): RemoteDshEndpoint? {
      val rawUrl = Regex("https?://[^\\s]+").find(line)?.value ?: return null
      return try {
        val uri = URI(rawUrl)
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        if (uri.userInfo != null || (uri.host != "127.0.0.1" && !uri.host.equals("localhost", ignoreCase = true))) return null
        if (uri.path != "/" || uri.fragment != null) return null
        val query = uri.rawQuery ?: return null
        if (!query.matches(Regex("token=[A-Za-z0-9._~-]+"))) return null
        val token = query.removePrefix("token=")
        val port = uri.port
        if (port !in 1..65535) return null
        RemoteDshEndpoint(port, token)
      } catch (_: Exception) {
        null
      }
    }
  }
}
