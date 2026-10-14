package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已删除的 worktree 会让 location 级接口返回 500；目录健康表必须记住它、跳过重试窗口，
 * 而不是每轮对账都重打一遍并让整页停在“部分数据待同步”。
 */
class DirectoryHealthTest {
  private var now = 1_000_000L
  private fun health() = DirectoryHealth { now }

  @Test fun aFailedDirectoryIsSkippedUntilItsRetryWindowOpens() {
    val health = health()
    assertFalse(health.isDown("/tmp/gone"))
    health.failed("/tmp/gone")
    assertTrue(health.isDown("/tmp/gone"))
    now += DirectoryHealth.backoffMillis(0)
    assertFalse(health.isDown("/tmp/gone"))
  }

  @Test fun backoffGrowsWithRepeatedFailuresAndIsCapped() {
    assertEquals(30_000L, DirectoryHealth.backoffMillis(0))
    assertEquals(60_000L, DirectoryHealth.backoffMillis(1))
    assertEquals(120_000L, DirectoryHealth.backoffMillis(2))
    assertEquals(300_000L, DirectoryHealth.backoffMillis(3))
    assertEquals(900_000L, DirectoryHealth.backoffMillis(4))
    assertEquals(900_000L, DirectoryHealth.backoffMillis(99))
  }

  @Test fun aSuccessClearsTheFailureAndTrailingSlashesAreTheSameDirectory() {
    val health = health()
    health.failed("/home/x/worktree/")
    assertTrue(health.isDown("/home/x/worktree"))
    health.succeeded("/home/x/worktree")
    assertFalse(health.isDown("/home/x/worktree/"))
  }
}
