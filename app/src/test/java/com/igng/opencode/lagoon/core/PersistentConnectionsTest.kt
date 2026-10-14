package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PersistentConnectionsTest {
  @Test fun persistentStreamsCannotStarveControlRequestsOnTheSameProxyHost() {
    assertNotSame(SharedHttp.dispatcher, SharedHttp.streamDispatcher)
    assertTrue(SharedHttp.streamDispatcher.maxRequestsPerHost > 5)
  }
  private fun store() = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
  private fun cache() = OfflineCache(memoryPreferences(), { it }, { it })
  private fun profile(id: String, server: MockWebServer) = ServerProfile(id, id, server.url("/").toString(), allowCleartext = true)
  private fun dispatcher(streams: AtomicInteger, permission: () -> Boolean = { false }, reply: (() -> Unit)? = null) = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      val path = request.requestUrl!!.encodedPath
      if (path == "/api/event") {
        streams.incrementAndGet()
        return MockResponse().setHeader("Content-Type", "text/event-stream")
          .setBody("data: {\"type\":\"server.connected\",\"data\":{}}\n\n")
      }
      if (path.endsWith("/reply")) {
        assertEquals("POST", request.method)
        assertEquals("once", org.json.JSONObject(request.body.readUtf8()).str("decision"))
        reply?.invoke()
        return MockResponse().setResponseCode(204)
      }
      val body = when (path) {
        "/api/info" -> """{"version":"2.0.22"}"""
        "/api/session" -> """{"data":[{"id":"s","title":"Session","location":{"directory":"/repo"},"time":{"idle":1,"updated":2}}],"cursor":{}}"""
        "/api/session/active" -> """{"data":{"s":{"type":"running"}}}"""
        "/api/permission/request", "/api/session/s/permission" -> if (permission())
          """{"data":[{"id":"p","sessionID":"s","action":"shell","resources":["echo hi"]}]}""" else """{"data":[]}"""
        else -> """{"data":[]}"""
      }
      return MockResponse().setBody(body)
    }
  }

  private suspend fun awaitCondition(test: () -> Boolean) = withTimeout(8000) { while (!test()) delay(20) }

  @Test fun filterChangesShareClientsAndDoNotDisconnectTheOtherServer() = runBlocking {
    MockWebServer().use { first -> MockWebServer().use { second ->
      val firstStreams = AtomicInteger(); val secondStreams = AtomicInteger()
      first.dispatcher = dispatcher(firstStreams); second.dispatcher = dispatcher(secondStreams)
      val store = store(); val a = profile("a", first); val b = profile("b", second)
      store.save(a, null); store.save(b, null)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      var selected: String? = null
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, cache(), scope, { snapshots[it.serverId] = it }, { selected })
      try {
        manager.start(listOf(a, b))
        awaitCondition { snapshots["a"]?.connected == true && snapshots["b"]?.connected == true }
        val aClient = manager.client("a"); val bClient = manager.client("b")
        repeat(10) { selected = if (it % 2 == 0) "a" else "b"; manager.start(listOf(a, b)); manager.refreshAll() }
        assertSame(aClient, manager.client("a")); assertSame(bClient, manager.client("b"))
        awaitCondition { firstStreams.get() > 0 && secondStreams.get() > 0 }
        manager.start(listOf(b))
        assertSame(bClient, manager.client("b"))
      } finally { manager.stop(); scope.cancel() }
    } }
  }

  @Test fun droppedStreamsAutomaticallyReconnectWithoutNavigation() = runBlocking {
    MockWebServer().use { server ->
      val streams = AtomicInteger(); server.dispatcher = dispatcher(streams)
      val store = store(); val profile = profile("a", server); store.save(profile, null)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val manager = AllServersOverview(store, cache(), scope, {})
      try {
        manager.start(listOf(profile))
        awaitCondition { streams.get() >= 2 }
      } finally { manager.stop(); scope.cancel() }
    }
  }

  @Test fun existingRequestsOnUnopenedServersAreAutomaticallyAllowedOnce() = runBlocking {
    MockWebServer().use { server ->
      val replies = AtomicInteger(); val streams = AtomicInteger()
      server.dispatcher = dispatcher(streams, { replies.get() == 0 }, { replies.incrementAndGet() })
      val store = store(); val profile = profile("a", server); store.save(profile, null)
      store.rememberPermissionPolicy(PermissionPolicy(autoApprove = true))
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, cache(), scope, { snapshots[it.serverId] = it })
      try {
        manager.start(listOf(profile))
        awaitCondition { replies.get() == 1 && snapshots["a"]?.permissions?.isEmpty() == true }
        manager.refreshAll()
        delay(200)
        assertEquals(1, replies.get())
        assertEquals(TaskPhase.THINKING, snapshots["a"]?.tasks?.get("s")?.phase)
      } finally { manager.stop(); scope.cancel() }
    }
  }

  @Test fun successfulEmptyPermissionReadsClearPersistedFalseWaiting() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = dispatcher(AtomicInteger())
      val store = store(); val profile = profile("a", server); store.save(profile, null)
      store.rememberTask("a", TaskState("s", TaskPhase.WAITING_PERMISSION, activeAt = 1))
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, cache(), scope, { snapshots[it.serverId] = it })
      try {
        manager.start(listOf(profile))
        awaitCondition { snapshots["a"]?.connected == true }
        assertTrue(snapshots.getValue("a").permissions.isEmpty())
        assertEquals(TaskPhase.THINKING, snapshots.getValue("a").tasks.getValue("s").phase)
      } finally { manager.stop(); scope.cancel() }
    }
  }

  @Test fun aReplyEventPreventsAnOlderPendingReadFromResurrectingTheRequest() = runBlocking {
    MockWebServer().use { server ->
      val readStarted = java.util.concurrent.CountDownLatch(1)
      val streams = AtomicInteger()
      val base = dispatcher(streams)
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
          "/api/permission/request" -> {
            readStarted.countDown()
            MockResponse().setBody("""{"data":[{"id":"p","sessionID":"s","action":"shell","resources":["echo hi"]}]}""")
              .setBodyDelay(700, java.util.concurrent.TimeUnit.MILLISECONDS)
          }
          "/api/event" -> {
            assertTrue(readStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            MockResponse().setHeader("Content-Type", "text/event-stream")
              .setBody("data: {\"id\":\"replied\",\"type\":\"permission.replied\",\"location\":{\"directory\":\"/repo\"},\"data\":{\"sessionID\":\"s\",\"requestID\":\"p\"}}\n\n")
          }
          else -> base.dispatch(request)
        }
      }
      val store = store(); val profile = profile("a", server); store.save(profile, null)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, cache(), scope, { snapshots[it.serverId] = it })
      try {
        manager.start(listOf(profile))
        awaitCondition { snapshots["a"]?.connected == true }
        assertTrue(snapshots.getValue("a").permissions.isEmpty())
        assertNotEquals(TaskPhase.WAITING_PERMISSION, snapshots.getValue("a").tasks["s"]?.phase)
      } finally { manager.stop(); scope.cancel() }
    }
  }

  @Test fun completionOnAnUnopenedServerUpdatesItsUnreadLedgerImmediately() = runBlocking {
    MockWebServer().use { server ->
      val base = dispatcher(AtomicInteger())
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = if (request.requestUrl!!.encodedPath == "/api/event")
          MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setBody("data: {\"id\":\"done\",\"type\":\"session.execution.succeeded\",\"data\":{\"sessionID\":\"s\"}}\n\n")
        else base.dispatch(request)
      }
      val store = store(); val profile = profile("a", server); store.save(profile, null)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, cache(), scope, { snapshots[it.serverId] = it })
      try {
        manager.start(listOf(profile))
        awaitCondition { snapshots["a"]?.notices?.isNotEmpty() == true }
        assertEquals("s", store.sessionNotices("a").single().sessionId)
        assertEquals(1, snapshots.getValue("a").displaySummary(PermissionPolicy()).completed)
      } finally { manager.stop(); scope.cancel() }
    }
  }

  @Test fun aServerWithAutoConnectOffIsOnlyConnectedWhenExplicitlyIncluded() = runBlocking {
    MockWebServer().use { server ->
      val requests = AtomicInteger()
      val base = dispatcher(AtomicInteger())
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          requests.incrementAndGet()
          return base.dispatch(request)
        }
      }
      val store = store()
      val profile = profile("a", server).copy(autoConnect = false)
      store.save(profile, null)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, cache(), scope, { snapshots[it.serverId] = it })
      try {
        manager.start(listOf(profile))
        delay(300)
        assertEquals("关闭自动连接的服务器不应被拨号", 0, requests.get())
        manager.start(listOf(profile), include = setOf("a"))
        awaitCondition { snapshots["a"]?.connected == true }
        assertTrue(requests.get() > 0)
      } finally { manager.stop(); scope.cancel() }
    }
  }

  @Test fun sessionsDeletedOnTheServerArePrunedFromTheOverview() = runBlocking {
    MockWebServer().use { server ->
      val listed = java.util.concurrent.atomic.AtomicReference(sessionPage(listOf("s")))
      // 会话从未运行：否则活跃任务会（按设计）把它钉在概览里。
      val active = java.util.concurrent.atomic.AtomicReference(activePage(emptyList()))
      val base = dispatcher(AtomicInteger())
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
          "/api/session" -> MockResponse().setBody(listed.get())
          "/api/session/active" -> MockResponse().setBody(active.get())
          else -> base.dispatch(request)
        }
      }
      val store = store(); val profile = profile("a", server); store.save(profile, null)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, cache(), scope, { snapshots[it.serverId] = it })
      try {
        manager.start(listOf(profile))
        awaitCondition { snapshots["a"]?.sessions?.any { it.id == "s" } == true }
        listed.set(sessionPage(emptyList()))
        active.set(activePage(emptyList()))
        manager.refreshAll()
        awaitCondition { snapshots["a"]?.sessions?.none { it.id == "s" } == true }
      } finally { manager.stop(); scope.cancel() }
    }
  }

  /** `GET /api/session` 的一页根会话（仅测试用）。 */
  private fun sessionPage(ids: List<String>) = org.json.JSONObject().put("data", org.json.JSONArray(ids.map { id ->
    org.json.JSONObject().put("id", id).put("title", "Session").put("location", org.json.JSONObject().put("directory", "/repo"))
      .put("time", org.json.JSONObject().put("idle", 1).put("updated", 2))
  })).put("cursor", org.json.JSONObject()).toString()

  private fun activePage(ids: List<String>) = org.json.JSONObject().put("data",
    org.json.JSONObject().also { data -> ids.forEach { data.put(it, org.json.JSONObject().put("type", "running")) } }).toString()

}
