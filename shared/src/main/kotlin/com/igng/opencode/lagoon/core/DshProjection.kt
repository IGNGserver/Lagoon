package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Projects a DSH `user/message` using durable source attribution. DSH uses role=user for every
 * model-facing input, so source.kind—not the role or prose—is the author identity.
 */
internal fun dshInputMessage(sessionId: String, seq: Long, at: Long, turn: Long?, data: JSONObject): Message? {
  val hasSource = data.has("source") && data.opt("source") != JSONObject.NULL
  val source = data.optJSONObject("source")
  // A present but malformed source is never treated as a legacy human message.
  if (hasSource && source == null) return null
  val kind = source?.str("kind")?.takeIf(String::isNotBlank)
  if (hasSource && kind == null) return null
  val id = data.str("id").ifBlank { "user:$sessionId:$seq" }
  val text = data.arr("content").objects().mapNotNull { it.str("text").takeIf(String::isNotBlank) }.joinToString("\n")
  val turnId = turn?.let { "turn:$it" }
  val attachments = dshAttachmentParts(id, data)
  if (!hasSource || kind == "user") {
    val message = JSONObject().put("id", id).put("type", "user")
      .put("text", text).put("metadata", JSONObject().put("displayText", text)).put("sourceRequestId", source?.str("rpcId"))
      .put("time", JSONObject().put("created", at)).toMessage()
    return message.copy(parts = message.parts + attachments, turnId = turnId, sequence = seq,
      sourceRequestId = source?.str("rpcId")?.ifBlank { null }, sourceKind = kind)
  }
  val sourceKind = kind ?: return null
  if (sourceKind == "agent-message") {
    if (source.str("form") != "relay" || source.str("senderSessionId").isBlank()) return null
    return Message(id, "relay", at,
      listOf(MessagePart("$id:text", "text", text = text)),
      notice = Notice("代理来信", target = source.str("senderSessionId")),
      type = sourceKind, turnId = turnId, sequence = seq, sourceKind = sourceKind)
  }
  val label = when (sourceKind) {
    "goal" -> "目标轮次"
    "tool-goal" -> "目标完成"
    "compact-checkpoint" -> "上下文已压缩"
    "subagent-settled" -> if (source.str("form") == "notice" && source.str("senderSessionId").isNotBlank()) "子代理运行结束" else return null
    "tool-jobs" -> if (source.str("form") == "notice") "后台任务通知" else return null
    else -> return null
  }
  val detail = when (sourceKind) {
    "goal", "tool-goal" -> {
      val round = Regex("Round:\\s*(\\d+/\\d+)").find(text)?.groupValues?.get(1).orEmpty()
      val objective = Regex("Objective:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)?.trim().orEmpty()
      listOf(round, objective.take(160) + if (objective.length > 160) "…" else "").filter(String::isNotBlank).joinToString(" · ")
    }
    "compact-checkpoint" -> ""
    // Never display model-facing injected instructions as a user prompt.
    else -> source.str("summary")
  }
  val activityParts = if (sourceKind == "subagent-settled") listOf(MessagePart("$id:text", "text", text = text)) else emptyList()
  return Message(id, "notice", at, activityParts, notice = Notice(label, detail,
    target = source.str("senderSessionId").takeIf { sourceKind == "subagent-settled" && it.isNotBlank() }),
    type = sourceKind, turnId = turnId, sequence = seq, sourceKind = sourceKind)
}

/** Keep both structured metadata and every rendered content block in both projection paths. */
internal fun dshToolState(data: JSONObject): JSONObject {  val message = data.obj("message")
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

/**
 * DSH 的 prompt 里图片/文件是**附件引用**（`{type:"image", attachment:{attachmentId,mediaType,name}}`），
 * 字节由 `session/attachment` 读取（见 DshApi），这里只把它们折成时间线认识的 `file` 部件：
 * `path` 是 DshApi 解出的 `data:` URI（拿不到时为空白，界面至少还能显示文件名）。
 */
private fun dshAttachmentParts(id: String, data: JSONObject): List<MessagePart> {
  val blobs = data.optJSONObject("_blobs")
  val parts = ArrayList<MessagePart>()
  data.arr("content").objects().forEachIndexed { index, block ->
    val type = block.str("type")
    if (type != "image" && type != "file") return@forEachIndexed
    val attachment = block.obj("attachment")
    val attachmentId = attachment.str("attachmentId")
    if (attachmentId.isBlank()) return@forEachIndexed
    parts += MessagePart(
      id = "$id:file:$index",
      type = "file",
      path = blobs?.optString(attachmentId).orEmpty(),
      title = attachment.str("name").ifBlank { if (type == "image") "图片" else "附件" },
      mime = attachment.str("mediaType").ifBlank { if (type == "image") "image/png" else "" }
    )
  }
  return parts
}
