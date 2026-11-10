package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real Kotlin Noise + real patched Node RpcRouter/NativeServicePeer + external loopback service. */
class RemoteNativeConversationTest {
  private class Store(private val original: RemoteDeviceRecord) : RemoteDeviceStore {
    private var current = original
    override fun load(slot: String) = current
    override fun save(slot: String, record: RemoteDeviceRecord) { current = record }
    override fun delete(slot: String) { }
  }

  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }

  private data class RunningNode(val process: Process, val scope: CoroutineScope, val rpc: StdioRpc) : AutoCloseable {
    override fun close() {
      rpc.close()
      process.destroy()
      if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
      scope.cancel()
    }
  }

  private class StdioRpc(process: Process, scope: CoroutineScope) : AutoCloseable {
    private val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean(false)
    @Volatile var publish: (String, JSONObject) -> Unit = { _, _ -> }
    val ready = CompletableDeferred<JSONObject>()
    private val errors = StringBuilder()
    private val errorJob = scope.launch(Dispatchers.IO) {
      BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).useLines { lines ->
        lines.forEach { line -> synchronized(errors) { if (errors.length < 4096) errors.append(line).append('\n') } }
      }
    }
    private val readerJob = scope.launch(Dispatchers.IO) {
      var failure: Throwable = IllegalStateException("stdio host exited")
      try {
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
          lines.forEach { line ->
            val message = JSONObject(line)
            when (message.optString("type")) {
              "ready" -> ready.complete(message)
              "event" -> publish(message.getString("event"), message.getJSONObject("data"))
              "response", "error" -> pending.remove(message.optString("id"))?.complete(message)
              else -> throw AssertionError("Unexpected stdio message: ${message.optString("type")}")
            }
          }
        }
      } catch (error: Throwable) { failure = error }
      finally {
        val error = IllegalStateException("Native stdio reader stopped: ${synchronized(errors) { errors.toString() }}", failure)
        ready.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
      }
    }

    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject = kotlinx.coroutines.withContext(Dispatchers.IO) {
      check(!closed.get())
      val id = "kt-${sequence.incrementAndGet()}"
      val result = CompletableDeferred<JSONObject>()
      pending[id] = result
      try {
        synchronized(writer) {
          writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString())
          writer.newLine(); writer.flush()
        }
        withTimeout(TIMEOUT_MILLIS) { result.await() }.also { response ->
          if (response.optString("type") == "error") throw NativeRemoteException(response.optString("code"), response.optString("message"))
          return@withContext response.optJSONObject("result") ?: JSONObject()
        }
      } finally { pending.remove(id) }
    }

    suspend fun fixture(method: String, params: JSONObject = JSONObject()) = call(method, params)

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        try { synchronized(writer) { writer.close() } } catch (_: Exception) { }
        readerJob.cancel()
        errorJob.cancel()
        pending.values.forEach { it.cancel() }; pending.clear()
      }
    }
  }

  private fun accessToken() = UUID.randomUUID().toString() + UUID.randomUUID().toString()

  private fun startNode(official: Boolean = false): RunningNode {
    val script = File(System.getenv("LAGOON_NATIVE_STDIO_HOST")!!).canonicalFile
    require(script.isFile) { "LAGOON_NATIVE_STDIO_HOST must name the staged stdio-host.mjs" }
    val router = System.getenv("LAGOON_PATCHED_ROUTER")
    require(!router.isNullOrBlank() && File(router).isFile) { "Actual patched-router.mjs is required for native integration" }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val builder = ProcessBuilder("node", script.absolutePath, router).directory(script.parentFile)
    if (official) builder.environment()["LAGOON_STDIO_OFFICIAL_REGISTRATION"] =
      requireNotNull(System.getenv("LAGOON_OFFICIAL_OPENCODE_REGISTRATION"))
    else builder.environment().remove("LAGOON_STDIO_OFFICIAL_REGISTRATION")
    val process = builder.start()
    val rpc = StdioRpc(process, scope)
    return RunningNode(process, scope, rpc)
  }

  private fun session(host: FakeRemoteHost): RemoteSession {
    val account = "stdio-user@example.test"
    val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), account, RemoteIdentity.generate(),
      RemoteTokenPair(accessToken(), Long.MAX_VALUE / 2, accessToken(), Long.MAX_VALUE / 2))
    val manager = RemoteDeviceManager(Store(record), "native-${UUID.randomUUID()}", host.url, account)
    return RemoteSession(host.url, true, host.hostId, manager,
      RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account)
  }

  @Test fun actualPatchedRouterCarriesCatalogRenameSseBinaryAndCloseIsolation() = runBlocking {
    assumeTrue("Set LAGOON_NATIVE_STDIO_HOST to run the cross-language test",
      !System.getenv("LAGOON_NATIVE_STDIO_HOST").isNullOrBlank())
    startNode().use { node ->
      assertTrue("must load the actual patched RpcRouter", withTimeout(TIMEOUT_MILLIS) { node.rpc.ready.await() }.getBoolean("router"))
      FakeRemoteHost(nativeRpc = { method, params, _ -> runBlocking(Dispatchers.IO) { node.rpc.call(method, params) } }).use { host ->
        node.rpc.publish = host::publishNativeEvent
        host.start()
        val session = session(host)
        try {
          val catalog = requireNotNull(withTimeout(TIMEOUT_MILLIS) { session.nativeServiceCatalog() })
          assertEquals(DiscoveryCompleteness.COMPLETE, catalog.completeness)
          val descriptor = catalog.services.single()
          assertTrue(descriptor.available)
          assertEquals("opencode-native", descriptor.protocol)
          assertTrue(descriptor.capabilities.containsAll(setOf("http", "binary", "sse", "websocket")))
          val profile = ServerProfile("native-test", "Native service", RemoteNativeServices.VIRTUAL_BASE_URL,
            kind = ServerKind.OPENCODE, remoteHostId = host.hostId, remoteServiceId = descriptor.id)
          // Two child leases on ONE physical Noise session; neither child owns that session.
          RemoteBackend(session, profile, release = {}).use { backend ->
            RemoteBackend(session, profile.copy(id = "native-sibling"), release = {}).use { sibling ->
              val api = backend.client
              val page = withTimeout(TIMEOUT_MILLIS) { api.rootSessionsPage(size = 20) }
              assertEquals("ses_native /one", page.items.single().id)
              withTimeout(TIMEOUT_MILLIS) { api.renameSession(page.items.single(), "Renamed over Noise") }
              assertEquals(1, node.rpc.fixture("fixture.stats").getInt("renameCount"))

              val firstEvent = CompletableDeferred<ServerEvent>()
              val siblingEvent = CompletableDeferred<ServerEvent>()
              val collector = launch(Dispatchers.IO) {
                api.events(onOpen = {}).collect { firstEvent.complete(it) }
              }
              val siblingCollector = launch(Dispatchers.IO) {
                sibling.client.events(onOpen = {}).collect { siblingEvent.complete(it) }
              }
              try {
                for (received in listOf(withTimeout(TIMEOUT_MILLIS) { firstEvent.await() },
                  withTimeout(TIMEOUT_MILLIS) { siblingEvent.await() })) {
                  assertEquals("session.updated", received.type)
                  assertEquals("/repo", received.directory)
                  assertEquals("界面", received.properties.getString("marker"))
                }
                node.rpc.fixture("fixture.waitForStreams", JSONObject().put("count", 2))
                val response = withTimeout(TIMEOUT_MILLIS) {
                  session.nativeServiceTransport(descriptor.id).use { transport ->
                    transport.request("POST", "api/large", body = byteArrayOf(0, 1, -1), maxResponseBytes = 1024L * 1024)
                  }
                }
                assertEquals(200, response.statusCode)
                assertArrayEquals(ByteArray(512 * 1024 + 23) { (it % 251).toByte() }, response.body)
                assertFalse(response.headers.keys.any { it.equals("set-cookie", true) || it.equals("x-private", true) })
                val stats = node.rpc.fixture("fixture.stats")
                assertEquals(1, stats.getInt("largeMutations"))
                val calls = synchronized(host.rpcCalls) { host.rpcCalls.toList() }
                assertEquals(1, calls.count { it.getString("method") == "harness.services.call" &&
                  it.getJSONObject("params").optString("path") == "/api/large" })
                assertEquals("cached response is read in exactly two chunks", 2,
                  calls.count { it.getString("method") == "harness.services.transfer.read" })
                assertEquals(1, calls.count { it.getString("method") == "harness.services.transfer.close" })
                val requests = stats.getJSONArray("requests")
                assertTrue((0 until requests.length()).all {
                  val request = requests.getJSONObject(it)
                  request.getBoolean("authenticated") == (request.getString("path").substringBefore('?') != "/api/info")
                })
                assertTrue(calls.any { it.getString("method") == "harness.services.describe" })
                assertTrue("native service must never route through ACP or the DSH gateway", calls.all {
                  it.getString("method").startsWith("harness.services.") || it.getString("method") == "harness.transport.describe"
                })
                assertTrue("local origin and credentials remain Host-owned", calls.none {
                  val params = it.getJSONObject("params")
                  params.has("url") || params.has("password") || params.toString().contains("stdio-fixture-password") ||
                    params.toString().contains("127.0.0.1") || params.toString().contains("authorization", ignoreCase = true)
                })

                val attachment = ByteArray(2 * 1024 * 1024 + 17) { (it % 239).toByte() }
                val echoed = withTimeout(TIMEOUT_MILLIS) {
                  session.nativeServiceTransport(descriptor.id).use { transport ->
                    transport.request("POST", "api/upload", body = attachment, maxResponseBytes = attachment.size.toLong())
                  }
                }
                assertArrayEquals(attachment, echoed.body)
                assertEquals(1, node.rpc.fixture("fixture.stats").getInt("uploadCount"))
                val uploadCalls = synchronized(host.rpcCalls) { host.rpcCalls.toList() }
                assertEquals(1, uploadCalls.count { it.getString("method") == "harness.services.transfer.open" })
                assertEquals(5, uploadCalls.count { it.getString("method") == "harness.services.transfer.chunk" })
                assertEquals(1, uploadCalls.count { it.getString("method") == "harness.services.transfer.commit" })

                backend.close()
                // Closing a transport suppresses callbacks; the UI owns cancellation of its flow.
                collector.cancel(); collector.join()
                assertEquals(1, node.rpc.fixture("fixture.waitForStreams", JSONObject().put("count", 1)).getInt("activeSse"))
                assertTrue("sibling SSE collector stays live", siblingCollector.isActive)
                assertFalse("child close must not close the shared session", session.isClosed)
                val siblingPage = withTimeout(TIMEOUT_MILLIS) { sibling.client.rootSessionsPage(size = 20) }
                assertEquals("Renamed over Noise", siblingPage.items.single().title)
                assertEquals("only one Noise connection attempt", 1, session.status.value.generation)
                assertEquals("only one physical Host WebSocket/handshake", 1,
                  synchronized(host.requests) { host.requests.count { it == "GET /ws/v1/connect" } })
              } finally {
                collector.cancel(); siblingCollector.cancel()
                collector.join(); siblingCollector.join()
              }
            }
          }
        } finally { session.close() }
      }
    }
  }

  @Test fun officialServiceReadsAndEventsCrossActualPatchedRouterAndNoise() = runBlocking {
    assumeTrue("Set official registration, staged stdio Host and patched Router to enable live encrypted acceptance",
      !System.getenv("LAGOON_OFFICIAL_OPENCODE_REGISTRATION").isNullOrBlank() &&
        !System.getenv("LAGOON_NATIVE_STDIO_HOST").isNullOrBlank())
    startNode(official = true).use { node ->
      assertTrue(withTimeout(TIMEOUT_MILLIS) { node.rpc.ready.await() }.getBoolean("router"))
      FakeRemoteHost(nativeRpc = { method, params, _ -> runBlocking(Dispatchers.IO) { node.rpc.call(method, params) } }).use { host ->
        node.rpc.publish = host::publishNativeEvent
        host.start()
        val session = session(host)
        try {
          val catalog = requireNotNull(withTimeout(TIMEOUT_MILLIS) { session.nativeServiceCatalog() })
          assertEquals(DiscoveryCompleteness.COMPLETE, catalog.completeness)
          val descriptor = catalog.services.single()
          assertTrue(descriptor.available)
          val profile = ServerProfile("official-noise", "Official encrypted service", RemoteNativeServices.VIRTUAL_BASE_URL,
            kind = ServerKind.OPENCODE, remoteHostId = host.hostId, remoteServiceId = descriptor.id)
          RemoteBackend(session, profile, release = {}).use { backend ->
            assertEquals("2.2.6", withTimeout(TIMEOUT_MILLIS) { backend.client.health() })
            assertTrue(withTimeout(TIMEOUT_MILLIS) { backend.client.discoverCapabilities() }.documented)
            assertTrue(withTimeout(TIMEOUT_MILLIS) { backend.client.projects() }.all { it.id.isNotBlank() })
            assertTrue(withTimeout(TIMEOUT_MILLIS) { backend.client.rootSessionsPage(size = 10) }.items.isEmpty())
            val opened = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.IO) { backend.client.events { opened.complete(Unit) }.collect {} }
            try { withTimeout(TIMEOUT_MILLIS) { opened.await() } } finally { collector.cancel(); collector.join() }
            assertEquals("2.2.6", withTimeout(TIMEOUT_MILLIS) { backend.client.health() })
          }
          assertEquals(1, session.status.value.generation)
          assertEquals(1, synchronized(host.requests) { host.requests.count { it == "GET /ws/v1/connect" } })
        } finally { session.close() }
      }
    }
  }

  @Test fun previousHostWithoutNativeCapabilityReturnsNullWithoutAcpFallback() = runBlocking {
    FakeRemoteHost().use { host ->
      host.start()
      val session = session(host)
      try {
        assertEquals(null, withTimeout(TIMEOUT_MILLIS) { session.nativeServiceCatalog() })
        assertEquals(setOf("harness.transport.describe"), host.rpcCalls.map { it.getString("method") }.toSet())
      } finally { session.close() }
    }
  }

  private companion object { const val TIMEOUT_MILLIS = 15_000L }
}
