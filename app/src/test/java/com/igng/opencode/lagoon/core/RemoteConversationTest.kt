package com.igng.opencode.lagoon.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        assertEquals(20, page.items.size)
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
            for (seq in before..through) records.put(record(seq))
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
        assertEquals(5, request.getInt("beforeSeq"))
        assertEquals(8, request.getInt("throughSeq"))
        collector.cancelAndJoin()
      } finally { backend.close(); session.close() }
    }
  }
}
