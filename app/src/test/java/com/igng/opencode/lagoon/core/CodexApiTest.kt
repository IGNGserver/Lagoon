package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexApiTest {
  private class Fake(override val remote: Boolean = true) : CodexTransport {
    override var serverVersion: String? = null
    val frames = MutableSharedFlow<JSONObject>(extraBufferCapacity = 16)
    var eventSource: Flow<JSONObject>? = null
    var eventSubscriptions = 0
    val calls = mutableListOf<Pair<String, JSONObject>>()
    var response: suspend (String, JSONObject) -> JSONObject = { method, _ -> when (method) {
      "account/read" -> JSONObject().put("requiresOpenaiAuth", false)
      "thread/list" -> JSONObject().put("data", JSONArray())
      "thread/resume" -> JSONObject()
      "turn/start" -> JSONObject().put("turn", JSONObject().put("id", "turn-1"))
      else -> JSONObject()
    } }
    var answer: Pair<Any, JSONObject>? = null
    var answerSignal: CompletableDeferred<Unit>? = null
    var errorAnswer: Triple<Any, Int, String>? = null
    var errorSignal: CompletableDeferred<Unit>? = null
    val subscribed = linkedSetOf<String>()
    val unsubscribed = mutableListOf<String>()
    var beforeUnsubscribe: suspend (String) -> Unit = {}
    override suspend fun call(method: String, params: JSONObject): JSONObject {
      calls += method to JSONObject(params.toString())
      return response(method, params)
    }
    override suspend fun respond(requestId: Any, result: JSONObject) { answer = requestId to result; answerSignal?.complete(Unit) }
    override suspend fun respondError(requestId: Any, code: Int, message: String) { errorAnswer = Triple(requestId, code, message); errorSignal?.complete(Unit) }
    override suspend fun subscribe(threadId: String) { subscribed.add(threadId) }
    override suspend fun unsubscribe(threadId: String) { beforeUnsubscribe(threadId); subscribed.remove(threadId); unsubscribed.add(threadId) }
    override fun events(): Flow<JSONObject> { eventSubscriptions++; return eventSource ?: frames }
    override fun close() = Unit
  }
  private val session = Session("thread-1", "/repo", "Example", 0)

  @Test fun sendsOnceAndSteersOnlyTheKnownActiveTurn() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    api.send(session, "hello")
    assertEquals(listOf("thread/resume", "turn/start"), transport.calls.map { it.first })
    assertEquals("hello", transport.calls.last().second.getJSONArray("input").getJSONObject(0).getString("text"))
    api.send(session, "change direction")
    assertEquals("turn/steer", transport.calls.last().first)
    assertEquals("turn-1", transport.calls.last().second.getString("expectedTurnId"))
    api.abort(session)
    assertEquals("turn-1", transport.calls.last().second.getString("turnId"))
    // Acknowledging interrupt does not mean the turn has ended.
    assertTrue("thread-1" in api.activeSessions())
    api.close()
  }

  @Test fun remoteHistoryMapsProjectionAndRejectsMalformedPage() = runBlocking {
    val transport = Fake()
    transport.response = { method, _ -> if (method == "thread/list") JSONObject().put("data", JSONArray()) else JSONObject("""{"records":[
      {"event":{"type":"turn/start","seq":0,"time":100,"data":{"turn":1}}},
      {"event":{"type":"user/message","seq":1,"time":101,"data":{"content":[{"type":"text","text":"Hello"}]}}},
      {"event":{"type":"assistant/message","seq":2,"time":102,"data":{"turn":1,"message":{"content":[{"type":"text","text":"Hi"}]}}}}
    ],"hasMore":false,"activeTurnId":"turn-live"}""") }
    val api = CodexApi(transport)
    val page = api.messagesPage("thread-1", null, 20)
    assertEquals(listOf("user", "assistant"), page.items.map { it.role })
    assertEquals("Hello", page.items[0].parts.first().text)
    assertEquals("Hi", page.items[1].parts.first().text)
    assertTrue("thread-1" in api.activeSessions())
    transport.response = { _, _ -> JSONObject() }
    try { api.messagesPage("thread-1", null, 20); fail("missing records must fail") } catch (_: ApiException) { }
    api.close()
  }

  @Test fun broadcastsEventsToEachObserverAndPreservesApprovalId() = runBlocking {
    val transport = Fake(false)
    val api = CodexApi(transport)
    api.selectCodexSession(session)
    val a = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "permission.asked" } } }
    val b = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "permission.asked" } } }
    transport.frames.emit(JSONObject().put("id", 42).put("method", "item/commandExecution/requestApproval")
      .put("params", JSONObject().put("threadId", "thread-1").put("command", "ls")))
    val first = a.await()
    assertEquals(first.properties.str("id"), b.await().properties.str("id"))
    val request = api.sessionPermissions(session).single()
    assertFalse(request.manualOnly)
    api.replyPermission(request, "once")
    assertEquals(42, transport.answer?.first)
    assertEquals("accept", transport.answer?.second?.getString("decision"))
    api.close()
  }

  @Test fun remoteNeverPretendsUnsupportedPersistentGrantSucceeded() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    api.selectCodexSession(session)
    val asked = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "permission.asked" } } }
    transport.frames.emit(JSONObject().put("id", "opaque").put("method", "item/fileChange/requestApproval")
      .put("params", JSONObject().put("threadId", "thread-1")))
    asked.await()
    try { api.replyPermission(api.sessionPermissions(session).single(), "always"); fail("Remote cannot grant for session") }
    catch (e: ApiException) { assertEquals(501, e.status) }
    assertNull(transport.answer)
    api.close()
  }

  @Test fun fileApprovalShowsGrantRootAndRespectsSessionDecision() = runBlocking {
    for (remote in listOf(false, true)) {
      val transport = Fake(remote); val api = CodexApi(transport)
      api.selectCodexSession(session)
      val asked = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "permission.asked" } } }
      transport.frames.emit(JSONObject().put("id", 99).put("method", "item/fileChange/requestApproval")
        .put("params", JSONObject().put("threadId", session.id).put("grantRoot", "/sensitive/root")))
      val event = asked.await()
      assertTrue(event.properties.str("message").contains("/sensitive/root"))
      assertTrue(event.properties.getBoolean("manualOnly"))
      val request = api.sessionPermissions(session).single()
      assertTrue(request.manualOnly)
      assertTrue(request.detail.contains("本会话剩余时间"))
      assertTrue(request.detail.contains("允许一次"))
      if (!remote) assertTrue(request.detail.contains("会话级批准（此处不可选择）"))
      api.replyPermission(request, if (remote) "once" else "always")
      assertEquals(if (remote) "accept" else "acceptForSession", transport.answer!!.second.getString("decision"))
      api.close()
    }
  }

  @Test fun additionalPermissionsExplainTurnScopeAndPreserveSessionScope() = runBlocking {
    val transport = Fake(false); val api = CodexApi(transport)
    api.selectCodexSession(session)
    for ((id, reply) in listOf(101 to "once", 102 to "always")) {
      val asked = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "permission.asked" } } }
      transport.frames.emit(JSONObject().put("id", id).put("method", "item/permissions/requestApproval")
        .put("params", JSONObject().put("threadId", session.id).put("permissions", JSONObject().put("network", JSONObject().put("enabled", true)))))
      asked.await()
      val request = api.sessionPermissions(session).single()
      assertTrue(request.manualOnly)
      assertTrue(request.detail.contains("授权本轮有效"))
      api.replyPermission(request, reply)
      val response = transport.answer!!.second
      assertTrue(response.getJSONObject("permissions").getJSONObject("network").getBoolean("enabled"))
      if (reply == "once") assertFalse(response.has("scope")) else assertEquals("session", response.getString("scope"))
    }
    api.close()
  }

  @Test fun malformedGrantRootCannotBeApproved() = runBlocking {
    val transport = Fake(false); val api = CodexApi(transport)
    api.selectCodexSession(session)
    val asked = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "permission.asked" } } }
    transport.frames.emit(JSONObject().put("id", 103).put("method", "item/fileChange/requestApproval")
      .put("params", JSONObject().put("threadId", session.id).put("grantRoot", JSONObject())))
    asked.await()
    val request = api.sessionPermissions(session).single()
    assertTrue(request.manualOnly)
    try { api.replyPermission(request, "once"); fail("malformed root must not be approved") } catch (_: ApiException) {}
    assertNull(transport.answer)
    api.replyPermission(request, "reject")
    assertEquals("decline", transport.answer!!.second.getString("decision"))
    api.close()
  }
  @Test fun projectAdmissionUsesHostConfirmedRootAndStableKeyAfterUncertainResult() = runBlocking {
    val transport = Fake()
    var attempts = 0
    transport.response = { method, params ->
      assertEquals("project/create", method)
      assertEquals("/link", params.getJSONArray("roots").getJSONObject(0).getString("path"))
      attempts++
      if (attempts == 1) throw java.io.IOException("unknown result")
      JSONObject("""{"project":{"id":"project-1","name":"Project","roots":[{"path":"/canonical"}]}}""")
    }
    val api = CodexApi(transport)
    try { api.createCodexProject("/link"); fail("unknown outcome must surface") } catch (_: java.io.IOException) { }
    val project = requireNotNull(api.createCodexProject("/link"))
    assertEquals("/canonical", project.directory)
    assertEquals(transport.calls[0].second.getString("idempotencyKey"), transport.calls[1].second.getString("idempotencyKey"))
    api.close()
  }

  @Test fun newlyCreatedThreadIsNotResumedTwice() = runBlocking {
    val transport = Fake(false)
    transport.response = { method, _ -> when (method) {
      "thread/start" -> JSONObject("""{"thread":{"id":"new-1","cwd":"/repo","createdAt":1}}""")
      "turn/start" -> JSONObject("""{"turn":{"id":"turn-1"}}""")
      else -> JSONObject()
    } }
    val api = CodexApi(transport)
    val created = api.createSession("/repo", "")
    api.send(created, "hello")
    assertEquals(listOf("thread/start", "turn/start"), transport.calls.map { it.first })
    api.close()
  }

  @Test fun onOpenWaitsForSuccessfulHealthCheck() = runBlocking {
    val transport = Fake()
    transport.response = { _, _ -> throw java.io.IOException("unreachable") }
    val api = CodexApi(transport)
    var opened = false
    try { api.events { opened = true }.first(); fail("health failure must propagate") }
    catch (_: java.io.IOException) { }
    assertFalse(opened)
    api.close()
  }

  @Test fun transportFailureTerminatesAllCollectorsAndInvalidatesClient() = runBlocking {
    val channel = Channel<JSONObject>(1)
    val transport = Fake().apply { eventSource = channel.receiveAsFlow() }
    val api = CodexApi(transport)
    val firstOpen = CompletableDeferred<Unit>()
    val secondOpen = CompletableDeferred<Unit>()
    val a = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.events { firstOpen.complete(Unit) }.collect() } }
    val b = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.events { secondOpen.complete(Unit) }.collect() } }
    firstOpen.await(); secondOpen.await()
    channel.close(java.io.IOException("lost"))
    assertTrue(withTimeout(3000) { a.await().isFailure })
    assertTrue(withTimeout(3000) { b.await().isFailure })
    assertTrue(api.isClosed)
    api.close()
  }

  @Test fun sessionPaginationIncludesArchivedThreadsAndRetainsTheirState() = runBlocking {
    val transport = Fake()
    transport.response = { method, params ->
      when (method) {
        "thread/list" -> if (params.optBoolean("archived")) JSONObject("""{"data":[{"id":"old","cwd":"/repo","name":"old"}]}""")
          else JSONObject("""{"data":[{"id":"active","cwd":"/repo","name":"active"}]}""")
        "thread/read" -> JSONObject("""{"thread":{"id":"old","cwd":"/repo","name":"old"}}""")
        else -> JSONObject()
      }
    }
    val api = CodexApi(transport)
    val active = api.rootSessionsPage("/repo", null, 100)
    assertEquals(listOf("active", "old"), active.items.map { it.id })
    assertFalse(active.items.first().archived)
    assertTrue(active.items.last().archived)
    assertNull(active.next)
    assertTrue(api.session("old").archived)
    api.close()
  }

  @Test fun constructingClientDoesNotStartOrConnectCarrier() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    assertEquals(0, transport.eventSubscriptions)
    assertTrue(transport.calls.isEmpty())
    api.health()
    assertEquals(1, transport.eventSubscriptions)
    api.close()
  }

  @Test fun fullLastActivePageStillProvidesArchivedCursor() = runBlocking {
    val transport = Fake()
    transport.response = { method, params ->
      assertEquals("thread/list", method)
      if (params.optBoolean("archived")) JSONObject("""{"data":[{"id":"old","cwd":"/repo"}]}""")
      else JSONObject("""{"data":[{"id":"active","cwd":"/repo"}]}""")
    }
    val api = CodexApi(transport)
    val first = api.rootSessionsPage("/repo", null, 1)
    assertEquals("archived:", first.next)
    val second = api.rootSessionsPage("/repo", first.next, 1)
    assertEquals("old", second.items.single().id)
    assertTrue(second.items.single().archived)
    assertNull(second.next)
    api.close()
  }

  @Test fun completedTurnCannotBeRevivedByLateStartResponse() = runBlocking {
    val transport = Fake(false)
    val api = CodexApi(transport)
    val completionSeen = CompletableDeferred<Unit>()
    val observer = async(start = CoroutineStart.UNDISPATCHED) {
      withTimeout(3000) { api.events().first { it.type == "session.execution.succeeded" } }
      completionSeen.complete(Unit)
    }
    transport.response = { method, _ ->
      when (method) {
        "turn/start" -> {
          transport.frames.emit(JSONObject().put("method", "turn/started").put("params",
            JSONObject().put("threadId", "thread-1").put("turn", JSONObject().put("id", "turn-1"))))
          transport.frames.emit(JSONObject().put("method", "turn/completed").put("params",
            JSONObject().put("threadId", "thread-1").put("turn", JSONObject().put("id", "turn-1").put("status", "completed"))))
          completionSeen.await()
          JSONObject().put("turn", JSONObject().put("id", "turn-1"))
        }
        else -> JSONObject()
      }
    }
    api.send(session, "hello")
    observer.await()
    assertFalse("thread-1" in api.activeSessions())
    api.close()
  }

  @Test fun missingRequiredHostLoginIsNotReportedAsHealthy() = runBlocking {
    val transport = Fake()
    transport.response = { _, _ -> JSONObject().put("requiresOpenaiAuth", true).put("account", JSONObject.NULL) }
    val api = CodexApi(transport)
    try { api.health(); fail("missing login must fail") } catch (error: ApiException) { assertEquals(401, error.status) }
    api.close()
  }

  @Test fun foregroundAndMonitorSubscriptionsHaveSeparateOwnership() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val foreground = Session("visible", "/repo", "Visible", 3)
    val running = Session("running", "/repo", "Running", 2)
    api.selectCodexSession(foreground)
    api.monitorCodexSessions(listOf(foreground, running)) { foreground.id }
    assertEquals(setOf("visible", "running"), transport.subscribed)
    api.monitorCodexSessions(emptyList()) { foreground.id }
    assertEquals(setOf("visible"), transport.subscribed)
    assertTrue("running" in transport.unsubscribed)
    val next = Session("next", "/repo", "Next", 4)
    api.selectCodexSession(next)
    assertEquals(setOf("next"), transport.subscribed)
    api.monitorCodexSessions(emptyList()) { null }
    assertTrue(transport.subscribed.isEmpty())
    api.close()
  }

  @Test fun monitorReservesForegroundSlotAndReportsOverflow() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val foreground = Session("visible", "/repo", "Visible", 0)
    api.selectCodexSession(foreground)
    val active = (0..15).map { Session("task-$it", "/repo", "Task", it.toLong()) }
    try { api.monitorCodexSessions(active) { foreground.id }; fail("overflow must be visible") }
    catch (error: ApiException) { assertEquals(429, error.status) }
    assertEquals(16, transport.subscribed.size)
    assertTrue("visible" in transport.subscribed)
    assertFalse("task-0" in transport.subscribed)
    api.close()
  }

  @Test fun switchingForegroundDuringCleanupRestoresItsLease() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val old = Session("old", "/repo", "Old", 1)
    api.selectCodexSession(old)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    transport.beforeUnsubscribe = { entered.complete(Unit); release.await() }
    var selected: String? = null
    val cleanup = async { api.monitorCodexSessions(emptyList()) { selected } }
    entered.await()
    selected = old.id
    val opening = async { api.selectCodexSession(old) }
    release.complete(Unit)
    cleanup.await(); opening.await()
    assertTrue(old.id in transport.subscribed)
    api.close()
  }

  @Test fun malformedCatalogAndPagingNeverBecomeEmptySuccess() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    transport.response = { _, _ -> JSONObject() }
    try { api.rootSessionsPage(); fail("missing data must fail") } catch (error: ApiException) { assertEquals(502, error.status) }
    transport.response = { _, _ -> JSONObject().put("data", JSONArray().put("not-an-object")) }
    try { api.modelCatalog("/repo"); fail("malformed model must fail") } catch (error: ApiException) { assertEquals(502, error.status) }
    transport.response = { _, _ -> JSONObject().put("data", JSONArray()).put("nextCursor", 123) }
    try { api.rootSessionsPage(); fail("malformed cursor must fail") } catch (error: ApiException) { assertEquals(502, error.status) }
    api.close()
  }

  @Test fun readAcknowledgementDoesNotStealForegroundSubscription() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val foreground = Session("visible", "/repo", "Visible", 1)
    api.selectCodexSession(foreground)
    api.viewSession(Session("other", "/repo", "Other", 2), 1)
    assertEquals(setOf("visible"), transport.subscribed)
    assertTrue(transport.unsubscribed.isEmpty())
    api.close()
  }

  @Test fun remoteActiveScanCoversEveryPageAndNeverTreatsIncompleteAsEmpty() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    transport.response = { method, params ->
      assertEquals("thread/list", method)
      if (!params.has("cursor")) JSONObject("""{"data":[{"id":"idle","status":{"type":"idle"}}],"nextCursor":"next"}""")
      else JSONObject("""{"data":[{"id":"busy","status":{"type":"active"}}],"nextCursor":null}""")
    }
    assertEquals(setOf("busy"), api.activeSessions())
    transport.response = { _, params ->
      if (!params.has("cursor")) JSONObject("""{"data":[],"nextCursor":"next"}""")
      else throw java.io.IOException("second page unavailable")
    }
    try { api.activeSessions(); fail("partial scan must fail") } catch (_: java.io.IOException) { }
    transport.response = { _, _ -> JSONObject("""{"data":[{"id":"unknown"}]}""") }
    try { api.activeSessions(); fail("unknown status must fail") } catch (error: ApiException) { assertEquals(502, error.status) }
    api.close()
  }

  @Test fun liveBusySurvivesLaggingCatalogAndClearsOnTerminalEvent() = runBlocking {
    val transport = Fake()
    var catalogStatus = "idle"
    transport.response = { method, _ -> when (method) {
      "account/read" -> JSONObject().put("requiresOpenaiAuth", false)
      "thread/list" -> JSONObject().put("data", JSONArray().put(JSONObject().put("id", "thread-1")
        .put("cwd", "/repo").put("status", JSONObject().put("type", catalogStatus))))
      else -> JSONObject()
    } }
    val api = CodexApi(transport)
    val started = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.status" } } }
    transport.frames.emit(JSONObject().put("method", "thread/status/changed").put("params",
      JSONObject().put("threadId", "thread-1").put("status", JSONObject().put("type", "active"))))
    started.await()
    // Catalog is still idle, including when its first page is loaded after the event.
    api.rootSessionsPage("/repo", null, 1)
    assertEquals(setOf("thread-1"), api.activeSessions())
    catalogStatus = "systemError"
    val failedStatus = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.error" } } }
    transport.frames.emit(JSONObject().put("method", "thread/status/changed").put("params",
      JSONObject().put("threadId", "thread-1").put("status", JSONObject().put("type", "systemError"))))
    failedStatus.await()
    try { api.activeSessions(); fail("systemError must remain unknown") } catch (e: ApiException) { assertEquals(502, e.status) }
    catalogStatus = "idle"
    val ended = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.status" } } }
    transport.frames.emit(JSONObject().put("method", "thread/status/changed").put("params",
      JSONObject().put("threadId", "thread-1").put("status", JSONObject().put("type", "idle"))))
    ended.await()
    assertTrue(api.activeSessions().isEmpty())
    api.close()
  }

  @Test fun lateCompletionDoesNotMarkNewerTurnIdle() = runBlocking {
    val transport = Fake(false)
    val api = CodexApi(transport)
    val invalidated = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.transcript.invalidate" } } }
    fun frame(method: String, id: String, status: String? = null): JSONObject {
      val turn = JSONObject().put("id", id)
      status?.let { turn.put("status", it) }
      return JSONObject().put("method", method).put("params", JSONObject().put("threadId", "thread-1").put("turn", turn))
    }
    transport.frames.emit(frame("turn/started", "old"))
    transport.frames.emit(frame("turn/started", "new"))
    transport.frames.emit(frame("turn/completed", "old", "completed"))
    invalidated.await()
    assertTrue("thread-1" in api.activeSessions())
    api.abort(session)
    assertEquals("new", transport.calls.last().second.getString("turnId"))
    api.close()
  }

  @Test fun completeIdleCatalogRetiresOldSnapshotOnlyState() = runBlocking {
    val transport = Fake()
    transport.response = { method, _ -> when (method) {
      "dsh/sessionHistory" -> JSONObject("""{"records":[],"hasMore":false,"activeTurnId":"old"}""")
      "thread/list" -> JSONObject("""{"data":[{"id":"thread-1","status":{"type":"idle"}}]}""")
      else -> JSONObject()
    } }
    val api = CodexApi(transport)
    api.messagesPage("thread-1", null, 20)
    assertTrue(api.activeSessions().isEmpty())
    api.close()
  }

  @Test fun delayedActiveHistoryDoesNotResurrectCompletedTurn() = runBlocking {
    val transport = Fake()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    transport.response = { method, _ -> when (method) {
      "dsh/sessionHistory" -> { entered.complete(Unit); release.await(); JSONObject("""{"records":[],"hasMore":false,"activeTurnId":"old"}""") }
      "account/read" -> JSONObject().put("requiresOpenaiAuth", false)
      else -> JSONObject()
    } }
    val api = CodexApi(transport)
    val loading = async { api.messagesPage("thread-1", null, 20) }
    entered.await()
    val completed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.execution.succeeded" } } }
    transport.frames.emit(JSONObject().put("method", "turn/completed").put("params", JSONObject().put("threadId", "thread-1")
      .put("turn", JSONObject().put("id", "old").put("status", "completed"))))
    completed.await()
    release.complete(Unit)
    loading.await()
    try { api.abort(session); fail("a completed turn cannot be interrupted") } catch (e: ApiException) { assertEquals(409, e.status) }
    api.close()
  }

  @Test fun omittedRemoteActiveTurnClearsPriorSnapshotButNotNewerEvent() = runBlocking {
    val transport = Fake()
    var historyCalls = 0
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    transport.response = { method, _ -> if (method == "dsh/sessionHistory") {
      when (++historyCalls) {
        1, 3, 5 -> JSONObject("""{"records":[],"hasMore":false,"activeTurnId":"old"}""")
        4 -> { entered.complete(Unit); release.await(); JSONObject("""{"records":[],"hasMore":false}""") }
        else -> JSONObject("""{"records":[],"hasMore":false}""")
      }
    } else if (method == "account/read") JSONObject().put("requiresOpenaiAuth", false) else JSONObject() }
    val api = CodexApi(transport)
    api.messagesPage("thread-1", null, 20)
    api.messagesPage("thread-1", null, 20)
    try { api.abort(session); fail("an idle snapshot clears prior active state") } catch (e: ApiException) { assertEquals(409, e.status) }
    api.messagesPage("thread-1", null, 20)
    val loading = async { api.messagesPage("thread-1", null, 20) }
    entered.await()
    val started = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.execution.started" } } }
    transport.frames.emit(JSONObject().put("method", "turn/started").put("params", JSONObject().put("threadId", "thread-1")
      .put("turn", JSONObject().put("id", "new"))))
    started.await()
    release.complete(Unit)
    loading.await()
    api.abort(session)
    assertEquals("new", transport.calls.last().second.getString("turnId"))
    api.messagesPage("thread-1", null, 20) // Host history may lag an already-observed new turn.
    api.abort(session)
    assertEquals("new", transport.calls.last().second.getString("turnId"))
    api.close()
  }

  @Test fun delayedCreationNeverChangesForegroundSelection() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val first = Session("first", "/repo", "First", 0)
    val next = Session("next", "/repo", "Next", 0)
    api.selectCodexSession(first)
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    transport.response = { method, _ -> when (method) {
      "thread/start" -> { started.complete(Unit); release.await(); JSONObject("""{"thread":{"id":"created","cwd":"/repo"}}""") }
      else -> JSONObject()
    } }
    val creating = async { api.createSession("/repo", "") }
    started.await()
    api.selectCodexSession(next)
    release.complete(Unit)
    assertEquals("created", creating.await().id)
    assertEquals(setOf("next"), transport.subscribed)
    api.close()
  }

  @Test fun delayedSendKeepsTaskLeaseWithoutStealingNewForeground() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val first = Session("first", "/repo", "First", 0)
    val next = Session("next", "/repo", "Next", 0)
    api.selectCodexSession(first)
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val oldReleased = CompletableDeferred<Unit>()
    transport.beforeUnsubscribe = { if (it == first.id) oldReleased.complete(Unit) }
    transport.response = { method, _ -> when (method) {
      "thread/resume" -> { started.complete(Unit); release.await(); JSONObject() }
      "turn/start" -> JSONObject("""{"turn":{"id":"turn-1"}}""")
      else -> JSONObject()
    } }
    val sending = async { api.send(first, "hello") }
    started.await()
    api.selectCodexSession(next)
    assertEquals(setOf("first", "next"), transport.subscribed)
    release.complete(Unit)
    sending.await()
    assertEquals(setOf("first", "next"), transport.subscribed)
    transport.frames.emit(JSONObject().put("method", "turn/completed").put("params", JSONObject()
      .put("threadId", first.id).put("turn", JSONObject().put("id", "turn-1").put("status", "completed"))))
    withTimeout(3000) { oldReleased.await() }
    // Wait for the lease mutex: the callback above is invoked immediately before unsubscribe.
    api.selectCodexSession(next)
    assertEquals(setOf("next"), transport.subscribed)
    api.close()
  }

  @Test fun unknownSendOutcomeKeepsTaskLeaseAfterForegroundChanges() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val first = Session("first", "/repo", "First", 0)
    val next = Session("next", "/repo", "Next", 0)
    api.selectCodexSession(first)
    transport.response = { method, _ -> when (method) {
      "thread/resume" -> JSONObject()
      "turn/start" -> throw java.io.IOException("outcome unknown")
      else -> JSONObject()
    } }
    try { api.send(first, "hello"); fail("lost response must surface") } catch (_: java.io.IOException) { }
    api.selectCodexSession(next)
    api.monitorCodexSessions(emptyList()) { next.id }
    assertEquals(setOf("first", "next"), transport.subscribed)
    api.close()
  }

  @Test fun failedResumeReleasesNewTaskLeaseWithoutIssuingTurn() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val first = Session("first", "/repo", "First", 0)
    val next = Session("next", "/repo", "Next", 0)
    api.selectCodexSession(first)
    transport.response = { method, _ -> if (method == "thread/resume") throw java.io.IOException("resume failed") else JSONObject() }
    try { api.send(first, "hello"); fail("resume failure must surface") } catch (_: java.io.IOException) { }
    assertFalse(transport.calls.any { it.first == "turn/start" })
    api.selectCodexSession(next)
    assertEquals(setOf("next"), transport.subscribed)
    api.close()
  }

  @Test fun explicitTurnRejectionReleasesNewTaskLease() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val first = Session("first", "/repo", "First", 0)
    val next = Session("next", "/repo", "Next", 0)
    api.selectCodexSession(first)
    transport.response = { method, _ -> when (method) {
      "thread/resume" -> JSONObject()
      "turn/start" -> throw RemoteRpcException("INVALID_MESSAGE")
      else -> JSONObject()
    } }
    try { api.send(first, "hello"); fail("rejection must surface") } catch (_: RemoteRpcException) { }
    api.selectCodexSession(next)
    assertEquals(setOf("next"), transport.subscribed)
    api.close()
  }

  @Test fun rejectedSteerDoesNotReleaseAlreadyRunningTask() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val first = Session("first", "/repo", "First", 0)
    val next = Session("next", "/repo", "Next", 0)
    api.selectCodexSession(first)
    api.send(first, "hello")
    transport.response = { method, _ -> if (method == "turn/steer") throw RemoteRpcException("INVALID_MESSAGE") else JSONObject() }
    try { api.send(first, "follow up"); fail("rejection must surface") } catch (_: RemoteRpcException) { }
    api.selectCodexSession(next)
    assertEquals(setOf("first", "next"), transport.subscribed)
    api.close()
  }

  @Test fun remoteImageTransferErrorMayBePostAdmissionAndKeepsLease() = runBlocking {
    val transport = Fake()
    val api = CodexApi(transport)
    val first = Session("first", "/repo", "First", 0)
    val next = Session("next", "/repo", "Next", 0)
    api.selectCodexSession(first)
    transport.response = { method, _ -> when (method) {
      "thread/resume" -> JSONObject()
      "turn/start" -> throw RemoteRpcException("INVALID_MESSAGE")
      else -> JSONObject()
    } }
    try { api.send(first, "", inline = listOf(InlineFile("image.png", "image/png", "data:image/png;base64,YQ=="))); fail("transfer error must surface") }
    catch (_: RemoteRpcException) { }
    api.selectCodexSession(next)
    assertEquals(setOf("first", "next"), transport.subscribed)
    api.close()
  }

  private fun mcpRequest(id: Int, mode: String, schema: JSONObject? = null): JSONObject {
    val params = JSONObject().put("threadId", "thread-1").put("serverName", "example")
      .put("mode", mode).put("message", "请提供参数")
    if (schema != null) params.put("requestedSchema", schema)
    if (mode == "url") params.put("url", "https://example.invalid/auth").put("elicitationId", "auth")
    return JSONObject().put("id", id).put("method", "mcpServer/elicitation/request").put("params", params)
  }
  private fun mcpFixture(): JSONObject = JSONObject("""{
    "type":"object", "required":["name","count","enabled","choice","tags"],
    "properties":{
      "name":{"type":"string","minLength":1,"maxLength":1},
      "count":{"type":"integer","minimum":1},
      "enabled":{"type":"boolean"},
      "choice":{"type":"string","oneOf":[{"const":"a","title":"选项 A"},{"const":"b","title":"选项 B"}]},
      "tags":{"type":"array","items":{"type":"string","enum":["red","blue"]},"minItems":1}
    }}""")
  private suspend fun openMcpForm(api: CodexApi, transport: Fake, id: Int): QuestionRequest {
    val event = kotlinx.coroutines.coroutineScope {
      val pending = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "form.created" } } }
      transport.frames.emit(mcpRequest(id, "form", mcpFixture()))
      pending.await()
    }
    return event.properties.obj("form").toForm("/repo")
  }

  @Test fun mcpFormSubmitsTypedValuesAndPreservesExactIntegerAndUnicodeLength() = runBlocking {
    val transport = Fake(false); val api = CodexApi(transport)
    api.selectCodexSession(session)
    val form = openMcpForm(api, transport, 91)
    val rows = form.questions.map { prompt ->
      val key = JSONObject(prompt.field).getString("key")
      when (key) {
        "__lagoon_mcp_action" -> listOf("accept")
        "name" -> listOf("😀")
        "count" -> listOf("9007199254740993")
        "enabled" -> listOf("true")
        "choice" -> listOf("b")
        "tags" -> listOf("red", "blue")
        else -> error("Unexpected form field")
      }
    }
    api.replyQuestion(form, rows)
    val response = requireNotNull(transport.answer)
    assertEquals(91, response.first)
    assertEquals("accept", response.second.getString("action"))
    val content = response.second.getJSONObject("content")
    assertEquals("😀", content.getString("name"))
    assertEquals(9007199254740993L, content.getLong("count"))
    assertTrue(content.getBoolean("enabled"))
    assertEquals("b", content.getString("choice"))
    assertEquals(2, content.getJSONArray("tags").length())
    assertFalse(content.has("__lagoon_mcp_action"))
    assertTrue(api.forms("/repo").isEmpty())
    api.close()
  }

  @Test fun mcpFormCanDeclineWithoutSupplyingRequiredValuesOrCancel() = runBlocking {
    val transport = Fake(false); val api = CodexApi(transport)
    api.selectCodexSession(session)
    val form = openMcpForm(api, transport, 92)
    val answers = form.questions.map { if (JSONObject(it.field).getString("key") == "__lagoon_mcp_action") listOf("decline") else emptyList() }
    api.replyQuestion(form, answers)
    assertEquals("decline", transport.answer!!.second.getString("action"))
    assertTrue(transport.answer!!.second.isNull("content"))
    val second = openMcpForm(api, transport, 93)
    api.rejectQuestion(second)
    assertEquals(93, transport.answer!!.first)
    assertEquals("cancel", transport.answer!!.second.getString("action"))
    assertTrue(transport.answer!!.second.isNull("content"))
    api.close()
  }

  @Test fun unsupportedMcpUrlAndSchemaAreCancelledAndExplained() = runBlocking {
    val transport = Fake(false); val api = CodexApi(transport)
    api.selectCodexSession(session)
    for ((id, frame) in listOf(
      94 to mcpRequest(94, "url"),
      95 to mcpRequest(95, "form", JSONObject("""{"type":"object","properties":{"email":{"type":"string","format":"email"}}}"""))
    )) {
      val response = CompletableDeferred<Unit>(); transport.answerSignal = response
      val notice = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.synthetic" } } }
      transport.frames.emit(frame)
      notice.await()
      withTimeout(3000) { response.await() }
      assertEquals(id, transport.answer!!.first)
      assertEquals("cancel", transport.answer!!.second.getString("action"))
      assertTrue(api.forms("/repo").isEmpty())
    }
    api.close()
  }

  @Test fun unknownServerRequestProducesVisibleUnsupportedState() = runBlocking {
    val transport = Fake(false); val api = CodexApi(transport)
    api.selectCodexSession(session)
    transport.errorSignal = CompletableDeferred()
    val notice = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.synthetic" && it.properties.str("description").contains("future/request") } } }
    transport.frames.emit(JSONObject().put("id", 96).put("method", "future/request")
      .put("params", JSONObject().put("threadId", "thread-1")))
    assertTrue(notice.await().properties.str("description").contains("future/request"))
    withTimeout(3000) { transport.errorSignal!!.await() }
    assertEquals(Triple(96, -32601, "Unsupported server request"), transport.errorAnswer)
    assertNull(transport.answer)
    api.close()
  }

  @Test fun remoteUnknownRequestNeverSendsUnsupportedRpcError() = runBlocking {
    val transport = Fake(true); val api = CodexApi(transport)
    api.selectCodexSession(session)
    val notice = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.synthetic" } } }
    transport.frames.emit(JSONObject().put("id", "remote-handle").put("method", "future/request")
      .put("params", JSONObject().put("threadId", "thread-1")))
    assertTrue(notice.await().properties.str("description").contains("future/request"))
    assertNull(transport.errorAnswer)
    api.close()
  }

  @Test fun deleteRequiresVerifiedSshVersionAndNeverRetriesFailure() = runBlocking {
    val transport = Fake(false); val api = CodexApi(transport)
    assertFalse(api.capabilities().deleteSession)
    transport.serverVersion = "0.167.1"
    assertFalse(api.capabilities().deleteSession)
    transport.serverVersion = "0.167.2"
    assertTrue(api.capabilities().deleteSession)
    var attempts = 0
    transport.response = { method, _ ->
      if (method == "thread/delete") { attempts++; throw java.io.IOException("outcome unknown") }
      JSONObject()
    }
    try { api.deleteSession(session); fail("delete must propagate transport failure") } catch (_: java.io.IOException) {}
    assertEquals(1, attempts)
    transport.response = { _, _ -> JSONObject() }
    api.deleteSession(session)
    assertEquals("thread-1", transport.calls.last().second.getString("threadId"))
    val remote = Fake(true).apply { serverVersion = "0.167.2" }
    val remoteApi = CodexApi(remote)
    assertFalse(remoteApi.capabilities().deleteSession)
    try { remoteApi.deleteSession(session); fail("Remote deletion must be unsupported") } catch (_: ApiException) {}
    assertTrue(remote.calls.none { it.first == "thread/delete" })
    api.close(); remoteApi.close()
  }

  @Test fun deleteNotificationProjectsAuthoritativeSessionId() = runBlocking {
    val transport = Fake(false).apply { serverVersion = "0.167.2" }; val api = CodexApi(transport)
    api.selectCodexSession(session)
    val deleted = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.deleted" } } }
    transport.frames.emit(JSONObject().put("method", "thread/deleted").put("params", JSONObject().put("threadId", session.id)))
    assertEquals(session.id, deleted.await().properties.str("sessionID"))
    api.close()
  }

  @Test fun sshSubAgentActivityDisplaysOnlyReportedFactsAndDeduplicatesHistory() = runBlocking {
    val transport = Fake(false); val api = CodexApi(transport)
    val activity = JSONObject().put("type", "subAgentActivity").put("id", "activity-1")
      .put("agentPath", "worker/research").put("agentThreadId", "child-thread").put("kind", "started")
      .put("model", "example-model").put("reasoningEffort", "high")
    transport.response = { method, _ ->
      if (method == "thread/turns/list") JSONObject().put("data", JSONArray().put(
        JSONObject().put("id", "turn-1").put("status", "inProgress").put("items", JSONArray().put(activity))))
      else if (method == "account/read") JSONObject().put("requiresOpenaiAuth", false) else JSONObject()
    }
    api.selectCodexSession(session)
    val live = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.synthetic" } } }
    transport.frames.emit(JSONObject().put("method", "item/started").put("params", JSONObject().put("threadId", session.id).put("item", activity)))
    val event = live.await()
    val notice = event.properties.str("description")
    assertTrue(notice.contains("已开始"))
    assertTrue(notice.contains("worker/research"))
    assertTrue(notice.contains("child-thread"))
    assertTrue(notice.contains("example-model"))
    val history = api.messagesPage(session.id, null, 20).items.single()
    assertEquals(event.id, history.id)
    assertNull(history.notice?.target)
    val projected = TranscriptProjection.apply(emptyList(), event)!!.messages.single()
    assertEquals(history.id, projected.id)
    assertNull(projected.notice?.target)
    assertTrue(TranscriptProjection.apply(listOf(history), event)!!.messages.size == 1)
    api.close()
  }

}
