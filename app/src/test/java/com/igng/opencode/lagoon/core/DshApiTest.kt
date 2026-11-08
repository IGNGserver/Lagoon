package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

  private fun sessionRow(id: String = "s1", directory: String = "/repo", title: String = "修复登录", running: Boolean = false,
                         blank: Boolean = false) = JSONObject()
    .put("sessionId", id)
    .put("updatedAt", 100)
    .put("running", running)
    .put("blank", blank)
    .put("agentAvailable", true)
    .put("formatStatus", "current")
    .put("cwd", directory)
    .put("projections", JSONObject().put("kind", "sequenced").put("asOfSeq", 3)
      .put("values", JSONObject().put("title", title).put("agentPreset", "standard")))

  /** One durable `session/follow` record. */
  private fun event(type: String, seq: Int, time: Long, data: JSONObject) = JSONObject()
    .put("type", "event").put("event", JSONObject().put("type", type).put("seq", seq).put("time", time).put("data", data))

  /** A turn-aligned Host snapshot, like `session/follow` produces. */
  private fun snapshotFrame(records: JSONArray, cursor: Long, hasMore: Boolean) = JSONObject()
    .put("type", "snapshot")
    .put("header", JSONObject().put("version", 4).put("id", "s1").put("createdAt", 1).put("cwd", "/repo"))
    .put("cursor", cursor).put("hasMore", hasMore).put("records", records)

  /** [turns] turns with [steps] assistant steps each; [goalTurns] are injected goal rounds without a user row. */
  private fun conversationRecords(turns: Int, steps: Int, goalTurns: Set<Int> = emptySet()): JSONArray {
    val records = JSONArray()
    var seq = 0
    var time = 1_000L
    for (turn in 1..turns) {
      records.put(event("turn/start", ++seq, ++time, JSONObject().put("turn", turn)))
      val goal = turn in goalTurns
      val prompt = if (goal) "<goal_round>\nObjective: \"目标 $turn\"\nRound: 1/30" else "问题 $turn"
      records.put(event("user/message", ++seq, ++time, JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
        .put("source", JSONObject().put("kind", if (goal) "goal" else "user"))
        .put("role", "user").put("id", "u$turn")))
      for (step in 1..steps) {
        records.put(event("step/start", ++seq, ++time, JSONObject().put("turn", turn).put("step", step)))
        records.put(event("assistant/message", ++seq, ++time, JSONObject().put("turn", turn).put("step", step)
          .put("message", JSONObject().put("role", "assistant").put("content", JSONArray()
            .put(JSONObject().put("type", "reasoning").put("text", "思考 $turn/$step"))
            .put(JSONObject().put("type", "text").put("text", "回复 $turn/$step"))))))
        records.put(event("step/end", ++seq, ++time, JSONObject().put("turn", turn).put("step", step)))
      }
      records.put(event("turn/end", ++seq, ++time, JSONObject().put("turn", turn)
        .put("reason", JSONObject().put("kind", "completed"))))
    }
    return records
  }

  /** A MockWebServer that serves the token exchange, the session row and one `session/follow` snapshot. */
  private fun snapshotServer(snapshot: JSONObject,
    onPage: (RecordedRequest) -> MockResponse = { envelope("""{"records":[],"hasMore":false}""") }): MockWebServer {
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
        request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow()}]}""")
        request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JSONObject(text)
            if (frame.optString("type") != "open" || frame.optString("endpoint") != "session/follow") return
            webSocket.send(JSONObject().put("type", "item")
              .put("streamId", frame.getString("streamId")).put("value", snapshot).toString())
          }
        })
        request.path == "/api/session/page" -> onPage(request)
        else -> MockResponse().setResponseCode(404)
      }
    }
    server.start()
    return server
  }

  @Test fun customProviderRouteUsesSettingsMutateAndCredential() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"writable":true,"hasDocument":false,"namespaces":[{"ns":"llm-pi-ai","autoGenerate":true,"schema":{},"value":{},"revision":3,"applies":"live","secrets":[]}]}"""))
      server.enqueue(envelope("""{"ns":"llm-pi-ai"}"""))
      server.enqueue(envelope("""{}"""))
      assertTrue(api(server).createProviderRoute("acme", "Acme", "openai-completions",
        "https://gw.example/v1", "ACME_API_KEY", "acme-1", "sk-test"))
      server.takeRequest()
      assertEquals("/api/settings/describe", server.takeRequest().path)
      val mutate = server.takeRequest()
      assertEquals("/api/settings/mutate", mutate.path)
      val body = JSONObject(mutate.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("llm-pi-ai", body.getString("ns"))
      assertEquals(3, body.getInt("expectedRevision"))
      val op = body.getJSONArray("ops").getJSONObject(0)
      assertEquals("providers", op.getJSONArray("path").getString(0))
      assertEquals("acme", op.getJSONArray("path").getString(1))
      val credSet = server.takeRequest()
      assertEquals("/api/credentials/set", credSet.path)
      assertEquals("sk-test", JSONObject(credSet.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getString("value"))
    }
  }

  @Test fun scheduleListIsReadOnlyAndSessionScoped() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""[{"id":"r1","kind":"daily","title":"晨会","prompt":"提醒我","scheduledAt":"2026-11-06T01:00:00Z","time":"09:00","timeZone":"Asia/Shanghai"}]"""))
      val list = api(server).schedules("s1")
      assertEquals(1, list.size)
      assertEquals("晨会", list.single().title)
      assertEquals("09:00 Asia/Shanghai", list.single().detail)
      server.takeRequest()
      val request = server.takeRequest()
      assertEquals("/api/schedule/list", request.path)
      assertEquals("s1", JSONObject(request.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getJSONObject("request").getString("sessionId"))
    }
  }

  @Test fun subagentModeComesFromSessionProjection() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"items":[{"sessionId":"c1","updatedAt":1,"running":false,"cwd":"/repo","parentSessionId":"p1","origin":"subagent",
        "projections":{"values":{"title":"子任务","subagent":{"mode":"continuable"}}}}]}"""))
      val session = api(server).children(Session("p1", "/repo", "P", 1)).single()
      assertEquals("continuable", session.subagentMode)
    }
  }

  @Test fun filePreviewUsesBase64ReadBytesAndOfficeRender() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"absolutePath":"/repo/a.png","bytes":5,"offset":0,"data":"aGVsbG8=","eof":true}"""))
      server.enqueue(envelope("""{"data":"JVBERi0="}"""))
      val client = api(server)
      val session = Session("s1", "/repo", "T", 1)
      val image = client.previewFile("/repo", "a.png", session)!!
      assertEquals("image/png", image.mime)
      assertArrayEquals("hello".toByteArray(), image.bytes)
      val pdf = client.previewFile("/repo", "a.docx", session)!!
      assertEquals("application/pdf", pdf.mime)
      assertArrayEquals("%PDF-".toByteArray(), pdf.bytes)
      server.takeRequest() // root
      assertEquals("/api/workspaceFiles/readBytes", server.takeRequest().path)
      assertEquals("/api/officeToPdf/render", server.takeRequest().path)
    }
  }

  @Test fun goalReadAndMutationUseSessionScopedRpc() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"id":"g1","revision":2,"objective":"完成 P4","phase":"active","maxGoalRounds":30,"roundsStarted":1,"activation":"armed","createdAt":1,"updatedAt":2}"""))
      server.enqueue(envelope("""{"ref":{"id":"g1","revision":2}}"""))
      server.enqueue(envelope("""{}"""))
      val client = api(server)
      val goal = client.goal("s1")!!
      assertEquals("g1", goal.id)
      assertEquals(2, goal.revision)
      assertEquals("完成 P4", goal.objective)
      assertEquals(1, goal.roundsStarted)
      assertTrue(client.createGoal("s1", "新目标", 5))
      assertTrue(client.goalAction("s1", "g1", 2, "pause"))
      server.takeRequest() // root
      val getReq = server.takeRequest()
      assertEquals("/api/goals/get", getReq.path)
      assertEquals("s1", JSONObject(getReq.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getString("agentId"))
      val createReq = server.takeRequest()
      assertEquals("/api/goals/create", createReq.path)
      val createArgs = JSONObject(createReq.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("s1", createArgs.getString("agentId"))
      assertEquals("新目标", createArgs.getJSONObject("request").getString("objective"))
      val pause = server.takeRequest()
      assertEquals("/api/goals/pause", pause.path)
      val body = JSONObject(pause.body.readUtf8())
      assertEquals("s1", body.getJSONObject("payload").getJSONObject("args").getString("agentId"))
    }
  }

  @Test fun credentialStatusAndWritesUseSettingsChannel() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"DEEPSEEK_API_KEY":{"configured":true,"writable":true,"source":"file"}}"""))
      server.enqueue(envelope("{}"))
      server.enqueue(envelope("{}"))
      val client = api(server)
      val status = client.credentialStatus(listOf("DEEPSEEK_API_KEY")).getValue("DEEPSEEK_API_KEY")
      assertTrue(status.configured)
      assertTrue(status.writable)
      assertEquals("file", status.source)
      assertTrue(client.setCredential("DEEPSEEK_API_KEY", "sk-test"))
      assertTrue(client.clearCredential("DEEPSEEK_API_KEY"))
      server.takeRequest() // root token exchange
      val describe = server.takeRequest()
      assertEquals("/api/credentials/describe", describe.path)
      assertEquals("DEEPSEEK_API_KEY", JSONObject(describe.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getJSONArray("refs").getString(0))
      val set = server.takeRequest(); assertEquals("/api/credentials/set", set.path)
      assertEquals("sk-test", JSONObject(set.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getString("value"))
      val unset = server.takeRequest(); assertEquals("/api/credentials/unset", unset.path)
      assertEquals("DEEPSEEK_API_KEY", JSONObject(unset.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getString("ref"))
    }
  }

  @Test fun pluginInventoryIsParsedFromRemoteSettingsChannel() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""[{"name":"dsh-opencode-go","enabled":true,"installed":true,"official":false,"version":"1.0.0","description":"Go"} ]"""))
      server.enqueue(envelope("""{}"""))
      server.enqueue(envelope("""{}"""))
      val client = api(server)
      val bundles = client.pluginBundles()
      assertEquals(1, bundles.size)
      assertEquals("dsh-opencode-go", bundles.single().name)
      assertTrue(bundles.single().enabled)
      assertEquals("1.0.0", bundles.single().version)
      assertTrue(client.setPluginBundle("dsh-opencode-go", false))
      assertTrue(client.setPluginEntry("entry-1", true))
      server.takeRequest() // root
      server.takeRequest() // listBundles
      val bundleToggle = server.takeRequest()
      assertEquals("/api/pluginManager/setBundleEnabled", bundleToggle.path)
      val bundleArgs = JSONObject(bundleToggle.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("dsh-opencode-go", bundleArgs.getString("name"))
      assertFalse(bundleArgs.getBoolean("enabled"))
      val entryToggle = server.takeRequest()
      assertEquals("/api/pluginManager/setPluginEnabled", entryToggle.path)
      val entryArgs = JSONObject(entryToggle.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("entry-1", entryArgs.getString("id"))
      assertTrue(entryArgs.getBoolean("enabled"))
    }
  }

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

  @Test fun blankSessionsAreParsedAndStartedSessionsStayVisible() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      val metadataOnly = sessionRow(id = "metadata").apply {
        remove("blank")
        getJSONObject("projections").getJSONObject("values")
          .put("sessionListMetadata", JSONObject().put("blank", true))
      }
      // top-level blank 可能是旧缓存；lastPromptAt 证明它已经收到过 prompt。
      val prompted = sessionRow(id = "prompted", blank = true).apply {
        getJSONObject("projections").getJSONObject("values")
          .put("sessionListMetadata", JSONObject().put("blank", true).put("lastPromptAt", 123))
      }
      server.enqueue(envelope("""{"items":[
        ${sessionRow(id = "draft", blank = true)},
        ${sessionRow(id = "started", blank = false)},
        $metadataOnly,
        $prompted
      ]}"""))
      server.enqueue(envelope("""{"items":[
        ${sessionRow(id = "draft", blank = true)},
        ${sessionRow(id = "started", blank = true)},
        $metadataOnly,
        $prompted
      ]}"""))
      val client = api(server)
      val first = client.rootSessionsPage().items.associateBy { it.id }
      // 空白行不进入首页目录 ...
      assertFalse(first.containsKey("draft"))
      assertFalse(first.containsKey("metadata"))
      assertFalse(first.getValue("started").blank)
      assertFalse(first.getValue("prompted").blank)
      // ... 但仍留在 known 里，供复用与单调判断使用。
      assertTrue(client.session("draft").blank)
      assertTrue(client.session("metadata").blank)
      assertFalse(client.session("prompted").blank)
      // 旧列表/旧事件把已开始的会话重新标成 blank 时，不能把首页行藏掉。
      val second = client.rootSessionsPage().items.associateBy { it.id }
      assertFalse(second.containsKey("draft"))
      assertFalse(second.getValue("started").blank)
      assertTrue(client.session("draft").blank)
    }
  }

  @Test fun createSessionReusesBlankThenFallsBackWhenReuseIsDeclined() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"items":[${sessionRow(id = "draft", blank = true)}]}"""))
      server.enqueue(failure("session/conflict"))
      server.enqueue(envelope("""{"sessionId":"fresh"}"""))
      val client = api(server)
      client.rootSessionsPage()
      val created = client.createSession("/repo", "")
      assertEquals("fresh", created.id)
      assertTrue(created.blank)
      server.takeRequest() // token exchange
      server.takeRequest() // session/list
      val reuse = JSONObject(server.takeRequest().body.readUtf8())
        .getJSONObject("payload").getJSONObject("args").getJSONObject("request")
      assertEquals("draft", reuse.getString("sessionId"))
      val retry = JSONObject(server.takeRequest().body.readUtf8())
        .getJSONObject("payload").getJSONObject("args").getJSONObject("request")
      assertFalse(retry.has("sessionId"))
    }
  }

  @Test fun acceptedPromptClearsBlankOnTheCachedSession() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"sessionId":"s5"}"""))
      server.enqueue(envelope("""{"accepted":true}"""))
      val client = api(server)
      val created = client.createSession("/repo", "")
      assertTrue(created.blank)
      client.send(created, "开始", null, null, id = "req-1", delivery = Delivery.QUEUE)
      assertFalse(client.session("s5").blank)
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
      // DSH 选项只有 label；表单必须把 label 同时写成 value，否则整组选项共用一个空身份。
      assertEquals("A", field.getJSONArray("options").getJSONObject(0).getString("value"))
      assertEquals("B", field.getJSONArray("options").getJSONObject(1).getString("value"))
      val request = event.properties.obj("form").toForm("/repo")
      assertEquals(listOf("A", "B"), request.questions.single().options.map { it.value })
      // 单选（select）必须能通过表单校验，否则卡片里的「提交回答」永远是灰的。
      assertTrue(runCatching { formAnswer(request, listOf(listOf("A"))) }.isSuccess)
      client.replyQuestion(request, listOf(listOf("A")))
      val settle = generateSequence { server.takeRequest(2, TimeUnit.SECONDS) }.first { it.requestUrl!!.encodedPath == "/api/\$events/result" }
      val value = JSONObject(settle.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
        .getJSONObject("outcome").getJSONObject("value")
      val answer = value.getJSONArray("answers").getJSONObject(0)
      assertEquals("q1", answer.getString("id"))
      assertEquals("A", answer.getJSONArray("selected").getString(0))
      assertFalse(answer.has("custom"))
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
                // Injected model context is not something the user said.
                put(JSONObject("""{"type":"event","event":{"type":"user/message","seq":2,"time":1001,"data":{"content":[{"type":"text","text":"Current runtime context."}],"source":{"kind":"runtime-context","form":"snapshot"},"role":"user","id":"u2"}}}"""))
                put(JSONObject("""{"type":"event","event":{"type":"user/message","seq":2,"time":1002,"data":{"content":[{"type":"text","text":"Time sampled"}],"source":{"kind":"time-context"},"role":"user","id":"u3"}}}"""))
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

  @Test fun openingWindowKeepsWholeTurnWindowAndHasMoreCursor() = runBlocking {
    val snapshot = snapshotFrame(conversationRecords(turns = 3, steps = 25), cursor = 1000, hasMore = true)
    snapshotServer(snapshot).use { server ->
      val page = api(server).messagesPage("s1", null, HarnessClient.MESSAGE_PAGE)
      // 3 users + 3*25 assistant steps: the old takeLast(20) returned only 20 and next=null.
      assertEquals(78, page.items.size)
      assertEquals("1", page.next)
    }
  }

  @Test fun goalTurnKeepsItsOwnBoundaryAndNotice() = runBlocking {
    val snapshot = snapshotFrame(conversationRecords(turns = 2, steps = 25, goalTurns = setOf(1)), cursor = 1000, hasMore = false)
    snapshotServer(snapshot).use { server ->
      val messages = api(server).messagesPage("s1", null, HarnessClient.MESSAGE_PAGE).items
      val rows = TranscriptRows.build(messages)
      assertEquals(2, rows.count { it.kind == "tool-group" })
      assertEquals(1, rows.count { it.kind == "user" })
      assertEquals("目标轮次", rows.single { it.kind == "note" }.title)
    }
  }

  @Test fun olderPageAsksForTurnAlignedWindow() = runBlocking {
    val snapshot = snapshotFrame(conversationRecords(turns = 1, steps = 2), cursor = 1000, hasMore = true)
    var captured: JSONObject? = null
    snapshotServer(snapshot) { request ->
      captured = JSONObject(request.body.readUtf8()).getJSONObject("payload")
        .getJSONObject("args").getJSONObject("request")
      envelope("""{"records":[],"hasMore":false}""")
    }.use { server ->
      val client = api(server)
      val first = client.messagesPage("s1", null, HarnessClient.MESSAGE_PAGE)
      assertNotNull(first.next)
      val older = client.messagesPage("s1", first.next, HarnessClient.HISTORY_PAGE)
      assertTrue(older.items.isEmpty())
      val request = requireNotNull(captured)
      assertEquals(HarnessClient.HISTORY_PAGE, request.getInt("maxMessages"))
      assertEquals(50, request.getJSONObject("turnWindow").getInt("minMessages"))
      assertEquals(2, request.getJSONObject("turnWindow").getInt("minTurns"))
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
      val workspaceStreams = java.util.concurrent.atomic.AtomicInteger()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              if (frame.getString("endpoint") != "workspace/follow") return
              workspaceStreams.incrementAndGet()
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
      val client = api(server)
      val projects = client.projects()
      val project = projects.single()
      assertEquals("w1", project.id)
      assertEquals("/repo", project.directory)
      assertEquals("Lagoon", project.name)
      // 行内标签只读已经维护好的清单，不再为标签单独开一条 workspace/follow。
      assertEquals(projects, client.projectsForDisplay())
      assertEquals(1, workspaceStreams.get())
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
                  webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a1","revision":1,"index":1,"time":10,"chunk":{"type":"text-delta","index":0,"text":"好"}}}}""")
                }
              }
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      client.rootSessionsPage("/repo")
      val events = withTimeout(20_000) { client.events {}.take(4).toList() }
      assertEquals("session.step.started", events[0].type)
      assertEquals("session.text.started", events[1].type)
      assertEquals(listOf("session.text.delta", "session.text.delta"), events.drop(2).map { it.type })
      assertEquals(listOf("你", "好"), events.drop(2).map { it.properties.getString("delta") })
      assertEquals("msg_1:1", events[2].properties.getString("assistantMessageID"))
      // Both chunks intentionally have time=10. Wall time is not an event identity.
      assertEquals(4, events.map { it.id }.distinct().size)
    } finally {
      server.shutdown()
    }
  }

  @Test fun liveUserMessagesAreSourceGatedAndDurableIdsIncludeSession() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow(id = "s1", running = true)},${sessionRow(id = "s2", running = true)}]}""")
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.str("type") != "open") return
              val id = frame.str("streamId")
              when (frame.str("endpoint")) {
                "\$events" -> webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1"}}""")
                "session/follow" -> {
                  val sessionId = frame.obj("payload").obj("args").obj("request").obj("address").str("sessionId")
                  val snapshot = JSONObject().put("type", "snapshot")
                    .put("header", JSONObject().put("version", 4).put("id", sessionId).put("createdAt", 1).put("cwd", "/repo"))
                    .put("cursor", 0).put("hasMore", false).put("records", JSONArray())
                  fun send(value: JSONObject) { webSocket.send(JSONObject().put("type", "item").put("streamId", id).put("value", value).toString()) }
                  send(snapshot)
                  fun user(seq: Int, messageId: String, kind: String?, body: String) {
                    val source = if (kind == null) JSONObject() else JSONObject().put("kind", kind)
                    send(event("user/message", seq, 1_000 + seq.toLong(), JSONObject().put("id", messageId).put("role", "user")
                      .put("source", source).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", body)))))
                  }
                  if (sessionId == "s1") {
                    user(6, "injected", "runtime-context", "Current runtime context")
                    user(7, "u1", "user", "来自另一个客户端")
                  } else {
                    // Same session-scoped seq as s1: this event must not collide in global deduplication.
                    user(7, "u2", "user", "第二个会话")
                  }
                }
              }
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      client.rootSessionsPage("/repo")
      val events = withTimeout(20_000) { client.events {}.take(2).toList() }
      assertEquals(setOf("u1", "u2"), events.map { it.properties.str("messageID") }.toSet())
      assertEquals(setOf("s1", "s2"), events.map { it.properties.str("sessionID") }.toSet())
      assertEquals(2, events.map { it.id }.distinct().size)
      assertTrue(events.all { it.type == "session.user.message" && !it.properties.str("text").contains("runtime") })
      val first = events.single { it.properties.str("messageID") == "u1" }
      val optimistic = Message("u1", "user", 1, listOf(MessagePart("u1:text", "text", text = "本地乐观行")))
      val projected = TranscriptProjection.apply(listOf(optimistic), first)!!.messages.single()
      assertEquals("u1", projected.id)
      assertEquals("来自另一个客户端", projected.parts.single().text)
      assertEquals(1_007L, projected.created)
    } finally {
      server.shutdown()
    }
  }

  /**
   * `$events` 只说“不再运行”。follow 没开时，停下来要补读日志尾部的 `turn/end`，
   * 再补一条带结局的执行事件；读不到结局就不能发，否则会被记成已完成。
   */
  @Test fun idleStatusReadsTheTurnEndItDidNotSee() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow(running = true)}]}""")
          request.path == "/api/session/page" -> envelope("""{"records":[${
            event("turn/end", 9, 20, JSONObject().put("turn", 1).put("reason", JSONObject().put("kind", "completed")))
          }],"hasMore":false}""")
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"emit","event":"api-session/status","args":["s1",false]}}""")
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      client.rootSessionsPage("/repo")
      val events = withTimeout(20_000) { client.events {}.take(2).toList() }
      assertEquals("session.status", events[0].type)
      assertEquals("idle", events[0].properties.getJSONObject("status").getString("type"))
      assertEquals("session.execution.succeeded", events[1].type)
      assertEquals("s1", events[1].properties.getString("sessionID"))
      assertEquals("succeeded", client.session("s1").outcome)
    } finally {
      server.shutdown()
    }
  }

  /** 日志里没有 `turn/end`（比如进程中途退出）时，只报告空闲，不补一条成功。 */
  @Test fun idleStatusWithoutATurnEndStaysIdle() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow(running = true)}]}""")
          request.path == "/api/session/page" -> envelope("""{"records":[],"hasMore":false}""")
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("type") != "open") return
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1","host":{"home":"/home"}}}""")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"emit","event":"api-session/status","args":["s1",false]}}""")
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      client.rootSessionsPage("/repo")
      val events = withTimeout(20_000) { client.events {}.take(1).toList() }
      assertEquals(listOf("session.status"), events.map { it.type })
      assertNull(client.session("s1").outcome)
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
          request.path == "/api/session/list" -> envelope("""{"items":[${sessionRow(running = true)}]}""")
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

  // ---------------------------------------------------------------- 登录持久化（P0）

  private val signingSecret = ByteArray(32) { (it * 7).toByte() }

  /** 一张由 [signingSecret] 签发、绑定 MockWebServer authority 的浏览器 Cookie（v1）。 */
  private fun browserCookie(server: MockWebServer, now: Long = System.currentTimeMillis()) =
    DshCookie.mint(signingSecret, server.url("/"), now)

  private fun rootWith(pair: String) = MockResponse().setResponseCode(303)
    .addHeader("Set-Cookie", "$pair; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict")
    .addHeader("Location", "./")

  @Test fun storedBrowserCookieIsReusedWithoutTokenExchange() = runBlocking {
    MockWebServer().use { server ->
      val stored = browserCookie(server)
      server.enqueue(envelope("""{"items":[]}"""))
      val persisted = mutableListOf<String>()
      DshApi(profile(server), ServerCredentials("dsh", "stale-token", stored), onCookie = { persisted += it }).rootSessionsPage()
      val list = server.takeRequest()
      assertEquals("/api/session/list", list.requestUrl!!.encodedPath)
      assertEquals(stored, list.getHeader("Cookie"))
      assertEquals(1, server.requestCount)
      assertTrue(persisted.isEmpty())
    }
  }

  @Test fun exchangedBrowserCookieIsHandedOutForPersistence() = runBlocking {
    MockWebServer().use { server ->
      val minted = browserCookie(server)
      server.enqueue(rootWith(minted))
      server.enqueue(envelope("""{"items":[]}"""))
      val persisted = mutableListOf<String>()
      DshApi(profile(server), ServerCredentials("dsh", "tok"), onCookie = { persisted += it }).rootSessionsPage()
      assertEquals(listOf(minted), persisted)
    }
  }

  @Test fun apiPeerCookieStaysInMemoryOnly() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
      server.enqueue(connectOk())
      server.enqueue(envelope("""{"items":[]}"""))
      val persisted = mutableListOf<String>()
      DshApi(profile(server), ServerCredentials("dsh", "tok"), onCookie = { persisted += it }).rootSessionsPage()
      assertTrue(persisted.isEmpty())
    }
  }

  @Test fun rejectedStoredCookieIsClearedThenTokenExchangedAndRetried() = runBlocking {
    MockWebServer().use { server ->
      val stale = browserCookie(server)
      val fresh = browserCookie(server, System.currentTimeMillis() + 1_000)
      server.enqueue(MockResponse().setResponseCode(401))
      server.enqueue(rootWith(fresh))
      server.enqueue(envelope("""{"items":[]}"""))
      val persisted = mutableListOf<String>()
      DshApi(profile(server), ServerCredentials("dsh", "tok", stale), onCookie = { persisted += it }).rootSessionsPage()
      assertEquals(stale, server.takeRequest().getHeader("Cookie"))
      assertEquals("tok", server.takeRequest().requestUrl!!.queryParameter("token"))
      assertEquals(fresh, server.takeRequest().getHeader("Cookie"))
      assertEquals(listOf("", fresh), persisted)
    }
  }

  @Test fun rejectedCookieWithRotatedTokenAsksForRepairing() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(401))
      server.enqueue(MockResponse().setResponseCode(401))
      val failure = runCatching {
        DshApi(profile(server), ServerCredentials("dsh", "old-token", browserCookie(server))).rootSessionsPage()
      }.exceptionOrNull()
      assertTrue(failure!!.isAuthFailure)
      assertTrue(failure.message!!.contains("重新配对"))
    }
  }

  @Test fun expiredStoredCookieIsNotSentAtAll() = runBlocking {
    MockWebServer().use { server ->
      val expired = browserCookie(server, System.currentTimeMillis() - 2 * 24 * 60 * 60_000L)
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"items":[]}"""))
      DshApi(profile(server), ServerCredentials("dsh", "tok", expired)).rootSessionsPage()
      assertEquals("/", server.takeRequest().requestUrl!!.encodedPath)
    }
  }

  @Test fun oldBrowserCookieIsRenewedInBackgroundWhileTokenStillWorks() = runBlocking {
    MockWebServer().use { server ->
      val now = System.currentTimeMillis()
      // 签发于 13 小时前：仍然有效，但超过续期阈值。
      val old = DshCookie.mint(signingSecret, server.url("/"), now - 13 * 60 * 60_000L + DshCookie.CLOCK_SKEW_MILLIS)
      val renewed = browserCookie(server)
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token=") -> rootWith(renewed)
          else -> envelope("""{"items":[]}""")
        }
      }
      val persisted = CountDownLatch(1)
      var saved = ""
      val client = DshApi(profile(server), ServerCredentials("dsh", "tok", old), onCookie = { saved = it; persisted.countDown() })
      client.rootSessionsPage()
      assertTrue(persisted.await(10, TimeUnit.SECONDS))
      assertEquals(renewed, saved)
      client.rootSessionsPage()
      val cookies = (0 until server.requestCount).map { server.takeRequest() }.filter { it.path!!.startsWith("/api/") }.map { it.getHeader("Cookie") }
      assertEquals(old, cookies.first())
      assertEquals(renewed, cookies.last())
    }
  }

  @Test fun secretModeSignsItsOwnCookieWithoutExchange() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(envelope("""{"items":[]}"""))
      val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signingSecret)
      val persisted = mutableListOf<String>()
      DshApi(profile(server).copy(dshAuth = DshAuthMode.SECRET), ServerCredentials("dsh", secret), onCookie = { persisted += it }).rootSessionsPage()
      val list = server.takeRequest()
      assertEquals("/api/session/list", list.requestUrl!!.encodedPath)
      val cookie = list.getHeader("Cookie")!!
      assertTrue(cookie.startsWith(DshCookie.name(DshCookie.authority(server.url("/"))) + "=v1."))
      assertEquals(1, server.requestCount)
      assertTrue(persisted.isEmpty())
    }
  }

  @Test fun malformedSecretFailsAsAuthProblem() = runBlocking {
    MockWebServer().use { server ->
      val failure = runCatching {
        DshApi(profile(server).copy(dshAuth = DshAuthMode.SECRET), ServerCredentials("dsh", "not-a-secret")).rootSessionsPage()
      }.exceptionOrNull()
      assertTrue(failure!!.isAuthFailure)
      assertEquals(0, server.requestCount)
    }
  }

  @Test fun untrustedHostIsExplained() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(403))
      val failure = runCatching { api(server).rootSessionsPage() }.exceptionOrNull()
      assertTrue(failure!!.message!!.contains("--trusted-host"))
    }
  }

  @Test fun createSessionSendsOnlyContractFieldsThenRenames() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"sessionId":"s5"}"""))
      server.enqueue(envelope("""{"title":"新任务","seq":2}"""))
      val created = api(server).createSession("/repo", "新任务")
      assertEquals("s5", created.id)
      assertTrue(created.blank)
      server.takeRequest()
      val create = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("payload").getJSONObject("args").getJSONObject("request")
      assertEquals("/repo", create.getString("cwd"))
      assertFalse(create.has("title"))
      val rename = server.takeRequest()
      assertEquals("/api/session/rename", rename.requestUrl!!.encodedPath)
    }
  }

  @Test fun createSessionSendsWorkspaceIdOnlyForAWorkspaceRoot() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      val created = java.util.concurrent.atomic.AtomicInteger()
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
          request.path == "/api/session/create" -> envelope("""{"sessionId":"s${created.incrementAndGet()}"}""")
          request.path == "/api/session/rename" -> envelope("""{"title":"新任务","seq":2}""")
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      // 控制器连上服务器后会先拉一次工作区清单；createSession 只读这份缓存，不再多开一条流。
      client.projects()

      client.createSession("/repo", "新任务")
      val attached = createRequests(server).single()
      assertEquals("w1", attached.getString("workspaceId"))
      assertFalse(attached.has("cwd"))

      // 工作区子目录不能换成 workspaceId：那会把会话 cwd 强制成工作区根。
      client.createSession("/repo/sub", "子目录任务")
      val fallback = createRequests(server).last()
      assertEquals("/repo/sub", fallback.getString("cwd"))
      assertFalse(fallback.has("workspaceId"))
    } finally {
      server.shutdown()
    }
  }

  /** 按到达顺序取出所有 `session/create` 的 `args.request`（`takeRequest` 会把它们从队列里消费掉）。 */
  private fun createRequests(server: MockWebServer): List<JSONObject> {
    val requests = mutableListOf<JSONObject>()
    while (true) {
      val call = server.takeRequest(1, TimeUnit.SECONDS) ?: break
      if (call.path != "/api/session/create") continue
      requests += JSONObject(call.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getJSONObject("request")
    }
    return requests
  }

  // ---------------------------------------------------------------- 功能补齐（P1）

  @Test fun fileSearchUsesFileReferencesWithAgentScope() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""[{"path":"src/Main.kt","kind":"file"},{"path":"src/","kind":"directory"}]"""))
      val results = api(server).searchFiles("/repo", " Main ", Session("s1", "/repo", "T", 1))
      assertEquals(listOf("src/Main.kt", "src"), results)
      server.takeRequest()
      val call = server.takeRequest()
      assertEquals("/api/fileReferences/list", call.requestUrl!!.encodedPath)
      val args = JSONObject(call.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("s1", args.getString("agentId"))
      assertEquals("Main", args.getString("query"))
    }
  }

  @Test fun agentsComeFromPresetRosterDefaultFirst() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"presets":[{"id":"plan","isDefault":false,"description":"只规划"},{"id":"standard","isDefault":true,"name":"标准"},{"id":"bad","isDefault":false,"broken":"缺依赖"}]}"""))
      val agents = api(server).agents("/repo")
      assertEquals(listOf("standard", "plan"), agents.map { it.name })
      assertEquals("标准", agents[0].description)
      server.takeRequest()
      assertEquals("/api/agentPresets/list", server.takeRequest().requestUrl!!.encodedPath)
    }
  }

  @Test fun differentAgentSelectsPresetBeforePrompt() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("\"plan\""))
      server.enqueue(envelope("""{"accepted":true}"""))
      api(server).send(Session("s1", "/repo", "T", 1, agent = "standard"), "先规划", "plan", null)
      server.takeRequest()
      val select = server.takeRequest()
      assertEquals("/api/agentPresets/select", select.requestUrl!!.encodedPath)
      val args = JSONObject(select.body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals("s1", args.getString("agentId"))
      assertEquals("plan", args.getString("agentPreset"))
      assertEquals("/api/session/prompt", server.takeRequest().requestUrl!!.encodedPath)
    }
  }

  @Test fun dataUriAttachmentsBecomeImageAndTextBlocks() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"accepted":true}"""))
      val png = byteArrayOf(1, 2, 3)
      val image = InlineFile("shot.png", "image/png", AttachmentPolicy.dataUri("image/png", png))
      val note = InlineFile("note.md", "text/markdown", AttachmentPolicy.dataUri("text/markdown", "# 标题".toByteArray()))
      api(server).send(Session("s1", "/repo", "T", 1), "看图", null, null,
        references = listOf(FileReference("src/A.kt")), inline = listOf(image, note))
      server.takeRequest()
      val content = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("payload").getJSONObject("args")
        .getJSONObject("request").getJSONArray("content")
      assertEquals("看图\n@src/A.kt", content.getJSONObject(0).getString("text"))
      val imageBlock = content.getJSONObject(1)
      assertEquals("image", imageBlock.getString("type"))
      assertEquals("image/png", imageBlock.getString("mediaType"))
      assertArrayEquals(png, java.util.Base64.getDecoder().decode(imageBlock.getString("data")))
      val textBlock = content.getJSONObject(2)
      assertEquals("text", textBlock.getString("type"))
      assertTrue(textBlock.getString("text").contains("note.md"))
      assertTrue(textBlock.getString("text").contains("# 标题"))
    }
  }

  @Test fun directoryWithoutSessionIsBrowsedThroughDirectoryPicker() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"items":[]}"""))
      server.enqueue(envelope("""{"path":"/home/me","home":"/home/me","crumbs":[],"entries":[{"name":"repo","path":"/home/me/repo","hidden":false}],"truncated":false}"""))
      val nodes = api(server).files("/home/me", ".", null)
      assertEquals(listOf("repo" to "directory"), nodes.map { it.path to it.type })
      server.takeRequest(); server.takeRequest()
      val call = server.takeRequest()
      assertEquals("/api/directoryPicker/list", call.requestUrl!!.encodedPath)
      assertEquals("/home/me", JSONObject(call.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getString("path"))
    }
  }

  @Test fun addedSessionEventCarriesOverviewInfo() = runBlocking {
    val server = MockWebServer().also { it.start() }
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.optString("endpoint") != "\$events") return
              val id = frame.getString("streamId")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c1"}}""")
              webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"emit","event":"api-session/added","args":[${sessionRow(id = "s7", directory = "/w", blank = true)}]}}""")
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val event = withTimeout(20_000) { api(server).events().take(1).toList() }.single()
      val session = event.properties.sessionInfo().toSession()
      assertEquals("s7", session.id)
      assertEquals("/w", session.directory)
      assertEquals("修复登录", session.title)
      assertTrue(session.blank)
    } finally {
      server.shutdown()
    }
  }

  // ---------------------------------------------------------------- 前端差异（P2）

  @Test fun dshCapabilitiesReplaceDeleteWithArchiveAndOfferFork() {
    val capabilities = api(MockWebServer()).capabilities()
    assertFalse(capabilities.deleteSession)
    assertTrue(capabilities.fork)
    assertTrue(ApiCapabilities.BASELINE.deleteSession)
    assertFalse(ApiCapabilities.BASELINE.fork)
  }

  @Test fun concurrentListingReadsShareOneSessionListRequest() = runBlocking {
    MockWebServer().use { server ->
      val lists = java.util.concurrent.atomic.AtomicInteger()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> { lists.incrementAndGet()
            envelope("""{"items":[${sessionRow(running = true)}]}""").setBodyDelay(200, TimeUnit.MILLISECONDS) }
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = api(server)
      val (version, page, active) = coroutineScope {
        val version = async { client.health() }
        val page = async { client.rootSessionsPage() }
        val active = async { client.activeSessions() }
        Triple(version.await(), page.await(), active.await())
      }
      assertEquals("DeepSeek Harness", version)
      assertEquals(listOf("s1"), page.items.map { it.id })
      assertEquals(setOf("s1"), active)
      assertEquals("one shared session/list read", 1, lists.get())
      // Nothing is cached afterwards: the next read goes to the server again.
      client.rootSessionsPage()
      assertEquals(2, lists.get())
    }
  }

  @Test fun tunnelHostHeaderIsAppliedToEveryRequest() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(MockResponse().setResponseCode(401))
      val client = DshApi(profile(server), ServerCredentials("dsh", "tok"), hostHeader = "127.0.0.1:45678")
      runCatching { client.health() }
      val request = server.takeRequest(2, TimeUnit.SECONDS)
      assertNotNull(request)
      assertEquals("127.0.0.1:45678", request!!.getHeader("Host"))
      client.close()
    }
  }

  /** 断线缺口只在有限范围内自动补；过大或还没有基线时不补，交给快照校正。 */
  @Test fun gapRepairWindowCoversOnlyBoundedForwardGaps() {
    val api = DshApi(ServerProfile("srv", "DSH", "http://127.0.0.1:1", username = "dsh", allowCleartext = true, kind = ServerKind.DSH),
      ServerCredentials("dsh", "tok"))
    assertEquals(5L..8L, api.gapRepairWindow(4, 9))
    assertNull("还没有基线时不补", api.gapRepairWindow(0, 9))
    assertNull("连续事件不是缺口", api.gapRepairWindow(4, 5))
    assertNull("过大缺口交给快照", api.gapRepairWindow(4, 4 + DshApi.MAX_GAP_REPAIR + 2))
  }
  @Test fun sessionListPagesLocallyAndNeverRepeatsACursor() = runBlocking {
    MockWebServer().use { server ->
      val rows = JSONArray()
      repeat(150) { index -> rows.put(sessionRow(id = "s$index", title = "S$index")) }
      server.enqueue(rootOk())
      server.enqueue(envelope(JSONObject().put("items", rows).toString()))
      server.enqueue(envelope(JSONObject().put("items", rows).toString()))
      val client = api(server)
      val page = client.rootSessionsPage()
      assertEquals(100, page.items.size)
      assertEquals("100", page.next)
      val second = client.rootSessionsPage(cursor = page.next)
      assertEquals(50, second.items.size)
      assertNull(second.next)
    }
  }

  @Test fun eventsReadyTimeoutClosesAndReconnectsTheLogicalStream() = runBlocking {
    val server = MockWebServer().also { it.start() }
    val attempts = java.util.concurrent.atomic.AtomicInteger()
    val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val frame = JSONObject(text)
              if (frame.str("type") != "open" || frame.str("endpoint") != "\$events") return
              if (attempts.incrementAndGet() >= 2) {
                val id = frame.str("streamId")
                webSocket.send("""{"type":"item","streamId":"$id","value":{"type":"ready","clientId":"c2"}}""")
              }
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      val client = DshApi(profile(server), ServerCredentials("dsh", "tok"), eventsReadyTimeoutMillis = 100)
      val collector = launch { client.events { opened.complete(Unit) }.collect { } }
      withTimeout(5_000) { opened.await() }
      assertTrue("the silent first generation must be replaced", attempts.get() >= 2)
      collector.cancel()
      client.close()
    } finally {
      server.shutdown()
    }
  }

  @Test fun explicitWorkspaceIdWinsOverDirectoryMatching() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(rootOk())
      server.enqueue(envelope("""{"sessionId":"s1"}"""))
      server.enqueue(envelope("""{"title":"x","seq":2}"""))
      val calls = mutableListOf<JSONObject>()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path!!.startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/create" -> {
            calls += JSONObject(request.body.readUtf8()).getJSONObject("payload").getJSONObject("args").getJSONObject("request")
            envelope("""{"sessionId":"s1"}""")
          }
          request.path == "/api/session/rename" -> envelope("""{"title":"x","seq":2}""")
          else -> MockResponse().setResponseCode(404)
        }
      }
      // Replace the queued responses: the dispatcher owns the exchange and request.
      api(server).createSession("/anywhere", "", "workspace-from-cache")
      val create = calls.single()
      assertEquals("workspace-from-cache", create.getString("workspaceId"))
      assertFalse(create.has("cwd"))
    }
  }

  @Test fun aGapRepairsToolHeadsBeforeProjectingTheCurrentResult() = runBlocking {
    val records = JSONArray().put(event("turn/start", 1, 1001, JSONObject().put("turn", 1)))
      .put(event("user/message", 2, 1002, JSONObject().put("id", "u").put("source", JSONObject().put("kind", "user"))
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "go")))))
    val missing = JSONArray().put(event("step/start", 3, 1003, JSONObject().put("turn", 1).put("step", 1)))
      .put(event("tool/call", 4, 1004, JSONObject().put("turn", 1).put("step", 1).put("callId", "c")
        .put("name", "bash").put("arguments", "{\"command\":\"pwd\"}")))
    val requestSeen = kotlinx.coroutines.CompletableDeferred<JSONObject>()
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") || request.path == "/" -> rootOk()
          request.path == "/api/session/list" -> envelope("{\"items\":[${sessionRow(running = true)}]}")
          request.path == "/api/session/page" -> {
            requestSeen.complete(JSONObject(request.body.readUtf8()).obj("payload").obj("args").obj("request"))
            envelope(JSONObject().put("records", missing).put("hasMore", false).toString())
          }
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val open = JSONObject(text)
              if (open.str("type") != "open") return
              val stream = open.str("streamId")
              fun send(value: JSONObject) { webSocket.send(JSONObject().put("type", "item").put("streamId", stream).put("value", value).toString()) }
              when (open.str("endpoint")) {
                "\$events" -> send(JSONObject().put("type", "ready").put("clientId", "fixture"))
                "session/follow" -> {
                  send(snapshotFrame(records, 2, false))
                  send(event("tool/result", 5, 1005, JSONObject().put("turn", 1).put("step", 1)
                    .put("message", JSONObject().put("toolCallId", "c").put("content", JSONArray()
                      .put(JSONObject().put("type", "text").put("text", "/repo"))))))
                }
              }
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val client = api(server)
      try {
        client.rootSessionsPage("/repo")
        var shown = client.messagesPage("s1").items
        val events = withTimeout(10_000) { client.events().take(4).toList() }
        assertEquals(listOf("session.step.started", "session.tool.input.started", "session.tool.called", "session.tool.success"), events.map { it.type })
        val request = requestSeen.await()
        assertEquals(4L, request.optLong("throughSeq"))
        assertEquals(5L, request.optLong("beforeSeq"))
        events.forEach { shown = TranscriptProjection.apply(shown, it)?.messages ?: shown }
        assertEquals("/repo", shown.last().parts.single().output)
        assertEquals("completed", shown.last().parts.single().status)
      } finally { client.close() }
    }
  }

}
