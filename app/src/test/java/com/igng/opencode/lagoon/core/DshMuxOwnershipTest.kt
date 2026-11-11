package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Regression for the one-API-Peer/one-physical-socket DSH mux contract. */
class DshMuxOwnershipTest {
  @Test fun terminalInputIsWrappedAsItemValueAndNeverReplayedAcrossPhysicalGenerations() = runBlocking {
    MuxFixture().use { fixture ->
      val terminal = fixture.open("terminal/attach")
      terminal.item()
      val command = JSONObject().put("type", "write").put("sequence", 1).put("data", "echo test\r")
      assertTrue(terminal.socket.send(command.toString()))
      val frame = withTimeout(5_000) { fixture.uplinks.receive() }
      assertEquals("item", frame.getString("type"))
      assertEquals(fixture.id("terminal/attach"), frame.getString("streamId"))
      assertEquals("echo test\r", frame.getJSONObject("value").getString("data"))
      assertFalse(frame.has("payload")); assertFalse(frame.has("data"))
      val old = fixture.clientSocket()
      fixture.listener(old).onFailure(old, IOException("drop"), null)
      withTimeout(5_000) { terminal.closed.await() }
      assertFalse(terminal.socket.send(command.toString()))
      val replacement = fixture.open("terminal/attach")
      replacement.item()
      assertFalse(terminal.socket.send(command.toString()))
      assertTrue(fixture.uplinks.tryReceive().isFailure)
      assertFalse(replacement.socket.send("{\"type\":\"unexpected\"}"))
      replacement.socket.cancel()
      assertFalse(replacement.socket.send(command.toString()))
    }
  }

  @Test fun readOnlyLogicalStreamsDoNotGainGenericUplinkCapability() = runBlocking {
    MuxFixture().use { fixture ->
      val reader = fixture.open("workspace/follow"); reader.item()
      assertFalse(reader.socket.send("{\"type\":\"write\",\"sequence\":1,\"data\":\"x\"}"))
      val terminal = fixture.open("terminal/attach"); terminal.item()
      assertFalse(terminal.socket.send("not json"))
      assertFalse(terminal.socket.send(okio.ByteString.of(0x78.toByte())))
      assertFalse(terminal.socket.send("{\"type\":\"write\",\"data\":\"${"x".repeat(256 * 1024)}\"}"))
      assertTrue(fixture.uplinks.tryReceive().isFailure)
    }
  }

  @Test fun twoLogicalStreamsShareOneSocketAndCancelIsolatedStream() = runBlocking {
    MuxFixture().use { fixture ->
      val a = fixture.open("stream-a")
      val b = fixture.open("stream-b")
      assertEquals("first", a.item())
      assertEquals("first", b.item())
      assertEquals(1, fixture.physicalSockets.get())
      assertEquals(2, fixture.opened.size)

      a.socket.cancel()
      assertEquals(fixture.id("stream-a"), fixture.cancelled())
      assertNull(withTimeout(5_000) { a.closed.await() })
      fixture.item("stream-b", "after-cancel")
      assertEquals("after-cancel", b.item())
      assertFalse(b.closed.isCompleted)

      fixture.peer.send(JSONObject().put("type", "error").put("streamId", fixture.id("stream-b"))
        .put("error", JSONObject().put("code", "stream/typed-failure")
          .put("message", "typed failure").put("details", JSONObject().put("attempt", 3))).toString())
      val typed = withTimeout(5_000) { b.closed.await() } as ApiException
      assertEquals("stream/typed-failure", typed.code)
      assertEquals(3, typed.details?.getInt("attempt"))
    }
  }

  @Test fun cancellingEveryStreamThenReopeningRetainsApiPeerSocket() = runBlocking {
    // The fixture refuses a second connection with this API-Peer cookie, like the real Host.
    MuxFixture(refuseReconnect = true).use { fixture ->
      val a = fixture.open("stream-a")
      val b = fixture.open("stream-b")
      a.item(); b.item()
      a.socket.cancel(); b.socket.cancel()
      val cancelled = setOf(fixture.cancelled(), fixture.cancelled())
      assertEquals(setOf(fixture.id("stream-a"), fixture.id("stream-b")), cancelled)
      assertNull(withTimeout(5_000) { a.closed.await() })
      assertNull(withTimeout(5_000) { b.closed.await() })

      val c = fixture.open("stream-c")
      assertEquals("first", c.item())
      fixture.item("stream-c", "still-connected")
      assertEquals("still-connected", c.item())
      assertEquals(1, fixture.muxRequests.get())
      assertEquals(1, fixture.physicalSockets.get())
      assertEquals(1, fixture.cookieExchanges.get())
    }
  }

  @Test fun logicalEndThenReopeningRetainsPhysicalSocket() = runBlocking {
    MuxFixture(refuseReconnect = true).use { fixture ->
      val a = fixture.open("stream-a")
      a.item()
      fixture.peer.send(JSONObject().put("type", "end").put("streamId", fixture.id("stream-a")).toString())
      assertNull(withTimeout(5_000) { a.closed.await() })
      assertEquals("first", fixture.open("stream-b").item())
      assertEquals(1, fixture.muxRequests.get())
    }
  }

  @Test fun activePhysicalClosingSettlesEveryStream() = runBlocking {
    MuxFixture().use { fixture ->
      val a = fixture.open("stream-a")
      val b = fixture.open("stream-b")
      a.item(); b.item()
      fixture.peer.close(1000, "host shutdown")
      assertTrue(withTimeout(5_000) { a.closed.await() } is IOException)
      assertTrue(withTimeout(5_000) { b.closed.await() } is IOException)
    }
  }

  @Test fun onClosedWithoutOnClosingSettlesEveryStream() = runBlocking {
    MuxFixture().use { fixture ->
      val a = fixture.open("stream-a")
      val b = fixture.open("stream-b")
      a.item(); b.item()
      val physical = fixture.clientSocket()
      fixture.listener(physical).onClosed(physical, 1000, "closed directly")
      assertTrue(withTimeout(5_000) { a.closed.await() } is IOException)
      assertTrue(withTimeout(5_000) { b.closed.await() } is IOException)
    }
  }

  @Test fun retiredCallbacksCannotCloseReplacementOrRejectItsCookie() = runBlocking {
    MuxFixture().use { fixture ->
      val a = fixture.open("stream-a")
      a.item()
      val old = fixture.clientSocket()
      val listener = fixture.listener(old)
      val failure = IOException("first physical failure")
      listener.onFailure(old, failure, null)
      assertSame(failure, withTimeout(5_000) { a.closed.await() })

      val b = fixture.open("stream-b")
      assertEquals("first", b.item())
      val replacement = fixture.clientSocket()
      listener.onFailure(old, IOException("late failure"), fixture.response(old, 401))
      listener.onClosing(old, 1000, "late closing")
      listener.onClosed(old, 1000, "late closed")
      listener.onMessage(old, "not JSON")
      listener.onMessage(old, JSONObject().put("type", "end").put("streamId", fixture.id("stream-b")).toString())
      listener.onOpen(old, fixture.response(old, 101))
      fixture.item("stream-b", "replacement-survives")
      assertEquals("replacement-survives", b.item())
      assertSame(replacement, fixture.clientSocket())
      assertFalse(b.closed.isCompleted)
      val cookie = DshApi::class.java.getDeclaredField("sessionCookie").apply { isAccessible = true }
        .get(fixture.api)
      assertNotNull(cookie)
      assertEquals(2, fixture.physicalSockets.get())
    }
  }

  @Test fun firstHandshakeFailureDoesNotPoisonNextAttempt() = runBlocking {
    MuxFixture(firstMuxFailure = true).use { fixture ->
      val a = fixture.open("stream-a")
      assertNotNull(withTimeout(5_000) { a.closed.await() })
      val b = fixture.open("stream-b")
      assertEquals("first", b.item())
      fixture.item("stream-b", "after-retry")
      assertEquals("after-retry", b.item())
      assertFalse(b.closed.isCompleted)
      assertEquals(2, fixture.muxRequests.get())
      assertEquals(1, fixture.physicalSockets.get())
    }
  }

  @Test fun closeDuringCookieExchangeNeverStartsPhysicalSocket() = runBlocking {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    MuxFixture(cookieEntered = entered, cookieRelease = release).use { fixture ->
      val opening = async(Dispatchers.IO) { runCatching { fixture.open("stream-a") } }
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        fixture.api.close()
      } finally {
        release.countDown()
      }
      withTimeout(5_000) { opening.await() }
      assertEquals(0, fixture.muxRequests.get())
    }
  }

  @Test fun malformedItemCancelsOnlyItsServerProducer() = runBlocking {
    MuxFixture().use { fixture ->
      val a = fixture.open("stream-a")
      val b = fixture.open("stream-b")
      a.item(); b.item()
      fixture.peer.send(JSONObject().put("type", "item").put("streamId", fixture.id("stream-a"))
        .put("value", "not an object").toString())
      assertEquals(fixture.id("stream-a"), fixture.cancelled())
      assertTrue(withTimeout(5_000) { a.closed.await() } is IOException)
      fixture.item("stream-b", "unaffected")
      assertEquals("unaffected", b.item())
      assertFalse(b.closed.isCompleted)
      assertEquals(1, fixture.physicalSockets.get())
    }
  }

  @Test fun callbackFailureCancelsOnlyItsServerProducerAndPreservesTypedError() = runBlocking {
    MuxFixture().use { fixture ->
      val failure = ApiException(502, "parser failure", "stream/parser", JSONObject().put("offset", 9))
      val a = fixture.open("stream-a") { throw failure }
      val b = fixture.open("stream-b")
      b.item()
      assertEquals(fixture.id("stream-a"), fixture.cancelled())
      assertSame(failure, withTimeout(5_000) { a.closed.await() })
      fixture.item("stream-b", "unaffected")
      assertEquals("unaffected", b.item())
      assertFalse(b.closed.isCompleted)
      assertEquals(1, fixture.physicalSockets.get())
    }
  }

  @Test fun queueOverflowCancelsOnlyItsServerProducer() = runBlocking {
    MuxFixture().use { fixture ->
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val a = fixture.open("stream-a") {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "consumer was not released" }
      }
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val b = fixture.open("stream-b")
        b.item()
        repeat(257) { fixture.item("stream-a", "queued-$it") }
        assertEquals(fixture.id("stream-a"), fixture.cancelled())
        fixture.item("stream-b", "unaffected")
        assertEquals("unaffected", b.item())
        assertFalse(b.closed.isCompleted)
        assertEquals(1, fixture.physicalSockets.get())
      } finally {
        release.countDown()
      }
      val failure = withTimeout(5_000) { a.closed.await() }
      assertTrue(failure is IOException)
      assertTrue(failure?.message.orEmpty().contains("消费者过慢"))
    }
  }

  private class LogicalStream(val socket: WebSocket, val items: Channel<JSONObject>,
    val closed: CompletableDeferred<Throwable?>) {
    suspend fun item(): String = withTimeout(5_000) { items.receive().getString("value") }
  }

  private class MuxFixture(
    private val refuseReconnect: Boolean = false,
    private val firstMuxFailure: Boolean = false,
    private val cookieEntered: CountDownLatch? = null,
    private val cookieRelease: CountDownLatch? = null
  ) : Closeable {
    val server = MockWebServer()
    val physicalSockets = AtomicInteger()
    val muxRequests = AtomicInteger()
    val cookieExchanges = AtomicInteger()
    val opened = ConcurrentHashMap<String, String>()
    val cancellations = Channel<String>(Channel.UNLIMITED)
    val uplinks = Channel<JSONObject>(Channel.UNLIMITED)
    @Volatile lateinit var peer: WebSocket
    val api: DshApi

    init {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> {
            cookieExchanges.incrementAndGet()
            cookieEntered?.countDown()
            check(cookieRelease?.await(5, TimeUnit.SECONDS) != false)
            MockResponse().setResponseCode(303)
              .addHeader("Set-Cookie", "dsh-auth-mux=fixture; Path=/")
              .addHeader("Location", "./")
          }
          request.path == "/api/remote.mux" -> {
            val attempt = muxRequests.incrementAndGet()
            if (firstMuxFailure && attempt == 1) MockResponse().setResponseCode(503)
            else if (refuseReconnect && attempt > 1) MockResponse().setResponseCode(409)
            else MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                peer = webSocket
                physicalSockets.incrementAndGet()
              }

              override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                when (frame.str("type")) {
                  "open" -> {
                    val id = frame.str("streamId")
                    opened[frame.str("endpoint")] = id
                    webSocket.send(itemFrame(id, "first"))
                  }
                  "cancel" -> cancellations.trySend(frame.str("streamId"))
                  "item" -> uplinks.trySend(frame)
                }
              }
            })
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      api = DshApi(
        ServerProfile("mux", "DSH", server.url("/").toString(), kind = ServerKind.DSH, allowCleartext = true),
        ServerCredentials("dsh", "token")
      )
    }

    suspend fun open(endpoint: String, onItem: ((JSONObject) -> Unit)? = null): LogicalStream {
      val items = Channel<JSONObject>(Channel.UNLIMITED)
      val closed = CompletableDeferred<Throwable?>()
      val socket = api.openMuxForTest(endpoint, onItem = { value ->
        if (onItem == null) items.trySend(value) else onItem(value)
      }, onClosed = { closed.complete(it) })
      return LogicalStream(socket, items, closed)
    }

    fun id(endpoint: String): String = requireNotNull(opened[endpoint])
    fun item(endpoint: String, value: String) { check(peer.send(itemFrame(id(endpoint), value))) }
    suspend fun cancelled(): String = withTimeout(5_000) { cancellations.receive() }

    // Capture real listeners to deterministically replay delayed OkHttp callbacks after replacement.
    fun clientSocket(): WebSocket {
      val owner = DshApi::class.java.getDeclaredField("muxOwner").apply { isAccessible = true }.get(api)
      return owner.javaClass.getDeclaredField("physical").apply { isAccessible = true }.get(owner) as WebSocket
    }

    fun listener(socket: WebSocket): WebSocketListener = socket.javaClass.declaredFields
      .first { WebSocketListener::class.java.isAssignableFrom(it.type) }
      .apply { isAccessible = true }.get(socket) as WebSocketListener

    fun response(socket: WebSocket, code: Int): Response = Response.Builder().request(socket.request())
      .protocol(Protocol.HTTP_1_1).code(code).message("fixture").build()

    override fun close() {
      cookieRelease?.countDown()
      api.close()
      server.shutdown()
    }

    private fun itemFrame(id: String, value: String): String = JSONObject().put("type", "item")
      .put("streamId", id).put("value", JSONObject().put("value", value)).toString()
  }
}
