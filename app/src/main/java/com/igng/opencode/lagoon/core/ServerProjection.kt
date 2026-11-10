package com.igng.opencode.lagoon.core

/** The selected connection's offline TTL, shared by home rows, chat subtitle and the island. */
internal val LagoonState.staleConnection: Boolean
  get() = !connected && lastContactAt > 0 &&
    System.currentTimeMillis() - lastContactAt > STALE_CONNECTION_TASK_MILLIS

/** Tasks after applying the same offline TTL as [ServerOverviewSnapshot.displaySummary]. */
internal val LagoonState.livePresentedTasks: Map<String, TaskState>
  get() = if (staleConnection) presentedTasks.filterValues {
    it.phase !in TaskState.RUNNING_PHASES && it.phase != TaskPhase.WAITING_SERVER
  } else presentedTasks.filterValues { !it.uncertain }

/** The local conversation overrides its own snapshot, never another server's state. */
internal fun LagoonState.serverSnapshots(): Map<String, ServerOverviewSnapshot> {
  val enabledIds = enabledProfiles.mapTo(HashSet()) { it.id }
  val visible = if (enabledIds.size == profiles.size) allServerSnapshots
    else allServerSnapshots.filterKeys { it in enabledIds }
  val id = serverId?.takeIf { it in enabledIds } ?: return visible
  val profile = profiles.firstOrNull { it.id == id } ?: return visible
  // The writable connection is authoritative even before its first catalog arrives. Leaving the
  // background snapshot in place made the device list say 离线 while this server was already up.
  if (!connected && sessions.isEmpty() && tasks.isEmpty() && !loading && error.isNullOrBlank() && lastContactAt <= 0) return visible
  return visible + (id to ServerOverviewSnapshot(id, profile, version = version, connected = connected,
    canSyncRead = capabilities.sessionView != null,
    cached = cached, loading = loading, lastReadAt = lastContactAt, error = error, degraded = degraded,
    streamConnected = streamConnected, projects = projects, sessions = sessions, parents = parents, tasks = tasks,
    notices = notices, permissions = permissions, questions = questions, backgroundRunning = backgroundRunning,
    summary = summary, autoApprovalFailures = autoApprovalFailures, shells = shells,
    workspaceCatalog = workspaceCatalog))
}

/**
 * Display-only project/workspace titles for the server a session belongs to. The device-local
 * [LagoonState.projects] registry is deliberately not consulted for other servers: a row must keep
 * the titles of its own server, never borrow the writable server's catalog.
 */
fun LagoonState.workspaceCatalogFor(session: Session): List<Project> {
  val id = session.serverId ?: serverId ?: return emptyList()
  return if (id == serverId) workspaceCatalog else allServerSnapshots[id]?.workspaceCatalog.orEmpty()
}

/** The servers the home page is currently projecting: one server, one device group, or every server. */
internal fun LagoonState.homeReadSnapshots(): Map<String, ServerOverviewSnapshot> {
  val snapshots = serverSnapshots()
  if (allServers) return snapshots
  homeGroup?.let { group ->
    return snapshots.filterKeys { it in group.memberIds }
  }
  val preferred = homeServerId ?: serverId
  val id = preferred?.takeIf { snapshots.containsKey(it) }
    ?: serverId?.takeIf { snapshots.containsKey(it) }
    ?: snapshots.keys.firstOrNull()
    ?: return emptyMap()
  return snapshots[id]?.let { mapOf(id to it) } ?: emptyMap()
}

/** Unread results the home button would clear. Aggregate modes already use server-scoped keys. */
internal fun LagoonState.homeUnseenSessions(): Set<String> =
  if (multiServerView) allUnseenSessions
  else notices.filterNot { it.viewed }.map { it.sessionId }.toSet()

/**
 * The one sync line above the session list, or null when the page does not need a warning.
 *
 * The off-line wording belongs to the page, not to one device: an aggregate with at least one live
 * server is still usable, and a connected server whose *transcript* came from cache (the `cached`
 * flag) is not an off-line catalog. A single off-line member is shown by its device chip instead.
 */
internal fun LagoonState.homeSyncNotice(): String? = when {
  connected && degraded -> "部分数据待同步 · 下拉重试"
  connected -> null
  loading -> null
  enabledProfiles.isEmpty() -> null
  sessions.isNotEmpty() -> "离线缓存 · 下拉重试"
  else -> "尚未连接 · 点按重试"
}

/**
 * The global strip may clear a project/workspace filter and widen a single server to every device.
 * A device group is a container, not a filter: “查看全部” there would silently leave the group.
 */
internal fun LagoonState.showsGlobalViewAll(): Boolean =
  homeGroup == null && (!allServers || scopeProjectId != null || scopeWorkspaceKey != null)

/**
 * Server-scoped candidates for the independent “服务端已读” action. */
internal fun LagoonState.serverReadTargets(): Map<String, List<Session>> =
  homeReadSnapshots().mapNotNull { (server, snapshot) ->
    snapshot.serverReadCandidates().takeIf { it.isNotEmpty() }?.let { server to it }
  }.toMap()

/** Session indicators for every monitored server, using the same offline TTL as the island. */
internal fun LagoonState.allServerStatuses(now: Long = System.currentTimeMillis()): Map<String, SessionStatus> =
  serverSnapshots().values.flatMap { snapshot ->
    snapshot.sessionStatuses(permissionPolicy, now).map { (id, status) -> scopedKey(snapshot.serverId, id) to status }
  }.toMap()

/** Offline-TTL staleness fingerprint so [homeProjectionCache] invalidates once an offline server expires. */
private fun LagoonState.offlineStaleFingerprint(now: Long): Int {
  var hash = if (!connected && lastContactAt > 0 && now - lastContactAt > STALE_CONNECTION_TASK_MILLIS) 1 else 0
  for ((id, snapshot) in allServerSnapshots) {
    val stale = !snapshot.connected && snapshot.lastReadAt > 0 && now - snapshot.lastReadAt > STALE_CONNECTION_TASK_MILLIS
    if (stale) hash = 31 * hash + id.hashCode()
  }
  return hash
}

/** A home filter is a projection, not a connection switch. */
fun LagoonState.homeProjection(now: Long = System.currentTimeMillis()): LagoonState {
  val fingerprint = offlineStaleFingerprint(now)
  homeProjectionCache?.takeIf { it.first == fingerprint }?.let { return it.second }
  return computeHomeProjection(now).also { homeProjectionCache = fingerprint to it }
}

private fun LagoonState.computeHomeProjection(now: Long = System.currentTimeMillis()): LagoonState {
  val snapshots = serverSnapshots()
  val enabledIds = enabledProfiles.mapTo(HashSet()) { it.id }
  if (!allServers) {
    val group = homeGroup
    // Parent gates only hide runtime data; they must not discard the saved group selection.
    if (group != null) return groupHomeProjection(snapshots, group, now)
    if (group == null) {
      val id = (homeServerId ?: serverId)?.takeIf { it in enabledIds }
        ?: serverId?.takeIf { it in enabledIds }
        ?: enabledProfiles.firstOrNull()?.id
        ?: return copy(allServers = true, homeGroupId = null, homeServerId = null, serverId = null,
          sessionId = null, projectId = null, scopeProjectId = null, scopeWorkspaceKey = null,
          sessions = emptyList(), projects = emptyList(), messages = emptyList(), tasks = emptyMap(), permissions = emptyList(),
          questions = emptyList(), notices = emptyList(), knownParents = emptyMap(), backgroundRunning = emptySet(),
          connected = false, loading = false, cached = false, summary = TaskSummary.EMPTY, workspaceCatalog = emptyList(),
          allServerSnapshots = snapshots, allSessionStatuses = emptyMap(), allUnseenSessions = emptySet())
      if (id == serverId) return copy(allServers = false, homeServerId = id, allServerSnapshots = snapshots)
      val snapshot = snapshots[id] ?: return copy(serverId = id, homeServerId = id, version = "", sessions = emptyList(), projects = emptyList(), tasks = emptyMap(),
        permissions = emptyList(), questions = emptyList(), notices = emptyList(), connected = false, streamConnected = false,
        loading = true, cached = false, degraded = false, lastContactAt = 0, error = null, allServers = false,
        summary = TaskSummary.EMPTY, knownParents = emptyMap(), backgroundRunning = emptySet(), shells = emptyMap(),
        autoApprovalFailures = emptySet(), workspaceCatalog = emptyList(), allServerSnapshots = snapshots)
      return copy(serverId = id, homeServerId = id, version = snapshot.version, connected = snapshot.connected, streamConnected = snapshot.streamConnected,
        cached = snapshot.cached, loading = snapshot.loading, degraded = snapshot.degraded, sessions = snapshot.sessions,
        projects = snapshot.projects, tasks = snapshot.tasks, permissions = snapshot.permissions, questions = snapshot.questions,
        notices = snapshot.notices, knownParents = snapshot.parents, backgroundRunning = snapshot.backgroundRunning,
        summary = snapshot.displaySummary(permissionPolicy, now), lastContactAt = snapshot.lastReadAt,
        autoApprovalFailures = snapshot.autoApprovalFailures, sessionId = null, projectId = null, scopeProjectId = null,
        serverReadPending = serverReadPending, workspaceCatalog = snapshot.workspaceCatalog,
        allServerSnapshots = snapshots)
    }
  }
  val statuses = allServerStatuses(now)
  val mergedSummary = if (snapshots.isEmpty()) TaskSummary.EMPTY
    else TaskSummary.merge(snapshots.values.filter { it.profile.notifications }.map { it.displaySummary(permissionPolicy, now) })
  return copy(serverId = null, homeGroupId = null, allServers = true,
    homeServerId = homeServerId?.takeIf { it in enabledIds },
    sessionId = null, projectId = null, scopeProjectId = null, workspaceCatalog = emptyList(),
    sessions = snapshots.values.flatMap { it.sessions }.distinctBy { it.key }.sortedWith(sessionActivityOrder),
    projects = snapshots.values.flatMap { it.projects }.distinctBy { it.key }, tasks = emptyMap(), permissions = emptyList(),
    questions = emptyList(), notices = emptyList(), knownParents = emptyMap(), backgroundRunning = emptySet(),
    // 置顶是“单服务器 + 裸 id”的本地状态；聚合视图按服务器隔离，避免同 id 会话误入置顶。
    // 分页游标只属于可写服务器：更早的会话按产品约定切换到对应单服务器后再加载。
    pinned = emptySet(), sessionCursors = emptyMap(), catalogComplete = true,
    connected = snapshots.values.any { it.connected }, loading = snapshots.values.any { it.loading },
    streamConnected = snapshots.values.any { it.streamConnected },
    // “离线”与“数据待同步”是两回事：某台服务器没连上时由 chip 的状态点表达，不再把整页钉在
    // “部分数据待同步”上；只有已连接服务器上影响可见状态的读取失败才计 degraded。
    degraded = snapshots.values.any { it.connected && it.degraded },
    // `cached` 描述的是“当前对话的消息是否为缓存”，不能拿来给整页的目录数据下结论。聚合页只有在
    // 一台服务器都没连上时才可能整页都是缓存；否则由各设备的 chip 状态点表达。
    cached = snapshots.values.none { it.connected } && snapshots.values.any { it.cached },
    summary = mergedSummary,
    allServerSnapshots = snapshots, allSessionStatuses = statuses,
    allUnseenSessions = snapshots.values.flatMap { snapshot -> snapshot.notices.filterNot { it.viewed }.map { scopedKey(snapshot.serverId, it.sessionId) } }.toSet())
}

/**
 * A device group is an aggregate like 全部, restricted to its members. The writable connection stays
 * one profile so chat, drafts and actions keep their existing server-scoped contracts.
 */
private fun LagoonState.groupHomeProjection(
  snapshots: Map<String, ServerOverviewSnapshot>,
  group: DeviceGroup,
  now: Long = System.currentTimeMillis()
): LagoonState {
  val members = group.memberIds.toSet()
  val scoped = snapshots.filterKeys { it in members }
  val statuses = scoped.values.flatMap { snapshot ->
    snapshot.sessionStatuses(permissionPolicy, now).map { (id, status) -> scopedKey(snapshot.serverId, id) to status }
  }.toMap()
  val active = serverId?.takeIf { id -> id in members && scoped[id]?.connected == true }
    ?: scoped.keys.firstOrNull { id -> scoped[id]?.connected == true }
    ?: serverId?.takeIf { it in members && profiles.any { profile -> profile.id == it && profile.effectiveEnabled } }
    ?: scoped.keys.firstOrNull()
  val summary = TaskSummary.merge(scoped.values.filter { it.profile.notifications }.map { it.displaySummary(permissionPolicy, now) })
  val unseen = scoped.values.flatMap { snapshot ->
    snapshot.notices.filterNot { it.viewed }.map { scopedKey(snapshot.serverId, it.sessionId) }
  }.toSet()
  return copy(serverId = active, homeServerId = active ?: homeServerId?.takeIf { id -> id in members && profiles.any { it.id == id && it.effectiveEnabled } },
    homeGroupId = group.id, allServers = false,
    sessionId = null, projectId = null, scopeProjectId = null,
    sessions = scoped.values.flatMap { it.sessions }.distinctBy { it.key }.sortedWith(sessionActivityOrder),
    projects = scoped.values.flatMap { it.projects }.distinctBy { it.key },
    // The writable profile of a group can differ from the conversation's server; labels follow it.
    workspaceCatalog = active?.let { scoped[it]?.workspaceCatalog }.orEmpty(),
    tasks = emptyMap(), permissions = emptyList(), questions = emptyList(), notices = emptyList(),
    knownParents = emptyMap(), backgroundRunning = emptySet(), pinned = emptySet(),
    sessionCursors = emptyMap(), catalogComplete = true,
    connected = scoped.values.any { it.connected }, loading = scoped.values.any { it.loading },
    cached = scoped.values.none { it.connected } && scoped.values.any { it.cached },
    degraded = scoped.values.any { it.connected && it.degraded },
    streamConnected = scoped.values.any { it.streamConnected },
    lastContactAt = scoped.values.maxOfOrNull { it.lastReadAt } ?: lastContactAt,
    autoApprovalFailures = scoped.values.flatMap { it.autoApprovalFailures }.toSet(),
    summary = summary, allServerSnapshots = scoped, allSessionStatuses = statuses,
    allUnseenSessions = unseen)
}

/** Notification-disabled servers that still have work, so the home count can say what the island omits. */
internal fun LagoonState.silentServersWithTasks(now: Long = System.currentTimeMillis()): List<String> =
  homeReadSnapshots().values.filter { snapshot ->
    !snapshot.profile.notifications && !snapshot.displaySummary(permissionPolicy, now).isEmpty
  }.map { it.profile.name }
