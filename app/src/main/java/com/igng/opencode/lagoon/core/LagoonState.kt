package com.igng.opencode.lagoon.core

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

data class LagoonState(
  val profiles: List<ServerProfile> = emptyList(),
  /** Saved SSH host metadata; secrets stay in SshHostStore. */
  val sshHosts: List<SshHostConfig> = emptyList(),
  /** Local, user-created device groups. Presentation only; never sent to a server. */
  val deviceGroups: List<DeviceGroup> = emptyList(),
  /** null = the home is not grouped; 全部 is the implicit aggregate. */
  val homeGroupId: String? = null,
  /** True for one launch after a v1 group document was discarded during the v2 migration. */
  val deviceGroupReset: Boolean = false,
  val serverId: String? = null, val version: String = "",
  val connected: Boolean = false, val cached: Boolean = false, val loading: Boolean = false, val error: String? = null,
  /** True when the last catalog refresh could not read every project; some values are last-known. */
  val degraded: Boolean = false,
  val streamConnected: Boolean = false,
  /** Non-error feedback (e.g. a share link); kept separate so it is not rendered as a failure. */
  val message: String? = null,
  val projects: List<Project> = emptyList(), val projectId: String? = null,
  /**
   * Read-only project/workspace titles of the writable server, used only for row labels. This is
   * deliberately separate from [projects]: the registry above drives the scope picker, project
   * counts and the new-session target, while this catalog never filters or creates anything.
   */
  val workspaceCatalog: List<Project> = emptyList(),
  /** Home list filter: null = 全部会话 (default), otherwise one project id. Not the execution target. */
  val scopeProjectId: String? = null,
  /** Device-group workspace filter; only meaningful while [homeGroupId] is set. */
  val scopeWorkspaceKey: String? = null,
  val sessions: List<Session> = emptyList(), val sessionId: String? = null,
  val knownParents: Map<String, String> = emptyMap(),
  val messages: List<Message> = emptyList(), val tasks: Map<String, TaskState> = emptyMap(),
  val permissions: List<PermissionRequest> = emptyList(), val questions: List<QuestionRequest> = emptyList(),
  val changes: List<FileChange> = emptyList(),
  val agents: List<AgentChoice> = emptyList(), val models: List<ModelChoice> = emptyList(), val commands: List<CommandChoice> = emptyList(),
  /** [models] with catalog metadata; visibility follows [ModelVisibility] plus this phone's switches. */
  val modelCatalog: List<ModelInfo> = emptyList(), val modelOverrides: Map<String, Boolean> = emptyMap(),
  /** False means the server's complete catalog is shown; true means [modelOverrides] is an allow-list. */
  val modelVisibilityConfigured: Boolean = false,
  /** Per-server variant selections keyed by provider/model; values are always server-provided ids. */
  val modelVariants: Map<String, String> = emptyMap(), val recentModels: List<String> = emptyList(),
  /** Phone-local defaults for the blank "new session" page; they never rewrite server configuration. */
  val newSessionAgent: String? = null, val newSessionModel: ModelChoice? = null,
  /** Files picked on this phone, per session (`draft` before a session exists), until they are sent. */
  val pendingAttachments: Map<String, List<LocalAttachment>> = emptyMap(),
  val agent: String? = null, val model: ModelChoice? = null,
  val files: List<FileNode> = emptyList(), val filePath: String = ".", val fileText: String? = null, val fileBinary: Boolean = false,
  val filePreview: ByteArray? = null, val filePreviewMime: String? = null,
  /** 添加项目用的远程目录选择器：当前绝对路径、目录项与搜索结果；[browsePath] 为 null 表示未打开。 */
  val browsePath: String? = null, val browseEntries: List<FileNode> = emptyList(), val browseSearch: List<String> = emptyList(),
  /** 目录浏览器是否显示以点开头的隐藏项；默认隐藏，每次重新打开浏览器时复位。 */
  val browseShowHidden: Boolean = false,
  val searchResults: List<String> = emptyList(),
  val capabilities: ApiCapabilities = ApiCapabilities(),
  val previews: Map<String, SessionPreview> = emptyMap(), val resources: Map<String, ResourceStatus> = emptyMap(),
  val pendingOperations: Set<String> = emptySet(),
  val agentChanged: Boolean = false, val modelChanged: Boolean = false,
  /**
   * [draft] is the composer text as last set by the app (restore, queued prompt returned, cleared after
   * send), not every keystroke: typing stays in the composer and [LagoonController.typeDraft], so a key
   * press never re-broadcasts the whole state. [draftRevision] increments on each such set.
   */
  val draftSessions: Set<String> = emptySet(), val draft: String = "", val draftRevision: Long = 0, val references: List<FileReference> = emptyList(),
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
   * 根会话自己没有在跑、但家族里仍有运行中的 shell 或子会话：只用于首页的低调标记和会话内
   * 后台面板，不参与灵动岛计数，也不改变根会话的 [TaskState]。
   */
  val backgroundRunning: Set<String> = emptySet(),
  /**
   * 运行中的 shell 作业（`GET /api/shell` 与 `shell.created/exited/deleted` 事件），按 shell id 索引。
   * 后台 shell 不是会话，[tasks] 里看不到它；它只参与 [LagoonState.backgroundRunning] 与会话内面板。
   */
  val shells: Map<String, ShellJob> = emptyMap(),
  /** 后台 shell 输出查看器；同一时刻只打开一个，关闭后清空。 */
  val shellOutput: ShellOutputView? = null,
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
  /** Home is projecting all saved devices. With groups this is mutually exclusive with [homeGroupId]. */
  val allServers: Boolean = false,
  /** Home filter; the writable conversation's serverId can differ without changing this. */
  val homeServerId: String? = null,
  val permissionPolicy: PermissionPolicy = PermissionPolicy(),
  val autoApprovalFailures: Set<String> = emptySet(),
  /** Wall clock of the last successful control-plane read of the writable server. */
  val lastContactAt: Long = 0,
  /** What the selected server's connection is waiting for; drives the status strip under the header. */
  val connection: ConnectionStatus = ConnectionStatus(),
  /** SSH bootstrap / relay handshake detail, when the transport exposes one. */
  val transportDetail: String? = null,
  /** Set only for a typed Remote failure whose fix needs a UI sheet (login/trust/host). */
  val remoteRecovery: RemoteRecoveryKind? = null,
  val goal: RemoteGoal? = null,
  val goalLoading: Boolean = false,
  val schedules: List<RemoteSchedule> = emptyList(),
  val schedulesLoading: Boolean = false,
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
  /** Lazily computed per state instance; see [homeProjection]. */
  internal var homeProjectionCache: LagoonState? = null
  val server: ServerProfile? get() = profiles.firstOrNull { it.id == serverId }
  /** Connections the user has not disabled. Presentation layers must use this, not [profiles]. */
  val enabledProfiles: List<ServerProfile> by lazy(LazyThreadSafetyMode.PUBLICATION) { profiles.filter { it.enabled } }
  val disabledProfileCount: Int get() = profiles.count { !it.enabled }
  val homeGroup: DeviceGroup? get() = homeGroupId?.let { id -> deviceGroups.firstOrNull { it.id == id } }
  val homeGroupProfiles: List<ServerProfile> get() = homeGroup?.memberIds?.mapNotNull { id -> profiles.firstOrNull { it.id == id && it.enabled } }.orEmpty()
  /** The home is aggregating more than one server: all-servers mode or a device group. */
  val multiServerView: Boolean get() = allServers || homeGroup != null
  val deviceTabs: List<DeviceTab> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    DeviceGroupState(deviceGroups, homeGroupId).tabs(enabledProfiles)
  }
  /**
   * The open conversation can take a prompt now and deliver it once the server answers: the server is
   * still connecting or is between retries, rather than not configured at all.
   */
  val canQueueSend: Boolean get() = !connected && serverId != null && session != null &&
    connection.stage in setOf(ConnectStage.CONNECTING, ConnectStage.READING, ConnectStage.SYNCING, ConnectStage.RETRYING)
  /**
   * Creation target. Registered device-local projects win; for DSH an unregistered server workspace
   * is still a valid target because session creation can pass its opaque `workspaceId` directly.
   * OpenCode never falls back to the server's project list (it is display-only).
   */
  val project: Project? get() = projects.firstOrNull { it.id == projectId }
    ?: workspaceCatalog.firstOrNull { it.id == projectId && (server?.kind == ServerKind.DSH || server?.kind == ServerKind.DSH_REMOTE) }
  // Derived maps below are cached per state instance (copy() makes a new one): the reducers read them
  // once per task on every streamed event, and rebuilding each on every access made one event cost
  // tasks × sessions.
  val session: Session? by lazy(LazyThreadSafetyMode.PUBLICATION) { sessions.firstOrNull { it.id == sessionId } }
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
  /** Root → … → [id] from the parents this device already knows; null when the chain is incomplete. */
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
  val parents: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) { knownParents + sessions.mapNotNull { it.parentId?.let { parent -> it.id to parent } }.toMap() }
  /** Live (running / waiting) state of each session family, rolled up to its root. */
  val presentedTasks: Map<String, TaskState> by lazy(LazyThreadSafetyMode.PUBLICATION) { permissionPolicy.tasks(tasks, permissions, autoApprovalFailures) }
  val visiblePermissions: List<PermissionRequest> by lazy(LazyThreadSafetyMode.PUBLICATION) { permissions.filter { permissionPolicy.visible(it.id, autoApprovalFailures) } }
  val globalSummary: TaskSummary get() {
    val snapshots = serverSnapshots().values
    if (snapshots.isEmpty()) return if (enabledProfiles.isEmpty()) TaskSummary.EMPTY else summary
    // 关闭通知的服务器不进入系统汇总（灵动岛 / 前台服务），但它的数据仍留在应用内首页。
    return TaskSummary.merge(snapshots.filter { it.profile.notifications }.map { it.displaySummary(permissionPolicy) })
  }
  val globalSummaryTarget: SessionTarget? get() = globalSummary.items.firstOrNull()?.let { item -> item.serverId?.let { SessionTarget(it, item.sessionId) } }
  /** Roots with background activity that should keep the low-key monitor notification alive. */
  val globalBackgroundTargets: List<SessionTarget>
    get() = serverSnapshots().values.filter { it.profile.notifications }
      .flatMap { snapshot -> snapshot.backgroundRunning.map { SessionTarget(snapshot.serverId, it) } }
      .distinct()
  val globalBackgroundCount: Int get() = globalBackgroundTargets.size
  val activeRootTasks: Map<String, TaskState> get() = SessionActivityRules.rootStates(livePresentedTasks.filterValues { it.active }, parents)
  /** Running shells and the descendant tree for the open conversation's family. */
  fun sessionActivity(sessionId: String? = this.sessionId): SessionActivity =
    SessionActivityRules.activity(sessionId, sessions, presentedTasks, shells, parents)
  fun sessionStatus(session: Session, active: Map<String, TaskState> = activeRootTasks): SessionStatus {
    if (multiServerView) allSessionStatuses[session.key]?.let { return it }
    // `active` is already the live set: uncertain claims and server-owned work past the offline TTL
    // are excluded, so a row the phone cannot prove is simply idle (or its unread result).
    val present = presentedTasks[session.id]
    val state = if (session.parentId == null) active[session.id] else present?.takeIf { it.active && !it.uncertain } ?: active[session.id]
    return sessionStatus(state, notices.unseenFor(session.id))
  }
}
