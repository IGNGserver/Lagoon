package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.Attachment
import com.igng.opencode.lagoon.core.Message
import com.igng.opencode.lagoon.core.MessagePart
import com.igng.opencode.lagoon.core.ModelChoice
import com.igng.opencode.lagoon.core.Notice
import com.igng.opencode.lagoon.core.TranscriptRow
import com.igng.opencode.lagoon.core.TranscriptRows
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * contract/transcript-rows.json pins the web client's TypeScript port of [TranscriptRows] to the Kotlin
 * original: this test checks the Kotlin output against it (UTC), web/src/transcript/rows.test.ts the
 * TypeScript one. Regenerate after a deliberate projection change with `UPDATE_CONTRACT=1`.
 */
class TranscriptRowsContractTest {
  private val file = File("contract/transcript-rows.json")

  @Test fun kotlinProjectionMatchesTheSharedContract() {
    if (System.getenv("UPDATE_CONTRACT") == "1") {
      file.parentFile.mkdirs()
      file.writeText(JSONObject().put("cases", JSONArray(cases().map(::evaluate))).toString(2) + "\n")
    }
    val fixture = JSONObject(file.readText()).getJSONArray("cases")
    for (i in 0 until fixture.length()) {
      val case = fixture.getJSONObject(i)
      val actual = evaluate(Case(case.getString("name"), messages(case.getJSONArray("messages")),
        strings(case.getJSONArray("expanded")).toSet(), case.getBoolean("working"),
        case.optString("revertMessageId").ifBlank { null }, case.optString("details").ifBlank { null }, case.optBoolean("unverified")))
      assertEquals(case.getString("name"), case.getJSONArray("rows").toString(2), actual.getJSONArray("rows").toString(2))
    }
  }

  @Test fun failedCallDetailsKeepRawDiagnosticsBehindTheWarningSummary() {
    val case = cases().first { it.name == "failed calls summarize diagnostics without failing the turn" }
    val group = TranscriptRows.build(case.messages).single { it.kind == "tool-group" }
    assertEquals("warning", group.status)
    assertEquals("含 1 条异常命令、1 条异常调用", group.subtitle)
    val details = TranscriptRows.details(case.messages, group.key)
    assertEquals(2, details.count { it.kind == "tool" && it.status == "error" })
    assertTrue(details.any { it.kind == "tool-body" && it.text == "exit 1" })
    assertTrue(details.any { it.kind == "tool-body" && it.text == "not found" })
  }

  private class Case(val name: String, val messages: List<Message>, val expanded: Set<String> = emptySet(), val working: Boolean = false,
    val revert: String? = null, val details: String? = null, val unverified: Boolean = false)

  private fun evaluate(case: Case): JSONObject {
    val rows = if (case.details != null) TranscriptRows.details(case.messages, case.details, case.working, case.revert, case.unverified)
      else TranscriptRows.build(case.messages, case.expanded, case.working, case.revert, case.unverified)
    return JSONObject().put("name", case.name).put("working", case.working).put("revertMessageId", case.revert ?: "")
      .put("unverified", case.unverified)
      .put("details", case.details ?: "").put("expanded", JSONArray(case.expanded.sorted()))
      .put("messages", JSONArray(case.messages.map(Codec::message))).put("rows", JSONArray(rows.map(::row)))
  }

  private fun row(row: TranscriptRow): JSONObject = JSONObject()
    .put("key", row.key).put("kind", row.kind).put("title", row.title).put("subtitle", row.subtitle).put("args", JSONArray(row.args))
    .put("status", row.status).put("text", row.text).put("attachments", JSONArray(row.attachments.map(Codec::attachment)))
    .put("meta", row.meta).put("copyText", row.copyText ?: JSONObject.NULL).put("target", row.target ?: JSONObject.NULL)
    .put("detailOf", row.detailOf ?: JSONObject.NULL).put("bodyOf", row.bodyOf ?: JSONObject.NULL)

  // ---- Inputs ----

  private val base = 1_767_225_600_000L // 2026-01-01T00:00:00Z
  private val model = ModelChoice("anthropic", "claude", "Claude")

  private fun text(id: String, value: String) = MessagePart(id, "text", text = value)
  private fun user(id: String, at: Long, value: String, queued: Boolean = false, files: List<MessagePart> = emptyList()) =
    Message(id, "user", base + at, listOf(text("$id:text", value)) + files, agent = "build", model = model, queued = queued, type = "user")
  private fun assistant(id: String, at: Long, parts: List<MessagePart>, completed: Long? = at + 5_000, finish: String? = "stop",
    error: String? = null, errorType: String? = null, retry: String? = null) =
    Message(id, "assistant", base + at, parts, error, "build", model, completed?.let { base + it }, finish, errorType, retry, type = "assistant")

  private fun cases(): List<Case> {
    val read = MessagePart("call_read", "tool", tool = "read", status = "completed", input = """{"path":"/repo/src/App.kt","offset":10,"limit":50}""", output = "fun main() {}")
    val edit = MessagePart("call_edit", "tool", tool = "edit", status = "completed", input = """{"path":"/repo/src/App.kt","oldString":"a","newString":"b"}""",
      patch = "--- a/src/App.kt\n+++ b/src/App.kt\n@@ -1 +1 @@\n-a\n+b", files = listOf("/repo/src/App.kt"))
    val thinking = MessagePart("m2:reasoning:0", "reasoning", text = "先读文件再改。")
    val finished = listOf(
      user("msg_1", 0, "把 a 改成 b", files = listOf(MessagePart("msg_1:file:0", "file", path = "data:image/png;base64,AAA", title = "shot.png", mime = "image/png"))),
      assistant("msg_2", 1_000, listOf(thinking, text("msg_2:text:0", "我先看看。"), read, edit, text("msg_2:text:1", "已经改好了。")), completed = 9_000)
    )
    val shell = MessagePart("call_sh", "tool", tool = "bash", status = "running", input = """{"command":"./gradlew test\necho done","description":"跑测试"}""")
    val running = listOf(
      user("msg_10", 0, "跑一下测试"),
      assistant("msg_11", 1_000, listOf(text("msg_11:text:0", "开始跑。"), shell, MessagePart("msg_11:reasoning:0", "reasoning", text = "等结果", status = "running")),
        completed = null, finish = null, retry = "第 1 次重试：overloaded")
    )
    val mixed = listOf(
      Message("msg_20", "notice", base, emptyList(), notice = Notice("指令已更新", items = listOf("AGENTS.md")), type = "system"),
      Message("msg_21", "shell", base + 1_000, listOf(MessagePart("msg_21:shell", "tool", tool = "shell", status = "error",
        input = """{"command":"ls /nope"}""", output = "No such file", error = "退出码 2", target = "sh_1")), completedAt = base + 2_000, type = "shell"),
      user("msg_22", 3 * 60 * 60 * 1000L, "继续"),
      assistant("msg_23", 3 * 60 * 60 * 1000L + 500, listOf(text("msg_23:text:0", "处理中")), finish = "aborted", errorType = "MessageAbortedError", error = "aborted"),
      user("msg_24", 3 * 60 * 60 * 1000L + 9_000, "再试一次"),
      assistant("msg_25", 3 * 60 * 60 * 1000L + 9_500, listOf(MessagePart("call_task", "tool", tool = "task", status = "completed",
        input = """{"agent":"general","description":"查资料"}""", output = "done", target = "ses_child")),
        error = """Error: {"error":{"message":"上下文过长","type":"context_length"}}"""),
      user("msg_26", 3 * 60 * 60 * 1000L + 20_000, "排队的消息", queued = true)
    )
    val failedCalls = listOf(assistant("msg_30", 1_000, listOf(
      MessagePart("failed_shell", "tool", tool = "bash", status = "error", input = "false", error = "exit 1"),
      MessagePart("failed_read", "tool", tool = "read", status = "error", input = """{"path":"/missing"}""", error = "not found")
    )))
    val cachedCalls = listOf(assistant("msg_31", 1_000, listOf(
      MessagePart("cached_thought", "reasoning", text = "等结果", status = "running"),
      MessagePart("cached_shell", "tool", tool = "bash", status = "running", input = "test")
    ), completed = null, finish = null))
    val cachedShell = listOf(Message("msg_32", "shell", base + 1_000,
      listOf(MessagePart("cached_shell", "tool", tool = "shell", status = "pending", input = "test")), type = "shell"))
    return listOf(
      Case("finished turn collapses into one activity group", finished),
      Case("finished turn with activity and diff expanded", finished, setOf("turn:msg_1:activities", "diff:msg_2")),
      Case("activity details", finished, details = "turn:msg_1:activities"),
      Case("running turn stays transparent", running, working = true),
      Case("running batch expanded", running, setOf("batch:msg_11/call_sh"), working = true),
      Case("shell turn, notices, interruption, errors and queued input", mixed),
      Case("revert boundary hides later messages", mixed, revert = "msg_24"),
      Case("expanded shell tool", mixed, setOf("tool:msg_21/msg_21:shell")),
      Case("failed calls summarize diagnostics without failing the turn", failedCalls),
      Case("cached active calls are unverified rather than live", cachedCalls, working = true, unverified = true),
      Case("unverified activity details keep cached statuses neutral", cachedCalls, working = true,
        details = "turn:msg_31:activities", unverified = true),
      Case("cached standalone shell is unverified", cachedShell, unverified = true)
    )
  }

  // ---- Reading the fixture back ----

  private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)

  private fun messages(array: JSONArray): List<Message> = (0 until array.length()).map { i ->
    val json = array.getJSONObject(i)
    Message(json.getString("id"), json.getString("role"), json.getLong("created"),
      json.getJSONArray("parts").let { parts -> (0 until parts.length()).map { part(parts.getJSONObject(it)) } },
      json.optString("error").ifBlank { null }, json.optString("agent").ifBlank { null }, Codec.modelFrom(json.optJSONObject("model")),
      json.optLong("completedAt").takeIf { json.has("completedAt") }, json.optString("finish").ifBlank { null },
      json.optString("errorType").ifBlank { null }, json.optString("retry").ifBlank { null },
      json.optJSONObject("notice")?.let { Notice(it.getString("label"), it.getString("detail"), strings(it.getJSONArray("items")),
        it.optString("target").ifBlank { null }, it.getBoolean("error"), it.getBoolean("running")) },
      json.getBoolean("queued"), json.getString("type"))
  }

  private fun part(json: JSONObject) = MessagePart(json.getString("id"), json.getString("type"), json.getString("text"), json.getString("tool"),
    json.getString("title"), json.getString("status"), json.getString("input"), json.getString("output"), json.getString("path"),
    json.getString("error"), json.getString("patch"), strings(json.getJSONArray("files")), json.getString("mime"),
    json.getJSONArray("attachments").let { list -> (0 until list.length()).map { list.getJSONObject(it).let { a -> Attachment(a.getString("url"), a.getString("mime"), a.getString("name")) } } },
    json.optString("target").ifBlank { null }, json.getBoolean("background"))
}
