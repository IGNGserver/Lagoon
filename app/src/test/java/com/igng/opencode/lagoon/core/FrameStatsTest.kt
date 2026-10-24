package com.igng.opencode.lagoon.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStatsTest {
  @After fun clear() = FrameStats.reset()

  private fun ms(value: Long) = value * 1_000_000

  @Test
  fun summarisesPerPageWithBucketPercentiles() {
    repeat(90) { FrameStats.record(ms(10), jank = false, label = "会话") }
    repeat(9) { FrameStats.record(ms(30), jank = true, label = "会话") }
    FrameStats.record(ms(700), jank = true, label = "会话")
    FrameStats.record(ms(5), jank = false, label = "首页")

    val lines = FrameStats.summary().lines()
    assertEquals(2, lines.size)
    assertEquals("会话：100 帧，卡顿 10（10.0%），P50 ≤12ms P90 ≤12ms P99 ≤34ms，最长 700ms", lines[0])
    assertEquals("首页：1 帧，卡顿 0（0.0%），P50 ≤8ms P90 ≤8ms P99 ≤8ms，最长 5ms", lines[1])
  }

  @Test
  fun framesBeyondTheLastBoundAreOpenEnded() {
    FrameStats.record(ms(4_000), jank = true, label = "启动")
    assertTrue(FrameStats.summary().contains("P50 >1000ms"))
  }

  @Test
  fun emptyWhenNothingRecorded() = assertEquals("", FrameStats.summary())
}
