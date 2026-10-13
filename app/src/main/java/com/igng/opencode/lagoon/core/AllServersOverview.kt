package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Read-only state for one server while the home screen is in all-servers mode.
 *
 * The normal controller remains the authoritative, writable single-server context. This snapshot
 * deliberately contains only the pieces needed by the aggregate home/notification projection; a
 * click on a session switches back to that server before any write operation is allowed.
 */
data class ServerOverviewSnapshot(
  val serverId: String,
  val profile: ServerProfile,
  val version: String = "",
  val connected: Boolean = false,
  val cached: Boolean = false,
  val loading: Boolean = true,
  val error: String? = null,
  val degraded: Boolean = false,
  val streamConnected: Boolean = false,
  val projects: List<Project> = emptyList(),
  val sessions: List<Session> = emptyList(),
  val parents: Map<String, String> = emptyMap(),
  val tasks: Map<String, TaskState> = emptyMap(),
  val notices: List<SessionNotice> = emptyList(),
  val permissions: List<PermissionRequest> = emptyList(),
  val questions: List<QuestionRequest> = emptyList(),
  val backgroundRunning: Set<String> = emptySet(),
  val summary: TaskSummary = TaskSummary.EMPTY
)

/**
 * Concurrent overview readers used by [LagoonController] in all-servers mode.
 *
 * Each server owns an API client, a polling loop and an SSE loop. The polling loop is intentional:
 * OpenCode's event stream is volatile, and the control-plane read also repairs missed terminal and
 * permission events. A per-server mutex prevents an event burst from creating overlapping reads.
 */
internal class AllServersOverview(
  private val store: ServerStore,
  private val cache: OfflineCache,
  private val scope: CoroutineScope,
  private val onSnapshot: (ServerOverviewSnapshot) -> Unit
) {
  private val jobs = mutableMapOf<String, Job>()
  private val locks = mutableMapOf<String, Mutex>()

  fun start(profiles: List<ServerProfile>) {
    stop()
    profiles.forEach { profile ->
      val id = profile.id
      onSnapshot(ServerOverviewSnapshot(id, profile, notices = store.sessionNotices(id), loading = true))
      jobs[id] = scope.launch { monitor(profile) }
    }
  }

  fun restart(profiles: List<ServerProfile>) = start(profiles)

  fun stop() {
    jobs.values.forEach(Job::cancel)
    jobs.clear()
    locks.clear()
  }

  private suspend fun monitor(profile: ServerProfile) {
    val id = profile.id
    val client = try {
      OpenCodeApi(profile, store.credentials(id))
    } catch (error: Exception) {
      onSnapshot(ServerOverviewSnapshot(id, profile, connected = false, loading = false, error = error.message))
      return
    }
    val cachedCatalog = cache.catalog(id)
    val cachedProjects = cachedCatalog?.first.orEmpty().map { it.copy(serverId = id) }
    val cachedSessions = cachedCatalog?.second.orEmpty().map { it.copy(serverId = id) }
    val cachedTasks = store.taskStates(id)
    val cachedParents = store.taskParents(id)
    val cachedNotices = store.sessionNotices(id)
    val cachedTitles = cachedSessions.associate { it.id to it.displayTitle() }
    val cachedBackground = TaskSummary.backgroundRoots(cachedTasks, cachedParents)
    var snapshot = ServerOverviewSnapshot(
      id,
      profile,
      cached = cachedCatalog != null,
      projects = cachedProjects,
      sessions = cachedSessions,
      parents = cachedParents,
      tasks = cachedTasks,
      notices = cachedNotices,
      backgroundRunning = cachedBackground,
      summary = TaskSummary.of(cachedTasks, cachedNotices, cachedParents, cachedTitles, cachedBackground,
        serverId = id, serverName = profile.name)
    )
    val lock = locks.getOrPut(id) { Mutex() }
    store.migrateTaskLedger(id)

    suspend fun refresh() {
      val next = lock.withLock {
        val previous = snapshot
        try {
          read(profile, client, previous)
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (error: Exception) {
          previous.copy(connected = false, loading = false, cached = previous.cached || cachedCatalog != null,
            degraded = true, error = error.message ?: "连接失败")
        }
      }
      snapshot = next
      onSnapshot(next)
    }

    refresh()
    coroutineScope {
      launch {
        while (isActive) {
          delay(POLL_MILLIS)
          refresh()
        }
      }
      launch {
        var retry = 1_000L
        while (isActive) {
          try {
            client.events(onOpen = {
              scope.launch {
                snapshot = snapshot.copy(streamConnected = true)
                onSnapshot(snapshot)
              }
            }).collect {
              // A targeted reducer would duplicate the single-server controller. The overview is
              // intentionally read-repair based: one event wakes the authoritative control read.
              refresh()
              retry = 1_000L
            }
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (error: Exception) {
            Diagnostics.warn("AllServers", "服务器 ${profile.name} 实时连接断开", error)
          }
          snapshot = snapshot.copy(streamConnected = false)
          onSnapshot(snapshot)
          delay(retry)
          retry = (retry * 2).coerceAtMost(30_000L)
        }
      }
      awaitCancellation()
    }
  }

  private suspend fun read(profile: ServerProfile, client: OpenCodeApi, previous: ServerOverviewSnapshot): ServerOverviewSnapshot {
    val id = profile.id
    val version = client.health()
    val projects = store.localProjects(id).map { it.copy(serverId = id) }
    val page = client.rootSessionsPage()
    val activeResult = attempt { client.activeSessions() }
    val active = activeResult.getOrNull()

    val listed = page.items.map { it.copy(serverId = id) }
    val identityMap = (listed + previous.sessions).associateBy { it.id }.toMutableMap()
    val missing = active.orEmpty().filterNot(identityMap::containsKey).distinct().take(MAX_ACTIVE_IDENTITIES)
    if (missing.isNotEmpty()) {
      coroutineScope {
        missing.map { sessionId ->
          async { attempt { client.session(sessionId) }.getOrNull()?.copy(serverId = id) }
        }.forEach { deferred -> deferred.await()?.let { identityMap[it.id] = it } }
      }
    }

    // An active child can outlive the first page of root sessions. Walk its ancestry so the home list
    // can keep the work under the correct root and the status reducer does not expose a child as an
    // unrelated top-level task. The cap prevents a malformed parent cycle from creating unbounded IO.
    var parentRounds = 0
    while (parentRounds++ < MAX_PARENT_ROUNDS) {
      val parentIds = identityMap.values.mapNotNull { it.parentId }
        .filterNot(identityMap::containsKey).distinct().take(MAX_PARENT_FETCH)
      if (parentIds.isEmpty()) break
      coroutineScope {
        parentIds.map { parentId ->
          async { attempt { client.session(parentId) }.getOrNull()?.copy(serverId = id) }
        }.forEach { deferred -> deferred.await()?.let { identityMap[it.id] = it } }
      }
    }
    val sessions = (listed + identityMap.values.filter { it.id in active.orEmpty() || it.parentId == null } + previous.sessions.filter {
      previous.tasks[it.id]?.active == true || previous.notices.unseenFor(it.id).isNotEmpty()
    }).distinctBy { it.id }.sortedWith(sessionActivityOrder)
    sessions.forEach { identityMap[it.id] = it }
    val parentMap = store.taskParents(id) + identityMap.values.mapNotNull { session -> session.parentId?.let { session.id to it } }
    store.rememberParents(id, identityMap.values.toList())

    val permissionResults = if (active != null) coroutineScope {
      identityMap.values.filter { it.id in active }.take(MAX_REQUEST_SESSIONS).map { session ->
        async { attempt { client.sessionPermissions(session) } }
      }.map { it.await() }
    } else emptyList()
    val formResults = if (active != null) coroutineScope {
      identityMap.values.filter { it.id in active }.map { it.directory }.filter(String::isNotBlank)
        .distinctBy(::normalizedDirectory).take(MAX_REQUEST_SESSIONS).map { directory ->
          async { attempt { client.forms(directory) } }
        }.map { it.await() }
    } else emptyList()
    val permissions = if (active == null) previous.permissions else permissionResults.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }
    val questions = if (active == null) previous.questions else formResults.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }

    val oldTasks = store.taskStates(id) + previous.tasks
    val states = linkedMapOf<String, TaskState>()
    val newlyFinished = mutableListOf<Pair<Session, Long>>()
    if (active == null) {
      oldTasks.values.filter { it.active }.forEach { states[it.sessionId] = it }
    } else {
      active.forEach { sessionId ->
        val session = identityMap[sessionId]
        val old = oldTasks[sessionId]
        states[sessionId] = old?.takeIf { it.active }?.let { running ->
          if (running.activeAt >= 0) running else running.copy(activeAt = session?.idle ?: -1L)
        } ?: TaskState(sessionId, TaskPhase.THINKING, TaskState.RUNNING_DETAIL, activeAt = session?.idle ?: -1L)
      }
      oldTasks.values.filter { it.active && it.sessionId !in active }.forEach { old ->
        val session = identityMap[old.sessionId] ?: sessions.firstOrNull { it.id == old.sessionId }
        if (session == null || old.activeAt < 0 || session.idle <= old.activeAt) {
          states[old.sessionId] = old
        } else if (session.parentId == null && session.outcome != "interrupted" && !store.sessionNotices(id).coversRun(session.id, old.since)) {
          newlyFinished += session to old.since
        }
      }
    }

    permissions.forEach { request ->
      val old = oldTasks[request.sessionId]
      states[request.sessionId] = TaskState(request.sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", old?.since ?: now(), activeAt = old?.activeAt ?: -1L)
    }
    questions.forEach { request ->
      val old = oldTasks[request.sessionId]
      states[request.sessionId] = TaskState(request.sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", old?.since ?: now(), activeAt = old?.activeAt ?: -1L)
    }
    states.values.forEach { task -> store.rememberTask(id, task, parentMap[task.sessionId]) }
    newlyFinished.forEach { (session, since) -> session.transitionNotice(since)?.let { store.rememberNotice(id, it) } }
    val notices = store.sessionNotices(id)
    val background = TaskSummary.backgroundRoots(states, parentMap)
    val titles = sessions.associate { it.id to it.displayTitle() }
    val summary = TaskSummary.of(states, notices, parentMap, titles, background, serverId = id, serverName = profile.name)
    val degraded = activeResult.isFailure || permissionResults.any { it.isFailure } || formResults.any { it.isFailure }
    if (degraded) Diagnostics.warn("AllServers", "服务器 ${profile.name} 部分状态读取失败")
    return previous.copy(
      profile = profile,
      version = version,
      connected = true,
      cached = false,
      loading = false,
      error = null,
      degraded = degraded,
      projects = projects,
      sessions = sessions,
      parents = store.taskParents(id),
      tasks = states,
      notices = notices,
      permissions = permissions,
      questions = questions,
      backgroundRunning = background,
      summary = summary
    )
  }

  private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (error: Exception) {
    Result.failure(error)
  }

  private fun now(): Long = System.currentTimeMillis()

  private companion object {
    const val POLL_MILLIS = 8_000L
    const val MAX_REQUEST_SESSIONS = 24
    const val MAX_ACTIVE_IDENTITIES = 48
    const val MAX_PARENT_FETCH = 48
    const val MAX_PARENT_ROUNDS = 8
  }
}
