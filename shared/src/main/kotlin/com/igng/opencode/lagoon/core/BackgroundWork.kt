package com.igng.opencode.lagoon.core

import org.json.JSONObject

/**
 * 后台工作派生，对齐官方客户端的 `packages/app/src/session/requests/background.ts`：
 * 后台 shell 与子会话是独立于根会话执行状态的活动。这里只保留家族/父链工具和从工具元数据
 * 恢复子会话身份的辅助；后台活动本身由 [SessionActivityRules] 汇总到列表标记与会话内面板，
 * 不再参与 [TaskState] 的折算。
 */
object BackgroundWork {
  /** A completed tool call whose metadata says the child work is still running. */
  fun backgroundSubagentId(type: String, properties: JSONObject): String? {
    if (type !in setOf("session.tool.success", "session.tool.failed")) return null
    val metadata = properties.obj("metadata")
    if (metadata.str("status") != "running" && !metadata.optBoolean("background")) return null
    val tool = properties.str("name").ifBlank { properties.str("tool") }.ifBlank { metadata.str("tool") }
    if (tool !in SUBAGENT_TOOLS) return null
    return metadata.str("sessionID").ifBlank { properties.obj("input").str("sessionID") }
      .takeIf(String::isNotBlank)
  }

  /** Synthetic completion notices carry the child id even when the child execution event was missed. */
  fun completedSubagentId(type: String, properties: JSONObject): String? {
    if (type != "session.synthetic") return null
    val metadata = properties.obj("metadata")
    return metadata.str("childID").takeIf { metadata.str("source") == "subagent" && it.isNotBlank() }
  }

  /** Background subagents recoverable from the currently loaded parent transcript. */
  fun messageSubagents(parentId: String?, messages: List<Message>): Map<String, Long> {
    if (parentId.isNullOrBlank()) return emptyMap()
    val completed = messages.mapNotNull { it.notice?.target }.toSet()
    val running = linkedMapOf<String, Long>()
    messages.forEach { message ->
      message.parts.filter { it.type == "tool" && it.tool in SUBAGENT_TOOLS && it.background }
        .mapNotNull { it.target?.takeIf(String::isNotBlank) }
        .filterNot { it in completed }
        .forEach { child -> running[child] = message.created }
    }
    return running
  }

  /** 从 [id] 沿父链找到根会话；父链缺失时 [id] 自己就是根。 */
  fun rootOf(id: String, parents: Map<String, String>): String {
    var current = id
    val seen = mutableSetOf<String>()
    while (seen.add(current)) current = parents[current] ?: return current
    return id
  }

  /** [id] 到根的层数；对账时先处理子会话再处理父会话，父会话才能看到子会话已收尾。 */
  fun depth(id: String, parents: Map<String, String>): Int {
    var current = id
    var depth = 0
    val seen = mutableSetOf<String>()
    while (seen.add(current)) {
      val parent = parents[current] ?: return depth
      current = parent
      depth++
    }
    return depth
  }

  /** 根会话及其全部后代（含跨层级的子会话）。 */
  fun familyOf(root: String, parents: Map<String, String>): Set<String> {
    if (parents.isEmpty()) return setOf(root)
    val children = mutableMapOf<String, MutableList<String>>()
    parents.forEach { (child, parent) -> children.getOrPut(parent) { mutableListOf() }.add(child) }
    val family = linkedSetOf(root)
    val queue = ArrayDeque<String>().apply { add(root) }
    while (queue.isNotEmpty()) {
      children[queue.removeFirst()]?.forEach { child -> if (family.add(child)) queue.add(child) }
    }
    return family
  }
}
