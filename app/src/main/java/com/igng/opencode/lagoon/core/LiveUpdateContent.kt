package com.igng.opencode.lagoon.core

/**
 * 服务器总览通知所处的阶段，决定它是否作为系统「实时更新」上岛。
 *
 * - [ACTIVE]：有任务正在运行或等待用户处理，通知常驻并申请提升（状态栏胶囊 / 超级岛）。
 * - [SETTLED]：没有进行中的任务，只剩未读的完成 / 失败结果，降级为可划掉的普通通知。
 * - [EMPTY]：没有任何需要展示的内容，通知移除。
 *
 * Google 的规范要求实时更新只覆盖“有明确开始和结束、正在进行”的活动，
 * 所以结束后的结果不再常驻在岛上。
 */
enum class LiveUpdateStage { ACTIVE, SETTLED, EMPTY }

/**
 * 总览通知的全部内容，由 [TaskSummary] 纯函数派生，与 Android 通知 API 解耦以便单测。
 * 系统卡片的字体和布局仍由系统渲染；[progress] 只描述语义分段及其颜色类别。
 */
data class LiveUpdateContent(
  val stage: LiveUpdateStage,
  val title: String,
  val text: String,
  /** 展开态：状态统计 + 最多 3 行“状态 · 会话名”，超出时追加“另有 N 个任务”。 */
  val expandedText: String,
  /** 状态栏胶囊短文案；只在 [LiveUpdateStage.ACTIVE] 时使用。 */
  val shortCriticalText: String,
  /** Android 16 expanded Live Update 使用的分段状态轨道。 */
  val progress: LiveUpdateProgress,
  /** 头条任务（最需要关注的那个）的会话 id，用作通知点击落点。 */
  val headlineSessionId: String?
) {
  companion object {
    private const val MAX_LINES = 3

    fun stageOf(summary: TaskSummary): LiveUpdateStage = when {
      summary.running > 0 || summary.waiting > 0 -> LiveUpdateStage.ACTIVE
      summary.completed > 0 || summary.failed > 0 -> LiveUpdateStage.SETTLED
      else -> LiveUpdateStage.EMPTY
    }

    fun of(summary: TaskSummary): LiveUpdateContent {
      val stage = stageOf(summary)
      val title = when {
        summary.waiting > 0 -> "${summary.waiting} 个任务待你处理"
        summary.running > 0 -> when {
          summary.background <= 0 -> "${summary.running} 个任务运行中"
          summary.background >= summary.running -> "${summary.running} 个任务在后台运行中"
          else -> "${summary.running} 个任务运行中（${summary.background} 个在后台）"
        }
        summary.failed > 0 && summary.completed > 0 -> "${summary.completed} 个已完成，${summary.failed} 个失败"
        summary.failed > 0 -> "${summary.failed} 个任务失败"
        summary.completed > 0 -> "${summary.completed} 个任务已完成"
        else -> ""
      }
      val statusText = summary.text.orEmpty()
       val lines = summary.items.take(MAX_LINES).map { item ->
         val title = item.serverName?.takeIf(String::isNotBlank)?.let { "$it · ${item.title}" } ?: item.title
         "${phaseLabel(item.phase)} · $title"
       }
      val total = summary.running + summary.waiting + summary.completed + summary.failed
      val hidden = total - lines.size
      val detailLines = if (hidden > 0) lines + "另有 $hidden 个任务" else lines
      val expanded = (listOf(statusText).filter { it.isNotBlank() } + detailLines).joinToString("\n")
      return LiveUpdateContent(
        stage = stage,
        title = title,
        text = statusText.ifBlank { summary.items.firstOrNull()?.title.orEmpty() },
        expandedText = expanded,
        shortCriticalText = summary.shortText,
        progress = LiveUpdateProgress.of(summary),
        headlineSessionId = summary.items.firstOrNull()?.sessionId
      )
    }

    fun phaseLabel(phase: TaskPhase): String = when (phase) {
      in TaskState.WAITING_PHASES -> "待处理"
      in TaskState.RUNNING_PHASES -> "运行中"
      TaskPhase.FAILED -> "失败"
      TaskPhase.COMPLETED -> "已完成"
      TaskPhase.ABORTED -> "已停止"
      else -> "未知"
    }

    /**
     * 用户划掉总览通知后是否仍应发布。划掉时记录当时的阶段；只要阶段没变就不再重发，
     * 阶段一变（例如全部结束、或又有新任务开始）就重新展示。这遵循“不要重发用户已划掉的
     * 实时更新”的规范，同时不吞掉真正的新状态。
     */
    fun shouldPost(stage: LiveUpdateStage, dismissedStage: LiveUpdateStage?): Boolean =
      stage != LiveUpdateStage.EMPTY && stage != dismissedStage
  }
}
