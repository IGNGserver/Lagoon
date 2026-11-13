package com.igng.opencode.lagoon.core.ssh

import java.util.concurrent.CancellationException

/** Installation evidence only: authentication and actual App Server readiness remain unknown. */
internal data class SshAgyObservation(
  val installed: Boolean?,
  val version: String? = null,
  val compatible: Boolean? = null,
  val issue: String? = null
)

internal class SshAgyProbe(private val transport: SshTransport) {
  fun discover(): SshAgyObservation {
    val result = try { transport.exec(SCRIPT, 20_000) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { return SshAgyObservation(null, issue = "probe_failed") }
    if (result.exitCode != 0 || result.stdout.length > 64 * 1024)
      return SshAgyObservation(null, issue = "probe_failed")
    val lines = result.stdout.lineSequence().filter { it.startsWith(PREFIX) }.toList()
    if (lines.size != 1) return SshAgyObservation(null, issue = "invalid_output")
    val value = lines.single().removePrefix(PREFIX)
    if (value == "absent") return SshAgyObservation(false)
    if (value == "version-unavailable") return SshAgyObservation(true, issue = "version_unavailable")
    if (value == "no-acp") return SshAgyObservation(true, compatible = false, issue = "acp_unavailable")
    val version = value.removePrefix("version:").takeIf {
      value.startsWith("version:") && it.length in 1..128 && it.all { char -> char.code in 32..126 }
    } ?: return SshAgyObservation(null, issue = "invalid_output")
    return SshAgyObservation(true, version, compatible = true)
  }

  companion object {
    private const val PREFIX = "LAGOON_AGY_DISCOVERY "
    internal const val SCRIPT = """
      if ! command -v agy >/dev/null 2>&1; then
        printf '%s\n' 'LAGOON_AGY_DISCOVERY absent'
      elif ! agy acp --help >/dev/null 2>&1; then
        printf '%s\n' 'LAGOON_AGY_DISCOVERY no-acp'
      else
        version=$(agy --version 2>/dev/null | head -n 1)
        if [ -z "${'$'}version" ]; then
          printf '%s\n' 'LAGOON_AGY_DISCOVERY version-unavailable'
        else
          printf 'LAGOON_AGY_DISCOVERY version:%s\n' "${'$'}version"
        fi
      fi
    """
  }
}
