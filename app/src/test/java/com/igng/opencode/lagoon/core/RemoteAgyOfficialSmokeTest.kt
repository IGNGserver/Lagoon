package com.igng.opencode.lagoon.core

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real AGY + pinned gateway, carried over an isolated fake Relay/Host Noise connection. */
class RemoteAgyOfficialSmokeTest {
  private class Store(private var record: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String) = record
    override fun save(slot: String, record: RemoteDeviceRecord) { this.record = record }
    override fun delete(slot: String) = Unit
  }
  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }
  private class Host(script: File) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val process = ProcessBuilder("node", script.absolutePath).start()
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    val ready = CompletableDeferred<JSONObject>()
    @Volatile var publish: (String, JSONObject) -> Unit = { _, _ -> }
    init {
      scope.launch {
        try {
          process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
            val frame = JSONObject(line)
            when (frame.optString("type")) {
              "ready" -> ready.complete(frame)
              "event" -> publish(frame.getString("event"), frame.getJSONObject("data"))
              "response", "error" -> pending.remove(frame.getString("id"))?.complete(frame)
              else -> ready.completeExceptionally(IOException("AGY test Host unavailable"))
            }
          } }
        } finally {
          val failure = IOException("AGY test Host stopped")
          ready.completeExceptionally(failure)
          pending.values.forEach { it.completeExceptionally(failure) }
        }
      }
      scope.launch { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { } } }
    }
    suspend fun call(method: String, params: JSONObject): Any? {
      val id = "agy-${sequence.incrementAndGet()}"
      val response = CompletableDeferred<JSONObject>(); pending[id] = response
      try {
        synchronized(writer) {
          writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString())
          writer.newLine(); writer.flush()
        }
        val result = withTimeout(30_000) { response.await() }
        if (result.optString("type") == "error") throw RemoteRpcException(result.optString("code"))
        return result.opt("result").takeUnless { it == JSONObject.NULL }
      } finally { pending.remove(id) }
    }
    override fun close() {
      runCatching { synchronized(writer) { writer.close() } }
      if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
      scope.cancel()
    }
  }
  @Test fun noiseCarriesRealAgyGatewayAndRestoresTheIsolatedSession() = runBlocking {
    val scriptEnv = System.getenv("LAGOON_AGY_STDIO_HOST")
    val rootEnv = System.getenv("LAGOON_OFFICIAL_AGY_ROOT")
    val binaryEnv = System.getenv("LAGOON_OFFICIAL_AGY_BINARY")
    assumeTrue("Opt in with a scratch AGY Host fixture", scriptEnv != null || rootEnv != null || binaryEnv != null)
    require(!scriptEnv.isNullOrBlank() && !rootEnv.isNullOrBlank() && !binaryEnv.isNullOrBlank())
    val scratch = File(System.getenv("BUILD_SCRATCH_ROOT") ?: File(System.getProperty("user.home"), ".build").path).canonicalFile
    val root = File(rootEnv).canonicalFile
    require(root.toPath().startsWith(scratch.toPath()) && root != scratch && root.isDirectory)
    val script = File(scriptEnv).canonicalFile
    require(script.toPath().startsWith(scratch.toPath()) && script.isFile)
    val workspace = Files.createTempDirectory(root.toPath(), "agy-noise-").toFile().canonicalFile
    Host(script).use { node ->
      assertEquals("bb33-agy-domain", withTimeout(30_000) { node.ready.await() }.getString("host"))
      val capabilities = listOf("agent.acp.v1", "agent.acp.transfer.v1", "agent.acp.antigravity.v1",
        "agent.acp.workspace-files.v1", "lagoon.antigravity.native-acp.v1")
      FakeRemoteHost(capabilities = capabilities, agyRpc = { method, params, _ -> runBlocking(Dispatchers.IO) { node.call(method, params) } }).use { host ->
        node.publish = host::publishNativeEvent
        host.start()
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val account = "agy-fixture@example.test"
        val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), account, RemoteIdentity.generate(),
          RemoteTokenPair(token, Long.MAX_VALUE / 2, token, Long.MAX_VALUE / 2))
        val devices = RemoteDeviceManager(Store(record), "agy-${UUID.randomUUID()}", host.url, account)
        val remote = RemoteSession(host.url, true, host.hostId, devices, RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account)
        var sessionId = ""
        var quotaLimited = false
        try {
          val api = AgyApi(remote.agyTransport())
          try {
            assertEquals("Antigravity ACP", api.health())
            val session = api.createSession(workspace.path, "Lagoon Noise smoke")
            sessionId = session.id
            val requested = System.getenv("LAGOON_AGY_TEST_MODEL")?.takeIf(String::isNotBlank)
            val model = requested?.let { value -> api.modelCatalogForSession(workspace.path, session).first { it.choice.modelId == value }.choice }
            val finished = async(start = CoroutineStart.UNDISPATCHED) {
              withTimeout(180_000) { api.events().first { it.type in setOf("session.execution.succeeded", "session.execution.failed", "session.execution.interrupted") } }
            }
            api.send(session, "Reply with exactly LAGOON_NOISE_SMOKE_OK. Do not use tools or change files.", model = model, id = "noise-smoke-input")
            assertEquals("session.execution.succeeded", finished.await().type)
            val messages = api.messagesPage(session.id).items
            val answer = messages.filter { it.role == "assistant" }.flatMap { it.parts }.joinToString("") { it.text }
            quotaLimited = answer.contains("quota reached", ignoreCase = true)
            assertTrue("AGY response was unexpected: ${answer.take(300)}", answer.contains("LAGOON_NOISE_SMOKE_OK") || quotaLimited)
            assertEquals(1, messages.count { it.role == "user" && it.parts.any { part -> part.text.contains("LAGOON_NOISE_SMOKE_OK") } })
            println(if (quotaLimited) "Noise AGY turn: account quota reached; warning displayed" else "Noise AGY turn: marker received")
          } finally { api.close() }
          val reopened = AgyApi(remote.agyTransport())
          try {
            assertTrue(reopened.rootSessionsPage(workspace.path).items.any { it.id == sessionId })
            val history = reopened.messagesPage(sessionId).items
            assertTrue(history.any { it.role == "user" && it.parts.any { part -> part.text.contains("LAGOON_NOISE_SMOKE_OK") } })
            if (!quotaLimited) assertTrue(history.any { it.role == "assistant" && it.parts.any { part -> part.text.contains("LAGOON_NOISE_SMOKE_OK") } })
            assertTrue(synchronized(host.rpcCalls) { host.rpcCalls.any { it.optString("method") == "agent.acp.transfer.commit" } })
          } finally { reopened.close() }
        } finally { remote.close() }
      }
    }
  }
}
