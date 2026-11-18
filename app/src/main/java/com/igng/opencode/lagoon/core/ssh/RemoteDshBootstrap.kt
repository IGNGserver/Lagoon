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
  /** The launch URL recorded for this instance (redact before showing it). */
  val launchLine: String? = null
)

/**
 * Manages Lagoon's own `dsh web` under `~/.lagoon-ssh-dsh` with a private inter-client lock.
 *
 * DSH is a multi-instance server: every `dsh web` process gets its own launch token and its own
 * port, so a `dsh web` that the user started (Desktop, the CLI, an older Lagoon) is never a reason
 * to refuse to connect. This bootstrap only ever manages the instance it recorded itself; unverifiable
 * or stale records are treated as absent and replaced, and a process that cannot be proven to be
 * ours is never signalled. Legacy dsh-Go records under `~/.dsh-app-remote` stay read-only and are
 * reused only when process generation and launch configuration still agree.
 *
 * The server binds `127.0.0.1` on an OS-assigned port and is reached through the SSH tunnel.
 */
internal class RemoteDshBootstrap(
  private val transport: SshTransport,
  private val launchCommand: String = DEFAULT_DSH_LAUNCH_COMMAND,
  private val workingDirectory: String? = null,
  private val startTimeoutSeconds: Int = DEFAULT_START_TIMEOUT_SECONDS
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

  /** Read-only observation of the instance this client recorded (pid/url only, never a start). */
  fun probe(): RemoteDshProbe {
    val result = transport.exec(probeScript(), 20_000)
    if (looksLikeMissingShell(result)) {
      throw SshFailure(SshFailureKind.NO_SHELL, "远端没有 POSIX shell，无法启动 dsh web", tail(result), retryable = false)
    }
    if (result.stdout.contains("LAGOON unusable")) throw unusableStateFailure()
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

  /** The recorded state directory belongs to another user or is not a directory: never touch it. */
  private fun unusableStateFailure() = SshFailure(
    SshFailureKind.DSH_START,
    "无法使用远端 DSH 状态目录",
    "电脑上的 ~/.lagoon-ssh-dsh 不是普通目录或不属于当前用户。请处理该路径后重试；已有的 DSH 服务不会被停止。",
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
      # Classify one managed path into §KIND: absent, ours, or other (symlink / foreign owner).
      # A path we own is tightened to 0600/0700 in place: umask 002 machines otherwise make our own
      # record look foreign to the next launch.
      _kind() {
        KIND=absent
        [ -e "§1" ] || [ -L "§1" ] || return 0
        KIND=other
        [ ! -L "§1" ] || return 0
        case "§2" in dir) [ -d "§1" ] || return 0;; file) [ -f "§1" ] || return 0;; *) return 0;; esac
        OWN=§(stat -c '%u' "§1" 2>/dev/null) || OWN=§(stat -f '%u' "§1" 2>/dev/null) || return 0
        [ "§OWN" = "§(id -u)" ] || return 0
        KIND=ours
        case "§2" in dir) chmod 700 "§1" 2>/dev/null;; file) chmod 600 "§1" 2>/dev/null;; esac
        return 0
      }
      # 0 = verified live candidate, 1 = absent/stale (safe to replace), 2 = untouchable.
      # A record that no longer matches the live process generation is stale, not a conflict:
      # reboots and PID reuse are the normal way a managed instance disappears.
      _inspect() {
        SD=§1
        _kind "§SD" dir
        [ "§KIND" = other ] && return 2
        [ "§KIND" = ours ] || return 1
        _kind "§SD/web.pid" file
        [ "§KIND" = other ] && return 2
        [ "§KIND" = ours ] || return 1
        P=§(head -c 64 "§SD/web.pid" 2>/dev/null) || return 1
        case "§P" in ''|*[!0-9]*) return 1;; esac
        [ "§{#P}" -le 10 ] && [ "§P" -gt 1 ] 2>/dev/null || return 1
        kill -0 "§P" 2>/dev/null || return 1
        _kind "§SD/web.st" file
        [ "§KIND" = other ] && return 2
        [ "§KIND" = ours ] || return 1
        B=§(head -c 512 "§SD/web.st" 2>/dev/null) || return 1
        A=§(_st "§P") || return 1
        # BSD ps pads lstart with spaces; older clients may store a trimmed value. Compare the
        # canonical fields so the same process generation remains reusable on macOS.
        A=§(printf '%s\n' "§A" | awk '{§1=§1; print}')
        B=§(printf '%s\n' "§B" | awk '{§1=§1; print}')
        [ -n "§A" ] && [ "§A" = "§B" ] || return 1
        _kind "§SD/web.boot" file
        [ "§KIND" = other ] && return 2
        if [ "§KIND" = ours ]; then
          B=§(head -c 128 "§SD/web.boot" 2>/dev/null) || return 1
          A=§(head -c 128 /proc/sys/kernel/random/boot_id 2>/dev/null)
          [ -n "§A" ] && [ "§A" = "§B" ] || return 1
        fi
        _kind "§SD/web.cmd" file
        [ "§KIND" = other ] && return 2
        [ "§KIND" = ours ] || return 1
        C=§(head -c 4096 "§SD/web.cmd" 2>/dev/null) || return 1
        [ "§C" = "§REQUEST_COMMAND" ] || return 1
        _kind "§SD/web.cwd" file
        [ "§KIND" = other ] && return 2
        if [ "§KIND" = ours ]; then
          C=§(head -c 4096 "§SD/web.cwd" 2>/dev/null) || return 1
          [ "§C" = "§REQUEST_CWD" ] || return 1
        else
          # Older shared records did not save cwd; do not assert a configured cwd was used.
          [ -z "§REQUEST_CWD" ] || return 1
        fi
        _kind "§SD/web.log" file
        [ "§KIND" = other ] && return 2
        [ "§KIND" = ours ] || return 1
        L=§(tail -c 65536 "§SD/web.log" 2>/dev/null | grep -E 'https?://[^ ]*[?&]token=' | tail -n 1)
        [ -n "§L" ] || return 1
        RESULT_PID=§P; RESULT_URL=§L
        return 0
      }
      # 0 = reusable now, 1 = start our own, 2 = our state path is untouchable.
      # A verified managed instance wins; otherwise a verified legacy (dsh-Go) record is reused
      # read-only; otherwise we start a private instance. An unverifiable legacy record is ignored,
      # never signalled and never a reason to fail.
      _existing() {
        OWN_STATUS=1; LEGACY_STATUS=1
        _inspect "§D"; OWN_STATUS=§?
        if [ "§OWN_STATUS" -eq 2 ]; then return 2; fi
        if [ "§OWN_STATUS" -eq 0 ]; then
          OWN_PID=§RESULT_PID; OWN_URL=§RESULT_URL
          printf 'LAGOON alive %s\nLAGOON samecmd\nLAGOON reused %s\nLAGOON source managed\nLAGOON url %s\n' "§OWN_PID" "§OWN_PID" "§OWN_URL"
          return 0
        fi
        _inspect "§LEGACY"; LEGACY_STATUS=§?
        if [ "§LEGACY_STATUS" -eq 0 ]; then
          LEGACY_PID=§RESULT_PID; LEGACY_URL=§RESULT_URL
          printf 'LAGOON alive %s\nLAGOON samecmd\nLAGOON reused %s\nLAGOON source legacy\nLAGOON url %s\n' "§LEGACY_PID" "§LEGACY_PID" "§LEGACY_URL"
          return 0
        fi
        return 1
      }
      # Direct children of a pid. The launcher records the `sh -c` wrapper, whose child is the
      # actual dsh process; signalling only the wrapper would orphan that child on its port.
      _children() {
        ps -eo pid=,ppid= 2>/dev/null | awk -v p="§1" '§2==p {print §1}'
      }
      # Stop a previous generation that is provably ours (pid + recorded start stamp) but no longer
      # usable, so a lost token or an edited launch command cannot leave an orphan `dsh web` behind.
      # Anything that is not proven ours is left alone.
      _stop_ours() {
        _kind "§D/web.pid" file
        [ "§KIND" = ours ] || return 0
        _kind "§D/web.st" file
        [ "§KIND" = ours ] || return 0
        P=§(head -c 64 "§D/web.pid" 2>/dev/null) || return 0
        case "§P" in ''|*[!0-9]*) return 0;; esac
        kill -0 "§P" 2>/dev/null || return 0
        B=§(head -c 512 "§D/web.st" 2>/dev/null) || return 0
        A=§(_st "§P") || return 0
        A=§(printf '%s\n' "§A" | awk '{§1=§1; print}')
        B=§(printf '%s\n' "§B" | awk '{§1=§1; print}')
        [ -n "§A" ] && [ "§A" = "§B" ] || return 0
        KIDS=§(_children "§P")
        kill "§P" §KIDS 2>/dev/null || return 0
        i=0
        while kill -0 "§P" 2>/dev/null && [ "§i" -lt 50 ]; do sleep 0.1; i=§((i+1)); done
        kill -0 "§P" 2>/dev/null && kill -9 "§P" 2>/dev/null
        return 0
      }
      # One exclusive-create file holds both the lock and the owner pid. `set -C` (O_EXCL) is the
      # atomic primitive every POSIX shell and filesystem provides; directory creation is not
      # reliably atomic on every filesystem this runs on (a sandboxed or network mount can report
      # success to two racing creators), and an advisory flock needs an interpreter.
      _lock() {
        LOCK="§D/.bootstrap.lock"
        TICKS=${startTimeoutSeconds.coerceIn(5, 600) * 10}
        # A directory lock left by an older build cannot be taken by `>`, and nothing protects it
        # any more: drop it before competing.
        [ -d "§LOCK" ] && rmdir "§LOCK" 2>/dev/null
        i=0
        while :; do
          if ( set -o noclobber; printf '%s' "§§" > "§LOCK" ) 2>/dev/null; then
            trap 'rm -f "§LOCK" 2>/dev/null' EXIT
            return 0
          fi
          i=§((i+1))
          if [ "§i" -gt "§TICKS" ]; then printf 'LAGOON lockbusy\n'; return 1; fi
          OWNER=§(head -c 32 "§LOCK" 2>/dev/null)
          case "§OWNER" in
            ''|*[!0-9]*) ;;
            *) if ! kill -0 "§OWNER" 2>/dev/null; then rm -f "§LOCK" 2>/dev/null; continue; fi;;
          esac
          # An old client left the file without an owner; replace it once it looks abandoned.
          if [ "§i" -eq 30 ]; then
            NOW=§(date +%s 2>/dev/null || echo 0)
            MT=§(stat -c %Y "§LOCK" 2>/dev/null || stat -f %m "§LOCK" 2>/dev/null || echo "§NOW")
            [ "§((NOW - MT))" -gt 90 ] && rm -f "§LOCK" 2>/dev/null
          fi
          sleep 0.1
        done
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
    appendLine("if [ \"\$S\" -eq 2 ]; then printf 'LAGOON unusable\\n'; fi")
    appendLine("true")
  }

  fun startScript(): String = buildString {
    append(prelude())
    appendLine("printf 'LAGOON home %s\\n' \"\$HOME\"")
    append(shell("""
      # Our own state directory is ours to create and tighten; a symlink or a foreign owner is not.
      _kind "§D" dir
      [ "§KIND" = other ] && { printf 'LAGOON unusable\n'; exit 70; }
      [ "§KIND" = ours ] || mkdir -m 700 "§D" 2>/dev/null || true
      _kind "§D" dir
      [ "§KIND" = ours ] || { printf 'LAGOON nodir\n'; exit 70; }
      _lock || exit 73
      _existing; STATE=§?
      if [ "§STATE" -eq 0 ]; then exit 0; fi
      if [ "§STATE" -eq 2 ]; then printf 'LAGOON unusable\n'; exit 70; fi
      # Stale record: replace it, but first stop only the generation we can prove we started.
      _stop_ours
      # A racing client may have finished while this one waited. Reusing its record here keeps a
      # lost lock race harmless instead of truncating a live instance's log and metadata.
      sleep 0.2
      _existing; RACE=§?
      if [ "§RACE" -eq 0 ]; then exit 0; fi
      if [ "§RACE" -eq 2 ]; then printf 'LAGOON unusable\n'; exit 70; fi
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
      NO_COLOR=1; export NO_COLOR
      N=''; if command -v nohup >/dev/null 2>&1; then N=nohup; fi
    """))
    val launch = "$command --host 127.0.0.1 --port 0 --no-open"
    appendLine("LAUNCH=${SshShell.quote(launch)}")
    appendLine("\$N sh -c 'eval \"\$1\" >>\"\$D/web.log\" 2>&1 & echo \$! >\"\$D/web.pid\"; wait' sh \"\$LAUNCH\" >/dev/null 2>&1 &")
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
    var noCwd = false
    var exited = false
    var timedOut = false
    var unusable = false
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
        "nocwd" -> noCwd = true
        "exited" -> exited = true
        "timeout" -> timedOut = true
        "unusable" -> unusable = true
        "lockbusy" -> lockBusy = true
        "metadata" -> metadataError = true
        "nodir" -> noDirectory = true
        "log" -> log += value
      }
    }
    if (unusable) throw unusableStateFailure()
    if (lockBusy) throw SshFailure(
      SshFailureKind.DSH_START, "另一台设备正在启动这台电脑上的 DSH",
      "请稍后重试；若一直出现，请检查电脑上的 ~/.lagoon-ssh-dsh/.bootstrap.lock。", retryable = true
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
    /** First `dsh web` on a machine initializes `~/.dsh/profiles/web` (dependency install). */
    internal const val DEFAULT_START_TIMEOUT_SECONDS = 180

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
