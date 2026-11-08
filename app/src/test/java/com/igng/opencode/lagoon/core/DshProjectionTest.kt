package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The same durable log must render identically live, after reconnect, and after reopening. */
class DshProjectionTest {
  private val session = Session("root", "/repo", "Task", 1)
  private fun client() = DshApi(ServerProfile("srv", "DSH", "http://127.0.0.1:1", kind = ServerKind.DSH, allowCleartext = true), ServerCredentials("dsh", ""))
  private fun record(type: String, seq: Int, data: String) = JSONObject().put("type", "event").put("event",
    JSONObject().put("type", type).put("seq", seq).put("time", 1000L + seq).put("data", JSONObject(data)))

  @Test fun liveAndSnapshotKeepMultipleTextBlocksFailedToolsAndMetadata() {
    val records = listOf(
      record("turn/start", 1, """{"turn":1}"""),
      record("user/message", 2, """{"id":"u","source":{"kind":"user"},"content":[{"type":"text","text":"go"}]}"""),
      record("step/start", 3, """{"turn":1,"step":1}"""),
      record("assistant/message", 4, """{"turn":1,"step":1,"message":{"content":[{"type":"reasoning","text":"think"},{"type":"text","text":"answer"},{"type":"text","text":"extra"}]}}"""),
      record("tool/call", 5, """{"turn":1,"step":1,"callId":"c","name":"bash","arguments":"{\"command\":\"false\"}"}"""),
      record("tool/result", 6, """{"turn":1,"step":1,"message":{"toolCallId":"c","isError":true,"content":[{"type":"text","text":"diagnostic"},{"type":"file","uri":"/repo/image.png","mime":"image/png"}]},"error":{"reason":"exit 1"},"meta":{"exit":1,"files":[{"file":"a.kt","patch":"diff"}]}}"""),
      record("step/end", 7, """{"turn":1,"step":1}"""),
      record("turn/end", 8, """{"turn":1,"reason":{"kind":"completed"}}""")
    )
    val api = client()
    try {
      var live = emptyList<Message>()
      records.flatMap { api.translate(session, it.obj("event")) }.forEach { event ->
        live = TranscriptProjection.apply(live, event)?.messages ?: live
      }
      val snapshot = api.recordsToMessages(session, records)
      assertEquals(snapshot, live)
      val parts = live.last().parts
      assertEquals(listOf("think", "answer", "extra"), parts.take(3).map { it.text })
      assertEquals("error", parts.last().status)
      assertEquals("exit 1", parts.last().error)
      assertEquals(listOf("a.kt"), parts.last().files)
      assertEquals(1, JSONObject(parts.last().metadata).optInt("exit"))
      assertEquals("/repo/image.png", parts.last().attachments.single().url)
    } finally { api.close() }
  }

  @Test fun sourceAttributionNeverTurnsAgentOrJobNoticesIntoUserPrompts() {
    val api = client()
    val records = listOf(
      record("turn/start", 1, """{"turn":2}"""),
      record("user/message", 2, """{"id":"job","source":{"kind":"tool-jobs","form":"notice","summary":"bash exited 0"},"content":[{"type":"text","text":"Read its output with job_output"}]}"""),
      record("user/message", 3, """{"id":"relay","source":{"kind":"agent-message","form":"relay","senderSessionId":"child"},"content":[{"type":"text","text":"## 子代理结果\nhello"}]}"""),
      record("user/message", 4, """{"id":"settled","source":{"kind":"subagent-settled","form":"notice","senderSessionId":"child","summary":"completed"},"content":[{"type":"text","text":"runtime closing instructions"}]}"""),
      record("user/message", 5, """{"id":"context","source":{"kind":"runtime-context"},"content":[{"type":"text","text":"private runtime"}]}"""),
      record("user/message", 6, """{"id":"goal","source":{"kind":"goal"},"content":[{"type":"text","text":"Objective: \"test\"\nRound: 1/2"}]}""")
    )
    try {
      var live = emptyList<Message>()
      records.flatMap { api.translate(session, it.obj("event")) }.forEach { live = TranscriptProjection.apply(live, it)?.messages ?: live }
      val snapshot = api.recordsToMessages(session, records)
      assertEquals(snapshot, live)
      assertEquals(listOf("notice", "relay", "notice", "notice"), snapshot.map { it.role })
      assertEquals(listOf("tool-jobs", "agent-message", "subagent-settled", "goal"), snapshot.map { it.sourceKind })
      assertTrue(snapshot.none { it.isHumanInput })
      assertEquals("bash exited 0", snapshot.first().notice?.detail)
      assertEquals("child", snapshot[1].notice?.target)
      assertTrue(snapshot.all { it.turnId == "turn:2" })
      val rows = TranscriptRows.build(snapshot)
      assertTrue(rows.none { it.kind == "user" || it.kind == "relay" || it.text.contains("Read its output") || it.text.contains("runtime closing") })
      assertTrue(snapshot.any { it.role == "relay" && it.parts.any { part -> part.text.contains("子代理结果") } })
    } finally { api.close() }
  }

  @Test fun missingSourceIsLegacyHumanButExplicitUnknownSourceIsNot() {
    val api = client()
    try {
      val legacy = record("user/message", 1, """{"id":"legacy","content":[{"type":"text","text":"human"}]}""")
      val unknown = record("user/message", 2, """{"id":"unknown","source":{"kind":"runtime-context"},"content":[{"type":"text","text":"private"}]}""")
      val malformed = record("user/message", 3, """{"id":"malformed","source":{},"content":[{"type":"text","text":"private"}]}""")
      val messages = listOf(legacy, unknown, malformed).flatMap { api.translate(session, it.obj("event")) }
        .fold(emptyList<Message>()) { current, event -> TranscriptProjection.apply(current, event)?.messages ?: current }
      assertEquals(listOf("legacy"), messages.filter { it.isHumanInput }.map { it.id })
      assertTrue(messages.none { it.id == "unknown" || it.id == "malformed" })
    } finally { api.close() }
  }
  @Test fun translatingOneEventDetachesItsMutableProperties() {
    val api = client()
    try {
      val event = JSONObject("""{"type":"tool/call","seq":3,"time":3,"data":{"turn":1,"step":1,"callId":"c","name":"read","arguments":"{\"path\":\"a\"}"}}""")
      val translated = api.translate(session, event)
      assertEquals(2, translated.size)
      translated.last().properties.put("id", "changed")
      assertEquals("c", translated.first().properties.str("id"))
    } finally { api.close() }
  }

  @Test fun authoritativeBlocksReplaceLiveTextWithoutDuplicatingTools() {
    val api = client()
    try {
      val prior = Message("msg_1:1", "assistant", 1003,
        listOf(MessagePart("live", "text", "partial"), MessagePart("c", "tool", tool = "bash", status = "running")),
        turnId = "turn:1", sequence = 3)
      val event = api.translate(session, record("assistant/message", 4,
        """{"turn":1,"step":1,"message":{"content":[{"type":"reasoning","text":"think"},{"type":"text","text":"done"}]}}""").obj("event")).single()
      val projected = TranscriptProjection.apply(listOf(prior), event)!!.messages.single()
      assertEquals(listOf("think", "done", ""), projected.parts.map { it.text })
      assertEquals(1, projected.parts.count { it.type == "tool" })
    } finally { api.close() }
  }

  @Test fun durableUserMessageCarriesRpcIdForOptimisticReconciliation() {
    val api = client()
    try {
      val event = record("user/message", 2, """{"id":"user:server","source":{"kind":"user","rpcId":"req-7"},"content":[{"type":"text","text":"继续"}]}""")
      val translated = api.translate(session, event.obj("event")).single()
      val optimistic = Message("req-7", "user", 1, listOf(MessagePart("req-7:text", "text", text = "继续")), sourceRequestId = "req-7")
      val projected = TranscriptProjection.apply(listOf(optimistic), translated)!!.messages
      assertEquals(1, projected.size)
      assertEquals("user:server", projected.single().id)
      assertEquals("req-7", projected.single().sourceRequestId)
    } finally { api.close() }
  }

  @Test fun mergeReplacesOptimisticMessageWhenDurableIdDiffers() {
    val optimistic = Message("req-7", "user", 1, listOf(MessagePart("req-7:text", "text", text = "继续")), sourceRequestId = "req-7")
    val durable = Message("user:server", "user", 2, listOf(MessagePart("user:server:text", "text", text = "继续")), sourceRequestId = "req-7")
    val merged = mergeTranscript(listOf(optimistic), listOf(durable))
    assertEquals(listOf("user:server"), merged.map { it.id })
  }

  @Test fun sameMillisecondMergeKeepsTheDurableSequenceNotArrivalOrder() {
    val user = Message("u", "user", 1, listOf(MessagePart("p", "text", "question")), sequence = 2)
    val assistant = Message("a", "assistant", 1, listOf(MessagePart("p", "text", "answer")), sequence = 3)
    assertEquals(listOf(user, assistant), mergeTranscript(listOf(assistant), listOf(user, assistant)))
  }
}
