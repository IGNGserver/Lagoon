package com.igng.opencode.lagoon.core

import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parent gates affect runtime surfaces, never the saved leaf preference, group IDs or cache. */
class HierarchyRuntimeSurfaceTest {
  private val allowed = ServerProfile("allowed", "Allowed", "https://allowed.example")
  private val denied = ServerProfile("denied", "Denied", "https://denied.example", parentEnabled = false)

  @Test fun overviewNeverDialsDisabledParentEvenWhenSelectedAndIncluded() = runBlocking {
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    val profile = denied.copy(kind = ServerKind.DSH, sshHostId = "ssh-source")
    store.save(profile, password = null)
    val cache = OfflineCache(memoryPreferences(), { it }, { it })
    var created = 0
    var closed = 0
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    val snapshots = mutableMapOf<String, ServerOverviewSnapshot>()
    val overview = AllServersOverview(store, cache, scope, { snapshots[it.serverId] = it },
      selectedServer = { profile.id }, onSnapshotRemoved = { snapshots.remove(it) },
      sshClientFactory = { _, _ -> created++; fakeClient { closed++ } })
    try {
      overview.start(listOf(profile), include = setOf(profile.id))
      yield()
      assertEquals(0, created)
      assertTrue(snapshots.isEmpty())
      assertTrue(runCatching { overview.client(profile.id) }.isFailure)

      // Enabling only the parent starts the existing leaf, without rewriting its local preference.
      overview.start(listOf(profile.copy(parentEnabled = true)), include = setOf(profile.id))
      withTimeout(5_000) { while (snapshots[profile.id]?.connected != true) yield() }
      assertEquals(1, created)
      val client = overview.client(profile.id)
      assertSame(client, overview.client(profile.id))

      overview.start(listOf(profile), include = setOf(profile.id))
      yield()
      assertEquals(1, created)
      assertEquals(1, closed)
      assertTrue(snapshots.isEmpty())
      assertTrue(runCatching { overview.client(profile.id) }.isFailure)
      assertTrue(store.profiles().single().enabled)
    } finally {
      overview.stop()
      scope.cancel()
    }
  }

  @Test fun groupAndAllHomeExcludeDeniedParentsEvenWithOldEnabledSnapshots() {
    val group = DeviceGroup("stable-group", "Group", listOf(denied.id, allowed.id))
    val state = LagoonState(profiles = listOf(denied, allowed), deviceGroups = listOf(group), homeGroupId = group.id,
      serverId = denied.id, homeServerId = denied.id, connected = true,
      sessions = snapshot(denied, TaskPhase.THINKING).sessions,
      tasks = snapshot(denied, TaskPhase.THINKING).tasks, summary = TaskSummary(running = 1),
      // A persisted snapshot can still carry yesterday's enabled profile; today's parents win.
      allServerSnapshots = mapOf(denied.id to snapshot(denied.copy(parentEnabled = true), TaskPhase.THINKING),
        allowed.id to snapshot(allowed, TaskPhase.WAITING_QUESTION)))

    assertTrue(denied.enabled)
    assertFalse(denied.effectiveEnabled)
    assertEquals(listOf(allowed.id), state.enabledProfiles.map { it.id })
    assertEquals(1, state.disabledProfileCount)
    assertEquals(listOf(allowed.id), state.homeGroupProfiles.map { it.id })
    assertEquals(listOf(allowed.id), state.deviceTabs.single().profiles.map { it.id })
    assertEquals(setOf(allowed.id), state.serverSnapshots().keys)
    assertEquals(setOf(allowed.id), state.homeReadSnapshots().keys)
    assertEquals(0, state.globalSummary.running)
    assertEquals(1, state.globalSummary.waiting)
    assertEquals(SessionTarget(allowed.id, "same"), state.globalSummaryTarget)
    assertEquals(listOf(SessionTarget(allowed.id, "background")), state.globalBackgroundTargets)

    val home = state.homeProjection()
    assertEquals(group.id, home.homeGroupId)
    assertEquals(group.memberIds, home.deviceGroups.single().memberIds)
    assertEquals(allowed.id, home.serverId)
    assertEquals(listOf(allowed.id), home.sessions.map { it.serverId })
    assertEquals(setOf(scopedKey(allowed.id, "same")), home.allSessionStatuses.keys)
    assertEquals(setOf(scopedKey(allowed.id, "result")), home.allUnseenSessions)
    assertEquals(0, home.summary.running)
    assertEquals(1, home.summary.waiting)
    assertEquals(1, home.summary.completed)
    assertSame(home, state.homeProjection())
    // Projection neither deletes nor replaces the hidden server's cached snapshot.
    assertEquals(setOf(denied.id, allowed.id), state.allServerSnapshots.keys)

    val all = state.copy(homeGroupId = null, allServers = true).homeProjection()
    assertEquals(listOf(allowed.id), all.sessions.map { it.serverId })
    assertEquals(0, all.summary.running)
    assertEquals(1, all.summary.waiting)
    assertEquals(setOf(allowed.id), all.allServerSnapshots.keys)
  }

  @Test fun allDeniedGroupKeepsItsIdentityAndDoesNotFallBackToAnOutsideConnection() {
    val group = DeviceGroup("stable-group", "Group", listOf(denied.id))
    val state = LagoonState(profiles = listOf(denied, allowed), deviceGroups = listOf(group), homeGroupId = group.id,
      serverId = denied.id, homeServerId = allowed.id, connected = true,
      sessions = snapshot(denied, TaskPhase.THINKING).sessions, tasks = snapshot(denied, TaskPhase.THINKING).tasks,
      summary = TaskSummary(running = 1), allServerSnapshots = mapOf(
        denied.id to snapshot(denied, TaskPhase.THINKING), allowed.id to snapshot(allowed, TaskPhase.THINKING)))

    val home = state.homeProjection()
    assertEquals(group.id, home.homeGroupId)
    assertEquals(group.memberIds, home.deviceGroups.single().memberIds)
    assertFalse(home.allServers)
    assertNull(home.serverId)
    assertNull(home.homeServerId)
    assertTrue(home.sessions.isEmpty())
    assertTrue(home.tasks.isEmpty())
    assertTrue(home.allServerSnapshots.isEmpty())
    assertTrue(home.allSessionStatuses.isEmpty())
    assertTrue(home.allUnseenSessions.isEmpty())
    assertTrue(home.summary.isEmpty)
    assertFalse(home.connected)
    assertSame(home, state.homeProjection())
    assertEquals(2, state.allServerSnapshots.size)
  }

  @Test fun singleHomeFallsBackToAllowedProfileWithoutLeakingDeniedSummary() {
    val state = LagoonState(profiles = listOf(denied, allowed), serverId = denied.id, homeServerId = denied.id,
      sessions = snapshot(denied, TaskPhase.THINKING).sessions, tasks = snapshot(denied, TaskPhase.THINKING).tasks,
      backgroundRunning = setOf("background"), summary = TaskSummary(running = 1),
      allServerSnapshots = mapOf(denied.id to snapshot(denied, TaskPhase.THINKING)))

    assertTrue(state.globalSummary.isEmpty)
    assertNull(state.globalSummaryTarget)
    assertTrue(state.globalBackgroundTargets.isEmpty())
    val home = state.homeProjection()
    assertEquals(allowed.id, home.serverId)
    assertTrue(home.sessions.isEmpty())
    assertTrue(home.tasks.isEmpty())
    assertTrue(home.summary.isEmpty)
    assertTrue(home.globalSummary.isEmpty)
    assertTrue(home.backgroundRunning.isEmpty())
  }

  @Test fun allParentsDeniedProducesEmptyHomeAndNoSystemSummary() {
    val state = LagoonState(profiles = listOf(denied), serverId = denied.id, homeServerId = denied.id, connected = true,
      sessions = snapshot(denied, TaskPhase.THINKING).sessions, tasks = snapshot(denied, TaskPhase.THINKING).tasks,
      summary = TaskSummary(running = 1), allServerSnapshots = mapOf(denied.id to snapshot(denied, TaskPhase.THINKING)))

    assertTrue(state.globalSummary.isEmpty)
    assertTrue(state.copy(allServerSnapshots = emptyMap()).globalSummary.isEmpty)
    assertNull(state.globalSummaryTarget)
    val home = state.homeProjection()
    assertTrue(home.allServers)
    assertNull(home.serverId)
    assertTrue(home.sessions.isEmpty())
    assertTrue(home.summary.isEmpty)
    assertTrue(home.globalSummary.isEmpty)
  }

  private fun snapshot(profile: ServerProfile, phase: TaskPhase) = ServerOverviewSnapshot(
    profile.id, profile, connected = true, loading = false,
    sessions = listOf(Session("same", "/repo", profile.name, 1, serverId = profile.id)),
    tasks = mapOf("same" to TaskState("same", phase)),
    notices = listOf(SessionNotice("notice", "result", 1, false)), backgroundRunning = setOf("background"))

  /** Same minimal proxy pattern as OverviewReconciliationTest; no HTTP, SSH or Android calls. */
  private fun fakeClient(onClose: () -> Unit): HarnessClient {
    var closed = false
    return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HarnessClient::class.java)) { proxy, method, args ->
      when (method.name) {
        "capabilities", "discoverCapabilities" -> ApiCapabilities()
        "health" -> "test"
        "projects", "projectsForDisplay", "shells", "pendingPermissions", "forms", "sessionPermissions", "inbox" -> emptyList<Any>()
        "rootSessionsPage" -> ApiPage<Session>(emptyList(), null)
        "activeSessions" -> emptySet<String>()
        "getRecoverableQuestions", "getCacheableTranscripts" -> false
        "isClosed" -> closed
        "close" -> { if (!closed) { closed = true; onClose() }; null }
        "setContentWatching", "transportStatus", "getWorkspaceUpdates" -> null
        "events" -> {
          @Suppress("UNCHECKED_CAST") val onOpen = args!![0] as () -> Unit
          flow<ServerEvent> { onOpen(); awaitCancellation() }
        }
        "toString" -> "hierarchy-test-client"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.get(0)
        else -> throw UnsupportedOperationException(method.name)
      }
    } as HarnessClient
  }
}
