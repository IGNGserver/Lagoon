package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/** Shared durable/live decoding. Attribution comes from source, never from model-facing prose. */
internal fun dshInputMessage(sessionId: String, seq: Long, at: Long, turn: Long?, data: JSONObject): Message? {
  val source = data.obj("source")
  val kind = source.str("kind")
  val id = data.str("id").ifBlank { "user:$sessionId:$seq" }
  val text = data.arr("content").objects().mapNotNull { it.str("text").takeIf(String::isNotBlank) }.joinToString("\n")
  val turnId = turn?.let { "turn:$it" }
  if (kind.isBlank() || kind == "user") return JSONObject().put("id", id).put("type", "user")
    .put("text", text).put("metadata", JSONObject().put("displayText", text)).put("sourceRequestId", source.str("rpcId"))
    .put("time", JSONObject().put("created", at)).toMessage().copy(turnId = turnId, sequence = seq,
      sourceRequestId = source.str("rpcId").ifBlank { null })
  if (kind == "agent-message" && source.str("form") == "relay") return Message(id, "relay", at,
    listOf(MessagePart("$id:text", "text", text = text)),
    notice = Notice("代理来信", target = source.str("senderSessionId").takeIf(String::isNotBlank)),
    type = kind, turnId = turnId, sequence = seq)
  val label = when (kind) {
    "goal" -> "目标轮次"
    "tool-goal" -> "目标完成"
    "compact-checkpoint" -> "上下文已压缩"
    "subagent-settled" -> "子代理运行结束"
    "tool-jobs" -> "后台任务通知"
    else -> if (source.str("form") == "notice") "系统通知" else return null
  }
  val detail = when (kind) {
    "goal", "tool-goal" -> {
      val round = Regex("Round:\\s*(\\d+/\\d+)").find(text)?.groupValues?.get(1).orEmpty()
      val objective = Regex("Objective:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)?.trim().orEmpty()
      listOf(round, objective.take(160) + if (objective.length > 160) "…" else "").filter(String::isNotBlank).joinToString(" · ")
    }
    "compact-checkpoint" -> ""
    // Do not show the injected instructions (e.g. "Read its output with job_output") as a user prompt.
    else -> source.str("summary")
  }
  return Message(id, "notice", at, emptyList(), notice = Notice(label, detail,
    target = source.str("senderSessionId").takeIf { kind == "subagent-settled" && it.isNotBlank() }),
    type = kind, turnId = turnId, sequence = seq)
}

/** Keep both structured metadata and every rendered content block in both projection paths. */
internal fun dshToolState(data: JSONObject): JSONObject {
  val message = data.obj("message")
  val failed = message.optBoolean("isError")
  val metadata = JSONObject(data.obj("meta").toString())
  val state = JSONObject().put("status", if (failed) "error" else "completed")
    .put("content", JSONArray(message.arr("content").toString())).put("metadata", metadata)
  if (failed) {
    val failure = data.obj("error")
    state.put("error", JSONObject().put("message", failure.str("reason").ifBlank { failure.str("message") }
      .ifBlank { message.arr("content").contentText() }.ifBlank { "工具执行失败" }))
  }
  return state
}
