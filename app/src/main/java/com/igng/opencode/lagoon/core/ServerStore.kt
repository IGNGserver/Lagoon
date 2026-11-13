package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ServerStore internal constructor(private val preferences: SharedPreferences, private val secrets: SharedPreferences,
  private val encryptValue: (String) -> String, private val decryptValue: (String) -> String,
  private val appContext: Context? = null,
  private val readConnectionGraph: (() -> ConnectionGraph?)? = null) : OverviewStore, ConnectionRouteStore {
  init {
    if (preferences.getInt(PREVIEW_FORMAT_VERSION_KEY, 0) != PREVIEW_FORMAT_VERSION) {
      val plain = preferences.edit()
      preferences.all.keys.filter { it.startsWith("preview:") }.forEach(plain::remove)
      plain.putInt(PREVIEW_FORMAT_VERSION_KEY, PREVIEW_FORMAT_VERSION).apply()
      val encrypted = secrets.edit()
      secrets.all.keys.filter { it.startsWith("preview:") }.forEach(encrypted::remove)
      encrypted.apply()
    }
    // Configuration writes span the plain and encrypted stores. Recover locally, never by dialing.
    runCatching { synchronized(configurationLock) { recoverConfigurationWrite() } }
      .onFailure { Diagnostics.warn("ServerStore", "配置保存日志无法恢复，保留原资料并暂停认证") }
  }
  internal val remoteDevices: RemoteDeviceStore by lazy {
    AndroidRemoteDeviceStore(requireNotNull(appContext) { "Remote device storage needs Android Context" })
  }
  internal val remotePins: RemotePeerTrustStore by lazy {
    AndroidRemotePeerTrustStore(requireNotNull(appContext) { "Remote Host pins need Android Context" }
      .getSharedPreferences("remote-host-pins", Context.MODE_PRIVATE))
  }
  internal val remoteCatalogStore: RemoteCatalogStore by lazy {
    AndroidRemoteCatalogStore(requireNotNull(appContext) { "Remote Host catalog needs Android Context" })
  }
  internal fun remoteCatalogs(): List<RemoteCatalog> = remoteCatalogStore.load()
  internal fun saveRemoteCatalog(catalog: RemoteCatalog) = remoteCatalogStore.save(catalog)
  internal fun clearRemoteCatalog(origin: String, account: String) = remoteCatalogStore.clear(origin, account)
  constructor(context: Context) : this(
    context.getSharedPreferences("servers", Context.MODE_PRIVATE),
    context.getSharedPreferences("secrets", Context.MODE_PRIVATE),
    KeystoreCipher.of("lagoon-server-passwords", legacyAlias = "opencode-lagoon-server-passwords")::encrypt,
    KeystoreCipher.of("lagoon-server-passwords", legacyAlias = "opencode-lagoon-server-passwords")::decrypt,
    context.applicationContext
  )

  /** Weak identity table: SharedPreferences proxies need not implement equals/hashCode. */
  private class PendingConfigurations {
    private val entries = mutableListOf<Pair<java.lang.ref.WeakReference<SharedPreferences>, String>>()
    private fun prune() { entries.removeAll { it.first.get() == null } }
    operator fun get(key: SharedPreferences): String? { prune(); return entries.firstOrNull { it.first.get() === key }?.second }
    operator fun set(key: SharedPreferences, value: String) { remove(key); entries += java.lang.ref.WeakReference(key) to value }
    fun containsKey(key: SharedPreferences): Boolean = get(key) != null
    fun remove(key: SharedPreferences) { entries.removeAll { it.first.get() == null || it.first.get() === key } }
  }

  private val previewCache = ConcurrentHashMap<String, SessionPreview>()

  /** Runtime view only. Profile writers and graph migration must keep using [readProfiles]. */
  fun profiles(): List<ServerProfile> {
    val raw = try {
      readProfiles()
    } catch (error: Exception) {
      Diagnostics.warn("ServerStore", "profiles 解析失败，保留原记录并停止资料写入", error)
      return emptyList()
    }
    if (hasPendingConfiguration()) return ConnectionRuntimeProjection.apply(raw, graph = null, unavailable = true)
    val graph = try {
      if (readConnectionGraph != null) readConnectionGraph.invoke() else readStoredConnectionGraph()
    } catch (_: Exception) {
      // A present but unreadable/future graph is not the same as an absent graph. Preserve every
      // raw leaf and user preference, but fail closed until the parent gates can be read safely.
      Diagnostics.warn("ServerStore", "connection graph 不可读，保留资料并暂停连接")
      return ConnectionRuntimeProjection.apply(raw, graph = null, unavailable = true)
    }
    return ConnectionRuntimeProjection.apply(raw, graph)
  }

  /** Execution admission is stricter than the display overlay; it never migrates or writes. */
  override fun runtimeProfile(expected: ServerProfile): ServerProfile {
    val (current, route) = admitExpectedRoute(expected)
    return route.executionProfile(current).copy(parentEnabled = true)
  }

  override fun runtimeRoute(expected: ServerProfile): ConnectionRouteSnapshot = admitExpectedRoute(expected).second

  private fun admitExpectedRoute(expected: ServerProfile): Pair<ServerProfile, ConnectionRouteSnapshot> {
    check(expected.effectiveEnabled) { "连接或其所属接入源/主机已停用" }
    check(!hasPendingConfiguration()) { "连接配置保存尚未恢复，已暂停认证，请重试保存" }
    val current = readProfiles().singleOrNull { it.id == expected.id } ?: error("连接资料已移除")
    if (!ConnectionRouteAdmission.sameLeafRoute(current, expected)) {
      throw StaleConnectionRouteException("连接路由已变化，旧页面或客户端不能继续操作，请重新连接")
    }
    val graph = if (readConnectionGraph != null) readConnectionGraph.invoke() else readStoredConnectionGraph()
    return current to ConnectionRouteAdmission.requireCurrent(current, graph)
  }

  /** Saved sources may exist without leaves; discovery still obeys their committed parent gates. */
  internal fun requireParentRoute(sourceId: String, hostId: String): Pair<ConnectionSource, HostBinding> {
    val graph = (if (readConnectionGraph != null) readConnectionGraph.invoke() else readStoredConnectionGraph())
      ?: error("接入源尚未保存到连接图，请重新保存后发现服务")
    val source = graph.sources.singleOrNull { it.id == sourceId } ?: error("接入源已移除")
    val host = graph.hosts.singleOrNull { it.id == hostId && it.sourceId == sourceId } ?: error("主机绑定已移除")
    check(source.enabled && host.enabled) { "接入源或主机已停用，请先启用父级" }
    return source to host
  }

  private fun readStoredConnectionGraph(): ConnectionGraph? {
    val graphPreferences = appContext?.getSharedPreferences("connection-graph", Context.MODE_PRIVATE) ?: return null
    if (!graphPreferences.contains("graph")) return null
    val raw = graphPreferences.getString("graph", null) ?: error("Connection graph is not a string")
    // Do not call ConnectionGraphStore here: runtime reads must not migrate, recover a journal or
    // write anything. Only the committed primary document is authoritative for parent gating.
    return ConnectionGraphCodec.decode(raw)
  }

  /** Raw leaf preferences only. Writers/migration must never replace an unreadable document. */
  internal fun readProfiles(): List<ServerProfile> = decodeProfiles(preferences.getString("profiles", "[]") ?: error("连接资料不是文本"))

  private fun decodeProfiles(raw: String): List<ServerProfile> {
    try {
      val array = JSONArray(raw)
      check(array.length() <= 5000) { "连接资料超过上限" }
      val profiles = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val id = item.getString("id").also { check(it.isNotBlank() && it.length <= 512 && '\u0000' !in it) }
        val kindWire = if (!item.has("kind")) ServerKind.OPENCODE.wire else item.getString("kind")
        ServerProfile(id, item.str("name"), item.str("url"), item.str("username"),
          item.optBoolean("autoConnect", true), item.optBoolean("notifications", true), item.optBoolean("allowCleartext", false),
          MigrationProjection.legacyKind(kindWire),
          DshAuthMode.fromWire(item.str("dshAuth")), item.str("remoteHostId").takeIf(String::isNotBlank),
          item.str("remoteHostFingerprint").takeIf(String::isNotBlank), item.str("sshHostId").takeIf(String::isNotBlank),
          item.optBoolean("enabled", true), item.str("sshServicePath").takeIf(String::isNotBlank),
          parentEnabled = true, remoteServiceId = item.str("remoteServiceId").takeIf(String::isNotBlank),
          protocolId = MigrationProjection.storedProtocol(kindWire, item.str("protocolId").takeIf(String::isNotBlank)))
      }
      check(profiles.distinctBy { it.id }.size == profiles.size) { "连接资料身份重复" }
      return profiles
    } catch (error: Exception) {
      throw java.io.IOException("连接资料损坏，已停止写入以保护原记录", error)
    }
  }

  private companion object {
    const val PREVIEW_FORMAT_VERSION_KEY = "projectedPreviewFormatVersion"
    const val PREVIEW_FORMAT_VERSION = 3
    val taskLock = Any()
    val configurationLock = Any()
    const val CONFIGURATION_JOURNAL_KEY = "configuration-save-journal-v1"
    // Android commit(false) still changes the in-process map. Retain the encrypted journal until
    // every durability barrier succeeded, even if a failed cleanup already removed its key in RAM.
    val unconfirmedConfigurations = PendingConfigurations()
  }

  override fun taskStates(id: String): Map<String, TaskState> = preferences.all.filterKeys { it.startsWith("taskState:$id:") }
    .mapNotNull { (key, raw) -> runCatching {
      val json = JSONObject(raw as String); val session = key.removePrefix("taskState:$id:")
      // Legacy versions folded background shells/children into a synthetic THINKING task; it is not
      // a server execution claim and must not resurrect a spinner after the upgrade.
      if (json.optBoolean("background")) return@mapNotNull null
      session to TaskState(session, TaskPhase.valueOf(json.str("phase")), json.str("detail"), json.optLong("since"),
        json.optLong("finished").takeIf { it > 0 }, json.optLong("activeAt", -1), json.optBoolean("uncertain"), json.optBoolean("pendingInput"))
    }.getOrNull() }.toMap()
  override fun taskParents(id: String): Map<String, String> = preferences.all.filterKeys { it.startsWith("taskParent:$id:") }
    .mapKeys { it.key.removePrefix("taskParent:$id:") }.mapValues { it.value as? String ?: "" }.filterValues(String::isNotBlank)
  override fun rememberParents(id: String, sessions: List<Session>) {
    val editor = preferences.edit()
    sessions.forEach { session ->
      val key = "taskParent:$id:${session.id}"
      if (session.parentId == null) editor.remove(key) else editor.putString(key, session.parentId)
    }
    editor.apply()
  }
  override fun rememberTask(id: String, state: TaskState, parent: String?, observedAt: Long, durable: Boolean): TaskState = synchronized(taskLock) {
    val key = "taskState:$id:${state.sessionId}"
    // `uncertain` describes one failed read, not the task: it is re-derived on every start, so the
    // durable record keeps only what this device actually observed (running / waiting / finished).
    val observed = if (state.uncertain) state.copy(uncertain = false) else state
    val previous = runCatching { JSONObject(preferences.getString(key, "")!!).let { json -> TaskState(observed.sessionId, TaskPhase.valueOf(json.str("phase")), json.str("detail"), json.optLong("since"), json.optLong("finished").takeIf { it > 0 }, json.optLong("activeAt", -1), json.optBoolean("uncertain"), json.optBoolean("pendingInput")) } }.getOrNull()
    val lastObserved = preferences.getLong("taskTime:$id:${observed.sessionId}", 0)
    if (observedAt < lastObserved) return@synchronized previous ?: observed
    val next = if (previous != null && observed.active && !previous.active && observed.since <= previous.since)
      observed.copy(since = observedAt, finishedAt = null) else observed
    if (previous != next || parent != preferences.getString("taskParent:$id:${state.sessionId}", null)) {
      val editor = preferences.edit().putString(key, JSONObject().put("phase", next.phase.name).put("detail", next.detail)
        .put("since", next.since).put("finished", next.finishedAt).put("activeAt", next.activeAt).put("uncertain", next.uncertain).put("pendingInput", next.pendingInput).toString()).putLong("taskTime:$id:${state.sessionId}", observedAt)
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
  override fun migrateTaskLedger(id: String, now: Long) = synchronized(taskLock) {
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

  override fun sessionNotices(server: String, now: Long): List<SessionNotice> = synchronized(taskLock) {
    runCatching { JSONArray(preferences.getString("sessionNotices:$server", "[]")).objects().map {
      SessionNotice(it.str("id"), it.str("session"), it.optLong("time"), it.optBoolean("error"), it.optBoolean("viewed"))
    }.pruned(now) }.getOrDefault(emptyList())
  }
  private fun writeNotices(server: String, notices: List<SessionNotice>) {
    preferences.edit().putString("sessionNotices:$server", JSONArray().apply { notices.forEach {
      put(JSONObject().put("id", it.id).put("session", it.sessionId).put("time", it.time).put("error", it.error).put("viewed", it.viewed))
    } }.toString()).apply()
  }
  override fun rememberNotice(server: String, notice: SessionNotice, now: Long): List<SessionNotice> = synchronized(taskLock) {
    val current = sessionNotices(server, now)
    val next = if (current.any { it.id == notice.id }) current else (current + notice).pruned(now)
    writeNotices(server, next); next
  }
  fun viewNotices(server: String, session: String, ids: Set<String>): List<SessionNotice> = synchronized(taskLock) {
    sessionNotices(server).viewObserved(session, ids).also { writeNotices(server, it) }
  }
  /** Atomically marks the server's whole result ledger viewed; results arriving afterwards stay unread. */
  fun viewAllNotices(server: String): List<SessionNotice> = synchronized(taskLock) {
    sessionNotices(server).viewAllObserved().also { writeNotices(server, it) }
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
  /** 自动刷新频率（标准 / 更快）；缺省「更快」。 */
  override fun refreshMode(): RefreshMode = runCatching {
    RefreshMode.valueOf(preferences.getString("refreshMode", RefreshMode.STANDARD.name)!!)
  }.getOrDefault(RefreshMode.STANDARD)
  fun rememberRefreshMode(mode: RefreshMode) {
    preferences.edit().putString("refreshMode", mode.name).apply()
  }

  fun selectedId(): String? = preferences.getString("selected", null)
  fun homeServerId(): String? = preferences.getString("homeServer", null) ?: selectedId()
  fun rememberHomeServer(id: String) { preferences.edit().putString("homeServer", id).apply() }
  /** The all-servers switch is separate from the last single-server selection. */
  override fun permissionPolicy(): PermissionPolicy = PermissionPolicy(preferences.getBoolean("hidePermissions", false), preferences.getBoolean("autoApprovePermissions", false))
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
  /**
   * The blank "new session" defaults configured on this phone.  The model variant is deliberately
   * not stored here: it is read from [modelVariants], the same per-model memory the composer writes,
   * so changing the default strength never leaves two disagreeing copies.
   */
  fun newSessionDefaults(id: String): NewSessionDefaults = runCatching {
    val value = JSONObject(preferences.getString("newSessionDefaults:$id", "{}").orEmpty())
    NewSessionDefaults(
      agent = value.str("agent").ifBlank { null },
      model = value.obj("model").toModelChoice()?.copy(variant = null)
    )
  }.getOrDefault(NewSessionDefaults())
  fun rememberNewSessionDefaults(id: String, defaults: NewSessionDefaults) {
    val model = defaults.model?.let { JSONObject().put("providerID", it.providerId).put("modelID", it.modelId).put("name", it.label) }
    preferences.edit().putString("newSessionDefaults:$id",
      JSONObject().put("agent", defaults.agent).put("model", model).toString()).apply()
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
  override fun localProjects(id: String): List<Project> = runCatching {
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
  /** Retire activity after an authoritative 404 without destroying unsent composer content. */
  override fun forgetTask(server: String, session: String) {
    synchronized(taskLock) { writeNotices(server, sessionNotices(server).filterNot { it.sessionId == session }) }
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

  fun deviceId(): String = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }

  override fun requireCurrentAuthentication(id: String, captured: ServerCredentials) {
    check(!hasPendingConfiguration()) { "连接配置保存尚未恢复，已暂停认证" }
    check(readProfiles().any { it.id == id }) { "连接资料已移除，旧认证不能继续使用" }
    val current = credentials(id)
    check(current.username == captured.username && current.password == captured.password) {
      "连接认证已变化，请重新连接后操作"
    }
  }

  override fun credentials(id: String): ServerCredentials {
    val profile = profiles().firstOrNull { it.id == id }
    val fallback = profile?.username ?: "opencode"
    if (profile == null) return ServerCredentials(fallback)
    if (hasPendingConfiguration()) return ServerCredentials(fallback)
    // SSH credentials live in the dedicated SSH store. Never try to interpret `ssh://…` as an HTTP origin.
    if (profile.sshHostId != null) return ServerCredentials(fallback)
    return try {
      val raw = decrypt(id)
      when {
        raw == null -> ServerCredentials(fallback)
        !raw.trimStart().startsWith("{") -> {
          // Legacy material is read-only here. Only an explicit journaled save may bind/replace it.
          ServerCredentials(fallback, raw)
        }
        else -> {
          val json = JSONObject(raw)
          if (json.has("origin") && json.str("origin") != credentialOrigin(profile.url)) return ServerCredentials(fallback)
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

  private fun hasPendingConfiguration(): Boolean = synchronized(configurationLock) {
    secrets.contains(CONFIGURATION_JOURNAL_KEY) || unconfirmedConfigurations.containsKey(secrets)
  }

  /** Recover only an exact prepared before/after pair; an unrelated current document is never overwritten. */
  private fun recoverConfigurationWrite() {
    if (!hasPendingConfiguration()) return
    fun invalid(): Nothing = throw java.io.IOException("连接配置保存日志不可读，已保留原资料并停止认证")
    val cached = unconfirmedConfigurations[secrets]
    val stored = if (secrets.contains(CONFIGURATION_JOURNAL_KEY)) secrets.getString(CONFIGURATION_JOURNAL_KEY, null) ?: invalid() else null
    if (stored != null && cached != null && stored != cached) invalid()
    val encrypted = stored ?: cached ?: invalid()
    val journal = try {
      val parser = org.json.JSONTokener(decryptValue(encrypted))
      (parser.nextValue() as? JSONObject)?.takeIf { parser.nextClean() == '\u0000' } ?: invalid()
    } catch (_: Exception) { invalid() }
    val keys = setOf("version", "id", "before", "after", "beforeMigration", "afterMigration", "oldSecret", "newSecret", "beforeToken", "afterToken")
    if (journal.keys().asSequence().toSet() != keys || journal.opt("version") != 1) invalid()
    fun text(key: String): String? = when (val value = journal.opt(key)) {
      JSONObject.NULL -> null
      is String -> value
      else -> invalid()
    }
    val id = text("id")?.takeIf { it.isNotBlank() && it.length <= 512 && '\u0000' !in it } ?: invalid()
    val before = text("before")
    val after = text("after") ?: invalid()
    val oldProfiles = decodeProfiles(before ?: "[]")
    val newProfiles = decodeProfiles(after)
    if (newProfiles.count { it.id == id } != 1 || oldProfiles.filterNot { it.id == id } != newProfiles.filterNot { it.id == id }) invalid()
    val migrationKey = "remoteMigration:$id"
    val previousMigration = text("beforeMigration")
    val nextMigration = text("afterMigration")
    val oldSecret = text("oldSecret")
    val newSecret = text("newSecret")
    val current = preferences.getString("profiles", null)
    val currentMigration = preferences.getString(migrationKey, null)
    val slot = secrets.getString(id, null)
    val token = preferences.getString("configuration-save-token", null)
    val beforeToken = text("beforeToken")
    val afterToken = text("afterToken")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: invalid()
    // Android commit(false) can still update memory. Both exact snapshots are valid, no third one is.
    val committed = current == after && currentMigration == nextMigration && token == afterToken
    val unchanged = current == before && currentMigration == previousMigration && token == beforeToken
    if (!committed && !unchanged || slot != oldSecret && slot != newSecret) invalid()
    val desired = if (committed) newSecret else oldSecret
    unconfirmedConfigurations[secrets] = encrypted
    // RAM equality is NOT disk evidence. First durably re-prepare the recovery record, then
    // durably reaffirm the selected plain snapshot; only then may old credentials be retired.
    check(secrets.edit().putString(CONFIGURATION_JOURNAL_KEY, encrypted).commit()) { "配置恢复准备尚未确认，已暂停认证" }
    check(preferences.edit().putString("profiles", if (committed) after else before)
      .putString(migrationKey, if (committed) nextMigration else previousMigration)
      .putString("configuration-save-token", if (committed) afterToken else beforeToken).commit()) {
      "配置恢复的资料落盘未确认，已保留旧凭据和保存日志"
    }
    if (!secrets.edit().putString(id, desired).remove(CONFIGURATION_JOURNAL_KEY).commit())
      throw java.io.IOException("连接配置恢复尚未确认，已保留保存日志并暂停认证")
    unconfirmedConfigurations.remove(secrets)
  }

  /** Preserve malformed-but-decryptable records too; fallback reads are never overwrite authority. */
  private fun requireReadableCredentials(id: String) {
    if (!secrets.contains(id)) return
    val encrypted = secrets.getString(id, null) ?: error("已保存凭据不是文本，已停止覆盖")
    val raw = try { decryptValue(encrypted) } catch (_: Exception) {
      throw java.io.IOException("已保存凭据暂不可读，已停止覆盖并保留原记录")
    }
    if (raw.trimStart().startsWith("{")) try {
      val parser = org.json.JSONTokener(raw)
      val json = parser.nextValue() as? JSONObject ?: error("Credential record is not an object")
      check(parser.nextClean() == '\u0000')
      for (key in listOf("origin", "username", "password", "cookie"))
        check(!json.has(key) || json.opt(key) is String)
    } catch (_: Exception) { throw java.io.IOException("已保存凭据格式损坏，已停止覆盖并保留原记录") }
  }

  fun save(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null) = synchronized(configurationLock) {
    recoverConfigurationWrite()
    val currentProfiles = readProfiles()
    val previousProfile = currentProfiles.firstOrNull { it.id == profile.id }
    if (password == null || cookie == null) requireReadableCredentials(profile.id)
    val previousCredentials = credentials(profile.id)
    val next = profileCredentials(previousProfile?.url, profile.url, previousCredentials,
      credentialUsername ?: profile.username, password, cookie)
    // Prepare both representations before a durable change. Keep the old credential slot until
    // the profile commit is known; an encrypted journal preserves both snapshots across restart.
    // SSH-backed DSH profiles keep their private key/password in the SSH secret store; their URL is
    // a synthetic `ssh://…` identity that must never be parsed as an HTTP credential origin.
    val encoded = if (profile.sshHostId != null || (next.password.isEmpty() && next.cookie.isEmpty())) null else encryptValue(JSONObject()
      .put("origin", credentialOrigin(profile.url)).put("username", next.username).put("password", next.password).put("cookie", next.cookie).toString())
    val updated = currentProfiles.filterNot { it.id == profile.id } + profile
    val json = profilesJson(updated)
    val migration = DshRemoteMigration.plan(profile)
    val migrationKey = "remoteMigration:${profile.id}"
    val migrationValue = migration?.let { JSONObject().put("retained", true).put("reason", it.reason).toString() }
    val before = preferences.getString("profiles", null)
    val transaction = UUID.randomUUID().toString()
    val journal = JSONObject().put("version", 1).put("id", profile.id)
      .put("beforeToken", preferences.getString("configuration-save-token", null) ?: JSONObject.NULL).put("afterToken", transaction)
      .put("before", before ?: JSONObject.NULL).put("after", json)
      .put("beforeMigration", preferences.getString(migrationKey, null) ?: JSONObject.NULL)
      .put("afterMigration", migrationValue ?: JSONObject.NULL)
      .put("oldSecret", secrets.getString(profile.id, null) ?: JSONObject.NULL)
      .put("newSecret", encoded ?: JSONObject.NULL)
    val encryptedJournal = encryptValue(journal.toString())
    unconfirmedConfigurations[secrets] = encryptedJournal
    check(secrets.edit().putString(CONFIGURATION_JOURNAL_KEY, encryptedJournal).commit()) { "无法准备配置保存，原凭据已保留" }
    val editor = preferences.edit().putString("profiles", json).putString(migrationKey, migrationValue).putString("configuration-save-token", transaction)
    check(editor.commit()) { "无法保存服务器资料，原凭据已保留，请重试" }
    check(secrets.edit().putString(profile.id, encoded).remove(CONFIGURATION_JOURNAL_KEY).commit()) {
      "配置保存结果尚未确认，已暂停认证；请重启或重试保存以恢复"
    }
    unconfirmedConfigurations.remove(secrets)
  }

  /**
   * Changes only the display name. Credentials, routing, migration notes and every other field stay
   * untouched, so a naming repair can never invalidate a login.
   */
  fun rename(id: String, name: String) = synchronized(configurationLock) {
    recoverConfigurationWrite()
    val updated = readProfiles().map { if (it.id == id) it.copy(name = name) else it }
    check(preferences.edit().putString("profiles", profilesJson(updated)).commit()) { "无法保存服务器资料，请重试" }
  }

  /**
   * Enables or disables one saved connection without touching credentials, caches, drafts or the
   * unread ledger. A no-op when the profile is already in the requested state.
   */
  fun setProfileEnabled(id: String, enabled: Boolean): Boolean = synchronized(configurationLock) {
    recoverConfigurationWrite()
    val current = readProfiles()
    val profile = current.firstOrNull { it.id == id } ?: return@synchronized false
    if (profile.enabled == enabled) return@synchronized true
    val updated = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
    preferences.edit().putString("profiles", profilesJson(updated)).commit()
  }

  /** Updates display routes for all leaves of one saved source without rewriting child preferences or credentials. */
  fun updateSshSourceRoute(hostId: String, url: String, username: String) = synchronized(configurationLock) {
    recoverConfigurationWrite()
    val updated = readProfiles().map { profile ->
      if (profile.sshHostId == hostId) profile.copy(url = url, username = username) else profile
    }
    check(preferences.edit().putString("profiles", profilesJson(updated)).commit()) { "无法保存 SSH source 路由" }
  }

  private fun profilesJson(profiles: List<ServerProfile>): String = JSONArray().apply {
    profiles.forEach { item ->
      put(JSONObject()
        .put("id", item.id).put("name", item.name).put("url", item.url).put("username", item.username)
        .put("autoConnect", item.autoConnect).put("notifications", item.notifications)
        .put("allowCleartext", item.allowCleartext).put("kind", MigrationProjection.kindWire(item)).put("protocolId", item.protocol.wire).put("dshAuth", item.dshAuth.wire)
        .put("enabled", item.enabled)
        .putOpt("remoteHostId", item.remoteHostId).putOpt("remoteHostFingerprint", item.remoteHostFingerprint)
        .putOpt("sshHostId", item.sshHostId).putOpt("sshServicePath", item.sshServicePath)
        .putOpt("remoteServiceId", item.remoteServiceId))
    }
  }.toString()

  /** Direct DSH material stays on its original profile. Null means this profile needs no migration note. */
  fun remoteMigration(id: String): DshRemoteMigration? {
    val profile = profiles().firstOrNull { it.id == id } ?: return null
    val planned = DshRemoteMigration.plan(profile) ?: return null
    val stored = runCatching { JSONObject(preferences.getString("remoteMigration:$id", null) ?: return planned) }.getOrNull()
    return planned.copy(retained = stored?.optBoolean("retained") != false, reason = stored?.optString("reason").orEmpty().ifBlank { planned.reason })
  }
  /**
   * 只更新已保存凭据里的会话 Cookie（DSH 换到新 Cookie 或旧 Cookie 被拒）。[url] 是签发该 Cookie
   * 时的服务器地址：资料已改到别的地址时丢弃，绝不把旧地址的 Cookie 绑到新地址上。
   */
  override fun rememberCookie(id: String, url: String, cookie: String) = synchronized(configurationLock) {
    if (hasPendingConfiguration()) return@synchronized
    val profile = profiles().firstOrNull { it.id == id } ?: return@synchronized
    if (profile.sshHostId != null) return@synchronized
    if (profile.url != url) return@synchronized
    try { requireReadableCredentials(id) } catch (_: Exception) {
      Diagnostics.warn("ServerStore", "旧认证回调未覆盖不可读凭据")
      return@synchronized
    }
    val current = credentials(id)
    if (current.cookie == cookie) return@synchronized
    val encoded = if (current.password.isEmpty() && cookie.isEmpty()) null else encryptValue(JSONObject()
      .put("origin", credentialOrigin(profile.url)).put("username", current.username).put("password", current.password).put("cookie", cookie).toString())
    secrets.edit().putString(id, encoded).apply()
  }
  private fun decrypt(id: String): String? = try {
    val value = secrets.getString(id, null) ?: return null
    decryptValue(value)
  } catch (_: Exception) { null }
  fun delete(id: String) = synchronized(configurationLock) {
    recoverConfigurationWrite()
    val remaining = readProfiles().filterNot { it.id == id }
    previewCache.keys.removeAll { it.startsWith("$id\u0000") }
    val editor = preferences.edit().putString("profiles", profilesJson(remaining))
      .remove("directories:$id").remove("sessionNotices:$id").remove("collapsedSections:$id").remove("collapsedProjects:$id").remove("pinned:$id").remove("remoteMigration:$id")
      .remove("backgroundRunning:$id")
      .remove("modelVisibility:$id").remove("modelVisibilityConfigured:$id").remove("modelVariants:$id").remove("recentModels:$id").remove("scope:$id")
      .remove("newSessionDefaults:$id")
    val prefixes = listOf("location", "preview", "configuration", "taskRead", "taskState", "taskParent", "taskTime", "notification", "taskLedgerVersion").map { "$it:$id:" }
    preferences.all.keys.filter { key -> prefixes.any(key::startsWith) }.forEach(editor::remove)
    check(editor.commit()) { "服务器资料删除未确认，已保留凭据和草稿，请刷新核对" }
    val encrypted = secrets.edit().remove(id)
    val secretPrefixes = listOf("draft", "references", "preview").map { "$it:$id:" }
    secrets.all.keys.filter { key -> secretPrefixes.any(key::startsWith) }.forEach(encrypted::remove)
    check(encrypted.commit()) { "服务器资料已移除，但加密记录清理未确认，请刷新核对" }
    if (selectedId() == id) select("", null, null)
  }
}
