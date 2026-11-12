package com.igng.opencode.lagoon.core

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Registry belongs to exactly one authenticated Noise connection. Child closure never closes it. */
internal class RemoteCodexRegistry(
  private val transferSupported: Boolean,
  private val rpc: suspend (String, JSONObject) -> Any?
) : Closeable {
  private val children = ConcurrentHashMap.newKeySet<RemoteCodexTransport>()
  @Volatile private var closed = false

  fun acquire(): CodexTransport {
    check(!closed) { "Remote Codex 连接已关闭" }
    val child = RemoteCodexTransport(transferSupported, rpc) { children.remove(it) }
    children.add(child)
    if (closed) { child.close(); throw IOException("Remote Codex 连接已关闭") }
    return child
  }

  fun event(payload: JSONObject) {
    if (closed) return
    val name = payload.optString("event")
    if (name != "codex.app.frame" && name != "codex.app.stream.closed") return
    val data = payload.optJSONObject("data") ?: return
    children.forEach { it.event(name, data) }
  }

  override fun close() {
    closed = true
    children.toList().forEach { it.connectionClosed() }
    children.clear()
  }
}

/** Restricted Remote domain, not a general JSON-RPC proxy. Never retries a business mutation. */
internal class RemoteCodexTransport(
  private val transferSupported: Boolean,
  private val rpc: suspend (String, JSONObject) -> Any?,
  private val release: (RemoteCodexTransport) -> Unit = {}
) : CodexTransport {
  override val remote: Boolean get() = true
  private val streams = ConcurrentHashMap<String, String>() // streamId -> threadId
  private val output = CodexEventBroadcast()
  private val subscriptionLock = Mutex()
  private val transferLock = Mutex()
  @Volatile private var closed = false

  override val isClosed: Boolean get() = closed

  override suspend fun call(method: String, params: JSONObject): JSONObject {
    ensureOpen()
    if (method !in ALLOWED) throw ApiException(501, "Remote Host 不支持该 Codex 操作", "METHOD_NOT_ALLOWED")
    val request = JSONObject().put("method", method).put("params", params)
    val large = method == "dsh/sessionHistory" || method == "dsh/sessionAttachment" ||
      method == "thread/read" && params.optBoolean("includeTurns", false) || hasImageInput(params)
    if (hasImageInput(params) && !transferSupported) throw IOException("Remote Host 未声明 Codex 图片传输能力")
    val response = try { if (large && transferSupported) transferred(request) else rpc("codex.app.call", request) }
    catch (error: RemoteRpcException) {
      if (error.code == "METHOD_NOT_ALLOWED" || error.code == "METHOD_NOT_FOUND")
        throw ApiException(501, "Remote Host 不支持该 Codex 操作", error.code)
      if (method == "project/list" && error.code == "CODEX_UPSTREAM_ERROR")
        throw ApiException(502, "Remote Host 无法读取 Codex 项目列表", error.code)
      throw error
    }
    ensureOpen()
    return response as? JSONObject ?: throw IOException("Remote Codex 响应不是对象")
  }

  override suspend fun respond(requestId: Any, result: JSONObject) {
    ensureOpen()
    val handle = requestId as? String ?: throw IOException("Remote Codex 审批标识无效")
    if (!validId(handle)) throw IOException("Remote Codex 审批标识无效")
    val decision = result.opt("decision") as? String
    if (decision !in setOf("accept", "decline", "cancel") || result.length() != 1)
      throw IOException("Remote Codex 仅支持单次接受、拒绝或取消")
    rpc("codex.app.respond", JSONObject().put("requestHandle", handle).put("decision", decision))
    ensureOpen()
  }

  override suspend fun subscribe(threadId: String) = subscriptionLock.withLock {
    ensureOpen()
    require(validId(threadId)) { "Codex thread ID 无效" }
    if (streams.containsValue(threadId)) return@withLock
    val streamId = UUID.randomUUID().toString()
    streams[streamId] = threadId // register before opening: frames may precede the RPC response
    try {
      val response = rpc("codex.app.stream.open", JSONObject().put("streamId", streamId).put("threadId", threadId)) as? JSONObject
        ?: throw IOException("Remote Codex 流打开响应无效")
      ensureOpen()
      if (response.opt("opened") != true || response.optString("streamId") != streamId ||
        response.optString("threadId") != threadId) throw IOException("Remote Codex 流打开响应不匹配")
      if (streams[streamId] != threadId) throw IOException("Remote Codex 流打开时已关闭")
    } catch (error: Throwable) {
      streams.remove(streamId)
      // Open might have reached Host even if response was lost. Close this unique stream id only.
      closeRemoteStream(streamId)
      throw error
    }
  }

  override suspend fun unsubscribe(threadId: String) = subscriptionLock.withLock {
    val matches = streams.entries.filter { it.value == threadId }.map { it.key }
    matches.forEach { streams.remove(it) }
    matches.forEach { closeRemoteStream(it) }
  }

  override fun events(): Flow<JSONObject> = output.events()

  internal fun event(name: String, data: JSONObject) {
    if (closed) return
    val id = data.opt("streamId") as? String ?: return
    val threadId = streams[id] ?: return
    if (name == "codex.app.stream.closed") {
      streams.remove(id)
      if (!offer(JSONObject().put("method", "lagoon/streamClosed").put("params", JSONObject()
        .put("threadId", threadId).put("reason", data.optString("reason", "failed"))))) failStream(id, threadId)
      // A Host-ended subscription can no longer provide a complete baseline. Retire this child;
      // the owner can reacquire a fresh carrier, re-subscribe and re-read without replaying writes.
      output.close(IOException("Remote Codex 流已结束，需要重新读取会话"))
      close()
      return
    }
    val source = data.optJSONObject("frame") ?: return failStream(id, threadId)
    val method = source.opt("method") as? String ?: return failStream(id, threadId)
    if (method.isBlank() || method.length > 256) return failStream(id, threadId)
    val normalized = JSONObject(source.toString())
    val params = normalized.optJSONObject("params")
    val frameThread = params?.opt("threadId") as? String
    if (frameThread != null && frameThread != threadId) return failStream(id, threadId)
    val handle = params?.opt("requestHandle") as? String
    if (handle != null) {
      if (!validId(handle)) return failStream(id, threadId)
      normalized.put("id", handle)
    }
    if (!offer(normalized)) failStream(id, threadId)
  }

  private fun offer(value: JSONObject): Boolean = output.offer(value)
  private fun failStream(streamId: String, threadId: String) {
    streams.remove(streamId)
    // A full event queue means state may be incomplete; close the whole child so callers resync.
    output.close(IOException("Remote Codex 事件流过载或格式错误：$threadId"))
    close()
    SharedHttp.ioScope.launch { closeRemoteStream(streamId) }
  }

  internal fun connectionClosed() {
    if (closed) return
    closed = true
    streams.clear()
    output.close(IOException("Remote Codex 连接已断开；请重新连接并读取历史"))
    release(this)
  }

  override fun close() {
    if (closed) return
    closed = true
    val ids = streams.keys.toList()
    streams.clear()
    output.close()
    release(this)
    ids.forEach { id -> SharedHttp.ioScope.launch { closeRemoteStream(id) } }
  }

  private suspend fun closeRemoteStream(id: String) = withContext(NonCancellable) {
    try { withTimeout(5_000) { rpc("codex.app.stream.close", JSONObject().put("streamId", id)) } }
    catch (_: Exception) { /* A lost close must not trigger a new connection or replay a mutation. */ }
    Unit
  }

  private suspend fun transferred(request: JSONObject): Any? = transferLock.withLock {
    if (!transferSupported) throw IOException("Remote Host 未声明 Codex 大消息传输能力")
    val bytes = request.toString().toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty() || bytes.size > CLIENT_LIMIT) throw IOException("Codex 请求超过设备处理上限")
    val transferId = UUID.randomUUID().toString()
    val count = (bytes.size + CHUNK - 1) / CHUNK
    var openAttempted = false
    try {
      // The Host may allocate this ID before its response is lost or the caller is cancelled.
      openAttempted = true
      rpc("codex.app.transfer.open", JSONObject().put("transferId", transferId).put("totalBytes", bytes.size).put("totalChunks", count))
      for (index in 0 until count) {
        ensureOpen()
        val start = index * CHUNK
        val data = Base64.getEncoder().encodeToString(bytes.copyOfRange(start, minOf(start + CHUNK, bytes.size)))
        rpc("codex.app.transfer.chunk", JSONObject().put("transferId", transferId).put("index", index).put("data", data))
      }
      val result = rpc("codex.app.transfer.commit", JSONObject().put("transferId", transferId)) as? JSONObject
        ?: throw IOException("Codex 传输提交响应无效")
      when (result.optString("kind")) {
        "inline" -> if (result.has("response")) result.opt("response") else throw IOException("Codex 传输缺少响应")
        "chunked" -> readTransfer(transferId, result)
        else -> throw IOException("Codex 传输描述无效")
      }
    } finally {
      if (openAttempted) withContext(NonCancellable) {
        try { withTimeout(5_000) { rpc("codex.app.transfer.close", JSONObject().put("transferId", transferId)) } }
        catch (_: Exception) { }
      }
    }
  }

  private suspend fun readTransfer(id: String, descriptor: JSONObject): JSONObject {
    val size = descriptor.optLong("totalBytes", -1)
    val chunks = descriptor.optInt("totalChunks", -1)
    if (descriptor.optString("transferId") != id || size !in 1..CLIENT_LIMIT.toLong() ||
      chunks != ((size + CHUNK - 1) / CHUNK).toInt()) throw IOException("Codex 传输描述无效")
    val bytes = ByteArray(size.toInt())
    var offset = 0
    for (index in 0 until chunks) {
      ensureOpen()
      val part = rpc("codex.app.transfer.read", JSONObject().put("transferId", id).put("index", index)) as? JSONObject
        ?: throw IOException("Codex 传输分块无效")
      if (part.optString("transferId") != id || part.optInt("index", -1) != index) throw IOException("Codex 传输分块顺序无效")
      val encoded = part.opt("data") as? String ?: throw IOException("Codex 传输分块缺少数据")
      val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw IOException("Codex 传输分块编码无效") }
      if (Base64.getEncoder().encodeToString(decoded) != encoded || decoded.size != minOf(CHUNK, bytes.size - offset))
        throw IOException("Codex 传输分块大小或编码无效")
      decoded.copyInto(bytes, offset); offset += decoded.size
    }
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
    catch (_: Exception) { throw IOException("Codex 传输不是有效 UTF-8") }
    return try { JSONObject(text) } catch (_: Exception) { throw IOException("Codex 传输响应不是 JSON 对象") }
  }

  private fun ensureOpen() { if (closed) throw IOException("Remote Codex 连接已关闭") }
  private fun hasImageInput(params: JSONObject): Boolean {
    val inputs: JSONArray = params.optJSONArray("input") ?: return false
    return (0 until inputs.length()).any { inputs.optJSONObject(it)?.optString("type") == "image" }
  }
  private fun validId(value: String) = value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)

  private companion object {
    const val CHUNK = 512 * 1024
    // Lower than the 288 MiB wire ceiling to bound peak memory on Android.
    const val CLIENT_LIMIT = 64 * 1024 * 1024
    val ALLOWED = setOf("account/read", "model/list", "project/list", "project/create", "thread/list", "thread/read",
      "dsh/sessionHistory", "dsh/sessionAttachment", "dsh/directoryList", "thread/start", "thread/resume", "thread/fork",
      "thread/name/set", "thread/archive", "thread/unarchive", "thread/unsubscribe", "turn/start", "turn/steer", "turn/interrupt")
  }
}

/** Broadcast without allowing a slow observer to silently miss part of a thread. */
private class CodexEventBroadcast {
  private val gate = Any()
  private val subscribers = mutableSetOf<Channel<JSONObject>>()
  private val backlog = java.util.ArrayDeque<JSONObject>()
  private var closed = false

  private var failure: Throwable? = null

  fun offer(event: JSONObject): Boolean = synchronized(gate) {
    if (closed) return false
    if (subscribers.isEmpty()) {
      if (backlog.size >= 256) return false
      backlog.addLast(event)
      return true
    }
    subscribers.all { it.trySend(event).isSuccess }
  }

  fun events(): Flow<JSONObject> = flow {
    val channel = Channel<JSONObject>(256)
    synchronized(gate) {
      if (closed) channel.close(failure)
      else {
        while (backlog.isNotEmpty()) channel.trySend(backlog.removeFirst())
        subscribers.add(channel)
      }
    }
    try { for (event in channel) emit(event) }
    finally { synchronized(gate) { subscribers.remove(channel); channel.cancel() } }
  }

  fun close(cause: Throwable? = null) = synchronized(gate) {
    if (!closed) {
      closed = true; failure = cause; backlog.clear()
      subscribers.forEach { it.close(cause) }; subscribers.clear()
    }
  }
}

/** Delays connecting until first use while keeping release idempotent and race-safe. */
internal class LazyRemoteCodexTransport(
  private val load: suspend () -> CodexTransport,
  private val releaseLease: () -> Unit = {}
) : CodexTransport {
  override val remote: Boolean get() = true
  private val lock = Mutex()
  private val gate = Any()
  private var delegate: CodexTransport? = null
  private var closed = false

  override val isClosed: Boolean get() = synchronized(gate) { closed || delegate?.isClosed == true }

  private suspend fun get(): CodexTransport = lock.withLock {
    synchronized(gate) {
      check(!closed) { "Remote Codex 连接已关闭" }
      delegate?.let { return@withLock it }
    }
    val acquired = load()
    synchronized(gate) {
      if (closed) { acquired.close(); throw IOException("Remote Codex 连接已关闭") }
      delegate = acquired
    }
    acquired
  }

  override suspend fun call(method: String, params: JSONObject): JSONObject = get().call(method, params)
  override suspend fun respond(requestId: Any, result: JSONObject) = get().respond(requestId, result)
  override suspend fun subscribe(threadId: String) = get().subscribe(threadId)
  override suspend fun unsubscribe(threadId: String) = get().unsubscribe(threadId)
  override fun events(): Flow<JSONObject> = flow { emitAll(get().events()) }
  override fun close() {
    val child = synchronized(gate) {
      if (closed) return
      closed = true
      delegate.also { delegate = null }
    }
    try { child?.close() } finally { releaseLease() }
  }
}
