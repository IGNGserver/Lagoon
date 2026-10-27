package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel

/**
 * Server-scoped control state, independent of the current home filter or conversation.
 */
data class ServerOverviewSnapshot(
  val serverId: String,
  val profile: ServerProfile,
  val version: String = "",
  val connected: Boolean = false,
  val cached: Boolean = false,
  val loading: Boolean = true,
  /** Wall clock of the last successful control-plane read; drives the offline task TTL. */
  val lastReadAt: Long = 0,
  val error: String? = null,
  val degraded: Boolean = false,
  val streamConnected: Boolean = false,
  /** The instance accepts `POST /api/session/{id}/view`; DSH and documented-absent instances do not. */
  val canSyncRead: Boolean = false,
  val projects: List<Project> = emptyList(),
  val sessions: List<Session> = emptyList(),
  val parents: Map<String, String> = emptyMap(),
  val tasks: Map<String, TaskState> = emptyMap(),
  val notices: List<SessionNotice> = emptyList(),
  val permissions: List<PermissionRequest> = emptyList(),
  val questions: List<QuestionRequest> = emptyList(),
  /** Roots whose own execution is idle but whose family still has running shells/children. */
  val backgroundRunning: Set<String> = emptySet(),
  val summary: TaskSummary = TaskSummary.EMPTY,
  val autoApprovalFailures: Set<String> = emptySet(),
  val shells: Map<String, ShellJob> = emptyMap()
)

/**
 * Process-wide server connections. The selected conversation shares this connection's API and SSE;
 * its richer controller supplies control state while the other servers reconcile independently.
 *
 * Each server owns an API client, a polling loop and an SSE loop. The polling loop is intentional:
 * OpenCode's event stream is volatile, and the control-plane read also repairs missed terminal and
 * permission events. A per-server mutex prevents an event burst from creating overlapping reads.
 */
internal class AllServersOverview(
  private val store: OverviewStore,
  private val cache: CatalogCache,
  private val scope: CoroutineScope,
  private val onSnapshot: (ServerOverviewSnapshot) -> Unit,
  private val selectedServer: () -> String? = { null },
  private val onEvent: (String, ServerEvent) -> Unit = { _, _ -> },
  private val onConnected: (String) -> Unit = {},
  /** Shared with the controller so a dead directory is remembered across every server connection. */
  private val directoryHealth: DirectoryHealth = DirectoryHealth(),
  /** A server stopped being monitored: its last snapshot must leave the global island count too. */
  private val onSnapshotRemoved: (String) -> Unit = {}
) {
  private val jobs = mutableMapOf<String, Job>()
  private val locks = mutableMapOf<String, Mutex>()
  private val clients = mutableMapOf<String, HarnessClient>()
  private val configurations = mutableMapOf<String, Pair<ServerProfile, ServerCredentials>>()
  private val wakes = mutableMapOf<String, Channel<Unit>>()
  private val reconnectHints = mutableMapOf<String, Channel<Unit>>()
  private val approvers = mutableMapOf<String, PermissionAutoApprover>()
  private val snapshots = mutableMapOf<String, ServerOverviewSnapshot>()
  private val handoffs = mutableMapOf<String, ServerOverviewSnapshot>()
  private val childDiscoveries = mutableMapOf<String, ChildDiscovery>()
  /** The collecting job of each server's live event stream, so it can be dropped on purpose. */
  private val liveStreams = mutableMapOf<String, Job>()

  /**
   * Ensures one monitor per server that should stay connected: profiles with `autoConnect` enabled,
   * plus [include] (the server the user just opened). The “自动连接此服务器” switch used to be
   * ignored and every saved server was dialled forever, including permanently unreachable ones.
   */
  fun start(profiles: List<ServerProfile>, include: Set<String> = emptySet()) {
    val wanted = profiles.filter { it.autoConnect || it.id in include }
    (jobs.keys - wanted.map { it.id }.toSet()).forEach(::remove)
    wanted.forEach { profile ->
      val id = profile.id
      val config = profile to store.credentials(id)
      val previous = configurations[id]
      if (jobs[id]?.isActive == true && previous?.second == config.second &&
        previous.first.copy(name = "", autoConnect = true, notifications = true) == profile.copy(name = "", autoConnect = true, notifications = true)) return@forEach
      remove(id)
      configurations[id] = config
      wakes[id] = Channel(Channel.CONFLATED)
      reconnectHints[id] = Channel(Channel.CONFLATED)
      publish(ServerOverviewSnapshot(id, profile, notices = store.sessionNotices(id), loading = true))
      jobs[id] = scope.launch { monitor(profile) }
    }
  }

  fun restart(profiles: List<ServerProfile>) { start(profiles); refreshAll() }
  fun client(id: String): HarnessClient = clients.getOrPut(id) {
    val (profile, credentials) = configurations[id] ?: error("服务器已移除")
    createHarnessClient(profile, credentials) { cookie -> rememberCookie(id, profile, cookie) }
  }

  /**
   * DSH 换到的会话 Cookie 落盘，下次启动直接复用（启动令牌在 dsh 重启后就作废了）。同步更新
   * [configurations]，否则下一次 [start] 会把凭据变化误判为资料变更而重建这台服务器的连接。
   */
  private fun rememberCookie(id: String, profile: ServerProfile, cookie: String) {
    store.rememberCookie(id, profile.url, cookie)
    scope.launch {
      val current = configurations[id] ?: return@launch
      if (current.first == profile) configurations[id] = profile to store.credentials(id)
    }
  }
  fun refreshAll() { wakes.values.forEach { it.trySend(Unit) }; reconnectHints.values.forEach { it.trySend(Unit) } }

  /**
   * Drops every live event stream so each reconnects at once. After a network switch, or after the
   * app slept in the background, a socket can be half-open: it looks connected but will never deliver
   * another frame until the OS gives up on it, minutes later. Reconnecting is cheap, the stale stream
   * is not.
   */
  fun restartStreams() { liveStreams.values.toList().forEach { it.cancel(CancellationException(STREAM_RESTART)) } }
  fun refresh(id: String) { wakes[id]?.trySend(Unit) }
  fun selectionChanged(previous: String?) { previous?.let(::refresh) }
  fun handoff(snapshot: ServerOverviewSnapshot) { handoffs[snapshot.serverId] = snapshot }
  fun consider(id: String, requests: List<PermissionRequest>) { requests.forEach { approvers[id]?.consider(it) } }
  fun pending(id: String, requests: List<PermissionRequest>): List<PermissionRequest> = approvers[id]?.pending(requests) ?: requests
  fun policyChanged() {
    approvers.values.forEach { it.resetBackoff() }
    snapshots.values.toList().forEach { snapshot -> consider(snapshot.serverId, snapshot.permissions); publish(snapshot) }
    refreshAll()
  }
  fun settled(id: String, requestId: String) { approvers[id]?.settled(requestId); refresh(id) }

  private fun publish(snapshot: ServerOverviewSnapshot) {
    snapshots[snapshot.serverId] = snapshot
    onSnapshot(snapshot)
  }
  private fun remove(id: String) {
    jobs.remove(id)?.cancel(); wakes.remove(id)?.close(); approvers.remove(id)?.dispose()
    reconnectHints.remove(id)?.close()
    clients.remove(id); configurations.remove(id); locks.remove(id); snapshots.remove(id)
    handoffs.remove(id)
    childDiscoveries.remove(id)
    onSnapshotRemoved(id)
  }

  fun stop() {
    jobs.keys.toList().forEach(::remove)
  }

  private suspend fun monitor(profile: ServerProfile) {
    val id = profile.id
    val client = try {
      client(id)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      publish(ServerOverviewSnapshot(id, profile, connected = false, loading = false, error = userFacingError(error, "连接失败")))
      return
    }
    val cachedCatalog = cache.catalog(id)
    val cachedProjects = (cachedCatalog?.first.orEmpty() + store.localProjects(id))
      .distinctBy { normalizedDirectory(it.directory) }
      .map { it.copy(serverId = id) }
    val cachedSessions = cachedCatalog?.second.orEmpty().map { it.copy(serverId = id) }
    val cachedTasks = TaskReconciliation.restored(store.taskStates(id))
    val cachedParents = store.taskParents(id)
    val cachedNotices = store.sessionNotices(id)
    val cachedTitles = cachedSessions.associate { it.id to it.displayTitle() }
    val cachedBackground = SessionActivityRules.backgroundRoots(cachedTasks, emptyMap(), cachedParents)
    var snapshot = ServerOverviewSnapshot(
      id,
      profile,
      cached = cachedCatalog != null,
      canSyncRead = client.capabilities().sessionView != null,
      projects = cachedProjects,
      sessions = cachedSessions,
      parents = cachedParents,
      tasks = cachedTasks,
      notices = cachedNotices,
      backgroundRunning = cachedBackground,
      summary = TaskSummary.of(cachedTasks, cachedNotices, cachedParents, cachedTitles,
        serverId = id, serverName = profile.name)
    )
    val lock = locks.getOrPut(id) { Mutex() }
    store.migrateTaskLedger(id)
    var waitingRevision = 0L
    var lastReadAt = 0L
    val resolvedSessions = linkedSetOf<String>()
    val resolvedFormSessions = linkedSetOf<String>()
    val seenEvents = linkedSetOf<String>()
    fun emit(value: ServerOverviewSnapshot) {
      // An onOpen callback queued by a disposed connection must not overwrite its replacement.
      if (clients[id] === client) publish(value)
    }
    val approver = PermissionAutoApprover(scope, { store.permissionPolicy().autoApprove }, { request ->
      try { client.replyPermission(request, "once") } catch (error: ApiException) {
        // Another client can reply first. A missing endpoint/session is NOT blanket success.
        if (error.status != 404 || client.sessionPermissions(Session(request.sessionId, request.directory, "", 0)).any { it.id == request.id }) throw error
      }
    }, { request ->
      waitingRevision++
      resolvedSessions += request.sessionId
      snapshot = snapshot.copy(permissions = snapshot.permissions.filterNot { it.id == request.id })
      snapshot = reconcileWaiting(snapshot, setOf(request.sessionId))
      emit(snapshot)
      onEvent(id, ServerEvent("", request.directory, "permission.replied", org.json.JSONObject().put("sessionID", request.sessionId).put("requestID", request.id), 0))
      refresh(id)
    }, { failed ->
      snapshot = snapshot.copy(autoApprovalFailures = failed)
      emit(snapshot)
    })
    approvers[id] = approver

    suspend fun refresh() {
      if (selectedServer() == id) return
      handoffs.remove(id)?.let { snapshot = it.copy(streamConnected = snapshot.streamConnected) }
      // Conflate streaming bursts without issuing a full control-plane read for every token. The
      // overview read repairs state, so a few seconds of coalescing is enough and keeps several
      // streaming servers from turning every token into HTTP traffic.
      val elapsed = now() - lastReadAt
      if (elapsed < EVENT_READ_COALESCE_MILLIS) delay(EVENT_READ_COALESCE_MILLIS - elapsed)
      if (selectedServer() == id) return
      lastReadAt = now()
      val revision = waitingRevision
      val next = lock.withLock {
        val previous = snapshot
        try {
          read(profile, client, previous) { revision == waitingRevision }
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (error: Exception) {
          previous.copy(connected = false, loading = false, cached = previous.cached || cachedCatalog != null,
            degraded = true, error = userFacingError(error, "连接失败"))
        }
      }
      if (selectedServer() == id) return
      snapshot = if (revision == waitingRevision) next.copy(streamConnected = snapshot.streamConnected, autoApprovalFailures = snapshot.autoApprovalFailures)
        else next.copy(tasks = snapshot.tasks, notices = snapshot.notices, permissions = snapshot.permissions, questions = snapshot.questions,
          shells = snapshot.shells, parents = next.parents + snapshot.parents,
          sessions = (snapshot.sessions + next.sessions).distinctBy { it.id },
          streamConnected = snapshot.streamConnected, autoApprovalFailures = snapshot.autoApprovalFailures)
      snapshot = snapshot.copy(permissions = approver.pending(snapshot.permissions))
      snapshot = reconcileWaiting(snapshot, resolvedSessions, resolvedFormSessions = resolvedFormSessions)
      store.rememberParents(id, snapshot.sessions)
      snapshot.tasks.values.forEach { store.rememberTask(id, it, snapshot.parents[it.sessionId]) }
      store.taskStates(id).values.filter { it.active && it.sessionId !in snapshot.tasks }.forEach {
        store.rememberTask(id, it.copy(phase = TaskPhase.IDLE, detail = ""), snapshot.parents[it.sessionId])
      }
      snapshot.notices.forEach { store.rememberNotice(id, it) }
      snapshot = snapshot.copy(notices = store.sessionNotices(id))
      emit(snapshot)
      consider(id, snapshot.permissions)
    }

    coroutineScope {
      launch {
        refresh()
        while (isActive) {
          kotlinx.coroutines.withTimeoutOrNull(if (snapshot.tasks.values.any { it.active }) store.refreshMode().activeReconcileMs else store.refreshMode().idleReconcileMs) {
            wakes[id]?.receive()
          }
          refresh()
        }
      }
      launch {
        var retry = 1_000L
        while (isActive) {
          var restarted = false
          try {
            coroutineScope {
              val stream = launch {
                client.events(onOpen = {
                  scope.launch {
                    snapshot = snapshot.copy(streamConnected = true)
                    if (clients[id] !== client) return@launch
                    emit(snapshot)
                    refresh(id)
                    onConnected(id)
                  }
                }).collect { event ->
                  if (event.id.isNotBlank()) {
                    if (!seenEvents.add(event.id)) return@collect
                    while (seenEvents.size > 512) seenEvents.remove(seenEvents.first())
                  }
                  val props = event.properties
                  if (event.type.startsWith("session.execution.") || event.type.startsWith("shell.") ||
                    event.type in setOf("session.deleted", "session.synthetic", "session.error", "session.idle", "session.status") ||
                    BackgroundWork.backgroundSubagentId(event.type, props) != null) childDiscoveries.remove(id)
                  val sessionId = props.sessionEventId(event.type)
                  if (event.type == "permission.asked" && approver.pending(listOf(props.toPermission(event.directory))).isEmpty()) return@collect
                  val before = snapshot.tasks[sessionId]
                  if (selectedServer() != id && sessionId.isNotBlank()) {
                    TaskReducer.event(sessionId, event.type, props, before, event.created)?.let { task ->
                      waitingRevision++
                      snapshot = snapshot.copy(tasks = snapshot.tasks + (sessionId to task))
                    }
                  }
                  if (event.type == "permission.asked") {
                    waitingRevision++
                    val request = props.toPermission(event.directory)
                    snapshot = snapshot.copy(permissions = approver.pending(snapshot.permissions.filterNot { it.id == request.id } + request))
                  } else if (event.type == "permission.replied") {
                    waitingRevision++
                    val requestId = props.str("requestID").ifBlank { props.str("id") }
                    resolvedSessions += props.sessionEventId(event.type)
                    while (resolvedSessions.size > 1000) resolvedSessions.remove(resolvedSessions.first())
                    approver.settled(requestId)
                    snapshot = snapshot.copy(permissions = snapshot.permissions.filterNot { it.id == requestId })
                  } else if (event.type == "form.created") {
                    waitingRevision++
                    val form = props.obj("form").toForm(event.directory)
                    snapshot = snapshot.copy(questions = snapshot.questions.filterNot { it.id == form.id } + form)
                  } else if (event.type in setOf("form.replied", "form.cancelled")) {
                    waitingRevision++
                    val requestId = props.str("id").ifBlank { props.str("requestID") }
                    resolvedFormSessions += props.sessionEventId(event.type)
                    while (resolvedFormSessions.size > 1000) resolvedFormSessions.remove(resolvedFormSessions.first())
                    snapshot = snapshot.copy(questions = snapshot.questions.filterNot { it.id == requestId })
                  }
                  onEvent(id, event)
                  if (selectedServer() != id) {
                    if (event.type == "session.created") {
                      waitingRevision++
                      val session = props.sessionInfo().toSession().copy(serverId = id)
                      if (session.id.isNotBlank()) snapshot = snapshot.copy(sessions = (listOf(session) + snapshot.sessions).distinctBy { it.id },
                        parents = snapshot.parents + listOfNotNull(session.parentId?.let { session.id to it }).toMap())
                    }
                    if (event.type == "session.deleted") {
                      waitingRevision++
                      store.forgetSession(id, sessionId)
                      snapshot = snapshot.copy(
                        sessions = snapshot.sessions.filterNot { it.id == sessionId },
                         tasks = snapshot.tasks - sessionId,
                         parents = snapshot.parents - sessionId,
                         shells = snapshot.shells.filterValues { it.sessionId != sessionId },
                         notices = snapshot.notices.filterNot { it.sessionId == sessionId },
                        permissions = snapshot.permissions.filterNot { it.sessionId == sessionId },
                        questions = snapshot.questions.filterNot { it.sessionId == sessionId })
                    }
                    if (event.type == "shell.created") props.obj("info").toShellJob(event.directory)?.let { job ->
                      waitingRevision++
                      snapshot = snapshot.copy(shells = snapshot.shells + (job.id to job))
                    }
                     if (event.type in setOf("shell.exited", "shell.deleted")) {
                      waitingRevision++
                       snapshot = snapshot.copy(shells = snapshot.shells - props.str("id"))
                     }
                     BackgroundWork.backgroundSubagentId(event.type, props)?.let { child ->
                       waitingRevision++
                       val old = snapshot.tasks[child]
                       val task = old?.takeIf { it.active || it.phase in TaskState.TERMINAL_PHASES }
                         ?: TaskState(child, TaskPhase.SUBAGENT, TaskState.RUNNING_DETAIL, activeAt = -1)
                       snapshot = snapshot.copy(tasks = snapshot.tasks + (child to task), parents = snapshot.parents + (child to sessionId))
                     }
                     BackgroundWork.completedSubagentId(event.type, props)?.let { child ->
                       waitingRevision++
                       snapshot = snapshot.copy(tasks = snapshot.tasks + (child to TaskReducer.terminal(child,
                         when (props.obj("metadata").str("state")) {
                           "error" -> "failed"; "cancelled" -> "interrupted"; else -> "succeeded"
                         }, snapshot.tasks[child])))
                     }
                    snapshot = reconcileWaiting(snapshot, resolvedSessions, resolvedFormSessions = resolvedFormSessions)
                    val session = snapshot.sessions.firstOrNull { it.id == sessionId }
                    if (event.type in setOf("session.execution.succeeded", "session.execution.failed") && session?.parentId == null && session != null &&
                      snapshot.tasks[sessionId]?.active != true &&
                      !snapshot.notices.coversRun(sessionId, before?.since ?: event.created)) {
                      val notice = SessionNotice("event:${event.id.ifBlank { "$sessionId:${event.created}" }}", sessionId,
                        event.created.takeIf { it > 0 } ?: now(), event.type == "session.execution.failed")
                      store.rememberNotice(id, notice)
                      snapshot = snapshot.copy(notices = store.sessionNotices(id))
                    }
                    snapshot.tasks.values.forEach { store.rememberTask(id, it, snapshot.parents[it.sessionId]) }
                  }
                  emit(snapshot)
                  if (event.type == "permission.asked") approver.consider(props.toPermission(event.directory))
                  // Conflation keeps event consumption fast; never do a full HTTP round per token.
                  refresh(id)
                  retry = 1_000L
                }
              }
              liveStreams[id] = stream
              stream.join()
              // A deliberate restart cancels only this collector; the monitor itself keeps running.
              restarted = stream.isCancelled
            }
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (error: Exception) {
            Diagnostics.warn("AllServers", "服务器 ${profile.name} 实时连接断开", error)
          }
          liveStreams.remove(id)
          snapshot = snapshot.copy(streamConnected = false)
          emit(snapshot)
          if (restarted) { retry = 1_000L; continue }
          kotlinx.coroutines.withTimeoutOrNull(retry + kotlin.random.Random.nextLong(250)) { reconnectHints[id]?.receive() }
          retry = (retry * 2).coerceAtMost(30_000L)
        }
      }
      awaitCancellation()
    }
  }

  private suspend fun read(profile: ServerProfile, client: HarnessClient, previous: ServerOverviewSnapshot,
    unchanged: () -> Boolean): ServerOverviewSnapshot {
    val id = profile.id
    val version = client.health()
    val projects = store.localProjects(id).map { it.copy(serverId = id) }
    val page = client.rootSessionsPage()
    val activeResult = attempt { client.activeSessions() }
    val active = activeResult.getOrNull()

    val listed = page.items.map { it.copy(serverId = id) }
    val listedIds = listed.map { it.id }.toSet()
    val identityMap = (previous.sessions + listed).associateBy { it.id }.toMutableMap()
    /** Ids fetched as ancestors/owners this round: those rows must survive the ghost-session prune. */
    val fetchedIds = mutableSetOf<String>()
    val deletedSessions = mutableSetOf<String>()
    fun fetched(session: Session) { identityMap[session.id] = session; fetchedIds += session.id }
    val missing = (active.orEmpty().filterNot(identityMap::containsKey) + previous.tasks.filterValues { it.active }.keys.filter { old -> listed.none { it.id == old } }).distinct()
    if (missing.isNotEmpty()) {
      missing.chunked(4).forEach { batch -> coroutineScope {
        batch.map { sessionId -> async { sessionId to attempt { client.session(sessionId) } } }.forEach { deferred ->
          val (sessionId, result) = deferred.await()
          result.getOrNull()?.copy(serverId = id)?.let(::fetched)
          if (active != null && (result.exceptionOrNull() as? ApiException)?.status == 404 && sessionId !in active) deletedSessions += sessionId
        }
      } }
    }
    val oldTasks = previous.tasks + store.taskStates(id).filter { (sessionId, stored) ->
      previous.tasks[sessionId]?.let { old -> stored.since > old.since || (stored.finishedAt ?: 0) > (old.finishedAt ?: 0) } ?: true
    }
    val knownParents = store.taskParents(id) + identityMap.values.mapNotNull { it.parentId?.let { parent -> it.id to parent } }
    val candidateRoots = (oldTasks.filterValues { it.active }.keys + active.orEmpty()).map { BackgroundWork.rootOf(it, knownParents) }.toSet()
    val childRead = TaskReconciliation.children(client, identityMap.values.filter {
      it.parentId == null && it.id in candidateRoots && it.id !in active.orEmpty() && it.id !in deletedSessions
    }, knownParents, childDiscoveries.getOrPut(id) { ChildDiscovery() })
    childRead.sessions.forEach { fetched(it.copy(serverId = id)) }

    // Pending requests: per-session reads for sessions that can actually be waiting (the active set
    // and any session with a known pending request). The location sweep only runs for the
    // auto-approver, matching the controller: sweeping every known directory hammered deleted
    // worktrees (HTTP 500) and pinned `degraded` on every reconciliation.
    val activeIds = active.orEmpty()
    val waitingSessions = (activeIds + previous.permissions.map { it.sessionId } + previous.questions.map { it.sessionId })
      .distinct().mapNotNull { identityMap[it] }
    val skippedPermissionSessions = waitingSessions.filter { directoryHealth.isDown(it.directory) }.map { it.id }.toSet()
    val readableSessions = waitingSessions.filterNot { it.id in skippedPermissionSessions }
    val sessionPermissionResults = coroutineScope {
      readableSessions.map { session -> async { session.id to attempt { client.sessionPermissions(session) } } }.awaitAll()
    }
    sessionPermissionResults.forEach { (sessionId, result) ->
      val directory = identityMap[sessionId]?.directory ?: return@forEach
      if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory)
    }
    val sweepDirectories = if (store.permissionPolicy().autoApprove) (identityMap.values.map { it.directory } + projects.map { it.directory })
      .filter(String::isNotBlank).distinctBy(::normalizedDirectory).filterNot { directoryHealth.isDown(it) }.take(MAX_SWEEP_DIRECTORIES) else emptyList()
    val sweepPermissionResults = sweepDirectories.chunked(4).flatMap { batch -> coroutineScope {
      batch.map { directory -> async { directory to attempt { client.pendingPermissions(directory) } } }.awaitAll()
    } }
    sweepPermissionResults.forEach { (directory, result) -> if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory) }
    val formDirectories = (readableSessions.map { it.directory } + previous.questions.map { it.directory })
      .filter(String::isNotBlank).distinctBy(::normalizedDirectory).filterNot { directoryHealth.isDown(it) }
    val formResults = formDirectories.chunked(4).flatMap { batch -> coroutineScope {
      batch.map { directory -> async { directory to attempt { client.forms(directory) } } }.awaitAll()
    } }
    formResults.forEach { (directory, result) -> if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory) }
    val successfulPermissionSessions = sessionPermissionResults.filter { it.second.isSuccess }.map { it.first }.toSet()
    val successfulSweepDirs = sweepPermissionResults.filter { it.second.isSuccess }.map { normalizedDirectory(it.first) }.toSet()
    val permissions = (sessionPermissionResults.flatMap { it.second.getOrDefault(emptyList()) } +
      sweepPermissionResults.flatMap { it.second.getOrDefault(emptyList()) } +
      previous.permissions.filter { it.sessionId !in successfulPermissionSessions && normalizedDirectory(it.directory) !in successfulSweepDirs }).distinctBy { it.id }
      .filterNot { it.sessionId in deletedSessions }
    val successfulFormDirs = formResults.filter { it.second.isSuccess }.map { normalizedDirectory(it.first) }.toSet()
    val questions = (formResults.flatMap { it.second.getOrDefault(emptyList()) } + previous.questions.filter { normalizedDirectory(it.directory) !in successfulFormDirs }).distinctBy { it.id }
      .filterNot { it.sessionId in deletedSessions }

    // Requests can be parked on sessions absent from session.active. Resolve their identities too,
    // including child ancestry, before projecting them onto the home/notification root.
    (permissions.map { it.sessionId } + questions.map { it.sessionId }).distinct().filterNot(identityMap::containsKey).chunked(4).forEach { batch ->
      coroutineScope { batch.map { session -> async { attempt { client.session(session) }.getOrNull()?.copy(serverId = id) } }
        .forEach { deferred -> deferred.await()?.let(::fetched) } }
    }
    val attemptedParents = mutableSetOf<String>()
    suspend fun fetchParents() {
      var parentRounds = 0
      while (parentRounds++ < MAX_PARENT_ROUNDS) {
        val parentIds = identityMap.values.mapNotNull { it.parentId }.filter { it !in identityMap && attemptedParents.add(it) }.distinct().take(MAX_PARENT_FETCH)
        if (parentIds.isEmpty()) break
        coroutineScope { parentIds.map { parent -> async { attempt { client.session(parent) }.getOrNull()?.copy(serverId = id) } }
          .forEach { deferred -> deferred.await()?.let(::fetched) } }
      }
    }
    fetchParents()
    val unseenNoticeSessions = store.sessionNotices(id).filterNot { it.viewed }.map { it.sessionId }.toSet()
    // Drop ghost sessions: rows the server no longer lists and that nothing local still refers to
    // (active tasks, unread results, or an identity fetched as an ancestor this round). Without
    // this, a session deleted from another client stayed on the all-servers home forever.
    fun visibleSessions() = identityMap.values.filter { session ->
      session.id !in deletedSessions && (session.id in listedIds || session.id in activeIds || session.id in fetchedIds ||
        oldTasks[session.id]?.active == true || session.id in unseenNoticeSessions)
    }.sortedWith(sessionActivityOrder)
    var sessions = visibleSessions()
    val shellDirectories = TaskReconciliation.shellDirectories(oldTasks, previous.shells,
      sessions, projects, activeIds).filterNot { directoryHealth.isDown(it) }
    val shellResults = coroutineScope { shellDirectories.map { directory ->
      async { directory to attempt { client.shells(directory) } }
    }.map { it.await() } }
    shellResults.forEach { (directory, result) -> if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory) }
    val successfulShellDirs = shellResults.filter { it.second.isSuccess }.map { normalizedDirectory(it.first) }.toSet()
    val shells = previous.shells.filter { (_, shell) -> normalizedDirectory(shell.directory) !in successfulShellDirs && now() - shell.observedAt < 300_000 }
      .plus(shellResults.flatMap { it.second.getOrDefault(emptyList()) }.associate { it.id to it.copy(observedAt = now()) })
      .filterValues { it.sessionId !in deletedSessions }
    // A discovered shell can belong to a child absent from the root catalog. Resolve it before
    // deciding which family still has work; otherwise its real root can be falsely completed.
    shells.values.map { it.sessionId }.filterNot(identityMap::containsKey).distinct().take(100).chunked(4).forEach { batch ->
      coroutineScope { batch.map { owner -> async { attempt { client.session(owner) }.getOrNull()?.copy(serverId = id) } }
        .forEach { deferred -> deferred.await()?.let(::fetched) } }
    }
    fetchParents()
    sessions = visibleSessions()
    val parentMap = (store.taskParents(id) + sessions.mapNotNull { session -> session.parentId?.let { session.id to it } }) - deletedSessions
    val unresolvedOwners = shells.values.any {
      it.sessionId !in identityMap || BackgroundWork.rootOf(it.sessionId, parentMap) !in identityMap
    }
    val inboxRead = PendingInputs.read(client, sessions, oldTasks, active)
    val states = TaskReconciliation.reconcile(oldTasks, sessions, active, shells, parentMap,
      identityMap.keys + fetchedIds, successfulShellDirs, deletedSessions,
      childRead.incompleteRoots + if (unresolvedOwners) candidateRoots else emptySet(), inboxRead).toMutableMap()

    permissions.forEach { request ->
      val old = oldTasks[request.sessionId]
      states[request.sessionId] = TaskState(request.sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", old?.since ?: now(),
        activeAt = old?.activeAt ?: -1L, pendingInput = states[request.sessionId]?.pendingInput == true)
    }
    questions.forEach { request ->
      val old = oldTasks[request.sessionId]
      states[request.sessionId] = TaskState(request.sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", old?.since ?: now(),
        activeAt = old?.activeAt ?: -1L, pendingInput = states[request.sessionId]?.pendingInput == true)
    }
    val folded = states
    val wasActive = SessionActivityRules.rootStates(oldTasks.filterValues { it.active && it.sessionId !in deletedSessions }, parentMap)
    val stillActive = SessionActivityRules.rootStates(folded.filterValues { it.active }, parentMap)
    val newlyFinished = if (active == null) emptyList() else sessions.filter {
      it.parentId == null && it.id in wasActive && it.id !in stillActive
    }.map { it to wasActive.getValue(it.id).since }
    // A view may have selected this server while these HTTP reads were suspended. Its richer
    // reducer now owns the writes; an older overview read must not invent results or overwrite it.
    if (selectedServer() == id || !unchanged()) return previous
    deletedSessions.forEach { store.forgetTask(id, it) }
    val notices = (store.sessionNotices(id) + newlyFinished.filterNot { (session, since) -> store.sessionNotices(id).coversRun(session.id, since) }
      .mapNotNull { (session, since) -> session.transitionNotice(since) }).distinctBy { it.id }
    val background = SessionActivityRules.backgroundRoots(folded, shells, parentMap)
    val titles = sessions.associate { it.id to it.displayTitle() }
    val summary = TaskSummary.of(folded, notices, parentMap, titles, serverId = id, serverName = profile.name)
    // Only failures that can hide currently relevant state count as degraded. Failures for idle
    // sessions' directories (deleted worktrees) are handled by the directory backoff above.
    val activeDirectories = activeIds.mapNotNull { identityMap[it]?.directory }.map(::normalizedDirectory).toSet()
    val degraded = activeResult.isFailure || folded.values.any { it.active && it.uncertain } ||
      sessionPermissionResults.any { it.second.isFailure && (it.first in activeIds || normalizedDirectory(identityMap[it.first]?.directory.orEmpty()) in activeDirectories) } ||
      formResults.any { it.second.isFailure && normalizedDirectory(it.first) in activeDirectories }
    if (degraded) Diagnostics.warn("AllServers", "服务器 ${profile.name} 部分状态读取失败")
    return reconcileWaiting(previous.copy(
      profile = profile,
      version = version,
      connected = true,
      canSyncRead = client.capabilities().sessionView != null,
      cached = false,
      loading = false,
      lastReadAt = now(),
      error = null,
      degraded = degraded,
      projects = projects,
      sessions = sessions,
      parents = parentMap,
      tasks = folded,
      notices = notices,
      permissions = permissions,
      questions = questions,
      backgroundRunning = background,
      summary = summary,
      shells = shells
    ),
      successfulPermissionSessions + identityMap.values.filter { normalizedDirectory(it.directory) in successfulSweepDirs }.map { it.id },
      active?.toSet(),
      identityMap.values.filter { normalizedDirectory(it.directory) in successfulFormDirs }.map { it.id }.toSet()
    )
  }

  /** Empty authoritative pending lists clear old WAITING_PERMISSION / WAITING_QUESTION instead of carrying them forever. */
  private fun reconcileWaiting(
    snapshot: ServerOverviewSnapshot,
    verified: Set<String>,
    active: Set<String>? = null,
    resolvedFormSessions: Set<String> = emptySet()
  ): ServerOverviewSnapshot {
    val waitingSessions = (snapshot.permissions.map { it.sessionId } + snapshot.questions.map { it.sessionId }).toSet()
    val base = snapshot.tasks + waitingSessions.filterNot { it in snapshot.tasks }.associateWith { TaskState(it, TaskPhase.THINKING) }
    val tasks = base.mapValues { (id, task) ->
      when {
        snapshot.questions.any { it.sessionId == id } -> task.copy(phase = TaskPhase.WAITING_QUESTION, detail = "等待你的回答")
        snapshot.permissions.any { it.sessionId == id } -> task.copy(phase = TaskPhase.WAITING_PERMISSION, detail = "等待权限确认")
        (id in verified && task.phase == TaskPhase.WAITING_PERMISSION) ||
          (id in resolvedFormSessions && task.phase == TaskPhase.WAITING_QUESTION) ->
          if (task.pendingInput) task.copy(phase = TaskPhase.WAITING_SERVER, detail = "等待服务器处理")
          else if (active != null && id !in active && !task.uncertain)
            task.copy(phase = TaskPhase.IDLE, detail = "")
          else task.copy(phase = TaskPhase.THINKING, detail = TaskState.RUNNING_DETAIL)
        else -> task
      }
    }
    val visible = store.permissionPolicy().tasks(tasks, snapshot.permissions, snapshot.autoApprovalFailures)
    val background = SessionActivityRules.backgroundRoots(tasks, snapshot.shells, snapshot.parents)
    val live = tasks.filterValues { it.phase != TaskPhase.IDLE }
    return snapshot.copy(tasks = live, backgroundRunning = background,
      summary = TaskSummary.of(visible, snapshot.notices, snapshot.parents, snapshot.sessions.associate { it.id to it.displayTitle() }, snapshot.serverId, snapshot.profile.name))
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
    const val MAX_PARENT_FETCH = 48
    const val MAX_PARENT_ROUNDS = 8
    /** Upper bound of directories the auto-approver may sweep in one read. */
    const val MAX_SWEEP_DIRECTORIES = 12
    /** Streaming bursts coalesce for this long before one control-plane read is issued. */
    const val EVENT_READ_COALESCE_MILLIS = 3_000L
    const val STREAM_RESTART = "实时连接重建"
  }
}
