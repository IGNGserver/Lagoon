package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.CodexTransport
import com.igng.opencode.lagoon.core.ApiException
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One App Server process over a pinned SSH connection. The transport is lazy and never retries a
 * request. Closing the SSH channel can terminate this process; it does not promise background
 * execution across disconnects. The pooled SSH lease is released independently of sibling services.
 */
internal class SshCodexTransport(
  private val config: SshHostConfig,
  private val secrets: SshSecrets,
  private val factory: SshTransportFactory,
  private val timeoutMillis: Long = 60_000
) : CodexTransport {
  private val gate = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val closed = AtomicBoolean(false)
  private val nextId = AtomicLong(0)
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
  private val eventLock = Any()
  private val subscribers = mutableSetOf<Channel<JSONObject>>()
  private val backlog = ArrayDeque<JSONObject>()
  private var terminalError: IOException? = null
  private val writerLock = Any()
  private val lifecycleLock = Any()
  @Volatile private var connection: SshTransport? = null
  @Volatile private var channel: SshExecChannel? = null
  @Volatile private var ready = false
  @Volatile private var initializedServerVersion: String? = null

  override val isClosed: Boolean get() = closed.get()
  override val serverVersion: String? get() = if (closed.get()) null else initializedServerVersion

  override fun events(): Flow<JSONObject> = flow {
    val subscriber = Channel<JSONObject>(MAX_PENDING_EVENTS)
    synchronized(eventLock) {
      val failure = terminalError
      if (failure != null) subscriber.close(failure)
      else {
        subscribers += subscriber
        // Events that arrived before any collector are delivered once, not replayed to every
        // subsequent collector (in particular, old approval requests must not reappear).
        while (backlog.isNotEmpty()) check(subscriber.trySend(backlog.removeFirst()).isSuccess)
      }
    }
    try { emitAll(subscriber.receiveAsFlow()) }
    finally { synchronized(eventLock) { subscribers.remove(subscriber) }; subscriber.cancel() }
  }

  override suspend fun call(method: String, params: JSONObject): JSONObject {
    require(method.isNotBlank() && method.length <= 256)
    ensureReady()
    return request(method, params)
  }

  override suspend fun respond(requestId: Any, result: JSONObject) {
    require(requestId is String || requestId is Int || requestId is Long) { "Invalid JSON-RPC request id" }
    ensureReady()
    deadline { send(JSONObject().put("id", requestId).put("result", result)) }
  }

  override suspend fun respondError(requestId: Any, code: Int, message: String) {
    require(requestId is String || requestId is Int || requestId is Long) { "Invalid JSON-RPC request id" }
    require(message.length <= 1024) { "JSON-RPC error message is too long" }
    ensureReady()
    val error = JSONObject().put("code", code).put("message", message)
    deadline { send(JSONObject().put("id", requestId).put("error", error)) }
  }

  // thread/resume, issued by the core, establishes the App Server subscription.
  override suspend fun subscribe(threadId: String) { require(threadId.isNotBlank()); ensureReady() }
  override suspend fun unsubscribe(threadId: String) { require(threadId.isNotBlank()) }

  private suspend fun ensureReady() = gate.withLock {
    check(!closed.get()) { "Codex SSH transport closed" }
    if (ready) return@withLock
    withContext(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      try {
        val lease = factory.connect(config, secrets, SshHostKeyVerifier { key ->
          if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED,
            "SSH 主机指纹已变化", retryable = false, presentedKey = key)
          true
        })
        synchronized(lifecycleLock) {
          if (closed.get()) { lease.close(); throw IOException("Codex SSH transport closed") }
          connection = lease
        }
        // Fixed command only. No user-provided path or arguments are interpreted as shell code.
        val exec = lease.openExecChannel("exec codex app-server --listen stdio://")
        synchronized(lifecycleLock) {
          if (closed.get()) { exec.close(); throw IOException("Codex SSH transport closed") }
          channel = exec
        }
        scope.launch { readMessages(exec) }
        // Drain stderr independently so diagnostics cannot stall the protocol. Never log it: it may
        // contain account, path or prompt data. stdout is reserved exclusively for JSONL.
        scope.launch { try { val buffer = ByteArray(8192); while (exec.stderr.read(buffer) >= 0) { } } catch (_: Exception) { } }
        val initialization = request("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "lagoon")
          .put("title", "Lagoon").put("version", "1")))
        deadline { send(JSONObject().put("method", "initialized").put("params", JSONObject())) }
        check(!closed.get()) { "Codex SSH transport closed during initialization" }
        initializedServerVersion = parseServerVersion(initialization.opt("userAgent") as? String)
        ready = true
      } catch (error: Throwable) {
        terminate(IOException("Codex SSH connection failed", error))
        throw error
      }
    }
  }

  private suspend fun request(method: String, params: JSONObject): JSONObject {
    val id = nextId.incrementAndGet()
    val waiter = CompletableDeferred<JSONObject>()
    pending[id] = waiter
    try {
      return deadline {
        send(JSONObject().put("id", id).put("method", method).put("params", params))
        waiter.await()
      }
    } finally {
      pending.remove(id, waiter)
    }
  }

  /** Deadline covers writing and awaiting a response. A timeout invalidates the whole channel:
   * whether any bytes or mutation reached the peer is unknown, so no request is replayed. */
  private suspend fun <T> deadline(block: suspend () -> T): T = try {
    withTimeout(timeoutMillis) { block() }
  } catch (error: TimeoutCancellationException) {
    val failure = IOException("Codex SSH 请求超时，结果未知；请重新连接并检查状态", error)
    terminate(failure)
    throw failure
  }

  private suspend fun send(value: JSONObject) {
    val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_MESSAGE_BYTES) throw IOException("Codex request exceeds size limit")
    // A blocking socket write cannot be interrupted by coroutine cancellation. Run it in the
    // independently owned scope so the caller's deadline can close the SSH channel and lease.
    val write = scope.async {
      synchronized(writerLock) {
        check(!closed.get()) { "Codex SSH transport closed" }
        val out = channel?.stdin ?: throw IOException("Codex SSH channel unavailable")
        try { out.write(bytes); out.flush() }
        catch (error: Exception) { terminate(IOException("Codex SSH write failed", error)); throw error }
      }
    }
    try { write.await() }
    catch (error: CancellationException) {
      // Cancellation may arrive after a partial write. Never allow that writer to keep using the
      // connection after its caller is gone.
      terminate(IOException("Codex SSH 写入中断，结果未知", error))
      if (currentCoroutineContext().isActive) throw IOException("Codex SSH 通道已关闭，结果未知", error)
      throw error
    }
  }

  private fun readMessages(exec: SshExecChannel) {
    try {
      val input = exec.stdout.buffered()
      while (!closed.get()) {
        val bytes = ByteArrayOutputStream()
        while (true) {
          val next = input.read()
          if (next < 0) throw EOFException("Codex SSH stream ended")
          if (next == 10) break
          if (bytes.size() >= MAX_MESSAGE_BYTES) throw IOException("Codex message exceeds size limit")
          bytes.write(next)
        }
        val decoder = Charsets.UTF_8.newDecoder()
        val line = decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        val tokener = JSONTokener(line)
        val message = tokener.nextValue() as? JSONObject ?: throw IOException("Invalid Codex JSON frame")
        if (tokener.nextClean() != '\u0000') throw IOException("Invalid Codex JSON frame")
        val method = message.opt("method") as? String
        if (method != null) {
          if (bytes.size() > MAX_EVENT_BYTES) throw IOException("Codex event exceeds size limit")
          val delivered = synchronized(eventLock) {
            if (subscribers.isEmpty()) {
              if (backlog.size >= MAX_PENDING_EVENTS) false else { backlog.addLast(message); true }
            } else subscribers.all { it.trySend(message).isSuccess }
          }
          if (!delivered) throw IOException("Codex event queue overflow")
        } else {
          val id = (message.opt("id") as? Number)?.toLong() ?: continue
          val waiter = pending[id] ?: continue // late replies after a timeout are ignored
          val error = message.optJSONObject("error")
          if (error != null) {
            val code = error.optInt("code")
            waiter.completeExceptionally(ApiException(code, error.optString("message", "Codex request failed"), code.toString()))
          }
          else {
            val result = message.optJSONObject("result")
            if (result == null) waiter.completeExceptionally(IOException("Invalid Codex response"))
            else waiter.complete(result)
          }
        }
      }
    } catch (error: Exception) {
      terminate(IOException("Codex SSH stream closed", error))
    }
  }

  private fun terminate(error: IOException) {
    val owned = synchronized(lifecycleLock) {
      if (!closed.compareAndSet(false, true)) return
      ready = false
      val pair = channel to connection
      channel = null
      connection = null
      pair
    }
    runCatching { owned.first?.close() }
    runCatching { owned.second?.close() }
    pending.values.forEach { it.completeExceptionally(error) }
    pending.clear()
    synchronized(eventLock) {
      terminalError = error
      subscribers.forEach { it.close(error) }
      subscribers.clear()
      backlog.clear()
    }
    scope.cancel()
  }

  override fun close() = terminate(IOException("Codex SSH transport closed"))

  private companion object {
    const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024
    const val MAX_EVENT_BYTES = 1024 * 1024
    const val MAX_PENDING_EVENTS = 64
  }
}

/** Accept only the product token observed from this client in the initialize response. The
 * remainder of User-Agent can contain host details and must never be persisted or logged. */
internal fun parseServerVersion(userAgent: String?): String? {
  if (userAgent == null || userAgent.length > 1024) return null
  return Regex("^lagoon/((?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*))(?:[ \\t]|$)")
    .find(userAgent)?.groupValues?.get(1)
}
