package com.igng.opencode.lagoon.core

/** Server identities and execution directories are separate: a worktree is still the same project. */
fun resolveSessionProject(session: Session, projects: List<Project>): Project? {
  session.projectId?.let { id ->
    projects.firstOrNull { project ->
      project.id == id && (session.serverId == null || project.serverId == null || project.serverId == session.serverId)
    }?.let { return it }
  }
  val directory = normalizedDirectory(session.directory)
  fun belongsToSource(project: Project): Boolean =
    session.serverId == null || project.serverId == null || project.serverId == session.serverId
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
 * [UNKNOWN] marks server-owned work whose last read is older than the island's offline TTL: it must
 * not keep spinning as if the phone could still prove it is running.
 */
enum class SessionStatus(val label: String) {
  WAITING_PERMISSION("等待授权"), WAITING_QUESTION("等待回答"), WAITING_SERVER("等待服务器处理"), RUNNING("运行中"),
  FAILED("错误"), COMPLETED("已完成"), UNKNOWN("状态待确认"), NONE("")
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

val Session.visibleOnHome: Boolean get() = parentId == null && !archived
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
  val visible = filter { it.isDisplayable && it.role != "notice" }
  val firstPrompt = visible.firstOrNull { it.role == "user" }?.parts
    ?.firstOrNull { it.type == "text" }?.text.orEmpty()
  return SessionPreview(if (visible.isEmpty()) SessionContent.EMPTY else SessionContent.CONTENT, firstPrompt.take(300))
}

val MessagePart.isDisplayable: Boolean get() = when (type) {
  "text", "reasoning" -> text.isNotBlank()
  "tool" -> tool.isNotBlank() || input.isNotBlank() || output.isNotBlank() || error.isNotBlank()
  "file" -> path.isNotBlank()
  else -> false
}
/** Official visibility: notices and shell turns always render; `hidden` records never do. */
val Message.isDisplayable: Boolean get() = when (role) {
  "hidden" -> false
  "notice", "shell" -> true
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
