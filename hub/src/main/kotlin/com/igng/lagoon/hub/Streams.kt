package com.igng.lagoon.hub

import com.sun.net.httpserver.HttpExchange
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One browser tab's `text/event-stream`. Frames are queued by the runtime and written by the request
 * thread; a tab that stops reading (the queue overflows) is disconnected and resyncs on reconnect,
 * instead of the hub buffering for it without bound.
 */
class EventStream(val id: String, val loginHash: String) {
  private val queue = LinkedBlockingQueue<String>(MAX_QUEUED)
  @Volatile var closed = false
    private set

  /** Conversations this tab has open, as `serverId` to `sessionId`; owned by the runtime thread. */
  var watched: Set<Pair<String, String>> = emptySet()
  /** The tab reported itself visible; its open conversations then count as read. */
  @Volatile var visible = false

  fun send(event: String, data: String) {
    if (closed) return
    if (!queue.offer("event: $event\ndata: $data\n\n")) close()
  }

  fun send(event: String, data: JSONObject) = send(event, data.toString())

  fun close() {
    closed = true
    queue.offer(CLOSE)
  }

  /** Writes frames until the client goes away or [close] is called. Runs on the request thread. */
  fun pump(exchange: HttpExchange) {
    val headers = exchange.responseHeaders
    headers.set("Content-Type", "text/event-stream; charset=utf-8")
    headers.set("Cache-Control", "no-store")
    headers.set("X-Accel-Buffering", "no")
    exchange.sendResponseHeaders(200, 0)
    val out = exchange.responseBody
    try {
      // Tell EventSource how long to wait before reconnecting, then announce the stream id.
      out.write("retry: 3000\nevent: hello\ndata: ${JSONObject().put("streamId", id)}\n\n".toByteArray())
      out.flush()
      while (!closed) {
        val frame = queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS)
        if (frame == CLOSE) break
        out.write((frame ?: ": ping\n\n").toByteArray())
        // Drain whatever else is ready before flushing once.
        var more = queue.poll()
        while (more != null && more != CLOSE) { out.write(more.toByteArray()); more = queue.poll() }
        out.flush()
        if (more == CLOSE) break
      }
    } catch (_: IOException) {
      // The browser went away.
    } finally {
      closed = true
      runCatching { exchange.close() }
    }
  }

  companion object {
    private const val CLOSE = "\u0000close"
    const val MAX_QUEUED = 2_000
    const val HEARTBEAT_SECONDS = 25L
  }
}

class StreamRegistry {
  private val streams = ConcurrentHashMap<String, EventStream>()
  private val random = SecureRandom()

  fun open(loginHash: String): EventStream {
    val id = ByteArray(12).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    return EventStream(id, loginHash).also { streams[id] = it }
  }

  fun get(id: String): EventStream? = streams[id]?.takeUnless { it.closed }
  fun remove(stream: EventStream) { streams.remove(stream.id) }
  fun all(): List<EventStream> = streams.values.filterNot { it.closed }

  fun broadcast(event: String, data: String) = all().forEach { it.send(event, data) }

  /** Ends the streams of signed-out browsers ([loginHash] null: every browser). */
  fun closeLogins(loginHash: String?) = streams.values.filter { loginHash == null || it.loginHash == loginHash }.forEach(EventStream::close)
}
