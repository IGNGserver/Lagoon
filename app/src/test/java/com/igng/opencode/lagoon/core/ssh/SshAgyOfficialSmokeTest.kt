package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opted-in live test. The fixture supplies a loopback SSH server and an isolated workspace. */
class SshAgyOfficialSmokeTest {
  @Test fun realAgyOverPinnedSshCreatesSendsAndReloadsAnIsolatedSession() = runBlocking {
    val fixtureEnv = System.getenv("LAGOON_AGY_SSH_FIXTURE")
    assumeTrue("Set LAGOON_AGY_SSH_FIXTURE to a caller-owned scratch SSH fixture", !fixtureEnv.isNullOrBlank())
    val scratch = File(System.getenv("BUILD_SCRATCH_ROOT") ?: File(System.getProperty("user.home"), ".build").path).canonicalFile
    val fixture = File(fixtureEnv!!).canonicalFile
    require(fixture.toPath().startsWith(scratch.toPath()) && fixture != scratch)
    val port = File(fixture, "port").readText().trim().toInt()
    val key = File(fixture, "client").readText()
    val publicHost = File(fixture, "host.pub").readText().trim().split(Regex("\\s+"))
    require(publicHost.firstOrNull() == "ssh-ed25519")
    val hostKey = Base64.getDecoder().decode(publicHost[1])
    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(hostKey))
    val config = SshHostConfig(id = "agy-live", name = "AGY smoke", host = "127.0.0.1", port = port,
      user = System.getProperty("user.name") ?: error("Current user unavailable"), auth = SshAuthMethod.PRIVATE_KEY,
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = fingerprint)
    val parent = File(fixture.parentFile, "workspace").canonicalFile
    require(parent.toPath().startsWith(scratch.toPath()) && parent.isDirectory)
    val workspace = Files.createTempDirectory(parent.toPath(), "agy-ssh-").toFile().canonicalFile
    File(workspace, "lagoon-smoke.txt").writeText("scratch fixture\n")
    fun client() = AgyApi(SshAgyTransport(config, SshSecrets(privateKey = key), JschSshTransportFactory()))
    val api = client()
    var sessionId = ""
    var quotaLimited = false
    try {
      assertEquals("Antigravity ACP", withTimeout(30_000) { api.health() })
      val session = withTimeout(30_000) { api.createSession(workspace.path, "Lagoon isolated smoke") }
      sessionId = session.id
      assertEquals("scratch fixture\n", api.fileContent(workspace.path, "lagoon-smoke.txt", session).content)
      assertTrue(api.files(workspace.path, ".", session).any { it.path == "lagoon-smoke.txt" })
      val models = api.modelCatalogForSession(workspace.path, session)
      val requestedModel = System.getenv("LAGOON_AGY_TEST_MODEL")?.takeIf(String::isNotBlank)
      val model = requestedModel?.let { requested -> models.firstOrNull { it.choice.modelId == requested }
        ?: error("The live AGY account did not advertise the selected test model") }
      val completion = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(180_000) { api.events().first { it.type in setOf("session.execution.succeeded", "session.execution.failed", "session.execution.interrupted") } }
      }
      api.send(session, "Reply with exactly LAGOON_AGY_SMOKE_OK. Do not use tools or change files.", model = model?.choice, id = "lagoon-smoke-input")
      assertEquals("session.execution.succeeded", completion.await().type)
      val messages = api.messagesPage(session.id).items
      val assistant = messages.filter { it.role == "assistant" }.flatMap { it.parts }.joinToString("") { it.text }
      quotaLimited = assistant.contains("quota reached", ignoreCase = true)
      assertTrue("isolated AGY response was unexpected: ${assistant.take(300)}", assistant.contains("LAGOON_AGY_SMOKE_OK") || quotaLimited)
      assertEquals("AGY's input echo must not duplicate the optimistic message", 1,
        messages.count { it.role == "user" && it.parts.any { part -> part.text.contains("LAGOON_AGY_SMOKE_OK") } })
      println(if (quotaLimited) "Live AGY model turn: account quota reached; warning displayed" else "Live AGY model turn: marker received")
    } finally { api.close() }
    val reopened = client()
    try {
      val session = withTimeout(30_000) { reopened.rootSessionsPage(workspace.path).items.firstOrNull { it.id == sessionId } }
      assertNotNull("AGY should persist the isolated session", session)
      val history = withTimeout(60_000) { reopened.messagesPage(sessionId).items }
      assertTrue("AGY should replay the saved input", history.any { it.role == "user" && it.parts.any { part -> part.text.contains("LAGOON_AGY_SMOKE_OK") } })
      if (!quotaLimited) assertTrue("AGY should replay the saved answer", history.any { it.role == "assistant" && it.parts.any { part -> part.text.contains("LAGOON_AGY_SMOKE_OK") } })
    } finally { reopened.close() }
  }
}
