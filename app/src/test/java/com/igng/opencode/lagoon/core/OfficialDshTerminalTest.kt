package com.igng.opencode.lagoon.core

import java.io.File
import java.net.URI
import com.igng.opencode.lagoon.core.ssh.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in: one fresh scratch WebHost, no prompt/model, test-created terminal only. */
class OfficialDshTerminalTest {
  @Test fun officialSessionTerminalAcknowledgesInputAndTransfersControlWithoutReplay() = runBlocking {
    val path = System.getenv("LAGOON_OFFICIAL_DSH_TERMINAL_CONNECTION")
    assumeTrue("Set LAGOON_OFFICIAL_DSH_TERMINAL_CONNECTION for isolated official rc.5 acceptance", path != null)
    val file = File(requireNotNull(path)).canonicalFile
    check(file.isFile && file.length() in 1..16_384)
    val connection = JSONObject(file.readText())
    val url = connection.getString("url"); val uri = URI(url)
    check(uri.host == "127.0.0.1" && uri.scheme == "http" && uri.port > 0 && uri.port != 3080)
    val directory = File(connection.getString("directory")).canonicalFile
    check(directory.isDirectory && directory.toPath().startsWith(file.parentFile.toPath()))
    exercise(url, connection.getString("token"), directory)
  }

  @Test fun pinnedRealSshCarriesTheSameOfficialSessionTerminalControlAndAckContract() = runBlocking {
    val path = System.getenv("LAGOON_OFFICIAL_DSH_TERMINAL_CONNECTION")
    val sshPath = System.getenv("LAGOON_OFFICIAL_DSH_TERMINAL_SSH")
    assumeTrue("Set both isolated official DSH terminal and pinned SSH connection files", path != null && sshPath != null)
    val file = File(requireNotNull(path)).canonicalFile
    val connection = JSONObject(file.readText())
    val uri = URI(connection.getString("url"))
    check(uri.host == "127.0.0.1" && uri.port > 0 && uri.port != 3080)
    val directory = File(connection.getString("directory")).canonicalFile
    check(directory.isDirectory && directory.toPath().startsWith(file.parentFile.toPath()))
    val input = JSONObject(File(requireNotNull(sshPath)).readText())
    check(input.getString("host") == "127.0.0.1" && input.getInt("port") !in setOf(22, 3080))
    val key = File(input.getString("privateKeyPath")).canonicalFile
    check(key.toPath().startsWith(file.parentFile.parentFile.toPath()) && key.length() in 1..65_536)
    val config = SshHostConfig("official-dsh-terminal-ssh", "Isolated SSH", "127.0.0.1", input.getInt("port"), input.getString("user"),
      auth = SshAuthMethod.PRIVATE_KEY, hostKeyType = input.getString("hostKeyType"), hostKeyFingerprint = input.getString("fingerprint"))
    val transport = withContext(Dispatchers.IO) { JschSshTransportFactory().connect(config, SshSecrets(privateKey = key.readText())) {
      it.type == config.hostKeyType && it.fingerprint == config.hostKeyFingerprint
    } }
    try {
      SshForwarder { source, sink -> transport.openTunnel("127.0.0.1", uri.port, source, sink) }.use { forwarder ->
        exercise("http://127.0.0.1:${forwarder.port}", connection.getString("token"), directory)
      }
    } finally { transport.close() }
  }

  private suspend fun exercise(url: String, token: String, directory: File) {
    val api = DshApi(ServerProfile("official-dsh-terminal", "Isolated DSH terminal", url,
      kind = ServerKind.DSH, allowCleartext = true), ServerCredentials("dsh", token))
    var owner: TerminalScope.Session? = null
    var terminal: NativeTerminal? = null
    var first: TerminalAttachment? = null
    var second: TerminalAttachment? = null
    try {
      assertTrue(api.capabilities().terminal)
      val session = withTimeout(20_000) { api.createSession(directory.path, "", null) }
      owner = TerminalScope.Session(session.id, directory.path)
      assertTrue(api.terminals(owner).isEmpty())
      terminal = withTimeout(20_000) { api.createTerminal(owner) }
      assertEquals(session.id, terminal.sessionId)
      assertFalse(terminal.pidKnown)
      first = withTimeout(20_000) { api.attachTerminal(owner, terminal.id) }
      assertTrue(first.state.value.ready && first.state.value.writable)
      assertTrue(first.sendText("printf 'LAGOON_%s\\n' DSH_TERMINAL_ACCEPTANCE\r"))
      withTimeout(15_000) { first.state.first { it.acknowledgedSequence == 1L && it.output.contains("LAGOON_DSH_TERMINAL_ACCEPTANCE") } }
      assertEquals(0, first.state.value.pendingInputs)
      assertTrue(first.resize(90, 30))
      withTimeout(15_000) { first.state.first { it.acknowledgedSequence == 2L } }
      second = withTimeout(20_000) { api.attachTerminal(owner, terminal.id) }
      assertTrue(second.state.value.writable)
      withTimeout(10_000) { first.state.first { !it.writable } }
      assertFalse(first.sendText("must-not-send\r"))
      second.close(); second = null
      assertFalse("Detaching newest attachment must not restore the old controller", first.state.value.writable)
      first.close(); first = null
      assertEquals("running", api.terminals(owner).single { it.id == terminal.id }.status)
      withTimeout(15_000) { api.closeTerminal(owner, terminal.id) }
      assertTrue(api.terminals(owner).none { it.id == terminal.id })
      terminal = null
    } finally {
      second?.close(); first?.close()
      try { if (terminal != null && owner != null) withTimeout(10_000) { api.closeTerminal(owner, terminal.id) } }
      finally { api.close(); withTimeout(10_000) { api.awaitClosed() } }
    }
  }
}
