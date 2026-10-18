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

/**
 * DSH 侧协议翻译（对齐 `@deepseek-ai/dsh@0.2.0-rc.2` 的真机契约，见 docs/dsh-protocol.md）：
 * 启动令牌换 Cookie、`{args:…}` 一元信封、mux 流帧、实时 assistant 流、审批/问答 waterfall。
 */
class DshApiTest {
  private fun profile(server: MockWebServer) =
    ServerProfile("srv", "DSH", server.url("/").toString().trimEnd('/'), username = "dsh", allowCleartext = true, kind = ServerKind.DSH)

  private fun api(server: MockWebServer, token: String = "tok") =
    DshApi(profile(server), ServerCredentials("dsh", token))

  /** 浏览器式根路径交换：3xx + `Set-Cookie: dsh-auth-…`。 */
  private fun rootOk() = MockResponse().setResponseCode(303)
    .addHeader("Set-Cookie", "dsh-auth-abc=cookie-value; Path=/; HttpOnly; SameSite=Strict")
    .addHeader("Location", "./")

  /** API Peer 交换（0.2.1 线起）：302 + `Set-Cookie` + mux 目标。 */
  private fun connectOk() = MockResponse().setResponseCode(302)
    .addHeader("Set-Cookie", "dsh-auth-abc=cookie-value; Path=/; HttpOnly; SameSite=Strict")
    .addHeader("Location", "./api/remote.mux")

  private fun envelope(value: String) = MockResponse().setBody(
    """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":$value}}"""
  )

  private fun failure(code: String, message: String = "失败") = MockResponse().setBody(
    """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":{"code":"$code","message":"$message","details":{}}}}"""
  )

  private fun sessionRow(id: String = "s1", directory: String = "/repo", title: String = "修复登录", running: Boolean = false) = JSONObject()
    .put("sessionId", id)
    .put("updatedAt", 100)
    .put("running", running)
    .put("blank", false)
    .put("agentAvailable", true)
    .put("formatStatus", "current")
    .put("cwd", directory)
    .put("projections", JSONObject().put("kind", "sequenced").put("asOfSeq", 3)
      .put("values", JSONObject().put("title", title).put("agentPreset", "standard")))

  @Test fun listingExchangesTokenForCookieThenSendsArgsEnvelope() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"items":[${sessionRow()}]}"""))
      val page = api(server).rootSessionsPage()
      val exchange = server.takeRequest()
      assertEquals("/", exchange.requestUrl!!.encodedPath)
      assertEquals("tok", exchange.requestUrl!!.queryParameter("token"))
      val list = server.takeRequest()
      assertEquals("POST", list.method)
      assertEquals("/api/session/list", list.requestUrl!!.encodedPath)
      assertEquals("dsh-auth-abc=cookie-value", list.getHeader("Cookie"))
      val body = JSONObject(list.body.readUtf8())
      assertEquals("client-request", body.getString("type"))
      assertEquals("session/list", body.getString("method"))
      assertNotNull(body.optString("rpcId").takeIf { it.isNotBlank() })
      // 0.2.x：payload 只包含一个 args 对象，字段是方法形参名。
      val args = body.getJSONObject("payload").getJSONObject("args")
      assertEquals(1, args.length())
      assertNotNull(args.optJSONObject("_request"))
      val session = page.items.single()
      assertEquals("s1", session.id)
      assertEquals("修复登录", session.title)
      assertEquals("/repo", session.directory)
      assertEquals(null, page.next)
    }
  }

  @Test fun fallsBackToConnectApiPeerWhenRootExchangeYieldsNoCookie() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
      server.enqueue(connectOk())
      server.enqueue(envelope("""{"items":[]}"""))
      api(server).rootSessionsPage()
      val exchange = server.takeRequest()
      assertEquals("/", exchange.requestUrl!!.encodedPath)
      val connect = server.takeRequest()
      assertEquals("/connect", connect.requestUrl!!.encodedPath)
      assertEquals("tok", connect.requestUrl!!.queryParameter("token"))
      assertEquals("answer", connect.requestUrl!!.queryParameter("interactions"))
      val list = server.takeRequest()
      assertEquals("dsh-auth-abc=cookie-value", list.getHeader("Cookie"))
    }
  }

  @Test fun eventsStreamPublishesReadyAckAndSessionCreated() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      val opened = CountDownLatch(1)
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              assertEquals("\$events", frame.getString("endpoint"))
              assertEquals(0, frame.getJSONObject("payload").getJSONObject("args").length())
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
              webSocket.send(
                """{"type":"item","streamId":"$id","value":{"type":"emit","event":"api-session/added","args":[${sessionRow(id = "s9")}]}}"""
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
      server.shutdown()
    }
  }

  @Test fun approvalWaterfallBecomesPermissionAndReplySettlesIt() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
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
      val args = JSONObject(settle.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("c1", args.getString("clientId"))
      assertEquals("wf-1", args.getString("eventId"))
      assertEquals("allowed-once", args.getJSONObject("outcome").getString("value"))
    } finally {
      server.shutdown()
    }
  }

  @Test fun cancelledWaterfallRemovesPendingPermission() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
              webSocket.send(
                """{"type":"item","streamId":"$id","value":{"type":"waterfall","event":"approval/request","eventId":"wf-2","agentId":"s1","request":{"toolName":"bash"}}}"""
              )
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"cancel","eventId":"wf-2"}}""")
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val events = withTimeout(20_000) { api(server).events {}.take(2).toList() }
      assertEquals("permission.asked", events[0].type)
      assertEquals("permission.replied", events[1].type)
      assertEquals("wf-2", events[1].properties.getString("id"))
    } finally {
      server.shutdown()
    }
  }

  @Test fun questionWaterfallBecomesFormWithAnswerableIds() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
              webSocket.send(
                """{"type":"item","streamId":"$id","value":{"type":"waterfall","event":"user-questions/request","eventId":"wf-q1","agentId":"s1","request":{"questions":[{"id":"q1","question":"选哪个方案？","options":[{"label":"A"},{"label":"B"}],"multiSelect":false}]}}}"""
              )
            }
          })
          request.path == "/api/\$events/result" -> envelope("null")
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      val event = withTimeout(20_000) { client.events {}.take(1).toList() }.single()
      assertEquals("form.created", event.type)
      // 控制器从 properties.form 读表单。
      val form = event.properties.getJSONObject("form")
      assertEquals("wf-q1", form.getString("id"))
      assertEquals("s1", form.getString("sessionID"))
      val field = form.getJSONArray("fields").getJSONObject(0)
      assertEquals("q1", field.getString("key"))
      assertEquals("选哪个方案？", field.getString("title"))
      assertEquals(2, field.getJSONArray("options").length())
      val request = event.properties.obj("form").toForm("/repo")
      client.replyQuestion(request, listOf(listOf("A")))
      val settle = generateSequence { server.takeRequest(2, TimeUnit.SECONDS) }.first { it.requestUrl!!.encodedPath == "/api/\$events/result" }
      val value = JSONObject(settle.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
        .getJSONObject("outcome").getJSONObject("value")
      val answer = value.getJSONArray("answers").getJSONObject(0)
      assertEquals("q1", answer.getString("id"))
      assertEquals("A", answer.getJSONArray("selected").getString(0))
    } finally {
      server.shutdown()
    }
  }

  @Test fun snapshotFramesBecomeTimelineMessages() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow()}]}""")
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
      server.shutdown()
    }
  }

  @Test fun promptCarriesRequestIdAndMode() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"accepted":true}"""))
      val client = api(server)
      client.send(Session("s1", "/repo", "T", 1), "继续", null, null, id = "req-7", delivery = Delivery.QUEUE)
      server.takeRequest()
      val call = server.takeRequest()
      assertEquals("/api/session/prompt", call.requestUrl!!.encodedPath)
      val request = JSONObject(call.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getJSONObject("request")
      assertEquals("req-7", request.getString("requestId"))
      assertEquals("s1", request.getString("sessionId"))
      assertEquals("queue", request.getString("mode"))
      assertEquals("继续", request.getJSONArray("content").getJSONObject(0).getString("text"))
    }
  }

  @Test fun projectsComeFromWorkspaceBaseline() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              if (frame.getString("endpoint") != "workspace/follow") return
              val id = frame.getString("streamId")
              val value = JSONObject().put("type", "baseline").put("value", JSONObject()
                .put("items", JSONArray().put(JSONObject()
                  .put("workspaceId", "w1").put("path", "/repo").put("title", "Lagoon")))
                .put("archivedSessionIds", JSONArray())
                .put("pinnedSessionIds", JSONArray()))
              webSocket.send(JSONObject().put("type", "item").put("streamId", id).put("value", value).toString())
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val projects = api(server).projects()
      val project = projects.single()
      assertEquals("w1", project.id)
      assertEquals("/repo", project.directory)
      assertEquals("Lagoon", project.name)
    } finally {
      server.shutdown()
    }
  }

  @Test fun inboxComesFromSessionProjections() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope(
        """{"asOfSeq":4,"values":{"inbox":{"next-turn":[{"id":"m-1","role":"user","source":{"kind":"user"},"content":[{"type":"text","text":"排队的问题"}]}],"next-step":[{"id":"m-2","role":"user","source":{"kind":"user"},"content":[{"type":"text","text":"下一步的问题"}]}]}}}"""
      ))
      val messages = api(server).inbox("s1")
      server.takeRequest()
      val call = server.takeRequest()
      assertEquals("/api/session/projections", call.requestUrl!!.encodedPath)
      val args = JSONObject(call.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("s1", args.getJSONObject("request").getString("sessionId"))
      assertEquals(2, messages.size)
      assertEquals("m-1", messages[0].id)
      assertTrue(messages[0].queued)
      assertFalse(messages[1].queued)
      assertEquals("排队的问题", messages[0].parts.single().text)
    }
  }

  @Test fun filesAndContentUseWorkspaceFilesWithSessionScope() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"path":"","entries":[{"name":"a.kt","type":"file"},{"name":"sub","type":"directory"}],"truncated":false}"""))
      server.enqueue(envelope("""{"text":"hello","lines":1,"eof":true,"absolutePath":"/repo/a.kt","version":1}"""))
      val client = api(server)
      val session = Session("s1", "/repo", "T", 1)
      val files = client.files("/repo", ".", session)
      assertEquals(listOf("a.kt" to "file", "sub" to "directory"), files.map { it.path to it.type })
      server.takeRequest()
      val listCall = server.takeRequest()
      val listArgs = JSONObject(listCall.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("s1", listArgs.getString("workspaceFileScopeId"))
      assertEquals(".", listArgs.getString("path"))
      val content = client.fileContent("/repo", "a.kt", session)
      assertEquals("text", content.type)
      assertEquals("hello", content.content)
      val readCall = server.takeRequest()
      val readArgs = JSONObject(readCall.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("a.kt", readArgs.getString("path"))
      assertNotNull(readArgs.optJSONObject("range"))
    }
  }

  @Test fun binaryFileReadFallsBackToBinaryPreview() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(failure("workspace-file/not-text", "file is not UTF-8 text"))
      val content = api(server).fileContent("/repo", "a.png", Session("s1", "/repo", "T", 1))
      assertEquals("binary", content.type)
      assertEquals("", content.content)
    }
  }

  @Test fun diffMergesRecentChangeSummariesAndRendersPatch() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              if (frame.getString("endpoint") != "session/follow") return
              val id = frame.getString("streamId")
              val records = JSONArray().apply {
                put(JSONObject("""{"type":"event","event":{"type":"workspace/changes","seq":5,"time":1,"data":{"turn":1}}}"""))
                put(JSONObject("""{"type":"event","event":{"type":"workspace/changes","seq":9,"time":2,"data":{"turn":2}}}"""))
              }
              val value = JSONObject().put("type", "snapshot")
                .put("header", JSONObject().put("version", 4).put("id", "s1").put("createdAt", 1))
                .put("cursor", 9).put("hasMore", false).put("records", records)
              webSocket.send(JSONObject().put("type", "item").put("streamId", id).put("value", value).toString())
            }
          })
          request.path!!.startsWith("/api/changes.summary") -> {
            val seq = request.requestUrl!!.queryParameter("seq")
            if (seq == "5") MockResponse().setBody("""{"turn":1,"files":[{"path":"a.kt","display":"a.kt","added":1,"deleted":0}],"total":1,"added":1,"deleted":0}""")
            else MockResponse().setBody("""{"turn":2,"files":[{"path":"a.kt","display":"a.kt","added":2,"deleted":1},{"path":"b.kt","display":"b.kt","added":3,"deleted":0}],"total":2,"added":5,"deleted":1}""")
          }
          request.path!!.startsWith("/api/changes.diff") -> {
            val index = request.requestUrl!!.queryParameter("index")
            if (index == "0") MockResponse().setBody("""{"kind":"text","path":"a.kt","display":"a.kt","before":true,"after":true,"hunks":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,"lines":["-old","+new","+more"]}],"coarse":false}""")
            else MockResponse().setBody("""{"kind":"binary","path":"b.kt","display":"b.kt"}""")
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
      val changes = api(server).diff(Session("s1", "/repo", "T", 1))
      assertEquals(2, changes.size)
      val a = changes.single { it.path == "a.kt" }
      assertEquals(2, a.additions)
      assertEquals(1, a.deletions)
      assertTrue(a.patch.contains("@@ -1,1 +1,2 @@"))
      assertTrue(a.patch.contains("+new"))
      val b = changes.single { it.path == "b.kt" }
      assertEquals("二进制文件，无法预览对比", b.after)
      assertEquals("", b.patch)
    } finally {
      server.shutdown()
    }
  }

  @Test fun commandsListAndExecuteUseAgentScope() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow()}]}""")
          request.path == "/api/commands/list" -> envelope("""[{"name":"compact","description":"整理上下文"}]""")
          request.path == "/api/commands/execute" -> envelope("""{"commandId":"cmd-1","result":{"kind":"success","text":"已整理"}}""")
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      client.rootSessionsPage("/repo")
      val commands = client.commands("/repo")
      assertEquals(listOf(CommandChoice("compact", "整理上下文")), commands)
      val listCall = generateSequence { server.takeRequest(2, TimeUnit.SECONDS) }.first { it.requestUrl!!.encodedPath == "/api/commands/list" }
      assertEquals("s1", JSONObject(listCall.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getString("agentId"))
      client.command(Session("s1", "/repo", "T", 1), "compact", "", null, null)
      val execCall = generateSequence { server.takeRequest(2, TimeUnit.SECONDS) }.first { it.requestUrl!!.encodedPath == "/api/commands/execute" }
      val args = JSONObject(execCall.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("s1", args.getString("agentId"))
      assertEquals("/compact", args.getString("line"))
      assertEquals(0, args.getJSONArray("submittedAttachments").length())
    } finally {
      server.shutdown()
    }
  }

  @Test fun compactUsesSlashCommand() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"commandId":"cmd-2","result":{"kind":"success"}}"""))
      api(server).compact(Session("s1", "/repo", "T", 1))
      server.takeRequest()
      val call = server.takeRequest()
      assertEquals("/api/commands/execute", call.requestUrl!!.encodedPath)
      assertEquals("/compact", JSONObject(call.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getString("line"))
    }
  }

  @Test fun shellsComeFromRunningJobs() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow()}]}""")
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              if (frame.getString("endpoint") != "job/list") return
              val id = frame.getString("streamId")
              val value = JSONObject().put("type", "rows").put("jobs", JSONArray().apply {
                put(JSONObject().put("id", "shell-1").put("kind", "shell").put("label", "npm test").put("owner", "s1").put("status", "running").put("startedAt", 5))
                put(JSONObject().put("id", "shell-2").put("kind", "shell").put("label", "done").put("owner", "s1").put("status", "completed").put("startedAt", 1))
              })
              webSocket.send(JSONObject().put("type", "item").put("streamId", id).put("value", value).toString())
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      client.rootSessionsPage("/repo")
      val jobs = client.shells("/repo")
      val job = jobs.single()
      assertEquals("shell-1", job.id)
      assertEquals("s1", job.sessionId)
      assertEquals("npm test", job.command)
      assertEquals(5, job.started)
    } finally {
      server.shutdown()
    }
  }

  @Test fun liveAssistantStreamBecomesTextDeltas() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow(running = true)}]}""")
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              val id = frame.getString("streamId")
              when (frame.getString("endpoint")) {
                "\$events" -> webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
                "session/follow" -> {
                  // Web 适配器显式选择 assistantStream 才会收到实时帧。
                  assertTrue(frame.getJSONObject("payload").getJSONObject("args").getJSONObject("request").optBoolean("assistantStream"))
                  val snapshot = JSONObject().put("type", "snapshot")
                    .put("header", JSONObject().put("version", 4).put("id", "s1").put("createdAt", 1).put("cwd", "/repo"))
                    .put("cursor", 3).put("hasMore", false).put("records", JSONArray())
                  webSocket.send(JSONObject().put("type", "item").put("streamId", id).put("value", snapshot).toString())
                  webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"assistant-stream","frame":{"type":"start","attemptId":"a1","revision":1,"startedAfterSeq":3,"turn":1,"step":1}}}""")
                  webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a1","revision":1,"index":0,"time":10,"chunk":{"type":"text-delta","index":0,"text":"你"}}}}""")
                  webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a1","revision":1,"index":1,"time":11,"chunk":{"type":"text-delta","index":0,"text":"好"}}}}""")
                }
              }
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      client.rootSessionsPage("/repo")
      val events = withTimeout(20_000) { client.events {}.take(3).toList() }
      assertEquals("session.step.started", events[0].type)
      assertEquals("session.text.started", events[1].type)
      assertEquals("session.text.delta", events[2].type)
      assertEquals("你", events[2].properties.getString("delta"))
      assertEquals("msg_1:1", events[2].properties.getString("assistantMessageID"))
    } finally {
      server.shutdown()
    }
  }

  @Test fun sessionStatusEventsMapToBusyAndIdle() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
              // 参数是纯值（sessionId, running），不是行对象。
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"emit","event":"api-session/status","args":["s1",true]}}""")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"emit","event":"api-session/status","args":["s1",false]}}""")
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val events = withTimeout(20_000) { api(server).events {}.take(2).toList() }
      assertEquals("session.status", events[0].type)
      assertEquals("busy", events[0].properties.getJSONObject("status").getString("type"))
      assertEquals("idle", events[1].properties.getJSONObject("status").getString("type"))
      assertEquals("s1", events[1].properties.getString("sessionID"))
    } finally {
      server.shutdown()
    }
  }

  @Test fun liveTurnEndReasonsMapToExecutionEvents() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow()}]}""")
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              val id = frame.getString("streamId")
              when (frame.getString("endpoint")) {
                "\$events" -> webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
                "session/follow" -> {
                  val snapshot = JSONObject().put("type", "snapshot")
                    .put("header", JSONObject().put("version", 4).put("id", "s1").put("createdAt", 1))
                    .put("cursor", 1).put("hasMore", false).put("records", JSONArray())
                  webSocket.send(JSONObject().put("type", "item").put("streamId", id).put("value", snapshot).toString())
                  webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"event","event":{"type":"turn/end","seq":4,"time":20,"data":{"turn":1,"reason":{"kind":"aborted","reason":{"kind":"user"}}}}}}""")
                }
              }
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      client.rootSessionsPage("/repo")
      val events = withTimeout(20_000) { client.events {}.take(1).toList() }
      assertEquals("session.execution.interrupted", events[0].type)
      assertEquals("user", events[0].properties.getString("reason"))
    } finally {
      server.shutdown()
    }
  }

  @Test fun dshCapabilitiesDescribeSupportedSurface() {
    val server = MockWebServer()
    val capabilities = api(server).capabilities()
    assertTrue(capabilities.diff)
    assertTrue(capabilities.supports(SessionAction.RENAME))
    assertEquals(null, capabilities.sessionView)
    assertNotNull(capabilities.archive)
    assertEquals(true, capabilities.archive?.restorable)
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
