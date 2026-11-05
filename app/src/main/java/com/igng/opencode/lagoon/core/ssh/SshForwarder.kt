package com.igng.opencode.lagoon.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** Opens one direct-tcpip channel to the current remote `dsh web`; may wait for an SSH reconnect. */
internal fun interface SshTunnelOpener {
  fun open(input: InputStream, output: OutputStream): TunnelConnection
}

/**
 * A local `127.0.0.1:<ephemeral>` listener piped to the remote `dsh web`. The listener and its port
 * outlive individual SSH connections: when SSH drops, open socket/channel pairs are destroyed, but
 * new sockets wait for [opener] until the link has reconnected. That keeps DshApi's base URL stable.
 */
internal class SshForwarder(private val opener: SshTunnelOpener) : Closeable {
  private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val pairs = ConcurrentHashMap.newKeySet<Pair>()
  private val pending = ConcurrentHashMap.newKeySet<Socket>()
  @Volatile private var closed = false
  private val acceptJob: Job

  init {
    server.reuseAddress = true
    acceptJob = scope.launch { acceptLoop() }
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
      pending += socket
      scope.launch { connect(socket) }
    }
  }

  private fun connect(socket: Socket) {
    try {
      socket.tcpNoDelay = true
      val pair = opener.open(socket.getInputStream(), socket.getOutputStream())
      if (closed || !pending.remove(socket)) {
        runCatching { pair.close() }
        runCatching { socket.close() }
        return
      }
      val entry = Pair(socket, pair, onDone = { pairs.remove(it) })
      pairs += entry
      entry.start()
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
    acceptJob.cancel()
    scope.cancel()
  }

  private class Pair(
    private val socket: Socket,
    private val channel: TunnelConnection,
    private val onDone: (Pair) -> Unit
  ) {
    @Volatile private var done = false

    /** JSch's direct-tcpip channel pumps both directions itself; poll only its connection state. */
    fun start() {
      Thread({
        try {
          // JSch marks a direct-tcpip channel connected after the channel-open confirmation, which
          // can lag the connect() return. Do not treat the first few milliseconds as a close.
          Thread.sleep(3_000)
          while (!done && channel.isOpen) Thread.sleep(2_000)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
        } finally {
          close()
        }
      }, "lagoon-ssh-tunnel-${socket.port}").apply {
        isDaemon = true
        start()
      }
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
