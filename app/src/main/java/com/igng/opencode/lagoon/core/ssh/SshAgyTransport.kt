package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.AgyTransport
import com.igng.opencode.lagoon.core.ApiException
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.FileNode
import com.igng.opencode.lagoon.core.FileContent
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
 * One AGY ACP process over a pinned SSH connection. The transport is lazy and never retries a
 * request. Closing the SSH channel can terminate this process; it does not promise background
 * execution across disconnects. The pooled SSH lease is released independently of sibling services.
 */
internal class SshAgyTransport(
  private val config: SshHostConfig,
  private val secrets: SshSecrets,
  private val factory: SshTransportFactory,
  private val timeoutMillis: Long = 60_000
) : AgyTransport {
  private val gate = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val closed = AtomicBoolean(false)
  private val nextId = AtomicLong(0)
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
  private val barriers = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
  private val eventLock = Any()
  private data class QueuedEvent(val frame: JSONObject, val bytes: Int)
  private val subscribers = mutableSetOf<Channel<QueuedEvent>>()
  private val backlog = ArrayDeque<QueuedEvent>()
  private var queuedEventBytes = 0
  private val budgetChanged = Channel<Unit>(Channel.CONFLATED)
  private var terminalError: IOException? = null
  private val writerLock = Any()
  private val lifecycleLock = Any()
  @Volatile private var connection: SshTransport? = null
  @Volatile private var channel: SshExecChannel? = null
  @Volatile private var ready = false
  @Volatile private var initializedCapabilities: JSONObject? = null

  override val isClosed: Boolean get() = closed.get()
  override val filesAvailable: Boolean get() = true
  override suspend fun verifyDirectory(path: String): String {
    ensureReady()
    return withContext(Dispatchers.IO) { (connection ?: throw IOException("SSH 连接已关闭")).verifyWorkspace(path) }
  }
  override suspend fun files(session: Session, path: String): List<FileNode> {
    ensureReady()
    return withContext(Dispatchers.IO) { (connection ?: throw IOException("SSH 连接已关闭")).listWorkspace(session.directory, path) }
  }
  override suspend fun fileContent(session: Session, path: String): FileContent {
    ensureReady()
    return withContext(Dispatchers.IO) { (connection ?: throw IOException("SSH 连接已关闭")).readWorkspace(session.directory, path) }
  }
  override val initialization: JSONObject? get() = if (closed.get()) null else initializedCapabilities?.let { JSONObject(it.toString()) }

  override fun events(): Flow<JSONObject> = flow {
    val subscriber = Channel<QueuedEvent>(MAX_PENDING_EVENTS)
    synchronized(eventLock) {
      val failure = terminalError
      if (failure != null) subscriber.close(failure)
      else {
        check(subscribers.isEmpty()) { "AGY 事件只能有一个所有者" }
        subscribers += subscriber
        // Events that arrived before any collector are delivered once, not replayed to every
        // subsequent collector (in particular, old approval requests must not reappear).
        while (backlog.isNotEmpty()) check(subscriber.trySend(backlog.removeFirst()).isSuccess)
      }
    }
    try {
      for ((frame, bytes) in subscriber) {
        synchronized(eventLock) { queuedEventBytes -= bytes }
        budgetChanged.trySend(Unit)
        if (frame.optString("method") == "lagoon/internalBarrier") {
          barriers.remove(frame.optString("token"))?.complete(Unit)
        } else emit(frame)
      }
    }
    finally { synchronized(eventLock) { subscribers.remove(subscriber); queuedEventBytes = 0 }; budgetChanged.trySend(Unit); subscriber.cancel() }
  }

  override suspend fun call(method: String, params: JSONObject): JSONObject {
    require(method.isNotBlank() && method.length <= 256)
    ensureReady()
    val result = request(method, params, if (method == "session/prompt") 30 * 60_000L else timeoutMillis)
    return if (method == "session/list") withContext(Dispatchers.IO) {
      SshAgyCatalog.enrich(connection ?: throw IOException("SSH 连接已关闭"), result)
    } else result
  }

  override suspend fun beginPrompt(params: JSONObject): kotlinx.coroutines.Deferred<JSONObject> {
    ensureReady()
    val id = nextId.incrementAndGet()
    val waiter = CompletableDeferred<JSONObject>()
    pending[id] = waiter
    waiter.invokeOnCompletion { pending.remove(id, waiter) }
    try {
      deadline { send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "session/prompt").put("params", params)) }
    } catch (error: Throwable) {
      pending.remove(id, waiter)
      waiter.completeExceptionally(error)
      throw error
    }
    return waiter
  }

  override suspend fun notify(method: String, params: JSONObject) {
    require(method.isNotBlank() && method.length <= 256)
    ensureReady()
    deadline { send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)) }
  }

  override suspend fun respond(requestId: Any, result: JSONObject) {
    require(requestId is String || requestId is Int || requestId is Long) { "Invalid JSON-RPC request id" }
    ensureReady()
    deadline { send(JSONObject().put("jsonrpc", "2.0").put("id", requestId).put("result", result)) }
  }

  override suspend fun respondError(requestId: Any, code: Int, message: String) {
    require(requestId is String || requestId is Int || requestId is Long) { "Invalid JSON-RPC request id" }
    require(message.length <= 1024) { "JSON-RPC error message is too long" }
    ensureReady()
    val error = JSONObject().put("code", code).put("message", message)
    deadline { send(JSONObject().put("jsonrpc", "2.0").put("id", requestId).put("error", error)) }
  }

  override suspend fun subscribe(sessionId: String) { require(sessionId.isNotBlank()); ensureReady() }
  override suspend fun unsubscribe(sessionId: String) { require(sessionId.isNotBlank()) }
  override suspend fun syncEvents() {
    val token = java.util.UUID.randomUUID().toString()
    val waiter = CompletableDeferred<Unit>()
    barriers[token] = waiter
    try {
      val subscriber = synchronized(eventLock) { subscribers.singleOrNull() } ?: throw IOException("AGY 事件所有者不可用")
      deadline {
        subscriber.send(QueuedEvent(JSONObject().put("method", "lagoon/internalBarrier").put("token", token), 0))
        waiter.await()
      }
    } finally { barriers.remove(token) }
  }

  private suspend fun ensureReady() = gate.withLock {
    check(!closed.get()) { "AGY SSH transport closed" }
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
          if (closed.get()) { lease.close(); throw IOException("AGY SSH transport closed") }
          connection = lease
        }
        // Fixed command only. No user-provided path or arguments are interpreted as shell code.
        val exec = lease.openExecChannel("exec agy acp")
        synchronized(lifecycleLock) {
          if (closed.get()) { exec.close(); throw IOException("AGY SSH transport closed") }
          channel = exec
        }
        scope.launch { readMessages(exec) }
        // Drain stderr independently so diagnostics cannot stall the protocol. Never log it: it may
        // contain account, path or prompt data. stdout is reserved exclusively for JSONL.
        scope.launch { try { val buffer = ByteArray(8192); while (exec.stderr.read(buffer) >= 0) { } } catch (_: Exception) { } }
        val initialization = request("initialize", JSONObject().put("protocolVersion", 1)
          .put("clientCapabilities", JSONObject().put("elicitation", JSONObject().put("form", JSONObject()))).put("clientInfo", JSONObject().put("name", "lagoon").put("version", "1")))
        if (initialization.optInt("protocolVersion", -1) != 1) throw IOException("AGY ACP 版本不兼容")
        check(!closed.get()) { "AGY SSH transport closed during initialization" }
        initializedCapabilities = initialization
        ready = true
      } catch (error: Throwable) {
        terminate(IOException("AGY SSH connection failed", error))
        throw error
      }
    }
  }

  private suspend fun request(method: String, params: JSONObject, timeout: Long = timeoutMillis): JSONObject {
    val id = nextId.incrementAndGet()
    val waiter = CompletableDeferred<JSONObject>()
    pending[id] = waiter
    try {
      return deadline(timeout) {
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        waiter.await()
      }
    } finally {
      pending.remove(id, waiter)
    }
  }

  /** Deadline covers writing and awaiting a response. A timeout invalidates the whole channel:
   * whether any bytes or mutation reached the peer is unknown, so no request is replayed. */
  private suspend fun <T> deadline(timeout: Long = timeoutMillis, block: suspend () -> T): T = try {
    withTimeout(timeout) { block() }
  } catch (error: TimeoutCancellationException) {
    val failure = IOException("AGY SSH 请求超时，结果未知；请重新连接并检查状态", error)
    terminate(failure)
    throw failure
  }

  private suspend fun send(value: JSONObject) {
    val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_MESSAGE_BYTES) throw IOException("AGY request exceeds size limit")
    // A blocking socket write cannot be interrupted by coroutine cancellation. Run it in the
    // independently owned scope so the caller's deadline can close the SSH channel and lease.
    val write = scope.async {
      synchronized(writerLock) {
        check(!closed.get()) { "AGY SSH transport closed" }
        val out = channel?.stdin ?: throw IOException("AGY SSH channel unavailable")
        try { out.write(bytes); out.flush() }
        catch (error: Exception) { terminate(IOException("AGY SSH write failed", error)); throw error }
      }
    }
    try { write.await() }
    catch (error: CancellationException) {
      // Cancellation may arrive after a partial write. Never allow that writer to keep using the
      // connection after its caller is gone.
      terminate(IOException("AGY SSH 写入中断，结果未知", error))
      if (currentCoroutineContext().isActive) throw IOException("AGY SSH 通道已关闭，结果未知", error)
      throw error
    }
  }

  private suspend fun reserveEventBytes(size: Int) {
    withTimeout(timeoutMillis) {
      while (true) {
        val acquired = synchronized(eventLock) {
          if (closed.get()) throw IOException("AGY SSH transport closed")
          if (queuedEventBytes + size <= MAX_QUEUED_EVENT_BYTES) { queuedEventBytes += size; true } else false
        }
        if (acquired) return@withTimeout
        budgetChanged.receive()
      }
    }
  }
  private suspend fun readMessages(exec: SshExecChannel) {
    try {
      val input = exec.stdout.buffered()
      while (!closed.get()) {
        val bytes = ByteArrayOutputStream()
        while (true) {
          val next = input.read()
          if (next < 0) throw EOFException("AGY SSH stream ended")
          if (next == 10) break
          if (bytes.size() >= MAX_MESSAGE_BYTES) throw IOException("AGY message exceeds size limit")
          bytes.write(next)
        }
        val decoder = Charsets.UTF_8.newDecoder()
        val line = decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        val tokener = JSONTokener(line)
        val message = tokener.nextValue() as? JSONObject ?: throw IOException("Invalid AGY JSON frame")
        if (tokener.nextClean() != '\u0000') throw IOException("Invalid AGY JSON frame")
        val method = message.opt("method") as? String
        if (method != null) {
          if (method.startsWith("lagoon/internal")) throw IOException("Reserved AGY event method")
          if (bytes.size() > MAX_EVENT_BYTES) throw IOException("AGY event exceeds size limit")
          val size = bytes.size()
          reserveEventBytes(size)
          val queued = QueuedEvent(message, size)
          val subscriber = synchronized(eventLock) {
            if (subscribers.isEmpty()) {
              if (backlog.size >= MAX_PENDING_EVENTS) throw IOException("AGY event backlog overflow")
              backlog.addLast(queued); null
            } else subscribers.single()
          }
          // Pause stdout reads while the sole consumer drains. This bounds memory
          // without dropping a burst of history before its JSON-RPC response.
          if (subscriber != null) withTimeout(timeoutMillis) { subscriber.send(queued) }
        } else {
          val id = (message.opt("id") as? Number)?.toLong() ?: continue
          val waiter = pending[id] ?: continue // late replies after a timeout are ignored
          val error = message.optJSONObject("error")
          if (error != null) {
            val code = error.optInt("code")
            waiter.completeExceptionally(ApiException(code, error.optString("message", "AGY request failed"), code.toString()))
          }
          else {
            val result = message.optJSONObject("result")
            if (result == null) waiter.completeExceptionally(IOException("Invalid AGY response"))
            else waiter.complete(result)
          }
        }
      }
    } catch (error: Exception) {
      terminate(IOException("AGY SSH stream closed", error))
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
    barriers.values.forEach { it.completeExceptionally(error) }
    barriers.clear()
    synchronized(eventLock) {
      terminalError = error
      subscribers.forEach { it.close(error) }
      subscribers.clear()
      backlog.clear()
      queuedEventBytes = 0
    }
    budgetChanged.close()
    scope.cancel()
  }

  override fun close() = terminate(IOException("AGY SSH transport closed"))

  private companion object {
    const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024
    const val MAX_EVENT_BYTES = 16 * 1024 * 1024
    const val MAX_QUEUED_EVENT_BYTES = 24 * 1024 * 1024
    const val MAX_PENDING_EVENTS = 64
  }
}
