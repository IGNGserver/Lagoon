package com.igng.opencode.lagoon.core

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject

/** Children belong to one authenticated Noise generation; never silently retarget a request. */
internal class RemoteAgyRegistry(private val transfer: Boolean, private val files: Boolean, private val terminal: Boolean = false,
  private val rpc: suspend (String, JSONObject) -> Any?) : Closeable {
  private val children = ConcurrentHashMap.newKeySet<RemoteAgyTransport>()
  @Volatile private var closed = false
  fun acquire(): AgyTransport {
    check(!closed) { "Remote AGY 连接已关闭" }
    val child = RemoteAgyTransport(transfer, files, rpc, terminal) { children.remove(it) }
    children.add(child)
    if (closed) { child.connectionClosed(); throw IOException("Remote AGY 连接已关闭") }
    return child
  }
  fun event(value: JSONObject) {
    if (closed) return
    val name = value.optString("event")
    if (name != "agent.acp.frame" && name != "agent.acp.stream.closed") return
    val data = value.optJSONObject("data") ?: return
    children.forEach { it.event(name, data) }
  }
  override fun close() {
    if (closed) return
    closed = true; children.toList().forEach { it.connectionClosed() }; children.clear()
  }
}

internal class RemoteAgyTransport(
  private val transferSupported: Boolean,
  override val filesAvailable: Boolean,
  private val rpc: suspend (String, JSONObject) -> Any?,
  override val terminalAvailable: Boolean = false,
  private val release: (RemoteAgyTransport) -> Unit = {}
) : AgyTransport {
  override val remote = true
  override val promptCompletesOnResponse = false
  private val output = Channel<JSONObject>(256)
  private val streams = ConcurrentHashMap<String, String>() // session -> stream
  private val liveLoaded = ConcurrentHashMap.newKeySet<String>()
  private val loadLocks = ConcurrentHashMap<String, Mutex>()
  private val options = ConcurrentHashMap<String, JSONObject>()
  private val barriers = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
  private val subscriptionLock = Mutex()
  private val transferLock = Mutex()
  private val terminalSockets = ConcurrentHashMap.newKeySet<WebSocket>()
  private val terminalGateway = lazy { RemoteGateway { method, params -> terminalRpc(method, params) } }
  @Volatile private var closed = false
  override val isClosed: Boolean get() = closed
  private fun checkOpen() { if (closed) throw IOException("Remote AGY 连接已关闭") }
  private fun validId(id: String) = id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl)
  /** Reuse the existing terminal safety/ACK state machine, routing only its fixed endpoints via ACP. */
  private suspend fun terminalRpc(method: String, params: JSONObject): Any? {
    if (!terminalAvailable) throw ApiException(501, "Remote Host 未启用 AGY 终端")
    if (method == "harness.remote.stream.close") return rpc("agent.acp.stream.close", params)
    checkOpen()
    val endpoint = params.optString("endpoint")
    val args = params.optJSONObject("payload")?.optJSONObject("args") ?: throw IOException("AGY 终端参数无效")
    val agent = args.optString("agentId"); val session = args.optString("sessionId")
    val id = agent.ifBlank { session }
    if (!validId(id) || agent.isNotBlank() && session.isNotBlank() && agent != session) throw IOException("AGY 终端会话无效")
    val scoped = JSONObject(args.toString())
    if (agent.isNotBlank()) scoped.put("agentId", "acp:$id")
    if (session.isNotBlank()) scoped.put("sessionId", "acp:$id")
    return when (method) {
      "harness.remote.call" -> {
        if (endpoint !in setOf("terminal/environment", "terminal/shells", "terminal/list", "terminal/create", "terminal/write", "terminal/resize", "terminal/rename", "terminal/close"))
          throw IOException("AGY 终端操作无效")
        val value = hostCall("dsh/toolCall", JSONObject().put("sessionId", id).put("endpoint", endpoint).put("args", scoped))
        JSONObject().put("ok", true).put("value", value ?: JSONObject.NULL)
      }
      "harness.remote.stream.open" -> {
        if (endpoint !in setOf("terminal/follow", "terminal/retain")) throw IOException("AGY 终端流无效")
        val result = rpc("agent.acp.stream.open", JSONObject().put("streamId", params.getString("streamId")).put("sessionId", id)
          .put("tool", JSONObject().put("backend", "antigravity").put("endpoint", endpoint).put("args", scoped))) as? JSONObject
          ?: throw IOException("AGY 终端流响应无效")
        if (!result.optBoolean("opened")) throw IOException("AGY 终端流未打开")
        result
      }
      else -> throw IOException("AGY 终端操作无效")
    }
  }
  override suspend fun terminalCall(endpoint: String, args: JSONObject): Any? =
    terminalGateway.value.call(endpoint, JSONObject().put("args", args))
  override suspend fun terminalOpen(endpoint: String, args: JSONObject, onItem: (JSONObject) -> Unit, onClosed: (Throwable?) -> Unit): WebSocket {
    if (!terminalAvailable) throw ApiException(501, "Remote Host 未启用 AGY 终端")
    val gateway = terminalGateway.value
    if (endpoint == "terminal/attach") {
      val socket = RemoteTerminalSocket.open(gateway, JSONObject().put("args", args), onItem, onClosed) { terminalSockets.remove(it) }
      terminalSockets += socket
      if (closed) { terminalSockets.remove(socket); socket.cancel(); throw IOException("AGY 终端连接已关闭") }
      return socket
    }
    require(endpoint == "terminal/retain")
    val stream = gateway.open(endpoint, JSONObject().put("args", args))
    val reference = java.util.concurrent.atomic.AtomicReference<WebSocket>()
    val socket = RemoteBackend.StreamSocket(stream, onItem) { error -> reference.get()?.let { terminalSockets.remove(it) }; onClosed(error) }
    reference.set(socket); terminalSockets += socket
    if (closed) { terminalSockets.remove(socket); socket.cancel(); throw IOException("AGY 终端连接已关闭") }
    return socket
  }
  private suspend fun hostCall(method: String, params: JSONObject): Any? {
    checkOpen()
    val arguments = JSONObject(params.toString())
    // session/cancel has a strict envelope without backend; session ownership supplies it.
    if (method != "session/cancel") arguments.put("backend", "antigravity")
    val value = rpc("agent.acp.call", JSONObject().put("method", method).put("params", arguments))
    checkOpen()
    return value
  }
  override suspend fun health() {
    val result = hostCall("initialize", JSONObject().put("protocolVersion", 1)) as? JSONObject
      ?: throw IOException("Remote AGY 健康检查响应无效")
    if (result.optInt("protocolVersion", -1) != 1 || result.optString("backend") != "antigravity")
      throw IOException("Remote AGY 健康检查身份无效")
  }
  override suspend fun call(method: String, params: JSONObject): JSONObject = when (method) {
    "session/list" -> listSessions(params)
    "session/load" -> loadHistory(params)
    "session/new" -> (hostCall(method, params) as? JSONObject ?: throw IOException("Remote AGY 会话响应无效")).also {
      val id = it.optString("sessionId"); if (!validId(id)) throw IOException("Remote AGY 会话身份无效")
      liveLoaded.add(id)
      // Model catalog is a separate read after the confirmed creation. Never let
      // its failure or cancellation obscure a successfully created session.
    }
    "session/set_config_option" -> setConfig(params)
    "lagoon/modelConfig" -> {
      val id = params.optString("sessionId")
      if (!validId(id)) throw IOException("AGY 会话身份无效")
      ensureLiveLoaded(id)
      JSONObject().put("configOptions", sessionConfig(id))
    }
    else -> throw ApiException(501, "Remote AGY 不支持此操作")
  }
  private suspend fun listSessions(params: JSONObject): JSONObject {
    if (params.has("cursor") && !params.isNull("cursor")) throw ApiException(501, "当前 Remote Host 不支持 AGY 会话分页")
    val cwd = params.optString("cwd")
    val paths = if (cwd.isNotBlank()) listOf(cwd) else {
      val value = hostCall("dsh/workspaceList", JSONObject()) as? JSONArray ?: throw IOException("Remote AGY 工作区目录无效")
      if (value.length() > 1000) throw IOException("Remote AGY 工作区目录超过上限")
      (0 until value.length()).map { index -> value.optJSONObject(index)?.optString("path")?.takeIf(String::isNotBlank)
        ?: throw IOException("Remote AGY 工作区路径无效") }
    }
    val sessions = JSONArray()
    for (path in paths.distinct()) {
      val result = hostCall("dsh/sessionList", JSONObject().put("path", path).put("limit", 100).put("prewarm", false)) as? JSONObject
        ?: throw IOException("Remote AGY 会话列表无效")
      val rows = result.optJSONArray("items") ?: throw IOException("Remote AGY 会话列表缺少内容")
      if (rows.length() >= 100) throw IOException("Remote Host 只返回每个工作区最多 100 个 AGY 会话，无法确认列表完整")
      for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: throw IOException("Remote AGY 会话条目无效")
        val id = row.optString("conversationId")
        if (!validId(id)) throw IOException("Remote AGY 会话身份无效")
        val item = JSONObject().put("sessionId", id).put("cwd", path).put("title", row.optString("title"))
        val updated = row.optLong("updatedAt", 0)
        if (updated > 0) item.put("updatedAt", java.time.Instant.ofEpochMilli(updated).toString())
        sessions.put(item)
      }
    }
    return JSONObject().put("sessions", sessions)
  }
  private suspend fun loadHistory(params: JSONObject): JSONObject {
    val sessionId = params.optString("sessionId")
    if (!validId(sessionId)) throw IOException("AGY 会话身份无效")
    val result = transferred(JSONObject().put("method", "dsh/sessionHistory")
      .put("params", JSONObject().put("backend", "antigravity").put("sessionId", "acp:$sessionId"))) as? JSONObject
      ?: throw IOException("Remote AGY 历史响应无效")
    val events = result.optJSONArray("events") ?: throw IOException("Remote AGY 历史缺少事件")
    if (events.length() > 10_000) throw IOException("Remote AGY 历史超过上限")
    for (index in 0 until events.length()) {
      val event = events.optJSONObject(index)?.optJSONObject("event") ?: throw IOException("Remote AGY 历史事件无效")
      val data = event.optJSONObject("data") ?: JSONObject()
      when (event.optString("type")) {
        "user/message", "assistant/message" -> {
          val message = if (event.optString("type") == "user/message") data else data.optJSONObject("message") ?: JSONObject()
          val content = message.optJSONArray("content") ?: JSONArray()
          val kind = if (event.optString("type") == "user/message") "user_message_chunk" else "agent_message_chunk"
          val messageId = message.optString("id").ifBlank { "history:$index" }
          for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            val effectiveKind = if (part.optString("type") == "reasoning") "agent_thought_chunk" else kind
            val block = when (part.optString("type")) {
              "text", "reasoning" -> JSONObject().put("type", "text").put("text", part.optString("text"))
              "image" -> JSONObject().put("type", "image").put("mimeType", part.optString("mediaType")).put("data", part.optString("data"))
              else -> null
            }
            if (block != null) output.send(update(sessionId, JSONObject().put("sessionUpdate", effectiveKind)
              .put("messageId", messageId).put("content", block)).put("lagoonReplay", true))
          }
        }
        "tool/call" -> output.send(update(sessionId, JSONObject().put("sessionUpdate", "tool_call")
          .put("toolCallId", data.optString("callId")).put("title", data.optString("name"))
          .put("status", data.optString("status")).put("rawInput", data.opt("arguments"))).put("lagoonReplay", true))
      }
    }
    return JSONObject()
  }
  override suspend fun verifyDirectory(path: String): String {
    checkOpen()
    val response = rpc("agent.acp.call", JSONObject().put("method", "dsh/directoryList").put("params", JSONObject().put("path", path))) as? JSONObject
      ?: throw IOException("Remote AGY 目录响应无效")
    return response.optString("path").takeIf(String::isNotBlank) ?: throw IOException("Remote AGY 未确认目录")
  }
  override suspend fun files(session: Session, path: String): List<FileNode> {
    if (!filesAvailable) throw ApiException(501, "Remote Host 未声明 AGY 文件浏览能力")
    val relative = path.trim().ifBlank { "." }
    val result = hostCall("dsh/toolCall", JSONObject().put("sessionId", session.id).put("endpoint", "workspaceFiles/list")
      .put("args", JSONObject().put("workspaceFileScopeId", "acp:${session.id}").put("path", relative))) as? JSONObject
      ?: throw IOException("Remote AGY 文件列表无效")
    val listed = result.optString("path").trim('/')
    val entries = result.optJSONArray("entries") ?: throw IOException("Remote AGY 文件列表缺少条目")
    if (result.optBoolean("truncated")) throw IOException("Remote Host 截断了文件列表，请缩小浏览目录")
    if (entries.length() > 10_000) throw IOException("Remote AGY 文件列表超过上限")
    return (0 until entries.length()).mapNotNull { index ->
      val item = entries.optJSONObject(index) ?: return@mapNotNull null
      val name = item.optString("name").takeIf { it.isNotBlank() && '/' !in it && it !in setOf(".", "..") } ?: return@mapNotNull null
      FileNode(if (listed.isBlank() || listed == ".") name else "$listed/$name", item.optString("type").ifBlank { "file" })
    }
  }
  override suspend fun fileContent(session: Session, path: String): FileContent {
    if (!filesAvailable) throw ApiException(501, "Remote Host 未声明 AGY 文件浏览能力")
    val limit = 2 * 1024 * 1024
    val result = hostCall("dsh/toolCall", JSONObject().put("sessionId", session.id).put("endpoint", "workspaceFiles/readBytes")
      .put("args", JSONObject().put("workspaceFileScopeId", "acp:${session.id}").put("path", path)
        .put("range", JSONObject().put("offset", 0).put("length", limit)))) as? JSONObject
      ?: throw IOException("Remote AGY 文件内容无效")
    val size = result.optLong("bytes", -1)
    if (size !in 0..limit.toLong() || result.opt("eof") != true || result.optLong("offset", -1) != 0L)
      throw IOException("Remote AGY 文件超过 2 MiB 预览上限或读取不完整")
    val encoded = result.opt("data") as? String ?: throw IOException("Remote AGY 文件数据无效")
    val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw IOException("Remote AGY 文件编码无效") }
    if (bytes.size.toLong() != size || Base64.getEncoder().encodeToString(bytes) != encoded) throw IOException("Remote AGY 文件内容不完整")
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
      catch (_: Exception) { return FileContent("binary", "") }
    return if ('\u0000' in text) FileContent("binary", "") else FileContent("text", text)
  }
  private fun update(id: String, value: JSONObject) = JSONObject().put("method", "session/update")
    .put("params", JSONObject().put("sessionId", id).put("update", value))
  private suspend fun ensureLiveLoaded(id: String) {
    loadLocks.computeIfAbsent(id) { Mutex() }.withLock {
      if (id in liveLoaded) return
      hostCall("session/load", JSONObject().put("sessionId", id))
      syncEvents()
      liveLoaded.add(id)
    }
  }
  private suspend fun sessionModels(id: String): JSONObject =
    hostCall("dsh/sessionModels", JSONObject().put("sessionId", id)) as? JSONObject
      ?: throw IOException("Remote AGY 模型目录无效")
  private fun configFromModels(result: JSONObject): JSONArray {
    val current = result.optJSONObject("current") ?: throw IOException("Remote AGY 模型状态无效")
    val groups = result.optJSONArray("groups") ?: throw IOException("Remote AGY 模型目录缺失")
    val models = JSONArray(); var effort: JSONObject? = null
    for (index in 0 until groups.length()) {
      val rows = groups.optJSONObject(index)?.optJSONArray("models") ?: continue
      for (item in 0 until rows.length()) {
        val row = rows.optJSONObject(item) ?: continue
        val id = row.optString("id"); if (id.isBlank()) continue
        models.put(JSONObject().put("value", id).put("name", row.optString("name").ifBlank { id }))
        if (id == current.optString("model")) effort = row.optJSONObject("reasoning")
      }
    }
    val config = JSONArray().put(JSONObject().put("id", "model").put("category", "model").put("type", "select")
      .put("currentValue", current.optString("model")).put("options", models))
    val available = effort?.optJSONArray("efforts")
    if (available != null) {
      val values = JSONArray()
      for (index in 0 until available.length()) {
        val row = available.optJSONObject(index) ?: continue
        val value = row.optString("id"); if (value.isNotBlank()) values.put(JSONObject().put("value", value).put("name", row.optString("name").ifBlank { value }))
      }
      config.put(JSONObject().put("id", "effort").put("type", "select").put("currentValue", current.optString("reasoningEffort")).put("options", values))
    }
    return config
  }
  private suspend fun sessionConfig(id: String) = configFromModels(sessionModels(id))
  private suspend fun setConfig(params: JSONObject): JSONObject {
    val id = params.optString("sessionId"); val key = params.optString("configId"); val value = params.optString("value")
    if (!validId(id) || key !in setOf("model", "effort") || value.isBlank()) throw ApiException(400, "AGY 模型选择无效")
    ensureLiveLoaded(id)
    val current = sessionModels(id).optJSONObject("current") ?: throw IOException("Remote AGY 模型状态无效")
    val selection = JSONObject().put("sessionId", id).put("model", if (key == "model") value else current.optString("model"))
    if (key == "effort") selection.put("reasoningEffort", value)
    val response = hostCall("dsh/selectModel", selection) as? JSONObject ?: throw IOException("Remote AGY 未确认模型")
    val selected = response.optJSONObject("selected") ?: throw IOException("Remote AGY 未确认模型")
    if (selected.optString(if (key == "model") "model" else "reasoningEffort") != value) throw IOException("Remote AGY 未确认所选值")
    return JSONObject().put("configOptions", sessionConfig(id))
  }
  override suspend fun beginPrompt(params: JSONObject): Deferred<JSONObject> {
    val id = params.optString("sessionId")
    if (!validId(id)) throw IOException("AGY 会话身份无效")
    ensureLiveLoaded(id)
    val request = JSONObject(params.toString()).put("backend", "antigravity")
    val prompt = request.optJSONArray("prompt") ?: JSONArray()
    val images = (0 until prompt.length()).count { prompt.optJSONObject(it)?.optString("type") == "image" }
    if (images > 4) throw ApiException(400, "Remote AGY 每条消息最多支持 4 张图片")
    val response = if (images > 0) transferred(JSONObject().put("method", "session/prompt").put("params", request))
      else hostCall("session/prompt", request)
    val value = response as? JSONObject ?: throw IOException("Remote AGY 未确认消息")
    if (!value.optBoolean("accepted")) throw IOException("Remote AGY 未确认消息，结果未知")
    return CompletableDeferred(value)
  }
  override suspend fun notify(method: String, params: JSONObject) {
    if (method != "session/cancel") throw ApiException(501, "Remote AGY 不支持此通知")
    hostCall(method, params)
  }
  override suspend fun respond(requestId: Any, result: JSONObject) {
    val handle = requestId as? String ?: throw IOException("Remote AGY 审批标识无效")
    val original = options[handle] ?: throw ApiException(409, "Remote AGY 请求已失效")
    if (original.optString("mode") == "form") {
      val action = result.optString("action")
      val decision = when (action) { "accept" -> "allow-once"; "decline" -> "reject-once"; "cancel" -> "cancel"; else -> throw ApiException(400, "AGY 表单结果无效") }
      val envelope = JSONObject().put("requestHandle", handle).put("decision", decision)
      if (action == "accept") envelope.put("result", result)
      rpc("agent.acp.respond", envelope); options.remove(handle); return
    }
    val selected = result.optJSONObject("outcome")?.optString("optionId") ?: ""
    val values = original.optJSONArray("options") ?: JSONArray()
    val match = (0 until values.length()).mapNotNull { values.optJSONObject(it) }.singleOrNull { it.optString("optionId") == selected }
      ?: throw ApiException(409, "Remote AGY 审批选项已变化")
    val decision = when (match.optString("kind")) { "allow_once" -> "allow-once"; "reject_once" -> "reject-once"; else -> throw ApiException(501, "Remote 仅支持单次审批") }
    rpc("agent.acp.respond", JSONObject().put("requestHandle", handle).put("decision", decision))
    options.remove(handle)
  }
  override suspend fun respondError(requestId: Any, code: Int, message: String) {
    val handle = requestId as? String ?: throw IOException("Remote AGY 请求标识无效")
    rpc("agent.acp.respond", JSONObject().put("requestHandle", handle).put("decision", "cancel")); options.remove(handle)
  }
  override suspend fun subscribe(sessionId: String) = subscriptionLock.withLock {
    checkOpen(); require(validId(sessionId))
    if (streams.containsKey(sessionId)) return@withLock
    val stream = UUID.randomUUID().toString()
    streams[sessionId] = stream
    try {
      val result = rpc("agent.acp.stream.open", JSONObject().put("streamId", stream).put("sessionId", sessionId)) as? JSONObject
        ?: throw IOException("Remote AGY 流响应无效")
      if (result.optBoolean("opened") != true) throw IOException("Remote AGY 流未打开")
    } catch (error: Throwable) { streams.remove(sessionId, stream); throw error }
  }
  override suspend fun unsubscribe(sessionId: String) = subscriptionLock.withLock {
    val stream = streams.remove(sessionId) ?: return@withLock
    rpc("agent.acp.stream.close", JSONObject().put("streamId", stream)); Unit
  }
  private fun offer(frame: JSONObject) {
    if (!output.trySend(frame).isSuccess) { close(); throw IOException("Remote AGY 事件流过载") }
  }
  internal fun event(name: String, data: JSONObject) {
    if (closed) return
    val stream = data.optString("streamId")
    if (terminalGateway.isInitialized()) {
      if (name == "agent.acp.stream.closed") terminalGateway.value.event(JSONObject().put("event", "harness.remote.stream.closed").put("data", data))
      else {
        val frame = data.optJSONObject("frame")
        if (frame?.optString("method") == "dsh/workspaceTool") {
          val value = frame.optJSONObject("params")?.opt("value") ?: JSONObject.NULL
          terminalGateway.value.event(JSONObject().put("event", "harness.remote.frame")
            .put("data", JSONObject().put("streamId", stream).put("hasValue", true).put("value", value)))
          return
        }
      }
    }
    if (name == "agent.acp.stream.closed") {
      if (stream in streams.values) close()
      return
    }
    val frame = data.optJSONObject("frame") ?: return
    val params = frame.optJSONObject("params") ?: return
    val session = params.optString("sessionId")
    if (!validId(session) || streams[session] == null || stream !in setOf(streams[session], "session:$session")) return
    val handle = params.optString("requestHandle")
    val normalized = JSONObject(frame.toString())
    normalized.remove("lagoonReplay") // the replay marker is internal, never supplied by the peer
    if (handle.isNotBlank()) { normalized.put("id", handle); options[handle] = JSONObject(params.toString()) }
    // Overflow retires this child in offer(); never throw through the shared
    // Noise event callback and disconnect unrelated Harness siblings.
    try { offer(normalized) } catch (_: IOException) { }
  }
  override fun events(): Flow<JSONObject> = flow {
    for (frame in output) {
      if (frame.optString("method") == "lagoon/internalBarrier") barriers.remove(frame.optString("token"))?.complete(Unit)
      else emit(frame)
    }
  }
  override suspend fun syncEvents() {
    val token = UUID.randomUUID().toString(); val waiter = CompletableDeferred<Unit>(); barriers[token] = waiter
    try { offer(JSONObject().put("method", "lagoon/internalBarrier").put("token", token)); withTimeout(30_000) { waiter.await() } }
    finally { barriers.remove(token) }
  }
  internal fun connectionClosed() {
    if (closed) return
    closed = true; terminalSockets.toList().forEach { it.cancel() }; terminalSockets.clear()
    if (terminalGateway.isInitialized()) terminalGateway.value.close()
    streams.clear(); options.clear(); liveLoaded.clear()
    barriers.values.forEach { it.completeExceptionally(IOException("Remote AGY 连接已关闭")) }; barriers.clear()
    output.close(IOException("Remote AGY 连接已断开")); release(this)
  }
  override fun close() {
    val ids = streams.values.toList()
    connectionClosed()
    ids.forEach { id -> SharedHttp.ioScope.launch { runCatching { rpc("agent.acp.stream.close", JSONObject().put("streamId", id)) } } }
  }

  private suspend fun transferred(request: JSONObject): Any? = transferLock.withLock {
    if (!transferSupported) throw IOException("Remote Host 未声明 AGY 大消息传输能力")
    val bytes = request.toString().toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty() || bytes.size > CLIENT_LIMIT) throw IOException("AGY 请求超过设备处理上限")
    val id = UUID.randomUUID().toString(); val count = (bytes.size + CHUNK - 1) / CHUNK
    var attempted = false
    try {
      attempted = true
      rpc("agent.acp.transfer.open", JSONObject().put("transferId", id).put("totalBytes", bytes.size).put("totalChunks", count))
      for (index in 0 until count) {
        checkOpen(); val start = index * CHUNK
        val data = Base64.getEncoder().encodeToString(bytes.copyOfRange(start, minOf(start + CHUNK, bytes.size)))
        rpc("agent.acp.transfer.chunk", JSONObject().put("transferId", id).put("index", index).put("data", data))
      }
      val result = rpc("agent.acp.transfer.commit", JSONObject().put("transferId", id)) as? JSONObject ?: throw IOException("AGY 传输提交响应无效")
      when (result.optString("kind")) {
        "inline" -> if (result.has("response")) result.opt("response") else throw IOException("AGY 传输缺少响应")
        "chunked" -> readTransfer(id, result)
        else -> throw IOException("AGY 传输描述无效")
      }
    } finally {
      if (attempted) withContext(NonCancellable) { try { withTimeout(5_000) { rpc("agent.acp.transfer.close", JSONObject().put("transferId", id)) } } catch (_: Exception) {} }
    }
  }
  private suspend fun readTransfer(id: String, descriptor: JSONObject): JSONObject {
    val size = descriptor.optLong("totalBytes", -1); val chunks = descriptor.optInt("totalChunks", -1)
    if (descriptor.optString("transferId") != id || size !in 1..CLIENT_LIMIT.toLong() || chunks != ((size + CHUNK - 1) / CHUNK).toInt()) throw IOException("AGY 传输描述无效")
    val bytes = ByteArray(size.toInt()); var offset = 0
    for (index in 0 until chunks) {
      checkOpen()
      val part = rpc("agent.acp.transfer.read", JSONObject().put("transferId", id).put("index", index)) as? JSONObject ?: throw IOException("AGY 分块无效")
      if (part.optString("transferId") != id || part.optInt("index", -1) != index) throw IOException("AGY 分块顺序无效")
      val encoded = part.opt("data") as? String ?: throw IOException("AGY 分块缺少内容")
      val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw IOException("AGY 分块编码无效") }
      if (Base64.getEncoder().encodeToString(decoded) != encoded || decoded.size != minOf(CHUNK, bytes.size - offset)) throw IOException("AGY 分块大小无效")
      decoded.copyInto(bytes, offset); offset += decoded.size
    }
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
      catch (_: Exception) { throw IOException("AGY 传输不是有效 UTF-8") }
    return try { JSONObject(text) } catch (_: Exception) { throw IOException("AGY 传输响应无效") }
  }
  private companion object { const val CHUNK = 512 * 1024; const val CLIENT_LIMIT = 64 * 1024 * 1024 }
}

internal class LazyRemoteAgyTransport(private val load: suspend () -> AgyTransport, private val release: () -> Unit) : AgyTransport {
  override val remote = true
  override val promptCompletesOnResponse = false
  override val filesAvailable: Boolean get() = synchronized(gate) { delegate?.filesAvailable == true }
  override val terminalAvailable: Boolean get() = synchronized(gate) { delegate?.terminalAvailable == true }
  private val lock = Mutex()
  private val gate = Any()
  private var delegate: AgyTransport? = null
  private var closed = false
  override val isClosed: Boolean get() = synchronized(gate) { closed || delegate?.isClosed == true }
  private suspend fun get(): AgyTransport = lock.withLock {
    synchronized(gate) { check(!closed) { "Remote AGY 已关闭" }; delegate?.let { return@withLock it } }
    val result = load()
    synchronized(gate) { if (closed) { result.close(); throw IOException("Remote AGY 已关闭") }; delegate = result }
    result
  }
  override suspend fun call(method: String, params: JSONObject) = get().call(method, params)
  override suspend fun discoverCapabilities() { get().discoverCapabilities() }
  override suspend fun health() = get().health()
  override suspend fun terminalCall(endpoint: String, args: JSONObject) = get().terminalCall(endpoint, args)
  override suspend fun terminalOpen(endpoint: String, args: JSONObject, onItem: (JSONObject) -> Unit, onClosed: (Throwable?) -> Unit) =
    get().terminalOpen(endpoint, args, onItem, onClosed)
  override suspend fun beginPrompt(params: JSONObject) = get().beginPrompt(params)
  override suspend fun notify(method: String, params: JSONObject) = get().notify(method, params)
  override suspend fun verifyDirectory(path: String) = get().verifyDirectory(path)
  override suspend fun files(session: Session, path: String) = get().files(session, path)
  override suspend fun fileContent(session: Session, path: String) = get().fileContent(session, path)
  override suspend fun respond(requestId: Any, result: JSONObject) = get().respond(requestId, result)
  override suspend fun respondError(requestId: Any, code: Int, message: String) = get().respondError(requestId, code, message)
  override suspend fun subscribe(sessionId: String) = get().subscribe(sessionId)
  override suspend fun unsubscribe(sessionId: String) = get().unsubscribe(sessionId)
  override suspend fun syncEvents() = get().syncEvents()
  override fun events(): Flow<JSONObject> = flow { get().events().collect { emit(it) } }
  override fun close() {
    val child = synchronized(gate) { if (closed) return; closed = true; delegate.also { delegate = null } }
    try { child?.close() } finally { release() }
  }
}
