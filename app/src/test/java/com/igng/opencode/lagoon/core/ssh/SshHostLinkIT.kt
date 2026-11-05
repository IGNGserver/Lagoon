package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Opt-in full SSH → bootstrap → local forward → DSH login check. Requires a POSIX host with
 * `dsh` installed (user-local npm prefix is found by the bootstrap):
 *
 *   LAGOON_SSH_IT_HOST=... LAGOON_SSH_IT_USER=... LAGOON_SSH_IT_KEY=... \
 *   ./gradlew :app:testDebugUnitTest --tests '*SshHostLinkIT'
 */
class SshHostLinkIT {
  @Test fun startsDshAndReturnsAnAuthenticatedClient() = runBlocking {
    val host = System.getenv("LAGOON_SSH_IT_HOST")
    val user = System.getenv("LAGOON_SSH_IT_USER")
    val keyPath = System.getenv("LAGOON_SSH_IT_KEY")
    assumeTrue(host != null && user != null && keyPath != null)
    val key = String(Files.readAllBytes(File(keyPath!!).toPath()))
    val factory = JschSshTransportFactory()
    val base = SshHostConfig(
      id = "ssh-it-link", name = "it", host = host!!, user = user!!, auth = SshAuthMethod.PRIVATE_KEY,
      launchCommand = "dsh web"
    )
    val presented = factory.probeHostKey(base)
    val config = base.copyWith(hostKeyType = presented.type, hostKeyFingerprint = presented.fingerprint)
    val profile = ServerProfile(
      id = config.id, name = config.name, url = config.profileUrl(), username = config.user,
      kind = ServerKind.DSH, sshHostId = config.id
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val link = SshHostLink(scope, profile, config, SshSecrets(privateKey = key), factory,
      onStatus = { println("SSHIT ${it.phase}/${it.step} ${it.detail} err=${it.error?.message} detail=${it.error?.detail}") })
    try {
      val client = withTimeout(30_000) { link.awaitClient(25_000) }
      assertTrue(client.health().isNotBlank())
      client.rootSessionsPage()
      // Start the multiplexed WebSocket through the tunnel; a Host-header mismatch would fail the
      // handshake. No event is required within the window.
      withTimeoutOrNull(5_000) { client.events().first() }
      Unit
    } finally {
      link.close()
      scope.cancel()
    }
  }

  @Test fun restartsRemoteDshAfterItDies() = runBlocking {
    val host = System.getenv("LAGOON_SSH_IT_HOST")
    val user = System.getenv("LAGOON_SSH_IT_USER")
    val keyPath = System.getenv("LAGOON_SSH_IT_KEY")
    assumeTrue(host != null && user != null && keyPath != null)
    val key = String(Files.readAllBytes(File(keyPath!!).toPath()))
    val factory = JschSshTransportFactory()
    val base = SshHostConfig(id = "ssh-it-restart", name = "it", host = host!!, user = user!!,
      auth = SshAuthMethod.PRIVATE_KEY, launchCommand = "dsh web")
    val presented = factory.probeHostKey(base)
    val config = base.copyWith(hostKeyType = presented.type, hostKeyFingerprint = presented.fingerprint)
    val profile = ServerProfile(id = config.id, name = config.name, url = config.profileUrl(), username = config.user,
      kind = ServerKind.DSH, sshHostId = config.id)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val link = SshHostLink(scope, profile, config, SshSecrets(privateKey = key), factory,
      onStatus = { println("SSHIT2 ${it.phase}/${it.step} ${it.detail} err=${it.error?.message}") })
    try {
      val first = withTimeout(90_000) { link.awaitClient(80_000) }
      val admin = factory.connect(config, SshSecrets(privateKey = key)) { it.fingerprint == presented.fingerprint }
      try {
        admin.exec("kill \$(cat \$HOME/.dsh-app-remote/web.pid) 2>/dev/null || true", 10_000)
      } finally { admin.close() }
      // The link probes dsh's loopback port on its 15s keepalive tick; wait for one tick before asking
      // for the next generation.
      kotlinx.coroutines.delay(35_000)
      val second = withTimeout(90_000) { link.awaitClient(80_000) }
      assertTrue("a restarted remote dsh must produce a new client generation", first !== second)
      assertTrue(second.health().isNotBlank())
    } finally {
      link.close()
      scope.cancel()
    }
  }

  @Test fun rejectsUnknownAndChangedHostKeys() = runBlocking {
    val host = System.getenv("LAGOON_SSH_IT_HOST")
    val user = System.getenv("LAGOON_SSH_IT_USER")
    val keyPath = System.getenv("LAGOON_SSH_IT_KEY")
    assumeTrue(host != null && user != null && keyPath != null)
    val key = String(Files.readAllBytes(File(keyPath!!).toPath()))
    val factory = JschSshTransportFactory()
    val base = SshHostConfig(id = "ssh-it-key", name = "it", host = host!!, user = user!!, auth = SshAuthMethod.PRIVATE_KEY)
    val profile = ServerProfile(id = base.id, name = base.name, url = base.profileUrl(), username = base.user,
      kind = ServerKind.DSH, sshHostId = base.id)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val unpinned = SshHostLink(scope, profile, base, SshSecrets(privateKey = key), factory)
    try {
      val failure = runCatching { withTimeout(40_000) { unpinned.awaitClient(30_000) } }.exceptionOrNull()
      assertTrue(failure is com.igng.opencode.lagoon.core.SshFailure)
      assertEquals(com.igng.opencode.lagoon.core.SshFailureKind.HOST_KEY_UNKNOWN,
        (failure as com.igng.opencode.lagoon.core.SshFailure).kind)
      assertTrue(failure.presentedKey != null)
    } finally { unpinned.close() }

    val presented = factory.probeHostKey(base)
    val changed = base.copyWith(hostKeyType = presented.type, hostKeyFingerprint = "SHA256:not-the-key")
    val mismatch = SshHostLink(scope, profile, changed, SshSecrets(privateKey = key), factory)
    try {
      val failure = runCatching { withTimeout(40_000) { mismatch.awaitClient(30_000) } }.exceptionOrNull()
      assertEquals(com.igng.opencode.lagoon.core.SshFailureKind.HOST_KEY_CHANGED,
        (failure as com.igng.opencode.lagoon.core.SshFailure).kind)
    } finally {
      mismatch.close()
      scope.cancel()
    }
  }
}
