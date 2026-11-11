package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Actual Noise + actual pinned RpcRouter/TerminalPolicy/CarrierCompat, processless native provider. */
class RemoteTerminalConversationTest {
  private class Store(private var record: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String) = record
    override fun save(slot: String, record: RemoteDeviceRecord) { this.record = record }
    override fun delete(slot: String) = Unit
  }
  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }
  private class Node(official: Boolean = false) : AutoCloseable {
    private val script = File(requireNotNull(System.getenv("LAGOON_TERMINAL_STDIO_HOST"))).canonicalFile
    private val router = File(requireNotNull(System.getenv("LAGOON_PATCHED_ROUTER"))).canonicalFile
    private val process = ProcessBuilder("node", script.path, router.path).directory(script.parentFile).apply {
      if (official) environment()["LAGOON_STDIO_OFFICIAL_DSH_CONNECTION"] = requireNotNull(System.getenv("LAGOON_OFFICIAL_REMOTE_DSH_CONNECTION"))
      else environment().remove("LAGOON_STDIO_OFFICIAL_DSH_CONNECTION")
    }.start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = process.outputStream.bufferedWriter()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    val ready = CompletableDeferred<JSONObject>()
    @Volatile var publish: (String, JSONObject) -> Unit = { _, _ -> }
    init {
      scope.launch { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { /* no credential-bearing output */ } } }
      scope.launch {
        try {
          process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
            val value = JSONObject(line)
            when (value.optString("type")) {
              "ready" -> ready.complete(value)
              "event" -> publish(value.getString("event"), value.getJSONObject("data"))
              "response", "error" -> pending.remove(value.optString("id"))?.complete(value)
              else -> error("Unexpected terminal fixture response")
            }
          } }
        } catch (_: Exception) { }
        finally {
          val failure = IOException("Terminal fixture stopped")
          ready.completeExceptionally(failure)
          pending.values.forEach { it.completeExceptionally(failure) }
        }
      }
    }
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
      val id = "terminal-${sequence.incrementAndGet()}"
      val reply = CompletableDeferred<JSONObject>(); pending[id] = reply
      try {
        synchronized(writer) { writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString()); writer.newLine(); writer.flush() }
        val response = withTimeout(15_000) { reply.await() }
        if (response.optString("type") == "error") throw RemoteRpcException(response.optString("code"))
        response.optJSONObject("result") ?: JSONObject()
      } finally { pending.remove(id) }
    }
    override fun close() {
      runCatching { writer.close() }; process.destroy()
      if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
      scope.cancel()
    }
  }
  private fun session(host: FakeRemoteHost): RemoteSession {
    val account = host.accountEmail()
    val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), account, RemoteIdentity.generate(),
      RemoteTokenPair(UUID.randomUUID().toString(), Long.MAX_VALUE / 2, UUID.randomUUID().toString(), Long.MAX_VALUE / 2))
    return RemoteSession(host.url, true, host.hostId, RemoteDeviceManager(Store(record), "terminal-slot", host.url, account),
      RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account)
  }

  @Test fun actualNoiseRouterAndPolicyCarryTheOfficialDshDaemonTerminal() = runBlocking {
    assumeTrue("Set staged terminal Host, verified Router and isolated official DSH provider",
      !System.getenv("LAGOON_TERMINAL_STDIO_HOST").isNullOrBlank() && !System.getenv("LAGOON_PATCHED_ROUTER").isNullOrBlank() &&
        !System.getenv("LAGOON_OFFICIAL_REMOTE_DSH_CONNECTION").isNullOrBlank())
    Node(official = true).use { node ->
      assertTrue(withTimeout(15_000) { node.ready.await() }.getBoolean("official"))
      val created = node.call("fixture.createSession")
      assertTrue(created.getBoolean("ok"))
      val owner = TerminalScope.Session(created.getJSONObject("value").getString("sessionId"), "/isolated/remote-display")
      FakeRemoteHost(harnessRpc = { method, params, _ -> runBlocking(Dispatchers.IO) { node.call(method, params) } }).use { host ->
        node.publish = host::publishNativeEvent; host.start()
        val session = session(host); val backend = RemoteBackend(session); val api = backend.client
        var terminal: NativeTerminal? = null; var attached: TerminalAttachment? = null
        try {
          assertTrue(api.discoverCapabilities().terminal)
          assertTrue(api.terminals(owner).isEmpty())
          terminal = api.createTerminal(owner)
          attached = api.attachTerminal(owner, terminal.id)
          assertTrue(attached.sendText("printf 'LAGOON_%s\\n' REMOTE_OFFICIAL_DSH\r"))
          withTimeout(15_000) { attached.state.first { it.acknowledgedSequence == 1L && it.output.contains("LAGOON_REMOTE_OFFICIAL_DSH") } }
          assertTrue(attached.resize(90, 30))
          withTimeout(15_000) { attached.state.first { it.acknowledgedSequence == 2L } }
          attached.close(); attached = null
          assertEquals("running", api.terminals(owner).single().status)
          api.closeTerminal(owner, terminal.id); terminal = null
          assertTrue(api.terminals(owner).isEmpty())
        } finally {
          attached?.close()
          try { terminal?.let { runCatching { api.closeTerminal(owner, it.id) } } }
          finally { api.close(); backend.close(); session.close() }
        }
      }
    }
  }

  @Test fun actualNoiseRouterAndHostPolicyCarryTerminalProviderAcksWithoutOwnershipBypass() = runBlocking {
    assumeTrue("Set staged terminal stdio Host and verified Router for encrypted integration",
      !System.getenv("LAGOON_TERMINAL_STDIO_HOST").isNullOrBlank() && !System.getenv("LAGOON_PATCHED_ROUTER").isNullOrBlank())
    Node().use { node ->
      assertTrue(withTimeout(15_000) { node.ready.await() }.getBoolean("terminal"))
      FakeRemoteHost(harnessRpc = { method, params, _ -> runBlocking(Dispatchers.IO) { node.call(method, params) } }).use { host ->
        node.publish = host::publishNativeEvent; host.start()
        val session = session(host)
        val backend = RemoteBackend(session)
        val api = backend.client
        val owner = TerminalScope.Session("session-terminal-a", "/display/workspace")
        var terminal: NativeTerminal? = null
        var first: TerminalAttachment? = null
        var second: TerminalAttachment? = null
        try {
          assertFalse(api.capabilities().terminal) // No permission inferred before negotiation.
          assertTrue(api.discoverCapabilities().terminal)
          assertTrue(api.terminals(owner).isEmpty()) // External native terminal hidden by actual device policy.
          terminal = api.createTerminal(owner)
          first = try { api.attachTerminal(owner, terminal.id) } catch (error: Exception) {
            val stats = node.call("fixture.stats")
            throw AssertionError("Terminal attach failed: native attach=${stats.optInt("attach")}, holds=${stats.optInt("activeHolds")}, follows=${stats.optInt("activeFollows")}, streams=${backend.streamStats()}, gateway event=${session.gateway().lastError}", error)
          }
          assertTrue(first.state.value.writable)
          node.call("fixture.manualAck", JSONObject().put("enabled", true))
          assertTrue(first.sendText("Noise terminal input\r"))
          node.call("fixture.waitFor", JSONObject().put("writes", 1))
          assertEquals(0L, first.state.value.acknowledgedSequence)
          assertEquals(1, first.state.value.pendingInputs)
          node.call("fixture.ack")
          withTimeout(10_000) { first.state.first { it.acknowledgedSequence == 1L } }
          assertTrue(first.state.value.output.contains("Noise terminal input"))
          node.call("fixture.manualAck", JSONObject().put("enabled", false))
          assertTrue(first.resize(90, 30))
          withTimeout(10_000) { first.state.first { it.acknowledgedSequence == 2L && it.info?.cols == 90 } }
          second = api.attachTerminal(owner, terminal.id)
          withTimeout(10_000) { first.state.first { !it.writable } }
          assertFalse(first.sendText("must not replay\r"))
          assertTrue(second.state.value.writable)
          second.close(); second = null; first.close(); first = null
          node.call("fixture.waitFor", JSONObject().put("activeHolds", 0).put("activeFollows", 0))
          assertEquals(0, node.call("fixture.stats").getInt("closes"))
          assertEquals("running", api.terminals(owner).single().status)
          val before = node.call("fixture.stats").getInt("nativeRPCcount")
          val foreign = runCatching { node.call("fixture.secondDeviceProbe", JSONObject().put("method", "harness.remote.call").put("params",
            JSONObject().put("endpoint", "terminal/close").put("payload", JSONObject().put("args", JSONObject().put("agentId", owner.sessionId).put("id", terminal.id))))) }
          assertTrue(foreign.isFailure)
          assertEquals(before, node.call("fixture.stats").getInt("nativeRPCcount"))
          api.closeTerminal(owner, terminal.id); terminal = null
          assertTrue(api.terminals(owner).isEmpty())
          node.call("fixture.disable")
          assertTrue(runCatching { api.createTerminal(owner) }.isFailure)
          assertEquals(1, node.call("fixture.stats").getInt("creates"))
        } finally {
          second?.close(); first?.close()
          try { terminal?.let { runCatching { api.closeTerminal(owner, it.id) } } }
          finally { api.close(); backend.close(); session.close() }
        }
      }
    }
  }
}
