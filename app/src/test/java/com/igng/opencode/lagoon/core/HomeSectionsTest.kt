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

  @Test fun oldLiveTasksBelongToTodayWithoutChangingTheirActivity() {
    val live = listOf(SessionStatus.RUNNING, SessionStatus.BACKGROUND_RUNNING, SessionStatus.WAITING_PERMISSION,
      SessionStatus.WAITING_QUESTION, SessionStatus.WAITING_SERVER)
    val sessions = live.mapIndexed { index, status -> session(status.name, at(5, 9) - (40 + index) * 86_400_000L) }
    val timestamps = sessions.map { it.activityAt }
    val statuses = sessions.zip(live).associate { (session, status) -> session.key to status }

    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), statuses, now = now, zone = zone)

    assertEquals(listOf("date:today"), sections.map { it.key })
    assertEquals(listOf("今天"), sections.map { it.title })
    assertEquals(sessions, sections.single().sessions)
    assertEquals(timestamps, sections.single().sessions.map { it.activityAt })
  }

  @Test fun nonLiveAndMissingStatusesKeepTheirRealActivityDates() {
    val sessions = listOf(session("unknown", at(4, 23)), session("done", at(1, 12)),
      session("failed", now - 10 * 86_400_000L), session("idle", now - 40 * 86_400_000L),
      session("missing", now - 41 * 86_400_000L))
    val statuses = mapOf("unknown" to SessionStatus.UNKNOWN, "done" to SessionStatus.COMPLETED,
      "failed" to SessionStatus.FAILED, "idle" to SessionStatus.NONE)

    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), statuses, now = now, zone = zone)

    assertEquals(listOf("昨天", "近 7 天", "近 30 天", "更早"), sections.map { it.title })
    assertEquals(listOf(listOf("unknown"), listOf("done"), listOf("failed"), listOf("idle", "missing")),
      sections.map { section -> section.sessions.map { it.id } })
  }

  @Test fun promotedTodaySectionLeadsOlderBucketsAndPreservesActivityOrder() {
    val sessions = listOf(session("yesterday", at(4, 23)), session("week", at(1, 12)),
      session("running", now - 10 * 86_400_000L), session("month", now - 11 * 86_400_000L),
      session("background", now - 40 * 86_400_000L), session("earlier", now - 41 * 86_400_000L))
    val statuses = mapOf("running" to SessionStatus.RUNNING, "background" to SessionStatus.BACKGROUND_RUNNING)

    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), statuses, now = now, zone = zone)

    assertEquals(listOf("date:today", "date:yesterday", "date:week", "date:month", "date:earlier"), sections.map { it.key })
    assertEquals(listOf("running", "background"), sections.first().sessions.map { it.id })
    assertEquals(listOf("month"), sections[3].sessions.map { it.id })
  }

  @Test fun datePromotionUsesServerScopedKeysAndExpiresWithLiveStatus() {
    val running = session("same", now - 40 * 86_400_000L).copy(serverId = "a")
    val idle = running.copy(serverId = "b")
    val sessions = listOf(idle, running)
    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(),
      mapOf(running.key to SessionStatus.RUNNING), now = now, zone = zone)

    assertEquals(listOf("今天", "更早"), sections.map { it.title })
    assertEquals(listOf(running), sections.first().sessions)
    assertEquals(listOf(idle), sections.last().sessions)
    for (status in listOf(SessionStatus.UNKNOWN, SessionStatus.COMPLETED, SessionStatus.NONE)) {
      val settled = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(),
        mapOf(running.key to status), now = now, zone = zone)
      assertEquals(listOf("更早"), settled.map { it.title })
      assertEquals(sessions, settled.single().sessions)
    }
  }

  @Test fun todaysActivityAndPromotedWorkKeepTheirRelativeInputOrder() {
    val sessions = listOf(session("today", at(5, 8)), session("yesterday", at(4, 23)),
      session("running", at(1, 12)), session("waiting", now - 40 * 86_400_000L))
    val sections = HomeSections.byDate(sessions, now, zone,
      mapOf("running" to SessionStatus.RUNNING, "waiting" to SessionStatus.WAITING_QUESTION))

    assertEquals(listOf("今天", "昨天"), sections.map { it.title })
    assertEquals(listOf("today", "running", "waiting"), sections.first().sessions.map { it.id })
  }

  @Test fun pinnedLiveSessionsStayPinnedWithoutDuplicatingIntoToday() {
    val sessions = listOf(session("yesterday", at(4, 23)), session("pinned", now - 10 * 86_400_000L),
      session("running", now - 40 * 86_400_000L))
    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(),
      mapOf("pinned" to SessionStatus.RUNNING, "running" to SessionStatus.RUNNING),
      pinned = setOf("pinned"), now = now, zone = zone)

    assertEquals(listOf("置顶", "今天", "昨天"), sections.map { it.title })
    assertEquals(listOf(listOf("pinned"), listOf("running"), listOf("yesterday")),
      sections.map { section -> section.sessions.map { it.id } })
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
