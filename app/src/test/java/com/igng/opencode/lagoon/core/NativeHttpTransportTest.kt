package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NativeHttpTransportTest {
  @Test
  fun relativeRoutesStayUnderTheFixedOriginAndPrefix() {
    val endpoint = NativeHttpEndpoint("https://example.test:8443/opencode/")

    assertEquals(
      "https://example.test:8443/opencode/api/event?directory=%2Fworkspace",
      endpoint.resolve("/api/event?directory=%2Fworkspace")
    )
    endpoint.requireSameOrigin("https://EXAMPLE.test:8443/another-path")
    endpoint.requireNoRedirect(
      "https://example.test:8443/opencode/api/info",
      "https://example.test:8443/opencode/api/info"
    )
  }

  @Test
  fun absoluteAuthorityTraversalAndCrossOriginRoutesAreRejected() {
    val endpoint = NativeHttpEndpoint("https://example.test/opencode")

    listOf(
      "https://evil.test/api/info",
      "//evil.test/api/info",
      "/../api/info",
      "/%2e%2e/api/info",
      "/%2Fescape/api/info",
      "/api/info#fragment"
    ).forEach { route ->
      assertTrue("route should be rejected: $route", runCatching { endpoint.resolve(route) }.isFailure)
    }
    assertTrue(
      runCatching {
        endpoint.requireSameOrigin("http://example.test/opencode/api/info")
      }.isFailure
    )
    assertTrue(
      runCatching {
        endpoint.requireNoRedirect(
          "https://example.test/opencode/api/info",
          "https://example.test/opencode/api/other"
        )
      }.isFailure
    )
  }

  @Test
  fun responseBudgetIsEnforcedBeforeAResponseIsPublished() = runBlocking {
    val transport = FakeNativeHttpTransport()

    val small = transport.request(
      method = "get",
      relativePath = "/api/info",
      maxResponseBytes = 3
    )
    assertEquals("ok", small.body.toString(Charsets.UTF_8))
    assertEquals("GET", transport.lastMethod)

    val oversized = runCatching {
      transport.request(
        method = "GET",
        relativePath = "/api/info",
        maxResponseBytes = 3,
        response = "toolong".toByteArray()
      )
    }.exceptionOrNull()
    assertTrue(oversized is NativeHttpResponseTooLargeException)
    assertTrue(runCatching { transport.request("GET", "/api/info", maxResponseBytes = 0) }.isFailure)
  }

  @Test
  fun cancellingRequestCancelsTheFakeUnderlyingOperation() = runBlocking {
    val transport = FakeNativeHttpTransport()
    val request = launch {
      transport.request("GET", "/api/info", maxResponseBytes = 32, waitForResponse = true)
    }
    withTimeout(1_000) { transport.requestStarted.await() }

    request.cancel()
    request.join()

    assertEquals(1, transport.cancelledRequests.get())
  }

  @Test
  fun generationCancellationStopsOldSseAndWebSocketCallbacksOnly() {
    val transport = FakeNativeHttpTransport()
    val events = mutableListOf<String>()
    val texts = mutableListOf<String>()
    val stream = transport.openEventStream(
      "/api/event",
      generation = 7,
      callback = object : NativeHttpEventStreamCallback {
        override fun onEvent(event: NativeHttpEvent) { events += event.data }
      }
    )
    val socket = transport.openWebSocket(
      "/api/mux",
      generation = 7,
      listener = object : NativeWebSocketListener {
        override fun onText(text: String) { texts += text }
      }
    )
    transport.emitEvent(7, "before")
    transport.emitText(7, "before")
    transport.cancelGeneration(7)
    transport.emitEvent(7, "after")
    transport.emitText(7, "after")

    assertEquals(listOf("before"), events)
    assertEquals(listOf("before"), texts)
    // The public carrier intentionally exposes cancellation only through callback suppression.
    // The two "after" emissions above did not reach the listeners.

    transport.openEventStream(
      "/api/event",
      generation = 8,
      callback = object : NativeHttpEventStreamCallback {
        override fun onEvent(event: NativeHttpEvent) { events += event.data }
      }
    )
    transport.emitEvent(8, "new-generation")
    assertEquals(listOf("before", "new-generation"), events)
  }

  private class FakeNativeHttpTransport : NativeHttpTransport {
    override val endpoint = NativeHttpEndpoint("https://example.test/opencode")
    val requestStarted = CompletableDeferred<Unit>()
    val cancelledRequests = AtomicInteger()
    var lastMethod: String? = null
    private val streams = mutableMapOf<Long, MutableList<FakeStream>>()
    private val sockets = mutableMapOf<Long, MutableList<FakeSocket>>()

    override suspend fun request(
      method: String,
      relativePath: String,
      headers: Map<String, String>,
      body: ByteArray?,
      maxResponseBytes: Long,
      generation: Long
    ): NativeHttpResponse = request(
      method, relativePath, headers, body, maxResponseBytes, generation,
      response = "ok".toByteArray(), waitForResponse = false
    )

    suspend fun request(
      method: String,
      relativePath: String,
      headers: Map<String, String> = emptyMap(),
      body: ByteArray? = null,
      maxResponseBytes: Long = DEFAULT_NATIVE_HTTP_RESPONSE_BYTES,
      generation: Long = 0L,
      response: ByteArray = "ok".toByteArray(),
      waitForResponse: Boolean = false
    ): NativeHttpResponse {
      lastMethod = requireNativeHttpMethod(method)
      requireNativeHttpHeaders(headers)
      requireNativeHttpResponseBudget(maxResponseBytes)
      endpoint.resolve(relativePath)
      val checked = response.requireNativeHttpResponseBudget(maxResponseBytes)
      if (waitForResponse) {
        requestStarted.complete(Unit)
        return try {
          awaitCancellation()
        } finally {
          cancelledRequests.incrementAndGet()
        }
      }
      return NativeHttpResponse(200, emptyMap(), checked, generation)
    }

    override fun openEventStream(
      relativePath: String,
      headers: Map<String, String>,
      generation: Long,
      callback: NativeHttpEventStreamCallback
    ): NativeHttpEventStream {
      requireNativeHttpHeaders(headers)
      endpoint.resolve(relativePath)
      return FakeStream(generation, callback).also { streams.getOrPut(generation) { mutableListOf() } += it }
    }

    override fun openWebSocket(
      relativePath: String,
      headers: Map<String, String>,
      generation: Long,
      listener: NativeWebSocketListener
    ): NativeWebSocketChannel {
      requireNativeHttpHeaders(headers)
      endpoint.resolve(relativePath)
      return FakeSocket(generation, listener).also { sockets.getOrPut(generation) { mutableListOf() } += it }
    }

    override fun cancelGeneration(generation: Long) {
      if (generation == 0L) {
        streams.values.flatten().forEach(FakeStream::cancel)
        sockets.values.flatten().forEach(FakeSocket::cancel)
      } else {
        streams[generation].orEmpty().forEach(FakeStream::cancel)
        sockets[generation].orEmpty().forEach(FakeSocket::cancel)
      }
    }

    fun emitEvent(generation: Long, data: String) {
      streams[generation].orEmpty().forEach { stream ->
        if (!stream.cancelled) stream.callback.onEvent(NativeHttpEvent(data = data, generation = generation))
      }
    }

    fun emitText(generation: Long, text: String) {
      sockets[generation].orEmpty().forEach { socket -> if (!socket.cancelled) socket.listener.onText(text) }
    }

    override fun close() = cancelGeneration(0L)

    private class FakeStream(
      override val generation: Long,
      val callback: NativeHttpEventStreamCallback
    ) : NativeHttpEventStream {
      var cancelled = false
      val isCancelled: Boolean get() = cancelled
      override fun cancel() { cancelled = true }
    }

    private class FakeSocket(
      override val generation: Long,
      val listener: NativeWebSocketListener
    ) : NativeWebSocketChannel {
      var cancelled = false
      val isCancelled: Boolean get() = cancelled
      override fun sendText(text: String) = !cancelled
      override fun sendBytes(bytes: ByteArray) = !cancelled
      override fun close(code: Int, reason: String?) = !cancelled.also { cancelled = true }
      override fun cancel() { cancelled = true }
    }
  }
}
