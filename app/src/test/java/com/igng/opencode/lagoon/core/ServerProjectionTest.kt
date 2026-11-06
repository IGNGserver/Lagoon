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
        // A failed read on an off-line member marks that snapshot degraded; the aggregate must not
        // inherit it, otherwise one sleeping device pins “部分数据待同步” on the whole page.
        "b" to snapshot(b, TaskPhase.THINKING).copy(connected = false, loading = false, cached = true, degraded = true)))
    val home = state.homeProjection()
    assertFalse(home.degraded)
    assertNull(home.homeSyncNotice())
  }

  @Test fun transcriptCacheDoesNotBecomeAnOfflineCatalogBanner() {
    val session = Session("same", "/repo", "A", 0, serverId = "a")
    val liveButCached = LagoonState(profiles = listOf(a), serverId = "a", connected = true, cached = true,
      sessions = listOf(session))
    // A cached transcript on a connected server is not an off-line catalog.
    assertNull(liveButCached.homeSyncNotice())

    val offline = liveButCached.copy(connected = false, loading = false)
    assertEquals("离线缓存 · 下拉重试", offline.homeSyncNotice())
    assertEquals("尚未连接 · 点按重试", offline.copy(sessions = emptyList()).homeSyncNotice())
    assertEquals("部分数据待同步 · 下拉重试", liveButCached.copy(degraded = true).homeSyncNotice())
  }

  @Test fun aggregateCacheFlagNeedsEveryMemberToBeOffline() {
    val session = Session("same", "/repo", "A", 0, serverId = "a")
    val online = snapshot(a, TaskPhase.IDLE).copy(cached = true, sessions = listOf(session))
    val offline = snapshot(b, TaskPhase.IDLE).copy(connected = false, loading = false, cached = true)
    val oneLive = LagoonState(profiles = listOf(a, b), allServers = true,
      allServerSnapshots = mapOf("a" to online, "b" to offline))
    assertFalse(oneLive.homeProjection().cached)
    assertNull(oneLive.homeProjection().homeSyncNotice())

    val allOffline = LagoonState(profiles = listOf(a, b), allServers = true,
      allServerSnapshots = mapOf("a" to online.copy(connected = false), "b" to offline))
    assertTrue(allOffline.homeProjection().cached)
    assertEquals("离线缓存 · 下拉重试", allOffline.homeProjection().homeSyncNotice())
  }

  @Test fun groupViewHasNoGlobalViewAllButSingleServerAndFilteredAllDo() {
    val group = DeviceGroup("g", "A", listOf("a"))
    val grouped = LagoonState(profiles = listOf(a), deviceGroups = listOf(group), homeGroupId = "g")
    assertFalse(grouped.showsGlobalViewAll())
    assertTrue(grouped.copy(homeGroupId = null, allServers = false).showsGlobalViewAll())
    assertFalse(grouped.copy(homeGroupId = null, allServers = true).showsGlobalViewAll())
    assertTrue(grouped.copy(homeGroupId = null, allServers = true, scopeProjectId = "p").showsGlobalViewAll())
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

  @Test fun homeReadTargetsFollowTheProjectedServerAndAllServersMode() {
    val state = LagoonState(profiles = listOf(a, b), serverId = "a", homeServerId = "b", connected = true,
      sessions = snapshot(a, TaskPhase.IDLE).sessions,
      allServerSnapshots = mapOf("a" to snapshot(a, TaskPhase.IDLE), "b" to snapshot(b, TaskPhase.IDLE)))
    assertEquals(setOf("b"), state.homeReadSnapshots().keys)
    assertEquals(setOf("a", "b"), state.copy(allServers = true).homeReadSnapshots().keys)
  }

  @Test fun homeUnseenSessionsStayScopedInAllServersMode() {
    val single = LagoonState(notices = listOf(SessionNotice("n", "same", 1, false)))
    assertEquals(setOf("same"), single.homeUnseenSessions())
    val all = LagoonState(allServers = true, allUnseenSessions = setOf("a:same", "b:same"))
    assertEquals(setOf("a:same", "b:same"), all.homeUnseenSessions())
  }

  @Test fun serverReadCandidatesRequireAConnectedViewCapableRootWithAResult() {
    val sessions = listOf(
      Session("root", "/repo", "Root", 10, idle = 20, viewed = 10),
      Session("seen", "/repo", "Seen", 10, idle = 20, viewed = 20),
      Session("other", "/repo", "Other", 10, idle = 20, viewed = 0),
      Session("child", "/repo", "Child", 10, parentId = "root", idle = 20, viewed = 0))
    val base = snapshot(a, TaskPhase.IDLE).copy(canSyncRead = true, sessions = sessions,
      notices = listOf(SessionNotice("n", "root", 1, false)))
    assertEquals(listOf("root"), base.serverReadCandidates().map { it.id })
    // Local read is a separate axis: viewed notices still drive the server-side sync.
    assertEquals(listOf("root"), base.copy(notices = base.notices.map { it.copy(viewed = true) })
      .serverReadCandidates().map { it.id })
    assertTrue(base.copy(canSyncRead = false).serverReadCandidates().isEmpty())
    assertTrue(base.copy(connected = false).serverReadCandidates().isEmpty())
  }

  @Test fun localAllReadKeepsTheNotificationSlotOfAWaitingSession() {
    val waiting = snapshot(a, TaskPhase.WAITING_QUESTION).copy(
      permissions = listOf(PermissionRequest("p", "same", "/repo", "shell", "echo", emptyList())))
    assertTrue(waiting.resultNotificationSessions(setOf("same")).isEmpty())
    val running = waiting.copy(permissions = emptyList(), tasks = mapOf("same" to TaskState("same", TaskPhase.THINKING)))
    assertEquals(setOf("same"), running.resultNotificationSessions(setOf("same")))
  }

  @Test fun pendingMatchesListLevelActionsInTheAggregateProjection() {
    val raw = LagoonState(serverId = "a", projectId = "p", pendingOperations = setOf("a:p:sessions-more"))
    val aggregate = raw.copy(allServers = true).homeProjection()
    assertTrue(aggregate.pending("sessions-more"))
    assertFalse(aggregate.pending("create"))
  }

  @Test fun archivedTasksStayInTheIslandButAreCountedAsHiddenFromHome() {
    val running = Session("run", "/repo", "Running", 0, archived = true, serverId = "a")
    val done = Session("done", "/repo", "Done", 0, archived = true, serverId = "a")
    val snapshot = ServerOverviewSnapshot("a", a, connected = true, lastReadAt = 1, loading = false,
      sessions = listOf(running, done),
      tasks = mapOf("run" to TaskState("run", TaskPhase.THINKING)),
      notices = listOf(SessionNotice("n-done", "done", 1, false)))
    val state = LagoonState(profiles = listOf(a), allServers = true, allServerSnapshots = mapOf("a" to snapshot))

    assertEquals(1, state.globalSummary.running)
    assertEquals(1, state.globalSummary.completed)
    assertEquals(2, state.archivedTaskCount())
    // The island sees them; the home list's `visibleOnHome` still hides archived rows.
    assertEquals(2, state.homeProjection().sessions.size)
    assertTrue(HomeScope.roots(state.homeProjection().sessions, state.homeProjection().projects, null).isEmpty())
  }

  @Test fun staleServerWorkLeavesTheIslandAndTheRowSimplySettles() {
    val session = Session("same", "/repo", "A", 0, serverId = "a")
    val snapshot = ServerOverviewSnapshot("a", a, connected = false, loading = false,
      lastReadAt = System.currentTimeMillis() - STALE_CONNECTION_TASK_MILLIS - 1_000,
      sessions = listOf(session), tasks = mapOf("same" to TaskState("same", TaskPhase.THINKING)))
    val state = LagoonState(profiles = listOf(a), allServers = true, allServerSnapshots = mapOf("a" to snapshot))

    assertEquals(0, state.globalSummary.running)
    // 离线超时的旧运行态不再转圈，也不再显示第三种状态：没有未读结果就是安静圆点。
    assertEquals(SessionStatus.NONE, state.homeProjection().allSessionStatuses[session.key])
  }

  @Test fun pendingPermissionsSurviveTheOfflineTtlBecauseThePhoneStillHoldsThem() {
    val request = PermissionRequest("p", "same", "/repo", "shell", "echo", emptyList())
    val snapshot = ServerOverviewSnapshot("a", a, connected = false, loading = false,
      lastReadAt = System.currentTimeMillis() - STALE_CONNECTION_TASK_MILLIS - 1_000,
      sessions = listOf(Session("same", "/repo", "A", 0, serverId = "a")),
      tasks = mapOf("same" to TaskState("same", TaskPhase.WAITING_PERMISSION)),
      permissions = listOf(request))
    val state = LagoonState(profiles = listOf(a), allServers = true, allServerSnapshots = mapOf("a" to snapshot))

    assertEquals(1, state.globalSummary.waiting)
    assertEquals(SessionStatus.WAITING_PERMISSION, state.homeProjection().allSessionStatuses.getValue(scopedKey("a", "same")))
  }

  @Test fun silentServerWorkIsNamedWhileTheSystemIslandOmitsIt() {
    val silent = a.copy(notifications = false)
    val state = LagoonState(profiles = listOf(silent), allServers = true,
      allServerSnapshots = mapOf("a" to snapshot(silent, TaskPhase.THINKING)))

    assertTrue(state.globalSummary.isEmpty)
    assertEquals(listOf("A"), state.silentServersWithTasks())
  }
}
