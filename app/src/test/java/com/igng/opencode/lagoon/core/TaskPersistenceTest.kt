package com.igng.opencode.lagoon.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPersistenceTest {
  private val running = TaskState("s", TaskPhase.THINKING, TaskState.RUNNING_DETAIL, since = 1_000, activeAt = 10_000)

  @Test fun firstSightAndNewParentAreWritten() {
    assertTrue(TaskPersistence.shouldWrite(null, null, running, null))
    assertTrue(TaskPersistence.shouldWrite(running, null, running, "root"))
  }

  @Test fun unchangedIsSkipped() = assertFalse(TaskPersistence.shouldWrite(running, "root", running, "root"))

  @Test fun streamedBaselineIsThrottled() {
    assertFalse(TaskPersistence.shouldWrite(running, null, running.copy(activeAt = 12_000), null))
    assertTrue(TaskPersistence.shouldWrite(running, null, running.copy(activeAt = 15_000), null))
    assertTrue(TaskPersistence.shouldWrite(running, null, running.copy(activeAt = 9_000), null))
  }

  @Test fun phaseChangeIsWrittenAtOnce() {
    val done = TaskState("s", TaskPhase.COMPLETED, "任务已完成", since = 1_000, finishedAt = 10_500, activeAt = 10_500)
    assertTrue(TaskPersistence.shouldWrite(running, null, done, null))
    assertTrue(TaskPersistence.shouldWrite(running, null, running.copy(phase = TaskPhase.TOOL, activeAt = 10_100), null))
  }
}
