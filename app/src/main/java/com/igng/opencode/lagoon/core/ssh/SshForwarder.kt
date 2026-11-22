package com.igng.opencode.lagoon.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.net.SocketFactory

/** Opens one direct-tcpip channel to the current remote `dsh web`; may wait for an SSH reconnect. */
internal fun interface SshTunnelOpener {
  fun open(input: InputStream, output: OutputStream): TunnelConnection
}

/**
 * A local `127.0.0.1:<ephemeral>` listener piped to the remote `dsh web`. The listener and its port
 * outlive individual SSH connections: when SSH drops, open socket/channel pairs are destroyed, but
 * new sockets wait for [opener] until the link has reconnected. That keeps DshApi's base URL stable.
 */
internal class SshForwarder(
  private val admissionTimeoutMillis: Int = 5_000,
  private val opener: SshTunnelOpener
) : Closeable {
  init { require(admissionTimeoutMillis in 1..30_000) }
  private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
  // Android's TCP loopback is reachable by other apps. Authenticate the connection before opening
  // an SSH channel; this secret never becomes an HTTP header, URL, log entry, or remote byte.
  private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
  val socketFactory: SocketFactory = ForwarderSocketFactory(server.localPort, token)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val pairs = ConcurrentHashMap.newKeySet<Pair>()
  private val pending = ConcurrentHashMap.newKeySet<Socket>()
  private val pendingAdmissions = Semaphore(MAX_PENDING_ADMISSIONS)
  private val reapSignal = Channel<Unit>(Channel.CONFLATED)
  @Volatile private var closed = false
  private val acceptJob: Job

  init {
    server.reuseAddress = true
    acceptJob = scope.launch { acceptLoop() }
    scope.launch { reapLoop() }
  }

  private suspend fun reapLoop() {
    while (scope.isActive && !closed) {
      if (pairs.isEmpty()) {
        reapSignal.receive()
        continue
      }
      delay(1_000)
      pairs.toList().forEach { pair -> if (!pair.isOpen) pair.close() }
    }
  }

  val port: Int get() = server.localPort
  val activeConnections: Int get() = pairs.size

  private suspend fun acceptLoop() {
    while (scope.isActive && !closed) {
      val socket = try {
        server.accept()
      } catch (_: Exception) {
        if (closed) return
        continue
      }
      if (closed) {
        runCatching { socket.close() }
        return
      }
      if (!pendingAdmissions.tryAcquire()) {
        runCatching { socket.close() }
        continue
      }
      pending += socket
      // A close can race acceptance before launch. ATOMIC guarantees the socket is closed and the
      // admission permit released even if the scope is cancelled during that interval.
      scope.launch(start = CoroutineStart.ATOMIC) {
        try { connect(socket) } finally { pendingAdmissions.release() }
      }
    }
  }

  private fun connect(socket: Socket) {
    try {
      socket.tcpNoDelay = true
      val presented = ByteArray(token.size)
      var offset = 0
      val input = socket.getInputStream()
      val deadline = System.nanoTime() + admissionTimeoutMillis * 1_000_000L
      while (offset < presented.size) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw java.net.SocketTimeoutException("SSH forwarder admission timed out")
        socket.soTimeout = ((remaining + 999_999L) / 1_000_000L).coerceAtMost(admissionTimeoutMillis.toLong()).toInt()
        val count = input.read(presented, offset, presented.size - offset)
        if (count < 0) throw java.io.EOFException("SSH forwarder admission ended")
        offset += count
      }
      if (closed || !MessageDigest.isEqual(token, presented)) throw java.io.IOException("SSH forwarder admission rejected")
      socket.soTimeout = 0
      val pair = opener.open(input, socket.getOutputStream())
      if (closed || !pending.remove(socket)) {
        runCatching { pair.close() }
        runCatching { socket.close() }
        return
      }
      val entry = Pair(socket, pair, onDone = { pairs.remove(it) })
      pairs += entry
      entry.start()
      reapSignal.trySend(Unit)
    } catch (_: Exception) {
      pending.remove(socket)
      runCatching { socket.close() }
    }
  }

  /** Drops every open connection so DSH notices the tunnel died instead of hanging on a write. */
  fun dropConnections() {
    pending.forEach { runCatching { it.close() } }
    pending.clear()
    pairs.toList().forEach { it.close() }
    pairs.clear()
  }

  override fun close() {
    if (closed) return
    closed = true
    runCatching { server.close() }
    dropConnections()
    reapSignal.close()
    acceptJob.cancel()
    scope.cancel()
    token.fill(0)
  }

  private companion object {
    const val MAX_PENDING_ADMISSIONS = 32
  }

  private class Pair(
    private val socket: Socket,
    private val channel: TunnelConnection,
    private val onDone: (Pair) -> Unit
  ) {
    @Volatile private var done = false
    val isOpen: Boolean get() = !done && channel.isOpen

    /** JSch pumps both directions. Close the local socket immediately on [TunnelConnection.onClosed],
     *  backed by the forwarder's single shared reaper coroutine for channels that do not emit it. */
    fun start() {
      channel.onClosed { close() }
      if (!channel.isOpen) close()
    }

    fun close() {
      if (done) return
      done = true
      runCatching { channel.close() }
      runCatching { socket.close() }
      onDone(this)
    }
  }
}

/**
 * OkHttp connects normally to loopback, but this app-owned Socket writes the random admission
 * prefix before the first HTTP/1.1 byte. Connection reuse, SSE, and WebSocket all stay on that
 * authenticated socket and the forwarder consumes the prefix without sending it to the host.
 */
private class ForwarderSocketFactory(private val port: Int, private val token: ByteArray) : SocketFactory() {
  override fun createSocket(): Socket = ForwarderSocket(Socket(), port, token)
  override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
  override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
    createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
  override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
  override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
    createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
}

private class ForwarderSocket(private val delegate: Socket, private val allowedPort: Int, private val token: ByteArray) : Socket() {
  override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
  override fun connect(endpoint: SocketAddress?, timeout: Int) {
    val address = endpoint as? InetSocketAddress ?: throw java.io.IOException("Invalid SSH forwarder endpoint")
    if (address.port != allowedPort || address.address != InetAddress.getByName("127.0.0.1")) {
      throw java.io.IOException("SSH forwarder endpoint mismatch")
    }
    try {
      delegate.connect(endpoint, timeout)
      delegate.getOutputStream().write(token)
      delegate.getOutputStream().flush()
    } catch (error: Exception) {
      runCatching { delegate.close() }
      throw error
    }
  }
  override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
  override fun getInputStream(): InputStream = delegate.getInputStream()
  override fun getOutputStream(): OutputStream = delegate.getOutputStream()
  override fun close() = delegate.close()
  override fun isClosed(): Boolean = delegate.isClosed
  override fun isConnected(): Boolean = delegate.isConnected
  override fun isBound(): Boolean = delegate.isBound
  override fun isInputShutdown(): Boolean = delegate.isInputShutdown
  override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
  override fun shutdownInput() = delegate.shutdownInput()
  override fun shutdownOutput() = delegate.shutdownOutput()
  override fun getInetAddress(): InetAddress? = delegate.inetAddress
  override fun getLocalAddress(): InetAddress = delegate.localAddress
  override fun getPort(): Int = delegate.port
  override fun getLocalPort(): Int = delegate.localPort
  override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
  override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
  override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
  override fun getSoTimeout(): Int = delegate.soTimeout
  override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
  override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
  override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
  override fun getKeepAlive(): Boolean = delegate.keepAlive
}
