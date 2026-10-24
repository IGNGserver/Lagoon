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
import kotlinx.coroutines.currentCoroutineContext
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
  val streamConnected: Boolean = false,
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
  /** The independent server-side read sync is in flight; the menu shows progress and ignores retaps. */
  val serverReadPending: Boolean = false,
  /** Home is projecting all saved servers instead of the writable single-server connection. */
  val allServers: Boolean = false,
  /** Home filter; the writable conversation's serverId can differ without changing this. */
  val homeServerId: String? = null,
  val permissionPolicy: PermissionPolicy = PermissionPolicy(),
  val autoApprovalFailures: Set<String> = emptySet(),
  /** Wall clock of the last successful control-plane read of the writable server. */
  val lastContactAt: Long = 0,
  /** What the selected server's connection is waiting for; drives the status strip under the header. */
  val connection: ConnectionStatus = ConnectionStatus(),
  /** Wall clock when the selected server's live stream went down; 0 while it is up. */
  val streamDownSince: Long = 0,
  /**
   * Ids of transcript rows shown from the device cache while the server copy is read. They are
   * replaced, not merged, once the authoritative page arrives.
   */
  val provisionalMessageIds: Set<String> = emptySet(),
  /** Wall clock when the open conversation's transcript read began; 0 when it is not loading. */
  val transcriptLoadingSince: Long = 0
) {
  val server: ServerProfile? get() = profiles.firstOrNull { it.id == serverId }
  /**
   * The open conversation can take a prompt now and deliver it once the server answers: the server is
   * still connecting or is between retries, rather than not configured at all.
   */
  val canQueueSend: Boolean get() = !connected && serverId != null && session != null &&
    connection.stage in setOf(ConnectStage.CONNECTING, ConnectStage.READING, ConnectStage.SYNCING, ConnectStage.RETRYING)
  val project: Project? get() = projects.firstOrNull { it.id == projectId }
  val session: Session? get() = sessions.firstOrNull { it.id == sessionId }
  val executionDirectory: String? get() = session?.directory ?: project?.directory
  /** 首页当前服务器可用的执行目录：项目 → 最近会话。用于未打开会话时也能按首页选中的服务器加载模型目录。 */
  val homeDirectory: String? get() = project?.directory ?: sessions.firstOrNull()?.directory
  val attachments: List<LocalAttachment> get() = pendingAttachments[attachmentKey(sessionId)].orEmpty()
  val visibleModels: List<ModelInfo> get() = ModelVisibility.visibleConfigured(modelCatalog, modelOverrides, modelVisibilityConfigured)
  fun pending(action: String): Boolean {
    // 投影状态把 server/session/project 置空（全部服务器模式），此时按动作后缀匹配，
    // 否则列表级按钮永远不显示“正在加载…”。
    val key = operationKey(serverId, sessionId, projectId, action)
    return pendingOperations.contains(key) || (serverId == null && pendingOperations.any { it.endsWith(":$action") })
  }
  fun resource(name: String): ResourceStatus = resources[name] ?: ResourceStatus()
  /** The next older page is already on this device (the transcript store), readable without a connection. */
  val olderFromDevice: Boolean get() = TranscriptSync.localId(messagesCursor) != null
  fun title(session: Session): String = session.displayTitle()
  val parents: Map<String, String> get() = knownParents + sessions.mapNotNull { it.parentId?.let { parent -> it.id to parent } }.toMap()
  /** Live (running / waiting) state of each session family, rolled up to its root. */
  val presentedTasks: Map<String, TaskState> get() = permissionPolicy.tasks(tasks, permissions, autoApprovalFailures)
  val visiblePermissions: List<PermissionRequest> get() = permissions.filter { permissionPolicy.visible(it.id, autoApprovalFailures) }
  val globalSummary: TaskSummary get() {
    val snapshots = serverSnapshots().values
    if (snapshots.isEmpty()) return summary
    // 关闭通知的服务器不进入系统汇总（灵动岛 / 前台服务），但它的数据仍留在应用内首页。
    return TaskSummary.merge(snapshots.filter { it.profile.notifications }.map { it.displaySummary(permissionPolicy) })
  }
  val globalSummaryTarget: SessionTarget? get() = globalSummary.items.firstOrNull()?.let { item -> item.serverId?.let { SessionTarget(it, item.sessionId) } }
  val activeRootTasks: Map<String, TaskState> get() = TaskSummary.aggregate(livePresentedTasks.filterValues { it.active }, parents)
  fun sessionStatus(session: Session, active: Map<String, TaskState> = activeRootTasks): SessionStatus {
    if (allServers && serverId == null) return allSessionStatuses[session.key] ?: SessionStatus.NONE
    val unseen = notices.unseenFor(session.id)
    val staleOwn = staleConnection && presentedTasks[session.id]?.active == true
    val staleRoot = staleConnection && rawActiveRootTasks.containsKey(session.id)
    if ((staleOwn || staleRoot) && active[session.id] == null && unseen.isEmpty()) return SessionStatus.UNKNOWN
    return sessionStatus(active[session.id], unseen, session.id in backgroundRunning)
  }
}

class LagoonController private constructor(private val appContext: Context) {
  companion object {
    private val TERMINAL_PHASES = setOf(TaskPhase.COMPLETED, TaskPhase.FAILED)
    /** Backoff attempt that maps to the slowest reconnect delay; used once credentials are rejected. */
    private const val AUTH_RETRY_ATTEMPT = 4
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
    /** Recent conversations whose first page is fetched into memory after a full read. */
    private const val PREFETCH_SESSIONS = 3
    /** Away from the foreground longer than this, the live streams are rebuilt on return. */
    private const val STREAM_RESUME_RESTART_MILLIS = 60_000L
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
        // 工具结果里的 `metadata.status="running"` 是返回当时的静态快照，不是实时状态。只补
        // 未知的子任务：已有任务（尤其已收尾的墓碑）不能被旧 transcript 再次改回活跃。
        if (tasks[child] == null) tasks[child] = TaskState(child, TaskPhase.SUBAGENT, TaskState.RUNNING_DETAIL,
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
    val summary = TaskSummary.of(next.livePresentedTasks, next.notices, next.parents, titles, background,
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
    val server = state.value.serverId ?: return
    val key = "$server\u0000$root"
    if (backgroundSettles.containsKey(key)) return
    backgroundSettles[key] = scope.launch {
      delay(BACKGROUND_SETTLE_MILLIS)
      backgroundSettles.remove(key)
      settleBackgroundRun(server, root)
    }
  }

  private fun settleBackgroundRun(server: String, root: String) {
    val current = state.value
    // 会话 id 只在单台服务器内唯一；切换后旧定时器不得在新连接上收尾同 id 会话。
    if (current.serverId != server) return
    val task = current.tasks[root] ?: return
    if (!task.active || !task.background) return
    if (BackgroundWork.hasBackgroundWork(root, current.tasks, current.shells, current.parents)) return
    val session = current.sessions.firstOrNull { it.id == root } ?: return
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
  /** Transcript cache access runs one at a time in submission order: binding a server precedes reading it. */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val storeDispatcher = Dispatchers.IO.limitedParallelism(1)
  /** Verified on-device copy of OpenCode transcripts; its database opens on first use, off the main thread. */
  private val transcripts: TranscriptStore? = TranscriptStore.get(appContext)
  private val transcriptSync = transcripts?.let { TranscriptSync(it, storeDispatcher) }
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
  /** Shared per-directory read health; a dead directory must not degrade every server's state. */
  private val directoryHealth = DirectoryHealth()
  private val allOverview = AllServersOverview(store, cache, scope, ::acceptAllSnapshot, { state.value.serverId },
    { id, event -> if (id == state.value.serverId) handleEvent(event) },
    { id ->
      if (id == state.value.serverId) trace(generation, ConnectMilestone.STREAM)
      if (id == state.value.serverId && api != null && connecting?.isActive != true) reconcileAfterStream()
    },
    directoryHealth, ::acceptSnapshotRemoved)
  private var api: HarnessClient? = null
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
  /** The long-running independent “服务端已读” sweep; prevents a second tap from racing it. */
  private var serverReadJob: Job? = null
  /** 等待态事件的单调序号：对账只在读取期间没有新的等待事件时才清除等待态，避免事件/对读竞态造成抖动。 */
  private var waitingEventSeq = 0L
  private val waitingEventSeqAt = mutableMapOf<String, Long>()
  private val resolvedPermissions = linkedSetOf<String>()
  /** Field timing of connect / reconnect / refresh attempts, keyed by [generation]. */
  private val connectTrace = ConnectTrace()
  /** Field timing of opening a conversation, keyed by [selectionRevision]. */
  private val openTrace = ConnectTrace()
  /** Recently viewed transcripts kept in memory, so switching back paints without decrypting the cache. */
  private val transcriptMemory = TranscriptMemory()
  /** Prompts submitted while the server was unreachable; delivered once it is interactive again. */
  private val queuedSends = mutableListOf<QueuedPrompt>()

  init {
    store.migrateLocalProjects()
    // OpenCode transcripts moved to the verified store; drop their truncated SharedPreferences copies.
    if (transcriptSync != null) scope.launch(storeDispatcher) {
      attempt { store.profiles().filter { it.kind == ServerKind.OPENCODE }.forEach { cache.deleteTranscripts(it.id) } }
    }
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
    allOverview.start(mutable.value.profiles, include = setOfNotNull(mutable.value.serverId))
    mutable.value.serverId?.takeIf { id -> mutable.value.profiles.any { it.id == id } }?.let { connect(it, preserveHome = true) }
    observeNetwork()
    scheduleBackgroundSync()
  }
  private fun scheduleBackgroundSync() {
    attempt { BackgroundSyncWorker.schedule(appContext, store.profiles().isNotEmpty()) }
      .onFailure { Diagnostics.warn("BackgroundSync", "无法安排后台定时刷新", it) }
  }

  /** A monitor was deliberately stopped: drop its stale tasks/notices from the global island. */
  private fun acceptSnapshotRemoved(id: String) {
    if (allSnapshots.remove(id) == null) return
    mutable.update { it.copy(allServerSnapshots = allSnapshots.toMap()) }
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
        streamDownSince = if (current.serverId != snapshot.serverId) current.streamDownSince
          else if (snapshot.streamConnected) 0 else current.streamDownSince.takeIf { it > 0 } ?: System.currentTimeMillis(),
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
    // Sessions the overview dropped (deleted on the server, pruned as ghosts): their notifications
    // must not stay tappable forever.
    (previous?.sessions?.map { it.id }?.toSet().orEmpty() - normalized.sessions.map { it.id }.toSet()).forEach { session ->
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
  /** [onCookie] 收到测试连接换到的会话 Cookie，保存资料时一并落盘，避免保存后再用令牌换一次。 */
  suspend fun testServer(profile: ServerProfile, credentials: ServerCredentials, onCookie: (String) -> Unit = {}): String {
    val client = createHarnessClient(profile, credentials, onCookie = onCookie)
    val version = client.health()
    client.projects()
    return version
  }
  fun notifyOverviewAction(serverId: String) = allOverview.refresh(serverId)
  fun clearError() = mutable.update { it.copy(error = null) }
  /**
   * The live stream (re)connected. Events between the last read and the handshake may be lost, so the
   * control plane and the open transcript's tail are re-read — but only those: the catalog paging,
   * model/agent/command lists, children and diff did not go stale because a socket reconnected.
   */
  private fun reconcileAfterStream() {
    if (!state.value.connected) { reloadConversation(); return }
    if (refresh?.isActive == true) return
    val token = generation
    refresh = scope.launch {
      try { loadAll(token, controlOnly = true) } catch (error: Exception) {
        if (error is CancellationException) throw error
        Diagnostics.warn("LagoonController", "实时连接恢复后的对账失败", error)
        if (token == generation) mutable.update { it.copy(degraded = true) }
      }
    }
  }
  /** The default network last reported by Android; a different one means sockets may be stale. */
  private var defaultNetwork: Any? = null
  private var networkSeen = false
  /** Wall clock when the app last left the foreground; 0 while visible. */
  @Volatile private var backgroundedAt = 0L
  /**
   * Holds a prompt typed while the server is unreachable. It is shown at once as a “等待连接后发送” row
   * and the composer is cleared; one pending prompt per conversation, so a second one is appended to
   * it. Nothing typed is ever dropped: if it is not delivered, it goes back into the draft.
   */
  private fun queuePrompt(text: String, accepted: (() -> Unit)?) {
    val current = state.value
    val serverId = current.serverId ?: return
    val sessionId = current.sessionId ?: return
    if (text.isBlank()) { mutable.update { it.copy(error = "附件需要在连接恢复后发送") }; return }
    val existing = queuedSends.firstOrNull { it.serverId == serverId && it.sessionId == sessionId }
    val prompt = existing?.copy(text = existing.text + "\n\n" + text)
      ?: QueuedPrompt(serverId, sessionId, text, PENDING_PREFIX + MessageIds.ascending(), System.currentTimeMillis())
    queuedSends.remove(existing); queuedSends += prompt
    mutable.update { it.copy(messages = it.messages.filterNot { message -> message.id == prompt.messageId } + prompt.row()) }
    accepted?.invoke()
  }
  /** [messages] plus the conversation's pending prompt row, which no server read or cache paint knows about. */
  private fun withPending(serverId: String?, sessionId: String?, messages: List<Message>): List<Message> {
    val prompt = queuedSends.firstOrNull { it.serverId == serverId && it.sessionId == sessionId } ?: return messages
    return if (messages.any { it.id == prompt.messageId }) messages else messages + prompt.row()
  }
  /** Delivers the open conversation's pending prompt once the server is interactive. */
  private fun flushQueuedSends() {
    val current = state.value
    if (queuedSends.isEmpty() || !current.connected) return
    val prompt = queuedSends.firstOrNull { it.serverId == current.serverId && it.sessionId == current.sessionId } ?: return
    queuedSends.remove(prompt)
    mutable.update { it.copy(messages = it.messages.filterNot { message -> message.id == prompt.messageId }) }
    deliver(prompt.text, prompt.serverId, prompt.sessionId, accepted = null, onFailed = { restoreDraft(prompt) })
  }
  /** Pending prompts of conversations the user is leaving go back into their drafts, undelivered. */
  private fun returnQueuedToDrafts(keep: (QueuedPrompt) -> Boolean = { false }) {
    val leaving = queuedSends.filterNot(keep)
    if (leaving.isEmpty()) return
    queuedSends.removeAll(leaving)
    val ids = leaving.map { it.messageId }.toSet()
    mutable.update { it.copy(messages = it.messages.filterNot { message -> message.id in ids }) }
    leaving.forEach(::restoreDraft)
  }
  private fun restoreDraft(prompt: QueuedPrompt) {
    val current = state.value
    if (current.serverId == prompt.serverId && current.sessionId == prompt.sessionId) {
      updateDraft(listOf(prompt.text, current.draft).filter(String::isNotBlank).joinToString("\n\n"))
    } else {
      draftWrites.remove("${prompt.serverId}:${prompt.sessionId}")?.cancel()
      val stored = attempt { store.draft(prompt.serverId, prompt.sessionId) }.getOrDefault("")
      attempt { store.rememberDraft(prompt.serverId, prompt.sessionId, listOf(prompt.text, stored).filter(String::isNotBlank).joinToString("\n\n")) }
    }
  }
  /**
   * After a full read, the few most recent other conversations are brought up to date in the
   * transcript store (or, without one, their first page is kept in memory), so opening one paints at
   * once and verifies with a single small read. OpenCode only (a DSH page read changes its live
   * subscriptions) and never on a metered network.
   */
  private fun prefetchTranscripts(token: Int, client: HarnessClient) {
    val current = state.value
    val serverId = current.serverId ?: return
    if (current.server?.kind != ServerKind.OPENCODE) return
    val sync = transcriptSync?.takeIf { client.cacheableTranscripts }
    if (sync != null) {
      val candidates = current.sessions.filter { it.parentId == null && !it.archived && it.id != current.sessionId }.take(PREFETCH_SESSIONS)
      if (candidates.isEmpty()) return
      scope.launch {
        if (meteredNetwork()) return@launch
        for (session in candidates) {
          if (token != generation) return@launch
          // Already current in the store: nothing to read.
          val info = kotlinx.coroutines.withContext(storeDispatcher) { attempt { transcripts?.info(serverId, session.id) }.getOrNull() }
          if (info != null && session.updated > 0 && info.sessionUpdated == session.updated) continue
          attempt { sync.open(client, serverId, session) }.onFailure { Diagnostics.warn("TranscriptSync", "预取会话失败", it) }
        }
      }
      return
    }
    val targets = current.sessions.asSequence()
      .filter { it.parentId == null && !it.archived && it.id != current.sessionId && transcriptMemory.get(serverId, it.id) == null }
      .take(PREFETCH_SESSIONS).toList()
    if (targets.isEmpty()) return
    scope.launch {
      if (meteredNetwork()) return@launch
      for (session in targets) {
        if (token != generation) return@launch
        val page = kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { client.messagesPage(session.id) }.getOrNull() } ?: continue
        if (token == generation && transcriptMemory.get(serverId, session.id) == null) transcriptMemory.put(serverId, session.id, page.items)
      }
    }
  }
  private fun meteredNetwork(): Boolean =
    attempt { (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered }.getOrDefault(true)
  /**
   * Opening only checks the newest page against the stored run. About once a week per conversation,
   * on an unmetered network, the whole run is compared too; a difference drops it and the open
   * conversation is read again from the server.
   */
  private fun scheduleFullCheck(sync: TranscriptSync, client: HarnessClient, serverId: String, session: Session, token: Int) {
    scope.launch {
      if (!sync.dueForFullCheck(serverId, session.id) || meteredNetwork()) return@launch
      val matched = attempt { sync.verifyAll(client, serverId, session) }.getOrNull()
      if (matched == false && token == generation && state.value.serverId == serverId && state.value.sessionId == session.id) {
        transcriptMemory.forget(serverId, session.id)
        selectSession(session.id)
      }
    }
  }
  /** Size of the on-device transcript copy, for settings; null when there is no store. */
  internal suspend fun transcriptCacheUsage(): TranscriptStore.Usage? = transcripts?.let { store ->
    kotlinx.coroutines.withContext(storeDispatcher) { attempt { store.usage() }.getOrNull() }
  }
  /** Drops every stored transcript (the server keeps them); conversations are read again on open. */
  fun clearTranscriptCache(onCleared: () -> Unit = {}) {
    transcriptMemory.clear()
    scope.launch {
      kotlinx.coroutines.withContext(storeDispatcher) {
        attempt { transcripts?.clear() }
        attempt { store.profiles().forEach { cache.deleteTranscripts(it.id) } }
      }
      onCleared()
    }
  }
  /** “立即重试” from the status strip: skip the backoff wait and read the server now. */
  fun retryNow() {
    val id = state.value.serverId ?: return
    if (api == null) connect(id, preserveHome = true) else reload()
  }
  private fun retryReason(error: String?): String = error?.removePrefix("离线缓存：")?.takeIf { it.isNotBlank() } ?: "连接失败"
  private fun setStage(token: Int, stage: ConnectStage) {
    if (token != generation) return
    mutable.update { if (it.connection.stage == stage) it else it.copy(connection = ConnectionStatus(stage, System.currentTimeMillis())) }
  }
  /** Moves the connecting stages forward; a background refresh of an interactive screen stays READY. */
  private fun advanceStage(token: Int, stage: ConnectStage) {
    if (state.value.connection.stage != ConnectStage.READY) setStage(token, stage)
  }
  /** Cold start: show the last catalog this device saw while the server is read, never an empty list. */
  private fun paintCachedCatalog(id: String, token: Int) {
    scope.launch {
      val cached = kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { cache.catalog(id) }.getOrNull() } ?: return@launch
      if (token != generation || state.value.serverId != id || state.value.connected || state.value.sessions.isNotEmpty()) return@launch
      val projects = (cached.first + store.localProjects(id)).distinctBy { normalizedDirectory(it.directory) }.map { it.copy(serverId = id) }
      if (cached.second.isEmpty()) return@launch
      mutable.update { current -> withSummary(current.copy(sessions = cached.second.map { scoped(id, it) }.sortedWith(sessionActivityOrder),
        projects = current.projects.takeIf { it.isNotEmpty() } ?: projects)) }
      trace(token, ConnectMilestone.CACHE_PAINT)
    }
  }
  /** Transcripts of this server live in [transcripts]: an OpenCode server with an open database. */
  private fun storesTranscripts(serverId: String?): Boolean =
    transcriptSync != null && serverId != null && state.value.profiles.firstOrNull { it.id == serverId }?.kind == ServerKind.OPENCODE
  /** The device's last copy of a conversation: the verified store for OpenCode, the snapshot cache otherwise. */
  private suspend fun cachedTranscript(serverId: String, sessionId: String): List<Message> = kotlinx.coroutines.withContext(storeDispatcher) {
    attempt { if (storesTranscripts(serverId)) transcriptSync!!.paint(serverId, sessionId) else cache.messages(serverId, sessionId) }.getOrDefault(emptyList())
  }
  /** [cachedTranscript] plus where its older history continues (stored rows are readable offline). */
  private suspend fun cachedTranscriptWindow(serverId: String, sessionId: String): Pair<List<Message>, String?> =
    if (!storesTranscripts(serverId)) cachedTranscript(serverId, sessionId) to null
    else kotlinx.coroutines.withContext(storeDispatcher) { attempt { transcriptSync!!.paintWindow(serverId, sessionId) }.getOrDefault(emptyList<Message>() to null) }
  /** False when the copy is known to be cut short; the store never truncates what it keeps. */
  private suspend fun cachedTranscriptComplete(serverId: String, sessionId: String): Boolean =
    if (storesTranscripts(serverId)) true else kotlinx.coroutines.withContext(storeDispatcher) { cache.messagesComplete(serverId, sessionId) }
  private fun forgetStoredTranscript(serverId: String, sessionId: String) {
    if (transcripts != null) scope.launch(storeDispatcher) { attempt { transcripts.delete(serverId, sessionId) } }
  }
  /**
   * Shows the conversation's last known transcript (memory first, then the encrypted cache) while the
   * server copy is read. Only fills an empty, still-loading transcript of the same selection.
   */
  private fun showCachedTranscript(serverId: String, sessionId: String, revision: Long) {
    fun stillWaiting() = state.value.serverId == serverId && state.value.sessionId == sessionId && selectionRevision == revision &&
      state.value.messages.persistable().isEmpty() && state.value.resource("messages").state in setOf(ResourceState.LOADING, ResourceState.NOT_LOADED)
    fun show(messages: List<Message>) {
      if (messages.isEmpty() || !stillWaiting()) return
      mutable.update { withSummary(it.copy(messages = withPending(serverId, sessionId, messages), provisionalMessageIds = messages.mapTo(mutableSetOf()) { message -> message.id })) }
      openTrace.mark(revision, ConnectMilestone.CACHE_PAINT)
    }
    transcriptMemory.get(serverId, sessionId)?.let { show(it); return }
    scope.launch { show(cachedTranscript(serverId, sessionId)) }
  }
  /** Recent connection and conversation-open timings, one line each, for the copied diagnostics. */
  fun connectionDiagnostics(): String = ((connectTrace.reports() + openTrace.reports()).map { it.line() } + listOfNotNull(transcriptSync?.statsLine())).joinToString("\n")
  private fun trace(token: Int, milestone: ConnectMilestone) {
    connectTrace.mark(token.toLong(), milestone)?.let { Diagnostics.info("ConnectTrace", "${milestone.label} ${it}ms") }
  }
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
          scope.launch {
            // The default network changed (Wi-Fi ↔ cellular, or back after a loss): sockets opened on the
            // previous network may be half-open, so every stream reconnects now instead of hanging.
            val switched = networkSeen && network != defaultNetwork
            networkSeen = true; defaultNetwork = network
            if (switched) allOverview.restartStreams()
            allOverview.refreshAll(); if (!state.value.connected) reloadConversation()
          }
        }
        override fun onLost(network: android.net.Network) {
          scope.launch { if (network == defaultNetwork) defaultNetwork = null }
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
    val hadNotifications = store.profiles().firstOrNull { it.id == profile.id }?.notifications == true
    store.save(profile, password, cookie, credentialUsername)
    if (hadNotifications && !profile.notifications) {
      (store.taskStates(profile.id).keys + store.sessionNotices(profile.id).map { it.sessionId }).distinct().forEach { session ->
        notifications.cancel(profile.id, session)
      }
    }
    mutable.update { it.copy(profiles = store.profiles()) }
    scheduleBackgroundSync()
    allOverview.start(store.profiles(), include = setOfNotNull(if (connect) profile.id else state.value.serverId))
    if (connect) connect(profile.id, preserveHome = state.value.allServers)
  }
  fun deleteServer(id: String) {
    notifications.cancelSummary(id)
    // The global summary id is shared now; cancel the per-session results/attention of this server.
    (store.taskStates(id).keys + store.sessionNotices(id).map { it.sessionId }).distinct().forEach { session ->
      notifications.cancel(id, session)
    }
    if (mutable.value.serverId == id) {
      stopSingleConnection()
    }
    allSnapshots.remove(id)
    transcriptMemory.forgetServer(id)
    store.delete(id)
    cache.delete(id)
    if (transcripts != null) scope.launch(storeDispatcher) { attempt { transcripts.deleteServer(id) } }
    scheduleBackgroundSync()

    allOverview.start(store.profiles(), include = setOfNotNull(state.value.serverId))
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
    // A reconnect to the same server keeps the open conversation's pending prompt; anything else goes back to its draft.
    returnQueuedToDrafts { it.serverId == id && it.sessionId == (sessionOverride ?: previous.sessionId) && id == previousServer }
    stopSingleConnection()
    val token = generation
    connectTrace.begin(token.toLong(), "连接 ${profile.kind.label}")
    allOverview.start(store.profiles(), include = setOfNotNull(id))
    api = allOverview.client(id)
    if (transcripts != null && profile.kind == ServerKind.OPENCODE) scope.launch(storeDispatcher) {
      attempt { transcripts.bindServer(id, transcriptIdentity(profile)) }.onFailure { Diagnostics.warn("TranscriptStore", "绑定服务器失败", it) }
    }
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
      streamDownSince = if (snapshot?.streamConnected == true) 0 else System.currentTimeMillis(),
      connection = ConnectionStatus(ConnectStage.CONNECTING, System.currentTimeMillis()),
      agent = configuration.agent, model = configuration.model, agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = rememberedSession?.let { store.draft(id, it) }.orEmpty(), references = rememberedSession?.let { store.references(id, it) }.orEmpty(),
      tasks = snapshot?.tasks ?: store.taskStates(id), knownParents = store.taskParents(id), notices = store.sessionNotices(id), collapsedSections = store.collapsedSections(id), pinned = store.pinnedSessions(id),
      pendingAttachments = previous.pendingAttachments,
      modelOverrides = store.modelOverrides(id), modelVisibilityConfigured = store.modelVisibilityConfigured(id),
      modelVariants = store.modelVariants(id), recentModels = store.recentModels(id), scopeProjectId = store.scope(id)) }
    if (state.value.sessions.isNotEmpty()) trace(token, ConnectMilestone.CACHE_PAINT)
    else paintCachedCatalog(id, token)
    rememberedSession?.let { openTrace.begin(selectionRevision, "恢复会话"); showCachedTranscript(id, it, selectionRevision) }
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
        connectTrace.fail(token.toLong(), userFacingError(error, "连接失败"))
        showOffline(id, token, userFacingError(error, "连接失败"))
        // A refresh while offline must be able to recover: retry the connection with backoff instead
        // of leaving the user on a stale snapshot with no path back to a live stream (A10).
        // Expired credentials do not heal by retrying quickly; keep probing at the slowest pace.
        scheduleReconnect(id, token, if (error.isAuthFailure) AUTH_RETRY_ATTEMPT else 0)
      }
    }
  }
  /** Opens immediately; loading the transcript does not change the originating home filter. */
  fun openConversation(target: SessionTarget) {
    if (target.serverId != state.value.serverId) {
      connect(target.serverId, sessionOverride = target.sessionId, preserveHome = true)
    } else if (state.value.sessions.any { it.id == target.sessionId }) selectSession(target.sessionId)
    else {
      // 先清掉上一个会话的输入：随后的 selectSession 会把它当作"重新选中"而保留，并异步填回本会话的草稿。
      mutable.update { it.copy(sessionId = target.sessionId, messages = emptyList(), draft = "", references = emptyList(), resources = mapOf("messages" to ResourceStatus(ResourceState.LOADING))) }
      resolveSession(target.sessionId) { if (state.value.serverId == target.serverId && state.value.sessionId == target.sessionId) selectSession(target.sessionId) }
    }
  }
  private fun scheduleReconnect(id: String, token: Int, attempt: Int = 0) {
    if (token != generation || state.value.serverId != id) return
    val delayMillis = (2_000L shl attempt.coerceAtMost(4)).coerceAtMost(60_000L)
    val now = System.currentTimeMillis()
    mutable.update { it.copy(connection = ConnectionStatus(ConnectStage.RETRYING, now, now + delayMillis, retryReason(it.error))) }
    scope.launch {
      delay(delayMillis)
      if (token == generation && state.value.serverId == id && !state.value.connected) reconnect(id, token, attempt)
    }
  }
  private suspend fun reconnect(id: String, token: Int, attempt: Int) {
    val profile = store.profiles().firstOrNull { it.id == id } ?: return
    connectTrace.begin(token.toLong(), "重连 ${profile.kind.label} 第 ${attempt + 1} 次")
    setStage(token, ConnectStage.CONNECTING)
    api = allOverview.client(id)
    try {
      loadAll(token)
      if (token != generation) return
      if (streamAllowed()) startStream(token)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      if (token == generation) connectTrace.fail(token.toLong(), userFacingError(error, "连接失败"))
      if (token == generation) showOffline(id, token, if (error.isAuthFailure) userFacingError(error) else "连接失败，正在重试")
      scheduleReconnect(id, token, if (error.isAuthFailure) AUTH_RETRY_ATTEMPT else attempt + 1)
    }
  }
  private suspend fun showOffline(id: String, token: Int, reason: String) {
    // 完整性标志需要解密并解析缓存：与读取一起放到 IO，主线程只接收结果。
    val loaded = kotlinx.coroutines.withContext(Dispatchers.IO) {
      val catalog = cache.catalog(id) ?: return@withContext null
      Triple(catalog.first, catalog.second, cache.catalogComplete(id))
    }
    if (token != generation || state.value.serverId != id) return
    if (loaded == null) mutable.update { it.copy(loading = false, connected = false, error = reason) }
    else {
      val (cachedProjects, cachedSessions, catalogComplete) = loaded
      val projects = (cachedProjects + store.localProjects(id))
        .distinctBy { normalizedDirectory(it.directory) }
        .map { it.copy(serverId = id) }
      val sessions = cachedSessions.map { scoped(id, it) }
      val sessionId = store.selectedSession(id)?.takeIf { selected -> sessions.any { it.id == selected } }
      trace(token, ConnectMilestone.CACHE_PAINT)
      mutable.update { it.copy(loading = false, connected = false, cached = true, error = "离线缓存：$reason",
        projects = projects, sessions = sessions, projectId = store.selectedProject(id) ?: projects.firstOrNull()?.id,
        sessionId = sessionId, messages = withPending(id, sessionId, emptyList()), catalogComplete = catalogComplete) }
      if (sessionId != null) {
        val (messages, olderCursor) = cachedTranscriptWindow(id, sessionId)
        val messagesComplete = cachedTranscriptComplete(id, sessionId)
        if (token == generation && state.value.serverId == id && state.value.sessionId == sessionId) mutable.update { withSummary(it.copy(messages = withPending(id, sessionId, messages), cacheComplete = messagesComplete,
          messagesCursor = olderCursor, resources = mapOf("messages" to ResourceStatus(ResourceState.STALE, "离线缓存")))) }
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
    val serverId = state.value.serverId ?: return
    // Probe, contract, catalog and activity are independent reads: one round trip instead of four.
    // The probe is awaited first so a wrong address or old server still reports its own error.
    val firstRound = coroutineScope {
      val versionTask = async { client.health() }
      val capabilitiesTask = async { client.discoverCapabilities() }
      val pageTask = async { attempt { client.rootSessionsPage() } }
      val activeTask = async { attempt { client.activeSessions() } }
      val version = versionTask.await()
      if (token == generation) advanceStage(token, ConnectStage.READING)
      FirstRound(version, capabilitiesTask.await(), pageTask.await().getOrThrow(), activeTask.await())
    }
    val version = firstRound.version
    val capabilities = firstRound.capabilities
    if (token != generation || state.value.serverId != serverId) return
    // The project list is device-local: the user registers a server directory, and the server's own
    // list is never auto-imported. A control-only refresh keeps the current list to avoid flicker.
    val projects = if (controlOnly && state.value.projects.isNotEmpty()) state.value.projects else store.localProjects(serverId).map { it.copy(serverId = serverId) }
    // Official home index: one global, newest-first listing of root sessions (directory filters are
    // exact, so per-project queries would miss worktrees). Reconciliation re-reads its first page too,
    // so a session created elsewhere while an event was missed still appears.
    val page = firstRound.page
    trace(token, ConnectMilestone.CATALOG)
    advanceStage(token, ConnectStage.SYNCING)
    val sessions = page.items.map { scoped(serverId, it) }
    // Activity is authoritative from `/api/session/active`; a failed read keeps last-known tasks.
    val activeResult = firstRound.active
    val active = activeResult.getOrNull()
    if (token != generation || requestSequence != catalogSequence) return
    // First tier done: the catalog is authoritative and the conversation can be read and written now,
    // while the slower control-plane reads below (ancestry, children, shells, permissions, forms) run.
    var earlyFollowUp: Job? = null
    var earlySession: Session? = null
    if (!controlOnly) {
      mutable.update { current -> withSummary(current.copy(version = version, capabilities = capabilities, connected = true, loading = false,
        error = null, lastContactAt = System.currentTimeMillis(),
        sessions = (sessions + current.sessions).distinctBy { it.id }.sortedWith(sessionActivityOrder))) }
      trace(token, ConnectMilestone.INTERACTIVE)
      flushQueuedSends()
      if (followUp) {
        val current = state.value
        earlySession = current.session
        earlyFollowUp = CoroutineScope(currentCoroutineContext()).launch {
          launch { loadChoices(current.homeDirectory.orEmpty(), token) }
          current.session?.let { loadSession(it, token = token) }
        }
      }
    }
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

    // Pending requests only need the running and selected sessions' identities, which are all known
    // now, so they are read while the child and shell discovery below runs instead of after it.
    // Pending requests, read like the official client: permissions per session, forms per location.
    // A directory whose recent read failed (typically a deleted worktree answering 500) is skipped
    // until its retry window opens, so one dead directory cannot degrade the whole list forever.
    val waitingCandidates = (active.orEmpty() + state.value.permissions.map { it.sessionId } + listOfNotNull(state.value.sessionId))
      .distinct().mapNotNull { identities[it] }
    val skippedPermissionSessions = waitingCandidates.filter { directoryHealth.isDown(it.directory) }.map { it.id }.toSet()
    val readableCandidates = waitingCandidates.filterNot { it.id in skippedPermissionSessions }
    val formDirectories = readableCandidates.map { it.directory }.filter(String::isNotBlank).distinctBy(::normalizedDirectory)
      .filterNot { directoryHealth.isDown(it) }
    val pendingReads = CoroutineScope(currentCoroutineContext()).async {
      coroutineScope {
        val permissionTasks = readableCandidates.map { session -> async { session.id to attempt { client.sessionPermissions(session) } } }
        val formTasks = formDirectories.map { directory -> async { directory to attempt { client.forms(directory) } } }
        permissionTasks.awaitAll() to formTasks.awaitAll()
      }
    }

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
      .filter(String::isNotBlank).distinctBy(::normalizedDirectory)
      .filterNot { directoryHealth.isDown(it) }.take(MAX_SHELL_DIRECTORIES)
    val shellResults = coroutineScope { shellDirectories.map { directory -> async { directory to attempt { client.shells(directory) } } }.awaitAll() }
    if (token != generation || requestSequence != catalogSequence) return
    val refreshedShellDirectories = mutableSetOf<String>()
    val freshShells = linkedMapOf<String, ShellJob>()
    shellResults.forEach { (directory, result) ->
      val key = normalizedDirectory(directory)
      if (result.isSuccess) {
        directoryHealth.succeeded(directory)
        refreshedShellDirectories += key
        result.getOrDefault(emptyList()).forEach { freshShells[it.id] = it }
      } else {
        directoryHealth.failed(directory)
        Diagnostics.warn("LagoonController", "shell 列表读取失败：$directory", result.exceptionOrNull())
      }
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
    // Pending requests, read like the official client: permissions per session, forms per location.
    // A directory whose recent read failed (typically a deleted worktree answering 500) is skipped
    // until its retry window opens, so one dead directory cannot degrade the whole list forever.
    val (permissionResults, formResults) = pendingReads.await()
    permissionResults.forEach { (sessionId, result) ->
      val directory = identities[sessionId]?.directory ?: return@forEach
      if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory)
    }
    // Like the official desktop auto-approver, sweep known locations too: external clients can
    // park requests on idle sessions that are absent from session.active and not currently open.
    val sweepDirectories = if (store.permissionPolicy().autoApprove) (identities.values.map { it.directory } + projects.map { it.directory })
      .filter(String::isNotBlank).distinctBy(::normalizedDirectory).filterNot { directoryHealth.isDown(it) } else emptyList()
    val sweptPermissions = sweepDirectories.chunked(4).flatMap { batch -> coroutineScope {
      batch.map { directory -> async { directory to attempt { client.pendingPermissions(directory) } } }.awaitAll()
    } }
    sweptPermissions.forEach { (directory, result) -> if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory) }
    formResults.forEach { (directory, result) -> if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory) }
    if (token != generation || requestSequence != catalogSequence) return
    val failedPermissionSessions = permissionResults.filter { it.second.isFailure }.map { it.first }.toSet() + skippedPermissionSessions
    val failedFormDirs = formResults.filter { it.second.isFailure }.map { normalizedDirectory(it.first) }.toSet()
    val permissions = (permissionResults.mapNotNull { it.second.getOrNull() }.flatten() +
      sweptPermissions.mapNotNull { it.second.getOrNull() }.flatten()).distinctBy { it.id }
    val questions = formResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    store.rememberParents(serverId, sessions + extraSessions)
    // 只有“影响当前可见状态”的读取失败才算降级：活跃会话自身的权限/表单读不到，列表与通知可能
    // 展示旧状态；空闲会话目录的失败（常见于已删除的 worktree）只做目录退避，不再钉住 banner。
    val activeIds = active.orEmpty()
    val activeDirectories = activeIds.mapNotNull { identities[it]?.directory }.map(::normalizedDirectory).toSet()
    val degraded = activeResult.isFailure ||
      permissionResults.any { it.second.isFailure && (it.first in activeIds || normalizedDirectory(identities[it.first]?.directory.orEmpty()) in activeDirectories) } ||
      formResults.any { it.second.isFailure && normalizedDirectory(it.first) in activeDirectories }
    if (degraded) Diagnostics.warn("LagoonController", "部分状态读取失败，保留上次可信状态", activeResult.exceptionOrNull())

    val storedTasks = store.taskStates(serverId)
    var finishedRuns = emptyList<Pair<Session, Long>>()
    // 本次读取前已知的待处理项，用于识别事件流可能漏掉的新权限/表单。
    val priorRequestIds = (state.value.permissions.map { it.id } + state.value.questions.map { it.id }).toSet()
    // 对账期间被权威读取证明已处理的等待项，读取结束后撤掉已发通知（见下方 cancelAttention）。
    var resolvedWaitingIds = emptyList<String>()
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
        // 子代理任务由父会话的工具结果/transcript 派生，没有服务端时间基线（activeAt = -1）。
        val derivedChild = task != null && (task.phase == TaskPhase.SUBAGENT || parentMap[session.id] != null)
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
          // 派生的子任务没有基线可比，但会话不在 active 且服务端已记录结束（time.idle > 0）同样说明
          // 它已经停了——否则 -1 会让子任务永远无法收尾，把根会话永久钉在“后台运行中”。
          task?.active == true && task.background != true && session.idle > 0 && !hasWork &&
            ((task.activeAt >= 0 && session.idle > task.activeAt) || (task.activeAt < 0 && derivedChild)) ->
            TaskReducer.terminal(session.id, session.outcome, task)
          task?.active == true -> task
          // 非活跃的任务不再作为常驻相位保留：终态结果只存在于未读账本（notices）。子会话例外：
          // 留下终态墓碑，防止旧 transcript 的静态 background 元数据把它重新派生为活跃子任务。
          else -> task?.takeIf { it.phase in TaskState.TERMINAL_PHASES && parentMap[session.id] != null }
        }
        if (next != null && next.phase != TaskPhase.IDLE) states[session.id] = next
      }
      // 子会话可能已经不在本页目录里：把之前收尾的子任务墓碑带过去，同样是为了让 transcript
      // 兜底看到“这个子任务已经结束”，而不是每次界面重建都再折算回“后台运行中”。
      previousTasks.forEach { (id, task) ->
        if (id !in states && task.phase in TaskState.TERMINAL_PHASES && parentMap[id] != null) states[id] = task
      }
      // A successful read is authoritative; a failed one keeps the previous requests so a pending
      // approval is never silently dropped.
      val readIds = waitingCandidates.map { it.id }.toSet()
      val changedWhileReading = waitingEventSeqAt.filterValues { it > readWaitingSeq }.keys
      val keptPermissions = previous.permissions.filter { it.sessionId !in readIds || it.sessionId in failedPermissionSessions || it.sessionId in changedWhileReading }
      val fetchedPermissions = permissions.filterNot { it.sessionId in changedWhileReading || it.id in resolvedPermissions }
      val nextPermissions = allOverview?.pending(serverId, (fetchedPermissions + keptPermissions).distinctBy { it.id })
        ?: (fetchedPermissions + keptPermissions).distinctBy { it.id }
      val keptQuestions = previous.questions.filter { active == null || normalizedDirectory(it.directory) in failedFormDirs || it.sessionId in changedWhileReading }
      val fetchedQuestions = questions.filterNot { it.sessionId in changedWhileReading || it.id in resolvedPermissions }
      val nextQuestions = (fetchedQuestions + keptQuestions).distinctBy { it.id }
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
      resolvedWaitingIds = resolvedWaiting
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
        error = null, degraded = degraded, lastContactAt = System.currentTimeMillis(),
        connection = if (previous.connection.stage == ConnectStage.READY) previous.connection else ConnectionStatus(ConnectStage.READY, System.currentTimeMillis()),
        projects = projects, sessions = nextSessions, knownParents = store.taskParents(serverId), tasks = states, shells = nextShells, notices = store.sessionNotices(serverId), collapsedSections = store.collapsedSections(serverId), sessionCursors = cursors, catalogComplete = cursors.isEmpty(),
        permissions = nextPermissions, questions = nextQuestions,
        projectId = (previous.projectId ?: store.selectedProject(serverId))?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id,
        scopeProjectId = previous.scopeProjectId?.takeIf { id -> projects.any { it.id == id } },
        sessionId = (previous.sessionId ?: store.selectedSession(serverId))?.takeIf { id -> nextSessions.any { it.id == id } }))
    }
    trace(token, ConnectMilestone.INTERACTIVE)
    trace(token, ConnectMilestone.CONTROL)
    flushQueuedSends()
    // 权威读取证明已处理的等待项：撤掉可能已发出的“需要授权 / 等待回答”通知。
    resolvedWaitingIds.forEach(::cancelAttention)
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
    // 没有项目/会话时传空目录，让服务器按自己的默认工作区返回模型目录。它们与会话内容已在第一层读完后
    // 并行开始；这里只补上当时还不认识的会话（例如只能从祖先链补读到的会话）。
    earlyFollowUp?.join()
    if (earlySession == null) current.session?.let { loadSession(it, token = token) }
    prefetchTranscripts(token, client)
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
  fun loadOlderMessages(): Job {
    val cursor = state.value.messagesCursor
    if (transcriptSync != null && TranscriptSync.localId(cursor) != null) return loadStoredOlderMessages(cursor!!)
    return loadServerOlderMessages()
  }
  /** Older history the device already holds: no connection needed, so it also works offline. */
  private fun loadStoredOlderMessages(cursor: String): Job {
    val current = state.value
    val serverId = current.serverId ?: return scope.launch { }
    val session = current.session ?: return scope.launch { }
    val revision = selectionRevision
    val key = operationKey(serverId, session.id, current.projectId, "messages-more")
    if (key in current.pendingOperations) return scope.launch { }
    mutable.update { it.copy(pendingOperations = it.pendingOperations + key) }
    return scope.launch {
      try {
        val older = transcriptSync!!.loadOlder(api, serverId, session, cursor)
        if (state.value.serverId == serverId && selectionRevision == revision && state.value.messagesCursor == cursor) mutable.update {
          it.copy(messages = (older.messages + it.messages).distinctBy { m -> m.id }, messagesCursor = older.next,
            loadedOlderMessages = true, historyRevision = it.historyRevision + 1)
        }
      } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
        Diagnostics.warn("TranscriptSync", "读取本机历史失败", error)
      } finally {
        mutable.update { it.copy(pendingOperations = it.pendingOperations - key) }
      }
    }
  }
  private fun loadServerOlderMessages() = withSession("messages-more") { op, client, session ->
    val cursor = op.snapshot.messagesCursor ?: return@withSession
    val sync = transcriptSync?.takeIf { client.cacheableTranscripts }
    // Stored history first (no network), then the server; server pages extend the stored run.
    val page = if (sync != null) sync.loadOlder(client, op.serverId, session, cursor).let { ApiPage(it.messages, it.next) }
      else client.messagesPage(session.id, cursor, HarnessClient.HISTORY_PAGE)
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
    connectTrace.begin(token.toLong(), "刷新")
    advanceStage(token, ConnectStage.CONNECTING)
    return scope.launch {
      try {
        loadAll(token)
        // A refresh after an offline start must also (re)establish the live event stream, otherwise
        // the UI shows connected=true with no realtime updates (A10).
        if (token == generation && stream?.isActive != true && streamAllowed()) startStream(token)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (token == generation) connectTrace.fail(token.toLong(), userFacingError(error, "刷新失败"))
        if (token == generation) mutable.update { state -> state.copy(error = userFacingError(error, "刷新失败"),
          connection = if (state.connection.stage == ConnectStage.READY) state.connection
            else ConnectionStatus(ConnectStage.RETRYING, System.currentTimeMillis(), state.connection.retryAt, retryReason(userFacingError(error, "刷新失败")))) }
      }
    }.also { refresh = it }
  }

  /**
   * The controller's own fallback reconcile only runs while the app is visible or the monitoring
   * service is up. The process-level SSE subscriptions in AllServersOverview stay connected, so
   * background notifications still work; this only stops the extra polling pass.
   */
  private fun streamAllowed() = foreground || monitoring

  /** The app came to the foreground: re-read the server unless that just happened, and resume live events. */
  fun onForeground() {
    foreground = true
    backgroundStop?.cancel()
    // After a long pause the OS may have frozen or silently dropped the sockets; reconnect rather than
    // trust a stream that only looks open.
    val away = backgroundedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0
    backgroundedAt = 0
    if (away > STREAM_RESUME_RESTART_MILLIS) allOverview.restartStreams()
    allOverview.start(store.profiles(), include = setOfNotNull(state.value.serverId))
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

  /** The app left the foreground: process-level connections keep running, the fallback reconcile pauses. */
  fun onBackground() {
    foreground = false
    backgroundedAt = System.currentTimeMillis()
  }

  /** Called by [TaskMonitorService]: while it runs, the process (and the live stream) may stay up in background. */
  fun setMonitoring(active: Boolean) {
    monitoring = active
    if (active && stream?.isActive != true && state.value.connected) startStream(generation)
    if (!active) monitorRequested.remove(TaskNotifications.ALL_SERVERS_ID)
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
          .onFailure {
            // 一次启动失败（例如前台服务启动窗口受限）不能把本轮监控永久锁死，下一轮状态变化再试。
            monitorRequested.remove(TaskNotifications.ALL_SERVERS_ID)
            Diagnostics.warn("Monitor", "无法启动全部服务器任务监控", it)
          }
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
        if (token != generation || refresh?.isActive == true || !streamAllowed()) continue
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
        mutable.update { withSummary(it.copy(questions = (it.questions.filterNot { old -> old.id == request.id } + request))) }
        notifyAttention(request.sessionId)
      }
      "permission.replied" -> {
        val requestId = props.str("requestID").ifBlank { props.str("id") }
        permissionResolved(sessionId, requestId)
        cancelAttention(sessionId)
      }
      "form.replied", "form.cancelled" -> {
        val requestId = props.str("id").ifBlank { props.str("requestID") }
        questionResolved(sessionId, requestId)
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
        state.value.serverId?.let { server ->
          transcriptMemory.forget(server, sessionId)
          forgetStoredTranscript(server, sessionId)
          store.forgetSession(server, sessionId)
          attempt { notifications.cancel(server, sessionId) }
        }
        mutable.update { it.copy(
          sessions = it.sessions.filterNot { session -> session.id == sessionId },
          tasks = it.tasks - sessionId,
          permissions = it.permissions.filterNot { request -> request.sessionId == sessionId },
          questions = it.questions.filterNot { request -> request.sessionId == sessionId },
          notices = it.notices.filterNot { notice -> notice.sessionId == sessionId }) }
      }
      "project.updated" -> {
        val project = props.toProject()
        val server = state.value.serverId
        if (project.id.isNotBlank()) mutable.update { current ->
          val updated = current.projects.map {
            if (it.id == project.id) project.copy(sandboxes = project.sandboxes.ifEmpty { it.sandboxes }, serverId = server) else it
          }
          if (server != null) store.rememberLocalProjects(server, updated)
          current.copy(projects = updated)
        }
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
        val pending = attempt { client?.sessionPermissions(session) }.getOrNull()
        if (generation != token || state.value.serverId != serverId) return@launch
        if (pending != null && pending.none { it.id == permission.id }) { permissionResolved(sessionId, permission.id); return@launch }
      }
      if (permission != null && state.value.visiblePermissions.none { it.id == permission.id }) return@launch
      val question = current.questions.firstOrNull { it.sessionId == sessionId }
      notifications.show(profile, session, task, permission, dedupeId = permission?.id ?: question?.id)
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
        val pending = attempt { client.sessionPermissions(session) }.getOrNull()
        if (store.profiles().none { it.id == server } || state.value.serverId == server || allOverview.client(server) !== client) return@launch
        if (pending != null && pending.none { it.id == permission.id }) { allOverview.settled(server, permission.id); allOverview.refresh(server); return@launch }
        if (!state.value.permissionPolicy.visible(permission.id, allSnapshots[server]?.autoApprovalFailures.orEmpty())) return@launch
      }
      notifications.show(snapshot.profile, session, TaskState(sessionId,
        if (permission != null) TaskPhase.WAITING_PERMISSION else TaskPhase.WAITING_QUESTION), permission,
        dedupeId = permission?.id ?: question?.id)
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

  private fun questionResolved(sessionId: String, requestId: String) {
    resolvedPermissions += requestId
    while (resolvedPermissions.size > 1000) resolvedPermissions.remove(resolvedPermissions.first())
    waitingEventSeqAt[sessionId] = ++waitingEventSeq
    mutable.update { current ->
      val pending = current.questions.filterNot { it.id == requestId }
      val task = current.tasks[sessionId]
      val restored = if (task?.phase == TaskPhase.WAITING_QUESTION && pending.none { it.sessionId == sessionId })
        task.copy(phase = if (current.permissions.any { it.sessionId == sessionId }) TaskPhase.WAITING_PERMISSION else TaskPhase.THINKING,
          detail = if (current.permissions.any { it.sessionId == sessionId }) "等待权限确认" else TaskState.RUNNING_DETAIL)
      else task
      withSummary(current.copy(questions = pending, tasks = if (restored == null) current.tasks else current.tasks + (sessionId to restored)))
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
  private suspend fun findSession(client: HarnessClient, id: String): Session? = try { client.session(id).takeIf { it.id == id } }
    catch (error: ApiException) { if (error.status == 404) null else throw error }
  fun selectSession(id: String) {
    val session = mutable.value.sessions.firstOrNull { it.id == id } ?: return
    selectionRevision += 1
    openTrace.begin(selectionRevision, "打开会话")
    val project = resolveSessionProject(session, mutable.value.projects)
    val serverId = state.value.serverId ?: return
    returnQueuedToDrafts { it.serverId == serverId && it.sessionId == id }
    val outgoing = mutable.value
    outgoing.sessionId?.takeIf { it != id && outgoing.provisionalMessageIds.isEmpty() }?.let { transcriptMemory.put(serverId, it, outgoing.messages.persistable()) }
    val configuration = store.configuration(serverId, id)
    // 同一会话被重新选中（通知/深链）时保留输入框里的最新文字，避免回退尚未落盘（约 350ms 防抖）的输入。
    // 草稿与文件引用要经 Keystore 解密，放到 [restoreComposerInput] 异步填回：点开会话的第一帧不等它。
    val reselected = mutable.value.sessionId == id
    val draft = if (reselected) mutable.value.draft else ""
    val references = if (reselected) mutable.value.references else emptyList()
    visibleConversation = null
    val offline = mutable.value.cached && !mutable.value.connected
    val messages = withPending(serverId, id, emptyList())
    mutable.update { withSummary(it.copy(projectId = project?.id, sessionId = id, messages = messages,
      agent = configuration.agent ?: session.agent, model = configuration.model ?: session.model,
      agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = draft, references = references, loadedOlderMessages = false, messagesCursor = null, resources = mapOf("messages" to ResourceStatus(ResourceState.LOADING)),
      provisionalMessageIds = emptySet(), transcriptLoadingSince = if (offline) 0 else System.currentTimeMillis(),
      children = emptyList(), changes = emptyList(), files = emptyList(),
      searchResults = emptyList(), fileText = null, fileBinary = false)) }
    store.rememberLocation(project?.id, id)
    transcripts?.protectedSession = serverId to id
    restoreComposerInput(serverId, id, selectionRevision)
    if (offline) {
      val serverId = state.value.serverId ?: return
      val revision = selectionRevision
      scope.launch {
        val (cachedMessages, olderCursor) = cachedTranscriptWindow(serverId, id)
        val complete = storesTranscripts(serverId)
        if (state.value.serverId == serverId && selectionRevision == revision && cachedMessages.isNotEmpty()) openTrace.mark(revision, ConnectMilestone.CACHE_PAINT)
        if (state.value.serverId == serverId && selectionRevision == revision) mutable.update { it.copy(messages = cachedMessages, cacheComplete = complete, messagesCursor = olderCursor,
          resources = it.resources + ("messages" to ResourceStatus(if (cachedMessages.isEmpty()) ResourceState.ERROR else ResourceState.STALE, "离线缓存"))) }
      }
    } else {
      val token = generation
      showCachedTranscript(serverId, id, selectionRevision)
      // Choices and the session transcript are independent; run them concurrently.
      scope.launch { coroutineScope { launch { loadChoices(session.directory, token) }; loadSession(session, token = token) } }
    }
  }
  /**
   * 解密并填回 [selectSession] 留空的草稿与文件引用。只填仍为空的字段：解密完成前用户已经输入或添加的
   * 内容以用户为准；期间又切走了会话则丢弃结果。
   */
  private fun restoreComposerInput(serverId: String, sessionId: String, revision: Long) {
    scope.launch {
      val (draft, references) = kotlinx.coroutines.withContext(Dispatchers.IO) { store.draft(serverId, sessionId) to store.references(serverId, sessionId) }
      if (selectionRevision != revision || state.value.serverId != serverId || state.value.sessionId != sessionId) return@launch
      mutable.update { it.copy(draft = it.draft.ifEmpty { draft }, references = it.references.ifEmpty { references }) }
    }
  }
  private suspend fun loadSession(session: Session, ancillary: Boolean = true, token: Int = generation, client: HarnessClient? = api) {
    val client = client ?: return
    val serverId = state.value.serverId ?: return
    val revision = selectionRevision
    val requestSequence = ++messageSequence
    fun current() = token == generation && revision == selectionRevision && state.value.sessionId == session.id && state.value.executionDirectory == session.directory && requestSequence == messageSequence
    val showingCache = state.value.provisionalMessageIds.isNotEmpty()
    if (current() && (state.value.messages.isEmpty() || showingCache)) mutable.update { it.copy(resources = it.resources + ("messages" to ResourceStatus(ResourceState.LOADING)),
      transcriptLoadingSince = it.transcriptLoadingSince.takeIf { since -> since > 0 } ?: System.currentTimeMillis()) }
    // Rows painted from the cache are a placeholder: the read starts from a fresh initial window.
    val initialWindow = (state.value.messages.isEmpty() || showingCache) && !state.value.loadedOlderMessages
    var nextCursor: String? = null
    // Wire form of the fetched window (oldest first), kept for the transcript store.
    var records: List<MessageRecord> = emptyList()
    // OpenCode returns `cursor.next` for every non-empty page; a short page is what marks the session start.
    var reachedStart = false
    var opened: TranscriptSync.Window? = null
    val sync = transcriptSync?.takeIf { client.cacheableTranscripts }
    // Taken before the read: a deletion while it is in flight makes its write a no-op.
    val epoch = sync?.epoch(serverId, session.id) ?: 0L
    // The newest page of the projected timeline plus admitted-but-undelivered input, like the official
    // client's message and pending syncs.
    val (result, pending) = coroutineScope {
      val page = async { attempt {
        // A cacheable conversation opens from its verified stored run plus only what is new on the server.
        if (sync != null && initialWindow) sync.open(client, serverId, session).also { opened = it; nextCursor = it.olderCursor }.messages
        else client.messageRecords(session.id).let { nextCursor = it.next; records = it.items; reachedStart = it.items.size < HarnessClient.MESSAGE_PAGE; it.items.map(MessageRecord::message) }
      } }
      val inbox = async { attempt { client.inbox(session.id) } }
      page.await() to inbox.await().getOrDefault(emptyList())
    }
    if (token != generation) return
    // Cache-painted rows are replaced by the server page; rows streamed in meanwhile are kept.
    val previousMessages = state.value.provisionalMessageIds.let { provisional -> state.value.messages.filterNot { it.id in provisional } }
    val messages = result.map { first ->
      // Official initial window: keep pulling older pages until the oldest boundary is a whole turn,
      // so the first screen never starts mid-reply. Bounded so a refresh cannot burst unbounded.
      val fetched = if (!initialWindow || opened != null) first else {
        val merged = first.toMutableList()
        var cursor = nextCursor
        var extra = 0
        while (cursor != null && extra < MAX_INITIAL_PAGES && needsOlderTurnRoot(merged) && current()) {
          val older = attempt { client.messageRecords(session.id, cursor, HarnessClient.MESSAGE_PAGE) }.getOrElse { break }
          if (older.items.isEmpty()) { nextCursor = older.next; reachedStart = true; break }
          merged.addAll(0, older.items.map(MessageRecord::message))
          records = older.items + records
          reachedStart = older.items.size < HarnessClient.MESSAGE_PAGE
          nextCursor = older.next
          cursor = older.next
          extra++
        }
        merged
      }
      // Merge by id so a tail refresh keeps older pages the reader already loaded and any
      // streaming/optimistic entry the server page has not caught up with yet.
      withPending(serverId, session.id, mergeTranscript(previousMessages.takeIf { current() }.orEmpty(), fetched, pending))
    }.getOrElse { error ->
      // Shown in place (“保留上次数据 · …”); a transcript read failure is not a global error toast.
      Diagnostics.warn("LagoonController", "会话消息读取失败", error)
      val fallback = cachedTranscript(serverId, session.id)
      if (current()) mutable.update { it.copy(cached = fallback.isNotEmpty(), cacheComplete = false, provisionalMessageIds = emptySet(), transcriptLoadingSince = 0,
        resources = it.resources + ("messages" to ResourceStatus(if (fallback.isEmpty()) ResourceState.ERROR else ResourceState.STALE, userFacingError(error)))) }
      if (fallback.isEmpty()) return else fallback
    }
    if (result.isSuccess) {
      if (sync == null) cache.saveMessages(serverId, session.id, messages.persistable(), complete = nextCursor == null)
      else if (opened == null) {
        // A tail refresh: fold the newest page into the stored run (never across a gap).
        val window = records
        val older = nextCursor.takeUnless { reachedStart }
        val start = reachedStart
        scope.launch(storeDispatcher) { attempt { sync.absorb(serverId, session, epoch, window, older, start) } }
      }
      opened?.let {
        Diagnostics.info("TranscriptSync", "打开会话 · ${it.outcome} · 请求 ${it.requests} · 新增 ${it.added}")
        if (it.outcome == TranscriptSync.Outcome.VERIFIED || it.outcome == TranscriptSync.Outcome.CORRECTED) sync?.let { s -> scheduleFullCheck(s, client, serverId, session, token) }
      }
    }
    if (!current()) return
    if (result.isSuccess) {
      trace(token, ConnectMilestone.TRANSCRIPT)
      openTrace.mark(revision, ConnectMilestone.TRANSCRIPT)?.let { Diagnostics.info("ConnectTrace", "打开会话 · 会话内容 ${it}ms") }
    }
    val preview = messages.sessionPreview()
    if (result.isSuccess) store.rememberPreview(serverId, session.id, preview)
    if (result.isSuccess) transcriptMemory.put(serverId, session.id, messages.persistable())
    val latestAgent = messages.asReversed().firstNotNullOfOrNull { it.agent } ?: session.agent
    val latestModel = messages.asReversed().firstNotNullOfOrNull { it.model } ?: session.model
    // 缓存完整性标志解密+解析缓存体，不能放在主线程的状态提交里。
    val cachedComplete = if (result.isSuccess) true else cachedTranscriptComplete(serverId, session.id)
    mutable.update { withSummary(it.copy(messages = withPending(serverId, session.id, messages), cached = result.isFailure, messagesCursor = if (it.loadedOlderMessages) it.messagesCursor else nextCursor, cacheComplete = cachedComplete,
      provisionalMessageIds = emptySet(), transcriptLoadingSince = 0,
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
    // 发送中的请求已捕获引用快照；此处不再静默忽略，否则文件面板会“点了没反应”。
    mutable.update { it.copy(references = (it.references + FileReference(path, mime)).distinctBy { ref -> ref.path }) }; rememberReferences()
  }
  fun removeReference(path: String) { mutable.update { it.copy(references = it.references.filterNot { ref -> ref.path == path }) }; rememberReferences() }
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
    val resolved = (existing ?: project).copy(sandboxes = project.sandboxes.ifEmpty { existing?.sandboxes.orEmpty() }, serverId = op.serverId)
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
      mutable.update { it.copy(error = userFacingError(error, "无法打开目录"), resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
      return@launch
    }
    mutable.update { it.copy(browsePath = "", browseEntries = emptyList(), browseSearch = emptyList(), browseShowHidden = false, resources = it.resources + ("browse" to ResourceStatus(ResourceState.LOADING))) }
    val root = attempt { op.client.projectAt("") }.getOrNull()?.directory?.takeIf(String::isNotBlank)
      ?: state.value.homeDirectory ?: "/"
    loadBrowse(op, root)
  }
  fun browseDirectory(path: String): Job = operationScope.launch {
    val op = attempt { beginOperation(path) }.getOrElse { error ->
      mutable.update { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
      return@launch
    }
    mutable.update { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.LOADING)), browseSearch = emptyList()) }
    loadBrowse(op, path)
  }
  private suspend fun loadBrowse(op: OperationContext, path: String) {
    val entries = attempt { op.client.files(path, ".", op.snapshot.session) }.getOrElse { error ->
      op.commit { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
      return
    }
    op.commit { it.copy(browsePath = path,
      browseEntries = entries.distinctBy { it.path }.sortedWith(compareBy({ it.type != "directory" }, { it.path.lowercase() })), browseSearch = emptyList(),
      resources = it.resources + ("browse" to ResourceStatus(if (entries.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
  }
  fun searchDirectories(query: String): Job {
    val request = ++searchSequence
    val text = query.trim()
    if (text.length < 2) {
      mutable.update { it.copy(browseSearch = emptyList(), resources = it.resources + ("browse-search" to ResourceStatus(ResourceState.EMPTY))) }
      return operationScope.launch { }
    }
    val path = state.value.browsePath ?: return operationScope.launch { }
    mutable.update { it.copy(browseSearch = emptyList(), resources = it.resources + ("browse-search" to ResourceStatus(ResourceState.LOADING))) }
    return operationScope.launch {
      val op = attempt { beginOperation(path) }.getOrElse { return@launch }
      delay(250)
      if (request != searchSequence) return@launch
      val results = attempt { op.client.searchFiles(path, text, op.snapshot.session) }.getOrElse { error ->
        if (request == searchSequence) op.commit { it.copy(resources = it.resources + ("browse-search" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
        return@launch
      }
      if (request == searchSequence) op.commit { it.copy(browseSearch = results.distinct(), resources = it.resources + ("browse-search" to ResourceStatus(if (results.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
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
   * 首页「全部已读」：把当前首页范围内的本机结果账本全部标为已读，并撤下对应的结果通知。
   *
   * 单服务器模式只作用于首页显示的那台服务器；「全部」模式作用于所有已监视服务器。它不写服务端，
   * 也不改变运行中/待处理状态；同一会话正在等待授权或回答时保留其通知。
   */
  fun markAllRead() {
    val current = state.value
    val snapshots = current.homeReadSnapshots()
    val unseen = snapshots.mapValues { (_, snapshot) -> snapshot.notices.filterNot { it.viewed } }
      .filterValues { it.isNotEmpty() }
    if (unseen.isEmpty()) return
    var cleared = 0
    val noticesByServer = mutableMapOf<String, List<SessionNotice>>()
    unseen.forEach { (server, notices) ->
      val sessions = notices.map { it.sessionId }.distinct()
      cleared += sessions.size
      noticesByServer[server] = store.viewAllNotices(server)
      snapshots.getValue(server).resultNotificationSessions(sessions.toSet()).forEach { session ->
        attempt { notifications.cancel(server, session) }
      }
    }
    if (noticesByServer.isEmpty()) return
    noticesByServer.forEach { (server, notices) ->
      val snapshot = snapshots[server] ?: return@forEach
      allSnapshots[server] = snapshot.copy(notices = notices,
        summary = snapshot.copy(notices = notices).displaySummary(current.permissionPolicy))
    }
    mutable.update { latest ->
      val next = noticesByServer[latest.serverId]?.let { withSummary(latest.copy(notices = it)) } ?: latest
      next.copy(allServerSnapshots = allSnapshots.toMap(),
        message = "已将 $cleared 个会话标记为已读")
    }
  }
  /**
   * 独立的「服务端已读」：对当前首页范围内、支持 `POST /api/session/{id}/view` 且已连接的服务器，
   * 把本机结果账本里已结束但 `time.viewed < time.idle` 的主会话按精确 idle 写回服务端。它不清本机
   * 未读，也不发通知；因此可以先清本机、之后再同步服务端，或只同步服务端给其他客户端看。
   */
  fun markServerRead() {
    if (serverReadJob?.isActive == true) return
    val current = state.value
    val targets = current.serverReadTargets()
    if (targets.isEmpty()) {
      mutable.update { it.copy(message = "没有需要同步的服务端已读") }
      return
    }
    val clients = targets.keys.associateWith { server ->
      if (server == current.serverId) api else attempt { allOverview.client(server) }.getOrNull()
    }
    mutable.update { it.copy(serverReadPending = true) }
    serverReadJob = operationScope.launch {
      var succeeded = 0
      var failed = 0
      val acknowledged = mutableMapOf<String, MutableMap<String, Long>>()
      try {
        targets.forEach { (server, sessions) ->
          val client = clients[server]
          if (client == null) {
            failed += sessions.size
            return@forEach
          }
          sessions.chunked(4).forEach { batch ->
            val results = coroutineScope {
              batch.map { session -> async { session to attempt { client.viewSession(session, session.idle) } } }.awaitAll()
            }
            results.forEach { (session, result) ->
              result.fold(
                onSuccess = {
                  succeeded += 1
                  acknowledged.getOrPut(server) { mutableMapOf() }[session.id] = session.idle
                },
                onFailure = { Diagnostics.warn("SessionView", "服务端已读同步失败：$server/${session.id}", it); failed += 1 }
              )
            }
          }
        }
        applyServerRead(acknowledged)
        mutable.update {
          it.copy(
            message = when {
              failed == 0 -> "服务端已读已同步 $succeeded 个会话"
              succeeded == 0 -> null
              else -> "服务端已读：$succeeded 个成功，$failed 个失败"
            },
            error = if (succeeded == 0 && failed > 0) "服务端已读同步失败" else it.error
          )
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        Diagnostics.warn("SessionView", "服务端已读同步失败", error)
        mutable.update { it.copy(error = "服务端已读同步失败") }
      } finally {
        mutable.update { it.copy(serverReadPending = false) }
      }
    }
  }
  /** Locally reflect acknowledged idle timestamps; a newer idle is never acknowledged by an older request. */
  private fun applyServerRead(acknowledged: Map<String, Map<String, Long>>) {
    if (acknowledged.isEmpty()) return
    val current = state.value
    val sources = current.serverSnapshots()
    val updated = mutableMapOf<String, List<Session>>()
    acknowledged.forEach { (server, idles) ->
      val snapshot = sources[server] ?: return@forEach
      val sessions = snapshot.sessions.map { session ->
        val idle = idles[session.id]
        if (idle != null && session.idle == idle && session.viewed < idle) session.copy(viewed = idle) else session
      }
      updated[server] = sessions
      allSnapshots[server] = snapshot.copy(sessions = sessions)
    }
    if (updated.isEmpty()) return
    mutable.update { latest ->
      latest.copy(sessions = updated[latest.serverId] ?: latest.sessions,
        allServerSnapshots = allSnapshots.toMap())
    }
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
  private data class OperationContext(val token: Int, val serverId: String, val client: HarnessClient,
    val revision: Long, val snapshot: LagoonState) {
    fun connectionCurrent(c: LagoonController) = token == c.generation && c.state.value.serverId == serverId && c.api === client
    fun isCurrent(c: LagoonController) = connectionCurrent(c) && revision == c.selectionRevision &&
      c.state.value.projectId == snapshot.projectId && c.state.value.sessionId == snapshot.sessionId && c.state.value.executionDirectory == snapshot.executionDirectory
  }
  private fun beginOperation(targetDirectory: String? = null): OperationContext {
    val current = state.value
    check(current.connected) { "数据尚未同步，请重新连接后操作" }
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
  fun send(text: String, expectedServer: String? = state.value.serverId, expectedSession: String? = state.value.sessionId, accepted: (() -> Unit)? = null): Job =
    deliver(text, expectedServer, expectedSession, accepted, onFailed = null)
  /** [send] with [onFailed] run when the server refuses the prompt (a held prompt then returns to the draft). */
  private fun deliver(text: String, expectedServer: String?, expectedSession: String?, accepted: (() -> Unit)?, onFailed: (() -> Unit)?): Job {
    if (state.value.serverId != expectedServer || state.value.sessionId != expectedSession) {
      // 静默丢弃会让用户以为已经发出；明确提示并要求重试。
      mutable.update { it.copy(error = "当前会话已切换，消息未发送，请重试") }
      return operationScope.launch { }
    }
    if (state.value.canQueueSend) {
      queuePrompt(text, accepted)
      return operationScope.launch { }
    }
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
        onFailed?.invoke()
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
    forgetStoredTranscript(op.serverId, session.id)
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
  /** Notification execution uses its own immutable client. Reconcile both the matching UI state and the overview connection. */
  fun notificationCompleted(serverId: String, permissionId: String? = null) {
    scope.launch {
      if (permissionId != null) allOverview.settled(serverId, permissionId)
      else allOverview.refresh(serverId)
      if (state.value.serverId == serverId) reload()
    }
  }
  fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) = act("question:${request.id}", request.directory) { op ->
    check(request in op.snapshot.questions) { "问题已变化，请刷新" }
    op.client.replyQuestion(request, answers)
    if (op.connectionCurrent(this)) questionResolved(request.sessionId, request.id)
    cancelAttention(request.sessionId)
  }
  fun rejectQuestion(request: QuestionRequest) = act("question:${request.id}", request.directory) { op ->
    check(request in op.snapshot.questions) { "问题已变化，请刷新" }
    op.client.rejectQuestion(request)
    if (op.connectionCurrent(this)) questionResolved(request.sessionId, request.id)
    cancelAttention(request.sessionId)
  }
  fun listFiles(path: String = "."): Job {
    val request = ++fileSequence
    mutable.update { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.LOADING)), searchResults = emptyList()) }
    ++searchSequence
    return act { op ->
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val files = try { op.client.files(directory, path, op.snapshot.session) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
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
      val content = try { op.client.fileContent(directory, path, op.snapshot.session) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
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
      val results = try { op.client.searchFiles(directory, query.trim(), op.snapshot.session) } catch (error: Exception) {
        if (request == searchSequence) op.commit { it.copy(resources = it.resources + ("search" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
        throw error
      }
      if (request == searchSequence) op.commit { it.copy(searchResults = results,
        resources = it.resources + ("search" to ResourceStatus(if (results.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  private fun withSession(action: String, target: String? = null, block: suspend (OperationContext, HarnessClient, Session) -> Unit) = act(action) { op ->
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
        mutable.update { it.copy(error = userFacingError(error), resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.ERROR, userFacingError(error))), pendingOperations = it.pendingOperations - setOfNotNull(key)) }
        return@launch
      }
      try {
        block(op)
        if (key != null && op.isCurrent(this@LagoonController)) mutable.update {
          it.copy(resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.READY)))
        }
      } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
        if (op.connectionCurrent(this@LagoonController)) mutable.update { it.copy(error = userFacingError(error),
          resources = if (key == null || !op.isCurrent(this@LagoonController)) it.resources else it.resources + ("action:$action" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
      } finally {
        if (key != null) mutable.update { it.copy(pendingOperations = it.pendingOperations - key) }
      }
    }
  }
  private inline fun <T> attempt(block: () -> T): Result<T> = try { Result.success(block()) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { Result.failure(error) }
}

/** The four independent reads of a control-plane load's first round trip. */
private data class FirstRound(val version: String, val capabilities: ApiCapabilities, val page: ApiPage<Session>, val active: Result<Set<String>>)

/** A prompt held while its server is unreachable (see `LagoonController.queuePrompt`). */
private data class QueuedPrompt(val serverId: String, val sessionId: String, val text: String, val messageId: String, val created: Long) {
  fun row() = Message(messageId, "user", created, listOf(MessagePart("$messageId:text", "text", text = text)), queued = true, type = "user")
}

/** Id prefix of “等待连接后发送” rows; they exist only on this device and are never cached. */
internal const val PENDING_PREFIX = "pending:"

/** Rows that may be written to the transcript cache: everything except local pending prompts. */
internal fun List<Message>.persistable(): List<Message> = filterNot { it.id.startsWith(PENDING_PREFIX) }

/** Who a saved server is for the transcript store: a profile edited to another address or account is another server. */
internal fun transcriptIdentity(profile: ServerProfile): String = "${profile.kind.wire}|${profile.url.trim().trimEnd('/').lowercase()}|${profile.username}"
