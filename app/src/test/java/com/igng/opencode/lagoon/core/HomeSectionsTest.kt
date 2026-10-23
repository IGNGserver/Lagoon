package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HomeSectionsTest {
  private val zone = ZoneId.of("Asia/Shanghai")
  private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
  private val now = at(5, 9)
  private fun session(id: String, time: Long, project: String = "p1") = Session(id, "/repo/$project", id, time, projectId = project)

  @Test fun dateSectionsFollowLocalCalendarDaysNotRolling24Hours() {
    val sessions = listOf(
      session("today", at(5, 0)), session("lateYesterday", at(4, 23)), session("yesterday", at(4, 1)),
      session("week", at(1, 12)), session("month", LocalDateTime.of(2026, 9, 10, 8, 0).atZone(zone).toInstant().toEpochMilli()),
      session("old", LocalDateTime.of(2026, 8, 1, 8, 0).atZone(zone).toInstant().toEpochMilli()))
    val sections = HomeSections.byDate(sessions, now, zone)
    assertEquals(listOf("今天", "昨天", "近 7 天", "近 30 天", "更早"), sections.map { it.title })
    assertEquals(listOf("lateYesterday", "yesterday"), sections[1].sessions.map { it.id })
  }

  @Test fun sixDaysAgoIsStillThisWeekAndSevenIsNot() {
    val sections = HomeSections.byDate(listOf(session("six", at(5, 9) - 6 * 86_400_000L), session("seven", at(5, 9) - 7 * 86_400_000L)), now, zone)
    assertEquals(listOf("近 7 天", "近 30 天"), sections.map { it.title })
  }

  @Test fun sectionsKeepTheInputActivityOrder() {
    val sessions = listOf(session("a", at(5, 8)), session("b", at(5, 7), "p2"), session("c", at(5, 6)))
    val projects = listOf(Project("p1", "/repo/p1", "One"), Project("p2", "/repo/p2", "Two"))
    val byProject = HomeSections.byProject(sessions, projects)
    assertEquals(listOf("One", "Two"), byProject.map { it.title })
    assertEquals(listOf("a", "c"), byProject.first().sessions.map { it.id })
  }

  @Test fun statusSectionsRankAttentionRunningUnreadThenTheRest() {
    val sessions = listOf(session("idle", at(5, 8)), session("done", at(5, 7)), session("run", at(5, 6)), session("ask", at(5, 5)), session("fail", at(5, 4)))
    val statuses = mapOf("done" to SessionStatus.COMPLETED, "run" to SessionStatus.RUNNING, "ask" to SessionStatus.WAITING_QUESTION, "fail" to SessionStatus.FAILED)
    val sections = HomeSections.byStatus(sessions, statuses)
    assertEquals(listOf("需要处理", "运行中", "未读结果", "其他"), sections.map { it.title })
    assertEquals(listOf("done", "fail"), sections[2].sessions.map { it.id })
  }

  @Test fun backgroundRunningRanksWithRunning() {
    val sessions = listOf(session("bg", at(5, 8)), session("run", at(5, 7)))
    val sections = HomeSections.byStatus(sessions, mapOf("bg" to SessionStatus.BACKGROUND_RUNNING, "run" to SessionStatus.RUNNING))
    assertEquals(listOf("运行中"), sections.map { it.title })
    assertEquals(listOf("bg", "run"), sections.single().sessions.map { it.id })
  }

  @Test fun waitingOnTheServerRanksWithNeedsAttention() {
    val sections = HomeSections.byStatus(listOf(session("server", at(5, 8))), mapOf("server" to SessionStatus.WAITING_SERVER))
    assertEquals(listOf("需要处理"), sections.map { it.title })
  }

  @Test fun unknownOfflineWorkFallsUnderTheRest() {
    val sections = HomeSections.byStatus(listOf(session("offline", at(5, 8))), mapOf("offline" to SessionStatus.UNKNOWN))
    assertEquals(listOf("其他"), sections.map { it.title })
  }

  @Test fun pinnedSessionsLeadAndLeaveTheirSection() {
    val sessions = listOf(session("a", at(5, 8)), session("b", at(3, 8)))
    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), emptyMap(), pinned = setOf("b"), now = now, zone = zone)
    assertEquals(listOf("置顶", "今天"), sections.map { it.title })
    assertEquals(listOf("b"), sections.first().sessions.map { it.id })
  }
}
