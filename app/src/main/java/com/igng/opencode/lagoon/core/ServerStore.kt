package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-Harness 运行态与偏好持久化存储（项目目录、草稿、文件引用、会话摘要、任务账本、
 * 未读通知、置顶/折叠、模型偏好、默认新建会话配置与全局开关）。
 *
 * 设备与 Harness 的权威配置归属 [DeviceStore]，本类不再持久化连接资料或直连 HTTP 凭据。
 */
class ServerStore internal constructor(
  private val preferences: SharedPreferences,
  private val secrets: SharedPreferences,
  private val encryptValue: (String) -> String,
  private val decryptValue: (String) -> String,
  private val readProfiles: (() -> List<ServerProfile>)? = null
) : OverviewStore {
  init {
    if (preferences.getInt(PREVIEW_FORMAT_VERSION_KEY, 0) != PREVIEW_FORMAT_VERSION) {
      val plain = preferences.edit()
      preferences.all.keys.filter { it.startsWith("preview:") }.forEach(plain::remove)
      plain.putInt(PREVIEW_FORMAT_VERSION_KEY, PREVIEW_FORMAT_VERSION).apply()
      val encrypted = secrets.edit()
      secrets.all.keys.filter { it.startsWith("preview:") }.forEach(encrypted::remove)
      encrypted.apply()
    }
  }

  constructor(context: Context, deviceStore: DeviceStore? = null) : this(
    context.getSharedPreferences("servers", Context.MODE_PRIVATE),
    context.getSharedPreferences("secrets", Context.MODE_PRIVATE),
    KeystoreCipher.also { it.install(context) }.of("lagoon-server-passwords", legacyAlias = "opencode-lagoon-server-passwords")::encrypt,
    KeystoreCipher.of("lagoon-server-passwords", legacyAlias = "opencode-lagoon-server-passwords")::decrypt,
    deviceStore?.let { ds -> { ds.profiles() } }
  )

  private val previewCache = ConcurrentHashMap<String, SessionPreview>()
  private val cachedSessionNotices = ConcurrentHashMap<String, Pair<String, List<SessionNotice>>>()
  private val taskStatesCache = ConcurrentHashMap<String, ConcurrentHashMap<String, TaskState>>()
  private val taskParentsCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
  private val claimedNotificationsCache = ConcurrentHashMap<String, String>()

  private companion object {
    const val PREVIEW_FORMAT_VERSION_KEY = "projectedPreviewFormatVersion"
    const val PREVIEW_FORMAT_VERSION = 3
    const val MIGRATION_NOTICE_KEY = "sshOnlyMigrationNotice"
    val taskLock = Any()
  }

  /** 读取指定 ID 的最新投影资料（由唯一真源 [DeviceStore] 提供）。 */
  internal fun findProfile(id: String): ServerProfile? = readProfiles?.invoke()?.firstOrNull { it.id == id }

  /** 运行时准入检查：验证该 Harness 仍存在且其自身与所属 SSH 设备均处于启用状态。 */
  override fun runtimeProfile(expected: ServerProfile): ServerProfile {
    check(expected.effectiveEnabled) { "此 Harness 或所属设备已停用" }
    val reader = readProfiles ?: return expected
    val current = reader().firstOrNull { it.id == expected.id } ?: error("Harness 已移除")
    check(current.effectiveEnabled) { "此 Harness 或所属设备已停用" }
    check(current.sameRoute(expected)) { "连接路由已变化，旧页面或客户端不能继续操作，请重新连接" }
    return current
  }

  override fun credentials(id: String): ServerCredentials {
    val user = readProfiles?.invoke()?.firstOrNull { it.id == id }?.username ?: "opencode"
    return ServerCredentials(user)
  }

  fun migrationNotice(): String? = preferences.getString(MIGRATION_NOTICE_KEY, null)?.takeIf(String::isNotBlank)

  fun saveMigrationNotice(text: String) {
    preferences.edit().putString(MIGRATION_NOTICE_KEY, text).commit()
  }

  fun dismissMigrationNotice() {
    preferences.edit().remove(MIGRATION_NOTICE_KEY).apply()
  }

  private fun serverTaskStatesMap(id: String): ConcurrentHashMap<String, TaskState> =
    taskStatesCache.computeIfAbsent(id) {
      val prefix = "taskState:$id:"
      val loaded = ConcurrentHashMap<String, TaskState>()
      preferences.all.forEach { (key, raw) ->
        if (!key.startsWith(prefix)) return@forEach
        runCatching {
          val json = JSONObject(raw as String)
          val session = key.removePrefix(prefix)
          if (json.optBoolean("background")) return@runCatching
          loaded[session] = TaskState(
            session,
            TaskPhase.valueOf(json.str("phase")),
            json.str("detail"),
            json.optLong("since"),
            json.optLong("finished").takeIf { it > 0 },
            json.optLong("activeAt", -1),
            json.optBoolean("uncertain"),
            json.optBoolean("pendingInput")
          )
        }
      }
      loaded
    }

  private fun serverTaskParentsMap(id: String): ConcurrentHashMap<String, String> =
    taskParentsCache.computeIfAbsent(id) {
      val prefix = "taskParent:$id:"
      val loaded = ConcurrentHashMap<String, String>()
      preferences.all.forEach { (key, value) ->
        if (!key.startsWith(prefix)) return@forEach
        val parent = (value as? String)?.takeIf(String::isNotBlank) ?: return@forEach
        loaded[key.removePrefix(prefix)] = parent
      }
      loaded
    }

  override fun taskStates(id: String): Map<String, TaskState> = HashMap(serverTaskStatesMap(id))
  override fun taskParents(id: String): Map<String, String> = HashMap(serverTaskParentsMap(id))
  override fun rememberParents(id: String, sessions: List<Session>) {
    val cached = serverTaskParentsMap(id)
    val editor = preferences.edit()
    var mutated = false
    sessions.forEach { session ->
      val key = "taskParent:$id:${session.id}"
      if (session.parentId == null) {
        if (cached.remove(session.id) != null) {
          editor.remove(key)
          mutated = true
        }
      } else if (cached.put(session.id, session.parentId) != session.parentId) {
        editor.putString(key, session.parentId)
        mutated = true
      }
    }
    if (mutated) editor.apply()
  }

  override fun rememberTask(id: String, state: TaskState, parent: String?, observedAt: Long, durable: Boolean): TaskState = synchronized(taskLock) {
    val key = "taskState:$id:${state.sessionId}"
    val observed = if (state.uncertain) state.copy(uncertain = false) else state
    val statesMap = serverTaskStatesMap(id)
    val parentsMap = serverTaskParentsMap(id)
    val previous = statesMap[observed.sessionId]
    val lastObserved = preferences.getLong("taskTime:$id:${observed.sessionId}", 0)
    if (observedAt < lastObserved) return@synchronized previous ?: observed
    val next = if (previous != null && observed.active && !previous.active && observed.since <= previous.since)
      observed.copy(since = observedAt, finishedAt = null) else observed
    val currentParent = parentsMap[state.sessionId]
    if (previous != next || (parent != null && parent != currentParent)) {
      statesMap[observed.sessionId] = next
      val editor = preferences.edit().putString(
        key,
        JSONObject().put("phase", next.phase.name).put("detail", next.detail)
          .put("since", next.since).put("finished", next.finishedAt).put("activeAt", next.activeAt)
          .put("uncertain", next.uncertain).put("pendingInput", next.pendingInput).toString()
      ).putLong("taskTime:$id:${observed.sessionId}", observedAt)
      if (parent != null) {
        parentsMap[state.sessionId] = parent
        editor.putString("taskParent:$id:${state.sessionId}", parent)
      }
      if (previous?.since != next.since || previous?.phase !in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED) && next.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED))
        editor.remove("taskRead:$id:${state.sessionId}")
      if (durable) editor.commit() else editor.apply()
    } else if (observedAt > lastObserved) {
      preferences.edit().putLong("taskTime:$id:${observed.sessionId}", observedAt).let {
        if (durable) it.commit() else it.apply()
      }
    }
    next
  }

  fun claimNotification(id: String, state: TaskState, detail: String): Boolean = synchronized(taskLock) {
    val key = "notification:$id:${state.sessionId}"
    val signature = "${state.since}:${state.phase}:$detail"
    val previous = claimedNotificationsCache[key] ?: preferences.getString(key, "")
    if (previous == signature) {
      claimedNotificationsCache[key] = signature
      return@synchronized false
    }
    claimedNotificationsCache[key] = signature
    preferences.edit().putString(key, signature).apply()
    true
  }

  fun acknowledgedTasks(id: String): Set<String> {
    val states = taskStates(id)
    return preferences.all.filterKeys { it.startsWith("taskRead:$id:") }.filter { (key, read) ->
      read == "1" || read == states[key.removePrefix("taskRead:$id:")]?.since?.toString()
    }.keys.map { it.removePrefix("taskRead:$id:") }.toSet()
  }

  override fun migrateTaskLedger(id: String, now: Long) = synchronized(taskLock) {
    if (preferences.getInt("taskLedgerVersion:$id", 0) >= 2) return@synchronized
    val ttl = 30L * 24 * 60 * 60 * 1000
    val statesMap = serverTaskStatesMap(id)
    val states = HashMap(statesMap)
    val editor = preferences.edit()
    preferences.all.keys.filter { it.startsWith("taskRead:$id:") }.forEach(editor::remove)
    states.values.filter { !it.active || preferences.getLong("taskTime:$id:${it.sessionId}", it.since) < now - ttl }.forEach { task ->
      statesMap.remove(task.sessionId)
      editor.remove("taskState:$id:${task.sessionId}").remove("taskTime:$id:${task.sessionId}")
    }
    editor.putInt("taskLedgerVersion:$id", 2).commit()
  }

  override fun sessionNotices(server: String, now: Long): List<SessionNotice> = synchronized(taskLock) {
    val raw = preferences.getString("sessionNotices:$server", "[]") ?: "[]"
    val decoded = cachedSessionNotices[server]?.takeIf { it.first == raw }?.second ?: runCatching {
      JSONArray(raw).objects().map {
        SessionNotice(it.str("id"), it.str("session"), it.optLong("time"), it.optBoolean("error"), it.optBoolean("viewed"))
      }
    }.getOrDefault(emptyList()).also { cachedSessionNotices[server] = raw to it }
    decoded.pruned(now)
  }

  private fun writeNotices(server: String, notices: List<SessionNotice>) {
    val serialized = JSONArray().apply {
      notices.forEach {
        put(JSONObject().put("id", it.id).put("session", it.sessionId).put("time", it.time).put("error", it.error).put("viewed", it.viewed))
      }
    }.toString()
    cachedSessionNotices[server] = serialized to notices
    preferences.edit().putString("sessionNotices:$server", serialized).apply()
  }

  override fun rememberNotice(server: String, notice: SessionNotice, now: Long): List<SessionNotice> = synchronized(taskLock) {
    val current = sessionNotices(server, now)
    val next = if (current.any { it.id == notice.id }) current else (current + notice).pruned(now)
    writeNotices(server, next); next
  }

  fun viewNotices(server: String, session: String, ids: Set<String>): List<SessionNotice> = synchronized(taskLock) {
    sessionNotices(server).viewObserved(session, ids).also { writeNotices(server, it) }
  }

  fun viewAllNotices(server: String): List<SessionNotice> = synchronized(taskLock) {
    sessionNotices(server).viewAllObserved().also { writeNotices(server, it) }
  }

  fun collapsedSections(server: String): Set<String> = preferences.getStringSet("collapsedSections:$server", emptySet()).orEmpty().toSet()
  fun rememberCollapsedSections(server: String, keys: Set<String>) {
    preferences.edit().putStringSet("collapsedSections:$server", keys.toSet()).apply()
  }

  fun pinnedSessions(server: String): Set<String> = preferences.getStringSet("pinned:$server", emptySet()).orEmpty().toSet()
  fun rememberPinnedSessions(server: String, ids: Set<String>) {
    preferences.edit().putStringSet("pinned:$server", ids.toSet()).apply()
  }

  override fun refreshMode(): RefreshMode = runCatching {
    RefreshMode.valueOf(preferences.getString("refreshMode", RefreshMode.STANDARD.name)!!)
  }.getOrDefault(RefreshMode.STANDARD)

  fun rememberRefreshMode(mode: RefreshMode) {
    preferences.edit().putString("refreshMode", mode.name).apply()
  }

  fun selectedId(): String? = preferences.getString("selected", null)?.takeIf(String::isNotBlank)
  fun homeServerId(): String? = preferences.getString("homeServer", null)?.takeIf(String::isNotBlank) ?: selectedId()
  fun rememberHomeServer(id: String) { preferences.edit().putString("homeServer", id).apply() }

  /** 单设备首页范围（null 表示未聚焦到单台设备）。 */
  fun homeDeviceId(): String? = preferences.getString("homeDevice", null)?.takeIf(String::isNotBlank)
  fun rememberHomeDevice(deviceId: String?) {
    if (deviceId.isNullOrBlank()) preferences.edit().remove("homeDevice").apply()
    else preferences.edit().putString("homeDevice", deviceId).apply()
  }

  override fun permissionPolicy(): PermissionPolicy = PermissionPolicy(
    preferences.getBoolean("hidePermissions", false),
    preferences.getBoolean("autoApprovePermissions", false)
  )

  fun rememberPermissionPolicy(policy: PermissionPolicy) {
    preferences.edit().putBoolean("hidePermissions", policy.hide).putBoolean("autoApprovePermissions", policy.autoApprove).apply()
  }

  fun allServers(): Boolean = preferences.getBoolean("allServersMode", false)
  fun rememberAllServers(enabled: Boolean) {
    preferences.edit().putBoolean("allServersMode", enabled).apply()
  }

  fun selectedProject(id: String? = selectedId()): String? = id?.let {
    preferences.getString("location:$it:project", null)
      ?: preferences.getString("selectedProject", null).takeIf { selectedId() == id }
  }

  fun selectedSession(id: String? = selectedId()): String? = id?.let {
    preferences.getString("location:$it:session", null)
      ?: preferences.getString("selectedSession", null).takeIf { selectedId() == id }
  }

  fun modelOverrides(id: String): Map<String, Boolean> = runCatching {
    val json = JSONObject(preferences.getString("modelVisibility:$id", "{}").orEmpty())
    json.keys().asSequence().associateWith { json.optBoolean(it) }
  }.getOrDefault(emptyMap())

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

  fun clearModelVisibility(id: String) {
    preferences.edit().remove("modelVisibility:$id").remove("modelVisibilityConfigured:$id").apply()
  }

  fun modelVariants(id: String): Map<String, String> = runCatching {
    val json = JSONObject(preferences.getString("modelVariants:$id", "{}").orEmpty())
    json.keys().asSequence().mapNotNull { key -> json.optString(key).takeIf(String::isNotBlank)?.let { key to it } }.toMap()
  }.getOrDefault(emptyMap())

  fun rememberModelVariant(id: String, key: String, variant: String?) {
    val json = JSONObject(preferences.getString("modelVariants:$id", "{}").orEmpty())
    if (variant.isNullOrBlank()) json.remove(key) else json.put(key, variant)
    preferences.edit().putString("modelVariants:$id", json.toString()).apply()
  }

  fun recentModels(id: String): List<String> = runCatching {
    val json = JSONArray(preferences.getString("recentModels:$id", "[]"))
    (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
  }.getOrDefault(emptyList())

  fun rememberRecentModel(id: String, key: String) {
    val next = (listOf(key) + recentModels(id).filterNot { it == key }).take(5)
    preferences.edit().putString("recentModels:$id", JSONArray(next).toString()).apply()
  }

  fun newSessionDefaults(id: String): NewSessionDefaults = runCatching {
    val value = JSONObject(preferences.getString("newSessionDefaults:$id", "{}").orEmpty())
    NewSessionDefaults(
      agent = value.str("agent").ifBlank { null },
      model = value.obj("model").toModelChoice()?.copy(variant = null)
    )
  }.getOrDefault(NewSessionDefaults())

  fun rememberNewSessionDefaults(id: String, defaults: NewSessionDefaults) {
    val model = defaults.model?.let { JSONObject().put("providerID", it.providerId).put("modelID", it.modelId).put("name", it.label) }
    preferences.edit().putString(
      "newSessionDefaults:$id",
      JSONObject().put("agent", defaults.agent).put("model", model).toString()
    ).apply()
  }

  fun scope(id: String): String? = preferences.getString("scope:$id", null)?.takeIf(String::isNotBlank)
  fun rememberScope(id: String, project: String?) {
    preferences.edit().putString("scope:$id", project.orEmpty()).apply()
  }

  override fun localProjects(id: String): List<Project> = runCatching {
    JSONArray(preferences.getString("directories:$id", "[]")).objects().mapNotNull { item ->
      val directory = item.str("directory").ifBlank { item.str("path") }
      if (directory.isBlank()) null else Project(
        item.str("id").ifBlank { normalizedDirectory(directory) }, directory,
        item.str("name").ifBlank { directory.trimEnd('/').substringAfterLast('/').ifBlank { directory } },
        item.arr("sandboxes").strings()
      )
    }
  }.getOrDefault(emptyList())

  fun rememberLocalProjects(id: String, projects: List<Project>) {
    preferences.edit().putString("directories:$id", JSONArray().apply {
      projects.forEach { put(JSONObject().put("id", it.id).put("directory", it.directory).put("name", it.name).put("sandboxes", JSONArray(it.sandboxes))) }
    }.toString()).apply()
  }

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

  fun migrateLocalProjects() {
    if (preferences.getInt("projectCatalogVersion", 0) >= 2) return
    val editor = preferences.edit()
    preferences.all.keys.filter { it.startsWith("location:") && it.endsWith(":project") }.forEach(editor::remove)
    preferences.all.keys.filter { it.startsWith("scope:") }.forEach(editor::remove)
    editor.remove("selectedProject").putInt("projectCatalogVersion", 2).apply()
  }

  fun sessionPreview(server: String, session: String): SessionPreview {
    val cacheKey = "$server\u0000$session"
    previewCache[cacheKey]?.let { return it }
    return runCatching {
      val raw = secrets.getString("preview:$server:$session", null)?.let(decryptValue)
        ?: preferences.getString("preview:$server:$session", "{}").orEmpty()
      val value = JSONObject(raw)
      if (value.optInt("version", -1) != PREVIEW_FORMAT_VERSION) return@runCatching SessionPreview()
      SessionPreview(SessionContent.valueOf(value.str("content").ifBlank { "UNKNOWN" }), value.str("text"))
    }.getOrDefault(SessionPreview()).also { previewCache[cacheKey] = it }
  }

  fun rememberPreview(server: String, session: String, preview: SessionPreview) {
    val cacheKey = "$server\u0000$session"
    if (previewCache[cacheKey] == preview) return
    previewCache[cacheKey] = preview
    runCatching {
      val value = JSONObject().put("version", PREVIEW_FORMAT_VERSION).put("content", preview.content.name).put("text", preview.text).toString()
      secrets.edit().putString("preview:$server:$session", encryptValue(value)).apply()
      preferences.edit().remove("preview:$server:$session").apply()
    }.onFailure { Diagnostics.warn("ServerStore", "会话摘要缓存暂不可用") }
  }

  fun configuration(server: String, session: String): SessionConfiguration = runCatching {
    val value = JSONObject(preferences.getString("configuration:$server:$session", "{}").orEmpty())
    SessionConfiguration(value.str("agent").ifBlank { null }, value.obj("model").toModelChoice(), value.optBoolean("agentChanged"), value.optBoolean("modelChanged"))
  }.getOrDefault(SessionConfiguration())

  fun rememberConfiguration(server: String, session: String, configuration: SessionConfiguration) {
    val model = configuration.model?.let {
      JSONObject().put("providerID", it.providerId).put("modelID", it.modelId).put("name", it.label)
        .apply { it.variant?.let { variant -> put("variant", variant) } }
    }
    preferences.edit().putString(
      "configuration:$server:$session",
      JSONObject().put("agent", configuration.agent).put("model", model)
        .put("agentChanged", configuration.agentChanged).put("modelChanged", configuration.modelChanged).toString()
    ).apply()
  }

  fun references(server: String, session: String): List<FileReference> = runCatching {
    val raw = secrets.getString("references:$server:$session", null)?.let(decryptValue) ?: "[]"
    JSONArray(raw).objects().map { FileReference(it.str("path"), it.str("mime")) }
  }.getOrDefault(emptyList())

  fun rememberReferences(server: String, session: String, references: List<FileReference>) {
    if (references.isEmpty()) secrets.edit().remove("references:$server:$session").apply()
    else secrets.edit().putString(
      "references:$server:$session",
      encryptValue(JSONArray().apply { references.forEach { put(JSONObject().put("path", it.path).put("mime", it.mime)) } }.toString())
    ).apply()
  }

  fun draftSessionIds(server: String): Set<String> =
    secrets.all.keys.filter { it.startsWith("draft:$server:") }.map { it.removePrefix("draft:$server:") }.toSet()

  fun draft(server: String, session: String): String = runCatching {
    secrets.getString("draft:$server:$session", null)?.let(decryptValue).orEmpty()
  }.getOrDefault("")

  fun rememberDraft(server: String, session: String, text: String) {
    val key = "draft:$server:$session"
    if (text.isBlank()) secrets.edit().remove(key).apply()
    else secrets.edit().putString(key, encryptValue(text.take(100_000))).apply()
  }

  override fun forgetTask(server: String, session: String) {
    synchronized(taskLock) {
      taskStatesCache[server]?.remove(session)
      taskParentsCache[server]?.remove(session)
      claimedNotificationsCache.remove("notification:$server:$session")
      writeNotices(server, sessionNotices(server).filterNot { it.sessionId == session })
    }
    val editor = preferences.edit()
    listOf("taskRead", "taskState", "taskParent", "taskTime", "notification").forEach { editor.remove("$it:$server:$session") }
    editor.apply()
  }

  override fun forgetSession(server: String, session: String) {
    forgetTask(server, session)
    previewCache.remove("$server\u0000$session")
    val editor = preferences.edit()
    listOf("preview", "configuration").forEach { editor.remove("$it:$server:$session") }
    editor.apply()
    secrets.edit().remove("draft:$server:$session").remove("references:$server:$session").remove("preview:$server:$session").apply()
  }

  fun deviceId(): String = preferences.getString("deviceId", null)
    ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }

  fun select(id: String, project: String? = null, session: String? = null) {
    preferences.edit().putString("selected", id).putString("selectedProject", project).putString("selectedSession", session)
      .putString("location:$id:project", project).putString("location:$id:session", session).apply()
  }

  fun rememberLocation(project: String?, session: String?) {
    val id = selectedId() ?: return
    preferences.edit().putString("selectedProject", project).putString("selectedSession", session)
      .putString("location:$id:project", project).putString("location:$id:session", session).apply()
  }

  /** 清理一条被移除或下线的 Harness 关联的本地项目、草稿、摘要、账本与模型偏好。 */
  fun cleanupRetiredHarness(id: String) {
    previewCache.keys.removeAll { it.startsWith("$id\u0000") }
    taskStatesCache.remove(id)
    taskParentsCache.remove(id)
    cachedSessionNotices.remove(id)
    claimedNotificationsCache.keys.removeAll { it.startsWith("notification:$id:") }
    val editor = preferences.edit()
      .remove("directories:$id").remove("sessionNotices:$id").remove("collapsedSections:$id")
      .remove("collapsedProjects:$id").remove("pinned:$id").remove("remoteMigration:$id")
      .remove("backgroundRunning:$id").remove("modelVisibility:$id")
      .remove("modelVisibilityConfigured:$id").remove("modelVariants:$id").remove("recentModels:$id")
      .remove("scope:$id").remove("newSessionDefaults:$id").remove("taskLedgerVersion:$id")
    val prefixes = listOf(
      "location", "preview", "configuration", "taskRead", "taskState",
      "taskParent", "taskTime", "notification", "taskLedgerVersion"
    ).map { "$it:$id:" }
    preferences.all.keys.filter { key -> prefixes.any(key::startsWith) }.forEach(editor::remove)
    editor.commit()
    val encrypted = secrets.edit().remove(id)
    val secretPrefixes = listOf("draft", "references", "preview").map { "$it:$id:" }
    secrets.all.keys.filter { key -> secretPrefixes.any(key::startsWith) }.forEach(encrypted::remove)
    encrypted.commit()
    if (selectedId() == id) select("", null, null)
    if (preferences.getString("homeServer", null) == id) preferences.edit().remove("homeServer").apply()
  }
}
