package com.igng.opencode.lagoon.core.ssh

import java.util.concurrent.CancellationException

/** Installation evidence only: authentication and actual App Server readiness remain unknown. */
internal data class SshCodexObservation(
  val installed: Boolean?,
  val version: String? = null,
  val issue: String? = null
)

internal class SshCodexProbe(private val transport: SshTransport) {
  fun discover(): SshCodexObservation {
    val result = try { transport.exec(SCRIPT, 20_000) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { return SshCodexObservation(null, issue = "probe_failed") }
    if (result.exitCode != 0 || result.stdout.length > 64 * 1024)
      return SshCodexObservation(null, issue = "probe_failed")
    val lines = result.stdout.lineSequence().filter { it.startsWith(PREFIX) }.toList()
    if (lines.size != 1) return SshCodexObservation(null, issue = "invalid_output")
    val value = lines.single().removePrefix(PREFIX)
    if (value == "absent") return SshCodexObservation(false)
    if (value == "version-unavailable") return SshCodexObservation(true, issue = "version_unavailable")
    val version = value.removePrefix("version:").takeIf {
      value.startsWith("version:") && it.length in 1..128 && it.all { char -> char.code in 32..126 }
    } ?: return SshCodexObservation(null, issue = "invalid_output")
    return SshCodexObservation(true, version)
  }

  companion object {
    private const val PREFIX = "LAGOON_CODEX_DISCOVERY "
    internal const val SCRIPT = """
      if ! command -v codex >/dev/null 2>&1; then
        printf '%s\n' 'LAGOON_CODEX_DISCOVERY absent'
      else
        version=$(codex --version 2>/dev/null | head -n 1)
        if [ -z "${'$'}version" ]; then
          printf '%s\n' 'LAGOON_CODEX_DISCOVERY version-unavailable'
        else
          printf 'LAGOON_CODEX_DISCOVERY version:%s\n' "${'$'}version"
        fi
      fi
    """
  }
}
