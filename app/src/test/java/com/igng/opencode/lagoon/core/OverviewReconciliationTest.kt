package com.igng.opencode.lagoon.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual all-server HTTP reader, not just its pure task rule. */
class OverviewReconciliationTest {
  private val root = Session("root", "/repo", "Root", 200, idle = 200, outcome = "succeeded", serverId = "a")
  private fun background(baseline: Long = -1) = TaskState("root", TaskPhase.THINKING,
    TaskState.RUNNING_DETAIL, since = 100, activeAt = baseline)
  private fun dispatcher(active: String = "{}", shellCode: Int = 200, childCode: Int = 200) = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      val path = request.requestUrl!!.encodedPath
      if (path == "/api/shell" && shellCode != 200) return MockResponse().setResponseCode(shellCode)
      if (path == "/api/session/child" && childCode != 200) return MockResponse().setResponseCode(childCode)
      val body = when (path) {
        "/api/info" -> """{"version":"2.0.22"}"""
        "/api/session" -> """{"data":[{"id":"root","title":"Root","location":{"directory":"/repo"},"outcome":"succeeded","time":{"updated":200,"idle":200}}],"cursor":{}}"""
        "/api/session/active" -> """{"data":$active}"""
        "/api/session/child" -> """{"data":{"id":"child","parentID":"root","location":{"directory":"/repo"},"outcome":"succeeded","time":{"updated":210,"idle":210}}}"""
        else -> """{"data":[]}"""
      }
      return MockResponse().setBody(body)
    }
  }

  private suspend fun read(server: MockWebServer, tasks: Map<String, TaskState>, parents: Map<String, String> = emptyMap(),
    children: List<Session> = emptyList(), permissions: List<PermissionRequest> = emptyList(), unchanged: () -> Boolean = { true }): ServerOverviewSnapshot {
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    tasks.values.forEach { store.rememberTask("a", it, parents[it.sessionId]) }
    val profile = ServerProfile("a", "A", server.url("/").toString(), allowCleartext = true)
    val scope = CoroutineScope(Job().apply { cancel() })
    val overview = AllServersOverview(store, OfflineCache(memoryPreferences(), { it }, { it }), scope, {})
    val previous = ServerOverviewSnapshot("a", profile, connected = true, sessions = listOf(root) + children, tasks = tasks, parents = parents, permissions = permissions)
    return suspendCoroutine { continuation ->
      try {
        val method = AllServersOverview::class.java.getDeclaredMethod("read", ServerProfile::class.java,
          HarnessClient::class.java, ServerOverviewSnapshot::class.java, Function0::class.java, Continuation::class.java).apply { isAccessible = true }
        val result = method.invoke(overview, profile, OpenCodeApi(profile, "fixture"), previous, unchanged, continuation)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(result as ServerOverviewSnapshot)
      } catch (error: Throwable) { continuation.resumeWithException(error.cause ?: error) }
    }
  }

  @Test fun overviewClearsTheSameUnknownAndEqualBaselineRootsAsTheSelectedConnection() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = dispatcher()
      listOf(-1L, 199L).forEach { baseline ->
        val snapshot = read(server, mapOf("root" to background(baseline)))
        assertFalse(snapshot.tasks.getValue("root").active)
        assertEquals(0, snapshot.summary.running)
        assertEquals(listOf("root"), snapshot.notices.map { it.sessionId })
        assertEquals(1, snapshot.summary.completed)
      }
    }
  }

  @Test fun foregroundReadsClearPersistedBackgroundFlags() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = dispatcher(active = """{"root":{"type":"running"}}""")
      val snapshot = read(server, mapOf("root" to background().copy(uncertain = true)))
      assertTrue(snapshot.tasks.getValue("root").active)
      assertFalse(snapshot.tasks.getValue("root").uncertain)
      assertEquals(1, snapshot.summary.running)
      assertTrue(snapshot.backgroundRunning.isEmpty())
    }
  }

  @Test fun shellReadFailuresDoNotCountAsEitherRunningOrCompleted() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = dispatcher(shellCode = 500)
      val snapshot = read(server, mapOf("root" to background()))
      assertTrue(snapshot.tasks.getValue("root").uncertain)
      assertTrue(snapshot.degraded)
      assertEquals(0, snapshot.summary.running)
      assertTrue(snapshot.notices.isEmpty())
      val state = LagoonState(allServerSnapshots = mapOf("a" to snapshot))
      assertEquals(SessionStatus.UNKNOWN, state.allServerStatuses()[root.key])
    }
  }

  @Test fun aDeletedActiveChildCannotKeepTheRootRunning() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = dispatcher(childCode = 404)
      val child = Session("child", "/repo", "Child", 1, parentId = "root")
      val snapshot = read(server, mapOf("root" to background(), "child" to TaskState("child", TaskPhase.SUBAGENT)),
        parents = mapOf("child" to "root"), children = listOf(child))
      assertNull(snapshot.tasks["child"])
      assertFalse(snapshot.tasks.getValue("root").active)
      assertTrue(snapshot.sessions.none { it.id == "child" })
      assertEquals(0, snapshot.summary.running)
    }
  }

  @Test fun aMissingChildIdentityDoesNotLeaveTheRootSpinning() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = dispatcher(childCode = 500)
      val child = Session("child", "/repo", "Child", 1, parentId = "root")
      val snapshot = read(server, mapOf("root" to background(), "child" to TaskState("child", TaskPhase.SUBAGENT, since = 100, activeAt = -1)),
        parents = mapOf("child" to "root"), children = listOf(child))
      assertTrue(snapshot.tasks.getValue("child").uncertain)
      assertFalse("the root settles on its own idle baseline", snapshot.tasks.getValue("root").active)
      assertEquals(listOf("root"), snapshot.notices.map { it.sessionId })
    }
  }

  @Test fun aNewLifecycleEventFencesAnOlderHttpSnapshot() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = dispatcher()
      val snapshot = read(server, mapOf("root" to background()), unchanged = { false })
      assertTrue(snapshot.tasks.getValue("root").active)
      assertTrue(snapshot.notices.isEmpty())
    }
  }

  @Test fun aFailedChildSweepCannotCompleteTheRoot() = runBlocking {
    MockWebServer().use { server ->
      val base = dispatcher()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = if (request.requestUrl!!.queryParameter("parentID") == "root")
          MockResponse().setResponseCode(500) else base.dispatch(request)
      }
      val snapshot = read(server, mapOf("root" to background()))
      assertTrue(snapshot.tasks.getValue("root").uncertain)
      assertTrue(snapshot.notices.isEmpty())
    }
  }

  @Test fun newlyDiscoveredShellOwnersAreResolvedBeforeTheirRootCanFinish() = runBlocking {
    MockWebServer().use { server ->
      val base = dispatcher()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
          "/api/shell" -> MockResponse().setBody("""{"data":[{"id":"sh","status":"running","metadata":{"sessionID":"child"},"time":{"started":100}}]}""")
          else -> base.dispatch(request)
        }
      }
      val snapshot = read(server, mapOf("root" to background()))
      assertEquals("root", snapshot.parents["child"])
      assertEquals(setOf("root"), snapshot.backgroundRunning)
      assertFalse("the root's own execution settled", snapshot.tasks.getValue("root").active)
      assertFalse(snapshot.tasks.getValue("root").uncertain)
      assertEquals(listOf("root"), snapshot.notices.map { it.sessionId })
    }
  }

  @Test fun oneSessionsEmptyPermissionReadCannotClearAnotherSessionsFailedReadInTheSameDirectory() = runBlocking {
    MockWebServer().use { server ->
      val base = dispatcher(active = """{"root":{"type":"running"},"child":{"type":"running"}}""")
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = if (request.requestUrl!!.encodedPath == "/api/session/child/permission")
          MockResponse().setResponseCode(500) else base.dispatch(request)
      }
      val pending = PermissionRequest("p", "child", "/repo", "shell", "Needs approval")
      val snapshot = read(server, mapOf("root" to TaskState("root", TaskPhase.THINKING),
        "child" to TaskState("child", TaskPhase.WAITING_PERMISSION)), mapOf("child" to "root"),
        listOf(Session("child", "/repo", "Child", 1, parentId = "root")), listOf(pending))
      assertEquals(listOf("p"), snapshot.permissions.map { it.id })
      assertEquals(TaskPhase.WAITING_PERMISSION, snapshot.tasks.getValue("child").phase)
    }
  }

  @Test fun admittedInboxInputPreventsCompletionUsingThePreviousOutcome() = runBlocking {
    MockWebServer().use { server ->
      val base = dispatcher()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = if (request.requestUrl!!.encodedPath == "/api/session/root/inbox")
          MockResponse().setBody("""{"data":[{"id":"pending","type":"compaction","payload":{},"time":{"created":300}}]}""")
        else base.dispatch(request)
      }
      val snapshot = read(server, mapOf("root" to background()))
      assertTrue(snapshot.tasks.getValue("root").pendingInput)
      assertEquals(TaskPhase.WAITING_SERVER, snapshot.tasks.getValue("root").phase)
      assertTrue(snapshot.notices.isEmpty())
      assertEquals(0, snapshot.summary.completed)
    }
  }
}
