package com.igng.opencode.lagoon.core

/** Where a session row's workspace label came from. Presentation only; never a filter identity. */
enum class WorkspaceSource { PROJECT, SERVER, DIRECTORY }

/** Row labels are cosmetic: never let a title read delay the catalog or the home projection. */
const val WORKSPACE_CATALOG_TIMEOUT_MILLIS = 5_000L

/**
 * One session's display-only workspace. [name] is what the home row shows; [path] is only used to
 * disambiguate same-named workspaces. It never filters, scopes, or chooses a new-session target:
 * those stay on the device-local [Project] registry.
 */
data class SessionWorkspace(
  val name: String,
  val path: String = "",
  val source: WorkspaceSource = WorkspaceSource.DIRECTORY,
  val serverId: String? = null,
  /** Server-scoped project key when [source] is PROJECT; a project section header already names it. */
  val projectKey: String? = null
)

/**
 * Resolves the workspace a session belongs to, in display precedence:
 * device-registered project → server project/workspace catalog → session directory name.
 *
 * [catalog] is a read-only list of the server's projects/workspaces (DSH's Workspace is its project).
 * It must never be merged into the registered [projects] list: that registry drives the scope picker,
 * project counts and the new-session target.
 */
fun resolveSessionWorkspace(
  session: Session,
  projects: List<Project>,
  catalog: List<Project> = emptyList()
): SessionWorkspace? {
  matchSessionProject(session, projects)?.let { project ->
    return SessionWorkspace(
      name = projectDisplayName(project),
      path = project.directory,
      source = WorkspaceSource.PROJECT,
      serverId = project.serverId ?: session.serverId,
      projectKey = project.key
    )
  }
  if (catalog.isNotEmpty()) matchSessionProject(session, catalog)?.let { project ->
    return SessionWorkspace(
      name = projectDisplayName(project),
      path = project.directory,
      source = WorkspaceSource.SERVER,
      serverId = project.serverId ?: session.serverId
    )
  }
  val directory = session.directory.trim()
  val name = directoryDisplayName(directory) ?: return null
  return SessionWorkspace(name, directory, WorkspaceSource.DIRECTORY, session.serverId)
}

/**
 * Labels for a page of sessions, keyed by [Session.key]. Same-named workspaces on one server are
 * qualified with their parent segment (`work/Lagoon`) so two rows never look identical by accident.
 * Labels are presentation only: they never feed filtering, sorting or a create target.
 */
fun sessionWorkspaceLabels(
  sessions: List<Session>,
  projects: List<Project>,
  catalogFor: (Session) -> List<Project> = { emptyList() }
): Map<String, SessionWorkspace> {
  val labels = LinkedHashMap<String, SessionWorkspace>()
  sessions.forEach { session ->
    resolveSessionWorkspace(session, projects, catalogFor(session))?.let { labels[session.key] = it }
  }
  val collisions = labels.entries
    .groupBy { (_, workspace) -> (workspace.serverId ?: "") to workspace.name }
    .filterValues { entries -> entries.map { it.value.path }.distinct().size > 1 }
  if (collisions.isEmpty()) return labels
  val qualified = LinkedHashMap(labels)
  collisions.values.flatten().forEach { (sessionKey, workspace) ->
    qualified[sessionKey] = workspace.copy(name = qualifiedName(workspace.path, workspace.name))
  }
  return qualified
}

/** `/home/me/work/Lagoon` + `Lagoon` → `work/Lagoon`; roots keep the bare name. */
private fun qualifiedName(path: String, name: String): String {
  val parts = path.trim().replace('\\', '/').trimEnd('/').split('/').filter { it.isNotBlank() }
  if (parts.size < 2) return name
  val parent = parts[parts.size - 2]
  return if (parent.isBlank() || parent == name) name else "$parent/$name"
}

/** Last path segment, or null for blank directories, filesystem roots and `~`. */
fun directoryDisplayName(directory: String): String? {
  val path = directory.trim().replace('\\', '/').trimEnd('/')
  if (path.isBlank() || path == "/" || path == "~") return null
  if (Regex("^[A-Za-z]:$").matches(path)) return null
  return path.substringAfterLast('/').trim().ifBlank { null }
}

private fun projectDisplayName(project: Project): String =
  project.name.trim().takeIf { it.isNotBlank() }
    ?: directoryDisplayName(project.directory)
    ?: project.directory

/** Server identities and execution directories are separate: a worktree is still the same project. */
fun resolveSessionProject(session: Session, projects: List<Project>): Project? =
  matchSessionProject(session, projects)

/** projectID first, then an exact directory/sandbox, then the most specific ancestor. */
private fun matchSessionProject(session: Session, projects: List<Project>): Project? {
  fun belongsToSource(project: Project): Boolean =
    session.serverId == null || project.serverId == null || project.serverId == session.serverId
  session.projectId?.let { id ->
    projects.firstOrNull { project -> project.id == id && belongsToSource(project) }?.let { return it }
  }
  val directory = normalizedDirectory(session.directory)
  projects.firstOrNull { project -> belongsToSource(project) && project.directories.any { normalizedDirectory(it) == directory } }?.let { return it }
  return projects.flatMap { project -> project.directories.map { project to normalizedDirectory(it) } }
    .filter { (project, root) -> belongsToSource(project) && root.isNotEmpty() && (directory == root || directory.startsWith(if (root == "/") root else "$root/")) }
    .maxByOrNull { (_, root) -> root.length }?.first
}

fun normalizedDirectory(directory: String): String {
  val path = directory.trim().replace('\\', '/').trimEnd('/').ifEmpty { if (directory.startsWith('/')) "/" else "" }
  return if (Regex("^[A-Za-z]:/").containsMatchIn(path)) path.lowercase() else path
}

enum class SessionContent { UNKNOWN, EMPTY, CONTENT }
data class SessionPreview(val content: SessionContent = SessionContent.UNKNOWN, val text: String = "")

/**
 * 官方客户端的标题口径：只认 `session.title`。
 * 自动生成的占位标题（`New session - <ISO时间>` / `Child session - <ISO时间>`）归一为“新会话 / 子会话”，
 * 空标题按是否有父会话回退；不使用首条用户消息兜底。
 */
private val placeholderTitle = Regex("^(New session|Child session) - \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
fun Session.displayTitle(): String {
  val value = title
  if (value.isNotBlank()) {
    val match = placeholderTitle.matchEntire(value) ?: return value
    return if (match.groupValues[1] == "Child session") "子会话" else "新会话"
  }
  return if (parentId != null) "子会话" else "新会话"
}

/**
 * One indicator per session, in the official client's priority: attention › running › unread failure ›
 * unread result › nothing. Attention may roll up from descendants; a running child is background
 * activity, not the root's running state. Results belong to the root and exist only while unread — a
 * session that finished long ago, or that was already opened, shows nothing.
 *
 * There is deliberately no "unknown" member: the v2 client derives a session's state from
 * `session.active` alone, so a session the phone cannot currently prove is running is simply idle.
 */
enum class SessionStatus(val label: String) {
  WAITING_PERMISSION("等待授权"), WAITING_QUESTION("等待回答"), WAITING_SERVER("等待服务器处理"), RUNNING("运行中"),
  FAILED("错误"), COMPLETED("已完成"), NONE("")
}

fun sessionStatus(activeFamily: TaskState?, unseen: List<SessionNotice>): SessionStatus = when {
  activeFamily?.phase == TaskPhase.WAITING_PERMISSION -> SessionStatus.WAITING_PERMISSION
  activeFamily?.phase == TaskPhase.WAITING_QUESTION -> SessionStatus.WAITING_QUESTION
  activeFamily?.phase == TaskPhase.WAITING_SERVER -> SessionStatus.WAITING_SERVER
  activeFamily?.phase in TaskState.RUNNING_PHASES -> SessionStatus.RUNNING
  unseen.any { it.error } -> SessionStatus.FAILED
  unseen.isNotEmpty() -> SessionStatus.COMPLETED
  else -> SessionStatus.NONE
}

/**
 * 首页/搜索/项目计数统一使用这个可见性。DSH 的 `blank` 是服务端尚未发送的草稿；
 * Lagoon 自己已有本地草稿页，所以服务端空白行全部隐藏（OpenCode 的 blank 恒为 false）。
 */
val Session.visibleOnHome: Boolean get() = parentId == null && !archived && !blank
val sessionActivityOrder: Comparator<Session> = compareByDescending<Session> { it.activityAt }.thenBy { it.id }

data class SessionGroup(val key: String, val name: String, val sessions: List<Session>) {
  val latest: Long get() = sessions.firstOrNull()?.activityAt ?: 0
}

/** Keep official root/archive eligibility, regardless of title, content, draft, or execution origin. */
fun groupSessions(sessions: List<Session>, projects: List<Project>): List<SessionGroup> {
  // Keep the historical raw project ids for the single-server helper, but scope keys whenever the
  // input visibly contains more than one server so identical OpenCode ids do not merge.
  val sourceCount = (sessions.mapNotNull { it.serverId } + projects.mapNotNull { it.serverId }).distinct().size
  val scoped = sourceCount > 1
  return sessions.filter { it.visibleOnHome }.sortedWith(sessionActivityOrder).groupBy { session ->
    val project = resolveSessionProject(session, projects)
    ((if (scoped) project?.key else project?.id) ?: "unassigned") to (project?.name ?: "未归入项目")
  }.map { (key, list) -> SessionGroup(key.first, key.second, list) }
    .sortedWith(compareByDescending<SessionGroup> { it.latest }.thenBy { it.key })
}

fun List<Message>.sessionPreview(): SessionPreview {
  val visible = filter { it.isDisplayable && !it.isBackgroundActivityMessage && it.role != "notice" }
  val firstPrompt = visible.firstOrNull { it.isHumanInput }?.parts
    ?.firstOrNull { it.type == "text" }?.text.orEmpty()
  return SessionPreview(if (visible.isEmpty()) SessionContent.EMPTY else SessionContent.CONTENT, firstPrompt.take(300))
}

val MessagePart.isDisplayable: Boolean get() = when (type) {
  "text", "reasoning" -> text.isNotBlank()
  "tool" -> tool.isNotBlank() || input.isNotBlank() || output.isNotBlank() || error.isNotBlank()
  "file" -> path.isNotBlank()
  else -> false
}
val Message.isDisplayable: Boolean get() = when {
  role == "user" && !isHumanInput -> false
  role == "hidden" -> false
  role == "notice" || role == "relay" || role == "shell" -> true
  else -> error?.isNotBlank() == true || retry != null || parts.any { it.isDisplayable }
}

enum class ResourceState { NOT_LOADED, LOADING, READY, EMPTY, ERROR, UNSUPPORTED, STALE }
data class ResourceStatus(val state: ResourceState = ResourceState.NOT_LOADED, val error: String? = null)
data class SessionConfiguration(val agent: String? = null, val model: ModelChoice? = null, val agentChanged: Boolean = false, val modelChanged: Boolean = false)
data class FileReference(val path: String, val mime: String = "text/plain")

/** Preserve binary/image reference semantics instead of labelling every file as plain text. */
fun referenceMime(path: String): String = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
  "png" -> "image/png"
  "jpg", "jpeg" -> "image/jpeg"
  "gif" -> "image/gif"
  "webp" -> "image/webp"
  "svg" -> "image/svg+xml"
  "pdf" -> "application/pdf"
  "mp3" -> "audio/mpeg"
  "wav" -> "audio/wav"
  "mp4" -> "video/mp4"
  "zip" -> "application/zip"
  "bin", "exe", "so", "dll" -> "application/octet-stream"
  else -> "text/plain"
}
