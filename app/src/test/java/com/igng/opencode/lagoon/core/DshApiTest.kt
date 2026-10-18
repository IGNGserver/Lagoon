package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** DSH 侧协议翻译：令牌换 Cookie、一元 RPC 信封、mux 流帧、审批 waterfall。 */
class DshApiTest {
  private fun profile(server: MockWebServer) =
    ServerProfile("srv", "DSH", server.url("/").toString().trimEnd('/'), username = "dsh", allowCleartext = true, kind = ServerKind.DSH)

  private fun api(server: MockWebServer, token: String = "tok") =
    DshApi(profile(server), ServerCredentials("dsh", token))

  private fun connectOk() = MockResponse().setResponseCode(302)
    .addHeader("Set-Cookie", "dsh-auth-abc=cookie-value; Path=/; HttpOnly; SameSite=Strict")
    .addHeader("Location", "./api/remote.mux")

  private fun envelope(value: String) = MockResponse().setBody(
    """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":$value}}"""
  )

  @Test fun listingExchangesTokenForCookieThenSendsRequestEnvelope() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(connectOk())
      server.enqueue(envelope("""{"items":[{"sessionId":"s1","updatedAt":100,"running":false,"blank":false,"parentSessionId":null,"agentAvailable":true,"formatStatus":"current","projections":{"kind":"cached","asOfSeq":3,"values":{"title":"修复登录","workingDirectory":"/repo"}}}]}"""))
      val page = api(server).rootSessionsPage()
      val connect = server.takeRequest()
      assertEquals("/connect", connect.requestUrl!!.encodedPath)
      assertEquals("tok", connect.requestUrl!!.queryParameter("token"))
      assertEquals("answer", connect.requestUrl!!.queryParameter("interactions"))
      val list = server.takeRequest()
      assertEquals("POST", list.method)
      assertEquals("/api/session/list", list.requestUrl!!.encodedPath)
      assertEquals("dsh-auth-abc=cookie-value", list.getHeader("Cookie"))
      val body = JSONObject(list.body.readUtf8())
      assertEquals("client-request", body.getString("type"))
      assertEquals("session/list", body.getString("method"))
      assertNotNull(body.optString("rpcId").takeIf { it.isNotBlank() })
      assertNotNull(body.getJSONObject("payload").optJSONObject("_request"))
      val session = page.items.single()
      assertEquals("s1", session.id)
      assertEquals("修复登录", session.title)
      assertEquals("/repo", session.directory)
      assertEquals(null, page.next)
    }
  }

  @Test fun eventsStreamPublishesReadyAckAndSessionCreated() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      val opened = CountDownLatch(1)
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/connect") -> connectOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              assertEquals("\$events", frame.getString("endpoint"))
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
              webSocket.send(
                """{"type":"item","streamId":"$id","value":{"type":"emit","event":"api-session/added","args":[{"sessionId":"s9","updatedAt":1,"running":false,"blank":false,"agentAvailable":true,"formatStatus":"current","projections":{"kind":"cached","asOfSeq":1,"values":{"title":"新会话","workingDirectory":"/repo"}}}]}}"""
              )
              opened.countDown()
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val events = withTimeout(20_000) { api(server).events { opened.countDown() }.take(1).toList() }
      assertTrue(opened.await(10, TimeUnit.SECONDS))
      val event = events.single()
      assertEquals("session.created", event.type)
      assertEquals("s9", event.properties.getString("sessionID"))
      assertEquals("/repo", event.directory)
    } finally {
      runCatching { server.shutdown() }
    }
  }

  @Test fun approvalWaterfallBecomesPermissionAndReplySettlesIt() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/connect") -> connectOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
              webSocket.send(
                """{"type":"item","streamId":"$id","value":{"type":"waterfall","event":"approval/request","eventId":"wf-1","agentId":"s1","request":{"toolName":"bash","callId":"call-9","reason":"执行 rm -rf build"}}}"""
              )
            }
          })
          request.path == "/api/\$events/result" -> envelope("null")
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      val event = withTimeout(20_000) { client.events {}.take(1).toList() }.single()
      assertEquals("permission.asked", event.type)
      assertEquals("wf-1", event.properties.getString("id"))
      assertEquals("s1", event.properties.getString("sessionID"))
      assertEquals("bash", event.properties.getString("action"))
      client.replyPermission(event.properties.toPermission("/repo"), "once")
      val settle = generateSequence { server.takeRequest(2, TimeUnit.SECONDS) }.first { it.requestUrl!!.encodedPath == "/api/\$events/result" }
      assertEquals("/api/\$events/result", settle.requestUrl!!.encodedPath)
      val body = JSONObject(settle.body.readUtf8()).getJSONObject("payload")
      assertEquals("c1", body.getString("clientId"))
      assertEquals("wf-1", body.getString("eventId"))
      assertEquals("allowed-once", body.getJSONObject("outcome").getString("value"))
    } finally {
      runCatching { server.shutdown() }
    }
  }

  @Test fun snapshotFramesBecomeTimelineMessages() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/connect") -> connectOk()
          request.path == "/api/session/list" -> envelope(
            """{"items":[{"sessionId":"s1","updatedAt":9,"running":false,"blank":false,"agentAvailable":true,"formatStatus":"current","projections":{"kind":"cached","asOfSeq":9,"values":{"title":"T","workingDirectory":"/repo"}}}]}"""
          )
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              if (frame.getString("endpoint") != "session/follow") return
              val id = frame.getString("streamId")
              val records = JSONArray().apply {
                put(JSONObject("""{"type":"event","event":{"type":"user/message","seq":2,"time":1000,"data":{"content":[{"type":"text","text":"你好"}],"source":{"kind":"user"},"role":"user","id":"u1"}}}"""))
                put(JSONObject("""{"type":"event","event":{"type":"step/start","seq":3,"time":1000,"data":{"turn":1,"step":1}}}"""))
                put(JSONObject("""{"type":"event","event":{"type":"assistant/message","seq":4,"time":1100,"data":{"turn":1,"step":1,"message":{"role":"assistant","content":[{"type":"reasoning","text":"想一想"},{"type":"text","text":"你好！"}]}}}}"""))
                put(JSONObject("""{"type":"event","event":{"type":"tool/call","seq":5,"time":1150,"data":{"turn":1,"step":1,"callId":"c1","name":"bash","arguments":"{\"command\":\"ls\"}"}}}"""))
                put(JSONObject("""{"type":"event","event":{"type":"tool/result","seq":6,"time":1200,"data":{"turn":1,"step":1,"message":{"role":"tool","toolCallId":"c1","content":[{"type":"text","text":"README.md"}]}}}}"""))
                put(JSONObject("""{"type":"event","event":{"type":"step/end","seq":7,"time":1300,"data":{"turn":1,"step":1}}}"""))
              }
              val value = JSONObject().put("type", "snapshot")
                .put("header", JSONObject().put("version", 4).put("id", "s1").put("createdAt", 1).put("cwd", "/repo"))
                .put("cursor", 9).put("hasMore", false).put("records", records)
              webSocket.send(JSONObject().put("type", "item").put("streamId", id).put("value", value).toString())
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val messages = api(server).messagesPage("s1", null, HarnessClient.MESSAGE_PAGE).items
      assertEquals(2, messages.size)
      assertEquals("user", messages[0].role)
      assertEquals("你好", messages[0].parts.joinToString("") { it.text })
      val assistant = messages[1]
      assertEquals("assistant", assistant.role)
      assertTrue(assistant.parts.any { it.type == "reasoning" && it.text == "想一想" })
      assertTrue(assistant.parts.any { it.type == "text" && it.text == "你好！" })
      val tool = assistant.parts.single { it.type == "tool" }
      assertEquals("bash", tool.tool)
      assertEquals("completed", tool.status)
      assertTrue(tool.output.orEmpty().contains("README.md"))
    } finally {
      runCatching { server.shutdown() }
    }
  }

  @Test fun promptCarriesRequestIdAndMode() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(connectOk())
      server.enqueue(envelope("""{"accepted":true}"""))
      val client = api(server)
      client.send(Session("s1", "/repo", "T", 1), "继续", null, null, id = "req-7", delivery = Delivery.QUEUE)
      server.takeRequest()
      val call = server.takeRequest()
      assertEquals("/api/session/prompt", call.requestUrl!!.encodedPath)
      val request = JSONObject(call.body.readUtf8()).getJSONObject("payload").getJSONObject("request")
      assertEquals("req-7", request.getString("requestId"))
      assertEquals("s1", request.getString("sessionId"))
      assertEquals("queue", request.getString("mode"))
      assertEquals("继续", request.getJSONArray("content").getJSONObject(0).getString("text"))
    }
  }

  @Test fun dshKindRoutesThroughTheFactory() {
    val server = MockWebServer()
    val client = createHarnessClient(profile(server), ServerCredentials("dsh", "tok"))
    assertTrue(client is DshApi)
    val opencode = createHarnessClient(profile(server).copy(kind = ServerKind.OPENCODE), ServerCredentials("opencode", "pw"))
    assertTrue(opencode is OpenCodeApi)
    assertFalse(client === opencode)
  }
}
