package com.igng.opencode.lagoon.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Question lifecycle through the real HTTP RPC and physical mux transports. */
class DshQuestionIntegrationTest {
  private fun rootOk() = MockResponse().setResponseCode(303)
    .addHeader("Set-Cookie", "dsh-auth-abc=cookie-value; Path=/; HttpOnly; SameSite=Strict")
    .addHeader("Location", "./")

  private fun envelope(value: Any?) = MockResponse().setBody(JSONObject()
    .put("type", "server-response").put("rpcId", "fixture")
    .put("result", JSONObject().put("ok", true).put("value", value ?: JSONObject.NULL)).toString())

  private fun questions() = JSONArray().put(JSONObject().put("id", "question-1")
    .put("question", "Choose a plan").put("multiSelect", false)
    .put("options", JSONArray().put(JSONObject().put("label", "A")).put(JSONObject().put("label", "B"))))

  private fun values(continued: Boolean = false, queuedMain: Boolean = false, extraQueued: Boolean = false,
    activeState: String? = if (continued) "continued" else null): JSONObject {
    val active = JSONArray()
    fun row(callId: String) = JSONObject().put("callId", callId).put("state", activeState ?: "continued").put("questions", questions())
    fun queued(callId: String) = JSONObject().put("id", "reply-$callId")
      .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "A")))
      .put("source", JSONObject().put("kind", "user-question-reply").put("callId", callId))
    val nextStep = JSONArray()
    val nextTurn = JSONArray()
    if (activeState != null) active.put(row("call-7"))
    if (queuedMain) nextTurn.put(queued("call-7"))
    if (extraQueued) {
      active.put(row("queued-step")).put(row("queued-turn"))
      nextStep.put(queued("queued-step"))
      nextTurn.put(queued("queued-turn"))
    }
    val settled = JSONArray().put(JSONObject().put("callId", "settled-call")
      .put("answers", JSONArray().put(JSONObject().put("id", "question-1").put("selected", JSONArray().put("B")))))
    return JSONObject().put("userQuestions", JSONObject().put("active", active).put("settled", settled))
      .put("inbox", JSONObject().put("next-step", nextStep).put("next-turn", nextTurn))
  }

  private data class Rpc(val endpoint: String, val args: JSONObject)
  private data class MuxFrame(val endpoint: String, val frame: JSONObject)
  private data class Stream(val socket: WebSocket, val id: String) {
    fun send(value: JSONObject) {
      check(socket.send(JSONObject().put("type", "item").put("streamId", id).put("value", value).toString()))
    }
  }

  private inner class Fixture(private val scope: CoroutineScope, val remainingMs: Long = 5_000) {
    val now = AtomicLong(1_000)
    val server = MockWebServer()
    val calls = CopyOnWriteArrayList<Rpc>()
    val opens = CopyOnWriteArrayList<MuxFrame>()
    val cancels = CopyOnWriteArrayList<MuxFrame>()
    private val projection = AtomicReference(values().toString())
    private val sessionRows = AtomicReference("[]")
    private val sessionProjections = ConcurrentHashMap<String, String>()
    private val scriptedProjections = ConcurrentLinkedQueue<String>()
    val physicalClosed = CompletableDeferred<Unit>()
    private val publications = Channel<ServerEvent>(Channel.UNLIMITED)
    private val muxFrames = Channel<MuxFrame>(Channel.UNLIMITED)
    private val rpcFrames = Channel<Rpc>(Channel.UNLIMITED)
    private var collector: Job? = null
    private var barrier = 0
    @Volatile private var eventsStream: Stream? = null
    @Volatile var resultStatus = 200
    @Volatile var projectionAfterResult: String? = null
    @Volatile var projectionAfterAnswer: String? = null
    @Volatile var holdResult = false
    val resultEntered = CountDownLatch(1)
    val resultRelease = CountDownLatch(1)
    val resultReturned = CountDownLatch(1)
    val holdNextProjection = AtomicBoolean(false)
    val projectionEntered = CountDownLatch(1)
    val projectionRelease = CountDownLatch(1)
    var client: DshApi
      private set

    init {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.requestUrl!!.encodedPath
          if (path == "/") return rootOk()
          if (path == "/api/remote.mux") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            private val endpoints = mutableMapOf<String, String>()
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              val id = frame.getString("streamId")
              if (frame.getString("type") == "open") endpoints[id] = frame.getString("endpoint")
              val message = MuxFrame(endpoints[id].orEmpty(), frame)
              muxFrames.trySend(message)
              if (frame.getString("type") == "cancel") cancels += message
              if (frame.getString("type") != "open") return
              opens += message
              val stream = Stream(webSocket, id)
              when (message.endpoint) {
                "\$events" -> {
                  eventsStream = stream
                  stream.send(JSONObject().put("type", "ready").put("clientId", "question-client"))
                }
                "userQuestions/attachWait" -> stream.send(JSONObject().put("remainingMs", remainingMs))
              }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
              physicalClosed.complete(Unit)
              webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { physicalClosed.complete(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
              physicalClosed.complete(Unit)
            }
          })
          val endpoint = path.removePrefix("/api/")
          val rpc = Rpc(endpoint, JSONObject(request.body.readUtf8()).getJSONObject("payload").getJSONObject("args"))
          calls += rpc
          rpcFrames.trySend(rpc)
          return when (endpoint) {
            "session/list" -> envelope(JSONObject().put("items", JSONArray(sessionRows.get())))
            "session/projections" -> {
              val sessionId = rpc.args.getJSONObject("request").getString("sessionId")
              val value = scriptedProjections.poll() ?: sessionProjections[sessionId] ?: projection.get()
              if (holdNextProjection.compareAndSet(true, false)) {
                projectionEntered.countDown()
                check(projectionRelease.await(10, TimeUnit.SECONDS)) { "Test did not release the projection response" }
              }
              envelope(JSONObject().put("kind", "sequenced").put("asOfSeq", 1).put("values", JSONObject(value)))
            }
            "\$events/result" -> {
              projectionAfterResult?.let(projection::set)
              if (holdResult) {
                resultEntered.countDown()
                check(resultRelease.await(10, TimeUnit.SECONDS)) { "Test did not release the result response" }
              }
              resultReturned.countDown()
              if (resultStatus == 200) envelope(null) else MockResponse().setResponseCode(resultStatus)
            }
            "userQuestions/answer" -> {
              projectionAfterAnswer?.let(projection::set)
              envelope(true)
            }
            else -> MockResponse().setResponseCode(404)
          }
        }
      }
      server.start()
      client = newClient()
    }

    private fun newClient() = DshApi(ServerProfile("srv", "DSH", server.url("/").toString().trimEnd('/'),
      username = "dsh", allowCleartext = true, kind = ServerKind.DSH), ServerCredentials("dsh", "tok"), clock = now::get)

    fun project(value: JSONObject) { projection.set(value.toString()) }
    fun project(sessionId: String, value: JSONObject) { sessionProjections[sessionId] = value.toString() }
    fun projectSequence(vararg responses: JSONObject) { responses.forEach { scriptedProjections.add(it.toString()) } }
    fun listSessions(vararg ids: String) {
      sessionRows.set(JSONArray().apply {
        ids.forEachIndexed { index, id -> put(JSONObject().put("sessionId", id).put("updatedAt", index + 1)
          .put("running", false).put("blank", false).put("agentAvailable", true).put("formatStatus", "current")
          .put("cwd", "/repo").put("projections", JSONObject().put("kind", "sequenced").put("asOfSeq", 1)
            .put("values", JSONObject().put("title", "Session $id").put("agentPreset", "standard")))) }
      }.toString())
    }

    suspend fun start() {
      val ready = CompletableDeferred<Unit>()
      client.setContentWatching(false)
      // Keep collection alive until cleanup: taking the first event would close the logical $events stream.
      collector = scope.launch { client.events { ready.complete(Unit) }.collect { publications.send(it) } }
      withTimeout(10_000) { ready.await() }
    }

    suspend fun restart() {
      client.close()
      collector?.cancelAndJoin()
      client = newClient()
      start()
    }

    suspend fun stopCollector() { collector?.cancelAndJoin() }

    fun refreshAfterDurableEvent(type: String) {
      val method = DshApi::class.java.getDeclaredMethod("refreshQuestionsAfterDurableEvent", String::class.java, JSONObject::class.java)
        .apply { isAccessible = true }
      method.invoke(client, "s1", JSONObject().put("type", type))
    }

    fun waterfall(eventId: String, timed: Boolean = true) {
      send(JSONObject().put("type", "waterfall").put("event", "user-questions/request")
        .put("eventId", eventId).put("agentId", "s1").put("request", JSONObject().put("questions", questions())
          .put("wait", JSONObject().put("callId", "call-7").put("timed", timed))))
    }

    fun legacyWaterfall(eventId: String) {
      send(JSONObject().put("type", "waterfall").put("event", "user-questions/request")
        .put("eventId", eventId).put("agentId", "s1")
        .put("request", JSONObject().put("questions", questions())))
    }

    fun cancel(eventId: String) { send(JSONObject().put("type", "cancel").put("eventId", eventId)) }
    private fun send(frame: JSONObject) { requireNotNull(eventsStream).send(frame) }

    /** An ordered event-stream barrier, including when a duplicate waterfall produces no form change. */
    suspend fun flushEvents() {
      val marker = "question-test-barrier-${++barrier}"
      send(JSONObject().put("type", "emit").put("event", "api-session/error")
        .put("args", JSONArray().put("s1").put(marker)))
      event { it.type == "session.error" && it.properties.optString("message") == marker }
    }

    suspend fun event(predicate: (ServerEvent) -> Boolean): ServerEvent =
      withTimeout(10_000) { publications.receiveAsFlow().first { predicate(it) } }

    suspend fun form(type: String, predicate: (QuestionRequest) -> Boolean = { true }): QuestionRequest {
      val event = event { it.type == type && predicate(it.properties.getJSONObject("form").toForm(it.directory)) }
      return event.properties.getJSONObject("form").toForm(event.directory)
    }

    suspend fun mux(type: String, endpoint: String): MuxFrame = withTimeout(10_000) {
      muxFrames.receiveAsFlow().first { it.endpoint == endpoint && it.frame.getString("type") == type }
    }

    suspend fun rpc(endpoint: String): Rpc = withTimeout(10_000) {
      rpcFrames.receiveAsFlow().first { it.endpoint == endpoint }
    }

    fun tick() {
      // Use the injected clock without exposing an extra production API or waiting for the real ticker.
      val field = DshApi::class.java.getDeclaredField("questionCoordinatorDelegate").apply { isAccessible = true }
      ((field.get(client) as Lazy<*>).value as DshQuestionCoordinator).tick()
    }

    suspend fun close() {
      projectionRelease.countDown()
      resultRelease.countDown()
      try { client.close() } finally {
        try { collector?.cancelAndJoin() } finally {
          server.shutdown()
          publications.close()
          muxFrames.close()
          rpcFrames.close()
        }
      }
    }
  }

  @Test fun foregroundClaimsOncePublishesCountdownAndBackgroundDelegatesExactNext() = runBlocking {
    val f = Fixture(this)
    try {
      f.client.questionPresentation("s1", true)
      f.start()
      f.waterfall("event-live")
      val created = f.form("form.created")
      assertNotEquals("event-live", created.id)
      assertNotEquals("call-7", created.id)
      assertEquals("question-1", JSONObject(created.questions.single().field).getString("key"))
      assertEquals(listOf("A", "B"), created.questions.single().options.map { it.value })
      val claimed = f.form("form.updated") { it.presentation?.countdownRunning == true }
      assertEquals(created.id, claimed.id)
      assertEquals(5_000L, claimed.presentation!!.remainingMs)
      val claim = f.mux("open", "userQuestions/attachWait")
      val claimArgs = claim.frame.getJSONObject("payload").getJSONObject("args")
      assertEquals(2, claimArgs.length())
      assertEquals("s1", claimArgs.getString("agentId"))
      assertEquals("call-7", claimArgs.getString("callId"))
      f.waterfall("event-live")
      f.client.questionPresentation("s1", true)
      f.client.questionPresentation("s1", true)
      f.flushEvents()
      assertEquals(1, f.opens.count { it.endpoint == "userQuestions/attachWait" })

      f.now.set(2_250)
      f.tick()
      val counting = f.form("form.updated") { it.presentation?.remainingMs == 4_000L }
      assertTrue(counting.presentation!!.countdownRunning)
      f.client.questionFocus(created.id, true)
      f.form("form.updated") { it.presentation?.countdownRunning == false }
      f.now.set(20_000)
      f.tick()
      assertEquals(4_000L, f.client.forms("").single().presentation!!.remainingMs)
      f.client.questionFocus(created.id, false)
      f.now.set(20_250)
      f.client.questionEngage(created.id)
      f.form("form.updated") { it.presentation?.countdownRunning == false }
      f.now.set(60_000)
      f.tick()
      assertEquals(4_000L, f.client.forms("").single().presentation!!.remainingMs)
      f.client.questionTakeTime(created.id)
      val held = f.form("form.updated") { it.presentation?.held == true }
      assertFalse(held.presentation!!.countdownRunning)
      assertNull(held.presentation!!.remainingMs)

      f.client.questionPresentation("s1", false)
      f.event { it.type == "form.cancelled" && it.properties.getString("id") == created.id }
      val released = f.mux("cancel", "userQuestions/attachWait")
      assertEquals(claim.frame.getString("streamId"), released.frame.getString("streamId"))
      val result = f.rpc("\$events/result").args
      assertEquals(3, result.length())
      assertEquals("question-client", result.getString("clientId"))
      assertEquals("event-live", result.getString("eventId"))
      assertEquals("{\"kind\":\"next\"}", result.getJSONObject("outcome").toString())
      f.client.questionPresentation("s1", false)
      f.client.questionPresentation("s1", true)
      f.tick()
      assertTrue(f.client.forms("").isEmpty())
      assertEquals(1, f.opens.count { it.endpoint == "userQuestions/attachWait" })
      assertEquals(1, f.calls.count { it.endpoint == "\$events/result" })
      assertTrue(f.calls.none { it.endpoint == "userQuestions/answer" })
    } finally { f.close() }
  }

  @Test fun continuedProjectionSurvivesRestartAndQueuedAnswerHidesStableCard() = runBlocking {
    val f = Fixture(this)
    try {
      f.project(values(continued = true, extraQueued = true))
      f.start()
      f.client.composerContext("s1")
      val original = f.form("form.created")
      assertTrue(original.presentation!!.continued)
      assertEquals(listOf(original.id), f.client.forms("").map { it.id })
      assertNotEquals("call-7", original.id)
      f.client.questionPresentation("s1", true)
      for (event in listOf("before-restart-1", "before-restart-2")) {
        f.waterfall(event, timed = false)
        f.flushEvents()
        assertEquals(original.id, f.client.forms("").single().id)
        assertNotEquals(event, original.id)
        f.cancel(event)
        f.flushEvents()
        assertEquals(original.id, f.client.forms("").single().id)
      }
      f.restart()
      f.client.composerContext("s1")
      val recovered = f.form("form.created")
      assertEquals(original.id, recovered.id)
      assertTrue(recovered.presentation!!.continued)
      assertFalse(recovered.presentation!!.countdownRunning)
      // Both next-step and next-turn already contain replies for the two other active calls.
      assertEquals(listOf(recovered.id), f.client.forms("").map { it.id })
      assertTrue(f.opens.none { it.endpoint == "userQuestions/attachWait" })

      f.projectionAfterAnswer = values(continued = true, queuedMain = true, extraQueued = true).toString()
      f.client.replyQuestion(recovered, listOf(listOf("A")))
      f.event { it.type == "form.cancelled" && it.properties.getString("id") == recovered.id }
      val answer = f.rpc("userQuestions/answer").args
      assertEquals(3, answer.length())
      assertEquals("s1", answer.getString("agentId"))
      assertEquals("call-7", answer.getString("callId"))
      val field = answer.getJSONObject("answer").getJSONArray("answers").getJSONObject(0)
      assertEquals("question-1", field.getString("id"))
      assertEquals("A", field.getJSONArray("selected").getString(0))
      assertFalse(field.has("custom"))
      f.client.composerContext("s1")
      assertTrue(f.client.forms("").isEmpty())
      val queuedRepeat = runCatching { f.client.replyQuestion(recovered, listOf(listOf("A"))) }.exceptionOrNull()
      assertTrue(queuedRepeat is ApiException)
      assertEquals(409, (queuedRepeat as ApiException).status)
      assertEquals(1, f.calls.count { it.endpoint == "userQuestions/answer" })

      // Discarding the queued reply makes the same call answerable again in this same client.
      f.project(values(continued = true, extraQueued = true))
      f.client.composerContext("s1")
      val restored = f.form("form.created") { it.id == recovered.id && it.presentation?.continued == true }
      assertEquals(recovered.id, restored.id)
      assertEquals(listOf(restored.id), f.client.forms("").map { it.id })
      f.client.replyQuestion(restored, listOf(listOf("B")))
      f.event { it.type == "form.cancelled" && it.properties.getString("id") == restored.id }
      val second = f.rpc("userQuestions/answer").args
      assertEquals("s1", second.getString("agentId"))
      assertEquals("call-7", second.getString("callId"))
      assertEquals("B", second.getJSONObject("answer").getJSONArray("answers").getJSONObject(0)
        .getJSONArray("selected").getString(0))
      assertEquals(2, f.calls.count { it.endpoint == "userQuestions/answer" })
      assertTrue(f.client.forms("").isEmpty())

      // Prove suppression is backed by the queued-reply projection, not just this client's completed set.
      f.restart()
      f.client.composerContext("s1")
      assertTrue(f.client.forms("").isEmpty())
      val repeat = runCatching { f.client.replyQuestion(restored, listOf(listOf("B"))) }.exceptionOrNull()
      assertTrue(repeat is ApiException)
      assertEquals(409, (repeat as ApiException).status)
      assertEquals(2, f.calls.count { it.endpoint == "userQuestions/answer" })
      assertTrue(f.calls.none { it.endpoint == "\$events/result" })
      assertTrue(f.calls.filter { it.endpoint == "session/projections" }.all {
        it.args.getJSONObject("request").getString("sessionId") == "s1"
      })
    } finally { f.close() }
  }

  @Test fun durableRefreshCoalescesEventArrivingDuringProjectionRead() = runBlocking {
    val f = Fixture(this)
    try {
      f.client.questionPresentation("s1", true)
      f.project("s1", values(activeState = "open"))
      f.start()
      f.holdNextProjection.set(true)
      f.refreshAfterDurableEvent("tool/result")
      assertTrue(f.projectionEntered.await(10, TimeUnit.SECONDS))
      assertEquals(1, f.calls.count { it.endpoint == "session/projections" })

      f.project("s1", values(continued = true))
      f.refreshAfterDurableEvent("user/message")
      f.projectionRelease.countDown()

      val recovered = f.form("form.created") {
        it.sessionId == "s1" && it.id == "dsh:question:2:s1:6:call-7" && it.presentation?.continued == true
      }
      assertEquals("dsh:question:2:s1:6:call-7", recovered.id)
      assertTrue(recovered.presentation!!.continued)
      assertEquals(2, f.calls.count { it.endpoint == "session/projections" })
      assertEquals(2, f.calls.filter { it.endpoint == "session/projections" }.count {
        it.args.getJSONObject("request").getString("sessionId") == "s1"
      })
      assertTrue(f.opens.none { it.endpoint == "userQuestions/attachWait" })
      assertTrue(f.calls.none { it.endpoint == "\$events/result" || it.endpoint == "userQuestions/answer" })
    } finally { f.close() }
  }

  @Test fun directoryFormsHydrateQuestionsAcrossListedIdleSessions() = runBlocking {
    val f = Fixture(this)
    try {
      f.project("session-a", values(continued = true))
      f.project("session-b", values())
      f.listSessions("session-a", "session-b")
      f.start()
      val listed = f.client.rootSessionsPage("/repo", cursor = null, size = 20)
      assertEquals(setOf("session-a", "session-b"), listed.items.map { it.id }.toSet())
      // No per-session composerContext or selected-session presentation was requested.
      assertTrue(f.calls.none { it.endpoint == "session/projections" })
      val forms = f.client.forms("/repo")
      assertEquals(1, forms.size)
      assertEquals("session-a", forms.single().sessionId)
      assertEquals("/repo", forms.single().directory)
      assertTrue(forms.single().presentation!!.continued)
      assertTrue(f.opens.none { it.endpoint == "userQuestions/attachWait" })
      assertTrue(f.calls.none { it.endpoint == "\$events/result" || it.endpoint == "userQuestions/answer" })
      val reads = f.calls.filter { it.endpoint == "session/projections" }
      assertEquals(setOf("session-a", "session-b"), reads.map {
        it.args.getJSONObject("request").getString("sessionId")
      }.toSet())
      assertEquals(1, reads.count { it.args.getJSONObject("request").getString("sessionId") == "session-a" })
      assertEquals(1, reads.count { it.args.getJSONObject("request").getString("sessionId") == "session-b" })
    } finally { f.close() }
  }

  @Test fun closeKeepsMuxAliveUntilLegacyWaterfallDelegationCompletes() = runBlocking {
    val f = Fixture(this)
    try {
      f.start()
      f.client.questionPresentation("s1", true)
      f.legacyWaterfall("legacy-close-event")
      val form = f.form("form.created")
      assertEquals("s1", form.sessionId)
      f.holdResult = true
      f.client.close()
      assertTrue(f.client.isClosed)
      assertTrue(f.resultEntered.await(10, TimeUnit.SECONDS))
      assertEquals(1, f.calls.count { it.endpoint == "\$events/result" })
      val result = f.rpc("\$events/result").args
      assertEquals("question-client", result.getString("clientId"))
      assertEquals("legacy-close-event", result.getString("eventId"))
      assertEquals("{\"kind\":\"next\"}", result.getJSONObject("outcome").toString())
      assertTrue("physical mux must remain alive while delegation is in flight", !f.physicalClosed.isCompleted)
      assertTrue(f.opens.none { it.endpoint == "userQuestions/attachWait" })
      f.resultRelease.countDown()
      withTimeout(10_000) { f.physicalClosed.await() }
      assertTrue(f.client.isClosed)
      assertEquals(1, f.opens.count { it.endpoint == "\$events" })
      assertTrue(f.calls.none { it.endpoint == "userQuestions/answer" })
    } finally {
      f.resultRelease.countDown()
      f.close()
    }
  }

  @Test fun cancellingSpectatorSubscriptionDoesNotDelegateForegroundQuestionOrCancelItsClaim() = runBlocking {
    val f = Fixture(this)
    var spectator: Job? = null
    try {
      f.client.questionPresentation("s1", true)
      f.start()
      val spectatorReady = CompletableDeferred<Unit>()
      spectator = launch { f.client.events { spectatorReady.complete(Unit) }.collect {} }
      withTimeout(5_000) { spectatorReady.await() }
      f.waterfall("shared-foreground-event")
      f.form("form.created")
      f.form("form.updated") { it.presentation?.countdownRunning == true }
      val claim = f.mux("open", "userQuestions/attachWait")
      spectator.cancelAndJoin(); spectator = null
      f.flushEvents()
      assertEquals(1, f.opens.count { it.endpoint == "\$events" })
      assertTrue(f.cancels.none { it.endpoint == "\$events" || it.frame.optString("streamId") == claim.frame.optString("streamId") })
      assertTrue(f.calls.none { it.endpoint == "\$events/result" })
      assertTrue(f.client.forms("").single().presentation!!.countdownRunning)
      f.client.questionPresentation("s1", false)
      f.rpc("\$events/result")
      assertEquals(1, f.calls.count { it.endpoint == "\$events/result" })
    } finally { spectator?.cancelAndJoin(); f.close() }
  }

  @Test fun collectorCancellationBeforeApiCloseDrainsLegacyDelegationBeforeEventsCancel() = runBlocking {
    val f = Fixture(this)
    try {
      f.client.questionPresentation("s1", true)
      f.start()
      f.legacyWaterfall("collector-close-event")
      f.form("form.created")
      f.holdResult = true
      f.stopCollector()
      assertTrue(f.resultEntered.await(10, TimeUnit.SECONDS))
      assertFalse(f.client.isClosed)
      assertTrue(f.cancels.none { it.endpoint == "\$events" })
      assertFalse(f.physicalClosed.isCompleted)

      f.client.close()
      assertTrue(f.client.isClosed)
      val result = f.rpc("\$events/result").args
      assertEquals("question-client", result.getString("clientId"))
      assertEquals("collector-close-event", result.getString("eventId"))
      assertEquals("{\"kind\":\"next\"}", result.getJSONObject("outcome").toString())
      assertTrue(f.cancels.none { it.endpoint == "\$events" })
      assertFalse(f.physicalClosed.isCompleted)
      f.resultRelease.countDown()
      withTimeout(10_000) { f.client.awaitClosed(); f.physicalClosed.await() }
      assertEquals(1, f.calls.count { it.endpoint == "\$events/result" })
      assertEquals(1, f.opens.count { it.endpoint == "\$events" })
      assertTrue(f.calls.none { it.endpoint == "userQuestions/answer" })
      assertTrue(f.resultReturned.await(10, TimeUnit.SECONDS))
    } finally { f.close() }
  }

  @Test fun hostCancellationRefreshesContinuedCardWithoutComposerRead() = runBlocking {
    val f = Fixture(this)
    try {
      f.client.questionPresentation("s1", true)
      f.start()
      f.waterfall("host-cancelled-event")
      val live = f.form("form.created")
      f.form("form.updated") { it.id == live.id && it.presentation?.countdownRunning == true }
      val claim = f.mux("open", "userQuestions/attachWait")
      assertTrue(f.calls.none { it.endpoint == "session/projections" })

      f.projectSequence(values(activeState = "open"), values(continued = true))
      f.project(values(continued = true))
      f.cancel("host-cancelled-event")
      // Await the recovered card before any forms/composerContext read can trigger reconciliation.
      val changed = f.event { event ->
        if (event.type != "form.created" && event.type != "form.updated") false
        else event.properties.getJSONObject("form").let { form ->
          form.getString("id") == live.id && form.getJSONObject("questionPresentation").optBoolean("continued")
        }
      }
      val continued = changed.properties.getJSONObject("form").toForm(changed.directory)
      assertEquals(live.id, continued.id)
      assertTrue(continued.presentation!!.continued)
      assertFalse(continued.presentation!!.countdownRunning)
      assertNull(continued.presentation!!.remainingMs)
      val released = f.mux("cancel", "userQuestions/attachWait")
      assertEquals(claim.frame.getString("streamId"), released.frame.getString("streamId"))
      assertEquals(2, f.calls.count { it.endpoint == "session/projections" })
      assertTrue(f.calls.all { it.endpoint == "session/projections" })
      assertEquals(1, f.opens.count { it.endpoint == "userQuestions/attachWait" })
      assertEquals(listOf(live.id), f.client.forms("").map { it.id })
    } finally { f.close() }
  }

  @Test fun liveAnswerHttp500ReconcilesWithoutBlindContinuedAnswerFallback() = runBlocking {
    val f = Fixture(this)
    try {
      f.resultStatus = 500
      f.projectionAfterResult = values(continued = true).toString()
      f.client.questionPresentation("s1", true)
      f.start()
      f.waterfall("failed-live-event", timed = false)
      val live = f.form("form.created")
      val failure = runCatching { f.client.replyQuestion(live, listOf(listOf("B"))) }.exceptionOrNull()
      assertTrue(failure is ApiException)
      assertEquals(500, (failure as ApiException).status)
      val result = f.rpc("\$events/result").args
      assertEquals("question-client", result.getString("clientId"))
      assertEquals("failed-live-event", result.getString("eventId"))
      val outcome = result.getJSONObject("outcome")
      assertEquals("result", outcome.getString("kind"))
      val answer = outcome.getJSONObject("value").getJSONArray("answers").getJSONObject(0)
      assertEquals("question-1", answer.getString("id"))
      assertEquals("B", answer.getJSONArray("selected").getString(0))
      val continued = f.client.forms("").single()
      assertEquals(live.id, continued.id)
      assertTrue(continued.presentation!!.continued)
      assertEquals(1, f.calls.count { it.endpoint == "\$events/result" })
      assertTrue(f.calls.none { it.endpoint == "userQuestions/answer" })
      assertTrue(f.opens.none { it.endpoint == "userQuestions/attachWait" })
    } finally { f.close() }
  }

  @Test fun timedExpirationRejectsOnceAndCancelsClaimWithoutSuccessfulEmptyAnswer() = runBlocking {
    val f = Fixture(this, remainingMs = 2_500)
    try {
      f.client.questionPresentation("s1", true)
      f.start()
      f.waterfall("expiring-live-event")
      val live = f.form("form.created")
      val claimed = f.form("form.updated") { it.presentation?.countdownRunning == true }
      assertEquals(3_000L, claimed.presentation!!.remainingMs)
      val claim = f.mux("open", "userQuestions/attachWait")
      f.now.set(3_500)
      f.tick()
      f.event { it.type == "form.cancelled" && it.properties.getString("id") == live.id }
      val cancelled = f.mux("cancel", "userQuestions/attachWait")
      assertEquals(claim.frame.getString("streamId"), cancelled.frame.getString("streamId"))
      val result = f.rpc("\$events/result").args
      assertEquals("question-client", result.getString("clientId"))
      assertEquals("expiring-live-event", result.getString("eventId"))
      val outcome = result.getJSONObject("outcome")
      assertEquals("rejected", outcome.getString("kind"))
      assertFalse(outcome.has("value"))
      val error = outcome.getJSONObject("error")
      assertEquals("UserQuestionError", error.getString("name"))
      assertEquals("ASK_TIMED_OUT", error.getString("code"))
      f.tick()
      f.now.set(90_000)
      f.tick()
      f.client.questionPresentation("s1", true)
      assertTrue(f.client.forms("").isEmpty())
      assertEquals(1, f.calls.count { it.endpoint == "\$events/result" })
      assertEquals(1, f.opens.count { it.endpoint == "userQuestions/attachWait" })
      assertTrue(f.calls.none { it.endpoint == "userQuestions/answer" })
    } finally { f.close() }
  }
}
