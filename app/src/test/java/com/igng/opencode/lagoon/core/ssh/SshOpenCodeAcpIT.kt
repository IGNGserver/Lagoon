package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.AgyApi
import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in real-sshd coverage for the ACP route. The fixture is a caller-owned loopback sshd whose
 * HOME may or may not be authenticated: this test only proves the transport initializes, lists
 * sessions and replays a transcript; it never sends a prompt and never changes an existing session.
 *
 * Fixture: `LAGOON_OPENCODE_ACP_SSH_FIXTURE` points at a directory containing `port`, `client`
 * (private key) and `host.pub`, laid out like `SshAgyOfficialSmokeTest`.
 */
class SshOpenCodeAcpIT {
  @Test fun realAcpOverPinnedSshInitializesAndReads(): Unit = runBlocking {
    val fixtureEnv = System.getenv("LAGOON_OPENCODE_ACP_SSH_FIXTURE")
    assumeTrue("Set LAGOON_OPENCODE_ACP_SSH_FIXTURE to a caller-owned scratch SSH fixture", !fixtureEnv.isNullOrBlank())
    val scratch = File(System.getenv("BUILD_SCRATCH_ROOT") ?: File(System.getProperty("user.home"), ".build").path).canonicalFile
    val fixture = File(fixtureEnv!!).canonicalFile
    require(fixture.toPath().startsWith(scratch.toPath()) && fixture != scratch)
    val port = File(fixture, "port").readText().trim().toInt()
    val key = File(fixture, "client").readText()
    val publicHost = File(fixture, "host.pub").readText().trim().split(Regex("\\s+"))
    require(publicHost.firstOrNull() == "ssh-ed25519")
    val hostKey = Base64.getDecoder().decode(publicHost[1])
    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
      .encodeToString(MessageDigest.getInstance("SHA-256").digest(hostKey))
    val config = SshHostConfig(id = "opencode-acp-live", name = "OpenCode ACP smoke", host = "127.0.0.1", port = port,
      user = System.getProperty("user.name") ?: error("Current user unavailable"), auth = SshAuthMethod.PRIVATE_KEY,
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = fingerprint)
    val transport = SshAgyTransport(config, SshSecrets(privateKey = key), JschSshTransportFactory(),
      command = "opencode acp", label = "OpenCode ACP", catalog = false)
    val api = AgyApi(transport, "opencode", "OpenCode ACP", "opencode")
    try {
      assertEquals("OpenCode ACP", withTimeout(60_000) { api.health() })
      val page = withTimeout(60_000) { api.rootSessionsPage() }
      assertTrue("Sessions must be root sessions", page.items.all { it.parentId == null })
      page.items.firstOrNull()?.let { session ->
        val history = withTimeout(60_000) { api.messagesPage(session.id, null, 20) }
        assertTrue("Replayed history must not invent empty messages", history.items.all { it.id.isNotBlank() })
      }
    } finally {
      runCatching { api.close() }
      runCatching { transport.close() }
    }
  }
}
