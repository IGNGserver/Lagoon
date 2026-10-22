package com.igng.opencode.lagoon.core

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise actual Controller operations across delayed responses and context switches. */
class ControllerRegressionTest {
  private fun put(target: Any, name: String, value: Any?) {
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
  }
  private fun preferences(): SharedPreferences {
    val data = mutableMapOf<String, Any?>()
    val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
      when {
        method.name.startsWith("put") -> { data[args!![0] as String] = args[1]; proxy }
        method.name == "remove" -> { data.remove(args!![0]); proxy }
        method.name == "clear" -> { data.clear(); proxy }
        method.name == "commit" -> true
        else -> null
      }
    }
    return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
      when(method.name) {
        "edit" -> editor
        "getAll" -> data.toMap()
        "contains" -> data.containsKey(args!![0])
        "getString", "getBoolean", "getInt", "getLong", "getFloat", "getStringSet" -> data[args!![0]] ?: args[1]
        else -> null
      }
    } as SharedPreferences
  }
  private val context = object: ContextWrapper(null) {
    private val stores = mutableMapOf<String, SharedPreferences>()
    override fun getSharedPreferences(name: String, mode: Int) = stores.getOrPut(name) { preferences() }
  }
  private fun controller(api: OpenCodeApi, initial: LagoonState): Pair<LagoonController, MutableStateFlow<LagoonState>> {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val c = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LagoonController::class.java) as LagoonController
    val mutable = MutableStateFlow(initial)
    put(c,"mutable", mutable); put(c,"state",mutable); put(c,"generation",1); put(c,"api",api)
    put(c,"operationScope",CoroutineScope(SupervisorJob()+Dispatchers.Unconfined))
    put(c,"scope",CoroutineScope(Job().apply { cancel() }+Dispatchers.Default))
    put(c,"store",ServerStore(preferences(),preferences(),{it},{it}));put(c,"cache",OfflineCache(context.getSharedPreferences("cache",0),{it},{it}));put(c,"sendMutex",kotlinx.coroutines.sync.Mutex()); put(c,"referenceMutex",kotlinx.coroutines.sync.Mutex()); put(c,"monitorRequested",mutableSetOf<String>()); put(c,"draftWrites",mutableMapOf<String,Job>()); put(c,"attentionJobs",mutableMapOf<String,Job>()); put(c,"waitingEventSeqAt",mutableMapOf<String,Long>())
    put(c, "resolvedPermissions", linkedSetOf<String>()); put(c,"backgroundSettles",mutableMapOf<String,Job>()); put(c,"directoryHealth",DirectoryHealth())
    put(c, "connectTrace", ConnectTrace()); put(c, "openTrace", ConnectTrace()); put(c, "transcriptMemory", TranscriptMemory()); put(c, "queuedSends", mutableListOf<Any>())
    return c to mutable
  }
  private fun api(s:MockWebServer) = OpenCodeApi(ServerProfile("server","Server",s.url("/").toString(),allowCleartext=true), "fixture")
  private suspend fun refresh(c:LagoonController, controlOnly:Boolean = true) = suspendCoroutine<Unit> { cont ->
    try {
      val m = LagoonController::class.java.getDeclaredMethod("loadAll", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Continuation::class.java).apply{isAccessible=true}
      val result=m.invoke(c,1,controlOnly,true,cont)
      if(result !== COROUTINE_SUSPENDED) cont.resume(Unit)
    }catch(e:Throwable){cont.resumeWithException(e.cause?:e)}
  }
  private suspend fun readTranscript(c: LagoonController, client: OpenCodeApi, session: Session) = suspendCoroutine<Unit> { continuation ->
    try {
      val method = LagoonController::class.java.getDeclaredMethod("loadSession", Session::class.java, Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, HarnessClient::class.java, Continuation::class.java).apply { isAccessible = true }
      val result = method.invoke(c, session, false, 1, client, continuation)
      if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
    } catch (error: Throwable) { continuation.resumeWithException(error.cause ?: error) }
  }

  private fun viewing(c: LagoonController, server: String, session: String, foreground: Boolean = true) {
    put(c, "foreground", foreground); c.conversationVisible(server, session, true)
  }

  @Test fun openingASessionClearsEveryUnreadResultImmediately() {
    val session = Session("s", "/repo", "Title", 1)
    val now = System.currentTimeMillis()
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    val notices = listOf(SessionNotice("first", "s", now, false), SessionNotice("second", "s", now + 1, true), SessionNotice("other", "o", now, false))
      .fold(emptyList<SessionNotice>()) { _, notice -> store.rememberNotice("server", notice) }
    val (controller, state) = controller(api(MockWebServer()), LagoonState(serverId = "server", connected = true, sessions = listOf(session), sessionId = "s", notices = notices))
    put(controller, "store", store)
    // Official MarkSessionNotificationsViewed: no transcript round trip is required.
    viewing(controller, "server", "s")
    assertTrue(state.value.notices.filter { it.sessionId == "s" }.all { it.viewed })
    assertFalse(state.value.notices.single { it.sessionId == "o" }.viewed)
    assertTrue(store.sessionNotices("server").filter { it.sessionId == "s" }.all { it.viewed })
    assertEquals(SessionStatus.NONE, state.value.sessionStatus(session))
    // Only the other session's unread result is still counted on the island.
    assertEquals(1, state.value.summary.completed)
    assertEquals(0, state.value.summary.failed)
  }

  @Test fun aConversationLeftOpenInTheBackgroundDoesNotReadNewResults() {
    val session = Session("s", "/repo", "Title", 1)
    val notice = SessionNotice("result", "s", System.currentTimeMillis(), false)
    val (controller, state) = controller(api(MockWebServer()), LagoonState(serverId = "server", connected = true, sessions = listOf(session), sessionId = "s", notices = listOf(notice)))
    viewing(controller, "server", "s", foreground = false)
    assertFalse(state.value.notices.single().viewed)
    assertEquals(SessionStatus.COMPLETED, state.value.sessionStatus(session))
  }

  @Test fun markingAllReadClearsTheLocalLedgerButKeepsLiveTasksRunning() {
    val profile = ServerProfile("server", "Server", "https://server.example")
    val session = Session("s", "/repo", "Title", 1)
    val now = System.currentTimeMillis()
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    val notices = listOf(SessionNotice("first", "s", now, false), SessionNotice("second", "other", now + 1, true))
      .fold(emptyList<SessionNotice>()) { _, notice -> store.rememberNotice("server", notice) }
    val (controller, state) = controller(api(MockWebServer()), LagoonState(profiles = listOf(profile),
      serverId = "server", connected = true, sessions = listOf(session), notices = notices,
      tasks = mapOf("s" to TaskState("s", TaskPhase.THINKING, since = now))))
    put(controller, "store", store)
    put(controller, "allSnapshots", mutableMapOf<String, ServerOverviewSnapshot>())
    controller.markAllRead()
    assertTrue(state.value.notices.all { it.viewed })
    assertTrue(store.sessionNotices("server").all { it.viewed })
    assertEquals(0, state.value.summary.completed)
    assertEquals(0, state.value.summary.failed)
    assertEquals(1, state.value.summary.running)
    assertEquals("已将 2 个会话标记为已读", state.value.message)
  }

  @Test fun markingAllReadCoversEveryProjectedServerInAllMode() {
    val profileA = ServerProfile("a", "A", "https://a.example")
    val profileB = ServerProfile("b", "B", "https://b.example")
    val now = System.currentTimeMillis()
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    val aNotices = listOf(SessionNotice("a1", "sa", now, false), SessionNotice("a2", "sa2", now + 1, true))
      .fold(emptyList<SessionNotice>()) { _, notice -> store.rememberNotice("a", notice) }
    val bNotices = listOf(SessionNotice("b1", "sb", now, false))
      .fold(emptyList<SessionNotice>()) { _, notice -> store.rememberNotice("b", notice) }
    val aSnapshot = ServerOverviewSnapshot("a", profileA, connected = true,
      sessions = listOf(Session("sa", "/repo", "A", 1)), notices = aNotices)
    val bSnapshot = ServerOverviewSnapshot("b", profileB, connected = true,
      sessions = listOf(Session("sb", "/repo", "B", 1)), notices = bNotices)
    val (controller, state) = controller(api(MockWebServer()), LagoonState(profiles = listOf(profileA, profileB),
      serverId = "a", homeServerId = "a", allServers = true, connected = true,
      sessions = aSnapshot.sessions, notices = aNotices,
      allServerSnapshots = mapOf("a" to aSnapshot, "b" to bSnapshot)))
    put(controller, "store", store)
    put(controller, "allSnapshots", linkedMapOf("a" to aSnapshot, "b" to bSnapshot))
    controller.markAllRead()
    assertTrue(state.value.notices.all { it.viewed })
    assertTrue(state.value.allServerSnapshots.getValue("a").notices.all { it.viewed })
    assertTrue(state.value.allServerSnapshots.getValue("b").notices.all { it.viewed })
    assertTrue(store.sessionNotices("a").all { it.viewed })
    assertTrue(store.sessionNotices("b").all { it.viewed })
  }

  @Test fun serverReadPostsTheExactIdleAndLeavesLocalUnreadAlone() = runBlocking {
    MockWebServer().use { server ->
      val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
      val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          paths += request.path.orEmpty()
          if (request.path == "/api/session/s/view") {
            bodies += request.body.readUtf8()
            return MockResponse().setResponseCode(200).setBody("{}")
          }
          return MockResponse().setResponseCode(404)
        }
      }
      val profile = ServerProfile("server", "Server", server.url("/").toString(), allowCleartext = true)
      val session = Session("s", "/repo", "Title", 1, idle = 100, viewed = 10)
      val notice = SessionNotice("n", "s", System.currentTimeMillis(), false)
      val (controller, state) = controller(OpenCodeApi(profile, "fixture"),
        LagoonState(profiles = listOf(profile), serverId = "server", connected = true,
          sessions = listOf(session), notices = listOf(notice), capabilities = ApiCapabilities.BASELINE))
      put(controller, "allSnapshots", mutableMapOf<String, ServerOverviewSnapshot>())
      controller.markServerRead()
      val job = controller.javaClass.getDeclaredField("serverReadJob").apply { isAccessible = true }.get(controller) as Job?
      requireNotNull(job).join()
      assertEquals(100L, state.value.sessions.single().viewed)
      assertFalse("服务端已读是独立动作，不得顺带清掉本机未读", state.value.notices.single().viewed)
      assertEquals(listOf("/api/session/s/view"), paths)
      assertTrue(bodies.single().contains("\"idle\":100"))
      assertFalse(state.value.serverReadPending)
    }
  }

  private fun v2Server(paths: MutableList<String>, sessionsBody: () -> String, active: () -> String, sessionBody: (String) -> String? = { null }): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      paths += request.path.orEmpty()
      val path = request.requestUrl!!.encodedPath
      sessionBody(path)?.let { return MockResponse().setBody(it) }
      val body = when (path) {
        "/openapi.json" -> return MockResponse().setResponseCode(404)
        "/api/info" -> """{"version":"2.0.22"}"""
        // The published V2 contract returns Project[] as a bare array.
        "/api/project" -> """[{"id":"p","canonical":"/repo","sandboxes":["/trees/task"],"name":"Project","time":{"created":1,"updated":1,"active":1}}]"""
        "/api/session" -> sessionsBody()
        "/api/session/active" -> active()
        else -> """{"data":[]}"""
      }
      return MockResponse().setBody(body)
    }
  }

  @Test fun aRunThisDeviceSawEndWhileEventsWereMissedBecomesOneUnreadResult() = runBlocking {
    MockWebServer().use { server ->
      val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
      // The finished worktree session has dropped out of the first page and out of the active list;
      // its authoritative re-read reports outcome/time.idle, which settles the run.
      server.dispatcher = v2Server(paths, { """{"data":[{"id":"old","projectID":"p","location":{"directory":"/repo"},"time":{"created":1,"updated":1}}],"cursor":{}}""" }, { """{"data":{}}""" },
        { path -> if (path == "/api/session/root") """{"data":{"id":"root","projectID":"p","outcome":"succeeded","location":{"directory":"/trees/task"},"time":{"created":1,"updated":200,"idle":200}}}""" else null })
      val root = Session("root", "/trees/task", "Worktree task", 50, projectId = "p")
      val (controller, state) = controller(api(server), LagoonState(serverId = "server", connected = true, sessions = listOf(root),
        tasks = mapOf("root" to TaskState("root", TaskPhase.TOOL, since = 100, activeAt = 150))))
      refresh(controller, controlOnly = false)
      assertTrue("a session seen running must not vanish when it finishes", state.value.sessions.any { it.id == "root" })
      assertEquals(SessionStatus.COMPLETED, state.value.sessionStatus(root))
      assertEquals(1, state.value.summary.completed)
      assertEquals(1, state.value.notices.size)
      refresh(controller, controlOnly = false)
      refresh(controller, controlOnly = true)
      assertEquals("reconciliation must not duplicate the result", 1, state.value.notices.size)
      assertNull("a finished run is not kept as a permanent phase", state.value.tasks["root"])
      assertTrue("an unread result keeps its session listed", state.value.sessions.any { it.id == "root" })
    }
  }

  @Test fun historicalAndInterruptedSessionsNeverGetACompletedMark() = runBlocking {
    MockWebServer().use { server ->
      val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
      server.dispatcher = v2Server(paths, { """{"data":[
        {"id":"done","projectID":"p","outcome":"succeeded","location":{"directory":"/repo"},"time":{"created":1,"updated":9,"idle":9}},
        {"id":"stopped","projectID":"p","outcome":"interrupted","location":{"directory":"/repo"},"time":{"created":1,"updated":8,"idle":8}}],"cursor":{}}""" }, { """{"data":{}}""" })
      val (controller, state) = controller(api(server), LagoonState(serverId = "server", connected = true,
        tasks = mapOf("stopped" to TaskState("stopped", TaskPhase.THINKING, since = 5, activeAt = 6))))
      refresh(controller, controlOnly = false)
      assertTrue(state.value.notices.isEmpty())
      assertTrue(state.value.sessions.all { state.value.sessionStatus(it) == SessionStatus.NONE })
      assertTrue(state.value.summary.isEmpty)
    }
  }

  @Test fun aChildRunRollsUpToOneRootResultAndNeverMarksTheChild() = runBlocking {
    MockWebServer().use { server ->
      val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
      var running = true
      server.dispatcher = v2Server(paths, { """{"data":[{"id":"root","projectID":"p","location":{"directory":"/repo"},"time":{"created":1,"updated":20}}],"cursor":{}}""" },
        { if (running) """{"data":{"child":{"type":"running"}}}""" else """{"data":{}}""" },
        { path -> if (path == "/api/session/child" && !running) """{"data":{"id":"child","projectID":"p","parentID":"root","location":{"directory":"/repo"},"time":{"created":1,"updated":30,"idle":30}}}""" else null })
      val root = Session("root", "/repo", "Root", 20, projectId = "p")
      val child = Session("child", "/repo", "Child", 20, parentId = "root", projectId = "p")
      val (controller, state) = controller(api(server), LagoonState(serverId = "server", connected = true, sessions = listOf(root, child)))
      refresh(controller, controlOnly = true)
      // 主线程（root 自身）没有在跑，只有子任务在跑 → 后台运行中（而不是普通运行中）。
      assertEquals(SessionStatus.BACKGROUND_RUNNING, state.value.sessionStatus(root))
      assertEquals(1, state.value.summary.running)
      assertEquals(1, state.value.summary.background)
      running = false
      refresh(controller, controlOnly = true)
      assertEquals(listOf("root"), state.value.notices.map { it.sessionId })
      assertEquals(SessionStatus.COMPLETED, state.value.sessionStatus(root))
    }
  }

  @Test fun reconciliationRecoversAnActiveChildFromTheRootListingAndDoesNotTrustAStaleRootRun() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val url = request.requestUrl!!
          return when (url.encodedPath) {
            "/openapi.json" -> MockResponse().setResponseCode(404)
            "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
            "/api/session/active" -> MockResponse().setBody("""{"data":{"child":{"type":"running"}}}""")
            "/api/session/child" -> MockResponse().setResponseCode(404)
            "/api/session" -> if (url.queryParameter("parentID") == "root")
              MockResponse().setBody("""{"data":[{"id":"child","parentID":"root","location":{"directory":"/repo"},"time":{"created":1,"updated":20}}],"cursor":{}}""")
            else MockResponse().setBody("""{"data":[{"id":"root","location":{"directory":"/repo"},"time":{"created":1,"updated":20}}],"cursor":{}}""")
            else -> MockResponse().setBody("""{"data":[]}""")
          }
        }
      }
      val root = Session("root", "/repo", "Root", 20)
      val (controller, state) = controller(api(server), LagoonState(serverId = "server", connected = true,
        projects = listOf(Project("p", "/repo", "Repo")), sessions = listOf(root),
        tasks = mapOf("root" to TaskState("root", TaskPhase.THINKING, since = 10, activeAt = -1))))
      refresh(controller, controlOnly = true)

      assertEquals("root", state.value.parents["child"])
      assertTrue(state.value.tasks["child"]?.active == true)
      assertTrue(state.value.tasks["root"]?.background == true)
      assertEquals(SessionStatus.BACKGROUND_RUNNING, state.value.sessionStatus(root))
    }
  }

  @Test fun nativeCatalogIncludesWorktreesWithoutNarrowingToTheSelectedProject() = runBlocking {
    MockWebServer().use { server ->
      val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        paths += request.path.orEmpty()
        val body = when (request.requestUrl!!.encodedPath) {
          "/openapi.json" -> return MockResponse().setResponseCode(404)
          "/api/info" -> """{"version":"2.0.22"}"""
          "/api/project" -> """{"data":[{"id":"p","canonical":"/repo","sandboxes":["/trees/task"],"name":"Project"},{"id":"q","canonical":"/other","name":"Other"}]}"""
          "/api/session" -> {
            assertNull(request.requestUrl!!.queryParameter("directory"))
            assertEquals("null", request.requestUrl!!.queryParameter("parentID"))
            """{"data":[{"id":"tree","projectID":"p","title":"Server title","location":{"directory":"/trees/task"},"time":{"updated":20}},{"id":"other","projectID":"q","title":"Other session","location":{"directory":"/other"},"time":{"updated":10}}],"cursor":{}}"""
          }
          "/api/session/active" -> """{"data":{}}"""
          else -> """{"data":[]}"""
        }
        return MockResponse().setBody(body)
      } }
      val (controller, state) = controller(api(server), LagoonState(serverId = "server", projectId = "q"))
      // Projects are device-local now: register the two server directories before refreshing.
      val store = LagoonController::class.java.getDeclaredField("store").apply { isAccessible = true }.get(controller) as ServerStore
      store.addLocalProject("server", Project("p", "/repo", "Project"))
      store.addLocalProject("server", Project("q", "/other", "Other"))
      refresh(controller, controlOnly = false)
      val groups = groupSessions(state.value.sessions, state.value.projects)
      assertEquals(listOf("p", "q"), groups.map { it.key })
      assertEquals("Server title", state.value.title(state.value.sessions.first()))
      assertEquals("q", state.value.projectId)
      assertEquals(1, paths.count { it.startsWith("/api/session/active") })
      assertFalse(paths.any { it.contains("/message") })
    }
  }

  /** 项目删除只发生在设备本地：移除后服务器目录与其中的数据不受影响。 */
  @Test fun removingAProjectOnlyDropsTheLocalRegistration() {
    MockWebServer().use { server ->
      val (c, state) = controller(api(server), LagoonState(serverId = "server", connected = true,
        projects = listOf(Project("a", "/a", "A"), Project("b", "/b", "B")), projectId = "a", scopeProjectId = "a"))
      val store = LagoonController::class.java.getDeclaredField("store").apply { isAccessible = true }.get(c) as ServerStore
      store.addLocalProject("server", Project("a", "/a", "A"))
      store.addLocalProject("server", Project("b", "/b", "B"))
      c.removeProject("a")
      assertEquals(listOf("b"), state.value.projects.map { it.id })
      assertEquals("b", state.value.projectId)
      assertNull(state.value.scopeProjectId)
      assertEquals(listOf("/b"), store.localProjects("server").map { it.directory })
    }
  }

  @Test fun offlineReadCannotOverwriteAChangedConnectionGeneration() = runBlocking {
    MockWebServer().use { server ->
      val (c,state)=controller(api(server),LagoonState(serverId="server",connected=true))
      val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
      val prefs=memoryPreferences()
      val cache=OfflineCache(prefs,{it},{entered.countDown();check(release.await(3,TimeUnit.SECONDS));it})
      cache.saveCatalog("server",emptyList(),emptyList());cache.awaitWrites();put(c,"cache",cache)
      val job=async {
        suspendCoroutine<Unit> { cont ->
          try {
            val m=LagoonController::class.java.getDeclaredMethod("showOffline",String::class.java,Int::class.javaPrimitiveType,String::class.java,Continuation::class.java).apply{isAccessible=true}
            val result=m.invoke(c,"server",1,"offline",cont)
            if(result !== COROUTINE_SUSPENDED)cont.resume(Unit)
          }catch(e:Throwable){cont.resumeWithException(e.cause?:e)}
        }
      }
      yield();assertTrue(entered.await(2,TimeUnit.SECONDS))
      put(c,"generation",2);state.value=state.value.copy(connected=true,error="new connection")
      release.countDown();job.await()
      assertTrue(state.value.connected);assertFalse(state.value.cached);assertEquals("new connection",state.value.error)
    }
  }
  @Test fun oldProjectFileResponseCannotOverwriteNewProject() = runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      s.enqueue(MockResponse().setBody("""{"type":"text","content":"private from project A"}""").setBodyDelay(250,TimeUnit.MILLISECONDS))
      val api=api(s);api.health();s.takeRequest()
      val(c,state)=controller(api,LagoonState(serverId="server",connected=true,projects=listOf(Project("a","/a","A"),Project("b","/b","B")),projectId="a"))
      val job=c.readFile("secret.txt");assertNotNull(s.takeRequest(2,TimeUnit.SECONDS))
      state.value=state.value.copy(projectId="b",sessionId="b-session")
      job.join();assertEquals("b",state.value.projectId);assertNull(state.value.fileText)
    }
  }
  @Test fun slowDeletePreservesNewlySelectedSession() = runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().setBody("""{"healthy":true}"""));s.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(250,TimeUnit.MILLISECONDS))
      val api=api(s);api.health();s.takeRequest()
      val(c,state)=controller(api,LagoonState(serverId="server",connected=true,sessions=listOf(Session("a","/repo","A",0),Session("b","/repo","B",0)),sessionId="a"))
      val job=c.deleteSession();assertNotNull(s.takeRequest(2,TimeUnit.SECONDS));state.value=state.value.copy(sessionId="b")
      job.join();assertEquals("b",state.value.sessionId)
    }
  }
  @Test fun failedActivityReadPreservesTaskAndSelection() = runBlocking {
    MockWebServer().use { s ->
      s.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
        val body=when(r.requestUrl!!.encodedPath) {
          "/api/info" -> """{"version":"2.0.22"}"""
          "/api/project" -> """[{"id":"a","canonical":"/a","sandboxes":[]},{"id":"b","canonical":"/b","sandboxes":[]}]"""
          "/api/session" -> """{"data":[{"id":"sb","location":{"directory":"/b"},"title":"B","time":{}}],"cursor":{}}"""
          "/api/session/active" -> return MockResponse().setResponseCode(500)
          else -> """{"data":[]}"""
        }; return MockResponse().setBody(body)
      }}
      val(c,state)=controller(api(s),LagoonState(serverId="server",connected=true,projects=listOf(Project("a","/a","A"),Project("b","/b","B")),projectId="a",sessions=listOf(Session("sa","/a","A",0)),sessionId="sa",tasks=mapOf("sa" to TaskState("sa",TaskPhase.THINKING))))
      refresh(c, controlOnly=false)
      assertTrue(state.value.degraded);assertTrue(state.value.sessions.any{it.id=="sa"});assertEquals(TaskPhase.THINKING,state.value.tasks["sa"]?.phase);assertEquals("sa",state.value.sessionId)
    }
  }
  @Test fun permissionsAreReadPerRunningSessionAndSurviveReconciliation() = runBlocking {
    MockWebServer().use { s ->
      val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
      s.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
        paths += r.path.orEmpty()
        val body=when(r.requestUrl!!.encodedPath) {
          "/api/info" -> """{"version":"2.0.22"}"""
          "/api/project" -> """[{"id":"p","canonical":"/work/repo","sandboxes":[]}]"""
          "/api/session" -> """{"data":[{"id":"ses_r","projectID":"p","location":{"directory":"/work/repo"},"title":"R","time":{"updated":5}}],"cursor":{}}"""
          "/api/session/active" -> """{"data":{"ses_r":{"type":"running"}}}"""
          "/api/session/ses_r/permission" -> """{"data":[{"id":"per_1","sessionID":"ses_r","action":"bash","resources":["rm -rf build"]}]}"""
          "/api/form" -> """{"location":{"directory":"/work/repo"},"data":[]}"""
          else -> """{"data":[]}"""
        }; return MockResponse().setBody(body)
      }}
      val(c,state)=controller(api(s),LagoonState(serverId="server",connected=true))
      refresh(c, controlOnly=false)
      assertEquals(listOf("per_1"), state.value.permissions.map { it.id })
      assertEquals(TaskPhase.WAITING_PERMISSION, state.value.tasks["ses_r"]?.phase)
      // The location-scoped list is never read without a location: it would only cover the server's cwd.
      assertFalse(paths.any { it.startsWith("/api/permission/request") })
      assertTrue(paths.any { it.startsWith("/api/form") && it.contains("location%5Bdirectory%5D=%2Fwork%2Frepo") })
      refresh(c, controlOnly=true)
      assertEquals(listOf("per_1"), state.value.permissions.map { it.id })
    }
  }
  @Test fun forkResponseSelectsNewSession() = runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().setBody("""{"version":"2.0.22"}"""));s.enqueue(MockResponse().setBody("""{"data":{"id":"forked","location":{"directory":"/repo"},"title":"Fork","time":{}}}"""))
      val api=api(s);api.health()
      val(c,state)=controller(api,LagoonState(serverId="server",connected=true,sessions=listOf(Session("original","/repo","Original",0)),sessionId="original"))
      c.fork().join();assertEquals("forked",state.value.sessionId);assertTrue(state.value.sessions.any{it.id=="forked"})
    }
  }
  @Test fun queuedSendCannotMixServerAndSession() = runBlocking {
    MockWebServer().use { a -> MockWebServer().use { b ->
      a.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse = when {
        r.path!!.startsWith("/api/info") -> MockResponse().setBody("""{"version":"2.0.22"}""")
        r.path!!.startsWith("/api/session/sa/prompt") -> MockResponse().setBody("{}").setBodyDelay(350,TimeUnit.MILLISECONDS)
        r.path!!.contains("/prompt") -> MockResponse().setBody("{}")
        else -> MockResponse().setBody("""{"data":[]}""")
      }}
      val aa=api(a);aa.health();a.takeRequest()
      val(c,state)=controller(aa,LagoonState(serverId="server",connected=true,sessions=listOf(Session("sa","/a","A",0)),sessionId="sa"))
      val first=c.send("first");assertTrue(a.takeRequest(2,TimeUnit.SECONDS)!!.path!!.contains("/sa/prompt"))
      val second=c.send("queued on A");delay(80)
      put(c,"generation",2);put(c,"api",api(b))
      state.value=LagoonState(serverId="other",connected=true,sessions=listOf(Session("sb","/b","B",0)),sessionId="sb")
      first.join();second.join()
      val requests=generateSequence { a.takeRequest(100,TimeUnit.MILLISECONDS) }.toList()
      assertFalse(requests.any{it.path!!.contains("/prompt")})
      assertEquals(0,b.requestCount)
    }}
  }
  @Test fun transcriptFailureKeepsCachedFlagAcrossControlRefresh() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        return when (request.requestUrl!!.encodedPath) {
          "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
          "/api/project" -> MockResponse().setBody("""[{"id":"p","canonical":"/repo","sandboxes":[]}]""")
          "/api/session" -> MockResponse().setBody("""{"data":[{"id":"s","location":{"directory":"/repo"},"title":"Task","time":{}}],"cursor":{}}""")
          "/api/session/active" -> MockResponse().setBody("""{"data":{}}""")
          "/api/session/s/message" -> MockResponse().setResponseCode(500)
          else -> MockResponse().setBody("""{"data":[]}""")
        }
      } }
      val api = api(server); api.health()
      val session = Session("s", "/repo", "Task", 0)
      val (controller, state) = controller(api, LagoonState(serverId="server", connected=true,
        projects=listOf(Project("p", "/repo", "P")), projectId="p", sessions=listOf(session), sessionId="s"))
      val cache = LagoonController::class.java.getDeclaredField("cache").apply { isAccessible=true }.get(controller) as OfflineCache
      cache.saveMessages("server", "s", listOf(Message("old", "user", 0, emptyList()))); cache.awaitWrites()
      suspendCoroutine<Unit> { cont ->
        val method=LagoonController::class.java.getDeclaredMethod("loadSession", Session::class.java, Boolean::class.javaPrimitiveType,
          Int::class.javaPrimitiveType, HarnessClient::class.java, Continuation::class.java).apply { isAccessible=true }
        val result=method.invoke(controller, session, false, 1, api, cont)
        if (result !== COROUTINE_SUSPENDED) cont.resume(Unit)
      }
      assertTrue(state.value.cached); assertEquals("old", state.value.messages.single().id)
      refresh(controller)
      assertTrue(state.value.cached); assertTrue(state.value.connected)
    }
  }


  @Test fun outgoingComposerCannotWriteOrSendIntoAnotherSession() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"version":"2.0.22"}"""))
      val api = api(server); api.health()
      val (controller, state) = controller(api, LagoonState(serverId="server", connected=true,
        sessions=listOf(Session("new", "/repo", "New", 0)), sessionId="new", draft="new draft"))
      controller.updateDraft("outgoing draft", "server", "old")
      controller.send("outgoing prompt", "server", "old").join()
      assertEquals("new draft", state.value.draft)
      assertEquals(1, server.requestCount)
    }
  }
  @Test fun firstPromptFailureKeepsCreatedSessionDraftAndOldConfiguration() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest) = when(request.requestUrl!!.encodedPath) {
        "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
        "/api/session" -> MockResponse().setBody("""{"data":{"id":"created","location":{"directory":"/repo"},"title":"","time":{}}}""")
        "/api/session/created/prompt" -> MockResponse().setResponseCode(500)
        "/api/session/created/agent" -> MockResponse().setResponseCode(204)
        else -> MockResponse().setBody("""{"data":[]}""")
      } }
      val api = api(server); api.health()
      val (controller, state) = controller(api, LagoonState(serverId="server", connected=true,
        projects=listOf(Project("project", "/repo", "Repo")), projectId="project",
        sessions=listOf(Session("old", "/repo", "Old", 0)), sessionId="old", agent="previous"))
      val store=LagoonController::class.java.getDeclaredField("store").apply { isAccessible=true }.get(controller) as ServerStore
      store.rememberConfiguration("server", "old", SessionConfiguration("previous", agentChanged=true))
      var opened=0
      controller.startSession("", "retry this prompt", "build") { opened++ }.join()
      withTimeout(3_000) { while (state.value.pending("send")) delay(10) }
      assertEquals(1, opened)
      assertEquals("created", state.value.sessionId)
      assertEquals("retry this prompt", state.value.draft)
      assertEquals("retry this prompt", store.draft("server", "created"))
      assertEquals("previous", store.configuration("server", "old").agent)
      assertTrue(store.configuration("server", "old").agentChanged)
      assertEquals(ResourceState.ERROR, state.value.resource("action:send").state)
      val requests=List(server.requestCount) { server.takeRequest() }
      assertEquals(1, requests.count { it.method == "POST" && it.requestUrl!!.encodedPath == "/api/session" })
      assertEquals("build", org.json.JSONObject(requests.single { it.requestUrl!!.encodedPath.endsWith("/agent") }.body.readUtf8()).getString("agent"))
      assertEquals("build", org.json.JSONObject(requests.single { it.requestUrl!!.encodedPath.endsWith("/prompt") }.body.readUtf8()).getJSONObject("metadata").getString("agent"))
      // The failed admission leaves no optimistic row behind.
      assertTrue(state.value.messages.none { it.role == "user" })
    }
  }
  @Test fun draftFirstSendCreatesOnceAndCarriesPhoneAttachmentsIntoTheSession() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest) = when(request.requestUrl!!.encodedPath) {
        "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
        "/api/session" -> MockResponse().setBody("""{"data":{"id":"created","location":{"directory":"/repo"},"title":"","time":{}}}""")
        "/api/session/created/prompt" -> MockResponse().setResponseCode(500)
        "/api/session/created/agent" -> MockResponse().setResponseCode(204)
        else -> MockResponse().setBody("""{"data":[]}""")
      } }
      val api = api(server); api.health()
      val photo = LocalAttachment("a1", "photo.jpg", "image/jpeg", 10, "/nonexistent/a1")
      val (controller, state) = controller(api, LagoonState(serverId="server", connected=true,
        projects=listOf(Project("project", "/repo", "Repo")), projectId="project", pendingAttachments=mapOf(attachmentKey(null) to listOf(photo))))
      controller.startSession("", "", null, null).join()
      withTimeout(3_000) { while (state.value.pending("send") || state.value.pending("create")) delay(10) }
      assertEquals("created", state.value.sessionId)
      assertEquals(listOf(photo), state.value.attachments)
      assertFalse(attachmentKey(null) in state.value.pendingAttachments)
      assertEquals(1, List(server.requestCount) { server.takeRequest() }.count { it.method == "POST" && it.requestUrl!!.encodedPath == "/api/session" })
    }
  }
  @Test fun scopeIsAFilterThatSurvivesUnknownProjects() {
    val (controller, state) = controller(OpenCodeApi(ServerProfile("server","Server","https://example.invalid"), "x"),
      LagoonState(serverId="server", projects=listOf(Project("p", "/repo", "Repo")), projectId="p"))
    controller.setScope("missing")
    assertEquals(null, state.value.scopeProjectId)
    controller.setScope("p")
    assertEquals("p", state.value.scopeProjectId)
    assertEquals("p", state.value.projectId)
    controller.setScope(null)
    assertEquals(null, state.value.scopeProjectId)
    assertEquals("p", state.value.projectId)
  }
  @Test fun rejectedDeleteKeepsSessionAndDoesNotNavigateAway() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"version":"2.0.22"}""")); server.enqueue(MockResponse().setResponseCode(500))
      val api = api(server); api.health()
      val (controller, state)=controller(api, LagoonState(serverId="server", connected=true,
        sessions=listOf(Session("s", "/repo", "Task", 0)), sessionId="s"))
      var navigated=false
      controller.deleteSession { navigated=true }.join()
      assertFalse(navigated); assertEquals("s", state.value.sessionId)
      assertEquals(1, state.value.sessions.size)
      assertEquals(ResourceState.ERROR, state.value.resource("action:delete").state)
    }
  }
  @Test fun notificationTargetOutsideFirstPageResolvesItsParentChain() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest) = when(request.requestUrl!!.encodedPath) {
        "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
        "/api/session/old-child" -> MockResponse().setBody("""{"data":{"id":"old-child","parentID":"old-parent","location":{"directory":"/repo"},"time":{}}}""")
        "/api/session/old-parent" -> MockResponse().setBody("""{"data":{"id":"old-parent","location":{"directory":"/repo"},"time":{}}}""")
        else -> MockResponse().setResponseCode(404)
      } }
      val api=api(server);api.health()
      val (controller,state)=controller(api,LagoonState(serverId="server", connected=true,
        projects=listOf(Project("p","/repo","Repo")),projectId="p",sessionCursors=mapOf("/repo" to "older")))
      var lineage=emptyList<String>()
      controller.resolveSession("old-child") { lineage=it }.join()
      assertEquals(listOf("old-parent","old-child"),lineage)
      assertEquals("old-parent",state.value.parents["old-child"])
      assertTrue(state.value.sessions.any { it.id=="old-parent" })
    }
  }
  @Test fun pendingOperationsBelongToTheirSessionOrApprovalRequest() {
    val key=operationKey("server","a","project","send")
    val state=LagoonState(serverId="server",sessionId="b",projectId="project",pendingOperations=setOf(key,operationKey("server","a","project","permission:approval")))
    assertFalse(state.pending("send"))
    assertTrue(state.pending("permission:approval"))
    assertFalse(state.pending("permission:another"))
  }

  @Test fun olderHistoryUsesTheFullOfficialPageSizeAndAdvancesTheRevision() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        return when (url.encodedPath) {
          "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
          "/api/session/s/message" -> if (url.queryParameter("cursor") == null)
            MockResponse().setBody("""{"data":[{"id":"m2","type":"user","time":{"created":2},"text":"b"}],"cursor":{"next":"older"}}""")
          else MockResponse().setBody("""{"data":[{"id":"m1","type":"user","time":{"created":1},"text":"a"}],"cursor":{}}""")
          else -> MockResponse().setBody("""{"data":[]}""")
        }
      } }
      val api = api(server); api.health()
      val session = Session("s", "/repo", "T", 0)
      val (controller, state) = controller(api, LagoonState(serverId = "server", connected = true,
        sessions = listOf(session), sessionId = "s", messages = listOf(Message("m2", "user", 2, emptyList())), messagesCursor = "older"))
      controller.loadOlderMessages().join()
      assertEquals(listOf("m1", "m2"), state.value.messages.map { it.id })
      assertEquals(1, state.value.historyRevision)
      val older = List(server.requestCount) { server.takeRequest() }.first { it.requestUrl!!.queryParameter("cursor") == "older" }
      assertEquals("200", older.requestUrl!!.queryParameter("limit"))
    }
  }

  @Test fun replyPermissionTolerates404WhenAlreadySettledElsewhere() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        return when (request.requestUrl!!.encodedPath) {
          "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
          "/api/session/ses_r/permission/per_1/reply" -> MockResponse().setResponseCode(404).setBody("""{"message":"Permission request not found"}""")
          else -> MockResponse().setBody("""{"data":[]}""")
        }
      } }
      val api = api(server); api.health()
      val perm = PermissionRequest("per_1", "ses_r", "/repo", "bash", "ls", emptyList())
      val (controller, state) = controller(api, LagoonState(serverId = "server", connected = true,
        permissions = listOf(perm), tasks = mapOf("ses_r" to TaskState("ses_r", TaskPhase.WAITING_PERMISSION))))
      controller.replyPermission(perm, "once").join()
      assertTrue(state.value.permissions.isEmpty())
      assertFalse(state.value.pending("permission:per_1"))
      assertEquals(TaskPhase.THINKING, state.value.tasks["ses_r"]?.phase)
      assertEquals(0, state.value.summary.waiting)
    }
  }

  @Test fun replyingOrRejectingQuestionRestoresRunningTaskAndSummary() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) =
        if (request.requestUrl!!.encodedPath == "/api/info") MockResponse().setBody("""{"version":"2.0.22"}""")
        else MockResponse().setResponseCode(204)
      }
      val client = api(server); client.health()
      val form = org.json.JSONObject("""{"id":"q1","sessionID":"s","fields":[{"key":"k","type":"string","required":true}]}""").toForm("/repo")
      val (controller, state) = controller(client, LagoonState(serverId = "server", connected = true,
        questions = listOf(form), tasks = mapOf("s" to TaskState("s", TaskPhase.WAITING_QUESTION, "等待你的回答"))))
      controller.replyQuestion(form, listOf(listOf("ok"))).join()
      assertTrue(state.value.questions.isEmpty())
      assertEquals(TaskPhase.THINKING, state.value.tasks["s"]?.phase)
      assertEquals(0, state.value.summary.waiting)
      assertEquals(1, state.value.summary.running)
    }
  }

  @Test fun replyingOnePermissionKeepsOtherPendingPermissionsUntilLastReply() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) =
        if (request.requestUrl!!.encodedPath == "/api/info") MockResponse().setBody("""{"version":"2.0.22"}""")
        else MockResponse().setResponseCode(204)
      }
      val client = api(server); client.health()
      val first = PermissionRequest("p1", "s", "/repo", "shell", "one")
      val second = first.copy(id = "p2")
      val (controller, state) = controller(client, LagoonState(serverId = "server", connected = true,
        permissions = listOf(first, second), tasks = mapOf("s" to TaskState("s", TaskPhase.WAITING_PERMISSION))))
      controller.replyPermission(first, "once").join()
      assertEquals(listOf(second), state.value.permissions)
      assertEquals(TaskPhase.WAITING_PERMISSION, state.value.tasks["s"]?.phase)
      controller.replyPermission(second, "once").join()
      assertEquals(TaskPhase.THINKING, state.value.tasks["s"]?.phase)
      assertEquals(0, state.value.summary.waiting)
    }
  }

  @Test fun initialLoadPullsOlderPagesUntilTheWindowStartsOnAWholeTurn() = runBlocking {
    MockWebServer().use { server ->
      val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!; requests += url.toString()
        return when (url.encodedPath) {
          "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
          "/api/session/s/inbox" -> MockResponse().setBody("""{"data":[]}""")
          "/api/session/s/message" -> if (url.queryParameter("cursor") == null)
            MockResponse().setBody("""{"data":[{"id":"a1","type":"assistant","time":{"created":2},"content":[{"type":"text","text":"mid"}]}],"cursor":{"next":"older"}}""")
          else MockResponse().setBody("""{"data":[{"id":"u1","type":"user","time":{"created":1},"text":"hi"}],"cursor":{}}""")
          else -> MockResponse().setBody("""{"data":[]}""")
        }
      } }
      val api = api(server); api.health()
      val session = Session("s", "/repo", "T", 0)
      val (controller, state) = controller(api, LagoonState(serverId = "server", connected = true, sessions = listOf(session), sessionId = "s"))
      readTranscript(controller, api, session)
      // The newest page began mid-turn, so one older page was pulled to start on the user turn.
      assertEquals(listOf("u1", "a1"), state.value.messages.map { it.id })
      assertEquals(2, requests.count { it.contains("/api/session/s/message") })
    }
  }

  @Test fun cachePaintedRowsAreReplacedByTheServerPageWhileStreamedRowsSurvive() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        return when (url.encodedPath) {
          "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
          "/api/session/s/message" -> MockResponse().setBody("""{"data":[{"id":"u1","type":"user","time":{"created":1},"text":"hi"}],"cursor":{}}""")
          else -> MockResponse().setBody("""{"data":[]}""")
        }
      } }
      val api = api(server); api.health()
      val session = Session("s", "/repo", "T", 0)
      // "gone" was reverted on the server after the cache was written; "live" streamed in during the read.
      val cached = listOf(Message("u1", "user", 1, emptyList()), Message("gone", "assistant", 2, emptyList()))
      val (controller, state) = controller(api, LagoonState(serverId = "server", connected = true, sessions = listOf(session), sessionId = "s",
        messages = cached + Message("live", "assistant", 3, emptyList()), provisionalMessageIds = setOf("u1", "gone"),
        transcriptLoadingSince = 1))
      readTranscript(controller, api, session)
      assertEquals(listOf("u1", "live"), state.value.messages.map { it.id })
      assertTrue(state.value.provisionalMessageIds.isEmpty())
      assertEquals(0L, state.value.transcriptLoadingSince)
    }
  }


  @Test fun firstRoundReadsRunTogetherAndTheTranscriptDoesNotWaitForPendingRequests() = runBlocking {
    MockWebServer().use { server ->
      val firstRound = java.util.concurrent.CountDownLatch(4)
      val transcriptRead = java.util.concurrent.CountDownLatch(1)
      val sequentialFirstRound = java.util.concurrent.atomic.AtomicBoolean(false)
      val transcriptWaitedForPermissions = java.util.concurrent.atomic.AtomicBoolean(false)
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        // Each first-round read holds its response until all four have arrived: a sequential client never gets there.
        fun gate() { firstRound.countDown(); if (!firstRound.await(3, TimeUnit.SECONDS)) sequentialFirstRound.set(true) }
        return when {
          url.encodedPath == "/api/info" -> { gate(); MockResponse().setBody("""{"version":"2.0.22"}""") }
          url.encodedPath == "/openapi.json" -> { gate(); MockResponse().setResponseCode(404) }
          url.encodedPath == "/api/session" && url.queryParameter("parentID") == "null" -> { gate()
            MockResponse().setBody("""{"data":[{"id":"s","projectID":"p","location":{"directory":"/repo"},"time":{"created":1,"updated":1}}],"cursor":{}}""") }
          url.encodedPath == "/api/session/active" -> { gate(); MockResponse().setBody("""{"data":{"s":{}}}""") }
          // The permission read only answers once the transcript was requested.
          url.encodedPath == "/api/session/s/permission" -> {
            if (!transcriptRead.await(3, TimeUnit.SECONDS)) transcriptWaitedForPermissions.set(true)
            MockResponse().setBody("""{"data":[]}""")
          }
          url.encodedPath == "/api/session/s/message" -> { transcriptRead.countDown()
            MockResponse().setBody("""{"data":[{"id":"u1","type":"user","time":{"created":1},"text":"hi"}],"cursor":{}}""") }
          else -> MockResponse().setBody("""{"data":[]}""")
        }
      } }
      val session = Session("s", "/repo", "T", 0, projectId = "p")
      val (controller, state) = controller(api(server), LagoonState(serverId = "server", sessions = listOf(session), sessionId = "s"))
      refresh(controller, controlOnly = false)
      assertFalse("probe, contract, catalog and activity must be read in one round trip", sequentialFirstRound.get())
      assertFalse("the transcript must not wait for pending-request reads", transcriptWaitedForPermissions.get())
      assertTrue(state.value.connected)
      assertEquals(ConnectStage.READY, state.value.connection.stage)
      assertEquals(listOf("u1"), state.value.messages.map { it.id })
    }
  }


  @Test fun aPromptTypedWhileReconnectingIsHeldThenDeliveredOnceTheServerAnswers() = runBlocking {
    MockWebServer().use { server ->
      val prompts = java.util.Collections.synchronizedList(mutableListOf<String>())
      server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        return when (url.encodedPath) {
          "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
          "/api/session" -> MockResponse().setBody("""{"data":[{"id":"s","projectID":"p","location":{"directory":"/repo"},"time":{"created":1,"updated":1}}],"cursor":{}}""")
          "/api/session/active" -> MockResponse().setBody("""{"data":{}}""")
          "/api/session/s/prompt" -> { prompts += request.body.readUtf8(); MockResponse().setResponseCode(204) }
          else -> MockResponse().setBody("""{"data":[]}""")
        }
      } }
      val session = Session("s", "/repo", "T", 0, projectId = "p")
      val (controller, state) = controller(api(server), LagoonState(serverId = "server", sessions = listOf(session), sessionId = "s",
        connection = ConnectionStatus(ConnectStage.RETRYING, 1, 2, "连接失败")))
      assertTrue(state.value.canQueueSend)
      var cleared = 0
      controller.send("hello", "server", "s") { cleared++ }.join()
      controller.send("again", "server", "s") { cleared++ }.join()
      // Shown at once as one pending row, the composer is cleared, nothing reached the server yet.
      val pending = state.value.messages.single()
      assertTrue(pending.id.startsWith(PENDING_PREFIX))
      assertEquals("hello\n\nagain", pending.parts.single().text)
      assertEquals(2, cleared)
      assertTrue(prompts.isEmpty())
      assertTrue("等待连接后发送" in TranscriptRows.build(state.value.messages, emptySet(), false).first { it.kind == "user" }.meta)
      // The server answers: the held prompt goes out once, and its placeholder is replaced by the real row.
      refresh(controller, controlOnly = false)
      withTimeout(3_000) { while (prompts.isEmpty() || state.value.pending("send")) delay(10) }
      assertEquals(1, prompts.size)
      assertTrue(prompts.single(), prompts.single().contains("hello\\n\\nagain"))
      assertTrue(state.value.messages.none { it.id.startsWith(PENDING_PREFIX) })
    }
  }

  @Test fun pendingPromptsAreNeverWrittenToTheTranscriptCache() {
    val rows = listOf(Message("m1", "user", 1, emptyList()), Message(PENDING_PREFIX + "x", "user", 2, emptyList(), queued = true))
    assertEquals(listOf("m1"), rows.persistable().map { it.id })
  }

}
