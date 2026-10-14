package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies one `/api/event` frame to the open session's timeline, mirroring the official client's
 * event reducer (`packages/client/src/solid/data.ts` `handleEvent`). Streaming events target one
 * assistant message and, within it, the latest content of a kind; an event whose target was never
 * loaded is dropped, exactly like the official client. [Result.reconcile] asks for an authoritative
 * re-read where the official client also re-syncs (a run ending with tools still in flight).
 */
/**
 * Merges a freshly fetched page into the loaded transcript by message id, keeping ascending
 * `created` order. A tail refresh must never drop pages the reader already pulled in, and an
 * optimistic/streaming entry the server page has not caught up with yet must survive too. The
 * server copy wins for the same id; [pending] items are only added when absent.
 */
internal fun mergeTranscript(existing: List<Message>, fetched: List<Message>, pending: List<Message> = emptyList()): List<Message> {
  if (existing.isEmpty()) return (fetched + pending).distinctBy { it.id }
  val merged = LinkedHashMap<String, Message>(existing.size + fetched.size)
  existing.forEach { merged[it.id] = it }
  fetched.forEach { merged[it.id] = it }
  pending.forEach { if (!merged.containsKey(it.id)) merged[it.id] = it }
  // Stable sort: equal timestamps keep their insertion order (older pages first, then fresh tail).
  return merged.values.sortedBy { it.created }
}

/**
 * Mirrors the official client's `needsOlderTurnRoot`: true when the oldest loaded boundary is an
 * assistant message, i.e. the window starts in the middle of a turn. The initial load then pulls one
 * more page so the first screen begins on a whole turn instead of a half reply.
 */
internal fun needsOlderTurnRoot(messages: List<Message>): Boolean {
  val boundary = messages.firstOrNull { message ->
    message.role == "user" || message.role == "shell" || message.role == "assistant" ||
      (message.type == "synthetic" && (message.notice?.detail?.isNotBlank() == true || message.notice?.label?.isNotBlank() == true))
  }
  return boundary?.role == "assistant"
}

object TranscriptProjection {
  data class Result(val messages: List<Message>, val reconcile: Boolean = false)

  /** Event ids become message ids for records projected from that event (`messageIDFromEvent`). */
  fun messageId(event: ServerEvent): String = event.id.replace(Regex("^evt_"), "msg_")

  /** Null when the event does not touch the transcript. */
  fun apply(messages: List<Message>, event: ServerEvent): Result? {
    val p = event.properties
    val at = event.created.takeIf { it > 0 } ?: System.currentTimeMillis()
    val assistantId = p.str("assistantMessageID")
    fun result(next: List<Message>) = Result(next)
    fun editAssistant(change: (Message) -> Message): Result {
      val index = messages.indexOfFirst { it.id == assistantId && it.role == "assistant" }
      if (index < 0) return Result(messages)
      return result(messages.toMutableList().also { it[index] = change(it[index]) })
    }
    /** Like [editAssistant], but asks for an authoritative re-read when the target message was never loaded. */
    fun editAssistantOrReconcile(change: (Message) -> Message): Result {
      val index = messages.indexOfFirst { it.id == assistantId && it.role == "assistant" }
      if (index < 0) return Result(messages, reconcile = true)
      return result(messages.toMutableList().also { it[index] = change(it[index]) })
    }
    fun editLast(kind: String, change: (MessagePart) -> MessagePart) = editAssistant { message ->
      val index = message.parts.indexOfLast { it.type == kind && (kind != "reasoning" || it.status == "running") }
      if (index < 0) message else message.copy(parts = message.parts.toMutableList().also { it[index] = change(it[index]) })
    }
    fun editTool(change: (MessagePart) -> MessagePart) = editAssistant { message ->
      val index = message.parts.indexOfLast { it.type == "tool" && it.id == p.str("id") }
      if (index < 0) message else message.copy(parts = message.parts.toMutableList().also { it[index] = change(it[index]) })
    }
    fun insert(message: Message?): Result = if (message == null || messages.any { it.id == message.id }) Result(messages) else result(messages + message)
    fun record(type: String, fields: JSONObject): Message =
      JSONObject(fields.toString()).put("id", messageId(event)).put("type", type).put("time", JSONObject().put("created", at)).toMessage()

    return when (event.type) {
      "session.step.started" -> {
        val existing = messages.indexOfFirst { it.id == assistantId && it.role == "assistant" }
        val agent = p.str("agent").ifBlank { null }
        val model = p.obj("model").toModelChoice()
        val started = p.optLong("started").takeIf { it > 0 } ?: at
        if (existing >= 0) result(messages.toMutableList().also {
          it[existing] = it[existing].copy(agent = agent ?: it[existing].agent, model = model ?: it[existing].model, retry = null, error = null,
            errorType = null, finish = null, completedAt = null, created = started)
        }) else {
          val active = messages.indexOfLast { it.role == "assistant" && it.completedAt == null }
          val closed = if (active < 0) messages else messages.toMutableList().also { it[active] = it[active].copy(retry = null, completedAt = at) }
          result(closed + Message(assistantId, "assistant", started, emptyList(), agent = agent, model = model, type = "assistant"))
        }
      }
      "session.step.ended" -> editAssistant { it.copy(completedAt = at, finish = p.str("finish").ifBlank { "stop" }) }
      "session.step.failed" -> editAssistantOrReconcile {
        val error = p.obj("error")
        it.copy(completedAt = at, finish = p.str("finish").ifBlank { "error" }, error = p.errorText("error").ifBlank { "执行失败" },
          errorType = error.str("type").ifBlank { null }, retry = null)
      }
      "session.retry.scheduled" -> editAssistant { it.copy(retry = "第 ${p.optInt("attempt")} 次重试：${p.errorText("error")}") }
      "session.text.started", "session.reasoning.started" -> {
        val kind = if (event.type.contains("reasoning")) "reasoning" else "text"
        editAssistant { message ->
          val ordinal = message.parts.count { it.type == kind }
          message.copy(parts = message.parts + MessagePart("${message.id}:$kind:$ordinal", kind, status = if (kind == "reasoning") "running" else ""))
        }
      }
      "session.text.delta" -> editLast("text") { it.copy(text = it.text + p.str("delta")) }
      "session.text.ended" -> editLast("text") { it.copy(text = p.str("text")) }
      "session.reasoning.delta" -> editLast("reasoning") { it.copy(text = it.text + p.str("delta")) }
      "session.reasoning.ended" -> editLast("reasoning") { it.copy(text = p.str("text"), status = "") }
      "session.tool.input.started" -> editAssistant { message ->
        message.copy(parts = message.parts + MessagePart(p.str("id"), "tool", tool = p.str("name"), status = "pending"))
      }
      "session.tool.input.delta" -> editTool { if (it.status == "pending") it.copy(input = it.input + p.str("delta")) else it }
      "session.tool.input.ended" -> editTool { if (it.status == "pending") it.copy(input = p.str("text")) else it }
      "session.tool.called" -> editTool { tool ->
        val input = p.obj("input")
        tool.copy(status = "running", input = input.toString(2), title = input.str("description").ifBlank { tool.title },
          path = input.str("path").ifBlank { tool.path },
          target = input.str("sessionID").takeIf { tool.tool in SUBAGENT_TOOLS && it.isNotBlank() } ?: tool.target)
      }
      "session.tool.progress" -> editTool { tool ->
        val child = p.obj("metadata").str("sessionID")
        if (tool.status == "running" && tool.tool in SUBAGENT_TOOLS && child.isNotBlank()) tool.copy(target = child) else tool
      }
      "session.tool.success", "session.tool.failed" -> editTool { tool ->
        if (tool.status != "running" && !(event.type.endsWith("failed") && tool.status == "pending")) return@editTool tool
        // Rebuild through the snapshot parser so live and loaded tools look identical.
        val state = JSONObject().put("status", if (event.type.endsWith("success")) "completed" else "error")
          .put("input", tool.input.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
          .put("content", p.optJSONArray("content") ?: JSONArray()).put("metadata", p.optJSONObject("metadata") ?: JSONObject())
        p.opt("error")?.takeIf { it != JSONObject.NULL }?.let { state.put("error", it) }
        val rebuilt = JSONObject().put("type", "tool").put("id", tool.id).put("name", tool.tool).put("state", state).toAssistantPart(tool.id)
        rebuilt.copy(title = rebuilt.title.ifBlank { tool.title }, target = rebuilt.target ?: tool.target)
      }
      "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" -> {
        val active = messages.indexOfLast { it.role == "assistant" && it.completedAt == null }
        val cleared = if (active < 0) messages else messages.toMutableList().also { it[active] = it[active].copy(retry = null) }
        // Official: a run that ends with tools still streaming/running is re-read from the server.
        val dangling = cleared.any { message -> message.role == "assistant" && message.parts.any { it.type == "tool" && it.status in setOf("pending", "running") } }
        // A failed run whose error never landed on a loaded assistant message (the step event was missed) is
        // re-read so the red error row is shown instead of silently dropping it.
        val missingError = event.type == "session.execution.failed" && cleared.none { it.role == "assistant" && !it.error.isNullOrBlank() }
        Result(cleared, reconcile = dangling || missingError)
      }
      "session.instructions.updated" -> if (!p.has("text")) null else insert(record("system", JSONObject()
        .put("text", p.str("text")).put("description", "Instructions updated: ${p.obj("delta").keys().asSequence().joinToString(", ")}")
        .put("metadata", JSONObject().put("notice", "instructions").put("instructionSources", JSONArray(p.obj("delta").keys().asSequence().toList())))))
      "session.synthetic" -> insert(record("synthetic", JSONObject().put("text", p.str("text")).put("description", p.str("description"))
        .put("metadata", p.optJSONObject("metadata") ?: JSONObject())))
      "session.skill.activated" -> insert(record("skill", JSONObject().put("skill", p.str("id")).put("name", p.str("name")).put("text", p.str("text"))))
      "session.agent.selected" -> insert(record("agent-switched", JSONObject().put("agent", p.str("agent")).put("previous", p.str("previous"))))
      "session.model.selected" -> insert(record("model-switched", JSONObject().put("model", p.obj("model"))))
      "session.moved" -> insert(record("location-switched", JSONObject().put("location", p.obj("location"))))
      "session.shell.started" -> {
        val shell = p.obj("shell")
        insert(record("shell", JSONObject().put("shellID", shell.str("id")).put("command", shell.str("command")).put("status", shell.str("status"))))
          .let { inserted -> Result(inserted.messages.map { if (it.id == messageId(event)) it.withShellId(shell.str("id")) else it }) }
      }
      "session.shell.ended" -> {
        val shell = p.obj("shell")
        val index = messages.indexOfLast { it.role == "shell" && it.parts.firstOrNull()?.target == shell.str("id") }
        if (index < 0) Result(messages) else {
          val old = messages[index]
          val ended = JSONObject().put("id", old.id).put("type", "shell").put("command", shell.str("command")).put("status", shell.str("status"))
            .put("output", p.obj("output")).put("time", JSONObject().put("created", old.created).put("completed", at))
          (shell.opt("exit") as? Number)?.let { ended.put("exit", it) }
          result(messages.toMutableList().also { it[index] = ended.toMessage().withShellId(shell.str("id")) })
        }
      }
      "session.compaction.started" -> insert(JSONObject().put("id", p.str("inputID").ifBlank { messageId(event) }).put("type", "compaction")
        .put("status", "running").put("time", JSONObject().put("created", at)).toMessage())
      "session.compaction.ended", "session.compaction.failed" -> {
        val failed = event.type.endsWith("failed")
        val index = messages.indexOfLast { it.type == "compaction" && it.notice?.running == true }
        val fields = JSONObject().put("type", "compaction").put("status", if (failed) "failed" else "completed").put("error", p.opt("error") ?: JSONObject.NULL)
        if (index >= 0) result(messages.toMutableList().also {
          it[index] = fields.put("id", it[index].id).put("time", JSONObject().put("created", it[index].created)).toMessage()
        }) else insert(fields.put("id", messageId(event)).put("time", JSONObject().put("created", at)).toMessage())
      }
      "session.inbox.enqueued" -> {
        val item = JSONObject(p.obj("item").toString()).put("id", p.str("inboxID")).put("time", JSONObject().put("created", at))
        val message = item.toInboxMessage() ?: return null
        val index = messages.indexOfFirst { it.id == message.id }
        result(if (index < 0) messages + message else messages.toMutableList().also { it[index] = message })
      }
      "session.inbox.delivery.changed" -> {
        val id = p.str("inboxID")
        if (messages.none { it.id == id }) null else result(messages.map { if (it.id == id) it.copy(queued = p.str("delivery") == "queue") else it })
      }
      // Delivery moves the input to the end of the timeline at its delivery time.
      "session.inbox.delivered" -> {
        val id = p.str("inboxID")
        val existing = messages.firstOrNull { it.id == id } ?: return null
        result(messages.filterNot { it.id == id } + existing.copy(created = at, queued = false))
      }
      "session.inbox.cancelled" -> if (messages.none { it.id == p.str("inboxID") }) null else result(messages.filterNot { it.id == p.str("inboxID") })
      "session.revert.committed" -> {
        val boundary = p.str("to")
        result(messages.filter { it.id < boundary })
      }
      // Replay-only: older releases replaced completed assistant content wholesale.
      "session.message.content.updated" -> {
        val index = messages.indexOfFirst { it.id == p.str("messageID") && it.role == "assistant" }
        if (index < 0) null else {
          val old = messages[index]
          val rebuilt = JSONObject().put("id", old.id).put("type", "assistant").put("content", p.arr("content")).put("time", JSONObject().put("created", old.created)).toMessage()
          result(messages.toMutableList().also { it[index] = old.copy(parts = rebuilt.parts) })
        }
      }
      else -> null
    }
  }

  private fun Message.withShellId(shellId: String) = copy(parts = parts.map { it.copy(target = shellId) })
}
