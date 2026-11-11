package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Actual Kotlin Noise and pinned RpcRouter/DshChangesPeer; fake native data/history, not a daemon. */
class RemoteChangesConversationTest {
  private class Store(private var value: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String) = value
    override fun save(slot: String, record: RemoteDeviceRecord) { value = record }
    override fun delete(slot: String) = Unit
  }
  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }
  private class Node : AutoCloseable {
    private val script = File(requireNotNull(System.getenv("LAGOON_CHANGES_STDIO_HOST"))).canonicalFile
    private val process = ProcessBuilder("node", script.path, requireNotNull(System.getenv("LAGOON_PATCHED_ROUTER"))).directory(script.parentFile).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = process.outputStream.bufferedWriter()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val ids = AtomicLong()
    val ready = CompletableDeferred<JSONObject>()
    @Volatile var publish: (String, JSONObject) -> Unit = { _, _ -> }
    init {
      scope.launch { process.errorStream.bufferedReader().useLines { it.forEach { /* protocol errors are sanitized by the fixture */ } } }
      scope.launch {
        try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
          val value = JSONObject(line)
          when (value.optString("type")) {
            "ready" -> ready.complete(value)
            "event" -> publish(value.getString("event"), value.getJSONObject("data"))
            "response", "error" -> pending.remove(value.optString("id"))?.complete(value)
            else -> error("Unexpected changes fixture response")
          }
        } } } catch (_: Exception) { }
        finally {
          val failure = IOException("Changes fixture stopped")
          ready.completeExceptionally(failure); pending.values.forEach { it.completeExceptionally(failure) }
        }
      }
    }
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
      val id = "changes-${ids.incrementAndGet()}"; val reply = CompletableDeferred<JSONObject>(); pending[id] = reply
      try {
        synchronized(writer) { writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString()); writer.newLine(); writer.flush() }
        val response = withTimeout(10_000) { reply.await() }
        if (response.optString("type") == "error") throw RemoteRpcException(response.optString("code"))
        response.optJSONObject("result") ?: JSONObject()
      } finally { pending.remove(id) }
    }
    override fun close() { runCatching { writer.close() }; process.destroy(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS); scope.cancel() }
  }
  private fun session(host: FakeRemoteHost): RemoteSession {
    val account = host.accountEmail()
    val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), account, RemoteIdentity.generate(),
      RemoteTokenPair(UUID.randomUUID().toString(), Long.MAX_VALUE / 2, UUID.randomUUID().toString(), Long.MAX_VALUE / 2))
    return RemoteSession(host.url, true, host.hostId, RemoteDeviceManager(Store(record), "changes", host.url, account),
      RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account)
  }

  @Test fun actualNoiseAndRouterExposeTheSameNativePerFileDiffWithoutArbitraryFetch() = runBlocking {
    assumeTrue("Set staged changes Host and verified Router", !System.getenv("LAGOON_CHANGES_STDIO_HOST").isNullOrBlank() && !System.getenv("LAGOON_PATCHED_ROUTER").isNullOrBlank())
    Node().use { node ->
      assertTrue(withTimeout(10_000) { node.ready.await() }.getBoolean("changes"))
      FakeRemoteHost(harnessRpc = { method, params, _ -> runBlocking(Dispatchers.IO) { node.call(method, params) } }).use { host ->
        node.publish = host::publishNativeEvent; host.start()
        val session = session(host); val backend = RemoteBackend(session); val api = backend.client
        try {
          assertFalse(api.capabilities().diff)
          assertTrue(api.discoverCapabilities().diff)
          val owner = Session("session-changes-a", "/repo", "Changes", 1)
          val result = withTimeout(10_000) { api.diff(owner) }.single()
          assertEquals("src/main.kt", result.path)
          assertEquals(1, result.additions); assertEquals(1, result.deletions)
          assertTrue(result.patch.contains("-old\n+new\n"))
          assertEquals(2, node.call("fixture.stats").getInt("readCount"))
          val reads = host.rpcCalls.filter { it.optString("method") == "harness.changes.read" }
          assertEquals(2, reads.size)
          assertTrue(reads.all { it.getJSONObject("params").keys().asSequence().all { key -> key in setOf("kind", "sessionId", "seq", "index") } })
          node.call("fixture.status", JSONObject().put("status", 404))
          val missing = runCatching { api.diff(owner) }.exceptionOrNull()
          assertTrue(missing is ApiException); assertEquals(404, (missing as ApiException).status)
          node.call("fixture.status", JSONObject().put("status", 500))
          assertTrue(runCatching { api.diff(owner) }.isFailure)
        } finally { api.close(); backend.close(); session.close() }
      }
    }
  }

  @Test fun oldHostWithoutChangesCapabilityIsDeniedBeforeAnyChangesRead() = runBlocking {
    FakeRemoteHost().use { host ->
      host.start(); val session = session(host); val backend = RemoteBackend(session)
      try {
        assertFalse(backend.client.discoverCapabilities().diff)
        assertTrue(runCatching { backend.client.diff(Session("session", "/repo", "Old Host", 1)) }.isFailure)
        assertTrue(host.rpcCalls.none { it.optString("method") == "harness.changes.read" })
      } finally { backend.close(); session.close() }
    }
  }
}
