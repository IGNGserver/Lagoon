package com.igng.opencode.lagoon.core

/**
 * Per-page frame timing, fed by JankStats from the activity window. Frames are counted into fixed
 * duration buckets, so recording is allocation-free and the summary needs no stored samples. The
 * copied diagnostics carry one line per page: how many frames, how many janky, and the 50/90/99th
 * percentile frame time. That is the before/after measure for smoothness work on a real phone.
 */
object FrameStats {
  /** Upper bounds (ms) of the duration buckets; the last bucket is open-ended. */
  internal val BOUNDS_MS = longArrayOf(8, 12, 17, 25, 34, 50, 75, 100, 150, 250, 500, 1000)

  private class Page {
    val buckets = LongArray(BOUNDS_MS.size + 1)
    var frames = 0L
    var janky = 0L
    var worstMs = 0L
  }

  private val pages = LinkedHashMap<String, Page>()

  /** The page the next frames belong to (home, chat, settings…), set by the UI as it navigates. */
  @Volatile var page: String = "启动"

  fun record(durationNanos: Long, jank: Boolean, label: String = page) = synchronized(pages) {
    val ms = durationNanos / 1_000_000
    val stats = pages.getOrPut(label) { Page() }
    stats.frames++
    if (jank) stats.janky++
    if (ms > stats.worstMs) stats.worstMs = ms
    val index = BOUNDS_MS.indexOfFirst { ms <= it }.let { if (it < 0) BOUNDS_MS.size else it }
    stats.buckets[index]++
  }

  fun reset() = synchronized(pages) { pages.clear() }

  /** One line per page, for the copied diagnostics; empty when nothing was recorded. */
  fun summary(): String = synchronized(pages) {
    pages.entries.joinToString("\n") { (label, stats) ->
      val jankPercent = if (stats.frames == 0L) 0.0 else stats.janky * 100.0 / stats.frames
      "$label：${stats.frames} 帧，卡顿 ${stats.janky}（${"%.1f".format(jankPercent)}%），" +
        "P50 ${percentile(stats, 0.50)} P90 ${percentile(stats, 0.90)} P99 ${percentile(stats, 0.99)}，最长 ${stats.worstMs}ms"
    }
  }

  /** Upper bound of the bucket holding the [fraction] percentile, as text ("≤17ms" style, or ">1000ms"). */
  private fun percentile(stats: Page, fraction: Double): String {
    if (stats.frames == 0L) return "-"
    val target = Math.ceil(stats.frames * fraction).toLong().coerceAtLeast(1)
    var seen = 0L
    stats.buckets.forEachIndexed { index, count ->
      seen += count
      if (seen >= target) return if (index < BOUNDS_MS.size) "≤${BOUNDS_MS[index]}ms" else ">${BOUNDS_MS.last()}ms"
    }
    return ">${BOUNDS_MS.last()}ms"
  }
}
