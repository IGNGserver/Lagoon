package com.igng.opencode.lagoon.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real mux regression tests: one DshApi owns one business stream, not one per collector. */
class DshEventSubscribersTest {
  private data class Stream(val socket: WebSocket, val id: String) {
    fun send(value: JSONObject) = assertTrue(socket.send(JSONObject().put("type", "item")
      .put("streamId", id).put("value", value).toString()))
    fun ready() = send(JSONObject().put("type", "ready").put("clientId", "client-$id"))
    fun created(id: String) = send(JSONObject().put("type", "emit").put("event", "api-session/added")
      .put("args", JSONArray().put(JSONObject().put("sessionId", id).put("cwd", "/repo")
        .put("updatedAt", 100).put("running", false).put("blank", false)
        .put("projections", JSONObject().put("values", JSONObject().put("title", id))))))
  }
  private class Watch(scope: CoroutineScope, client: DshApi) {
    val opened = Channel<Unit>(Channel.UNLIMITED)
    val events = Channel<ServerEvent>(Channel.UNLIMITED)
    val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
      client.events { opened.trySend(Unit) }.collect { events.send(it) }
    }
    suspend fun ready() = withTimeout(5_000) { opened.receive() }
    suspend fun created(): ServerEvent = withTimeout(5_000) {
      val event = events.receive()
      assertEquals("session.created", event.type)
      event
    }
    suspend fun stop() = withTimeout(5_000) { job.cancelAndJoin() }
  }
  private class Fixture : AutoCloseable {
    val opens = Channel<Stream>(Channel.UNLIMITED)
    val cancels = Channel<String>(Channel.UNLIMITED)
    val disconnected = Channel<Unit>(Channel.UNLIMITED)
    val logical = AtomicInteger()
    val physical = AtomicInteger()
    val canceled = ConcurrentHashMap<String, AtomicInteger>()
    private val seenDisconnects = ConcurrentHashMap.newKeySet<WebSocket>()
    val server = MockWebServer()
    val client: DshApi
    init {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token=") -> MockResponse().setResponseCode(303)
            .addHeader("Set-Cookie", "dsh-auth-abc=cookie-value; Path=/; HttpOnly")
            .addHeader("Location", "./")
          request.path == "/api/remote.mux" -> {
            assertEquals("dsh-auth-abc=cookie-value", request.getHeader("Cookie"))
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) { physical.incrementAndGet() }
              override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                when (frame.getString("type")) {
                  "open" -> {
                    assertEquals("\$events", frame.getString("endpoint"))
                    assertEquals(0, frame.getJSONObject("payload").getJSONObject("args").length())
                    logical.incrementAndGet()
                    opens.trySend(Stream(webSocket, frame.getString("streamId")))
                  }
                  "cancel" -> {
                    val id = frame.getString("streamId")
                    canceled.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
                    cancels.trySend(id)
                  }
                }
              }
              private fun ended(socket: WebSocket) {
                if (seenDisconnects.add(socket)) disconnected.trySend(Unit)
              }
              override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason); ended(webSocket)
              }
              override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(webSocket)
              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended(webSocket)
            })
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      client = DshApi(ServerProfile("srv", "DSH", server.url("/").toString().trimEnd('/'),
        username = "dsh", allowCleartext = true, kind = ServerKind.DSH), ServerCredentials("dsh", "tok"))
      client.setContentWatching(false)
    }
    suspend fun open() = withTimeout(5_000) { opens.receive() }
    suspend fun canceled(id: String) {
      assertEquals(id, withTimeout(5_000) { cancels.receive() })
      assertEquals(1, canceled[id]?.get())
    }
    override fun close() { client.close(); server.shutdown() }
  }

  @Test fun concurrentCollectorsShareLogicalAndPhysicalStreamAndTranslation() = runBlocking {
    Fixture().use { f ->
      val a = Watch(this, f.client); val b = Watch(this, f.client)
      val stream = f.open(); stream.ready(); a.ready(); b.ready()
      stream.created("s1")
      val first = a.created(); val second = b.created()
      assertEquals(first.id, second.id)
      assertEquals(first.properties.toString(), second.properties.toString())
      assertEquals("s1", first.properties.getString("sessionID"))
      assertEquals("/repo", first.directory)
      assertEquals(1, f.logical.get()); assertEquals(1, f.physical.get())
      a.stop(); b.stop(); f.canceled(stream.id)
    }
  }

  @Test fun subscribersCannotMutateEachOthersJsonPayloads() = runBlocking {
    Fixture().use { f ->
      val a = Watch(this, f.client); val b = Watch(this, f.client)
      val stream = f.open(); stream.ready(); a.ready(); b.ready()
      stream.created("isolated")
      val first = a.created(); val second = b.created()
      first.properties.put("sessionID", "changed-by-reader")
      first.properties.getJSONObject("info").put("title", "changed-by-reader")
      assertEquals("isolated", second.properties.getString("sessionID"))
      assertEquals("isolated", second.properties.getJSONObject("info").getString("title"))
      a.stop(); b.stop(); f.canceled(stream.id)
    }
  }

  @Test fun cancelingOneCollectorDoesNotCancelItsSiblingOrBusinessStream() = runBlocking {
    Fixture().use { f ->
      val a = Watch(this, f.client); val b = Watch(this, f.client)
      val stream = f.open(); stream.ready(); a.ready(); b.ready()
      a.stop(); stream.created("survivor")
      assertEquals("survivor", b.created().properties.getString("sessionID"))
      assertTrue(f.cancels.tryReceive().isFailure)
      assertEquals(1, f.logical.get()); assertEquals(1, f.physical.get())
      b.stop(); f.canceled(stream.id)
    }
  }

  @Test fun readyOwnerImmediatelyAcknowledgesLateSubscriberWithoutSecondOpen() = runBlocking {
    Fixture().use { f ->
      val a = Watch(this, f.client); val stream = f.open(); stream.ready(); a.ready()
      val b = Watch(this, f.client); b.ready()
      stream.created("late")
      assertEquals(a.created().id, b.created().id)
      assertEquals(1, f.logical.get()); assertEquals(1, f.physical.get())
      assertTrue(a.opened.tryReceive().isFailure); assertTrue(b.opened.tryReceive().isFailure)
      a.stop(); b.stop(); f.canceled(stream.id)
    }
  }

  @Test fun finalCancellationRetiresStreamAndFencesOldFramesFromReplacement() = runBlocking {
    Fixture().use { f ->
      val a = Watch(this, f.client); val old = f.open(); old.ready(); a.ready()
      a.stop(); f.canceled(old.id)
      val b = Watch(this, f.client); val fresh = f.open()
      assertNotEquals(old.id, fresh.id)
      assertTrue(b.opened.tryReceive().isFailure)
      old.ready(); old.created("stale"); fresh.ready(); b.ready(); fresh.created("fresh")
      assertEquals("fresh", b.created().properties.getString("sessionID"))
      assertTrue(b.opened.tryReceive().isFailure); assertTrue(a.opened.tryReceive().isFailure)
      assertEquals(2, f.logical.get()); assertEquals(1, f.physical.get())
      b.stop(); f.canceled(fresh.id)
      assertEquals(1, f.canceled[old.id]?.get())
    }
  }

  @Test fun clientCloseCompletesEverySubscriberAndClosesPhysicalOwnerOnce() = runBlocking {
    Fixture().use { f ->
      val a = Watch(this, f.client); val b = Watch(this, f.client)
      val stream = f.open(); stream.ready(); a.ready(); b.ready()
      f.client.close(); f.client.close()
      withTimeout(5_000) { a.job.join(); b.job.join(); f.client.awaitClosed(); f.disconnected.receive() }
      assertTrue(a.job.isCompleted); assertTrue(b.job.isCompleted); assertTrue(f.client.isClosed)
      var rejected = false
      try {
        withTimeout(5_000) { f.client.events { fail("Closed client acknowledged a subscriber") }
          .collect { fail("Closed client published an event") } }
      } catch (_: IllegalStateException) { rejected = true }
      assertTrue("Closed client must reject new subscribers", rejected)
      assertEquals(1, f.logical.get()); assertEquals(1, f.physical.get())
      assertTrue(f.disconnected.tryReceive().isFailure)
      assertTrue(f.opens.tryReceive().isFailure)
    }
  }

  @Test fun finalSubscriberCancellationBeforeReadyRetiresTheUnacknowledgedProducer() = runBlocking {
    Fixture().use { f ->
      val a = Watch(this, f.client); val abandoned = f.open()
      a.stop(); f.canceled(abandoned.id)
      val b = Watch(this, f.client); val fresh = f.open()
      abandoned.ready(); abandoned.created("must-not-deliver")
      fresh.ready(); b.ready(); fresh.created("new-reader")
      assertEquals("new-reader", b.created().properties.getString("sessionID"))
      assertTrue(a.opened.tryReceive().isFailure)
      assertEquals(2, f.logical.get()); assertEquals(1, f.physical.get())
      b.stop(); f.canceled(fresh.id)
    }
  }

  @Test fun firstSubscriberCancellationBeforeReadyDoesNotStealSiblingAcknowledgement() = runBlocking {
    Fixture().use { f ->
      val a = Watch(this, f.client); val b = Watch(this, f.client); val stream = f.open()
      a.stop(); stream.ready(); b.ready(); stream.created("waiting")
      assertEquals("waiting", b.created().properties.getString("sessionID"))
      assertTrue(a.opened.tryReceive().isFailure); assertTrue(f.cancels.tryReceive().isFailure)
      assertEquals(1, f.logical.get()); assertEquals(1, f.physical.get())
      b.stop(); f.canceled(stream.id)
    }
  }
}
