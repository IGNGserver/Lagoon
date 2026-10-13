package com.igng.opencode.lagoon.core

/**
 * The home list's scope is only a filter: 全部会话 (null) or one project. It is deliberately separate
 * from the project a new session starts in and from the directory agents/models load from.
 */
object HomeScope {
  /** Root sessions visible in [scope]; null keeps every project's sessions. */
  fun roots(sessions: List<Session>, projects: List<Project>, scope: String?): List<Session> =
    sessions.filter { session ->
      val project = resolveSessionProject(session, projects)
      session.visibleOnHome && (scope == null || project?.id == scope || project?.key == scope)
    }.sortedWith(sessionActivityOrder)

  /** Home-visible root session count per project id, for the project picker. */
  fun counts(sessions: List<Session>, projects: List<Project>): Map<String, Int> =
    sessions.filter { it.visibleOnHome }.mapNotNull { resolveSessionProject(it, projects)?.key }.groupingBy { it }.eachCount()

  /** Projects ordered by their latest root-session activity; projects without sessions keep their order last. */
  fun byActivity(sessions: List<Session>, projects: List<Project>): List<Project> {
    val latest = sessions.filter { it.visibleOnHome }.groupBy { resolveSessionProject(it, projects)?.key }
      .mapValues { (_, items) -> items.maxOf { it.activityAt } }
    return projects.withIndex().sortedWith(compareByDescending<IndexedValue<Project>> { latest[it.value.key] ?: Long.MIN_VALUE }.thenBy { it.index }).map { it.value }
  }

  /**
   * Where a new session starts: the scoped project, otherwise the project of the most recently
   * active root session, otherwise the last used / first project.
   */
  fun draftTarget(sessions: List<Session>, projects: List<Project>, scope: String?, current: String?): String? {
    scope?.takeIf { id -> projects.any { it.id == id } }?.let { return it }
    byActivity(sessions, projects).firstOrNull { project -> sessions.any { it.parentId == null && resolveSessionProject(it, projects)?.key == project.key } }?.let { return it.id }
    return current?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id
  }

  /** `/home/me/项目/repo` → `~/项目/repo` when [home] is known. */
  fun displayPath(path: String, home: String?): String {
    val root = home?.trimEnd('/')?.takeIf { it.length > 1 } ?: return path
    return when {
      path == root -> "~"
      path.startsWith("$root/") -> "~" + path.removePrefix(root)
      else -> path
    }
  }

  /** Breadcrumb segments of an absolute path: (label, absolute path) pairs starting at `/`. */
  fun crumbs(path: String): List<Pair<String, String>> {
    val parts = path.trim().trimEnd('/').split('/').filter(String::isNotBlank)
    return listOf("/" to "/") + parts.mapIndexed { index, name -> name to "/" + parts.take(index + 1).joinToString("/") }
  }

  fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }

  fun child(path: String, name: String): String = path.trimEnd('/') + "/" + name
}
