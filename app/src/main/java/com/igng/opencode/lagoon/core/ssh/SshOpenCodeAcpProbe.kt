package com.igng.opencode.lagoon.core.ssh

import java.util.concurrent.CancellationException

/** Evidence that the installed `opencode` can serve ACP; authentication is only known after attach. */
internal data class SshOpenCodeAcpObservation(
  val supported: Boolean?,
  val version: String? = null,
  val issue: String? = null
)

/**
 * `opencode acp` exists on 1.x and 2.x and speaks JSON-RPC over stdio, so it needs no background
 * service, registration file or loopback proof. This probe only reads version/help output.
 */
internal class SshOpenCodeAcpProbe(private val transport: SshTransport) {
  fun discover(): SshOpenCodeAcpObservation {
    val result = try {
      transport.exec(SCRIPT, 20_000)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (_: Exception) {
      return SshOpenCodeAcpObservation(null, issue = "probe_failed")
    }
    if (result.exitCode != 0 || result.stdout.length > 64 * 1024)
      return SshOpenCodeAcpObservation(null, issue = "probe_failed")
    val lines = result.stdout.lineSequence().filter { it.startsWith(PREFIX) }.toList()
    if (lines.size != 1) return SshOpenCodeAcpObservation(null, issue = "invalid_output")
    val value = lines.single().removePrefix(PREFIX)
    if (value == "absent") return SshOpenCodeAcpObservation(false)
    if (value == "no-acp") return SshOpenCodeAcpObservation(false, issue = "acp_unavailable")
    val version = value.removePrefix("version:").takeIf {
      value.startsWith("version:") && it.length in 1..128 && it.all { char -> char.code in 32..126 }
    } ?: return SshOpenCodeAcpObservation(null, issue = "invalid_output")
    return SshOpenCodeAcpObservation(true, version)
  }

  companion object {
    private const val PREFIX = "LAGOON_OPENCODE_ACP "
    internal val SCRIPT = SshExecutableSearch.prologue + "\n" + """
      if ! command -v opencode >/dev/null 2>&1; then
        printf '%s\n' 'LAGOON_OPENCODE_ACP absent'
      elif ! __lagoon_help=$(opencode acp --help 2>&1); then
        printf '%s\n' 'LAGOON_OPENCODE_ACP no-acp'
      elif ! printf '%s' "${'$'}__lagoon_help" | grep -qi 'agent client protocol'; then
        printf '%s\n' 'LAGOON_OPENCODE_ACP no-acp'
      else
        __lagoon_version=$(opencode --version 2>/dev/null | head -n 1)
        printf 'LAGOON_OPENCODE_ACP version:%s\n' "${'$'}__lagoon_version"
      fi
    """.trimIndent()
  }
}
