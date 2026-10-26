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
  else tasks.filterValues { !it.uncertain }

internal fun ServerOverviewSnapshot.liveBackground(now: Long = System.currentTimeMillis()): Set<String> =
  if (isStale(now)) emptySet() else backgroundRunning

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

/**
 * One indicator per session of [this] server, using the same offline TTL as the summary: roots whose
 * only activity was server-owned work dropped by that TTL are [SessionStatus.UNKNOWN], not idle.
 */
fun ServerOverviewSnapshot.sessionStatuses(policy: PermissionPolicy, now: Long = System.currentTimeMillis()): Map<String, SessionStatus> {
  val live = liveTasks(now)
  val active = TaskSummary.aggregate(policy.tasks(live, permissions, autoApprovalFailures).filterValues { it.active }, parents)
  // Roots whose only activity was server-owned work dropped by the offline TTL must stop spinning,
  // but they are not "idle" either: mark them unknown until an authoritative read returns.
  val staleRoots = if (isStale(now) || tasks.values.any { it.uncertain }) {
    TaskSummary.aggregate(policy.tasks(tasks, permissions, autoApprovalFailures).filterValues { it.active }, parents).keys - active.keys
  } else emptySet()
  val background = liveBackground(now)
  return sessions.associate { session ->
    val unseen = notices.unseenFor(session.id)
    session.id to (if (session.id in staleRoots && unseen.isEmpty()) SessionStatus.UNKNOWN
      else sessionStatus(active[session.id], unseen, session.id in background))
  }
}
