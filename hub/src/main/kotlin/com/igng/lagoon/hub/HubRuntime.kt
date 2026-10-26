package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.AllServersOverview
import com.igng.opencode.lagoon.core.ApiCapabilities
import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.DirectoryHealth
import com.igng.opencode.lagoon.core.HarnessClient
import com.igng.opencode.lagoon.core.Message
import com.igng.opencode.lagoon.core.ServerEvent
import com.igng.opencode.lagoon.core.ServerOverviewSnapshot
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.displaySummary
import com.igng.opencode.lagoon.core.isStale
import com.igng.opencode.lagoon.core.liveTasks
import com.igng.opencode.lagoon.core.sessionEventId
import com.igng.opencode.lagoon.core.sessionStatuses
import com.igng.opencode.lagoon.core.unseenFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The hub's live state. Every saved server is watched by the same [AllServersOverview] monitor the
 * phone uses for its all-servers home (the hub has no “current server”: all of them are reduced the
 * same way). Snapshots are normalized against the store, published to browsers at most every
 * [PUBLISH_MS] per server, and inspected by [Notifier] for pushes. Open conversations are
 * [ConversationView]s fed by the same event streams.
 *
 * Everything stateful runs on one thread ([dispatcher]); request threads enter it through [run].
 */
class HubRuntime(private val store: HubStore, private val streams: StreamRegistry, private val push: PushService?) : AutoCloseable {
  private val executor = Executors.newSingleThreadExecutor { Thread(it, "hub-runtime").apply { isDaemon = true } }
  private val dispatcher = executor.asCoroutineDispatcher()
  val scope = CoroutineScope(SupervisorJob() + dispatcher)

  private val overview = AllServersOverview(store, store, scope, ::acceptSnapshot, { null }, ::acceptEvent, ::acceptConnected,
    DirectoryHealth(), ::acceptRemoved)
  private val snapshots = mutableMapOf<String, ServerOverviewSnapshot>()
  private val published = mutableMapOf<String, String>()
  private val publishJobs = mutableMapOf<String, Job>()
  private val publishedAt = mutableMapOf<String, Long>()
  private val views = mutableMapOf<Pair<String, String>, ConversationView>()
  /** Servers with `autoConnect` off are only connected while a browser uses them. */
  private val touched = mutableMapOf<String, Long>()
  private var monitored = emptySet<String>()
  private val notifier = Notifier(scope, store, push, { snapshots[it] })

  fun start() = scope.launch {
    restartMonitors()
    // Drop on-demand connections nobody used for a while.
    while (true) {
      delay(60_000)
      val cutoff = System.currentTimeMillis() - ON_DEMAND_MS
      if (touched.values.any { it < cutoff }) { touched.entries.removeAll { it.value < cutoff }; restartMonitors() }
    }
  }

  /** Runs [block] on the runtime thread and waits for it; the entry point for request handlers. */
  fun <T> run(block: suspend HubRuntime.() -> T): T = runBlocking(dispatcher) { block() }

  override fun close() {
    runCatching { run { overview.stop(); views.values.forEach(ConversationView::stop) } }
    executor.shutdownNow()
  }

  // ---- Monitors ----

  private fun wanted(): Set<String> = store.profiles().filter { it.autoConnect || it.id in touched }.map { it.id }.toSet()

  /** Applies saved-profile changes: new, edited and removed servers are (re)connected or dropped. */
  fun restartMonitors() {
    val profiles = store.profiles()
    monitored = wanted()
    overview.start(profiles, include = monitored)
    val ids = profiles.map { it.id }.toSet()
    (snapshots.keys - ids).toList().forEach(::acceptRemoved)
    views.keys.filter { it.first !in ids }.forEach { key -> views.remove(key)?.stop() }
  }

  /** The live client of [serverId], connecting it first when its profile is not auto-connected. */
  fun client(serverId: String): HarnessClient {
    store.profile(serverId) ?: throw HttpError(404, "server-not-found", "服务器不存在")
    touched[serverId] = System.currentTimeMillis()
    if (serverId !in monitored) restartMonitors()
    return overview.client(serverId)
  }

  fun capabilities(serverId: String): ApiCapabilities =
    runCatching { if (serverId in monitored) overview.client(serverId).capabilities() else null }.getOrNull() ?: ApiCapabilities.BASELINE

  fun snapshot(serverId: String): ServerOverviewSnapshot? = snapshots[serverId]

  /** A session from the monitor's catalog, or read from the server. */
  suspend fun session(serverId: String, sessionId: String): Session {
    snapshots[serverId]?.sessions?.firstOrNull { it.id == sessionId }?.let { return it }
    val found = client(serverId).session(sessionId)
    if (found.id != sessionId) throw HttpError(404, "session-not-found", "会话不存在")
    return found.copy(serverId = serverId)
  }

  fun refresh(serverId: String) = overview.refresh(serverId)
  fun permissionSettled(serverId: String, requestId: String) = overview.settled(serverId, requestId)

  fun policyChanged() {
    overview.policyChanged()
    published.clear()
    snapshots.keys.forEach(::schedulePublish)
  }

  /** Applies a local change (rename, archive, new session…) at once instead of waiting for the next read. */
  fun patch(serverId: String, change: (ServerOverviewSnapshot) -> ServerOverviewSnapshot) {
    val current = snapshots[serverId] ?: return
    snapshots[serverId] = change(current)
    schedulePublish(serverId)
    overview.refresh(serverId)
  }

  private fun acceptSnapshot(snapshot: ServerOverviewSnapshot) {
    val id = snapshot.serverId
    val profile = store.profile(id) ?: return
    // The store is the ledger's single owner; the monitor's copy can lag a read acknowledgement.
    var normalized = snapshot.copy(profile = profile, notices = store.sessionNotices(id))
    normalized = acknowledgeVisible(normalized)
    val previous = snapshots.put(id, normalized)
    if (normalized.connected && !normalized.cached) store.rememberCatalog(id, normalized.projects, normalized.sessions)
    notifier.consider(previous, normalized)
    schedulePublish(id)
  }

  private fun acceptEvent(serverId: String, event: ServerEvent) {
    val sessionId = event.properties.sessionEventId(event.type)
    if (sessionId.isBlank()) return
    views[serverId to sessionId]?.onEvent(event)
  }

  private fun acceptConnected(serverId: String) {
    views.values.filter { it.serverId == serverId }.forEach(ConversationView::onReconnected)
  }

  private fun acceptRemoved(serverId: String) {
    snapshots.remove(serverId)
    published.remove(serverId)
    publishJobs.remove(serverId)?.cancel()
    notifier.forget(serverId)
    if (store.profile(serverId) == null) streams.broadcast("server.removed", JSONObject().put("serverId", serverId).toString())
    else schedulePublish(serverId)
  }

  // ---- Publishing ----

  private fun schedulePublish(serverId: String) {
    if (publishJobs[serverId]?.isActive == true) return
    val wait = (PUBLISH_MS - (System.currentTimeMillis() - (publishedAt[serverId] ?: 0))).coerceAtLeast(0)
    publishJobs[serverId] = scope.launch {
      delay(wait)
      publishedAt[serverId] = System.currentTimeMillis()
      val text = serverJson(serverId)?.toString() ?: return@launch
      if (published[serverId] == text) return@launch
      published[serverId] = text
      streams.broadcast("server", text)
    }
  }

  /** The browser's view of one server: the monitor snapshot plus everything derived from the policy. */
  fun serverJson(serverId: String, now: Long = System.currentTimeMillis()): JSONObject? {
    val profile = store.profile(serverId) ?: return null
    val snapshot = snapshots[serverId] ?: ServerOverviewSnapshot(serverId, profile, loading = serverId in monitored,
      notices = store.sessionNotices(serverId))
    val policy = store.permissionPolicy()
    val tasks = policy.tasks(snapshot.liveTasks(now), snapshot.permissions, snapshot.autoApprovalFailures)
    return JSONObject()
      .put("serverId", serverId).put("profile", Codec.profile(profile)).put("hasSecret", store.hasSecret(serverId))
      .put("monitored", serverId in monitored).put("version", snapshot.version).put("connected", snapshot.connected)
      .put("cached", snapshot.cached).put("loading", snapshot.loading).put("lastReadAt", snapshot.lastReadAt)
      .put("error", snapshot.error ?: JSONObject.NULL).put("degraded", snapshot.degraded).put("streamConnected", snapshot.streamConnected)
      .put("stale", snapshot.isStale(now)).put("canSyncRead", snapshot.canSyncRead)
      .put("capabilities", Codec.capabilities(capabilities(serverId)))
      .put("projects", Codec.array(snapshot.projects.map(Codec::project)))
      .put("sessions", Codec.array(snapshot.sessions.map(Codec::session)))
      .put("parents", JSONObject(snapshot.parents as Map<*, *>))
      .put("tasks", JSONObject().apply { tasks.forEach { (id, task) -> put(id, Codec.task(task)) } })
      .put("notices", Codec.array(snapshot.notices.map(Codec::notice)))
      .put("permissions", Codec.array(snapshot.permissions.filter { policy.visible(it.id, snapshot.autoApprovalFailures) }.map(Codec::permission)))
      .put("questions", Codec.array(snapshot.questions.map(Codec::question)))
      .put("backgroundRunning", Codec.array(snapshot.backgroundRunning))
      .put("autoApprovalFailures", Codec.array(snapshot.autoApprovalFailures))
      .put("shells", Codec.array(snapshot.shells.values.map(Codec::shell)))
      .put("summary", Codec.summary(snapshot.displaySummary(policy, now)))
      .put("statuses", JSONObject().apply { snapshot.sessionStatuses(policy, now).forEach { (id, status) -> if (status.name != "NONE") put(id, status.name) } })
  }

  fun stateJson(): JSONObject = JSONObject()
    .put("servers", Codec.array(store.profiles().mapNotNull { serverJson(it.id) }))
    .put("policy", Codec.policy(store.permissionPolicy()))
    .put("refreshMode", store.refreshMode().name)
    .put("ui", store.ui())

  // ---- Browser streams and open conversations ----

  /** Sends the complete state to a newly connected tab. */
  fun greet(stream: EventStream) {
    stream.send("state", stateJson())
  }

  /** Replaces the conversations [stream] has open. */
  fun watch(stream: EventStream, conversations: Set<Pair<String, String>>, visible: Boolean) {
    val before = stream.watched
    stream.watched = conversations
    stream.visible = visible
    (before - conversations).forEach { key -> unwatch(stream.id, key) }
    conversations.forEach { key ->
      if (store.profile(key.first) == null) return@forEach
      val view = views.getOrPut(key) {
        ConversationView(key.first, key.second, scope, { client(key.first) }) { _, streamId, frame ->
          streams.get(streamId)?.send(if (frame.has("ops")) "conv.ops" else "conv.reset", frame)
        }.also { it.start() }
      }
      view.releaseJob?.cancel(); view.releaseJob = null
      // A tab that was already watching keeps its copy; a new watcher gets the window once it is loaded.
      if (view.watchers.add(stream.id) && key !in before) stream.send("conv.reset", view.resetFrame())
    }
    if (visible) conversations.forEach { (server, session) -> acknowledge(server, session) }
  }

  /** A tab closed its stream: release what it watched. */
  fun detach(stream: EventStream) {
    stream.watched.forEach { unwatch(stream.id, it) }
    stream.watched = emptySet()
  }

  private fun unwatch(streamId: String, key: Pair<String, String>) {
    val view = views[key] ?: return
    view.watchers.remove(streamId)
    if (view.watchers.isEmpty() && view.releaseJob == null) {
      view.releaseJob = scope.launch {
        delay(ConversationView.RELEASE_AFTER_MS)
        if (view.watchers.isEmpty()) { views.remove(key); view.stop() }
      }
    }
  }

  internal fun conversation(serverId: String, sessionId: String): ConversationView? = views[serverId to sessionId]

  fun optimistic(serverId: String, sessionId: String, message: Message) { views[serverId to sessionId]?.optimistic(message) }
  fun dropOptimistic(serverId: String, sessionId: String, id: String) { views[serverId to sessionId]?.dropOptimistic(id) }
  fun reloadConversation(serverId: String, sessionId: String) { views[serverId to sessionId]?.scheduleReload() }

  /**
   * Official `MarkSessionNotificationsViewed`: a conversation on screen has no unread results; on
   * servers that record it, the server's own read marker for the finished run is synced too.
   */
  fun acknowledge(serverId: String, sessionId: String) {
    val snapshot = snapshots[serverId] ?: return
    val unseen = store.sessionNotices(serverId).unseenFor(sessionId)
    if (unseen.isNotEmpty()) {
      val notices = store.viewNotices(serverId, sessionId, unseen.map { it.id }.toSet())
      snapshots[serverId] = snapshot.copy(notices = notices)
      schedulePublish(serverId)
    }
    val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return
    val idle = session.idle
    if (idle > 0 && session.viewed < idle && capabilities(serverId).sessionView != null) {
      patchSession(serverId, sessionId) { it.copy(viewed = idle) }
      scope.launch {
        runCatching { client(serverId).viewSession(session, idle) }
          .onFailure { Diagnostics.warn("SessionView", "服务端已读同步失败，保留本地已读记录") }
      }
    }
  }

  private fun acknowledgeVisible(snapshot: ServerOverviewSnapshot): ServerOverviewSnapshot {
    val visible = streams.all().filter { it.visible }.flatMap { it.watched }.filter { it.first == snapshot.serverId }.map { it.second }.toSet()
    val unseen = snapshot.notices.filter { !it.viewed && it.sessionId in visible }
    if (unseen.isEmpty()) return snapshot
    var notices = snapshot.notices
    unseen.groupBy { it.sessionId }.forEach { (session, list) -> notices = store.viewNotices(snapshot.serverId, session, list.map { it.id }.toSet()) }
    return snapshot.copy(notices = notices)
  }

  private fun patchSession(serverId: String, sessionId: String, change: (Session) -> Session) {
    val snapshot = snapshots[serverId] ?: return
    snapshots[serverId] = snapshot.copy(sessions = snapshot.sessions.map { if (it.id == sessionId) change(it) else it })
    schedulePublish(serverId)
  }

  fun profileChanged(profile: ServerProfile) {
    touched[profile.id] = System.currentTimeMillis()
    restartMonitors()
    snapshots[profile.id]?.let { snapshots[profile.id] = it.copy(profile = profile) }
    schedulePublish(profile.id)
  }

  fun profileRemoved(serverId: String) {
    touched.remove(serverId)
    restartMonitors()
    acceptRemoved(serverId)
  }

  companion object {
    const val PUBLISH_MS = 250L
    const val ON_DEMAND_MS = 10 * 60_000L
  }
}
