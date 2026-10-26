package com.igng.opencode.lagoon.core

/**
 * 系统实时更新总览里的单个具体任务条目（最多提取前 3 个）。
 */
data class TaskSummaryItem(
  val sessionId: String,
  val title: String,
  val phase: TaskPhase,
  val detail: String = "",
  /** Non-null when the item belongs to a multi-server aggregate. */
  val serverId: String? = null,
  val serverName: String? = null,
  val since: Long = 0
)

/**
 * 全服务器范围的任务计数与明细，供系统实时更新与 App 内任务状态复用同一份口径。
 *
 * 计数规则（已确认）：
 * - [running]：正在执行（THINKING / TOOL / SUBAGENT / TESTING）。
 * - [completed]：已完成但用户尚未查看（“未读的已完成”），用户打开过对应会话后不再计入。
 * - [waiting]：需要用户处理，权限确认与问题回答合并显示为“待回复”。
 * - [failed]：执行失败，单独显示，不与已完成合并。
 * - [items]：按重要度排序的具体任务列表（运行/等待 > 失败 > 未读已完成），最多取 3 条。
 */
data class TaskSummary(
  val running: Int = 0,
  val completed: Int = 0,
  val waiting: Int = 0,
  val failed: Int = 0,
  val items: List<TaskSummaryItem> = emptyList(),
  /** 其中在应用处于后台时仍在运行的根会话数（用于“后台运行中”文案）。 */
  val background: Int = 0,
  /** Work blocked on the server with no phone-local approval prompt. */
  val processing: Int = 0
) {
  val isEmpty: Boolean get() = running == 0 && completed == 0 && waiting == 0 && failed == 0 && processing == 0

  /**
   * 统一显示文案：
   * - 始终显示“xx个运行中，xx个已完成”；
   * - 存在需要回答/确认的任务时，额外显示“xx个待回复”；
   * - 存在失败任务时，额外单独显示“xx个失败”。
   */
  val text: String?
    get() {
      if (isEmpty) return null
      return buildString {
        append("${running}个运行中，${completed}个已完成")
        if (waiting > 0) append("，${waiting}个待回复")
        if (processing > 0) append("，${processing}个等待服务器处理")
        if (failed > 0) append("，${failed}个失败")
      }
    }

  /**
   * 完整的一行计数，用于旧系统通知主行与首页全局任务总览。
   *
   * 与 [shortText] 使用完全相同的计数桶与顺序，但写成可读文案，避免旧系统只显示
   * `contentTitle` 时把“待回复”或“已完成”数量漏掉。
   */
  val countLine: String
    get() = buildString {
      append("${running} 运行中")
      append(" · ${completed} 未读完成")
      if (waiting > 0) append(" · ${waiting} 待回复")
      if (processing > 0) append(" · ${processing} 处理中")
      if (failed > 0) append(" · ${failed} 失败")
    }

  /**
   * 极短胶囊文案（未展开状态展示）：
   * 必须同时展示运行中与已完成计数，例如：“0跑·1完”或“1跑·2完”；
   * 存在待处理或失败时追加：“·1待”或“·1败”。
   */
  val shortText: String
    get() = buildString {
      append("${running}跑·${completed}完")
      if (waiting > 0) append("·${waiting}待")
      if (processing > 0) append("·${processing}处理中")
      if (failed > 0) append("·${failed}败")
    }

  companion object {
    val EMPTY = TaskSummary()

    /**
     * Running/waiting come from live task state rolled up to each root; completed/failed count the
     * roots that have an unread result in [notices]. A root that is running again counts only as running.
     * @param titles 会话标题映射表，用于生成 items。
     */
    fun of(
      tasks: Map<String, TaskState>,
      notices: List<SessionNotice> = emptyList(),
      parents: Map<String, String> = emptyMap(),
      titles: Map<String, String> = emptyMap(),
      backgroundRoots: Set<String> = emptySet(),
      serverId: String? = null,
      serverName: String? = null
    ): TaskSummary {
      val active = aggregate(tasks.filterValues { it.active && !it.uncertain }, parents)
      val results = notices.filterNot { it.viewed || it.sessionId in active }.groupBy { it.sessionId }.map { (session, unseen) ->
        val latest = unseen.maxBy { it.time }
        TaskState(session, if (unseen.any { it.error }) TaskPhase.FAILED else TaskPhase.COMPLETED, since = latest.time, finishedAt = latest.time)
      }
      val rootTasks = active + results.associateBy { it.sessionId }
      var running = 0
      var completed = 0
      var waiting = 0
      var failed = 0
      var background = 0
      var processing = 0
      for (task in rootTasks.values) {
        when (task.phase) {
          in TaskState.RUNNING_PHASES -> {
            running += 1
            if (task.sessionId in backgroundRoots) background += 1
          }
          TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION -> waiting += 1
          TaskPhase.WAITING_SERVER -> processing += 1
          TaskPhase.COMPLETED -> completed += 1
          TaskPhase.FAILED -> failed += 1
          else -> Unit
        }
      }

      val sortedTasks = rootTasks.values
        .filter { it.active || it.phase == TaskPhase.FAILED || it.phase == TaskPhase.COMPLETED }
        .sortedWith(compareByDescending<TaskState> { priority(it) }.thenByDescending { it.since })
        .take(3)
        .map { task ->
          val taskTitle = titles[task.sessionId]?.ifBlank { null } ?: "会话 ${task.sessionId.take(6)}"
          TaskSummaryItem(
            sessionId = task.sessionId,
            title = taskTitle,
            phase = task.phase,
            detail = task.detail,
            serverId = serverId,
            serverName = serverName,
            since = task.since
          )
        }

      return TaskSummary(
        running = running,
        completed = completed,
        waiting = waiting,
        failed = failed,
        items = sortedTasks,
        background = background,
        processing = processing
      )
    }

    /** Combines independently reduced server summaries without colliding session ids. */
    fun merge(summaries: List<TaskSummary>): TaskSummary {
      if (summaries.isEmpty()) return EMPTY
      val items = summaries.flatMap { it.items }
        .sortedWith(compareByDescending<TaskSummaryItem> { priority(it.phase) }.thenByDescending { it.since })
        .take(3)
      return TaskSummary(
        running = summaries.sumOf { it.running },
        completed = summaries.sumOf { it.completed },
        waiting = summaries.sumOf { it.waiting },
        failed = summaries.sumOf { it.failed },
        items = items,
        background = summaries.sumOf { it.background },
        processing = summaries.sumOf { it.processing }
      )
    }

    /**
     * 根会话里“主线程没在跑、只有它派生的任务在跑”的那些，或主线程已把阻塞工具/子代理转入后台
     * （[TaskState.background]）的那些：这才是“后台运行中”（主线程在等后台任务）。
     * 不用“应用是否退到过后台”判断，否则前台正常运行的会话会被误标成后台。
     */
    fun backgroundRoots(tasks: Map<String, TaskState>, parents: Map<String, String>): Set<String> {
      val activeRoots = aggregate(tasks.filterValues { it.active }, parents)
      return activeRoots.keys.filter { root -> tasks[root]?.active != true || tasks[root]?.background == true }.toSet()
    }

    fun aggregate(tasks: Map<String, TaskState>, parents: Map<String, String>): Map<String, TaskState> {
      fun root(id: String): String {
        var current = id
        val seen = mutableSetOf<String>()
        while (seen.add(current)) current = parents[current] ?: return current
        return id
      }
      return tasks.values.groupBy { root(it.sessionId) }.mapValues { (id, values) ->
        // 同优先级时优先折算出的后台任务：同一家族里“后台任务运行中”的明细比子任务的“运行中”更能
        // 说明根会话为什么还活着。
        values.maxWith(compareBy<TaskState> { priority(it) }.thenBy { it.background }).copy(sessionId = id)
      }
    }

    private fun priority(task: TaskState): Int = priority(task.phase)

    private fun priority(phase: TaskPhase): Int = when (phase) {
      TaskPhase.WAITING_SERVER -> 5
      in TaskState.WAITING_PHASES -> 6
      in TaskState.RUNNING_PHASES -> 4
      TaskPhase.FAILED -> 3
      TaskPhase.COMPLETED -> 2
      else -> 1
    }
  }
}
