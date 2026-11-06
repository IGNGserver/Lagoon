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
    val sessions = listOf(session("six", at(5, 9) - 6 * 86_400_000L), session("seven", at(5, 9) - 7 * 86_400_000L))
    val sections = HomeSections.byDate(sessions, now, zone)
    assertEquals(listOf("近 7 天", "近 30 天"), sections.map { it.title })
  }

  @Test fun oldLiveTasksBelongToTodayAndLeadByPriority() {
    val live = listOf(SessionStatus.RUNNING, SessionStatus.WAITING_PERMISSION,
      SessionStatus.WAITING_QUESTION, SessionStatus.WAITING_SERVER)
    val sessions = live.mapIndexed { index, status -> session(status.name, at(5, 9) - (40 + index) * 86_400_000L) }
    val statuses = sessions.zip(live).associate { (session, status) -> session.key to status }

    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), statuses, now = now, zone = zone)

    assertEquals(listOf("date:today"), sections.map { it.key })
    assertEquals(listOf("今天"), sections.map { it.title })
    assertEquals(listOf("WAITING_PERMISSION", "WAITING_QUESTION", "RUNNING", "WAITING_SERVER"),
      sections.single().sessions.map { it.id })
  }

  @Test fun nonLiveAndMissingStatusesKeepTheirRealActivityDates() {
    val sessions = listOf(session("unknown", at(4, 23)), session("idle", now - 40 * 86_400_000L),
      session("missing", now - 41 * 86_400_000L))
    val statuses = mapOf("unknown" to SessionStatus.NONE, "idle" to SessionStatus.NONE)

    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), statuses, now = now, zone = zone)

    assertEquals(listOf("昨天", "更早"), sections.map { it.title })
    assertEquals(listOf(listOf("unknown"), listOf("idle", "missing")),
      sections.map { section -> section.sessions.map { it.id } })
  }

  @Test fun unreadResultsArePromotedToTodayUntilViewed() {
    val sessions = listOf(session("done", at(1, 12)), session("failed", now - 10 * 86_400_000L),
      session("quietToday", at(5, 8)))
    val unread = mapOf("done" to SessionStatus.COMPLETED, "failed" to SessionStatus.FAILED,
      "quietToday" to SessionStatus.NONE)

    val promoted = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), unread, now = now, zone = zone)
    assertEquals(listOf("今天"), promoted.map { it.title })
    assertEquals(listOf("done", "failed", "quietToday"), promoted.single().sessions.map { it.id })

    val viewed = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(),
      mapOf("done" to SessionStatus.NONE, "failed" to SessionStatus.NONE, "quietToday" to SessionStatus.NONE),
      now = now, zone = zone)
    assertEquals(listOf("今天", "近 7 天", "近 30 天"), viewed.map { it.title })
    assertEquals(listOf(listOf("quietToday"), listOf("done"), listOf("failed")),
      viewed.map { section -> section.sessions.map { it.id } })
  }

  @Test fun promotedTodaySectionLeadsOlderBuckets() {
    val sessions = listOf(session("yesterday", at(4, 23)), session("week", at(1, 12)),
      session("running", now - 10 * 86_400_000L), session("month", now - 11 * 86_400_000L),
      session("earlier", now - 41 * 86_400_000L))
    val statuses = mapOf("running" to SessionStatus.RUNNING)

    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), statuses, now = now, zone = zone)

    assertEquals(listOf("date:today", "date:yesterday", "date:week", "date:month", "date:earlier"), sections.map { it.key })
    assertEquals(listOf("running"), sections.first().sessions.map { it.id })
    assertEquals(listOf("month"), sections[3].sessions.map { it.id })
  }

  @Test fun datePromotionUsesServerScopedKeysAndReturnsViewedUnreadToItsDate() {
    val running = session("same", now - 40 * 86_400_000L).copy(serverId = "a")
    val idle = running.copy(serverId = "b")
    val sessions = listOf(idle, running)

    val live = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(),
      mapOf(running.key to SessionStatus.RUNNING), now = now, zone = zone)
    assertEquals(listOf("今天", "更早"), live.map { it.title })
    assertEquals(listOf(running), live.first().sessions)
    assertEquals(listOf(idle), live.last().sessions)

    val unread = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(),
      mapOf(running.key to SessionStatus.COMPLETED), now = now, zone = zone)
    assertEquals(listOf("今天", "更早"), unread.map { it.title })
    assertEquals(listOf(running), unread.first().sessions)

    val settled = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(),
      mapOf(running.key to SessionStatus.NONE), now = now, zone = zone)
    assertEquals(listOf("更早"), settled.map { it.title })
    assertEquals(sessions, settled.single().sessions)
  }

  @Test fun dateViewRanksQuestionsThenLiveWorkThenUnreadThenQuiet() {
    val sessions = listOf(session("quiet", at(5, 8)), session("unread", at(5, 7)),
      session("running", at(5, 6)), session("waiting", at(5, 5)))
    val statuses = mapOf("quiet" to SessionStatus.NONE, "unread" to SessionStatus.COMPLETED,
      "running" to SessionStatus.RUNNING, "waiting" to SessionStatus.WAITING_QUESTION)

    val sections = HomeSections.byDate(sessions, now, zone, statuses)

    assertEquals(listOf("waiting", "running", "unread", "quiet"),
      sections.single().sessions.map { it.id })
  }

  @Test fun waitingOnServerRanksWithRunningNotWithQuestions() {
    val sessions = listOf(session("server", at(5, 8)), session("run", at(5, 7)), session("ask", at(5, 6)))
    val statuses = mapOf("server" to SessionStatus.WAITING_SERVER, "run" to SessionStatus.RUNNING,
      "ask" to SessionStatus.WAITING_QUESTION)

    val sections = HomeSections.byDate(sessions, now, zone, statuses)

    assertEquals(listOf("ask", "server", "run"), sections.single().sessions.map { it.id })
  }

  @Test fun sameDatePriorityKeepsTheCallersActivityOrder() {
    val sessions = listOf(session("quiet", at(5, 9)), session("newerRun", at(5, 8)),
      session("newerUnread", at(5, 7)), session("olderRun", at(5, 6)), session("olderUnread", at(5, 5)))
    val statuses = mapOf("quiet" to SessionStatus.NONE, "newerRun" to SessionStatus.RUNNING,
      "newerUnread" to SessionStatus.COMPLETED, "olderRun" to SessionStatus.RUNNING,
      "olderUnread" to SessionStatus.COMPLETED)

    val sections = HomeSections.byDate(sessions, now, zone, statuses)

    assertEquals(listOf("newerRun", "olderRun", "newerUnread", "olderUnread", "quiet"),
      sections.single().sessions.map { it.id })
  }

  @Test fun todaysQuietRowsKeepActivityOrder() {
    val sessions = listOf(session("a", at(5, 8)), session("b", at(5, 7)), session("c", at(5, 6)))
    val sections = HomeSections.byDate(sessions, now, zone, sessions.associate { it.key to SessionStatus.NONE })
    assertEquals(listOf("a", "b", "c"), sections.single().sessions.map { it.id })
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

  @Test fun pinnedSectionStaysFirstAndKeepsItsOwnOrder() {
    val sessions = listOf(session("pinnedIdle", at(5, 9)), session("pinnedWaiting", at(4, 23)),
      session("running", now - 40 * 86_400_000L))
    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(),
      mapOf("pinnedIdle" to SessionStatus.NONE, "pinnedWaiting" to SessionStatus.WAITING_QUESTION,
        "running" to SessionStatus.RUNNING),
      pinned = setOf("pinnedIdle", "pinnedWaiting"), now = now, zone = zone)

    assertEquals(listOf("置顶", "今天"), sections.map { it.title })
    assertEquals(listOf("pinnedIdle", "pinnedWaiting"), sections.first().sessions.map { it.id })
    assertEquals(listOf("running"), sections.last().sessions.map { it.id })
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

  @Test fun waitingOnTheServerRanksWithNeedsAttentionInTheStatusGrouping() {
    val sections = HomeSections.byStatus(listOf(session("server", at(5, 8))), mapOf("server" to SessionStatus.WAITING_SERVER))
    assertEquals(listOf("需要处理"), sections.map { it.title })
  }

  @Test fun settledOfflineWorkFallsUnderTheRest() {
    val sections = HomeSections.byStatus(listOf(session("offline", at(5, 8))), mapOf("offline" to SessionStatus.NONE))
    assertEquals(listOf("其他"), sections.map { it.title })
  }

  @Test fun pinnedSessionsLeadAndLeaveTheirSection() {
    val sessions = listOf(session("a", at(5, 8)), session("b", at(3, 8)))
    val sections = HomeSections.build(HomeGrouping.DATE, sessions, emptyList(), emptyMap(), pinned = setOf("b"), now = now, zone = zone)
    assertEquals(listOf("置顶", "今天"), sections.map { it.title })
    assertEquals(listOf("b"), sections.first().sessions.map { it.id })
  }
}
