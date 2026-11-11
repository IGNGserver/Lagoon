package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.UUID

/** One fixed Remote peer and one short-lived terminal attachment. Never reconnects or replays. */
internal class RemoteTerminalSocket private constructor(
  private val gateway: RemoteGateway,
  private val agentId: String,
  private val terminalId: String,
  private val onItem: (JSONObject) -> Unit,
  private val onClosed: (Throwable?) -> Unit,
  private val onDispose: (RemoteTerminalSocket) -> Unit
) : WebSocket {
  private data class Input(val value: JSONObject, val sequence: Long, val bytes: Long)
  private val lock = Any()
  // Carrier callbacks may enter DshTerminalApi; never hold lock while invoking one.
  private val callbacks = Any()
  private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val queue = Channel<Input>(MAX_ITEMS)
  private val attachmentId = UUID.randomUUID().toString()
  private var stream: RemoteGatewayStream? = null
  private var closed = false
  private var notified = false
  private var ready = false
  private var writable = false
  private var refused = false
  private var sequence = 0L
  private var queuedBytes = 0L
  private var queuedItems = 0

  private val receiver = lifetime.launch(start = CoroutineStart.LAZY) {
    var failure: Throwable? = null
    try {
      val source = synchronized(lock) { stream } ?: invalid()
      while (true) {
        val value = source.receive() as? JSONObject ?: invalid()
        val translated = translate(value)
        emit(translated)
      }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: ClosedReceiveChannelException) { }
    catch (_: Exception) { failure = IOException("Remote terminal stream failed; input was not replayed") }
    finally { finish(failure) }
  }
  private val sender = lifetime.launch(start = CoroutineStart.LAZY) {
    try {
      for (input in queue) {
        val allowed = synchronized(lock) { if (closed) return@launch; writable && !refused }
        var refusal = !allowed
        if (allowed) {
          val args = attachmentArgs()
          val type = input.value.getString("type")
          if (type == "write") args.put("data", input.value.getString("data"))
          else args.put("cols", input.value.getInt("cols")).put("rows", input.value.getInt("rows"))
          try {
            val result = withTimeout(RPC_TIMEOUT_MS) {
              gateway.call("terminal/$type", JSONObject().put("args", args))
            }
            currentCoroutineContext().ensureActive()
            if (result != null) invalid()
          } catch (error: Exception) {
            if (!permissionDenied(error)) throw error
            refusal = true
          }
        }
        synchronized(lock) {
          if (closed) return@launch
          if (refusal) { writable = false; refused = true }
          queuedItems--
          queuedBytes -= input.bytes
        }
        val ack = JSONObject().put("type", "ack").put("sequence", input.sequence)
        if (refusal) ack.put("refusal", "read-only")
        emit(ack)
      }
    } catch (_: TimeoutCancellationException) {
      finish(IOException("Remote terminal input acknowledgement timed out; delivery is unknown"))
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { finish(IOException("Remote terminal input delivery is unknown; input was not replayed")) }
  }

  private fun attachmentArgs() = JSONObject().put("agentId", agentId).put("id", terminalId).put("attachmentId", attachmentId)

  private fun translate(value: JSONObject): JSONObject {
    val result = JSONObject(value.toString())
    when (result.opt("type")) {
      "snapshot", "state" -> {
        val snapshot = result.opt("type") == "snapshot"
        exact(result, if (snapshot) setOf("type", "info", "sequence", "screen", "writable", "inputWindow")
          else setOf("type", "info", "writable"), optional = if (snapshot) setOf("writable", "inputWindow") else setOf("writable"))
        val info = result.opt("info") as? JSONObject ?: invalid()
        if (snapshot) {
          integer(result, "sequence", 0)
          if (result.opt("screen") !is String) invalid()
          if (result.has("inputWindow")) {
            val window = result.opt("inputWindow") as? JSONObject ?: invalid()
            exact(window, setOf("maxBytes", "frameOverheadBytes"))
            integer(window, "maxBytes", 1)
            integer(window, "frameOverheadBytes", 0)
          }
        }
        val ownsControl = info.opt("controllerId") == attachmentId
        synchronized(lock) {
          if (closed) return result
          if (snapshot) { if (ready) invalid(); ready = true }
          else if (!ready) invalid()
          writable = ownsControl && !refused
        }
        // Never authorize a peer-supplied writable flag. Preserve the native screen/window.
        result.put("info", JSONObject(info.toString())).put("writable", ownsControl)
      }
      "output" -> {
        exact(result, setOf("type", "sequence", "data"))
        integer(result, "sequence", 0)
        if (result.opt("data") !is String) invalid()
        synchronized(lock) { if (!ready) invalid() }
      }
      // Host compatibility consumes native ACKs; only our completed RPC may generate one.
      else -> invalid()
    }
    return result
  }

  private fun emit(value: JSONObject) = synchronized(callbacks) {
    if (!synchronized(lock) { closed }) onItem(value)
  }

  override fun request(): Request = Request.Builder().url("https://remote.invalid/").build()
  override fun queueSize(): Long = synchronized(lock) { queuedBytes }
  override fun send(bytes: ByteString): Boolean = false
  override fun send(text: String): Boolean {
    // Reject stale/read-only inputs without any provider call. A control loss after reservation
    // is handled by the worker as a known refusal, not replayed on another attachment.
    synchronized(lock) { if (closed || !ready || !writable || refused) return false }
    val input = try {
      if (text.length > MAX_BYTES) invalid()
      val tokener = JSONTokener(text)
      val value = tokener.nextValue() as? JSONObject ?: invalid()
      if (tokener.nextClean() != '\u0000') invalid()
      val type = value.opt("type")
      when (type) {
        "write" -> {
          exact(value, setOf("type", "sequence", "data"))
          val data = value.opt("data") as? String ?: invalid()
          if (data.length > 64 * 1024) invalid()
        }
        "resize" -> {
          exact(value, setOf("type", "sequence", "cols", "rows"))
          integer(value, "cols", 1, 1000)
          integer(value, "rows", 1, 1000)
        }
        else -> invalid()
      }
      val rpcArgs = attachmentArgs()
      if (type == "write") rpcArgs.put("data", value.getString("data"))
      else rpcArgs.put("cols", value.getInt("cols")).put("rows", value.getInt("rows"))
      val rpcFrame = JSONObject().put("endpoint", "terminal/$type").put("payload", JSONObject().put("args", rpcArgs))
      // Account for the full Remote call envelope as well as the native compact input.
      val bytes = maxOf(value.toString().toByteArray(Charsets.UTF_8).size, rpcFrame.toString().toByteArray(Charsets.UTF_8).size + 1024)
      Input(value, integer(value, "sequence", 1), bytes.toLong())
    } catch (_: Exception) { finish(IOException("Invalid Remote terminal input")); return false }
    var protocolFailure = false
    val accepted = synchronized(lock) {
      if (closed || !ready || !writable || refused) return@synchronized false
      if (sequence == MAX_SAFE_INTEGER || input.sequence != sequence + 1) {
        protocolFailure = true
        return@synchronized false
      }
      if (queuedItems >= MAX_ITEMS || input.bytes > MAX_BYTES - queuedBytes) return@synchronized false
      // Reserve before publishing, even if the worker and Host answer immediately.
      queuedItems++
      queuedBytes += input.bytes
      sequence = input.sequence
      if (queue.trySend(input).isSuccess) true else {
        queuedItems--; queuedBytes -= input.bytes; sequence--; false
      }
    }
    if (protocolFailure) finish(IOException("Invalid Remote terminal input sequence"))
    return accepted
  }

  override fun close(code: Int, reason: String?): Boolean { cancel(); return true }
  override fun cancel() = finish(null)

  private fun finish(error: Throwable?) {
    val source = synchronized(lock) {
      if (closed) return
      closed = true
      ready = false
      writable = false
      queuedItems = 0
      queuedBytes = 0
      queue.cancel()
      stream.also { stream = null }
    }
    // Release the follow slot synchronously; never terminal/close (the process stays alive).
    source?.closeNow()
    lifetime.cancel()
    runCatching { onDispose(this) }
    synchronized(callbacks) {
      if (!notified) { notified = true; runCatching { onClosed(error) } }
    }
  }

  companion object {
    private const val MAX_ITEMS = 32
    private const val MAX_BYTES = 256 * 1024L
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val RPC_TIMEOUT_MS = 10_000L
    private val ID = Regex("[A-Za-z0-9_:-]{1,256}")

    suspend fun open(gateway: RemoteGateway, payload: JSONObject, onItem: (JSONObject) -> Unit,
      onClosed: (Throwable?) -> Unit, onDispose: (RemoteTerminalSocket) -> Unit = {}): RemoteTerminalSocket {
      exact(payload, setOf("args"))
      val args = payload.opt("args") as? JSONObject ?: invalid()
      exact(args, setOf("agentId", "id"))
      val agent = args.opt("agentId") as? String ?: invalid()
      val id = args.opt("id") as? String ?: invalid()
      if (!ID.matches(agent) || !ID.matches(id)) invalid()
      val socket = RemoteTerminalSocket(gateway, agent, id, onItem, onClosed, onDispose)
      var installed = false
      try {
        val source = gateway.open("terminal/follow", JSONObject().put("args", socket.attachmentArgs()))
        // Capture an acquired resource before checking prompt cancellation at this boundary.
        val reject = synchronized(socket.lock) {
          if (socket.closed) true else { socket.stream = source; false }
        }
        if (reject) source.closeNow()
        currentCoroutineContext().ensureActive()
        socket.receiver.start()
        socket.sender.start()
        currentCoroutineContext().ensureActive()
        installed = true
        return socket
      } finally { if (!installed) socket.cancel() }
    }

    private fun permissionDenied(error: Exception): Boolean = when (error) {
      is RemoteRpcException -> error.code == "PERMISSION_DENIED"
      is RemoteGatewayException -> error.code == "PERMISSION_DENIED"
      else -> false
    }
    private fun exact(value: JSONObject, fields: Set<String>, optional: Set<String> = emptySet()) {
      val keys = value.keys().asSequence().toSet()
      if (!fields.containsAll(keys) || !keys.containsAll(fields - optional)) invalid()
    }
    private fun integer(value: JSONObject, key: String, min: Long, max: Long = MAX_SAFE_INTEGER): Long {
      val number = value.opt(key) as? Number ?: invalid()
      val result = try { java.math.BigDecimal(number.toString()).longValueExact() } catch (_: Exception) { invalid() }
      if (result !in min..max) invalid()
      return result
    }
    private fun invalid(): Nothing = throw IOException("Invalid Remote terminal protocol")
  }
}
