package com.igng.opencode.lagoon.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class ServerCredentials(
  val username: String = "opencode",
  val password: String = "",
  val cookie: String = ""
)

data class ApiPage<T>(val items: List<T>, val next: String? = null)

/** One `/api/event` frame: `type`, its `data` as [properties], the event `location` and `created` time. */
data class ServerEvent(val id: String = "", val directory: String, val type: String, val properties: JSONObject, val created: Long = 0)
class ApiException(val status: Int, message: String) : IOException(message)

/** How a new prompt is delivered while the session is busy (official `Session.Inbox.Delivery`). */
enum class Delivery(val wire: String) { STEER("steer"), QUEUE("queue") }

/**
 * Client for the OpenCode 2.x server (`anomalyco/opencode` `packages/protocol`). Routes, payloads and
 * response envelopes follow the published contract; operations whose route differs between 2.0.x
 * releases are resolved from the server's own `/openapi.json` (see [ApiCapabilities]).
 */
class OpenCodeApi(
  private val profile: ServerProfile,
  private val credentials: ServerCredentials,
  /** Whole-call deadline. Background callers (e.g. notification actions) pass a small value to stay
   *  inside the short lifetime Android grants a BroadcastReceiver; foreground callers keep the default. */
  callTimeoutSeconds: Long = 60
) {
  constructor(profile: ServerProfile, password: String) : this(profile, ServerCredentials(profile.username, password))

  private val base: HttpUrl = requireNotNull(profile.url.trimEnd('/').toHttpUrlOrNull()) { "服务器地址无效" }
  private val origin = HttpOrigin.of(base)
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
    .connectionPool(SharedHttp.connectionPool)
    .dispatcher(SharedHttp.dispatcher)
    // Never let OkHttp's default redirect policy replay an authenticated request to a different
    // scheme/host/port: a trusted server (or its proxy) could otherwise reflect the Authorization
    // header or the raw Cookie onto a downgraded or unrelated origin before we ever check the final
    // URL.
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
      // Defence in depth: even if the redirect policy is ever changed, no authenticated request can
      // leave the configured origin.
      val request = chain.request()
      if (HttpOrigin.of(request.url) != origin) throw IOException("拒绝向非授权地址发送凭据")
      chain.proceed(request)
    }
    .build()
  private val streamClient = client.newBuilder()
    .dispatcher(SharedHttp.streamDispatcher)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()
  /** Dedicated scope for blocking HTTP work so a cancelled caller never waits on the socket. */
  private val ioScope = SharedHttp.ioScope
  @Volatile private var version: String? = null
  @Volatile private var discoveredCapabilities: ApiCapabilities? = null
  fun capabilities(): ApiCapabilities = discoveredCapabilities ?: ApiCapabilities.BASELINE

  /** Reads the instance's own OpenAPI document once; routes that moved between 2.0.x releases are taken from it. */
  suspend fun discoverCapabilities(): ApiCapabilities {
    discoveredCapabilities?.let { return it }
    val document = try { obj("openapi.json") } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
      catch (error: Exception) { Diagnostics.warn("OpenCodeApi", "无法读取 /openapi.json，按 2.x 最新契约处理", error); null }
    return (if (document == null) ApiCapabilities.BASELINE else ApiCapabilities.fromDocument(document)).also { discoveredCapabilities = it }
  }

  init {
    require(base.scheme == "https" || base.scheme == "http" && profile.allowCleartext) { "HTTP 明文连接未获授权，请在服务器资料中明确开启" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "请使用不含凭据或参数的服务器地址" }
  }

  private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
    // The path is already percent-encoded segment-by-segment (see [segment]/[encodedPath]); using the
    // encoded variant avoids OkHttp re-encoding '%' and producing e.g. '%2520' for ids containing spaces.
    val builder = base.newBuilder().addEncodedPathSegments(path.trimStart('/'))
    query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    return builder.build()
  }

  private fun requestBuilder(path: String, query: Map<String, String>): Request.Builder {
    val builder = Request.Builder().url(url(path, query)).header("Accept", "application/json")
    if (credentials.password.isNotEmpty()) {
      builder.header("Authorization", Credentials.basic(credentials.username.ifBlank { profile.username.ifBlank { "opencode" } }, credentials.password))
    }
    if (credentials.cookie.isNotBlank()) builder.header("Cookie", credentials.cookie)
    return builder
  }

  /** Every request this client builds targets [origin]; the redirect policy forbids OkHttp from
   *  moving an authenticated request elsewhere, so the credentials can only ever reach [origin]. */
  private fun requireOrigin(request: Request) {
    require(HttpOrigin.of(request.url) == origin) { "拒绝向非授权地址发送凭据" }
  }

  private fun httpErrorMessage(status: Int, body: String): String {
    val serverMessage = runCatching {
      val json = JSONObject(body)
      json.errorMessage().ifBlank { json.obj("data").errorMessage() }
    }.getOrDefault("")
    return serverMessage.ifBlank {
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
    val mediaType = "application/json; charset=utf-8".toMediaType()
    return runCall(requestBuilder(path, query).method(method, if (method == "GET" || method == "DELETE" && body == null) null else (body?.toString() ?: "{}").toRequestBody(mediaType)).build()) { response ->
      val responseBody = response.body?.readText(MAX_RESPONSE_CHARS).orEmpty()
      if (!response.isSuccessful) throw ApiException(response.code, httpErrorMessage(response.code, responseBody))
      responseBody
    }
  }

  private suspend fun requestBytes(path: String, query: Map<String, String> = emptyMap()): Pair<ByteArray, String> =
    runCall(requestBuilder(path, query).get().build()) { response ->
      val responseBody = response.body ?: error("服务器返回空响应")
      if (!response.isSuccessful) {
        val body = responseBody.readText(MAX_RESPONSE_CHARS)
        throw ApiException(response.code, httpErrorMessage(response.code, body))
      }
      responseBody.bytes(MAX_FILE_BYTES) to (response.header("Content-Type") ?: "application/octet-stream")
    }

  /**
   * Runs the blocking call and its body read on a background worker, but cancels the underlying
   * socket the moment the caller's coroutine is cancelled. Cancellation must not wait for the server
   * to answer a request the user already navigated away from (A15): the coroutine returns
   * immediately while [call] is aborted and the abandoned worker unwinds on its own.
   */
  private suspend fun <T> runCall(request: Request, read: (Response) -> T): T {
    requireOrigin(request)
    return suspendCancellableCoroutine { continuation ->
      val call = client.newCall(request)
      val worker = ioScope.launch {
        try {
          continuation.resume(call.execute().use(read))
        } catch (error: Throwable) {
          continuation.resumeWith(Result.failure(error))
        }
      }
      continuation.invokeOnCancellation {
        call.cancel()
        worker.cancel()
      }
    }
  }

  private fun ResponseBody.readText(limit: Int): String {
    val source = source()
    return if (source.request(limit.toLong())) {
      throw IOException("服务器响应过大（超过 $limit 字节），已拒绝读取")
    } else source.readString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
  }

  private fun ResponseBody.bytes(limit: Long): ByteArray {
    val source = source()
    if (source.request(limit + 1)) throw IOException("文件过大（超过 $limit 字节），已拒绝下载")
    return bytes()
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
  suspend fun health(): String {
    version?.let { return it }
    val info = try { obj("api/info") } catch (error: ApiException) {
      if (error.status == 404) throw IOException("该地址不是 OpenCode 2.x 服务器（需要 OpenCode 2.0 及以上版本）")
      throw error
    }
    return info.str("version").ifBlank { "2.x" }.also { version = it }
  }

  suspend fun projects(): List<Project> {
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
  suspend fun session(id: String): Session = dataObject(obj(sessionPath(id))).toSession()

  /**
   * Resolve [directory] to the server's canonical project, like the desktop client's `project.current()`.
   * Passing the directory through `location[directory]` lets the server do the path normalization, so
   * the phone stores the canonical directory and never spawns a duplicate project for the same folder.
   */
  suspend fun projectAt(directory: String): Project? {
    val location = dataObject(obj("api/location", locationQuery(directory)))
    val project = location.obj("project")
    val canonical = project.str("canonical").ifBlank { project.str("worktree") }.ifBlank { location.str("directory") }.ifBlank { directory }
    if (canonical.isBlank()) return null
    val id = project.str("id").ifBlank { canonical }
    return Project(id, canonical, project.str("name").ifBlank { canonical.trimEnd('/').substringAfterLast('/').ifBlank { canonical } }, project.arr("sandboxes").strings())
  }

  /** Home catalog: like the official home index, global root sessions newest first (`parentID=null`). */
  suspend fun rootSessionsPage(directory: String? = null, cursor: String? = null, size: Int = 100): ApiPage<Session> = withContext(Dispatchers.IO) {
    val query = mutableMapOf("limit" to size.toString(), "parentID" to "null")
    if (directory != null) query["directory"] = directory
    if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
    val page = obj("api/session", query)
    ApiPage(dataArray(page).objects().map { it.toSession() }, page.obj("cursor").str("next").takeIf(String::isNotBlank))
  }
  /** Bind the acknowledgement to the exact completed execution, never to wall-clock time. */
  suspend fun viewSession(session: Session, idle: Long) {
    if (idle <= 0) return
    val endpoint = capabilities().sessionView ?: return
    request(endpoint.method, endpoint.path(session), body = JSONObject().put("idle", idle))
  }
  /**
   * One page of the projected timeline, oldest first. The first page is the newest [size] messages
   * (official page size 20); [cursor] walks further back.
   */
  suspend fun messagesPage(id: String, cursor: String? = null, size: Int = MESSAGE_PAGE): ApiPage<Message> = withContext(Dispatchers.IO) {
    val query = mutableMapOf("limit" to size.toString())
    if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
    val page = obj(sessionPath(id, "/message"), query)
    ApiPage(dataArray(page).objects().map { it.toMessage() }.asReversed(), page.obj("cursor").str("next").takeIf(String::isNotBlank))
  }
  /** Durable input admitted but not yet delivered to the agent loop (`GET /api/session/{id}/inbox`). */
  suspend fun inbox(id: String): List<Message> = withContext(Dispatchers.IO) {
    dataArray(obj(sessionPath(id, "/inbox"))).objects().mapNotNull { it.toInboxMessage() }
  }
  /** Sessions whose foreground drain this server currently owns; every other session is idle. */
  suspend fun activeSessions(): Set<String> {
    val json = withContext(Dispatchers.IO) { jsonObject(request("GET", "api/session/active")) }
    // 有的实现把运行集合包成数组；不能把 {"data":[...]} 的键名当成会话 id（会造出一个叫 data 的假会话）。
    json.optJSONObject("data")?.let { return it.keys().asSequence().toSet() }
    val array = json.optJSONArray("data") ?: json.optJSONArray("sessions")
    if (array != null) return array.objects().mapNotNull { it.str("id").ifBlank { it.str("sessionID") }.takeIf(String::isNotBlank) }.toSet()
    if (!json.has("data")) return json.keys().asSequence().toSet()
    return emptySet()
  }

  /**
   * Running shell jobs for [directory] (`GET /api/shell`). Shells are not sessions: a background
   * command keeps running while its session execution is idle, so this is the only authoritative
   * read for "background work still pending" after a cold start (official `shell.list`).
   */
  suspend fun shells(directory: String): List<ShellJob> =
    dataArray(obj("api/shell", locationQuery(directory))).objects().mapNotNull { it.toShellJob(directory) }

  suspend fun createSession(directory: String, title: String): Session =
    dataObject(requestObject("POST", "api/session", JSONObject().put("location", JSONObject().put("directory", directory)).apply {
      if (title.isNotBlank()) put("title", title.trim())
    })).toSession()
  suspend fun renameSession(session: Session, title: String) {
    val endpoint = capabilities().actions[SessionAction.RENAME] ?: unsupported("服务器未提供重命名")
    request(endpoint.method, endpoint.path(session), body = JSONObject().put("title", title))
  }
  /** Archive (or, when the instance allows a null `time.archived`, restore) through the documented update body. */
  suspend fun setArchived(session: Session, archived: Boolean, now: Long = System.currentTimeMillis()) {
    val archive = capabilities().archive ?: unsupported("服务器未提供归档")
    if (!archived && !archive.restorable) unsupported("服务器不支持取消归档")
    request(archive.endpoint.method, archive.endpoint.path(session), body = JSONObject().put("time", JSONObject().put("archived", if (archived) now else JSONObject.NULL)))
  }
  suspend fun deleteSession(session: Session) { request("DELETE", sessionPath(session.id)) }
  suspend fun forkSession(session: Session): Session = dataObject(requestObject("POST", sessionPath(session.id, "/fork"), JSONObject())).toSession()
  suspend fun abort(session: Session) { request("POST", sessionPath(session.id, "/interrupt")) }
  suspend fun compact(session: Session) { request("POST", sessionPath(session.id, "/compact")) }
  suspend fun revert(session: Session, messageId: String) {
    request("POST", sessionPath(session.id, "/revert/stage"), body = JSONObject().put("messageID", messageId))
  }
  suspend fun unrevert(session: Session) {
    val endpoint = capabilities().actions[SessionAction.UNREVERT] ?: unsupported("服务器未提供恢复撤销")
    request(endpoint.method, endpoint.path(session))
  }

  /**
   * Official composer order: a steering prompt first applies the composer's agent/model selection to
   * the session, then admits the prompt under a client-minted id so one retry of a lost response is
   * idempotent. Phone files travel inline as `data:` URIs in `files[].uri`.
   */
  suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?, references: List<FileReference> = emptyList(),
    inline: List<InlineFile> = emptyList(), id: String = MessageIds.ascending(), delivery: Delivery = Delivery.STEER) {
    if (delivery == Delivery.STEER) applySelection(session, agent, model)
    val body = JSONObject().put("id", id).put("text", text).put("delivery", delivery.wire)
    files(session, references, inline)?.let { body.put("files", it) }
    val metadata = JSONObject()
    if (!agent.isNullOrBlank()) metadata.put("agent", agent)
    if (model != null) metadata.put("model", modelRef(model))
    if (metadata.length() > 0) body.put("metadata", metadata)
    retryOnce { request("POST", sessionPath(session.id, "/prompt"), body = body) }
  }
  /** `POST /api/session/{id}/command`: the server expands the command template (never sent as plain text). */
  suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?,
    references: List<FileReference> = emptyList(), inline: List<InlineFile> = emptyList(), delivery: Delivery = Delivery.STEER) {
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
  suspend fun agents(directory: String): List<AgentChoice> =
    dataArray(obj("api/agent", locationQuery(directory))).objects().filterNot { it.optBoolean("hidden") }.map { AgentChoice(it.str("id").ifBlank { it.str("name") }, it.str("description")) }
  suspend fun commands(directory: String): List<CommandChoice> =
    dataArray(obj("api/command", locationQuery(directory))).objects().map { CommandChoice(it.str("name"), it.str("description")) }

  /** Models the server can run in [directory], with the metadata [ModelVisibility] needs. */
  suspend fun modelCatalog(directory: String): List<ModelInfo> {
    val models = dataArray(obj("api/model", locationQuery(directory))).objects()
    // Display names only; the catalog stays usable when this optional route is missing.
    val names = try {
      dataArray(obj("api/provider", locationQuery(directory))).objects().associate { it.str("id") to it.str("name") }
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { emptyMap() }
    return models.mapNotNull { it.toV2ModelInfo(names) }
  }
  /** Pending permission requests owned by one session (official `session.permission.list`). */
  suspend fun sessionPermissions(session: Session): List<PermissionRequest> =
    dataArray(obj(sessionPath(session.id, "/permission"))).objects().map { it.toPermission(session.directory) }
  /** Location-scoped sweep, also covers parked requests on locally known idle sessions. */
  suspend fun pendingPermissions(directory: String): List<PermissionRequest> =
    dataArray(obj("api/permission/request", locationQuery(directory))).objects().map { it.toPermission(directory) }
  /** Pending forms at [directory] (`form.list` is location-scoped); callers filter by session. */
  suspend fun forms(directory: String): List<QuestionRequest> =
    dataArray(obj("api/form", locationQuery(directory))).objects().map { it.toForm(directory) }
  suspend fun replyPermission(request: PermissionRequest, reply: String) {
    require(reply in setOf("once", "always", "reject")) { "未知权限决定" }
    require(reply != "always" || request.always.isNotEmpty()) { "无法确认保存规则范围，请仅允许一次" }
    request("POST", sessionPath(request.sessionId, "/permission/${segment(request.id)}/reply"), body = JSONObject().put("decision", reply))
  }
  suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    request("POST", sessionPath(request.sessionId, "/form/${segment(request.id)}/reply"), body = JSONObject().put("answer", formAnswer(request, answers)))
  }
  suspend fun rejectQuestion(request: QuestionRequest) { request("DELETE", sessionPath(request.sessionId, "/form/${segment(request.id)}")) }
  suspend fun children(session: Session): List<Session> = dataObjects("api/session", mapOf("parentID" to session.id)).map { it.toSession() }
  suspend fun diff(session: Session): List<FileChange> =
    if (capabilities().diff) dataArray(obj(sessionPath(session.id, "/diff"))).objects().map { it.toChange() } else unsupported("服务器不提供改动记录")
  suspend fun files(directory: String, path: String): List<FileNode> =
    dataArray(obj("api/fs/list", locationQuery(directory) + (if (path.isNotBlank() && path != ".") mapOf("path" to path) else emptyMap())))
      .objects().map { item -> FileNode(item.str("path"), item.str("type")) }
  suspend fun fileContent(directory: String, path: String): FileContent {
    val (bytes, contentType) = requestBytes("api/fs/read/${encodedPath(path)}", locationQuery(directory))
    return if (!isTextFile(path, contentType)) FileContent("binary", Base64.encodeToString(bytes, Base64.NO_WRAP))
    else FileContent("text", bytes.toString(Charsets.UTF_8))
  }
  suspend fun searchFiles(directory: String, query: String): List<String> =
    dataArray(obj("api/fs/find", locationQuery(directory) + mapOf("query" to query))).objects().mapNotNull { it.str("path").takeIf(String::isNotBlank) }

  /** `GET /api/event`: volatile by contract, so a dropped stream is recovered by reconnect + reconciliation. */
  fun events(onOpen: () -> Unit = {}): Flow<ServerEvent> = callbackFlow {
    val request = requestBuilder("api/event", emptyMap()).header("Accept", "text/event-stream").build()
    requireOrigin(request)
    val source: EventSource = EventSources.createFactory(streamClient).newEventSource(request, object : EventSourceListener() {
      override fun onOpen(eventSource: EventSource, response: Response) { onOpen() }
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        try {
          val event = data.toServerEvent(id.orEmpty())
          // A full/closed buffer must not silently drop an event: the dropped event could be the very
          // permission or terminal state the UI is waiting for. Surface it as a stream failure so the
          // controller reconnects and runs a full reconciliation instead of trusting a partial view.
          val delivered = trySend(event)
          if (delivered.isFailure) {
            Diagnostics.warn("SSE", "事件积压，触发重新同步", delivered.exceptionOrNull())
            close(IOException("SSE 事件积压，已触发全量对账"))
          }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
          throw cancel
        } catch (error: Exception) {
          // Ignore malformed event; next event or refresh repairs state. Logged for diagnosis.
          Diagnostics.warn("SSE", "忽略无法解析的事件", error)
        }
      }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        close(t ?: IOException("SSE 已断开：HTTP ${response?.code ?: 0}"))
      }
      override fun onClosed(eventSource: EventSource) { close() }
    })
    awaitClose { source.cancel() }
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
  return ServerEvent(json.str("id").ifBlank { sseId }, json.obj("location").str("directory"), json.str("type"), properties, created)
}
