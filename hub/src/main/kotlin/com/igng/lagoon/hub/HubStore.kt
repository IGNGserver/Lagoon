package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.CatalogCache
import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.HubAccessKey
import com.igng.opencode.lagoon.core.OverviewStore
import com.igng.opencode.lagoon.core.PermissionPolicy
import com.igng.opencode.lagoon.core.Project
import com.igng.opencode.lagoon.core.RefreshMode
import com.igng.opencode.lagoon.core.ServerCredentials
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SessionNotice
import com.igng.opencode.lagoon.core.TaskState
import com.igng.opencode.lagoon.core.credentialOrigin
import com.igng.opencode.lagoon.core.normalizedDirectory
import com.igng.opencode.lagoon.core.pruned
import com.igng.opencode.lagoon.core.viewAllObserved
import com.igng.opencode.lagoon.core.viewObserved
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A hashed browser session; the raw token only ever exists in the cookie. */
data class LoginSession(val hash: String, val created: Long, val lastSeen: Long, val label: String)

/** A client access key (its secret sealed) and when and from where it last exported the servers. */
data class ClientKey(val id: String, val sealedSecret: String, val label: String, val created: Long, val lastUsed: Long = 0, val lastAddress: String = "")

/** One browser's Web Push subscription. */
data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String, val label: String, val created: Long)

/**
 * Everything the hub persists, kept in memory and written to the data volume. It mirrors the Android
 * `ServerStore` semantics that [com.igng.opencode.lagoon.core.AllServersOverview] relies on (task
 * ledger with observation times, parent links, the 30-day/500-entry unread-result ledger), so the
 * shared monitor behaves exactly as on the phone. One user, so one ledger shared by every browser.
 *
 * State changes are written about a second later in one atomic replace; profile, credential and
 * login changes are written immediately.
 */
class HubStore(private val dataDir: Path, private val box: SecretBox, private val clock: () -> Long = System::currentTimeMillis) :
  OverviewStore, CatalogCache, AutoCloseable {
  private class StoredTask(val task: TaskState, val observedAt: Long)

  private val lock = Any()
  private val profiles = mutableListOf<ServerProfile>()
  /** Sealed `{origin, username, password, cookie}` per server. */
  private val credentials = mutableMapOf<String, String>()
  /** The address other clients should use for a server when the hub's own one only works inside its container. */
  private val clientUrls = mutableMapOf<String, String>()
  private val clientKeys = mutableMapOf<String, ClientKey>()
  private var instanceId = ""
  private val projects = mutableMapOf<String, List<Project>>()
  private val tasks = mutableMapOf<String, MutableMap<String, StoredTask>>()
  private val parents = mutableMapOf<String, MutableMap<String, String>>()
  private val notices = mutableMapOf<String, List<SessionNotice>>()
  private val catalogs = mutableMapOf<String, Pair<List<Project>, List<Session>>>()
  private var policy = PermissionPolicy()
  private var refresh = RefreshMode.FAST
  private var ui = JSONObject()
  private var passwordHash: String? = null
  private val logins = mutableMapOf<String, LoginSession>()
  private var vapidPublic: String? = null
  private var vapidPrivateSealed: String? = null
  private val subscriptions = mutableListOf<PushSubscription>()

  private val statePath = dataDir.resolve("state.json")
  private val catalogPath = dataDir.resolve("catalog.json")
  private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "hub-store").apply { isDaemon = true } }
  private var pendingState: ScheduledFuture<*>? = null
  private var pendingCatalog: ScheduledFuture<*>? = null

  init {
    Files.createDirectories(dataDir)
    load()
    if (instanceId.isEmpty()) {
      instanceId = UUID.randomUUID().toString()
      writeState()
    }
  }

  /** This hub's stable identity; clients use it to recognize servers they imported from it before. */
  val hubId: String get() = synchronized(lock) { instanceId }

  // ---- Profiles and credentials ----

  fun profiles(): List<ServerProfile> = synchronized(lock) { profiles.toList() }
  fun profile(id: String): ServerProfile? = synchronized(lock) { profiles.firstOrNull { it.id == id } }

  /** Saves [profile] with the credentials that were just verified against it; a null [clientUrl] keeps the current one. */
  fun saveServer(profile: ServerProfile, next: ServerCredentials, clientUrl: String? = null) = synchronized(lock) {
    val index = profiles.indexOfFirst { it.id == profile.id }
    if (index >= 0) profiles[index] = profile else profiles += profile
    clientUrl?.let { if (it.isBlank()) clientUrls.remove(profile.id) else clientUrls[profile.id] = it }
    if (next.password.isEmpty() && next.cookie.isEmpty()) credentials.remove(profile.id)
    else credentials[profile.id] = box.seal(JSONObject().put("origin", credentialOrigin(profile.url)).put("username", next.username)
      .put("password", next.password).put("cookie", next.cookie).toString())
    persistNow()
  }

  fun deleteServer(id: String) = synchronized(lock) {
    profiles.removeAll { it.id == id }
    credentials.remove(id); clientUrls.remove(id); projects.remove(id); tasks.remove(id); parents.remove(id); notices.remove(id); catalogs.remove(id)
    persistNow(); scheduleCatalog()
  }

  override fun credentials(id: String): ServerCredentials = synchronized(lock) {
    val profile = profiles.firstOrNull { it.id == id }
    val fallback = profile?.username?.ifBlank { null } ?: "opencode"
    val sealed = credentials[id] ?: return ServerCredentials(fallback)
    return try {
      val json = JSONObject(box.open(sealed))
      // Credentials are bound to the address they were verified against; an edited address never inherits them.
      if (profile == null || json.optString("origin") != credentialOrigin(profile.url)) ServerCredentials(fallback)
      else ServerCredentials(json.optString("username").ifBlank { fallback }, json.optString("password"), json.optString("cookie"))
    } catch (error: Exception) {
      Diagnostics.warn("HubStore", "凭据解密失败，回退到用户名", error)
      ServerCredentials(fallback)
    }
  }

  fun hasSecret(id: String): Boolean = synchronized(lock) { credentials.containsKey(id) }

  fun clientUrl(id: String): String = synchronized(lock) { clientUrls[id].orEmpty() }

  /** A server as the web client sees it: the profile, whether a secret is stored, and its client address. */
  fun profileJson(profile: ServerProfile): JSONObject = Codec.profile(profile).put("hasSecret", hasSecret(profile.id)).put("clientUrl", clientUrl(profile.id))

  override fun rememberCookie(id: String, url: String, cookie: String) = synchronized(lock) {
    val profile = profiles.firstOrNull { it.id == id } ?: return
    if (profile.url != url) return
    val current = credentials(id)
    if (current.cookie == cookie) return
    if (current.password.isEmpty() && cookie.isEmpty()) credentials.remove(id)
    else credentials[id] = box.seal(JSONObject().put("origin", credentialOrigin(profile.url)).put("username", current.username)
      .put("password", current.password).put("cookie", cookie).toString())
    persistNow()
  }

  // ---- Projects registered per server ----

  override fun localProjects(id: String): List<Project> = synchronized(lock) { projects[id].orEmpty() }

  /** Registers a server-resolved project, replacing any entry with the same canonical directory. */
  fun addProject(id: String, project: Project): List<Project> = synchronized(lock) {
    val canonical = normalizedDirectory(project.directory)
    val next = (listOf(project.copy(serverId = null)) + projects[id].orEmpty().filterNot { normalizedDirectory(it.directory) == canonical }).take(50)
    projects[id] = next
    scheduleState()
    next
  }

  fun removeProject(id: String, projectId: String): List<Project> = synchronized(lock) {
    val next = projects[id].orEmpty().filterNot { it.id == projectId }
    projects[id] = next
    scheduleState()
    next
  }

  // ---- Preferences ----

  override fun permissionPolicy(): PermissionPolicy = synchronized(lock) { policy }
  override fun refreshMode(): RefreshMode = synchronized(lock) { refresh }
  fun ui(): JSONObject = synchronized(lock) { JSONObject(ui.toString()) }

  fun savePreferences(policy: PermissionPolicy?, refresh: RefreshMode?, ui: JSONObject?) = synchronized(lock) {
    policy?.let { this.policy = it }
    refresh?.let { this.refresh = it }
    ui?.let { this.ui = JSONObject(it.toString()) }
    scheduleState()
  }

  // ---- Task and result ledger (ServerStore semantics) ----

  override fun taskStates(id: String): Map<String, TaskState> = synchronized(lock) { tasks[id].orEmpty().mapValues { it.value.task } }
  override fun taskParents(id: String): Map<String, String> = synchronized(lock) { parents[id].orEmpty().toMap() }

  override fun rememberParents(id: String, sessions: List<Session>) = synchronized(lock) {
    val map = parents.getOrPut(id) { mutableMapOf() }
    var changed = false
    sessions.forEach { session ->
      val parent = session.parentId
      if (parent == null) { if (map.remove(session.id) != null) changed = true }
      else if (map.put(session.id, parent) != parent) changed = true
    }
    if (changed) scheduleState()
  }

  override fun rememberTask(id: String, state: TaskState, parent: String?, observedAt: Long, durable: Boolean): TaskState = synchronized(lock) {
    val serverTasks = tasks.getOrPut(id) { mutableMapOf() }
    val previous = serverTasks[state.sessionId]
    if (previous != null && observedAt < previous.observedAt) return previous.task
    // A new run of a session that this ledger last saw idle starts now, not when the stale state began.
    val next = if (previous != null && state.active && !previous.task.active && state.since <= previous.task.since)
      state.copy(since = observedAt, finishedAt = null) else state
    val knownParent = parents[id]?.get(state.sessionId)
    if (previous?.task != next || (parent != null && parent != knownParent)) {
      serverTasks[state.sessionId] = StoredTask(next, observedAt)
      if (parent != null) parents.getOrPut(id) { mutableMapOf() }[state.sessionId] = parent
      scheduleState()
    }
    next
  }

  /** The hub started with the current ledger format; only runs left “active” for 30 days are dropped. */
  override fun migrateTaskLedger(id: String, now: Long) = synchronized(lock) {
    val serverTasks = tasks[id] ?: return
    val before = serverTasks.size
    serverTasks.entries.removeAll { (_, stored) -> stored.observedAt < now - LEDGER_TTL }
    if (serverTasks.size != before) scheduleState()
  }

  override fun sessionNotices(server: String, now: Long): List<SessionNotice> = synchronized(lock) { notices[server].orEmpty().pruned(now) }

  override fun rememberNotice(server: String, notice: SessionNotice, now: Long): List<SessionNotice> = synchronized(lock) {
    val current = sessionNotices(server, now)
    val next = if (current.any { it.id == notice.id }) current else (current + notice.copy(serverId = null)).pruned(now)
    if (next != current) { notices[server] = next; scheduleState() }
    next
  }

  fun viewNotices(server: String, session: String, ids: Set<String>): List<SessionNotice> = synchronized(lock) {
    val next = sessionNotices(server, clock()).viewObserved(session, ids)
    notices[server] = next; scheduleState(); next
  }

  fun viewAllNotices(server: String): List<SessionNotice> = synchronized(lock) {
    val next = sessionNotices(server, clock()).viewAllObserved()
    notices[server] = next; scheduleState(); next
  }

  override fun forgetTask(server: String, session: String) = synchronized(lock) {
    notices[server] = notices[server].orEmpty().filterNot { it.sessionId == session }
    tasks[server]?.remove(session)
    parents[server]?.remove(session)
    scheduleState()
  }

  override fun forgetSession(server: String, session: String) = forgetTask(server, session)

  // ---- Last catalog per server, painted while a cold monitor reads the live one ----

  override fun catalog(serverId: String): Pair<List<Project>, List<Session>>? = synchronized(lock) { catalogs[serverId] }

  fun rememberCatalog(serverId: String, projects: List<Project>, sessions: List<Session>) = synchronized(lock) {
    val next = projects.map { it.copy(serverId = null) } to sessions.take(MAX_CATALOG_SESSIONS).map { it.copy(serverId = null) }
    if (catalogs[serverId] == next) return
    catalogs[serverId] = next
    scheduleCatalog()
  }

  // ---- Access password and browser sessions ----

  fun passwordHash(): String? = synchronized(lock) { passwordHash }

  /** Replaces the password; every existing browser session is signed out. */
  fun setPasswordHash(hash: String) = synchronized(lock) {
    passwordHash = hash
    logins.clear()
    persistNow()
  }

  fun login(hash: String): LoginSession? = synchronized(lock) { logins[hash] }

  fun addLogin(session: LoginSession) = synchronized(lock) {
    logins[session.hash] = session
    // Keep the newest sessions only; a forgotten browser falls out after enough fresh logins.
    if (logins.size > MAX_LOGINS) logins.values.sortedBy { it.lastSeen }.take(logins.size - MAX_LOGINS).forEach { logins.remove(it.hash) }
    persistNow()
  }

  fun touchLogin(hash: String, now: Long) = synchronized(lock) {
    val session = logins[hash] ?: return
    // Sliding expiry needs day precision, not a write per request.
    if (now - session.lastSeen < 60_000) return
    logins[hash] = session.copy(lastSeen = now)
    scheduleState()
  }

  fun removeLogin(hash: String) = synchronized(lock) { if (logins.remove(hash) != null) persistNow() }
  fun clearLogins() = synchronized(lock) { logins.clear(); persistNow() }
  fun pruneLogins(olderThan: Long) = synchronized(lock) {
    if (logins.values.removeAll { it.lastSeen < olderThan }) scheduleState()
  }

  // ---- Client access keys ----

  fun clientKeys(): List<ClientKey> = synchronized(lock) { clientKeys.values.sortedByDescending { it.created } }

  fun addClientKey(key: HubAccessKey, label: String): ClientKey = synchronized(lock) {
    if (clientKeys.size >= MAX_CLIENT_KEYS) throw IllegalStateException("接入密钥已达 $MAX_CLIENT_KEYS 个，请先吊销不用的密钥")
    val record = ClientKey(key.id, box.seal(key.encoded), label.trim().take(60), clock())
    clientKeys[key.id] = record
    persistNow()
    record
  }

  /** The key behind [id], or null when it does not exist (any more). */
  fun clientKey(id: String): HubAccessKey? = synchronized(lock) {
    val record = clientKeys[id] ?: return null
    runCatching { HubAccessKey.parse(box.open(record.sealedSecret)) }.getOrNull()
  }

  fun removeClientKey(id: String): Boolean = synchronized(lock) { (clientKeys.remove(id) != null).also { if (it) persistNow() } }

  fun touchClientKey(id: String, now: Long, address: String) = synchronized(lock) {
    val record = clientKeys[id] ?: return
    clientKeys[id] = record.copy(lastUsed = now, lastAddress = address.take(64))
    scheduleState()
  }

  // ---- Web Push ----

  fun vapid(): Pair<String, String>? = synchronized(lock) {
    val public = vapidPublic ?: return null
    val sealed = vapidPrivateSealed ?: return null
    public to box.open(sealed)
  }

  fun saveVapid(publicKey: String, privateKey: String) = synchronized(lock) {
    vapidPublic = publicKey
    vapidPrivateSealed = box.seal(privateKey)
    persistNow()
  }

  fun subscriptions(): List<PushSubscription> = synchronized(lock) { subscriptions.toList() }

  fun addSubscription(subscription: PushSubscription) = synchronized(lock) {
    subscriptions.removeAll { it.endpoint == subscription.endpoint }
    subscriptions += subscription
    while (subscriptions.size > MAX_SUBSCRIPTIONS) subscriptions.removeAt(0)
    persistNow()
  }

  fun removeSubscription(endpoint: String) = synchronized(lock) { if (subscriptions.removeAll { it.endpoint == endpoint }) persistNow() }

  // ---- Persistence ----

  private fun scheduleState() {
    if (pendingState?.isDone == false) return
    pendingState = writer.schedule({ synchronized(lock) { writeState() } }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
  }

  private fun scheduleCatalog() {
    if (pendingCatalog?.isDone == false) return
    pendingCatalog = writer.schedule({ synchronized(lock) { writeCatalog() } }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
  }

  private fun persistNow() {
    pendingState?.cancel(false)
    writeState()
  }

  fun flush() = synchronized(lock) {
    pendingState?.cancel(false); pendingCatalog?.cancel(false)
    writeState(); writeCatalog()
  }

  override fun close() {
    flush()
    writer.shutdown()
  }

  private fun writeState() {
    try {
      DataFiles.write(statePath, stateJson().toString())
    } catch (error: Exception) {
      Diagnostics.warn("HubStore", "状态写入失败", error)
    }
  }

  private fun writeCatalog() {
    try {
      DataFiles.write(catalogPath, JSONObject().apply {
        catalogs.forEach { (id, catalog) ->
          put(id, JSONObject().put("projects", Codec.array(catalog.first.map(Codec::project))).put("sessions", Codec.array(catalog.second.map(Codec::session))))
        }
      }.toString())
    } catch (error: Exception) {
      Diagnostics.warn("HubStore", "目录缓存写入失败", error)
    }
  }

  private fun stateJson(): JSONObject = JSONObject()
    .put("version", 1)
    .put("hubId", instanceId)
    .put("profiles", Codec.array(profiles.map(Codec::profile)))
    .put("credentials", JSONObject(credentials as Map<*, *>))
    .put("clientUrls", JSONObject(clientUrls as Map<*, *>))
    .put("clientKeys", Codec.array(clientKeys.values.map {
      JSONObject().put("id", it.id).put("secret", it.sealedSecret).put("label", it.label).put("created", it.created)
        .put("lastUsed", it.lastUsed).put("lastAddress", it.lastAddress)
    }))
    .put("projects", JSONObject().apply { projects.forEach { (id, list) -> put(id, Codec.array(list.map(Codec::project))) } })
    .put("tasks", JSONObject().apply {
      tasks.forEach { (id, map) ->
        put(id, JSONObject().apply { map.forEach { (session, stored) -> put(session, Codec.task(stored.task).put("activeAt", stored.task.activeAt).put("observedAt", stored.observedAt)) } })
      }
    })
    .put("parents", JSONObject().apply { parents.forEach { (id, map) -> put(id, JSONObject(map as Map<*, *>)) } })
    .put("notices", JSONObject().apply { notices.forEach { (id, list) -> put(id, Codec.array(list.map(Codec::notice))) } })
    .put("preferences", JSONObject().put("permissionPolicy", Codec.policy(policy)).put("refreshMode", refresh.name).put("ui", ui))
    .put("auth", JSONObject().putOpt("password", passwordHash).put("sessions", Codec.array(logins.values.map {
      JSONObject().put("hash", it.hash).put("created", it.created).put("lastSeen", it.lastSeen).put("label", it.label)
    })))
    .put("push", JSONObject().putOpt("public", vapidPublic).putOpt("private", vapidPrivateSealed).put("subscriptions", Codec.array(subscriptions.map {
      JSONObject().put("endpoint", it.endpoint).put("p256dh", it.p256dh).put("auth", it.auth).put("label", it.label).put("created", it.created)
    })))

  private fun load() {
    val raw = try { DataFiles.read(statePath) } catch (error: Exception) { null }
    if (raw != null) {
      try { applyState(JSONObject(raw)) } catch (error: Exception) {
        // Never start from a half-read state silently: keep the broken file for inspection.
        val broken = statePath.resolveSibling("state.json.broken-${clock()}")
        runCatching { Files.move(statePath, broken) }
        Diagnostics.warn("HubStore", "state.json 无法解析，已另存为 ${broken.fileName} 并以空状态启动", error)
        clearAll()
      }
    }
    val catalog = runCatching { DataFiles.read(catalogPath)?.let(::JSONObject) }.getOrNull()
    catalog?.keys()?.forEach { id ->
      val item = catalog.optJSONObject(id) ?: return@forEach
      catalogs[id] = Decode.projects(item.optJSONArray("projects")) to Decode.sessions(item.optJSONArray("sessions"))
    }
  }

  private fun clearAll() {
    instanceId = ""; profiles.clear(); credentials.clear(); clientUrls.clear(); clientKeys.clear(); projects.clear(); tasks.clear(); parents.clear(); notices.clear()
    passwordHash = null; logins.clear(); vapidPublic = null; vapidPrivateSealed = null; subscriptions.clear()
    policy = PermissionPolicy(); refresh = RefreshMode.FAST; ui = JSONObject()
  }

  private fun applyState(json: JSONObject) {
    val now = clock()
    instanceId = json.optString("hubId").trim()
    json.optJSONArray("profiles")?.let { list ->
      for (i in 0 until list.length()) list.optJSONObject(i)?.let { item ->
        val id = item.optString("id")
        if (id.isNotBlank()) profiles += Codec.profileFrom(item, id)
      }
    }
    json.optJSONObject("credentials")?.let { map -> map.keys().forEach { credentials[it] = map.optString(it) } }
    json.optJSONObject("clientUrls")?.let { map -> map.keys().forEach { id -> map.optString(id).takeIf(String::isNotBlank)?.let { clientUrls[id] = it } } }
    json.optJSONArray("clientKeys")?.let { list ->
      for (i in 0 until list.length()) list.optJSONObject(i)?.let {
        val id = it.optString("id"); val secret = it.optString("secret")
        if (id.isNotBlank() && secret.isNotBlank()) clientKeys[id] = ClientKey(id, secret, it.optString("label"), it.optLong("created"),
          it.optLong("lastUsed"), it.optString("lastAddress"))
      }
    }
    json.optJSONObject("projects")?.let { map -> map.keys().forEach { projects[it] = Decode.projects(map.optJSONArray(it)) } }
    json.optJSONObject("tasks")?.let { map ->
      map.keys().forEach { server ->
        val entries = map.optJSONObject(server) ?: return@forEach
        val serverTasks = mutableMapOf<String, StoredTask>()
        entries.keys().forEach { session ->
          val item = entries.optJSONObject(session) ?: return@forEach
          val task = Decode.task(session, item) ?: return@forEach
          val observedAt = item.optLong("observedAt", task.since)
          // Settled runs older than the ledger window carry no information any more.
          if (!task.active && observedAt < now - LEDGER_TTL) return@forEach
          serverTasks[session] = StoredTask(task, observedAt)
        }
        tasks[server] = serverTasks
      }
    }
    json.optJSONObject("parents")?.let { map ->
      map.keys().forEach { server ->
        val entries = map.optJSONObject(server) ?: return@forEach
        parents[server] = entries.keys().asSequence().associateWith { entries.optString(it) }.filterValues(String::isNotBlank).toMutableMap()
      }
    }
    json.optJSONObject("notices")?.let { map -> map.keys().forEach { notices[it] = Decode.notices(map.optJSONArray(it)).pruned(now) } }
    json.optJSONObject("preferences")?.let { prefs ->
      prefs.optJSONObject("permissionPolicy")?.let { policy = PermissionPolicy(it.optBoolean("hide"), it.optBoolean("autoApprove")) }
      refresh = RefreshMode.entries.firstOrNull { it.name == prefs.optString("refreshMode") } ?: RefreshMode.FAST
      ui = prefs.optJSONObject("ui") ?: JSONObject()
    }
    json.optJSONObject("auth")?.let { auth ->
      passwordHash = auth.optString("password").ifBlank { null }
      auth.optJSONArray("sessions")?.let { list ->
        for (i in 0 until list.length()) list.optJSONObject(i)?.let {
          val hash = it.optString("hash")
          if (hash.isNotBlank()) logins[hash] = LoginSession(hash, it.optLong("created"), it.optLong("lastSeen"), it.optString("label"))
        }
      }
    }
    json.optJSONObject("push")?.let { push ->
      vapidPublic = push.optString("public").ifBlank { null }
      vapidPrivateSealed = push.optString("private").ifBlank { null }
      push.optJSONArray("subscriptions")?.let { list ->
        for (i in 0 until list.length()) list.optJSONObject(i)?.let {
          val endpoint = it.optString("endpoint")
          if (endpoint.isNotBlank()) subscriptions += PushSubscription(endpoint, it.optString("p256dh"), it.optString("auth"), it.optString("label"), it.optLong("created"))
        }
      }
    }
  }

  private companion object {
    const val WRITE_DELAY_MS = 1_000L
    const val LEDGER_TTL = 30L * 24 * 60 * 60 * 1000
    const val MAX_CATALOG_SESSIONS = 200
    const val MAX_LOGINS = 50
    const val MAX_SUBSCRIPTIONS = 50
    const val MAX_CLIENT_KEYS = 50
  }
}

/** Reads back what [Codec] wrote into the data volume. */
object Decode {
  private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
  private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)

  fun projects(array: JSONArray?): List<Project> = objects(array).mapNotNull {
    val directory = it.optString("directory")
    if (directory.isBlank()) null else Project(it.optString("id").ifBlank { normalizedDirectory(directory) }, directory,
      it.optString("name").ifBlank { directory.trimEnd('/').substringAfterLast('/').ifBlank { directory } }, strings(it.optJSONArray("sandboxes")))
  }

  fun sessions(array: JSONArray?): List<Session> = objects(array).mapNotNull {
    val id = it.optString("id")
    if (id.isBlank()) null else Session(id, it.optString("directory"), it.optString("title"), it.optLong("updated"),
      it.optString("parentId").ifBlank { null }, it.optString("projectId").ifBlank { null }, it.optLong("created"), it.optBoolean("archived"),
      it.optString("agent").ifBlank { null }, Codec.modelFrom(it.optJSONObject("model")), it.optLong("viewed"), it.optLong("idle"),
      it.optString("outcome").ifBlank { null }, it.optString("revertMessageId").ifBlank { null })
  }

  fun task(session: String, json: JSONObject): TaskState? {
    val phase = Codec.phase(json.optString("phase")) ?: return null
    return TaskState(session, phase, json.optString("detail"), json.optLong("since"),
      json.optLong("finishedAt").takeIf { it > 0 }, json.optLong("activeAt", -1), json.optBoolean("background"),
      uncertain = json.optBoolean("uncertain"), pendingInput = json.optBoolean("pendingInput"))
  }

  fun notices(array: JSONArray?): List<SessionNotice> = objects(array).mapNotNull {
    val id = it.optString("id")
    if (id.isBlank()) null else SessionNotice(id, it.optString("sessionId"), it.optLong("time"), it.optBoolean("error"), it.optBoolean("viewed"))
  }
}
