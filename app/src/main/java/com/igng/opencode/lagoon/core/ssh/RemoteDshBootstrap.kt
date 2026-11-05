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
 * Finds or starts the managed `dsh web` on the remote POSIX host. The files and command shape are
 * deliberately compatible with dsh-Go's `~/.dsh-app-remote` bootstrap, so both apps can reuse the
 * same remote process:
 *
 *  - `web.pid` — the process we started;
 *  - `web.log` — stdout/stderr, truncated on each start so the last `token=` line is current;
 *  - `web.cmd` — the launch command, so a different command forces a restart;
 *  - `web.st`  — process start time, guarding against PID reuse after a reboot.
 *
 * The server always binds `127.0.0.1` on an OS-assigned port and is reached through the SSH tunnel.
 */
internal class RemoteDshBootstrap(
  private val transport: SshTransport,
  private val launchCommand: String = DEFAULT_DSH_LAUNCH_COMMAND,
  private val workingDirectory: String? = null,
  private val startTimeoutSeconds: Int = 60
) {
  private val command = launchCommand.trim().ifBlank { DEFAULT_DSH_LAUNCH_COMMAND }

  fun ensureRunning(forceRestart: Boolean = false, onDetail: (String) -> Unit = {}): RemoteDshEndpoint {
    if (!forceRestart) {
      val probe = probe()
      if (probe.reusable) {
        val parsed = parseLaunchLine(probe.launchLine!!)
        if (parsed != null) {
          onDetail("复用 127.0.0.1:${parsed.port} 上已运行的 dsh web")
          return RemoteDshEndpoint(parsed.port, parsed.token, probe.pid, reused = true, home = probe.home)
        }
      }
    }
    onDetail("正在启动远端的 dsh web")
    return start()
  }

  fun probe(): RemoteDshProbe {
    val result = transport.exec(probeScript(), 20_000)
    if (looksLikeMissingShell(result)) {
      throw SshFailure(SshFailureKind.NO_SHELL, "远端没有 POSIX shell，无法启动 dsh web", tail(result), retryable = false)
    }
    return parseProbe(result.stdout)
  }

  fun start(): RemoteDshEndpoint {
    val result = transport.exec(startScript(), (startTimeoutSeconds + 30) * 1_000L)
    if (looksLikeMissingShell(result)) {
      throw SshFailure(SshFailureKind.NO_SHELL, "远端没有 POSIX shell，无法启动 dsh web", tail(result), retryable = false)
    }
    return parseStart(result)
  }

  fun stop() {
    runCatching { transport.exec(stopScript(), 20_000) }
  }

  // ---------------------------------------------------------------- scripts

  private fun prelude(): String = buildString {
    appendLine("umask 077")
    appendLine("D=\"\$HOME/.dsh-app-remote\"")
    appendLine("_st() {")
    appendLine("  if [ -r \"/proc/\$1/stat\" ]; then")
    appendLine("    S=\$(cat \"/proc/\$1/stat\" 2>/dev/null) || return 1")
    appendLine("    S=\${S##*) }")
    appendLine("    set -- \$S")
    appendLine("    [ \"\$#\" -ge 20 ] || return 1")
    appendLine("    shift 19")
    appendLine("    printf '%s\\n' \"\$1\"")
    appendLine("  else")
    appendLine("    ps -p \"\$1\" -o lstart= 2>/dev/null")
    appendLine("  fi")
    appendLine("}")
    appendLine("ours() {")
    appendLine("  kill -0 \"\$1\" 2>/dev/null || return 1")
    appendLine("  if [ -f \"\$D/web.st\" ]; then")
    appendLine("    A=\$(_st \"\$1\") || return 1")
    appendLine("    B=\$(cat \"\$D/web.st\" 2>/dev/null) || return 1")
    appendLine("    [ -n \"\$A\" ] && [ -n \"\$B\" ] && [ \"\$A\" = \"\$B\" ]")
    appendLine("    return \$?")
    appendLine("  fi")
    appendLine("  A=\$(ps -p \"\$1\" -o args= 2>/dev/null) || return 1")
    appendLine("  case \"\$A\" in")
    appendLine("    *'--host 127.0.0.1 --port 0 --no-open'*) return 0 ;;")
    appendLine("    *) return 1 ;;")
    appendLine("  esac")
    appendLine("}")
  }

  private fun dshSearch(): String = buildString {
    appendLine("if ! command -v dsh >/dev/null 2>&1; then")
    appendLine("  F=\"\"")
    appendLine("  for c in \"\$HOME/.local/bin\" \"\$HOME/.npm-global/bin\" \"\$HOME/.npm/bin\" \"\$HOME/.volta/bin\" \\")
    appendLine("           \"\$HOME/.bun/bin\" \"\$HOME/.local/share/pnpm\" \"\$HOME/Library/pnpm\" \\")
    appendLine("           /usr/local/bin /opt/homebrew/bin \"\$HOME\"/.nvm/versions/node/*/bin \\")
    appendLine("           \"\$HOME\"/.local/share/fnm/node-versions/*/installation/bin; do")
    appendLine("    if [ -x \"\$c/dsh\" ]; then F=\"\$c\"; fi")
    appendLine("  done")
    appendLine("  if [ -n \"\$F\" ]; then PATH=\"\$F:\$PATH\"; export PATH; fi")
    appendLine("fi")
    appendLine("if ! command -v dsh >/dev/null 2>&1; then printf 'LAGOON nodsh\\n'; exit 127; fi")
    appendLine("printf 'LAGOON dsh %s\\n' \"\$(command -v dsh)\"")
  }

  private fun stopOld(): String = buildString {
    appendLine("if [ -f \"\$D/web.pid\" ]; then")
    appendLine("  P=\$(cat \"\$D/web.pid\" 2>/dev/null)")
    appendLine("  if [ -n \"\$P\" ] && ours \"\$P\"; then")
    appendLine("    if command -v pkill >/dev/null 2>&1; then pkill -TERM -P \"\$P\" 2>/dev/null; fi")
    appendLine("    kill \"\$P\" 2>/dev/null")
    appendLine("    i=0")
    appendLine("    while kill -0 \"\$P\" 2>/dev/null && [ \"\$i\" -lt 10 ]; do sleep 1; i=\$((i+1)); done")
    appendLine("    kill -9 \"\$P\" 2>/dev/null")
    appendLine("  fi")
    appendLine("  rm -f \"\$D/web.pid\"")
    appendLine("fi")
  }

  fun probeScript(): String = buildString {
    append(prelude())
    appendLine("printf 'LAGOON home %s\\n' \"\$HOME\"")
    appendLine("if [ -f \"\$D/web.pid\" ]; then")
    appendLine("  P=\$(cat \"\$D/web.pid\" 2>/dev/null)")
    appendLine("  if [ -n \"\$P\" ] && ours \"\$P\"; then")
    appendLine("    printf 'LAGOON alive %s\\n' \"\$P\"")
    appendLine("    if [ \"\$(cat \"\$D/web.cmd\" 2>/dev/null)\" = ${SshShell.quote(command)} ]; then printf 'LAGOON samecmd\\n'; fi")
    appendLine("    L=\$(grep -E 'https?://[^ ]*[?&]token=' \"\$D/web.log\" 2>/dev/null | tail -n 1)")
    appendLine("    if [ -n \"\$L\" ]; then printf 'LAGOON url %s\\n' \"\$L\"; fi")
    appendLine("  else")
    appendLine("    rm -f \"\$D/web.pid\"")
    appendLine("  fi")
    appendLine("fi")
    appendLine("true")
  }

  fun startScript(): String = buildString {
    append(prelude())
    appendLine("printf 'LAGOON home %s\\n' \"\$HOME\"")
    appendLine("mkdir -p \"\$D\" || { printf 'LAGOON nodir\\n'; exit 70; }")
    appendLine("chmod 700 \"\$D\" 2>/dev/null")
    append(stopOld())
    append(dshSearch())
    val cwd = workingDirectory?.trim()
    if (!cwd.isNullOrEmpty()) appendLine("cd ${SshShell.remotePathExpr(cwd)} 2>/dev/null || printf 'LAGOON nocwd\\n'")
    appendLine("printf '%s' ${SshShell.quote(command)} > \"\$D/web.cmd\"")
    appendLine(": > \"\$D/web.log\"")
    appendLine("NO_COLOR=1; export NO_COLOR")
    appendLine("N=\"\"; if command -v nohup >/dev/null 2>&1; then N=nohup; fi")
    appendLine("\$N $command --host 127.0.0.1 --port 0 --no-open </dev/null >>\"\$D/web.log\" 2>&1 &")
    appendLine("P=\$!")
    appendLine("_st \"\$P\" > \"\$D/web.st\"")
    appendLine("printf '%s\\n' \"\$P\" > \"\$D/web.pid\"")
    appendLine("printf 'LAGOON started %s\\n' \"\$P\"")
    appendLine("i=0")
    appendLine("while [ \$i -lt ${startTimeoutSeconds.coerceIn(5, 600)} ]; do")
    appendLine("  L=\$(grep -E 'https?://[^ ]*[?&]token=' \"\$D/web.log\" 2>/dev/null | tail -n 1)")
    appendLine("  if [ -n \"\$L\" ]; then printf 'LAGOON url %s\\n' \"\$L\"; exit 0; fi")
    appendLine("  if ! kill -0 \"\$P\" 2>/dev/null; then")
    appendLine("    printf 'LAGOON exited\\n'")
    appendLine("    tail -n 20 \"\$D/web.log\" 2>/dev/null | sed 's/^/LAGOON log /'")
    appendLine("    exit 1")
    appendLine("  fi")
    appendLine("  sleep 1; i=\$((i+1))")
    appendLine("done")
    appendLine("printf 'LAGOON timeout\\n'")
    appendLine("tail -n 20 \"\$D/web.log\" 2>/dev/null | sed 's/^/LAGOON log /'")
    appendLine("exit 1")
  }

  fun stopScript(): String = buildString {
    append(prelude())
    append(stopOld())
    appendLine("true")
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
    var exited = false
    var timedOut = false
    val log = ArrayList<String>()
    for ((key, value) in tagged(result.stdout)) {
      when (key) {
        "home" -> home = value.ifBlank { null }
        "dsh" -> dshPath = value.trim().ifBlank { null }
        "started" -> pid = value.trim().toIntOrNull()
        "url" -> url = value.trim()
        "nodsh" -> noDsh = true
        "exited" -> exited = true
        "timeout" -> timedOut = true
        "log" -> log += value
      }
    }
    val parsed = url?.let(::parseLaunchLine)
    if (parsed != null) return RemoteDshEndpoint(parsed.port, parsed.token, pid, home = home, dshPath = dshPath)
    val tail = SshShell.redactTokens(log.filter(String::isNotBlank).joinToString("\n")).trim()
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

  private companion object {
    fun parseLaunchLine(line: String): RemoteDshEndpoint? {
      val match = Regex("https?://[^\\s]*[?&]token=([^\\s&]+)").find(line) ?: return null
      val token = match.groupValues[1]
      val urlText = match.value.substringBefore("?token=").substringBefore("&token=")
      return try {
        val uri = URI(urlText)
        val port = if (uri.port > 0) uri.port else return null
        RemoteDshEndpoint(port, token)
      } catch (_: Exception) {
        null
      }
    }
  }
}
