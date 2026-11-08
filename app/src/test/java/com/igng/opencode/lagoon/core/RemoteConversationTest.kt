package com.igng.opencode.lagoon.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** 端到端（假 Host）：Remote 连接 → 会话列表 → `session/follow` 快照 → 会话内容。 */
class RemoteConversationTest {
  private class Store(private val record: RemoteDeviceRecord) : RemoteDeviceStore {
    private var current = record
    override fun load(slot: String): RemoteDeviceRecord? = current
    override fun save(slot: String, record: RemoteDeviceRecord) { current = record }
    override fun delete(slot: String) { }
  }

  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope): String = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = key
  }

  private fun token(): String = UUID.randomUUID().toString() + UUID.randomUUID().toString()

  private fun backend(host: FakeRemoteHost): Pair<RemoteSession, RemoteBackend> {
    val identity = RemoteIdentity.generate()
    val origin = canonicalRemoteOrigin(host.url)
    val record = RemoteDeviceRecord(origin, host.accountEmail(), identity,
      RemoteTokenPair(token(), Long.MAX_VALUE / 2, token(), Long.MAX_VALUE / 2))
    val manager = RemoteDeviceManager(Store(record), "slot-${UUID.randomUUID()}", host.url, host.accountEmail())
    val trust = RemotePeerTrust(Pins(host.hostIdentity.publicKey))
    val session = RemoteSession(host.url, true, host.hostId, manager, trust, host.accountEmail())
    return session to RemoteBackend(session)
  }

  private fun snapshot(id: String, records: Int): JSONObject {
    val items = JSONArray()
    items.put(JSONObject().put("type", "event").put("event", JSONObject().put("type", "user/message")
      .put("seq", 1).put("time", 100).put("data", JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "修一下登录")))
        .put("role", "user").put("id", "u1"))))
    items.put(JSONObject().put("type", "event").put("event", JSONObject().put("type", "step/start")
      .put("seq", 2).put("time", 110).put("data", JSONObject().put("turn", 1).put("step", 1))))
    items.put(JSONObject().put("type", "event").put("event", JSONObject().put("type", "assistant/message")
      .put("seq", 3).put("time", 120).put("data", JSONObject().put("turn", 1).put("step", 1)
        .put("message", JSONObject().put("role", "assistant")
          .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "已修复登录")))))))
    items.put(JSONObject().put("type", "event").put("event", JSONObject().put("type", "step/end")
      .put("seq", 4).put("time", 130).put("data", JSONObject().put("turn", 1).put("step", 1))))
    return JSONObject().put("type", "snapshot")
      .put("header", JSONObject().put("version", 4).put("id", id).put("createdAt", 1).put("cwd", "/repo").put("agentPreset", "standard"))
      .put("cursor", records).put("records", items).put("hasMore", false)
      .put("projections", JSONObject().put("asOfSeq", 4).put("values", JSONObject()))
  }

  private fun sessionRow(id: String) = JSONObject().put("sessionId", id).put("updatedAt", 100)
    .put("running", false).put("cwd", "/repo")

  @Test fun loadsConversationFromRemoteStream() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(sessionRow(id))))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(snapshot(id, 4)) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        val page = backend.client.messagesPage(id)
        println("REMOTE messages=${page.items.size} opens=${host.streamOpens.size}")
        assertEquals(2, page.items.size)
        assertEquals("修一下登录", page.items.first().parts.first().text)
      } finally {
        backend.close()
        session.close()
      }
    }
  }

  /**
   * 端到端回归：Goal / Schedule / Plugin / Credential 在真实 RemoteGateway + FakeRemoteHost 严格 `{args}` 校验下
   * 全部使用合法的 Typert RPC 信封，不再因顶层缺少 `args` 报 `gateway/internal`。
   */
  @Test fun remoteGoalSchedulePluginAndCredentialCallsCarryStrictArgsEnvelope() = runBlocking {
    FakeRemoteHost().use { host ->
      host.call = { endpoint, payload ->
        val args = payload.getJSONObject("args")
        when (endpoint) {
          // 远程白名单里没有 `goals/get`：目标以投影为准，activation 走 `goal/activation-changed`。
          "goals/get" -> throw AssertionError("远程模式不应调用 goals/get（不在 HARNESS_REMOTE_ALLOWLIST 里）")
          "session/projections" -> {
            assertEquals("s-remote", args.getJSONObject("request").getString("sessionId"))
            host.ok(JSONObject().put("values", JSONObject().put("goal", JSONObject()
              .put("goal", JSONObject().put("id", "g1").put("revision", 2).put("objective", "完成修复")
                .put("phase", "active").put("maxGoalRounds", 16))
              .put("roundsStarted", 1).put("createdAt", 1).put("updatedAt", 2))))
          }
          "goals/create" -> {
            assertEquals("s-remote", args.getString("agentId"))
            assertEquals("新目标", args.getJSONObject("request").getString("objective"))
            host.ok(JSONObject().put("ref", JSONObject().put("id", "g2").put("revision", 1)))
          }
          "goals/pause" -> {
            assertEquals("s-remote", args.getString("agentId"))
            assertEquals("g1", args.getJSONObject("ref").getString("id"))
            host.ok(JSONObject())
          }
          "schedule/list" -> {
            assertEquals("s-remote", args.getJSONObject("request").getString("sessionId"))
            host.ok(JSONArray().put(JSONObject().put("id", "sch-1").put("kind", "every").put("everySeconds", 300).put("title", "轮询")))
          }
          "pluginManager/setBundleEnabled" -> {
            assertEquals("dsh-bundle", args.getString("name"))
            host.ok(JSONObject())
          }
          "pluginManager/setPluginEnabled" -> {
            assertEquals("plugin-1", args.getString("id"))
            host.ok(JSONObject())
          }
          "credentials/describe" -> {
            assertEquals("API_KEY", args.getJSONArray("refs").getString(0))
            host.ok(JSONObject().put("API_KEY", JSONObject().put("configured", true).put("writable", true)))
          }
          "credentials/set", "credentials/unset" -> host.ok(JSONObject())
          else -> host.ok(JSONObject())
        }
      }
      host.start()
      val (session, backend) = backend(host)
      try {
        val goal = backend.client.goal("s-remote")
        assertEquals("g1", goal?.id)
        assertTrue(backend.client.createGoal("s-remote", "新目标", 10))
        assertTrue(backend.client.goalAction("s-remote", "g1", 2, "pause"))
        assertEquals(1, backend.client.schedules("s-remote").size)
        assertTrue(backend.client.setPluginBundle("dsh-bundle", true))
        assertTrue(backend.client.setPluginEntry("plugin-1", false))
        assertTrue(backend.client.credentialStatus(listOf("API_KEY")).getValue("API_KEY").configured)
        assertTrue(backend.client.setCredential("API_KEY", "secret"))
        assertTrue(backend.client.clearCredential("API_KEY"))
      } finally {
        backend.close()
        session.close()
      }
    }
  }

  /** 与真机一致：~1.5 MB 的快照会被切成几十个 DSHF 分片，跨 Noise 逐帧送达。 */
  @Test fun loadsLargeSnapshotDeliveredInFragments() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    val padding = "x".repeat(3_000)
    val records = JSONArray()
    var seq = 0
    repeat(500) {
      records.put(JSONObject().put("type", "event").put("event", JSONObject().put("type", "assistant/message")
        .put("seq", ++seq).put("time", 100L + seq).put("data", JSONObject().put("turn", 1).put("step", seq)
          .put("message", JSONObject().put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "第 $seq 段 $padding")))))))
    }
    val large = JSONObject().put("type", "snapshot")
      .put("header", JSONObject().put("version", 4).put("id", id).put("createdAt", 1).put("cwd", "/repo").put("agentPreset", "standard"))
      .put("cursor", seq).put("records", records).put("hasMore", false)
      .put("projections", JSONObject().put("asOfSeq", seq).put("values", JSONObject()))
    assertTrue("构造的快照应超过 1 MB", large.toString().length > 1_000_000)
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(sessionRow(id))))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(large) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        val page = backend.client.messagesPage(id)
        // The whole Host window is installed; the old code truncated this to 20 and returned next=null.
        assertEquals(500, page.items.size)
      } finally { backend.close(); session.close() }
    }
  }

  @Test fun openingWindowKeepsHasMoreAndPagesOlderOverRemote() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    val records = JSONArray()
    repeat(30) { index ->
      val step = index + 1
      records.put(JSONObject().put("type", "event").put("event", JSONObject().put("type", "assistant/message")
        .put("seq", step).put("time", 100L + step).put("data", JSONObject().put("turn", 1).put("step", step)
          .put("message", JSONObject().put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "第 $step 段")))))))
    }
    val snapshot = JSONObject().put("type", "snapshot")
      .put("header", JSONObject().put("version", 4).put("id", id).put("createdAt", 1).put("cwd", "/repo"))
      .put("cursor", 1000).put("records", records).put("hasMore", true)
      .put("projections", JSONObject().put("asOfSeq", 30).put("values", JSONObject()))
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(sessionRow(id))))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(snapshot) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        val page = backend.client.messagesPage(id)
        assertEquals(30, page.items.size)
        assertTrue("hasMore 必须变成 older cursor", page.next != null)
        val older = backend.client.messagesPage(id, page.next, HarnessClient.HISTORY_PAGE)
        assertTrue(older.items.isEmpty())
        val request = host.rpcCalls.last { it.getString("method") == "harness.remote.call" &&
          it.getJSONObject("params").getString("endpoint") == "session/page" }
          .getJSONObject("params").getJSONObject("payload").getJSONObject("args").getJSONObject("request")
        assertEquals(HarnessClient.HISTORY_PAGE, request.getInt("maxMessages"))
        assertEquals(50, request.getJSONObject("turnWindow").getInt("minMessages"))
        assertEquals(2, request.getJSONObject("turnWindow").getInt("minTurns"))
      } finally { backend.close(); session.close() }
    }
  }

  /** 首帧不是 snapshot 时必须报错，而不是把空 records 当成“这个会话没有内容”。 */
  @Test fun rejectsNonSnapshotFirstFrame() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(sessionRow(id))))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { _, _ -> listOf(JSONObject().put("type", "ready")) }
      host.start()
      val (session, backend) = backend(host)
      try {
        val error = runCatching { backend.client.messagesPage(id) }.exceptionOrNull()
        assertTrue("应抛出可读错误", error is ApiException)
        assertTrue(error!!.message!!.contains("快照"), )
      } finally { backend.close(); session.close() }
    }
  }

  /** 主机侧流失败要带端点与原因，供聊天页直接显示。 */
  @Test fun streamFailureCarriesEndpointAndReason() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(sessionRow(id))))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFailure = "会话日志损坏"
      host.start()
      val (session, backend) = backend(host)
      try {
        val error = runCatching { backend.client.messagesPage(id) }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertTrue(error!!.message!!.contains("session/follow"), )
      } finally { backend.close(); session.close() }
    }
  }

  /** 中枢下发独立 Relay 数据通道时，握手与密文都必须搬到该通道上（relay.attach → relay.ready → transport.selected）。 */
  @Test fun loadsConversationThroughAssignedRelayDataSocket() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    FakeRemoteHost().use { host ->
      host.assignRelay = true
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(sessionRow(id))))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(snapshot(id, 4)) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        val page = backend.client.messagesPage(id)
        assertEquals(2, page.items.size)
        assertTrue("应打开过独立 Relay 数据通道", host.requests.any { it.startsWith("GET /relay/v1") })
      } finally { backend.close(); session.close() }
    }
  }

  /**
   * `workspace/follow` 的参数形状按代际不同：0.2.1 线只收 `{args:{}}`，新代际收 `{args:{request:{}}}`。
   * 被 `gateway/arguments-invalid` 拒绝时要自动换形状，而不是把“没有工作区”当成结果。
   */
  @Test fun workspaceFollowFallsBackToGenerationSpecificArgs() = runBlocking {
    val baseline = JSONObject().put("type", "baseline").put("value", JSONObject()
      .put("items", JSONArray().put(JSONObject().put("workspaceId", "w1").put("path", "/repo").put("title", "repo")))
      .put("archivedSessionIds", JSONArray()))
    FakeRemoteHost().use { host ->
      var rejected = 0
      host.streamOpenError = { endpoint, payload ->
        val args = payload.optJSONObject("args")
        if (endpoint == "workspace/follow" && args != null && !args.has("request")) {
          rejected++
          "gateway/arguments-invalid"
        } else null
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "workspace/follow") listOf(baseline) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        val projects = backend.client.projects()
        assertEquals(1, rejected)
        assertEquals(1, projects.size)
        assertEquals("/repo", projects.single().directory)
        // 第二次沿用已经确认的形状，不再被拒。
        assertEquals(1, backend.client.projects().size)
        assertEquals(1, rejected)
      } finally { backend.close(); session.close() }
    }
  }

  /** 实时流出现 seq 缺口时用 `session/page` 补齐，缺的事件不会悄悄丢掉。 */
  @Test fun repairsLiveSeqGapFromSessionPage() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    fun record(seq: Int): JSONObject = JSONObject().put("type", "event").put("event", JSONObject()
      .put("type", "assistant/message").put("seq", seq).put("time", 100L + seq)
      .put("data", JSONObject().put("turn", 1).put("step", seq)
        .put("message", JSONObject().put("role", "assistant")
          .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "补洞 $seq"))))))
    FakeRemoteHost().use { host ->
      host.call = { endpoint, payload ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(sessionRow(id))))
          "session/page" -> {
            val request = payload.getJSONObject("args").getJSONObject("request")
            val before = request.getInt("beforeSeq")
            val through = request.getInt("throughSeq")
            val records = JSONArray()
            // session/page is backwards with an exclusive upper beforeSeq, not a lower bound.
            for (seq in 5..minOf(through, before - 1)) records.put(record(seq))
            host.ok(JSONObject().put("records", records).put("hasMore", false))
          }
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(snapshot(id, 4)) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        val received = mutableListOf<ServerEvent>()
        val collector = launch { backend.client.events {}.collect { event -> synchronized(received) { received += event } } }
        delay(300)
        backend.client.messagesPage(id)
        delay(300)
        host.push("session/follow", JSONObject().put("type", "event").put("event", JSONObject()
          .put("type", "assistant/message").put("seq", 9).put("time", 900L)
          .put("data", JSONObject().put("turn", 1).put("step", 9)
            .put("message", JSONObject().put("role", "assistant")
              .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "最新一步")))))))
        fun pageCalls() = host.rpcCalls.filter {
          it.optString("method") == "harness.remote.call" && it.optJSONObject("params")?.optString("endpoint") == "session/page"
        }
        withTimeout(5_000) { while (pageCalls().isEmpty()) delay(20) }
        val request = pageCalls().first()
          .getJSONObject("params").getJSONObject("payload").getJSONObject("args").getJSONObject("request")
        assertEquals(9, request.getInt("beforeSeq"))
        assertEquals(8, request.getInt("throughSeq"))
        withTimeout(5_000) {
          while (synchronized(received) { received.count { it.type == "session.dsh.assistant" } } < 5) delay(20)
        }
        assertEquals((5..9).map { "msg_1:$it" }, synchronized(received) {
          received.filter { it.type == "session.dsh.assistant" }.map { it.properties.str("assistantMessageID") }
        })
        collector.cancelAndJoin()
      } finally { backend.close(); session.close() }
    }
  }

  /** 首页只应为运行中的会话开 follow；空闲会话不该占长期流预算。 */
  @Test fun homeListFollowsOnlyRunningSessions() = runBlocking {
    val ids = (1..20).map { "s%02d".format(it) }
    val running = ids.take(3).toSet()
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().apply {
            ids.forEach { id ->
              put(JSONObject().put("sessionId", id).put("updatedAt", 100)
                .put("running", id in running).put("cwd", "/repo"))
            }
          }))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(snapshot("s01", 4)) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        backend.client.rootSessionsPage()
        val collector = launch { backend.client.events {}.collect { } }
        delay(500)
        backend.client.rootSessionsPage()
        delay(700)
        val follows = host.streamOpens.filter { it.optString("endpoint") == "session/follow" }
        println("REMOTE running-only follows=${follows.size} total=${host.streamOpens.size}")
        assertEquals("只为运行中的 3 个会话开 follow", 3, follows.size)
        collector.cancelAndJoin()
      } finally { backend.close(); session.close() }
    }
  }

  /** 后台 job/follow 不能把核心事件流和会话流挤出预算；打开旧会话仍应成功。 */
  @Test fun backgroundStreamsDoNotStarveCoreStreams() = runBlocking {
    val ids = (1..20).map { "s%02d".format(it) }
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().apply {
            ids.forEach { id ->
              put(JSONObject().put("sessionId", id).put("updatedAt", 100)
                .put("running", true).put("cwd", "/repo"))
            }
          }))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(snapshot("s01", 4)) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        repeat(8) { index ->
          launch { runCatching { backend.client.followJobOutput("s01", "job-$index") { _, _, _ -> } } }
        }
        delay(500)
        backend.client.rootSessionsPage()
        val collector = launch { backend.client.events {}.collect { } }
        delay(700)
        backend.client.rootSessionsPage()
        delay(500)
        val opens = host.streamOpens.map { it.optString("endpoint") }
        println("REMOTE saturation opens=$opens")
        assertTrue("核心事件流应能打开", "\$events" in opens)
        assertTrue("工作区流应能打开", "workspace/follow" in opens)
        val error = runCatching { backend.client.messagesPage(ids.last()) }.exceptionOrNull()
        println("REMOTE saturation open error=${error?.message}")
        assertNull("后台流不应导致会话打开失败", error)
        collector.cancelAndJoin()
      } finally { backend.close(); session.close() }
    }
  }

  /** 当前阅读的会话固定在 watch 集合里，首页刷新不能把它挤掉。 */
  @Test fun activeConversationFollowSurvivesHomeRefresh() = runBlocking {
    val ids = (1..20).map { "s%02d".format(it) }
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().apply {
            ids.forEach { id ->
              put(JSONObject().put("sessionId", id).put("updatedAt", 100)
                .put("running", true).put("cwd", "/repo"))
            }
          }))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(snapshot("s01", 4)) else emptyList() }
      host.start()
      val (session, backend) = backend(host)
      try {
        backend.client.rootSessionsPage()
        val collector = launch { backend.client.events {}.collect { } }
        delay(500)
        backend.client.messagesPage(ids.first())
        delay(300)
        backend.client.rootSessionsPage()
        delay(500)
        val follows = (backend.client as DshApi).followDiagnostics()
        println("REMOTE active-pin follows=${follows.map { it.sessionId }}")
        assertTrue("当前会话应仍在 watch 集合", follows.any { it.sessionId == ids.first() })
        assertTrue("follow 数不应超过 WATCH_LIMIT", follows.size <= DshApi.WATCH_LIMIT)
        collector.cancelAndJoin()
      } finally { backend.close(); session.close() }
    }
  }
}
