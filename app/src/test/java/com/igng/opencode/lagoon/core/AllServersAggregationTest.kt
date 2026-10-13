package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AllServersAggregationTest {
  @Test fun sessionAndProjectKeysRemainDistinctAcrossServers() {
    val firstProject = Project("project", "/one/repo", "One", serverId = "first")
    val secondProject = Project("project", "/two/repo", "Two", serverId = "second")
    val first = Session("same-session", "/one/repo", "One task", 20, projectId = "project", serverId = "first")
    val second = Session("same-session", "/two/repo", "Two task", 10, projectId = "project", serverId = "second")

    assertNotEquals(first.key, second.key)
    assertNotEquals(firstProject.key, secondProject.key)
    assertEquals(firstProject, resolveSessionProject(first, listOf(firstProject, secondProject)))
    assertEquals(secondProject, resolveSessionProject(second, listOf(firstProject, secondProject)))
    assertEquals(2, HomeScope.roots(listOf(first, second), listOf(firstProject, secondProject), null).size)
    assertEquals(mapOf(firstProject.key to 1, secondProject.key to 1), HomeScope.counts(listOf(first, second), listOf(firstProject, secondProject)))
  }

  @Test fun aggregateSectionsDoNotMergeSameProjectIds() {
    val firstProject = Project("project", "/one/repo", "One", serverId = "first")
    val secondProject = Project("project", "/two/repo", "Two", serverId = "second")
    val sessions = listOf(
      Session("same", "/one/repo", "One", 20, projectId = "project", serverId = "first"),
      Session("same", "/two/repo", "Two", 10, projectId = "project", serverId = "second")
    )

    val sections = HomeSections.byProject(sessions, listOf(firstProject, secondProject))
    assertEquals(listOf("One", "Two"), sections.map { it.title })
    assertEquals(listOf(1, 1), sections.map { it.sessions.size })
  }

  @Test fun mergedTaskSummaryKeepsTheSourceForNotificationDeepLinks() {
    val first = TaskSummary.of(
      mapOf("same" to TaskState("same", TaskPhase.THINKING, since = 20)),
      titles = mapOf("same" to "One task"), serverId = "first", serverName = "One"
    )
    val second = TaskSummary.of(
      mapOf("same" to TaskState("same", TaskPhase.WAITING_QUESTION, since = 10)),
      titles = mapOf("same" to "Two task"), serverId = "second", serverName = "Two"
    )

    val merged = TaskSummary.merge(listOf(first, second))
    assertEquals(1, merged.running)
    assertEquals(1, merged.waiting)
    assertEquals(listOf("second", "first"), merged.items.map { it.serverId })
    assertEquals(listOf("Two", "One"), merged.items.map { it.serverName })
    assertEquals(SessionTarget("second", "same"), SessionTarget(merged.items[0].serverId!!, merged.items[0].sessionId))
  }
}
