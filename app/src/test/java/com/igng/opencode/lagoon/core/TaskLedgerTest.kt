package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class TaskLedgerTest {
  private fun store() = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
  @Test fun migrationDropsPermanentResultsAndReadMarkersButKeepsObservedRuns() {
    val prefs = memoryPreferences()
    val store = ServerStore(prefs, memoryPreferences(), { it }, { it })
    val now = 40L * 24 * 60 * 60 * 1000
    store.rememberTask("server", TaskState("done", TaskPhase.COMPLETED, since = now - 10, finishedAt = now - 5), observedAt = now - 5)
    store.rememberTask("server", TaskState("running", TaskPhase.TOOL, since = now - 100), observedAt = now - 100)
    store.rememberTask("server", TaskState("ancient", TaskPhase.THINKING, since = 1), observedAt = 1)
    store.rememberTask("other", TaskState("done", TaskPhase.FAILED, since = 1), observedAt = 1)
    prefs.edit().putString("taskRead:server:done", "1").apply()
    store.migrateTaskLedger("server", now)
    assertEquals(setOf("running"), store.taskStates("server").keys)
    assertTrue(store.acknowledgedTasks("server").isEmpty())
    assertEquals(setOf("done"), store.taskStates("other").keys)
    // Runs observed after the migration are never dropped again.
    store.rememberTask("server", TaskState("later", TaskPhase.COMPLETED, since = now), observedAt = now)
    store.migrateTaskLedger("server", now)
    assertTrue("later" in store.taskStates("server"))
  }
  @Test fun olderTaskEventCannotOverwriteANewerForegroundRun() {
    val store = store()
    store.rememberTask("server", TaskState("s", TaskPhase.THINKING, since = 200), observedAt = 200)
    val next = store.rememberTask("server", TaskState("s", TaskPhase.COMPLETED, since = 100), observedAt = 150)
    assertEquals(TaskPhase.THINKING, next.phase)
    assertEquals(200L, store.taskStates("server")["s"]?.since)
  }
  @Test fun bothPublishersClaimOneNotificationPerPhaseAndRun() {
    val store = store(); val task = TaskState("s", TaskPhase.COMPLETED, "done", since = 100)
    assertTrue(store.claimNotification("server", task, "done"))
    assertFalse(store.claimNotification("server", task, "done"))
    assertTrue(store.claimNotification("server", task.copy(since = 200), "done"))
    assertTrue(store.claimNotification("other", task, "done"))
  }
  @Test fun viewingAllResultsTouchesOnlyThatServerAndLateResultsStayUnread() {
    val store = store()
    val now = System.currentTimeMillis()
    store.rememberNotice("server", SessionNotice("first", "s", now, false))
    store.rememberNotice("server", SessionNotice("second", "other", now + 1, true))
    store.rememberNotice("other", SessionNotice("third", "x", now, false))
    store.viewAllNotices("server")
    assertTrue(store.sessionNotices("server").all { it.viewed })
    assertFalse(store.sessionNotices("other").single().viewed)
    // A result recorded after the batch belongs to a newer run and must remain unread.
    store.rememberNotice("server", SessionNotice("late", "s", now + 2, false))
    assertFalse(store.sessionNotices("server").single { it.id == "late" }.viewed)
  }
}
