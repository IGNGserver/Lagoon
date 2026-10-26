package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Live projection of OpenCode 2.x `/api/event` frames, checked against the official reducer's semantics. */
class TranscriptProjectionTest {
  private var sequence = 0
  private fun event(type: String, data: String) = ServerEvent("evt_${++sequence}", "/p", type, JSONObject(data), created = 1_000L + sequence)
  private fun List<Message>.apply(type: String, data: String) = TranscriptProjection.apply(this, event(type, data))!!.messages

  @Test fun aStepStreamsTextAndReasoningIntoOneAssistantMessage() {
    val messages = listOf(Message("msg_u", "user", 1, listOf(MessagePart("msg_u:text", "text", text = "hi"))))
      .apply("session.step.started", """{"sessionID":"s","assistantMessageID":"msg_a","agent":"build","model":{"providerID":"p","id":"m"},"started":5}""")
      .apply("session.reasoning.started", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0}""")
      .apply("session.reasoning.delta", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"delta":"想"}""")
      .apply("session.reasoning.ended", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"text":"想一想"}""")
      .apply("session.text.started", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0}""")
      .apply("session.text.delta", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"delta":"你"}""")
      .apply("session.text.delta", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"delta":"好"}""")
    val assistant = messages.last()
    assertEquals("msg_a", assistant.id); assertEquals("build", assistant.agent); assertEquals("m", assistant.model?.modelId)
    assertEquals(listOf("msg_a:reasoning:0", "msg_a:text:0"), assistant.parts.map { it.id })
    assertEquals("想一想", assistant.parts[0].text); assertEquals("你好", assistant.parts[1].text)
    val ended = messages.apply("session.text.ended", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"text":"你好！"}""")
      .apply("session.step.ended", """{"sessionID":"s","assistantMessageID":"msg_a","finish":"stop","cost":0,"tokens":{}}""")
    assertEquals("你好！", ended.last().parts.last().text)
    assertNotNull(ended.last().completedAt)
  }

  @Test fun eventsForAnAssistantThatWasNeverLoadedAreDropped() {
    val result = TranscriptProjection.apply(emptyList(), event("session.text.delta", """{"sessionID":"s","assistantMessageID":"msg_x","ordinal":0,"delta":"lost"}"""))!!
    assertTrue(result.messages.isEmpty())
    assertFalse(result.reconcile)
  }

  @Test fun toolLifecycleProjectsInputOutputAndEditDiffs() {
    val base = listOf(Message("msg_a", "assistant", 1, emptyList(), type = "assistant"))
      .apply("session.tool.input.started", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","name":"edit"}""")
    assertEquals("pending", base.single().parts.single().status)
    val called = base.apply("session.tool.called", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","input":{"path":"src/a.kt","oldString":"a","newString":"b"},"executed":true}""")
    assertEquals("running", called.single().parts.single().status)
    assertEquals("src/a.kt", called.single().parts.single().path)
    val done = called.apply("session.tool.success", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","content":[{"type":"text","text":"edited"}],
      "metadata":{"files":[{"file":"src/a.kt","patch":"@@ -1 +1 @@","additions":1,"deletions":1,"status":"modified"}],"replacements":1},"executed":true}""")
    val tool = done.single().parts.single()
    assertEquals("completed", tool.status); assertEquals("edited", tool.output)
    assertEquals(listOf("src/a.kt"), tool.files); assertEquals("@@ -1 +1 @@", tool.patch)
  }

  @Test fun aShellCommandWithANonZeroExitKeepsDiagnosticsWithoutFailingTheGroup() {
    val done = listOf(Message("msg_a", "assistant", 1, emptyList(), type = "assistant"))
      .apply("session.tool.input.started", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","name":"shell"}""")
      .apply("session.tool.called", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","input":{"command":"false"},"executed":true}""")
      .apply("session.tool.success", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","content":[{"type":"text","text":""}],"metadata":{"exit":1},"executed":true}""")
    assertEquals("error", done.single().parts.single().status)
    assertEquals("退出码 1", done.single().parts.single().error)
    assertNull(done.single().error)
    val group = TranscriptRows.build(done).single { it.kind == "tool-group" }
    assertEquals("warning", group.status)
    assertEquals("含 1 条异常命令", group.subtitle)
    assertTrue(TranscriptRows.details(done, group.key).any { it.kind == "tool-body" && it.title == "错误" && it.text == "退出码 1" })

    val continuing = done
      .apply("session.tool.input.started", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_2","name":"read"}""")
      .apply("session.tool.called", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_2","input":{"path":"a.kt"},"executed":true}""")
    val running = TranscriptRows.build(continuing, working = true)
    assertEquals("running", running.single { it.kind == "tool-group" }.status)
    assertEquals("含 1 条异常命令", running.single { it.kind == "tool-group" }.subtitle)
    assertTrue(running.none { it.kind == "error" })
  }

  @Test fun instructionUpdatesBecomeNoticesNeverReplies() {
    val messages = listOf(Message("msg_a", "assistant", 1, emptyList(), type = "assistant"))
      .apply("session.instructions.updated", """{"sessionID":"s","delta":{"mcp:playwright":{}},"text":"tools.playwright.browser_close(): Promise<unknown>"}""")
    val notice = messages.last()
    assertEquals("notice", notice.role)
    assertEquals("指令已更新", notice.notice?.label)
    assertEquals(listOf("mcp:playwright"), notice.notice?.items)
    assertTrue(notice.parts.isEmpty())
    val rows = TranscriptRows.build(messages)
    assertTrue(rows.none { it.text.contains("Promise<unknown>") })
  }

  @Test fun inboxInputAppearsQueuedAndMovesToTheEndWhenDelivered() {
    val pending = listOf(Message("msg_a", "assistant", 1, emptyList(), type = "assistant"))
      .apply("session.inbox.enqueued", """{"sessionID":"s","inboxID":"msg_q","item":{"type":"user","delivery":"queue","payload":{"text":"然后呢"}}}""")
    assertTrue(pending.last().queued)
    val delivered = (pending + Message("msg_b", "assistant", 3, emptyList(), type = "assistant"))
      .apply("session.inbox.delivered", """{"sessionID":"s","inboxID":"msg_q"}""")
    assertEquals("msg_q", delivered.last().id)
    assertFalse(delivered.last().queued)
    val cancelled = pending.apply("session.inbox.cancelled", """{"sessionID":"s","inboxID":"msg_q"}""")
    assertEquals(listOf("msg_a"), cancelled.map { it.id })
  }

  @Test fun committedRevertDropsTheMessagesAfterItsBoundary() {
    val messages = listOf("msg_1", "msg_2", "msg_3").map { Message(it, "user", 1, emptyList()) }
    assertEquals(listOf("msg_1"), messages.apply("session.revert.committed", """{"sessionID":"s","to":"msg_2"}""").map { it.id })
  }

  @Test fun aRunEndingWithToolsInFlightAsksForReconciliation() {
    val running = listOf(Message("msg_a", "assistant", 1, listOf(MessagePart("call_1", "tool", tool = "shell", status = "running")), type = "assistant"))
    val result = TranscriptProjection.apply(running, event("session.execution.succeeded", """{"sessionID":"s"}"""))!!
    assertTrue(result.reconcile)
  }

  @Test fun subagentResultsLinkToTheChildSession() {
    val messages = emptyList<Message>()
      .apply("session.synthetic", """{"sessionID":"s","text":"<task result>…</task result>","description":"找到 3 处调用","metadata":{"source":"subagent","state":"completed","agent":"explore","childID":"ses_child"}}""")
    assertEquals("explore 已完成", messages.single().notice?.label)
    assertEquals("ses_child", messages.single().notice?.target)
    // Synthetic input without a description is not a transcript row.
    val hidden = emptyList<Message>().apply("session.synthetic", """{"sessionID":"s","text":"model-only context"}""")
    assertFalse(hidden.single().isDisplayable)
  }

  @Test fun mergeKeepsOlderPagesAndLetsTheServerCopyWinForTheSameId() {
    val older = (1..3).map { Message("m$it", "user", it.toLong(), emptyList()) }
    val tail = listOf(Message("m3", "user", 3, emptyList(), error = "updated"), Message("m4", "user", 4, emptyList()))
    val merged = mergeTranscript(older, tail)
    assertEquals(listOf("m1", "m2", "m3", "m4"), merged.map { it.id })
    assertEquals("updated", merged.first { it.id == "m3" }.error)
  }

  @Test fun mergeKeepsAnOptimisticEntryTheServerHasNotSeen() {
    val existing = listOf(Message("m1", "user", 1, emptyList()), Message("local", "user", 2, emptyList()))
    val merged = mergeTranscript(existing, listOf(Message("m1", "user", 1, emptyList())), listOf(Message("queued", "user", 3, emptyList())))
    assertEquals(listOf("m1", "local", "queued"), merged.map { it.id })
  }

  @Test fun aLateReadPreservesChangedMessagesButStillAdmitsServerCorrectionsAndNewIds() {
    val prompt = Message("u", "user", 1, listOf(MessagePart("u:text", "text", text = "prompt")))
    val assistant = Message("a", "assistant", 2, listOf(
      MessagePart("a:text:0", "text", text = "partial"), MessagePart("call", "tool", tool = "read", status = "running")))
    val start = listOf(prompt, assistant)
    val live = start
      .apply("session.text.delta", """{"sessionID":"s","assistantMessageID":"a","delta":" answer"}""")
      .apply("session.tool.success", """{"sessionID":"s","assistantMessageID":"a","id":"call","content":[{"type":"text","text":"done"}]}""")
    val corrected = prompt.copy(parts = listOf(MessagePart("u:text", "text", text = "server correction")))
    val discovered = Message("new", "user", 3, emptyList())
    val fetched = listOf(corrected, assistant, discovered)
    val merged = mergeTranscript(live, fetched, readStart = start.associateBy { it.id })
    assertEquals(listOf(corrected, live.last(), discovered), merged)
    assertEquals("partial answer", merged[1].parts[0].text)
    assertEquals("completed", merged[1].parts[1].status)
    // The UI merge must not mutate the wire-derived values later used by TranscriptSync.absorb.
    assertEquals("running", fetched[1].parts[1].status)
    assertEquals("partial", fetched[1].parts[0].text)
  }

  @Test fun aSecondMergeAtCommitProtectsEventsAfterTheFirstMerge() {
    val start = listOf(Message("a", "assistant", 1, listOf(MessagePart("a:text:0", "text", text = "start"))))
    val baseline = start.associateBy { it.id }
    val fetched = start.map { it.copy(parts = listOf(MessagePart("a:text:0", "text", text = "http"))) }
    val prepared = mergeTranscript(start, fetched, readStart = baseline)
    val later = start.apply("session.text.delta", """{"sessionID":"s","assistantMessageID":"a","delta":" live"}""") +
      Message("local", "user", 2, emptyList())
    val committed = mergeTranscript(later, prepared, readStart = baseline)
    assertEquals(later, committed)
    // On the next read this entry is unchanged: the server can reconcile it instead of being starved.
    assertEquals("http", mergeTranscript(committed, fetched, readStart = committed.associateBy { it.id }).first().parts.single().text)
  }

  @Test fun sameIdCreatedWhileAnInitialReadIsWideningKeepsItsLiveVersion() {
    val fetched = Message("a", "assistant", 2, listOf(MessagePart("a:text:0", "text", text = "old")))
    val live = listOf(fetched).apply("session.text.delta", """{"sessionID":"s","assistantMessageID":"a","delta":" new"}""")
    val older = Message("u", "user", 1, emptyList())
    assertEquals(listOf(older, live.single()), mergeTranscript(live, listOf(older, fetched), readStart = emptyMap()))
  }

  @Test fun needsOlderTurnRootOnlyWhenTheWindowStartsMidTurn() {
    assertTrue(needsOlderTurnRoot(listOf(Message("a", "assistant", 2, emptyList()))))
    assertFalse(needsOlderTurnRoot(listOf(Message("u", "user", 1, emptyList()), Message("a", "assistant", 2, emptyList()))))
    assertFalse(needsOlderTurnRoot(listOf(Message("s", "shell", 1, emptyList()), Message("a", "assistant", 2, emptyList()))))
    assertFalse(needsOlderTurnRoot(emptyList()))
  }
}
