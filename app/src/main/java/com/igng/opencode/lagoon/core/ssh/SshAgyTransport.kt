package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.AgyTransport
import com.igng.opencode.lagoon.core.FileContent
import com.igng.opencode.lagoon.core.FileNode
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshLinkStatus
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * One ACP process (Antigravity ACP or OpenCode ACP) over a pinned SSH connection.
 * Delegates SSH lifecycle, framing, timeouts and status reporting to [SshStdioRpcTransport],
 * adding ACP byte-budget backpressure, event sync barriers, SFTP workspace files, and optional
 * Antigravity catalog enrichment.
 */
internal class SshAgyTransport(
  config: SshHostConfig,
  secrets: SshSecrets,
  factory: SshTransportFactory,
  private val timeoutMillis: Long = 60_000L,
  /** Fixed ACP command; callers pass an app-owned literal, never profile text. */
  command: String = "agy acp",
  /** Short label used only in local diagnostics. */
  private val label: String = "Antigravity ACP",
  /** Antigravity publishes a directory database that OpenCode ACP does not. */
  private val catalog: Boolean = true,
  onStatusChanged: (SshLinkStatus) -> Unit = {}
) : AgyTransport {
  init {
    require(command.matches(Regex("[a-z0-9][a-z0-9 ._-]{0,64}")) && "  " !in command) { "Invalid ACP command" }
    require(label.isNotBlank() && label.length <= 32) { "Invalid ACP label" }
  }

  private data class QueuedEvent(val frame: JSONObject, val bytes: Int)

  private val barriers = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
  private val eventLock = Any()
  private val subscribers = mutableSetOf<Channel<QueuedEvent>>()
  private val backlog = ArrayDeque<QueuedEvent>()
  private var queuedEventBytes = 0
  private val budgetChanged = Channel<Unit>(Channel.CONFLATED)
  private var terminalError: IOException? = null
  @Volatile private var initializedCapabilities: JSONObject? = null

  private val rpc = SshStdioRpcTransport(
    config = config,
    secrets = secrets,
    factory = factory,
    command = command,
    label = label,
    timeoutMillis = timeoutMillis,
    onStatusChanged = onStatusChanged,
    onEvent = { message, byteSize ->
      val method = message.optString("method")
      if (method.startsWith("lagoon/internal")) throw IOException("Reserved $label event method")
      if (byteSize > MAX_EVENT_BYTES) throw IOException("$label event exceeds size limit")
      reserveEventBytes(byteSize)
      val queued = QueuedEvent(message, byteSize)
      val subscriber = synchronized(eventLock) {
        if (subscribers.isEmpty()) {
          if (backlog.size >= MAX_PENDING_EVENTS) throw IOException("$label event backlog overflow")
          backlog.addLast(queued)
          null
        } else {
          subscribers.single()
        }
      }
      // Pause stdout reads while the sole consumer drains. Bounds memory without dropping bursts.
      if (subscriber != null) withTimeout(timeoutMillis) { subscriber.send(queued) }
    },
    onTerminate = { error ->
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
    }
  )

  val status: StateFlow<SshLinkStatus> get() = rpc.status
  override val isClosed: Boolean get() = rpc.isClosed
  override val filesAvailable: Boolean get() = true

  override suspend fun verifyDirectory(path: String): String {
    ensureReady()
    return withContext(Dispatchers.IO) { rpc.activeConnection().verifyWorkspace(path) }
  }

  override suspend fun files(session: Session, path: String): List<FileNode> {
    ensureReady()
    return withContext(Dispatchers.IO) { rpc.activeConnection().listWorkspace(session.directory, path) }
  }

  override suspend fun fileContent(session: Session, path: String): FileContent {
    ensureReady()
    return withContext(Dispatchers.IO) { rpc.activeConnection().readWorkspace(session.directory, path) }
  }

  override val initialization: JSONObject?
    get() = if (rpc.isClosed) null else initializedCapabilities?.let { JSONObject(it.toString()) }

  override fun events(): Flow<JSONObject> = flow {
    val subscriber = Channel<QueuedEvent>(MAX_PENDING_EVENTS)
    synchronized(eventLock) {
      val failure = terminalError
      if (failure != null) subscriber.close(failure)
      else {
        check(subscribers.isEmpty()) { "$label 事件只能有一个所有者" }
        subscribers += subscriber
        while (backlog.isNotEmpty()) check(subscriber.trySend(backlog.removeFirst()).isSuccess)
      }
    }
    try {
      for ((frame, bytes) in subscriber) {
        synchronized(eventLock) { queuedEventBytes -= bytes }
        budgetChanged.trySend(Unit)
        if (frame.optString("method") == "lagoon/internalBarrier") {
          barriers.remove(frame.optString("token"))?.complete(Unit)
        } else {
          emit(frame)
        }
      }
    } finally {
      synchronized(eventLock) {
        subscribers.remove(subscriber)
        queuedEventBytes = 0
      }
      budgetChanged.trySend(Unit)
      subscriber.cancel()
    }
  }

  override suspend fun call(method: String, params: JSONObject): JSONObject {
    require(method.isNotBlank() && method.length <= 256)
    ensureReady()
    val timeout = if (method == "session/prompt") 30 * 60_000L else timeoutMillis
    val result = rpc.request(
      envelope = { id -> JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params) },
      timeout = timeout
    )
    return if (method == "session/list" && catalog) {
      withContext(Dispatchers.IO) { SshAgyCatalog.enrich(rpc.activeConnection(), result) }
    } else result
  }

  override suspend fun beginPrompt(params: JSONObject): Deferred<JSONObject> {
    ensureReady()
    return rpc.beginRequest { id ->
      JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "session/prompt").put("params", params)
    }
  }

  override suspend fun notify(method: String, params: JSONObject) {
    require(method.isNotBlank() && method.length <= 256)
    ensureReady()
    rpc.deadline { rpc.send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)) }
  }

  override suspend fun respond(requestId: Any, result: JSONObject) {
    require(requestId is String || requestId is Int || requestId is Long) { "Invalid JSON-RPC request id" }
    ensureReady()
    rpc.deadline { rpc.send(JSONObject().put("jsonrpc", "2.0").put("id", requestId).put("result", result)) }
  }

  override suspend fun respondError(requestId: Any, code: Int, message: String) {
    require(requestId is String || requestId is Int || requestId is Long) { "Invalid JSON-RPC request id" }
    require(message.length <= 1024) { "JSON-RPC error message is too long" }
    ensureReady()
    val error = JSONObject().put("code", code).put("message", message)
    rpc.deadline { rpc.send(JSONObject().put("jsonrpc", "2.0").put("id", requestId).put("error", error)) }
  }

  override suspend fun subscribe(sessionId: String) {
    require(sessionId.isNotBlank())
    ensureReady()
  }

  override suspend fun unsubscribe(sessionId: String) {
    require(sessionId.isNotBlank())
  }

  override suspend fun syncEvents() {
    val token = UUID.randomUUID().toString()
    val waiter = CompletableDeferred<Unit>()
    barriers[token] = waiter
    try {
      val subscriber = synchronized(eventLock) { subscribers.singleOrNull() }
        ?: throw IOException("$label 事件所有者不可用")
      rpc.deadline {
        subscriber.send(QueuedEvent(JSONObject().put("method", "lagoon/internalBarrier").put("token", token), 0))
        waiter.await()
      }
    } finally {
      barriers.remove(token)
    }
  }

  private suspend fun ensureReady() {
    rpc.ensureReady {
      val initialization = rpc.request({ id ->
        JSONObject()
          .put("jsonrpc", "2.0")
          .put("id", id)
          .put("method", "initialize")
          .put(
            "params",
            JSONObject()
              .put("protocolVersion", 1)
              .put("clientCapabilities", JSONObject().put("elicitation", JSONObject().put("form", JSONObject())))
              .put("clientInfo", JSONObject().put("name", "lagoon").put("version", "1"))
          )
      })
      if (initialization.optInt("protocolVersion", -1) != 1) throw IOException("$label ACP 版本不兼容")
      initializedCapabilities = initialization
    }
  }

  private suspend fun reserveEventBytes(size: Int) {
    withTimeout(timeoutMillis) {
      while (true) {
        val acquired = synchronized(eventLock) {
          if (rpc.isClosed) throw IOException("$label SSH 传输已关闭")
          if (queuedEventBytes + size <= MAX_QUEUED_EVENT_BYTES) {
            queuedEventBytes += size
            true
          } else false
        }
        if (acquired) return@withTimeout
        budgetChanged.receive()
      }
    }
  }

  override fun close() = rpc.close()

  private companion object {
    const val MAX_EVENT_BYTES = 16 * 1024 * 1024
    const val MAX_QUEUED_EVENT_BYTES = 24 * 1024 * 1024
    const val MAX_PENDING_EVENTS = 64
  }
}
