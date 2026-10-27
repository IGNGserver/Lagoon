package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.ApiException
import com.igng.opencode.lagoon.core.AttachmentPolicy
import com.igng.opencode.lagoon.core.Delivery
import com.igng.opencode.lagoon.core.DshAuthMode
import com.igng.opencode.lagoon.core.DshLaunchLink
import com.igng.opencode.lagoon.core.FileReference
import com.igng.opencode.lagoon.core.HarnessClient
import com.igng.opencode.lagoon.core.InlineFile
import com.igng.opencode.lagoon.core.Message
import com.igng.opencode.lagoon.core.MessageIds
import com.igng.opencode.lagoon.core.MessagePart
import com.igng.opencode.lagoon.core.PairLinkResolver
import com.igng.opencode.lagoon.core.PermissionPolicy
import com.igng.opencode.lagoon.core.Project
import com.igng.opencode.lagoon.core.RefreshMode
import com.igng.opencode.lagoon.core.ServerCredentials
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.TaskReconciliation
import com.igng.opencode.lagoon.core.createHarnessClient
import com.igng.opencode.lagoon.core.normalizedDirectory
import com.igng.opencode.lagoon.core.profileCredentials
import com.igng.opencode.lagoon.core.referenceMime
import com.igng.opencode.lagoon.core.serverReadCandidates
import com.igng.opencode.lagoon.core.sessionActivityOrder
import com.igng.opencode.lagoon.core.widenOlderToTurn
import kotlinx.coroutines.async
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The `/api/v1` routes; the contract is documented in docs/HUB_DESIGN.md §7. */
class HubApi(
  private val runtime: HubRuntime,
  private val store: HubStore,
  private val auth: Authenticator,
  private val streams: StreamRegistry,
  private val push: PushService?,
  private val tls: TlsSetup?,
  private val version: String
) {
  private val choicesCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
  private val clientAccess = ClientAccess(store, auth)

  /** Routes reachable without a session; the export authorizes itself with a client access key. */
  val publicPaths = setOf("/api/v1/auth/status", "/api/v1/auth/login", "/api/v1/auth/setup", "/api/v1/tls/cert", ClientAccess.EXPORT_PATH)

  fun register(router: Router) {
    authRoutes(router)
    serverRoutes(router)
    sessionRoutes(router)
    workspaceRoutes(router)
    preferenceRoutes(router)
    clientAccess.register(router)
  }

  // ---- Access password ----

  private fun authRoutes(router: Router) {
    router.get("/api/v1/auth/status") { call ->
      val signedIn = auth.session(call.cookies[Authenticator.COOKIE]) != null
      call.respond(JSONObject().put("authenticated", signedIn).put("setupRequired", auth.setupRequired)
        .put("passwordFromEnv", auth.passwordFromEnv).put("version", version).put("secure", call.secure)
        .put("tls", tls?.let { JSONObject().put("selfSigned", it.selfSigned).put("port", it.port) } ?: JSONObject.NULL))
    }
    router.post("/api/v1/auth/setup") { call ->
      val body = call.json()
      val password = body.optString("password")
      if (password.isEmpty()) throw HttpError(400, "password-empty", "密码不能为空")
      throttled(call) {
        if (!auth.setupRequired) throw HttpError(409, "already-set", "访问密码已经设置，请直接登录")
        auth.setup(body.optString("code"), password)
      }
      signIn(call)
    }
    router.post("/api/v1/auth/login") { call ->
      val password = call.json().optString("password")
      if (auth.setupRequired) throw HttpError(409, "setup-required", "请先设置访问密码")
      throttled(call) { auth.verify(password) }
      signIn(call)
    }
    router.post("/api/v1/auth/logout") { call ->
      auth.revoke(call.token)
      streams.closeLogins(call.login?.hash)
      call.setCookie(cookie("", call.secure, 0))
      call.ok()
    }
    router.post("/api/v1/auth/password") { call ->
      if (auth.passwordFromEnv) throw HttpError(409, "password-from-env", "密码由环境变量 LAGOON_PASSWORD 管理，请修改部署配置")
      val body = call.json()
      val next = body.optString("next")
      if (next.isEmpty()) throw HttpError(400, "password-empty", "新密码不能为空")
      throttled(call) { auth.verify(body.optString("current")) }
      auth.changePassword(next)
      streams.closeLogins(null)
      signIn(call)
    }
    router.post("/api/v1/auth/revoke") { call ->
      auth.revokeAll()
      streams.closeLogins(null)
      call.setCookie(cookie("", call.secure, 0))
      call.ok()
    }
    router.get("/api/v1/tls/cert") { call ->
      val certificate = tls?.authorityCertificate() ?: throw HttpError(404, "no-certificate", "未启用自签名证书")
      call.respondBytes(200, certificate, "application/x-x509-ca-cert",
        mapOf("Content-Disposition" to "attachment; filename=\"lagoon-hub-ca.crt\""))
    }
  }

  /** Runs a password check through the brute-force brake; a wrong password answers 401. */
  private fun throttled(call: Call, check: () -> Boolean) {
    val wait = auth.throttle.retryAfter(call.clientAddress)
    if (wait > 0) {
      val seconds = (wait + 999) / 1000
      throw HttpError(429, "throttled", "尝试次数过多，请 $seconds 秒后再试", seconds)
    }
    if (!check()) {
      auth.throttle.failed(call.clientAddress)
      throw HttpError(401, "wrong-password", "密码或初始化码不正确")
    }
    auth.throttle.succeeded(call.clientAddress)
  }

  private fun signIn(call: Call) {
    val label = call.header("User-Agent").orEmpty()
    val token = auth.issue(label)
    call.setCookie(cookie(token, call.secure, Authenticator.SESSION_TTL_MS / 1000))
    call.respond(JSONObject().put("authenticated", true))
  }

  private fun cookie(value: String, secure: Boolean, maxAge: Long): String =
    "${Authenticator.COOKIE}=$value; Path=/; HttpOnly; SameSite=Strict; Max-Age=$maxAge" + if (secure) "; Secure" else ""

  // ---- Saved servers ----

  private fun serverRoutes(router: Router) {
    router.get("/api/v1/servers") { call ->
      call.respond(JSONObject().put("servers", Codec.array(store.profiles().map(store::profileJson))))
    }
    router.post("/api/v1/servers/parse") { call -> call.respond(parse(call.json().optString("text"))) }
    router.post("/api/v1/servers") { call -> saveServer(call, UUID.randomUUID().toString(), creating = true) }
    router.put("/api/v1/servers/{id}") { call -> saveServer(call, call.param("id"), creating = false) }
    router.delete("/api/v1/servers/{id}") { call ->
      val id = call.param("id")
      store.profile(id) ?: throw HttpError(404, "server-not-found", "服务器不存在")
      store.deleteServer(id)
      runtime.run { profileRemoved(id) }
      call.ok()
    }
    router.get("/api/v1/state") { call -> call.respond(runtime.run { stateJson() }) }
    router.get("/api/v1/events") { call ->
      val login = call.login ?: throw HttpError(401, "unauthorized", "请先登录")
      val stream = streams.open(login.hash)
      try {
        runtime.run { greet(stream) }
        stream.pump(call.exchange)
      } finally {
        streams.remove(stream)
        runtime.run { detach(stream) }
      }
    }
    router.put("/api/v1/streams/{id}/watch") { call ->
      val stream = streams.get(call.param("id")) ?: throw HttpError(404, "stream-gone", "实时连接已断开")
      if (stream.loginHash != call.login?.hash) throw HttpError(403, "forbidden", "无权操作该连接")
      val body = call.json()
      val list = body.optJSONArray("conversations") ?: JSONArray()
      val conversations = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull {
        val server = it.optString("serverId"); val session = it.optString("sessionId")
        if (server.isBlank() || session.isBlank()) null else server to session
      }.take(8).toSet()
      runtime.run { watch(stream, conversations, body.optBoolean("visible", true)) }
      call.ok()
    }
  }

  /** Recognizes what the user pasted: a `dsh web` launch link, an `opencode pair` link or a plain address. */
  private fun parse(text: String): JSONObject {
    val value = text.trim()
    DshLaunchLink.parse(value)?.let { link ->
      return JSONObject().put("kind", ServerKind.DSH.wire).put("url", link.serverUrl).put("secret", link.token)
        .put("dshAuth", DshAuthMode.TOKEN.wire).put("allowCleartext", link.serverUrl.startsWith("http://", true))
    }
    if (PairLinkResolver.isPairLink(value)) return JSONObject().put("kind", ServerKind.OPENCODE.wire).put("url", value).put("pair", true)
    return JSONObject().put("url", value.trimEnd('/')).put("allowCleartext", value.startsWith("http://", true))
  }

  private fun saveServer(call: Call, id: String, creating: Boolean) {
    val body = call.json()
    val input = body.optJSONObject("profile") ?: throw HttpError(400, "bad-request", "缺少服务器资料")
    val existing = store.profile(id)
    if (!creating && existing == null) throw HttpError(404, "server-not-found", "服务器不存在")
    val secret = body.optString("secret").takeIf { body.has("secret") && !body.isNull("secret") }
    val clientUrl = if (input.has("clientUrl")) clientAddress(input.optString("clientUrl")) else null
    val requested = Codec.profileFrom(input, id).let { profile ->
      val dsh = profile.kind == ServerKind.DSH
      profile.copy(name = profile.name.ifBlank { profile.kind.label },
        username = if (dsh) "dsh" else profile.username.ifBlank { "opencode" },
        allowCleartext = profile.url.startsWith("http://", true) && profile.allowCleartext,
        dshAuth = if (dsh) profile.dshAuth else DshAuthMode.TOKEN)
    }
    val result = runtime.run {
      val pair = PairLinkResolver.isPairLink(requested.url)
      val resolved = if (pair) PairLinkResolver.resolve(requested.url) else null
      val profile = if (resolved == null) requested else requested.copy(url = resolved.serverUrl, username = resolved.credentials.username)
      validateAddress(profile)
      val dsh = profile.kind == ServerKind.DSH
      if (dsh && existing != null && existing.dshAuth != profile.dshAuth && secret.isNullOrBlank()) {
        throw HttpError(400, "secret-required", "切换认证方式后请填写新的${profile.dshAuth.label}")
      }
      if (creating && resolved == null && secret.isNullOrBlank() && dsh) throw HttpError(400, "secret-required", "请填写${profile.dshAuth.label}")
      val credentials = resolved?.credentials ?: profileCredentials(existing?.url, profile.url,
        if (existing != null) store.credentials(id) else ServerCredentials(profile.username), profile.username,
        (if (dsh) secret?.trim() else secret)?.takeIf { it.isNotEmpty() || existing == null },
        // A new DSH token/secret or a different auth mode must be proven by this test, not by an old cookie.
        cookie = "".takeIf { dsh && (secret != null || existing?.dshAuth != profile.dshAuth) })
      var minted: String? = null
      val client = createHarnessClient(profile, credentials) { minted = it }
      val serverVersion = client.health()
      client.projects()
      store.saveServer(profile, credentials.copy(cookie = minted ?: credentials.cookie), clientUrl)
      profileChanged(profile)
      profile to serverVersion
    }
    call.respond(JSONObject().put("profile", store.profileJson(result.first)).put("version", result.second))
  }

  /** The optional address other clients use for a server; empty clears it. */
  private fun clientAddress(value: String): String {
    val url = value.trim().trimEnd('/')
    if (url.isEmpty()) return ""
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true) || url.toHttpUrlOrNull() == null) {
      throw HttpError(400, "client-url-invalid", "客户端访问地址必须以 http:// 或 https:// 开头")
    }
    return url
  }

  private fun validateAddress(profile: ServerProfile) {
    val url = profile.url
    when {
      url.isBlank() -> throw HttpError(400, "url-empty", "请填写服务器地址")
      url.startsWith("https://", true) -> Unit
      url.startsWith("http://", true) -> if (!profile.allowCleartext) throw HttpError(400, "cleartext", "HTTP 地址需要开启「允许明文连接」")
      else -> throw HttpError(400, "url-invalid", "地址必须以 http:// 或 https:// 开头")
    }
  }

  // ---- Sessions ----

  private fun sessionRoutes(router: Router) {
    router.get("/api/v1/servers/{sid}/sessions") { call ->
      val sid = call.param("sid")
      val page = runtime.run { client(sid).rootSessionsPage(null, call.queryParam("cursor")) }
      call.respond(JSONObject().put("sessions", Codec.array(page.items.map(Codec::session))).put("next", page.next ?: JSONObject.NULL))
    }
    router.post("/api/v1/servers/{sid}/sessions") { call ->
      val sid = call.param("sid")
      val body = call.json(SEND_BODY_LIMIT)
      val directory = body.optString("directory").ifBlank { throw HttpError(400, "directory-required", "先选择项目") }
      val session = runtime.run {
        val created = client(sid).createSession(directory, body.optString("title")).copy(serverId = sid)
        patch(sid) { it.copy(sessions = (listOf(created) + it.sessions).distinctBy { s -> s.id }.sortedWith(sessionActivityOrder)) }
        created
      }
      val prompt = body.optJSONObject("prompt")
      if (prompt != null) send(sid, session, prompt)
      call.respond(JSONObject().put("session", Codec.session(session)))
    }
    router.get("/api/v1/servers/{sid}/sessions/{id}") { call ->
      val session = runtime.run { session(call.param("sid"), call.param("id")) }
      call.respond(JSONObject().put("session", Codec.session(session)))
    }
    router.get("/api/v1/servers/{sid}/sessions/{id}/messages") { call ->
      val sid = call.param("sid"); val id = call.param("id")
      val cursor = call.queryParam("cursor") ?: throw HttpError(400, "cursor-required", "缺少游标")
      val (messages, next) = runtime.run {
        val client = client(sid)
        val first = client.messagesPage(id, cursor, OLDER_PAGE)
        widenOlderToTurn(first.items, first.next, 4) { from -> client.messagesPage(id, from, OLDER_PAGE).let { it.items to it.next } }
      }
      call.respond(JSONObject().put("messages", Codec.array(messages.map(Codec::message))).put("cursor", next ?: JSONObject.NULL))
    }
    router.post("/api/v1/servers/{sid}/sessions/{id}/send") { call ->
      val sid = call.param("sid")
      val body = call.json(SEND_BODY_LIMIT)
      val session = runtime.run { session(sid, call.param("id")) }
      call.respond(JSONObject().put("id", send(sid, session, body)))
    }
    sessionAction(router, "abort") { client, session, _ -> client.abort(session) }
    sessionAction(router, "compact") { client, session, _ -> client.compact(session) }
    sessionAction(router, "unrevert") { client, session, sid ->
      client.unrevert(session)
      runtime.patch(sid) { s -> s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(revertMessageId = null) else it }) }
    }
    router.post("/api/v1/servers/{sid}/sessions/{id}/fork") { call ->
      val sid = call.param("sid")
      val fork = runtime.run {
        val forked = client(sid).forkSession(session(sid, call.param("id"))).copy(serverId = sid)
        patch(sid) { it.copy(sessions = (listOf(forked) + it.sessions).distinctBy { s -> s.id }) }
        forked
      }
      call.respond(JSONObject().put("session", Codec.session(fork)))
    }
    router.post("/api/v1/servers/{sid}/sessions/{id}/rename") { call ->
      val title = call.json().optString("title").trim()
      if (title.isEmpty()) throw HttpError(400, "title-empty", "标题不能为空")
      withSession(call) { client, session, sid ->
        client.renameSession(session, title)
        runtime.patch(sid) { s -> s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(title = title) else it }) }
      }
      call.ok()
    }
    router.post("/api/v1/servers/{sid}/sessions/{id}/archive") { call ->
      val archived = call.json().optBoolean("archived", true)
      withSession(call) { client, session, sid ->
        client.setArchived(session, archived)
        runtime.patch(sid) { s -> s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(archived = archived) else it }) }
      }
      call.ok()
    }
    router.post("/api/v1/servers/{sid}/sessions/{id}/revert") { call ->
      val messageId = call.json().optString("messageId").ifBlank { throw HttpError(400, "message-required", "缺少消息") }
      withSession(call) { client, session, sid ->
        client.revert(session, messageId)
        runtime.patch(sid) { s -> s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(revertMessageId = messageId) else it }) }
      }
      call.ok()
    }
    router.post("/api/v1/servers/{sid}/sessions/{id}/view") { call ->
      runtime.run { acknowledge(call.param("sid"), call.param("id")) }
      call.ok()
    }
    router.delete("/api/v1/servers/{sid}/sessions/{id}") { call ->
      withSession(call) { client, session, sid ->
        client.deleteSession(session)
        store.forgetSession(sid, session.id)
        runtime.patch(sid) { s -> s.copy(sessions = s.sessions.filterNot { it.id == session.id }, tasks = s.tasks - session.id) }
      }
      call.ok()
    }
    router.get("/api/v1/servers/{sid}/sessions/{id}/children") { call ->
      val sid = call.param("sid")
      // Discover the whole descendant tree (bounded), so finished/historical grandchildren remain
      // available after the direct child closes; the web panel also shows their terminal state.
      val children = runtime.run {
        val id = call.param("id")
        TaskReconciliation.children(client(sid), listOf(session(sid, id)), snapshot(sid)?.parents.orEmpty()).sessions
      }
      call.respond(JSONObject().put("sessions", Codec.array(children.map(Codec::session))))
    }
    router.get("/api/v1/servers/{sid}/shells/{id}/output") { call ->
      val sid = call.param("sid")
      val id = call.param("id")
      val directory = call.queryParam("directory") ?: runtime.snapshot(sid)?.shells?.get(id)?.directory
        ?: throw HttpError(400, "directory-required", "缺少命令目录")
      val cursor = call.queryParam("cursor")?.toLongOrNull()
      val limit = call.queryParam("limit")?.toIntOrNull()?.coerceIn(1, HarnessClient.SHELL_OUTPUT_PAGE)
      val result = runtime.run {
        val client = client(sid)
        val info = client.shellInfo(id, directory)
        val page = client.shellOutput(id, directory, cursor, limit ?: HarnessClient.SHELL_OUTPUT_PAGE)
          ?: throw HttpError(404, "shell-output-unavailable", "该服务器不支持查看后台输出")
        JSONObject().put("output", page.output).put("cursor", page.cursor).put("size", page.size)
          .put("truncated", page.truncated).put("status", info?.status ?: "unknown").put("exit", info?.exit ?: JSONObject.NULL)
      }
      call.respond(result)
    }
    router.delete("/api/v1/servers/{sid}/shells/{id}") { call ->
      val sid = call.param("sid")
      val id = call.param("id")
      val directory = call.queryParam("directory") ?: runtime.snapshot(sid)?.shells?.get(id)?.directory
        ?: throw HttpError(400, "directory-required", "缺少命令目录")
      runtime.run { client(sid).removeShell(id, directory) }
      call.ok()
    }
    router.get("/api/v1/servers/{sid}/sessions/{id}/diff") { call ->
      val changes = runtime.run { val sid = call.param("sid"); client(sid).diff(session(sid, call.param("id"))) }
      call.respond(JSONObject().put("changes", Codec.array(changes.map(Codec::change))))
    }
    router.post("/api/v1/servers/{sid}/permissions/{rid}") { call ->
      val sid = call.param("sid"); val rid = call.param("rid")
      val reply = call.json().optString("reply")
      if (reply !in setOf("once", "always", "reject")) throw HttpError(400, "bad-reply", "无效的授权回复")
      runtime.run {
        val request = snapshot(sid)?.permissions?.firstOrNull { it.id == rid } ?: throw HttpError(409, "request-gone", "权限请求已处理或已变化")
        if (reply == "always" && request.always.isEmpty()) throw HttpError(400, "always-unavailable", "该请求不支持始终允许")
        val client = client(sid)
        try {
          client.replyPermission(request, reply)
        } catch (error: ApiException) {
          // Another client may have answered first; only a request that is still pending is a real failure.
          if (error.status != 404) throw error
          val owner = snapshot(sid)?.sessions?.firstOrNull { it.id == request.sessionId } ?: Session(request.sessionId, request.directory, "", 0)
          if (client.sessionPermissions(owner).any { it.id == request.id }) throw error
        }
        patch(sid) { s -> s.copy(permissions = s.permissions.filterNot { it.id == rid }) }
        permissionSettled(sid, rid)
      }
      call.ok()
    }
    router.post("/api/v1/servers/{sid}/questions/{rid}") { call ->
      val body = call.json()
      val answers = body.optJSONArray("answers") ?: throw HttpError(400, "answers-required", "缺少回答")
      val parsed = (0 until answers.length()).map { i ->
        val item = answers.optJSONArray(i) ?: JSONArray()
        (0 until item.length()).map { item.optString(it) }.filter(String::isNotEmpty)
      }
      answerQuestion(call) { client, request -> client.replyQuestion(request, parsed) }
    }
    router.post("/api/v1/servers/{sid}/questions/{rid}/reject") { call ->
      answerQuestion(call) { client, request -> client.rejectQuestion(request) }
    }
  }

  private fun answerQuestion(call: Call, block: suspend (HarnessClient, com.igng.opencode.lagoon.core.QuestionRequest) -> Unit) {
    val sid = call.param("sid"); val rid = call.param("rid")
    runtime.run {
      val request = snapshot(sid)?.questions?.firstOrNull { it.id == rid } ?: throw HttpError(409, "request-gone", "问题已回答或已变化")
      block(client(sid), request)
      patch(sid) { s -> s.copy(questions = s.questions.filterNot { it.id == rid }) }
    }
    call.ok()
  }

  private fun sessionAction(router: Router, name: String, block: suspend (HarnessClient, Session, String) -> Unit) {
    router.post("/api/v1/servers/{sid}/sessions/{id}/$name") { call ->
      withSession(call, block)
      call.ok()
    }
  }

  private fun withSession(call: Call, block: suspend (HarnessClient, Session, String) -> Unit) {
    val sid = call.param("sid")
    runtime.run { block(client(sid), session(sid, call.param("id")), sid) }
  }

  /**
   * Official composer submission: a client-minted id, shown at once, steering a running turn unless
   * queued; a slash command the server knows runs through its command route. Returns the message id.
   */
  private fun send(sid: String, session: Session, body: JSONObject): String {
    val text = body.optString("text")
    val agent = body.optString("agent").ifBlank { null }
    val model = Codec.modelFrom(body.optJSONObject("model"))
    val delivery = if (body.optString("delivery") == "queue") Delivery.QUEUE else Delivery.STEER
    val references = body.optJSONArray("references")?.let { list ->
      (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull {
        val path = it.optString("path"); if (path.isBlank()) null else FileReference(path, it.optString("mime").ifBlank { referenceMime(path) })
      }
    }.orEmpty()
    val inline = attachments(body.optJSONArray("attachments"))
    if (text.isBlank() && references.isEmpty() && inline.isEmpty()) throw HttpError(400, "empty", "请输入任务")
    return runtime.run {
      val client = client(sid)
      val name = if (text.startsWith('/')) text.substringAfter('/').substringBefore(' ').substringBefore('\n') else ""
      val command = if (name.isNotEmpty()) runCatching { client.commands(session.directory) }.getOrDefault(emptyList()).firstOrNull { it.name == name } else null
      val id = MessageIds.ascending()
      if (command == null && text.isNotBlank()) optimistic(sid, session.id, Message(id, "user", System.currentTimeMillis(),
        listOf(MessagePart("$id:text", "text", text = text)), agent = agent, model = model, queued = delivery == Delivery.QUEUE, type = "user"))
      try {
        if (command != null) client.command(session, command.name, text.substringAfter(' ', ""), agent, model, references, inline, delivery)
        else client.send(session, text, agent, model, references, inline, id, delivery)
      } catch (error: Exception) {
        dropOptimistic(sid, session.id, id)
        throw error
      }
      refresh(sid)
      // Without a live stream the projected turn would never arrive; read it back.
      if (snapshot(sid)?.streamConnected != true) reloadConversation(sid, session.id)
      id
    }
  }

  private fun attachments(list: JSONArray?): List<InlineFile> {
    if (list == null) return emptyList()
    var total = 0L
    return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { item ->
      val name = item.optString("name").ifBlank { "附件" }
      val mime = item.optString("mime")
      val uri = item.optString("uri")
      if (!uri.startsWith("data:")) throw HttpError(400, "attachment-invalid", "附件格式无效")
      val size = (uri.length - uri.indexOf(',') - 1) * 3L / 4
      AttachmentPolicy.rejection(mime, name, size, total)?.let { throw HttpError(400, "attachment-rejected", it) }
      total += size
      InlineFile(name, mime, uri)
    }
  }

  // ---- Projects, files and choices ----

  private fun workspaceRoutes(router: Router) {
    router.get("/api/v1/servers/{sid}/choices") { call ->
      val sid = call.param("sid")
      val directory = call.queryParam("directory").orEmpty()
      val key = "$sid\u0000$directory"
      choicesCache[key]?.takeIf { System.currentTimeMillis() - it.first < CHOICES_TTL_MS }?.let { return@get call.respond(it.second) }
      val json = runtime.run {
        val client = client(sid)
        coroutineScope {
          val agents = async { runCatching { client.agents(directory) }.getOrDefault(emptyList()) }
          val commands = async { runCatching { client.commands(directory) }.getOrDefault(emptyList()) }
          val models = async { runCatching { client.modelCatalog(directory) }.getOrDefault(emptyList()) }
          JSONObject().put("agents", Codec.array(agents.await().map(Codec::agent)))
            .put("commands", Codec.array(commands.await().map(Codec::command)))
            .put("models", Codec.array(models.await().map(Codec::modelInfo)))
        }
      }
      choicesCache[key] = System.currentTimeMillis() to json
      call.respond(json)
    }
    router.get("/api/v1/servers/{sid}/files") { call ->
      val nodes = withDirectory(call) { client, directory, session -> client.files(directory, call.queryParam("path") ?: ".", session) }
      call.respond(JSONObject().put("files", Codec.array(nodes.map(Codec::file))))
    }
    router.get("/api/v1/servers/{sid}/file") { call ->
      val path = call.queryParam("path") ?: throw HttpError(400, "path-required", "缺少文件路径")
      val content = withDirectory(call) { client, directory, session -> client.fileContent(directory, path, session) }
      call.respond(Codec.content(content))
    }
    router.get("/api/v1/servers/{sid}/search") { call ->
      val query = call.queryParam("query")?.trim().orEmpty()
      if (query.length < 2) return@get call.respond(JSONObject().put("results", JSONArray()))
      val results = withDirectory(call) { client, directory, session -> client.searchFiles(directory, query, session) }
      call.respond(JSONObject().put("results", Codec.array(results.distinct())))
    }
    router.get("/api/v1/servers/{sid}/browse") { call ->
      val sid = call.param("sid")
      val result = runtime.run {
        val client = client(sid)
        val path = call.queryParam("path") ?: (runCatching { client.projectAt("") }.getOrNull()?.directory?.takeIf(String::isNotBlank)
          ?: snapshot(sid)?.sessions?.firstOrNull()?.directory ?: "/")
        path to client.files(path, ".", null)
      }
      val entries = result.second.distinctBy { it.path }.sortedWith(compareBy({ it.type != "directory" }, { it.path.lowercase() }))
      call.respond(JSONObject().put("path", result.first).put("entries", Codec.array(entries.map(Codec::file))))
    }
    router.get("/api/v1/servers/{sid}/browse/search") { call ->
      val sid = call.param("sid")
      val path = call.queryParam("path") ?: throw HttpError(400, "path-required", "缺少目录")
      val query = call.queryParam("query")?.trim().orEmpty()
      if (query.length < 2) return@get call.respond(JSONObject().put("results", JSONArray()))
      val results = runtime.run { client(sid).searchFiles(path, query, null) }
      call.respond(JSONObject().put("results", Codec.array(results.distinct())))
    }
    router.post("/api/v1/servers/{sid}/projects") { call ->
      val sid = call.param("sid")
      val directory = call.json().optString("directory").ifBlank { throw HttpError(400, "directory-required", "请选择目录") }
      val project = runtime.run {
        // DSH only lists directories it already has workspaces for, yet starts sessions in any directory.
        val resolved = client(sid).projectAt(directory)
          ?: if (store.profile(sid)?.kind == ServerKind.DSH) Project(normalizedDirectory(directory), directory.trimEnd('/').ifBlank { directory },
            directory.trimEnd('/').substringAfterLast('/').ifBlank { directory })
          else throw HttpError(400, "project-unresolved", "服务器没有解析出这个目录对应的项目")
        val projects = store.addProject(sid, resolved)
        patch(sid) { it.copy(projects = projects.map { p -> p.copy(serverId = sid) }) }
        projects.first()
      }
      call.respond(JSONObject().put("project", Codec.project(project)))
    }
    router.delete("/api/v1/servers/{sid}/projects/{pid}") { call ->
      val sid = call.param("sid")
      val projects = store.removeProject(sid, call.param("pid"))
      runtime.run { patch(sid) { it.copy(projects = projects.map { p -> p.copy(serverId = sid) }) } }
      call.ok()
    }
    router.post("/api/v1/servers/{sid}/read") { call ->
      val sid = call.param("sid")
      val syncServer = call.json().optBoolean("server", false)
      val result = runtime.run {
        val notices = store.viewAllNotices(sid)
        patch(sid) { it.copy(notices = notices) }
        if (!syncServer) return@run JSONObject().put("synced", 0).put("failed", 0)
        val snapshot = snapshot(sid) ?: return@run JSONObject().put("synced", 0).put("failed", 0)
        val client = client(sid)
        var synced = 0; var failed = 0
        snapshot.serverReadCandidates().chunked(4).forEach { batch ->
          coroutineScope { batch.map { session -> async { session to runCatching { client.viewSession(session, session.idle) } } }.awaitAll() }
            .forEach { (session, outcome) ->
              if (outcome.isSuccess) { synced++; patch(sid) { s -> s.copy(sessions = s.sessions.map { if (it.id == session.id && it.idle == session.idle) it.copy(viewed = session.idle) else it }) } }
              else failed++
            }
        }
        JSONObject().put("synced", synced).put("failed", failed)
      }
      call.respond(result)
    }
    router.post("/api/v1/servers/{sid}/refresh") { call ->
      runtime.run { client(call.param("sid")); refresh(call.param("sid")) }
      call.ok()
    }
  }

  private fun <T> withDirectory(call: Call, block: suspend (HarnessClient, String, Session?) -> T): T {
    val sid = call.param("sid")
    val directory = call.queryParam("directory") ?: throw HttpError(400, "directory-required", "先选择项目")
    return runtime.run {
      val session = call.queryParam("session")?.let { session(sid, it) }
      block(client(sid), directory, session)
    }
  }

  // ---- Preferences and push ----

  private fun preferenceRoutes(router: Router) {
    router.get("/api/v1/prefs") { call ->
      call.respond(JSONObject().put("policy", Codec.policy(store.permissionPolicy())).put("refreshMode", store.refreshMode().name).put("ui", store.ui()))
    }
    router.put("/api/v1/prefs") { call ->
      val body = call.json(PREFS_BODY_LIMIT)
      val policy = body.optJSONObject("policy")?.let { PermissionPolicy(it.optBoolean("hide"), it.optBoolean("autoApprove")) }
      val refresh = body.optString("refreshMode").takeIf { it.isNotBlank() }?.let { value -> RefreshMode.entries.firstOrNull { it.name == value } }
      val ui = body.optJSONObject("ui")
      val policyChanged = policy != null && policy != store.permissionPolicy()
      store.savePreferences(policy, refresh, ui)
      if (policyChanged) runtime.run { policyChanged() }
      call.ok()
    }
    router.get("/api/v1/push/key") { call ->
      call.respond(JSONObject().put("enabled", push != null).put("publicKey", push?.vapid?.publicKeyBase64 ?: JSONObject.NULL))
    }
    router.post("/api/v1/push/subscriptions") { call ->
      if (push == null) throw HttpError(409, "push-disabled", "推送未启用")
      val body = call.json()
      val subscription = body.optJSONObject("subscription") ?: throw HttpError(400, "bad-request", "缺少订阅")
      val endpoint = subscription.optString("endpoint")
      val keys = subscription.optJSONObject("keys") ?: JSONObject()
      if (!endpoint.startsWith("https://")) throw HttpError(400, "bad-endpoint", "无效的推送地址")
      val p256dh = keys.optString("p256dh"); val authKey = keys.optString("auth")
      runCatching { P256.publicKey(B64.decode(p256dh)); require(B64.decode(authKey).size == 16) }
        .onFailure { throw HttpError(400, "bad-keys", "无效的推送密钥") }
      store.addSubscription(PushSubscription(endpoint, p256dh, authKey, body.optString("label").take(80).ifBlank { "浏览器" }, System.currentTimeMillis()))
      call.ok()
    }
    router.delete("/api/v1/push/subscriptions") { call ->
      store.removeSubscription(call.json().optString("endpoint"))
      call.ok()
    }
    router.post("/api/v1/push/test") { call ->
      val service = push ?: throw HttpError(409, "push-disabled", "推送未启用")
      val endpoint = call.json().optString("endpoint").ifBlank { null }
      val delivered = service.send(JSONObject().put("title", "Lagoon").put("body", "推送已开启，任务完成和待处理时会通知你").put("url", "/").put("tag", "test").put("kind", "test"), only = endpoint)
      if (delivered == 0) throw HttpError(502, "push-failed", "推送没有送达，请检查中枢能否访问浏览器的推送服务")
      call.respond(JSONObject().put("delivered", delivered))
    }
  }

  companion object {
    /** A message may carry up to 20 MB of attachments, base64 encoded. */
    const val SEND_BODY_LIMIT = 30 * 1024 * 1024
    const val PREFS_BODY_LIMIT = 512 * 1024
    const val OLDER_PAGE = 40
    const val CHOICES_TTL_MS = 30_000L
  }
}
