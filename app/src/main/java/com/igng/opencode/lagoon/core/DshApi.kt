package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * DeepSeek Harness（DSH）客户端。
 *
 * DSH 的 Host 只暴露一套 Typert RPC：一元调用走 `POST /api/<endpoint>`，长流走
 * `/api/remote.mux` WebSocket 的多路复用帧，全局事件走 `$events` 端点。凭据是一次性
 * 启动令牌换来的签名 Cookie：`GET /connect?token=…` 返回 302 + `Set-Cookie`，之后所有
 * 调用都带该 Cookie。
 *
 * 本类把 DSH 的会话模型（turn/step 事件日志）翻译成 Lagoon 已经在用的 OpenCode 形状：
 * 会话列表、消息时间线、执行事件、审批与问答。控制器和 UI 因此不需要知道服务端是哪一种。
 */
class DshApi(
  private val profile: ServerProfile,
  private val credentials: ServerCredentials,
  callTimeoutSeconds: Long = 60
) : HarnessClient {
  constructor(profile: ServerProfile, password: String) : this(profile, ServerCredentials(profile.username, password))

  private val base: HttpUrl = requireNotNull(profile.url.trimEnd('/').toHttpUrlOrNull()) { "服务器地址无效" }
  private val origin = HttpOrigin.of(base)

  /** DSH 用一次性启动令牌换 Cookie；令牌沿用凭据的 password 槽位。 */
  private val token: String = credentials.password.trim()

  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
    .connectionPool(SharedHttp.connectionPool)
    .dispatcher(SharedHttp.dispatcher)
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
      val request = chain.request()
      if (HttpOrigin.of(request.url) != origin) throw IOException("拒绝向非授权地址发送凭据")
      chain.proceed(request)
    }
    .build()

  private val streamHttp = http.newBuilder()
    .dispatcher(SharedHttp.streamDispatcher)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()

  private val ioScope = SharedHttp.ioScope

  init {
    require(base.scheme == "https" || base.scheme == "http" && profile.allowCleartext) { "HTTP 明文连接未获授权，请在服务器资料中明确开启" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "请使用不含凭据或参数的服务器地址" }
  }

  /** 会话目录与标题缓存；事件缺目录时用它补 `ServerEvent.directory`。 */
  private val known = ConcurrentHashMap<String, Session>()
  /** `session/follow` 快照给出的日志切点：`session/page` 的 `throughSeq`。 */
  private val cursors = ConcurrentHashMap<String, Long>()
  /** 需要实时事件（`session/follow`）的会话，最近打开的放最后。 */
  private val watched = LinkedHashSet<String>()
  private val follows = ConcurrentHashMap<String, WebSocket>()
  @Volatile private var eventChannel: Channel<ServerEvent>? = null
  @Volatile private var eventsClientId: String? = null
  /** 已发出的审批/问答瀑布 id，回复时按 `PermissionRequest.id` 找回。 */
  private val waterfallRequests = ConcurrentHashMap<String, String>()
  /** 仍在等待回答的审批（`sessionPermissions`/`pendingPermissions` 用）。 */
  private val pendingApprovals = ConcurrentHashMap<String, PermissionRequest>()
  private val pendingQuestions = ConcurrentHashMap<String, QuestionRequest>()
  @Volatile private var catalog: JSONObject? = null

  override fun capabilities(): ApiCapabilities = ApiCapabilities.BASELINE

  override suspend fun discoverCapabilities(): ApiCapabilities = ApiCapabilities.BASELINE

  // ---------------------------------------------------------------- 鉴权

  @Volatile private var sessionCookie: String? = null
  private val authLock = Mutex()

  private suspend fun cookie(): String = authLock.withLock {
    sessionCookie ?: connect().also { sessionCookie = it }
  }

  /** 用启动令牌换签名 Cookie；Host 用 302 + `Set-Cookie` 交付。 */
  private suspend fun connect(): String = withContext(Dispatchers.IO) {
    if (token.isEmpty()) throw ApiException(401, "请填写 DeepSeek Harness 的访问令牌")
    val url = base.newBuilder()
      .addPathSegment("connect")
      .addQueryParameter("token", token)
      .addQueryParameter("interactions", "answer")
      .build()
    val call = http.newCall(Request.Builder().url(url).get().build())
    val response = try {
      withContext(Dispatchers.IO) { call.execute() }
    } catch (error: IOException) {
      throw ApiException(0, "无法连接 DeepSeek Harness：${error.message ?: "网络不可达"}")
    }
    response.use { value ->
      if (value.code != 302 && value.code != 200) {
        throw ApiException(value.code, if (value.code == 401 || value.code == 403) "访问令牌无效或已过期" else "连接失败：HTTP ${value.code}")
      }
      for (raw in value.headers("Set-Cookie")) {
        val pair = raw.substringBefore(';').trim()
        if (pair.startsWith("dsh-auth-")) return@withContext pair
      }
      throw ApiException(401, "连接成功但没有拿到会话 Cookie，请确认访问令牌来自该实例")
    }
  }

  // ---------------------------------------------------------------- RPC

  private fun payload(request: JSONObject): JSONObject = JSONObject().put("request", request)

  /** 一元调用的请求信封是 `{type, rpcId, method, payload}`；响应是 `{type, rpcId, result}`。 */
  private suspend fun call(endpoint: String, payload: JSONObject = JSONObject(), retried: Boolean = false): Any? =
    withContext(Dispatchers.IO) {
      val current = cookie()
      val url = base.newBuilder().addEncodedPathSegment("api").addEncodedPathSegments(endpoint).build()
      val envelope = JSONObject()
        .put("type", "client-request")
        .put("rpcId", UUID.randomUUID().toString())
        .put("method", endpoint)
        .put("payload", payload)
      val request = Request.Builder()
        .url(url)
        .header("Content-Type", "application/json; charset=utf-8")
        .header("Cookie", current)
        .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()
      val response = try {
        http.newCall(request).execute()
      } catch (error: IOException) {
        throw ApiException(0, "无法连接 DeepSeek Harness：${error.message ?: "网络不可达"}")
      }
      val status = response.code
      val text = response.use { it.body?.string().orEmpty() }
      if (status == 401 && !retried) {
        authLock.withLock { sessionCookie = null }
        return@withContext call(endpoint, payload, retried = true)
      }
      if (status !in 200..299) throw ApiException(status, httpMessage(status, text))
      val parsed = try { JSONObject(text) } catch (error: Exception) {
        throw ApiException(status, "服务器响应不是 JSON，请确认地址指向 DeepSeek Harness 的 Web 实例")
      }
      val result = parsed.optJSONObject("result") ?: throw ApiException(status, "服务器响应缺少 result")
      if (result.optBoolean("ok")) return@withContext result.opt("value")
      val failure = result.optJSONObject("error")
      throw ApiException(status, failure?.optString("message").orEmpty().ifBlank { "请求失败" })
    }

  private suspend fun callObject(endpoint: String, payload: JSONObject = JSONObject()): JSONObject =
    call(endpoint, payload) as? JSONObject ?: JSONObject()

  private fun httpMessage(status: Int, body: String): String = when (status) {
    401, 403 -> "访问令牌无效或已过期"
    404 -> "该地址不是 DeepSeek Harness 的 Web 实例"
    409 -> "连接已被其他客户端占用，请重新启动实例"
    else -> "请求失败：HTTP $status"
  }

  // ---------------------------------------------------------------- 会话读

  /** `session/list` 的形状：`{items:[{sessionId,updatedAt,running,cwd,parentSessionId,projections,…}]}`。 */
  private data class Listing(val items: List<JSONObject>) {
    val running: Set<String> get() = items.filter { it.optBoolean("running") }.map { it.str("sessionId") }.toSet()
  }

  private suspend fun listing(): Listing {
    val result = callObject("session/list", JSONObject().put("_request", JSONObject()))
    val items = result.arr("items").objects()
    // session/list 没有 directory 过滤参数；顺手刷新目录缓存，事件解码时要用。
    for (item in items) itemToSession(item)?.let { known[it.id] = it }
    return Listing(items)
  }

  private fun itemToSession(item: JSONObject): Session? {
    val id = item.str("sessionId").ifBlank { return null }
    val values = item.obj("projections").obj("values")
    val directory = item.str("cwd").ifBlank { values.str("workingDirectory") }
    val updated = item.optDouble("updatedAt", 0.0).toLong()
    val previous = known[id]
    val title = values.str("title").ifBlank { previous?.title.orEmpty() }
    return Session(
      id = id,
      directory = directory,
      title = title.ifBlank { "新会话" },
      updated = updated,
      parentId = item.str("parentSessionId").ifBlank { null },
      projectId = directory.ifBlank { null },
      created = previous?.created ?: updated,
      archived = previous?.archived ?: false,
      agent = values.str("agentPreset").ifBlank { previous?.agent }?.takeIf(String::isNotBlank),
      model = previous?.model,
      viewed = previous?.viewed ?: 0,
      idle = if (item.optBoolean("running")) 0 else previous?.idle ?: 0,
      outcome = previous?.outcome,
      revertMessageId = null,
      serverId = previous?.serverId
    )
  }

  private suspend fun sessionOrFetch(id: String): Session = known[id] ?: run {
    listing()
    known[id] ?: throw ApiException(404, "会话不存在或已被删除")
  }

  override suspend fun session(id: String): Session = sessionOrFetch(id)

  override suspend fun projects(): List<Project> = withContext(Dispatchers.IO) {
    // DSH 的 Workspace 就是「项目」：一次 workspace/follow 的首帧给出全量清单。
    val frame = fetchFirstFrame("workspace/follow", JSONObject()) ?: return@withContext emptyList()
    frame.arr("items").objects().mapNotNull { workspace ->
      val path = workspace.str("path").ifBlank { return@mapNotNull null }
      Project(
        id = workspace.str("workspaceId").ifBlank { path },
        directory = path,
        name = workspace.str("title").ifBlank { path.trimEnd('/').substringAfterLast('/').ifBlank { path } }
      )
    }
  }

  override suspend fun projectAt(directory: String): Project? {
    val normalized = directory.trimEnd('/')
    return projects().firstOrNull { it.directory.trimEnd('/') == normalized || it.directories.any { path -> path.trimEnd('/') == normalized } }
  }

  override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int): ApiPage<Session> =
    withContext(Dispatchers.IO) {
      val items = listing().items
      val sessions = items.mapNotNull { itemToSession(it) }
        .filter { directory == null || it.directory.trimEnd('/') == directory.trimEnd('/') }
        .filter { it.parentId == null }
        .sortedByDescending { it.activityAt }
      rememberWatched(sessions)
      ApiPage(sessions, null)
    }

  override suspend fun viewSession(session: Session, idle: Long) {
    // DSH 没有「已查看」写接口；本地时间线已经按事件推进。
  }

  override suspend fun messagesPage(id: String, cursor: String?, size: Int): ApiPage<Message> = withContext(Dispatchers.IO) {
    val session = sessionOrFetch(id)
    rememberWatched(listOf(session))
    val wanted = size.coerceAtLeast(1)
    if (cursor == null) {
      // 首屏：`session/follow` 的首帧快照直接给出完整日志（`records` + 切点 `cursor`）。
      val snapshot = fetchSnapshot(id)
      val through = snapshot.optDouble("cursor", 0.0).toLong()
      if (through > 0) cursors[id] = through
      val messages = recordsToMessages(session, snapshot.arr("records").objects())
      ApiPage(messages.takeLast(wanted), null)
    } else {
      val through = cursors[id] ?: run {
        val snapshot = fetchSnapshot(id)
        snapshot.optDouble("cursor", 0.0).toLong().also { if (it > 0) cursors[id] = it }
      }
      if (through <= 0) return@withContext ApiPage(emptyList(), null)
      val before = cursor.toLongOrNull() ?: return@withContext ApiPage(emptyList(), null)
      val request = JSONObject()
        .put("address", address(id))
        .put("throughSeq", through)
        .put("beforeSeq", before)
        .put("maxMessages", wanted)
      val page = callObject("session/page", payload(request))
      val records = page.arr("records").objects()
      val messages = recordsToMessages(session, records)
      val next = if (page.optBoolean("hasMore")) earlierCursor(records) else null
      ApiPage(messages, next)
    }
  }

  private fun earlierCursor(records: List<JSONObject>): String? =
    records.mapNotNull { it.obj("event").optDouble("seq", 0.0).toLong().takeIf { value -> value > 0 } }.minOrNull()?.toString()

  // ---------------------------------------------------------------- mux 流

  /** 打开一条 mux 流：客户端发 `open`，服务端回 `item`/`end`/`error` 帧。 */
  private suspend fun openMux(
    endpoint: String,
    payload: JSONObject,
    onItem: (JSONObject) -> Unit,
    onClosed: (Throwable?) -> Unit
  ): WebSocket = withContext(Dispatchers.IO) {
    val current = cookie()
    val streamId = UUID.randomUUID().toString()
    // OkHttp 的 WebSocket 直接接受 http(s) URL 并发起 Upgrade；HttpUrl 不接受 ws/wss 方案。
    val socketUrl = base.newBuilder()
      .addEncodedPathSegment("api")
      .addEncodedPathSegment("remote.mux")
      .build()
    val request = Request.Builder().url(socketUrl).header("Cookie", current).build()
    val listener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        val open = JSONObject()
          .put("type", "open")
          .put("streamId", streamId)
          .put("endpoint", endpoint)
          .put("payload", payload)
        webSocket.send(open.toString())
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = try { JSONObject(text) } catch (error: Exception) { return }
        if (frame.optString("streamId") != streamId) return
        when (frame.optString("type")) {
          "item" -> frame.optJSONObject("value")?.let(onItem) ?: onItem(JSONObject())
          "error" -> onClosed(IOException(frame.obj("error").str("message").ifBlank { "会话流出错" }))
          "end" -> {
            webSocket.close(1000, null)
            onClosed(null)
          }
        }
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        onClosed(t)
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        onClosed(null)
      }
    }
    streamHttp.newWebSocket(request, listener)
  }

  /** 取一条流的首个 `item` 帧（`workspace/follow` 的清单、`session/follow` 的快照）。 */
  private suspend fun fetchFirstFrame(endpoint: String, payload: JSONObject): JSONObject? {
    val deferred = CompletableDeferred<JSONObject?>()
    val socket = try {
      openMux(endpoint, payload, onItem = { frame ->
        if (!deferred.isCompleted) deferred.complete(frame)
      }, onClosed = { cause ->
        if (!deferred.isCompleted) deferred.completeExceptionally(cause ?: IOException("流已关闭"))
      })
    } catch (error: Exception) {
      return null
    }
    return try {
      withTimeout(20_000) { deferred.await() }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      null
    } finally {
      runCatching { socket.close(1000, null) }
    }
  }

  private suspend fun fetchSnapshot(id: String): JSONObject =
    fetchFirstFrame("session/follow", payload(JSONObject().put("address", address(id))))
      ?: throw ApiException(502, "无法读取会话日志：DSH 没有返回快照")

  private fun address(id: String): JSONObject = JSONObject().put("kind", "session").put("sessionId", id)

  // ---------------------------------------------------------------- 事件翻译

  /**
   * 把一条 DSH 会话事件翻译成 Lagoon 认识的事件；一次输入可能产出多条（例如 `assistant/message`
   * 里的多个内容块）。不认识的记录（`user/message`、`tool/result` 之外的内部事件）返回空列表。
   */
  private fun translate(session: Session, event: JSONObject): List<ServerEvent> {
    val type = event.str("type")
    val seq = event.optDouble("seq", 0.0).toLong()
    val time = event.optDouble("time", 0.0).toLong()
    val data = event.obj("data")
    val output = mutableListOf<ServerEvent>()
    fun emit(name: String, properties: JSONObject) {
      output += ServerEvent(id = "dsh:$seq:${output.size}", directory = session.directory, type = name, properties = properties, created = time)
    }
    val base = JSONObject().put("sessionID", session.id)
    when (type) {
      "turn/start" -> emit("session.execution.started", base.put("at", time))
      "turn/end" -> {
        val reason = data.obj("reason")
        when (reason.str("kind")) {
          "completed" -> emit("session.execution.succeeded", base)
          "aborted" -> emit("session.execution.interrupted", base.put("reason", reason.obj("reason").str("kind").ifBlank { "user" }))
          else -> {
            val error = reason.obj("error")
            emit("session.execution.failed", base.put("error", JSONObject()
              .put("type", error.str("code").ifBlank { "error" })
              .put("message", error.str("message").ifBlank { "执行失败" })))
          }
        }
      }
      "step/start" -> emit("session.step.started", base
        .put("assistantMessageID", messageId(data))
        .put("started", time))
      "step/end" -> emit("session.step.ended", base
        .put("assistantMessageID", messageId(data))
        .put("finish", "stop"))
      "assistant/message" -> {
        val assistantId = messageId(data)
        val message = data.obj("message")
        for (block in message.arr("content").objects()) {
          when (block.str("type")) {
            "reasoning" -> {
              emit("session.reasoning.started", base.put("assistantMessageID", assistantId).put("started", time))
              emit("session.reasoning.ended", base.put("assistantMessageID", assistantId).put("text", block.str("text")))
            }
            "text" -> {
              emit("session.text.started", base.put("assistantMessageID", assistantId).put("started", time))
              emit("session.text.ended", base.put("assistantMessageID", assistantId).put("text", block.str("text")))
            }
            // 工具调用由独立的 `tool/call` 记录承载，避免同一调用出现两行。
            else -> Unit
          }
        }
      }
      "tool/call" -> {
        val callId = data.str("callId")
        val name = data.str("name")
        if (callId.isNotBlank()) {
          val input = parseArguments(data.str("arguments"))
          emit("session.tool.input.started", base.put("assistantMessageID", messageId(data)).put("id", callId).put("name", name))
          emit("session.tool.called", base.put("assistantMessageID", messageId(data)).put("id", callId).put("name", name).put("input", input))
        }
      }
      "tool/result" -> {
        val message = data.obj("message")
        val callId = message.str("toolCallId").ifBlank { data.str("callId") }
        if (callId.isNotBlank()) {
          val content = message.arr("content")
          val json = JSONArray()
          for (block in content.objects()) {
            if (block.str("type") == "text") json.put(JSONObject().put("type", "text").put("text", block.str("text")))
          }
          emit("session.tool.success", base
            .put("assistantMessageID", messageId(data))
            .put("id", callId)
            .put("content", json)
            .put("metadata", JSONObject()))
        }
      }
      "session/title" -> emit("session.renamed", base.put("title", data.str("title")))
      "approval/asked", "todo/write" -> Unit
      else -> Unit
    }
    return output
  }

  /** `msg_<turn>:<step>`：同一步的 `step/start`、`assistant/message`、`tool/call` 共用一个消息 id。 */
  private fun messageId(data: JSONObject): String {
    val turn = data.optDouble("turn", 0.0).toLong()
    val step = data.optDouble("step", 0.0).toLong()
    return "msg_$turn:$step"
  }

  private fun parseArguments(raw: String): Any =
    if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse { raw }

  // ---------------------------------------------------------------- 事件流

  override fun events(onOpen: () -> Unit): Flow<ServerEvent> = callbackFlow {
    val channel = Channel<ServerEvent>(Channel.UNLIMITED)
    eventChannel = channel
    var closed = false
    val push: (ServerEvent) -> Unit = { event -> channel.trySend(event) }

    val globalSocket = try {
      openMux("\$events", JSONObject(), onItem = { frame ->
        when (frame.str("type")) {
          "ready" -> {
            eventsClientId = frame.str("clientId")
            onOpen()
          }
          "emit" -> handleEmit(frame, push)
          "waterfall" -> handleWaterfall(frame, push)
          else -> Unit
        }
      }, onClosed = { cause ->
        if (!closed) {
          closed = true
          close(cause)
        }
      })
    } catch (error: Exception) {
      close(error)
      awaitClose { }
      return@callbackFlow
    }

    val pump = launch {
      for (event in channel) send(event)
    }

    // 已在观察中的会话补上 follow，运行中的会话也会从 $events 的 api-session/status 补进来。
    synchronized(watched) { watched.toList() }.forEach { startFollow(it, channel) }

    awaitClose {
      closed = true
      runCatching { globalSocket.close(1000, null) }
      followers().forEach { runCatching { it.close(1000, null) } }
      follows.clear()
      pump.cancel()
      channel.close()
      if (eventChannel === channel) eventChannel = null
    }
  }

  private fun followers(): List<WebSocket> = follows.values.toList()

  private fun handleEmit(frame: JSONObject, push: (ServerEvent) -> Unit) {
    val name = frame.str("event")
    val args = frame.arr("args").objects()
    when (name) {
      "api-session/added" -> {
        val item = args.firstOrNull() ?: return
        val session = itemToSession(item) ?: return
        push(ServerEvent(id = "dsh:added:${session.id}:${session.updated}", directory = session.directory,
          type = "session.created", properties = JSONObject().put("sessionID", session.id).put("title", session.title), created = session.updated))
      }
      "api-session/removed" -> {
        val item = args.firstOrNull() ?: return
        val id = item.str("sessionId").ifBlank { item.str("id") }
        if (id.isNotBlank()) {
          known.remove(id)
          watched.remove(id)
          follows.remove(id)?.close(1000, null)
          push(ServerEvent(id = "dsh:removed:$id", directory = "", type = "session.deleted",
            properties = JSONObject().put("sessionID", id), created = System.currentTimeMillis()))
        }
      }
      // api-session/activity|status 只影响列表排序与运行标记，控制器会定期对账。
      else -> Unit
    }
  }

  private fun handleWaterfall(frame: JSONObject, push: (ServerEvent) -> Unit) {
    val name = frame.str("event")
    val eventId = frame.str("eventId")
    val sessionId = frame.str("agentId")
    val request = frame.obj("request")
    val session = known[sessionId]
    val directory = session?.directory.orEmpty()
    when (name) {
      "approval/request" -> {
        if (eventId.isBlank()) return
        val toolName = request.str("toolName").ifBlank { "工具" }
        val reason = request.str("reason")
        val properties = JSONObject()
          .put("id", eventId)
          .put("sessionID", sessionId)
          .put("action", toolName)
          .put("resources", JSONArray().put(reason.ifBlank { toolName }))
          .put("message", reason)
          .put("save", JSONArray())
          .put("source", JSONObject().put("messageID", "").put("id", request.str("callId")))
        val permission = try { properties.toPermission(directory) } catch (error: Exception) { null }
        if (permission != null) {
          pendingApprovals[eventId] = permission
          waterfallRequests[eventId] = eventId
        }
        push(ServerEvent(id = "dsh:approval:$eventId", directory = directory, type = "permission.asked",
          properties = properties, created = System.currentTimeMillis()))
      }
      "user-questions/request" -> {
        if (eventId.isBlank()) return
        val questions = JSONArray()
        for (question in request.arr("questions").objects()) {
          val options = JSONArray()
          for (option in question.arr("options").objects()) {
            options.put(JSONObject()
              .put("label", option.str("label"))
              .put("description", option.str("description")))
          }
          questions.put(JSONObject()
            .put("title", question.str("question"))
            .put("options", options)
            .put("multiple", question.optBoolean("multiSelect"))
            .put("custom", true)
            .put("field", question.str("id")))
        }
        val properties = JSONObject()
          .put("id", eventId)
          .put("sessionID", sessionId)
          .put("questions", questions)
        val form = try { properties.toForm(directory) } catch (error: Exception) { null }
        if (form != null) pendingQuestions[eventId] = form
        push(ServerEvent(id = "dsh:question:$eventId", directory = directory, type = "form.created",
          properties = properties, created = System.currentTimeMillis()))
      }
    }
  }

  /** 回一条瀑布：`{clientId, eventId, outcome}`，结果值按事件类型自己决定。 */
  private suspend fun settle(eventId: String, value: Any?) {
    val clientId = eventsClientId ?: throw ApiException(409, "事件流尚未连接，请稍后重试")
    val outcome = JSONObject().put("kind", "result")
    if (value != null) outcome.put("value", value)
    call("${'$'}events/result", JSONObject().put("clientId", clientId).put("eventId", eventId).put("outcome", outcome))
  }

  private fun startFollow(id: String, channel: Channel<ServerEvent>) {
    if (follows.containsKey(id)) return
    val session = known[id] ?: return
    ioScope.launch {
      try {
        val socket = openMux("session/follow", payload(JSONObject().put("address", address(id))), onItem = { frame ->
          when (frame.str("type")) {
            "snapshot" -> {
              frame.optDouble("cursor", 0.0).toLong().takeIf { it > 0 }?.let { cursors[id] = it }
              frame.arr("records").objects().forEach { record ->
                translate(session, record.obj("event")).forEach { channel.trySend(it) }
              }
            }
            "event" -> translate(session, frame.obj("event")).forEach { channel.trySend(it) }
            else -> Unit
          }
        }, onClosed = { follows.remove(id) })
        follows[id] = socket
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        Diagnostics.warn("DshApi", "会话事件流未建立", error)
      }
    }
  }

  private fun stopFollow(id: String) {
    follows.remove(id)?.close(1000, null)
  }

  /** 记录最近打开的会话并限制 follow 数量，避免长连堆积。 */
  private fun rememberWatched(sessions: List<Session>) {
    synchronized(watched) {
      for (session in sessions) {
        watched.remove(session.id)
        watched.add(session.id)
      }
      while (watched.size > WATCH_LIMIT) {
        val oldest = watched.first()
        watched.remove(oldest)
        stopFollow(oldest)
      }
    }
    val channel = eventChannel ?: return
    sessions.forEach { startFollow(it.id, channel) }
  }

  // ---------------------------------------------------------------- 记录折算

  /**
   * 把 DSH 的事件日志折算成 OpenCode 形状的消息：一条 `(turn, step)` 就是一条 assistant 消息，
   * `user/message` 是一条用户消息。工具调用的输入来自 `tool/call`，输出来自 `tool/result`。
   */
  private fun recordsToMessages(session: Session, records: List<JSONObject>): List<Message> {
    data class ToolEntry(val id: String, val name: String, val input: JSONObject, var output: String, var done: Boolean)
    data class Step(val turn: Long, val step: Long, var started: Long, val blocks: MutableList<JSONObject>, val tools: MutableMap<String, ToolEntry>, var ended: Long)

    val steps = LinkedHashMap<Pair<Long, Long>, Step>()
    val users = mutableListOf<Pair<Long, JSONObject>>()
    fun stepOf(data: JSONObject): Step {
      val turn = data.optDouble("turn", 0.0).toLong()
      val number = data.optDouble("step", 0.0).toLong()
      return steps.getOrPut(turn to number) { Step(turn, number, 0, mutableListOf(), mutableMapOf(), 0) }
    }

    for (record in records) {
      val event = record.obj("event")
      val type = event.str("type")
      val time = event.optDouble("time", 0.0).toLong()
      val data = event.obj("data")
      when (type) {
        "step/start" -> stepOf(data).let { if (it.started == 0L) it.started = time }
        "step/end" -> stepOf(data).ended = time
        "user/message" -> users += time to data
        "assistant/message" -> {
          val step = stepOf(data)
          for (block in data.obj("message").arr("content").objects()) {
            if (block.str("type") == "tool-call") continue
            step.blocks += block
          }
        }
        "tool/call" -> {
          val step = stepOf(data)
          val callId = data.str("callId")
          if (callId.isNotBlank()) step.tools[callId] = ToolEntry(callId, data.str("name"), parseArguments(data.str("arguments")) as? JSONObject ?: JSONObject(), "", false)
        }
        "tool/result" -> {
          val step = stepOf(data)
          val message = data.obj("message")
          val callId = message.str("toolCallId").ifBlank { data.str("callId") }
          step.tools[callId]?.let { entry ->
            entry.output = message.arr("content").objects().mapNotNull { block -> block.str("text").takeIf(String::isNotBlank) }.joinToString("\n")
            entry.done = true
          }
        }
      }
    }

    val entries = mutableListOf<Pair<Long, Message>>()
    for ((created, data) in users) {
      val text = data.arr("content").objects().mapNotNull { it.str("text").takeIf(String::isNotBlank) }.joinToString("\n")
      val id = data.str("id").ifBlank { "user:${data.optDouble("seq", 0.0).toLong()}" }
      val json = JSONObject()
        .put("id", id)
        .put("type", "user")
        .put("text", text)
        .put("time", JSONObject().put("created", created))
        .put("metadata", JSONObject().put("displayText", text))
      entries += created to json.toMessage()
    }
    for (step in steps.values) {
      val id = "msg_${step.turn}:${step.step}"
      val content = JSONArray()
      for (block in step.blocks) {
        val kind = block.str("type")
        if (kind != "text" && kind != "reasoning") continue
        content.put(JSONObject().put("type", kind).put("text", block.str("text")))
      }
      for (tool in step.tools.values) {
        val state = JSONObject()
          .put("status", if (tool.done) "completed" else "running")
          .put("input", tool.input)
          .put("metadata", JSONObject())
        if (tool.output.isNotBlank()) {
          state.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", tool.output)))
        }
        content.put(JSONObject().put("type", "tool").put("id", tool.id).put("name", tool.name).put("state", state))
      }
      if (content.length() == 0) continue
      val json = JSONObject()
        .put("id", id)
        .put("type", "assistant")
        .put("content", content)
        .put("time", JSONObject().put("created", step.started).putOpt("completed", step.ended.takeIf { it > 0 }))
        .put("finish", "stop")
        .putOpt("agent", session.agent)
      val model = session.model
      if (model != null) {
        json.put("model", JSONObject().put("providerID", model.providerId).put("id", model.modelId).put("name", model.label))
      }
      entries += step.started to json.toMessage()
    }
    entries.sortBy { it.first }
    return entries.map { it.second }
  }

  // ---------------------------------------------------------------- 写操作

  override suspend fun inbox(id: String): List<Message> = emptyList()

  override suspend fun activeSessions(): Set<String> = withContext(Dispatchers.IO) {
    val listing = listing()
    val sessions = listing.items.mapNotNull { itemToSession(it) }
    rememberWatched(sessions.filter { listing.running.contains(it.id) })
    listing.running
  }

  override suspend fun shells(directory: String): List<ShellJob> = emptyList()

  override suspend fun createSession(directory: String, title: String): Session = withContext(Dispatchers.IO) {
    val request = JSONObject().put("cwd", directory)
    if (title.isNotBlank()) request.put("title", title)
    val result = callObject("session/create", payload(request))
    val id = result.str("sessionId").ifBlank { throw ApiException(502, "服务器没有返回新会话 id") }
    val created = Session(
      id = id, directory = directory, title = title.ifBlank { "新会话" },
      updated = System.currentTimeMillis(), projectId = directory, created = System.currentTimeMillis(),
      agent = result.str("agentPreset").ifBlank { null }
    )
    known[id] = created
    if (title.isNotBlank()) runCatching { renameSession(created, title) }
    created
  }

  override suspend fun renameSession(session: Session, title: String) {
    callObject("session/rename", payload(JSONObject().put("sessionId", session.id).put("title", title)))
    known[session.id] = session.copy(title = title)
  }

  override suspend fun setArchived(session: Session, archived: Boolean, now: Long) {
    val request = JSONObject().put("sessionId", session.id).put("stopActivity", true)
    callObject(if (archived) "workspace/archiveSession" else "workspace/unarchiveSession", payload(request))
    known[session.id] = session.copy(archived = archived)
  }

  override suspend fun deleteSession(session: Session) {
    // DSH 没有删除会话的 RPC：归档是唯一可逆的“移出列表”语义。
    setArchived(session, true)
  }

  override suspend fun forkSession(session: Session): Session = withContext(Dispatchers.IO) {
    val result = callObject("session/fork", payload(JSONObject().put("sessionId", session.id)))
    val id = result.str("sessionId").ifBlank { throw ApiException(502, "服务器没有返回分支会话") }
    known[id] ?: session.copy(id = id, title = "${session.title} 副本", updated = System.currentTimeMillis())
  }

  override suspend fun abort(session: Session) {
    callObject("session/cancel", payload(JSONObject().put("sessionId", session.id)))
  }

  override suspend fun compact(session: Session) {
    throw ApiException(501, "DeepSeek Harness 没有手动压缩接口")
  }

  override suspend fun revert(session: Session, messageId: String) {
    throw ApiException(501, "DeepSeek Harness 不支持回退消息")
  }

  override suspend fun unrevert(session: Session) {
    throw ApiException(501, "DeepSeek Harness 不支持回退消息")
  }

  override suspend fun send(
    session: Session,
    text: String,
    agent: String?,
    model: ModelChoice?,
    references: List<FileReference>,
    inline: List<InlineFile>,
    id: String,
    delivery: Delivery
  ) {
    if (model != null) runCatching { selectModel(session, model) }
    val content = JSONArray()
    if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
    for (file in inline) {
      val payload = inlineContent(file) ?: continue
      content.put(payload)
    }
    val request = JSONObject()
      .put("requestId", id)
      .put("sessionId", session.id)
      .put("mode", delivery.wire)
      .put("content", content)
      .put("clientTimeZone", java.util.TimeZone.getDefault().id)
    callObject("session/prompt", payload(request))
  }

  /** 图片附件走 DSH 的多模态内容块；其他类型暂时只保留文件名提示。 */
  private fun inlineContent(file: InlineFile): JSONObject? {
    val mime = file.mime.lowercase()
    if (!mime.startsWith("image/")) return null
    return try {
      val bytes = readInlineBytes(file) ?: return null
      JSONObject()
        .put("type", "image")
        .put("mediaType", mime)
        .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        .put("name", file.name)
    } catch (error: Exception) {
      Diagnostics.warn("DshApi", "附件无法读取，已跳过", error)
      null
    }
  }

  private fun readInlineBytes(file: InlineFile): ByteArray? {
    val uri = android.net.Uri.parse(file.uri)
    return when (uri.scheme) {
      "file" -> uri.path?.let { java.io.File(it).takeIf(java.io.File::isFile)?.readBytes() }
      else -> null
    }
  }

  override suspend fun command(
    session: Session,
    name: String,
    arguments: String,
    agent: String?,
    model: ModelChoice?,
    references: List<FileReference>,
    inline: List<InlineFile>,
    delivery: Delivery
  ) {
    // DSH 没有 slash command 路由：按普通提示词发送，保留命令原文。
    send(session, "/$name $arguments".trim(), agent, model, references, inline, MessageIds.ascending(), delivery)
  }

  private suspend fun selectModel(session: Session, model: ModelChoice) {
    val request = JSONObject()
      .put("sessionId", session.id)
      .put("provider", model.providerId)
      .put("model", model.modelId)
    model.variant?.takeIf { it.isNotBlank() }?.let { request.put("reasoningEffort", it) }
    callObject("session/selectModel", payload(request))
    known[session.id] = session.copy(model = model)
  }

  // ---------------------------------------------------------------- 目录 / 模型 / 权限

  override suspend fun agents(directory: String): List<AgentChoice> = withContext(Dispatchers.IO) {
    val names = LinkedHashSet<String>()
    for (item in listing().items) {
      item.obj("projections").obj("values").str("agentPreset").takeIf(String::isNotBlank)?.let(names::add)
    }
    names.map { AgentChoice(it, "") }
  }

  override suspend fun commands(directory: String): List<CommandChoice> = emptyList()

  override suspend fun modelCatalog(directory: String): List<ModelInfo> = withContext(Dispatchers.IO) {
    val result = catalog ?: callObject("session/modelCatalog").also { catalog = it }
    val models = mutableListOf<ModelInfo>()
    for (group in result.arr("groups").objects()) {
      val providerId = group.str("id")
      val providerName = group.str("name")
      for (model in group.arr("models").objects()) {
        val modelId = model.str("id").ifBlank { continue }
        val variants = model.obj("reasoning").arr("efforts").objects().mapNotNull { effort ->
          val effortId = effort.str("id").ifBlank { return@mapNotNull null }
          ModelVariant(effortId, effort.str("name").ifBlank { effortId })
        }
        models += ModelInfo(
          choice = ModelChoice(providerId, modelId, model.str("name").ifBlank { modelId }, variants.firstOrNull()?.id),
          providerName = providerName.ifBlank { providerId },
          variants = variants
        )
      }
    }
    models
  }

  override suspend fun sessionPermissions(session: Session): List<PermissionRequest> =
    pendingApprovals.values.filter { it.sessionId == session.id }

  override suspend fun pendingPermissions(directory: String): List<PermissionRequest> =
    pendingApprovals.values.toList()

  override suspend fun forms(directory: String): List<QuestionRequest> = pendingQuestions.values.toList()

  override suspend fun replyPermission(request: PermissionRequest, reply: String) {
    val eventId = waterfallRequests.remove(request.id) ?: request.id
    pendingApprovals.remove(eventId)
    val outcome = when (reply) {
      "once", "always" -> "allowed-once"
      else -> "rejected"
    }
    settle(eventId, outcome)
  }

  override suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    val eventId = request.id
    pendingQuestions.remove(eventId)
    val payload = JSONArray()
    request.questions.forEachIndexed { index, prompt ->
      val selected = answers.getOrNull(index).orEmpty().filter(String::isNotBlank)
      payload.put(JSONObject()
        .put("id", prompt.field.ifBlank { prompt.title })
        .put("selected", JSONArray(selected)))
    }
    settle(eventId, JSONObject().put("answers", payload))
  }

  override suspend fun rejectQuestion(request: QuestionRequest) {
    pendingQuestions.remove(request.id)
    val clientId = eventsClientId ?: throw ApiException(409, "事件流尚未连接，请稍后重试")
    val outcome = JSONObject()
      .put("kind", "rejected")
      .put("error", JSONObject().put("message", "用户取消了回答"))
    call("${'$'}events/result", JSONObject().put("clientId", clientId).put("eventId", request.id).put("outcome", outcome))
  }

  override suspend fun children(session: Session): List<Session> = withContext(Dispatchers.IO) {
    listing().items.mapNotNull { itemToSession(it) }.filter { it.parentId == session.id }
  }

  override suspend fun diff(session: Session): List<FileChange> = emptyList()

  override suspend fun files(directory: String, path: String): List<FileNode> = emptyList()

  override suspend fun fileContent(directory: String, path: String): FileContent {
    throw ApiException(501, "DeepSeek Harness 客户端暂不支持浏览工作区文件")
  }

  override suspend fun searchFiles(directory: String, query: String): List<String> = emptyList()

  override suspend fun health(): String = withContext(Dispatchers.IO) {
    listing()
    "DeepSeek Harness"
  }

  private companion object {
    const val WATCH_LIMIT = 12
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}
