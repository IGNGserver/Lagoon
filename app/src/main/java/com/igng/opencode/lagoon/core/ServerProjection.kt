package com.igng.opencode.lagoon.core

/** The selected connection's offline TTL, shared by home rows, chat subtitle and the island. */
internal val LagoonState.staleConnection: Boolean
  get() = !connected && lastContactAt > 0 &&
    System.currentTimeMillis() - lastContactAt > STALE_CONNECTION_TASK_MILLIS

/** Tasks after applying the same offline TTL as [ServerOverviewSnapshot.displaySummary]. */
internal val LagoonState.livePresentedTasks: Map<String, TaskState>
  get() {
    val stale = staleConnection
    livePresentedTasksCache?.takeIf { it.first == stale }?.let { return it.second }
    val computed = if (stale) presentedTasks.filterValues {
      it.phase !in TaskState.RUNNING_PHASES && it.phase != TaskPhase.WAITING_SERVER
    } else presentedTasks.filterValues { !it.uncertain }
    livePresentedTasksCache = stale to computed
    return computed
  }

/** The local conversation overrides its own snapshot, never another server's state. */
internal fun LagoonState.serverSnapshots(): Map<String, ServerOverviewSnapshot> = cachedServerSnapshots

internal fun LagoonState.computeServerSnapshots(): Map<String, ServerOverviewSnapshot> {
  val enabledIds = enabledProfiles.mapTo(HashSet()) { it.id }
  val visible = if (enabledIds.size == profiles.size) allServerSnapshots
    else allServerSnapshots.filterKeys { it in enabledIds }
  val id = serverId?.takeIf { it in enabledIds } ?: return visible
  val profile = profiles.firstOrNull { it.id == id } ?: return visible
  if (!connected && sessions.isEmpty() && tasks.isEmpty() && !loading && error.isNullOrBlank() && lastContactAt <= 0) return visible
  return visible + (id to ServerOverviewSnapshot(
    serverId = id,
    profile = profile,
    version = version,
    connected = connected,
    canSyncRead = capabilities.sessionView != null,
    cached = cached,
    loading = loading,
    lastReadAt = lastContactAt,
    error = error,
    degraded = degraded,
    streamConnected = streamConnected,
    projects = projects,
    sessions = sessions,
    parents = parents,
    tasks = tasks,
    notices = notices,
    permissions = permissions,
    questions = questions,
    backgroundRunning = backgroundRunning,
    summary = summary,
    autoApprovalFailures = autoApprovalFailures,
    shells = shells,
    workspaceCatalog = workspaceCatalog,
    sshActionRequired = sshActionRequired ?: visible[id]?.sshActionRequired?.takeIf { !connected },
    transportError = visible[id]?.transportError?.takeIf { !connected }
  ))
}

/** Display-only project/workspace titles for the server a session belongs to. */
fun LagoonState.workspaceCatalogFor(session: Session): List<Project> {
  val id = session.serverId ?: serverId ?: return emptyList()
  return if (id == serverId) workspaceCatalog else allServerSnapshots[id]?.workspaceCatalog.orEmpty()
}

/** The Harnesses the home page is currently projecting: all, one group, one device, or one Harness. */
internal fun LagoonState.homeReadSnapshots(): Map<String, ServerOverviewSnapshot> {
  val snapshots = serverSnapshots()
  if (allServers) return snapshots
  homeGroup?.let { group ->
    val members = group.memberHarnessIds(enabledProfiles).toSet()
    return snapshots.filterKeys { it in members }
  }
  homeDevice?.let { device ->
    return snapshots.filterValues { it.profile.deviceId == device.id }
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

internal fun LagoonState.homeSyncNotice(): String? = when {
  connected && degraded -> "部分数据待同步"
  connected -> null
  loading -> null
  enabledProfiles.isEmpty() -> null
  sessions.isNotEmpty() -> "离线缓存"
  else -> "尚未连接"
}

/**
 * The global strip may clear a project/workspace filter and widen a single device/harness to 全部.
 * A device group is a container, not a filter: “查看全部” there would silently leave the group.
 */
internal fun LagoonState.showsGlobalViewAll(): Boolean =
  homeGroup == null && (!allServers || homeDevice != null || scopeProjectId != null || scopeWorkspaceKey != null)

internal fun LagoonState.serverReadTargets(): Map<String, List<Session>> =
  homeReadSnapshots().mapNotNull { (server, snapshot) ->
    snapshot.serverReadCandidates().takeIf { it.isNotEmpty() }?.let { server to it }
  }.toMap()

internal fun LagoonState.allServerStatuses(now: Long = System.currentTimeMillis()): Map<String, SessionStatus> =
  serverSnapshots().values.flatMap { snapshot ->
    snapshot.sessionStatuses(permissionPolicy, now).map { (id, status) -> scopedKey(snapshot.serverId, id) to status }
  }.toMap()

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
    if (group != null) {
      val members = group.memberHarnessIds(enabledProfiles).toSet()
      return aggregateHomeProjection(snapshots, members, groupId = group.id, deviceId = null, now = now)
    }
    val device = homeDevice
    if (device != null) {
      val members = enabledProfiles.filter { it.deviceId == device.id }.mapTo(HashSet()) { it.id }
      return aggregateHomeProjection(snapshots, members, groupId = null, deviceId = device.id, now = now)
    }
    val id = (homeServerId ?: serverId)?.takeIf { it in enabledIds }
      ?: serverId?.takeIf { it in enabledIds }
      ?: enabledProfiles.firstOrNull()?.id
      ?: return copy(
        allServers = true, homeGroupId = null, homeDeviceId = null, homeServerId = null, serverId = null,
        sessionId = null, projectId = null, scopeProjectId = null, scopeWorkspaceKey = null,
        sessions = emptyList(), projects = emptyList(), messages = emptyList(), tasks = emptyMap(),
        permissions = emptyList(), questions = emptyList(), notices = emptyList(), knownParents = emptyMap(),
        backgroundRunning = emptySet(), connected = false, loading = false, cached = false,
        summary = TaskSummary.EMPTY, workspaceCatalog = emptyList(),
        allServerSnapshots = snapshots, allSessionStatuses = emptyMap(), allUnseenSessions = emptySet()
      )
    if (id == serverId) return copy(allServers = false, homeServerId = id, allServerSnapshots = snapshots)
    val snapshot = snapshots[id] ?: return copy(
      serverId = id, homeServerId = id, version = "", sessions = emptyList(), projects = emptyList(), tasks = emptyMap(),
      permissions = emptyList(), questions = emptyList(), notices = emptyList(), connected = false, streamConnected = false,
      loading = true, cached = false, degraded = false, lastContactAt = 0, error = null, allServers = false,
      summary = TaskSummary.EMPTY, knownParents = emptyMap(), backgroundRunning = emptySet(), shells = emptyMap(),
      autoApprovalFailures = emptySet(), workspaceCatalog = emptyList(), allServerSnapshots = snapshots
    )
    return copy(
      serverId = id, homeServerId = id, version = snapshot.version, connected = snapshot.connected,
      streamConnected = snapshot.streamConnected, cached = snapshot.cached, loading = snapshot.loading,
      degraded = snapshot.degraded, sessions = snapshot.sessions, projects = snapshot.projects,
      tasks = snapshot.tasks, permissions = snapshot.permissions, questions = snapshot.questions,
      notices = snapshot.notices, knownParents = snapshot.parents, backgroundRunning = snapshot.backgroundRunning,
      summary = snapshot.displaySummary(permissionPolicy, now), lastContactAt = snapshot.lastReadAt,
      autoApprovalFailures = snapshot.autoApprovalFailures, sessionId = null, projectId = null, scopeProjectId = null,
      serverReadPending = serverReadPending, workspaceCatalog = snapshot.workspaceCatalog,
      allServerSnapshots = snapshots
    )
  }
  val statuses = allServerStatuses(now)
  val mergedSummary = if (snapshots.isEmpty()) TaskSummary.EMPTY
    else TaskSummary.merge(snapshots.values.filter { it.profile.notifications }.map { it.displaySummary(permissionPolicy, now) })
  return copy(
    serverId = null, homeGroupId = null, homeDeviceId = null, allServers = true,
    homeServerId = homeServerId?.takeIf { it in enabledIds },
    sessionId = null, projectId = null, scopeProjectId = null, workspaceCatalog = emptyList(),
    sessions = snapshots.values.flatMap { it.sessions }.distinctBy { it.key }.sortedWith(sessionActivityOrder),
    projects = snapshots.values.flatMap { it.projects }.distinctBy { it.key },
    tasks = emptyMap(), permissions = emptyList(), questions = emptyList(), notices = emptyList(),
    knownParents = emptyMap(), backgroundRunning = emptySet(), pinned = emptySet(),
    sessionCursors = emptyMap(), catalogComplete = true,
    connected = snapshots.values.any { it.connected }, loading = snapshots.values.any { it.loading },
    streamConnected = snapshots.values.any { it.streamConnected },
    degraded = snapshots.values.any { it.connected && it.degraded },
    cached = snapshots.values.none { it.connected } && snapshots.values.any { it.cached },
    summary = mergedSummary,
    allServerSnapshots = snapshots,
    allSessionStatuses = statuses,
    allUnseenSessions = snapshots.values.flatMap { snapshot ->
      snapshot.notices.filterNot { it.viewed }.map { scopedKey(snapshot.serverId, it.sessionId) }
    }.toSet()
  )
}

/** 聚合一组 Harness（设备组或单台 SSH 设备）为首页投影。 */
private fun LagoonState.aggregateHomeProjection(
  snapshots: Map<String, ServerOverviewSnapshot>,
  members: Set<String>,
  groupId: String?,
  deviceId: String?,
  now: Long = System.currentTimeMillis()
): LagoonState {
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
  return copy(
    serverId = active,
    homeServerId = active ?: homeServerId?.takeIf { id -> id in members && profiles.any { it.id == id && it.effectiveEnabled } },
    homeGroupId = groupId,
    homeDeviceId = deviceId,
    allServers = false,
    sessionId = null,
    projectId = null,
    scopeProjectId = null,
    sessions = scoped.values.flatMap { it.sessions }.distinctBy { it.key }.sortedWith(sessionActivityOrder),
    projects = scoped.values.flatMap { it.projects }.distinctBy { it.key },
    workspaceCatalog = active?.let { scoped[it]?.workspaceCatalog }.orEmpty(),
    tasks = emptyMap(),
    permissions = emptyList(),
    questions = emptyList(),
    notices = emptyList(),
    knownParents = emptyMap(),
    backgroundRunning = emptySet(),
    pinned = emptySet(),
    sessionCursors = emptyMap(),
    catalogComplete = true,
    connected = scoped.values.any { it.connected },
    loading = scoped.values.any { it.loading },
    cached = scoped.values.none { it.connected } && scoped.values.any { it.cached },
    degraded = scoped.values.any { it.connected && it.degraded },
    streamConnected = scoped.values.any { it.streamConnected },
    lastContactAt = scoped.values.maxOfOrNull { it.lastReadAt } ?: lastContactAt,
    autoApprovalFailures = scoped.values.flatMap { it.autoApprovalFailures }.toSet(),
    summary = summary,
    allServerSnapshots = scoped,
    allSessionStatuses = statuses,
    allUnseenSessions = unseen
  )
}

/** Notification-disabled Harnesses that still have work, so the home count can say what the island omits. */
internal fun LagoonState.silentServersWithTasks(now: Long = System.currentTimeMillis()): List<String> =
  homeReadSnapshots().values.filter { snapshot ->
    !snapshot.profile.notifications && !snapshot.displaySummary(permissionPolicy, now).isEmpty
  }.map { it.profile.name }
