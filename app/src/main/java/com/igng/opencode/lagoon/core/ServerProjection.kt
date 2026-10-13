package com.igng.opencode.lagoon.core

/** The local conversation overrides its own snapshot, never another server's state. */
internal fun LagoonState.serverSnapshots(): Map<String, ServerOverviewSnapshot> {
  val id = serverId ?: return allServerSnapshots
  val profile = server ?: return allServerSnapshots
  if (!connected && sessions.isEmpty() && tasks.isEmpty()) return allServerSnapshots
  return allServerSnapshots + (id to ServerOverviewSnapshot(id, profile, version, connected, cached, loading, error,
    degraded, streamConnected, projects, sessions, parents, tasks, notices, permissions, questions, backgroundRunning,
    summary, autoApprovalFailures, shells))
}

internal fun ServerOverviewSnapshot.displaySummary(policy: PermissionPolicy): TaskSummary = TaskSummary.of(
  policy.tasks(tasks, permissions, autoApprovalFailures), notices, parents, sessions.associate { it.id to it.displayTitle() },
  backgroundRunning, serverId, profile.name)

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
    connected = snapshots.values.any { it.connected }, loading = snapshots.values.any { it.loading },
    streamConnected = snapshots.values.any { it.streamConnected }, degraded = snapshots.size < profiles.size || snapshots.values.any { it.degraded || !it.connected },
    summary = globalSummary, allServerSnapshots = snapshots, allSessionStatuses = statuses,
    allUnseenSessions = snapshots.values.flatMap { snapshot -> snapshot.notices.filterNot { it.viewed }.map { scopedKey(snapshot.serverId, it.sessionId) } }.toSet())
}
