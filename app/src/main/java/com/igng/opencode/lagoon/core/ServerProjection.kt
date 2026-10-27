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

/** Root families this phone last knew as active, before the offline TTL is applied. */
internal val LagoonState.rawActiveRootTasks: Map<String, TaskState>
  get() = SessionActivityRules.rootStates(presentedTasks.filterValues { it.active }, parents)

/** The local conversation overrides its own snapshot, never another server's state. */
internal fun LagoonState.serverSnapshots(): Map<String, ServerOverviewSnapshot> {
  val id = serverId ?: return allServerSnapshots
  val profile = server ?: return allServerSnapshots
  if (!connected && sessions.isEmpty() && tasks.isEmpty()) return allServerSnapshots
  return allServerSnapshots + (id to ServerOverviewSnapshot(id, profile, version = version, connected = connected,
    canSyncRead = capabilities.sessionView != null,
    cached = cached, loading = loading, lastReadAt = lastContactAt, error = error, degraded = degraded,
    streamConnected = streamConnected, projects = projects, sessions = sessions, parents = parents, tasks = tasks,
    notices = notices, permissions = permissions, questions = questions, backgroundRunning = backgroundRunning,
    summary = summary, autoApprovalFailures = autoApprovalFailures, shells = shells))
}

/** The servers the home page is currently projecting: one server, or every monitored server in 全部 mode. */
internal fun LagoonState.homeReadSnapshots(): Map<String, ServerOverviewSnapshot> {
  val snapshots = serverSnapshots()
  if (allServers) return snapshots
  val id = homeServerId ?: serverId ?: return emptyMap()
  return snapshots[id]?.let { mapOf(id to it) } ?: emptyMap()
}

/** Unread results the home button would clear. 全部 mode already uses server-scoped keys. */
internal fun LagoonState.homeUnseenSessions(): Set<String> =
  if (allServers) allUnseenSessions
  else notices.filterNot { it.viewed }.map { it.sessionId }.toSet()

/** Server-scoped candidates for the independent “服务端已读” action. */
internal fun LagoonState.serverReadTargets(): Map<String, List<Session>> =
  homeReadSnapshots().mapNotNull { (server, snapshot) ->
    snapshot.serverReadCandidates().takeIf { it.isNotEmpty() }?.let { server to it }
  }.toMap()

/** Session indicators for every monitored server, using the same offline TTL as the island. */
internal fun LagoonState.allServerStatuses(now: Long = System.currentTimeMillis()): Map<String, SessionStatus> =
  serverSnapshots().values.flatMap { snapshot ->
    snapshot.sessionStatuses(permissionPolicy, now).map { (id, status) -> scopedKey(snapshot.serverId, id) to status }
  }.toMap()

/** A home filter is a projection, not a connection switch. */
fun LagoonState.homeProjection(): LagoonState {
  val snapshots = serverSnapshots()
  if (!allServers) {
    val id = homeServerId ?: serverId
    if (id == serverId) return copy(allServers = false, allServerSnapshots = snapshots)
    val snapshot = snapshots[id] ?: return copy(serverId = id, sessions = emptyList(), projects = emptyList(), tasks = emptyMap(),
      permissions = emptyList(), questions = emptyList(), notices = emptyList(), connected = false, loading = true, allServers = false,
      allServerSnapshots = snapshots)
    return copy(serverId = id, version = snapshot.version, connected = snapshot.connected, streamConnected = snapshot.streamConnected,
      cached = snapshot.cached, loading = snapshot.loading, degraded = snapshot.degraded, sessions = snapshot.sessions,
      projects = snapshot.projects, tasks = snapshot.tasks, permissions = snapshot.permissions, questions = snapshot.questions,
      notices = snapshot.notices, knownParents = snapshot.parents, backgroundRunning = snapshot.backgroundRunning,
      summary = snapshot.displaySummary(permissionPolicy), lastContactAt = snapshot.lastReadAt,
      autoApprovalFailures = snapshot.autoApprovalFailures, sessionId = null, projectId = null, scopeProjectId = null,
      serverReadPending = serverReadPending,
      allServerSnapshots = snapshots)
  }
  val statuses = allServerStatuses()
  return copy(serverId = null, sessionId = null, projectId = null, scopeProjectId = null,
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
    degraded = snapshots.values.any { it.degraded },
    summary = globalSummary, allServerSnapshots = snapshots, allSessionStatuses = statuses,
    allUnseenSessions = snapshots.values.flatMap { snapshot -> snapshot.notices.filterNot { it.viewed }.map { scopedKey(snapshot.serverId, it.sessionId) } }.toSet())
}

/**
 * Archived root sessions still counted by the island (active work or unread result) but hidden from
 * the home list by `visibleOnHome`. The home global strip links this count to the archived page.
 */
internal fun LagoonState.archivedTaskCount(now: Long = System.currentTimeMillis()): Int =
  serverSnapshots().values.sumOf { snapshot ->
    val archived = snapshot.sessions.filter { it.parentId == null && it.archived }.map { it.id }.toSet()
    if (archived.isEmpty()) return@sumOf 0
    val active = SessionActivityRules.rootStates(
      permissionPolicy.tasks(snapshot.liveTasks(now), snapshot.permissions, snapshot.autoApprovalFailures).filterValues { it.active },
      snapshot.parents
    ).keys
    val unseen = snapshot.notices.filterNot { it.viewed }.map { it.sessionId }
    (active + unseen).count { it in archived }
  }

/** Notification-disabled servers that still have work, so the home count can say what the island omits. */
internal fun LagoonState.silentServersWithTasks(now: Long = System.currentTimeMillis()): List<String> =
  serverSnapshots().values.filter { snapshot ->
    !snapshot.profile.notifications && !snapshot.displaySummary(permissionPolicy, now).isEmpty
  }.map { it.profile.name }
