package com.igng.opencode.lagoon.core

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexOverviewReconnectTest {
  private val profile = ServerProfile("ssh-codex", "Codex", "ssh://alice@dev.example:22",
    username = "alice", kind = ServerKind.CODEX, sshHostId = "host")

  @Test fun failedOwnerIsReplacedAndResumesMonitoring() = runBlocking {
    val fixture = Fixture(this)
    try {
      fixture.start()
      await { fixture.transports.size == 1 && fixture.opens.get() >= 1 }
      val first = fixture.transports.first { it.first == profile.id }.second
      val oldClient = fixture.overview.client(profile.id)
      first.fail()
      await { fixture.transports.size >= 2 && fixture.opens.get() >= 2 }
      assertTrue(oldClient.isClosed)
      assertNotSame(oldClient, fixture.overview.client(profile.id))
      assertFalse(fixture.overview.client(profile.id).isClosed)
      assertTrue(fixture.transports.last { it.first == profile.id }.second.calls.contains("account/read"))
    } finally { fixture.close() }
  }

  @Test fun lateFailureCannotDisposeReplacementOrSiblingOwner() = runBlocking {
    val fixture = Fixture(this)
    val sibling = profile.copy(id = "sibling", name = "Sibling")
    try {
      fixture.start(listOf(profile, sibling))
      await { fixture.transports.size >= 2 && fixture.opens.get() >= 2 }
      val oldClient = fixture.overview.client(profile.id)
      val siblingClient = fixture.overview.client(sibling.id)
      val first = fixture.transports.first { it.first == profile.id }.second
      first.fail()
      await { oldClient.isClosed && profile.id in fixture.pendingRetries() }
      // Install a newer owner before the failed owner's delayed restart fires.
      fixture.overview.invalidate(profile.id)
      fixture.overview.start(listOf(profile, sibling))
      await { fixture.transports.size >= 3 && fixture.opens.get() >= 3 }
      val replacement = fixture.overview.client(profile.id)
      delay(1_250)
      assertSame(replacement, fixture.overview.client(profile.id))
      assertSame(siblingClient, fixture.overview.client(sibling.id))
      assertFalse(replacement.isClosed)
      assertFalse(siblingClient.isClosed)
    } finally { fixture.close() }
  }

  @Test fun supersededOwnerEventsCannotReachControllerOrRemoveReplacementState() = runBlocking {
    val events = Channel<ServerEvent>(Channel.UNLIMITED)
    val fixture = Fixture(this, events)
    try {
      fixture.start()
      await { fixture.transports.isNotEmpty() && fixture.opens.get() >= 1 }
      val oldTransport = fixture.transports.first().second
      val oldOwner = fixture.overview.client(profile.id)
      oldTransport.emit("thread/archived", "old-event")
      assertEquals("session.archived", withTimeout(3_000) { events.receive() }.type)
      // Keep the old collector alive while replacing the published owner: this reproduces the
      // boundary where a late frame arrives after connection replacement but before cancellation.
      val replacement = CodexApi(FakeTransport())
      fixture.replaceOwner(profile.id, replacement)
      oldTransport.emit("thread/deleted", "same-id")
      assertNull(withTimeoutOrNull(500) { events.receive() })
      assertSame(replacement, fixture.overview.client(profile.id))
      oldOwner.close()
    } finally { fixture.close() }
  }

  private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

  private class Fixture(parent: CoroutineScope, private val events: Channel<ServerEvent> = Channel(Channel.UNLIMITED)) {
    val transports = CopyOnWriteArrayList<Pair<String, FakeTransport>>()
    val opens = AtomicInteger()
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
    val overview = AllServersOverview(store, OfflineCache(memoryPreferences(), { it }, { it }), scope,
      {}, onEvent = { _, event -> events.trySend(event) }, onConnected = { opens.incrementAndGet() }, sshClientFactory = { configured, _ ->
        CodexApi(FakeTransport().also { transports.add(configured.id to it) })
      })
    fun start(profiles: List<ServerProfile> = listOf(profileForFixture())) {
      profiles.forEach { store.save(it, null) }
      overview.start(profiles)
    }
    @Suppress("UNCHECKED_CAST")
    fun replaceOwner(id: String, replacement: HarnessClient) {
      val owners = AllServersOverview::class.java.getDeclaredField("clients").apply { isAccessible = true }
        .get(overview) as MutableMap<String, HarnessClient>
      owners[id] = replacement
    }
    fun close() { overview.stop(); scope.cancel() }
    @Suppress("UNCHECKED_CAST")
    fun pendingRetries(): Map<String, Long> = AllServersOverview::class.java.getDeclaredField("codexRetryDelays")
      .apply { isAccessible = true }.get(overview) as Map<String, Long>
    private fun profileForFixture() = ServerProfile("ssh-codex", "Codex", "ssh://alice@dev.example:22",
      username = "alice", kind = ServerKind.CODEX, sshHostId = "host")
  }

  private class FakeTransport : CodexTransport {
    private val frames = Channel<JSONObject>(Channel.UNLIMITED)
    val calls = CopyOnWriteArrayList<String>()
    @Volatile private var closed = false
    override val isClosed: Boolean get() = closed
    override suspend fun call(method: String, params: JSONObject): JSONObject {
      check(!closed)
      calls += method
      return when (method) {
        "account/read" -> JSONObject().put("requiresOpenaiAuth", false).put("account", JSONObject.NULL)
        "project/list", "thread/list", "model/list" -> JSONObject().put("data", JSONArray())
        else -> JSONObject()
      }
    }
    override suspend fun respond(requestId: Any, result: JSONObject) = Unit
    override suspend fun subscribe(threadId: String) = Unit
    override suspend fun unsubscribe(threadId: String) = Unit
    override fun events() = frames.receiveAsFlow()
    fun emit(method: String, id: String) { check(frames.trySend(JSONObject().put("method", method)
      .put("params", JSONObject().put("threadId", id))).isSuccess) }
    fun fail() { closed = true; frames.close(IOException("test disconnect")) }
    override fun close() { closed = true; frames.close() }
  }
}
