package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class ServerProjectionTest {
  private val a = ServerProfile("a", "A", "https://a.example")
  private val b = ServerProfile("b", "B", "https://b.example")
  private fun snapshot(profile: ServerProfile, phase: TaskPhase) = ServerOverviewSnapshot(profile.id, profile, connected = true,
    streamConnected = true, loading = false, sessions = listOf(Session("same", "/repo", profile.name, 0, serverId = profile.id)),
    tasks = mapOf("same" to TaskState("same", phase)))

  @Test fun openingAConversationDoesNotChangeAggregateHomeOrDropOtherServerTotals() {
    val state = LagoonState(profiles = listOf(a, b), serverId = "a", homeServerId = "b", allServers = true,
      connected = true, sessions = listOf(Session("same", "/repo", "A", 0, serverId = "a")), sessionId = "same",
      tasks = mapOf("same" to TaskState("same", TaskPhase.THINKING)),
      allServerSnapshots = mapOf("a" to snapshot(a, TaskPhase.THINKING), "b" to snapshot(b, TaskPhase.WAITING_QUESTION)))
    val home = state.homeProjection()
    assertNull(home.serverId)
    assertEquals(2, home.sessions.size)
    assertEquals(1, state.globalSummary.running)
    assertEquals(1, state.globalSummary.waiting)
    assertEquals(SessionTarget("b", "same"), state.globalSummaryTarget)
    assertEquals(SessionStatus.RUNNING, state.sessionStatus(state.session!!))
    assertEquals(SessionStatus.WAITING_QUESTION, home.sessionStatus(home.sessions.single { it.serverId == "b" }))
  }

  @Test fun homeFilterAndGlobalNotificationAreIndependent() {
    val state = LagoonState(profiles = listOf(a, b), serverId = "a", homeServerId = "b", connected = true,
      sessions = snapshot(a, TaskPhase.THINKING).sessions, tasks = snapshot(a, TaskPhase.THINKING).tasks,
      allServerSnapshots = mapOf("a" to snapshot(a, TaskPhase.THINKING), "b" to snapshot(b, TaskPhase.THINKING)))
    assertEquals(listOf("b"), state.homeProjection().sessions.map { it.serverId })
    assertEquals(2, state.globalSummary.running)
    assertEquals(2, state.copy(allServers = true).globalSummary.running)
    assertEquals(2, state.copy(allServers = true).homeProjection().sessions.size)
  }

  @Test fun hidingPermissionsCoversAllServerListsAndTheGlobalNotification() {
    val request = PermissionRequest("p", "same", "/repo", "shell", "echo", emptyList())
    val state = LagoonState(profiles = listOf(a, b), allServers = true, permissionPolicy = PermissionPolicy(hide = true),
      allServerSnapshots = mapOf("a" to snapshot(a, TaskPhase.WAITING_PERMISSION).copy(permissions = listOf(request)),
        "b" to snapshot(b, TaskPhase.WAITING_QUESTION)))
    assertEquals(1, state.globalSummary.processing)
    assertEquals(1, state.globalSummary.waiting)
    val home = state.homeProjection()
    assertEquals(SessionStatus.WAITING_SERVER, home.sessionStatus(home.sessions.single { it.serverId == "a" }))
    assertEquals(SessionStatus.WAITING_QUESTION, home.sessionStatus(home.sessions.single { it.serverId == "b" }))
  }

  @Test fun updatingTheSelectedServerReplacesRatherThanDoubleCountsItsSnapshot() {
    val state = LagoonState(profiles = listOf(a, b), serverId = "a", connected = true,
      sessions = snapshot(a, TaskPhase.THINKING).sessions, tasks = emptyMap(),
      allServerSnapshots = mapOf("a" to snapshot(a, TaskPhase.THINKING), "b" to snapshot(b, TaskPhase.THINKING)))
    assertEquals(1, state.globalSummary.running)
  }

  @Test fun anOfflineServerIsNotReportedAsDegradedData() {
    val state = LagoonState(profiles = listOf(a, b), allServers = true,
      allServerSnapshots = mapOf(
        "a" to snapshot(a, TaskPhase.THINKING),
        "b" to snapshot(b, TaskPhase.THINKING).copy(connected = false, loading = false)))
    assertFalse(state.homeProjection().degraded)
  }

  @Test fun aConnectedServerWithFailedReadsKeepsTheAggregateDegraded() {
    val state = LagoonState(profiles = listOf(a, b), allServers = true,
      allServerSnapshots = mapOf(
        "a" to snapshot(a, TaskPhase.THINKING),
        "b" to snapshot(b, TaskPhase.THINKING).copy(degraded = true)))
    assertTrue(state.homeProjection().degraded)
  }

  @Test fun serversWithNotificationsOffStayOutOfTheSystemSummary() {
    val silent = a.copy(notifications = false)
    val state = LagoonState(profiles = listOf(silent, b), allServers = true,
      allServerSnapshots = mapOf(
        "a" to snapshot(silent, TaskPhase.THINKING),
        "b" to snapshot(b, TaskPhase.THINKING)))
    assertEquals(1, state.globalSummary.running)
  }

  @Test fun pendingMatchesListLevelActionsInTheAggregateProjection() {
    val raw = LagoonState(serverId = "a", projectId = "p", pendingOperations = setOf("a:p:sessions-more"))
    val aggregate = raw.copy(allServers = true).homeProjection()
    assertTrue(aggregate.pending("sessions-more"))
    assertFalse(aggregate.pending("create"))
  }

}
