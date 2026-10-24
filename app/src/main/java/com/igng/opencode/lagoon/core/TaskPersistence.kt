package com.igng.opencode.lagoon.core

/** When a task state is worth writing to the device store again; pure so the rule is unit-tested. */
internal object TaskPersistence {
  /** How stale the stored idle baseline ([TaskState.activeAt]) may get while a turn streams. */
  const val ACTIVE_AT_INTERVAL_MS = 5_000L

  fun shouldWrite(previous: TaskState?, previousParent: String?, next: TaskState, parent: String?): Boolean {
    if (previous == null || previousParent != parent) return true
    if (previous == next) return false
    // Anything but the baseline moving forward (phase, detail, since, finish, background) is written at once.
    if (previous.copy(activeAt = next.activeAt) != next) return true
    return next.activeAt < previous.activeAt || next.activeAt - previous.activeAt >= ACTIVE_AT_INTERVAL_MS
  }
}
