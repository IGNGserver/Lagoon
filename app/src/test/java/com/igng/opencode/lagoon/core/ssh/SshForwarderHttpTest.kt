package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.NativeHttpEndpoint
import com.igng.opencode.lagoon.core.NativeHttpEvent
import com.igng.opencode.lagoon.core.NativeHttpEventStreamCallback
import com.igng.opencode.lagoon.core.NativeOkHttpTransport
import com.igng.opencode.lagoon.core.NativeWebSocketChannel
import com.igng.opencode.lagoon.core.NativeWebSocketListener
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshForwarderHttpTest {
  @Test fun appOwnedSocketCarriesHttpKeepAliveEventsAndWebSocket() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
          "/one" -> MockResponse().setBody("one")
          "/two" -> MockResponse().setBody("two")
          "/events" -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: hello\n\n")
          "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("echo:$text") }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val forwarder = SshForwarder { input, output -> Relay(Socket("127.0.0.1", server.port), input, output) }
      val transport = NativeOkHttpTransport(NativeHttpEndpoint("http://127.0.0.1:${forwarder.port}"),
        callTimeoutSeconds = 5, streamIdleSeconds = 5, socketFactory = forwarder.socketFactory)
      try {
        assertEquals("one", transport.request("GET", "one").body.toString(Charsets.UTF_8))
        assertEquals("two", transport.request("GET", "two").body.toString(Charsets.UTF_8))
        val eventReceived = CountDownLatch(1)
        var eventData = ""
        val eventStream = transport.openEventStream("events", callback = object : NativeHttpEventStreamCallback {
          override fun onEvent(event: NativeHttpEvent) { eventData = event.data; eventReceived.countDown() }
        })
        try {
          assertTrue("SSE event should cross the authenticated connection", eventReceived.await(5, TimeUnit.SECONDS))
          assertEquals("hello", eventData)
        } finally { eventStream.cancel() }
        val messageReceived = CountDownLatch(1)
        var message = ""
        val socket = transport.openWebSocket("ws", listener = object : NativeWebSocketListener {
          override fun onOpen(channel: NativeWebSocketChannel) { channel.sendText("hello") }
          override fun onText(text: String) { message = text; messageReceived.countDown() }
        })
        try {
          assertTrue("WebSocket should cross the authenticated connection", messageReceived.await(5, TimeUnit.SECONDS))
          assertEquals("echo:hello", message)
        } finally { socket.cancel() }
        val requests = (0 until 4).map { server.takeRequest(5, TimeUnit.SECONDS) }
        assertTrue(requests.all { it != null })
        assertEquals(listOf("/one", "/two", "/events", "/ws"), requests.map { it!!.path })
        assertTrue("The admission token is never an HTTP header", requests.all { req ->
          req!!.headers.names().none { it.contains("lagoon", ignoreCase = true) }
        })
      } finally { transport.close(); forwarder.close() }
    }
  }

  private class Relay(private val socket: Socket, private val input: InputStream, private val output: OutputStream) : TunnelConnection {
    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    override val isOpen: Boolean get() = !closed.get()
    init {
      pump(input, socket.getOutputStream())
      pump(socket.getInputStream(), output)
    }
    private fun pump(source: InputStream, sink: OutputStream) {
      Thread({ try { source.copyTo(sink) } catch (_: Exception) { } finally { close() } },
        "lagoon-gate-http-relay").apply { isDaemon = true; start() }
    }
    override fun onClosed(listener: () -> Unit) { listeners += listener; if (closed.get()) listener() }
    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      runCatching { socket.close() }; runCatching { input.close() }; runCatching { output.close() }
      listeners.forEach { it() }
    }
  }
}
