package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, read-only contract against caller-owned loopback sshd and official OpenCode 2.2.6.
 * LAGOON_OFFICIAL_SSH_CONNECTION names an absolute connection.json in the common scratch root.
 * Private key and registration must be beneath that root; no service/process is started or stopped. */
class OfficialSshServiceTest {
  @Test fun pinnedSshSharesOneConnectionAndKeepsSiblingOpenCodeAlive() = runBlocking {
    val path = System.getenv("LAGOON_OFFICIAL_SSH_CONNECTION")
    assumeTrue("Set LAGOON_OFFICIAL_SSH_CONNECTION to enable isolated SSH coverage", path != null)
    var stage = "configuration"
    try {
      val connection = checkedFile(requireNotNull(path))
      val scratch = checkNotNull(connection.canonicalFile.parentFile)
      check(scratch.parentFile != null)
      val input = JSONObject(boundedText(connection, 16 * 1024))
      check(input.getString("host") == "127.0.0.1")
      val port = input.get("port") as? Int ?: error("port")
      check(port in 1..65535 && port !in setOf(22, 3080))
      val user = input.getString("user")
      check(user.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")))
      check(input.getString("hostKeyType") == "ssh-ed25519")
      val fingerprint = input.getString("fingerprint")
      check(fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
      val key = checkedFile(input.getString("privateKeyPath"), scratch)
      val registrationFile = checkedFile(input.getString("registrationPath"), scratch)
      val registrationPath = registrationFile.canonicalPath
      val registration = checkNotNull(OpenCodeSshRegistration.parse(registrationPath,
        boundedText(registrationFile, OpenCodeSshRegistration.MAX_BYTES)))
      check(registration.host == "127.0.0.1" && registration.port !in setOf(22, 3080))
      check(registration.version == "2.2.6" && !registration.password.isNullOrBlank())
      val config = SshHostConfig("official-ssh", "Isolated SSH", "127.0.0.1", port, user,
        auth = SshAuthMethod.PRIVATE_KEY, hostKeyType = "ssh-ed25519", hostKeyFingerprint = fingerprint)
      val secrets = SshSecrets(privateKey = boundedText(key, 64 * 1024))
      val native = JschSshTransportFactory()
      val connects = AtomicInteger()
      val counted = object : SshTransportFactory by native {
        override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
          connects.incrementAndGet()
          val transport = native.connect(config, secrets, verifier)
          return object : SshTransport by transport {
            override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
              check(script == SshHarnessProbe.probeScript(registrationPath))
              return transport.exec(script, timeoutMillis)
            }
            override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection {
              check(host == registration.host && port == registration.port)
              return transport.openTunnel(host, port, input, output)
            }
          }
        }
      }
      val pool = SshTransportPool(counted)
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      val lastStep = java.util.concurrent.atomic.AtomicReference("not started")
      val links = (1..2).map { index ->
        val profile = ServerProfile("official-ssh-$index", "Official SSH $index", config.profileUrl(),
          kind = ServerKind.OPENCODE, sshHostId = config.id, sshServicePath = registrationPath)
        SshHostLink(scope, profile, config, secrets, pool) { status ->
          lastStep.set("${status.phase.name}/${status.step.name}/${status.error?.kind?.name ?: "ok"}")
        }
      }
      val clients = mutableListOf<HarnessClient>()
      try {
        withTimeout(60_000) {
          stage = "attachment"
          try { links.forEach { clients += it.awaitHarness(25_000) } }
          catch (_: Throwable) { error("Isolated SSH stage ${lastStep.get()}") }
          stage = "native reads"
          clients.forEach { client ->
            assertTrue(client is OpenCodeApi)
            assertEquals("2.2.6", client.health())
            assertTrue(client.discoverCapabilities().documented)
            assertTrue(client.projects().all { it.id.isNotBlank() && it.directory.isNotBlank() })
            assertTrue(client.rootSessionsPage(size = 10).items.isEmpty())
          }
          stage = "events"
          val opened = CompletableDeferred<Unit>()
          val stream = clients[1].events { opened.complete(Unit) }.launchIn(this)
          try { withTimeout(10_000) { opened.await() } } finally { stream.cancelAndJoin() }
          assertTrue(stream.isCompleted)
          stage = "sibling ownership"
          links[0].close()
          clients[0].awaitClosed()
          assertTrue(clients[0].isClosed)
          assertFalse(clients[1].isClosed)
          assertEquals("2.2.6", clients[1].health())
          assertEquals(1, connects.get())
        }
      } finally {
        links.forEach { it.close() }
        try { clients.forEach { it.awaitClosed() } } finally { scope.cancel(); pool.close() }
      }
    } catch (error: Throwable) {
      val kind = (error as? SshFailure)?.kind?.name ?: error.javaClass.simpleName
      val phase = error.message?.takeIf { it.startsWith("Isolated SSH stage ") }.orEmpty()
      throw AssertionError("Official SSH contract failed at $stage ($kind) $phase; sensitive details suppressed")
    }
  }

  private fun checkedFile(path: String, scratch: File? = null): File {
    check(path.length <= 4096 && path.none(Char::isISOControl))
    val file = File(path)
    check(file.isAbsolute && file.isFile)
    check(scratch == null || file.canonicalFile.toPath().startsWith(scratch.toPath()))
    return file.canonicalFile
  }

  private fun boundedText(file: File, maximum: Int): String {
    val bytes = file.inputStream().use { it.readNBytes(maximum + 1) }
    check(bytes.isNotEmpty() && bytes.size <= maximum)
    return bytes.toString(Charsets.UTF_8)
  }
}
