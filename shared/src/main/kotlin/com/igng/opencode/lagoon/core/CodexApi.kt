package com.igng.opencode.lagoon.core

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject

/** Codex business projection. The connected carrier owns initialization and transport lifecycle. */
class CodexApi(private val transport: CodexTransport) : HarnessClient {
  private val sessions = ConcurrentHashMap<String, Session>()
  private val active = ConcurrentHashMap<String, String>()
  private val busy = ConcurrentHashMap.newKeySet<String>()
  // Live events can lead the paged catalog; do not let a lagging idle row erase them.
  private val eventBusy = ConcurrentHashMap.newKeySet<String>()
  private val loaded = ConcurrentHashMap.newKeySet<String>()
  private val stateGate = Any()
  private val completedTurns = LinkedHashSet<String>()
  private fun turnKey(sessionId: String, turnId: String) = "$sessionId\u0000$turnId"
  private val requests = ConcurrentHashMap<String, PendingRequest>()
  private val projectAdmissions = ConcurrentHashMap<String, String>()
  private val startedText = ConcurrentHashMap.newKeySet<String>()
  private val eventSequence = AtomicLong()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val subscriberLock = Any()
  private val subscribers = mutableSetOf<Channel<ServerEvent>>()
  private var terminalFailure: IOException? = null
  private fun publish(event: ServerEvent) {
    synchronized(subscriberLock) {
      if (closed) return
      val failed = subscribers.filter { !it.trySend(event).isSuccess }
      failed.forEach { it.close(IOException("Codex 事件消费者过载，请重新连接并读取历史")); subscribers.remove(it) }
    }
  }
  private fun finishSubscribers(error: IOException?) {
    synchronized(subscriberLock) {
      terminalFailure = error
      subscribers.forEach { it.close(error) }
      subscribers.clear()
    }
  }
  @Volatile private var closed = false
  private val collectorStarted = AtomicBoolean(false)
  private val subscriptionMutex = Mutex()
  private val subscriptions = LinkedHashSet<String>()
  private var foregroundId: String? = null
  private var monitoredIds: Set<String> = emptySet()
  // A prompt has its own subscription lease, independent of whatever thread the UI selects.
  private val taskIds = mutableSetOf<String>()
  private val admissions = mutableSetOf<String>()
  private val maxMonitoredThreads = 15 // Host supports at most 16 streams per connection; reserve one for foreground.
  private fun ensureCollector() {
    if (!collectorStarted.compareAndSet(false, true)) return
    // Start only after construction: a lazy Remote carrier can fail and release its owner synchronously.
    // One owner processes each frame exactly once, regardless of the number of UI/monitor readers.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        transport.events().collect { frame -> projectEvent(frame).forEach(::publish) }
      } catch (cancel: CancellationException) { throw cancel }
      catch (_: Exception) { /* fail closed below; readers see the lost connection */ }
      finally {
        if (!closed) {
          closed = true
          active.clear(); busy.clear(); eventBusy.clear(); loaded.clear(); requests.clear(); transport.close()
          finishSubscribers(IOException("Codex 连接已断开，请重新连接并读取历史"))
        }
      }
    }
  }
  private data class PendingRequest(val wireId: Any, val sessionId: String, val method: String, val params: JSONObject)

  private suspend fun rpc(method: String, params: JSONObject): JSONObject {
    ensureCollector()
    return transport.call(method, params)
  }
  private fun objects(array: JSONArray, name: String): List<JSONObject> =
    (0 until array.length()).map { array.optJSONObject(it) ?: throw ApiException(502, "Codex $name 格式错误") }
  private fun objects(value: JSONObject, name: String): List<JSONObject> =
    objects(value.optJSONArray(name) ?: throw ApiException(502, "Codex 响应缺少 $name"), name)
  private fun nextCursor(value: JSONObject): String? {
    if (!value.has("nextCursor") || value.isNull("nextCursor")) return null
    val raw = value.opt("nextCursor") as? String ?: throw ApiException(502, "Codex 分页游标格式错误")
    return raw.ifBlank { null }
  }
  override val isClosed: Boolean get() = closed || transport.isClosed
  override fun close() { if (!closed) { closed = true; requests.clear(); active.clear(); busy.clear(); eventBusy.clear(); loaded.clear(); scope.cancel(); transport.close(); finishSubscribers(null) } }
  override fun capabilities() = ApiCapabilities(
    actions = mapOf(SessionAction.RENAME to ActionEndpoint("RPC", "thread/name/set")),
    archive = ArchiveEndpoint(ActionEndpoint("RPC", "thread/archive")), documented = false,
    deleteSession = supportsDelete(), fork = true
  )
  private fun supportsDelete() = !transport.remote && !transport.isClosed && transport.serverVersion == "0.167.2"
  override suspend fun discoverCapabilities(): ApiCapabilities {
    // The SSH process advertises its version only after initialization.
    if (!transport.remote && transport.serverVersion == null) rpc("account/read", JSONObject().put("refreshToken", false))
    return capabilities()
  }
  override suspend fun health(): String {
    ensureCollector()
    val account = rpc("account/read", JSONObject().put("refreshToken", false))
    val requiresAuth = account.opt("requiresOpenaiAuth") as? Boolean ?: throw ApiException(502, "Codex 登录状态格式错误")
    if (requiresAuth && account.optJSONObject("account") == null)
      throw ApiException(401, "开发机上的 Codex 尚未登录，请先在开发机完成登录")
    return "Codex App Server"
  }

  private suspend fun listThreads(directory: String?, cursor: String?, size: Int, archived: Boolean = false): JSONObject {
    val params = JSONObject().put("limit", size.coerceIn(1, 100)).put("archived", archived)
      .put("sourceKinds", JSONArray().put("cli").put("vscode").put("exec").put("appServer"))
    if (!directory.isNullOrBlank()) params.put("cwd", directory)
    if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
    return rpc("thread/list", params)
  }
  override suspend fun projects(): List<Project> {
    ensureCollector()
    val projects = mutableListOf<Project>()
    var projectCursor: String? = null
    val projectSeen = mutableSetOf<String>()
    try {
      do {
        val params = JSONObject().put("limit", 100)
        projectCursor?.let { params.put("cursor", it) }
        val result = rpc("project/list", params)
        projects += objects(result, "data").flatMap { project ->
          val id = project.str("id")
          project.arr("roots").objects().mapNotNull { root ->
            val path = root.str("path"); if (id.isBlank() || !isAbsoluteCodexPath(path)) null
            else Project(id, path, project.str("name").ifBlank { path.substringAfterLast('/') })
          }
        }
        projectCursor = nextCursor(result)
        if (projectCursor != null && !projectSeen.add(projectCursor!!)) throw ApiException(502, "Codex 项目分页游标重复")
      } while (projectCursor != null)
    } catch (error: ApiException) {
      // The pinned Remote Host maps several upstream failures to CODEX_UPSTREAM_ERROR.
      // Its published catalog contract deliberately falls back even on transient failures;
      // subsequent thread/list is filtered by the Host's own workspace authority.
      if (projects.isNotEmpty() || (error.status !in setOf(404, 501, -32601) && error.code !in setOf("METHOD_NOT_FOUND", "METHOD_NOT_ALLOWED", "CODEX_UPSTREAM_ERROR"))) throw error
    }
    if (projects.isNotEmpty()) return projects
    // Exact observed cwd is a fallback. Never infer a common parent or grant access to it.
    val output = LinkedHashMap<String, Project>()
    for (archived in listOf(false, true)) {
      var cursor: String? = null
      val seen = mutableSetOf<String>()
      do {
        val page = listThreads(null, cursor, 100, archived)
        objects(page, "data").forEach { raw ->
          val cwd = raw.str("cwd")
          if (isAbsoluteCodexPath(cwd)) output.putIfAbsent(cwd, Project("codex:cwd:$cwd", cwd, cwd.substringAfterLast('/').ifBlank { cwd }))
        }
        cursor = nextCursor(page)
        if (cursor != null && !seen.add(cursor!!)) throw ApiException(502, "Codex 线程分页游标重复")
      } while (cursor != null)
    }
    return output.values.toList()
  }
  override suspend fun projectAt(directory: String): Project? {
    if (directory.isBlank()) return projects().firstOrNull()
    if (!isAbsoluteCodexPath(directory)) throw ApiException(400, "请选择绝对目录")
    if (transport.remote) {
      // Host verifies the path against its workspace authority and realpath.
      val listing = rpc("dsh/directoryList", JSONObject().put("path", directory))
      val confirmed = listing.str("path").takeIf(::isAbsoluteCodexPath) ?: throw ApiException(502, "Codex 未确认目录")
      return Project("codex:cwd:$confirmed", confirmed, confirmed.substringAfterLast('/').ifBlank { confirmed })
    }
    val metadata = rpc("fs/getMetadata", JSONObject().put("path", directory))
    if (!metadata.optBoolean("isDirectory")) throw ApiException(400, "所选路径不是目录")
    return Project("codex:cwd:$directory", directory, directory.substringAfterLast('/').ifBlank { directory })
  }
  override suspend fun createCodexProject(path: String): Project? {
    if (!transport.remote) return null
    require(isAbsoluteCodexPath(path) && path.none(Char::isISOControl)) { "请选择绝对目录" }
    val label = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { "Codex 项目" }
    val admission = projectAdmissions.computeIfAbsent(path) { java.util.UUID.randomUUID().toString() }
    val response = rpc("project/create", JSONObject().put("name", label)
      .put("roots", JSONArray().put(JSONObject().put("path", path))).put("idempotencyKey", admission))
    val raw = response.optJSONObject("project") ?: throw ApiException(502, "Codex 未确认新项目；请刷新项目列表")
    val id = raw.str("id")
    val roots = raw.optJSONArray("roots")?.objects().orEmpty()
    if (id.isBlank() || roots.size != 1 || !isAbsoluteCodexPath(roots[0].str("path")))
      throw ApiException(502, "Codex 返回了无效项目；请刷新项目列表")
    projectAdmissions.remove(path, admission)
    val root = roots[0].str("path")
    return Project(id, root, raw.str("name").ifBlank { label })
  }
  override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int): ApiPage<Session> {
    ensureCollector()
    // App Server lists active and archived threads separately. Carry both phases in the opaque
    // local cursor so a refresh still discovers archived threads for restoration.
    val archived = cursor?.startsWith("archived:") == true
    val serverCursor = when {
      cursor == null -> null
      cursor.startsWith("active:") -> cursor.removePrefix("active:").ifBlank { null }
      archived -> cursor.removePrefix("archived:").ifBlank { null }
      else -> throw ApiException(400, "Codex 线程游标无效")
    }
    val boundedSize = size.coerceIn(1, 100)
    val result = listThreads(directory, serverCursor, boundedSize, archived)
    val items = objects(result, "data").map { thread(it).copy(archived = archived) }.toMutableList()
    val nextServer = nextCursor(result)
    var next = if (nextServer != null) "${if (archived) "archived" else "active"}:$nextServer"
      else if (!archived) "archived:" else null
    if (!archived && nextServer == null && items.size < boundedSize) {
      val archivedResult = listThreads(directory, null, boundedSize - items.size, true)
      items += objects(archivedResult, "data").map { thread(it).copy(archived = true) }
      next = nextCursor(archivedResult)?.let { "archived:$it" }
    }
    items.forEach { sessions[it.id] = it }
    return ApiPage(items, next)
  }
  override suspend fun session(id: String): Session {
    ensureCollector()
    val value = rpc("thread/read", JSONObject().put("threadId", id).put("includeTurns", false)).optJSONObject("thread")
      ?: throw ApiException(502, "Codex 未返回线程")
    return thread(value).also { sessions[it.id] = it }
  }
  private fun thread(raw: JSONObject): Session {
    val id = raw.str("id"); if (id.isBlank()) throw ApiException(502, "Codex 线程缺少 ID")
    val cwd = raw.str("cwd")
    val updated = codexTime(raw.optLong("updatedAt"))
    val created = codexTime(raw.optLong("createdAt"))
    val status = raw.optJSONObject("status")?.str("type").orEmpty()
    synchronized(stateGate) {
      if (status == "active") {
        busy.add(id)
        raw.optJSONObject("status")?.str("turnId")?.takeIf(String::isNotBlank)?.let { active[id] = it }
      } else if (status in setOf("idle", "notLoaded") && id !in eventBusy) { busy.remove(id); active.remove(id) }
    }
    return Session(id, cwd, raw.str("name").ifBlank { raw.str("preview").ifBlank { "新会话" } }, updated,
      projectId = raw.str("projectId").ifBlank { null }, created = created,
      archived = if (raw.has("archived")) raw.optBoolean("archived") else sessions[id]?.archived ?: false)
  }
  private fun isAbsoluteCodexPath(path: String): Boolean = path.startsWith('/') || Regex("^[A-Za-z]:[\\\\/].+").matches(path) || path.startsWith("\\\\")
  private fun codexTime(value: Long): Long = if (value in 1..9_999_999_999L) value * 1000 else value

  override suspend fun viewSession(session: Session, idle: Long) = Unit
  override suspend fun selectCodexSession(session: Session?) {
    ensureCollector()
    session?.let { sessions[it.id] = it }
    subscriptionMutex.withLock {
      val previous = foregroundId
      val next = session?.id
      foregroundId = next
      if (previous != null && previous != next && previous !in monitoredIds && previous !in taskIds && subscriptions.contains(previous)) {
        transport.unsubscribe(previous)
        subscriptions.remove(previous)
      }
      if (next != null && subscriptions.add(next)) {
        try { transport.subscribe(next) } catch (error: Throwable) { subscriptions.remove(next); throw error }
      }
    }
  }
  private suspend fun acquireTaskSubscription(session: Session): Boolean {
    sessions[session.id] = session
    return subscriptionMutex.withLock {
      if (!admissions.add(session.id)) throw ApiException(409, "此 Codex 会话正在提交消息")
      val wasTask = !taskIds.add(session.id)
      if (subscriptions.add(session.id)) {
        try { transport.subscribe(session.id) } catch (error: Throwable) {
          subscriptions.remove(session.id); admissions.remove(session.id)
          if (!wasTask) taskIds.remove(session.id)
          throw error
        }
      }
      !wasTask
    }
  }
  private suspend fun releaseTaskLocked(sessionId: String) {
    taskIds.remove(sessionId)
    if (sessionId != foregroundId && sessionId !in monitoredIds && subscriptions.contains(sessionId)) {
      transport.unsubscribe(sessionId)
      subscriptions.remove(sessionId)
    }
  }
  private suspend fun finishTaskAdmission(sessionId: String, turnId: String?, releaseRejected: Boolean) = subscriptionMutex.withLock {
    admissions.remove(sessionId)
    val release = synchronized(stateGate) {
      (turnId != null && turnKey(sessionId, turnId) in completedTurns) ||
        (releaseRejected && sessionId !in eventBusy && active[sessionId] == null)
    }
    if (release) releaseTaskLocked(sessionId)
  }
  /** Only these protocol errors prove the request was rejected before a turn was admitted. */
  private fun definiteAdmissionRejection(error: Throwable): Boolean = when (error) {
    is ApiException -> error.status in setOf(-32601, -32602) || error.code in setOf("METHOD_NOT_ALLOWED", "METHOD_NOT_FOUND", "INVALID_MESSAGE")
    is RemoteRpcException -> error.code in setOf("METHOD_NOT_ALLOWED", "METHOD_NOT_FOUND", "INVALID_MESSAGE")
    else -> false
  }
  private fun releaseFinishedTask(sessionId: String) {
    scope.launch {
      subscriptionMutex.withLock {
        if (sessionId !in admissions && sessionId in taskIds) releaseTaskLocked(sessionId)
      }
    }
  }
  override suspend fun monitorCodexSessions(active: List<Session>, selectedId: () -> String?) {
    // A separate SSH stdio process cannot subscribe to turns owned by another process.
    if (!transport.remote) return
    ensureCollector()
    subscriptionMutex.withLock {
      val selected = selectedId()?.takeIf(String::isNotBlank)
      // The monitor receives the authoritative current foreground selection for this binding.
      foregroundId = selected
      val candidates = active.asSequence().filter { it.id.isNotBlank() && it.id != selected }
        .distinctBy { it.id }.sortedByDescending { it.activityAt }.toList()
      val wanted = candidates.take(maxMonitoredThreads).map { it.id }.toSet()
      monitoredIds = wanted
      val stale = subscriptions.filter { it !in wanted && it != foregroundId && it !in taskIds }
      stale.forEach { id ->
        // Selection may change during this pass; the selected thread takes priority over cleanup.
        if (id != selectedId() && id != foregroundId) {
          transport.unsubscribe(id)
          subscriptions.remove(id)
        }
      }
      if (selected != null && subscriptions.add(selected)) {
        try { transport.subscribe(selected) } catch (error: Throwable) { subscriptions.remove(selected); throw error }
      }
      for (session in candidates.take(maxMonitoredThreads)) {
        sessions[session.id] = session
        if (subscriptions.add(session.id)) {
          try { transport.subscribe(session.id) } catch (error: Throwable) { subscriptions.remove(session.id); throw error }
        }
      }
      if (candidates.size > maxMonitoredThreads) throw ApiException(429, "Codex 后台运行会话超过远端订阅上限，部分会话未监控")
    }
  }
  override suspend fun messagesPage(id: String, cursor: String?, size: Int): ApiPage<Message> {
    ensureCollector()
    if (transport.remote) {
      val params = JSONObject().put("threadId", id).put("maxMessages", size.coerceIn(1, 100))
      if (cursor != null) params.put("beforeSeq", cursor.toLongOrNull() ?: throw ApiException(400, "历史游标无效"))
      val value = rpc("dsh/sessionHistory", params)
      val records = value.optJSONArray("records") ?: throw ApiException(502, "Codex 历史响应缺少记录")
      val hasMore = value.opt("hasMore") as? Boolean ?: throw ApiException(502, "Codex 历史分页格式错误")
      if (value.has("activeTurnId") && !value.isNull("activeTurnId") && value.opt("activeTurnId") !is String)
        throw ApiException(502, "Codex 活动轮次格式错误")
      val activeTurn = value.str("activeTurnId")
      if (activeTurn.isNotBlank()) { active[id] = activeTurn; busy.add(id) }
      else if (value.has("activeTurnId") && id !in eventBusy) { active.remove(id); busy.remove(id) }
      val messages = remoteRecords(id, records)
      val next = if (hasMore) {
        objects(records, "records").mapNotNull { it.optJSONObject("event")?.optLong("seq") }.minOrNull()?.toString()
          ?: throw ApiException(502, "Codex 历史分页缺少游标")
      } else null
      return ApiPage(messages, next)
    }
    val params = JSONObject().put("threadId", id).put("limit", size.coerceIn(1, 100))
      .put("sortDirection", "desc").put("itemsView", "full")
    cursor?.let { params.put("cursor", it) }
    try {
      val response = rpc("thread/turns/list", params)
      val data = objects(response, "data")
      val turns = JSONArray()
      data.asReversed().forEach(turns::put)
      if (cursor == null) {
        val live = data.firstOrNull { it.str("status") == "inProgress" }?.str("id").orEmpty()
        if (live.isNotBlank()) { active[id] = live; busy.add(id) }
      }
      return ApiPage(nativeTurns(turns), nextCursor(response))
    } catch (error: ApiException) {
      if (cursor != null || error.status !in setOf(404, 501, -32601) && error.code !in setOf("METHOD_NOT_FOUND", "METHOD_NOT_ALLOWED")) throw error
    }
    val value = rpc("thread/read", JSONObject().put("threadId", id).put("includeTurns", true))
    val thread = value.optJSONObject("thread") ?: throw ApiException(502, "Codex 历史响应缺少线程")
    if (thread.str("id") != id) throw ApiException(502, "Codex 返回了错误的线程")
    val turns = thread.optJSONArray("turns") ?: throw ApiException(502, "Codex 历史响应缺少轮次")
    val live = turns.objects().lastOrNull { it.str("status") == "inProgress" }?.str("id").orEmpty()
    if (live.isNotBlank()) { active[id] = live; busy.add(id) }
    else if (id !in eventBusy) { active.remove(id); busy.remove(id) }
    return ApiPage(nativeTurns(turns))
  }
  override suspend fun inbox(id: String) = emptyList<Message>()
  override suspend fun activeSessions(): Set<String> {
    if (!transport.remote) return busy.toSet()
    // A single catalog page is not an authoritative inventory: missing pages must never clear
    // background watches. Limit the read explicitly and report an incomplete scan as degraded.
    val activeOnHost = mutableSetOf<String>()
    val idleOnHost = mutableSetOf<String>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    var pages = 0
    do {
      if (++pages > 64) throw ApiException(429, "Codex 活动会话目录超过扫描上限")
      val page = listThreads(null, cursor, 100)
      for (row in objects(page, "data")) {
        val id = row.str("id").takeIf(String::isNotBlank) ?: throw ApiException(502, "Codex 线程缺少 ID")
        val status = row.optJSONObject("status")?.str("type").orEmpty()
        when (status) {
          "active" -> activeOnHost.add(id)
          "idle", "notLoaded" -> idleOnHost.add(id)
          "systemError" -> throw ApiException(502, "Codex 线程状态异常，后台监控未更新")
          else -> throw ApiException(502, "Codex 线程状态未知，后台监控未更新")
        }
      }
      cursor = nextCursor(page)
      if (cursor != null && !seen.add(cursor!!)) throw ApiException(502, "Codex 线程分页游标重复")
    } while (cursor != null)
    // Own live turns may not have materialized in the catalog yet; never drop their watch.
    return synchronized(stateGate) {
      // A successful complete scan can retire old snapshot-derived busy state, but never a
      // newer live/locally-admitted turn whose terminal event has not yet been observed.
      idleOnHost.filterNot(eventBusy::contains).forEach { id -> busy.remove(id); active.remove(id) }
      activeOnHost + eventBusy + active.keys
    }
  }
  override suspend fun shells(directory: String): List<ShellJob> = unsupported()

  override suspend fun createSession(directory: String, title: String, workspaceId: String?): Session {
    ensureCollector()
    val response = rpc("thread/start", JSONObject().put("cwd", directory))
    val created = response.optJSONObject("thread") ?: throw ApiException(502, "Codex 未返回新线程")
    val session = thread(created).also { sessions[it.id] = it }
    loaded.add(session.id)
    // A late creation result must not change the user's foreground selection.
    // Creating a thread has already succeeded. Never retry creation if this separate rename fails.
    if (title.isNotBlank()) {
      try { renameSession(session, title) }
      catch (cancel: CancellationException) { throw cancel }
      catch (e: Exception) { throw ApiException(409, "线程已创建（${session.id}），但设置标题失败，请在列表中重命名") }
      return session.copy(title = title).also { sessions[it.id] = it }
    }
    return session
  }
  override suspend fun renameSession(session: Session, title: String) {
    require(title.isNotBlank())
    rpc("thread/name/set", JSONObject().put("threadId", session.id).put("name", title))
    sessions[session.id] = session.copy(title = title)
  }
  override suspend fun setArchived(session: Session, archived: Boolean, now: Long) {
    rpc(if (archived) "thread/archive" else "thread/unarchive", JSONObject().put("threadId", session.id))
    sessions[session.id] = session.copy(archived = archived)
  }
  override suspend fun deleteSession(session: Session) {
    if (!supportsDelete()) unsupported<Unit>()
    // A timeout has an unknown outcome; never repeat this destructive operation.
    rpc("thread/delete", JSONObject().put("threadId", session.id))
    forgetDeletedThread(session.id)
  }
  private fun forgetDeletedThread(id: String) {
    if (id.isBlank()) return
    synchronized(stateGate) { active.remove(id); busy.remove(id); eventBusy.remove(id) }
    loaded.remove(id)
    sessions.remove(id)
    requests.entries.filter { it.value.sessionId == id }.forEach { requests.remove(it.key, it.value) }
    releaseFinishedTask(id)
  }
  override suspend fun forkSession(session: Session): Session {
    val response = rpc("thread/fork", JSONObject().put("threadId", session.id))
    return thread(response.optJSONObject("thread") ?: throw ApiException(502, "Codex 未返回分叉线程"))
      .also { sessions[it.id] = it; loaded.add(it.id) }
  }
  override suspend fun abort(session: Session) {
    val turn = active[session.id] ?: throw ApiException(409, "当前没有已确认的运行轮次，请刷新会话")
    rpc("turn/interrupt", JSONObject().put("threadId", session.id).put("turnId", turn))
    // turn/completed, not the interrupt acknowledgement, settles the running state.
  }
  override suspend fun compact(session: Session): Unit = unsupported()
  override suspend fun revert(session: Session, messageId: String): Unit = unsupported()
  override suspend fun unrevert(session: Session): Unit = unsupported()
  override suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?, references: List<FileReference>,
    inline: List<InlineFile>, id: String, delivery: Delivery) {
    ensureCollector()
    if (references.isNotEmpty()) throw ApiException(501, "Codex 暂不支持此文件引用方式")
    val input = JSONArray()
    if (text.isNotBlank()) input.put(JSONObject().put("type", "text").put("text", text))
    inline.forEach { file ->
      val match = Regex("^data:(image/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/]+={0,2})$").matchEntire(file.uri)
        ?: throw ApiException(400, "Codex 只接受 PNG、JPEG、WebP 或 GIF 图片")
      if (transport.remote) input.put(JSONObject().put("type", "image").put("mediaType", match.groupValues[1]).put("data", match.groupValues[2]))
      else input.put(JSONObject().put("type", "image").put("url", file.uri))
    }
    require(input.length() > 0) { "消息不能为空" }
    val existing = active[session.id]
    if (existing == null && session.id in busy) throw ApiException(409, "当前轮次正在运行但尚未确认其 ID，请刷新会话")
    if (existing != null && delivery == Delivery.QUEUE) throw ApiException(501, "Codex 不支持此排队方式")
    val newTaskLease = acquireTaskSubscription(session)
    var admittedTurn: String? = existing
    var mutationIssued = false
    var rejected = false
    try {
      if (existing != null) {
        mutationIssued = true
        rpc("turn/steer", JSONObject().put("threadId", session.id).put("input", input).put("expectedTurnId", existing))
        return
      }
      // The read-only view does not resume. Resume only when the user explicitly sends.
      if (session.id !in loaded) {
        rpc("thread/resume", JSONObject().put("threadId", session.id))
        loaded.add(session.id)
      }
      val params = JSONObject().put("threadId", session.id).put("input", input)
      if (!transport.remote) params.put("clientUserMessageId", id)
      model?.let { params.put("model", it.modelId); it.variant?.let { variant -> params.put("effort", variant) } }
      mutationIssued = true
      val response = rpc("turn/start", params)
      admittedTurn = response.optJSONObject("turn")?.str("id")?.takeIf(String::isNotBlank)
        ?: throw ApiException(502, "Codex 未确认轮次，发送结果未知；请刷新会话")
      synchronized(stateGate) {
        if (turnKey(session.id, admittedTurn!!) !in completedTurns) {
          active[session.id] = admittedTurn!!; busy.add(session.id); eventBusy.add(session.id)
        }
      }
    } catch (error: Throwable) {
      // A failed resume precedes the prompt mutation. Once a mutation has been dispatched,
      // only an explicit method/parameter rejection proves that it was not admitted.
      // Remote image sends use a multi-stage transfer: even INVALID_MESSAGE can describe a
      // failed response-chunk read after the Host already executed the mutation.
      val ambiguousTransfer = transport.remote && inline.isNotEmpty()
      rejected = existing == null && newTaskLease && (!mutationIssued || (!ambiguousTransfer && definiteAdmissionRejection(error)))
      throw error
    } finally {
      // Cancellation or a lost response may still have admitted the turn. Never replay it or
      // release its stream based only on the absence of an acknowledgement.
      withContext(NonCancellable) { finishTaskAdmission(session.id, admittedTurn, rejected) }
    }
  }
  override suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?,
    references: List<FileReference>, inline: List<InlineFile>, delivery: Delivery): Unit = unsupported()
  override suspend fun agents(directory: String) = emptyList<AgentChoice>()
  override suspend fun commands(directory: String) = emptyList<CommandChoice>()
  override suspend fun modelCatalog(directory: String): List<ModelInfo> {
    val all = mutableListOf<ModelInfo>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
      val params = JSONObject().put("limit", 100); cursor?.let { params.put("cursor", it) }
      val response = rpc("model/list", params)
      all += objects(response, "data").mapNotNull { m ->
        val model = m.str("model").ifBlank { m.str("id") }; if (model.isBlank()) return@mapNotNull null
        val variants = m.arr("supportedReasoningEfforts").objects().mapNotNull { v ->
          val effort = v.str("reasoningEffort"); if (effort.isBlank()) null else ModelVariant(effort, v.str("description").ifBlank { effort })
        }
        val modalities = m.optJSONArray("inputModalities")
        ModelInfo(ModelChoice("codex", model, m.str("displayName").ifBlank { model }), imageInput = modalities?.strings()?.contains("image"), variants = variants)
      }
      cursor = nextCursor(response)
      if (cursor != null && !seen.add(cursor!!)) throw ApiException(502, "Codex 模型分页游标重复")
    } while (cursor != null)
    return all
  }
  override suspend fun sessionPermissions(session: Session) = pendingPermissions(session.directory).filter { it.sessionId == session.id }
  override suspend fun pendingPermissions(directory: String): List<PermissionRequest> = requests.entries.mapNotNull { (key, item) ->
    if (item.method !in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval")) return@mapNotNull null
    val session = sessions[item.sessionId]
    if (directory.isNotBlank() && session?.directory != directory) return@mapNotNull null
    permission(key, item)
  }
  private fun permission(id: String, request: PendingRequest): PermissionRequest {
    val p = request.params
    return PermissionRequest(id, request.sessionId, sessions[request.sessionId]?.directory.orEmpty(),
      when { request.method.contains("fileChange") -> "文件修改"; request.method.contains("permissions/") -> "额外权限"; p.optJSONObject("networkApprovalContext") != null -> "网络访问"; else -> "命令执行" },
      listOf(p.str("reason"), p.str("command"), p.str("cwd"), p.optJSONObject("networkApprovalContext")?.toString().orEmpty(), p.optJSONObject("permissions")?.toString().orEmpty()).filter(String::isNotBlank).joinToString("\n"))
  }
  override suspend fun forms(directory: String): List<QuestionRequest> = requests.entries.mapNotNull { (key, item) ->
    if (!isQuestionMethod(item.method)) return@mapNotNull null
    val target = sessions[item.sessionId]?.directory.orEmpty()
    if (directory.isNotBlank() && target != directory) return@mapNotNull null
    if (item.method == "mcpServer/elicitation/request") mcpQuestion(key, item) else question(key, item)
  }
  private fun isQuestionMethod(method: String): Boolean =
    method == "item/tool/requestUserInput" || method == "mcpServer/elicitation/request"

  private fun question(id: String, request: PendingRequest): QuestionRequest? {
    val rows = request.params.arr("questions").objects()
    // This UI has no secret input widget; do not render secret answers as ordinary visible text.
    if (rows.isEmpty() || rows.any { it.str("id").isBlank() || it.optBoolean("isSecret") }) return null
    return QuestionRequest(id, request.sessionId, sessions[request.sessionId]?.directory.orEmpty(), rows.map { row ->
      val choices = row.arr("options").objects().map { QuestionOption(it.str("label"), it.str("description")) }
      QuestionPrompt(row.str("question"), choices, multiple = false, custom = row.optBoolean("isOther") || choices.isEmpty(), field = row.str("id"))
    }, form = false)
  }
  /** Only project schemas whose full validation rules can be represented by the existing form UI. */
  private fun mcpForm(id: String, request: PendingRequest): JSONObject? {
    return try {
    val params = request.params
    if (params.str("mode") !in setOf("form", "openai/form", "openaiForm")) return null
    val schema = params.optJSONObject("requestedSchema") ?: return null
    if (schema.str("type") != "object" || schema.keys().asSequence().any { it !in setOf("type", "properties", "required", "$" + "schema") }) return null
    val properties = schema.optJSONObject("properties") ?: return null
    if (properties.length() > 32) return null
    val requiredRaw = if (!schema.has("required") || schema.isNull("required")) JSONArray() else schema.optJSONArray("required") ?: return null
    val required = (0 until requiredRaw.length()).map { requiredRaw.opt(it) as? String ?: return null }
    if (required.distinct().size != required.size || required.any { !properties.has(it) }) return null
    var actionKey = "__lagoon_mcp_action"
    while (properties.has(actionKey)) actionKey += "_"
    val fields = JSONArray()
    val actionOptions = JSONArray().put(JSONObject().put("label", "提交回答").put("value", "accept"))
      .put(JSONObject().put("label", "拒绝请求").put("value", "decline"))
    fields.put(JSONObject().put("key", actionKey).put("title", params.str("message").ifBlank { "MCP 请求" })
      .put("description", params.str("serverName")).put("type", "select").put("required", true)
      .put("default", "accept").put("options", actionOptions))
    for (key in properties.keys().asSequence().toList()) {
      if (key.isBlank() || key.length > 256) return null
      val source = properties.optJSONObject(key) ?: return null
      val type = source.str("type")
      val allowed = when (type) {
        "string" -> setOf("type", "title", "description", "default", "minLength", "maxLength", "enum", "enumNames", "oneOf", "format")
        "number", "integer" -> setOf("type", "title", "description", "default", "minimum", "maximum")
        "boolean" -> setOf("type", "title", "description", "default")
        "array" -> setOf("type", "title", "description", "default", "minItems", "maxItems", "items")
        else -> return null
      }
      if (source.keys().asSequence().any { it !in allowed }) return null
      for (name in listOf("title", "description"))
        if (source.has(name) && !source.isNull(name) && source.opt(name) !is String) return null
      for (name in listOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) {
        if (!source.has(name) || source.isNull(name)) continue
        val number = source.opt(name) as? Number ?: return null
        if (!number.toDouble().isFinite()) return null
        if (name in setOf("minLength", "maxLength", "minItems", "maxItems") &&
          (number.toDouble() < 0 || number.toDouble() > Int.MAX_VALUE || number.toDouble() % 1.0 != 0.0)) return null
      }
      for ((lower, upper) in listOf("minimum" to "maximum", "minLength" to "maxLength", "minItems" to "maxItems"))
        if (source.has(lower) && !source.isNull(lower) && source.has(upper) && !source.isNull(upper) &&
          source.optDouble(lower) > source.optDouble(upper)) return null
      if (source.has("default") && !source.isNull("default")) {
        val value = source.opt("default")
        if (when (type) {
          // The current composer trims free text; never silently change a server default.
          "string" -> value !is String || value != value.trim()
          "number", "integer" -> value !is Number || !value.toDouble().isFinite()
          "boolean" -> value !is Boolean
          "array" -> value !is JSONArray || (0 until value.length()).any { value.opt(it) !is String }
          else -> true
        }) return null
      }
      // The generic form widget does not validate URI/email/date formats; never silently ignore one.
      if (source.has("format") && !source.isNull("format")) return null
      val field = JSONObject().put("key", key).put("title", source.str("title").ifBlank { key })
        .put("description", source.str("description")).put("required", key in required)
        .put("when", JSONArray().put(JSONObject().put("key", actionKey).put("value", "accept")))
      for (bound in listOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "default")) {
        // Validate string length by Unicode code points at submission; the generic widget counts UTF-16 units.
        if (type == "string" && bound in setOf("minLength", "maxLength")) continue
        if (source.has(bound) && !source.isNull(bound)) field.put(bound, source.opt(bound))
      }
      fun option(label: String, value: String): JSONObject? = if (label.isBlank() || value.isBlank()) null
        else JSONObject().put("label", label).put("value", value)
      fun plainOptions(values: JSONArray, labels: JSONArray? = null): JSONArray? {
        if (values.length() !in 1..64 || labels != null && labels.length() != values.length()) return null
        val output = JSONArray()
        val seen = mutableSetOf<String>()
        for (i in 0 until values.length()) {
          val value = values.opt(i) as? String ?: return null
          if (!seen.add(value)) return null
          val label = if (labels == null) value else labels.opt(i) as? String ?: return null
          output.put(option(label, value) ?: return null)
        }
        return output
      }
      fun titledOptions(values: JSONArray): JSONArray? {
        if (values.length() !in 1..64) return null
        val output = JSONArray()
        val seen = mutableSetOf<String>()
        for (i in 0 until values.length()) {
          val value = values.optJSONObject(i) ?: return null
          if (!seen.add(value.str("const"))) return null
          if (value.keys().asSequence().any { it !in setOf("const", "title") }) return null
          output.put(option(value.str("title"), value.str("const")) ?: return null)
        }
        return output
      }
      when (type) {
        "string" -> {
          val enum = source.optJSONArray("enum")
          val oneOf = source.optJSONArray("oneOf")
          if (enum != null && oneOf != null || source.has("enum") && enum == null || source.has("oneOf") && oneOf == null) return null
          val names = if (!source.has("enumNames") || source.isNull("enumNames")) null else source.optJSONArray("enumNames") ?: return null
          if (names != null && enum == null) return null
          val options = when { enum != null -> plainOptions(enum, names); oneOf != null -> titledOptions(oneOf); else -> null }
          if ((enum != null || oneOf != null) && options == null) return null
          field.put("type", if (options == null) "string" else "select")
          if (options != null) field.put("options", options)
          else field.put("description", listOf(source.str("description"), "输入首尾空格会被移除；留空表示不提供").filter(String::isNotBlank).joinToString("\n"))
        }
        "array" -> {
          val items = source.optJSONObject("items") ?: return null
          val enum = items.optJSONArray("enum")
          val choices = items.optJSONArray("anyOf")
          val options = when {
            enum != null && choices == null && items.str("type") == "string" && items.keys().asSequence().all { it in setOf("type", "enum") } -> plainOptions(enum)
            choices != null && enum == null && items.keys().asSequence().all { it == "anyOf" } -> titledOptions(choices)
            else -> return null
          } ?: return null
          field.put("type", "multiselect").put("options", options)
        }
        else -> field.put("type", type)
      }
      fields.put(field)
    }
    JSONObject().put("id", id).put("sessionID", request.sessionId).put("fields", fields)
    } catch (_: Exception) { null }
  }

  private fun mcpQuestion(id: String, request: PendingRequest): QuestionRequest? =
    mcpForm(id, request)?.toForm(sessions[request.sessionId]?.directory.orEmpty())

  private fun cancelUnsupportedMcp(id: String, pending: PendingRequest, reason: String) {
    scope.launch {
      val message = try {
        transport.respond(pending.wireId, JSONObject().put("action", "cancel").put("content", JSONObject.NULL))
        requests.remove(id, pending)
        "$reason，已取消该请求"
      } catch (cancel: CancellationException) { throw cancel }
      catch (_: Exception) { "$reason，自动取消失败，请在开发机处理" }
      publish(ServerEvent("codex:${eventSequence.incrementAndGet()}", sessions[pending.sessionId]?.directory.orEmpty(),
        "session.synthetic", JSONObject().put("sessionID", pending.sessionId).put("description", message), System.currentTimeMillis()))
    }
  }

  private fun rejectUnsupportedRequest(wireId: Any, sessionId: String, key: String? = null) {
    if (transport.remote) return // Remote Host has no generic JSON-RPC error response operation.
    scope.launch {
      val message = try {
        transport.respondError(wireId, -32601, "Unsupported server request")
        if (key != null) requests.remove(key)
        "不支持的 Codex 请求已拒绝"
      } catch (cancel: CancellationException) { throw cancel }
      catch (_: Exception) { "拒绝不支持的 Codex 请求失败，请在开发机处理或停止任务" }
      publish(ServerEvent("codex:${eventSequence.incrementAndGet()}", sessions[sessionId]?.directory.orEmpty(),
        "session.synthetic", JSONObject().put("sessionID", sessionId).put("description", message), System.currentTimeMillis()))
    }
  }

  override suspend fun replyPermission(request: PermissionRequest, reply: String) {
    val pending = requests[request.id] ?: throw ApiException(409, "审批请求已失效")
    val decision = when (reply) { "once" -> "accept"; "always" -> if (transport.remote) throw ApiException(501, "Remote 不支持本会话授权") else "acceptForSession"; else -> "decline" }
    val response = if (pending.method == "item/permissions/requestApproval") {
      val granted = if (decision == "accept" || decision == "acceptForSession") pending.params.optJSONObject("permissions") ?: JSONObject() else JSONObject()
      JSONObject().put("permissions", granted).apply { if (decision == "acceptForSession") put("scope", "session") }
    } else JSONObject().put("decision", decision)
    transport.respond(pending.wireId, response)
    requests.remove(request.id, pending)
  }
  override suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    val pending = requests[request.id] ?: throw ApiException(409, "提问已失效")
    require(request.sessionId == pending.sessionId) { "问题不属于此会话" }
    if (pending.method == "mcpServer/elicitation/request") {
      val form = mcpForm(request.id, pending) ?: throw ApiException(409, "MCP 表单已不受支持")
      val expected = form.toForm(sessions[pending.sessionId]?.directory.orEmpty())
      require(request.questions == expected.questions && answers.size == expected.questions.size) { "表单已变化，请刷新" }
      val fields = form.arr("fields").objects()
      val actionKey = fields.first().str("key")
      val values = formAnswer(expected, answers)
      val action = values.str(actionKey)
      require(action in setOf("accept", "decline")) { "请选择如何处理该请求" }
      values.remove(actionKey)
      if (action == "accept") {
        val properties = pending.params.obj("requestedSchema").obj("properties")
        fields.forEachIndexed { index, field ->
          if (index == 0) return@forEachIndexed
          val key = field.str("key")
          if (!values.has(key)) return@forEachIndexed
          val spec = properties.obj(key)
          when (spec.str("type")) {
            "string" -> {
              val text = answers[index].first()
              val length = text.codePointCount(0, text.length)
              require(length >= spec.optInt("minLength", 0) && length <= spec.optInt("maxLength", Int.MAX_VALUE)) { "长度无效：${field.str("title")}" }
            }
            "number", "integer" -> {
              val number = try { java.math.BigDecimal(answers[index].first()) } catch (_: NumberFormatException) { error("请输入数字：${field.str("title")}") }
              fun decimal(name: String): java.math.BigDecimal? = spec.opt(name)?.takeUnless { it == JSONObject.NULL }
                ?.let { java.math.BigDecimal(it.toString()) }
              require(decimal("minimum")?.let { number >= it } != false && decimal("maximum")?.let { number <= it } != false) { "数值超出范围：${field.str("title")}" }
              val exact: Any = if (spec.str("type") == "integer") try { number.longValueExact() } catch (_: ArithmeticException) { error("请输入有效整数：${field.str("title")}") } else number
              values.put(key, exact)
            }
          }
        }
      }
      transport.respond(pending.wireId, JSONObject().put("action", action)
        .put("content", if (action == "accept") values else JSONObject.NULL))
      requests.remove(request.id, pending)
      return
    }
    require(pending.method == "item/tool/requestUserInput" && answers.size == request.questions.size)
    val values = JSONObject()
    request.questions.forEachIndexed { i, question ->
      val questionId = if (question.field.startsWith("{")) runCatching { JSONObject(question.field).str("key") }.getOrDefault("") else question.field
      require(questionId.isNotBlank() && answers[i].isNotEmpty()) { "请填写全部问题" }
      values.put(questionId, JSONObject().put("answers", JSONArray(answers[i])))
    }
    transport.respond(pending.wireId, JSONObject().put("answers", values))
    requests.remove(request.id, pending)
  }
  override suspend fun rejectQuestion(request: QuestionRequest) {
    val pending = requests[request.id] ?: throw ApiException(409, "提问已失效")
    require(request.sessionId == pending.sessionId) { "问题不属于此会话" }
    if (pending.method != "mcpServer/elicitation/request") return unsupported()
    transport.respond(pending.wireId, JSONObject().put("action", "cancel").put("content", JSONObject.NULL))
    requests.remove(request.id, pending)
  }
  override suspend fun children(session: Session) = emptyList<Session>()
  override suspend fun diff(session: Session): List<FileChange> = unsupported()
  override suspend fun files(directory: String, path: String, session: Session?): List<FileNode> {
    val absolute = codexPath(directory, path)
    if (transport.remote) {
      val listing = rpc("dsh/directoryList", JSONObject().put("path", absolute))
      val entries = listing.optJSONArray("entries") ?: throw ApiException(502, "Codex 目录格式错误")
      return objects(entries, "entries").mapNotNull { entry ->
        val name = entry.str("name"); val child = entry.str("path")
        if (name.isBlank() || !isAbsoluteCodexPath(child)) null else FileNode(name, "directory", child)
      }
    }
    val listing = rpc("fs/readDirectory", JSONObject().put("path", absolute))
    val entries = listing.optJSONArray("entries") ?: throw ApiException(502, "Codex 目录格式错误")
    return objects(entries, "entries").mapNotNull { entry ->
      val name = entry.str("fileName")
      if (name.isBlank() || name in setOf(".", "..") || name.contains('/') || name.contains('\\')) return@mapNotNull null
      val type = when { entry.optBoolean("isDirectory") -> "directory"; entry.optBoolean("isFile") -> "file"; else -> return@mapNotNull null }
      FileNode(name, type, codexPath(absolute, name))
    }
  }
  override suspend fun fileContent(directory: String, path: String, session: Session?): FileContent {
    if (transport.remote) return unsupported()
    val result = rpc("fs/readFile", JSONObject().put("path", codexPath(directory, path)))
    val data = result.opt("dataBase64") as? String ?: throw ApiException(502, "Codex 文件内容缺失")
    if (data.length > 4 * 1024 * 1024) throw ApiException(413, "文件过大")
    val bytes = try { java.util.Base64.getDecoder().decode(data) } catch (_: IllegalArgumentException) { throw ApiException(502, "Codex 文件内容格式错误") }
    return FileContent("text", bytes.toString(Charsets.UTF_8))
  }
  private fun codexPath(directory: String, path: String): String {
    val absolute = if (path == "." || path.isBlank()) directory else if (isAbsoluteCodexPath(path)) path
      else directory.trimEnd('/', '\\') + (if ('\\' in directory && '/' !in directory) "\\" else "/") + path
    require(isAbsoluteCodexPath(absolute) && absolute.none(Char::isISOControl)) { "目录必须是绝对路径" }
    return absolute
  }
  override suspend fun searchFiles(directory: String, query: String, session: Session?): List<String> = unsupported()
  private fun <T> unsupported(): T = throw ApiException(501, "当前 Codex 连接不支持此操作")

  override fun events(onOpen: () -> Unit): Flow<ServerEvent> = flow {
    ensureCollector()
    val subscriber = Channel<ServerEvent>(256)
    synchronized(subscriberLock) {
      val failure = terminalFailure
      if (closed || transport.isClosed) subscriber.close(failure ?: IOException("Codex 连接已关闭"))
      else subscribers.add(subscriber)
    }
    try {
      // Only report a live stream after an actual business round-trip, not merely constructing a lazy carrier.
      health()
      if (isClosed) throw IOException("Codex 连接已关闭")
      onOpen()
      emitAll(subscriber.receiveAsFlow())
    } finally {
      synchronized(subscriberLock) { subscribers.remove(subscriber) }
      subscriber.cancel()
    }
  }

  /** Translate authoritative native history without mixing different turns or treating errors as empty history. */
  private fun nativeTurns(turns: JSONArray): List<Message> {
    val messages = mutableListOf<Message>()
    for (turn in objects(turns, "turns")) {
      val turnId = turn.str("id")
      val timestamp = codexTime(turn.optLong("createdAt"))
      if (turn.str("status") == "inProgress" && turnId.isNotBlank()) {
        // The containing thread is not available here; its active id is learned from live events.
      }
      for (item in turn.arr("items").objects()) {
        val id = item.str("id").ifBlank { "$turnId:${messages.size}" }
        when (item.str("type")) {
          "userMessage" -> {
            val parts = item.arr("content").objects().mapIndexedNotNull { index, content ->
              when (content.str("type")) {
                "text" -> MessagePart("$id:$index", "text", text = content.str("text"))
                "image" -> MessagePart("$id:$index", "file", path = content.str("url"), mime = "image/*")
                else -> null
              }
            }
            messages += Message(id, "user", timestamp, parts, turnId = turnId)
          }
          "agentMessage" -> messages += Message(id, "assistant", timestamp,
            listOf(MessagePart("$id:text", "text", text = item.str("text"))), turnId = turnId,
            completedAt = if (turn.str("status") == "inProgress") null else timestamp.takeIf { it > 0 })
          "reasoning" -> {
            val text = item.arr("summary").strings().joinToString("\n")
            if (text.isNotBlank()) messages += Message(id, "assistant", timestamp,
              listOf(MessagePart("$id:reasoning", "reasoning", text = text)), turnId = turnId)
          }
          "commandExecution", "fileChange", "mcpToolCall", "collabAgentToolCall", "webSearch" -> {
            val status = when (item.str("status")) { "completed" -> "completed"; "failed", "declined" -> "error"; else -> "running" }
            val part = MessagePart(id, "tool", tool = item.str("type"), title = item.str("command").ifBlank { item.str("type") },
              status = status, input = item.opt("arguments")?.toString().orEmpty(),
              output = item.str("aggregatedOutput").ifBlank { item.opt("result")?.toString().orEmpty() },
              metadata = item.toString())
            messages += Message("$id:message", "assistant", timestamp, listOf(part), turnId = turnId)
          }
        }
      }
    }
    return messages
  }

  /** Remote Host's documented DSH-shaped history is a projection, not a DSH session store. */
  private fun remoteRecords(sessionId: String, records: JSONArray): List<Message> {
    val result = mutableListOf<Message>()
    val tools = mutableMapOf<String, Int>()
    var turn = 0L
    for (record in objects(records, "records")) {
      val event = record.optJSONObject("event") ?: throw ApiException(502, "Codex 历史记录格式错误")
      val data = event.optJSONObject("data") ?: JSONObject()
      val seq = event.optLong("seq")
      val at = event.optLong("time")
      when (event.str("type")) {
        "turn/start" -> turn = data.optLong("turn")
        "user/message" -> dshInputMessage(sessionId, seq, at, turn.takeIf { it > 0 }, data)?.let(result::add)
        "assistant/message" -> {
          val message = data.obj("message")
          val id = message.str("id").ifBlank { "codex:$sessionId:$seq" }
          val projected = JSONObject().put("id", id).put("type", "assistant").put("content", message.arr("content")).toMessage()
          result += projected.copy(created = at, turnId = "turn:$turn", sequence = seq)
        }
        "tool/call" -> {
          val callId = data.str("callId"); if (callId.isBlank()) continue
          val part = MessagePart(callId, "tool", tool = data.str("name"), title = data.str("name"),
            input = data.opt("arguments")?.toString().orEmpty(), status = "running")
          tools[callId] = result.size
          result += Message("$callId:message", "assistant", at, listOf(part), turnId = "turn:$turn", sequence = seq)
        }
        "tool/result" -> {
          val payload = data.obj("message").arr("content").objects().firstOrNull { it.str("type") == "tool-result" } ?: continue
          val callId = payload.str("toolCallId")
          val index = tools[callId] ?: continue
          result[index] = result[index].copy(parts = result[index].parts.map { part ->
            if (part.id != callId) part else part.copy(status = if (payload.optBoolean("isError")) "error" else "completed",
              output = payload.arr("content").contentText())
          }, completedAt = at.takeIf { it > 0 })
        }
      }
    }
    return result
  }

  private fun projectEvent(frame: JSONObject): List<ServerEvent> {
    if (closed) return emptyList()
    val method = frame.str("method")
    val params = frame.optJSONObject("params") ?: JSONObject()
    val sessionId = params.str("threadId").ifBlank { params.optJSONObject("thread")?.str("id").orEmpty() }
    val directory = sessions[sessionId]?.directory.orEmpty()
    val now = System.currentTimeMillis()
    fun event(type: String, p: JSONObject = JSONObject()): ServerEvent = ServerEvent(
      id = "codex:${eventSequence.incrementAndGet()}", directory = directory, type = type,
      properties = p.put("sessionID", sessionId), created = now)
    val turnId = params.str("turnId").ifBlank { params.optJSONObject("turn")?.str("id").orEmpty() }
    val itemId = params.str("itemId").ifBlank { params.optJSONObject("item")?.str("id").orEmpty() }
    val itemType = params.optJSONObject("item")?.str("type").orEmpty()
    val assistantId = if (itemType in setOf("commandExecution", "fileChange", "mcpToolCall", "collabAgentToolCall", "webSearch")) "$itemId:message" else itemId
    fun base() = JSONObject().put("assistantMessageID", assistantId.ifBlank { "codex:$sessionId:$turnId" })
    if (frame.has("id") && method == "mcpServer/elicitation/request") {
      val wireId = frame.opt("id")?.takeIf { it is String || it is Int || it is Long } ?: return emptyList()
      val key = "${wireId.javaClass.simpleName}:$wireId"
      val pending = PendingRequest(wireId, sessionId, method, JSONObject(params.toString()))
      if (requests.putIfAbsent(key, pending) != null) return emptyList()
      val form = if (sessionId.isNotBlank()) mcpForm(key, pending) else null
      if (form == null) {
        cancelUnsupportedMcp(key, pending, "此 MCP 请求的 URL 或表单格式暂不支持")
        return listOf(event("session.synthetic", JSONObject().put("description", "此 MCP 请求暂不支持，正在取消")))
      }
      return listOf(event("form.created", JSONObject().put("form", form)))
    }
    if (frame.has("id") && method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval") && sessionId.isNotBlank()) {
      val wireId = frame.opt("id") ?: return emptyList()
      val key = "${wireId.javaClass.simpleName}:$wireId"
      val pending = PendingRequest(wireId, sessionId, method, JSONObject(params.toString()))
      requests[key] = pending
      val perm = permission(key, pending)
      return listOf(event("permission.asked", JSONObject().put("id", key).put("action", perm.action).put("message", perm.detail)
        .put("resources", JSONArray().put(perm.detail)).put("save", JSONArray())))
    }
    if (frame.has("id") && method == "item/tool/requestUserInput" && sessionId.isNotBlank()) {
      val wireId = frame.opt("id") ?: return emptyList()
      val key = "${wireId.javaClass.simpleName}:$wireId"
      val pending = PendingRequest(wireId, sessionId, method, JSONObject(params.toString()))
      requests[key] = pending
      val q = question(key, pending) ?: run {
        rejectUnsupportedRequest(wireId, sessionId, key)
        return listOf(event("session.synthetic", JSONObject().put("description", "该 Codex 提问暂不支持在手机上回答")))
      }
      val fields = JSONArray()
      q.questions.forEach { row ->
        val options = JSONArray()
        row.options.forEach { option -> options.put(JSONObject().put("label", option.label).put("description", option.description).put("value", option.value)) }
        fields.put(JSONObject().put("key", row.field).put("title", row.title).put("type", if (row.options.isEmpty()) "string" else "select")
          .put("custom", row.custom).put("options", options).put("required", true))
      }
      return listOf(event("form.created", JSONObject().put("form", JSONObject().put("id", key).put("sessionID", sessionId).put("fields", fields))))
    }
    if (frame.has("id") && method.isNotBlank()) {
      val wireId = frame.opt("id")
      if (wireId is String || wireId is Int || wireId is Long) rejectUnsupportedRequest(wireId, sessionId)
      return listOf(event("session.synthetic", JSONObject().put("description", "暂不支持 Codex 请求 ${method.take(120)}，请在开发机处理或停止任务")))
    }
    return when (method) {
      "turn/started" -> {
        if (sessionId.isNotBlank() && turnId.isNotBlank()) synchronized(stateGate) {
          if (turnKey(sessionId, turnId) !in completedTurns) { active[sessionId] = turnId; busy.add(sessionId); eventBusy.add(sessionId) }
        }
        listOf(event("session.execution.started"), event("session.status", JSONObject().put("status", JSONObject().put("type", "busy"))))
      }
      "turn/completed" -> {
        val applies = synchronized(stateGate) {
          if (sessionId.isNotBlank() && turnId.isNotBlank()) {
            completedTurns.add(turnKey(sessionId, turnId))
            while (completedTurns.size > 512) completedTurns.remove(completedTurns.first())
          }
          val current = active[sessionId]
          if (current == turnId && turnId.isNotBlank()) {
            active.remove(sessionId); busy.remove(sessionId); eventBusy.remove(sessionId); true
          } else current == null && sessionId !in eventBusy
        }
        if (!applies) return listOf(event("session.transcript.invalidate"))
        val status = params.obj("turn").str("status")
        val type = when (status) { "completed" -> "session.execution.succeeded"; "failed" -> "session.execution.failed"; "interrupted" -> "session.execution.interrupted"; else -> null }
        if (type == null) listOf(event("session.transcript.invalidate"))
        else {
          releaseFinishedTask(sessionId)
          listOf(event(type), event("session.status", JSONObject().put("status", JSONObject().put("type", "idle"))))
        }
      }
      "thread/closed" -> { loaded.remove(sessionId); emptyList() }
      "thread/deleted" -> {
        if (sessionId.isBlank()) emptyList() else {
          forgetDeletedThread(sessionId)
          listOf(event("session.deleted"))
        }
      }
      "thread/archived", "thread/unarchived" -> {
        val archived = method == "thread/archived"
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(archived = archived) }
        listOf(event(if (archived) "session.archived" else "session.unarchived"))
      }
      "thread/status/changed" -> {
        when (params.obj("status").str("type")) {
          "idle", "notLoaded" -> {
            synchronized(stateGate) { active.remove(sessionId); busy.remove(sessionId); eventBusy.remove(sessionId) }
            releaseFinishedTask(sessionId)
            val expired = requests.entries.filter { it.value.sessionId == sessionId }
            expired.forEach { requests.remove(it.key, it.value) }
            expired.map { entry -> event(if (isQuestionMethod(entry.value.method)) "form.cancelled" else "permission.replied", JSONObject().put("id", entry.key).put("requestID", entry.key)) } +
              event("session.status", JSONObject().put("status", JSONObject().put("type", "idle")))
          }
          "active" -> { if (sessionId.isNotBlank()) synchronized(stateGate) { busy.add(sessionId); eventBusy.add(sessionId) }; listOf(event("session.status", JSONObject().put("status", JSONObject().put("type", "busy")))) }
          "systemError" -> listOf(event("session.error", JSONObject().put("error", "Codex 线程状态异常，请重新连接核对")))
          else -> emptyList()
        }
      }
      "item/started" -> {
        val item = params.obj("item")
        when (item.str("type")) {
          "agentMessage" -> listOf(event("session.step.started", base().put("started", now)), event("session.text.started", base()))
          "reasoning" -> listOf(event("session.step.started", base().put("started", now)))
          "commandExecution", "fileChange", "mcpToolCall", "collabAgentToolCall", "webSearch" -> {
            val itemId = item.str("id")
            listOf(event("session.step.started", base().put("started", now)), event("session.tool.input.started", base().put("id", itemId).put("name", item.str("type"))),
              event("session.tool.called", base().put("id", itemId).put("input", item)))
          }
          else -> emptyList()
        }
      }
      "item/agentMessage/delta" -> listOf(event("session.text.delta", base().put("delta", params.str("delta"))))
      "item/reasoning/summaryTextDelta" -> {
        val key = "$sessionId:$turnId:${params.str("itemId")}:reasoning"
        val output = mutableListOf<ServerEvent>()
        if (startedText.add(key)) output += event("session.reasoning.started", base())
        output += event("session.reasoning.delta", base().put("delta", params.str("delta")))
        output
      }
      "item/completed" -> {
        val item = params.obj("item")
        when (item.str("type")) {
          "agentMessage" -> listOf(event("session.text.ended", base().put("text", item.str("text"))), event("session.step.ended", base()))
          "commandExecution", "fileChange", "mcpToolCall", "collabAgentToolCall", "webSearch" -> {
            val failed = item.str("status") in setOf("failed", "declined")
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", item.str("aggregatedOutput").ifBlank { item.opt("result")?.toString().orEmpty() }))
            listOf(event(if (failed) "session.tool.failed" else "session.tool.success", base().put("id", item.str("id")).put("content", content)))
          }
          "userMessage" -> listOf(event("session.transcript.invalidate"))
          else -> emptyList()
        }
      }
      "serverRequest/resolved" -> {
        val resolved = params.opt("requestId")
        val matches = requests.entries.filter { it.value.wireId == resolved }
        matches.forEach { requests.remove(it.key, it.value) }
        matches.map { entry ->
          event(if (isQuestionMethod(entry.value.method)) "form.replied" else "permission.replied", JSONObject().put("requestID", entry.key).put("id", entry.key))
        }
      }
      "lagoon/streamClosed" -> {
        // No replay: the next view must fetch a fresh baseline and discover live state again.
        active.remove(sessionId); busy.add(sessionId); loaded.remove(sessionId)
        requests.entries.removeAll { it.value.sessionId == sessionId }
        listOf(event("session.transcript.invalidate"), event("server.connection", JSONObject().put("connected", false)))
      }
      else -> emptyList()
    }
  }
}
