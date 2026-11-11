package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeApiTransportTest {
  @Test fun injectedTransportReceivesTheRequestAndAuthWithoutOkHttp() = runBlocking {
    val transport = FakeTransport()
    val api = OpenCodeApi(
      ServerProfile("fake", "Fake", "https://example.test/opencode", "opencode"),
      ServerCredentials("opencode", "secret", "session=cookie"),
      transport = transport
    )

    assertEquals("2.2.6", api.health())
    assertEquals("GET", transport.method)
    assertEquals("api/info", transport.path)
    assertEquals("application/json", transport.headers["Accept"])
    assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", transport.headers["Authorization"])
    assertEquals("session=cookie", transport.headers["Cookie"])
    assertEquals(null, transport.body)
  }

  @Test fun injectedTransportEventsAreParsedAndCancelledWithTheFlow() = runBlocking {
    val transport = FakeTransport()
    val opened = mutableListOf<Boolean>()
    val api = OpenCodeApi(
      ServerProfile("fake", "Fake", "https://example.test/opencode", "opencode"),
      ServerCredentials(),
      transport = transport
    )

    val event = withTimeout(1_000) { api.events { opened += true }.first() }

    assertEquals(listOf(true), opened)
    assertEquals("evt_1", event.id)
    assertEquals("server.connected", event.type)
    assertEquals("/repo", event.directory)
    assertEquals(42L, event.created)
    assertTrue(transport.streamCancelled)
    assertEquals("api/event", transport.streamPath)
    assertEquals("text/event-stream", transport.streamHeaders["Accept"])
  }

  @Test fun stockOkHttpWebSocketRejectsAnAbsoluteOriginBeforeOpeningTheChannel() {
    NativeOkHttpTransport(NativeHttpEndpoint("https://example.test/opencode")).use { transport ->
      val error = runCatching {
        transport.openWebSocket("https://other.test/api/mux", emptyMap(), 0L, object : NativeWebSocketListener {
          override fun onText(text: String) = Unit
        })
      }.exceptionOrNull()
      assertTrue(error is IllegalArgumentException)
    }
  }

  private class FakeTransport : NativeHttpTransport {
    override val endpoint = NativeHttpEndpoint("https://example.test/opencode")
    var method: String? = null
    var path: String? = null
    var headers: Map<String, String> = emptyMap()
    var body: ByteArray? = null
    var streamPath: String? = null
    var streamHeaders: Map<String, String> = emptyMap()
    var streamCancelled = false

    override suspend fun request(
      method: String,
      relativePath: String,
      headers: Map<String, String>,
      body: ByteArray?,
      maxResponseBytes: Long,
      generation: Long
    ): NativeHttpResponse {
      this.method = method
      this.path = relativePath
      this.headers = headers
      this.body = body
      return NativeHttpResponse(200, mapOf("Content-Type" to "application/json"),
        "{\"version\":\"2.2.6\"}".toByteArray(), generation)
    }

    override fun openEventStream(
      relativePath: String,
      headers: Map<String, String>,
      generation: Long,
      callback: NativeHttpEventStreamCallback
    ): NativeHttpEventStream {
      streamPath = relativePath
      streamHeaders = headers
      callback.onOpen()
      callback.onEvent(NativeHttpEvent(
        id = "evt_1",
        data = "{\"id\":\"evt_1\",\"created\":42,\"type\":\"server.connected\",\"location\":{\"directory\":\"/repo\"},\"data\":{}}",
        generation = generation
      ))
      return object : NativeHttpEventStream {
        override val generation = generation
        override fun cancel() { streamCancelled = true }
      }
    }

    override fun openWebSocket(
      relativePath: String,
      headers: Map<String, String>,
      generation: Long,
      listener: NativeWebSocketListener
    ): NativeWebSocketChannel = error("not used")

    override fun cancelGeneration(generation: Long) = Unit
  }
}
