package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Tests the concrete carrier, including the native operations hidden behind its logical handles. */
class NativeOkHttpTransportTest {
  @Test fun cancelledGenerationStopsInFlightHttpAndRejectsLaterWorkWithoutAffectingNewerGeneration() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = when (request.path) {
          "/pending" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
          else -> MockResponse().setBody("new-generation")
        }
      }
      transport(server).use { transport ->
        val old = async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { transport.request("GET", "pending", generation = 7) }
        }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        transport.cancelGeneration(7)
        val cancelled = withTimeout(3_000) { old.await() }.exceptionOrNull()
        assertTrue(cancelled is NativeHttpGenerationCancelledException)
        awaitNoCalls(server)

        val stale = runCatching { transport.request("GET", "stale", generation = 7) }.exceptionOrNull()
        assertTrue(stale is NativeHttpGenerationCancelledException)
        assertEquals("new-generation", transport.request("GET", "new", generation = 8).body.decodeToString())
        assertEquals("/new", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        assertEquals(2, server.requestCount)
      }
    }
  }

  @Test fun coroutineCancellationCancelsTheConcreteHttpCall() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
      transport(server).use { transport ->
        val request = async(start = CoroutineStart.UNDISPATCHED) { transport.request("GET", "pending") }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        request.cancel()
        withTimeout(3_000) { request.join() }
        awaitNoCalls(server)
      }
    }
  }

  @Test fun closeCancelsEveryHttpGenerationAndIsTerminal() = runBlocking {
    MockWebServer().use { server ->
      repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
      val transport = transport(server)
      try {
        val requests = listOf(0L, 1L, 2L).map { generation ->
          async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { transport.request("GET", "pending/$generation", generation = generation) }
          }
        }
        repeat(3) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        transport.close()
        transport.close()
        requests.forEach { assertTrue(withTimeout(3_000) { it.await() }.exceptionOrNull() is CancellationException) }
        awaitNoCalls(server)
        assertTrue(runCatching { transport.request("GET", "after-close") }.exceptionOrNull() is CancellationException)
        assertEquals(3, server.requestCount)
      } finally { transport.close() }
    }
  }

  @Test fun generationCancellationAlsoStopsHttpWhileReadingTheBody() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("abcdefgh").throttleBody(1, 200, TimeUnit.MILLISECONDS))
      transport(server).use { transport ->
        val bytesBefore = HttpMetrics.bytes()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { transport.request("GET", "body", generation = 3) }
        }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        // CountingBody advances only as the transport consumes bytes, not merely on headers.
        withTimeout(3_000) { while (HttpMetrics.bytes() == bytesBefore) delay(5) }
        transport.cancelGeneration(3)
        assertTrue(withTimeout(3_000) { request.await() }.exceptionOrNull() is NativeHttpGenerationCancelledException)
        awaitNoCalls(server)
      }
    }
  }

  @Test fun zeroGenerationCancellationStopsCurrentWorkButAllowsFutureRequests() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
      server.enqueue(MockResponse().setBody("ok"))
      transport(server).use { transport ->
        val request = async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { transport.request("GET", "pending", generation = 11) }
        }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        transport.cancelGeneration(0)
        assertTrue(withTimeout(3_000) { request.await() }.exceptionOrNull() is CancellationException)
        assertEquals("ok", transport.request("GET", "later", generation = 11).body.decodeToString())
      }
    }
  }

  @Test fun unaryAndSseReject302WithoutForwardingCredentials() = runBlocking {
    MockWebServer().use { target ->
      MockWebServer().use { origin ->
        repeat(2) { origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/steal"))) }
        transport(origin).use { transport ->
          val headers = mapOf("Authorization" to "Bearer private", "Cookie" to "session=private")
          val unary = runCatching { transport.request("GET", "unary", headers) }.exceptionOrNull()
          assertTrue(unary is NativeHttpRedirectException)
          val failure = CompletableDeferred<Throwable>()
          val opened = AtomicInteger()
          transport.openEventStream("events", headers, callback = object : NativeHttpEventStreamCallback {
            override fun onOpen() { opened.incrementAndGet() }
            override fun onEvent(event: NativeHttpEvent) { error("redirect body must not be delivered") }
            override fun onFailure(error: Throwable) { failure.complete(error) }
          }).use {
            assertTrue(withTimeout(3_000) { failure.await() } is NativeHttpRedirectException)
          }
          assertEquals(0, opened.get())
          repeat(2) {
            val request = origin.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("Bearer private", request?.getHeader("Authorization"))
            assertEquals("session=private", request?.getHeader("Cookie"))
          }
          assertEquals(0, target.requestCount)
        }
      }
    }
  }

  @Test fun responseByteBudgetChecksContentLengthAndChunkedBodiesAndAcceptsExactLimit() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("12345"))
      server.enqueue(MockResponse().setChunkedBody("12345", 1))
      server.enqueue(MockResponse().setChunkedBody("1234", 1))
      transport(server).use { transport ->
        repeat(2) { index ->
          val failure = runCatching { transport.request("GET", "limit/$index", maxResponseBytes = 4) }.exceptionOrNull()
          assertTrue("oversize response $index", failure is NativeHttpResponseTooLargeException)
        }
        assertEquals("1234", transport.request("GET", "limit/exact", maxResponseBytes = 4).body.decodeToString())
        val invalid = runCatching { transport.request("GET", "invalid", maxResponseBytes = 0) }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        assertEquals(3, server.requestCount)
      }
    }
  }

  @Test fun sessionIdPolicyKeepsEncodingOnceAndRejectsCatchBypassTraversalAndSuffixSlashes() = runBlocking {
    val endpoint = NativeHttpEndpoint("https://example.test/root")
    assertEquals("https://example.test/root/api/session/ses%201%2F2/message", endpoint.resolve("api/session/ses%201%2F2/message"))
    // An encoded literal '%' remains exactly as supplied; validation never rewrites the wire path.
    assertEquals("https://example.test/root/api/session/ses%252F2/message", endpoint.resolve("api/session/ses%252F2/message"))
    val rejected = listOf(
      "api/session/a%2Fb/../message", "api/session/a%2Fb/%2e%2e/message",
      "api/session/a%2Fb/%252e%252e/message", "api/session/a%2F..%2Fb/message",
      "api/session/a%252F%252e%252e%252Fb/message", "api/session/a%2Fb/message%2Fother",
      "api/session/a%2Fb/message%252Fother", "api/session/a%2Fb/%5cmessage",
      "api/session/a%2Fb/%255cmessage", "api/session/a%2Fb/%250amessage",
      "api/session/a%2Fb/message#fragment",
      "api/session/../message", "api/session/%252e%252e/message",
      "api/%73ession/a%2Fb/message", "api/session-other/a%2Fb", "api/info%2Fother",
      "https://evil.test/api/session/a%2Fb", "//evil.test/api/session/a%2Fb"
    )
    rejected.forEach { route ->
      assertTrue("must reject $route", runCatching { endpoint.resolve(route) }.exceptionOrNull() is IllegalArgumentException)
    }
    assertTrue(runCatching {
      NativeHttpEndpoint("https://example.test", NativeHttpPathPolicy.STRICT).resolve("api/session/a%2Fb/message")
    }.isFailure)
    assertTrue(runCatching { NativeHttpEndpoint("https://example.test/api/session/a%2Fb") }.isFailure)
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("ok"))
      transport(server, "/root/").use { transport ->
        transport.request("GET", "api/session/ses%201%2F2/message")
        assertEquals("/root/api/session/ses%201%2F2/message", server.takeRequest(3, TimeUnit.SECONDS)?.requestUrl?.encodedPath)
      }
    }
  }

  @Test fun headerNamesUseRfcTokensAndRejectSeparatorsControlsAndUnicodeBeforeIo() = runBlocking {
    requireNativeHttpHeaders(mapOf("!#$%&'*+-.^_`|~012AZaz" to "value"))
    listOf("Bad:Name", "Bad Name", "Bad(Name)", "Bad/Name", "Bad=Name", "Bad[Name]", "Bad\\Name", "Bad\u007f", "头", "").forEach { name ->
      assertTrue("must reject $name", runCatching { requireNativeHttpHeaders(mapOf(name to "value")) }.isFailure)
    }
    listOf("bad\rvalue", "bad\nvalue", "bad\u0000value").forEach { value ->
      assertTrue(runCatching { requireNativeHttpHeaders(mapOf("Good" to value)) }.isFailure)
    }
    MockWebServer().use { server ->
      transport(server).use { transport ->
        assertTrue(runCatching { transport.request("GET", "http", mapOf("Bad:Name" to "secret")) }.isFailure)
        assertTrue(runCatching {
          transport.openEventStream("events", mapOf("Bad:Name" to "secret"), callback = EventRecorder())
        }.isFailure)
        assertTrue(runCatching {
          transport.openWebSocket("ws", mapOf("Bad:Name" to "secret"), listener = SocketRecorder())
        }.isFailure)
        assertEquals(0, server.requestCount)
      }
    }
  }

  @Test fun webSocketExchangesTextAndBinaryThroughOneStableLogicalHandle() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().withWebSocketUpgrade(echoListener()))
      transport(server, "/prefix/").use { transport ->
        val recorder = SocketRecorder()
        val channel = transport.openWebSocket("mux?directory=%2Frepo", mapOf("Authorization" to "Bearer private"), 12, recorder)
        assertSame(channel, withTimeout(3_000) { recorder.opened.await() })
        assertEquals(12L, channel.generation)
        assertTrue(channel.sendText("hello"))
        val bytes = byteArrayOf(0, 1, 2, -1)
        assertTrue(channel.sendBytes(bytes))
        assertEquals("hello", recorder.texts.poll(3, TimeUnit.SECONDS))
        assertArrayEquals(bytes, recorder.bytes.poll(3, TimeUnit.SECONDS))
        val request = server.takeRequest(3, TimeUnit.SECONDS)
        assertEquals("/prefix/mux?directory=%2Frepo", request?.path)
        assertEquals("Bearer private", request?.getHeader("Authorization"))
        channel.cancel()
        channel.cancel()
        assertFalse(channel.sendText("after"))
        assertFalse(channel.sendBytes(bytes))
        assertFalse(channel.close(1000, "after"))
      }
    }
  }

  @Test fun webSocketGracefulCloseIsIdempotentAndStopsSending() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().withWebSocketUpgrade(echoListener()))
      transport(server).use { transport ->
        val recorder = SocketRecorder()
        val channel = transport.openWebSocket("mux", listener = recorder)
        withTimeout(3_000) { recorder.opened.await() }
        assertTrue(channel.close(1000, "done"))
        assertFalse(channel.close(1000, "again"))
        assertFalse(channel.sendText("after close frame"))
        assertEquals(1000, withTimeout(3_000) { recorder.closed.await() })
        channel.cancel()
        channel.cancel()
      }
    }
  }

  @Test fun webSocketRedirectAndAbsoluteOriginAreRejectedWithoutCredentialLeakage() = runBlocking {
    MockWebServer().use { target ->
      MockWebServer().use { origin ->
        origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/steal")))
        transport(origin).use { transport ->
          val recorder = SocketRecorder()
          val channel = transport.openWebSocket("mux", mapOf("Authorization" to "Bearer private", "Cookie" to "session=private"), listener = recorder)
          assertTrue(withTimeout(3_000) { recorder.failure.await() } is NativeHttpRedirectException)
          assertFalse(channel.sendText("failed"))
          assertFalse(recorder.opened.isCompleted)
          assertTrue(runCatching { transport.openWebSocket(target.url("/steal").toString(), listener = SocketRecorder()) }.isFailure)
          assertEquals("Bearer private", origin.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Authorization"))
          assertEquals(0, target.requestCount)
        }
      }
    }
  }

  @Test fun cancellingOneWebSocketGenerationSuppressesStaleCallbacksAndKeepsItsSiblingAlive() = runBlocking {
    MockWebServer().use { server ->
      val oldPeer = CompletableDeferred<WebSocket>()
      val newPeer = CompletableDeferred<WebSocket>()
      val oldPeerStopped = CompletableDeferred<Unit>()
      server.enqueue(MockResponse().withWebSocketUpgrade(echoListener(oldPeer, oldPeerStopped)))
      server.enqueue(MockResponse().withWebSocketUpgrade(echoListener(newPeer)))
      transport(server).use { transport ->
        val old = SocketRecorder()
        val next = SocketRecorder()
        val oldChannel = transport.openWebSocket("old", generation = 21, listener = old)
        withTimeout(3_000) { old.opened.await() }
        val nextChannel = transport.openWebSocket("new", generation = 22, listener = next)
        withTimeout(3_000) { next.opened.await() }
        transport.cancelGeneration(21)
        transport.cancelGeneration(21)
        withTimeout(3_000) { oldPeerStopped.await() }
        assertFalse(oldChannel.sendText("stale"))
        assertFalse(oldChannel.close(1000, "stale"))
        oldPeer.await().send("stale server frame")
        assertTrue(nextChannel.sendText("sibling alive"))
        assertEquals("sibling alive", next.texts.poll(3, TimeUnit.SECONDS))
        assertEquals(0, old.texts.size)
        assertEquals(0, old.bytes.size)
        assertFalse(old.failure.isCompleted)
        assertFalse(old.closed.isCompleted)
        val stale = SocketRecorder()
        val inactive = transport.openWebSocket("stale", generation = 21, listener = stale)
        assertFalse(inactive.sendText("never connected"))
        assertFalse(stale.opened.isCompleted)
        assertEquals(2, server.requestCount)
        nextChannel.cancel()
      }
    }
  }

  @Test fun closeCancelsSseAndWebSocketAndRejectsFutureHandles() = runBlocking {
    MockWebServer().use { server ->
      // Slow comment-only traffic keeps the body in flight without a timing-sensitive data event.
      server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream")
        .setBody(": keepalive\n\n".repeat(64)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
      val peerStopped = CompletableDeferred<Unit>()
      server.enqueue(MockResponse().withWebSocketUpgrade(echoListener(stopped = peerStopped)))
      val transport = transport(server)
      try {
        val events = EventRecorder()
        val stream = transport.openEventStream("events", generation = 31, callback = events)
        withTimeout(3_000) { events.opened.await() }
        val socket = SocketRecorder()
        transport.openWebSocket("mux", generation = 32, listener = socket)
        withTimeout(3_000) { socket.opened.await() }
        transport.close()
        transport.close()
        stream.cancel()
        withTimeout(3_000) { peerStopped.await() }
        awaitNoCalls(server)
        assertFalse(socket.failure.isCompleted)
        assertFalse(socket.closed.isCompleted)
        assertFalse(events.failure.isCompleted)
        assertFalse(events.closed.isCompleted)
        assertEquals(0, events.events.size)
        val laterSocket = SocketRecorder()
        val laterEvents = EventRecorder()
        assertFalse(transport.openWebSocket("later", listener = laterSocket).sendText("never connected"))
        transport.openEventStream("later", callback = laterEvents).cancel()
        assertFalse(laterSocket.opened.isCompleted)
        assertFalse(laterEvents.opened.isCompleted)
        assertEquals(2, server.requestCount)
      } finally { transport.close() }
    }
  }

  @Test fun sseGenerationCancellationFromItsCallbackSuppressesBufferedEventsAndKeepsNewGenerationAlive() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream")
        .setBody("data: first\n\ndata: buffered-stale\n\n"))
      server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody("data: next\n\n"))
      transport(server).use { transport ->
        val cancelled = CompletableDeferred<Unit>()
        val delivered = LinkedBlockingQueue<String>()
        val terminalCallbacks = AtomicInteger()
        val old = transport.openEventStream("old-events", generation = 41, callback = object : NativeHttpEventStreamCallback {
          override fun onEvent(event: NativeHttpEvent) {
            delivered.add(event.data)
            transport.cancelGeneration(41)
            cancelled.complete(Unit)
          }
          override fun onFailure(error: Throwable) { terminalCallbacks.incrementAndGet() }
          override fun onClosed() { terminalCallbacks.incrementAndGet() }
        })
        withTimeout(3_000) { cancelled.await() }
        val next = EventRecorder()
        transport.openEventStream("next-events", generation = 42, callback = next).use {
          assertEquals("next", next.events.poll(3, TimeUnit.SECONDS))
          withTimeout(3_000) { next.closed.await() }
        }
        awaitNoCalls(server)
        assertEquals(listOf("first"), delivered.toList())
        assertEquals(0, terminalCallbacks.get())
        old.cancel()
        old.cancel()
      }
    }
  }

  @Test fun closingTransportDoesNotCancelSiblingTransportOnSharedOkHttpDispatchers() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().withWebSocketUpgrade(echoListener()))
      server.enqueue(MockResponse().withWebSocketUpgrade(echoListener()))
      transport(server).use { first ->
        transport(server).use { sibling ->
          val firstEvents = SocketRecorder()
          val siblingEvents = SocketRecorder()
          val firstSocket = first.openWebSocket("first", listener = firstEvents)
          withTimeout(3_000) { firstEvents.opened.await() }
          val siblingSocket = sibling.openWebSocket("sibling", listener = siblingEvents)
          withTimeout(3_000) { siblingEvents.opened.await() }
          first.close()
          assertFalse(firstSocket.sendText("closed"))
          assertTrue(siblingSocket.sendText("still connected"))
          assertEquals("still connected", siblingEvents.texts.poll(3, TimeUnit.SECONDS))
          siblingSocket.cancel()
        }
      }
    }
  }

  private fun transport(server: MockWebServer, prefix: String = "/") =
    NativeOkHttpTransport(NativeHttpEndpoint(server.url(prefix).toString()), callTimeoutSeconds = 10)

  private suspend fun awaitNoCalls(server: MockWebServer) = withTimeout(3_000) {
    while ((SharedHttp.dispatcher.runningCalls() + SharedHttp.dispatcher.queuedCalls() +
        SharedHttp.streamDispatcher.runningCalls() + SharedHttp.streamDispatcher.queuedCalls())
        .any { it.request().url.port == server.port }) delay(5)
  }

  private fun echoListener(
    opened: CompletableDeferred<WebSocket>? = null,
    stopped: CompletableDeferred<Unit>? = null
  ) = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) { opened?.complete(webSocket) }
    override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send(text) }
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(bytes) }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { stopped?.complete(Unit) }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { stopped?.complete(Unit) }
  }

  private class SocketRecorder : NativeWebSocketListener {
    val opened = CompletableDeferred<NativeWebSocketChannel>()
    val texts = LinkedBlockingQueue<String>()
    val bytes = LinkedBlockingQueue<ByteArray>()
    val failure = CompletableDeferred<Throwable>()
    val closed = CompletableDeferred<Int>()
    override fun onOpen(channel: NativeWebSocketChannel) { opened.complete(channel) }
    override fun onText(text: String) { texts.add(text) }
    override fun onBytes(bytes: ByteArray) { this.bytes.add(bytes) }
    override fun onFailure(error: Throwable) { failure.complete(error) }
    override fun onClosed(code: Int, reason: String?) { closed.complete(code) }
  }

  private class EventRecorder : NativeHttpEventStreamCallback {
    val opened = CompletableDeferred<Unit>()
    val events = LinkedBlockingQueue<String>()
    val failure = CompletableDeferred<Throwable>()
    val closed = CompletableDeferred<Unit>()
    override fun onOpen() { opened.complete(Unit) }
    override fun onEvent(event: NativeHttpEvent) { events.add(event.data) }
    override fun onFailure(error: Throwable) { failure.complete(error) }
    override fun onClosed() { closed.complete(Unit) }
  }
}
