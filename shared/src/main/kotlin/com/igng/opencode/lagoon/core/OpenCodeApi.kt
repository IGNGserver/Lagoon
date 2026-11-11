package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.withTimeoutOrNull

data class ServerCredentials(
  val username: String = "opencode",
  val password: String = "",
  val cookie: String = ""
)

data class ApiPage<T>(val items: List<T>, val next: String? = null)

/** One `/api/event` frame: `type`, its `data` as [properties], the event `location` and `created` time. */
data class ServerEvent(val id: String = "", val directory: String, val type: String, val properties: JSONObject, val created: Long = 0)
class ApiException(
  val status: Int,
  message: String,
  val code: String? = null,
  val details: JSONObject? = null
) : IOException(message)

/** How a new prompt is delivered while the session is busy (official `Session.Inbox.Delivery`). */
enum class Delivery(val wire: String) { STEER("steer"), QUEUE("queue") }

/**
 * Client for the OpenCode 2.x server (`anomalyco/opencode` `packages/protocol`). Routes, payloads and
 * response envelopes follow the published contract; operations whose route differs between 2.0.x
 * releases are resolved from the server's own `/openapi.json` (see [ApiCapabilities]).
 */
class OpenCodeApi internal constructor(
  private val profile: ServerProfile,
  private val credentials: ServerCredentials,
  callTimeoutSeconds: Long = 60,
  streamIdleSeconds: Long = SharedHttp.STREAM_IDLE_SECONDS,
  /** Optional carrier for SSH/Remote native services; omitted callers keep the stock OkHttp carrier. */
  transport: NativeHttpTransport?
) : HarnessClient {
  constructor(
    profile: ServerProfile,
    credentials: ServerCredentials,
    callTimeoutSeconds: Long = 60,
    streamIdleSeconds: Long = SharedHttp.STREAM_IDLE_SECONDS
  ) : this(profile, credentials, callTimeoutSeconds, streamIdleSeconds, null)

  constructor(profile: ServerProfile, password: String) : this(profile, ServerCredentials(profile.username, password), 60, SharedHttp.STREAM_IDLE_SECONDS, null)

  private val base: HttpUrl = requireNotNull(profile.url.trimEnd('/').toHttpUrlOrNull()) { "服务器地址无效" }
  private val nativeEndpoint = NativeHttpEndpoint(base.toString())
  private val nativeTransport: NativeHttpTransport = transport ?: NativeOkHttpTransport(nativeEndpoint, callTimeoutSeconds, streamIdleSeconds)
  @Volatile private var closed = false
  private val terminalApiDelegate = lazy { OpenCodeTerminalApi(nativeTransport, ::requestHeaders) { closed } }
  private val terminalApi: OpenCodeTerminalApi get() = terminalApiDelegate.value
  override val isClosed: Boolean get() = closed
  override fun close() {
    if (!closed) {
      closed = true
      if (terminalApiDelegate.isInitialized()) terminalApi.close()
      nativeTransport.close()
    }
  }

  override suspend fun terminals(directory: String): List<NativeTerminal> = terminalApi.terminals(directory)
  override suspend fun createTerminal(directory: String): NativeTerminal = terminalApi.createTerminal(directory)
  override suspend fun resizeTerminal(directory: String, id: String, cols: Int, rows: Int): NativeTerminal =
    terminalApi.resizeTerminal(directory, id, cols, rows)
  override suspend fun renameTerminal(directory: String, id: String, title: String): NativeTerminal =
    terminalApi.renameTerminal(directory, id, title)
  override suspend fun closeTerminal(directory: String, id: String) = terminalApi.closeTerminal(directory, id)
  override suspend fun attachTerminal(directory: String, id: String): TerminalAttachment = terminalApi.attachTerminal(directory, id)
  @Volatile private var version: String? = null
  @Volatile private var discoveredCapabilities: ApiCapabilities? = null
  /** Row-label titles only; the server's project list is never imported into the local registry. */
  @Volatile private var displayProjects: List<Project>? = null
  @Volatile private var displayProjectsAt = 0L
  @Volatile private var displayProjectsFailedAt = 0L
  override fun capabilities(): ApiCapabilities = discoveredCapabilities ?: ApiCapabilities.BASELINE

  /** Reads the instance's own OpenAPI document once; routes that moved between 2.0.x releases are taken from it. */
  override suspend fun discoverCapabilities(): ApiCapabilities {
    discoveredCapabilities?.let { return it }
    val document = try { obj("openapi.json") } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
      catch (error: Exception) { Diagnostics.warn("OpenCodeApi", "无法读取 /openapi.json，按 2.x 最新契约处理", error); null }
    return (if (document == null) ApiCapabilities.BASELINE else ApiCapabilities.fromDocument(document)).also { discoveredCapabilities = it }
  }

  init {
    require(base.scheme == "https" || base.scheme == "http" && profile.allowCleartext) { "HTTP 明文连接未获授权，请在服务器资料中明确开启" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "请使用不含凭据或参数的服务器地址" }
    require(nativeTransport.endpoint.origin == NativeHttpOrigin.from(java.net.URI(base.toString()))) { "原生传输必须使用配置的固定 origin" }
  }

  private fun requestHeaders(accept: String = "application/json"): Map<String, String> = buildMap {
    put("Accept", accept)
    if (credentials.password.isNotEmpty()) {
      put("Authorization", Credentials.basic(credentials.username.ifBlank { profile.username.ifBlank { "opencode" } }, credentials.password))
    }
    if (credentials.cookie.isNotBlank()) put("Cookie", credentials.cookie)
  }

  private fun requestPath(path: String, query: Map<String, String>): String {
    val encoded = path.trimStart('/').split('/').joinToString("/") { it }
    if (query.isEmpty()) return encoded
    return encoded + "?" + query.entries.joinToString("&") { (key, value) ->
      "${java.net.URLEncoder.encode(key, "UTF-8").replace("+", "%20") }=${java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20") }"
    }
  }

  private fun httpErrorMessage(status: Int, body: String): String {
      val serverMessage = runCatching {
        val json = JSONObject(body)
        json.errorMessage().ifBlank { json.obj("data").errorMessage() }
      }.getOrDefault("")
      val translated = serverMessage.takeUnless {
        // An empty-body 500 or raw `{}` must not stringify as "{}" and hide the status translation.
        it == "{}" || it == "[]"
      }.orEmpty()
      return translated.ifBlank {
        when (status) {
          401 -> "用户名或密码错误"
          403 -> "服务器拒绝访问"
          404 -> "OpenCode 未找到请求的资源或接口"
          503 -> "OpenCode 服务正在启动或停止，请稍后重试"
          else -> "服务器返回 HTTP $status"
        }
      }
  }

  private suspend fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): String {
    val normalizedMethod = requireNativeHttpMethod(method)
    val requestBody = if (normalizedMethod == "GET" || normalizedMethod == "DELETE" && body == null) null
      else (body?.toString() ?: "{}").toByteArray(Charsets.UTF_8)
    val headers = requestHeaders().toMutableMap().apply {
      if (requestBody != null) put("Content-Type", "application/json; charset=utf-8")
    }
    val response = nativeTransport.request(
      normalizedMethod,
      requestPath(path, query),
      headers,
      requestBody,
      maxResponseBytes = MAX_RESPONSE_CHARS.toLong()
    )
    val responseBody = response.body.toString(Charsets.UTF_8)
    if (response.statusCode !in 200..299) {
      val error = runCatching { JSONObject(responseBody) }.getOrNull()
      throw ApiException(response.statusCode, httpErrorMessage(response.statusCode, responseBody),
        code = (error?.opt("name") as? String)?.takeIf { it == "WorktreeError" },
        details = error?.optJSONObject("data"))
    }
    return responseBody
  }

  private suspend fun requestBytes(path: String, query: Map<String, String> = emptyMap()): Pair<ByteArray, String> {
    val response = nativeTransport.request(
      "GET",
      requestPath(path, query),
      requestHeaders(),
      maxResponseBytes = MAX_FILE_BYTES
    )
    if (response.statusCode !in 200..299) {
      val body = response.body.toString(Charsets.UTF_8)
      throw ApiException(response.statusCode, httpErrorMessage(response.statusCode, body))
    }
    return response.body.requireNativeHttpResponseBudget(MAX_FILE_BYTES) to response.headers.entries
      .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()
      .ifBlank { "application/octet-stream" }
  }

  // Parsing runs on the IO dispatcher: JSONObject/JSONArray construction of a large transcript is
  // CPU-bound and the controller calls these from its Main-immediate scope (A15).
  private suspend fun obj(path: String, query: Map<String, String> = emptyMap()): JSONObject =
    withContext(Dispatchers.IO) { jsonObject(request("GET", path, query)) }
  private fun dataObject(value: JSONObject): JSONObject = value.optJSONObject("data") ?: value
  private fun dataArray(value: JSONObject): JSONArray = value.optJSONArray("data") ?: JSONArray()

  /**
   * HTTP 200 bodies are parsed here. When the configured address is not an OpenCode API — a web UI
   * returning SPA HTML, a login/proxy page, or an empty body — org.json raises raw errors such as
   * "Value ... of type java.lang.String cannot be converted to JSONObject", which used to leak
   * verbatim into the add-server form. Translate them into a specific, actionable message instead.
   */
  private fun jsonObject(raw: String): JSONObject = try { JSONObject(raw) } catch (error: JSONException) { throw invalidJsonResponse(raw, error) }
  private fun jsonArray(raw: String): JSONArray = try { JSONArray(raw) } catch (error: JSONException) { throw invalidJsonResponse(raw, error) }
  private fun invalidJsonResponse(raw: String, cause: JSONException): IOException {
    val trimmed = raw.trim()
    val reason = when {
      trimmed.isEmpty() -> "服务器返回了空响应，请确认地址指向 OpenCode 服务端口"
      trimmed.startsWith("<") -> "服务器返回的是网页而非 OpenCode 接口数据，请确认填写的是 API 地址（不要使用网页界面地址）"
      else -> "服务器返回的数据不是有效 JSON，可能被代理拦截或该地址不是 OpenCode 服务"
    }
    // cause.message is deliberately not repeated: Android's org.json embeds the whole offending
    // body in the message, which would flood the dialog. The cause stays chained for diagnostics.
    return IOException(reason, cause)
  }

  /** Follows `cursor.next` of a `{data, cursor}` listing; [dropAfterFirst] keys are first-page-only (e.g. `order`). */
  private suspend fun dataObjects(
    path: String,
    query: Map<String, String> = emptyMap(),
    dropAfterFirst: Set<String> = emptySet()
  ): List<JSONObject> = withContext(Dispatchers.IO) {
    val result = mutableListOf<JSONObject>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    // A misbehaving server could hand out a fresh cursor forever; bound both the page count and the
    // total items so one listing cannot exhaust memory or spin indefinitely (A15).
    var pages = 0
    var totalChars = 0L
    do {
      if (pages++ >= MAX_PAGES) throw IOException("列表超过分页上限，请在服务器缩小查询范围")
      val pageQuery = query.toMutableMap().apply {
        if (cursor != null) {
          put("cursor", cursor!!)
          dropAfterFirst.forEach(::remove)
        }
      }
      val raw = request("GET", path, pageQuery)
      totalChars += raw.length
      if (totalChars > MAX_RESPONSE_CHARS * 2L) throw IOException("列表数据过大，未使用不完整结果")
      val page = jsonObject(raw)
      result += page.optJSONArray("data")?.objects().orEmpty()
      if (result.size > MAX_PAGE_ITEMS) {
        throw IOException("列表超过 $MAX_PAGE_ITEMS 条，未使用不完整结果")
      }
      val next = page.obj("cursor").str("next").takeIf { it.isNotBlank() }
      if (next != null && !seen.add(next)) throw IOException("服务器返回重复分页游标，未使用不完整结果")
      cursor = next
    } while (cursor != null)
    result
  }
  private fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
  /** Percent-encodes each `/`-separated segment so the result can be passed to [url] unchanged. */
  private fun encodedPath(value: String): String = value.trim('/').split('/').joinToString("/") { segment(it) }
  /** The official `LocationQuery` (deepObject): `location[directory]=…`. Absent means the server's own cwd. */
  private fun locationQuery(directory: String): Map<String, String> = if (directory.isBlank()) emptyMap() else mapOf("location[directory]" to directory)
  private fun sessionPath(id: String, suffix: String = "") = "api/session/${segment(id)}$suffix"
  private fun ActionEndpoint.path(session: Session) = path.replace(Regex("\\{[^}]+\\}"), segment(session.id))

  /** `GET /api/info`: the server version. Older addresses (1.x) do not have it and are rejected. */
  override suspend fun health(): String {
    version?.let { return it }
    val info = try { obj("api/info") } catch (error: ApiException) {
      if (error.status == 404) throw IOException("该地址不是 OpenCode 2.x 服务器（需要 OpenCode 2.0 及以上版本）")
      throw error
    }
    return info.str("version").ifBlank { "2.x" }.also { version = it }
  }

  private val projectManagement by lazy { OpenCodeProjectManagement(::capabilities, ::request) {
    displayProjects = null; displayProjectsAt = 0L; displayProjectsFailedAt = 0L
  } }
  override suspend fun openCodeProjects(): List<OpenCodeProjectInfo>? = projectManagement.projects()
  override suspend fun updateOpenCodeProject(expected: OpenCodeProjectInfo, edit: OpenCodeProjectEdit): OpenCodeProjectInfo? =
    projectManagement.update(expected, edit)
  override suspend fun openCodeWorktrees(expected: OpenCodeProjectInfo): List<OpenCodeWorktree>? = projectManagement.worktrees(expected)
  override suspend fun createOpenCodeWorktree(expected: OpenCodeProjectInfo, input: OpenCodeWorktreeCreate): OpenCodeWorktree? =
    projectManagement.create(expected, input)
  override suspend fun removeOpenCodeWorktree(expected: OpenCodeProjectInfo, worktree: OpenCodeWorktree, force: Boolean): Boolean? =
    projectManagement.remove(expected, worktree, force)
  override suspend fun refreshOpenCodeWorktrees(expected: OpenCodeProjectInfo): Boolean? = projectManagement.refresh(expected)

  override suspend fun projects(): List<Project> {
    val listed = try {
      val raw = withContext(Dispatchers.IO) { request("GET", "api/project") }
      // The published contract returns `Project[]` as a bare array; accept a `{data: […]}` envelope too.
      (if (raw.trimStart().startsWith("[")) jsonArray(raw) else dataArray(jsonObject(raw))).objects().map { it.toProject() }.filter { it.directory.isNotBlank() }
    } catch (error: ApiException) { if (error.status == 404) null else throw error }
    if (listed != null) return listed
    val location = dataObject(obj("api/location"))
    val directory = location.str("directory")
    return if (directory.isBlank()) emptyList()
    else listOf(Project(location.obj("project").str("id").ifBlank { directory }, directory, directory.substringAfterLast('/')))
  }

  /**
   * 行内标签只在 TTL 内复用同一份服务端项目清单，避免每次对账都读一次 `/api/project`。
   * 读取失败时退回上一次结果并退避重试；没有缓存才把错误交给调用方（调用方会回退到目录名）。
   */
  override suspend fun projectsForDisplay(): List<Project> {
    val now = System.currentTimeMillis()
    val cached = displayProjects
    if (cached != null && now - displayProjectsAt < DISPLAY_PROJECTS_TTL_MILLIS) return cached
    if (now - displayProjectsFailedAt < DISPLAY_PROJECTS_RETRY_MILLIS) {
      return cached ?: throw IOException("项目清单暂不可用")
    }
    val refreshed = try {
      withTimeoutOrNull(DISPLAY_PROJECTS_READ_TIMEOUT_MILLIS) { projects() }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      displayProjectsFailedAt = System.currentTimeMillis()
      Diagnostics.warn("OpenCodeApi", "读取项目清单失败，行内标签使用缓存或目录名", error)
      cached ?: throw error
    }
    if (refreshed != null) {
      displayProjects = refreshed
      displayProjectsAt = System.currentTimeMillis()
      return refreshed
    }
    // 超时也要退避，否则每次对账都会为装饰性标题白等一次。
    displayProjectsFailedAt = System.currentTimeMillis()
    Diagnostics.warn("OpenCodeApi", "读取项目清单超时，行内标签使用缓存或目录名")
    return cached ?: throw IOException("项目清单读取超时")
  }

  override suspend fun session(id: String): Session = dataObject(obj(sessionPath(id))).toSession()

  /**
   * Resolve [directory] to the server's canonical project, like the desktop client's `project.current()`.
   * Passing the directory through `location[directory]` lets the server do the path normalization, so
   * the phone stores the canonical directory and never spawns a duplicate project for the same folder.
   */
  override suspend fun projectAt(directory: String): Project? {
    val location = dataObject(obj("api/location", locationQuery(directory)))
    val project = location.obj("project")
    val canonical = project.str("canonical").ifBlank { project.str("worktree") }.ifBlank { location.str("directory") }.ifBlank { directory }
    if (canonical.isBlank()) return null
    val id = project.str("id").ifBlank { canonical }
    return Project(id, canonical, project.str("name").ifBlank { canonical.trimEnd('/').substringAfterLast('/').ifBlank { canonical } }, project.arr("sandboxes").strings())
  }

  /** Home catalog: like the official home index, global root sessions newest first (`parentID=null`). */
  override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int): ApiPage<Session> = withContext(Dispatchers.IO) {
    val query = mutableMapOf("limit" to size.toString(), "parentID" to "null")
    if (directory != null) query["directory"] = directory
    if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
    val page = obj("api/session", query)
    ApiPage(dataArray(page).objects().map { it.toSession() }, page.obj("cursor").str("next").takeIf(String::isNotBlank))
  }
  /** Bind the acknowledgement to the exact completed execution, never to wall-clock time. */
  override suspend fun viewSession(session: Session, idle: Long) {
    if (idle <= 0) return
    val endpoint = capabilities().sessionView ?: return
    request(endpoint.method, endpoint.path(session), body = JSONObject().put("idle", idle))
  }
  /**
   * One page of the projected timeline, oldest first. The first page is the newest [size] messages
   * (official page size 20); [cursor] walks further back.
   */
  override suspend fun messagesPage(id: String, cursor: String?, size: Int): ApiPage<Message> =
    messageRecords(id, cursor, size).let { page -> ApiPage(page.items.map { it.message }, page.next) }
  override val cacheableTranscripts: Boolean get() = true
  override suspend fun messageRecords(id: String, cursor: String?, size: Int): ApiPage<MessageRecord> = withContext(Dispatchers.IO) {
    val query = mutableMapOf("limit" to size.toString())
    if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
    val page = obj(sessionPath(id, "/message"), query)
    ApiPage(dataArray(page).objects().map { MessageRecord(it.toMessage(), it) }.asReversed(), page.obj("cursor").str("next").takeIf(String::isNotBlank))
  }
  /** Durable input admitted but not yet delivered to the agent loop (`GET /api/session/{id}/inbox`). */
  override suspend fun inbox(id: String): List<Message> = withContext(Dispatchers.IO) {
    dataArray(obj(sessionPath(id, "/inbox"))).objects().mapNotNull { it.toInboxMessage() }
  }
  override suspend fun hasPendingInput(id: String): Boolean = withContext(Dispatchers.IO) {
    dataArray(obj(sessionPath(id, "/inbox"))).length() > 0
  }
  /**
   * Sessions whose foreground drain this server currently owns; every other session is idle.
   * The published shape is `{data: {"ses_…": {type: "running"}}}`: the keys are session ids. Reading the
   * *object* is required — treating `data` as a plain key set would invent a session literally named
   * `data` on any response we did not expect.
   */
  override suspend fun activeSessions(): Set<String> {
    val json = withContext(Dispatchers.IO) { jsonObject(request("GET", "api/session/active")) }
    return json.optJSONObject("data")?.keys()?.asSequence()?.toSet().orEmpty()
  }

  /**
   * Running shell jobs for [directory] (`GET /api/shell`). Shells are not sessions: a background
   * command keeps running while its session execution is idle, so this is the only authoritative
   * read for "background work still pending" after a cold start (official `shell.list`).
   */
  override suspend fun shells(directory: String): List<ShellJob> =
    dataArray(obj("api/shell", locationQuery(directory))).objects().mapNotNull { it.toShellJob(directory) }

  override suspend fun shellInfo(id: String, directory: String): ShellSnapshot? =
    dataObject(obj("api/shell/${segment(id)}", locationQuery(directory))).toShellSnapshot(directory)

  override suspend fun shellOutput(id: String, directory: String, cursor: Long?, limit: Int): ShellOutput? {
    val query = locationQuery(directory).toMutableMap()
    if (cursor != null) query["cursor"] = cursor.toString()
    query["limit"] = limit.toString()
    return dataObject(obj("api/shell/${segment(id)}/output", query)).toShellOutput()
  }

  override suspend fun removeShell(id: String, directory: String) {
    request("DELETE", "api/shell/${segment(id)}", locationQuery(directory))
  }

  override suspend fun createSession(directory: String, title: String, workspaceId: String?): Session =
    dataObject(requestObject("POST", "api/session", JSONObject().put("location", JSONObject().put("directory", directory)).apply {
      if (title.isNotBlank()) put("title", title.trim())
    })).toSession()
  override suspend fun renameSession(session: Session, title: String) {
    val endpoint = capabilities().actions[SessionAction.RENAME] ?: unsupported("服务器未提供重命名")
    request(endpoint.method, endpoint.path(session), body = JSONObject().put("title", title))
  }
  /**
   * `PATCH /api/session/{id}` with a top-level `archived` boolean (OpenCode ≥ 2.1.0): `true` archives,
   * `false` restores. The server treats a repeat of the current state as a no-op, so there is no
   * timestamp to send — the read side derives `time.archived` itself.
   */
  override suspend fun setArchived(session: Session, archived: Boolean, now: Long) {
    val archive = capabilities().archive ?: unsupported("服务器未提供归档")
    if (!archived && !archive.restorable) unsupported("服务器不支持取消归档")
    request(archive.endpoint.method, archive.endpoint.path(session), body = JSONObject().put("archived", archived))
  }
  override suspend fun deleteSession(session: Session) { request("DELETE", sessionPath(session.id)) }
  override suspend fun forkSession(session: Session): Session {
    if (!capabilities().fork) unsupported("服务器未提供会话分叉")
    return forkSessionOnce(session, JSONObject())
  }

  override suspend fun forkSession(session: Session, boundary: ForkBoundary): Session {
    val before = (boundary as? ForkBoundary.OpenCodeBefore)?.messageId
      ?: throw ApiException(400, "OpenCode 分叉需要原生消息边界")
    if (before.isBlank() || before.length > 512 || before.any { it.isISOControl() }) {
      throw ApiException(400, "OpenCode 分叉消息 ID 无效")
    }
    if (!capabilities().historicalFork) unsupported("服务器未提供历史消息分叉")
    // OpenCode copies history BEFORE this message; this is not a DSH event sequence.
    return forkSessionOnce(session, JSONObject().put("before", before))
  }

  private suspend fun forkSessionOnce(source: Session, body: JSONObject): Session {
    // A lost response has an unknown outcome: never retry or emulate this non-idempotent write.
    val result = dataObject(requestObject("POST", sessionPath(source.id, "/fork"), body))
    val fork = result.toSession()
    if (result.sessionInfo().opt("id") !is String || fork.id.isBlank() || fork.id == source.id) {
      throw ApiException(502, "服务器未确认新的分叉会话，操作结果未知，请检查会话列表后再操作")
    }
    return fork
  }
  override suspend fun abort(session: Session) { request("POST", sessionPath(session.id, "/interrupt")) }
  /**
   * Official compaction admission: a client-minted id makes a repeated tap idempotent, and `steer` runs
   * the compaction at the next step boundary instead of queueing behind pending prompts.
   */
  override suspend fun compact(session: Session) {
    request("POST", sessionPath(session.id, "/compact"),
      body = JSONObject().put("id", MessageIds.ascending()).put("delivery", Delivery.STEER.wire))
  }
  override suspend fun revert(session: Session, messageId: String) {
    val endpoint = capabilities().actions[SessionAction.REVERT] ?: unsupported("服务器未提供消息回退")
    request(endpoint.method, endpoint.path(session), body = JSONObject().put("messageID", messageId))
  }
  override suspend fun unrevert(session: Session) {
    val endpoint = capabilities().actions[SessionAction.UNREVERT] ?: unsupported("服务器未提供恢复撤销")
    request(endpoint.method, endpoint.path(session))
  }

  /**
   * Queued input control (`session.inbox.update` / `session.inbox.cancel`). OpenCode has no in-place
   * edit: the official semantic for "edit a queued prompt" is cancel plus re-admit, so the original
   * attachments are read back first and travel with the replacement.
   */
  override suspend fun updateQueue(sessionId: String, itemId: String, action: QueueAction): Boolean {
    val path = "api/session/${segment(sessionId)}/inbox/${segment(itemId)}"
    return when (action) {
      QueueAction.Steer -> { request("PATCH", path, body = JSONObject().put("delivery", Delivery.STEER.wire)); true }
      QueueAction.Remove -> { request("DELETE", path); true }
      is QueueAction.Edit -> editQueued(sessionId, itemId, path, action.text)
    }
  }
  private suspend fun editQueued(sessionId: String, itemId: String, path: String, text: String): Boolean {
    // Read the admitted payload before cancelling it: the files are already resolved on the server and
    // are the only copy of what the user attached.
    val previous = withContext(Dispatchers.IO) {
      dataArray(obj("api/session/${segment(sessionId)}/inbox")).objects().firstOrNull { it.str("id") == itemId }
        ?.obj("payload")?.optJSONArray("files")
    }
    request("DELETE", path)
    val body = JSONObject().put("id", MessageIds.ascending()).put("text", text).put("delivery", Delivery.QUEUE.wire)
    if (previous != null && previous.length() > 0) body.put("files", previous)
    retryOnce { request("POST", sessionPath(sessionId, "/prompt"), body = body) }
    return true
  }

  /**
   * Official composer order: a steering prompt first applies the composer's agent/model selection to
   * the session, then admits the prompt under a client-minted id so one retry of a lost response is
   * idempotent. Phone files travel inline as `data:` URIs in `files[].uri`.
   */
  override suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?, references: List<FileReference>,
    inline: List<InlineFile>, id: String, delivery: Delivery) {
    if (delivery == Delivery.STEER) applySelection(session, agent, model)
    val body = JSONObject().put("id", id).put("text", text).put("delivery", delivery.wire)
    files(session, references, inline)?.let { body.put("files", it) }
    val metadata = JSONObject()
    if (!agent.isNullOrBlank()) metadata.put("agent", agent)
    if (model != null) metadata.put("model", metadataModel(model))
    if (metadata.length() > 0) body.put("metadata", metadata)
    retryOnce { request("POST", sessionPath(session.id, "/prompt"), body = body) }
  }
  /** `POST /api/session/{id}/command`: the server expands the command template (never sent as plain text). */
  override suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?,
    references: List<FileReference>, inline: List<InlineFile>, delivery: Delivery) {
    if (delivery == Delivery.STEER) applySelection(session, agent, model)
    val body = JSONObject().put("name", name).put("text", arguments).put("delivery", delivery.wire)
    files(session, references, inline)?.let { body.put("files", it) }
    request("POST", sessionPath(session.id, "/command"), body = body)
  }
  private suspend fun applySelection(session: Session, agent: String?, model: ModelChoice?) {
    if (!agent.isNullOrBlank()) request("POST", sessionPath(session.id, "/agent"), body = JSONObject().put("agent", agent))
    if (model != null) request("POST", sessionPath(session.id, "/model"), body = JSONObject().put("model", modelRef(model)))
  }
  private fun modelRef(model: ModelChoice) = JSONObject().put("providerID", model.providerId).put("id", model.modelId)
    .apply { model.variant?.let { put("variant", it) } }
  /**
   * `Model.Ref` on `POST /session/{id}/model` uses `id`, but the official composer records the same
   * selection in `metadata.model` under `modelID`; writing `id` there leaves the row unreadable to other
   * clients.
   */
  private fun metadataModel(model: ModelChoice) = JSONObject().put("providerID", model.providerId).put("modelID", model.modelId)
    .apply { model.variant?.let { put("variant", it) } }
  private fun files(session: Session, references: List<FileReference>, inline: List<InlineFile>): JSONArray? {
    if (references.isEmpty() && inline.isEmpty()) return null
    return JSONArray().apply {
      references.forEach { ref -> put(JSONObject().put("uri", referenceUri(session, ref)).put("name", ref.path.substringAfterLast('/'))) }
      inline.forEach { file -> put(JSONObject().put("uri", file.uri).put("name", file.name)) }
    }
  }
  /**
   * The official client retries an admission once with the same id. Only a transport failure is
   * retried; if that retry reports a conflict, the first attempt already admitted this id.
   */
  private suspend fun retryOnce(block: suspend () -> Unit) {
    try { block() } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (error: IOException) {
      if (error is ApiException) throw error
      try { block() } catch (conflict: ApiException) { if (conflict.status != 409) throw conflict }
    }
  }
  private fun referenceUri(session: Session, reference: FileReference): String {
    val path = if (reference.path.startsWith('/')) reference.path else session.directory.trimEnd('/') + "/" + reference.path
    return java.net.URI("file", "", path, null).toASCIIString()
  }
  override suspend fun agents(directory: String): List<AgentChoice> =
    dataArray(obj("api/agent", locationQuery(directory))).objects().filterNot { it.optBoolean("hidden") }.map { AgentChoice(it.str("id").ifBlank { it.str("name") }, it.str("description")) }
  override suspend fun commands(directory: String): List<CommandChoice> =
    dataArray(obj("api/command", locationQuery(directory))).objects().map { CommandChoice(it.str("name"), it.str("description")) }

  /** Models the server can run in [directory], with the metadata [ModelVisibility] needs. */
  override suspend fun modelCatalog(directory: String): List<ModelInfo> {
    val models = dataArray(obj("api/model", locationQuery(directory))).objects()
    // Display names only; the catalog stays usable when this optional route is missing.
    val names = try {
      dataArray(obj("api/provider", locationQuery(directory))).objects().associate { it.str("id") to it.str("name") }
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { emptyMap() }
    return models.mapNotNull { it.toV2ModelInfo(names) }
  }
  /** Pending permission requests owned by one session (official `session.permission.list`). */
  override suspend fun sessionPermissions(session: Session): List<PermissionRequest> =
    dataArray(obj(sessionPath(session.id, "/permission"))).objects().map { it.toPermission(session.directory) }
  /** Location-scoped sweep, also covers parked requests on locally known idle sessions. */
  override suspend fun pendingPermissions(directory: String): List<PermissionRequest> =
    dataArray(obj("api/permission/request", locationQuery(directory))).objects().map { it.toPermission(directory) }
  /** Pending forms at [directory] (`form.list` is location-scoped); callers filter by session. */
  override suspend fun forms(directory: String): List<QuestionRequest> =
    dataArray(obj("api/form", locationQuery(directory))).objects().map { it.toForm(directory) }
  override suspend fun replyPermission(request: PermissionRequest, reply: String) {
    require(reply in setOf("once", "always", "reject")) { "未知权限决定" }
    require(reply != "always" || request.always.isNotEmpty()) { "无法确认保存规则范围，请仅允许一次" }
    request("POST", sessionPath(request.sessionId, "/permission/${segment(request.id)}/reply"), body = JSONObject().put("decision", reply))
  }
  override suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    request("POST", sessionPath(request.sessionId, "/form/${segment(request.id)}/reply"), body = JSONObject().put("answer", formAnswer(request, answers)))
  }
  override suspend fun rejectQuestion(request: QuestionRequest) { request("DELETE", sessionPath(request.sessionId, "/form/${segment(request.id)}")) }
  override suspend fun children(session: Session): List<Session> = dataObjects("api/session", mapOf("parentID" to session.id)).map { it.toSession() }
  override suspend fun diff(session: Session): List<FileChange> =
    if (capabilities().diff) dataArray(obj(sessionPath(session.id, "/diff"))).objects().map { it.toChange() } else unsupported("服务器不提供改动记录")
  override suspend fun files(directory: String, path: String, session: Session?): List<FileNode> =
    dataArray(obj("api/fs/list", locationQuery(directory) + (if (path.isNotBlank() && path != ".") mapOf("path" to path) else emptyMap())))
      .objects().map { item -> FileNode(item.str("path"), item.str("type")) }
  override suspend fun fileContent(directory: String, path: String, session: Session?): FileContent {
    val (bytes, contentType) = requestBytes("api/fs/read/${encodedPath(path)}", locationQuery(directory))
    return if (!isTextFile(path, contentType)) FileContent("binary", java.util.Base64.getEncoder().encodeToString(bytes))
    else FileContent("text", bytes.toString(Charsets.UTF_8))
  }
  override suspend fun searchFiles(directory: String, query: String, session: Session?): List<String> =
    dataArray(obj("api/fs/find", locationQuery(directory) + mapOf("query" to query))).objects().mapNotNull { it.str("path").takeIf(String::isNotBlank) }

  /** `GET /api/event`: volatile by contract, so a dropped stream is recovered by reconnect + reconciliation. */
  override fun events(onOpen: () -> Unit): Flow<ServerEvent> = callbackFlow {
    val stream = nativeTransport.openEventStream(
      requestPath("api/event", emptyMap()),
      requestHeaders("text/event-stream"),
      callback = object : NativeHttpEventStreamCallback {
        override fun onOpen() { onOpen() }
        override fun onEvent(frame: NativeHttpEvent) {
          try {
            val event = frame.data.toServerEvent(frame.id.orEmpty())
            val delivered = trySend(event)
            if (delivered.isFailure) {
              Diagnostics.warn("SSE", "事件积压，触发重新同步", delivered.exceptionOrNull())
              close(IOException("SSE 事件积压，已触发全量对账"))
            }
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (error: Exception) {
            Diagnostics.warn("SSE", "事件无法解析，触发重新同步", error)
            close(IOException("SSE 事件无法解析，已触发全量对账", error))
          }
        }
        override fun onFailure(error: Throwable) { close(error) }
        override fun onClosed() { close() }
      }
    )
    awaitClose { stream.cancel() }
  }

  private suspend fun requestObject(method: String, path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) { jsonObject(request(method, path, body = body)) }

  private fun unsupported(message: String): Nothing = throw ApiException(501, message)

  private fun isTextFile(path: String, contentType: String): Boolean {
    val mime = contentType.substringBefore(';').trim().lowercase()
    if (mime.startsWith("text/") || mime in TEXT_MIME_TYPES) return true
    val name = path.substringAfterLast('/').lowercase()
    if (name in TEXT_FILE_NAMES) return true
    val extension = name.substringAfterLast('.', "")
    return extension in TEXT_EXTENSIONS
  }

  companion object {
    /** Official initial page size (`messagePageLimit`). */
    const val MESSAGE_PAGE = 20
    /** Older-history page size: the official client and the server both cap a page at 200. */
    const val HISTORY_PAGE = 200
    private const val MAX_RESPONSE_CHARS = 8_000_000
    private const val MAX_FILE_BYTES = 8_000_000L
    private const val MAX_PAGES = 40
    private const val MAX_PAGE_ITEMS = 5_000
    /** Row labels tolerate a stale project title; re-reading it on every 20–60 s reconcile does not. */
    private const val DISPLAY_PROJECTS_TTL_MILLIS = 10 * 60_000L
    /** A failed title read falls back to directory names and retries later, not on every reconcile. */
    private const val DISPLAY_PROJECTS_RETRY_MILLIS = 5 * 60_000L
    /** 装饰性标题不能占用一次对账的网络等待。 */
    private const val DISPLAY_PROJECTS_READ_TIMEOUT_MILLIS = 3_000L
    private val TEXT_MIME_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-javascript")
    private val TEXT_FILE_NAMES = setOf("dockerfile", "makefile", "license", "readme", "changelog", ".gitignore", ".gitattributes", ".env")
    private val TEXT_EXTENSIONS = setOf(
      "txt", "md", "markdown", "rst", "json", "jsonc", "yaml", "yml", "toml", "xml", "html", "htm", "css", "scss", "less",
      "js", "jsx", "ts", "tsx", "vue", "svelte", "kt", "kts", "java", "groovy", "gradle", "py", "rb", "go", "rs", "c", "cc",
      "cpp", "h", "hh", "hpp", "sh", "bash", "zsh", "sql", "swift", "php", "pl", "ini", "cfg", "conf", "properties",
      "env", "gitignore", "gitattributes", "bat", "cmd", "ps1", "tf", "hcl", "proto", "graphql", "lock", "svg", "tex", "diff", "patch"
    )
  }
}

/**
 * Client-minted message ids in the server's own format (`Identifier.ascending`): 6 bytes of
 * `timestamp * 0x1000 + counter` in hex, then 14 random base-62 characters. They sort with server
 * ids, which matters for revert boundaries and timeline order.
 */
object MessageIds {
  private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
  private val random = SecureRandom()
  private var lastTimestamp = 0L
  private var counter = 0L
  @Synchronized fun ascending(timestamp: Long = System.currentTimeMillis()): String {
    if (timestamp != lastTimestamp) { lastTimestamp = timestamp; counter = 0 }
    counter++
    val value = timestamp * 0x1000L + counter
    val time = (0 until 6).joinToString("") { index -> "%02x".format((value shr (40 - 8 * index)) and 0xff) }
    val tail = (0 until 14).joinToString("") { CHARS[random.nextInt(62)].toString() }
    return "msg_$time$tail"
  }
}

/** Parses one `/api/event` frame (`{id, type, created, location, data}`). */
internal fun String.toServerEvent(sseId: String): ServerEvent {
  val json = JSONObject(this)
  val properties = json.optJSONObject("data") ?: json.obj("properties")
  val created = json.optLong("created")
  val type = json.str("type")
  require(type.isNotBlank()) { "SSE 事件缺少类型" }
  return ServerEvent(json.str("id").ifBlank { sseId }, json.obj("location").str("directory"), type, properties, created)
}
