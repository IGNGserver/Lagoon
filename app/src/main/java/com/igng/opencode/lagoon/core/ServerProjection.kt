package com.igng.opencode.lagoon.core

/**
 * After this long without a successful read, a disconnected server's active tasks stop counting as
 * live: the server may still be running them, but the phone cannot prove it, and keeping the island
 * and the foreground service alive forever because one server went offline is worse. Results already
 * in the unread ledger are unaffected.
 */
internal const val STALE_CONNECTION_TASK_MILLIS = 15 * 60_000L

/** The local conversation overrides its own snapshot, never another server's state. */
internal fun LagoonState.serverSnapshots(): Map<String, ServerOverviewSnapshot> {
  val id = serverId ?: return allServerSnapshots
  val profile = server ?: return allServerSnapshots
  if (!connected && sessions.isEmpty() && tasks.isEmpty()) return allServerSnapshots
  return allServerSnapshots + (id to ServerOverviewSnapshot(id, profile, version = version, connected = connected,
    cached = cached, loading = loading, lastReadAt = lastContactAt, error = error, degraded = degraded,
    streamConnected = streamConnected, projects = projects, sessions = sessions, parents = parents, tasks = tasks,
    notices = notices, permissions = permissions, questions = questions, backgroundRunning = backgroundRunning,
    summary = summary, autoApprovalFailures = autoApprovalFailures, shells = shells))
}

internal fun ServerOverviewSnapshot.displaySummary(policy: PermissionPolicy, now: Long = System.currentTimeMillis()): TaskSummary {
  val stale = !connected && lastReadAt > 0 && now - lastReadAt > STALE_CONNECTION_TASK_MILLIS
  val live = if (stale) tasks.filterValues { !it.active } else tasks
  return TaskSummary.of(policy.tasks(live, permissions, autoApprovalFailures), notices, parents,
    sessions.associate { it.id to it.displayTitle() }, if (stale) emptySet() else backgroundRunning, serverId, profile.name)
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
      summary = snapshot.displaySummary(permissionPolicy),
      autoApprovalFailures = snapshot.autoApprovalFailures, sessionId = null, projectId = null, scopeProjectId = null,
      allServerSnapshots = snapshots)
  }
  val statuses = snapshots.values.flatMap { snapshot ->
    val active = TaskSummary.aggregate(permissionPolicy.tasks(snapshot.tasks, snapshot.permissions, snapshot.autoApprovalFailures).filterValues { it.active }, snapshot.parents)
    snapshot.sessions.map { session -> session.key to sessionStatus(active[session.id], snapshot.notices.unseenFor(session.id), session.id in snapshot.backgroundRunning) }
  }.toMap()
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
