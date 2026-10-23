package com.igng.opencode.lagoon.core

/**
 * After this long without a successful read, server-owned work (running, or waiting on the server)
 * stops counting as live: the server may still be running it, but the phone cannot prove it, and
 * keeping the island and the foreground service alive forever because one server went offline is
 * worse. Pending permission/question prompts stay; they are facts this phone already holds.
 * Results already in the unread ledger are unaffected.
 */
internal const val STALE_CONNECTION_TASK_MILLIS = 15 * 60_000L

/**
 * A read older than [STALE_CONNECTION_TASK_MILLIS] means the phone cannot prove server-owned work is
 * still running. Pending permission/question prompts stay live: they are facts this phone already
 * holds and can still act on.
 */
internal fun ServerOverviewSnapshot.isStale(now: Long = System.currentTimeMillis()): Boolean =
  !connected && lastReadAt > 0 && now - lastReadAt > STALE_CONNECTION_TASK_MILLIS

/** Server-owned active work (running / waiting on the server) after applying the offline TTL. */
internal fun ServerOverviewSnapshot.liveTasks(now: Long = System.currentTimeMillis()): Map<String, TaskState> =
  if (isStale(now)) tasks.filterValues { it.phase !in TaskState.RUNNING_PHASES && it.phase != TaskPhase.WAITING_SERVER }
  else tasks

internal fun ServerOverviewSnapshot.liveBackground(now: Long = System.currentTimeMillis()): Set<String> =
  if (isStale(now)) emptySet() else backgroundRunning

/** The selected connection's offline TTL, shared by home rows, chat subtitle and the island. */
internal val LagoonState.staleConnection: Boolean
  get() = !connected && lastContactAt > 0 &&
    System.currentTimeMillis() - lastContactAt > STALE_CONNECTION_TASK_MILLIS

/** Tasks after applying the same offline TTL as [ServerOverviewSnapshot.displaySummary]. */
internal val LagoonState.livePresentedTasks: Map<String, TaskState>
  get() = if (staleConnection) presentedTasks.filterValues {
    it.phase !in TaskState.RUNNING_PHASES && it.phase != TaskPhase.WAITING_SERVER
  } else presentedTasks

/** Root families this phone last knew as active, before the offline TTL is applied. */
internal val LagoonState.rawActiveRootTasks: Map<String, TaskState>
  get() = TaskSummary.aggregate(presentedTasks.filterValues { it.active }, parents)

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

/**
 * Root sessions that produced a result this device recorded and whose latest finished execution has
 * not been acknowledged on the server. Scoping to the ledger keeps the action tied to the results the
 * user can see (and still works after local “全部已读” because viewed notices remain in the ledger).
 */
internal fun ServerOverviewSnapshot.serverReadCandidates(): List<Session> {
  if (!connected || !canSyncRead) return emptyList()
  val withResults = notices.map { it.sessionId }.toSet()
  return sessions.filter { it.id in withResults && it.parentId == null && it.idle > 0 && it.viewed < it.idle }
}

/** Server-scoped candidates for the independent “服务端已读” action. */
internal fun LagoonState.serverReadTargets(): Map<String, List<Session>> =
  homeReadSnapshots().mapNotNull { (server, snapshot) ->
    snapshot.serverReadCandidates().takeIf { it.isNotEmpty() }?.let { server to it }
  }.toMap()

/** Exact sessions whose notification slot currently belongs to an authorization/question reminder. */
internal fun ServerOverviewSnapshot.waitingSessionIds(): Set<String> =
  (permissions.map { it.sessionId } + questions.map { it.sessionId } +
    tasks.filterValues { it.phase in TaskState.WAITING_PHASES }.keys).toSet()

/** Result notifications a batch read may withdraw; attention slots always survive the sweep. */
internal fun ServerOverviewSnapshot.resultNotificationSessions(sessions: Set<String>): Set<String> =
  sessions - waitingSessionIds()

internal fun ServerOverviewSnapshot.displaySummary(policy: PermissionPolicy, now: Long = System.currentTimeMillis()): TaskSummary =
  TaskSummary.of(policy.tasks(liveTasks(now), permissions, autoApprovalFailures), notices, parents,
    sessions.associate { it.id to it.displayTitle() }, liveBackground(now), serverId, profile.name)

/** Session indicators for every monitored server, using the same offline TTL as the island. */
internal fun LagoonState.allServerStatuses(now: Long = System.currentTimeMillis()): Map<String, SessionStatus> {
  val snapshots = serverSnapshots()
  return snapshots.values.flatMap { snapshot ->
    val live = snapshot.liveTasks(now)
    val active = TaskSummary.aggregate(permissionPolicy.tasks(live, snapshot.permissions, snapshot.autoApprovalFailures).filterValues { it.active }, snapshot.parents)
    // Roots whose only activity was server-owned work dropped by the offline TTL must stop spinning,
    // but they are not "idle" either: mark them unknown until an authoritative read returns.
    val staleRoots = if (snapshot.isStale(now)) {
      TaskSummary.aggregate(permissionPolicy.tasks(snapshot.tasks, snapshot.permissions, snapshot.autoApprovalFailures).filterValues { it.active }, snapshot.parents).keys - active.keys
    } else emptySet()
    val background = snapshot.liveBackground(now)
    snapshot.sessions.map { session ->
      val unseen = snapshot.notices.unseenFor(session.id)
      val status = if (session.id in staleRoots && unseen.isEmpty()) SessionStatus.UNKNOWN
        else sessionStatus(active[session.id], unseen, session.id in background)
      session.key to status
    }
  }.toMap()
}

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
    val active = TaskSummary.aggregate(
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
