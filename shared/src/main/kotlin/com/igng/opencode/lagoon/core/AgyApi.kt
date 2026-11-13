package com.igng.opencode.lagoon.core

import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Google Antigravity ACP projection. Remote carriers translate their restricted Host methods. */
class AgyApi(private val transport: AgyTransport) : HarnessClient {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val started = AtomicBoolean(false)
  private val sequence = AtomicLong()
  private val sessions = ConcurrentHashMap<String, Session>()
  private val sessionDirectories = ConcurrentHashMap<String, List<String>>()
  private val loaded = ConcurrentHashMap.newKeySet<String>()
  private val active = ConcurrentHashMap.newKeySet<String>()
  private val turnCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
  private val admissionLocks = ConcurrentHashMap<String, Mutex>()
  private val sessionLocks = ConcurrentHashMap<String, Mutex>()
  private val config = ConcurrentHashMap<String, JSONArray>()
  private val requests = ConcurrentHashMap<String, PendingPermission>()
  private val questions = ConcurrentHashMap<String, PendingQuestion>()
  private val stateLock = Any()
  private val histories = mutableMapOf<String, MutableList<Message>>()
  private val replaying = mutableSetOf<String>()
  private data class ReplayBuffer(val frames: MutableList<JSONObject> = mutableListOf(), var chars: Long = 0)
  private val liveDuringReplay = mutableMapOf<String, ReplayBuffer>()
  private val lastKind = mutableMapOf<String, String>()
  private data class LocalEcho(val id: String, var text: String?, val images: MutableList<String>)
  private val localEchoes = mutableMapOf<String, MutableList<LocalEcho>>()
  private val subscribers = mutableSetOf<Channel<ServerEvent>>()
  @Volatile private var closed = false
  private val terminalApi = lazy { DshTerminalApi(
    call = { endpoint, args -> transport.terminalCall(endpoint, args) },
    open = { endpoint, args, onItem, onClosed -> transport.terminalOpen(endpoint, args, onItem, onClosed) },
    ownerClosed = { isClosed }
  ) }
  private data class PendingPermission(val wireId: Any, val sessionId: String, val params: JSONObject)
  private data class PendingQuestion(val wireId: Any, val sessionId: String, val form: JSONObject)

  override val isClosed: Boolean get() = closed || transport.isClosed
  override fun close() {
    if (closed) return
    closed = true
    requests.clear(); questions.clear(); active.clear(); loaded.clear()
    if (terminalApi.isInitialized()) terminalApi.value.close()
    scope.cancel(); transport.close()
    synchronized(stateLock) { localEchoes.clear(); subscribers.forEach { it.close() }; subscribers.clear() }
  }
  private fun startCollector() {
    if (!started.compareAndSet(false, true)) return
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try { transport.events().collect(::handleFrame) }
      catch (cancel: CancellationException) { throw cancel }
      catch (_: Exception) { /* terminate below; callers must re-read after reconnect */ }
      finally {
        if (!closed) {
          closed = true; active.clear(); requests.clear(); questions.clear(); loaded.clear()
          if (terminalApi.isInitialized()) terminalApi.value.close()
          transport.close()
          synchronized(stateLock) {
            localEchoes.clear()
            subscribers.forEach { it.close(IOException("AGY 连接已断开，请重新连接并核对会话")) }
            subscribers.clear()
          }
        }
      }
    }
  }
  private suspend fun rpc(method: String, params: JSONObject): JSONObject {
    startCollector()
    return try { transport.call(method, params) }
    catch (error: ApiException) {
      if (error.status == -32000 && error.message.orEmpty().contains("auth", ignoreCase = true))
        throw ApiException(401, "开发机上的 Antigravity 尚未登录，请在开发机运行 agy acp --login")
      throw error
    }
  }
  private fun event(sessionId: String, type: String, payload: JSONObject = JSONObject()) {
    val item = ServerEvent("agy:${sequence.incrementAndGet()}", sessions[sessionId]?.directory.orEmpty(), type,
      payload.put("sessionID", sessionId), System.currentTimeMillis())
    synchronized(stateLock) {
      val failed = subscribers.filter { !it.trySend(item).isSuccess }
      failed.forEach { it.close(IOException("AGY 事件流过载，请重新读取会话")); subscribers.remove(it) }
    }
  }
  override fun capabilities() = ApiCapabilities(deleteSession = false, fileBrowser = transport.filesAvailable,
    fileSearch = false, fileReferences = !transport.remote, terminal = transport.terminalAvailable)
  override suspend fun discoverCapabilities(): ApiCapabilities { transport.discoverCapabilities(); return capabilities() }
  override suspend fun health(): String {
    startCollector()
    try { transport.health() }
    catch (error: ApiException) {
      if (error.status == -32000 && error.message.orEmpty().contains("auth", ignoreCase = true))
        throw ApiException(401, "开发机上的 Antigravity 尚未登录，请在开发机运行 agy acp --login")
      throw error
    }
    return "Antigravity ACP"
  }
  private fun objects(value: JSONObject, key: String): List<JSONObject> {
    val array = value.optJSONArray(key) ?: throw ApiException(502, "AGY 响应缺少 $key")
    if (array.length() > 10_000) throw ApiException(502, "AGY 响应超过上限")
    return (0 until array.length()).map { array.optJSONObject(it) ?: throw ApiException(502, "AGY 响应格式错误") }
  }
  private fun validPath(value: String) = value.startsWith('/') || Regex("^[A-Za-z]:[\\\\/].+").matches(value) || value.startsWith("\\\\")
  private fun validSessionId(value: String) = value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
  private fun imageData(mime: String, data: String): String? {
    if (mime !in setOf("image/png", "image/jpeg", "image/webp", "image/gif") || data.isEmpty() ||
      data.length > ((8 * 1024 * 1024 + 2) / 3) * 4 || data.length % 4 != 0 ||
      !data.matches(Regex("^[A-Za-z0-9+/]*={0,2}$"))) return null
    val tail = data.takeLast(4)
    if (runCatching { java.util.Base64.getEncoder().encodeToString(java.util.Base64.getDecoder().decode(tail)) }.getOrNull() != tail) return null
    return "data:$mime;base64,$data"
  }
  private fun timestamp(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
  private fun parseSession(value: JSONObject): Session {
    val id = value.str("sessionId").ifBlank { value.str("id") }
    val cwd = value.str("cwd").ifBlank { value.str("path") }
    if (!validSessionId(id) || !validPath(cwd) || cwd.length > 4096 || cwd.any(Char::isISOControl))
      throw ApiException(502, "AGY 会话缺少有效身份或工作目录")
    val old = sessions[id]
    return Session(id, cwd, value.str("title").ifBlank { old?.title ?: "新会话" },
      timestamp(value.str("updatedAt")).takeIf { it > 0 } ?: old?.updated ?: 0,
      projectId = "agy:cwd:$cwd", created = old?.created ?: 0)
  }
  override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int): ApiPage<Session> {
    val params = JSONObject()
    if (!directory.isNullOrBlank()) params.put("cwd", directory)
    if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
    val response = rpc("session/list", params)
    // Some AGY versions return a global page even when cwd was supplied. Preserve
    // the server cursor and check every Host-reported workspace, not only its first.
    if (!directory.isNullOrBlank() && response.opt("_lagoonWorkspaceLookupComplete") == false)
      throw ApiException(502, "无法确认 AGY 会话的全部工作目录，请检查开发机的 Python 3 与 AGY 目录数据库")
    val rows = objects(response, "sessions").mapNotNull { value ->
      val parsed = parseSession(value)
      val raw = value.optJSONArray("_lagoonWorkspaces")
      val locations = if (raw == null) listOf(parsed.directory) else {
        if (raw.length() > 64) throw ApiException(502, "AGY 会话工作目录超过上限")
        (0 until raw.length()).map { index ->
          (raw.opt(index) as? String)?.takeIf { validPath(it) && it.length <= 4096 && it.none(Char::isISOControl) }
            ?: throw ApiException(502, "AGY 会话工作目录无效")
        }.distinct().ifEmpty { listOf(parsed.directory) }
      }
      sessionDirectories[parsed.id] = locations
      val selected = if (directory.isNullOrBlank()) {
        sessions[parsed.id]?.directory?.takeIf { previous -> locations.any { normalizedDirectory(it) == normalizedDirectory(previous) } }
          ?: parsed.directory
      } else locations.firstOrNull { normalizedDirectory(it) == normalizedDirectory(directory) } ?: return@mapNotNull null
      parsed.copy(directory = selected, projectId = "agy:cwd:$selected")
    }
    rows.forEach { sessions[it.id] = it }
    val next = response.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
    return ApiPage(rows, next)
  }
  override suspend fun projects(): List<Project> {
    val dirs = linkedMapOf<String, Project>()
    var cursor: String? = null
    val seen = mutableSetOf<String>()
    do {
      val page = rootSessionsPage(null, cursor, 100)
      page.items.forEach { session -> (sessionDirectories[session.id] ?: listOf(session.directory)).forEach { directory ->
        dirs.putIfAbsent(directory, Project("agy:cwd:$directory", directory,
          directory.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { directory }))
      } }
      cursor = page.next
      if (cursor != null && !seen.add(cursor!!)) throw ApiException(502, "AGY 会话分页游标重复")
      if (seen.size > 100) throw ApiException(502, "AGY 会话目录分页超过上限")
    } while (cursor != null)
    return dirs.values.toList()
  }
  override suspend fun projectAt(directory: String): Project? {
    if (!validPath(directory) || directory.any(Char::isISOControl)) throw ApiException(400, "请选择有效的绝对工作目录")
    val canonical = transport.verifyDirectory(directory)
    if (!validPath(canonical)) throw ApiException(502, "AGY 未确认有效工作目录")
    return Project("agy:cwd:$canonical", canonical, canonical.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { canonical })
  }
  override suspend fun session(id: String): Session {
    sessions[id]?.let { return it }
    var cursor: String? = null
    val seen = mutableSetOf<String>()
    do {
      val page = rootSessionsPage(null, cursor, 100)
      page.items.firstOrNull { it.id == id }?.let { return it }
      cursor = page.next
      if (cursor != null && !seen.add(cursor!!)) throw ApiException(502, "AGY 会话分页游标重复")
    } while (cursor != null && seen.size < 100)
    throw ApiException(404, "AGY 会话不存在或不在当前账户中")
  }
  override suspend fun createSession(directory: String, title: String, workspaceId: String?): Session {
    val confirmed = projectAt(directory)?.directory ?: throw ApiException(400, "工作目录不可用")
    val result = rpc("session/new", JSONObject().put("cwd", confirmed).put("mcpServers", JSONArray()))
    val id = result.str("sessionId").takeIf(::validSessionId) ?: throw ApiException(502, "AGY 未确认新会话")
    val created = System.currentTimeMillis()
    val session = Session(id, confirmed, title.ifBlank { "新会话" }, created, projectId = "agy:cwd:$confirmed", created = created)
    sessions[id] = session; loaded.add(id)
    result.optJSONArray("configOptions")?.let { config[id] = it }
    transport.subscribe(id)
    return session
  }
  private suspend fun load(id: String) {
    val lock = sessionLocks.computeIfAbsent(id) { Mutex() }
    lock.withLock {
      if (id in loaded || id in active) return
      val session = session(id)
      synchronized(stateLock) { replaying.add(id); liveDuringReplay[id] = ReplayBuffer(); histories[id] = mutableListOf(); lastKind.remove(id) }
      try {
        transport.subscribe(id)
        val response = rpc("session/load", JSONObject().put("sessionId", id).put("cwd", session.directory).put("mcpServers", JSONArray()))
        transport.syncEvents()
        response.optJSONArray("configOptions")?.let { config[id] = it }
        loaded.add(id)
        synchronized(stateLock) {
          replaying.remove(id); lastKind.remove(id)
          liveDuringReplay.remove(id)?.frames.orEmpty().forEach(::handleFrame)
        }
      } finally { synchronized(stateLock) { replaying.remove(id); liveDuringReplay.remove(id); lastKind.remove(id) } }
    }
  }
  override suspend fun messagesPage(id: String, cursor: String?, size: Int): ApiPage<Message> {
    load(id)
    val all = synchronized(stateLock) { histories[id]?.toList().orEmpty() }
    val end = cursor?.toIntOrNull()?.coerceIn(0, all.size) ?: all.size
    val start = (end - size.coerceIn(1, 200)).coerceAtLeast(0)
    return ApiPage(all.subList(start, end), start.takeIf { it > 0 }?.toString())
  }
  override suspend fun viewSession(session: Session, idle: Long) = Unit
  override suspend fun inbox(id: String) = emptyList<Message>()
  // ACP has no global activity inventory. Locally initiated turns are tracked above,
  // but presenting that subset as the server's complete set would falsely settle
  // work started by another client or a previous connection.
  override suspend fun activeSessions(): Set<String> = throw ApiException(501, "AGY 未提供完整的活动会话清单")
  override suspend fun shells(directory: String): List<ShellJob> = unsupported()
  private fun terminalScope(scope: TerminalScope): TerminalScope.Session {
    val target = scope as? TerminalScope.Session ?: throw ApiException(400, "AGY 终端必须指定原会话")
    require(sessions[target.sessionId]?.directory == target.directory) { "AGY 终端不属于当前会话" }
    return target
  }
  override suspend fun terminals(scope: TerminalScope): List<NativeTerminal> = terminalApi.value.terminals(terminalScope(scope))
  override suspend fun createTerminal(scope: TerminalScope): NativeTerminal = terminalApi.value.createTerminal(terminalScope(scope))
  override suspend fun attachTerminal(scope: TerminalScope, id: String): TerminalAttachment = terminalApi.value.attachTerminal(terminalScope(scope), id)
  override suspend fun closeTerminal(scope: TerminalScope, id: String) = terminalApi.value.closeTerminal(terminalScope(scope), id)
  override suspend fun renameTerminal(scope: TerminalScope, id: String, title: String): NativeTerminal {
    val owner = terminalScope(scope)
    terminalApi.value.renameTerminal(owner, id, title)
    return terminalApi.value.terminals(owner).singleOrNull { it.id == id }
      ?: throw ApiException(409, "终端改名已返回，但当前清单无法确认；请重新读取")
  }
  private fun unsupported(): Nothing = throw ApiException(501, "Antigravity 当前连接未提供此功能")
  override suspend fun renameSession(session: Session, title: String): Unit = unsupported()
  override suspend fun setArchived(session: Session, archived: Boolean, now: Long): Unit = unsupported()
  override suspend fun deleteSession(session: Session): Unit = unsupported()
  override suspend fun forkSession(session: Session): Session = unsupported()
  override suspend fun compact(session: Session): Unit = unsupported()
  override suspend fun revert(session: Session, messageId: String): Unit = unsupported()
  override suspend fun unrevert(session: Session): Unit = unsupported()
  override suspend fun abort(session: Session) {
    if (session.id !in active) throw ApiException(409, "没有已确认运行的 AGY 轮次")
    transport.notify("session/cancel", JSONObject().put("sessionId", session.id))
    if (!transport.remote) {
      requests.entries.filter { it.value.sessionId == session.id }.forEach { (id, pending) ->
        if (requests.remove(id, pending)) runCatching { transport.respond(pending.wireId, JSONObject().put("outcome", JSONObject().put("outcome", "cancelled"))) }
      }
      questions.entries.filter { it.value.sessionId == session.id }.forEach { (id, pending) ->
        if (questions.remove(id, pending)) runCatching { transport.respond(pending.wireId, JSONObject().put("action", "cancel")) }
      }
    }
  }
  override suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?, references: List<FileReference>,
    inline: List<InlineFile>, id: String, delivery: Delivery): Unit = admissionLocks.computeIfAbsent(session.id) { Mutex() }.withLock {
    if (references.isNotEmpty() && transport.remote) throw ApiException(501, "Remote AGY 暂不支持文件引用")
    val running = session.id in active
    if (running && (transport.remote || delivery == Delivery.QUEUE)) throw ApiException(409, "此 AGY 连接不支持当前轮次的排队或插话")
    load(session.id)
    if (model != null) selectModel(session.id, model)
    val content = JSONArray()
    if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
    references.forEach { reference ->
      val relative = reference.path.replace('\\', '/')
      require(relative.isNotBlank() && !relative.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(relative) &&
        relative.split('/').none { it == ".." || it.isBlank() } && relative.none(Char::isISOControl)) { "文件引用必须位于工作区内" }
      var root = session.directory.replace('\\', '/').trimEnd('/')
      if (Regex("^[A-Za-z]:/").containsMatchIn(root)) root = "/$root"
      require(root.startsWith('/')) { "工作目录无效" }
      val uri = java.net.URI("file", "", "$root/$relative", null).toASCIIString()
      content.put(JSONObject().put("type", "resource_link").put("uri", uri).put("name", relative.substringAfterLast('/')))
    }
    if (inline.size > 4) throw ApiException(400, "AGY 每条消息最多支持 4 张图片")
    val parts = mutableListOf<MessagePart>()
    if (text.isNotBlank()) parts.add(MessagePart("$id:text", "text", text = text))
    for ((index, file) in inline.withIndex()) {
      val match = Regex("^data:(image/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/]+={0,2})$").matchEntire(file.uri)
        ?: throw ApiException(400, "AGY 只接受 PNG、JPEG、WebP 或 GIF 图片")
      if (imageData(match.groupValues[1], match.groupValues[2]) == null) throw ApiException(400, "AGY 图片无效或超过 8 MiB")
      if (!transport.remote && transport.initialization?.optJSONObject("agentCapabilities")?.optJSONObject("promptCapabilities")?.opt("image") == false)
        throw ApiException(501, "当前 AGY 不支持图片输入")
      content.put(JSONObject().put("type", "image").put("mimeType", match.groupValues[1]).put("data", match.groupValues[2]))
      parts.add(MessagePart("$id:image:$index", "file", path = file.uri, title = file.name, mime = match.groupValues[1]))
    }
    require(content.length() > 0) { "消息不能为空" }
    synchronized(stateLock) {
      val rows = histories.getOrPut(session.id) { mutableListOf() }
      rows.add(Message(id, "user", System.currentTimeMillis(), parts, sourceKind = "user"))
      localEchoes.getOrPut(session.id) { mutableListOf() }.add(LocalEcho(id, text.takeIf(String::isNotBlank), inline.map { it.uri }.toMutableList()))
      lastKind.remove(session.id)
    }
    active.add(session.id)
    turnCounts.computeIfAbsent(session.id) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    if (!running) {
      event(session.id, "session.execution.started")
      event(session.id, "session.status", JSONObject().put("status", JSONObject().put("type", "busy")))
    }
    val result = try { transport.beginPrompt(JSONObject().put("sessionId", session.id).put("prompt", content)) }
    catch (error: Throwable) {
      if ((turnCounts[session.id]?.decrementAndGet() ?: 0) <= 0) { turnCounts.remove(session.id); active.remove(session.id) }
      synchronized(stateLock) {
        localEchoes[session.id]?.removeAll { it.id == id }
        if (error is ApiException) histories[session.id]?.removeAll { it.id == id }
      }
      event(session.id, "session.error", JSONObject().put("error", "AGY 消息未确认，请核对会话后重试"))
      throw error
    }
    scope.launch {
      try {
        val response = result.await()
        if (transport.promptCompletesOnResponse) {
          transport.syncEvents()
          val stop = response.str("stopReason")
          if ((turnCounts[session.id]?.decrementAndGet() ?: 0) <= 0) {
            turnCounts.remove(session.id); active.remove(session.id)
            requests.entries.removeAll { it.value.sessionId == session.id }
            questions.entries.removeAll { it.value.sessionId == session.id }
            synchronized(stateLock) { localEchoes.remove(session.id) }
            event(session.id, when (stop) { "end_turn" -> "session.execution.succeeded"; "cancelled" -> "session.execution.interrupted"; else -> "session.execution.failed" })
            event(session.id, "session.status", JSONObject().put("status", JSONObject().put("type", "idle")))
          }
        }
      } catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) {
        if ((turnCounts[session.id]?.decrementAndGet() ?: 0) <= 0) { turnCounts.remove(session.id); active.remove(session.id) }
        synchronized(stateLock) { localEchoes.remove(session.id) }
        event(session.id, "session.error", JSONObject().put("error", error.message ?: "AGY 请求结果未知，请核对会话"))
        if (error is ApiException) event(session.id, "session.execution.failed")
        else event(session.id, "server.connection", JSONObject().put("connected", false))
      }
    }
    Unit
  }
  override suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?,
    references: List<FileReference>, inline: List<InlineFile>, delivery: Delivery): Unit = unsupported()
  override suspend fun agents(directory: String) = emptyList<AgentChoice>()
  override suspend fun commands(directory: String) = emptyList<CommandChoice>()
  private fun options(value: JSONObject): List<JSONObject> {
    val array = value.optJSONArray("options") ?: return emptyList()
    return (0 until array.length()).flatMap { index ->
      val row = array.optJSONObject(index) ?: return@flatMap emptyList()
      val group = row.optJSONArray("options")
      if (group == null) listOf(row) else (0 until group.length()).mapNotNull { group.optJSONObject(it) }
    }
  }
  private fun configOption(sessionId: String, category: String): JSONObject? {
    val values = config[sessionId] ?: return null
    return (0 until values.length()).mapNotNull { values.optJSONObject(it) }.firstOrNull {
      it.str("type") == "select" && (it.str("id") == category || it.str("category") == category ||
        category == "effort" && it.str("id") in setOf("thinking_level", "thought_level", "reasoning_effort"))
    }
  }
  override suspend fun modelCatalog(directory: String): List<ModelInfo> {
    val id = sessions.values.firstOrNull { it.directory == directory && config.containsKey(it.id) }?.id ?: return emptyList()
    return catalogFor(id)
  }
  override suspend fun modelCatalogForSession(directory: String, session: Session?): List<ModelInfo> {
    if (session == null || session.directory != directory) return emptyList()
    load(session.id)
    if (transport.remote && !config.containsKey(session.id)) {
      rpc("lagoon/modelConfig", JSONObject().put("sessionId", session.id)).optJSONArray("configOptions")?.let { config[session.id] = it }
    }
    return catalogFor(session.id)
  }
  private fun catalogFor(id: String): List<ModelInfo> {
    val model = configOption(id, "model") ?: return emptyList()
    val effort = configOption(id, "effort")
    val variants = effort?.let(::options).orEmpty().mapNotNull { row ->
      row.str("value").takeIf(String::isNotBlank)?.let { ModelVariant(it, row.str("name").ifBlank { it }) }
    }
    return options(model).mapNotNull { row ->
      val value = row.str("value").takeIf(String::isNotBlank) ?: return@mapNotNull null
      ModelInfo(ModelChoice("antigravity", value, row.str("name").ifBlank { value }),
        variants = if (value == model.str("currentValue")) variants else emptyList())
    }
  }
  private suspend fun selectModel(sessionId: String, selection: ModelChoice) {
    require(selection.providerId == "antigravity") { "模型不属于 Antigravity" }
    var option = configOption(sessionId, "model") ?: throw ApiException(501, "AGY 未声明模型选择")
    if (options(option).none { it.str("value") == selection.modelId }) throw ApiException(400, "AGY 模型不可用，请刷新目录")
    suspend fun set(optionId: String, value: String) {
      val response = rpc("session/set_config_option", JSONObject().put("sessionId", sessionId).put("configId", optionId).put("value", value))
      val values = response.optJSONArray("configOptions") ?: throw ApiException(502, "AGY 未确认模型配置")
      config[sessionId] = values
      val confirmed = (0 until values.length()).mapNotNull { values.optJSONObject(it) }.singleOrNull { it.str("id") == optionId }
      if (confirmed?.str("currentValue") != value) throw ApiException(502, "AGY 未确认所选模型配置")
    }
    if (option.str("currentValue") != selection.modelId) {
      set(option.str("id"), selection.modelId)
      option = configOption(sessionId, "model") ?: throw ApiException(502, "AGY 模型目录已变化")
    }
    selection.variant?.let { variant ->
      val effort = configOption(sessionId, "effort") ?: throw ApiException(501, "AGY 未声明思考档位")
      if (options(effort).none { it.str("value") == variant }) throw ApiException(400, "所选思考档位不可用")
      if (effort.str("currentValue") != variant) set(effort.str("id"), variant)
    }
  }
  override suspend fun sessionPermissions(session: Session) = pendingPermissions(session.directory).filter { it.sessionId == session.id }
  override suspend fun pendingPermissions(directory: String): List<PermissionRequest> = requests.entries.mapNotNull { (id, pending) ->
    val cwd = sessions[pending.sessionId]?.directory.orEmpty()
    if (directory.isNotBlank() && directory != cwd) return@mapNotNull null
    val tool = pending.params.optJSONObject("toolCall") ?: JSONObject()
    PermissionRequest(id, pending.sessionId, cwd, tool.str("title").ifBlank { "AGY 请求权限" }, tool.toString(), manualOnly = true)
  }
  override suspend fun forms(directory: String): List<QuestionRequest> = questions.values.mapNotNull { pending ->
    val cwd = sessions[pending.sessionId]?.directory.orEmpty()
    if (directory.isNotBlank() && directory != cwd) null else pending.form.toForm(cwd)
  }
  private fun elicitationForm(id: String, sessionId: String, params: JSONObject): JSONObject? {
    if (params.str("mode") != "form") return null
    val schema = params.optJSONObject("requestedSchema") ?: return null
    if (schema.str("type") != "object" || schema.keys().asSequence().any { it !in setOf("type", "properties", "required", "additionalProperties", "$" + "schema") }) return null
    val properties = schema.optJSONObject("properties") ?: return null
    if (properties.length() !in 1..32 || schema.has("additionalProperties") && schema.opt("additionalProperties") != false ||
      schema.has("required") && schema.optJSONArray("required") == null) return null
    val requiredArray = schema.optJSONArray("required") ?: JSONArray()
    val required = (0 until requiredArray.length()).map { requiredArray.opt(it) as? String ?: return null }
    if (required.distinct().size != required.size || required.any { !properties.has(it) }) return null
    val fields = JSONArray()
    for (key in properties.keys().asSequence()) {
      val property = properties.optJSONObject(key) ?: return null
      if (key.isBlank() || key.length > 256 || key in setOf("__proto__", "prototype", "constructor") || property.str("type") != "string" ||
        property.keys().asSequence().any { it !in setOf("type", "title", "description", "enum", "minLength", "maxLength") }) return null
      if (listOf("title", "description").any { property.has(it) && property.opt(it) !is String } ||
        property.has("enum") && property.optJSONArray("enum") == null) return null
      val field = JSONObject().put("key", key).put("title", property.str("title").ifBlank { key })
        .put("description", listOf(params.str("message"), property.str("description")).filter(String::isNotBlank).joinToString("\n"))
        .put("required", key in required)
      val values = property.optJSONArray("enum")
      if (values != null) {
        if (values.length() !in 1..64) return null
        val choices = JSONArray(); val seen = mutableSetOf<String>()
        for (i in 0 until values.length()) {
          val value = values.opt(i) as? String ?: return null
          if (value.isEmpty() || value.codePointCount(0, value.length) > 32768 || !seen.add(value)) return null
          choices.put(JSONObject().put("label", value).put("value", value))
        }
        field.put("type", "select").put("options", choices)
      } else field.put("type", "string")
      for (bound in listOf("minLength", "maxLength")) if (property.has(bound)) {
        val value = property.opt(bound) as? Number ?: return null
        if (value.toDouble() % 1.0 != 0.0 || value.toLong() !in 0L..32768L) return null
        field.put(bound, value.toInt())
      }
      if (field.optInt("minLength", 0) > field.optInt("maxLength", 32768)) return null
      fields.put(field)
    }
    return JSONObject().put("id", id).put("sessionID", sessionId).put("fields", fields)
  }
  override suspend fun replyPermission(request: PermissionRequest, reply: String) {
    val pending = requests[request.id] ?: throw ApiException(409, "审批请求已失效")
    require(pending.sessionId == request.sessionId) { "审批不属于该会话" }
    val kind = when (reply) { "once" -> "allow_once"; "always" -> "allow_always"; else -> "reject_once" }
    val options = pending.params.optJSONArray("options") ?: throw ApiException(502, "AGY 审批缺少选项")
    val matches = (0 until options.length()).mapNotNull { options.optJSONObject(it) }.filter { it.str("kind") == kind }
    val option = matches.singleOrNull()?.str("optionId")?.takeIf(String::isNotBlank)
      ?: throw ApiException(501, "AGY 未提供此审批选项")
    transport.respond(pending.wireId, JSONObject().put("outcome", JSONObject().put("outcome", "selected").put("optionId", option)))
    requests.remove(request.id, pending)
    event(request.sessionId, "permission.replied", JSONObject().put("id", request.id).put("requestID", request.id))
  }
  override suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    val pending = questions[request.id] ?: throw ApiException(409, "提问已失效")
    val expected = pending.form.toForm(sessions[pending.sessionId]?.directory.orEmpty())
    require(request.sessionId == pending.sessionId && request.questions == expected.questions) { "表单已变化，请刷新" }
    val content = formAnswer(expected, answers)
    pending.form.arr("fields").objects().forEach { field ->
      val value = content.opt(field.str("key")) as? String ?: return@forEach
      val length = value.codePointCount(0, value.length)
      require(length >= field.optInt("minLength", 0) && length <= field.optInt("maxLength", 32768)) { "回答长度无效" }
    }
    transport.respond(pending.wireId, JSONObject().put("action", "accept").put("content", content))
    questions.remove(request.id, pending)
    event(pending.sessionId, "form.replied", JSONObject().put("id", request.id).put("requestID", request.id))
  }
  override suspend fun rejectQuestion(request: QuestionRequest) {
    val pending = questions[request.id] ?: throw ApiException(409, "提问已失效")
    require(pending.sessionId == request.sessionId)
    transport.respond(pending.wireId, JSONObject().put("action", "decline"))
    questions.remove(request.id, pending)
    event(pending.sessionId, "form.cancelled", JSONObject().put("id", request.id).put("requestID", request.id))
  }
  override suspend fun children(session: Session) = emptyList<Session>()
  override suspend fun diff(session: Session): List<FileChange> = unsupported()
  override suspend fun files(directory: String, path: String, session: Session?): List<FileNode> {
    val target = session ?: throw ApiException(409, "先打开一个 AGY 会话")
    require(target.directory == directory) { "文件目录与当前会话不一致" }
    return transport.files(target, path)
  }
  override suspend fun fileContent(directory: String, path: String, session: Session?): FileContent {
    val target = session ?: throw ApiException(409, "先打开一个 AGY 会话")
    require(target.directory == directory) { "文件目录与当前会话不一致" }
    return transport.fileContent(target, path)
  }
  override suspend fun searchFiles(directory: String, query: String, session: Session?): List<String> = unsupported()
  override fun events(onOpen: () -> Unit): Flow<ServerEvent> = flow {
    startCollector()
    val subscriber = Channel<ServerEvent>(256)
    synchronized(stateLock) { if (isClosed) subscriber.close(IOException("AGY 连接已关闭")) else subscribers.add(subscriber) }
    try { health(); onOpen(); emitAll(subscriber.receiveAsFlow()) }
    finally { synchronized(stateLock) { subscribers.remove(subscriber) }; subscriber.cancel() }
  }
  private fun handleFrame(frame: JSONObject) {
    val method = frame.str("method")
    val params = frame.optJSONObject("params") ?: JSONObject()
    val sessionId = params.str("sessionId")
    if (transport.remote && method == "session/update" && !frame.optBoolean("lagoonReplay")) {
      synchronized(stateLock) {
        liveDuringReplay[sessionId]?.let { buffered ->
          val size = frame.toString().length
          if (buffered.frames.size >= 256 || buffered.chars + size > 16L * 1024 * 1024)
            throw IOException("AGY 历史加载期间的实时事件过多，请重新连接")
          buffered.frames.add(frame); buffered.chars += size
          return
        }
      }
    }
    if (method == "session/request_permission" && frame.has("id") && sessionId.isNotBlank()) {
      val wire = frame.opt("id")?.takeIf { it is String || it is Number } ?: return
      val id = "${wire.javaClass.simpleName}:$wire"
      requests[id] = PendingPermission(wire, sessionId, JSONObject(params.toString()))
      val tool = params.optJSONObject("toolCall") ?: JSONObject()
      event(sessionId, "permission.asked", JSONObject().put("id", id).put("action", tool.str("title").ifBlank { "AGY 请求权限" })
        .put("message", tool.toString()).put("manualOnly", true).put("save", JSONArray()))
      return
    }
    if (method == "elicitation/create" && frame.has("id") && sessionId.isNotBlank()) {
      val wire = frame.opt("id")?.takeIf { it is String || it is Number } ?: return
      val id = "${wire.javaClass.simpleName}:$wire"
      val form = elicitationForm(id, sessionId, params)
      if (form == null) { scope.launch { runCatching { transport.respond(wire, JSONObject().put("action", "decline")) } }; return }
      questions[id] = PendingQuestion(wire, sessionId, form)
      event(sessionId, "form.created", JSONObject().put("form", form))
      return
    }
    if (frame.has("id") && method.isNotBlank()) {
      val wire = frame.opt("id")
      if (wire is String || wire is Number) scope.launch { runCatching { transport.respondError(wire, -32601, "Client method unsupported") } }
      return
    }
    if (method != "session/update" || sessionId.isBlank()) return
    val update = params.optJSONObject("update") ?: return
    val kind = update.str("sessionUpdate")
    if (kind == "prompt_completed" || kind == "prompt_failed") {
      active.remove(sessionId); turnCounts.remove(sessionId)
      requests.entries.removeAll { it.value.sessionId == sessionId }
      questions.entries.removeAll { it.value.sessionId == sessionId }
      synchronized(stateLock) { localEchoes.remove(sessionId) }
      val outcome = when { kind == "prompt_failed" -> "session.execution.failed"; update.str("stopReason") == "cancelled" -> "session.execution.interrupted"; update.str("stopReason") == "end_turn" -> "session.execution.succeeded"; else -> "session.execution.failed" }
      event(sessionId, outcome)
      event(sessionId, "session.status", JSONObject().put("status", JSONObject().put("type", "idle")))
      event(sessionId, "session.transcript.invalidate")
      return
    }
    if (kind == "session_info_update") {
      sessions[sessionId]?.let { old -> sessions[sessionId] = old.copy(title = update.str("title").ifBlank { old.title }) }
      event(sessionId, "session.updated")
      return
    }
    if (kind == "config_option_update") update.optJSONArray("configOptions")?.let { config[sessionId] = it }
    val isReplay: Boolean
    synchronized(stateLock) {
      isReplay = sessionId in replaying
      val rows = histories.getOrPut(sessionId) { mutableListOf() }
      when (kind) {
        "user_message_chunk", "agent_message_chunk", "agent_thought_chunk" -> {
          val content = update.optJSONObject("content") ?: JSONObject()
          val text = if (content.str("type") == "text") content.str("text") else ""
          val streamKey = "$kind:${update.str("messageId")}"
          val image = if (content.str("type") == "image") imageData(content.str("mimeType"), content.str("data")) else null
          if (kind == "user_message_chunk" && !isReplay) {
            val pending = localEchoes[sessionId]
            val echo = pending?.firstOrNull { (text.isNotEmpty() && it.text == text) || (image != null && image in it.images) }
            if (echo != null) {
              if (text.isNotEmpty() && echo.text == text) echo.text = null
              if (image != null) echo.images.remove(image)
              if (echo.text == null && echo.images.isEmpty()) pending?.remove(echo)
              if (pending?.isEmpty() == true) localEchoes.remove(sessionId)
              return // the optimistic row already contains this exact echoed input
            }
          }
          if (image != null) {
            val role = if (kind == "user_message_chunk") "user" else "assistant"
            val last = rows.lastOrNull()
            val part = MessagePart("agy:$sessionId:image:${rows.size}:${last?.parts?.size ?: 0}", "file", path = image, mime = content.str("mimeType"))
            if (lastKind[sessionId] == streamKey && last != null && last.role == role) rows[rows.lastIndex] = last.copy(parts = last.parts + part)
            else rows.add(Message("agy:$sessionId:image:${rows.size}", role, 0, listOf(part), sourceKind = if (role == "user") "user" else null))
            lastKind[sessionId] = streamKey
          }
          if (text.isNotEmpty()) {
            val role = if (kind == "user_message_chunk") "user" else "assistant"
            val partType = if (kind == "agent_thought_chunk") "reasoning" else "text"
            val last = rows.lastOrNull()
            if (lastKind[sessionId] == streamKey && last != null && last.parts.size == 1 && last.parts[0].type == partType) {
              rows[rows.lastIndex] = last.copy(parts = listOf(last.parts[0].copy(text = last.parts[0].text + text)))
            } else {
              val base = update.str("messageId").takeIf(String::isNotBlank)?.let { "agy:$sessionId:$it:$partType" } ?: "agy:$sessionId:${rows.size}"
              val id = if (rows.any { it.id == base }) "$base:${rows.size}" else base
              rows.add(Message(id, role, 0, listOf(MessagePart("$id:$partType", partType, text = text)), sourceKind = if (role == "user") "user" else null))
            }
            lastKind[sessionId] = streamKey
          }
        }
        "tool_call", "tool_call_update" -> {
          val callId = update.str("toolCallId")
          if (callId.isNotBlank()) {
            val index = rows.indexOfLast { row -> row.parts.any { it.id == callId } }
            val old = if (index < 0) null else rows[index].parts.firstOrNull { it.id == callId }
            val contents = update.optJSONArray("content")
            val output = contents?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { item ->
              when (item.str("type")) { "content" -> item.optJSONObject("content")?.str("text"); "text" -> item.str("text"); else -> null }
            }.joinToString("\n") }
            // ACP supplies complete before/after texts, not a unified diff. Display them
            // as tool output instead of manufacturing an invalid patch.
            val changes = contents?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) }
              .filter { it.str("type") == "diff" }.joinToString("\n\n") { item ->
                listOf(item.str("path"), "修改前：", item.str("oldText"), "修改后：", item.str("newText")).joinToString("\n")
              } }
            val combinedOutput = listOfNotNull(output?.takeIf(String::isNotBlank), changes?.takeIf(String::isNotBlank)).joinToString("\n\n")
            val part = MessagePart(callId, "tool", tool = update.str("kind").ifBlank { old?.tool.orEmpty() },
              title = update.str("title").ifBlank { old?.title.orEmpty() }, status = update.str("status").ifBlank { old?.status.orEmpty() },
              input = if (update.has("rawInput")) update.opt("rawInput")?.toString().orEmpty() else old?.input.orEmpty(),
              output = combinedOutput.ifBlank { old?.output.orEmpty() }, patch = old?.patch.orEmpty())
            if (index < 0) rows.add(Message("agy:$sessionId:tool:$callId", "assistant", 0, listOf(part)))
            else rows[index] = rows[index].copy(parts = listOf(part))
            lastKind.remove(sessionId)
          }
        }
      }
    }
    if (!isReplay && kind in setOf("user_message_chunk", "agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update"))
      event(sessionId, "session.transcript.invalidate")
  }
}
