package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class DshExecutionTrackerTest {
  @Test fun successiveUnwatchedExecutionsCanHaveDifferentOutcomes() {
    val tracker = DshExecutionTracker()
    tracker.status("s", true)
    val first = tracker.epoch("s")
    tracker.status("s", false)
    assertTrue(tracker.settle("s", 9, "succeeded", first))
    assertEquals("succeeded", tracker.outcome("s"))
    tracker.status("s", true)
    assertNull(tracker.outcome("s"))
    val second = tracker.epoch("s")
    assertTrue(second > first)
    assertFalse(tracker.settle("s", 9, "succeeded", second))
    tracker.status("s", false)
    assertTrue(tracker.settle("s", 19, "failed", second))
    assertEquals("failed", tracker.outcome("s"))
  }

  @Test fun oldReadCannotSettleANewerExecution() {
    val tracker = DshExecutionTracker()
    tracker.status("s", true)
    val old = tracker.epoch("s")
    tracker.status("s", false)
    tracker.status("s", true)
    assertFalse(tracker.settle("s", 99, "succeeded", old))
    assertTrue(tracker.running("s"))
    assertNull(tracker.outcome("s"))
  }

  @Test fun durableStartsRejectOldEndsAndDuplicateStartsDoNotAdvanceEpoch() {
    val tracker = DshExecutionTracker()
    assertTrue(tracker.start("s", 10))
    val epoch = tracker.epoch("s")
    assertFalse(tracker.start("s", 10))
    assertEquals(epoch, tracker.epoch("s"))
    assertFalse(tracker.settle("s", 9, "succeeded"))
    assertTrue(tracker.settle("s", 15, "interrupted"))
    assertFalse(tracker.start("s", 10))
    assertEquals("interrupted", tracker.outcome("s"))
    assertTrue(tracker.start("s", 16))
    assertNull(tracker.outcome("s"))
  }

  @Test fun runningStatusAndItsDurableStartShareOneEpoch() {
    val tracker = DshExecutionTracker()
    tracker.status("s", true)
    val epoch = tracker.epoch("s")
    assertFalse(tracker.status("s", true))
    tracker.start("s", 1)
    assertEquals(epoch, tracker.epoch("s"))
  }
}
