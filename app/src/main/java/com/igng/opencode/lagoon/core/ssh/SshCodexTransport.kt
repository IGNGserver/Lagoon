package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.CodexTransport
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshLinkStatus
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject

/**
 * One Codex App Server process over a pinned SSH connection. Delegates SSH lifecycle, framing,
 * timeouts and status reporting to [SshStdioRpcTransport].
 */
internal class SshCodexTransport(
  config: SshHostConfig,
  secrets: SshSecrets,
  factory: SshTransportFactory,
  timeoutMillis: Long = 60_000L,
  onStatusChanged: (SshLinkStatus) -> Unit = {}
) : CodexTransport {
  private val eventLock = Any()
  private val subscribers = mutableSetOf<Channel<JSONObject>>()
  private val backlog = ArrayDeque<JSONObject>()
  private var terminalError: IOException? = null
  @Volatile private var initializedServerVersion: String? = null

  private val rpc = SshStdioRpcTransport(
    config = config,
    secrets = secrets,
    factory = factory,
    command = "codex app-server --listen stdio://",
    label = "Codex",
    timeoutMillis = timeoutMillis,
    onStatusChanged = onStatusChanged,
    onEvent = { message, byteSize ->
      if (byteSize > MAX_EVENT_BYTES) throw IOException("Codex event exceeds size limit")
      val delivered = synchronized(eventLock) {
        if (subscribers.isEmpty()) {
          if (backlog.size >= MAX_PENDING_EVENTS) false else { backlog.addLast(message); true }
        } else {
          subscribers.all { it.trySend(message).isSuccess }
        }
      }
      if (!delivered) throw IOException("Codex event queue overflow")
    },
    onTerminate = { error ->
      synchronized(eventLock) {
        terminalError = error
        subscribers.forEach { it.close(error) }
        subscribers.clear()
        backlog.clear()
      }
    }
  )

  val status: StateFlow<SshLinkStatus> get() = rpc.status
  override val isClosed: Boolean get() = rpc.isClosed
  override val serverVersion: String? get() = if (rpc.isClosed) null else initializedServerVersion

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
    try {
      emitAll(subscriber.receiveAsFlow())
    } finally {
      synchronized(eventLock) { subscribers.remove(subscriber) }
      subscriber.cancel()
    }
  }

  override suspend fun call(method: String, params: JSONObject): JSONObject {
    require(method.isNotBlank() && method.length <= 256)
    ensureReady()
    return rpc.request({ id -> JSONObject().put("id", id).put("method", method).put("params", params) })
  }

  override suspend fun respond(requestId: Any, result: JSONObject) {
    require(requestId is String || requestId is Int || requestId is Long) { "Invalid JSON-RPC request id" }
    ensureReady()
    rpc.deadline { rpc.send(JSONObject().put("id", requestId).put("result", result)) }
  }

  override suspend fun respondError(requestId: Any, code: Int, message: String) {
    require(requestId is String || requestId is Int || requestId is Long) { "Invalid JSON-RPC request id" }
    require(message.length <= 1024) { "JSON-RPC error message is too long" }
    ensureReady()
    val error = JSONObject().put("code", code).put("message", message)
    rpc.deadline { rpc.send(JSONObject().put("id", requestId).put("error", error)) }
  }

  // thread/resume, issued by the core, establishes the App Server subscription.
  override suspend fun subscribe(threadId: String) {
    require(threadId.isNotBlank())
    ensureReady()
  }

  override suspend fun unsubscribe(threadId: String) {
    require(threadId.isNotBlank())
  }

  private suspend fun ensureReady() {
    rpc.ensureReady {
      val initialization = rpc.request({ id ->
        JSONObject()
          .put("id", id)
          .put("method", "initialize")
          .put(
            "params",
            JSONObject().put(
              "clientInfo",
              JSONObject().put("name", "lagoon").put("title", "Lagoon").put("version", "1")
            )
          )
      })
      rpc.deadline { rpc.send(JSONObject().put("method", "initialized").put("params", JSONObject())) }
      initializedServerVersion = parseServerVersion(initialization.opt("userAgent") as? String)
    }
  }

  override fun close() = rpc.close()

  private companion object {
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
