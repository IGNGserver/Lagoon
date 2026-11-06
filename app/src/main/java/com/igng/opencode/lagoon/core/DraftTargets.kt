package com.igng.opencode.lagoon.core

/**
 * A server (harness instance) which can host a new blank-draft session.
 *
 * [registered] are device-local projects; they are the only immediate create targets because the
 * server is authoritative about what a directory resolves to. [serverWorkspaces] are the titles the
 * server itself reports (OpenCode `/api/project`, DSH `workspace/follow`); picking one registers it
 * explicitly, so the server's catalog is never imported behind the user's back.
 */
data class DraftServer(
  val profile: ServerProfile,
  val connected: Boolean,
  val loading: Boolean,
  val registered: List<Project>,
  val serverWorkspaces: List<Project>
) {
  val id: String get() = profile.id
  val name: String get() = profile.name
  val connectionLabel: String get() = profile.connectionTypeLabel
  val workspaceCount: Int get() = registered.size
  val hasAnyWorkspace: Boolean get() = registered.isNotEmpty() || serverWorkspaces.isNotEmpty()
  val statusLabel: String get() = when {
    connected -> "已连接"
    loading -> "连接中…"
    else -> "未连接"
  }
}

/** One selectable workspace of a [DraftServer]; [registered] is false for server-only entries. */
data class DraftWorkspace(val project: Project, val registered: Boolean) {
  val id: String get() = project.id
  val name: String get() = project.name
  val directory: String get() = project.directory
}

/** Projects registered on this device for [id]; the live writable connection always wins. */
fun LagoonState.registeredProjectsOn(id: String): List<Project> =
  if (id == serverId) projects else allServerSnapshots[id]?.projects.orEmpty()

/** Display-only server workspace titles for [id], scoped to that server. */
fun LagoonState.serverWorkspaceCatalogOn(id: String): List<Project> =
  (if (id == serverId) workspaceCatalog else allServerSnapshots[id]?.workspaceCatalog.orEmpty())
    .filter { it.serverId == null || it.serverId == id }

fun LagoonState.serverConnected(id: String): Boolean =
  if (id == serverId) connected else allServerSnapshots[id]?.connected == true

fun LagoonState.serverLoading(id: String): Boolean =
  if (id == serverId) loading else allServerSnapshots[id]?.loading == true

/**
 * Servers a new session may start on, restricted to [profileIds] when the home is a device group
 * (null = every enabled profile). The writable connection comes first, then online servers; offline
 * servers stay in the list because the blank page can wait for a reconnect instead of hiding them.
 */
fun LagoonState.draftServers(profileIds: Set<String>? = null): List<DraftServer> {
  val order = enabledProfiles.withIndex().associate { (index, profile) -> profile.id to index }
  return enabledProfiles
    .asSequence()
    .filter { profileIds == null || it.id in profileIds }
    .map { profile ->
      DraftServer(
        profile = profile,
        connected = serverConnected(profile.id),
        loading = serverLoading(profile.id),
        registered = registeredProjectsOn(profile.id),
        serverWorkspaces = serverWorkspaceCatalogOn(profile.id)
      )
    }
    .sortedWith(compareByDescending<DraftServer> { it.id == serverId }
      .thenByDescending { it.connected }
      .thenBy { order[it.id] ?: Int.MAX_VALUE })
    .toList()
}

/**
 * Workspaces of one server: registered ones first, then server-reported workspaces which are not
 * registered yet. Picking a server-only entry registers it explicitly; directories are deduplicated
 * with the same canonical form the project registry uses.
 */
fun LagoonState.draftWorkspaces(serverId: String): List<DraftWorkspace> {
  val registered = registeredProjectsOn(serverId)
  val seen = registered.mapTo(HashSet()) { normalizedDirectory(it.directory) }
  val serverOnly = serverWorkspaceCatalogOn(serverId)
    .filter { seen.add(normalizedDirectory(it.directory)) }
    .map { DraftWorkspace(it.copy(serverId = serverId), registered = false) }
  return registered.map { DraftWorkspace(it.copy(serverId = serverId), registered = true) } + serverOnly
}

/**
 * The server the new-session button can open directly, or null when the reader must choose one.
 *
 * One enabled candidate never needs a choice, and a single-server home whose connection is up opens
 * straight into the blank page. Aggregate and device-group homes ask first; so does an offline
 * single server, because the list is the only way to reach another harness from there.
 */
fun LagoonState.directDraftServer(profileIds: Set<String>? = null): DraftServer? {
  val candidates = draftServers(profileIds)
  if (candidates.size == 1) return candidates.first()
  if (allServers || homeGroupId != null) return null
  return candidates.firstOrNull { it.id == serverId && it.connected }
}

/**
 * The workspace a blank draft opens on when the caller has no explicit choice: the preferred id,
 * then the home scope's draft target, then the server's last/first registered project. Server-only
 * workspaces are deliberately not chosen automatically; they require an explicit tap to register.
 */
fun LagoonState.preferredDraftProject(serverId: String, preferred: String? = null): Project? {
  val known = registeredProjectsOn(serverId).map { it.copy(serverId = serverId) }
  preferred?.let { id -> known.firstOrNull { it.id == id }?.let { return it } }
  val knownSessions = if (serverId == this.serverId) sessions else allServerSnapshots[serverId]?.sessions.orEmpty()
  val scope = if (serverId == this.serverId) scopeProjectId else null
  val current = if (serverId == this.serverId) projectId else null
  HomeScope.draftTarget(knownSessions, known, scope, current)?.let { id -> known.firstOrNull { it.id == id }?.let { return it } }
  return known.firstOrNull()
}
