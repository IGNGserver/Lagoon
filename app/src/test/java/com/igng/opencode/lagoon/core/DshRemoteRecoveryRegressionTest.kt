package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual mux and RPC behavior; the Host is isolated and no approval is sent to a real service. */
class DshRemoteRecoveryRegressionTest {
  private fun profile(server: MockWebServer) = ServerProfile(
    "srv", "DSH", server.url("/").toString().trimEnd('/'), username = "dsh", allowCleartext = true, protocol = HarnessProtocol.DSH
  )

  private fun rootOk() = MockResponse().setResponseCode(303)
    .addHeader("Set-Cookie", "dsh-auth-abc=cookie-value; Path=/; HttpOnly; SameSite=Strict")
    .addHeader("Location", "./")

  private fun envelope(value: Any) = MockResponse().setBody(
    JSONObject().put("type", "server-response").put("rpcId", "x")
      .put("result", JSONObject().put("ok", true).put("value", value)).toString()
  )

  private fun row() = JSONObject().put("sessionId", "s1").put("updatedAt", 1).put("running", false)
    .put("blank", false).put("agentAvailable", true).put("formatStatus", "current").put("cwd", "/repo")
    .put("projections", JSONObject().put("kind", "sequenced").put("asOfSeq", 1)
      .put("values", JSONObject().put("title", "Test")))

  private fun record(seq: Int) = JSONObject().put("type", "event").put("event", JSONObject()
    .put("type", "assistant/message").put("seq", seq).put("time", 1_000L + seq)
    .put("data", JSONObject().put("turn", 1).put("step", seq)
      .put("message", JSONObject().put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "step $seq"))))))

  private fun snapshot(vararg seqs: Int) = JSONObject().put("type", "snapshot").put("cursor", seqs.maxOrNull() ?: 0)
    .put("records", JSONArray().apply { seqs.forEach { put(record(it)) } }).put("hasMore", false)

  private fun sendItem(socket: WebSocket, stream: String, value: JSONObject) {
    socket.send(JSONObject().put("type", "item").put("streamId", stream).put("value", value).toString())
  }

  private fun waterfall() = JSONObject().put("type", "waterfall").put("event", "approval/request")
    .put("eventId", "wf-1").put("agentId", "s1")
    .put("request", JSONObject().put("toolName", "bash").put("callId", "call-1").put("reason", "allow?"))

  private suspend fun nextType(channel: Channel<ServerEvent>, type: String): ServerEvent = withTimeout(8_000) {
    while (true) {
      val next = channel.receive()
      if (next.type == type) return@withTimeout next
    }
    error("unreachable")
  }

  @Test fun failedApprovalStaysPendingAndCannotReplayUntilRedelivery() = runBlocking {
    val server = MockWebServer()
    val submissions = AtomicInteger()
    val eventOpens = AtomicInteger()
    val allowRedelivery = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
        request.path == "/api/\$events/result" -> if (submissions.incrementAndGet() == 1)
          MockResponse().setResponseCode(503).addHeader("Retry-After", "0") else envelope(JSONObject.NULL)
        request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val open = JSONObject(text)
            if (open.optString("type") != "open" || open.optString("endpoint") != "\$events") return
            val attempt = eventOpens.incrementAndGet()
            if (attempt > 1) allowRedelivery.await(6, TimeUnit.SECONDS)
            val stream = open.getString("streamId")
            sendItem(webSocket, stream, JSONObject().put("type", "ready").put("clientId", "client-$attempt"))
            sendItem(webSocket, stream, waterfall())
          }
        })
        else -> MockResponse().setResponseCode(404)
      }
    }
    server.start()
    val client = DshApi(profile(server), ServerCredentials("dsh", "tok"))
    val events = Channel<ServerEvent>(Channel.UNLIMITED)
    val collector = launch { client.events().collect { events.send(it) } }
    try {
      val first = nextType(events, "permission.asked").properties.toPermission("/repo")
      val failure = runCatching { client.replyPermission(first, "once") }.exceptionOrNull()
      assertEquals("approval/delivery-unknown", (failure as ApiException).code)
      assertEquals(listOf("wf-1"), client.pendingPermissions("").map { it.id })
      assertTrue(runCatching { client.replyPermission(first, "once") }.isFailure)
      assertEquals("ambiguous delivery must not be replayed", 1, submissions.get())
      allowRedelivery.countDown()
      val redelivered = nextType(events, "permission.asked").properties.toPermission("/repo")
      client.replyPermission(redelivered, "once")
      assertEquals(2, submissions.get())
      assertTrue(client.pendingPermissions("").isEmpty())
    } finally {
      allowRedelivery.countDown()
      collector.cancelAndJoin(); client.close(); server.shutdown()
    }
  }

  @Test fun ambiguousApprovalSettledElsewhereIsClearedOnlyAfterNewReady() = runBlocking {
    val server = MockWebServer()
    val submissions = AtomicInteger()
    val eventOpens = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
        request.path == "/api/\$events/result" -> {
          submissions.incrementAndGet()
          // The Host may have accepted the decision while its response was lost.
          MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
        }
        request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val open = JSONObject(text)
            if (open.optString("type") != "open" || open.optString("endpoint") != "\$events") return
            val attempt = eventOpens.incrementAndGet()
            val stream = open.getString("streamId")
            sendItem(webSocket, stream, JSONObject().put("type", "ready").put("clientId", "client-$attempt"))
            if (attempt == 1) sendItem(webSocket, stream, waterfall())
          }
        })
        else -> MockResponse().setResponseCode(404)
      }
    }
    server.start()
    val client = DshApi(profile(server), ServerCredentials("dsh", "tok"))
    val events = Channel<ServerEvent>(Channel.UNLIMITED)
    val collector = launch { client.events().collect { events.send(it) } }
    try {
      val first = nextType(events, "permission.asked").properties.toPermission("/repo")
      assertTrue(runCatching { client.replyPermission(first, "once") }.isFailure)
      nextType(events, "permission.replied")
      assertTrue(client.pendingPermissions("").isEmpty())
      assertTrue(eventOpens.get() >= 2)
      assertEquals(1, submissions.get())
    } finally { collector.cancelAndJoin(); client.close(); server.shutdown() }
  }

  @Test fun approvalRedeliveredWhileReplyIsInFlightIsNotRemovedByItsAcknowledgement() = runBlocking {
    val server = MockWebServer()
    val submissionStarted = CompletableDeferred<Unit>()
    val allowReply = CountDownLatch(1)
    val eventSocket = AtomicReference<Pair<WebSocket, String>>()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
        request.path == "/api/\$events/result" -> {
          submissionStarted.complete(Unit)
          allowReply.await(6, TimeUnit.SECONDS)
          envelope(JSONObject.NULL)
        }
        request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val open = JSONObject(text)
            if (open.optString("type") != "open" || open.optString("endpoint") != "\$events") return
            val stream = open.getString("streamId")
            eventSocket.set(webSocket to stream)
            sendItem(webSocket, stream, JSONObject().put("type", "ready").put("clientId", "client-1"))
            sendItem(webSocket, stream, waterfall())
          }
        })
        else -> MockResponse().setResponseCode(404)
      }
    }
    server.start()
    val client = DshApi(profile(server), ServerCredentials("dsh", "tok"))
    val events = Channel<ServerEvent>(Channel.UNLIMITED)
    val collector = launch { client.events().collect { events.send(it) } }
    try {
      val first = nextType(events, "permission.asked").properties.toPermission("/repo")
      val reply = async { client.replyPermission(first, "once") }
      withTimeout(8_000) { submissionStarted.await() }
      val (socket, stream) = eventSocket.get()
      sendItem(socket, stream, waterfall())
      nextType(events, "permission.asked")
      assertTrue(runCatching { client.replyPermission(first, "once") }.isFailure)
      allowReply.countDown()
      reply.await()
      assertEquals(listOf("wf-1"), client.pendingPermissions("").map { it.id })
    } finally {
      allowReply.countDown()
      collector.cancelAndJoin(); client.close(); server.shutdown()
    }
  }

  @Test fun workspaceFirstFrameTimeoutIsAFailureAndClosesTheOneShotStream() = runBlocking {
    val server = MockWebServer()
    val streams = AtomicInteger()
    val cancels = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
        request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JSONObject(text)
            if (frame.optString("type") == "cancel") { cancels.incrementAndGet(); return }
            if (frame.optString("type") != "open" || frame.optString("endpoint") != "workspace/follow") return
            if (streams.incrementAndGet() > 1) sendItem(webSocket, frame.getString("streamId"),
              JSONObject().put("type", "baseline").put("value", JSONObject().put("items", JSONArray().put(
                JSONObject().put("workspaceId", "w1").put("path", "/repo").put("title", "Repo")))))
          }
        })
        else -> MockResponse().setResponseCode(404)
      }
    }
    server.start()
    val client = DshApi(profile(server), ServerCredentials("dsh", "tok"), streamFirstFrameTimeoutMillis = 150)
    try {
      val failure = runCatching { client.projects() }.exceptionOrNull()
      assertTrue(failure is ApiException)
      assertTrue(failure!!.message.orEmpty().contains("超时"))
      assertEquals("/repo", client.projects().single().directory)
      withTimeout(2_000) { while (cancels.get() < 1) delay(10) }
      assertEquals(2, streams.get())
    } finally { client.close(); server.shutdown() }
  }

  @Test fun timedOutSnapshotIsRetriedAndAReplacementCanLoad() = runBlocking {
    val server = MockWebServer()
    val follows = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
        request.path == "/api/session/list" -> envelope(JSONObject().put("items", JSONArray().put(row())))
        request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val open = JSONObject(text)
            if (open.optString("type") != "open" || open.optString("endpoint") != "session/follow") return
            if (follows.incrementAndGet() >= 3) sendItem(webSocket, open.getString("streamId"), snapshot(1))
          }
        })
        else -> MockResponse().setResponseCode(404)
      }
    }
    server.start()
    val client = DshApi(profile(server), ServerCredentials("dsh", "tok"), streamFirstFrameTimeoutMillis = 100)
    try {
      val failure = runCatching { client.messagesPage("s1") }.exceptionOrNull()
      assertTrue("internal timeout must be a read failure, not coroutine cancellation", failure is ApiException)
      assertTrue(failure!!.message.orEmpty().contains("超时"))
      assertEquals(2, follows.get())
      assertEquals(1, withTimeout(3_000) { client.messagesPage("s1").items.size })
      assertEquals(3, follows.get())
    } finally { client.close(); server.shutdown() }
  }

  @Test fun callerCancellationIsNotConvertedToAnInternalTimeout() = runBlocking {
    val server = MockWebServer()
    val follows = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
        request.path == "/api/session/list" -> envelope(JSONObject().put("items", JSONArray().put(row())))
        request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val open = JSONObject(text)
            if (open.optString("type") == "open" && open.optString("endpoint") == "session/follow") follows.incrementAndGet()
          }
        })
        else -> MockResponse().setResponseCode(404)
      }
    }
    server.start()
    val client = DshApi(profile(server), ServerCredentials("dsh", "tok"), streamFirstFrameTimeoutMillis = 4_000)
    try {
      val failure = runCatching { withTimeout(200) { client.messagesPage("s1") } }.exceptionOrNull()
      assertTrue(failure is CancellationException)
      assertEquals(1, follows.get())
    } finally { client.close(); server.shutdown() }
  }

  @Test fun reconnectSnapshotRepairsMissingRecordsBeforeNewWindow() = runBlocking {
    reconnectSnapshotTest(repairWorks = true)
  }

  @Test fun reconnectSnapshotInvalidatesWhenMissingRecordsCannotBeRepaired() = runBlocking {
    reconnectSnapshotTest(repairWorks = false)
  }

  @Test fun missingRecordsRetryInBackgroundAndNotifyWhenRepaired() = runBlocking {
    reconnectSnapshotTest(repairWorks = false, recoverAfterFailure = true)
  }

  private suspend fun reconnectSnapshotTest(repairWorks: Boolean, recoverAfterFailure: Boolean = false) {
    val server = MockWebServer()
    val follows = AtomicInteger()
    val firstFollow = AtomicReference<Pair<WebSocket, String>>()
    val pages = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
        request.path == "/api/session/list" -> envelope(JSONObject().put("items", JSONArray().put(row())))
        request.path == "/api/session/page" -> {
          val pageAttempt = pages.incrementAndGet()
          val args = JSONObject(request.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getJSONObject("request")
          assertEquals(4, args.getInt("throughSeq"))
          val before = args.getInt("beforeSeq")
          val records = if (!repairWorks && !(recoverAfterFailure && pageAttempt > 1)) JSONArray() else when (before) {
            5 -> JSONArray().put(record(4))
            4 -> JSONArray().put(record(3))
            else -> throw AssertionError("unexpected gap cursor $before")
          }
          envelope(JSONObject().put("records", records).put("hasMore", before == 5))
        }
        request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val open = JSONObject(text)
            if (open.optString("type") != "open") return
            val stream = open.getString("streamId")
            when (open.optString("endpoint")) {
              "\$events" -> sendItem(webSocket, stream, JSONObject().put("type", "ready").put("clientId", "fixture"))
              "session/follow" -> {
                if (follows.incrementAndGet() == 1) {
                  firstFollow.set(webSocket to stream)
                  sendItem(webSocket, stream, snapshot(1, 2))
                } else sendItem(webSocket, stream, snapshot(5, 6))
              }
            }
          }
        })
        else -> MockResponse().setResponseCode(404)
      }
    }
    server.start()
    val client = DshApi(profile(server), ServerCredentials("dsh", "tok"))
    val events = Channel<ServerEvent>(Channel.UNLIMITED)
    val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
      client.events().collect { events.send(it) }
    }
    try {
      client.messagesPage("s1")
      val (socket, stream) = withTimeout(3_000) {
        while (firstFollow.get() == null) delay(10)
        firstFollow.get()!!
      }
      socket.send(JSONObject().put("type", "end").put("streamId", stream).toString())
      if (repairWorks) {
        val seen = mutableListOf<String>()
        withTimeout(8_000) {
          while (seen.size < 4) {
            val event = events.receive()
            if (event.type == "session.dsh.assistant") seen += event.properties.str("assistantMessageID")
          }
        }
        assertEquals(listOf("msg_1:3", "msg_1:4", "msg_1:5", "msg_1:6"), seen)
      } else {
        val gap = nextType(events, "session.transcript.invalidate")
        assertTrue(gap.properties.optBoolean("incomplete"))
        if (recoverAfterFailure) {
          val repaired = withTimeout(12_000) { nextType(events, "session.transcript.invalidate") }
          assertFalse(repaired.properties.optBoolean("incomplete", true))
          assertTrue(client.messagesPage("s1").items.isNotEmpty())
        }
      }
      assertTrue(pages.get() >= if (repairWorks) 2 else 1)
    } finally { collector.cancelAndJoin(); client.close(); server.shutdown() }
  }
}
