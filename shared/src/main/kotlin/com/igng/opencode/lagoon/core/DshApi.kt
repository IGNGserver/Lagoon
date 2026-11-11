package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
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
  private val clock: () -> Long = System::currentTimeMillis,
  private val remoteBackend: RemoteBackend? = null,
  /**
   * SSH tunnel mode: requests go to a local forwarder, but DSH's loopback Host fence and cookie
   * audience must see the remote authority (`127.0.0.1:<remotePort>`).
   */
  private val hostHeader: String? = null,
  /** Test seam for the logical $events handshake; production keeps the shared first-frame deadline. */
  private val eventsReadyTimeoutMillis: Long = EVENTS_READY_TIMEOUT_MILLIS
) : HarnessClient, RemoteDoctorSource {
  constructor(profile: ServerProfile, password: String) : this(profile, ServerCredentials(profile.username, password))

  private val base: HttpUrl = requireNotNull(profile.url.trimEnd('/').toHttpUrlOrNull()) { "服务器地址无效" }
  private val origin = HttpOrigin.of(base)

  /** 凭据的 password 槽位：令牌模式是启动令牌，密钥模式是 Cookie 签名密钥。 */
  private val token: String = if (profile.dshAuth == DshAuthMode.TOKEN) credentials.password.trim() else ""
  private val signingSecret: ByteArray? =
    if (profile.dshAuth == DshAuthMode.SECRET) DshCookie.decodeSecret(credentials.password) else null

  private val http = OkHttpClient.Builder()
    .connectTimeout(SharedHttp.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
    .addInterceptor { chain ->
      val request = chain.request()
      if (hostHeader.isNullOrBlank()) chain.proceed(request)
      else chain.proceed(request.newBuilder().header("Host", hostHeader).build())
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
  private val muxOwner = PhysicalMuxOwner()

  init {
    require(base.scheme == "https" || base.scheme == "http" && profile.allowCleartext) { "HTTP 明文连接未获授权，请在服务器资料中明确开启" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "请使用不含凭据或参数的服务器地址" }
  }

  /** 会话目录与标题缓存；事件缺目录时用它补 `ServerEvent.directory`。 */
  private val known = ConcurrentHashMap<String, Session>()
  /** `session/follow` 快照给出的日志切点：`session/page` 的 `throughSeq`。 */
  private val cursors = ConcurrentHashMap<String, Long>()
  /**
   * 进程内的目标续跑状态（`armed`/`disarmed`）。投影里刻意没有它（只在进程内），远程模式下
   * 也不允许 `goals/get`，所以只能从转发事件 `goal/activation-changed` 与 mutation 的返回值里收集。
   */
  private data class GoalActivation(val id: String, val revision: Int, val activation: String)
  private val goalCacheLock = Any()
  private val goalActivation = mutableMapOf<String, GoalActivation>()
  /** Full mutation views fence lagging durable projections at the exact goal revision. */
  private val latestGoalViews = mutableMapOf<String, RemoteGoal>()
  /** Reads started before a mutation cannot clear or replace its acknowledged result. */
  private val goalCacheEpochs = mutableMapOf<String, Long>()
  private data class ClearedGoal(val id: String, val revision: Int)
  private val clearedGoalRefs = mutableMapOf<String, ClearedGoal>()
  private fun goalCacheEpoch(id: String): Long = synchronized(goalCacheLock) { goalCacheEpochs[id] ?: 0L }
  private fun advanceGoalCacheEpoch(id: String) { goalCacheEpochs[id] = (goalCacheEpochs[id] ?: 0L) + 1L }
  /** 每个后台任务输出流已读到的字节位移（`job/follow` 的 `from`），断线重连时续读。 */
  private val jobOffsets = ConcurrentHashMap<String, Long>()
  /** A waterfall belongs to the exact $events client that delivered it. */
  private data class QuestionDelivery(val clientId: String, val wireEventId: String, val sessionId: String, val callId: String?)
  private val questionEventClients = ConcurrentHashMap<String, QuestionDelivery>()
  private val closing = java.util.concurrent.atomic.AtomicBoolean(false)
  private val shutdown = CompletableDeferred<Unit>()
  private fun questionDeliveryId(clientId: String, eventId: String) = "${clientId.length}:$clientId:${eventId.length}:$eventId"
  @Volatile private var questionSession: String? = null
  @Volatile private var questionForeground = false
  private val questionRefreshes = ConcurrentHashMap<String, Job>()
  private val dirtyQuestionRefreshes = ConcurrentHashMap.newKeySet<String>()
  private val questionCoordinatorDelegate = lazy {
    DshQuestionCoordinator(ioScope, clock,
      openClaim = { session, _, callId, remaining, ended ->
        val socket = openMux("userQuestions/attachWait", envelope(argsOf("agentId" to session, "callId" to callId)),
          onItem = { item ->
            val value = item.opt("remainingMs") as? Number ?: throw IOException("提问认领缺少 remainingMs")
            require(value.toDouble().isFinite() && value.toDouble() >= 0 && value.toDouble() <= Long.MAX_VALUE.toDouble())
            remaining(value.toLong())
          }, onClosed = { ended() })
        java.io.Closeable { socket.cancel() }
      },
      settle = { eventId, outcome -> settleQuestionOutcome(eventId, outcome) },
      answer = { session, callId, answer ->
        val accepted = call("userQuestions/answer", envelope(argsOf("agentId" to session, "callId" to callId, "answer" to answer)))
        if (accepted != true) throw ApiException(409, "提问已经结束，请刷新后确认")
      },
      refresh = { session -> refreshQuestionProjection(session) },
      publish = { snapshots -> publishQuestionSnapshots(snapshots) },
      onError = { error -> Diagnostics.warn("DshApi", "提问状态更新失败", error) })
  }
  private val questionCoordinator: DshQuestionCoordinator get() = questionCoordinatorDelegate.value
  /** 需要实时事件（`session/follow`）的会话，最近打开的放最后。 */
  private val watched = LinkedHashSet<String>()
  /** 当前打开的会话；容量淘汰时优先保留，避免首页刷新把正在阅读的 follow 挤掉。 */
  @Volatile private var activeSessionId: String? = null
  /**
   * 每个会话只有一条长期 `session/follow`。快照从这条流取，实时事件也从这条流进；
   * 不再“先开一条取快照、再开一条做实时”。[followOpenLock] 串行化首次打开，
   * 避免消息页和事件流同时各开一条。
   */
  private val followStates = ConcurrentHashMap<String, FollowState>()
  private val followOpenLock = Any()
  /** 最近一次会话快照的规模；只记数量/游标，不记正文。 */
  private val snapshotStats = ConcurrentHashMap<String, RemoteSnapshotStats>()
  @Volatile private var eventChannel: Channel<ServerEvent>? = null
  @Volatile private var eventsClientId: String? = null
  /** 仍在等待回答的审批/问答瀑布；键是网关事件 id。 */
  private val pendingApprovals = ConcurrentHashMap<String, PermissionRequest>()
  private val pendingQuestions = ConcurrentHashMap<String, QuestionRequest>()
  /** 当前 `$events` 客户端代次与它重新投递过的瀑布 id（断线重连后对账用）。 */
  private val readyGeneration = java.util.concurrent.atomic.AtomicInteger()
  private val redelivered = ConcurrentHashMap.newKeySet<String>()
  /** `$events` 的当前状态，供连接医生展示。 */
  @Volatile private var eventsReady = false
  @Volatile private var eventsLastFrameAt = 0L
  @Volatile private var eventsReconnects = 0
  @Volatile private var eventsError: String? = null
  /** DshApi 本身已关闭；follow 重连协程据此退出。 */
  @Volatile private var closed = false
  @Volatile private var catalog: JSONObject? = null
  /** 已归档会话集合（`workspace/follow` 的 baseline 与 `archived` 增量维护）。 */
  private val archivedSessions = ConcurrentHashMap.newKeySet<String>()
  @Volatile private var archiveBaselineKnown = false
  /** 服务端置顶集合（`workspace/follow` 的 `pinnedSessionIds`，含 `pinned` 增量帧）。 */
  private val serverPinned = ConcurrentHashMap.newKeySet<String>()
  /** `workspace/follow` 的最新工作区清单；null 表示尚未读取。 */
  @Volatile private var workspaceItems: List<JSONObject>? = null
  private val workspaceLock = Any()
  /** Replays the current workspace titles to a controller that starts watching mid-session. */
  private val workspacePublisher = MutableSharedFlow<List<Project>>(replay = 1, extraBufferCapacity = 1)
  override val workspaceUpdates: Flow<List<Project>> = workspacePublisher.asSharedFlow()
  /** False for background notification monitors: keep `$events`, skip workspace/follows. */
  private val contentWatching = MutableStateFlow(true)
  override fun setContentWatching(enabled: Boolean) { contentWatching.value = enabled }
  override fun transportStatus(): String? = remoteBackend?.connectionStatus()?.let { status ->
    val transport = when {
      status.relayAssignment -> "Relay"
      status.transport.isNotBlank() -> status.transport
      else -> null
    }
    listOfNotNull(status.phaseLabel(), transport).joinToString(" · ")
  }
  /** 每个会话的作业清单缓存：`job/list` 是每会话一条流，短 TTL 避免对账风暴。 */
  private val jobCache = ConcurrentHashMap<String, Pair<Long, List<JSONObject>>>()
  /**
   * `attachmentId` → `data:` URI。DSH 的 user/message 只存附件引用，图片字节要另走
   * `session/attachment`；同一条消息在快照/实时/翻页里会重复出现，缓存避免重复拉取。
   */
  private val attachmentBlobs = ConcurrentHashMap<String, String>()
  @Volatile private var fullAttachmentReference: Boolean? = null
  /**
   * 一次性读取（当前只有 `job/list`）的并发闸门。首页会按最多 8 个目录并发读作业，
   * 每条 `job/list` 都是一条流；不限流会和 `$events`/`session/follow` 抢预算。
   */
  private val oneShotStreams = Semaphore(2)
  /**
   * 实时事件不是耐久日志，没有可供跨流去重的稳定 ID。每次实际发射都使用单调序号；
   * 时间戳只能当消息时间，不能当身份（同毫秒可能有多个 chunk，重试的 reset 也必须重新投递）。
   */
  private val liveEventSequence = java.util.concurrent.atomic.AtomicLong()
  private fun nextLiveEventId() = liveEventSequence.incrementAndGet()
  /** 实时流里已经建立文本/推理部件的 `sessionId|turn|step` 键与部件种类。 */
  private val durableTurns = ConcurrentHashMap<String, Long>()
  private val liveParts = ConcurrentHashMap<String, MutableSet<String>>()
  /** 活跃 attempt 的 `attemptId → sessionId|turn|step`（chunk 帧不带 turn/step）。 */
  private val liveAttempts = ConcurrentHashMap<String, String>()
  /** 每个会话已经投影过的最大事件 seq：消息页与实时流之间按 seq 去重。 */
  private val lastSeqs = ConcurrentHashMap<String, Long>()
  /** Execution-scoped outcomes: old history/late reads cannot overwrite a newer running turn. */
  private val executions = DshExecutionTracker()
  private val outcomeReads = ConcurrentHashMap<String, Long>()
  /** PTC ancestry survives page/snapshot boundaries; session eviction bounds retained tool data. */
  private val ptcProjections = LinkedHashMap<String, DshPtcProjection>()
  private fun ptcProjection(sessionId: String): DshPtcProjection = synchronized(ptcProjections) {
    ptcProjections.remove(sessionId)?.also { ptcProjections[sessionId] = it } ?: DshPtcProjection().also {
      ptcProjections[sessionId] = it
      while (ptcProjections.size > WATCH_LIMIT) {
        val victim = ptcProjections.keys.firstOrNull { id -> id != sessionId && id != activeSessionId } ?: break
        ptcProjections.remove(victim)
      }
    }
  }

  override val isClosed: Boolean get() = closing.get() || closed || remoteBackend?.isClosed == true

  override fun capabilities(): ApiCapabilities = if (remoteBackend == null) CAPABILITIES else CAPABILITIES.copy(diff = false)

  override fun close() {
    if (closed || !closing.compareAndSet(false, true)) return
    if (questionCoordinatorDelegate.isInitialized()) {
      questionCoordinator.close()
      if (!questionCoordinator.hasPendingSettlements()) { closeTransportAfterQuestions(); return }
      // Keep the physical carrier alive until ownership handoffs are delivered. Local channel close
      // alone does not settle an unowned Host waterfall. Bound teardown so a dead socket cannot
      // retain this client indefinitely.
      ioScope.launch {
        try {
          val drained = kotlinx.coroutines.withTimeoutOrNull(2_000) { questionCoordinator.awaitSettlements(); true } == true
          if (!drained) {
            Diagnostics.warn("DshApi", "提问交还请求未在关闭期限内确认，结果未知；释放认领且不重放")
            questionCoordinator.abortSettlements()
          }
        } finally { closeTransportAfterQuestions() }
      }
      return
    }
    closeTransportAfterQuestions()
  }

  private fun closeTransportAfterQuestions() {
    if (closed) return
    closed = true
    activeSessionId = null
    eventChannel?.close()
    followStates.values.toList().forEach(::stopFollowState)
    followStates.clear()
    durableTurns.clear(); liveParts.clear(); liveAttempts.clear()
    synchronized(ptcProjections) { ptcProjections.clear() }
    executions.clear(); outcomeReads.clear()
    pendingApprovals.clear(); pendingQuestions.clear()
    questionEventClients.clear()
    try {
      muxOwner.close()
      remoteBackend?.close()
    } finally { shutdown.complete(Unit) }
  }

  override suspend fun awaitClosed() { shutdown.await() }

  override suspend fun discoverCapabilities(): ApiCapabilities = capabilities()

  // ---------------------------------------------------------------- 鉴权

  /** 当前使用的 Cookie；先取落盘的浏览器 Cookie，过期或缺失时才用令牌/密钥换新的。
   *  SSH 隧道带了新的启动令牌时不预置 Cookie：先用令牌交换，令牌已轮换再退回这张 Cookie。 */
  @Volatile private var sessionCookie: String? =
    credentials.cookie.trim().takeIf {
      hostHeader == null && DshCookie.isFresh(it, clock()) && (signingSecret == null || DshCookie.isDurable(it))
    }
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
    try {
      connect().also(::adopt)
    } catch (error: ApiException) {
      // 隧道里的启动令牌在远端 dsh 重启后作废，但浏览器 Cookie 跨进程仍然有效。
      val saved = credentials.cookie.trim().takeIf { error.code == AUTH_TOKEN_ROTATED && DshCookie.isFresh(it, clock()) }
      if (saved != null) saved.also(::adopt) else throw error
    }
  }

  private fun repairMessage(): String = if (signingSecret != null) SECRET_REJECTED_MESSAGE else REPAIR_MESSAGE

  /** 采用新 Cookie；浏览器 Cookie 回调落盘，API Peer Cookie 只放内存（dsh 重启即失效）。
   *  SSH 隧道每次换代都会拿到新 token，Cookie 只服务于这一代 DshApi，不写入服务器凭据。 */
  private fun adopt(pair: String) {
    sessionCookie = pair
    cookieRejected = false
    // 密钥模式随时能在本机重签，不需要落盘。
    if (signingSecret == null && DshCookie.isDurable(pair) && pair != credentials.cookie.trim()) {
      runCatching { onCookie(pair) }
    }
  }

  /**
   * 密钥模式下 HTTPS 地址的 Cookie audience 取决于 dsh 自己是否是 TLS 监听：直连 TLS 是 `https://host`，
   * HTTPS 反向代理后面的 HTTP 监听是 `host`。先按 TLS 签发，被拒后改用后者再试一次。
   */
  @Volatile private var plainListener = false

  /** 服务端拒绝了当前 Cookie（401）：丢弃，并把落盘副本一起清掉，避免每次启动都先撞一次 401。
   *  SSH 隧道的 Cookie 只活在这一代 DshApi 的内存里，远端进程换代时由新 token 重新交换，不能把
   *  上一张还能用的长寿命 Cookie 从磁盘上抹掉。 */
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
   *
   * SSH 隧道的令牌每次远端进程重启都会变。根路径用旧令牌换票得到 401 时，不能把它当成「登录已失效」：
   * 调用方手里可能还有上一张长寿命 Cookie，那张 Cookie 跨 dsh 重启仍然有效。
   */
  private suspend fun connect(): String = withContext(Dispatchers.IO) {
    signingSecret?.let { return@withContext DshCookie.mint(it, base, clock(), secureListener = base.isHttps && !plainListener) }
    if (profile.dshAuth == DshAuthMode.SECRET) throw ApiException(401, "签名密钥格式不正确：应为 43 个字符的 base64url 字符串", AUTH_EXPIRED)
    if (token.isEmpty()) throw ApiException(401, "请填写 DeepSeek Harness 的启动令牌", AUTH_EXPIRED)
    try {
      exchange(base.newBuilder().addQueryParameter("token", token).build(), browser = true)?.let { return@withContext it }
    } catch (error: ApiException) {
      if (hostHeader != null && error.status == 401) {
        // 远端进程换代：令牌必然轮换。调用方手里可能还有上一张长寿命 Cookie；没有就如实说明，
        // 而不是让用户去「重新配对」（SSH 模式的凭据是自动管理的）。
        throw ApiException(401, "远端 dsh web 已重启，正在重新获取启动令牌", AUTH_TOKEN_ROTATED)
      }
      throw error
    }
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
    return try {
      runHttpCall(builder.build()) { value ->
        value.use {
          when (value.code) {
            401 -> throw ApiException(401, if (cookieRejected) REPAIR_MESSAGE else "启动令牌无效：dsh 每次启动都会换新令牌，请填写当前启动链接里的 token", AUTH_EXPIRED)
            403 -> throw ApiException(403, UNTRUSTED_HOST_MESSAGE, "http/403")
            !in 200..399 -> throw ApiException(value.code, "连接失败：HTTP ${value.code}")
          }
          value.headers("Set-Cookie").firstNotNullOfOrNull { raw ->
            raw.substringBefore(';').trim().takeIf { it.startsWith("dsh-auth-") }
          }
        }
      }
    } catch (error: ApiException) {
      throw error
    } catch (error: IOException) {
      throw ApiException(0, "无法连接 DeepSeek Harness：${error.message ?: "网络不可达"}")
    }
  }

  // ---------------------------------------------------------------- RPC

  /** 0.2.x 的 payload 只包含一个 `args` 对象，字段是方法形参名（`signal` 除外）。 */
  private fun argsOf(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().also { out ->
    for ((key, value) in fields) if (value != null) out.put(key, value)
  }

  /**
   * Host 只接受 `UTC` 或规范的 IANA `Area/Location`，而且会做
   * `Intl.DateTimeFormat(...).resolvedOptions().timeZone === value` 的规范化校验；不通过时
   * 抛在 `agent/pre-step` 里，**整轮失败**（prompt 已经 accepted）。设备报 `GMT`、`GMT+08:00`
   * 或旧别名（如 `Asia/Calcutta`）时必须整个字段省略，让 Host 退回自己的缺省。
   */
  private fun clientTimeZone(): String? = java.util.TimeZone.getDefault().id.takeIf { zone ->
    zone == "UTC" || Regex("[A-Za-z_]+/[A-Za-z0-9_+-]+(?:/[A-Za-z0-9_+-]+)?").matches(zone)
  }

  /**
   * v1 会话代际把 PTC 预设记作 `code`，v3 记作 `ptc`。只做**整值**映射——预设名是主机声明的
   * 标识符，用子串替换会把 `code-review` 这类名字一起改掉。
   */
  private fun normalizeAgentPreset(value: String): String = if (value == "code") "ptc" else value

  private fun envelope(args: JSONObject): RpcPayload = RpcPayload(JSONObject().put("args", args))

  /** 常见的 `{request: …}` 形状。 */
  private fun requestArgs(request: JSONObject): RpcPayload = envelope(argsOf("request" to request))

  private fun noArgs(): RpcPayload = envelope(JSONObject())

  /**
   * Runs one blocking OkHttp call on the shared IO dispatcher, but cancels the socket as soon as the
   * caller is cancelled. A page switch must not leave a 25–60 second request (and its ranked
   * connection slot) running in the background. The body is consumed inside the worker so a large
   * response is also interrupted by cancellation.
   */
  private suspend fun <T> runHttpCall(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
      val call = http.newCall(request)
      val worker = ioScope.launch {
        try {
          val value = call.execute().use(read)
          if (continuation.isActive) continuation.resume(value)
        } catch (cancel: CancellationException) {
          if (continuation.isActive) continuation.cancel(cancel)
        } catch (error: Throwable) {
          if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
      }
      continuation.invokeOnCancellation {
        call.cancel()
        worker.cancel()
      }
    }

  /** 一元调用的请求信封是 `{type, rpcId, method, payload}`；响应是 `{type, rpcId, result}`。 */
  private suspend fun call(endpoint: String, payload: RpcPayload = noArgs(), retried: Boolean = false): Any? =
    withContext(Dispatchers.IO) {
      val wirePayload = payload.json
      remoteBackend?.let { remote ->
        try { return@withContext remote.call(endpoint, wirePayload) }
        catch (cancel: CancellationException) { throw cancel }
        // Keep typed connection failures intact: the controller uses their kind to offer login,
        // fingerprint or host recovery. Only untyped I/O is collapsed into a transport failure.
        catch (error: RemoteFailureException) { throw error }
        catch (error: RemoteServerException) { throw remoteServerFailure(error) }
        catch (error: RemoteGatewayException) { throw ApiException(502, friendlyMessage(error.code, error.message.orEmpty()), error.code, error.details) }
        // The transport died, or its pending RPC could not finish. This is not a Host contract
        // rejection: name it as a connection problem (and keep the upstream code) so the controller
        // can reconnect instead of toasting “transfer closed” / WebSocket internals.
        catch (error: RemoteRpcException) {
          throw ApiException(502, "与主机的加密中继已断开，正在重连", error.code.ifBlank { "TRANSPORT_CLOSED" })
        }
        catch (error: IOException) {
          throw ApiException(0, "与主机的连接中断，正在重连", "TRANSPORT_CLOSED")
        }
      }
      val current = cookie()
      val url = base.newBuilder().addEncodedPathSegment("api").addEncodedPathSegments(endpoint).build()
      val envelope = JSONObject()
        .put("type", "client-request")
        .put("rpcId", UUID.randomUUID().toString())
        .put("method", endpoint)
        .put("payload", wirePayload)
      val request = Request.Builder()
        .url(url)
        .header("Content-Type", "application/json; charset=utf-8")
        .header("Cookie", current)
        .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()
      val (status, contentType, body) = try {
        runHttpCall(request) { response ->
          response.use {
            val body = it.body
            if (body != null && (body.contentLength() > MAX_RPC_RESPONSE_BYTES || body.source().request(MAX_RPC_RESPONSE_BYTES + 1)))
              throw ApiException(413, "DSH 响应超过本机读取上限", "response/too-large")
            Triple(it.code, it.header("Content-Type"), body?.bytes() ?: ByteArray(0))
          }
        }
      } catch (error: ApiException) {
        throw error
      } catch (error: IOException) {
        throw ApiException(0, "无法连接 DeepSeek Harness：${error.message ?: "网络不可达"}")
      }
      if (status == 401 && !retried) {
        // Cookie 失效（过期、签名密钥被撤销，或 API Peer 随连接/进程消失）：丢弃后重新换一张。
        rejectCookie(current)
        return@withContext call(endpoint, payload, retried = true)
      }
      if (status == 401) throw ApiException(401, repairMessage(), AUTH_EXPIRED, JSONObject().put("status", status))
      if (status !in 200..299) throw ApiException(status, httpMessage(status), "http/$status", JSONObject().put("status", status))
      val parsed = decodeRpcResponse(status, contentType, body)
      val result = parsed.optJSONObject("result") ?: throw ApiException(status, "服务器响应缺少 result")
      if (result.optBoolean("ok")) return@withContext result.opt("value")
      val failure = result.optJSONObject("error")
      val code = failure?.optString("code").orEmpty()
      val message = failure?.optString("message").orEmpty()
      throw ApiException(status, friendlyMessage(code, message.ifBlank { "请求失败" }), code.ifBlank { null }, failure?.optJSONObject("details"))
    }

  /**
   * 一元调用的响应体有两种形状：普通 JSON，以及二进制方法的 `multipart/form-data`。
   *
   * `workspaceFiles/readBytes`、`officeToPdf/render` 这类声明了 `codec:"bytes"` 的方法把字节放在
   * 独立分片里，信封本身在 `metadata` 分片的 `{"type":"server-response",…,"attachments":[…]}`，
   * `value` 里对应字段是 `null`。这里把分片字节按 `attachments[{path,part}]` 声明的路径折回
   * base64 字符串，调用方（和远程中继）仍然只看到 JSON。
   */
  private fun decodeRpcResponse(status: Int, contentType: String?, body: ByteArray): JSONObject {
    if (contentType?.trim()?.startsWith("multipart/", ignoreCase = true) == true) {
      return decodeMultipartEnvelope(contentType, body)
        ?: throw ApiException(status, "服务器返回了无法解析的二进制响应，请更新 Lagoon 或 dsh")
    }
    val text = body.toString(Charsets.UTF_8)
    return try { JSONObject(text) } catch (error: Exception) {
      throw ApiException(status, "服务器响应不是 JSON，请确认地址指向 DeepSeek Harness 的 Web 实例")
    }
  }

  /**
   * `multipart/form-data` 信封解码。用 ISO-8859-1 做逐字节映射再切分，二进制分片不会被字符集转换破坏。
   */
  private fun decodeMultipartEnvelope(contentType: String, body: ByteArray): JSONObject? {
    val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1)?.trim()
      ?.takeIf(String::isNotEmpty) ?: return null
    val raw = body.toString(Charsets.ISO_8859_1)
    val parts = LinkedHashMap<String, String>()
    for (chunk in raw.split("--$boundary").drop(1)) {
      if (chunk.startsWith("--")) break // 结束分界
      val headerEnd = chunk.indexOf("\r\n\r\n")
      val headers = if (headerEnd < 0) chunk else chunk.substring(0, headerEnd)
      val content = if (headerEnd < 0) "" else chunk.substring(headerEnd + 4).removeSuffix("\r\n")
      val name = Regex("name=\"([^\"]*)\"").find(headers)?.groupValues?.get(1) ?: continue
      parts[name] = content
    }
    val envelope = parts["metadata"]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    val attachments = envelope.optJSONArray("attachments") ?: return envelope
    for (index in 0 until attachments.length()) {
      val attachment = attachments.optJSONObject(index) ?: continue
      if (!attachment.optString("codec").equals("bytes", ignoreCase = true)) continue
      val content = parts[attachment.optString("part")] ?: continue
      val path = attachment.optJSONArray("path") ?: continue
      val segments = (0 until path.length()).mapNotNull { path.opt(it) as? String }.filter(String::isNotEmpty)
      if (segments.isEmpty()) continue
      putAttachmentBytes(envelope, segments, java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.ISO_8859_1)))
    }
    return envelope
  }

  /** 把分片字节写回 `result.value` 上的声明路径；路径首层不是对象时保持原样（不猜形状）。 */
  private fun putAttachmentBytes(envelope: JSONObject, segments: List<String>, base64: String) {
    val root = envelope.optJSONObject("result")?.optJSONObject("value") ?: return
    var node = root
    for (segment in segments.dropLast(1)) {
      node = node.optJSONObject(segment) ?: JSONObject().also { node.put(segment, it) }
    }
    node.put(segments.last(), base64)
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
    "RESPONSE_TOO_LARGE" -> "响应过大，且该操作不能安全地重试"
    else -> upstream
  }

  private suspend fun callObject(endpoint: String, payload: RpcPayload = noArgs()): JSONObject =
    call(endpoint, payload) as? JSONObject ?: JSONObject()

  private fun httpMessage(status: Int): String = when (status) {
    401 -> repairMessage()
    403 -> UNTRUSTED_HOST_MESSAGE
    404 -> "该地址不是 DeepSeek Harness 的 Web 实例"
    else -> "请求失败：HTTP $status"
  }

  /** 认证栅栏内的精确 GET 路由（改动摘要/对比）；失败返回 null。 */
  private suspend fun fetchJson(url: HttpUrl): JSONObject? = withContext(Dispatchers.IO) {
    if (remoteBackend != null) return@withContext null
    val request = Request.Builder().url(url).header("Cookie", cookie()).get().build()
    try {
      runHttpCall(request) { response ->
        response.use {
          if (it.code !in 200..299) null
          else runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
        }
      }
    } catch (error: IOException) {
      null
    }
  }

  // ---------------------------------------------------------------- 会话读

  /**
   * 认证栅栏内的只读文件路由 `GET /api/file?path=<绝对路径>`（官方 Web 预览图片/PDF 用的同一条）。
   * 成功返回字节与 Content-Type；404/403 表示该路径不可读，返回 null 让调用方回落。
   */
  private suspend fun fetchMedia(path: String, maxBytes: Int): FilePreview? = withContext(Dispatchers.IO) {
    if (remoteBackend != null) return@withContext null
    val url = base.newBuilder()
      .addEncodedPathSegment("api")
      .addEncodedPathSegment("file")
      .addQueryParameter("path", path)
      .build()
    val request = Request.Builder().url(url).header("Cookie", cookie()).get().build()
    try {
      runHttpCall(request) { response ->
        response.use {
          when {
            it.code == 413 -> throw ApiException(413, "文件超过预览上限")
            it.code !in 200..299 -> null
            else -> {
              val body = it.body ?: return@use null
              if (body.contentLength() > maxBytes || body.source().request(maxBytes.toLong() + 1))
                throw ApiException(413, "文件超过预览上限")
              val bytes = body.bytes()
              val mime = it.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank)
              FilePreview(mime ?: "application/octet-stream", bytes)
            }
          }
        }
      }
    } catch (error: ApiException) {
      throw error
    } catch (error: IOException) {
      null
    }
  }

  /** 工作区相对路径 → `/api/file` 需要的绝对路径；目录未知时返回 null。 */
  private fun absoluteWorkspacePath(directory: String, path: String): String? {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("/")) return trimmed
    val root = normalizedDirectory(directory).ifBlank { return null }
    return "$root/${trimmed.trimStart('/')}"
  }

  /** `session/list` 的形状：`{items:[{sessionId,updatedAt,running,cwd,parentSessionId,projections,…}]}`。 */
  private data class Listing(val items: List<JSONObject>) {
    val running: Set<String>
      get() = items.filter { it.optBoolean("running") }.mapNotNull { it.str("sessionId").takeIf(String::isNotBlank) }.toSet()
  }

  /** The `session/list` read in flight; concurrent callers share it (see [listing]). */
  /** `workspace/follow` 的参数形状；首次成功或被拒绝后确定，见 [fetchWorkspaceBaseline]。 */
  @Volatile private var workspaceArgsStyle = WorkspaceArgsStyle.EMPTY
  private enum class WorkspaceArgsStyle { EMPTY, REQUEST }

  private var listingFlight: Deferred<Listing>? = null
  private val listingLock = Mutex()

  /**
   * `session/list` backs the probe, the catalog, activity and every child listing, so one control-plane
   * read used to issue it many times at once. Callers that arrive while a read is in flight share it;
   * nothing is cached afterwards, so a later call always sees fresh state. The shared read runs in
   * [ioScope], so one caller's cancellation does not fail the others.
   */
  private suspend fun listing(): Listing {
    val flight = listingLock.withLock {
      listingFlight?.takeIf { it.isActive } ?: ioScope.async { fetchListing() }.also { listingFlight = it }
    }
    return flight.await()
  }

  private suspend fun fetchListing(): Listing {
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
    // DSH 的空白会话是“尚未开始任何轮次”的服务端草稿。顶层 `blank` 是权威字段；
    // 只带投影的旧代际回退到 `sessionListMetadata.blank`。一旦本机见过它开始过轮次，
    // 就不允许更旧的列表/事件把它重新标成空白。running 是同一事实的更新信号，优先相信它。
    val metadata = values.obj("sessionListMetadata")
    val listedBlank = if (item.has("blank")) item.optBoolean("blank")
      else metadata.optBoolean("blank", false)
    // lastPromptAt 是“已经收到过用户 prompt”的持久事实；即使 blank 是旧缓存，也不能把有 prompt 的会话藏掉。
    val prompted = (metadata.opt("lastPromptAt") as? Number)?.toLong()?.let { it > 0 } == true
    val running = item.optBoolean("running")
    if (executions.status(id, running)) clearKnownOutcome(id)
    val blank = listedBlank && !prompted && !running && previous?.blank != false
    val model = modelChoice(values.obj("modelSelection"), previous?.model)
    return Session(
      id = id,
      directory = directory,
      title = title.ifBlank { "新会话" },
      updated = updated,
      parentId = item.str("parentSessionId").ifBlank { null },
      subagentMode = values.obj("subagent").str("mode").takeIf { it.isNotBlank() },
      projectId = directory.ifBlank { null },
      created = previous?.created ?: updated,
      archived = if (archiveBaselineKnown) id in archivedSessions else previous?.archived == true,
      permission = values.obj("permissions").str("currentValue").ifBlank { previous?.permission.orEmpty() },
      agent = values.str("agentPreset").ifBlank { previous?.agent }?.takeIf(String::isNotBlank),
      model = model,
      viewed = previous?.viewed ?: 0,
      idle = if (running) 0 else previous?.idle ?: 0,
      // A result belongs to one execution. Never project the prior turn's result while a new turn runs.
      outcome = if (running) null else executions.outcome(id),
      revertMessageId = null,
      serverId = previous?.serverId,
      blank = blank
    )
  }

  /** The official projection is flat: `modelSelection.next/lastUsed`, not a nested selection object. */
  private fun modelChoice(selection: JSONObject, fallback: ModelChoice?): ModelChoice? {
    val next = selection.obj("next")
    val last = selection.obj("lastUsed")
    val chosen = if (next.length() > 0) next else last
    val provider = chosen.str("provider")
    val model = chosen.str("model")
    return if (provider.isBlank() || model.isBlank()) fallback
    else ModelChoice(provider, model, fallback?.label?.takeIf { fallback.providerId == provider && fallback.modelId == model } ?: model,
      chosen.str("reasoningEffort").takeIf { it.isNotBlank() })
  }

  private fun clearKnownOutcome(sessionId: String) {
    known.computeIfPresent(sessionId) { _, current -> current.copy(outcome = null) }
  }

  private suspend fun sessionOrFetch(id: String): Session = known[id] ?: run {
    listing()
    known[id] ?: throw ApiException(404, "会话不存在或已被删除")
  }

  override suspend fun session(id: String): Session = sessionOrFetch(id)

  override suspend fun projects(): List<Project> = withContext(Dispatchers.IO) {
    // DSH 的 Workspace 就是「项目」：workspace/follow 的 baseline 给出全量清单。
    val items = workspaceItems ?: fetchWorkspaceBaseline()
    workspaceProjects(items)
  }

  /**
   * 行内标签用的标题。`workspace/follow` 已经在持续维护清单，这里只读本地副本，不开新流；
   * 尚未收到 baseline 时返回空列表，界面回退到目录名，下一次对账再补上。
   */
  override suspend fun projectsForDisplay(): List<Project> =
    workspaceItems?.let(::workspaceProjects).orEmpty()

  private fun workspaceProjects(items: List<JSONObject>): List<Project> = items.mapNotNull { workspace ->
    val path = workspace.str("path").ifBlank { return@mapNotNull null }
    Project(
      id = workspace.str("workspaceId").ifBlank { path },
      directory = path,
      name = workspace.str("title").ifBlank { path.trimEnd('/').substringAfterLast('/').ifBlank { path } }
    )
  }

  /**
   * 取一次 workspace/follow 首帧并落缓存（事件流不在线时的兜底）。读不到就报错，不能装成“没有工作区”。
   *
   * 参数形状按代际不同：0.2.1 线只接受 `{args:{}}`，更新的代际用 `{args:{request:{}}}`。
   * 先用本机已知形状，被 `gateway/arguments-invalid` 拒绝时换另一种并记住，避免把协议代际写死。
   */
  private suspend fun fetchWorkspaceBaseline(): List<JSONObject> {
    var frame: JSONObject
    if (workspaceArgsStyle == WorkspaceArgsStyle.REQUEST) {
      frame = try {
        fetchFirstFrame("workspace/follow", requestArgs(JSONObject()))
      } catch (error: ApiException) {
        if (error.code != "gateway/arguments-invalid") throw error
        workspaceArgsStyle = WorkspaceArgsStyle.EMPTY
        fetchFirstFrame("workspace/follow", noArgs())
      }
    } else {
      frame = try {
        fetchFirstFrame("workspace/follow", noArgs())
      } catch (error: ApiException) {
        if (error.code != "gateway/arguments-invalid") throw error
        workspaceArgsStyle = WorkspaceArgsStyle.REQUEST
        fetchFirstFrame("workspace/follow", requestArgs(JSONObject()))
      }
    }
    if (frame.str("type") == "baseline") applyWorkspaceFrame(frame)
    return workspaceItems ?: throw ApiException(502, "工作区列表为空：workspace/follow 没有返回 baseline")
  }

  /** baseline / upsert / remove / order / archived 帧；pinned 暂不消费。 */
  private fun applyWorkspaceFrame(frame: JSONObject) {
    val snapshot = synchronized(workspaceLock) {
      when (frame.str("type")) {
        "baseline" -> {
          val value = frame.obj("value")
          workspaceItems = value.arr("items").objects()
          replaceArchiveSet(value.arr("archivedSessionIds").strings())
          serverPinned.clear()
          serverPinned += value.arr("pinnedSessionIds").strings()
        }
        // 置顶是独立的一帧（锚点与 `archived` 同形）：整值替换，不做增量猜测。
        "pinned" -> {
          serverPinned.clear()
          serverPinned += frame.arr("pinnedSessionIds").strings()
        }
        // `replace` 是"整份过滤后的状态"：主机在某个工作区不再对当前 peer 可见时发它，
        // 而不是发 `remove`（客户端不会复活被删的工作区）。按基线处理，否则列表会留下主机
        // 已经收回的条目。
        "replace" -> {
          val value = frame.obj("value")
          workspaceItems = value.arr("items").objects()
          if (value.has("archivedSessionIds")) {
            replaceArchiveSet(value.arr("archivedSessionIds").strings())
          }
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
          val list = workspaceItems
          if (list != null) {
            workspaceItems = order.mapNotNull { id -> list.firstOrNull { it.str("workspaceId") == id } } +
              list.filterNot { it.str("workspaceId") in order }
          }
        }
        "archived" -> {
          replaceArchiveSet(frame.arr("archivedSessionIds").strings())
        }
      }
      workspaceItems
    }
    snapshot?.let { workspacePublisher.tryEmit(workspaceProjects(it)) }
  }

  private fun replaceArchiveSet(ids: List<String>) {
    archivedSessions.clear()
    archivedSessions.addAll(ids)
    archiveBaselineKnown = true
    known.replaceAll { id, session -> session.copy(archived = id in archivedSessions) }
  }

  override suspend fun projectAt(directory: String): Project? {
    val normalized = directory.trimEnd('/')
    return projects().firstOrNull { it.directory.trimEnd('/') == normalized || it.directories.any { path -> path.trimEnd('/') == normalized } }
  }

  override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int): ApiPage<Session> =
    withContext(Dispatchers.IO) {
      val listing = listing()
      val items = listing.items
      val sessions = items.mapNotNull { itemToSession(it) }
        .filter { directory == null || it.directory.trimEnd('/') == directory.trimEnd('/') }
        .filter { it.parentId == null }
        // 空白会话不进入首页目录，也不为它们各开一条 session/follow 流；它们仍留在 known 里，
        // 下次新建同目录会话时可以复用。
        .filterNot { it.blank }
        .sortedByDescending { it.activityAt }
      // 首页只关注真正在跑的会话：空闲会话没有实时内容，长期 follow 只会占流预算。
      // 打开的会话由 messagesPage 单独加入并在淘汰时固定。
      rememberWatched(sessions.filter { it.id in listing.running })
      // session/list 没有服务端分页；在本地切片，避免首页每次物化并下发全部历史会话。
      val start = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
      val page = sessions.drop(start).take(size.coerceIn(1, 200))
      val next = (start + page.size).takeIf { it < sessions.size }?.toString()
      ApiPage(page, next)
    }

  override suspend fun viewSession(session: Session, idle: Long) {
    // DSH 没有「已查看」写接口；本地时间线已经按事件推进。
  }

  override suspend fun messagesPage(id: String, cursor: String?, size: Int): ApiPage<Message> = withContext(Dispatchers.IO) {
    val session = sessionOrFetch(id)
    // Reading a transcript always needs a follow, even if a background monitor had switched
    // content off before this server became writable.
    setContentWatching(true)
    // 当前阅读的会话固定保留；首页刷新只能替换其他 follow。
    activeSessionId = session.id
    rememberWatched(listOf(session))
    val wanted = size.coerceIn(1, SNAPSHOT_MAX_MESSAGES)
    if (cursor == null) {
      // 首屏：`session/follow` 的首帧快照是 Host 按 maxMessages/turnWindow 切好的 turn 窗口
      // （与官方客户端一致：最多 500 条、至少 2 轮）。整窗装入，不再用消息级 size 二次截断；
      // size 只用于后续 `session/page`。二次截断会从轮次中间开始，丢掉这一轮的用户提问。
      // 取快照的流会继续留着做实时流，不再取完就取消。
      val snapshot = snapshotFor(session)
      val through = snapshot.optDouble("cursor", 0.0).toLong()
      // 空会话的切点是 -1（Host 的 `cursorBeforeNext(0)`）；0 也合法，不能当作“没有日志”。
      if (through >= 0) cursors[id] = through
      val records = snapshot.arr("records").objects()
      val messages = recordsToMessages(session, records)
      // 空白会话排障：记录“拿到了什么”，不记录任何消息内容。
      val snapshotCursor = snapshot.optDouble("cursor", -1.0).toLong()
      val snapshotHasMore = snapshot.optBoolean("hasMore")
      // 与官方 `installWindow(entries, hasMore)` 对齐：hasMore 就是“还能向更早翻页”，
      // next 取本页最早一条记录的 seq（等价于官方的 baseSeq / beforeSeq）。
      val next = if (snapshotHasMore && records.isNotEmpty()) earlierCursor(records) else null
      snapshotStats[session.id] = RemoteSnapshotStats(session.id, records.size, messages.size,
        snapshotCursor, snapshotHasMore, pageNext = next?.toLongOrNull())
      Diagnostics.info("DshApi", "session/follow 快照 · ${address(id).str("kind")} · records=${records.size} " +
        "messages=${messages.size} cursor=$snapshotCursor hasMore=$snapshotHasMore next=${next ?: "-"}")
      // 时间线被整页替换：清掉实时部件记录，让后续增量重建部件而不是写进已消失的行。
      liveParts.keys.filter { it.startsWith("${session.id}|") }.forEach { liveParts.remove(it) }
      advanceSeq(id, records.mapNotNull { it.obj("event").optDouble("seq", 0.0).toLong().takeIf { value -> value > 0 } }.maxOrNull())
      // 不截断：Host 已经限制了窗口大小，且窗口起点是 turn/start。这里再 takeLast(size)
      // 会把用户提问切掉，UI 只剩最新一轮的 assistant 步骤。
      ApiPage(messages, next)
    } else {
      val through = cursors[id] ?: run {
        val snapshot = snapshotFor(session)
        snapshot.optDouble("cursor", 0.0).toLong().also { if (it >= 0) cursors[id] = it }
      }
      if (through < 0) return@withContext ApiPage(emptyList(), null)
      val before = cursor.toLongOrNull() ?: return@withContext ApiPage(emptyList(), null)
      val request = JSONObject()
        .put("address", address(id))
        .put("throughSeq", through)
        .put("beforeSeq", before)
        .put("maxMessages", wanted)
        // 与官方翻页一致：至少 50 条、至少 2 个 turn 再切，避免一页从轮次中间开始。
        // 0.2.x 早期 Host 若不认识 turnWindow，下面会降级重试一次。
        .put("turnWindow", JSONObject()
          .put("minMessages", wanted.coerceAtMost(SNAPSHOT_MIN_MESSAGES))
          .put("minTurns", 2))
      val page = try {
        callObject("session/page", requestArgs(request))
      } catch (error: Exception) {
        if (!isGatewayArgumentsInvalid(error)) throw error
        request.remove("turnWindow")
        Diagnostics.info("DshApi", "session/page 不接受 turnWindow，已降级为消息窗口")
        callObject("session/page", requestArgs(request))
      }
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
    payload: RpcPayload,
    onItem: (JSONObject) -> Unit,
    onClosed: (Throwable?) -> Unit
  ): WebSocket {
    var acquired: WebSocket? = null
    try {
      return withContext(Dispatchers.IO) {
        val wirePayload = payload.json
        (remoteBackend?.open(endpoint, wirePayload, onItem, onClosed)
          ?: muxOwner.open(endpoint, wirePayload, onItem, onClosed)).also { acquired = it }
      }
    } catch (error: Throwable) {
      // withContext has prompt cancellation on the return dispatcher: a freshly opened resource
      // may never reach its caller. Keep ownership here until that handoff actually succeeds.
      acquired?.cancel()
      throw error
    }
  }

  /** Test seam for exercising physical API-Peer ownership without starting the full event loops. */
  internal suspend fun openMuxForTest(
    endpoint: String,
    payload: JSONObject = JSONObject(),
    onItem: (JSONObject) -> Unit,
    onClosed: (Throwable?) -> Unit
  ): WebSocket = openMux(endpoint, RpcPayload(payload), onItem, onClosed)

  /**
   * One API Peer socket carrying many logical streams.  The logical handles deliberately retain the
   * small WebSocket surface used by the rest of DshApi (cancel only); the physical owner owns frame
   * routing, bounded callback queues, and close/error fan-out.
   */
  private class MuxLogicalState(
    val id: String,
    val endpoint: String,
    val onItem: (JSONObject) -> Unit,
    val onClosed: (Throwable?) -> Unit,
    val frames: Channel<JSONObject> = Channel(MUX_STREAM_BUFFER),
    var worker: Job? = null
  )

  private inner class PhysicalMuxOwner {
    private val lock = Any()
    private val streams = LinkedHashMap<String, MuxLogicalState>()
    private val pendingOpens = LinkedHashMap<String, String>()
    @Volatile private var physical: WebSocket? = null
    private var connectingSocket: WebSocket? = null
    // Allocate before cookie() suspends; every callback and send belongs to exactly this attempt.
    private var generation = 0L
    private var activeGeneration: Long? = null
    @Volatile private var ownerClosed = false

    suspend fun open(endpoint: String, payload: JSONObject, onItem: (JSONObject) -> Unit,
      onClosed: (Throwable?) -> Unit): WebSocket {
      if (ownerClosed || closed) throw IOException("DSH mux 已关闭")
      val id = UUID.randomUUID().toString()
      val state = MuxLogicalState(id, endpoint, onItem, onClosed)
      state.worker = ioScope.launch {
        var failure: Throwable? = null
        try {
          while (true) {
            val result = state.frames.receiveCatching()
            if (result.isClosed) {
              failure = result.exceptionOrNull()
              break
            }
            state.onItem(result.getOrThrow())
          }
        } catch (cancel: CancellationException) {
          failure = cancel
          throw cancel
        } catch (error: Throwable) {
          failure = error
        } finally {
          val stillOwned = synchronized(lock) { streams[id] === state }
          if (stillOwned && failure != null) {
            // A callback/parser failure belongs to this logical stream. Cancel its server-side
            // producer, but keep the physical API-Peer socket and all sibling streams alive.
            cancelLogical(id)
          } else {
            synchronized(lock) {
              if (streams[id] === state) {
                streams.remove(id)
                pendingOpens.remove(id)
              }
            }
          }
          state.onClosed(failure)
        }
      }

      var startGeneration: Long? = null
      var existing: Pair<Long, WebSocket>? = null
      synchronized(lock) {
        if (ownerClosed || closed) {
          state.frames.close(IOException("DSH mux 已关闭"))
        } else {
          streams[id] = state
          val open = JSONObject().put("type", "open").put("streamId", id)
            .put("endpoint", endpoint).put("payload", payload).toString()
          pendingOpens[id] = open
          existing = physical?.let { activeGeneration!! to it }
          if (activeGeneration == null) {
            startGeneration = ++generation
            activeGeneration = startGeneration
          }
        }
      }
      if (ownerClosed || closed) {
        state.worker?.cancel()
        throw IOException("DSH mux 已关闭")
      }
      startGeneration?.let { connectPhysical(it) }
        ?: existing?.let { (attempt, socket) -> sendPending(attempt, socket) }
      return LogicalSocket(id, state, this)
    }

    private fun isActive(attempt: Long): Boolean =
      !ownerClosed && !closed && activeGeneration == attempt

    private suspend fun connectPhysical(attempt: Long) {
      val current = try { cookie() } catch (error: Throwable) {
        failPhysical(attempt, error)
        throw error
      }
      if (!synchronized(lock) { isActive(attempt) }) return
      val socketUrl = base.newBuilder()
        .addEncodedPathSegment("api")
        .addEncodedPathSegment("remote.mux")
        .build()
      val request = Request.Builder().url(socketUrl).header("Cookie", current).build()
      val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          val accepted = synchronized(lock) {
            if (!isActive(attempt)) false else {
              physical = webSocket
              connectingSocket = null
              true
            }
          }
          if (!accepted) {
            webSocket.cancel()
            return
          }
          sendPending(attempt, webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          if (!synchronized(lock) { isActive(attempt) && physical === webSocket }) return
          val frame = try { JSONObject(text) } catch (error: Exception) {
            failPhysical(attempt, IOException("mux 帧不是有效 JSON", error)); return
          }
          val type = frame.str("type")
          if (type !in setOf("item", "end", "error")) return
          val id = frame.str("streamId")
          synchronized(lock) {
            if (!isActive(attempt) || physical !== webSocket) return
            val state = streams[id] ?: return
            when (type) {
              "item" -> {
                val value = frame.opt("value") as? JSONObject
                if (value == null) finishLogical(id, IOException("mux item 缺少对象载荷"), cancel = true)
                else if (state.frames.trySend(value).isFailure) {
                  finishLogical(id, IOException("mux 流消费者过慢"), cancel = true)
                }
              }
              "error" -> {
                val error = frame.obj("error")
                val code = error.str("code").takeIf { it.isNotBlank() }
                val details = error.optJSONObject("details")
                finishLogical(id, ApiException(502,
                  friendlyMessage(code.orEmpty(), error.str("message").ifBlank { "会话流出错" }), code, details))
              }
              "end" -> finishLogical(id, null)
            }
          }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          if (!synchronized(lock) { isActive(attempt) }) return
          val error = if (response?.code == 409) IOException("DSH API Peer 已有连接（HTTP 409）", t) else t
          if (response?.code == 401) ioScope.launch {
            // Reject before releasing this generation so a replacement cannot reuse its cookie.
            authLock.withLock {
              synchronized(lock) {
                if (!isActive(attempt)) return@withLock
                if (sessionCookie == current) {
                  sessionCookie = null
                  if (signingSecret != null && base.isHttps && !plainListener) plainListener = true
                  else {
                    cookieRejected = true
                    if (DshCookie.isDurable(current)) runCatching { onCookie("") }
                  }
                }
                failPhysical(attempt, error)
              }
            }
          } else failPhysical(attempt, error)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          failPhysical(attempt, IOException("DeepSeek Harness 关闭了连接（$code）"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          // Some peers emit onClosed without onClosing; only this generation may settle streams.
          failPhysical(attempt, IOException("DeepSeek Harness 物理 mux 已关闭（$code）"))
        }
      }
      val socket = try { streamHttp.newWebSocket(request, listener) } catch (error: Throwable) {
        failPhysical(attempt, error)
        throw error
      }
      // onFailure/onOpen may run before newWebSocket returns. Never resurrect a retired attempt.
      val accepted = synchronized(lock) {
        if (!isActive(attempt)) false else {
          if (physical == null) connectingSocket = socket
          true
        }
      }
      if (!accepted) socket.cancel()
    }

    private fun sendPending(attempt: Long, socket: WebSocket) {
      synchronized(lock) {
        if (!isActive(attempt) || physical !== socket) return
        val pending = pendingOpens.toMap()
        for ((id, open) in pending) {
          if (pendingOpens.remove(id) == null || !streams.containsKey(id)) continue
          if (!socket.send(open)) {
            failPhysical(attempt, IOException("mux open 帧发送失败"))
            return
          }
        }
      }
    }

    private fun finishLogical(id: String, cause: Throwable?, cancel: Boolean = false) {
      val state = synchronized(lock) {
        val removed = streams.remove(id) ?: return
        val wasPending = pendingOpens.remove(id) != null
        if (cancel && !wasPending) {
          physical?.send(JSONObject().put("type", "cancel").put("streamId", id).toString())
        }
        removed
      }
      state.frames.close(cause)
      // API-Peer cookies own a lifecycle socket. An idle socket must survive logical end/cancel.
    }

    private fun cancelLogical(id: String) = finishLogical(id, null, cancel = true)

    private fun failPhysical(attempt: Long, error: Throwable) {
      val states: List<MuxLogicalState>
      val socket: WebSocket?
      synchronized(lock) {
        if (!isActive(attempt)) return
        activeGeneration = null
        socket = physical ?: connectingSocket
        physical = null
        connectingSocket = null
        states = streams.values.toList()
        streams.clear()
        pendingOpens.clear()
      }
      states.forEach { it.frames.close(error) }
      socket?.cancel()
    }

    fun close() {
      val states: List<MuxLogicalState>
      val socket: WebSocket?
      synchronized(lock) {
        ownerClosed = true
        activeGeneration = null
        states = streams.values.toList()
        streams.clear()
        pendingOpens.clear()
        socket = physical ?: connectingSocket
        physical = null
        connectingSocket = null
      }
      states.forEach { it.frames.close() }
      socket?.cancel()
    }

    private inner class LogicalSocket(
      private val id: String,
      private val state: MuxLogicalState,
      private val owner: PhysicalMuxOwner
    ) : WebSocket {
      private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
      override fun request(): Request = Request.Builder().url(base).build()
      override fun queueSize(): Long = physical?.queueSize() ?: 0L
      override fun send(text: String): Boolean = false
      override fun send(bytes: okio.ByteString): Boolean = false
      override fun close(code: Int, reason: String?): Boolean { cancel(); return true }
      override fun cancel() {
        if (!finished.compareAndSet(false, true)) return
        owner.cancelLogical(id)
      }
    }
  }

  /**
   * 取一条流的首个 `item` 帧（`workspace/follow` 的清单、`session/follow` 的快照、`job/list` 的行）。
   * 失败必须带端点与上游原因抛出：把“读不到”当成空数据会让会话页永远空白且无法排障。
   */
  private suspend fun fetchFirstFrame(endpoint: String, payload: RpcPayload): JSONObject {
    val deferred = CompletableDeferred<JSONObject>()
    val socket = try {
      openMux(endpoint, payload, onItem = { frame ->
        if (!deferred.isCompleted) deferred.complete(frame)
      }, onClosed = { cause ->
        if (!deferred.isCompleted) deferred.completeExceptionally(cause ?: IOException("流已关闭"))
      })
    } catch (error: Exception) {
      throw streamFailure(endpoint, error)
    }
    return try {
      withTimeout(STREAM_FIRST_FRAME_TIMEOUT_MILLIS) { deferred.await() }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      throw streamFailure(endpoint, error)
    } finally {
      runCatching { socket.cancel() }
    }
  }

  /** 把流层的失败折成用户能看懂、也能定位的一层错误（保留上游 code）。 */
  private fun streamFailure(endpoint: String, error: Throwable): ApiException {
    if (error is ApiException) return error
    val gateway = error as? RemoteGatewayException
    val code = gateway?.code ?: (error as? RemoteRpcException)?.code
    val details = gateway?.details
    val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    return ApiException(502, "读取 $endpoint 失败：${friendlyMessage(code.orEmpty(), reason)}", code, details)
  }

  /**
   * 首屏快照从长期 follow 流取。参数对齐官方客户端的窗口：`assistantStream` 让“正在跑的轮次”
   * 也能看到已产出的内容，`maxMessages`/`turnWindow` 限制单帧体积，更早的历史仍由
   * `session/page` 翻页补。
   *
   * 首帧必须是 `snapshot`：中枢/协议漂移时返回别的帧，若直接当空日志处理就会得到永远空白的会话。
   * 失败会落在 [snapshotStats] 里，连接医生能看到“读了几条、游标在哪、失败原因是什么”。
   */
  private suspend fun snapshotFor(session: Session, fresh: Boolean = true): JSONObject {
    var failure: Exception? = null
    for (attempt in 1..SNAPSHOT_ATTEMPTS) {
      val state = followFor(session, fresh = fresh)
      try {
        val frame = withTimeout(STREAM_FIRST_FRAME_TIMEOUT_MILLIS) { state.snapshot.await() }
        if (frame.str("type") != "snapshot") throw ApiException(502,
          "会话流首帧不是快照（${frame.str("type").ifBlank { "未知帧" }}）", "stream/not-snapshot")
        return frame
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        failure = error
        snapshotStats[session.id] = RemoteSnapshotStats(session.id, 0, 0, -1, false,
          error = streamFailure("session/follow", error).message)
        stopFollow(session.id)
        if (attempt < SNAPSHOT_ATTEMPTS) kotlinx.coroutines.delay(SNAPSHOT_RETRY_DELAY_MILLIS)
      }
    }
    val last = failure ?: IOException("无法读取会话日志：没有返回快照")
    throw last as? ApiException ?: streamFailure("session/follow", last)
  }

  /**
   * 会话地址。子代理会话必须用父会话地址投递（Host 会拒绝 `kind:"session"` 并报
   * `session/agent-busy: subagent Sessions require their durable parent address`）。
   */
  private fun address(id: String): JSONObject {
    val session = known[id]
    val parent = session?.parentId?.takeIf { it.isNotBlank() }
    if (parent != null && parent != id) {
      return JSONObject().put("kind", "subagent").put("parentSessionId", parent)
        .put("childSessionId", id).put("mode", session.subagentMode?.takeIf { it.isNotBlank() } ?: "unknown")
    }
    return JSONObject().put("kind", "session").put("sessionId", id)
  }

  // ---------------------------------------------------------------- 事件翻译

  /**
   * 把一条 DSH 会话事件翻译成 Lagoon 认识的事件；一次输入可能产出多条（例如 `assistant/message`
   * 里的多个内容块）。不认识的记录（内部事件、log-only 标记）返回空列表。
   */
  internal suspend fun translate(session: Session, event: JSONObject): List<ServerEvent> {
    val type = event.str("type")
    val seq = event.optDouble("seq", 0.0).toLong()
    val time = event.optDouble("time", 0.0).toLong()
    val data = event.obj("data")
    val output = mutableListOf<ServerEvent>()
    fun emit(name: String, properties: JSONObject) {
      // DSH seq 只在单个会话的日志内单调；不同会话可以同时拥有 seq=1。
      // 全局 AllServersOverview 按 ID 去重，因此会话身份必须进入耐久事件 ID。
      output += ServerEvent(id = "dsh:${session.id}:$seq:${output.size}", directory = session.directory, type = name, properties = JSONObject(properties.toString()), created = time)
    }
    val turn = data.optLong("turn").takeIf { it > 0 } ?: durableTurns[session.id]
    if (type == "turn/start" && turn != null) durableTurns[session.id] = turn
    val base = JSONObject().put("sessionID", session.id).put("sequence", seq).putOpt("turn", turn)
    when (type) {
      "turn/start" -> {
        if (executions.start(session.id, seq)) clearKnownOutcome(session.id)
        emit("session.execution.started", base.put("at", time))
      }
      "turn/end" -> emitTurnEnd(session, base, data, ::emit)
      "step/start" -> emit("session.step.started", base
        .put("assistantMessageID", messageId(data))
        .put("started", time).putOpt("agent", session.agent).apply {
          session.model?.let { put("model", JSONObject().put("providerID", it.providerId).put("id", it.modelId).put("name", it.label)) }
        })
      "step/end" -> emit("session.step.ended", base
        .put("assistantMessageID", messageId(data))
        .put("finish", "stop"))
      "user/message" -> {
        // 图片/文件在日志里只有附件引用，先把字节换成 data URI 再投影，否则时间线只剩空气泡。
        val enriched = withAttachmentBlobs(session.id, data)
        val projected = dshInputMessage(session.id, seq, time, turn, enriched) ?: return emptyList()
        // Keep one source-aware event shape for all DSH inputs; the shared reducer uses the
        // projected role/source and never falls back from a rejected source to p.text.
        val name = if (projected.isHumanInput) "session.user.message" else "session.dsh.input"
        emit(name, base.put("messageID", data.str("id").ifBlank { "user:${session.id}:$seq" })
          .put("text", data.arr("content").contentText()).put("input", enriched))
      }
      "assistant/message" -> {
        val assistantId = messageId(data)
        liveParts.remove(liveKey(session.id, data))
        // Replace authoritative text/reasoning as a batch: multiple blocks of one kind and retries
        // must not overwrite the last live part or duplicate it. Tool heads stay call-id anchored.
        emit("session.dsh.assistant", base.put("assistantMessageID", assistantId)
          .put("content", data.obj("message").arr("content")))
      }
      "tool/call" -> {
        val projection = ptcProjection(session.id)
        synchronized(projection) { projection.indexRoots(listOf(event)) }
        val callId = data.str("callId")
        val assistantId = nativeToolMessageId(data)
        // The bounded PTC ancestry cache must never limit native tool cards.
        if (callId.isNotBlank() && assistantId != null) {
          val input = parseArguments(data.str("arguments"))
          emit("session.tool.input.started", base.put("assistantMessageID", assistantId).put("id", callId).put("name", data.str("name")))
          emit("session.tool.called", base.put("assistantMessageID", assistantId).put("id", callId).put("name", data.str("name")).put("input", input))
        }
      }
      "tool/result" -> {
        val message = data.obj("message")
        val callId = message.str("toolCallId").ifBlank { data.str("callId") }
        if (callId.isNotBlank()) {
          val state = dshToolState(data)
          val properties = base.put("assistantMessageID", messageId(data)).put("id", callId)
            .put("content", state.arr("content")).put("metadata", state.obj("metadata"))
            .put("name", state.obj("metadata").str("tool"))
          if (state.str("status") == "error") emit("session.tool.failed", properties.put("error", state.obj("error")))
          else emit("session.tool.success", properties)
        }
      }
      // PTC（程序化工具调用）的子调用是**耐久事件**，不是实时增量。v1 代际把它记作
      // `tool/code-dispatch*`，v3 改名成 `tool/ptc-dispatch*`；两个名字都得认，否则开着 PTC
      // 的主机上工具卡片整段缺席。
      "tool/ptc-dispatch-start", "tool/code-dispatch-start",
      "tool/ptc-dispatch", "tool/code-dispatch" -> {
        val projection = ptcProjection(session.id)
        val child = synchronized(projection) { projection.process(type, data, seq, time) }
        if (child != null) {
          val properties = child.properties().put("sessionID", session.id).put("sequence", seq)
          // Child payloads have no turn/step; ancestry is anchored by the native root call.
          if (child.kind == DshPtcEvent.Kind.START) {
            emit("session.tool.input.started", properties)
            emit("session.tool.called", properties)
          } else {
            emit(if (child.state.isError) "session.tool.failed" else "session.tool.success", properties)
          }
        }
      }
      "session/title" -> emit("session.renamed", base.put("title", data.str("title")))
      // `agent-preset/selected` 是耐久事件：会话的当前预设以它为准，列表投影只是快照。
      "agent-preset/selected" -> {
        val preset = normalizeAgentPreset(data.str("agentPreset"))
        if (preset.isNotBlank()) {
          known[session.id]?.let { known[session.id] = it.copy(agent = preset) }
          emit("session.agent.selected", base.put("agent", preset))
        }
      }
      // `model/selection` is the durable selection intent itself: `{provider, model, reasoningEffort?}`.
      // The projection shape (`modelSelection.next/lastUsed`) is only for session/projections.
      "model/selection" -> {
        val provider = data.str("provider")
        val modelId = data.str("model")
        if (provider.isNotBlank() && modelId.isNotBlank()) {
          val selected = ModelChoice(provider, modelId, modelId,
            data.str("reasoningEffort").takeIf(String::isNotBlank))
          known.computeIfPresent(session.id) { _, current -> current.copy(model = selected) }
          emit("session.model.selected", base.put("model", JSONObject()
            .put("providerID", provider).put("id", modelId).put("name", modelId)
            .putOpt("variant", selected.variant)))
        }
      }
      // 清单与目标都是整值投影：`todo/write` 每次都是完整替换，`goal/change` 是完整快照。
      "todo/write" -> emit("session.todos.changed", base.put("todos", data.arr("todos")))
      "goal/change" -> emit("session.goal.changed", base)
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

  private fun nativeToolMessageId(data: JSONObject): String? {
    listOf("assistantMessageID", "assistantMessageId", "messageID", "messageId").forEach { key ->
      (data.opt(key) as? String)?.takeIf(String::isNotBlank)?.let { return it }
    }
    val turn = data.optLong("turn").takeIf { it > 0 } ?: return null
    val step = data.optLong("step").takeIf { it > 0 } ?: return null
    return "msg_$turn:$step"
  }

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
          if ("text" in parts) push(ServerEvent(id = "dsh:live:${session.id}:${nextLiveEventId()}:text:reset", directory = session.directory, type = "session.text.ended",
            properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("text", ""), created = at))
          if ("reasoning" in parts) push(ServerEvent(id = "dsh:live:${session.id}:${nextLiveEventId()}:reasoning:reset", directory = session.directory, type = "session.reasoning.ended",
            properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("text", ""), created = at))
        }
      }
      "chunk" -> {
        val key = liveAttempts[frame.str("attemptId")] ?: return
        replayLiveChunk(session, key, frame.obj("chunk"), frame.optDouble("time", 0.0).toLong(), push)
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
        // 补出这条 assistant 消息（投影幂等）：跟随在 attempt 中途打开时也不会丢文本。
        push(ServerEvent(id = "dsh:live:${session.id}:${nextLiveEventId()}:step", directory = session.directory, type = "session.step.started",
          properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("started", at), created = at))
      }
      push(ServerEvent(id = "dsh:live:${session.id}:${nextLiveEventId()}:$kind:start", directory = session.directory,
        type = if (kind == "reasoning") "session.reasoning.started" else "session.text.started",
        properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("started", at), created = at))
    }
    push(ServerEvent(id = "dsh:live:${session.id}:${nextLiveEventId()}:$kind:delta", directory = session.directory,
      type = if (kind == "reasoning") "session.reasoning.delta" else "session.text.delta",
      properties = JSONObject().put("sessionID", session.id).put("assistantMessageID", message).put("delta", text), created = at))
  }

  /** 一条 `chunk` 帧里的增量（实时帧与开帧基线共用）。 */
  private fun replayLiveChunk(session: Session, key: String, chunk: JSONObject, time: Long, push: (ServerEvent) -> Unit) {
    val kind = when (chunk.str("type")) {
      "text-delta" -> "text"
      "reasoning-delta" -> "reasoning"
      else -> return
    }
    val text = chunk.str("text")
    if (text.isEmpty()) return
    emitLiveDelta(session, key, kind, text, time.takeIf { it > 0 } ?: System.currentTimeMillis(), push)
  }

  /**
   * 登记 `session/follow` 开帧里**进行中**的 attempt，并重放它已经产出的增量。
   *
   * Host 只在开帧的 `assistantStream.activeAttempt` 里给出正在跑的 attempt，之后不会再补
   * `start` 帧；不登记的话后续 `chunk` 会因 `liveAttempts[attemptId]` 缺失被整段丢弃。
   * `stream[]` 是紧凑记录：逐条 `chunk`，或按 `time0/dt/texts` 展开的批量运行。
   */
  private fun seedLiveAttempt(session: Session, assistantStream: JSONObject, state: FollowState) {
    val active = assistantStream.obj("activeAttempt")
    val attemptId = active.str("attemptId").takeIf { it.isNotBlank() } ?: return
    val key = liveKey(session.id, active)
    liveAttempts[attemptId] = key
    val push: (ServerEvent) -> Unit = { emitFollow(state, it) }
    for (record in active.arr("stream").objects()) {
      when (record.str("type")) {
        "chunk" -> replayLiveChunk(session, key, record.obj("chunk"), record.optDouble("time", 0.0).toLong(), push)
        "text-chunks", "reasoning-chunks" -> {
          val kind = if (record.str("type") == "text-chunks") "text" else "reasoning"
          var at = record.optDouble("time0", 0.0).toLong()
          val dt = record.arr("dt")
          record.arr("texts").strings().forEachIndexed { index, text ->
            if (index > 0) at += dt.optDouble(index - 1, 0.0).toLong()
            if (text.isNotEmpty()) emitLiveDelta(session, key, kind, text, at.takeIf { it > 0 } ?: System.currentTimeMillis(), push)
          }
        }
        // 工具调用的实时增量不参与时间线；耐久 `tool/call` 会补出完整卡片。
        else -> Unit
      }
    }
  }

  /**
   * `turn/end` 是 DSH 唯一带结局的事件。先记下本机见过的这一轮（`succeeded` / `failed` /
   * `interrupted`），再折算成控制器认识的执行事件。列表和对账都没有这个字段，只能靠这里。
   */
  private fun emitTurnEnd(session: Session, base: JSONObject, data: JSONObject, emit: (String, JSONObject) -> Unit) {
    val reason = data.obj("reason")
    val outcome = when (reason.str("kind")) {
      "completed", "forked" -> "succeeded"
      "aborted", "blocked", "interrupted" -> "interrupted"
      else -> "failed"
    }
    if (!executions.settle(session.id, base.optLong("sequence"), outcome)) return
    known.computeIfPresent(session.id) { _, current -> current.copy(outcome = outcome) }
    when (reason.str("kind")) {
      "completed", "forked" -> {
        emit("session.execution.succeeded", base)
      }
      "aborted", "blocked", "interrupted" -> {
        emit("session.execution.interrupted", base.put("reason", reason.obj("reason").str("kind").ifBlank { "user" }))
      }
      "max-tokens" -> {
        emit("session.execution.failed", base.put("error", JSONObject()
          .put("type", "max-tokens")
          .put("message", "达到输出长度上限")))
      }
      else -> {
        val error = reason.obj("error")
        emit("session.execution.failed", base.put("error", JSONObject()
          .put("type", error.str("code").ifBlank { reason.str("kind").ifBlank { "error" } })
          .put("message", error.str("message").ifBlank { "执行失败" })))
      }
    }
  }

  private fun parseArguments(raw: String): Any =
    if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse { raw }

  /**
   * 把 `user/message` 里的图片/文件附件换成可渲染的 `data:` URI（`_blobs`）。
   *
   * DSH 的 prompt 内容只保留附件引用（`{type:"image", attachment:{attachmentId}}`），字节要用
   * `session/attachment` 换回来——这是官方 Web 渲染历史图片的同一条接口。读不到（旧实例没有该
   * 方法、或附件已被清理）时保留引用、放弃字节，绝不让时间线因为一张图片失败。
   */
  private suspend fun withAttachmentBlobs(sessionId: String, data: JSONObject): JSONObject {
    val blocks = data.arr("content").objects().filter { it.str("type") == "image" || it.str("type") == "file" }
    if (blocks.isEmpty()) return data
    val blobs = JSONObject()
    var budget = ATTACHMENT_BUDGET_BYTES
    for (block in blocks) {
      val attachment = block.obj("attachment")
      val attachmentId = attachment.str("attachmentId")
      if (attachmentId.isBlank()) continue
      val cached = attachmentBlobs[attachmentId]
      if (cached != null) {
        blobs.put(attachmentId, cached)
        continue
      }
      if (budget <= 0) continue
      val loaded = readAttachment(sessionId, attachment) ?: continue
      budget -= loaded.length
      if (attachmentBlobs.size >= ATTACHMENT_CACHE_LIMIT) attachmentBlobs.clear()
      attachmentBlobs[attachmentId] = loaded
      blobs.put(attachmentId, loaded)
    }
    if (blobs.length() == 0) return data
    return JSONObject(data.toString()).put("_blobs", blobs)
  }

  /** `session/attachment` → `data:<mediaType>;base64,…`；失败返回 null（只丢图片，不丢消息）。 */
  private suspend fun readAttachment(sessionId: String, attachment: JSONObject): String? = try {
    val canUseFull = DshAttachmentRequest.hasVerifiedReference(attachment)
    val full = fullAttachmentReference ?: canUseFull
    suspend fun load(style: Boolean): JSONObject = callObject("session/attachment",
      envelope(DshAttachmentRequest.args(sessionId, attachment, style)))
    val value = try {
      load(full).also { fullAttachmentReference = full }
    } catch (error: ApiException) {
      if (error.code != "gateway/arguments-invalid" || (!full && !canUseFull)) throw error
      load(!full).also { fullAttachmentReference = !full }
    }
    val resultRef = value.optJSONObject("attachment") ?: attachment
    if (resultRef.str("attachmentId") != attachment.str("attachmentId")) throw ApiException(502, "附件身份不匹配")
    value.optString("data").takeIf(String::isNotBlank)?.let { encoded ->
      "data:${resultRef.str("mediaType").ifBlank { "application/octet-stream" }};base64,$encoded"
    }
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (error: Exception) {
    Diagnostics.warn("DshApi", "附件读取失败", error)
    null
  }

  // ---------------------------------------------------------------- 事件流

  override fun events(onOpen: () -> Unit): Flow<ServerEvent> = callbackFlow {
    val channel = Channel<ServerEvent>(Channel.UNLIMITED)
    eventChannel = channel
    var closed = false
    val push: (ServerEvent) -> Unit = { event -> channel.trySend(event) }

    // follow 可能先于事件流建立：把快照之后、事件流挂上之前到达的增量补送，避免“打开会话的头几秒丢了”。
    flushFollowBuffers(push)

    // The business waterfall outlives its UI collector long enough to hand ownership back. A
    // collector cancellation must not cancel the gateway client before its queued `next` RPC.
    val eventsJob = ioScope.launch { runEventsLoop(push, onOpen) { closed } }
    val statusJob = launch { runStatusFallback(push) { closed } }

    val pump = launch {
      for (event in channel) send(event)
    }

    // Content is expensive (workspace/follow + one follow per running session). Background monitors
    // keep only `$events`; the selected connection switches this on. Switching it off stops the
    // follows so an idle background host cannot consume the Host's stream budget.
    var contentJob: Job? = null
    val contentWatcher = launch {
      contentWatching.collect { enabled ->
        contentJob?.cancel()
        contentJob = null
        if (!enabled) {
          followStates.values.toList().forEach(::stopFollowState)
          return@collect
        }
        contentJob = launch {
          val workspace = launch { runWorkspaceLoop { closed } }
          try {
            synchronized(watched) { watched.toList() }.forEach(::startFollow)
            awaitCancellation()
          } finally {
            workspace.cancel()
          }
        }
      }
    }

    awaitClose {
      closed = true
      if (questionCoordinatorDelegate.isInitialized()) questionCoordinator.presentation(null, false)
      if (questionCoordinatorDelegate.isInitialized() && questionCoordinator.hasPendingSettlements()) {
        ioScope.launch {
          try { kotlinx.coroutines.withTimeoutOrNull(2_000) { questionCoordinator.awaitSettlements() } }
          finally { eventsJob.cancel() }
        }
      } else eventsJob.cancel()
      statusJob.cancel()
      contentWatcher.cancel()
      contentJob?.cancel()
      followStates.values.toList().forEach(::stopFollowState)
      pump.cancel()
      channel.close()
      if (eventChannel === channel) eventChannel = null
    }
  }

  /**
   * `$events` 是状态的主实时源，但不是内容和状态的唯一来源：它断开时只标记 degraded、
   * 按退避重连，**不关闭整条 Flow**。内容仍然由各会话自己的 follow 流提供；运行状态由
   * [runStatusFallback] 用 `session/list` 兜底。
   */
  private suspend fun runEventsLoop(push: (ServerEvent) -> Unit, onOpen: () -> Unit, isClosed: () -> Boolean) {
    var backoff = EVENTS_RETRY_MIN_MILLIS
    while (!isClosed()) {
      val ended = CompletableDeferred<Throwable?>()
      val readySignal = CompletableDeferred<Unit>()
      var socket: WebSocket? = null
      var wasReady = false
      try {
        socket = openMux("\$events", noArgs(), onItem = { frame ->
          eventsLastFrameAt = System.currentTimeMillis()
          when (frame.str("type")) {
            "ready" -> {
              val nextClientId = frame.str("clientId")
              for ((deliveryId, delivery) in questionEventClients.toMap()) {
                if (delivery.clientId != nextClientId) {
                  if (questionCoordinatorDelegate.isInitialized()) questionCoordinator.cancelled(deliveryId)
                  questionEventClients.remove(deliveryId, delivery)
                }
              }
              eventsClientId = nextClientId
              eventsReady = true
              wasReady = true
              readySignal.complete(Unit)
              eventsError = null
              backoff = EVENTS_RETRY_MIN_MILLIS
              flushFollowBuffers(push)
              reconcileAfterReady(push)
              runCatching { onOpen() }
              Diagnostics.info("DshApi", "\$events ready · client=${eventsClientId?.take(8)}")
            }
            "emit" -> handleEmit(frame, push)
            "waterfall" -> handleWaterfall(frame, push)
            "cancel" -> handleCancelled(frame, push)
            else -> Unit
          }
        }, onClosed = { cause ->
          eventsReady = false
          ended.complete(cause)
        })
        // TCP/TLS 建连成功不等于逻辑事件流可用。对端若一直不发 ready，必须按失败重连，
        // 否则 connected=true 但控制事件永久停摆，且没有后续快照能补上这段空窗。
        val readyOrClosed = kotlinx.coroutines.withTimeoutOrNull(eventsReadyTimeoutMillis) {
          kotlinx.coroutines.selects.select<Boolean> {
            readySignal.onAwait { true }
            ended.onAwait { false }
          }
        }
        if (readyOrClosed != true && !ended.isCompleted) {
          ended.complete(IOException("\$events ready 超时，重新建立事件流"))
          socket?.cancel()
        }
        val cause = try { ended.await() } finally { socket?.cancel() }
        if (isClosed()) break
        eventsError = cause?.message?.takeIf { it.isNotBlank() } ?: "事件流已关闭"
        eventsReconnects++
        if (wasReady) connectionEvent(false, eventsError)?.let(push)
        Diagnostics.warn("DshApi", "\$events 断开，准备重连：${eventsError}")
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        eventsReady = false
        eventsError = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        eventsReconnects++
        if (wasReady) connectionEvent(false, eventsError)?.let(push)
        socket?.cancel()
        Diagnostics.warn("DshApi", "\$events 建立失败", error)
      }
      delay(backoff)
      backoff = (backoff * 2).coerceAtMost(EVENTS_RETRY_MAX_MILLIS)
    }
    eventsReady = false
  }

  /**
   * 工作区清单与归档集合。参数形状按代际不同（0.2.1 只接受 `{args:{}}`，更新代际要
   * `{args:{request:{}}}`），一元基线与这条实时流共用同一个探测结果，避免一边成功一边失败。
   */
  private suspend fun runWorkspaceLoop(isClosed: () -> Boolean) {
    var backoff = WORKSPACE_RETRY_MIN_MILLIS
    while (!isClosed()) {
      val ended = CompletableDeferred<Throwable?>()
      var socket: WebSocket? = null
      var argumentsInvalid = false
      try {
        socket = openMux("workspace/follow", workspacePayload(), onItem = { frame -> applyWorkspaceFrame(frame) },
          onClosed = { cause ->
            if (cause != null) argumentsInvalid = isGatewayArgumentsInvalid(cause)
            ended.complete(cause)
          })
        val cause = try { ended.await() } finally { socket?.cancel() }
        if (isClosed()) break
        if (argumentsInvalid || isGatewayArgumentsInvalid(cause)) {
          flipWorkspaceStyle()
          backoff = WORKSPACE_RETRY_MIN_MILLIS
          Diagnostics.info("DshApi", "workspace/follow 参数代际切换为 $workspaceArgsStyle")
          continue
        }
        if (cause != null) Diagnostics.warn("DshApi", "workspace/follow 断开", cause)
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        socket?.cancel()
        if (isGatewayArgumentsInvalid(error)) {
          flipWorkspaceStyle()
          backoff = WORKSPACE_RETRY_MIN_MILLIS
          continue
        }
        Diagnostics.warn("DshApi", "workspace/follow 建立失败", error)
      }
      delay(backoff)
      backoff = (backoff * 2).coerceAtMost(WORKSPACE_RETRY_MAX_MILLIS)
    }
  }

  /**
   * `$events` 不可用时的状态兜底：低频读 `session/list.running`，只对变化发事件。
   * 这样审批/问答会显示“实时通道断开”，但“某会话正在跑”不会跟着一起消失。
   */
  private suspend fun runStatusFallback(push: (ServerEvent) -> Unit, isClosed: () -> Boolean) {
    var previous: Set<String>? = null
    while (!isClosed()) {
      delay(STATUS_FALLBACK_MILLIS)
      if (eventsReady) { previous = null; continue }
      val running = try { listing().running } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        Diagnostics.warn("DshApi", "状态兜底读取失败", error)
        continue
      }
      val before = previous
      if (before != null) {
        for (id in running - before) emitStatus(id, true, push)
        for (id in before - running) emitStatus(id, false, push)
      }
      previous = running
    }
  }

  /**
   * A transport-level signal the overview can use without closing the whole event flow.
   * Only Remote connections emit it: direct/SSH event streams are shared with existing callers
   * that treat every event as a session projection.
   */
  private fun connectionEvent(connected: Boolean, error: String?): ServerEvent? {
    val now = System.currentTimeMillis()
    return ServerEvent(id = "dsh:events:$connected:$now", directory = "", type = "server.connection",
      properties = JSONObject().put("connected", connected).apply { if (error != null) put("error", error) }, created = now)
  }

  private fun emitStatus(id: String, running: Boolean, push: (ServerEvent) -> Unit) {
    if (executions.status(id, running)) clearKnownOutcome(id)
    val now = System.currentTimeMillis()
    push(ServerEvent(id = "dsh:status:$id:$running:$now", directory = known[id]?.directory.orEmpty(),
      type = "session.status", properties = JSONObject()
        .put("sessionID", id)
        .put("status", JSONObject().put("type", if (running) "busy" else "idle")), created = now))
    if (!running) ioScope.launch { publishSettledOutcome(id, push) }
  }

  /**
   * `$events` 只报告“不再运行”，不报告这一轮是完成、失败还是中断。follow 在线时 `turn/end`
   * 已经带了结局；不在线时读日志尾部的最后一条 `turn/end`，翻译成带结局的执行事件。
   * 读不到、或这一轮已经翻译过，就什么都不补：没有见过结局不能猜成成功。
   */
  private suspend fun publishSettledOutcome(id: String, push: (ServerEvent) -> Unit) {
    if (id.isBlank() || executions.running(id)) return
    val epoch = executions.epoch(id)
    if (outcomeReads.putIfAbsent(id, epoch) != null) return
    try {
      if (executions.outcome(id) != null) return
      val session = known[id] ?: return
      val event = latestTurnEnd(session) ?: return
      if (executions.epoch(id) != epoch || executions.running(id) || executions.outcome(id) != null) return
      translate(session, event).filter { it.type.startsWith("session.execution.") }.forEach(push)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      Diagnostics.warn("DshApi", "读取会话结局失败", error)
    } finally {
      outcomeReads.remove(id, epoch)
    }
  }

  /**
   * 日志尾部最近的一条 `turn/end`。`session/page` 从日志末尾往前翻，不带 `turnWindow` 时按消息计数，
   * 一轮里的事件远多于两条消息，所以连续翻几页直到看见 `turn/end`。没有它就返回 null。
   *
   * 切点必须是**真实存在**的 seq：Host 的 `session/page` 会校验 `throughSeq <= 日志末尾`，
   * 超出就报 `gateway/bad-request`（不是"裁到末尾"），所以不能拿一个"足够大的上界"去探。
   * 还没读过这条日志时先取一次 follow 快照把 `cursor` 学来；学不到就放弃本次补读——宁可不补，
   * 也不能猜成成功。
   */
  private suspend fun latestTurnEnd(session: Session): JSONObject? {
    val through = knownCursor(session, refresh = true) ?: return null
    var before: Long? = null
    repeat(OUTCOME_PAGE_ATTEMPTS) {
      val request = JSONObject().put("address", address(session.id)).put("throughSeq", through)
        .put("maxMessages", OUTCOME_PAGE_MESSAGES)
      if (before != null) request.put("beforeSeq", before)
      val page = callObject("session/page", requestArgs(request))
      val records = page.arr("records").objects()
      val lifecycle = records.map { it.obj("event") }.lastOrNull { it.str("type") in setOf("turn/start", "turn/end") }
      if (lifecycle?.str("type") == "turn/start") return null
      if (lifecycle?.str("type") == "turn/end") return lifecycle
      if (!page.optBoolean("hasMore") || records.isEmpty()) return null
      before = records.mapNotNull { it.obj("event").optDouble("seq", 0.0).toLong().takeIf { seq -> seq > 0 } }.minOrNull()
        ?: return null
    }
    return null
  }

  /**
   * 一条会话当前已知的日志切点。本机读过就用记住的那个，否则开一条**一次性** follow 只取快照的
   * `cursor` 再立刻关掉（与 `job/list` 同一种用法，不占长期流预算）；取不到就返回 null。
   */
  private suspend fun knownCursor(session: Session, refresh: Boolean = false): Long? {
    val remembered = maxOf(cursors[session.id] ?: 0L, lastSeq(session.id))
    if (!refresh && remembered > 0) return remembered
    val snapshot = try {
      oneShotStreams.withPermit {
        fetchFirstFrame("session/follow", requestArgs(JSONObject()
          .put("address", address(session.id))
          .put("maxMessages", OUTCOME_PAGE_MESSAGES)))
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      Diagnostics.warn("DshApi", "读取会话日志切点失败，跳过结局补读", error)
      return null
    }
    if (snapshot.str("type") != "snapshot") return null
    return snapshot.optDouble("cursor", -1.0).toLong().takeIf { it >= 0 }?.also { cursors[session.id] = it }
  }

  private fun flushFollowBuffers(push: (ServerEvent) -> Unit) {
    for (state in followStates.values) {
      val drained = synchronized(state.pending) {
        if (state.pending.isEmpty()) emptyList() else state.pending.toList().also { state.pending.clear() }
      }
      drained.forEach(push)
    }
  }

  private fun workspacePayload(): RpcPayload =
    if (workspaceArgsStyle == WorkspaceArgsStyle.REQUEST) requestArgs(JSONObject()) else noArgs()

  private fun flipWorkspaceStyle() {
    workspaceArgsStyle = if (workspaceArgsStyle == WorkspaceArgsStyle.REQUEST) WorkspaceArgsStyle.EMPTY else WorkspaceArgsStyle.REQUEST
  }

  private fun isGatewayArgumentsInvalid(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.any { candidate ->
      (candidate as? RemoteGatewayException)?.code == "gateway/arguments-invalid" ||
        (candidate as? ApiException)?.code == "gateway/arguments-invalid" ||
        candidate.message?.contains("gateway/arguments-invalid") == true
    }

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
          .put("blank", session.blank)
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
          if (activeSessionId == id) activeSessionId = null
          synchronized(watched) { watched.remove(id) }
          stopFollow(id)
          push(ServerEvent(id = "dsh:removed:$id", directory = "", type = "session.deleted",
            properties = JSONObject().put("sessionID", id), created = System.currentTimeMillis()))
        }
      }
      "api-session/status" -> {
        // running 状态折算成 OpenCode 形状的 session.status，未关注的会话也能实时收尾。
        val id = args.optString(0).takeUnless { it == "null" }.orEmpty()
        if (id.isBlank()) return
        val running = args.optBoolean(1, false)
        emitStatus(id, running, push)
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
      // 目标续跑状态（`armed`/`disarmed`）只存在于进程内，投影刻意不含它，远程也没有 `goals/get`。
      // Full mutation responses also carry it; events must match the exact goal id AND revision.
      "goal/activation-changed" -> {
        val payload = args.optJSONObject(0) ?: return
        val id = payload.str("sessionId").takeIf { it.isNotBlank() } ?: return
        val current = payload.optJSONObject("goal")
        if (current == null) synchronized(goalCacheLock) {
          val previous = latestGoalViews[id]
          val activation = goalActivation[id]
          val clearedId = previous?.id ?: activation?.id
          val clearedRevision = previous?.revision ?: activation?.revision
          clearGoalCache(id)
          if (clearedId != null && clearedRevision != null) clearedGoalRefs[id] = ClearedGoal(clearedId, clearedRevision)
        }
        else {
          val goalId = current.str("id").takeIf { it.isNotBlank() } ?: return
          val revision = current.optInt("revision", 0).takeIf { it > 0 } ?: return
          val activation = current.str("activation").takeIf { it == "armed" || it == "disarmed" } ?: return
          synchronized(goalCacheLock) {
            val latest = latestGoalViews[id]
            val previous = goalActivation[id]
            // Delayed events cannot overwrite a view/event for a newer revision of this goal.
            if ((latest?.id != goalId || latest.revision <= revision) &&
              (previous?.id != goalId || previous.revision <= revision)) {
              advanceGoalCacheEpoch(id)
              goalActivation[id] = GoalActivation(goalId, revision, activation)
              if (latest?.id == goalId && latest.revision == revision)
                latestGoalViews[id] = latest.copy(activation = activation)
            }
          }
        }
        push(ServerEvent(id = "dsh:goal:$id:${current?.str("id").orEmpty()}:${current?.optInt("revision", 0) ?: 0}",
          directory = known[id]?.directory.orEmpty(), type = "session.goal.changed",
          properties = JSONObject().put("sessionID", id), created = System.currentTimeMillis()))
      }
    }
  }

  private fun handleWaterfall(frame: JSONObject, push: (ServerEvent) -> Unit) {
    if (closing.get()) return
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
        if (eventId.isBlank() || sessionId.isBlank()) return
        val clientId = eventsClientId ?: return
        val deliveryId = questionDeliveryId(clientId, eventId)
        if (questionEventClients.size >= 1024 && !questionEventClients.containsKey(deliveryId))
          throw IOException("提问事件超过客户端预算")
        questionEventClients[deliveryId] = QuestionDelivery(clientId, eventId, sessionId,
          request.optJSONObject("wait")?.optString("callId")?.takeIf { it.isNotBlank() })
        questionCoordinator.receive(sessionId, deliveryId, request)
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
    val stale = pendingApprovals.keys.toSet() + questionEventClients.values.map { it.wireEventId }.toSet()
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
    val deliveryId = eventsClientId?.let { questionDeliveryId(it, eventId) }
    val delivery = deliveryId?.let { questionEventClients.remove(it) }
    if (deliveryId != null && questionCoordinatorDelegate.isInitialized()) questionCoordinator.cancelled(deliveryId)
    if (delivery != null && !closed) ioScope.launch {
      try { reconcileCancelledQuestion(delivery) }
      catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { Diagnostics.warn("DshApi", "提问取消后的投影对账失败", error) }
    }
    val now = System.currentTimeMillis()
    val permission = pendingApprovals.remove(eventId)
    if (permission != null) {
      push(ServerEvent(id = "dsh:approval-settled:$eventId", directory = permission.directory, type = "permission.replied",
        properties = JSONObject().put("sessionID", permission.sessionId).put("id", eventId), created = now))
      return
    }

  }

  /** 回一条瀑布：`{clientId, eventId, outcome}`，结果值按事件类型自己决定。 */
  private suspend fun settle(eventId: String, value: Any?) {
    if (trySettle(eventId, value)) return
    throw ApiException(409, "事件流尚未连接，请稍后重试")
  }

  /**
   * 试回一条瀑布，事件流未连接或这一条已经关闭时返回 false，交给调用方选备用通道。
   * 审批没有备用通道，问答有（`userQuestions/answer`）。
   */
  private suspend fun trySettle(eventId: String, value: Any?): Boolean {
    val clientId = eventsClientId ?: return false
    val outcome = JSONObject().put("kind", "result")
    if (value != null) outcome.put("value", value)
    val args = JSONObject().put("clientId", clientId).put("eventId", eventId).put("outcome", outcome)
    return try {
      call("\$events/result", envelope(args))
      true
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      Diagnostics.info("DshApi", "瀑布结算失败，尝试备用通道：${error.message.orEmpty().take(120)}")
      false
    }
  }

  private suspend fun settleQuestionOutcome(eventId: String, outcome: JSONObject) {
    val delivery = questionEventClients[eventId] ?: throw ApiException(409, "提问事件通道已变化，请刷新后确认")
    try {
      call("\$events/result", envelope(JSONObject().put("clientId", delivery.clientId)
        .put("eventId", delivery.wireEventId).put("outcome", outcome)))
    } finally {
      questionEventClients.remove(eventId, delivery)
    }
  }

  private suspend fun refreshQuestionProjection(sessionId: String) {
    val result = call("session/projections", requestArgs(JSONObject().put("sessionId", sessionId))) as? JSONObject
      ?: throw IOException("提问投影不是对象")
    questionCoordinator.reconcile(sessionId, result.obj("values"))
  }

  private fun refreshQuestionsAfterDurableEvent(sessionId: String, event: JSONObject) {
    if (!questionCoordinatorDelegate.isInitialized() || closing.get()) return
    if (event.str("type") !in setOf("tool/result", "user/message", "turn/end", "session/inbox")) return
    synchronized(questionRefreshes) {
      if (questionRefreshes[sessionId]?.isActive == true) {
        dirtyQuestionRefreshes += sessionId
        return
      }
      val job = ioScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        var attempts = 0
        do {
          dirtyQuestionRefreshes.remove(sessionId)
          try { refreshQuestionProjection(sessionId) }
          catch (cancel: CancellationException) { throw cancel }
          catch (error: Exception) { Diagnostics.warn("DshApi", "提问耐久事件对账失败", error) }
          attempts++
        } while (!closed && !closing.get() && dirtyQuestionRefreshes.contains(sessionId) && attempts < 8)
      }
      questionRefreshes[sessionId] = job
      job.invokeOnCompletion {
        val again = synchronized(questionRefreshes) {
          val removed = questionRefreshes.remove(sessionId, job)
          removed && !closed && !closing.get() && dirtyQuestionRefreshes.remove(sessionId)
        }
        if (again) refreshQuestionsAfterDurableEvent(sessionId, JSONObject().put("type", "tool/result"))
      }
      job.start()
    }
  }

  private suspend fun reconcileCancelledQuestion(delivery: QuestionDelivery) {
    // Gateway cancellation precedes the durable pending tool result. Retry READS only while that
    // exact call is still open; never replay a settlement/answer mutation.
    for (attempt in 0..4) {
      if (closed || closing.get()) return
      if (attempt > 0) delay(100L shl (attempt - 1))
      refreshQuestionProjection(delivery.sessionId)
      val state = delivery.callId?.let { questionCoordinator.snapshotForCall(delivery.sessionId, it) }
      if (state?.state != "open" || state.eventId != null) return
    }
  }

  private fun publishQuestionSnapshots(snapshots: List<DshQuestionRegistry.Snapshot>) {
    val next = snapshots.map { snapshot -> snapshot.toQuestionForm(known[snapshot.sessionId]?.directory.orEmpty()).copy(
      presentation = QuestionPresentation(snapshot.remainingMs?.let { remaining ->
        (remaining.coerceAtMost(Long.MAX_VALUE - 999) + 999) / 1000 * 1000
      }, snapshot.countdownRunning, snapshot.held, snapshot.state == "continued")) }.associateBy { it.id }
    val previous = pendingQuestions.toMap()
    pendingQuestions.keys.removeAll { it !in next }
    pendingQuestions.putAll(next)
    for ((id, request) in previous) if (id !in next) eventChannel?.trySend(ServerEvent(
      id = "dsh:question-removed:${nextLiveEventId()}", directory = request.directory, type = "form.cancelled",
      properties = JSONObject().put("sessionID", request.sessionId).put("id", id), created = clock()))
    for ((id, request) in next) if (request != previous[id]) {
      val form = JSONObject().put("id", id).put("sessionID", request.sessionId)
        .put("fields", JSONArray().apply { request.questions.forEach { put(JSONObject(it.field)) } })
      request.presentation?.let { state -> form.put("questionPresentation", JSONObject()
        .putOpt("remainingMs", state.remainingMs).put("countdownRunning", state.countdownRunning)
        .put("held", state.held).put("continued", state.continued)) }
      eventChannel?.trySend(ServerEvent(id = "dsh:question-change:${nextLiveEventId()}", directory = request.directory,
        type = if (id in previous) "form.updated" else "form.created", properties = JSONObject().put("form", form), created = clock()))
    }
  }

  override fun questionPresentation(sessionId: String?, foreground: Boolean) {
    questionSession = sessionId
    questionForeground = foreground
    if (!isClosed) questionCoordinator.presentation(sessionId, foreground)
  }
  override fun questionFocus(id: String, focused: Boolean) = questionCoordinator.focus(id, focused)
  override fun questionEngage(id: String) = questionCoordinator.engage(id)
  override fun questionTakeTime(id: String) = questionCoordinator.takeTime(id)

  /** 拒绝一条瀑布：`{kind:"rejected", error:{name, message}}`。 */
  private suspend fun reject(eventId: String, message: String) {
    val clientId = eventsClientId ?: throw ApiException(409, "事件流尚未连接，请稍后重试")
    val outcome = JSONObject()
      .put("kind", "rejected")
      .put("error", JSONObject().put("name", "cancelled").put("message", message))
    val args = JSONObject().put("clientId", clientId).put("eventId", eventId).put("outcome", outcome)
    call("\$events/result", envelope(args))
  }

  /**
   * 一个会话一条 follow。首个调用者创建 [FollowState] 并异步打开；后来的消息页/事件流共用它。
   * 打开中的状态也会被复用，避免消息页和 `$events` 同时各开一条。
   */
  private fun followFor(session: Session, fresh: Boolean = false): FollowState = synchronized(followOpenLock) {
    val existing = followStates[session.id]
    // A live follow whose snapshot already covers every durable event is safe to reuse even when
    // the caller wanted a "fresh" page: there is nothing new to fetch. A follow that received
    // events after its snapshot must be replaced for a cold initial page, otherwise those events
    // would be missing from the fetched window.
    val snapshotCursor = snapshotStats[session.id]?.cursor ?: -1L
    val snapshotStillCurrent = existing?.snapshot?.isCompleted == true && snapshotCursor >= 0 &&
      lastSeq(session.id) <= snapshotCursor
    val reusable = existing != null && !existing.closed &&
      (existing.opening || existing.socket != null || existing.snapshot.isCompleted) &&
      (!fresh || !existing.snapshot.isCompleted || snapshotStillCurrent ||
        System.currentTimeMillis() - existing.snapshotAt <= SNAPSHOT_REUSE_MILLIS)
    if (reusable) return existing
    existing?.let(::stopFollowState)
    val state = FollowState(session.id)
    state.opening = true
    state.frameJob = ioScope.launch {
      for (frame in state.frames) processFollowFrame(session, state, frame)
    }
    followStates[session.id] = state
    ioScope.launch { openFollow(session, state) }
    state
  }

  private suspend fun openFollow(session: Session, state: FollowState) {
    var retriedLimit = false
    while (true) {
      try {
        val payload = requestArgs(JSONObject()
          .put("address", address(session.id))
          .put("assistantStream", true)
          .put("maxMessages", SNAPSHOT_MAX_MESSAGES)
          .put("turnWindow", JSONObject().put("minMessages", SNAPSHOT_MIN_MESSAGES).put("minTurns", 2)))
        val socket = openMux("session/follow", payload,
          onItem = { frame -> onFollowFrame(session, state, frame) },
          onClosed = { cause -> onFollowClosed(session, state, cause) })
        state.opening = false
        state.socket = socket
        if (state.closed) socket.cancel()
        return
      } catch (cancel: CancellationException) {
        state.opening = false
        if (!state.snapshot.isCompleted) state.snapshot.completeExceptionally(cancel)
        throw cancel
      } catch (limit: RemoteStreamLimitException) {
        // The local gateway is full. Do not cancel the in-flight open RPC (RemoteRpc treats that as
        // an unknown-delivery failure and closes the whole relay); evict a different live follow,
        // which releases its slot synchronously via StreamSocket.closeNow(), then retry once.
        if (!retriedLimit && !state.closed && releaseFollowForRetry(session.id)) {
          retriedLimit = true
          delay(STREAM_LIMIT_RETRY_DELAY_MILLIS)
          if (!state.closed) continue
        }
        failFollow(session, state, limit)
        return
      } catch (error: Exception) {
        failFollow(session, state, error)
        return
      }
    }
  }

  private fun failFollow(session: Session, state: FollowState, error: Exception) {
    state.opening = false
    state.error = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    if (!state.snapshot.isCompleted) state.snapshot.completeExceptionally(error)
    followStates.remove(session.id, state)
    state.frames.close()
    state.frameJob?.cancel()
    Diagnostics.warn("DshApi", "会话 follow 未建立：${state.error}")
  }

  /**
   * Drop one non-active live follow to make room for [exclude]. Only states with a socket are
   * eligible: an in-flight open owns no local slot yet and cannot be cancelled safely.
   */
  private fun releaseFollowForRetry(exclude: String): Boolean {
    val victim = synchronized(watched) {
      watched.firstOrNull { id ->
        id != activeSessionId && id != exclude && followStates[id]?.socket != null
      }?.also { watched.remove(it) }
    } ?: return false
    Diagnostics.warn("DshApi", "流预算已满，释放旧的会话 follow：${victim.take(24)}")
    stopFollow(victim)
    return true
  }

  private fun onFollowFrame(session: Session, state: FollowState, frame: JSONObject) {
    if (!state.closed) state.frames.trySend(frame)
  }

  private suspend fun processFollowFrame(session: Session, state: FollowState, frame: JSONObject) {
    if (state.closed) return
    if (frame.str("type") == "lagoon/follow-closed") {
      finishFollowClosed(session, state, state.closeCause)
      return
    }
    state.lastFrameAt = System.currentTimeMillis()
    val type = frame.str("type")
    if (type != "snapshot" && !state.snapshot.isCompleted) {
      // 协议要求首帧必须是 snapshot；把它当空日志会得到永远空白的会话。
      val error = ApiException(502, "会话流首帧不是快照（${type.ifBlank { "未知帧" }}）", "stream/not-snapshot")
      state.error = error.message
      state.snapshot.completeExceptionally(error)
      stopFollowState(state)
      return
    }
    when (type) {
      "snapshot" -> {
        state.error = null
        state.snapshotAt = System.currentTimeMillis()
        frame.optDouble("cursor", 0.0).toLong().takeIf { it >= 0 }?.let { cursors[session.id] = it }
        frame.arr("records").objects().lastOrNull { it.obj("event").str("type") == "turn/start" }
          ?.obj("event")?.obj("data")?.optLong("turn")?.takeIf { it > 0 }?.let { durableTurns[session.id] = it }
        val baseline = lastSeq(session.id)
        val projection = ptcProjection(session.id)
        synchronized(projection) {
          val records = frame.arr("records").objects()
          projection.indexRoots(records)
          // Seed already-covered ancestors before newer children arrive, even without a message read.
          records.filter { baseline == 0L || it.obj("event").optLong("seq") <= baseline }
            .forEach { projection.process(it) }
        }
        if (!state.snapshot.isCompleted) state.snapshot.complete(frame)
        // 重连/重同步：只补投时间线还没有的耐久记录，避免整页重复。
        if (baseline > 0) {
          for (record in frame.arr("records").objects()) {
            val event = record.obj("event")
            val seq = event.optDouble("seq", 0.0).toLong()
            if (seq <= baseline) continue
            translate(session, event).forEach { emitFollow(state, it) }
            advanceSeq(session.id, seq)
          }
        }
        val records = frame.arr("records").objects().size
        // 在生成中途打开会话时，Host 只把进行中的 attempt 放进**开帧**的 `assistantStream`，
        // 之后不会再补一个 `start`。不从这里登记，后续 `chunk` 会因找不到 attempt 被整段丢弃，
        // 表现为"手机打开会话看不到正在输出的文本"。
        seedLiveAttempt(session, frame.obj("assistantStream"), state)
        snapshotStats[session.id] = RemoteSnapshotStats(session.id, records,
          snapshotStats[session.id]?.messages ?: 0, frame.optDouble("cursor", -1.0).toLong(),
          frame.optBoolean("hasMore"), state.snapshotAt)
      }
      "event" -> {
        val event = frame.obj("event")
        val seq = event.optDouble("seq", 0.0).toLong()
        if (seq <= 0 || seq > lastSeq(session.id)) {
          val previous = lastSeq(session.id)
          if (seq > previous + 1 && previous > 0) {
            val missing = gapRepairWindow(previous, seq)
            if (missing == null || !repairGap(session, state, missing)) {
              state.snapshotAt = 0L
              emitFollow(state, ServerEvent("dsh:${session.id}:gap:$seq", session.directory, "session.transcript.invalidate",
                JSONObject().put("sessionID", session.id), System.currentTimeMillis()))
            }
          }
          translate(session, event).forEach { emitFollow(state, it) }
          refreshQuestionsAfterDurableEvent(session.id, event)
          if (seq > 0) advanceSeq(session.id, seq)
        }
      }
      "assistant-stream" -> translateLive(session, frame.obj("frame")) { emitFollow(state, it) }
      else -> Unit
    }
  }

  private fun onFollowClosed(session: Session, state: FollowState, cause: Throwable?) {
    if (state.closed) return
    state.closeCause = cause
    // Drain already received frames before handling EOF, including the last tool result/turn end.
    state.frames.trySend(JSONObject().put("type", "lagoon/follow-closed"))
  }

  private fun finishFollowClosed(session: Session, state: FollowState, cause: Throwable?) {
    state.opening = false
    state.socket = null
    if (!state.snapshot.isCompleted) {
      state.snapshot.completeExceptionally(cause ?: IOException("会话流已关闭"))
    }
    if (state.closed) return
    state.closed = true
    state.frames.close()
    state.frameJob?.cancel()
    state.error = cause?.message?.takeIf { it.isNotBlank() } ?: "会话流已关闭"
    followStates.remove(session.id, state)
    val watchedNow = synchronized(watched) { session.id in watched }
    // 只有仍在观察集合里的会话才重连；否则首页/历史会话的每条 follow 都会在断开后被复活，
    // 重新占满 RemoteGateway 的流预算。
    if (!watchedNow) return
    Diagnostics.warn("DshApi", "会话 follow 断开，准备重连：${state.error}")
    val reconnects = state.reconnects + 1
    val wait = (FOLLOW_RETRY_MIN_MILLIS shl (reconnects - 1).coerceAtMost(5)).coerceAtMost(FOLLOW_RETRY_MAX_MILLIS)
    ioScope.launch {
      delay(wait)
      if (closed) return@launch
      if (!synchronized(watched) { session.id in watched }) return@launch
      val next = followFor(session)
      next.reconnects = reconnects
    }
  }

  private fun emitFollow(state: FollowState, event: ServerEvent) {
    val channel = eventChannel
    if (channel != null) {
      channel.trySend(event)
      return
    }
    // 事件流还没建立：先放进有界缓冲，等 events() 挂上后补齐；不丢开场几秒。
    synchronized(state.pending) {
      if (state.pending.size >= FOLLOW_PENDING_LIMIT) state.pending.removeFirst()
      state.pending.addLast(event)
    }
  }

  /**
   * 补洞窗口：`previous` 之后、`seq` 之前缺失的耐久事件。缺口过大（> [MAX_GAP_REPAIR]）时不逐条补，
   * 交给下一次打开会话的快照整体校正，避免重连风暴里发出超长翻页请求。
   */
  internal fun gapRepairWindow(previous: Long, seq: Long): LongRange? =
    if (previous > 0 && seq > previous + 1 && seq - previous <= MAX_GAP_REPAIR) (previous + 1) until seq else null

  private suspend fun repairGap(session: Session, state: FollowState, missing: LongRange): Boolean {
    return try {
      // beforeSeq is exclusive and pages backwards. The old lower-bound value excluded the gap.
      val request = JSONObject().put("address", address(session.id))
        .put("throughSeq", missing.last).put("beforeSeq", missing.last + 1).put("maxMessages", GAP_REPAIR_PAGE)
      val page = callObject("session/page", requestArgs(request))
      val events = page.arr("records").objects().map { it.obj("event") }
        .filter { it.optLong("seq") in missing }.sortedBy { it.optLong("seq") }
      if (events.map { it.optLong("seq") }.distinct().size.toLong() != missing.last - missing.first + 1) return false
      for (event in events) {
        translate(session, event).forEach { emitFollow(state, it) }
        advanceSeq(session.id, event.optLong("seq"))
      }
      true
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) {
      Diagnostics.warn("DshApi", "会话事件缺口补齐失败", error)
      false
    }
  }

  private fun startFollow(id: String) {
    known[id]?.let { followFor(it) }
  }

  private fun stopFollow(id: String) {
    followStates[id]?.let(::stopFollowState)
    if (id != activeSessionId && !synchronized(watched) { id in watched }) {
      synchronized(ptcProjections) { ptcProjections.remove(id) }
    }
  }

  private fun stopFollowState(state: FollowState) {
    state.closed = true
    followStates.remove(state.sessionId, state)
    runCatching { state.socket?.cancel() }
    synchronized(state.pending) { state.pending.clear() }
    state.frames.close()
    state.frameJob?.cancel()
    if (!state.snapshot.isCompleted) state.snapshot.completeExceptionally(IOException("会话流已关闭"))
  }

  /**
   * 记录需要实时 follow 的会话，并把长期 follow 严格限制在 [WATCH_LIMIT] 条。
   *
   * 旧实现先裁到 12 条，却对传入列表里的**每个**会话调用 [startFollow]；首页有 20 个会话时
   * 实际会开 14 条 follow，正好把 RemoteGateway 的 16 条预算占满。这里只对保留下来的集合
   * 启动 follow，并保证淘汰顺序与“最近活跃”一致；[activeSessionId] 不参与淘汰。
   */
  private fun rememberWatched(sessions: List<Session>) {
    val retained: List<String>
    val evicted = mutableListOf<String>()
    synchronized(watched) {
      // 输入通常按“最新在前”排序；按 updated 从旧到新加入，LinkedHashSet 的尾部才是最近活跃。
      for (session in sessions.sortedBy { it.updated }) {
        watched.remove(session.id)
        watched.add(session.id)
      }
      while (watched.size > WATCH_LIMIT) {
        val oldest = watched.firstOrNull { it != activeSessionId } ?: break
        watched.remove(oldest)
        evicted += oldest
      }
      retained = watched.toList()
    }
    // 先停掉被淘汰的，释放槽位后再启动新流。
    evicted.forEach(::stopFollow)
    if (eventChannel == null || !contentWatching.value) return
    retained.forEach(::startFollow)
  }

  /** 连接医生用：每条 follow 的最近状态，只有会话 id 与计数，没有消息正文。 */
  internal fun followDiagnostics(): List<RemoteFollowStats> = followStates.values.map { state ->
    RemoteFollowStats(state.sessionId, open = !state.closed && (state.socket != null || state.opening),
      lastSeq = lastSeq(state.sessionId), lastFrameAt = state.lastFrameAt,
      reconnects = state.reconnects, error = state.error)
  }

  override fun remoteDoctor(appVersion: String): RemoteDoctor? {
    val backend = remoteBackend ?: return null
    val status = backend.connectionStatus()
    return RemoteDoctor(
      hub = backend.hub,
      account = backend.account,
      hostName = status.hostName,
      hostId = backend.hostId,
      appVersion = appVersion,
      status = status,
      credentials = backend.credentials(),
      snapshots = snapshotStats.values.sortedByDescending { it.at }.take(8),
      follows = followDiagnostics(),
      events = RemoteEventStats(eventsReady, eventsClientId, readyGeneration.get(), eventsLastFrameAt,
        eventsReconnects, eventsError),
      streams = backend.streamStats() ?: RemoteStreamStats()
    )
  }

  private class FollowState(val sessionId: String) {
    val snapshot = CompletableDeferred<JSONObject>()
    val pending = ArrayDeque<ServerEvent>()
    val frames = Channel<JSONObject>(Channel.UNLIMITED)
    var frameJob: Job? = null
    @Volatile var closeCause: Throwable? = null
    @Volatile var socket: WebSocket? = null
    @Volatile var opening = false
    @Volatile var closed = false
    @Volatile var snapshotAt = 0L
    @Volatile var lastFrameAt = 0L
    @Volatile var reconnects = 0
    @Volatile var error: String? = null
  }

  // ---------------------------------------------------------------- 记录折算

  /**
   * 把 DSH 的事件日志折算成 OpenCode 形状的消息：一条 `(turn, step)` 就是一条 assistant 消息，
   * `user/message` 是一条用户消息。工具调用的输入来自 `tool/call`，输出来自 `tool/result`。
   *
   * 每个 assistant 消息带上 `turnId`：DSH 的 `turn/start` 是真正的轮次边界，但 goal 轮、自动继续、
   * 压缩检查点这些轮次没有可显示的用户提问；只按 user 消息切轮会让它们和上一轮合并。
   * 注入类 turn root（goal / goal complete / compaction checkpoint）折成一行 notice，既不展示
   * 模型侧原文，也不丢失轮次起点。
   */
  internal suspend fun recordsToMessages(session: Session, records: List<JSONObject>): List<Message> {
    // Durable seq is authoritative even if a reconstructed/stitched window arrives out of order.
    val orderedRecords = records.withIndex().sortedWith(compareBy<IndexedValue<JSONObject>> {
      it.value.obj("event").optLong("seq").takeIf { seq -> seq > 0 } ?: Long.MAX_VALUE
    }.thenBy { it.index }).map { it.value }
    data class ToolEntry(val id: String, val name: String, val input: Any, var state: JSONObject?)
    data class Step(val id: String, val turn: Long?, var started: Long, var order: Int,
      val blocks: MutableList<JSONObject>, val tools: MutableMap<String, ToolEntry>, var ended: Long,
      val toolOrder: MutableSet<String> = LinkedHashSet())

    val sessionPtc = ptcProjection(session.id)
    val ptc = synchronized(sessionPtc) { sessionPtc.replayFor(orderedRecords) }
    val ptcParts = LinkedHashMap<String, LinkedHashMap<String, MessagePart>>()
    val steps = LinkedHashMap<String, Step>()
    val inputs = mutableListOf<Triple<Long, Int, Message>>()
    var lifecycleNotices = emptyList<Message>()
    var currentTurn: Long? = null
    var order = 0
    fun stepOf(data: JSONObject): Step {
      val turn = data.optDouble("turn", 0.0).toLong()
      val number = data.optDouble("step", 0.0).toLong()
      val id = "msg_$turn:$number"
      return steps.getOrPut(id) { Step(id, turn, 0, order, mutableListOf(), mutableMapOf(), 0) }
    }

    for (record in orderedRecords) {
      order++
      val event = record.obj("event")
      val type = event.str("type")
      val seq = event.optDouble("seq", 0.0).toLong()
      val time = event.optDouble("time", 0.0).toLong()
      val data = event.obj("data")
      when (type) {
        "turn/start" -> currentTurn = data.optDouble("turn", 0.0).toLong().takeIf { it > 0 }
        "step/start" -> stepOf(data).let { if (it.started == 0L) it.started = time }
        "step/end" -> stepOf(data).ended = time
        "compaction/start", "compaction/end", "command/done" -> {
          translate(session, event).forEach { translated ->
            lifecycleNotices = TranscriptProjection.apply(lifecycleNotices, translated)?.messages ?: lifecycleNotices
          }
        }
        // DSH also writes model-facing context (runtime / time snapshots) as user messages; only the
        // user's own prompts (`source.kind` = user, or no source on older hosts) are timeline rows.
        "user/message" -> {
          val enriched = withAttachmentBlobs(session.id, data)
          dshInputMessage(session.id, seq, time, currentTurn, enriched)?.let { inputs += Triple(time, order, it) }
        }

        "assistant/message" -> {
          val step = stepOf(data)
          if (step.started == 0L) step.started = time
          for (block in data.obj("message").arr("content").objects()) {
            if (block.str("type") == "tool-call") continue
            step.blocks += block
          }
        }
        "tool/call" -> {
          val callId = data.str("callId").takeIf(String::isNotBlank) ?: continue
          val assistantId = nativeToolMessageId(data) ?: continue
          val step = steps.getOrPut(assistantId) {
            Step(assistantId, data.optLong("turn").takeIf { it > 0 }, 0, order, mutableListOf(), mutableMapOf(), 0)
          }
          if (step.started == 0L) step.started = time
          step.tools[callId] = ToolEntry(callId, data.str("name"), parseArguments(data.str("arguments")), null)
          step.toolOrder += callId
        }
        "tool/ptc-dispatch-start", "tool/code-dispatch-start",
        "tool/ptc-dispatch", "tool/code-dispatch" -> {
          val child = ptc.process(type, data, seq, time) ?: continue
          synchronized(sessionPtc) { sessionPtc.process(type, data, seq, time) }
          val root = ptc.indexedRoots()[child.rootCallId] ?: continue
          val step = steps.getOrPut(child.assistantMessageId) {
            Step(child.assistantMessageId, root.turn, time, order, mutableListOf(), mutableMapOf(), 0)
          }
          if (step.started == 0L) step.started = time
          ptcParts.getOrPut(step.id) { LinkedHashMap() }[child.subCallId] = child.state.messagePart()
          step.toolOrder += child.subCallId
        }
        "tool/result" -> {
          val step = stepOf(data)
          if (step.started == 0L) step.started = time
          val message = data.obj("message")
          val callId = message.str("toolCallId").ifBlank { data.str("callId") }
          step.tools[callId]?.state = dshToolState(data)
        }
      }
    }

    // Sort by the event time and use the record position as a tie-breaker: a page can contain a user
    // prompt, an injected goal row and a step at the same millisecond.
    val entries = mutableListOf<Triple<Long, Int, Message>>()
    entries += inputs
    lifecycleNotices.forEach { message -> entries += Triple(message.created, 0, message) }
    for (step in steps.values) {
      val id = step.id
      val content = JSONArray()
      for (block in step.blocks) {
        val kind = block.str("type")
        if (kind != "text" && kind != "reasoning") continue
        content.put(JSONObject().put("type", kind).put("text", block.str("text")))
      }
      for (tool in step.tools.values) {
        val state = tool.state ?: JSONObject().put("status", "running")
        state.put("input", tool.input)
        content.put(JSONObject().put("type", "tool").put("id", tool.id).put("name", tool.name).put("state", state))
      }
      val children = ptcParts[id]?.values.orEmpty()
      if (content.length() == 0 && children.isEmpty()) continue
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
      val message = json.toMessage()
      val nativeParts = message.parts.filter { it.type == "tool" }.associateBy { it.id }
      val orderedTools = step.toolOrder.mapNotNull { toolId -> nativeParts[toolId] ?: ptcParts[id]?.get(toolId) }
      entries += Triple(step.started, step.order,
        message.copy(parts = message.parts.filterNot { it.type == "tool" } + orderedTools, turnId = step.turn?.let { "turn:$it" },
          sequence = orderedRecords.getOrNull(step.order - 1)?.obj("event")?.optLong("seq")))
    }
    entries.sortWith(compareBy<Triple<Long, Int, Message>> { it.first }.thenBy { it.second })
    return entries.map { it.third }
  }

  // ---------------------------------------------------------------- 写操作

  /**
   * 会话侧栏一次读取：未送达的排队输入（`inbox` 投影）、当前任务清单（`todos` 投影）与目标
   * （`goal` 投影）。投影对未加载的会话也可读，因此空闲会话的目标条不会因为 Agent 不在而消失；
   * 进程内的 `activation` 不在投影里，仍由 [goal] 在 Agent 活跃时补齐。
   */
  override suspend fun composerContext(id: String): ComposerContext = withContext(Dispatchers.IO) {
    val goalEpoch = goalCacheEpoch(id)
    val value = call("session/projections", requestArgs(JSONObject().put("sessionId", id))) as? JSONObject
      ?: return@withContext ComposerContext()
    val values = value.obj("values")
    questionCoordinator.reconcile(id, values)
    ComposerContext(inbox = inboxMessages(values), todos = values.todoProjection(),
      goal = if (values.has("goal")) mergeGoalRead(id, values.goalProjection(), startedEpoch = goalEpoch)
         else synchronized(goalCacheLock) { latestGoalViews[id].takeIf { goalEpoch != goalCacheEpoch(id) } })
  }

  override suspend fun inbox(id: String): List<Message> = composerContext(id).inbox

  /** `inbox` 投影（`next-turn` 等待当前轮，`next-step` 下一步）折算成排队消息。 */
  private fun inboxMessages(values: JSONObject): List<Message> {
    val inbox = values.obj("inbox")
    if (inbox.length() == 0) return emptyList()
    val now = System.currentTimeMillis()
    val out = mutableListOf<Message>()
    for ((key, queued) in listOf("next-turn" to true, "next-step" to false)) {
      for (item in inbox.arr(key).objects()) {
        val message = inboxMessage(item, queued, now) ?: continue
        out += message
      }
    }
    return out
  }

  private fun inboxMessage(item: JSONObject, queued: Boolean, created: Long): Message? {
    val id = item.str("id")
    if (id.isBlank()) return null
    val source = item.optJSONObject("source")
    val hasSource = item.has("source") && item.opt("source") != JSONObject.NULL
    val kind = source?.str("kind")?.takeIf(String::isNotBlank)
    if (hasSource && (source == null || kind != "user")) return null
    val text = item.arr("content").objects().mapNotNull { it.str("text").takeIf(String::isNotBlank) }.joinToString("\n")
    val parts = listOfNotNull(text.takeIf(String::isNotBlank)?.let { MessagePart("$id:text", "text", text = it) })
    return Message(id, "user", created, parts, queued = queued, type = "user", sourceKind = kind)
  }

  override suspend fun updateQueue(sessionId: String, itemId: String, action: QueueAction): Boolean {
    val payload = when (action) {
      is QueueAction.Edit -> JSONObject().put("kind", "edit").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", action.text)))
      QueueAction.Remove -> JSONObject().put("kind", "remove")
      QueueAction.Steer -> JSONObject().put("kind", "steer")
    }
    call("session/updateQueue", requestArgs(JSONObject().put("sessionId", sessionId).put("itemId", itemId).put("action", payload)))
    return true
  }

  override suspend fun probeRemote(): RemoteProbe? = remoteBackend?.probe()

  override suspend fun pluginBundles(): List<RemotePluginBundle> {
    val list = call("pluginManager/listBundles") as? JSONArray ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
      val item = list.optJSONObject(i) ?: return@mapNotNull null
      val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      RemotePluginBundle(name, item.optBoolean("enabled"), item.optBoolean("installed"),
        item.optBoolean("official"), item.optString("version").takeIf { it.isNotBlank() },
        item.optString("description").takeIf { it.isNotBlank() },
        item.optString("readOnlyReason").takeIf { it.isNotBlank() })
    }
  }

  override suspend fun pluginEntries(): List<RemotePluginEntry> {
    val list = call("pluginManager/listPlugins") as? JSONArray ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
      val item = list.optJSONObject(i) ?: return@mapNotNull null
      val id = item.optString("entryId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      RemotePluginEntry(id, item.optString("moduleName").takeIf { it.isNotBlank() } ?: id,
        item.optBoolean("enabled"), item.optString("fiberPhase").takeIf { it.isNotBlank() },
        item.optString("readOnlyReason").takeIf { it.isNotBlank() })
    }
  }

  override suspend fun setPluginBundle(name: String, enabled: Boolean): Boolean {
    applyPluginChange(call("pluginManager/setBundleEnabled", envelope(argsOf("name" to name, "enabled" to enabled))), name)
    return true
  }

  /**
   * 插件开关返回 `ChangeResult{changed, application, restartReason?, error?}`。`application` 可能是
   * `failed`/`cancelled`/`restart-required`——把它丢掉，界面上就是"开关拨动后回弹且没有任何提示"。
   */
  private fun applyPluginChange(result: Any?, name: String) {
    val value = result as? JSONObject ?: return
    val application = value.str("application")
    val detail = value.obj("error").str("diagnostic").ifBlank { value.obj("error").str("code") }
    when (application) {
      "failed" -> throw ApiException(409, detail.ifBlank { "插件 $name 启用状态没有生效" })
      "cancelled" -> throw ApiException(409, detail.ifBlank { "插件 $name 的变更已取消" })
      "restart-required" -> Diagnostics.info("DshApi",
        "插件 $name 需要重启 Host 才生效：${value.str("restartReason").ifBlank { "未给出原因" }}")
      else -> Unit
    }
  }

  override suspend fun createProviderRoute(
    route: String, displayName: String, api: String, baseUrl: String,
    apiKeyEnv: String, modelId: String, apiKey: String
  ): Boolean {
    val validRoute = Regex("[a-z][a-z0-9-]{0,63}").matches(route)
    if (!validRoute || api !in setOf("openai-completions", "openai-responses", "anthropic-messages") ||
      modelId.isBlank() || !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))
      throw ApiException(400, "模型服务商参数无效")
    val describe = callObject("settings/describe")
    // `writable` 是 describe 的**根**字段（`required:[writable,hasDocument,namespaces]`），
    // 命名空间上没有这个字段。读错位置会让只读 Host 的写保护永远不生效。
    if (!describe.optBoolean("writable", true)) throw ApiException(403, "Host 设置只读")
    val namespaces = describe.optJSONArray("namespaces") ?: throw ApiException(404, "Host 没有设置命名空间")
    val ns = (0 until namespaces.length()).mapNotNull { namespaces.optJSONObject(it) }
      .firstOrNull { it.optString("ns") == "llm-pi-ai" }
      ?: throw ApiException(404, "Host 没有启用 pi-ai 模型适配器")
    val profile = JSONObject().put("displayName", displayName.ifBlank { route })
      .put("api", api).put("baseURL", baseUrl).put("apiKeyEnv", apiKeyEnv)
      .put("models", JSONArray().put(JSONObject().put("id", modelId)))
    val ops = JSONArray().put(JSONObject().put("op", "set")
      .put("path", JSONArray().put("providers").put(route)).put("value", profile))
    call("settings/mutate", envelope(argsOf(
      "ns" to "llm-pi-ai",
      "ops" to ops,
      "expectedRevision" to ns.optInt("revision", 0)
    )))
    if (apiKey.isNotBlank()) setCredential(apiKeyEnv, apiKey)
    return true
  }

  override suspend fun schedules(sessionId: String): List<RemoteSchedule> {
    require(sessionId.isNotBlank()) { "Missing schedule owner" }
    val value = call("schedule/list", requestArgs(JSONObject().put("sessionId", sessionId)))
    return scheduleResponse {
      val rows = value as? JSONArray ?: throw IllegalArgumentException("Missing schedule list")
      scheduleImmutable((0 until rows.length()).map { index ->
        val row = rows.opt(index) as? JSONObject ?: throw IllegalArgumentException("Invalid schedule row")
        ScheduleRecord.parse(row).toRemoteSchedule(sessionId, "active")
      })
    }
  }

  override suspend fun scheduleCatalog(): List<RemoteSchedule>? {
    val value = try { call("schedule/catalog", noArgs()) } catch (error: ApiException) {
      if (error.isDshMethodUnavailable) return null
      throw error
    }
    return scheduleResponse {
      val rows = value as? JSONArray ?: throw IllegalArgumentException("Missing schedule catalog")
      scheduleImmutable((0 until rows.length()).map { index ->
        val row = rows.opt(index) as? JSONObject ?: throw IllegalArgumentException("Invalid schedule row")
        val owner = scheduleString(row, "sessionId").also { require(it.isNotBlank()) }
        val status = scheduleString(row, "status").also { require(it == "active" || it == "inactive") }
        val delivery = if (row.has("lastDelivery")) ScheduleDelivery.parse(
          row.opt("lastDelivery") as? JSONObject ?: throw IllegalArgumentException("Invalid delivery")
        ) else null
        ScheduleRecord.parse(row).toRemoteSchedule(owner, status, delivery)
      })
    }
  }

  override suspend fun scheduleHistory(sessionId: String, id: String, before: String?, limit: Int): ScheduleHistoryPage {
    require(sessionId.isNotBlank() && id.isNotBlank()) { "Missing schedule owner/identity" }
    require(limit in 1..100 && (before == null || before.isNotBlank())) { "Invalid history page/cursor" }
    val request = JSONObject().put("sessionId", sessionId).put("id", id).put("limit", limit)
    before?.let { request.put("before", it) }
    val value = call("schedule/history", requestArgs(request))
    return scheduleResponse {
      val result = value as? JSONObject ?: throw IllegalArgumentException("Missing history result")
      require(scheduleString(result, "id") == id) { "Mismatched history identity" }
      if (result.has("code")) {
        requireScheduleKeys(result, setOf("id", "code"))
        val code = scheduleString(result, "code")
        require(code == "schedule_not_found" || code == "delivery_cursor_not_found")
        throw ApiException(if (code == "schedule_not_found") 404 else 409,
          if (code == "schedule_not_found") "定时任务不存在或已删除" else "送达历史游标已失效，请重新加载", code)
      }
      requireScheduleKeys(result, setOf("id", "records", "earlierRecordsUnavailable", "earlierRecordsPruned", "retention"), setOf("nextBefore"))
      val records = result.opt("records") as? JSONArray ?: throw IllegalArgumentException("Invalid history records")
      val retention = result.opt("retention") as? JSONObject ?: throw IllegalArgumentException("Invalid retention")
      val cursor = scheduleOptionalString(result, "nextBefore").also { require(it == null || it.isNotBlank()) }
      ScheduleHistoryPage(id, scheduleImmutable((0 until records.length()).map { index ->
        ScheduleDelivery.parse(records.opt(index) as? JSONObject ?: throw IllegalArgumentException("Invalid receipt"))
      }), scheduleBoolean(result, "earlierRecordsUnavailable"), scheduleBoolean(result, "earlierRecordsPruned"),
        scheduleLong(retention.opt("days")), scheduleLong(retention.opt("records")), cursor)
    }
  }

  override suspend fun deleteSchedule(sessionId: String, id: String): ScheduleDeleteResult {
    require(sessionId.isNotBlank() && id.isNotBlank()) { "Missing schedule owner/identity" }
    // Never replay a write, including after an authentication response.
    val value = call("schedule/delete", requestArgs(JSONObject().put("sessionId", sessionId).put("id", id)), retried = true)
    return scheduleResponse {
      val result = value as? JSONObject ?: throw IllegalArgumentException("Missing delete result")
      require(scheduleString(result, "id") == id) { "Mismatched delete identity" }
      val deleted = scheduleBoolean(result, "deleted")
      if (deleted) {
        requireScheduleKeys(result, setOf("id", "deleted"))
        ScheduleDeleteResult(id, true)
      } else {
        requireScheduleKeys(result, setOf("id", "deleted", "code"))
        val code = scheduleString(result, "code").also { require(it == "schedule_not_found") }
        ScheduleDeleteResult(id, false, code)
      }
    }
  }

  override suspend fun updateSchedule(
    sessionId: String, id: String, expected: ScheduleRecord,
    title: String?, prompt: String?, change: ScheduleTimingChange?
  ): ScheduleUpdateResult {
    require(sessionId.isNotBlank() && id.isNotBlank() && expected.id == id) { "Invalid schedule owner/CAS identity" }
    val normalizedTitle = title?.trim()?.also { require(it.length in 1..120) { "Invalid schedule title" } }
    val normalizedPrompt = prompt?.trim()?.also { require(it.isNotBlank()) { "Invalid schedule prompt" } }
    val request = JSONObject().put("sessionId", sessionId).put("id", id).put("expected", expected.toJson())
    normalizedTitle?.let { request.put("title", it) }
    normalizedPrompt?.let { request.put("prompt", it) }
    change?.let { request.put("change", it.toJson()) }
    val value = call("schedule/update", requestArgs(request), retried = true)
    return scheduleResponse {
      val result = value as? JSONObject ?: throw IllegalArgumentException("Missing update result")
      if (result.has("record")) {
        requireScheduleKeys(result, setOf("id", "updated", "record"))
        require(scheduleString(result, "id") == id) { "Mismatched update identity" }
        val record = ScheduleRecord.parse(result.opt("record") as? JSONObject ?: throw IllegalArgumentException("Invalid record"))
        require(record.id == id) { "Mismatched updated record" }
        ScheduleUpdateResult(id, scheduleBoolean(result, "updated"), record)
      } else if (result.has("id")) {
        requireScheduleKeys(result, setOf("id", "updated", "code"))
        require(scheduleString(result, "id") == id && !scheduleBoolean(result, "updated"))
        val code = scheduleString(result, "code").also { require(it in setOf("schedule_not_found", "schedule_ended", "schedule_conflict")) }
        ScheduleUpdateResult(id, false, code = code)
      } else {
        requireScheduleKeys(result, setOf("code", "message"))
        val code = scheduleString(result, "code").also { require(it in setOf("invalid_prompt", "invalid_selector", "invalid_rule",
          "invalid_time_zone", "not_future", "time_out_of_range", "frequency_too_high", "subagent_session", "internal_error")) }
        ScheduleUpdateResult(null, false, code = code, message = scheduleString(result, "message"))
      }
    }
  }

  private fun requireScheduleKeys(value: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
    val keys = value.keys().asSequence().toSet()
    require(keys.containsAll(required) && keys.all { it in required || it in optional }) { "Invalid schedule result fields" }
  }

  private inline fun <T> scheduleResponse(parse: () -> T): T = try { parse() } catch (_: IllegalArgumentException) {
    throw ApiException(502, "定时任务响应格式无效，请刷新核对；不会自动重发", "schedule/response-invalid")
  }

  override suspend fun previewFile(directory: String, path: String, session: Session?): FilePreview? = withContext(Dispatchers.IO) {
    val ext = path.substringAfterLast('.', "").lowercase()
    val imageMime = when (ext) {
      "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
      else -> null
    }
    val budget = if (imageMime != null) 8 * 1024 * 1024 else 16 * 1024 * 1024
    // 远程主机的 `workspaceFiles/readBytes` 单次窗口被夹到 4 MiB（Remote Host 的 MAX_READ_BYTES），
    // 请求更长只会拿到 `eof=false`。直连没有这个窗口，按预算读即可。
    val window = if (remoteBackend != null) minOf(budget, REMOTE_READ_WINDOW) else budget
    val office = ext in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx")
    if (imageMime == null && ext != "pdf" && !office) return@withContext null
    // 图片与 PDF 走官方 Web 自己的只读路由 `GET /api/file?path=<绝对路径>`：一次 GET 直接拿到字节和
    // Content-Type，不必把二进制 RPC 的 multipart 信封往返一次。Remote 中继只代理 RPC，没有这条
    // HTTP 路由，所以只在直连/SSH 隧道下使用；失败仍回落到 RPC。
    if (!office && remoteBackend == null) {
      absoluteWorkspacePath(directory, path)?.let { absolute ->
        fetchMedia(absolute, budget)?.let { return@withContext it }
      }
    }
    val scope = fileScope(directory, session)
    val value = try {
      if (office) callObject("officeToPdf/render", envelope(argsOf(
        "workspaceFileScopeId" to scope.id, "path" to path,
        // `priority` 是必填参数（enum foreground|background），漏传会被网关判为
        // gateway/arguments-invalid，Office 预览因此整条路径失效。
        "priority" to "foreground"
      )))
      else callObject("workspaceFiles/readBytes", envelope(argsOf(
        "workspaceFileScopeId" to scope.id, "path" to path,
        // 契约里读取窗口是第三个参数 `options.range`，不是顶层 `range`。
        "options" to JSONObject().put("range", JSONObject().put("offset", 0).put("length", window))
      )))
    } catch (error: ApiException) {
      // Unsupported preview endpoints are a capability gap, not a failed file read.
      if (error.isDshMethodUnavailable) return@withContext null
      throw error
    }
    val encoded = value.optString("data").takeIf { it.isNotBlank() } ?: return@withContext null
    val bytes = try { java.util.Base64.getDecoder().decode(encoded) } catch (_: Exception) {
      throw ApiException(502, "预览数据编码无效")
    }
    if (bytes.size > window) throw ApiException(413, "文件超过 ${window / (1024 * 1024)} MiB 预览上限")
    if (!office && value.has("eof") && !value.optBoolean("eof"))
      throw ApiException(413, "文件超过 ${window / (1024 * 1024)} MiB 预览上限，请改在电脑上查看")
    FilePreview(if (office) "application/pdf" else imageMime ?: "application/pdf", bytes)
  }

  /**
   * 会话目标。
   *
   * 远程白名单里**没有** `goals/get`（只有 clear/complete/create/edit/pause/resume），所以远程模式
   * 改用 `session/projections` 里 durable 的 `values.goal`，进程内的 `activation` 由转发事件
   * `goal/activation-changed` 补齐。直连/SSH 仍然读 `goals/get`——它是 activation 的权威来源。
   */
  override suspend fun goal(sessionId: String): RemoteGoal? {
    if (remoteBackend != null) return composerContext(sessionId).goal
    val goalEpoch = goalCacheEpoch(sessionId)
    val value = call("goals/get", envelope(argsOf("agentId" to sessionId))) as? JSONObject
    return mergeGoalRead(sessionId, value?.goalView(), direct = true, startedEpoch = goalEpoch)
  }

  override suspend fun createGoal(sessionId: String, objective: String, maxRounds: Int?): Boolean =
    createGoalWithLimit(sessionId, objective, maxRounds?.toLong())

  override suspend fun createGoalWithLimit(sessionId: String, objective: String, maxRounds: Long?): Boolean {
    require(sessionId.isNotBlank()) { "会话 id 不能为空" }
    val request = goalRequest(objective, maxRounds)
    val acknowledgment = call("goals/create", envelope(argsOf("agentId" to sessionId, "request" to request))) as? JSONObject
    // Create only acknowledges a ref. Preserve a matching activation event which can precede this ack;
    // never invent armed/disarmed from create itself or retain the previous goal's activation.
    val created = acknowledgment?.optJSONObject("ref")
    synchronized(goalCacheLock) {
      val observed = goalActivation[sessionId]?.takeIf {
        it.id == created?.str("id") && it.revision == created.optInt("revision", 0)
      }
      clearGoalCache(sessionId)
      clearedGoalRefs.remove(sessionId)
      if (observed != null) goalActivation[sessionId] = observed
    }
    return true
  }

  override suspend fun editGoal(
    sessionId: String, id: String, revision: Int, objective: String?, maxRounds: Long?
  ): RemoteGoal? {
    requireGoalRef(sessionId, id, revision)
    val request = goalRequest(objective, maxRounds)
    val view = call("goals/edit", envelope(argsOf(
      "agentId" to sessionId,
      "ref" to JSONObject().put("id", id).put("revision", revision),
      "request" to request
    )))
    val result = (view as? JSONObject)?.goalView()
      ?: throw ApiException(502, "目标编辑响应缺少 GoalView")
    if (result.id != id || result.revision <= revision) throw ApiException(502, "目标编辑响应的引用不匹配，请刷新核对；不会自动重发")
    return commitGoalView(sessionId, view) ?: throw ApiException(502, "目标编辑响应无效")
  }

  private fun goalRequest(objective: String?, maxRounds: Long?): JSONObject {
    require(objective != null || maxRounds != null) { "至少修改目标内容或轮数上限" }
    val normalized = objective?.trim()
    require(normalized == null || normalized.isNotEmpty()) { "目标内容不能为空" }
    require(maxRounds == null || maxRounds in 1L..MAX_GOAL_ROUNDS) { "目标轮数必须是正安全整数" }
    return JSONObject().apply {
      if (normalized != null) put("objective", normalized)
      if (maxRounds != null) put("maxGoalRounds", maxRounds)
    }
  }

  private fun requireGoalRef(sessionId: String, id: String, revision: Int) {
    require(sessionId.isNotBlank() && id.isNotBlank() && revision > 0) { "目标引用无效" }
  }

  override suspend fun goalAction(sessionId: String, id: String, revision: Int, action: String): Boolean {
    if (action !in setOf("pause", "resume", "complete", "clear")) return false
    requireGoalRef(sessionId, id, revision)
    val view = call("goals/$action", envelope(argsOf(
      "agentId" to sessionId,
      "ref" to JSONObject().put("id", id).put("revision", revision)
    )))
    if (action == "clear") synchronized(goalCacheLock) {
      clearGoalCache(sessionId)
      val returnedRevision = (view as? JSONObject)?.goalRoundNumber("revision", 1)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
      clearedGoalRefs[sessionId] = ClearedGoal(id, returnedRevision?.takeIf { it > revision } ?: revision)
    } else commitGoalView(sessionId, view)
    return true
  }

  private fun clearGoalCache(sessionId: String) = synchronized(goalCacheLock) {
    advanceGoalCacheEpoch(sessionId)
    goalActivation.remove(sessionId)
    latestGoalViews.remove(sessionId)
  }

  /** Mutation views are authoritative; a ref-only create ack must never enter this cache. */
  private fun commitGoalView(sessionId: String, view: Any?): RemoteGoal? {
    val goal = (view as? JSONObject)?.goalView() ?: return null
    synchronized(goalCacheLock) {
      val latest = latestGoalViews[sessionId]
      if (latest == null || latest.id != goal.id || latest.revision <= goal.revision) {
        advanceGoalCacheEpoch(sessionId)
        latestGoalViews[sessionId] = goal
        val activation = goal.activation
        if (activation != null) goalActivation[sessionId] = GoalActivation(goal.id, goal.revision, activation)
        else goalActivation.remove(sessionId)
      }
    }
    return goal
  }

  /** Only older revisions of the SAME goal may be replaced with the last mutation response. */
  private fun mergeGoalRead(sessionId: String, read: RemoteGoal?, direct: Boolean = false, startedEpoch: Long): RemoteGoal? =
    synchronized(goalCacheLock) {
      if (startedEpoch != goalCacheEpoch(sessionId)) {
        val latest = latestGoalViews[sessionId]
        val activation = goalActivation[sessionId]
        if (latest != null) return@synchronized latest.copy(activation = activation?.takeIf {
          it.id == latest.id && it.revision == latest.revision
        }?.activation ?: latest.activation)
        return@synchronized read?.takeIf { activation?.id == it.id && activation.revision == it.revision }
          ?.copy(activation = activation?.activation)
      }
      if (read == null) {
        clearGoalCache(sessionId)
        return@synchronized null
      }
      val cleared = clearedGoalRefs[sessionId]
      if (cleared?.id == read.id && read.revision <= cleared.revision) return@synchronized null
      if (cleared != null && cleared.id != read.id) clearedGoalRefs.remove(sessionId)
      val latest = latestGoalViews[sessionId]
      val selected = if (latest?.id == read.id && (latest.revision > read.revision || !direct && latest.revision == read.revision)) {
        latest.copy(roundsStarted = listOfNotNull(latest.roundsStarted, read.roundsStarted).maxOrNull())
      } else read
      if (latest != null && (latest.id != read.id || read.revision > latest.revision))
        latestGoalViews.remove(sessionId)
      val activation = goalActivation[sessionId]
      val exactActivation = activation?.takeIf { it.id == selected.id && it.revision == selected.revision }
      val result = if (direct && selected === read && read.activation != null) read
        else selected.copy(activation = exactActivation?.activation ?: selected.activation)
      if (direct && selected === read) {
        if (read.activation != null) goalActivation[sessionId] = GoalActivation(read.id, read.revision, read.activation)
        else goalActivation.remove(sessionId)
        if (latest?.id == read.id && latest.revision == read.revision) latestGoalViews[sessionId] = result
      }
      result
    }

  override suspend fun credentialStatus(refs: List<String>): Map<String, RemoteCredentialStatus> {
    if (refs.isEmpty()) return emptyMap()
    val body = call("credentials/describe", envelope(argsOf("refs" to JSONArray(refs)))) as? JSONObject ?: return emptyMap()
    return body.keys().asSequence().mapNotNull { ref ->
      val item = body.optJSONObject(ref) ?: return@mapNotNull null
      ref to RemoteCredentialStatus(ref, item.optBoolean("configured"), item.optBoolean("writable", true),
        item.optString("source").takeIf { it.isNotBlank() })
    }.toMap()
  }

  override suspend fun setCredential(ref: String, value: String): Boolean {
    if (ref.isBlank() || value.isBlank()) return false
    call("credentials/set", envelope(argsOf("ref" to ref, "value" to value)))
    return true
  }

  override suspend fun clearCredential(ref: String): Boolean {
    if (ref.isBlank()) return false
    call("credentials/unset", envelope(argsOf("ref" to ref)))
    return true
  }

  override suspend fun setPluginEntry(entryId: String, enabled: Boolean): Boolean {
    applyPluginChange(call("pluginManager/setPluginEnabled", envelope(argsOf("id" to entryId, "enabled" to enabled))), entryId)
    return true
  }

  override suspend fun promptSubagent(parentId: String, childId: String, text: String): Boolean {
    if (text.isBlank()) return false
    val request = JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
      .put("parentSessionId", parentId).put("childSessionId", childId)
      .put("mode", "continuable").put("delivery", "steer")
      .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    clientTimeZone()?.let { request.put("clientTimeZone", it) }
    call("subagents/prompt", requestArgs(request))
    return true
  }

  override suspend fun followJobOutput(
    sessionId: String,
    jobId: String,
    onUpdate: (text: String, running: Boolean, finished: Boolean) -> Unit
  ): Boolean {
    val closed = CompletableDeferred<Unit>()
    var sawFrame = false
    var runningState = false
    var terminalSeen = false
    // `from` 是字节位移：断线重连时从上次读到的位置续读，而不是从任务开头重放。
    val offsetKey = "$sessionId|$jobId"
    var lossy = false
    val request = JSONObject().put("sessionId", sessionId).put("jobId", jobId)
    jobOffsets[offsetKey]?.let { request.put("from", it) }
    val socket = try {
      openMux("job/follow", requestArgs(request),
        onItem = { frame ->
          sawFrame = true
          // `next`/`lossy`/`gapBefore` 描述"这段输出是否连续"。忽略它们会让被裁剪的输出看起来完整。
          frame.optDouble("next", -1.0).toLong().takeIf { it >= 0 }?.let { jobOffsets[offsetKey] = it }
          if (frame.optBoolean("lossy") || frame.optBoolean("gapBefore")) lossy = true
          when (frame.optString("type")) {
            "opened", "status" -> {
              val job = frame.optJSONObject("job")
              val status = job?.optString("status").orEmpty()
              runningState = status == "running" || status == "stopping"
              terminalSeen = frame.str("type") == "status" && status.isNotBlank() && !runningState
              onUpdate("", runningState, terminalSeen)
            }
            "output" -> {
              val chunks = frame.optJSONArray("chunks")
              val text = buildString {
                if (chunks != null) for (i in 0 until chunks.length()) {
                  val chunk = chunks.optJSONObject(i) ?: continue
                  append(chunk.optString("text"))
                }
              }
              if (text.isNotEmpty()) onUpdate(text, runningState, false)
            }
          }
        },
        onClosed = { cause ->
          if (cause != null) closed.completeExceptionally(cause) else closed.complete(Unit)
        })
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { throw error }
    return try {
      closed.await()
      if (sawFrame && !terminalSeen) throw IOException("后台任务输出流中断，状态待核实")
      if (lossy && sawFrame) onUpdate("\n…（主机裁剪了早先输出，缺失部分无法回读）", runningState, true)
      sawFrame
    } finally { runCatching { socket.cancel() } }
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
    // Use the current authoritative roster: stale/deleted cached sessions must not poison all jobs
    // in this directory with a permanent session-not-found error.
    val sessions = listing().items.mapNotNull(::itemToSession)
      .filter { normalizedDirectory(it.directory) == normalized }.sortedByDescending { it.updated }
    if (sessions.isEmpty()) return@withContext emptyList()
    val jobs = LinkedHashMap<String, ShellJob>()
    for (session in sessions) {
      for (job in runningJobs(session)) {
        val id = job.str("id")
        val owner = job.str("owner")
        // 无主作业是 Host 全局的，无法归属到目录/会话，跳过。
        if (id.isBlank() || owner != session.id || job.str("status") !in ACTIVE_JOB_STATUSES) continue
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

  /**
   * 终止一个后台作业：一元 `job/kill {sessionId, jobId}`（`job/list` 行里带 `owner`）。
   *
   * 旧实现落到 HarnessClient 的空默认实现上，界面把任务从列表里删掉、远端进程却还在跑。
   * 这里找不到所属会话就直接报错，绝不假装成功。
   */
  override suspend fun removeShell(id: String, directory: String): Unit = withContext(Dispatchers.IO) {
    val sessionId = jobOwner(id, directory) ?: throw ApiException(404, "找不到该后台任务所属的会话，无法终止")
    call("job/kill", requestArgs(JSONObject().put("sessionId", sessionId).put("jobId", id)))
    // 清掉缓存，下一次对账读到的是 Host 的真实状态，而不是我们本地删掉的那一行。
    jobCache.remove(sessionId)
  }

  /** 先从缓存里找作业归属，再按目录扫描一次；都找不到说明作业已经结算并移出登记表。 */
  private suspend fun jobOwner(jobId: String, directory: String): String? {
    for ((sessionId, entry) in jobCache) {
      if (entry.second.any { it.str("id") == jobId }) return sessionId
    }
    val normalized = normalizedDirectory(directory)
    val sessions = listing().items.mapNotNull(::itemToSession)
      .filter { normalizedDirectory(it.directory) == normalized }.sortedByDescending { it.updated }
    for (session in sessions.take(SHELL_OWNER_SCAN_SESSIONS)) {
      val jobs = try { runningJobs(session) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {
        continue
      }
      if (jobs.any { it.str("id") == jobId }) return session.id
    }
    return null
  }

  /** `job/list` 是每会话一条流；短 TTL 缓存把同一轮对账里的重复读取收敛掉。 */
  private suspend fun runningJobs(session: Session): List<JSONObject> {
    jobCache[session.id]?.takeIf { clock() - it.first < JOB_CACHE_MILLIS }?.let { return it.second }
    // A failed/invalid read is not an authoritative empty list. Propagate it so the caller keeps
    // last-known jobs and marks the directory stale instead of silently deleting running work.
    val jobs = oneShotStreams.withPermit {
      jobCache[session.id]?.takeIf { clock() - it.first < JOB_CACHE_MILLIS }?.second ?: run {
        val frame = fetchFirstFrame("job/list", requestArgs(JSONObject().put("sessionId", session.id)))
        if (frame.str("type") != "rows" || frame.optJSONArray("jobs") == null)
          throw ApiException(502, "后台任务列表格式无效", "stream/jobs-invalid")
        frame.arr("jobs").objects()
      }
    }
    jobCache[session.id] = clock() to jobs
    return jobs
  }

  override suspend fun createSession(directory: String, title: String, workspaceId: String?): Session = withContext(Dispatchers.IO) {
    // `session/create` may durably create the Session before a workspace attach fails. The official
    // error carries details.sessionId; reconcile that identity and never replay creation.
    val reusable = reusableBlank(directory)
    val resolvedWorkspaceId = workspaceId?.takeIf(String::isNotBlank) ?: workspaceIdFor(directory)
    try {
      createSessionOnce(directory, title, reusable, resolvedWorkspaceId)
    } catch (error: ApiException) {
      if (error.code == "session/workspace-attach-failed") throw error
      // These errors explicitly mean “no creation/reuse”; only a blank reuse can safely be retried.
      if (reusable == null || error.code !in BLANK_REUSE_DECLINED) throw error
      createSessionOnce(directory, title, null, resolvedWorkspaceId)
    }
  }

  /**
   * 目录恰好是某个已注册工作区的根目录时返回它的 workspaceId，否则返回 null 退回 cwd 语义。
   * 只读 `workspace/follow` 已经维护好的本地清单（连上服务器后控制器会先拉一次），建会话时不再多开一条流；
   * 目录是工作区子目录时不能换成 workspaceId —— 那会把会话的 cwd 强制成工作区根。
   */
  private fun workspaceIdFor(directory: String): String? {
    val wanted = normalizedDirectory(directory)
    if (wanted.isEmpty()) return null
    return workspaceItems.orEmpty()
      .firstOrNull { normalizedDirectory(it.str("path")) == wanted }
      ?.str("workspaceId")?.ifBlank { null }
  }

  /** 同目录、未归档、未开始的空白会话；取最新的一条，避免把历史草稿全部翻出来。 */
  private fun reusableBlank(directory: String): String? {
    val wanted = normalizedDirectory(directory)
    if (wanted.isEmpty()) return null
    return known.values.asSequence()
      .filter { it.blank && it.parentId == null && !it.archived && normalizedDirectory(it.directory) == wanted }
      .maxByOrNull { it.updated }?.id
  }

  private suspend fun createSessionOnce(directory: String, title: String, reuse: String?, workspaceId: String?): Session {
    // workspaceId 与 cwd 互斥（网关严格校验）：给 workspaceId 时 Host 用它自己的工作区路径作为 cwd。
    val request = if (workspaceId != null) JSONObject().put("workspaceId", workspaceId)
    else JSONObject().put("cwd", directory)
    if (reuse != null) request.put("sessionId", reuse)
    val result = callObject("session/create", requestArgs(request))
    val id = result.str("sessionId").ifBlank { throw ApiException(502, "服务器没有返回新会话 id") }
    val created = Session(
      id = id, directory = directory, title = title.ifBlank { "新会话" },
      updated = System.currentTimeMillis(), projectId = directory, created = System.currentTimeMillis(),
      agent = result.str("agentPreset").ifBlank { null }, blank = true
    )
    known[id] = created
    if (title.isNotBlank()) runCatching { renameSession(created, title) }
    return created
  }

  override suspend fun renameSession(session: Session, title: String) {
    call("session/rename", requestArgs(JSONObject().put("sessionId", session.id).put("title", title)))
    known[session.id] = session.copy(title = title)
  }

  override suspend fun setArchived(session: Session, archived: Boolean, now: Long) {
    val request = JSONObject().put("sessionId", session.id)
    if (archived) request.put("stopActivity", true)
    val result = callObject(if (archived) "workspace/archiveSession" else "workspace/unarchiveSession", requestArgs(request))
    val authoritative = result.optJSONArray("archivedSessionIds")
      ?: throw ApiException(502, "归档响应缺少完整集合，请刷新核实状态", "workspace/archive-response-invalid")
    replaceArchiveSet(authoritative.strings())
    known[session.id] = session.copy(archived = session.id in archivedSessions)
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
    val parent = session.parentId?.takeIf { it.isNotBlank() }
    if (parent != null && parent != session.id) {
      // 子代理会话只能由**父地址**中断：`session/cancel` 的请求体固定只有 `{sessionId}`，
      // 装不下父地址，Host 会以 `session/agent-busy` 拒绝。DSH 自己走的也是这条端点。
      call("subagents/interruptByParent", envelope(argsOf(
        "childSessionId" to session.id,
        "parentSessionId" to parent,
        "mode" to (session.subagentMode?.takeIf { it.isNotBlank() } ?: "continuable")
      )))
      return
    }
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
    if (model != null) selectModel(session, model)
    selectPreset(session, agent)
    val content = JSONArray()
    val remote = remoteBackend
    val uploadLines = mutableListOf<String>()
    // Remote has no fileUploads receipt; publish arbitrary files into the current workspace and
    // reference the returned path. Images keep the existing multimodal image block.
    if (remote != null) {
      for (file in inline) {
        val mime = file.mime.substringBefore(';').trim().lowercase()
        if (AttachmentPolicy.kind(mime, file.name) == AttachmentPolicy.Kind.IMAGE) continue
        val bytes = readInlineBytes(file) ?: throw ApiException(400, "附件无法读取：${file.name}")
        val uploaded = remote.uploadWorkspaceFile(session.id, bytes, file.name)
        uploadLines += "已上传文件：${uploaded.path}"
      }
    }
    // DSH 的 prompt 没有「文件引用」块；引用的服务器文件折算成 `@路径` 文本，与官方 Web 的提及写法一致。
    val body = buildString {
      append(text)
      if (references.isNotEmpty()) {
        if (isNotEmpty()) append('\n')
        append(references.joinToString("\n") { "@${it.path}" })
      }
      if (uploadLines.isNotEmpty()) {
        if (isNotEmpty()) append('\n')
        append(uploadLines.joinToString("\n"))
      }
    }.trim()
    if (body.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", body))
    for (file in inline) {
      val mime = file.mime.substringBefore(';').trim().lowercase()
      val kind = AttachmentPolicy.kind(mime, file.name)
      if (kind == AttachmentPolicy.Kind.IMAGE) {
        inlineContent(file)?.let { content.put(it) }
        continue
      }
      // Remote 中继没有附件回执路由：上面已经折算成 `@路径` 文本。
      if (remote != null) continue
      val bytes = readInlineBytes(file) ?: throw ApiException(400, "附件无法读取：${file.name}")
      // 非图片附件走 `fileUploads/upload` 换 receipt，再以 `{type:"file", receiptId}` 发送：
      // 这样 Host 保留文件本身（字节、文件名、可读路径），而不是把正文拼进模型上下文。
      val receipt = try {
        uploadFileAttachment(session.id, bytes, file.name)
      } catch (error: ApiException) {
        // 旧实例/未装附件插件时才退回「文本内联」，二进制文件仍然明确失败。
        if (kind != AttachmentPolicy.Kind.TEXT || !error.isDshMethodUnavailable) throw error
        inlineContent(file)?.let { content.put(it) }
        continue
      }
      content.put(JSONObject().put("type", "file").put("receiptId", receipt))
    }
    if (content.length() == 0) throw ApiException(400, "消息内容为空：附件无法读取或格式不受支持")
    val request = JSONObject()
      .put("requestId", id)
      .put("sessionId", session.id)
      .put("mode", delivery.wire)
      .put("content", content)
    clientTimeZone()?.let { request.put("clientTimeZone", it) }
    call("session/prompt", requestArgs(request))
    // prompt 已被接受：本机不再把它当服务端草稿。即使随后的列表/事件仍带旧的 blank，
    // 也由 `itemToSession` 的单调判断保持可见；真正的 turn/start 会由 Host 再确认。
    known[session.id] = (known[session.id] ?: session).copy(blank = false)
  }

  /** `fileUploads/upload`：附件字节（base64）换成 Host 的 receipt，供 `session/prompt` 的 file 块引用。 */
  private suspend fun uploadFileAttachment(agentId: String, bytes: ByteArray, name: String): String {
    val request = JSONObject().put("data", java.util.Base64.getEncoder().encodeToString(bytes))
    if (name.isNotBlank()) request.put("name", name)
    val value = call("fileUploads/upload", envelope(argsOf("agentId" to agentId, "request" to request))) as? JSONObject
      ?: throw ApiException(502, "服务器没有返回附件回执")
    return value.optString("receiptId").takeIf(String::isNotBlank)
      ?: throw ApiException(502, "服务器没有返回附件回执")
  }

  /**
   * 手机附件（[InlineFile.uri] 是 `data:` URI）折算成 DSH 内容块。图片走多模态 `image` 块；
   * 其余文件由 [uploadFileAttachment] 换成 `file` 块的回执，不再把正文拼进提示词。
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
    val current = known[session.id] ?: session
    if (wanted == current.agent) return
    // 契约要求"在会话开始第一轮之前"选择预设；轮次已开始后 Host 会保留原组合（不报错也不通知），
    // 本地若照旧改名，药丸显示的预设就和主机真正用的工具集不一致。
    if (!current.blank) {
      Diagnostics.info("DshApi", "会话已开始首轮，预设不可再切换（${wanted.take(24)}）")
      return
    }
    try {
      // 返回的是**已提交**的预设标识：可能是主机规范化后的值，也可能与请求不同。
      val committed = (call("agentPresets/select", envelope(argsOf("agentId" to session.id, "agentPreset" to wanted))) as? String)
        ?.takeIf { it.isNotBlank() } ?: wanted
      known[session.id] = current.copy(agent = normalizeAgentPreset(committed))
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
    if (references.isNotEmpty()) {
      // commands/execute accepts only encoded images and staged file receipts. A FileReference is a
      // server path mention, not a receipt; dropping it would change the command's meaning.
      throw ApiException(400, "DeepSeek Harness 命令不支持文件引用，请改为上传附件", "command/attachments-unsupported")
    }
    val submitted = commandAttachments(session.id, inline)
    if (model != null) selectModel(session, model)
    executeCommand(session, "/$name $arguments".trim(), submitted)
  }

  private suspend fun commandAttachments(sessionId: String, inline: List<InlineFile>): JSONArray {
    if (inline.isEmpty()) return JSONArray()
    if (remoteBackend != null && inline.any { AttachmentPolicy.kind(it.mime, it.name) != AttachmentPolicy.Kind.IMAGE }) {
      throw ApiException(400, "此远程主机未提供命令文件附件通道，请更新 Host 连接器或改用普通消息上传", "command/attachments-unsupported")
    }
    val submitted = JSONArray()
    for (file in inline) {
      val bytes = readInlineBytes(file) ?: throw ApiException(400, "附件无法读取：${file.name}")
      val mime = file.mime.substringBefore(';').trim().lowercase()
      if (AttachmentPolicy.kind(mime, file.name) == AttachmentPolicy.Kind.IMAGE) {
        if (mime !in setOf("image/png", "image/jpeg", "image/webp", "image/gif"))
          throw ApiException(400, "命令图片附件类型不受支持：$mime", "command/attachment-invalid")
        submitted.put(JSONObject().put("type", "image").put("mediaType", mime)
          .put("data", java.util.Base64.getEncoder().encodeToString(bytes)).putOpt("name", file.name.takeIf { it.isNotBlank() }))
      } else {
        val receipt = uploadFileAttachment(sessionId, bytes, file.name)
        submitted.put(JSONObject().put("type", "file").put("receiptId", receipt))
      }
    }
    return submitted
  }

  /** `commands/execute` accepts the complete line plus submitted attachments as its third argument. */
  private suspend fun executeCommand(session: Session, line: String, submittedAttachments: JSONArray = JSONArray()) {
    val args = JSONObject()
      .put("agentId", session.id)
      .put("line", line)
      .put("submittedAttachments", submittedAttachments)
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
    val result = callObject("session/selectModel", requestArgs(request))
    // 结果是权威选择 `selected{provider,model,reasoningEffort?}`：Host 可能夹取或丢弃档位，
    // 用请求值覆盖本地缓存会让药丸一直显示主机没有采用的模型。
    val selected = result.obj("selected")
    val provider = selected.str("provider").ifBlank { model.providerId }
    val modelId = selected.str("model").ifBlank { model.modelId }
    val effort = selected.str("reasoningEffort").takeIf { it.isNotBlank() }
    known[session.id] = session.copy(model = ModelChoice(provider, modelId, model.label, effort))
  }

  // ---------------------------------------------------------------- 目录 / 模型 / 权限

  /** `agentPresets/list` 的预设名册：默认预设排第一，不能组合会话的（`broken`）不提供。 */
  override suspend fun agents(directory: String): List<AgentChoice> = withContext(Dispatchers.IO) {
    val roster = try { callObject("agentPresets/list", noArgs()) } catch (error: ApiException) {
      if (!error.isDshMethodUnavailable) throw error
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
        val reasoning = model.obj("reasoning")
        val variants = reasoning.arr("efforts").objects().mapNotNull { effort ->
          val effortId = effort.str("id").ifBlank { return@mapNotNull null }
          ModelVariant(effortId, effort.str("name").ifBlank { effortId })
        }
        // `efforts` 是**允许的档位枚举**，不是偏好顺序；默认档位在 `reasoning.defaultEffort`。
        // 拿 `efforts[0]` 当默认值会静默改动成本与质量。
        val defaultEffort = reasoning.str("defaultEffort").takeIf { it.isNotBlank() }
        models += ModelInfo(
          choice = ModelChoice(providerId, modelId, model.str("name").ifBlank { modelId }, defaultEffort),
          providerName = providerName.ifBlank { providerId },
          variants = variants
        )
      }
    }
    models
  }

  /**
   * `permissionPresets/catalog`：进程级权限目录（`{options:[{value,name,description?}]}`）。
   * 旧实例没有这个方法时当作「无可选权限」，而不是把设置页弄成错误。
   */
  override suspend fun permissionPresets(directory: String): List<PermissionPresetChoice> = withContext(Dispatchers.IO) {
    val value = try {
      callObject("permissionPresets/catalog")
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: ApiException) {
      if (error.isDshMethodUnavailable) return@withContext emptyList()
      throw error
    }
    value.arr("options").objects().mapNotNull { option ->
      val id = option.str("value").ifBlank { return@mapNotNull null }
      PermissionPresetChoice(id, option.str("name").ifBlank { id }, option.str("description"))
    }
  }

  /**
   * 切换权限模式。DSH 没有专用的 RPC：官方 UI 同样执行 `/permission <preset>` 命令，
   * 选项来自 [permissionPresets]；结果写回 `permissions` 投影，这里同步本地缓存。
   */
  override suspend fun setPermission(session: Session, value: String): Boolean {
    if (value.isBlank()) return false
    executeCommand(session, "/permission $value")
    known[session.id] = (known[session.id] ?: session).copy(permission = value)
    return true
  }

  override suspend fun serverPinnedSessions(): Set<String> = serverPinned.toSet()

  /** 置顶同步：断言式写服务端事实，本地集合跟着更新（`workspace/follow` 之后会再对一次）。 */
  override suspend fun setSessionPinned(session: Session, pinned: Boolean): Boolean {
    call(if (pinned) "workspace/pinSession" else "workspace/unpinSession", requestArgs(JSONObject().put("sessionId", session.id)))
    if (pinned) serverPinned += session.id else serverPinned -= session.id
    return true
  }

  override suspend fun sessionPermissions(session: Session): List<PermissionRequest> =
    pendingApprovals.values.filter { it.sessionId == session.id }

  override suspend fun pendingPermissions(directory: String): List<PermissionRequest> =
    pendingApprovals.values.toList()

  override val recoverableQuestions: Boolean get() = true

  override suspend fun forms(directory: String): List<QuestionRequest> {
    val listed = if (known.isEmpty() && questionSession == null && directory.isNotBlank())
      listing().items.mapNotNull(::itemToSession) else emptyList()
    val matching = (known.values + listed).distinctBy { it.id }.filter { !it.archived && !it.blank &&
      (directory.isBlank() || normalizedDirectory(it.directory) == normalizedDirectory(directory)) }
    val sessions = (matching.map { it.id } + listOfNotNull(questionSession)).distinct()
    // A partial scan is never an authoritative empty directory. The overview retains its last
    // question snapshot on this explicit error instead of silently losing unscanned requests.
    if (sessions.size > 256) throw ApiException(409, "目录中的会话过多，提问对账未完成，请打开目标会话")
    if (directory.isNotBlank() && sessions.isEmpty()) return emptyList()
    for (id in sessions) refreshQuestionProjection(id)
    return pendingQuestions.values.filter { directory.isBlank() || normalizedDirectory(it.directory) == normalizedDirectory(directory) }
  }

  override suspend fun replyPermission(request: PermissionRequest, reply: String) {
    pendingApprovals.remove(request.id)
    val outcome = when (reply) {
      "once", "always" -> "allowed-once"
      else -> "rejected"
    }
    settle(request.id, outcome)
  }

  override suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    require(answers.size == request.questions.size) { "提问字段已变化，请刷新" }
    val payload = JSONArray()
    request.questions.forEachIndexed { index, prompt ->
      val row = answers[index].filter(String::isNotBlank)
      val questionId = runCatching { JSONObject(prompt.field).str("key") }.getOrNull()?.takeIf(String::isNotBlank)
        ?: prompt.field.ifBlank { prompt.title }
      val (selected, custom) = row.splitAnswer(prompt.options)
      payload.put(JSONObject().put("id", questionId).put("selected", JSONArray(selected))
        .apply { if (custom != null) put("custom", custom) })
    }
    questionCoordinator.answer(request.id, JSONObject().put("answers", payload))
  }

  override suspend fun rejectQuestion(request: QuestionRequest) = questionCoordinator.dismiss(request.id)

  override suspend fun children(session: Session): List<Session> = withContext(Dispatchers.IO) {
    listing().items.mapNotNull { itemToSession(it) }.filter { it.parentId == session.id && !it.blank }
  }

  // ---------------------------------------------------------------- 改动 / 文件

  /**
   * 会话改动：读取最近若干 `workspace/changes` 事件的摘要（最新轮次优先）并按路径合并，
   * 逐文件对比来自认证栅栏内的 `/api/changes.diff`。摘要只在该 Host 进程内、会话存活期间提供。
   */
  override suspend fun diff(session: Session): List<FileChange> = withContext(Dispatchers.IO) {
    val snapshot = try { snapshotFor(session, fresh = false) } catch (error: CancellationException) { throw error } catch (error: Exception) { return@withContext emptyList() }
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
    // 契约要求客户端**不要**自己拼接路径段：主机给出的 `entry.path` 就是下一层要请求的绝对路径。
    // 因此在上一层点进来的绝对路径原样使用，只有传相对名时才退回本地拼接。
    val target = if (path.trim().startsWith("/")) path.trim() else {
      val relative = path.trim().trim('/').takeUnless { it.isEmpty() || it == "." }
      when {
        relative == null -> directory.ifBlank { "/" }
        directory.isBlank() || directory == "/" -> "/$relative"
        else -> "${directory.trimEnd('/')}/$relative"
      }
    }
    val listing = callObject("directoryPicker/list", envelope(argsOf("path" to target)))
    return listing.arr("entries").objects().mapNotNull { entry ->
      entry.str("name").takeIf(String::isNotBlank)?.let {
        FileNode(it, "directory", entry.str("path").takeIf { absolute -> absolute.isNotBlank() })
      }
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

  internal companion object {
    const val MUX_STREAM_BUFFER = 256
    const val MAX_RPC_RESPONSE_BYTES = 64L * 1024 * 1024
    const val WATCH_LIMIT = 12

    /** 首帧等待上限；只覆盖“流被打开到第一帧到达”，不含后续分片/transfer 读取。 */
    const val STREAM_FIRST_FRAME_TIMEOUT_MILLIS = 20_000L
    /** 首屏快照的尝试次数：拿到非 snapshot 帧或流错误时重开一次，仍失败才报错。 */
    const val SNAPSHOT_ATTEMPTS = 2
    const val SNAPSHOT_RETRY_DELAY_MILLIS = 400L
    /** 流预算满时，释放一条旧 follow 后给对端关闭帧留出的调度时间。 */
    const val STREAM_LIMIT_RETRY_DELAY_MILLIS = 150L
    /** 与官方客户端一致的窗口：限制单帧体积，更早的历史由 `session/page` 补。 */
    const val SNAPSHOT_MAX_MESSAGES = 500
    const val SNAPSHOT_MIN_MESSAGES = 50
    /** 重新打开会话时，超过这个时长的旧快照不再复用，改为从新 Follow Generation 重同步。 */
    const val SNAPSHOT_REUSE_MILLIS = 15_000L
    /** 单次补洞最多补这么多 seq；更大的缺口交给快照整体校正。 */
    const val MAX_GAP_REPAIR = 2_000L
    const val GAP_REPAIR_PAGE = 500
    /**
     * 复用空白会话被拒且明确没有创建时的错误码。网络错误/未知错误不在此列，避免重复创建。
     */
    private val BLANK_REUSE_DECLINED = setOf(
      "session/conflict", "session/agent-busy", "session/writer-held", "session/not-found",
      "gateway/arguments-invalid"
    )

    /** 认证失效的错误码：控制器据此提示重新配对，而不是当成网络抖动。 */
    const val AUTH_EXPIRED = DSH_AUTH_EXPIRED
    /** 隧道启动令牌已轮换，但上一张浏览器 Cookie 可能还能用。不是登录失效。 */
    const val AUTH_TOKEN_ROTATED = "auth/token-rotated"
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
    const val FOLLOW_PENDING_LIMIT = 512
    const val EVENTS_RETRY_MIN_MILLIS = 1_000L
    const val EVENTS_RETRY_MAX_MILLIS = 30_000L
    const val EVENTS_READY_TIMEOUT_MILLIS = STREAM_FIRST_FRAME_TIMEOUT_MILLIS
    const val WORKSPACE_RETRY_MIN_MILLIS = 2_000L
    const val WORKSPACE_RETRY_MAX_MILLIS = 60_000L
    /** `$events` 不可用时的状态兜底周期。 */
    const val STATUS_FALLBACK_MILLIS = 15_000L
    /** 补读结局时每页的消息数；一轮里的非消息事件不计入，所以要连翻几页。 */
    const val OUTCOME_PAGE_MESSAGES = 20
    /**
     * 远程主机的 `workspaceFiles/readBytes` 单次读取窗口。Host 侧夹到 4 MiB
     * （`harness-remote-bridge.ts` 的 `MAX_READ_BYTES`），请求更长只会拿到 `eof=false`。
     */
    const val REMOTE_READ_WINDOW = 4 * 1024 * 1024
    /** 往前翻这么多页还看不到 `turn/end` 就放弃，不当作成功。 */
    const val OUTCOME_PAGE_ATTEMPTS = 4

    /** 单次改动列表最多合并的轮次数与文件数。 */
    const val MAX_CHANGE_TURNS = 8
    const val MAX_CHANGED_FILES = 60

    /** 一条消息里最多回填多少附件字节（base64 后的长度）；超出的只在时间线里显示文件名。 */
    const val ATTACHMENT_BUDGET_BYTES = 12 * 1024 * 1024
    /** 附件 data URI 的在内存缓存条数上限。 */
    const val ATTACHMENT_CACHE_LIMIT = 64

    /** 作业清单缓存的有效期：把同一轮对账里的重复读取收敛掉。 */
    const val JOB_CACHE_MILLIS = 20_000L
    /** 终止作业时按目录回溯会话的上限：作业归属只可能落在最近活跃的这批会话里。 */
    const val SHELL_OWNER_SCAN_SESSIONS = 8
    val ACTIVE_JOB_STATUSES = setOf("running", "stopping")

    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * DSH 的实例能力。删除没有对应 RPC（归档代替）；回退没有对应语义；没有 `/openapi.json`
     * 之类的契约文档，漂移按方法级失败处理。
     */
    private val CAPABILITIES = ApiCapabilities(
      actions = mapOf(SessionAction.RENAME to ActionEndpoint("POST", "session/rename")),
      diff = true,
      sessionView = null,
      archive = ArchiveEndpoint(ActionEndpoint("POST", "workspace/archiveSession"), restorable = true),
      documented = false,
      deleteSession = false,
      fork = true,
      jobOutputStream = true,
      stopShell = true
    )

    /** Capabilities for a tunneled SSH client created before its DshApi instance exists. */
    internal val TUNNEL_CAPABILITIES: ApiCapabilities get() = CAPABILITIES
  }
}

/** Typert Gateway RPC 载荷只允许 `{args:{…}}` 信封；用类型包装避免新增接口时漏掉 `envelope(...)`。 */
@JvmInline
private value class RpcPayload(val json: JSONObject)
