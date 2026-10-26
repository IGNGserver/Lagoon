package com.igng.opencode.lagoon.core

/**
 * 自动刷新频率。数值刻意保守：实时更新仍以 SSE 事件流为主，对账只是兜底，
 * 所以「更快」把有任务时的对账压到 10 秒、空闲 30 秒，不至于形成请求风暴。
 */
enum class RefreshMode(val label: String) {
  STANDARD("标准"),
  FAST("更快");

  /** 有任务运行 / 等待处理时的控制面对账周期。 */
  val activeReconcileMs: Long get() = if (this == FAST) 10_000L else 20_000L

  /** 没有进行中任务时的对账周期。 */
  val idleReconcileMs: Long get() = if (this == FAST) 30_000L else 60_000L

  /** 回到前台后，距上次全量刷新超过多久才再拉一次。 */
  val foregroundRefreshMs: Long get() = if (this == FAST) 5_000L else 15_000L

  /** 事件流驱动的会话消息回读去抖。 */
  val messageDebounceMs: Long get() = if (this == FAST) 150L else 350L

  /** 元数据事件（会话创建/重命名等）触发的控制面回读去抖。 */
  val eventDebounceMs: Long get() = if (this == FAST) 120L else 250L
}
