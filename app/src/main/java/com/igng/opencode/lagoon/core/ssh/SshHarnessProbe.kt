package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.CancellationException

/** Read-only, bounded file discovery. No bootstrap, process launch/stop, HTTP request or tunnel. */
internal class SshHarnessProbe(
  private val transport: SshTransport,
  private val nowMillis: () -> Long = System::currentTimeMillis
) {
  /** Integration convenience; use [discover] to distinguish absent/corrupt/partial observations. */
  fun openCodeRegistration(path: String? = null): OpenCodeSshRegistration {
    val observation = discover(path).openCode
    return observation.registration ?: throw SshFailure(
      SshFailureKind.OTHER, "OpenCode registration unavailable (${observation.issue.name.lowercase()})",
      retryable = observation.state == SshDiscoveryState.UNKNOWN
    )
  }

  fun discover(path: String? = null): SshHarnessDiscoveryResult {
    val command = probeScript(path)
    val now = nowMillis()
    val result = try {
      transport.exec(command, 20_000)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (_: Exception) {
      // Remote stderr and exception messages may contain registration contents: never retain them.
      return unknown(now, SshDiscoveryIssue.EXEC_FAILED)
    }
    if (result.exitCode != 0) return unknown(now, SshDiscoveryIssue.EXEC_FAILED)
    return parseOutput(result.stdout, now)
  }

  companion object {
    private const val PREFIX = "LAGOON_SSH_DISCOVERY "
    private const val MAX_ENVELOPE_CHARS = 64 * 1024 // safely below exec's existing 512 KiB cap

    /** Default official path is XDG_STATE_HOME/opencode/service.json, otherwise HOME/.local/state. */
    fun probeScript(path: String? = null): String {
      require(path == null || validSshDiscoveryPath(path)) { "Registration path must be absolute and control-free" }
      return """
        if ! command -v python3 >/dev/null 2>&1; then
          printf '%s\n' 'LAGOON_SSH_DISCOVERY {"python":false}'
          exit 0
        fi
        python3 - ${SshShell.quote(path.orEmpty())} <<'LAGOON_SSH_DISCOVERY_PY'
        import base64, json, os, stat, sys
        home = os.environ.get('HOME', '')
        state = os.environ.get('XDG_STATE_HOME') or (os.path.join(home, '.local', 'state') if home else '')
        path = sys.argv[1] or (os.path.join(state, 'opencode', 'service.json') if state else '')
        marker = os.path.join(home, '.dsh-app-remote') if home else ''
        def read_registration(path):
            if not os.path.isabs(path): return {'state':'unknown','issue':'invalid_path'}
            fd = None
            try:
                # A registration may contain credentials. Never follow a final symlink or accept
                # records another user can replace. Retain unknown when ownership is uncertain.
                if not hasattr(os, 'O_NOFOLLOW'): return {'state':'unknown','issue':'unreadable'}
                fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | getattr(os, 'O_NONBLOCK', 0))
                info = os.fstat(fd)
                if not stat.S_ISREG(info.st_mode): return {'state':'corrupt','issue':'invalid_data'}
                if info.st_uid != os.geteuid() or info.st_mode & 0o022: return {'state':'unknown','issue':'unreadable'}
                with os.fdopen(fd, 'rb') as stream:
                    fd = None
                    data = stream.read(${OpenCodeSshRegistration.MAX_BYTES + 1})
                if len(data) > ${OpenCodeSshRegistration.MAX_BYTES}: return {'state':'corrupt','issue':'too_large'}
                return {'state':'present','rawBase64':base64.b64encode(data).decode('ascii')}
            except FileNotFoundError: return {'state':'absent','issue':'not_found'}
            except OSError: return {'state':'unknown','issue':'unreadable'}
            finally:
                if fd is not None: os.close(fd)
        def read_marker(path):
            if not os.path.isabs(path): return {'state':'unknown','issue':'invalid_path'}
            try:
                info = os.lstat(path)
                if stat.S_ISLNK(info.st_mode): return {'state':'unknown','issue':'unreadable'}
                if not stat.S_ISDIR(info.st_mode): return {'state':'corrupt','issue':'invalid_data'}
                if info.st_uid != os.geteuid() or info.st_mode & 0o022: return {'state':'unknown','issue':'unreadable'}
                return {'state':'present'}
            except FileNotFoundError: return {'state':'absent','issue':'not_found'}
            except OSError: return {'state':'unknown','issue':'unreadable'}
        registration = read_registration(path)
        registration['path'] = path
        legacy = read_marker(marker)
        legacy['path'] = marker
        print('LAGOON_SSH_DISCOVERY ' + json.dumps({'python':True,'opencode':registration,'dshLegacy':legacy}, separators=(',',':'), ensure_ascii=True))
        LAGOON_SSH_DISCOVERY_PY
      """.trimIndent()
    }

    /** Parse only the tagged envelope; shell banners are tolerated, partial/truncated JSON is not. */
    fun parseOutput(stdout: String, observedAtMs: Long): SshHarnessDiscoveryResult {
      if (stdout.length > MAX_ENVELOPE_CHARS) return unknown(observedAtMs, SshDiscoveryIssue.INVALID_OUTPUT)
      return try {
        val lines = stdout.lineSequence().filter { it.startsWith(PREFIX) }.toList()
        require(lines.size == 1)
        val tokener = JSONTokener(lines.single().removePrefix(PREFIX))
        val root = tokener.nextValue() as? JSONObject ?: error("envelope")
        require(tokener.nextClean() == '\u0000')
        val python = root.opt("python") as? Boolean ?: error("python")
        if (!python) return unknown(observedAtMs, SshDiscoveryIssue.PYTHON_UNAVAILABLE)
        val raw = root.getJSONObject("opencode")
        val path = (raw.opt("path") as? String)?.takeIf(::validSshDiscoveryPath)
        val ocState = state(raw)
        val registration = if (ocState == SshDiscoveryState.PRESENT) runCatching {
          require(path != null)
          val encoded = raw.get("rawBase64") as? String ?: error("bytes")
          require(encoded.length <= (OpenCodeSshRegistration.MAX_BYTES + 2) / 3 * 4)
          val bytes = Base64.getDecoder().decode(encoded)
          require(bytes.size <= OpenCodeSshRegistration.MAX_BYTES)
          val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
          OpenCodeSshRegistration.parse(path, text)
        }.getOrNull() else null
        val openCode = SshOpenCodeObservation(
          if (ocState == SshDiscoveryState.PRESENT && registration == null) SshDiscoveryState.CORRUPT else ocState,
          registration, path, if (ocState == SshDiscoveryState.PRESENT && registration == null) SshDiscoveryIssue.INVALID_DATA else issue(raw)
        )
        val legacy = root.getJSONObject("dshLegacy")
        val legacyState = state(legacy)
        val marker = (legacy.opt("path") as? String)?.takeIf(::validSshDiscoveryPath)
        require(legacyState != SshDiscoveryState.PRESENT || marker != null)
        val dsh = SshDshLegacyObservation(legacyState,
          if (legacyState == SshDiscoveryState.PRESENT) DshLegacySshDescriptor(marker!!) else null, issue(legacy))
        SshHarnessDiscoveryResult(openCode, dsh, observedAtMs,
          if (openCode.state == SshDiscoveryState.UNKNOWN || dsh.state == SshDiscoveryState.UNKNOWN)
            DiscoveryCompleteness.PARTIAL else DiscoveryCompleteness.COMPLETE)
      } catch (_: Exception) {
        unknown(observedAtMs, SshDiscoveryIssue.INVALID_OUTPUT)
      }
    }

    private fun state(value: JSONObject): SshDiscoveryState = when (value.opt("state")) {
      "present" -> SshDiscoveryState.PRESENT
      "absent" -> SshDiscoveryState.ABSENT
      "corrupt" -> SshDiscoveryState.CORRUPT
      "unknown" -> SshDiscoveryState.UNKNOWN
      else -> error("state")
    }
    private fun issue(value: JSONObject): SshDiscoveryIssue = when (value.opt("issue")) {
      null -> SshDiscoveryIssue.NONE
      "not_found" -> SshDiscoveryIssue.NOT_FOUND
      "unreadable" -> SshDiscoveryIssue.UNREADABLE
      "too_large" -> SshDiscoveryIssue.TOO_LARGE
      "invalid_data", "invalid_path" -> SshDiscoveryIssue.INVALID_DATA
      else -> SshDiscoveryIssue.INVALID_OUTPUT
    }
    internal fun unknown(now: Long, issue: SshDiscoveryIssue) = SshHarnessDiscoveryResult(
      SshOpenCodeObservation(SshDiscoveryState.UNKNOWN, issue = issue),
      SshDshLegacyObservation(SshDiscoveryState.UNKNOWN, issue = issue), now, DiscoveryCompleteness.PARTIAL
    )
  }
}

/**
 * Discovery and startup must see exactly the same executable search order. Keep the login-shell
 * PATH first, append common per-user installations, and never source shell profiles here.
 * Globs and every directory assignment are quoted so spaces in HOME and installed paths survive.
 */
internal object SshExecutableSearch {
  val prologue: String = """
    if [ -n "§{HOME:-}" ]; then
      for __lagoon_dir in "§HOME/.local/bin" "§HOME/.npm-global/bin" "§HOME/.npm/bin" \
        "§HOME/.volta/bin" "§HOME/.bun/bin" "§HOME/.local/share/pnpm" "§HOME/Library/pnpm" \
        "§HOME"/.nvm/versions/node/*/bin "§HOME"/.local/share/fnm/node-versions/*/installation/bin; do
        [ -d "§__lagoon_dir" ] || continue
        case ":§{PATH:-}:" in *":§__lagoon_dir:"*) ;; *) PATH="§{PATH:+§PATH:}§__lagoon_dir" ;; esac
      done
    fi
    for __lagoon_dir in /usr/local/bin /opt/homebrew/bin; do
      [ -d "§__lagoon_dir" ] || continue
      case ":§{PATH:-}:" in *":§__lagoon_dir:"*) ;; *) PATH="§{PATH:+§PATH:}§__lagoon_dir" ;; esac
    done
    export PATH
    unset __lagoon_dir
  """.trimIndent().replace('§', '$')
}

/** DSH executable presence is distinct from a running, reusable or authenticated service. */
internal data class SshDshObservation(
  val installed: Boolean?,
  val issue: String? = null,
  val customCommand: Boolean = false
)

internal class SshDshProbe(private val transport: SshTransport) {
  fun discover(customCommand: Boolean): SshDshObservation {
    val result = try { transport.exec(SCRIPT, 20_000) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { return SshDshObservation(null, "probe_failed", customCommand) }
    if (result.exitCode != 0 || result.stdout.length > 64 * 1024)
      return SshDshObservation(null, "probe_failed", customCommand)
    val lines = result.stdout.lineSequence().filter { it.startsWith(PREFIX) }.toList()
    if (lines.size != 1) return SshDshObservation(null, "invalid_output", customCommand)
    val found = when (lines.single().removePrefix(PREFIX)) {
      "present" -> true
      "absent" -> false
      else -> return SshDshObservation(null, "invalid_output", customCommand)
    }
    // Presence of a globally installed dsh is evidence even with a custom wrapper; absence of
    // one says nothing about that wrapper, and discovery never executes arbitrary user commands.
    return if (customCommand) SshDshObservation(if (found) true else null, "custom_command_unverified", true)
      else SshDshObservation(found)
  }

  companion object {
    private const val PREFIX = "LAGOON_DSH_DISCOVERY "
    internal val SCRIPT = SshExecutableSearch.prologue + "\n" + """
      if command -v dsh >/dev/null 2>&1; then
        printf '%s\n' 'LAGOON_DSH_DISCOVERY present'
      else
        printf '%s\n' 'LAGOON_DSH_DISCOVERY absent'
      fi
    """.trimIndent()
  }
}
