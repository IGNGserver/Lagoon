package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

/** `opencode acp` is an app-owned fixed command; discovery only reads help/version output. */
class SshOpenCodeAcpProbeTest {

  @Test fun supportsTheFrontierAndOwnedVersionsThroughAHelpProbe() {
    val supported = probe("LAGOON_OPENCODE_ACP version:2.0.18\n").discover()
    assertEquals(true, supported.supported)
    assertEquals("2.0.18", supported.version)
    assertNull(supported.issue)

    val absent = probe("LAGOON_OPENCODE_ACP absent\n").discover()
    assertEquals(false, absent.supported)

    val noAcp = probe("LAGOON_OPENCODE_ACP no-acp\n").discover()
    assertEquals(false, noAcp.supported)
    assertEquals("acp_unavailable", noAcp.issue)

    val failed = probe("LAGOON_OPENCODE_ACP version:2.0.18\n", exit = 1).discover()
    assertNull(failed.supported)
    assertEquals("probe_failed", failed.issue)
  }

  @Test fun scriptMatchesTheProtocolNameAndNeverStartsTheServer() {
    val script = SshOpenCodeAcpProbe.SCRIPT
    assertTrue(script.contains("opencode acp --help"))
    assertTrue(script.contains("grep -qi 'agent client protocol'"))
    // Discovery must not start a long-lived ACP process (no stdin, no bare `opencode acp`).
    assertFalse(script.contains("opencode acp\n"))
    assertFalse(script.contains("exec opencode acp"))
    assertTrue(script.contains(".opencode/bin"))
  }

  @Test fun transportRejectsACommandThatCouldEscapeTheFixedLiteral() {
    val config = SshHostConfig("host", "host", "127.0.0.1", 22, "user", auth = SshAuthMethod.PASSWORD)
    assertThrows(IllegalArgumentException::class.java) {
      SshAgyTransport(config, SshSecrets(password = "x"), JschSshTransportFactory(), command = "opencode acp; rm -rf /")
    }
    // The exact literals the app uses are accepted without touching the network.
    SshAgyTransport(config, SshSecrets(password = "x"), JschSshTransportFactory(), command = "opencode acp",
      label = "OpenCode ACP", catalog = false).close()
  }

  private fun probe(output: String, exit: Int = 0) = SshOpenCodeAcpProbe(object : SshTransport {
    override val isClosed = false
    override fun exec(script: String, timeoutMillis: Long) = RemoteExecResult(exit, output, "")
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection =
      throw UnsupportedOperationException()
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = false
    override fun ping(timeoutMillis: Long) = false
    override fun close() = Unit
  })
}
