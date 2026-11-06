package com.igng.opencode.lagoon.core

import java.time.Instant
import java.time.ZoneId

/** How the home list is split into sections (the ⋯ menu's 分组方式). */
enum class HomeGrouping(val label: String) { DATE("日期"), PROJECT("项目"), STATUS("状态") }

data class HomeSection(val key: String, val title: String, val sessions: List<Session>)

/**
 * Whether a row must repeat its workspace name. The list scope or the section header already states
 * it when the list is scoped to one project/workspace, when the section is a workspace section, or
 * when the section is exactly the session's registered project. The “未归入项目” section is not a
 * statement about a session's folder, so those rows keep their resolved label.
 */
fun showWorkspaceLabel(
  sectionKey: String,
  scopeProjectId: String?,
  scopeWorkspaceKey: String?,
  projectKey: String?
): Boolean {
  if (scopeProjectId != null || scopeWorkspaceKey != null) return false
  if (sectionKey.startsWith("workspace:")) return false
  if (sectionKey.startsWith("project:")) return sectionKey.removePrefix("project:") != projectKey
  return true
}

/**
 * Priority inside the date view, where the reader wants actionable rows before chronological ones:
 * questions > live execution > unread results > everything else. “等待服务器处理” is live work
 * (there is nothing for the reader to answer), so it ranks with [SessionStatus.RUNNING]; the STATUS
 * grouping still files it under 需要处理, where the server is the actor.
 */
private fun dateStatusRank(status: SessionStatus?): Int = when (status) {
  SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION -> 0
  SessionStatus.RUNNING, SessionStatus.WAITING_SERVER -> 1
  SessionStatus.FAILED, SessionStatus.COMPLETED -> 2
  SessionStatus.NONE, null -> 3
}

/**
 * Sections of the home list. Project and status sections keep the input activity order; date
 * grouping places live work and unread results in 今天 without changing server timestamps, then
 * ranks each date section by [dateStatusRank] before preserving that activity order.
 * Pinned sessions are this device's choice and are lifted into a leading 置顶 section.
 */
object HomeSections {
  fun build(
    grouping: HomeGrouping,
    sessions: List<Session>,
    projects: List<Project>,
    statuses: Map<String, SessionStatus>,
    pinned: Set<String> = emptySet(),
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
  ): List<HomeSection> {
    val (top, rest) = sessions.partition { it.id in pinned }
    val sections = when (grouping) {
      HomeGrouping.DATE -> byDate(rest, now, zone, statuses)
      HomeGrouping.PROJECT -> byProject(rest, projects)
      HomeGrouping.STATUS -> byStatus(rest, statuses)
    }
    return listOfNotNull(top.takeIf { it.isNotEmpty() }?.let { HomeSection("pinned", "置顶", it) }) + sections
  }

  /**
   * 今天 / 昨天 / 近 7 天 / 近 30 天 / 更早, by local calendar day of the last activity.
   * Questions, running work (including waiting for the server) and unread results are live work and
   * belong to 今天. Unknown/offline and idle work keeps its real date. Within a section, questions
   * lead, then running work, then unread results, then quiet rows; each priority keeps the caller's
   * activity order (a stable sort), and timestamps are never rewritten.
   */
  fun byDate(
    sessions: List<Session>, now: Long, zone: ZoneId = ZoneId.systemDefault(),
    statuses: Map<String, SessionStatus> = emptyMap()
  ): List<HomeSection> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun bucket(session: Session): Int {
      when (statuses[session.key]) {
        SessionStatus.RUNNING,
        SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION, SessionStatus.WAITING_SERVER,
        SessionStatus.FAILED, SessionStatus.COMPLETED -> return 0
        else -> Unit
      }
      val day = Instant.ofEpochMilli(session.activityAt).atZone(zone).toLocalDate()
      return when {
        !day.isBefore(today) -> 0
        day == today.minusDays(1) -> 1
        day.isAfter(today.minusDays(7)) -> 2
        day.isAfter(today.minusDays(30)) -> 3
        else -> 4
      }
    }
    val keys = listOf("today", "yesterday", "week", "month", "earlier")
    val titles = listOf("今天", "昨天", "近 7 天", "近 30 天", "更早")
    return sessions.groupBy(::bucket).toSortedMap().map { (index, list) ->
      val ranked = list.sortedBy { session -> dateStatusRank(statuses[session.key]) }
      HomeSection("date:${keys[index]}", titles[index], ranked)
    }
  }

  /**
   * One section per server project, ordered by its latest session. Sessions whose directory is not a
   * server project are not shown as a project of their own — they collect under one 未归入项目 section.
   */
  fun byProject(sessions: List<Session>, projects: List<Project>): List<HomeSection> =
    sessions.groupBy { session -> resolveSessionProject(session, projects)?.key.orEmpty() }
      .map { (projectKey, list) ->
        val project = projects.firstOrNull { it.key == projectKey }
        HomeSection("project:${project?.key ?: projectKey.ifBlank { "unassigned" }}", project?.name ?: "未归入项目", list)
      }

  /**
   * 需要处理 › 运行中 › 未读结果 › 其他, the order the official client ranks session indicators.
   * “等待服务器处理”也是需要处理的一类（只是等待的是服务端/电脑端），不再落到“其他”。
   */
  fun byStatus(sessions: List<Session>, statuses: Map<String, SessionStatus>): List<HomeSection> {
    fun bucket(session: Session) = when (statuses[session.key] ?: SessionStatus.NONE) {
      SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION, SessionStatus.WAITING_SERVER -> 0
      SessionStatus.RUNNING -> 1
      SessionStatus.FAILED, SessionStatus.COMPLETED -> 2
      SessionStatus.NONE -> 3
    }
    val titles = listOf("需要处理", "运行中", "未读结果", "其他")
    return sessions.groupBy(::bucket).toSortedMap().map { (index, list) -> HomeSection("status:$index", titles[index], list) }
  }
}
