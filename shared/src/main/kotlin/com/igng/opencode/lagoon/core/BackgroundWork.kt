package com.igng.opencode.lagoon.core

import org.json.JSONObject

/**
 * 后台工作派生，对齐官方客户端的 `packages/app/src/session/requests/background.ts`：
 * 一次会话执行（execution）settle 之后，仍在运行的后台任务 = 仍在执行的子会话（子代理）
 * ∪ 运行中的 shell 作业（按 `metadata.sessionID` 归属）。服务端在后台任务完成时会写合成消息并
 * 默认唤醒会话，因此这段窗口里的会话不应被判定为“已完成”。
 *
 * 官方客户端把结果放在会话摘要（“N tasks running”）里单独展示；本应用把它折算成根会话的
 * [TaskState]，让首页、详情、灵动岛、系统通知与前台服务用同一份口径显示“后台运行中”。
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

  /**
   * 会话 [root]（及其后代）里是否还有“它自己执行结束后仍会唤醒它”的后台工作：
   * - 子树里运行中的 shell（后台 shell 不是会话，不在 `/api/session/active` 里）；
   * - 子树里除它以外仍在执行的任务（后台子代理；前台阻塞子代理与它同时活跃，它自己在跑时不算后台）。
   * 对账与事件归约传入“正在判断的会话”本身：子会话自己的 run settle 时，兄弟子任务不算它的后台工作。
   */
  fun hasBackgroundWork(root: String, tasks: Map<String, TaskState>, shells: Map<String, ShellJob>, parents: Map<String, String>): Boolean {
    val family = familyOf(root, parents)
    if (shells.values.any { it.sessionId in family }) return true
    return tasks.values.any { it.sessionId != root && it.sessionId in family && it.active }
  }

  /** 有后台工作的根会话集合：对账收尾豁免与折算都读它。 */
  fun workRoots(tasks: Map<String, TaskState>, shells: Map<String, ShellJob>, parents: Map<String, String>): Set<String> {
    val roots = linkedSetOf<String>()
    shells.values.forEach { roots += rootOf(it.sessionId, parents) }
    // 已有活跃后代（或自身已折算）的根：根自身真正在跑时不算后台。
    TaskSummary.aggregate(tasks.filterValues { it.active && !it.background }, parents).keys.forEach { root ->
      if (tasks[root]?.let { it.active && !it.background } != true) roots += root
    }
    return roots
  }

  /**
   * 把后台工作折算成根会话的活跃任务（`THINKING + background`），代表“后台任务运行中”。
   * 折算只增不减：后台工作结束后不立即撤销，由控制器在宽限期内等待服务端唤醒；超时仍未唤醒时
   * 才按会话结局收尾（见 `LagoonController.settleBackgroundRun`），避免最后一次唤醒到达前抖动。
   */
  fun fold(tasks: Map<String, TaskState>, shells: Map<String, ShellJob>, parents: Map<String, String>,
    now: Long = System.currentTimeMillis()): Map<String, TaskState> {
    val roots = workRoots(tasks, shells, parents)
    if (roots.isEmpty()) return tasks
    var changed = false
    val result = LinkedHashMap(tasks)
    roots.forEach { root ->
      val existing = tasks[root]
      // 根自身真正在跑（前台运行）时不折算；它 settle 后由 [TaskReducer] 重新判断。
      if (existing?.let { it.active && !it.background } == true) return@forEach
      val folded = TaskState(root, TaskPhase.THINKING, TaskState.BACKGROUND_DETAIL,
        since = existing?.since ?: now, activeAt = existing?.activeAt ?: -1L, background = true)
      if (existing != folded) {
        result[root] = folded
        changed = true
      }
    }
    return if (changed) result else tasks
  }
}
