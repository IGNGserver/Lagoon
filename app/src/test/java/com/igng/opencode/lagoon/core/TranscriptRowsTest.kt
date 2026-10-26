package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRowsTest {
  private val t0 = 1_700_000_000_000L

  private fun part(
    id: String, type: String, tool: String = "", text: String = "", input: String = "", output: String = "",
    status: String = "completed", patch: String = "", error: String = "", files: List<String> = emptyList()
  ) = MessagePart(id, type, text = text, tool = tool, status = status, input = input, output = output, patch = patch, error = error, files = files)

  private fun user(id: String, created: Long, text: String = "你好") =
    Message(id, "user", created, listOf(part("$id:text", "text", text = text)))

  private fun assistant(id: String, created: Long, vararg parts: MessagePart, completedAt: Long? = created + 1_000) =
    Message(id, "assistant", created, parts.toList(), completedAt = completedAt)

  private fun tool(id: String, tool: String, input: String = "", output: String = "", status: String = "completed", patch: String = "") =
    part(id, "tool", tool = tool, input = input, output = output, status = status, patch = patch)

  @Test fun userAndAssistantMessagesFormOneTurnWithSingleUserRow() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "答案"))))
    assertEquals(1, rows.count { it.kind == "user" })
    assertEquals(1, rows.count { it.kind == "text" })
    assertEquals(1, rows.count { it.kind == "time" })
  }

  @Test fun secondUserStartsNewTurnAndTimeSeparatorNeedsAGap() {
    val close = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "x")), user("u2", t0 + 60_000)))
    assertEquals(2, close.count { it.kind == "user" })
    assertEquals(1, close.count { it.kind == "time" })
    val far = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "x")), user("u2", t0 + 3_600_000)))
    assertEquals(2, far.count { it.kind == "time" })
  }

  @Test fun mixedToolCallsBetweenTextsFoldIntoOneGroup() {
    val parts = listOf(
      tool("t1", "read", input = """{"filePath":"a.kt"}"""),
      tool("t2", "glob", input = """{"pattern":"*.kt"}"""),
      tool("t3", "grep", input = """{"pattern":"foo"}"""),
      tool("t4", "read", input = """{"filePath":"b.kt"}""")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    assertEquals(listOf("共处理 4 个操作"), rows.filter { it.kind == "tool-group" }.map { it.title })
    assertTrue(rows.none { it.kind == "tool" })
  }

  @Test fun toolAndReasoningBetweenTextsShareOneGroup() {
    val parts = listOf(
      part("r1", "reasoning", text = "先看看"),
      tool("t1", "grep", input = """{"pattern":"foo"}"""),
      tool("t2", "bash", input = """{"command":"ls"}"""),
      tool("t3", "bash", input = """{"command":"pwd"}"""),
      part("r2", "reasoning", text = "再总结")
    )
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray()))
    val rows = TranscriptRows.build(messages)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("共处理 5 个操作", group.title)
    val details = TranscriptRows.details(messages, group.key)
    assertEquals(3, details.count { it.kind == "tool" })
    assertEquals(2, details.count { it.kind == "reasoning" })
  }

  @Test fun textAndOperationsStayInOnePromptGroup() {
    val parts = listOf(
      tool("t1", "bash", input = """{"command":"ls"}"""),
      part("x1", "text", text = "先执行了第一步"),
      tool("t2", "read", input = """{"filePath":"a.kt"}""")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    assertEquals(listOf("共处理 2 个操作"), rows.filter { it.kind == "tool-group" }.map { it.title })
  }

  @Test fun processOutputStaysChronologicalInTheActivityTimelineAndFinalTextStaysInTranscript() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      part("p1", "text", text = "先检查现状"),
      tool("t1", "grep", input = """{"pattern":"TODO"}"""),
      part("p2", "text", text = "找到待办项，继续读取"),
      tool("t2", "read", input = """{"filePath":"src/Main.kt"}"""),
      part("p3", "text", text = "整理后的最终回答")))
    val rows = TranscriptRows.build(messages)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("共处理 2 个操作", group.title)
    assertEquals("整理后的最终回答", rows.single { it.kind == "text" }.text)

    val details = TranscriptRows.details(messages, group.key)
    val timeline = details.filter { it.bodyOf == null }
    assertEquals(listOf("process-output", "tool", "process-output", "tool"), timeline.map { it.kind })
    assertEquals(listOf("先检查现状", "找到待办项，继续读取"), timeline.filter { it.kind == "process-output" }.map { it.text })
  }

  @Test fun toolAttachmentsRemainAvailableInTheActivityDetails() {
    val image = Attachment("data:image/png;base64,AA==", "image/png", "screenshot.png")
    val imageTool = tool("t1", "read", input = """{"filePath":"/tmp/screenshot.png"}""").copy(attachments = listOf(image))
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, imageTool))
    val group = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val detail = TranscriptRows.details(messages, group.key).single { it.kind == "tool" }
    assertEquals(listOf(image), detail.attachments)
    assertEquals(imageTool, detail.source)
  }

  @Test fun textFromToolCallAssistantMessageIsProcessOutputNotFinalAnswer() {
    val messages = listOf(user("u1", t0),
      assistant("a1", t0 + 500, part("p1", "text", text = "即将调用工具"), tool("t1", "bash", input = """{"command":"pwd"}""")).copy(finish = "tool-calls"),
      assistant("a2", t0 + 900, part("p2", "text", text = "最终结果")))
    val rows = TranscriptRows.build(messages)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("最终结果", rows.single { it.kind == "text" }.text)
    assertTrue(TranscriptRows.details(messages, group.key).any { it.kind == "process-output" && it.text == "即将调用工具" })
  }

  @Test fun hiddenAndPendingToolsProduceNoRows() {
    val parts = listOf(
      tool("t1", "todowrite", input = """{"todos":[]}"""),
      tool("t2", "question", status = "running"),
      tool("t3", "question", status = "completed")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    assertEquals(1, rows.count { it.kind == "tool-group" })
    assertEquals("共处理 1 个操作", rows.first { it.kind == "tool-group" }.title)
  }

  @Test fun emptyMessagesProduceNoRows() {
    val rows = TranscriptRows.build(listOf(Message("m1", "assistant", t0, listOf(part("p1", "text", text = "  ")))))
    assertTrue(rows.isEmpty())
  }

  @Test fun aRunOfSameCategoryToolsFoldsIntoOneGroup() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *(1..8).map { tool("t$it", "bash", input = """{"command":"cmd$it"}""", output = "ok") }.toTypedArray()))
    val rows = TranscriptRows.build(messages)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("共处理 8 个操作", group.title)
    assertTrue(rows.none { it.kind == "tool" })
    assertEquals(8, TranscriptRows.details(messages, group.key).count { it.kind == "tool" })
  }

  @Test fun halfSheetCarriesToolTitleSubtitleAndBody() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "bash", input = """{"command":"ls -la"}""", output = "file1\nfile2")))
    val group = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val details = TranscriptRows.details(messages, group.key)
    val item = details.single { it.kind == "tool" }
    assertEquals("命令行", item.title)
    assertEquals("ls -la", item.subtitle)
    val body = details.filter { it.kind == "tool-body" }
    assertTrue(body.isNotEmpty())
    // The body is attached to the item header so the sheet can keep it collapsed until tapped.
    assertEquals(item.key, body.first().bodyOf)
    assertEquals("命令", body.first().title)
    assertEquals("ls -la", body.first().text)
    assertTrue(body.any { it.title == "输出" && it.text.contains("file1") })
  }

  @Test fun readToolUsesFilenameAsSubtitle() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "read", input = """{"filePath":"src/Main.kt"}""")))
    val group = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val item = TranscriptRows.details(messages, group.key).first { it.kind == "tool" }
    assertEquals("读取文件", item.title)
    assertEquals("Main.kt", item.subtitle)
  }

  @Test fun combinedGroupCarriesEveryCompactItem() {
    val parts = listOf(tool("t1", "read", input = """{"filePath":"a.kt"}"""), tool("t2", "grep", input = """{"pattern":"foo","include":"*.kt"}"""))
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray()))
    val base = TranscriptRows.build(messages)
    assertTrue(base.none { it.kind == "tool" })
    val group = base.single { it.kind == "tool-group" }
    assertEquals("共处理 2 个操作", group.title)
    val searchItem = TranscriptRows.details(messages, group.key).first { it.kind == "tool" && it.title == "搜索内容" }
    assertTrue(searchItem.args.contains("include=*.kt"))
  }

  @Test fun diffSummaryAggregatesEditedFiles() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"))))
    val summary = rows.single { it.kind == "diff-summary" }
    assertTrue(summary.title.contains("1 个文件"))
    val expanded = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"))), setOf(summary.key))
    assertTrue(expanded.any { it.kind == "diff-file" && it.title == "a.kt" })
  }

  @Test fun thinkingRowOnlyWhileWorkingWithoutAssistantContent() {
    val pending = TranscriptRows.build(listOf(user("u1", t0)), working = true)
    assertTrue(pending.any { it.kind == "thinking" })
    val done = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "x"))), working = true)
    assertTrue(done.none { it.kind == "thinking" })
  }

  @Test fun runningTurnOmitsTheStaticDuration() {
    // Between steps every existing assistant message may already be "completed"; showing a frozen 用时
    // there reads as “已结束”, so the running turn must not render it.
    val messages = listOf(user("u1", t0), assistant("a1", t0, part("p1", "text", text = "x"), completedAt = t0 + 5_000))
    assertFalse(TranscriptRows.build(messages, working = true).single { it.kind == "meta" }.meta.contains("用时"))
    assertTrue(TranscriptRows.build(messages, working = false).single { it.kind == "meta" }.meta.contains("用时 5 秒"))
  }

  @Test fun metaRowCarriesDurationAndCopyText() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0, part("p1", "text", text = "答案"), completedAt = t0 + 5_000)))
    val meta = rows.single { it.kind == "meta" }
    assertTrue(meta.meta.contains("用时 5 秒"))
    assertEquals("答案", meta.copyText)
  }

  @Test fun errorEnvelopeIsUnwrappedAndAbortBecomesDivider() {
    assertEquals("boom", TranscriptRows.unwrapError("""{"error":{"type":"ApiError","message":"boom"}}"""))
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "x"), completedAt = null)
      .copy(finish = "aborted")))
    assertTrue(rows.any { it.kind == "divider" && it.text.contains("中断") })
    assertTrue(rows.none { it.kind == "error" })
  }

  private fun assertUniqueKeys(rows: List<TranscriptRow>) {
    val duplicates = rows.groupBy { it.key }.filterValues { it.size > 1 }.keys
    assertTrue("duplicate LazyColumn keys: $duplicates", duplicates.isEmpty())
  }

  @Test fun v2TextAndReasoningWithoutIdsInOneMessageGetDistinctKeys() {
    // Official V2 schema: Assistant.Text / Assistant.Reasoning carry no id field.
    val message = JSONObject("""{"id":"msg_a","type":"assistant","time":{"created":$t0},"agent":"build","model":{"id":"m","providerID":"p"},
      "content":[{"type":"reasoning","text":"想一想"},{"type":"text","text":"答案"},{"type":"text","text":"补充"}]}""").toMessage()
    assertEquals(listOf("msg_a:reasoning:0", "msg_a:text:0", "msg_a:text:1"), message.parts.map { it.id })
    val rows = TranscriptRows.build(listOf(user("u1", t0), message))
    assertEquals(1, rows.count { it.kind == "tool-group" })
    assertEquals(1, TranscriptRows.details(listOf(user("u1", t0), message), rows.single { it.kind == "tool-group" }.key).count { it.kind == "reasoning" })
    assertEquals(1, rows.count { it.kind == "text" })
    assertEquals("答案\n\n补充", rows.single { it.kind == "text" }.text)
    assertUniqueKeys(rows)
  }

  @Test fun idlessPartsAreKeyedByPositionWhenTheParserDidNotAssignIds() {
    val rows = TranscriptRows.build(listOf(user("u1", t0),
      assistant("a1", t0 + 500, part("", "reasoning", text = "r"), part("", "text", text = "x"), part("", "text", text = "y"))))
    assertEquals(1, rows.count { it.kind == "text" })
    assertEquals("x\n\ny", rows.single { it.kind == "text" }.text)
    assertEquals(1, rows.count { it.kind == "tool-group" })
    assertUniqueKeys(rows)
  }

  @Test fun toolCallIdReusedAcrossMessagesOfOneTurnDoesNotCollide() {
    // Real data: the provider returned call_1966253 twice within one turn, each after a reasoning part.
    val messages = listOf(user("u1", t0),
      assistant("a1", t0 + 500, part("a1#0", "reasoning", text = "查找"), tool("call_1966253", "grep", input = """{"pattern":"x"}""")),
      assistant("a2", t0 + 900, part("a2#0", "reasoning", text = "再查"), tool("call_1966253", "grep", input = """{"pattern":"y"}""")),
      assistant("a3", t0 + 1_300, tool("call_7", "bash", input = """{"command":"ls"}"""), tool("call_7", "bash", input = """{"command":"pwd"}""")))
    val rows = TranscriptRows.build(messages)
    // One group for the whole span between user text and the turn end, even though call ids repeat.
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("共处理 6 个操作", group.title)
    assertTrue(rows.none { it.kind == "tool" })
    assertUniqueKeys(rows)
    val details = TranscriptRows.details(messages, group.key)
    assertEquals(4, details.count { it.kind == "tool" })
    assertEquals(2, details.count { it.kind == "reasoning" })
    assertUniqueKeys(details)
  }

  @Test fun duplicateMessagesStillProduceUniqueKeys() {
    val answer = assistant("a1", t0 + 500, part("p1", "text", text = "答案"), tool("t1", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"))
    val rows = TranscriptRows.build(listOf(user("u1", t0), answer, answer, user("u1", t0 + 4_000_000), answer))
    assertUniqueKeys(rows)
    val summary = rows.first { it.kind == "diff-summary" }
    assertUniqueKeys(TranscriptRows.build(listOf(user("u1", t0), answer, answer), setOf(summary.key)))
  }

  /**
   * A history page that starts mid-turn, then the older page that completes it: every row already on
   * screen keeps its key, so LazyColumn keeps its scroll anchor. Only the split turn's grouping rows
   * (its time separator, keyed by the turn) may be replaced; its answer, notices, process output and
   * tail (meta/diff/error) are keyed by their own message or part.
   */
  @Test fun completingASplitTurnKeepsTheKeysOfRowsAlreadyShown() {
    val messages = listOf(
      user("u1", t0),
      assistant("a1", t0 + 100, part("a1r", "reasoning", text = "想一想"), tool("a1t", "bash", input = """{"command":"ls"}"""), completedAt = t0 + 150),
      Message("n1", "notice", t0 + 160, emptyList(), notice = Notice("切换智能体")),
      assistant("a2", t0 + 200, part("a2p", "text", text = "中途说明"), tool("a2t", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"), completedAt = t0 + 250),
      assistant("a3", t0 + 300, part("a3p", "text", text = "最终答案"), completedAt = t0 + 400),
      user("u2", t0 + 60_000),
      assistant("b1", t0 + 60_100, tool("b1t", "read", input = """{"filePath":"b.kt"}""", output = "x"), completedAt = t0 + 60_200),
      assistant("b2", t0 + 60_300, part("b2p", "text", text = "第二轮答案"), completedAt = t0 + 60_400),
      user("u3", t0 + 120_000),
      assistant("c1", t0 + 120_100, part("c1p", "text", text = "进行中"), completedAt = null)
    )
    val full = TranscriptRows.build(messages, working = true)
    val fullKeys = full.map { it.key }.toSet()
    // Every possible mid-turn cut of the first two turns, finished or still running.
    for (cut in 1 until messages.indexOfFirst { it.id == "u3" }) {
      val half = TranscriptRows.build(messages.drop(cut), working = true)
      val kept = half.filterNot { it.kind == "time" || it.kind == "tool-group" || it.kind == "thinking" }
      val moved = kept.filter { it.key !in fullKeys }
      assertTrue("cut at $cut re-keyed ${moved.map { it.key }}", moved.isEmpty())
    }
    // Rows of turns the cut did not touch are identical, not just equal in key.
    val half = TranscriptRows.build(messages.drop(2), working = true)
    val untouched = half.drop(half.indexOfFirst { it.key == "user:u2" })
    assertTrue(untouched.size > 3)
    untouched.forEach { row -> assertEquals(full.single { it.key == row.key }, row) }
  }

  @Test fun aRunningBatchKeepsItsKeyAsCallsJoinIt() {
    val first = listOf(user("u1", t0), assistant("a1", t0 + 500, tool("t1", "bash", input = """{"command":"ls"}""", status = "running"), completedAt = null))
    val key = TranscriptRows.build(first, working = true).single { it.kind == "tool-group" }.key
    val grown = listOf(user("u1", t0), assistant("a1", t0 + 500, tool("t1", "bash", input = """{"command":"ls"}"""),
      tool("t2", "read", input = """{"filePath":"a.kt"}""", status = "running"), completedAt = null))
    assertEquals(key, TranscriptRows.build(grown, working = true).single { it.kind == "tool-group" }.key)
  }

  @Test fun keysAreStableAcrossRebuildsSoExpansionSurvivesUpdates() {
    val first = listOf(user("u1", t0), assistant("a1", t0 + 500, part("a1#0", "reasoning", text = "r"), tool("call_1", "bash", input = """{"command":"ls"}""", output = "x")))
    val groupKey = TranscriptRows.build(first).single { it.kind == "tool-group" }.key
    val grown = first + assistant("a2", t0 + 900, part("a2#0", "text", text = "完成"))
    val rebuilt = TranscriptRows.build(grown, setOf(groupKey))
    assertEquals(groupKey, rebuilt.single { it.kind == "tool-group" }.key)
    assertTrue(rebuilt.any { it.kind == "tool-body" })
  }

  @Test fun runningTurnShowsOnlyProcessReportsAndCollapsedToolGroups() {
    val pending = tool("call_1", "bash", input = """{"command":"pwd"}""", status = "running")
    val running = listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "先确认目录"), pending, completedAt = null))
    val live = TranscriptRows.build(running, working = true)
    // 运行中只内联过程报告；调用与思考折叠成一个小字分组，不再展开。
    assertEquals("先确认目录", live.single { it.kind == "process-output" }.text)
    val batch = live.single { it.kind == "tool-group" }
    assertEquals("调用了 1 次工具", batch.title)
    assertEquals("running", batch.status)
    assertTrue(live.none { it.kind == "tool" })
    assertEquals(pending, TranscriptRows.details(running, batch.key, working = true).single { it.kind == "tool" }.source)

    // 两段过程报告之间的所有调用合并为一组。
    val mixed = listOf(user("u1", t0), assistant("a1", t0 + 500,
      part("p1", "text", text = "第一段"),
      tool("t1", "grep", input = """{"pattern":"a"}"""),
      tool("t2", "read", input = """{"filePath":"a.kt"}"""),
      part("p2", "text", text = "第二段"),
      tool("t3", "bash", input = """{"command":"ls"}"""),
      completedAt = null))
    val split = TranscriptRows.build(mixed, working = true)
    assertEquals(listOf("第一段", "第二段"), split.filter { it.kind == "process-output" }.map { it.text })
    assertEquals(listOf("调用了 2 次工具", "调用了 1 次工具"), split.filter { it.kind == "tool-group" }.map { it.title })

    val finishedTool = pending.copy(status = "completed", output = "/workspace")
    val finished = listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "先确认目录"), finishedTool),
      assistant("a2", t0 + 1_500, part("answer", "text", text = "已确认工作目录")))
    val rows = TranscriptRows.build(finished)
    // 完成后：整轮折叠为「共处理 N 个操作」+ 最终回复。
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("共处理 1 个操作", group.title)
    assertEquals("completed", group.status)
    assertEquals("已确认工作目录", rows.single { it.kind == "text" }.text)
    assertTrue(rows.none { it.kind == "process-output" })
    val details = TranscriptRows.details(finished, group.key)
    assertEquals(finishedTool, details.single { it.kind == "tool" }.source)
  }

  /** 运行中的一轮里只有真正还在执行的批次转圈；已完成的调用保持完成态，不再误导成“仍在运行”。 */
  @Test fun runningTurnSpinsOnlyTheBatchThatIsStillExecuting() {
    val done = tool("t1", "bash", input = """{"command":"pwd"}""")
    val live = tool("t2", "read", input = """{"filePath":"a.kt"}""", status = "running")
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500,
      done, part("p1", "text", text = "先看目录"), live, completedAt = null)), working = true)
    val groups = rows.filter { it.kind == "tool-group" }
    assertEquals(listOf("调用了 1 次工具", "调用了 1 次工具"), groups.map { it.title })
    assertEquals(listOf("completed", "running"), groups.map { it.status })
  }

  @Test fun unverifiedCachedActivityHasNoRunningOrCompletedProjectionForActiveParts() {
    val active = listOf(
      tool("t1", "bash", input = """{"command":"pwd"}""", output = "cached output", status = "running"),
      tool("t2", "read", status = "pending"),
      part("r1", "reasoning", text = "cached reasoning", status = "running"),
      part("r2", "reasoning", text = "queued reasoning", status = "pending")
    )
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *active.toTypedArray(), completedAt = null))
    val rows = TranscriptRows.build(messages, working = false, unverified = true)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("unverified", group.status)
    val expanded = TranscriptRows.build(messages, setOf(group.key), unverified = true)
    val details = TranscriptRows.details(messages, group.key, unverified = true)
    assertEquals(expanded.filter { it.detailOf == group.key }, details)
    val headers = details.filter { it.kind in setOf("tool", "reasoning") }
    assertEquals(4, headers.size)
    assertTrue(headers.all { it.status == "unverified" })
    assertEquals(active, headers.map { it.source })
    assertEquals(listOf("running", "pending", "running", "pending"), messages.last().parts.map { it.status })
    assertTrue(details.any { it.kind == "tool-body" && it.text == "cached output" })
    assertTrue(details.any { it.kind == "reasoning-body" && it.text == "cached reasoning" })
    assertTrue((rows + expanded + details).none { it.status in setOf("running", "pending") || it.kind == "thinking" })
  }

  @Test fun verifiedActiveToolsKeepTheirSpinnerStatusesEvenWhenWorkingIsFalse() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "bash", status = "running"), tool("t2", "read", status = "pending"),
      part("r1", "reasoning", text = "thinking", status = "running"), completedAt = null))
    for (working in listOf(false, true)) {
      val group = TranscriptRows.build(messages, working = working).single { it.kind == "tool-group" }
      assertEquals("running", group.status)
      val details = TranscriptRows.details(messages, group.key, working = working)
      assertEquals(listOf("running", "pending", "running"), details.filter { it.bodyOf == null }.map { it.status })
    }
  }

  @Test fun unverifiedActivityPreservesCompletedCallsWarningSummariesAndTrueErrors() {
    val diagnostic = "Error: command failed"
    val failed = part("t1", "tool", tool = "bash", status = "error", error = diagnostic)
    val done = tool("t2", "read")
    val pending = tool("t3", "read", status = "pending")
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, failed, done, pending, completedAt = null)
      .copy(error = "Error: provider unavailable", errorType = "provider"))
    val rows = TranscriptRows.build(messages, unverified = true)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("unverified", group.status)
    assertEquals("含 1 条异常命令", group.subtitle)
    assertEquals("provider unavailable", rows.single { it.kind == "error" }.text)
    val details = TranscriptRows.details(messages, group.key, unverified = true)
    assertEquals(listOf("error", "completed", "unverified"), details.filter { it.kind == "tool" }.map { it.status })
    assertEquals(listOf(failed, done, pending), details.filter { it.kind == "tool" }.map { it.source })
    assertEquals(diagnostic, details.single { it.title == "错误" }.text)
    val finished = listOf(user("u1", t0), assistant("a1", t0 + 500, failed, done))
    assertEquals(TranscriptRows.build(finished), TranscriptRows.build(finished, unverified = true))
  }

  @Test fun unverifiedStandaloneShellDoesNotPretendToRunOrFinish() {
    val active = tool("t1", "bash", input = """{"command":"pwd"}""", status = "running")
    val messages = listOf(Message("sh1", "shell", t0, listOf(active)))
    val row = TranscriptRows.build(messages, unverified = true).single { it.kind == "tool" }
    assertEquals("unverified", row.status)
    assertEquals(active, row.source)
    assertTrue(TranscriptRows.details(messages, row.key, unverified = true).any { it.text == "pwd" })
    assertEquals("running", TranscriptRows.build(messages).single { it.kind == "tool" }.status)
  }

  @Test fun unverifiedOverridesWorkingWithoutInventingLivePlaceholderOrRetry() {
    assertTrue(TranscriptRows.build(listOf(user("u1", t0)), working = true, unverified = true).none { it.kind == "thinking" })
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "bash", status = "running"), completedAt = null).copy(retry = "cached retry"))
    val rows = TranscriptRows.build(messages, working = true, unverified = true)
    assertEquals("unverified", rows.single { it.kind == "tool-group" }.status)
    assertTrue(rows.none { it.status in setOf("running", "pending") || it.kind == "thinking" })
  }

  @Test fun completedGroupSummarizesCommandFailureButDetailsKeepTheRawDiagnostic() {
    val diagnostic = "Error: {\"message\":\"退出码 1\"}"
    val failed = part("t1", "tool", tool = "bash", input = """{"command":"false"}""",
      output = "command output", status = "error", error = diagnostic)
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, failed))
    val rows = TranscriptRows.build(messages)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("warning", group.status)
    assertEquals("含 1 条异常命令", group.subtitle)
    assertEquals("1 个Shell", group.meta)
    assertTrue(rows.none { it.kind == "error" })

    val details = TranscriptRows.details(messages, group.key)
    val tool = details.single { it.kind == "tool" }
    assertEquals("error", tool.status)
    assertEquals(failed, tool.source)
    val error = details.single { it.kind == "tool-body" && it.title == "错误" }
    assertEquals(diagnostic, error.text)
    assertEquals(diagnostic, error.copyText)
    assertEquals(tool.key, error.bodyOf)
    assertTrue(details.any { it.title == "输出" && it.text == "command output" })
  }

  @Test fun groupCountsCommandAndOtherToolFailuresWithoutPreviewingTheirErrors() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      part("t1", "tool", tool = "shell", status = "error", error = "退出码 1"),
      part("t2", "tool", tool = "execute", status = "error"),
      part("t3", "tool", tool = "read", status = "error", error = "private diagnostic"),
      tool("t4", "bash")))
    val group = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    assertEquals("warning", group.status)
    assertEquals("含 2 条异常命令、1 条异常调用", group.subtitle)

    val onlyRead = listOf(user("u1", t0), assistant("a1", t0 + 500,
      part("t1", "tool", tool = "read", status = "error", error = "not found")))
    assertEquals("含 1 条异常调用", TranscriptRows.build(onlyRead).single { it.kind == "tool-group" }.subtitle)
  }

  @Test fun aLargeActiveGroupStaysRunningDespiteHistoricalCommandFailures() {
    val tools = (1..129).map { index ->
      part("t$index", "tool", tool = "bash", status = if (index == 1) "error" else "completed",
        error = if (index == 1) "退出码 1" else "")
    } + tool("t130", "read", status = "running")
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *tools.toTypedArray(), completedAt = null))
    val rows = TranscriptRows.build(messages, working = true)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("调用了 130 次工具", group.title)
    assertEquals("running", group.status)
    assertEquals("含 1 条异常命令", group.subtitle)
    assertTrue(rows.none { it.kind == "error" })
    assertEquals(130, TranscriptRows.details(messages, group.key, working = true).count { it.kind == "tool" })
  }

  @Test fun completedFailedBatchDoesNotTurnRedWhileALaterBatchRuns() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      part("t1", "tool", tool = "bash", status = "error", error = "退出码 1"),
      part("p1", "text", text = "继续检查"),
      tool("t2", "read", status = "pending"), completedAt = null))
    val groups = TranscriptRows.build(messages, working = true).filter { it.kind == "tool-group" }
    assertEquals(listOf("warning", "running"), groups.map { it.status })
    assertEquals("含 1 条异常命令", groups.first().subtitle)
  }

  @Test fun neutralToolFailureSummaryDoesNotHideATrueAssistantError() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      part("t1", "tool", tool = "bash", status = "error", error = "退出码 1"))
      .copy(error = "Error: provider unavailable", errorType = "provider"))
    val rows = TranscriptRows.build(messages)
    assertEquals("warning", rows.single { it.kind == "tool-group" }.status)
    assertEquals("provider unavailable", rows.single { it.kind == "error" }.text)
  }

  @Test fun openingOneGroupDoesNotIncludeAnotherTurnsDetailsOrNotices() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 1, tool("t1", "bash", input = """{"command":"pwd"}""")),
      notice("n1", t0 + 2, "skill", """"skill":"test","name":"testing","text":"private instructions" """),
      user("u2", t0 + 3), assistant("a2", t0 + 4, tool("t2", "read", input = """{"filePath":"b.kt"}""")))
    val groups = TranscriptRows.build(messages).filter { it.kind == "tool-group" }
    val details = TranscriptRows.details(messages, groups.first().key)
    assertTrue(details.all { it.detailOf == groups.first().key })
    assertEquals(listOf("命令行"), details.filter { it.kind == "tool" }.map { it.title })
    assertTrue(details.none { it.kind == "note" })
  }

  @Test fun readAndEditDetailsKeepPathsAndOriginalAndReplacementText() {
    val read = tool("t1", "read", input = """{"filePath":"src/Main.kt"}""", output = "val x = 1")
    assertEquals(listOf("文件路径", "内容"), TranscriptRows.toolSections(read).map { it.label })
    assertEquals("src/Main.kt", TranscriptRows.toolSections(read).first().text)
    val edit = tool("t2", "edit", input = """{"path":"src/Main.kt","oldString":"val x = 1","newString":"val x = 2"}""")
    assertEquals(listOf("文件路径", "原内容", "新内容"), TranscriptRows.toolSections(edit).map { it.label })
    assertEquals(listOf("src/Main.kt", "val x = 1", "val x = 2"), TranscriptRows.toolSections(edit).map { it.text })
    val write = tool("t3", "write", input = """{"path":"src/New.kt","content":"val y = 3"}""")
    assertEquals(listOf("文件路径", "内容"), TranscriptRows.toolSections(write).map { it.label })
  }

  @Test fun workingPlaceholderSaysRunningNotThinking() {
    val pending = TranscriptRows.build(listOf(user("u1", t0)), working = true).single { it.kind == "thinking" }
    assertEquals("运行中…", pending.text)
  }

  @Test fun errorRowShowsReadableMessage() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), Message("a1", "assistant", t0 + 500, listOf(part("p1", "text", text = "x")), error = "Error: boom")))
    assertEquals(1, rows.count { it.kind == "error" })
    assertTrue(rows.first { it.kind == "error" }.text.contains("boom"))
    assertFalse(rows.first { it.kind == "error" }.text.startsWith("Error:"))
  }

  private fun notice(id: String, created: Long, type: String, json: String) = JSONObject("""{"id":"$id","type":"$type","time":{"created":$created},$json}""").toMessage()

  @Test fun modelFacingRecordsRenderAsOneLineNoticesNotReplies() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 100, part("p1", "text", text = "开始")),
      notice("s1", t0 + 200, "system", """"text":"tools.playwright.browser_close(): Promise<unknown>","description":"Instructions updated: mcp:playwright","metadata":{"notice":"instructions","instructionSources":["mcp:playwright"]}"""),
      notice("k1", t0 + 300, "skill", """"skill":"sk","name":"release","text":"# SKILL.md 全文" """),
      notice("y1", t0 + 400, "synthetic", """"text":"model only" """))
    val rows = TranscriptRows.build(messages)
    val notes = rows.filter { it.kind == "note" }
    assertEquals(listOf("指令已更新", "技能"), notes.map { it.title })
    assertEquals("mcp:playwright", notes[0].text); assertEquals("release", notes[1].text)
    assertEquals(listOf("开始"), rows.filter { it.kind == "text" }.map { it.text })
    assertTrue(rows.none { it.text.contains("Promise<unknown>") || it.text.contains("SKILL.md") || it.text.contains("model only") })
  }

  @Test fun shellCommandsFormTheirOwnTurn() {
    val shell = JSONObject("""{"id":"sh1","type":"shell","shellID":"sh_1","command":"ls","status":"exited","exit":2,"output":{"output":"nope","cursor":4,"size":4,"truncated":false},"time":{"created":${t0 + 10}}}""").toMessage()
    val rows = TranscriptRows.build(listOf(user("u1", t0), shell, assistant("a1", t0 + 20, part("p1", "text", text = "后续"))))
    assertEquals(1, rows.count { it.kind == "user" })
    val tool = rows.single { it.kind == "tool" }
    assertEquals("命令行", tool.title); assertEquals("ls", tool.subtitle); assertEquals("error", tool.status)
    // The assistant after a shell turn starts its own turn instead of joining the shell.
    assertEquals(2, rows.count { it.kind == "meta" } + rows.count { it.kind == "tool" })
  }

  @Test fun onlyTheLastAssistantErrorIsShownAndInterruptionsAreDividers() {
    val first = Message("a1", "assistant", t0 + 1, listOf(part("p1", "text", text = "x")), error = "rate limited", errorType = "provider", completedAt = t0 + 2)
    val last = Message("a2", "assistant", t0 + 3, listOf(part("p2", "text", text = "y")), error = "Interrupted by user", errorType = "session.interrupted", completedAt = t0 + 4)
    val rows = TranscriptRows.build(listOf(user("u1", t0), first, last))
    assertTrue(rows.none { it.kind == "error" })
    assertEquals(1, rows.count { it.kind == "divider" && it.text == "本轮已中断" })
    val failing = TranscriptRows.build(listOf(user("u1", t0), first.copy(error = null), last.copy(error = "boom", errorType = "provider")))
    assertEquals(listOf("boom"), failing.filter { it.kind == "error" }.map { it.text })
  }

  @Test fun aStagedRevertHidesItsBoundaryAndEverythingAfter() {
    val messages = listOf(user("msg_1", t0), assistant("msg_2", t0 + 1, part("p", "text", text = "a")), user("msg_3", t0 + 2, "撤销我"), assistant("msg_4", t0 + 3, part("q", "text", text = "b")))
    val rows = TranscriptRows.build(messages, revertMessageId = "msg_3")
    assertEquals(1, rows.count { it.kind == "user" })
    assertTrue(rows.none { it.text == "撤销我" || it.text == "b" })
  }

  @Test fun queuedInputIsMarkedAndSubagentToolsLinkToTheirChild() {
    val queued = user("msg_q", t0 + 5, "下一步").copy(queued = true)
    val subagent = part("call_s", "tool", tool = "subagent", input = """{"agent":"explore","description":"查找调用"}""", status = "running").copy(target = "ses_child")
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 1, subagent, completedAt = null), queued), working = true)
    assertTrue(rows.single { it.kind == "user" && it.text == "下一步" }.meta.startsWith("排队中"))
    val tool = rows.single { it.kind == "tool-group" }
    assertEquals("ses_child", tool.target); assertEquals("explore · 查找调用", tool.subtitle)
  }

  @Test fun editDiffsComeFromToolMetadata() {
    val edit = JSONObject("""{"type":"tool","id":"call_e","name":"edit","state":{"status":"completed","input":{"path":"src/a.kt","oldString":"a","newString":"b"},"content":[{"type":"text","text":"ok"}],
      "metadata":{"files":[{"file":"src/a.kt","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1,"status":"modified"}]}},"time":{"created":1}}""").toAssistantPart("call_e")
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 1, edit))
    val rows = TranscriptRows.build(messages)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("a.kt", TranscriptRows.details(messages, group.key).single { it.kind == "tool" }.subtitle)
    val summary = rows.single { it.kind == "diff-summary" }
    assertEquals("本轮改动 1 个文件", summary.title)
  }

  @Test fun halfSheetDetailsReturnTheGroupChildren() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, tool("t1", "bash", input = """{"command":"ls -la"}""", output = "file.txt")))
    val row = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val details = TranscriptRows.details(messages, row.key)
    assertTrue(details.isNotEmpty())
    assertTrue(details.all { it.detailOf == row.key })
    assertTrue(details.any { it.kind == "tool" && it.bodyOf == null })
    assertTrue(details.any { it.kind == "tool-body" && it.text.contains("ls -la") && it.bodyOf != null })
  }

  @Test fun halfSheetDetailsForContextGroupReturnItems() {
    val parts = listOf(tool("t1", "read", input = """{"filePath":"a.kt"}"""), tool("t2", "read", input = """{"filePath":"b.kt"}"""))
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray()))
    val group = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val details = TranscriptRows.details(messages, group.key)
    assertEquals(2, details.count { it.kind == "tool" })
    assertTrue(details.all { it.detailOf == group.key })
  }

  @Test fun halfSheetDetailsForDiffSummaryReturnFiles() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@\n-old\n+new")))
    val summary = TranscriptRows.build(messages).single { it.kind == "diff-summary" }
    val details = TranscriptRows.details(messages, summary.key)
    assertTrue(details.any { it.kind == "diff-file" && it.title == "a.kt" })
    assertTrue(details.any { it.kind == "tool-body" })
    assertTrue(details.all { it.detailOf == summary.key })
  }
}
