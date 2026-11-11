package com.igng.opencode.lagoon.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Two distinct guarded clients share actual DshApi auth/mux, without sharing foreground state. */
class DshClientRegistryIntegrationTest {
  private class Fixture : AutoCloseable {
    val server = MockWebServer()
    val physical = AtomicInteger(); val authentication = AtomicInteger(); val events = AtomicInteger()
    val followed = Channel<String>(Channel.UNLIMITED)
    val cancelled = Channel<String>(Channel.UNLIMITED)
    val endpoints = ConcurrentHashMap<String, String>()
    lateinit var owner: DshApi
    val registry = DshClientRegistry { profile, credentials, timeout, cookie ->
      DshApi(profile, credentials, timeout, cookie).also { owner = it }
    }
    val profile: ServerProfile
    init {
      server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> {
            authentication.incrementAndGet()
            MockResponse().setResponseCode(303).addHeader("Set-Cookie", "dsh-auth-lease=fixture; Path=/").addHeader("Location", "./")
          }
          request.path == "/api/session/list" -> MockResponse().setBody(JSONObject().put("result", JSONObject().put("ok", true).put("value",
            JSONObject().put("items", JSONArray(listOf(row("foreground"), row("background")))))).toString())
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { physical.incrementAndGet() }
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text); val id = frame.optString("streamId")
              if (frame.optString("type") == "cancel") { endpoints[id]?.let { cancelled.trySend(it) }; return }
              if (frame.optString("type") != "open") return
              val endpoint = frame.getString("endpoint"); endpoints[id] = endpoint
              fun send(value: JSONObject) { webSocket.send(JSONObject().put("type", "item").put("streamId", id).put("value", value).toString()) }
              when (endpoint) {
                "\$events" -> { events.incrementAndGet(); send(JSONObject().put("type", "ready").put("clientId", "shared-client")) }
                "workspace/follow" -> send(JSONObject().put("type", "baseline").put("items", JSONArray()))
                "session/follow" -> {
                  val address = frame.getJSONObject("payload").getJSONObject("args").getJSONObject("request").getJSONObject("address")
                  val sessionId = address.getString("sessionId")
                  followed.trySend(sessionId)
                  send(JSONObject().put("type", "snapshot").put("header", JSONObject().put("id", sessionId).put("cwd", "/same-directory")
                    .put("version", 4).put("createdAt", 1)).put("cursor", 0).put("hasMore", false).put("records", JSONArray()))
                }
              }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      profile = ServerProfile("shared-saved", "DSH", server.url("/").toString(), kind = ServerKind.DSH, allowCleartext = true)
    }
    fun acquire() = registry.acquire(profile, ServerCredentials("dsh", "test-token"))
    fun focus(): String? = DshApi::class.java.getDeclaredField("activeSessionId").apply { isAccessible = true }.get(owner) as String?
    fun content(): Boolean {
      val flow = DshApi::class.java.getDeclaredField("contentWatching").apply { isAccessible = true }.get(owner) as kotlinx.coroutines.flow.StateFlow<*>
      return flow.value as Boolean
    }
    override fun close() { registry.close(); server.shutdown() }
    private fun row(id: String) = JSONObject().put("sessionId", id).put("cwd", "/same-directory").put("updatedAt", 1)
      .put("running", false).put("blank", false).put("projections", JSONObject().put("values", JSONObject().put("title", id)))
  }

  @Test fun independentLeasesShareOneAuthAndMuxAndBackgroundReadCannotStealForeground() = runBlocking {
    Fixture().use { f ->
      val foreground = f.acquire(); val background = f.acquire()
      foreground.setContentWatching(true); background.setContentWatching(false)
      foreground.questionPresentation("foreground", true)
      val oneReady = CompletableDeferred<Unit>(); val twoReady = CompletableDeferred<Unit>()
      val one = launch { foreground.events { oneReady.complete(Unit) }.collect {} }
      val two = launch { background.events { twoReady.complete(Unit) }.collect {} }
      try {
        withTimeout(5000) { oneReady.await(); twoReady.await() }
        assertTrue(foreground.messagesPage("foreground").items.isEmpty())
        assertTrue(background.messagesPage("background").items.isEmpty())
        assertEquals("foreground", f.focus())
        assertTrue(f.content())
        assertEquals(1, f.authentication.get()); assertEquals(1, f.physical.get()); assertEquals(1, f.events.get())
        background.questionPresentation(null, false); background.setContentWatching(false); background.close()
        withTimeout(5000) { two.join(); background.awaitClosed() }
        assertTrue(one.isActive); assertEquals("foreground", f.focus()); assertTrue(f.content())
        assertFalse(f.owner.isClosed)
        foreground.close(); withTimeout(5000) { one.join(); foreground.awaitClosed() }
        assertTrue(f.owner.isClosed)
      } finally { foreground.close(); background.close(); one.cancelAndJoin(); two.cancelAndJoin() }
    }
  }

  @Test fun controlOnlyLeaseTransientReadDoesNotActivateWorkspaceOrClaimForeground() = runBlocking {
    Fixture().use { f ->
      val reader = f.acquire(); reader.setContentWatching(false)
      val ready = CompletableDeferred<Unit>()
      val stream = launch { reader.events { ready.complete(Unit) }.collect {} }
      try {
        withTimeout(5000) { ready.await() }
        assertTrue(reader.messagesPage("background").items.isEmpty())
        assertNull(f.focus()); assertFalse(f.content())
        assertTrue(f.endpoints.values.none { it == "workspace/follow" })
        assertTrue(f.owner.followDiagnostics().none { it.sessionId == "background" })
      } finally { reader.close(); withTimeout(5000) { stream.join(); reader.awaitClosed() } }
    }
  }
}
