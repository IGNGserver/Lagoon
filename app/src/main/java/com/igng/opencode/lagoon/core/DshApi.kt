package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
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

/**
 * DeepSeek Harness（DSH）客户端。
 *
 * 目标表面是 `dsh web` 的 Typert RPC Gateway——官方 Web UI 使用的同一套协议，也是 CLI/MCP 之外
 * 唯一的全功能客户端入口（`dsh sdk` / `acp` 是能力受限的 stdio 表面）。完整契约见
 * `docs/dsh-protocol.md`：
 *
 * - 一元调用：`POST /api/<namespace>/<method>`，信封 `{type:"client-request", rpcId, method, payload}`，
 *   `payload` 只包含一个 `args` 对象，字段是方法形参名（`signal` 除外；常见形状是 `{request:…}`，
 *   `session/list` 是 `{_request:{}}`，无参方法是 `{}`）。
 * - 长流：`/api/remote.mux` WebSocket 的多路复用 `open`/`item`/`end`/`error`/`cancel` 帧。
 * - 全局事件与审批/问答瀑布：`$events` 逻辑流；回应走 `POST /api/$events/result`。
 * - 凭据：启动令牌换签名 Cookie。主路线是浏览器式根路径交换（`GET /?token=…`，长寿命 Cookie，
 *   跨 dsh 重启仍有效）；备选是 API Peer 的 `GET /connect?token=…&interactions=answer`
 *   （0.2.1 线起提供，Peer 随生命周期连接与进程重启失效）。
 *
 * 本类把 DSH 的会话模型（turn/step 事件日志 + 实时 assistant 流）翻译成 Lagoon 已在用的
 * OpenCode 形状：会话列表、消息时间线、执行事件、审批与问答。控制器和 UI 因此不需要知道
 * 服务端是哪一种。
 */
class DshApi(
  private val profile: ServerProfile,
  private val credentials: ServerCredentials,
  callTimeoutSeconds: Long = 60,
  /**
   * 换到新的浏览器 Cookie，或确认已落盘的 Cookie 被服务端拒绝（传空串）时回调，由调用方加密落盘。
   * 落盘的 Cookie 跨 App 与 dsh 重启复用，启动令牌只在没有可用 Cookie 时才用得上。
   */
  private val onCookie: (String) -> Unit = {},
  private val clock: () -> Long = System::currentTimeMillis
) : HarnessClient {
  constructor(profile: ServerProfile, password: String) : this(profile, ServerCredentials(profile.username, password))

  private val base: HttpUrl = requireNotNull(profile.url.trimEnd('/').toHttpUrlOrNull()) { "服务器地址无效" }
  private val origin = HttpOrigin.of(base)

  /** 凭据的 password 槽位：令牌模式是启动令牌，密钥模式是 Cookie 签名密钥。 */
  private val token: String = if (profile.dshAuth == DshAuthMode.TOKEN) credentials.password.trim() else ""
  private val signingSecret: ByteArray? =
    if (profile.dshAuth == DshAuthMode.SECRET) DshCookie.decodeSecret(credentials.password) else null

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
    .addInterceptor(HttpMetrics.interceptor)
    .build()

  private val streamHttp = http.newBuilder()
    // Long-lived streams would pin the in-flight counter; only request/response calls are measured.
    .apply { interceptors().remove(HttpMetrics.interceptor) }
    .dispatcher(SharedHttp.streamDispatcher)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    // 长连心跳：Wi-Fi/NAT 会静默回收空闲连接，没有 ping 时事件流会「看起来在线」却再也收不到帧。
    .pingInterval(STREAM_PING_SECONDS, TimeUnit.SECONDS)
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
  /** 仍在等待回答的审批/问答瀑布；键是网关事件 id。 */
  private val pendingApprovals = ConcurrentHashMap<String, PermissionRequest>()
  private val pendingQuestions = ConcurrentHashMap<String, QuestionRequest>()
  /** 当前 `$events` 客户端代次与它重新投递过的瀑布 id（断线重连后对账用）。 */
  private val readyGeneration = java.util.concurrent.atomic.AtomicInteger()
  private val redelivered = ConcurrentHashMap.newKeySet<String>()
  /** 会话 follow 意外断开后的重试退避（毫秒），收到帧即清零。 */
  private val followRetries = ConcurrentHashMap<String, Long>()
  @Volatile private var catalog: JSONObject? = null
  /** 已归档会话集合（`workspace/follow` 的 baseline 与 `archived` 增量维护）。 */
  private val archivedSessions = ConcurrentHashMap.newKeySet<String>()
  /** `workspace/follow` 的最新工作区清单；null 表示尚未读取。 */
  @Volatile private var workspaceItems: List<JSONObject>? = null
  private val workspaceLock = Any()
  /** 每个会话的作业清单缓存：`job/list` 是每会话一条流，短 TTL 避免对账风暴。 */
  private val jobCache = ConcurrentHashMap<String, Pair<Long, List<JSONObject>>>()
  /** 实时流里已经建立文本/推理部件的 `sessionId|turn|step` 键与部件种类。 */
  private val liveParts = ConcurrentHashMap<String, MutableSet<String>>()
  /** 活跃 attempt 的 `attemptId → sessionId|turn|step`（chunk 帧不带 turn/step）。 */
  private val liveAttempts = ConcurrentHashMap<String, String>()
  /** 每个会话已经投影过的最大事件 seq：消息页与实时流之间按 seq 去重。 */
  private val lastSeqs = ConcurrentHashMap<String, Long>()

  override fun capabilities(): ApiCapabilities = CAPABILITIES

  override suspend fun discoverCapabilities(): ApiCapabilities = CAPABILITIES

  // ---------------------------------------------------------------- 鉴权

  /** 当前使用的 Cookie；先取落盘的浏览器 Cookie，过期或缺失时才用令牌/密钥换新的。 */
  @Volatile private var sessionCookie: String? =
    credentials.cookie.trim().takeIf { DshCookie.isFresh(it, clock()) && (signingSecret == null || DshCookie.isDurable(it)) }
  /** 落盘 Cookie 被服务端拒绝过：换新失败时提示「需要重新配对」而不是泛泛的令牌错误。 */
  @Volatile private var cookieRejected = false
  @Volatile private var renewAttemptedAt = 0L
  private val authLock = Mutex()

  private suspend fun cookie(): String = authLock.withLock {
    val current = sessionCookie?.takeIf { DshCookie.isFresh(it, clock()) }
    if (current != null) {
      scheduleRenewal(current)
      return@withLock current
    }
    sessionCookie = null
    connect().also(::adopt)
  }

  private fun repairMessage(): String = if (signingSecret != null) SECRET_REJECTED_MESSAGE else REPAIR_MESSAGE

  /** 采用新 Cookie；浏览器 Cookie 回调落盘，API Peer Cookie 只放内存（dsh 重启即失效）。 */
  private fun adopt(pair: String) {
    sessionCookie = pair
    cookieRejected = false
    // 密钥模式随时能在本机重签，不需要落盘。
    if (signingSecret == null && DshCookie.isDurable(pair) && pair != credentials.cookie.trim()) runCatching { onCookie(pair) }
  }

  /**
   * 密钥模式下 HTTPS 地址的 Cookie audience 取决于 dsh 自己是否是 TLS 监听：直连 TLS 是 `https://host`，
   * HTTPS 反向代理后面的 HTTP 监听是 `host`。先按 TLS 签发，被拒后改用后者再试一次。
   */
  @Volatile private var plainListener = false

  /** 服务端拒绝了当前 Cookie（401）：丢弃，并把落盘副本一起清掉，避免每次启动都先撞一次 401。 */
  private suspend fun rejectCookie(pair: String) = authLock.withLock {
    if (sessionCookie != pair) return@withLock
    sessionCookie = null
    if (signingSecret != null && base.isHttps && !plainListener) {
      plainListener = true
      return@withLock
    }
    cookieRejected = true
    if (DshCookie.isDurable(pair)) runCatching { onCookie("") }
  }

  /**
   * 滑动续期：Cookie 有绝对有效期，签发超过 [RENEW_AFTER_MILLIS] 后用手里的凭据在后台换一张新的。
   * 令牌模式只在该 dsh 进程仍存活（令牌未轮换）时成功，失败静默；密钥模式在本机直接重新签发。
   */
  private fun scheduleRenewal(current: String) {
    val lifetime = DshCookie.lifetime(current) ?: return
    if (lifetime.version != 1) return
    val now = clock()
    val dueAt = if (signingSecret != null) lifetime.expiresAt - MINTED_RENEW_MARGIN_MILLIS else lifetime.issuedAt + RENEW_AFTER_MILLIS
    if (now < dueAt || now - renewAttemptedAt < RENEW_RETRY_MILLIS) return
    if (signingSecret == null && token.isEmpty()) return
    renewAttemptedAt = now
    ioScope.launch {
      val fresh = try { connect() } catch (error: CancellationException) { throw error } catch (error: Exception) { null }
      if (fresh != null && DshCookie.isDurable(fresh)) authLock.withLock { if (sessionCookie == current) adopt(fresh) }
    }
  }

  /**
   * 换新 Cookie。密钥模式在本机签发；令牌模式先走浏览器式根路径交换（Cookie 跨进程重启有效），
   * 再退回 `/connect` 的 API Peer（0.2.1 线起；Peer 随生命周期连接与进程重启失效）。
   */
  private suspend fun connect(): String = withContext(Dispatchers.IO) {
    signingSecret?.let { return@withContext DshCookie.mint(it, base, clock(), secureListener = base.isHttps && !plainListener) }
    if (profile.dshAuth == DshAuthMode.SECRET) throw ApiException(401, "签名密钥格式不正确：应为 43 个字符的 base64url 字符串", AUTH_EXPIRED)
    if (token.isEmpty()) throw ApiException(401, "请填写 DeepSeek Harness 的启动令牌", AUTH_EXPIRED)
    exchange(base.newBuilder().addQueryParameter("token", token).build(), browser = true)?.let { return@withContext it }
    // 根路径没有交付 Cookie（不是 dsh 的认证首页）：退回 API Peer。令牌被拒（401/403）时同一个令牌
    // 换 /connect 也不会成功，上面已经直接抛出。
    try {
      exchange(
        base.newBuilder()
          .addPathSegment("connect")
          .addQueryParameter("token", token)
          .addQueryParameter("interactions", "answer")
          .build(),
        browser = false
      ) ?: throw ApiException(401, "连接成功但没有拿到会话 Cookie，请确认地址指向 dsh web 且启动令牌来自该实例", AUTH_EXPIRED)
    } catch (error: ApiException) {
      // /connect 不存在（0.2.0 线）：说明根路径就不是 dsh 的认证入口。
      if (error.status == 404) throw ApiException(401, "连接成功但没有拿到会话 Cookie，请确认地址指向 dsh web 且启动令牌来自该实例", AUTH_EXPIRED)
      throw error
    }
  }

  /** Host 用 3xx + `Set-Cookie: dsh-auth-…` 交付签名 Cookie；没有 Cookie 时返回 null。 */
  private suspend fun exchange(url: HttpUrl, browser: Boolean): String? {
    val builder = Request.Builder().url(url).get()
    // 根路径交换是浏览器入口；带上 Accept 与浏览器一致，避免被按静态资源处理。
    if (browser) builder.header("Accept", "text/html,application/xhtml+xml")
    val response = try {
      http.newCall(builder.build()).execute()
    } catch (error: IOException) {
      throw ApiException(0, "无法连接 DeepSeek Harness：${error.message ?: "网络不可达"}")
    }
    response.use { value ->
      when (value.code) {
        401 -> throw ApiException(401, if (cookieRejected) REPAIR_MESSAGE else "启动令牌无效：dsh 每次启动都会换新令牌，请填写当前启动链接里的 token", AUTH_EXPIRED)
        403 -> throw ApiException(403, UNTRUSTED_HOST_MESSAGE, "http/403")
        !in 200..399 -> throw ApiException(value.code, "连接失败：HTTP ${value.code}")
      }
      for (raw in value.headers("Set-Cookie")) {
        val pair = raw.substringBefore(';').trim()
        if (pair.startsWith("dsh-auth-")) return pair
      }
      return null
    }
  }

  // ---------------------------------------------------------------- RPC

  /** 0.2.x 的 payload 只包含一个 `args` 对象，字段是方法形参名（`signal` 除外）。 */
  private fun argsOf(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().also { out ->
    for ((key, value) in fields) if (value != null) out.put(key, value)
  }

  private fun envelope(args: JSONObject): JSONObject = JSONObject().put("args", args)

  /** 常见的 `{request: …}` 形状。 */
  private fun requestArgs(request: JSONObject): JSONObject = envelope(argsOf("request" to request))

  private fun noArgs(): JSONObject = envelope(JSONObject())

  /** 一元调用的请求信封是 `{type, rpcId, method, payload}`；响应是 `{type, rpcId, result}`。 */
  private suspend fun call(endpoint: String, payload: JSONObject = noArgs(), retried: Boolean = false): Any? =
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
        // Cookie 失效（过期、签名密钥被撤销，或 API Peer 随连接/进程消失）：丢弃后重新换一张。
        rejectCookie(current)
        return@withContext call(endpoint, payload, retried = true)
      }
      if (status == 401) throw ApiException(401, repairMessage(), AUTH_EXPIRED)
      if (status !in 200..299) throw ApiException(status, httpMessage(status), "http/$status")
      val parsed = try { JSONObject(text) } catch (error: Exception) {
        throw ApiException(status, "服务器响应不是 JSON，请确认地址指向 DeepSeek Harness 的 Web 实例")
      }
      val result = parsed.optJSONObject("result") ?: throw ApiException(status, "服务器响应缺少 result")
      if (result.optBoolean("ok")) return@withContext result.opt("value")
      val failure = result.optJSONObject("error")
      val code = failure?.optString("code").orEmpty()
      val message = failure?.optString("message").orEmpty()
      throw ApiException(status, friendlyMessage(code, message.ifBlank { "请求失败" }), code.ifBlank { null })
    }

  /** 已知的 DSH 错误码折算成用户可读文案；未知错误保留服务端原文（便于诊断协议漂移）。 */
  private fun friendlyMessage(code: String, upstream: String): String = when (code) {
    "session/not-found" -> "会话不存在或已被删除"
    "session/writer-held" -> "会话正被其他进程占用，请稍后重试"
    "session/agent-busy" -> "会话正忙，请稍后重试"
    "session/fork-unavailable" -> "会话还没有完成的轮次，无法分叉"
    "session/migration-required" -> "会话日志需要迁移，请先在 dsh 里打开一次"
    "workspace/session-active" -> "会话仍在运行，请先停止再归档"
    "workspace-file/not-found" -> "文件不存在或已被移动"
    "workspace-file/outside-workspace" -> "该路径在工作区之外"
    "workspace-file/not-directory" -> "目标不是目录"
    "workspace-file/too-large" -> "文件过大，无法读取"
    "gateway/service-unavailable" -> "该实例没有提供这项能力"
    "gateway/arguments-invalid" -> "请求与实例契约不一致，请更新 Lagoon 或 dsh"
    else -> upstream
  }

  private suspend fun callObject(endpoint: String, payload: JSONObject = noArgs()): JSONObject =
    call(endpoint, payload) as? JSONObject ?: JSONObject()

  private fun httpMessage(status: Int): String = when (status) {
    401 -> repairMessage()
    403 -> UNTRUSTED_HOST_MESSAGE
    404 -> "该地址不是 DeepSeek Harness 的 Web 实例"
    else -> "请求失败：HTTP $status"
  }

  /** 认证栅栏内的精确 GET 路由（改动摘要/对比）；失败返回 null。 */
  private suspend fun fetchJson(url: HttpUrl): JSONObject? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).header("Cookie", cookie()).get().build()
    try {
      http.newCall(request).execute().use { response ->
        if (response.code !in 200..299) return@withContext null
        val text = response.body?.string().orEmpty()
        runCatching { JSONObject(text) }.getOrNull()
      }
    } catch (error: IOException) {
      null
    }
  }

  // ---------------------------------------------------------------- 会话读

  /** `session/list` 的形状：`{items:[{sessionId,updatedAt,running,cwd,parentSessionId,projections,…}]}`。 */
  private data class Listing(val items: List<JSONObject>) {
    val running: Set<String>
      get() = items.filter { it.optBoolean("running") }.mapNotNull { it.str("sessionId").takeIf(String::isNotBlank) }.toSet()
  }

  private suspend fun listing(): Listing {
    val result = callObject("session/list", envelope(argsOf("_request" to JSONObject())))
    val items = result.arr("items").objects()
    // session/list 没有 directory 过滤参数；顺手刷新目录缓存，事件解码时要用。
    for (item in items) itemToSession(item)?.let { known[it.id] = it }
    return Listing(items)
  }

  private fun itemToSession(item: JSONObject): Session? {
    val id = item.str("sessionId").ifBlank { return null }
    val values = item.obj("projections").obj("values")
    // 0.2.x 把工作目录放在行顶层 `cwd`；`workingDirectory` 只是旧形状的兜底。
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
      archived = id in archivedSessions || previous?.archived == true,
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
    // DSH 的 Workspace 就是「项目」：workspace/follow 的 baseline 给出全量清单。
    val items = workspaceItems ?: fetchWorkspaceBaseline()
    items.mapNotNull { workspace ->
      val path = workspace.str("path").ifBlank { return@mapNotNull null }
      Project(
        id = workspace.str("workspaceId").ifBlank { path },
        directory = path,
        name = workspace.str("title").ifBlank { path.trimEnd('/').substringAfterLast('/').ifBlank { path } }
      )
    }
  }

  /** 取一次 workspace/follow 首帧并落缓存（事件流不在线时的兜底）。 */
  private suspend fun fetchWorkspaceBaseline(): List<JSONObject> {
    val frame = fetchFirstFrame("workspace/follow", noArgs()) ?: return emptyList()
    if (frame.str("type") == "baseline") applyWorkspaceFrame(frame)
    return workspaceItems ?: emptyList()
  }

  /** baseline / upsert / remove / order / archived 帧；pinned 暂不消费。 */
  private fun applyWorkspaceFrame(frame: JSONObject) {
    synchronized(workspaceLock) {
      when (frame.str("type")) {
        "baseline" -> {
          val value = frame.obj("value")
          workspaceItems = value.arr("items").objects()
          archivedSessions.clear()
          archivedSessions += value.arr("archivedSessionIds").strings()
        }
        "upsert" -> {
          val workspace = frame.obj("workspace")
          val id = workspace.str("workspaceId")
          if (id.isNotBlank()) workspaceItems = ((workspaceItems ?: emptyList()).filterNot { it.str("workspaceId") == id } + workspace)
        }
        "remove" -> {
          val id = frame.str("workspaceId")
          if (id.isNotBlank()) workspaceItems = (workspaceItems ?: emptyList()).filterNot { it.str("workspaceId") == id }
        }
        "order" -> {
          val order = frame.arr("workspaceIds").strings()
          val list = workspaceItems ?: return
          workspaceItems = order.mapNotNull { id -> list.firstOrNull { it.str("workspaceId") == id } } +
            list.filterNot { it.str("workspaceId") in order }
        }
        "archived" -> {
          archivedSessions.clear()
          archivedSessions += frame.arr("archivedSessionIds").strings()
        }
      }
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
      val records = snapshot.arr("records").objects()
      val messages = recordsToMessages(session, records)
      // 时间线被整页替换：清掉实时部件记录，让后续增量重建部件而不是写进已消失的行。
      liveParts.keys.filter { it.startsWith("${session.id}|") }.forEach { liveParts.remove(it) }
      advanceSeq(id, records.mapNotNull { it.obj("event").optDouble("seq", 0.0).toLong().takeIf { value -> value > 0 } }.maxOrNull())
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
      val page = callObject("session/page", requestArgs(request))
      val records = page.arr("records").objects()
      val messages = recordsToMessages(session, records)
      val next = if (page.optBoolean("hasMore")) earlierCursor(records) else null
      ApiPage(messages, next)
    }
  }

  private fun earlierCursor(records: List<JSONObject>): String? =
    records.mapNotNull { it.obj("event").optDouble("seq", 0.0).toLong().takeIf { value -> value > 0 } }.minOrNull()?.toString()

  /** 已投影的最大事件 seq（只前进不后退）；实时帧按它去重，避免与消息页快照重复。 */
  private fun lastSeq(sessionId: String): Long = lastSeqs[sessionId] ?: 0

  private fun advanceSeq(sessionId: String, seq: Long?) {
    if (seq == null || seq <= 0) return
    lastSeqs.compute(sessionId) { _, current -> if (current == null || seq > current) seq else current }
  }

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
    // 每条逻辑流只收尾一次：error 帧、end 帧、主动 cancel 与底层失败可能先后到达。
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    fun finish(webSocket: WebSocket, cause: Throwable?) {
      if (!finished.compareAndSet(false, true)) return
      // 立即关闭：mux 流不需要关闭握手，优雅关闭会让 MockWebServer 一类的对端等待超时。
      webSocket.cancel()
      onClosed(cause)
    }
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
          "error" -> finish(webSocket, IOException(frame.obj("error").str("message").ifBlank { "会话流出错" }))
          "end" -> finish(webSocket, null)
        }
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (response?.code == 401) {
          // Cookie 失效：丢弃，让下一次调用重新换取。
          ioScope.launch { rejectCookie(current) }
        }
        finish(webSocket, t)
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // 服务端主动关闭整条连接（dsh 重启、Host 下线）：按异常断开处理，让上层重连。
        finish(webSocket, IOException("DeepSeek Harness 关闭了连接（$code）"))
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        finish(webSocket, null)
      }
    }
    streamHttp.newWebSocket(request, listener)
  }

  /** 取一条流的首个 `item` 帧（`workspace/follow` 的清单、`session/follow` 的快照、`job/list` 的行）。 */
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
      runCatching { socket.cancel() }
    }
  }

  private suspend fun fetchSnapshot(id: String): JSONObject =
    fetchFirstFrame("session/follow", requestArgs(JSONObject().put("address", address(id))))
      ?: throw ApiException(502, "无法读取会话日志：DSH 没有返回快照")

  private fun address(id: String): JSONObject = JSONObject().put("kind", "session").put("sessionId", id)

  // ---------------------------------------------------------------- 事件翻译

  /**
   * 把一条 DSH 会话事件翻译成 Lagoon 认识的事件；一次输入可能产出多条（例如 `assistant/message`
   * 里的多个内容块）。不认识的记录（内部事件、log-only 标记）返回空列表。
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
          "completed", "forked" -> emit("session.execution.succeeded", base)
          "aborted", "blocked", "interrupted" -> emit("session.execution.interrupted",
            base.put("reason", reason.obj("reason").str("kind").ifBlank { "user" }))
          "max-tokens" -> emit("session.execution.failed", base.put("error", JSONObject()
            .put("type", "max-tokens")
            .put("message", "达到输出长度上限")))
          else -> {
            val error = reason.obj("error")
            emit("session.execution.failed", base.put("error", JSONObject()
              .put("type", error.str("code").ifBlank { reason.str("kind").ifBlank { "error" } })
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
        val key = liveKey(session.id, data)
        // 实时流已经建过部件时只用耐久文本收口，避免同一段文字出现两次。
        val live = liveParts.remove(key)
        val message = data.obj("message")
        for (block in message.arr("content").objects()) {
          when (block.str("type")) {
            "reasoning" -> {
              if (live?.contains("reasoning") != true) {
                emit("session.reasoning.started", base.put("assistantMessageID", assistantId).put("started", time))
              }
              emit("session.reasoning.ended", base.put("assistantMessageID", assistantId).put("text", block.str("text")))
            }
            "text" -> {
              if (live?.contains("text") != true) {
                emit("session.text.started", base.put("assistantMessageID", assistantId).put("started", time))
              }
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
          val properties = base
            .put("assistantMessageID", messageId(data))
            .put("id", callId)
            .put("content", json)
            .put("metadata", JSONObject())
          if (message.optBoolean("isError")) {
            val failure = data.obj("error")
            val reason = failure.str("reason").ifBlank { failure.str("message") }.ifBlank { "工具执行失败" }
            emit("session.tool.failed", properties.put("error", JSONObject().put("message", reason)))
          } else {
            emit("session.tool.success", properties)
          }
        }
      }
      "session/title" -> emit("session.renamed", base.put("title", data.str("title")))
      "compaction/start" -> emit("session.compaction.started", base
        .put("inputID", data.str("compactionId"))
        .put("at", time))
      "compaction/end" -> {
        val error = data.str("error")
        if (error.isNotBlank()) emit("session.compaction.failed", base.put("error", JSONObject().put("message", error)))
        else emit("session.compaction.ended", base)
      }
      "command/done" -> {
        // 失败的命令在时间线上留一行错误记录；成功的命令由它自己的领域事件呈现。
        if (data.str("kind") == "error") {
          val text = data.str("text").ifBlank { "命令执行失败" }
          emit("session.synthetic", base
            .put("text", text)
            .put("description", text)
            .put("metadata", JSONObject().put("state", "error")))
        }
      }
      else -> Unit
    }
    return output
  }

  /** `msg_<turn>:<step>`：同一步的 `step/start`、`assistant/message`、`tool/call` 共用一个消息 id。 */
  private fun messageId(data: JSONObject): String = "msg_${data.optDouble("turn", 0.0).toLong()}:${data.optDouble("step", 0.0).toLong()}"

  private fun liveKey(sessionId: String, data: JSONObject): String =
    "$sessionId|${data.optDouble("turn", 0.0).toLong()}|${data.optDouble("step", 0.0).toLong()}"

  /**
   * 实时 assistant 流（`assistant-stream` 帧）折算成 `session.text/reasoning.delta`。
   * `chunk` 帧不带 turn/step，按 `start` 帧登记的 attempt 找回。
   */
  private fun translateLive(session: Session, frame: JSONObject, push: (ServerEvent) -> Unit) {
    val at = System.currentTimeMillis()
    when (frame.str("type")) {
      "start" -> {
        val attemptId = frame.str("attemptId")
        val turn = frame.optDouble("turn", 0.0).toLong()
        val step = frame.optDouble("step", 0.0).toLong()
        if (attemptId.isNotBlank()) liveAttempts[attemptId] = liveKey(session.id, frame)
        // 同一步的第二次 attempt（重试）：清空已显示的实时文本，后续增量写回同一个部件。
        val parts = liveParts[liveKey(session.id, frame)] ?: return
        if (parts.isNotEmpty()) {
          val message = "msg_$turn:$step"
          if ("text" in parts) push(ServerEvent(id = "dsh:live:$message:text:reset", directory = session.directory, type = "session.text.ended",
            properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("text", ""), created = at))
          if ("reasoning" in parts) push(ServerEvent(id = "dsh:live:$message:reasoning:reset", directory = session.directory, type = "session.reasoning.ended",
            properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("text", ""), created = at))
        }
      }
      "chunk" -> {
        val attemptId = frame.str("attemptId")
        val key = liveAttempts[attemptId] ?: return
        val chunk = frame.obj("chunk")
        val kind = when (chunk.str("type")) {
          "text-delta" -> "text"
          "reasoning-delta" -> "reasoning"
          else -> return
        }
        val text = chunk.str("text")
        if (text.isEmpty()) return
        emitLiveDelta(session, key, kind, text, frame.optDouble("time", 0.0).toLong().takeIf { it > 0 } ?: at, push)
      }
      "end" -> {
        val attemptId = frame.str("attemptId")
        if (attemptId.isNotBlank()) liveAttempts.remove(attemptId)
        // committed 的耐久 assistant/message 随后收口；abandoned 保留已显示的片段直到下一次 attempt。
      }
    }
  }

  private fun emitLiveDelta(session: Session, key: String, kind: String, text: String, at: Long, push: (ServerEvent) -> Unit) {
    val message = "msg_${key.substringAfter('|').substringBefore('|')}:${key.substringAfterLast('|')}"
    val parts = liveParts.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }
    val first = parts.isEmpty()
    if (parts.add(kind)) {
      if (first) {
        // 补出这条 assistant 消息（幂等）：跟随在 attempt 中途打开时也不会丢文本。
        push(ServerEvent(id = "dsh:live:$key:step", directory = session.directory, type = "session.step.started",
          properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("started", at), created = at))
      }
      push(ServerEvent(id = "dsh:live:$key:$kind:start", directory = session.directory,
        type = if (kind == "reasoning") "session.reasoning.started" else "session.text.started",
        properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("started", at), created = at))
    }
    push(ServerEvent(id = "dsh:live:$key:$kind:$at", directory = session.directory,
      type = if (kind == "reasoning") "session.reasoning.delta" else "session.text.delta",
      properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("delta", text), created = at))
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
      openMux("\$events", noArgs(), onItem = { frame ->
        when (frame.str("type")) {
          "ready" -> {
            eventsClientId = frame.str("clientId")
            reconcileAfterReady(push)
            onOpen()
          }
          "emit" -> handleEmit(frame, push)
          "waterfall" -> handleWaterfall(frame, push)
          "cancel" -> handleCancelled(frame, push)
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

    // 工作区清单与归档集合：项目页与归档标记随增量保持新鲜。
    val workspaceSocket = try {
      openMux("workspace/follow", noArgs(), onItem = { frame -> applyWorkspaceFrame(frame) }, onClosed = { })
    } catch (error: Exception) {
      null
    }

    val pump = launch {
      for (event in channel) send(event)
    }

    // 已在观察中的会话补上 follow，运行中的会话也会从 $events 的 api-session/status 补进来。
    synchronized(watched) { watched.toList() }.forEach { startFollow(it, channel) }

    awaitClose {
      closed = true
      runCatching { globalSocket.cancel() }
      workspaceSocket?.let { runCatching { it.cancel() } }
      followers().forEach { runCatching { it.cancel() } }
      follows.clear()
      pump.cancel()
      channel.close()
      if (eventChannel === channel) eventChannel = null
    }
  }

  private fun followers(): List<WebSocket> = follows.values.toList()

  private fun handleEmit(frame: JSONObject, push: (ServerEvent) -> Unit) {
    val name = frame.str("event")
    val args = frame.arr("args")
    when (name) {
      "api-session/added" -> {
        val item = args.optJSONObject(0) ?: return
        val session = itemToSession(item) ?: return
        known[session.id] = session
        // 总览按 OpenCode 的 `info` 形状解析新会话（id / directory / title / parentID / time）。
        val info = JSONObject()
          .put("id", session.id)
          .put("directory", session.directory)
          .put("title", session.title)
          .putOpt("parentID", session.parentId)
          .put("time", JSONObject().put("created", session.created).put("updated", session.updated))
        push(ServerEvent(id = "dsh:added:${session.id}:${session.updated}", directory = session.directory,
          type = "session.created", properties = JSONObject().put("sessionID", session.id).put("title", session.title).put("info", info),
          created = session.updated))
      }
      "api-session/removed" -> {
        // 参数是纯字符串的会话 id，不是行对象。
        val id = args.optString(0).takeUnless { it == "null" }.orEmpty()
        if (id.isNotBlank()) {
          known.remove(id)
          synchronized(watched) { watched.remove(id) }
          follows.remove(id)?.cancel()
          push(ServerEvent(id = "dsh:removed:$id", directory = "", type = "session.deleted",
            properties = JSONObject().put("sessionID", id), created = System.currentTimeMillis()))
        }
      }
      "api-session/status" -> {
        // running 状态折算成 OpenCode 形状的 session.status，未关注的会话也能实时收尾。
        val id = args.optString(0).takeUnless { it == "null" }.orEmpty()
        if (id.isBlank()) return
        val running = args.optBoolean(1, false)
        val now = System.currentTimeMillis()
        push(ServerEvent(id = "dsh:status:$id:$running:$now", directory = known[id]?.directory.orEmpty(), type = "session.status",
          properties = JSONObject()
            .put("sessionID", id)
            .put("status", JSONObject().put("type", if (running) "busy" else "idle")), created = now))
      }
      "api-session/activity" -> {
        val id = args.optString(0).takeUnless { it == "null" }.orEmpty()
        val updatedAt = args.optDouble(1, 0.0).toLong()
        if (id.isNotBlank() && updatedAt > 0) known[id]?.let { known[id] = it.copy(updated = updatedAt) }
      }
      "api-session/error" -> {
        val id = args.optString(0).takeUnless { it == "null" }.orEmpty()
        val message = args.optString(1).takeUnless { it == "null" }.orEmpty()
        if (id.isNotBlank()) {
          push(ServerEvent(id = "dsh:error:$id:${message.hashCode()}", directory = known[id]?.directory.orEmpty(), type = "session.error",
            properties = JSONObject().put("sessionID", id).put("message", message.ifBlank { "执行失败" }), created = System.currentTimeMillis()))
        }
      }
    }
  }

  private fun handleWaterfall(frame: JSONObject, push: (ServerEvent) -> Unit) {
    val name = frame.str("event")
    val eventId = frame.str("eventId")
    if (eventId.isNotBlank()) redelivered += eventId
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
        if (permission != null) pendingApprovals[eventId] = permission
        push(ServerEvent(id = "dsh:approval:$eventId", directory = directory, type = "permission.asked",
          properties = properties, created = System.currentTimeMillis()))
      }
      "user-questions/request" -> {
        if (eventId.isBlank()) return
        val fields = JSONArray()
        for (question in request.arr("questions").objects()) {
          val options = JSONArray()
          for (option in question.arr("options").objects()) {
            options.put(JSONObject()
              .put("label", option.str("label"))
              .put("description", option.str("description")))
          }
          fields.put(JSONObject()
            .put("key", question.str("id"))
            .put("title", question.str("question"))
            .put("description", question.str("detail"))
            .put("type", if (question.optBoolean("multiSelect")) "multiselect" else "select")
            .put("options", options)
            .put("custom", true))
        }
        // 控制器从 `properties.form` 读表单；id 用瀑布事件 id，回答时按它结算。
        val form = JSONObject().put("id", eventId).put("sessionID", sessionId).put("fields", fields)
        val properties = JSONObject().put("form", form)
        val parsed = try { form.toForm(directory) } catch (error: Exception) { null }
        if (parsed != null) pendingQuestions[eventId] = parsed
        push(ServerEvent(id = "dsh:question:$eventId", directory = directory, type = "form.created",
          properties = properties, created = System.currentTimeMillis()))
      }
    }
  }

  /**
   * 新的 `$events` 客户端建立后，Host 会把仍在等待的瀑布全部重新投递给它（同一个 eventId）。
   * 断线期间被别处结算的审批不会再有 `cancel` 帧：宽限期内没有重新投递的旧待办按已结算撤回。
   */
  private fun reconcileAfterReady(push: (ServerEvent) -> Unit) {
    val generation = readyGeneration.incrementAndGet()
    redelivered.clear()
    val stale = pendingApprovals.keys.toSet() + pendingQuestions.keys.toSet()
    if (stale.isEmpty()) return
    ioScope.launch {
      kotlinx.coroutines.delay(REDELIVERY_GRACE_MILLIS)
      if (readyGeneration.get() != generation) return@launch
      for (eventId in stale - redelivered) settleLocally(eventId, push)
    }
  }

  /** 其他客户端结算了瀑布：移除本地待办，让 UI/任务状态跟随。 */
  private fun handleCancelled(frame: JSONObject, push: (ServerEvent) -> Unit) {
    val eventId = frame.str("eventId")
    if (eventId.isBlank()) return
    settleLocally(eventId, push)
  }

  private fun settleLocally(eventId: String, push: (ServerEvent) -> Unit) {
    val now = System.currentTimeMillis()
    val permission = pendingApprovals.remove(eventId)
    if (permission != null) {
      push(ServerEvent(id = "dsh:approval-settled:$eventId", directory = permission.directory, type = "permission.replied",
        properties = JSONObject().put("sessionID", permission.sessionId).put("id", eventId), created = now))
      return
    }
    val question = pendingQuestions.remove(eventId)
    if (question != null) {
      push(ServerEvent(id = "dsh:question-settled:$eventId", directory = question.directory, type = "form.cancelled",
        properties = JSONObject().put("sessionID", question.sessionId).put("id", eventId), created = now))
    }
  }

  /** 回一条瀑布：`{clientId, eventId, outcome}`，结果值按事件类型自己决定。 */
  private suspend fun settle(eventId: String, value: Any?) {
    val clientId = eventsClientId ?: throw ApiException(409, "事件流尚未连接，请稍后重试")
    val outcome = JSONObject().put("kind", "result")
    if (value != null) outcome.put("value", value)
    val args = JSONObject().put("clientId", clientId).put("eventId", eventId).put("outcome", outcome)
    call("\$events/result", envelope(args))
  }

  /** 拒绝一条瀑布：`{kind:"rejected", error:{name, message}}`。 */
  private suspend fun reject(eventId: String, message: String) {
    val clientId = eventsClientId ?: throw ApiException(409, "事件流尚未连接，请稍后重试")
    val outcome = JSONObject()
      .put("kind", "rejected")
      .put("error", JSONObject().put("name", "cancelled").put("message", message))
    val args = JSONObject().put("clientId", clientId).put("eventId", eventId).put("outcome", outcome)
    call("\$events/result", envelope(args))
  }

  private fun startFollow(id: String, channel: Channel<ServerEvent>) {
    if (follows.containsKey(id)) return
    val session = known[id] ?: return
    ioScope.launch {
      try {
        val socket = openMux(
          "session/follow",
          requestArgs(JSONObject().put("address", address(id)).put("assistantStream", true)),
          onItem = { frame ->
            when (frame.str("type")) {
              "snapshot" -> {
                followRetries.remove(id)
                frame.optDouble("cursor", 0.0).toLong().takeIf { it > 0 }?.let { cursors[id] = it }
                // 首次 follow 的快照记录已由消息页拉取，只登记切点；重连后的快照补投断线期间的事件。
                if (lastSeq(id) > 0) {
                  for (record in frame.arr("records").objects()) {
                    val event = record.obj("event")
                    val seq = event.optDouble("seq", 0.0).toLong()
                    if (seq <= lastSeq(id)) continue
                    translate(session, event).forEach { channel.trySend(it) }
                    advanceSeq(id, seq)
                  }
                }
              }
              "event" -> {
                val event = frame.obj("event")
                val seq = event.optDouble("seq", 0.0).toLong()
                // 只投影时间线还没有的耐久事件（与消息页快照按 seq 去重）。
                if (seq <= 0 || seq > lastSeq(id)) {
                  translate(session, event).forEach { channel.trySend(it) }
                  if (seq > 0) advanceSeq(id, seq)
                }
              }
              "assistant-stream" -> translateLive(session, frame.obj("frame"), { channel.trySend(it) })
              else -> Unit
            }
          },
          onClosed = { cause ->
            follows.remove(id)
            if (cause != null) retryFollow(id, channel)
          }
        )
        follows[id] = socket
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        Diagnostics.warn("DshApi", "会话事件流未建立", error)
      }
    }
  }

  /** 单条会话流意外断开（全局事件流仍在）：退避后重新 follow，仍在观察的会话才重连。 */
  private fun retryFollow(id: String, channel: Channel<ServerEvent>) {
    val wait = followRetries.merge(id, FOLLOW_RETRY_MIN_MILLIS) { previous, _ -> (previous * 2).coerceAtMost(FOLLOW_RETRY_MAX_MILLIS) } ?: return
    ioScope.launch {
      kotlinx.coroutines.delay(wait)
      val stillWatched = synchronized(watched) { id in watched }
      if (eventChannel === channel && stillWatched) startFollow(id, channel)
    }
  }

  private fun stopFollow(id: String) {
    follows.remove(id)?.cancel()
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

  /** 未送达的排队输入来自 `session/projections` 的 `inbox` 投影（`next-turn` 等待当前轮，`next-step` 下一步）。 */
  override suspend fun inbox(id: String): List<Message> = withContext(Dispatchers.IO) {
    val value = call("session/projections", requestArgs(JSONObject().put("sessionId", id))) as? JSONObject ?: return@withContext emptyList()
    val inbox = value.obj("values").obj("inbox")
    if (inbox.length() == 0) return@withContext emptyList()
    val now = System.currentTimeMillis()
    val out = mutableListOf<Message>()
    for ((key, queued) in listOf("next-turn" to true, "next-step" to false)) {
      for (item in inbox.arr(key).objects()) {
        val message = inboxMessage(item, queued, now) ?: continue
        out += message
      }
    }
    out
  }

  private fun inboxMessage(item: JSONObject, queued: Boolean, created: Long): Message? {
    val id = item.str("id")
    if (id.isBlank()) return null
    val text = item.arr("content").objects().mapNotNull { it.str("text").takeIf(String::isNotBlank) }.joinToString("\n")
    val parts = listOfNotNull(text.takeIf(String::isNotBlank)?.let { MessagePart("$id:text", "text", text = it) })
    return Message(id, "user", created, parts, queued = queued, type = "user")
  }

  override suspend fun activeSessions(): Set<String> = withContext(Dispatchers.IO) {
    val listing = listing()
    val sessions = listing.items.mapNotNull { itemToSession(it) }
    rememberWatched(sessions.filter { listing.running.contains(it.id) })
    listing.running
  }

  /**
   * 后台作业（`job/list`）：DSH 的后台 shell / 长任务。每个会话的作业清单是完整的，
   * 按目录聚合需要逐个会话读取；没有已知会话时该目录没有可归属的作业。
   */
  override suspend fun shells(directory: String): List<ShellJob> = withContext(Dispatchers.IO) {
    val normalized = normalizedDirectory(directory)
    val sessions = known.values.filter { normalizedDirectory(it.directory) == normalized }
      .sortedByDescending { it.updated }.take(MAX_JOB_SESSIONS)
    if (sessions.isEmpty()) return@withContext emptyList()
    val jobs = LinkedHashMap<String, ShellJob>()
    for (session in sessions) {
      for (job in runningJobs(session)) {
        val id = job.str("id")
        val owner = job.str("owner")
        // 无主作业是 Host 全局的，无法归属到目录/会话，跳过。
        if (id.isBlank() || owner.isBlank() || job.str("status") !in ACTIVE_JOB_STATUSES) continue
        jobs[id] = ShellJob(
          id = id,
          sessionId = owner,
          command = job.str("label"),
          started = job.optDouble("startedAt", 0.0).toLong(),
          directory = directory
        )
      }
    }
    jobs.values.toList()
  }

  /** `job/list` 是每会话一条流；短 TTL 缓存把同一轮对账里的重复读取收敛掉。 */
  private suspend fun runningJobs(session: Session): List<JSONObject> {
    val now = System.currentTimeMillis()
    jobCache[session.id]?.takeIf { now - it.first < JOB_CACHE_MILLIS }?.let { return it.second }
    val jobs = fetchFirstFrame("job/list", requestArgs(JSONObject().put("sessionId", session.id)))?.arr("jobs")?.objects() ?: emptyList()
    jobCache[session.id] = now to jobs
    return jobs
  }

  override suspend fun createSession(directory: String, title: String): Session = withContext(Dispatchers.IO) {
    // `session/create` 的请求只有 cwd/workspaceId/sessionId/agentPreset，网关按严格 schema 校验，
    // 多带 title 会以 gateway/arguments-invalid 失败；标题在创建后用 rename 设置。
    val request = JSONObject().put("cwd", directory)
    val result = callObject("session/create", requestArgs(request))
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
    call("session/rename", requestArgs(JSONObject().put("sessionId", session.id).put("title", title)))
    known[session.id] = session.copy(title = title)
  }

  override suspend fun setArchived(session: Session, archived: Boolean, now: Long) {
    val request = JSONObject().put("sessionId", session.id)
    if (archived) request.put("stopActivity", true)
    callObject(if (archived) "workspace/archiveSession" else "workspace/unarchiveSession", requestArgs(request))
    if (archived) archivedSessions += session.id else archivedSessions -= session.id
    known[session.id] = session.copy(archived = archived)
  }

  override suspend fun deleteSession(session: Session) {
    // DSH 没有删除会话的 RPC：归档是唯一可逆的「移出列表」语义。
    setArchived(session, true)
  }

  override suspend fun forkSession(session: Session): Session = withContext(Dispatchers.IO) {
    val result = callObject("session/fork", requestArgs(JSONObject().put("sessionId", session.id)))
    val id = result.str("sessionId").ifBlank { throw ApiException(502, "服务器没有返回分支会话") }
    known[id] ?: session.copy(id = id, title = "${session.title} 副本", updated = System.currentTimeMillis())
  }

  override suspend fun abort(session: Session) {
    call("session/cancel", requestArgs(JSONObject().put("sessionId", session.id)))
  }

  override suspend fun compact(session: Session) {
    // DSH 的手动压缩是 `/compact` 命令，不是独立 RPC。
    executeCommand(session, "/compact")
  }

  override suspend fun revert(session: Session, messageId: String) {
    throw ApiException(501, "DeepSeek Harness 不支持回退消息；可改用「分叉」从更早的位置继续")
  }

  override suspend fun unrevert(session: Session) {
    throw ApiException(501, "DeepSeek Harness 不支持回退消息；可改用「分叉」从更早的位置继续")
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
    selectPreset(session, agent)
    val content = JSONArray()
    // DSH 的 prompt 没有「文件引用」块；引用的服务器文件折算成 `@路径` 文本，与官方 Web 的提及写法一致。
    val body = buildString {
      append(text)
      if (references.isNotEmpty()) {
        if (isNotEmpty()) append('\n')
        append(references.joinToString("\n") { "@${it.path}" })
      }
    }.trim()
    if (body.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", body))
    for (file in inline) {
      val payload = inlineContent(file) ?: continue
      content.put(payload)
    }
    if (content.length() == 0) throw ApiException(400, "消息内容为空：附件无法读取或格式不受支持")
    val request = JSONObject()
      .put("requestId", id)
      .put("sessionId", session.id)
      .put("mode", delivery.wire)
      .put("content", content)
      .put("clientTimeZone", java.util.TimeZone.getDefault().id)
    call("session/prompt", requestArgs(request))
  }

  /**
   * 手机附件（[InlineFile.uri] 是 `data:` URI）折算成 DSH 内容块：图片走多模态 `image` 块，
   * 文本文件内联成带文件名的 `text` 块（不经 `fileUploads`，也不会写进服务器上的项目）。
   */
  private fun inlineContent(file: InlineFile): JSONObject? {
    val bytes = try { readInlineBytes(file) } catch (error: Exception) {
      Diagnostics.warn("DshApi", "附件无法读取，已跳过", error)
      null
    } ?: return null
    val mime = file.mime.substringBefore(';').trim().lowercase()
    return when (AttachmentPolicy.kind(mime, file.name)) {
      AttachmentPolicy.Kind.IMAGE -> JSONObject()
        .put("type", "image")
        .put("mediaType", mime)
        .put("data", java.util.Base64.getEncoder().encodeToString(bytes))
        .put("name", file.name)
      AttachmentPolicy.Kind.TEXT -> JSONObject()
        .put("type", "text")
        .put("text", "附件 ${file.name}：\n```\n${String(bytes, Charsets.UTF_8).trimEnd()}\n```")
      null -> null
    }
  }

  /** `data:<mime>;base64,<…>`（Lagoon 选取附件时生成）或本地 `file:` 路径。 */
  private fun readInlineBytes(file: InlineFile): ByteArray? {
    val uri = file.uri
    if (uri.startsWith("data:", ignoreCase = true)) {
      val comma = uri.indexOf(',')
      if (comma < 0) return null
      val meta = uri.substring(5, comma)
      val data = uri.substring(comma + 1)
      return if (meta.endsWith(";base64", ignoreCase = true)) java.util.Base64.getDecoder().decode(data)
        else java.net.URLDecoder.decode(data, "UTF-8").toByteArray(Charsets.UTF_8)
    }
    if (uri.startsWith("file:", ignoreCase = true)) {
      return java.io.File(java.net.URI(uri)).takeIf(java.io.File::isFile)?.readBytes()
    }
    return null
  }

  /** 会话的 Agent 预设（`agentPresets/select`）；只在轮次边界生效，失败保留当前预设。 */
  private suspend fun selectPreset(session: Session, agent: String?) {
    val wanted = agent?.takeIf(String::isNotBlank) ?: return
    if (wanted == (known[session.id] ?: session).agent) return
    try {
      call("agentPresets/select", envelope(argsOf("agentId" to session.id, "agentPreset" to wanted)))
      known[session.id] = (known[session.id] ?: session).copy(agent = wanted)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      Diagnostics.warn("DshApi", "切换 Agent 预设失败，沿用当前预设", error)
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
    if (model != null) runCatching { selectModel(session, model) }
    executeCommand(session, "/$name $arguments".trim())
  }

  /** `commands/execute`：DSH 的命令是独立通道，不创建模型消息。 */
  private suspend fun executeCommand(session: Session, line: String) {
    val args = JSONObject()
      .put("agentId", session.id)
      .put("line", line)
      .put("submittedAttachments", JSONArray())
    val value = call("commands/execute", envelope(args))
    val execution = value as? JSONObject
    if (execution == null) throw ApiException(501, "该实例没有 ${line.substringBefore(' ')} 命令")
    val result = execution.obj("result")
    if (result.str("kind") == "error") {
      throw ApiException(400, result.str("text").ifBlank { "命令执行失败" })
    }
  }

  private suspend fun selectModel(session: Session, model: ModelChoice) {
    val request = JSONObject()
      .put("sessionId", session.id)
      .put("provider", model.providerId)
      .put("model", model.modelId)
    model.variant?.takeIf { it.isNotBlank() }?.let { request.put("reasoningEffort", it) }
    call("session/selectModel", requestArgs(request))
    known[session.id] = session.copy(model = model)
  }

  // ---------------------------------------------------------------- 目录 / 模型 / 权限

  /** `agentPresets/list` 的预设名册：默认预设排第一，不能组合会话的（`broken`）不提供。 */
  override suspend fun agents(directory: String): List<AgentChoice> = withContext(Dispatchers.IO) {
    val roster = try { callObject("agentPresets/list", noArgs()) } catch (error: ApiException) {
      if (error.code?.startsWith("gateway/") != true) throw error
      // 没有预设注册表的实例：退回列表行里出现过的预设。
      val names = LinkedHashSet<String>()
      for (item in listing().items) item.obj("projections").obj("values").str("agentPreset").takeIf(String::isNotBlank)?.let(names::add)
      return@withContext names.map { AgentChoice(it, "") }
    }
    roster.arr("presets").objects()
      .filter { it.str("id").isNotBlank() && it.str("broken").isBlank() }
      .sortedByDescending { it.optBoolean("isDefault") }
      .map { AgentChoice(it.str("id"), it.str("description").ifBlank { it.str("name") }) }
  }

  /** 命令按 agent（会话）作用域注册；用该目录最近观察到的会话读取。 */
  override suspend fun commands(directory: String): List<CommandChoice> = withContext(Dispatchers.IO) {
    val session = sessionIn(directory) ?: return@withContext emptyList()
    val value = call("commands/list", envelope(argsOf("agentId" to session.id)))
    (value as? JSONArray)?.objects()?.mapNotNull { descriptor ->
      val name = descriptor.str("name")
      if (name.isBlank()) null else CommandChoice(name, descriptor.str("description"))
    } ?: emptyList()
  }

  override suspend fun modelCatalog(directory: String): List<ModelInfo> = withContext(Dispatchers.IO) {
    val result = catalog ?: callObject("session/modelCatalog", noArgs()).also { catalog = it }
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
    pendingApprovals.remove(request.id)
    val outcome = when (reply) {
      "once", "always" -> "allowed-once"
      else -> "rejected"
    }
    settle(request.id, outcome)
  }

  override suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    pendingQuestions.remove(request.id)
    val payload = JSONArray()
    request.questions.forEachIndexed { index, prompt ->
      val selected = answers.getOrNull(index).orEmpty().filter(String::isNotBlank)
      // `field` 是 OpenCode 形状的表单字段 JSON，问题 id 存在 `key` 里。
      val questionId = runCatching { JSONObject(prompt.field).str("key") }.getOrNull()?.takeIf(String::isNotBlank)
        ?: prompt.field.ifBlank { prompt.title }
      payload.put(JSONObject()
        .put("id", questionId)
        .put("selected", JSONArray(selected)))
    }
    settle(request.id, JSONObject().put("answers", payload))
  }

  override suspend fun rejectQuestion(request: QuestionRequest) {
    pendingQuestions.remove(request.id)
    reject(request.id, "用户取消了回答")
  }

  override suspend fun children(session: Session): List<Session> = withContext(Dispatchers.IO) {
    listing().items.mapNotNull { itemToSession(it) }.filter { it.parentId == session.id }
  }

  // ---------------------------------------------------------------- 改动 / 文件

  /**
   * 会话改动：读取最近若干 `workspace/changes` 事件的摘要（最新轮次优先）并按路径合并，
   * 逐文件对比来自认证栅栏内的 `/api/changes.diff`。摘要只在该 Host 进程内、会话存活期间提供。
   */
  override suspend fun diff(session: Session): List<FileChange> = withContext(Dispatchers.IO) {
    val snapshot = try { fetchSnapshot(session.id) } catch (error: CancellationException) { throw error } catch (error: Exception) { return@withContext emptyList() }
    val announced = mutableListOf<Long>()
    for (record in snapshot.arr("records").objects()) {
      val event = record.obj("event")
      if (event.str("type") != "workspace/changes") continue
      event.optDouble("seq", 0.0).toLong().takeIf { it > 0 }?.let(announced::add)
    }
    data class Changed(val file: JSONObject, val seq: Long, val index: Int)
    val merged = LinkedHashMap<String, Changed>()
    for (seq in announced.takeLast(MAX_CHANGE_TURNS)) {
      val summary = changesSummary(session.id, seq) ?: continue
      summary.arr("files").objects().forEachIndexed { index, file ->
        val path = file.str("path")
        if (path.isNotBlank()) merged[path] = Changed(file, seq, index)
      }
    }
    merged.values
      .sortedBy { it.file.str("display").ifBlank { it.file.str("path") } }
      .take(MAX_CHANGED_FILES)
      .map { changed ->
        val file = changed.file
        val display = file.str("display").ifBlank { file.str("path") }
        val comparison = changesDiff(session.id, changed.seq, changed.index)
        FileChange(
          path = display,
          after = when (comparison?.str("kind")) {
            "binary" -> "二进制文件，无法预览对比"
            "oversized" -> "文件过大，无法预览对比"
            else -> ""
          },
          additions = file.optInt("added"),
          deletions = file.optInt("deleted"),
          patch = if (comparison?.str("kind") == "text") renderPatch(display, comparison) else ""
        )
      }
  }

  private suspend fun changesSummary(sessionId: String, seq: Long): JSONObject? =
    fetchJson(base.newBuilder()
      .addEncodedPathSegment("api")
      .addEncodedPathSegment("changes.summary")
      .addQueryParameter("sessionId", sessionId)
      .addQueryParameter("seq", seq.toString())
      .build())

  private suspend fun changesDiff(sessionId: String, seq: Long, index: Int): JSONObject? =
    fetchJson(base.newBuilder()
      .addEncodedPathSegment("api")
      .addEncodedPathSegment("changes.diff")
      .addQueryParameter("sessionId", sessionId)
      .addQueryParameter("seq", seq.toString())
      .addQueryParameter("index", index.toString())
      .build())

  private fun renderPatch(display: String, diff: JSONObject): String {
    val hunks = diff.arr("hunks").objects()
    if (hunks.isEmpty()) return ""
    val out = StringBuilder()
    out.append("--- a/").append(display).append('\n')
    out.append("+++ b/").append(display).append('\n')
    for (hunk in hunks) {
      out.append("@@ -").append(hunk.optInt("oldStart")).append(',').append(hunk.optInt("oldLines"))
        .append(" +").append(hunk.optInt("newStart")).append(',').append(hunk.optInt("newLines")).append(" @@\n")
      val lines = hunk.arr("lines")
      for (i in 0 until lines.length()) out.append(lines.optString(i)).append('\n')
    }
    return out.toString()
  }

  /**
   * 工作区文件读取需要一个会话身份（`workspaceFileScopeId`）。优先用调用方给出的会话，
   * 其次用该目录最近观察到的会话；目录里没有会话时无法浏览。
   */
  private suspend fun fileScope(directory: String, preferred: Session?): Session {
    val normalized = normalizedDirectory(directory)
    preferred?.takeIf { normalizedDirectory(it.directory) == normalized }?.let { return it }
    sessionIn(directory)?.let { return it }
    throw ApiException(409, "DeepSeek Harness 需要先在该目录创建会话才能浏览文件")
  }

  private suspend fun sessionIn(directory: String): Session? {
    val normalized = normalizedDirectory(directory)
    synchronized(watched) { watched.toList() }.asReversed().forEach { id ->
      known[id]?.takeIf { normalizedDirectory(it.directory) == normalized }?.let { return it }
    }
    known.values.firstOrNull { normalizedDirectory(it.directory) == normalized }?.let { return it }
    listing()
    return known.values.firstOrNull { normalizedDirectory(it.directory) == normalized }
  }

  override suspend fun files(directory: String, path: String, session: Session?): List<FileNode> = withContext(Dispatchers.IO) {
    val scope = try { fileScope(directory, session) } catch (error: ApiException) {
      // 目录里还没有会话（例如添加项目时浏览服务器目录）：退回 Host 的目录选择器，只列子目录。
      if (error.status != 409) throw error
      return@withContext pickerDirectories(directory, path)
    }
    val relative = path.trim().ifBlank { "." }
    val result = callObject("workspaceFiles/list", envelope(argsOf(
      "workspaceFileScopeId" to scope.id,
      "path" to relative
    )))
    val listed = result.str("path").trim('/')
    result.arr("entries").objects().mapNotNull { entry ->
      val name = entry.str("name")
      if (name.isBlank()) return@mapNotNull null
      FileNode(if (listed.isEmpty()) name else "$listed/$name", entry.str("type").ifBlank { "file" })
    }
  }

  /** `directoryPicker/list`：Host 账户可见的一层子目录（官方 Web「选择工作目录」用的同一接口）。 */
  private suspend fun pickerDirectories(directory: String, path: String): List<FileNode> {
    val relative = path.trim().trim('/').takeUnless { it.isEmpty() || it == "." }
    val target = when {
      relative == null -> directory.ifBlank { "/" }
      directory.isBlank() || directory == "/" -> "/$relative"
      else -> "${directory.trimEnd('/')}/$relative"
    }
    val listing = callObject("directoryPicker/list", envelope(argsOf("path" to target)))
    return listing.arr("entries").objects().mapNotNull { entry ->
      entry.str("name").takeIf(String::isNotBlank)?.let { FileNode(it, "directory") }
    }
  }

  override suspend fun fileContent(directory: String, path: String, session: Session?): FileContent {
    val scope = fileScope(directory, session)
    return try {
      val result = callObject("workspaceFiles/read", envelope(argsOf(
        "workspaceFileScopeId" to scope.id,
        "path" to path,
        "range" to JSONObject()
      )))
      FileContent("text", result.str("text"))
    } catch (error: ApiException) {
      // 二进制文件以 `workspace-file/not-text` 拒绝；UI 只提示无法预览。
      if (error.code == "workspace-file/not-text") FileContent("binary", "") else throw error
    }
  }

  /** `fileReferences/list`：与官方 `@` 提及同一个候选源，按会话工作目录搜索文件与目录。 */
  override suspend fun searchFiles(directory: String, query: String, session: Session?): List<String> = withContext(Dispatchers.IO) {
    val scope = fileScope(directory, session)
    val value = call("fileReferences/list", envelope(argsOf("agentId" to scope.id, "query" to query.trim())))
    (value as? JSONArray)?.objects()
      ?.mapNotNull { candidate -> candidate.str("path").trim().trimEnd('/').takeIf(String::isNotBlank) }
      ?.distinct()
      ?: emptyList()
  }

  override suspend fun health(): String = withContext(Dispatchers.IO) {
    listing()
    "DeepSeek Harness"
  }

  private companion object {
    const val WATCH_LIMIT = 12

    /** 认证失效的错误码：控制器据此提示重新配对，而不是当成网络抖动。 */
    const val AUTH_EXPIRED = DSH_AUTH_EXPIRED
    const val REPAIR_MESSAGE = "登录已失效：dsh 重启后旧令牌作废、Cookie 也已过期或被撤销，请用电脑上新的 dsh web 启动链接重新配对"
    const val SECRET_REJECTED_MESSAGE = "签名密钥被拒绝：密钥不对，或 dsh 的 browser-session 记录已被删除重建，请重新复制 secret"
    const val UNTRUSTED_HOST_MESSAGE = "dsh 拒绝了这个地址（403）：请用 dsh web --host <该 IP> 启动，域名或反向代理需加 --trusted-host"

    /** 令牌模式：Cookie 签发超过半天就尝试续期（dsh 未重启时令牌仍可用）。 */
    const val RENEW_AFTER_MILLIS = 12 * 60 * 60_000L
    /** 密钥模式：过期前两小时重新签发。 */
    const val MINTED_RENEW_MARGIN_MILLIS = 2 * 60 * 60_000L
    /** 续期失败后的冷却，避免每个请求都撞一次失效令牌。 */
    const val RENEW_RETRY_MILLIS = 60 * 60_000L
    const val STREAM_PING_SECONDS = 20L
    /** 重连后等待 Host 重新投递瀑布的宽限期。 */
    const val REDELIVERY_GRACE_MILLIS = 3_000L
    const val FOLLOW_RETRY_MIN_MILLIS = 2_000L
    const val FOLLOW_RETRY_MAX_MILLIS = 60_000L

    /** 单次改动列表最多合并的轮次数与文件数。 */
    const val MAX_CHANGE_TURNS = 8
    const val MAX_CHANGED_FILES = 60

    /** 单次 shell 读取最多查询的会话数（`job/list` 是每会话一条流）。 */
    const val MAX_JOB_SESSIONS = 3

    /** 作业清单缓存的有效期：把同一轮对账里的重复读取收敛掉。 */
    const val JOB_CACHE_MILLIS = 20_000L
    val ACTIVE_JOB_STATUSES = setOf("running", "stopping")

    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * DSH 的实例能力。删除没有对应 RPC（归档代替）；回退没有对应语义；没有 `/openapi.json`
     * 之类的契约文档，漂移按方法级失败处理。
     */
    val CAPABILITIES = ApiCapabilities(
      actions = mapOf(SessionAction.RENAME to ActionEndpoint("POST", "session/rename")),
      diff = true,
      sessionView = null,
      archive = ArchiveEndpoint(ActionEndpoint("POST", "workspace/archiveSession"), restorable = true),
      documented = false,
      deleteSession = false,
      fork = true
    )
  }
}
