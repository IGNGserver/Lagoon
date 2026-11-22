package com.igng.opencode.lagoon.core

import androidx.compose.runtime.Immutable

/** Semantic tone for global toast feedback so non-error notices/warnings are never painted as green success. */
enum class FeedbackTone { INFO, SUCCESS, WARNING }

/** Pending operations and phone attachments are keyed by server/session/project. */
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

@Immutable
data class LagoonState(
  /** 唯一真源的 SSH 设备列表（含每台设备下挂载的 Harness）。 */
  val devices: List<SshDevice> = emptyList(),
  /** 全部 SSH 设备投影出的 Harness 运行资料（由 [devices] 派生）。 */
  val profiles: List<ServerProfile> = emptyList(),
  /** 设备发现缓存更新计数；只用于触发设备页重组。 */
  val discoveryRevision: Int = 0,
  /** 正在运行显式发现的设备 ID 集合。 */
  val deviceRefreshInFlight: Set<String> = emptySet(),
  /** 全部 5 种 Harness 的实时 SSH 链接状态，按 Harness ID 索引。 */
  val harnessLinkStatuses: Map<String, SshLinkStatus> = emptyMap(),
  /** 全局 SSH 密钥库元数据；私钥/口令密文留在 SshKeyStore。 */
  val sshKeys: List<SshKeyProfile> = emptyList(),
  /** 密钥库修订号（增删、锁定、解锁、生物识别过期均递增）。 */
  val sshKeysRevision: Long = 0L,
  /** 本机设备组（按设备 ID 归组，纯展示用）。 */
  val deviceGroups: List<DeviceGroup> = emptyList(),
  /** 当前首页选中的设备组 ID；与 [homeDeviceId] / [allServers] 互斥。 */
  val homeGroupId: String? = null,
  /** 当前首页聚焦的单台 SSH 设备 ID（聚合该设备上全部已启用 Harness）。 */
  val homeDeviceId: String? = null,
  /** 旧版 v1 分组在升级中被重置后提示一次。 */
  val deviceGroupReset: Boolean = false,
  /** 从 v5.3.x 升级到纯 SSH 版本后的一次性下线提示（可关闭）。 */
  val migrationNotice: String? = null,
  val serverId: String? = null,
  val version: String = "",
  val connected: Boolean = false,
  val cached: Boolean = false,
  val loading: Boolean = false,
  /** 一次显式的首页刷新（[LagoonController.reload]）正在跑。 */
  val refreshing: Boolean = false,
  val error: String? = null,
  /** True when the last catalog refresh could not read every project; some values are last-known. */
  val degraded: Boolean = false,
  val streamConnected: Boolean = false,
  /** Non-error feedback; kept separate so it is not rendered as a failure. */
  val message: String? = null,
  val messageTone: FeedbackTone = FeedbackTone.SUCCESS,
  val projects: List<Project> = emptyList(),
  val projectId: String? = null,
  /** Read-only project/workspace titles of the writable server, used only for row labels. */
  val workspaceCatalog: List<Project> = emptyList(),
  /** Home list filter: null = 全部会话 (default), otherwise one project id. Not the execution target. */
  val scopeProjectId: String? = null,
  /** Multi-Harness workspace filter; only meaningful while [multiServerView] is true. */
  val scopeWorkspaceKey: String? = null,
  val sessions: List<Session> = emptyList(),
  val sessionId: String? = null,
  val knownParents: Map<String, String> = emptyMap(),
  val messages: List<Message> = emptyList(),
  val tasks: Map<String, TaskState> = emptyMap(),
  val permissions: List<PermissionRequest> = emptyList(),
  val questions: List<QuestionRequest> = emptyList(),
  val changes: List<FileChange> = emptyList(),
  val agents: List<AgentChoice> = emptyList(),
  val models: List<ModelChoice> = emptyList(),
  val commands: List<CommandChoice> = emptyList(),
  val modelCatalog: List<ModelInfo> = emptyList(),
  val modelOverrides: Map<String, Boolean> = emptyMap(),
  val modelVisibilityConfigured: Boolean = false,
  val modelVariants: Map<String, String> = emptyMap(),
  val recentModels: List<String> = emptyList(),
  val newSessionAgent: String? = null,
  val newSessionModel: ModelChoice? = null,
  val pendingAttachments: Map<String, List<LocalAttachment>> = emptyMap(),
  val agent: String? = null,
  val model: ModelChoice? = null,
  val files: List<FileNode> = emptyList(),
  val filePath: String = ".",
  val fileText: String? = null,
  val fileBinary: Boolean = false,
  val filePreview: ByteArray? = null,
  val filePreviewMime: String? = null,
  val browsePath: String? = null,
  val browseEntries: List<FileNode> = emptyList(),
  val browseSearch: List<String> = emptyList(),
  val browseShowHidden: Boolean = false,
  val searchResults: List<String> = emptyList(),
  val capabilities: ApiCapabilities = ApiCapabilities(),
  val previews: Map<String, SessionPreview> = emptyMap(),
  val resources: Map<String, ResourceStatus> = emptyMap(),
  val pendingOperations: Set<String> = emptySet(),
  val uncertainForks: List<ForkMutationTarget> = emptyList(),
  val agentChanged: Boolean = false,
  val modelChanged: Boolean = false,
  val draftSessions: Set<String> = emptySet(),
  val draft: String = "",
  val draftRevision: Long = 0,
  val references: List<FileReference> = emptyList(),
  val cacheComplete: Boolean = true,
  val sessionCursors: Map<String, String> = emptyMap(),
  val messagesCursor: String? = null,
  val catalogComplete: Boolean = true,
  val loadedOlderMessages: Boolean = false,
  val historyRevision: Int = 0,
  val notices: List<SessionNotice> = emptyList(),
  val collapsedSections: Set<String> = emptySet(),
  val pinned: Set<String> = emptySet(),
  val summary: TaskSummary = TaskSummary.EMPTY,
  val backgroundRunning: Set<String> = emptySet(),
  val shells: Map<String, ShellJob> = emptyMap(),
  val shellOutput: ShellOutputView? = null,
  val summaryTargetId: String? = null,
  val summaryTarget: SessionTarget? = null,
  val allServerSnapshots: Map<String, ServerOverviewSnapshot> = emptyMap(),
  val allSessionStatuses: Map<String, SessionStatus> = emptyMap(),
  val allUnseenSessions: Set<String> = emptySet(),
  val serverReadPending: Boolean = false,
  /** 首页投影全部已启用 Harness。与 [homeGroupId] / [homeDeviceId] 互斥。 */
  val allServers: Boolean = false,
  /** 首页单 Harness 视图过滤器。 */
  val homeServerId: String? = null,
  val permissionPolicy: PermissionPolicy = PermissionPolicy(),
  val autoApprovalFailures: Set<String> = emptySet(),
  val lastContactAt: Long = 0,
  val connection: ConnectionStatus = ConnectionStatus(),
  /** SSH 握手/隧道阶段摘要文案。 */
  val transportDetail: String? = null,
  /** 当前活跃连接需要用户介入处理的 SSH 故障（确认指纹、解锁密钥、检查认证等）。 */
  val sshActionRequired: SshFailure? = null,
  val goal: DshGoal? = null,
  val todos: List<DshTodo> = emptyList(),
  val goalLoading: Boolean = false,
  val schedules: List<DshSchedule> = emptyList(),
  val schedulesLoading: Boolean = false,
  val streamDownSince: Long = 0,
  val provisionalMessageIds: Set<String> = emptySet(),
  val transcriptLoadingSince: Long = 0
) {
  @Volatile internal var homeProjectionCache: Pair<Int, LagoonState>? = null
  @Volatile internal var livePresentedTasksCache: Pair<Boolean, Map<String, TaskState>>? = null
  @Volatile internal var activeRootTasksCache: Pair<Boolean, Map<String, TaskState>>? = null
  internal val cachedServerSnapshots: Map<String, ServerOverviewSnapshot> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    computeServerSnapshots()
  }

  val server: ServerProfile? get() = profiles.firstOrNull { it.id == serverId }
  val activeDevice: SshDevice? get() = server?.deviceId?.let { id -> devices.firstOrNull { it.id == id } }
  val enabledProfiles: List<ServerProfile> by lazy(LazyThreadSafetyMode.PUBLICATION) { profiles.filter { it.effectiveEnabled } }
  val disabledProfileCount: Int get() = profiles.count { !it.effectiveEnabled }
  val homeGroup: DeviceGroup? get() = homeGroupId?.let { id -> deviceGroups.firstOrNull { it.id == id } }
  val homeDevice: SshDevice? get() = homeDeviceId?.let { id -> devices.firstOrNull { it.id == id } }
  val homeGroupProfiles: List<ServerProfile> get() = homeGroup?.memberProfiles(enabledProfiles).orEmpty()
  val homeDeviceProfiles: List<ServerProfile> get() = homeDeviceId?.let { did -> enabledProfiles.filter { it.deviceId == did } }.orEmpty()
  /** 首页处于聚合视图：全部、某个设备组，或聚焦于单台设备。 */
  val multiServerView: Boolean get() = allServers || homeGroup != null || homeDevice != null
  val deviceTabs: List<DeviceTab> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    DeviceGroupState(deviceGroups, homeGroupId).tabs(enabledProfiles)
  }

  val canQueueSend: Boolean get() = !connected && serverId != null && session != null &&
    connection.stage in setOf(ConnectStage.CONNECTING, ConnectStage.READING, ConnectStage.SYNCING, ConnectStage.RETRYING)

  val project: Project? get() = projects.firstOrNull { it.id == projectId }
    ?: workspaceCatalog.firstOrNull { it.id == projectId && server?.protocol == HarnessProtocol.DSH }

  val session: Session? by lazy(LazyThreadSafetyMode.PUBLICATION) { sessions.firstOrNull { it.id == sessionId } }
  val executionDirectory: String? get() = session?.directory ?: project?.directory
  val homeDirectory: String? get() = project?.directory ?: sessions.firstOrNull()?.directory
  val attachments: List<LocalAttachment> get() = pendingAttachments[attachmentKey(sessionId)].orEmpty()
  val visibleModels: List<ModelInfo> get() = ModelVisibility.visibleConfigured(modelCatalog, modelOverrides, modelVisibilityConfigured)

  fun pending(action: String): Boolean {
    val key = operationKey(serverId, sessionId, projectId, action)
    return pendingOperations.contains(key) || (serverId == null && pendingOperations.any { it.endsWith(":$action") })
  }

  fun resource(name: String): ResourceStatus = resources[name] ?: ResourceStatus()
  val olderFromDevice: Boolean get() = TranscriptSync.localId(messagesCursor) != null
  fun title(session: Session): String = session.displayTitle()

  fun lineage(id: String): List<String>? {
    val chain = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var current: String? = id
    while (current != null && seen.add(current) && chain.size < 32) {
      chain += current
      current = parents[current]
    }
    return chain.asReversed().takeIf { chain.isNotEmpty() }
  }

  val parents: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    knownParents + sessions.mapNotNull { it.parentId?.let { parent -> it.id to parent } }.toMap()
  }
  val presentedTasks: Map<String, TaskState> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    permissionPolicy.tasks(tasks, permissions, autoApprovalFailures)
  }
  val visiblePermissions: List<PermissionRequest> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    permissions.filter { permissionPolicy.visible(it, autoApprovalFailures) }
  }

  val globalSummary: TaskSummary by lazy(LazyThreadSafetyMode.PUBLICATION) {
    val snapshots = serverSnapshots().values
    if (snapshots.isEmpty()) {
      if (server?.let { it.effectiveEnabled && it.notifications } == true) summary else TaskSummary.EMPTY
    } else {
      TaskSummary.merge(snapshots.filter { it.profile.notifications }.map { it.displaySummary(permissionPolicy) })
    }
  }
  val globalSummaryTarget: SessionTarget? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    globalSummary.items.firstOrNull()?.let { item -> item.serverId?.let { SessionTarget(it, item.sessionId) } }
  }
  val globalBackgroundTargets: List<SessionTarget> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    serverSnapshots().values.filter { it.profile.notifications }
      .flatMap { snapshot -> snapshot.backgroundRunning.map { SessionTarget(snapshot.serverId, it) } }
      .distinct()
  }
  val globalBackgroundCount: Int get() = globalBackgroundTargets.size

  val activeRootTasks: Map<String, TaskState> get() {
    val stale = staleConnection
    activeRootTasksCache?.takeIf { it.first == stale }?.let { return it.second }
    return SessionActivityRules.rootStates(livePresentedTasks.filterValues { it.active }, parents)
      .also { activeRootTasksCache = stale to it }
  }

  fun sessionActivity(sessionId: String? = this.sessionId): SessionActivity =
    SessionActivityRules.activity(sessionId, sessions, presentedTasks, shells, parents)

  fun sessionStatus(session: Session, active: Map<String, TaskState> = activeRootTasks): SessionStatus {
    if (multiServerView) allSessionStatuses[session.key]?.let { return it }
    val present = presentedTasks[session.id]
    val state = if (session.parentId == null) active[session.id] else present?.takeIf { it.active && !it.uncertain } ?: active[session.id]
    return sessionStatus(state, notices.unseenFor(session.id))
  }
}
