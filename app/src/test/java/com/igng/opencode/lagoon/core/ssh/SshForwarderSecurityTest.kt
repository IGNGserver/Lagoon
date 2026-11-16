package com.igng.opencode.lagoon.core.ssh

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshForwarderSecurityTest {
  @Test fun unauthenticatedLocalSocketNeverOpensRemoteChannel() {
    val opens = AtomicInteger()
    val forwarder = SshForwarder { input, output -> opens.incrementAndGet(); EchoTunnel(input, output) }
    try {
      Socket("127.0.0.1", forwarder.port).use { socket ->
        socket.soTimeout = 2_000
        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        val rejected = try { socket.getInputStream().read() < 0 } catch (_: SocketException) { true }
        assertTrue("An uncredentialed local app must be rejected", rejected)
      }
      assertEquals(0, opens.get())
    } finally { forwarder.close() }
  }

  @Test fun appOwnedSocketConsumesPrefixOnceAndCanReuseTheConnection() {
    val opens = AtomicInteger()
    val forwarder = SshForwarder { input, output -> opens.incrementAndGet(); EchoTunnel(input, output) }
    try {
      forwarder.socketFactory.createSocket("127.0.0.1", forwarder.port).use { socket ->
        socket.soTimeout = 2_000
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        for (message in listOf("first", "second")) {
          output.write(message.toByteArray()); output.flush()
          val bytes = ByteArray(message.length)
          var offset = 0
          while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            assertTrue(count > 0)
            offset += count
          }
          assertEquals(message, bytes.toString(Charsets.UTF_8))
        }
      }
      assertEquals("Keep-alive traffic must use one authenticated SSH channel", 1, opens.get())
    } finally { forwarder.close() }
  }

  @Test fun socketTimeoutAndAbsoluteAdmissionDeadlineAreEnforced() {
    val opens = AtomicInteger()
    val forwarder = SshForwarder(admissionTimeoutMillis = 150) { input, output ->
      opens.incrementAndGet(); EchoTunnel(input, output)
    }
    try {
      Socket("127.0.0.1", forwarder.port).use { socket ->
        socket.soTimeout = 2_000
        socket.getOutputStream().write(1); socket.getOutputStream().flush()
        TimeUnit.MILLISECONDS.sleep(250)
        val rejected = try { socket.getInputStream().read() < 0 } catch (_: SocketException) { true }
        assertTrue(rejected)
      }
      assertEquals(0, opens.get())
      forwarder.socketFactory.createSocket("127.0.0.1", forwarder.port).use { socket ->
        socket.soTimeout = 100
        val timedOut = try { socket.getInputStream().read(); false } catch (_: SocketTimeoutException) { true }
        assertTrue("The wrapper must delegate SO_TIMEOUT", timedOut)
      }
    } finally { forwarder.close() }
  }

  @Test fun appOwnedSocketCannotConnectToAnotherPort() {
    val forwarder = SshForwarder { input, output -> EchoTunnel(input, output) }
    try {
      val wrongPort = if (forwarder.port == 65535) forwarder.port - 1 else forwarder.port + 1
      assertFalse(runCatching { forwarder.socketFactory.createSocket("127.0.0.1", wrongPort).close() }.isSuccess)
    } finally { forwarder.close() }
  }

  private class EchoTunnel(private val input: InputStream, private val output: OutputStream) : TunnelConnection {
    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    override val isOpen: Boolean get() = !closed.get()
    init {
      Thread({
        try {
          val buffer = ByteArray(1024)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count); output.flush()
          }
        } catch (_: Exception) { } finally { close() }
      }, "lagoon-gate-test-echo").apply { isDaemon = true; start() }
    }
    override fun onClosed(listener: () -> Unit) {
      listeners += listener
      if (closed.get()) listener()
    }
    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      runCatching { input.close() }; runCatching { output.close() }
      listeners.forEach { it() }
    }
  }
}
