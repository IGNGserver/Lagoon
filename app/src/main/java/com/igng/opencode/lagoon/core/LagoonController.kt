package com.igng.opencode.lagoon.core

import android.content.Context
import com.igng.opencode.lagoon.system.BackgroundSyncWorker
import com.igng.opencode.lagoon.system.TaskNotifications
import com.igng.opencode.lagoon.system.TaskMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal fun operationKey(server: String?, session: String?, project: String?, action: String): String {
  val target = when {
    action.startsWith("permission:") || action.startsWith("question:") || action.startsWith("revoke:") -> action.substringAfter(':')
    action == "create" || action == "sessions-more" -> project
    else -> session
  }
  return "$server:$target:$action"
}

/** Pending phone attachments belong to one composer: a session, or the not-yet-created draft. */
internal fun attachmentKey(session: String?): String = session ?: "draft"

data class LagoonState(
  val profiles: List<ServerProfile> = emptyList(), val serverId: String? = null, val version: String = "",
  val connected: Boolean = false, val cached: Boolean = false, val loading: Boolean = false, val error: String? = null,
  /** True when the last catalog refresh could not read every project; some values are last-known. */
  val degraded: Boolean = false,
  val staleDirectories: Set<String> = emptySet(), val streamConnected: Boolean = false,
  /** Non-error feedback (e.g. a share link); kept separate so it is not rendered as a failure. */
  val message: String? = null,
  val projects: List<Project> = emptyList(), val projectId: String? = null,
  /** Home list filter: null = 全部会话 (default), otherwise one project id. Not the execution target. */
  val scopeProjectId: String? = null,
  val sessions: List<Session> = emptyList(), val sessionId: String? = null,
  val knownParents: Map<String, String> = emptyMap(),
  val messages: List<Message> = emptyList(), val tasks: Map<String, TaskState> = emptyMap(),
  val permissions: List<PermissionRequest> = emptyList(), val questions: List<QuestionRequest> = emptyList(),
  val children: List<Session> = emptyList(), val changes: List<FileChange> = emptyList(),
  val agents: List<AgentChoice> = emptyList(), val models: List<ModelChoice> = emptyList(), val commands: List<CommandChoice> = emptyList(),
  /** [models] with catalog metadata; visibility follows [ModelVisibility] plus this phone's switches. */
  val modelCatalog: List<ModelInfo> = emptyList(), val modelOverrides: Map<String, Boolean> = emptyMap(),
  /** False means the server's complete catalog is shown; true means [modelOverrides] is an allow-list. */
  val modelVisibilityConfigured: Boolean = false,
  /** Per-server variant selections keyed by provider/model; values are always server-provided ids. */
  val modelVariants: Map<String, String> = emptyMap(), val recentModels: List<String> = emptyList(),
  /** Files picked on this phone, per session (`draft` before a session exists), until they are sent. */
  val pendingAttachments: Map<String, List<LocalAttachment>> = emptyMap(),
  val agent: String? = null, val model: ModelChoice? = null,
  val files: List<FileNode> = emptyList(), val filePath: String = ".", val fileText: String? = null, val fileBinary: Boolean = false,
  /** 添加项目用的远程目录选择器：当前绝对路径、目录项与搜索结果；[browsePath] 为 null 表示未打开。 */
  val browsePath: String? = null, val browseEntries: List<FileNode> = emptyList(), val browseSearch: List<String> = emptyList(),
  /** 目录浏览器是否显示以点开头的隐藏项；默认隐藏，每次重新打开浏览器时复位。 */
  val browseShowHidden: Boolean = false,
  val searchResults: List<String> = emptyList(),
  val capabilities: ApiCapabilities = ApiCapabilities(),
  val previews: Map<String, SessionPreview> = emptyMap(), val resources: Map<String, ResourceStatus> = emptyMap(),
  val pendingOperations: Set<String> = emptySet(),
  val agentChanged: Boolean = false, val modelChanged: Boolean = false,
  val draftSessions: Set<String> = emptySet(), val draft: String = "", val references: List<FileReference> = emptyList(),
  val cacheComplete: Boolean = true, val sessionCursors: Map<String, String> = emptyMap(), val messagesCursor: String? = null,
  val catalogComplete: Boolean = true, val loadedOlderMessages: Boolean = false,
  /** Increments once per successfully prepended older page; the transcript anchors on this, never on a streaming rebuild. */
  val historyRevision: Int = 0,
  /**
   * 未读结果账本（对齐官方客户端的通知账本）：只有本机见证“运行中 → 结束”的主会话才会产生一条，
   * 打开会话即标记已读。列表的“已完成 / 失败”、灵动岛计数都只读这里，从不由历史数据推断。
   */
  val notices: List<SessionNotice> = emptyList(), val collapsedSections: Set<String> = emptySet(), val pinned: Set<String> = emptySet(),
  /** 全服务器范围的任务计数，由实时 [tasks] 与未读 [notices] 派生，供灵动岛与系统通知复用。 */
  val summary: TaskSummary = TaskSummary.EMPTY,
  /**
   * 曾在应用处于后台时仍在运行的根会话：用于在首页 / 灵动岛 / 会话内提示“后台运行中”。
   * 当前仍活跃的保留，运行结束后自动移除。
   */
  val backgroundRunning: Set<String> = emptySet(),
  /**
   * 运行中的 shell 作业（`GET /api/shell` 与 `shell.created/exited/deleted` 事件），按 shell id 索引。
   * 后台 shell 不是会话，[tasks] 里看不到它；它是“后台任务运行中”判定的一部分（见 [BackgroundWork]）。
   */
  val shells: Map<String, ShellJob> = emptyMap(),
  /** 存在未读已完成/失败时，灵动岛点击应跳转的会话 id。 */
  val summaryTargetId: String? = null,
  /** Unambiguous target used by the aggregate notification. */
  val summaryTarget: SessionTarget? = null,
  /** Per-server read-only snapshots backing all-server home/status projection. */
  val allServerSnapshots: Map<String, ServerOverviewSnapshot> = emptyMap(),
  val allSessionStatuses: Map<String, SessionStatus> = emptyMap(),
  val allUnseenSessions: Set<String> = emptySet(),
  /** Home is projecting all saved servers instead of the writable single-server connection. */
  val allServers: Boolean = false,
  /** Home filter; the writable conversation's serverId can differ without changing this. */
  val homeServerId: String? = null,
  val permissionPolicy: PermissionPolicy = PermissionPolicy(),
  val autoApprovalFailures: Set<String> = emptySet()
) {
  val server: ServerProfile? get() = profiles.firstOrNull { it.id == serverId }
  val project: Project? get() = projects.firstOrNull { it.id == projectId }
  val session: Session? get() = sessions.firstOrNull { it.id == sessionId }
  val executionDirectory: String? get() = session?.directory ?: project?.directory
  /** 首页当前服务器可用的执行目录：项目 → 最近会话。用于未打开会话时也能按首页选中的服务器加载模型目录。 */
  val homeDirectory: String? get() = project?.directory ?: sessions.firstOrNull()?.directory
  val attachments: List<LocalAttachment> get() = pendingAttachments[attachmentKey(sessionId)].orEmpty()
  val visibleModels: List<ModelInfo> get() = ModelVisibility.visibleConfigured(modelCatalog, modelOverrides, modelVisibilityConfigured)
  fun pending(action: String): Boolean = pendingOperations.contains(operationKey(serverId, sessionId, projectId, action))
  fun resource(name: String): ResourceStatus = resources[name] ?: ResourceStatus()
  fun title(session: Session): String = session.displayTitle()
  val parents: Map<String, String> get() = knownParents + sessions.mapNotNull { it.parentId?.let { parent -> it.id to parent } }.toMap()
  /** Live (running / waiting) state of each session family, rolled up to its root. */
  val presentedTasks: Map<String, TaskState> get() = permissionPolicy.tasks(tasks, permissions, autoApprovalFailures)
  val visiblePermissions: List<PermissionRequest> get() = permissions.filter { permissionPolicy.visible(it.id, autoApprovalFailures) }
  val globalSummary: TaskSummary get() = serverSnapshots().values.map { it.displaySummary(permissionPolicy) }.let { summaries ->
    if (summaries.isEmpty()) summary else TaskSummary.merge(summaries)
  }
  val globalSummaryTarget: SessionTarget? get() = globalSummary.items.firstOrNull()?.let { item -> item.serverId?.let { SessionTarget(it, item.sessionId) } }
  val activeRootTasks: Map<String, TaskState> get() = TaskSummary.aggregate(presentedTasks.filterValues { it.active }, parents)
  fun sessionStatus(session: Session, active: Map<String, TaskState> = activeRootTasks): SessionStatus =
    if (allServers && serverId == null) allSessionStatuses[session.key] ?: SessionStatus.NONE
    else sessionStatus(active[session.id], notices.unseenFor(session.id), session.id in backgroundRunning)
}

class LagoonController private constructor(private val appContext: Context) {
  companion object {
    private val TERMINAL_PHASES = setOf(TaskPhase.COMPLETED, TaskPhase.FAILED)
    /** Keep the live stream briefly after leaving the app so quick app switches do not reconnect. */
    private const val BACKGROUND_GRACE_MILLIS = 30_000L
    /** 总览读完为非活跃后，先等这么久复核再拆岛，避免多步任务步骤间隙导致息屏超级岛反复重弹。 */
    private const val SUMMARY_SETTLE_MILLIS = 4_000L
    /** 进入“待处理”后先等这么久再发通知：权限/表单瞬时出现又消失时不打扰用户。 */
    private const val ATTENTION_DEBOUNCE_MILLIS = 1_500L
    @Volatile private var instance: LagoonController? = null
    fun get(context: Context): LagoonController = instance ?: synchronized(this) {
      instance ?: LagoonController(context.applicationContext).also { instance = it }
    }
    /** Upper bound of running sessions whose pending permissions are read in one reconciliation. */
    private const val MAX_REQUEST_SESSIONS = 24
    /** Extra pages the initial load may pull so the first screen starts on a whole turn (official behaviour). */
    private const val MAX_INITIAL_PAGES = 5
    /** 一次对账最多读取多少个目录的 shell 列表（首页项目优先，其次是最近活跃会话的目录）。 */
    private const val MAX_SHELL_DIRECTORIES = 8
    /** shell 归属会话不在目录页时，最多补读多少个会话身份来解析父链。 */
    private const val MAX_SHELL_SESSIONS = 12
    /** 对账时为仍活跃的根会话补拉后代，避免事件丢失后只剩根而丢掉父子关系。 */
    private const val MAX_BACKGROUND_CHILD_ROOTS = 24
    private const val MAX_BACKGROUND_CHILD_SESSIONS = 100
    private const val MAX_BACKGROUND_CHILD_DEPTH = 8
    /** 后台工作结束后等待服务端唤醒的宽限期；超时仍无新执行才按会话结局收尾。 */
    private const val BACKGROUND_SETTLE_MILLIS = 10_000L
    /** 目录读取失败时旧 shell 条目的最长保留时间，避免事件丢失后“后台运行中”僵死。 */
    private const val SHELL_TTL_MILLIS = 5 * 60_000L
  }
  private fun scoped(serverId: String, session: Session): Session = session.copy(serverId = serverId)

  /**
   * Recomputes the server-wide island summary and persists live task state. Persisted running states are
   * what lets a later refresh — even after the process was frozen or killed — notice that a run this device
   * saw has ended and record it as an unread result.
   *
    * 后台 shell / 子代理折算成根会话的活跃任务（[BackgroundWork.fold]）后，首页、灵动岛、通知与前台
   * 服务用同一份 [tasks] 口径显示“后台运行中”；折算态若已无后台工作，安排一次宽限收尾。
   */
  private fun withSummary(state: LagoonState): LagoonState {
    // A tool can settle in the parent session while its metadata still says that a `task`/`subagent`
    // child is running. Reconstruct those children from the loaded transcript as the official client
    // does; this also preserves more than one concurrent background child instead of one boolean.
    val transcriptParent = state.sessionId
    val transcriptChildren = BackgroundWork.messageSubagents(transcriptParent, state.messages)
    val observed = if (transcriptChildren.isEmpty() || transcriptParent == null) state else {
      val tasks = LinkedHashMap(state.tasks)
      transcriptChildren.forEach { (child, created) ->
        if (tasks[child]?.active != true) tasks[child] = TaskState(child, TaskPhase.SUBAGENT, TaskState.RUNNING_DETAIL,
          since = created.takeIf { it > 0 } ?: System.currentTimeMillis(), activeAt = -1L)
      }
      state.copy(tasks = tasks, knownParents = state.knownParents + transcriptChildren.keys.associateWith { transcriptParent })
    }
    val folded = BackgroundWork.fold(observed.tasks, observed.shells, observed.parents)
    val next = if (folded === observed.tasks) observed else observed.copy(tasks = folded)
    next.serverId?.let { server -> next.tasks.values.forEach { store.rememberTask(server, it, next.parents[it.sessionId]) } }
    val work = BackgroundWork.workRoots(next.tasks, next.shells, next.parents)
    next.tasks.values.filter { it.active && it.background && it.sessionId !in work }.forEach { scheduleBackgroundSettle(it.sessionId) }
    val titles = next.sessions.associate { it.id to next.title(it) }
    val active = next.activeRootTasks
    // “后台运行中”= 根会话自身没有在跑，只有它派生的后台/子任务在跑（主线程在等后台任务），
    // 而不是“应用退到过后台”。后者会把所有正在运行的会话都误标成后台运行中。
    val background = TaskSummary.backgroundRoots(next.tasks, next.parents)
    val summary = TaskSummary.of(next.presentedTasks, next.notices, next.parents, titles, background,
      serverId = next.serverId, serverName = next.server?.name)
    val targetId = active.values.firstOrNull { it.phase in TaskState.WAITING_PHASES }?.sessionId
      ?: next.notices.filter { !it.viewed && it.sessionId !in active }.maxByOrNull { it.time }?.sessionId
      ?: summary.items.firstOrNull()?.sessionId
    return next.copy(
    summary = summary,
    backgroundRunning = background,
    // Prefer the session that needs a reply, then the newest unread result; otherwise the summary's
    // headline task, so tapping the island always lands somewhere, including when tasks are only running.
     summaryTargetId = targetId,
     summaryTarget = targetId?.let { next.serverId?.let { server -> SessionTarget(server, it) } }
   )
  }

  /**
   * 后台工作结束后没等到服务端唤醒时的兜底收尾（正常路径下后台完成会写合成消息并立刻唤醒会话，
   * 新的 `session.execution.started` 会覆盖折算态，这里不会触发）。按会话持久化的结局写未读结果，
   * 与对账路径的 `transitionNotice` 同一口径。
   */
  private fun scheduleBackgroundSettle(root: String) {
    if (backgroundSettles.containsKey(root)) return
    backgroundSettles[root] = scope.launch {
      delay(BACKGROUND_SETTLE_MILLIS)
      backgroundSettles.remove(root)
      settleBackgroundRun(root)
    }
  }

  private fun settleBackgroundRun(root: String) {
    val current = state.value
    val task = current.tasks[root] ?: return
    if (!task.active || !task.background) return
    if (BackgroundWork.hasBackgroundWork(root, current.tasks, current.shells, current.parents)) return
    val session = current.sessions.firstOrNull { it.id == root } ?: return
    val server = current.serverId ?: return
    mutable.update { previous ->
      val live = previous.tasks[root]
      if (live == null || !live.active || !live.background) previous
      else withSummary(previous.copy(tasks = previous.tasks + (root to TaskReducer.terminal(root, session.outcome, live))))
    }
    if (!state.value.notices.coversRun(root, task.since)) {
      session.transitionNotice(task.since)?.let { notice -> recordNotice(server, session, notice, task.since) }
    }
  }
  private val store = ServerStore(appContext)
  private val cache = OfflineCache(appContext)
  private val notifications = TaskNotifications(appContext)
  private val attachmentImporter = AttachmentImporter(appContext)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  // Writing operations that must survive the UI leaving the foreground (permission replies, aborts)
  // run here rather than in a composer-scoped coroutine.
  private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val initialAllServers = store.allServers()
  private val mutable = MutableStateFlow(LagoonState(profiles = store.profiles(), serverId = if (initialAllServers) store.selectedId() else store.homeServerId(), homeServerId = store.homeServerId(), allServers = initialAllServers, permissionPolicy = store.permissionPolicy()))
  val state: StateFlow<LagoonState> = mutable
  private val allSnapshots = mutableMapOf<String, ServerOverviewSnapshot>()
  private val allOverview = AllServersOverview(store, cache, scope, ::acceptAllSnapshot, { state.value.serverId },
    { id, event -> if (id == state.value.serverId) handleEvent(event) },
    { id -> if (id == state.value.serverId && api != null && connecting?.isActive != true) reloadConversation() })
  private var api: OpenCodeApi? = null
  private var stream: Job? = null
  private var generation = 0
  private var refresh: Job? = null
  private var eventRefresh: Job? = null
  private var messageRefresh: Job? = null
  private val sendMutex = Mutex()
  private val referenceMutex = Mutex()
  private var searchSequence = 0L
  private var fileSequence = 0L
  private var selectionRevision = 0L
  private var catalogSequence = 0L
  private var messageSequence = 0L
  private var reconcile: Job? = null
  /** 上一次真正下发到系统的岛内容指纹（服务器 + 落点 + 渲染后的文案），与瞬时的 detail 无关。 */
  private var lastSummary: Triple<String?, SessionTarget?, LiveUpdateContent>? = null
  /** 拆岛的延迟复核任务：瞬时非活跃不立即取消，避免息屏岛 / 胶囊反复消失与重弹。 */
  private var summarySettle: Job? = null
  private var visibleConversation: Pair<String, String>? = null
  private val seenEvents = linkedSetOf<String>()
  private val draftWrites = mutableMapOf<String, Job>()
  /** An activity is started (ON_START…ON_STOP). The live stream runs only while foreground or monitoring. */
  @Volatile private var foreground = false
  /** [TaskMonitorService] keeps the process alive for running tasks; the stream may then run in background. */
  @Volatile private var monitoring = false
  private var backgroundStop: Job? = null
  private var connecting: Job? = null
  @Volatile private var lastFullRefreshAt = 0L
  /** 自动刷新频率；界面切换后立即生效（对账循环每次迭代都会读取）。 */
  @Volatile private var refreshMode: RefreshMode = RefreshMode.FAST
  /** Root sessions already handed to [TaskMonitorService] during this run. */
  private val monitorRequested = mutableSetOf<String>()
  /** 待处理事项通知的去抖任务：进入等待态后短暂延迟再发，期间被处理就不打扰。 */
  private val attentionJobs = mutableMapOf<String, Job>()
  /** 后台工作结束后的宽限收尾任务，按根会话 id 去重（见 [scheduleBackgroundSettle]）。 */
  private val backgroundSettles = mutableMapOf<String, Job>()
  /** 等待态事件的单调序号：对账只在读取期间没有新的等待事件时才清除等待态，避免事件/对读竞态造成抖动。 */
  private var waitingEventSeq = 0L
  private val waitingEventSeqAt = mutableMapOf<String, Long>()
  private val resolvedPermissions = linkedSetOf<String>()

  init {
    store.migrateLocalProjects()
    refreshMode = store.refreshMode()
    // Single publisher for the server-wide island summary, so the system notification never drifts
    // from the in-app island: both read the same derived `summary` on every state emission.
    scope.launch { state.collect { state ->
      // Dedupe on the content the system actually renders, not on the transient task detail: a running
      // task changes detail on every tool step, and re-posting the same island each time makes some
      // systems replay their expand animation on the AOD island.
      val signature = Triple(TaskNotifications.ALL_SERVERS_ID, state.globalSummaryTarget, LiveUpdateContent.of(state.globalSummary))
      if (signature != lastSummary) { lastSummary = signature; publishSummary(state); ensureMonitoring(state) }
    } }
    allOverview.start(mutable.value.profiles)
    mutable.value.serverId?.takeIf { id -> mutable.value.profiles.any { it.id == id } }?.let { connect(it, preserveHome = true) }
    observeNetwork()
    scheduleBackgroundSync()
  }
  private fun scheduleBackgroundSync() {
    attempt { BackgroundSyncWorker.schedule(appContext, store.profiles().isNotEmpty()) }
      .onFailure { Diagnostics.warn("BackgroundSync", "无法安排后台定时刷新", it) }
  }

  /** Rebuilds the home/notification projection after one overview connection changes. */
  private fun acceptAllSnapshot(snapshot: ServerOverviewSnapshot) {
    val profiles = store.profiles()
    if (profiles.none { it.id == snapshot.serverId }) return
    val currentProfile = profiles.first { it.id == snapshot.serverId }
    val normalized = snapshot.copy(profile = currentProfile)
    val previous = allSnapshots.put(normalized.serverId, normalized)
    mutable.update { current ->
      withSummary(current.copy(profiles = profiles, allServerSnapshots = allSnapshots.toMap(),
        streamConnected = if (current.serverId == snapshot.serverId) snapshot.streamConnected else current.streamConnected,
        autoApprovalFailures = if (current.serverId == snapshot.serverId) snapshot.autoApprovalFailures else current.autoApprovalFailures))
    }
    // The conversation reducer publishes its own attention/results. Never have two owners.
    if (state.value.serverId == snapshot.serverId) {
      val failures = normalized.autoApprovalFailures - previous?.autoApprovalFailures.orEmpty()
      if (failures.isNotEmpty() && !state.value.permissionPolicy.hide) {
        mutable.update { it.copy(error = "自动处理失败，正在重试；可在对话中手动处理") }
        state.value.permissions.filter { it.id in failures }.forEach { notifyAttention(it.sessionId) }
      }
      return
    }

    // Clear obsolete attention before publishing a result which uses the same notification id.
    (previous?.permissions?.map { it.sessionId }.orEmpty() + previous?.questions?.map { it.sessionId }.orEmpty()).distinct().forEach { session ->
      if (normalized.questions.none { it.sessionId == session } && normalized.permissions.none { it.sessionId == session })
        notifications.cancel(normalized.serverId, session)
    }

    // The overview reader does not infer notifications from historical rows. Only rows that appeared
    // after this server was already being watched are forwarded to the ordinary per-session channel.
    val oldNoticeIds = previous?.notices?.map { it.id }?.toSet().orEmpty()
    normalized.notices.filter { it.id !in oldNoticeIds && !it.viewed }.forEach { notice ->
      val session = normalized.sessions.firstOrNull { it.id == notice.sessionId } ?: return@forEach
      val task = TaskState(session.id, if (notice.error) TaskPhase.FAILED else TaskPhase.COMPLETED,
        if (notice.error) "执行失败" else "任务已完成", notice.time, finishedAt = notice.time)
      notifications.show(normalized.profile, session, task)
    }
    val oldPermissionIds = previous?.permissions?.map { it.id }?.toSet().orEmpty()
    val oldQuestionIds = previous?.questions?.map { it.id }?.toSet().orEmpty()
    normalized.permissions.filter { it.id !in oldPermissionIds || it.id in normalized.autoApprovalFailures - previous?.autoApprovalFailures.orEmpty() }.forEach { request ->
      notifyOverviewAttention(normalized.serverId, request.sessionId)
    }
    normalized.questions.filter { it.id !in oldQuestionIds }.forEach { request ->
      notifyOverviewAttention(normalized.serverId, request.sessionId)
    }
  }
  /**
   * 下发服务器总览（实时更新）。真正开始时立即发布；一旦读到的不是 ACTIVE，先等一个短暂窗口复核，
   * 只有持续非活跃才拆岛。多步任务在步骤之间会瞬时变为 idle，立即拆岛会让息屏岛 / 胶囊反复消失与重弹。
   */
  private fun publishSummary(state: LagoonState) {
    val profile = TaskNotifications.ALL_PROFILE
    if (LiveUpdateContent.stageOf(state.globalSummary) == LiveUpdateStage.ACTIVE) {
      summarySettle?.cancel(); summarySettle = null
      notifications.showSummary(profile, state.globalSummary, state.globalSummaryTarget)
      return
    }
    summarySettle?.cancel()
    summarySettle = scope.launch {
      delay(SUMMARY_SETTLE_MILLIS)
      val current = this@LagoonController.state.value
      notifications.showSummary(profile, current.globalSummary, current.globalSummaryTarget)
    }
  }
  fun credentials(serverId: String): ServerCredentials = store.credentials(serverId)
  fun deviceId(): String = store.deviceId()
  fun currentRefreshMode(): RefreshMode = refreshMode
  fun setRefreshMode(mode: RefreshMode) {
    if (refreshMode == mode) return
    refreshMode = mode
    store.rememberRefreshMode(mode)
  }
  suspend fun testServer(profile: ServerProfile, credentials: ServerCredentials): String {
    val client = OpenCodeApi(profile, credentials)
    val version = client.health()
    client.projects()
    return version
  }
  fun clearError() = mutable.update { it.copy(error = null) }
  fun clearMessage() = mutable.update { it.copy(message = null) }

  fun setPermissionPolicy(policy: PermissionPolicy) {
    store.rememberPermissionPolicy(policy)
    mutable.update { withSummary(it.copy(permissionPolicy = policy)) }
    state.value.serverId?.let { allOverview.consider(it, state.value.permissions) }
    allOverview.policyChanged()
    state.value.serverSnapshots().values.forEach { snapshot ->
      snapshot.permissions.forEach { request ->
        if (!policy.visible(request.id, snapshot.autoApprovalFailures)) notifications.cancel(snapshot.serverId, request.sessionId)
      }
    }
  }

  /** Android connectivity is a recovery hint, not permission to rebuild every connection. */
  private fun observeNetwork() {
    attempt {
      val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
      manager.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
          scope.launch { allOverview.refreshAll(); if (!state.value.connected) reloadConversation() }
        }
      })
    }.onFailure { Diagnostics.warn("Network", "无法监听网络恢复", it) }
  }

  /** Changes only the home projection. All connections and the conversation remain alive. */
  fun setAllServers(enabled: Boolean) {
    if (enabled == state.value.allServers) return
    store.rememberAllServers(enabled)
    mutable.update { it.copy(allServers = enabled) }
    if (!enabled) state.value.homeServerId?.takeIf { it != state.value.serverId }?.let { connect(it) }
  }

  fun saveServer(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null, connect: Boolean = true) {
    store.save(profile, password, cookie, credentialUsername)
    mutable.update { it.copy(profiles = store.profiles()) }
    scheduleBackgroundSync()
    allOverview.start(store.profiles())
    if (connect) connect(profile.id, preserveHome = state.value.allServers)
  }
  fun deleteServer(id: String) {
    notifications.cancelSummary(id)
    if (mutable.value.serverId == id) {
      stopSingleConnection()
    }
    allSnapshots.remove(id)
    store.delete(id)
    cache.delete(id)
    scheduleBackgroundSync()

    allOverview.start(store.profiles())
    mutable.update { it.copy(profiles = store.profiles(), allServerSnapshots = allSnapshots.toMap(),
      homeServerId = it.homeServerId.takeUnless { selected -> selected == id }) }
    if (mutable.value.serverId == id) {
      val next = store.profiles().firstOrNull()?.id
      if (next != null) connect(next, preserveHome = true)
      else mutable.update { LagoonState(allServers = it.allServers, permissionPolicy = it.permissionPolicy) }
    }
  }
  private fun stopSingleConnection() {
    stream?.cancel()
    refresh?.cancel()
    eventRefresh?.cancel()
    messageRefresh?.cancel()
    reconcile?.cancel()
    connecting?.cancel()
    generation += 1
    api = null
  }
  fun connect(id: String, projectOverride: String? = null, sessionOverride: String? = null, preserveHome: Boolean = false) {
    val profile = store.profiles().firstOrNull { it.id == id } ?: return
    val previous = state.value
    val previousServer = previous.serverId
    // Hand the old server's control state back to its persistent observer, not to a disconnected cache.
    allSnapshots.putAll(previous.serverSnapshots())
    previousServer?.let { previous.serverSnapshots()[it]?.let(allOverview::handoff) }
    if (!preserveHome) { store.rememberAllServers(false); store.rememberHomeServer(id) }
    stopSingleConnection()
    val token = generation
    allOverview.start(store.profiles())
    api = allOverview.client(id)
    val rememberedProject = projectOverride ?: store.selectedProject(id)
    val rememberedSession = sessionOverride ?: store.selectedSession(id)
    val snapshot = allSnapshots[id]
    seenEvents.clear(); visibleConversation = null
    resolvedPermissions.clear()
    val configuration = rememberedSession?.let { store.configuration(id, it) } ?: SessionConfiguration()
    store.select(id, rememberedProject, rememberedSession)
    // Drop the pre-ledger “永久已完成” states and read markers; keep runs this device saw as still running.
    store.migrateTaskLedger(id)
    mutable.update { LagoonState(profiles = store.profiles(), serverId = id, loading = true, projectId = rememberedProject, sessionId = rememberedSession,
      allServers = preserveHome && previous.allServers, homeServerId = if (preserveHome) previous.homeServerId else id,
      allServerSnapshots = allSnapshots.toMap(), permissionPolicy = store.permissionPolicy(),
      sessions = snapshot?.sessions.orEmpty(), projects = snapshot?.projects ?: store.localProjects(id).map { it.copy(serverId = id) },
      permissions = snapshot?.permissions.orEmpty(), questions = snapshot?.questions.orEmpty(),
      autoApprovalFailures = snapshot?.autoApprovalFailures.orEmpty(), streamConnected = snapshot?.streamConnected == true,
      agent = configuration.agent, model = configuration.model, agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = rememberedSession?.let { store.draft(id, it) }.orEmpty(), references = rememberedSession?.let { store.references(id, it) }.orEmpty(),
      tasks = snapshot?.tasks ?: store.taskStates(id), knownParents = store.taskParents(id), notices = store.sessionNotices(id), collapsedSections = store.collapsedSections(id), pinned = store.pinnedSessions(id),
      modelOverrides = store.modelOverrides(id), modelVisibilityConfigured = store.modelVisibilityConfigured(id),
      modelVariants = store.modelVariants(id), recentModels = store.recentModels(id), scopeProjectId = store.scope(id)) }
    allOverview.selectionChanged(previousServer)
    connecting = scope.launch {
      try {
        loadAll(token)
        if (token == generation && streamAllowed()) {
          startStream(token)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (token != generation) return@launch
        showOffline(id, token, error.message ?: "连接失败")
        // A refresh while offline must be able to recover: retry the connection with backoff instead
        // of leaving the user on a stale snapshot with no path back to a live stream (A10).
        scheduleReconnect(id, token)
      }
    }
  }
  /** Opens immediately; loading the transcript does not change the originating home filter. */
  fun openConversation(target: SessionTarget) {
    if (target.serverId != state.value.serverId) {
      connect(target.serverId, sessionOverride = target.sessionId, preserveHome = true)
    } else if (state.value.sessions.any { it.id == target.sessionId }) selectSession(target.sessionId)
    else {
      mutable.update { it.copy(sessionId = target.sessionId, messages = emptyList(), resources = mapOf("messages" to ResourceStatus(ResourceState.LOADING))) }
      resolveSession(target.sessionId) { if (state.value.serverId == target.serverId && state.value.sessionId == target.sessionId) selectSession(target.sessionId) }
    }
  }
  private fun scheduleReconnect(id: String, token: Int, attempt: Int = 0) {
    if (token != generation || state.value.serverId != id) return
    val delayMillis = (2_000L shl attempt.coerceAtMost(4)).coerceAtMost(60_000L)
    scope.launch {
      delay(delayMillis)
      if (token == generation && state.value.serverId == id && !state.value.connected) reconnect(id, token, attempt)
    }
  }
  private suspend fun reconnect(id: String, token: Int, attempt: Int) {
    val profile = store.profiles().firstOrNull { it.id == id } ?: return
    api = allOverview.client(id)
    try {
      loadAll(token)
      if (token != generation) return
      if (streamAllowed()) startStream(token)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      if (token == generation) showOffline(id, token, "连接失败，正在重试")
      scheduleReconnect(id, token, attempt + 1)
    }
  }
  private suspend fun showOffline(id: String, token: Int, reason: String) {
    val snapshot = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.catalog(id) }
    if (token != generation || state.value.serverId != id) return
    if (snapshot == null) mutable.update { it.copy(loading = false, connected = false, error = reason) }
    else {
      val (cachedProjects, cachedSessions) = snapshot
      val projects = cachedProjects.map { it.copy(serverId = id) }
      val sessions = cachedSessions.map { scoped(id, it) }
      val sessionId = store.selectedSession()?.takeIf { selected -> sessions.any { it.id == selected } }
      mutable.update { it.copy(loading = false, connected = false, cached = true, error = "离线缓存：$reason",
        projects = projects, sessions = sessions, projectId = store.selectedProject() ?: projects.firstOrNull()?.id,
        sessionId = sessionId, messages = emptyList(), catalogComplete = cache.catalogComplete(id)) }
      if (sessionId != null) {
        val messages = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(id, sessionId) }
        if (token == generation && state.value.serverId == id && state.value.sessionId == sessionId) mutable.update { withSummary(it.copy(messages = messages, cacheComplete = cache.messagesComplete(id, sessionId), resources = mapOf("messages" to ResourceStatus(ResourceState.STALE, "离线缓存")))) }
      }
    }
  }
  private suspend fun loadAll(token: Int, controlOnly: Boolean = false, followUp: Boolean = true) {
    val client = api ?: return
    val requestSequence = ++catalogSequence
    // 读取开始时的等待态事件序号：读取期间到达的 permission/form 事件不能被这次（更早的）读取结果清除。
    val readWaitingSeq = waitingEventSeq
    // 本设备认为仍在运行的会话；读取后用于判断哪些需要重新拉取会话信息（取得权威 outcome/time.idle）。
    val previousActiveIds = state.value.tasks.filterValues { it.active }.keys.toSet()
    val version = client.health()
    val capabilities = client.discoverCapabilities()
    if (token != generation) return
    val serverId = state.value.serverId ?: return
    // The project list is device-local: the user registers a server directory, and the server's own
    // list is never auto-imported. A control-only refresh keeps the current list to avoid flicker.
    val projects = if (controlOnly && state.value.projects.isNotEmpty()) state.value.projects else store.localProjects(serverId).map { it.copy(serverId = serverId) }
    // Official home index: one global, newest-first listing of root sessions (directory filters are
    // exact, so per-project queries would miss worktrees). Reconciliation re-reads its first page too,
    // so a session created elsewhere while an event was missed still appears.
    val page = client.rootSessionsPage()
    val sessions = page.items.map { scoped(serverId, it) }
    // Activity is authoritative from `/api/session/active`; a failed read keeps last-known tasks.
    val activeResult = attempt { client.activeSessions() }
    val active = activeResult.getOrNull()
    if (token != generation || requestSequence != catalogSequence) return
    // Active sessions (often subagent children) may be older than the first page. Keep their identity and parent chain.
    val missingIds = (active.orEmpty() + listOfNotNull(state.value.sessionId, store.selectedSession(serverId)))
      .filter { id -> sessions.none { it.id == id } && state.value.sessions.none { it.id == id } }.distinct()
    // 本设备认为仍在运行、但既不在前台活跃集合、也不在本页目录里的会话：重新读取其会话信息，取得
    // 服务端持久化的 outcome/time.idle，用来给漏掉终止事件的一轮做权威收尾（含转入后台的子代理）。
    val staleActiveIds = previousActiveIds.filter { id -> active.orEmpty().none { it == id } && sessions.none { it.id == id } }.distinct()
    val extraSessions = mutableListOf<Session>()
    (missingIds + staleActiveIds).distinct().take(100).chunked(4).forEach { batch ->
      extraSessions += coroutineScope { batch.map { id -> async { attempt { findSession(client, id) }.getOrNull() } }.awaitAll().filterNotNull().map { scoped(serverId, it) } }
      if (token != generation || requestSequence != catalogSequence) return
    }
    // An active child may refer to a root outside the catalog page. Fetch the ancestry so both
    // pending requests and running indicators roll up to that root instead of leaking child rows.
    val identities = (state.value.sessions + sessions + extraSessions).associateBy { it.id }.toMutableMap()
    val attempted = mutableSetOf<String>()
    suspend fun fetchMissingParents(): Boolean {
      var rounds = 0
      while (rounds++ < 32) {
        val parents = identities.values.mapNotNull { it.parentId }.filter { it !in identities && attempted.add(it) }.distinct().take(100)
        if (parents.isEmpty()) return true
        for (batch in parents.chunked(4)) {
          val fetched = coroutineScope { batch.map { id -> async { attempt { findSession(client, id) }.getOrNull() } }.awaitAll().filterNotNull().map { scoped(serverId, it) } }
          fetched.forEach { identities[it.id] = it }; extraSessions += fetched
          if (token != generation || requestSequence != catalogSequence) return false
        }
      }
      return true
    }
    if (!fetchMissingParents()) return

    // `/api/session/active` and the event stream can both omit a child identity after a reconnect.
    // A persisted live root is enough to discover its descendants through the authoritative child
    // listing; those identities then supply the parent map used by the task reducer below.
    val knownParents = store.taskParents(serverId) + identities.values.mapNotNull { session ->
      session.parentId?.let { session.id to it }
    }
    val candidateIds = previousActiveIds + active.orEmpty() + state.value.backgroundRunning
    val candidateRoots = candidateIds.map { BackgroundWork.rootOf(it, knownParents) }.toSet()
    // A root present in the authoritative foreground set is already running itself; querying its
    // children would only add catalog traffic and cannot change the background classification.
    var childFrontier = identities.values.filter { it.id in candidateRoots && it.parentId == null && active?.contains(it.id) != true }
    val inspectedChildren = mutableSetOf<String>()
    var childBudget = MAX_BACKGROUND_CHILD_SESSIONS
    var childDepth = 0
    while (childFrontier.isNotEmpty() && childDepth++ < MAX_BACKGROUND_CHILD_DEPTH && childBudget > 0) {
      val batch = childFrontier.filter { inspectedChildren.add(it.id) }.take(MAX_BACKGROUND_CHILD_ROOTS)
      if (batch.isEmpty()) break
      val listed = coroutineScope { batch.map { session -> async { attempt { client.children(session) } } }.awaitAll() }
        .flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }
      if (token != generation || requestSequence != catalogSequence) return
      val additions = listed.filter { it.id.isNotBlank() && it.id !in identities }.take(childBudget)
      additions.forEach { identities[it.id] = it }
      extraSessions += additions
      childBudget -= additions.size
      childFrontier = listed.filter { it.id !in inspectedChildren }
    }
    if (!fetchMissingParents()) return
    // 运行中的 shell 作业：后台 shell 不是会话，不在 /api/session/active 里，冷启动/断线后只能从
    // `GET /api/shell` 读到。目录列表以本机项目优先，其次是最新活跃会话的执行目录（上限防请求风暴）。
    val shellDirectories = (projects.map { it.directory } +
      active.orEmpty().mapNotNull { identities[it]?.directory } +
      (sessions + extraSessions).map { it.directory })
      .filter(String::isNotBlank).distinctBy(::normalizedDirectory).take(MAX_SHELL_DIRECTORIES)
    val shellResults = coroutineScope { shellDirectories.map { directory -> async { directory to attempt { client.shells(directory) } } }.awaitAll() }
    if (token != generation || requestSequence != catalogSequence) return
    val refreshedShellDirectories = mutableSetOf<String>()
    val freshShells = linkedMapOf<String, ShellJob>()
    shellResults.forEach { (directory, result) ->
      val key = normalizedDirectory(directory)
      if (result.isSuccess) {
        refreshedShellDirectories += key
        result.getOrDefault(emptyList()).forEach { freshShells[it.id] = it }
      } else Diagnostics.warn("LagoonController", "shell 列表读取失败：$directory", result.exceptionOrNull())
    }
    // shell 归属的会话不在目录页时补读身份与父链，保证后台状态能汇总到正确的根会话。
    val unknownShellSessions = freshShells.values.map { it.sessionId }.filter { it !in identities }.distinct().take(MAX_SHELL_SESSIONS)
    if (unknownShellSessions.isNotEmpty()) {
      unknownShellSessions.chunked(4).forEach { batch ->
        val fetched = coroutineScope { batch.map { id -> async { attempt { findSession(client, id) }.getOrNull() } }.awaitAll().filterNotNull() }
        fetched.forEach { identities[it.id] = it }; extraSessions += fetched
        if (token != generation || requestSequence != catalogSequence) return
      }
    }
    // A shell owner may itself be a grandchild outside the catalog. Resolve its complete ancestry
    // after the shell-specific identity fetch as well as before it.
    if (!fetchMissingParents()) return
    // Pending requests, read like the official client: permissions per session, forms per location.
    // Only a running session can be waiting on the user.
    val waitingCandidates = (active.orEmpty() + state.value.permissions.map { it.sessionId } + listOfNotNull(state.value.sessionId))
      .distinct().mapNotNull { identities[it] }
    val permissionResults = coroutineScope { waitingCandidates.map { session -> async { session.id to attempt { client.sessionPermissions(session) } } }.awaitAll() }
    // Like the official desktop auto-approver, sweep known locations too: external clients can
    // park requests on idle sessions that are absent from session.active and not currently open.
    val sweepDirectories = if (store.permissionPolicy().autoApprove) (identities.values.map { it.directory } + projects.map { it.directory })
      .filter(String::isNotBlank).distinctBy(::normalizedDirectory) else emptyList()
    val sweptPermissions = sweepDirectories.chunked(4).flatMap { batch -> coroutineScope {
      batch.map { directory -> async { attempt { client.pendingPermissions(directory) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
    } }
    val formDirectories = waitingCandidates.map { it.directory }.filter(String::isNotBlank).distinctBy(::normalizedDirectory)
    val formResults = coroutineScope { formDirectories.map { directory -> async { directory to attempt { client.forms(directory) } } }.awaitAll() }
    if (token != generation || requestSequence != catalogSequence) return
    val failedPermissionSessions = permissionResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val failedFormDirs = formResults.filter { it.second.isFailure }.map { normalizedDirectory(it.first) }.toSet()
    val permissions = (permissionResults.mapNotNull { it.second.getOrNull() }.flatten() + sweptPermissions).distinctBy { it.id }
    val questions = formResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    store.rememberParents(serverId, sessions + extraSessions)
    val degraded = activeResult.isFailure || failedPermissionSessions.isNotEmpty() || failedFormDirs.isNotEmpty()
    if (degraded) Diagnostics.warn("LagoonController", "部分状态读取失败，保留上次可信状态", activeResult.exceptionOrNull())

    val storedTasks = store.taskStates(serverId)
    var finishedRuns = emptyList<Pair<Session, Long>>()
    // 本次读取前已知的待处理项，用于识别事件流可能漏掉的新权限/表单。
    val priorRequestIds = (state.value.permissions.map { it.id } + state.value.questions.map { it.id }).toSet()
    mutable.update { previous ->
      val now = System.currentTimeMillis()
      // shell 合并：本次成功读取的目录以服务端结果为准；读取失败的目录保留旧条目（TTL 防僵死）。
      val keptShells = previous.shells.filter { (_, job) ->
        normalizedDirectory(job.directory) !in refreshedShellDirectories && now - job.observedAt < SHELL_TTL_MILLIS
      }
      val nextShells = keptShells + freshShells.mapValues { (_, job) -> job.copy(observedAt = now) }
      val previousTasks = previous.tasks + storedTasks.filter { (id, task) -> previous.tasks[id]?.let { old -> task.since > old.since || (task.finishedAt ?: 0) > (old.finishedAt ?: 0) } ?: true }
      val nextSessions = (sessions + extraSessions + previous.sessions.filter {
        // A session this device saw running, or one with an unread result, never silently drops out;
        // neither do rows loaded through “加载更多” while the catalog has more pages.
        it.id in active.orEmpty() || it.id == previous.sessionId || previousTasks[it.id]?.active == true ||
          previous.notices.unseenFor(it.id).isNotEmpty() || page.next != null
        }).map { scoped(serverId, it) }.distinctBy { it.id }.sortedWith(sessionActivityOrder)
      val parentMap = store.taskParents(serverId) + nextSessions.mapNotNull { s -> s.parentId?.let { s.id to it } }
      // Only live state is carried; finished runs live in the unread ledger, never as a permanent phase.
      val states = mutableMapOf<String, TaskState>()
      // 先子后父：父会话判断“子树是否还有后台工作”时，必须看到子会话本次是否已收尾。
      nextSessions.sortedByDescending { BackgroundWork.depth(it.id, parentMap) }.forEach { session ->
        val task = previousTasks[session.id]
        // 已处理会话的新状态覆盖旧值；未处理的会话仍按上次状态保守看待。
        val effectiveTasks = previousTasks + states
        // 本次权威读取里，该会话自身子树是否仍有后台工作（后台 shell / 活跃子代理）。
        val hasWork = BackgroundWork.hasBackgroundWork(session.id, effectiveTasks, nextShells, parentMap)
        val next = when {
          active == null -> task
          session.id in active -> TaskReducer.status(session.id, true, task, session.idle)
          // 折算出的后台任务：权威读取确认后台工作已结束（子代理/ shell 都不在了）时收尾；否则保留
          // “后台运行中”。SSE 路径的折算态不在这里收尾，交给宽限任务等唤醒（settleBackgroundRun）。
          task?.active == true && task.background == true && !hasWork ->
            TaskReducer.terminal(session.id, session.outcome, task)
          // 根执行可能因事件丢失仍保留旧的前台 active 状态；只要权威 active 列表已排除它、
          // 且同一子树仍有后台工作，就必须先转成折算态，不能让旧根状态遮住后台任务。
          task?.active == true && !task.background && session.id !in active && hasWork &&
            task.phase in TaskState.RUNNING_PHASES ->
            TaskState(session.id, TaskPhase.THINKING, TaskState.BACKGROUND_DETAIL, task.since,
              activeAt = task.activeAt, background = true)
          // 不在前台活跃集合（/api/session/active）不等于本轮结束：转入后台的阻塞工具/子代理会让会话
          // 继续存活。只有服务端持久化的 time.idle 越过本轮基线、且自身子树没有后台工作时才权威收尾。
          task?.active == true && task.background != true && task.activeAt >= 0 && session.idle > 0 && session.idle > task.activeAt && !hasWork ->
            TaskReducer.terminal(session.id, session.outcome, task)
          task?.active == true -> task
          // 非活跃的任务不再作为常驻相位保留：终态结果只存在于未读账本（notices）。
          else -> null
        }
        if (next != null && next.phase != TaskPhase.IDLE) states[session.id] = next
      }
      // A successful read is authoritative; a failed one keeps the previous requests so a pending
      // approval is never silently dropped.
      val readIds = waitingCandidates.map { it.id }.toSet()
      val changedWhileReading = waitingEventSeqAt.filterValues { it > readWaitingSeq }.keys
      val keptPermissions = previous.permissions.filter { it.sessionId !in readIds || it.sessionId in failedPermissionSessions || it.sessionId in changedWhileReading }
      val fetchedPermissions = permissions.filterNot { it.sessionId in changedWhileReading || it.id in resolvedPermissions }
      val nextPermissions = allOverview?.pending(serverId, (fetchedPermissions + keptPermissions).distinctBy { it.id })
        ?: (fetchedPermissions + keptPermissions).distinctBy { it.id }
      val keptQuestions = previous.questions.filter { active == null || normalizedDirectory(it.directory) in failedFormDirs }
      val nextQuestions = (questions + keptQuestions).distinctBy { it.id }
      fun waitingState(sessionId: String, phase: TaskPhase, detail: String): TaskState {
        val previousTask = previousTasks[sessionId]
        return TaskState(sessionId, phase, detail, previousTask?.since ?: System.currentTimeMillis(),
          activeAt = previousTask?.activeAt ?: -1L, background = previousTask?.background == true)
      }
      nextPermissions.forEach { states[it.sessionId] = waitingState(it.sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认") }
      nextQuestions.forEach { states[it.sessionId] = waitingState(it.sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答") }
      // 权威清除等待态：只有本次成功读取过该会话对应的权限/表单、且确认没有待处理项时才结束等待。
      // 读取失败、该会话没被读取、或读取期间又来了新的等待事件时一律保留，避免“等待输入 ↔ 运行中”的假切换（A04）。
      val readPermissionIds = waitingCandidates.map { it.id }.filterNot { it in failedPermissionSessions }.toSet()
      val readFormDirs = formDirectories.map(::normalizedDirectory).filterNot { it in failedFormDirs }.toSet()
      val resolvedWaiting = states.values.filter { it.phase in TaskState.WAITING_PHASES }.mapNotNull { task ->
        val session = nextSessions.firstOrNull { it.id == task.sessionId } ?: return@mapNotNull null
        if ((waitingEventSeqAt[task.sessionId] ?: 0L) > readWaitingSeq) return@mapNotNull null
        val resolved = when (task.phase) {
          TaskPhase.WAITING_PERMISSION -> task.sessionId in readPermissionIds && nextPermissions.none { it.sessionId == task.sessionId }
          TaskPhase.WAITING_QUESTION -> normalizedDirectory(session.directory) in readFormDirs && nextQuestions.none { it.sessionId == task.sessionId }
          else -> false
        }
        task.sessionId.takeIf { resolved }
      }
      resolvedWaiting.forEach { id ->
        val waiting = states[id]!!
        if (active != null && id !in active && !BackgroundWork.hasBackgroundWork(id, states, nextShells, parentMap)) {
          states.remove(id)
          store.rememberTask(serverId, waiting.copy(phase = TaskPhase.IDLE, detail = ""), parentMap[id])
        }
        else states[id] = TaskState(id, TaskPhase.THINKING, TaskState.RUNNING_DETAIL, waiting.since, activeAt = waiting.activeAt, background = waiting.background)
      }
      // Roots whose family this device saw running and that an authoritative read now shows idle.
      val wasActive = TaskSummary.aggregate(previousTasks.filterValues { it.active }, parentMap)
      val isActive = TaskSummary.aggregate(states.filterValues { it.active }, parentMap)
      finishedRuns = if (active == null) emptyList() else nextSessions.filter { it.parentId == null && it.id in wasActive && it.id !in isActive }
        .map { it to wasActive.getValue(it.id).since }
      // Reconciliation keeps “加载更多” progress; a full refresh restarts paging from the first page.
      val cursors = if (controlOnly && previous.sessionCursors.isNotEmpty()) previous.sessionCursors.takeIf { page.next != null }.orEmpty()
        else listOfNotNull(page.next).associateBy { "" }
      withSummary(previous.copy(version = version, capabilities = capabilities,
        draftSessions = store.draftSessionIds(serverId), previews = nextSessions.associate { session -> session.id to (previous.previews[session.id] ?: store.sessionPreview(serverId, session.id)) }, connected = true, cached = previous.cached && previous.sessionId != null, loading = false,
        error = null, degraded = degraded, staleDirectories = emptySet(),
        projects = projects, sessions = nextSessions, knownParents = store.taskParents(serverId), tasks = states, shells = nextShells, notices = store.sessionNotices(serverId), collapsedSections = store.collapsedSections(serverId), sessionCursors = cursors, catalogComplete = cursors.isEmpty(),
        permissions = nextPermissions, questions = nextQuestions,
        projectId = (previous.projectId ?: store.selectedProject(serverId))?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id,
        scopeProjectId = previous.scopeProjectId?.takeIf { id -> projects.any { it.id == id } },
        sessionId = (previous.sessionId ?: store.selectedSession(serverId))?.takeIf { id -> nextSessions.any { it.id == id } }))
    }
    finishedRuns.forEach { (session, runSince) ->
      if (!state.value.notices.coversRun(session.id, runSince)) session.transitionNotice(runSince)?.let { recordNotice(serverId, session, it, runSince) }
    }
    // 权威读取发现的、事件流没送达的待处理事项也要通知（例如断线期间产生的权限/表单）。
    val newWaitingSessions = state.value.permissions.filter { it.id !in priorRequestIds }.map { it.sessionId } +
      state.value.questions.filter { it.id !in priorRequestIds }.map { it.sessionId }
    newWaitingSessions.distinct().forEach(::notifyAttention)
    allOverview?.consider(serverId, state.value.permissions)
    if (!controlOnly) lastFullRefreshAt = System.currentTimeMillis()
    val current = mutable.value
    current.serverId?.let { cache.saveCatalog(it, current.projects, current.sessions, complete = current.catalogComplete) }
    if (!followUp) return
    if (controlOnly) {
      current.session?.let { loadSession(it, token = token, ancillary = false) }
      return
    }
     // 未打开会话时也按首页选中的服务器加载模型目录（项目 → 最近会话目录），管理模型因此不再依赖会话。
     // 没有项目/会话时传空目录，让服务器按自己的默认工作区返回模型目录。
     loadChoices(current.homeDirectory.orEmpty(), token)
    current.session?.let { loadSession(it, token = token) }
  }
  fun loadMoreSessions() = act("sessions-more") { op ->
    val cursor = op.snapshot.sessionCursors[""] ?: return@act
    val page = op.client.rootSessionsPage(cursor = cursor)
    check(page.next != cursor) { "服务器返回了重复分页游标" }
    store.rememberParents(op.serverId, page.items)
    op.commitConnection { it.copy(knownParents = store.taskParents(op.serverId), sessions = (page.items.map { session -> scoped(op.serverId, session) } + it.sessions).distinctBy { s -> s.id }.sortedWith(sessionActivityOrder),
      sessionCursors = listOfNotNull(page.next).associateBy { "" }, catalogComplete = page.next == null) }
    if (op.connectionCurrent(this)) cache.saveCatalog(op.serverId, state.value.projects, state.value.sessions, complete = state.value.catalogComplete)
  }
  fun loadOlderMessages() = withSession("messages-more") { op, client, session ->
    val cursor = op.snapshot.messagesCursor ?: return@withSession
    val page = client.messagesPage(session.id, cursor, OpenCodeApi.HISTORY_PAGE)
    check(page.next != cursor) { "服务器返回了重复分页游标" }
    op.commit { it.copy(messages = (page.items + it.messages).distinctBy { m -> m.id }, messagesCursor = page.next,
      loadedOlderMessages = true, historyRevision = it.historyRevision + 1) }
  }
  fun reload(): Job {
    allOverview.refreshAll()
    return reloadConversation()
  }
  private fun reloadConversation(): Job {
    if (api == null || state.value.serverId == null) return scope.launch { }
    val token = generation
    refresh?.cancel()
    return scope.launch {
      try {
        loadAll(token)
        // A refresh after an offline start must also (re)establish the live event stream, otherwise
        // the UI shows connected=true with no realtime updates (A10).
        if (token == generation && stream?.isActive != true && streamAllowed()) startStream(token)
      } catch (error: Exception) {
        if (token == generation) mutable.update { state -> state.copy(error = error.message) }
      }
    }.also { refresh = it }
  }

  /** Live events need a visible app or the monitoring service; otherwise the socket would only go stale. */
  private fun streamAllowed() = true

  /** The app came to the foreground: re-read the server unless that just happened, and resume live events. */
  fun onForeground() {
    foreground = true
    backgroundStop?.cancel()
    allOverview.start(store.profiles())
    allOverview.refreshAll()
    ensureMonitoring(state.value)
    val server = state.value.serverId ?: return
    if (api == null || connecting?.isActive == true || refresh?.isActive == true) return
    when {
      !state.value.connected -> reload()
      System.currentTimeMillis() - lastFullRefreshAt > refreshMode.foregroundRefreshMs -> reload()
      stream?.isActive != true && state.value.serverId == server -> startStream(generation)
    }
    ensureMonitoring(state.value)
  }

  /** The app left the foreground: keep live events only while the monitoring service runs. */
  fun onBackground() {
    foreground = false
    // A process-level subscription and its reconciliation are not owned by the visible Activity.
  }

  /** Called by [TaskMonitorService]: while it runs, the process (and the live stream) may stay up in background. */
  fun setMonitoring(active: Boolean) {
    monitoring = active
    if (active && stream?.isActive != true && state.value.connected) startStream(generation)
    if (!active) monitorRequested.remove(TaskNotifications.ALL_SERVERS_ID)
  }

  private fun stopStream() {
    stream?.cancel(); reconcile?.cancel()
    // This stops only the conversation's supplemental reads, never the shared server subscriptions.
  }

  /**
   * Periodic background check (WorkManager): one authoritative read without opening the live stream.
   * A run this device saw that has since ended becomes an unread result and a completion notification.
   */
  suspend fun backgroundSync() {
    allOverview.refreshAll()
    if (foreground || monitoring && stream?.isActive == true) return
    connecting?.takeIf { it.isActive }?.join()
    if (state.value.serverId == null || api == null) return
    if (System.currentTimeMillis() - lastFullRefreshAt < refreshMode.foregroundRefreshMs) return
    val token = generation
    attempt { loadAll(token, followUp = false) }.onFailure { Diagnostics.warn("BackgroundSync", "后台同步失败", it) }
  }

  /**
   * Running tasks — from this phone or any other client — are monitored while they run, so their
   * results arrive in real time even after the app is backgrounded. A foreground service can only be
   * started from the foreground, so this runs on state changes while the app is visible.
   */
  private fun ensureMonitoring(state: LagoonState) {
      val active = LiveUpdateContent.stageOf(state.globalSummary) == LiveUpdateStage.ACTIVE
      if (foreground && active && state.profiles.any { it.notifications } && TaskNotifications.ALL_SERVERS_ID !in monitorRequested) {
        monitorRequested += TaskNotifications.ALL_SERVERS_ID
        attempt { TaskMonitorService.startAll(appContext) }
          .onFailure { Diagnostics.warn("Monitor", "无法启动全部服务器任务监控", it) }
      }
      if (!active) monitorRequested.remove(TaskNotifications.ALL_SERVERS_ID)
  }
  private fun startStream(token: Int) {
    stream?.cancel()
    // AllServersOverview is the sole SSE owner. UI switches only replace this read loop.
    // Standing reconciliation: while the stream is nominally up, periodically re-read the control
    // plane (catalog/activity/permissions/forms). This converges state even if the server drops an
    // event without the client seeing a stream failure, so a missed permission or terminal state is
    // repaired rather than waiting for the user to notice (A04/A10).
    reconcile?.cancel()
    reconcile = scope.launch {
      while (isActive && token == generation) {
        // 有任务时加快对账、空闲时放慢：实时更新仍以事件流为主，这里只做兜底收敛。
        val interval = if (state.value.activeRootTasks.isNotEmpty()) refreshMode.activeReconcileMs else refreshMode.idleReconcileMs
        delay(interval)
        // Skip while a manual refresh is running or while the stream is already down: the stream
        // loop re-reads on every reconnect, so reconciling there would only fight the disconnected state.
        if (token != generation || refresh?.isActive == true) continue
        attempt { loadAll(token, controlOnly = true, followUp = false) }.onFailure { error ->
          Diagnostics.warn("LagoonController", "状态同步失败", error)
          if (token == generation) mutable.update { it.copy(degraded = true) }
        }
      }
    }
    stream = reconcile
  }

  /**
   * Applies one `/api/event` frame the way the official client does: targeted state updates and a
   * re-read of only the affected session, never a full catalog reload per event.
   */
  private fun handleEvent(event: ServerEvent) {
    val props = event.properties
    if (event.type == "permission.asked" && props.str("id") in resolvedPermissions) return
    val sessionId = props.sessionEventId(event.type)
    val known = mutable.value.sessions.firstOrNull { it.id == sessionId }
    val directory = known?.directory ?: event.directory.ifBlank { props.sessionInfo().toSession().directory }
    if (sessionId.isNotBlank()) {
      val before = mutable.value.tasks[sessionId]
      // 事件会话自身子树里是否还有会唤醒它的后台工作（后台 shell / 仍在执行的子代理）。
      // 用 sessionId 而不是根：子会话自己的 run settle 时，兄弟子任务不算它的后台工作。
      val backgroundWork = BackgroundWork.hasBackgroundWork(sessionId, mutable.value.tasks, mutable.value.shells, mutable.value.parents)
      var after = TaskReducer.event(sessionId, event.type, props, before, event.created, backgroundWork)
      if (after != null) {
        val resolved = props.str("requestID").ifBlank { props.str("id") }
        val pendingPermission = mutable.value.permissions.any { it.sessionId == sessionId && !(event.type == "permission.replied" && it.id == resolved) } || event.type == "permission.asked"
        val pendingQuestion = mutable.value.questions.any { it.sessionId == sessionId && !(event.type in setOf("form.replied", "form.cancelled") && it.id == resolved) } || event.type == "form.created"
        val pending = pendingPermission || pendingQuestion
        if (pending && after.phase !in TaskState.TERMINAL_PHASES) after = TaskState(sessionId,
          if (pendingPermission) TaskPhase.WAITING_PERMISSION else TaskPhase.WAITING_QUESTION,
          if (pendingPermission) "等待权限确认" else "等待你的回答", before?.since ?: System.currentTimeMillis(),
          activeAt = before?.activeAt ?: -1L, background = before?.background == true)
        val next = after
        val enteringTerminal = next.phase in TERMINAL_PHASES && before?.phase !in TERMINAL_PHASES
        mutable.update { current -> withSummary(current.copy(tasks = current.tasks + (sessionId to next))) }
        if (enteringTerminal && sessionId == state.value.sessionId && messageRefresh?.isActive != true) {
          state.value.session?.let { selected -> val token = generation; messageRefresh = scope.launch { delay(refreshMode.messageDebounceMs); loadSession(selected, ancillary = false, token = token) } }
        }
        // 运行中的任务不再单独发通知：通知中心只保留实时更新总览，结果由未读账本（recordNotice）通知，
        // 等待用户处理由 notifyAttention 通知；这样运行中的会话不会在通知中心堆积。
      }
    }
    when (event.type) {
      "permission.asked" -> {
        val request = props.toPermission(directory)
        if (request.id in resolvedPermissions) return
        waitingEventSeqAt[request.sessionId] = ++waitingEventSeq
        mutable.update { withSummary(it.copy(permissions = (it.permissions.filterNot { old -> old.id == request.id } + request))) }
        allOverview?.consider(state.value.serverId ?: return, listOf(request))
        notifyAttention(request.sessionId)
      }
      "form.created" -> {
        val request = props.obj("form").toForm(directory)
        waitingEventSeqAt[request.sessionId] = ++waitingEventSeq
        mutable.update { it.copy(questions = (it.questions.filterNot { old -> old.id == request.id } + request)) }
        notifyAttention(request.sessionId)
      }
      "permission.replied" -> {
        val requestId = props.str("requestID").ifBlank { props.str("id") }
        permissionResolved(sessionId, requestId)
        cancelAttention(sessionId)
      }
      "form.replied", "form.cancelled" -> {
        val requestId = props.str("id").ifBlank { props.str("requestID") }
        mutable.update { it.copy(questions = it.questions.filterNot { old -> old.id == requestId }) }
        cancelAttention(sessionId)
      }
      "session.created" -> {
        val info = JSONObject(props.sessionInfo().toString()).put("id", sessionId)
        if (event.created > 0) {
          val time = info.obj("time")
          if (time.optLong("created", 0) <= 0) time.put("created", event.created)
          if (time.optLong("updated", 0) <= 0) time.put("updated", event.created)
          info.put("time", time)
        }
        val created = state.value.serverId?.let { server -> scoped(server, info.toSession()) } ?: info.toSession()
        if (created.id.isNotBlank()) {
          state.value.serverId?.let { store.rememberParents(it, listOf(created)) }
          mutable.update { current ->
            val parent = created.parentId
            val parents = if (parent == null) current.knownParents - created.id
            else current.knownParents + (created.id to parent)
            current.copy(knownParents = parents,
              sessions = (listOf(created) + current.sessions).distinctBy { item -> item.id }.sortedWith(sessionActivityOrder))
          }
        }
      }
      "session.renamed" -> updateSession(sessionId) { it.copy(title = props.str("title")) }
      "session.moved" -> updateSession(sessionId) { session ->
        val location = props.obj("location").str("directory")
        val subpath = props.str("subpath")
        session.copy(directory = if (subpath.isBlank()) location else "${location.trimEnd('/')}/${subpath.trim('/')}", projectId = props.str("projectID").ifBlank { session.projectId })
      }
      "session.viewed" -> updateSession(sessionId) { it.copy(viewed = maxOf(it.viewed, props.optLong("idle"))) }
      "session.revert.staged" -> updateSession(sessionId) { it.copy(revertMessageId = props.obj("revert").str("messageID").ifBlank { null }) }
      "session.revert.cleared", "session.revert.committed" -> updateSession(sessionId) { it.copy(revertMessageId = null) }
      "session.agent.selected", "session.model.selected" -> {
        updateSession(sessionId) { session ->
          if (event.type == "session.agent.selected") session.copy(agent = props.str("agent").ifBlank { session.agent })
          else session.copy(model = props.obj("model").toModelChoice() ?: session.model)
        }
        mutable.update { current ->
          if (sessionId != current.sessionId) current else current.copy(
            agent = if (event.type == "session.agent.selected" && !current.agentChanged) props.str("agent").ifBlank { current.agent } else current.agent,
            model = if (event.type == "session.model.selected" && !current.modelChanged) props.obj("model").toModelChoice() ?: current.model else current.model)
        }
      }
      "session.deleted" -> {
        attentionJobs.remove(sessionId)?.cancel()
        waitingEventSeqAt.remove(sessionId)
        state.value.serverId?.let { store.forgetSession(it, sessionId) }
        mutable.update { it.copy(sessions = it.sessions.filterNot { session -> session.id == sessionId }, notices = it.notices.filterNot { notice -> notice.sessionId == sessionId }) }
      }
      "project.updated" -> {
        val project = props.toProject()
        if (project.id.isNotBlank()) mutable.update { current -> current.copy(projects = current.projects.map { if (it.id == project.id) project.copy(sandboxes = project.sandboxes.ifEmpty { it.sandboxes }) else it }) }
      }
      // 后台 shell 的实时增删：created 带完整 Shell.Info（`metadata.sessionID` 归属会话），
      // exited/deleted 只带 id。折算与宽限收尾由 withSummary / scheduleBackgroundSettle 处理。
      "shell.created" -> props.obj("info").toShellJob(event.directory)?.let { job ->
        mutable.update { withSummary(it.copy(shells = it.shells + (job.id to job))) }
      }
      "shell.exited", "shell.deleted" -> {
        val shellId = props.str("id")
        if (shellId.isNotBlank() && mutable.value.shells.containsKey(shellId)) mutable.update { withSummary(it.copy(shells = it.shells - shellId)) }
      }
      // A run started or ended: official `session.sync` of that one session (outcome, idle, updated).
      "session.execution.started", "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" -> refreshSession(sessionId)
    }
    // The parent tool may settle before the child session emits a useful execution event. Official
    // metadata still identifies that child as running; keep every such child separately so sibling
    // background tasks are not collapsed into one last-seen boolean.
    val backgroundChild = BackgroundWork.backgroundSubagentId(event.type, props)
    if (sessionId.isNotBlank() && backgroundChild != null) {
      mutable.update { current ->
        val previous = current.tasks[backgroundChild]
        val child = if (previous?.active == true) previous else TaskState(backgroundChild, TaskPhase.SUBAGENT,
          TaskState.RUNNING_DETAIL, since = event.created.takeIf { it > 0 } ?: System.currentTimeMillis(), activeAt = -1L)
        withSummary(current.copy(tasks = current.tasks + (backgroundChild to child),
          knownParents = current.knownParents + (backgroundChild to sessionId)))
      }
    }
    if (event.type in setOf("session.execution.succeeded", "session.execution.failed") && !familyActive(sessionId)) recordResultNotice(event, sessionId)
    if (sessionId.isNotBlank() && sessionId == mutable.value.sessionId) {
      val projected = TranscriptProjection.apply(mutable.value.messages, event)
      if (projected != null && projected.messages !== mutable.value.messages) mutable.update { it.copy(messages = projected.messages, resources = it.resources + ("messages" to ResourceStatus(ResourceState.READY))) }
       if (projected?.reconcile == true && messageRefresh?.isActive != true) {
         mutable.value.session?.let { session ->
           val token = generation
           messageRefresh = scope.launch { delay(refreshMode.messageDebounceMs); loadSession(session, ancillary = false, token = token) }
         }
      }
    }
    // Apply this after transcript projection so the completion notice prevents the history-derived
    // fallback above from immediately recreating the child task.
    BackgroundWork.completedSubagentId(event.type, props)?.let { child ->
      mutable.update { current -> withSummary(current.copy(tasks = current.tasks - child)) }
    }
  }

  private fun updateSession(id: String, change: (Session) -> Session) {
    if (id.isBlank()) return
    mutable.update { current -> if (current.sessions.none { it.id == id }) current else current.copy(sessions = current.sessions.map { if (it.id == id) change(it) else it }.sortedWith(sessionActivityOrder)) }
  }

  /** Re-reads one session's info; an unknown session becomes known (e.g. a run started from another client). */
  private fun refreshSession(id: String) {
    if (id.isBlank()) return
    val client = api ?: return
    val token = generation
    scope.launch {
      val freshRaw = attempt { client.session(id) }.getOrNull() ?: return@launch
      val server = state.value.serverId ?: return@launch
      val fresh = scoped(server, freshRaw)
      if (token != generation) return@launch
      state.value.serverId?.let { store.rememberParents(it, listOf(fresh)) }
      mutable.update { current -> withSummary(current.copy(sessions = (listOf(fresh) + current.sessions).distinctBy { it.id }.sortedWith(sessionActivityOrder),
        knownParents = current.knownParents + listOfNotNull(fresh.parentId?.let { fresh.id to it }))) }
      // A child whose root is outside the catalog page: resolve the ancestry so its state rolls up.
      fresh.parentId?.takeIf { parent -> state.value.sessions.none { it.id == parent } }?.let(::refreshSession)
    }
  }

  /**
   * 一个会话所属根家族里是否仍有活跃任务（根自身或任一子孙）或运行中的后台 shell。后台化的阻塞
   * 工具/子代理会让根执行 settle 但家族仍活跃，此时不应记录完成结果、也不应通知，等后台完成重新
   * 唤醒 AI 后再收尾。
   */
  private fun familyActive(sessionId: String): Boolean {
    if (sessionId.isBlank()) return false
    val current = mutable.value
    val root = BackgroundWork.rootOf(sessionId, current.parents)
    return BackgroundWork.hasBackgroundWork(root, current.tasks, current.shells, current.parents) ||
      current.tasks[root]?.active == true
  }

  private fun recordResultNotice(event: ServerEvent, sessionId: String) {
    if (sessionId.isBlank()) return
    val current = state.value
    val server = current.serverId ?: return
    val token = generation
    val known = current.sessions.firstOrNull { it.id == sessionId }
    val runSince = current.tasks[sessionId]?.since ?: System.currentTimeMillis()
    fun record(session: Session) {
      if (generation != token || state.value.serverId != server) return
      event.resultNotice(session)?.let { recordNotice(server, session, it, runSince) }
    }
    if (known != null) record(known)
    else scope.launch {
      val client = api ?: return@launch
      attempt { findSession(client, sessionId) }.getOrNull()?.let(::record)
    }
  }

  /**
   * Adds one unread result to the ledger. As in the official client it is born viewed when the user is
   * looking at that conversation; otherwise it raises the completion/failure notification. A second
   * report of the same run (`session.idle` plus a V2 execution event, or event plus reconciliation)
   * only upgrades a success to a failure.
   */
  private fun recordNotice(server: String, session: Session, notice: SessionNotice, runSince: Long) {
    val existing = state.value.notices.filter { it.sessionId == session.id && it.time >= runSince }
    if (existing.any { it.error || !notice.error }) return
    val viewing = foreground && visibleConversation == (server to session.id)
    val notices = store.rememberNotice(server, notice.copy(viewed = viewing))
    mutable.update { if (it.serverId == server) withSummary(it.copy(notices = notices)) else it }
    if (viewing) return
    val profile = state.value.server?.takeIf { it.id == server && it.notifications } ?: return
    val result = TaskState(session.id, if (notice.error) TaskPhase.FAILED else TaskPhase.COMPLETED,
      if (notice.error) "执行失败" else "任务已完成", runSince, notice.time)
    attempt { notifications.show(profile, session, result) }
  }

  fun toggleSection(key: String) {
    val current = state.value; val server = current.serverId ?: return
    val collapsed = if (key in current.collapsedSections) current.collapsedSections - key else current.collapsedSections + key
    store.rememberCollapsedSections(server, collapsed)
    mutable.update { it.copy(collapsedSections = collapsed) }
  }
  fun togglePin(sessionId: String) {
    val current = state.value; val server = current.serverId ?: return
    val pinned = if (sessionId in current.pinned) current.pinned - sessionId else current.pinned + sessionId
    store.rememberPinnedSessions(server, pinned)
    mutable.update { it.copy(pinned = pinned) }
  }

  /**
   * 进入“待处理”后去抖再通知：权限/表单瞬时出现又消失（事件与对账竞态、另一客户端已处理）时不打扰。
   * 去抖窗口内请求被清除则什么都不发。
   */
  private fun notifyAttention(sessionId: String) {
    if (sessionId.isBlank()) return
    attentionJobs.remove(sessionId)?.cancel()
    val serverId = state.value.serverId
    val token = generation
    val client = api
    attentionJobs[sessionId] = scope.launch {
      delay(ATTENTION_DEBOUNCE_MILLIS)
      val current = mutable.value
      if (current.serverId != serverId) return@launch
      val profile = current.server ?: return@launch
      val session = current.sessions.firstOrNull { it.id == sessionId } ?: return@launch
      if (!profile.notifications) return@launch
      val task = current.presentedTasks[sessionId] ?: return@launch
      // 期间已被处理（事件或对账清除）则不再打扰。
      val stillWaiting = task.phase in TaskState.WAITING_PHASES &&
        (current.visiblePermissions.any { it.sessionId == sessionId } || current.questions.any { it.sessionId == sessionId })
      if (!stillWaiting) return@launch
      // Recheck the server, not just the debounce timer: another client may already have approved.
      val permission = current.visiblePermissions.firstOrNull { p -> p.sessionId == sessionId }
      if (permission != null) {
        val pending = attempt { client?.sessionPermissions(session) }.getOrNull() ?: return@launch
        if (generation != token || state.value.serverId != serverId) return@launch
        if (pending.none { it.id == permission.id }) { permissionResolved(sessionId, permission.id); return@launch }
      }
      if (permission != null && state.value.visiblePermissions.none { it.id == permission.id }) return@launch
      notifications.show(profile, session, task, permission)
    }
  }

  private fun notifyOverviewAttention(server: String, sessionId: String) {
    val key = scopedKey(server, sessionId)
    attentionJobs.remove(key)?.cancel()
    attentionJobs[key] = scope.launch {
      delay(ATTENTION_DEBOUNCE_MILLIS)
      val snapshot = allSnapshots[server] ?: return@launch
      if (state.value.serverId == server) return@launch
      val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return@launch
      val permission = snapshot.permissions.firstOrNull { it.sessionId == sessionId && state.value.permissionPolicy.visible(it.id, snapshot.autoApprovalFailures) }
      val question = snapshot.questions.firstOrNull { it.sessionId == sessionId }
      if (permission == null && question == null) return@launch
      if (permission != null) {
        val client = allOverview.client(server)
        val pending = attempt { client.sessionPermissions(session) }.getOrNull() ?: return@launch
        if (store.profiles().none { it.id == server } || state.value.serverId == server || allOverview.client(server) !== client) return@launch
        if (pending.none { it.id == permission.id }) { allOverview.settled(server, permission.id); allOverview.refresh(server); return@launch }
        if (!state.value.permissionPolicy.visible(permission.id, allSnapshots[server]?.autoApprovalFailures.orEmpty())) return@launch
      }
      notifications.show(snapshot.profile, session, TaskState(sessionId,
        if (permission != null) TaskPhase.WAITING_PERMISSION else TaskPhase.WAITING_QUESTION), permission)
    }
  }

  private fun permissionResolved(sessionId: String, requestId: String) {
    resolvedPermissions += requestId
    while (resolvedPermissions.size > 1000) resolvedPermissions.remove(resolvedPermissions.first())
    waitingEventSeqAt[sessionId] = ++waitingEventSeq
    state.value.serverId?.let { allOverview?.settled(it, requestId) }
    mutable.update { current ->
      val pending = current.permissions.filterNot { it.id == requestId }
      val task = current.tasks[sessionId]
      val restored = if (task?.phase == TaskPhase.WAITING_PERMISSION && pending.none { it.sessionId == sessionId })
        task.copy(phase = if (current.questions.any { it.sessionId == sessionId }) TaskPhase.WAITING_QUESTION else TaskPhase.THINKING,
          detail = if (current.questions.any { it.sessionId == sessionId }) "等待你的回答" else TaskState.RUNNING_DETAIL)
      else task
      withSummary(current.copy(permissions = pending, tasks = if (restored == null) current.tasks else current.tasks + (sessionId to restored)))
    }
  }

  /** 请求被处理/清除后取消待处理通知：会话已无任何待处理项时才撤下。 */
  private fun cancelAttention(sessionId: String) {
    if (sessionId.isBlank()) return
    attentionJobs.remove(sessionId)?.cancel()
    val current = mutable.value
    val server = current.serverId ?: return
    if (current.permissions.any { it.sessionId == sessionId } || current.questions.any { it.sessionId == sessionId }) return
    attempt { notifications.cancel(server, sessionId) }
  }
  fun selectProject(id: String) {
    val project = mutable.value.projects.firstOrNull { it.id == id } ?: return
    selectionRevision += 1
    visibleConversation = null
    mutable.update { it.copy(projectId = id, sessionId = null, messages = emptyList(), agent = null, model = null,
      agentChanged = false, modelChanged = false, draft = "", references = emptyList(), resources = emptyMap(),
      files = emptyList(), searchResults = emptyList(), fileText = null, fileBinary = false) }
    store.rememberLocation(id, null)
    val token = generation
    scope.launch { loadChoices(project.directory, token) }
  }
  /**
   * Applies the phone's per-model variant selection without ever inventing a variant.  A stale
   * persisted id is removed when the server no longer advertises it, which makes the request fall
   * back to the server default on the next send.
   */
  private fun normalizeModelVariant(serverId: String, model: ModelChoice, catalog: List<ModelInfo>): ModelChoice {
    val info = catalog.firstOrNull { it.key == modelKey(model) } ?: return model
    val ids = info.variants.map { it.id }.toSet()
    val stored = store.modelVariants(serverId)[info.key]
    val selected = when {
      stored != null && stored in ids -> stored
      stored != null -> {
        store.rememberModelVariant(serverId, info.key, null)
        null
      }
      model.variant != null && model.variant in ids -> {
        // A model read from an existing server session is the same source of truth the official
        // client uses to initialize its local variant map.
        store.rememberModelVariant(serverId, info.key, model.variant)
        model.variant
      }
      else -> null
    }
    return info.choice.copy(variant = selected)
  }
  private suspend fun loadChoices(directory: String, token: Int = generation) {
    val client = api ?: return
    // Agents, models and commands are independent; one parallel round instead of three.
    val revision = selectionRevision
    val (agents, models, commands) = coroutineScope {
      val agentsTask = async { attempt { client.agents(directory) } }
      val modelsTask = async { attempt { client.modelCatalog(directory) } }
      val commandsTask = async { attempt { client.commands(directory) } }
      Triple(agentsTask.await(), modelsTask.await(), commandsTask.await())
    }
    val expectedDirectory = mutable.value.executionDirectory ?: mutable.value.homeDirectory
    if (token == generation && revision == selectionRevision && (expectedDirectory == null || expectedDirectory == directory)) {
      mutable.update { current ->
        val catalog = models.getOrDefault(current.modelCatalog)
        val serverId = current.serverId
        val model = if (serverId == null) current.model else current.model?.let { normalizeModelVariant(serverId, it, catalog) }
        current.copy(agents = agents.getOrDefault(current.agents), models = catalog.map { it.choice }, modelCatalog = catalog,
          model = model, modelChanged = current.modelChanged || model?.variant != current.model?.variant,
          modelVariants = serverId?.let(store::modelVariants) ?: current.modelVariants, commands = commands.getOrDefault(current.commands),
          resources = current.resources + mapOf("agents" to agents.resourceStatus(), "models" to models.resourceStatus(), "commands" to commands.resourceStatus()))
      }
    }
  }
  /** Resolve a notification/child target independently of the current catalog page. */
  fun resolveSession(id: String, onResolved: (List<String>) -> Unit): Job = act("open:$id") { op ->
    val known = op.snapshot.sessions.associateBy { it.id }.toMutableMap()
    val seen = linkedSetOf<String>(); val lineage = mutableListOf<String>()
    var next: String? = id
    while (next != null && seen.add(next) && lineage.size < 32) {
      val target = next
      val session = known[target] ?: findSession(op.client, target)?.let { scoped(op.serverId, it) }
      if (session == null) {
        check(lineage.isNotEmpty()) { "未找到这个会话，请刷新后重试" }
        break
      }
      known[target] = session; lineage += target
      next = session.parentId
    }
    if (!op.isCurrent(this)) return@act
    store.rememberParents(op.serverId, known.values.toList())
    op.commit { withSummary(it.copy(sessions = known.values.sortedWith(sessionActivityOrder), knownParents = store.taskParents(op.serverId))) }
    onResolved(lineage.asReversed())
  }
  /** Null when the server no longer has the session. */
  private suspend fun findSession(client: OpenCodeApi, id: String): Session? = try { client.session(id).takeIf { it.id == id } }
    catch (error: ApiException) { if (error.status == 404) null else throw error }
  fun selectSession(id: String) {
    val session = mutable.value.sessions.firstOrNull { it.id == id } ?: return
    selectionRevision += 1
    val project = resolveSessionProject(session, mutable.value.projects)
    val serverId = state.value.serverId ?: return
    val configuration = store.configuration(serverId, id)
    val draft = store.draft(serverId, id)
    visibleConversation = null
    val offline = mutable.value.cached && !mutable.value.connected
    val messages = emptyList<Message>()
    mutable.update { withSummary(it.copy(projectId = project?.id, sessionId = id, messages = messages,
      agent = configuration.agent ?: session.agent, model = configuration.model ?: session.model,
      agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = draft, references = store.references(serverId, id), loadedOlderMessages = false, messagesCursor = null, resources = mapOf("messages" to ResourceStatus(ResourceState.LOADING)),
      children = emptyList(), changes = emptyList(), files = emptyList(),
      searchResults = emptyList(), fileText = null, fileBinary = false)) }
    store.rememberLocation(project?.id, id)
    if (offline) {
      val serverId = state.value.serverId ?: return
      val revision = selectionRevision
      scope.launch {
        val cachedMessages = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(serverId, id) }
        if (state.value.serverId == serverId && selectionRevision == revision) mutable.update { it.copy(messages = cachedMessages, cacheComplete = false,
          resources = it.resources + ("messages" to ResourceStatus(if (cachedMessages.isEmpty()) ResourceState.ERROR else ResourceState.STALE, "离线缓存"))) }
      }
    } else {
      val token = generation
      // Choices and the session transcript are independent; run them concurrently.
      scope.launch { coroutineScope { launch { loadChoices(session.directory, token) }; loadSession(session, token = token) } }
    }
  }
  private suspend fun loadSession(session: Session, ancillary: Boolean = true, token: Int = generation, client: OpenCodeApi? = api) {
    val client = client ?: return
    val serverId = state.value.serverId ?: return
    val revision = selectionRevision
    val requestSequence = ++messageSequence
    fun current() = token == generation && revision == selectionRevision && state.value.sessionId == session.id && state.value.executionDirectory == session.directory && requestSequence == messageSequence
    if (current() && state.value.messages.isEmpty()) mutable.update { it.copy(resources = it.resources + ("messages" to ResourceStatus(ResourceState.LOADING))) }
    val initialWindow = state.value.messages.isEmpty() && !state.value.loadedOlderMessages
    var nextCursor: String? = null
    // The newest page of the projected timeline plus admitted-but-undelivered input, like the official
    // client's message and pending syncs.
    val (result, pending) = coroutineScope {
      val page = async { attempt { client.messagesPage(session.id).let { nextCursor = it.next; it.items } } }
      val inbox = async { attempt { client.inbox(session.id) } }
      page.await() to inbox.await().getOrDefault(emptyList())
    }
    if (token != generation) return
    val previousMessages = state.value.messages
    val messages = result.map { first ->
      // Official initial window: keep pulling older pages until the oldest boundary is a whole turn,
      // so the first screen never starts mid-reply. Bounded so a refresh cannot burst unbounded.
      val fetched = if (!initialWindow) first else {
        val merged = first.toMutableList()
        var cursor = nextCursor
        var extra = 0
        while (cursor != null && extra < MAX_INITIAL_PAGES && needsOlderTurnRoot(merged) && current()) {
          val older = attempt { client.messagesPage(session.id, cursor, OpenCodeApi.MESSAGE_PAGE) }.getOrElse { break }
          if (older.items.isEmpty()) { nextCursor = older.next; break }
          merged.addAll(0, older.items)
          nextCursor = older.next
          cursor = older.next
          extra++
        }
        merged
      }
      // Merge by id so a tail refresh keeps older pages the reader already loaded and any
      // streaming/optimistic entry the server page has not caught up with yet.
      mergeTranscript(previousMessages.takeIf { current() }.orEmpty(), fetched, pending)
    }.getOrElse { error ->
      // Shown in place (“保留上次数据 · …”); a transcript read failure is not a global error toast.
      Diagnostics.warn("LagoonController", "会话消息读取失败", error)
      val fallback = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(serverId, session.id) }
      if (current()) mutable.update { it.copy(cached = fallback.isNotEmpty(), cacheComplete = false,
        resources = it.resources + ("messages" to ResourceStatus(if (fallback.isEmpty()) ResourceState.ERROR else ResourceState.STALE, error.message))) }
      if (fallback.isEmpty()) return else fallback
    }
    if (result.isSuccess) cache.saveMessages(serverId, session.id, messages, complete = nextCursor == null)
    if (!current()) return
    val preview = messages.sessionPreview()
    if (result.isSuccess) store.rememberPreview(serverId, session.id, preview)
    val latestAgent = messages.asReversed().firstNotNullOfOrNull { it.agent } ?: session.agent
    val latestModel = messages.asReversed().firstNotNullOfOrNull { it.model } ?: session.model
    mutable.update { withSummary(it.copy(messages = messages, cached = result.isFailure, messagesCursor = if (it.loadedOlderMessages) it.messagesCursor else nextCursor, cacheComplete = result.isSuccess || cache.messagesComplete(serverId, session.id),
      previews = it.previews + (session.id to preview),
      agent = if (it.agentChanged) it.agent else latestAgent,
      model = if (it.modelChanged) it.model else latestModel?.let { candidate ->
        if (it.modelCatalog.isEmpty()) candidate else normalizeModelVariant(serverId, candidate, it.modelCatalog)
      },
      modelChanged = it.modelChanged || (!it.modelChanged && latestModel != null && it.modelCatalog.isNotEmpty() &&
        normalizeModelVariant(serverId, latestModel, it.modelCatalog).variant != latestModel.variant),
       resources = it.resources + ("messages" to ResourceStatus(if (result.isSuccess) { if (messages.none { message -> message.isDisplayable }) ResourceState.EMPTY else ResourceState.READY } else ResourceState.STALE))) ) }
    // Opening a session never infers a result from its history: “已完成” exists only in the ledger.
    if (ancillary) {
      // Children and diff are independent; fetch in parallel (one round trip when connected).
      mutable.update { it.copy(resources = it.resources + listOf("children", "changes").associateWith { ResourceStatus(ResourceState.LOADING) }) }
      val (children, changes) = coroutineScope {
        val childrenTask = async { attempt { client.children(session) } }
        val changesTask = async { attempt { client.diff(session) } }
        childrenTask.await() to changesTask.await()
      }
       if (current()) mutable.update { it.copy(children = children.getOrDefault(it.children).map { child -> scoped(serverId, child) }, changes = changes.getOrDefault(it.changes),
        resources = it.resources + mapOf("children" to children.resourceStatus(), "changes" to changes.resourceStatus()),
         sessions = (it.sessions + children.getOrDefault(emptyList()).map { child -> scoped(serverId, child) }).distinctBy { item -> item.id }.sortedWith(sessionActivityOrder)) }
    }
  }
  fun chooseAgent(name: String?) {
    mutable.update { it.copy(agent = name ?: it.messages.asReversed().firstNotNullOfOrNull { message -> message.agent } ?: it.session?.agent, agentChanged = name != null) }; rememberConfiguration()
  }
  private fun selectedModel(model: ModelChoice): ModelChoice {
    val server = state.value.serverId ?: return model.copy(variant = null)
    val info = state.value.modelCatalog.firstOrNull { it.key == modelKey(model) } ?: return model.copy(variant = null)
    val stored = store.modelVariants(server)[info.key]
    val variant = stored?.takeIf { candidate -> info.variants.any { it.id == candidate } }
    if (stored != null && variant == null) store.rememberModelVariant(server, info.key, null)
    return info.choice.copy(variant = variant)
  }
  fun chooseModel(model: ModelChoice?) {
    val next = model?.let(::selectedModel) ?: state.value.messages.asReversed().firstNotNullOfOrNull { it.model } ?: state.value.session?.model
    mutable.update { it.copy(model = next, modelChanged = model != null) }; rememberConfiguration()
    val server = state.value.serverId ?: return
    if (model != null) {
      store.rememberRecentModel(server, modelKey(model))
      mutable.update { it.copy(recentModels = store.recentModels(server)) }
    }
  }
  /** Selects a server-advertised variant for the current model; null means the server default. */
  fun chooseVariant(variant: String?) {
    val current = state.value.model ?: return
    val server = state.value.serverId ?: return
    val info = state.value.modelCatalog.firstOrNull { it.key == modelKey(current) } ?: return
    val selected = variant?.takeIf { id -> info.variants.any { it.id == id } }
    store.rememberModelVariant(server, info.key, selected)
    mutable.update { it.copy(model = info.choice.copy(variant = selected), modelChanged = true, modelVariants = store.modelVariants(server)) }
    rememberConfiguration()
  }
  /** A switch from the phone's "管理模型" list; it overrides the official default rule for this server. */
  fun setModelVisible(model: ModelInfo, visible: Boolean) {
    val server = state.value.serverId ?: return
    val current = state.value
    // The first edit materializes the current all-visible catalog as an allow-list, so changing one
    // switch does not accidentally hide every other model.
    val baseline = if (current.modelVisibilityConfigured) current.modelOverrides
    else current.modelCatalog.associate { it.key to true }
    val next = baseline + (model.key to visible)
    store.rememberModelOverrides(server, next)
    mutable.update { it.copy(modelOverrides = next, modelVisibilityConfigured = true) }
  }
  /** Returns to the unconfigured state: every model currently offered by the server is visible. */
  fun resetModelVisibility() {
    val server = state.value.serverId ?: return
    store.clearModelVisibility(server)
    mutable.update { it.copy(modelOverrides = emptyMap(), modelVisibilityConfigured = false) }
  }
  /** Copies picked phone files into the cache off the main thread; each rejection is reported once. */
  fun addLocalAttachments(uris: List<android.net.Uri>) {
    if (uris.isEmpty() || state.value.pending("send")) return
    val server = state.value.serverId ?: return
    val key = attachmentKey(state.value.sessionId)
    operationScope.launch {
      val errors = mutableListOf<String>()
      for (uri in uris) {
        val pending = state.value.pendingAttachments[key].orEmpty().sumOf { it.size }
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { attachmentImporter.import(uri, pending) } }
        result.onSuccess { item ->
          if (state.value.serverId == server) mutable.update { it.copy(pendingAttachments = it.pendingAttachments + (key to (it.pendingAttachments[key].orEmpty() + item))) }
          else attachmentImporter.discard(listOf(item))
        }.onFailure { errors += it.message ?: "无法添加附件" }
      }
      if (errors.isNotEmpty() && state.value.serverId == server) mutable.update { it.copy(error = errors.distinct().joinToString("\n")) }
    }
  }
  fun removeLocalAttachment(id: String) {
    if (state.value.pending("send")) return
    val key = attachmentKey(state.value.sessionId)
    val removed = state.value.pendingAttachments[key].orEmpty().filter { it.id == id }
    mutable.update { it.copy(pendingAttachments = it.pendingAttachments + (key to it.pendingAttachments[key].orEmpty().filterNot { item -> item.id == id })) }
    operationScope.launch(Dispatchers.IO) { attachmentImporter.discard(removed) }
  }
  private fun rememberConfiguration() {
    val current = state.value
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    store.rememberConfiguration(server, session, SessionConfiguration(current.agent, current.model, current.agentChanged, current.modelChanged))
  }
  fun updateDraft(text: String, expectedServer: String? = state.value.serverId, expectedSession: String? = state.value.sessionId) {
    val current = state.value
    if (current.serverId != expectedServer || current.sessionId != expectedSession) return
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    mutable.update { it.copy(draft = text, draftSessions = if (text.isBlank()) it.draftSessions - session else it.draftSessions + session) }
    val key = "$server:$session"
    draftWrites.remove(key)?.cancel()
    draftWrites[key] = operationScope.launch(Dispatchers.IO) {
      delay(if (text.isEmpty()) 0 else 350)
      attempt { store.rememberDraft(server, session, text) }.onFailure { Diagnostics.warn("Draft", "草稿暂未保存") }
    }
  }
  fun addReference(path: String, mime: String = referenceMime(path)) {
    if (state.value.pending("send")) return
    mutable.update { it.copy(references = (it.references + FileReference(path, mime)).distinctBy { ref -> ref.path }) }; rememberReferences()
  }
  fun removeReference(path: String) { if (state.value.pending("send")) return; mutable.update { it.copy(references = it.references.filterNot { ref -> ref.path == path }) }; rememberReferences() }
  private fun rememberReferences() {
    val current = state.value; val server = current.serverId ?: return; val session = current.sessionId ?: return
    operationScope.launch { referenceMutex.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { store.rememberReferences(server, session, current.references) } } } }
  }
  /** Home list filter only; choosing a project also makes it the target for new sessions. */
  fun setScope(projectId: String?) {
    val server = state.value.serverId ?: return
    val scope = projectId?.takeIf { id -> state.value.projects.any { it.id == id } }
    store.rememberScope(server, scope)
    mutable.update { it.copy(scopeProjectId = scope) }
    if (scope != null && scope != state.value.projectId) selectProject(scope)
  }

  // ---- Device-local projects ----
  /** Resolve [directory] on the server and register the canonical project on this device. */
  fun addProject(directory: String): Job = act("project-add", directory) { op ->
    val project = op.client.projectAt(directory) ?: error("服务器没有解析出这个目录对应的项目")
    val existing = state.value.projects.firstOrNull { normalizedDirectory(it.directory) == normalizedDirectory(project.directory) }
    val resolved = existing ?: project
    val projects = store.addLocalProject(op.serverId, resolved)
    op.commit { withSummary(it.copy(projects = projects, projectId = resolved.id,
      browsePath = null, browseEntries = emptyList(), browseSearch = emptyList(),
      resources = it.resources + ("browse" to ResourceStatus(ResourceState.READY)))) }
    store.rememberLocation(resolved.id, null)
    loadChoices(resolved.directory, generation)
  }
  fun removeProject(projectId: String) {
    val server = state.value.serverId ?: return
    val projects = store.removeLocalProject(server, projectId)
    mutable.update { withSummary(it.copy(projects = projects,
      projectId = it.projectId?.takeIf { id -> projects.any { p -> p.id == id } } ?: projects.firstOrNull()?.id,
      scopeProjectId = it.scopeProjectId?.takeIf { id -> projects.any { p -> p.id == id } })) }
  }

  // ---- Remote directory browser (used to add a project) ----
  fun openDirectoryBrowser(): Job = operationScope.launch {
    val op = attempt { beginOperation(state.value.homeDirectory) }.getOrElse { error ->
      mutable.update { it.copy(error = error.message ?: "无法打开目录", resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, error.message))) }
      return@launch
    }
    mutable.update { it.copy(browsePath = "", browseEntries = emptyList(), browseSearch = emptyList(), browseShowHidden = false, resources = it.resources + ("browse" to ResourceStatus(ResourceState.LOADING))) }
    val root = attempt { op.client.projectAt("") }.getOrNull()?.directory?.takeIf(String::isNotBlank)
      ?: state.value.homeDirectory ?: "/"
    loadBrowse(op, root)
  }
  fun browseDirectory(path: String): Job = operationScope.launch {
    val op = attempt { beginOperation(path) }.getOrElse { error ->
      mutable.update { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, error.message))) }
      return@launch
    }
    mutable.update { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.LOADING)), browseSearch = emptyList()) }
    loadBrowse(op, path)
  }
  private suspend fun loadBrowse(op: OperationContext, path: String) {
    val entries = attempt { op.client.files(path, ".") }.getOrElse { error ->
      op.commit { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, error.message))) }
      return
    }
    op.commit { it.copy(browsePath = path,
      browseEntries = entries.sortedWith(compareBy({ it.type != "directory" }, { it.path.lowercase() })), browseSearch = emptyList(),
      resources = it.resources + ("browse" to ResourceStatus(if (entries.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
  }
  fun searchDirectories(query: String): Job = operationScope.launch {
    val text = query.trim()
    if (text.length < 2) { mutable.update { it.copy(browseSearch = emptyList()) }; return@launch }
    val path = state.value.browsePath ?: return@launch
    val op = attempt { beginOperation(path) }.getOrElse { return@launch }
    delay(250)
    val results = attempt { op.client.searchFiles(path, text) }.getOrElse { error ->
      op.commit { it.copy(resources = it.resources + ("browse-search" to ResourceStatus(ResourceState.ERROR, error.message))) }
      return@launch
    }
    op.commit { it.copy(browseSearch = results, resources = it.resources + ("browse-search" to ResourceStatus(if (results.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
  }
  fun closeDirectoryBrowser() {
    mutable.update { it.copy(browsePath = null, browseEntries = emptyList(), browseSearch = emptyList()) }
  }
  /** 切换「显示隐藏目录」；仅影响本机列表投影，不重新请求服务器。 */
  fun setBrowseShowHidden(show: Boolean) {
    mutable.update { it.copy(browseShowHidden = show) }
  }
  /** Leaves any open session for a blank draft that starts in the scope's (or latest) project. */
  fun beginDraft() {
    val current = state.value
    val target = HomeScope.draftTarget(current.sessions, current.projects, current.scopeProjectId, current.projectId) ?: return
    if (target != current.projectId || current.sessionId != null) selectProject(target)
  }
  fun conversationVisible(server: String, session: String, visible: Boolean) {
    if (visible) visibleConversation = server to session
    else if (visibleConversation == (server to session)) visibleConversation = null
    acknowledgeVisible()
  }
  /**
   * Official `MarkSessionNotificationsViewed`: once the conversation is on screen, every unread result of
   * that session is viewed — immediately, not after its transcript loads — and its result notification is
   * withdrawn. On V2 the server's own read marker for that idle transition is synced best-effort.
   */
  private fun acknowledgeVisible() {
    val current = state.value
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    if (!foreground || visibleConversation != (server to session)) return
    val unseen = current.notices.unseenFor(session)
    if (unseen.isNotEmpty()) {
      val notices = store.viewNotices(server, session, unseen.map { it.id }.toSet())
      mutable.update { if (it.serverId == server) withSummary(it.copy(notices = notices)) else it }
      attempt { notifications.cancel(server, session) }
    }
    val target = current.session ?: return
    val idle = target.idle
    if (idle > 0 && target.viewed < idle && current.capabilities.sessionView != null) {
      val client = api
      mutable.update { it.copy(sessions = it.sessions.map { item -> if (item.id == session && item.idle == idle) item.copy(viewed = idle) else item }) }
      operationScope.launch {
        attempt { client?.viewSession(target, idle) }.onFailure { Diagnostics.warn("SessionView", "服务端已读同步失败，保留本地已读记录") }
      }
    }
  }
  private fun <T> Result<List<T>>.resourceStatus(): ResourceStatus = fold(
    { ResourceStatus(if (it.isEmpty()) ResourceState.EMPTY else ResourceState.READY) },
    { ResourceStatus(if (it is ApiException && it.status == 501) ResourceState.UNSUPPORTED else ResourceState.ERROR, it.message) })

  /**
   * Immutable identity of the connection an asynchronous operation started on. A response may only
   * be committed while [isCurrent] still holds, so a request that was in flight when the user
   * switched server/project cannot write its result into the new context (A02).
   */
  private data class OperationContext(val token: Int, val serverId: String, val client: OpenCodeApi,
    val revision: Long, val snapshot: LagoonState) {
    fun connectionCurrent(c: LagoonController) = token == c.generation && c.state.value.serverId == serverId && c.api === client
    fun isCurrent(c: LagoonController) = connectionCurrent(c) && revision == c.selectionRevision &&
      c.state.value.projectId == snapshot.projectId && c.state.value.sessionId == snapshot.sessionId && c.state.value.executionDirectory == snapshot.executionDirectory
  }
  private fun beginOperation(targetDirectory: String? = null): OperationContext {
    val current = state.value
    check(current.connected) { "数据尚未同步，请重新连接后操作" }
    val directory = targetDirectory ?: current.executionDirectory
    check(!current.degraded || directory !in current.staleDirectories) { "当前项目状态尚未同步，请刷新后操作" }
    return OperationContext(generation, current.serverId ?: error("请先连接服务器"),
      requireNotNull(api) { "请先连接服务器" }, selectionRevision, current)
  }
  private fun OperationContext.commit(update: (LagoonState) -> LagoonState) {
    if (isCurrent(this@LagoonController)) mutable.update(update)
  }
  private fun OperationContext.commitConnection(update: (LagoonState) -> LagoonState) {
    if (connectionCurrent(this@LagoonController)) mutable.update(update)
  }
  fun createSession(title: String, onCreated: ((Session) -> Unit)? = null) = act("create", state.value.project?.directory) { op ->
    val project = op.snapshot.project ?: error("先选择项目")
    val session = scoped(op.serverId, op.client.createSession(project.directory, title))
    op.commitConnection { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) { selectSession(session.id); onCreated?.invoke(session) }
  }
  /**
   * 新建会话一步流：建会话后直接发送第一条 Prompt，免去"空会话 → 再发一条消息"的往返。
   * 首条消息复用 [send]，命令解析、任务计数与前台服务逻辑不重复实现。
   */
  fun startSession(title: String, prompt: String, agent: String? = null, model: ModelChoice? = null, onStarted: ((Session) -> Unit)? = null) = act("create", state.value.project?.directory) { op ->
    // Files picked on the blank draft page move to the new session so a failed first send keeps them.
    val draftFiles = op.snapshot.pendingAttachments[attachmentKey(null)].orEmpty()
    check(prompt.isNotBlank() || draftFiles.isNotEmpty()) { "请输入任务" }
    val project = op.snapshot.project ?: error("先选择项目")
    val session = scoped(op.serverId, op.client.createSession(project.directory, title))
    op.commitConnection { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) {
      store.rememberDraft(op.serverId, session.id, prompt)
      store.rememberConfiguration(op.serverId, session.id, SessionConfiguration(agent, model, agent != null, model != null))
      if (draftFiles.isNotEmpty()) mutable.update { it.copy(pendingAttachments = it.pendingAttachments - attachmentKey(null) + (session.id to draftFiles)) }
      selectSession(session.id)
      mutable.update { it.copy(agentChanged = agent != null, modelChanged = model != null, draft = prompt, previews = it.previews + (session.id to SessionPreview(SessionContent.EMPTY))) }
      onStarted?.invoke(session)
      send(prompt)
    }
  }
  /**
   * Official composer submission: the prompt gets a client-minted id and is shown at once; it steers a
   * running turn instead of being refused, and a failed admission removes the optimistic row again.
   * A slash command known to the server runs through `/command`.
   */
  fun send(text: String, expectedServer: String? = state.value.serverId, expectedSession: String? = state.value.sessionId, accepted: (() -> Unit)? = null): Job {
    if (state.value.serverId != expectedServer || state.value.sessionId != expectedSession) return operationScope.launch { }
    return act("send") { op ->
    val attachments = op.snapshot.attachments
    check(text.isNotBlank() || op.snapshot.references.isNotEmpty() || attachments.isNotEmpty()) { "请输入任务" }
    val session = op.snapshot.session ?: error("先打开会话")
    sendMutex.withLock {
      if (!op.isCurrent(this)) return@act
      val command = if (text.startsWith('/')) op.snapshot.commands.firstOrNull { text.substringAfter('/').substringBefore(' ') == it.name } else null
      val agent = op.snapshot.agent.takeIf { op.snapshot.agentChanged }
      val model = op.snapshot.model.takeIf { op.snapshot.modelChanged }
      val inline = if (attachments.isEmpty()) emptyList() else kotlinx.coroutines.withContext(Dispatchers.IO) { attachments.map(attachmentImporter::inline) }
      val id = MessageIds.ascending()
      if (command == null && text.isNotBlank()) op.commit { it.copy(messages = it.messages + Message(id, "user", System.currentTimeMillis(),
        listOf(MessagePart("$id:text", "text", text = text)), agent = agent, model = model, type = "user")) }
      try {
        if (command != null) op.client.command(session, command.name, text.substringAfter(' ', ""), agent, model, op.snapshot.references, inline)
        else op.client.send(session, text, agent, model, op.snapshot.references, inline, id)
      } catch (error: Exception) {
        op.commit { it.copy(messages = it.messages.filterNot { message -> message.id == id }) }
        throw error
      }
      if (attachments.isNotEmpty()) {
        mutable.update { it.copy(pendingAttachments = it.pendingAttachments + (attachmentKey(session.id) to it.pendingAttachments[attachmentKey(session.id)].orEmpty().filterNot { item -> item in attachments })) }
        kotlinx.coroutines.withContext(Dispatchers.IO) { attachmentImporter.discard(attachments) }
      }
      store.rememberConfiguration(op.serverId, session.id, SessionConfiguration(op.snapshot.agent, op.snapshot.model))
      referenceMutex.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { store.rememberReferences(op.serverId, session.id, emptyList()) } }
      store.rememberPreview(op.serverId, session.id, SessionPreview(SessionContent.CONTENT, text.take(300)))
      if (op.isCurrent(this)) {
        if (state.value.draft == text) updateDraft("")
        accepted?.invoke()
      }
      op.commitConnection { current -> withSummary(current.copy(
        tasks = if (current.tasks[session.id]?.active == true) current.tasks else current.tasks + (session.id to TaskState(session.id, TaskPhase.THINKING, TaskState.RUNNING_DETAIL, activeAt = session.idle)),
        previews = current.previews + (session.id to SessionPreview(SessionContent.CONTENT, text.take(300))))) }
      op.commit { it.copy(agentChanged = false, modelChanged = false, references = emptyList()) }
    }
    if (op.connectionCurrent(this) && op.snapshot.server?.notifications == true) TaskMonitorService.start(appContext, op.serverId, session.id)
    // With the live stream up, the inbox/step events project the turn; otherwise read it back.
    if (op.isCurrent(this) && !state.value.streamConnected) loadSession(session, token = op.token, client = op.client, ancillary = false)
  }
  }
  fun abort() = withSession("abort") { op, client, session ->
    client.abort(session)
    op.commitConnection { withSummary(it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.ABORTED, "任务已停止", it.tasks[session.id]?.since ?: System.currentTimeMillis(), System.currentTimeMillis())))) }
  }
  /** Renames [target] (a session id from the home list) or, by default, the open session. */
  fun rename(title: String, target: String? = null, onRenamed: (() -> Unit)? = null) = withSession("rename", target) { op, client, session ->
    check(title.isNotBlank()) { "标题不能为空" }
    client.renameSession(session, title.trim())
    op.commitConnection { it.copy(sessions = it.sessions.map { item -> if (item.id == session.id) item.copy(title = title.trim()) else item }) }
    if (op.isCurrent(this)) onRenamed?.invoke()
  }
  fun deleteSession(target: String? = null, onDeleted: (() -> Unit)? = null) = withSession("delete", target) { op, client, session ->
    client.deleteSession(session)
    cache.deleteMessages(op.serverId, session.id)
    store.forgetSession(op.serverId, session.id)
    if (op.isCurrent(this)) onDeleted?.invoke()
    if (session.id == op.snapshot.sessionId) op.commit { it.copy(sessionId = null, messages = emptyList()) }
    if (session.id in op.snapshot.pinned) store.rememberPinnedSessions(op.serverId, op.snapshot.pinned - session.id)
    op.commitConnection { withSummary(it.copy(sessions = it.sessions.filterNot { s -> s.id == session.id }, tasks = it.tasks - session.id, pinned = it.pinned - session.id)) }
    if (op.connectionCurrent(this)) reload()
  }
  /** Archives (or restores) [target]; the home list hides archived sessions, 已归档 lists them. */
  fun setArchived(target: String, archived: Boolean, onDone: (() -> Unit)? = null) = withSession("archive", target) { op, client, session ->
    client.setArchived(session, archived)
    op.commitConnection { it.copy(sessions = it.sessions.map { item -> if (item.id == session.id) item.copy(archived = archived) else item }) }
    if (op.connectionCurrent(this)) onDone?.invoke()
  }
  fun fork(onForked: ((Session) -> Unit)? = null) = withSession("fork") { op, client, session ->
    val fork = scoped(op.serverId, client.forkSession(session))
    op.commitConnection { it.copy(sessions = (listOf(fork) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) { selectSession(fork.id); onForked?.invoke(fork) }
  }
  fun revert(messageId: String, onReverted: (() -> Unit)? = null) = withSession("revert") { op, client, session ->
    client.revert(session, messageId)
    op.commitConnection { current -> current.copy(sessions = current.sessions.map { if (it.id == session.id) it.copy(revertMessageId = messageId) else it }) }
    if (op.isCurrent(this)) { op.commit { it.copy(message = "已撤销，可通过恢复撤销还原") }; onReverted?.invoke() }
  }
  fun unrevert() = withSession("unrevert") { op, client, session ->
    client.unrevert(session)
    op.commitConnection { current -> current.copy(sessions = current.sessions.map { if (it.id == session.id) it.copy(revertMessageId = null) else it }) }
    if (op.isCurrent(this)) op.commit { it.copy(message = "已恢复撤销") }
  }
  fun replyPermission(request: PermissionRequest, reply: String) = act("permission:${request.id}", request.directory) { op ->
    check(request in op.snapshot.permissions) { "权限请求已变化，请刷新" }
    try {
      op.client.replyPermission(request, reply)
    } catch (e: ApiException) {
      if (e.status != 404) throw e
      val session = op.snapshot.sessions.firstOrNull { it.id == request.sessionId }
        ?: Session(request.sessionId, request.directory, "", 0)
      if (op.client.sessionPermissions(session).any { it.id == request.id }) throw e
    }
    if (op.connectionCurrent(this)) permissionResolved(request.sessionId, request.id)
    cancelAttention(request.sessionId)
  }
  /** Notification execution uses its own immutable client. Only reconcile matching UI state. */
  fun notificationCompleted(serverId: String) {
    scope.launch { if (state.value.serverId == serverId) reload() }
  }
  fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) = act("question:${request.id}", request.directory) { op ->
    check(request in op.snapshot.questions) { "问题已变化，请刷新" }
    op.client.replyQuestion(request, answers)
    op.commitConnection { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
    cancelAttention(request.sessionId)
  }
  fun rejectQuestion(request: QuestionRequest) = act("question:${request.id}", request.directory) { op ->
    check(request in op.snapshot.questions) { "问题已变化，请刷新" }
    op.client.rejectQuestion(request)
    op.commitConnection { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
    cancelAttention(request.sessionId)
  }
  fun listFiles(path: String = "."): Job {
    val request = ++fileSequence
    mutable.update { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.LOADING)), searchResults = emptyList()) }
    ++searchSequence
    return act { op ->
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val files = try { op.client.files(directory, path) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, error.message))) }
        throw error
      }
      if (request == fileSequence) op.commit { it.copy(files = files, filePath = path, fileText = null, fileBinary = false,
        resources = it.resources + ("files" to ResourceStatus(if (files.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  fun readFile(path: String): Job {
    val request = ++fileSequence
    mutable.update { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.LOADING))) }
    return act { op ->
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val content = try { op.client.fileContent(directory, path) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, error.message))) }
        throw error
      }
      if (request == fileSequence) op.commit { it.copy(fileText = content.content, fileBinary = content.type == "binary", filePath = path,
        resources = it.resources + ("files" to ResourceStatus(ResourceState.READY))) }
    }
  }
  fun searchFiles(query: String): Job {
    val request = ++searchSequence
    if (query.trim().length < 2) {
      mutable.update { it.copy(searchResults = emptyList(), resources = it.resources + ("search" to ResourceStatus(ResourceState.EMPTY))) }
      return operationScope.launch { }
    }
    mutable.update { it.copy(searchResults = emptyList(), resources = it.resources + ("search" to ResourceStatus(ResourceState.LOADING))) }
    return act { op ->
      delay(250)
      if (request != searchSequence) return@act
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val results = try { op.client.searchFiles(directory, query.trim()) } catch (error: Exception) {
        if (request == searchSequence) op.commit { it.copy(resources = it.resources + ("search" to ResourceStatus(ResourceState.ERROR, error.message))) }
        throw error
      }
      if (request == searchSequence) op.commit { it.copy(searchResults = results,
        resources = it.resources + ("search" to ResourceStatus(if (results.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  private fun withSession(action: String, target: String? = null, block: suspend (OperationContext, OpenCodeApi, Session) -> Unit) = act(action) { op ->
    val session = (if (target == null) op.snapshot.session else op.snapshot.sessions.firstOrNull { it.id == target }) ?: error("先打开会话")
    block(op, op.client, session)
  }
  private fun act(action: String = "", directory: String? = null, block: suspend (OperationContext) -> Unit): Job {
    val captured = attempt { beginOperation(directory) }
    val key = action.takeIf { it.isNotBlank() }?.let { operationKey(state.value.serverId, state.value.sessionId, state.value.projectId, it) }
    if (key != null && key in state.value.pendingOperations) return operationScope.launch { }
    if (key != null) mutable.update { it.copy(pendingOperations = it.pendingOperations + key,
      resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.LOADING))) }
    return operationScope.launch {
      val op = captured.getOrElse { error ->
        mutable.update { it.copy(error = error.message ?: "操作失败", resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.ERROR, error.message)), pendingOperations = it.pendingOperations - setOfNotNull(key)) }
        return@launch
      }
      try {
        block(op)
        if (key != null && op.isCurrent(this@LagoonController)) mutable.update {
          it.copy(resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.READY)))
        }
      } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
        if (op.connectionCurrent(this@LagoonController)) mutable.update { it.copy(error = error.message ?: "操作失败",
          resources = if (key == null || !op.isCurrent(this@LagoonController)) it.resources else it.resources + ("action:$action" to ResourceStatus(ResourceState.ERROR, error.message))) }
      } finally {
        if (key != null) mutable.update { it.copy(pendingOperations = it.pendingOperations - key) }
      }
    }
  }
  private inline fun <T> attempt(block: () -> T): Result<T> = try { Result.success(block()) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { Result.failure(error) }
}
