package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ServerStore internal constructor(private val preferences: SharedPreferences, private val secrets: SharedPreferences,
  private val encryptValue: (String) -> String, private val decryptValue: (String) -> String) {
  constructor(context: Context) : this(context.getSharedPreferences("servers", Context.MODE_PRIVATE),
    context.getSharedPreferences("secrets", Context.MODE_PRIVATE),
    KeystoreCipher("lagoon-server-passwords", legacyAlias = "opencode-lagoon-server-passwords")::encrypt,
    KeystoreCipher("lagoon-server-passwords", legacyAlias = "opencode-lagoon-server-passwords")::decrypt)

  fun profiles(): List<ServerProfile> = try {
    JSONArray(preferences.getString("profiles", "[]")).objects().map {
      val id = it.str("id")
      ServerProfile(id, it.str("name"), it.str("url"), it.str("username"),
        it.optBoolean("autoConnect", true), it.optBoolean("notifications", true), it.optBoolean("allowCleartext", false),
        ServerKind.fromWire(it.str("kind")), DshAuthMode.fromWire(it.str("dshAuth")))
    }
  } catch (error: Exception) {
    Diagnostics.warn("ServerStore", "profiles 解析失败，已忽略全部服务器资料", error)
    emptyList()
  }

  private companion object {
    val taskLock = Any()
  }

  fun taskStates(id: String): Map<String, TaskState> = preferences.all.filterKeys { it.startsWith("taskState:$id:") }
    .mapNotNull { (key, raw) -> runCatching {
      val json = JSONObject(raw as String); val session = key.removePrefix("taskState:$id:")
      session to TaskState(session, TaskPhase.valueOf(json.str("phase")), json.str("detail"), json.optLong("since"),
        json.optLong("finished").takeIf { it > 0 }, json.optLong("activeAt", -1), json.optBoolean("background"))
    }.getOrNull() }.toMap()
  fun taskParents(id: String): Map<String, String> = preferences.all.filterKeys { it.startsWith("taskParent:$id:") }
    .mapKeys { it.key.removePrefix("taskParent:$id:") }.mapValues { it.value as? String ?: "" }.filterValues(String::isNotBlank)
  fun rememberParents(id: String, sessions: List<Session>) {
    val editor = preferences.edit()
    sessions.forEach { session ->
      val key = "taskParent:$id:${session.id}"
      if (session.parentId == null) editor.remove(key) else editor.putString(key, session.parentId)
    }
    editor.apply()
  }
  fun rememberTask(id: String, state: TaskState, parent: String? = null, observedAt: Long = System.currentTimeMillis(), durable: Boolean = false): TaskState = synchronized(taskLock) {
    val key = "taskState:$id:${state.sessionId}"
    val previous = runCatching { JSONObject(preferences.getString(key, "")!!).let { json -> TaskState(state.sessionId, TaskPhase.valueOf(json.str("phase")), json.str("detail"), json.optLong("since"), json.optLong("finished").takeIf { it > 0 }, json.optLong("activeAt", -1), json.optBoolean("background")) } }.getOrNull()
    val lastObserved = preferences.getLong("taskTime:$id:${state.sessionId}", 0)
    if (observedAt < lastObserved) return@synchronized previous ?: state
    val next = if (previous != null && state.active && !previous.active && state.since <= previous.since)
      state.copy(since = observedAt, finishedAt = null) else state
    if (previous != next || parent != preferences.getString("taskParent:$id:${state.sessionId}", null)) {
      val editor = preferences.edit().putString(key, JSONObject().put("phase", next.phase.name).put("detail", next.detail)
        .put("since", next.since).put("finished", next.finishedAt).put("activeAt", next.activeAt).put("background", next.background).toString()).putLong("taskTime:$id:${state.sessionId}", observedAt)
      if (parent != null) editor.putString("taskParent:$id:${state.sessionId}", parent)
      if (previous?.since != next.since || previous?.phase !in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED) && next.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED))
        editor.remove("taskRead:$id:${state.sessionId}")
      if (durable) editor.commit() else editor.apply()
    }
    next
  }
  fun claimNotification(id: String, state: TaskState, detail: String): Boolean = synchronized(taskLock) {
    val key = "notification:$id:${state.sessionId}"
    val signature = "${state.since}:${state.phase}:$detail"
    if (preferences.getString(key, "") == signature) return@synchronized false
    preferences.edit().putString(key, signature).commit()
  }

  /**
   * Sessions whose terminal result the user has already opened. Persisted per server so the island
   * summary stays unread-aware even after navigation or restart.
   */
  fun acknowledgedTasks(id: String): Set<String> {
    val states = taskStates(id)
    return preferences.all.filterKeys { it.startsWith("taskRead:$id:") }.filter { (key, read) ->
      read == "1" || read == states[key.removePrefix("taskRead:$id:")]?.since?.toString()
    }.keys.map { it.removePrefix("taskRead:$id:") }.toSet()
  }

  /**
   * One-time move to the unread ledger: earlier versions persisted finished phases as permanent
   * “已完成/失败” states plus per-session read markers. Drop both; keep only runs this device saw still
   * running (they turn into unread results when found finished), unless they are older than the ledger TTL.
   */
  fun migrateTaskLedger(id: String, now: Long = System.currentTimeMillis()) = synchronized(taskLock) {
    if (preferences.getInt("taskLedgerVersion:$id", 0) >= 2) return@synchronized
    val ttl = 30L * 24 * 60 * 60 * 1000
    val states = taskStates(id)
    val editor = preferences.edit()
    preferences.all.keys.filter { it.startsWith("taskRead:$id:") }.forEach(editor::remove)
    states.values.filter { !it.active || preferences.getLong("taskTime:$id:${it.sessionId}", it.since) < now - ttl }.forEach { task ->
      editor.remove("taskState:$id:${task.sessionId}").remove("taskTime:$id:${task.sessionId}")
    }
    editor.putInt("taskLedgerVersion:$id", 2).commit()
  }

  fun sessionNotices(server: String, now: Long = System.currentTimeMillis()): List<SessionNotice> = synchronized(taskLock) {
    runCatching { JSONArray(preferences.getString("sessionNotices:$server", "[]")).objects().map {
      SessionNotice(it.str("id"), it.str("session"), it.optLong("time"), it.optBoolean("error"), it.optBoolean("viewed"))
    }.pruned(now) }.getOrDefault(emptyList())
  }
  private fun writeNotices(server: String, notices: List<SessionNotice>) {
    preferences.edit().putString("sessionNotices:$server", JSONArray().apply { notices.forEach {
      put(JSONObject().put("id", it.id).put("session", it.sessionId).put("time", it.time).put("error", it.error).put("viewed", it.viewed))
    } }.toString()).apply()
  }
  fun rememberNotice(server: String, notice: SessionNotice, now: Long = System.currentTimeMillis()): List<SessionNotice> = synchronized(taskLock) {
    val current = sessionNotices(server, now)
    val next = if (current.any { it.id == notice.id }) current else (current + notice).pruned(now)
    writeNotices(server, next); next
  }
  fun viewNotices(server: String, session: String, ids: Set<String>): List<SessionNotice> = synchronized(taskLock) {
    sessionNotices(server).viewObserved(session, ids).also { writeNotices(server, it) }
  }
  /** Keys of collapsed home sections (今天 / a project / 运行中 …) on this device. */
  fun collapsedSections(server: String): Set<String> = preferences.getStringSet("collapsedSections:$server", emptySet()).orEmpty().toSet()
  fun rememberCollapsedSections(server: String, keys: Set<String>) {
    preferences.edit().putStringSet("collapsedSections:$server", keys.toSet()).apply()
  }
  /** Sessions pinned to the top of the home list. Pinning is this device's choice; the server has no such field. */
  fun pinnedSessions(server: String): Set<String> = preferences.getStringSet("pinned:$server", emptySet()).orEmpty().toSet()
  fun rememberPinnedSessions(server: String, ids: Set<String>) {
    preferences.edit().putStringSet("pinned:$server", ids.toSet()).apply()
  }
  /** 曾在应用处于后台时仍在运行的根会话；回到前台后保留到该轮结束。 */
  fun backgroundRunning(server: String): Set<String> = preferences.getStringSet("backgroundRunning:$server", emptySet()).orEmpty().toSet()
  fun rememberBackgroundRunning(server: String, ids: Set<String>) {
    preferences.edit().putStringSet("backgroundRunning:$server", ids.toSet()).apply()
  }

  /** 自动刷新频率（标准 / 更快）；缺省「更快」。 */
  fun refreshMode(): RefreshMode = runCatching {
    RefreshMode.valueOf(preferences.getString("refreshMode", RefreshMode.FAST.name)!!)
  }.getOrDefault(RefreshMode.FAST)
  fun rememberRefreshMode(mode: RefreshMode) {
    preferences.edit().putString("refreshMode", mode.name).apply()
  }

  fun selectedId(): String? = preferences.getString("selected", null)
  fun homeServerId(): String? = preferences.getString("homeServer", null) ?: selectedId()
  fun rememberHomeServer(id: String) { preferences.edit().putString("homeServer", id).apply() }
  /** The all-servers switch is separate from the last single-server selection. */
  fun permissionPolicy(): PermissionPolicy = PermissionPolicy(preferences.getBoolean("hidePermissions", false), preferences.getBoolean("autoApprovePermissions", false))
  fun rememberPermissionPolicy(policy: PermissionPolicy) {
    preferences.edit().putBoolean("hidePermissions", policy.hide).putBoolean("autoApprovePermissions", policy.autoApprove).apply()
  }
  fun allServers(): Boolean = preferences.getBoolean("allServersMode", false)
  fun rememberAllServers(enabled: Boolean) {
    preferences.edit().putBoolean("allServersMode", enabled).apply()
  }
  fun selectedProject(id: String? = selectedId()): String? = id?.let { preferences.getString("location:$it:project", null)
    ?: preferences.getString("selectedProject", null).takeIf { selectedId() == id } }
  fun selectedSession(id: String? = selectedId()): String? = id?.let { preferences.getString("location:$it:session", null)
    ?: preferences.getString("selectedSession", null).takeIf { selectedId() == id } }

  /** Per-server model switches made on this phone (key = [modelKey]); absent keys follow [ModelVisibility]. */
  fun modelOverrides(id: String): Map<String, Boolean> = runCatching {
    val json = JSONObject(preferences.getString("modelVisibility:$id", "{}").orEmpty())
    json.keys().asSequence().associateWith { json.optBoolean(it) }
  }.getOrDefault(emptyMap())
  /** Whether this server has an explicit model allow-list.  An empty map alone cannot represent that state. */
  fun modelVisibilityConfigured(id: String): Boolean =
    if (preferences.contains("modelVisibilityConfigured:$id")) preferences.getBoolean("modelVisibilityConfigured:$id", false)
    else preferences.contains("modelVisibility:$id")
  fun rememberModelOverrides(id: String, overrides: Map<String, Boolean>) {
    val json = JSONObject().apply { overrides.forEach { (key, visible) -> put(key, visible) } }
    preferences.edit().putString("modelVisibility:$id", json.toString()).putBoolean("modelVisibilityConfigured:$id", true).apply()
  }
  fun rememberModelOverride(id: String, key: String, visible: Boolean) {
    rememberModelOverrides(id, modelOverrides(id) + (key to visible))
  }
  /** Clears the local allow-list; the next catalog read exposes every server model again. */
  fun clearModelVisibility(id: String) {
    preferences.edit().remove("modelVisibility:$id").remove("modelVisibilityConfigured:$id").apply()
  }
  /** Local variant selection, keyed by provider/model.  Missing keys mean the server default. */
  fun modelVariants(id: String): Map<String, String> = runCatching {
    val json = JSONObject(preferences.getString("modelVariants:$id", "{}").orEmpty())
    json.keys().asSequence().mapNotNull { key -> json.optString(key).takeIf(String::isNotBlank)?.let { key to it } }.toMap()
  }.getOrDefault(emptyMap())
  fun rememberModelVariant(id: String, key: String, variant: String?) {
    val json = JSONObject(preferences.getString("modelVariants:$id", "{}").orEmpty())
    if (variant.isNullOrBlank()) json.remove(key) else json.put(key, variant)
    preferences.edit().putString("modelVariants:$id", json.toString()).apply()
  }
  /** Most recently chosen models first, at most five like the official selector. */
  fun recentModels(id: String): List<String> = runCatching {
    val json = JSONArray(preferences.getString("recentModels:$id", "[]"))
    (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
  }.getOrDefault(emptyList())
  fun rememberRecentModel(id: String, key: String) {
    val next = (listOf(key) + recentModels(id).filterNot { it == key }).take(5)
    preferences.edit().putString("recentModels:$id", JSONArray(next).toString()).apply()
  }

  /** Home list scope per server: a project id, or null for 全部会话 (the default). */
  fun scope(id: String): String? = preferences.getString("scope:$id", null)?.takeIf(String::isNotBlank)
  fun rememberScope(id: String, project: String?) {
    preferences.edit().putString("scope:$id", project.orEmpty()).apply()
  }

  /**
   * Project directories registered on this device, newest first. The server's own project list is
   * never imported automatically: the user adds a project by resolving a remote directory.
   */
  fun localProjects(id: String): List<Project> = runCatching {
    JSONArray(preferences.getString("directories:$id", "[]")).objects().mapNotNull { item ->
      val directory = item.str("directory").ifBlank { item.str("path") }
      if (directory.isBlank()) null else Project(item.str("id").ifBlank { normalizedDirectory(directory) }, directory,
        item.str("name").ifBlank { directory.trimEnd('/').substringAfterLast('/').ifBlank { directory } },
        item.arr("sandboxes").strings())
    }
  }.getOrDefault(emptyList())
  fun rememberLocalProjects(id: String, projects: List<Project>) {
    preferences.edit().putString("directories:$id", JSONArray().apply {
      projects.forEach { put(JSONObject().put("id", it.id).put("directory", it.directory).put("name", it.name).put("sandboxes", JSONArray(it.sandboxes))) }
    }.toString()).apply()
  }
  /**Registers a server-resolved project, replacing any entry with the same canonical directory. */
  fun addLocalProject(id: String, project: Project): List<Project> {
    val canonical = normalizedDirectory(project.directory)
    val next = (listOf(project.copy(serverId = id)) + localProjects(id).filterNot { normalizedDirectory(it.directory) == canonical }).take(50)
    rememberLocalProjects(id, next)
    return next.map { it.copy(serverId = id) }
  }
  fun removeLocalProject(id: String, projectId: String): List<Project> {
    val next = localProjects(id).filterNot { it.id == projectId }
    rememberLocalProjects(id, next)
    return next.map { it.copy(serverId = id) }
  }
  /**
   * One-time reset to device-local projects. Earlier releases auto-imported the server's project list
   * and persisted the selection; drop only that local selection, never any server data.
   */
  fun migrateLocalProjects() {
    if (preferences.getInt("projectCatalogVersion", 0) >= 2) return
    val editor = preferences.edit()
    preferences.all.keys.filter { it.startsWith("location:") && it.endsWith(":project") }.forEach(editor::remove)
    preferences.all.keys.filter { it.startsWith("scope:") }.forEach(editor::remove)
    editor.remove("selectedProject").putInt("projectCatalogVersion", 2).apply()
  }

  fun sessionPreview(server: String, session: String): SessionPreview = runCatching {
    val raw = secrets.getString("preview:$server:$session", null)?.let(decryptValue)
      ?: preferences.getString("preview:$server:$session", "{}").orEmpty()
    val value = JSONObject(raw)
    SessionPreview(SessionContent.valueOf(value.str("content").ifBlank { "UNKNOWN" }), value.str("text"))
  }.getOrDefault(SessionPreview())
  fun rememberPreview(server: String, session: String, preview: SessionPreview) {
    runCatching {
      val value = JSONObject().put("content", preview.content.name).put("text", preview.text).toString()
      secrets.edit().putString("preview:$server:$session", encryptValue(value)).apply()
      preferences.edit().remove("preview:$server:$session").apply()
    }.onFailure { Diagnostics.warn("ServerStore", "会话摘要缓存暂不可用") }
  }
  fun configuration(server: String, session: String): SessionConfiguration = runCatching {
    val value = JSONObject(preferences.getString("configuration:$server:$session", "{}").orEmpty())
    SessionConfiguration(value.str("agent").ifBlank { null }, value.obj("model").toModelChoice(), value.optBoolean("agentChanged"), value.optBoolean("modelChanged"))
  }.getOrDefault(SessionConfiguration())
  fun rememberConfiguration(server: String, session: String, configuration: SessionConfiguration) {
    val model = configuration.model?.let { JSONObject().put("providerID", it.providerId).put("modelID", it.modelId).put("name", it.label)
      .apply { it.variant?.let { variant -> put("variant", variant) } } }
    preferences.edit().putString("configuration:$server:$session", JSONObject().put("agent", configuration.agent).put("model", model).put("agentChanged", configuration.agentChanged).put("modelChanged", configuration.modelChanged).toString()).apply()
  }
  fun references(server: String, session: String): List<FileReference> = runCatching {
    val raw = secrets.getString("references:$server:$session", null)?.let(decryptValue) ?: "[]"
    JSONArray(raw).objects().map { FileReference(it.str("path"), it.str("mime")) }
  }.getOrDefault(emptyList())
  fun rememberReferences(server: String, session: String, references: List<FileReference>) {
    if (references.isEmpty()) secrets.edit().remove("references:$server:$session").apply()
    else secrets.edit().putString("references:$server:$session", encryptValue(JSONArray().apply { references.forEach { put(JSONObject().put("path", it.path).put("mime", it.mime)) } }.toString())).apply()
  }
  fun draftSessionIds(server: String): Set<String> = secrets.all.keys.filter { it.startsWith("draft:$server:") }.map { it.removePrefix("draft:$server:") }.toSet()
  fun draft(server: String, session: String): String = runCatching {
    secrets.getString("draft:$server:$session", null)?.let(decryptValue).orEmpty()
  }.getOrDefault("")
  fun rememberDraft(server: String, session: String, text: String) {
    val key = "draft:$server:$session"
    if (text.isBlank()) secrets.edit().remove(key).apply()
    else secrets.edit().putString(key, encryptValue(text.take(100_000))).apply()
  }
  fun forgetSession(server: String, session: String) {
    synchronized(taskLock) { writeNotices(server, sessionNotices(server).filterNot { it.sessionId == session }) }
    val editor = preferences.edit()
    listOf("preview", "configuration", "taskRead", "taskState", "taskParent", "taskTime", "notification").forEach { editor.remove("$it:$server:$session") }
    editor.apply()
    secrets.edit().remove("draft:$server:$session").remove("references:$server:$session").remove("preview:$server:$session").apply()
  }
  fun deviceId(): String = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }

  fun credentials(id: String): ServerCredentials {
    val profile = profiles().firstOrNull { it.id == id }
    val fallback = profile?.username ?: "opencode"
    return try {
      val raw = decrypt(id)
      when {
        raw == null -> ServerCredentials(fallback)
        !raw.trimStart().startsWith("{") -> {
          // 旧版明文凭据没有 origin 绑定：读取时迁移为 JSON（绑定当前地址），之后改地址会像新格式一样拒绝旧凭据。
          if (profile != null) {
            val payload = JSONObject().put("origin", credentialOrigin(profile.url)).put("username", fallback)
              .put("password", raw).put("cookie", "")
            runCatching { secrets.edit().putString(id, encryptValue(payload.toString())).apply() }
              .onFailure { Diagnostics.warn("ServerStore", "旧凭据迁移失败，保留原格式") }
          }
          ServerCredentials(fallback, raw)
        }
        else -> {
          val json = JSONObject(raw)
          if (json.has("origin") && (profile == null || json.str("origin") != credentialOrigin(profile.url))) return ServerCredentials(fallback)
          ServerCredentials(
            username = json.str("username").ifBlank { fallback },
            password = json.str("password"),
            cookie = json.str("cookie")
          )
        }
      }
    } catch (error: Exception) {
      Diagnostics.warn("ServerStore", "凭据解密失败，回退到用户名", error)
      ServerCredentials(fallback)
    }
  }

  fun select(id: String, project: String? = null, session: String? = null) {
    preferences.edit().putString("selected", id).putString("selectedProject", project).putString("selectedSession", session)
      .putString("location:$id:project", project).putString("location:$id:session", session).apply()
  }
  fun rememberLocation(project: String?, session: String?) {
    val id = selectedId() ?: return
    preferences.edit().putString("selectedProject", project).putString("selectedSession", session)
      .putString("location:$id:project", project).putString("location:$id:session", session).apply()
  }

  fun save(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null) {
    val previousProfile = profiles().firstOrNull { it.id == profile.id }
    val previousCredentials = credentials(profile.id)
    val next = profileCredentials(previousProfile?.url, profile.url, previousCredentials,
      credentialUsername ?: profile.username, password, cookie)
    // Encrypt everything before mutating either store. Credentials carry their own origin, so a
    // crash between these two preference commits can only cause a missing login, never a leak.
    val encoded = if (next.password.isEmpty() && next.cookie.isEmpty()) null else encryptValue(JSONObject()
      .put("origin", credentialOrigin(profile.url)).put("username", next.username).put("password", next.password).put("cookie", next.cookie).toString())
    val updated = profiles().filterNot { it.id == profile.id } + profile
    val json = JSONArray().apply { updated.forEach { item -> put(JSONObject()
      .put("id", item.id).put("name", item.name).put("url", item.url).put("username", item.username)
      .put("autoConnect", item.autoConnect).put("notifications", item.notifications)
      .put("allowCleartext", item.allowCleartext).put("kind", item.kind.wire).put("dshAuth", item.dshAuth.wire)) } }
    check(secrets.edit().putString(profile.id, encoded).commit()) { "无法保存凭据，请重试" }
    check(preferences.edit().putString("profiles", json.toString()).commit()) { "无法保存服务器资料，请重试" }
  }
  /**
   * 只更新已保存凭据里的会话 Cookie（DSH 换到新 Cookie 或旧 Cookie 被拒）。[url] 是签发该 Cookie
   * 时的服务器地址：资料已改到别的地址时丢弃，绝不把旧地址的 Cookie 绑到新地址上。
   */
  fun rememberCookie(id: String, url: String, cookie: String) {
    val profile = profiles().firstOrNull { it.id == id } ?: return
    if (profile.url != url) return
    val current = credentials(id)
    if (current.cookie == cookie) return
    val encoded = if (current.password.isEmpty() && cookie.isEmpty()) null else encryptValue(JSONObject()
      .put("origin", credentialOrigin(profile.url)).put("username", current.username).put("password", current.password).put("cookie", cookie).toString())
    secrets.edit().putString(id, encoded).apply()
  }
  private fun decrypt(id: String): String? = try {
    val value = secrets.getString(id, null) ?: return null
    decryptValue(value)
  } catch (_: Exception) { null }
  fun delete(id: String) {
    val json = JSONArray().apply { profiles().filterNot { it.id == id }.forEach { item ->
      put(JSONObject().put("id", item.id).put("name", item.name).put("url", item.url)
        .put("username", item.username).put("autoConnect", item.autoConnect)
        .put("notifications", item.notifications)
        .put("allowCleartext", item.allowCleartext).put("kind", item.kind.wire).put("dshAuth", item.dshAuth.wire))
    } }
    val editor = preferences.edit().putString("profiles", json.toString()).remove("directories:$id").remove("sessionNotices:$id").remove("collapsedSections:$id").remove("collapsedProjects:$id").remove("pinned:$id")
      .remove("backgroundRunning:$id")
      .remove("modelVisibility:$id").remove("modelVisibilityConfigured:$id").remove("modelVariants:$id").remove("recentModels:$id").remove("scope:$id")
    val prefixes = listOf("location", "preview", "configuration", "taskRead", "taskState", "taskParent", "taskTime", "notification", "taskLedgerVersion").map { "$it:$id:" }
    preferences.all.keys.filter { key -> prefixes.any(key::startsWith) }.forEach(editor::remove)
    editor.apply()
    val encrypted = secrets.edit().remove(id)
    val secretPrefixes = listOf("draft", "references", "preview").map { "$it:$id:" }
    secrets.all.keys.filter { key -> secretPrefixes.any(key::startsWith) }.forEach(encrypted::remove)
    encrypted.apply()
    if (selectedId() == id) select("", null, null)
  }
}
