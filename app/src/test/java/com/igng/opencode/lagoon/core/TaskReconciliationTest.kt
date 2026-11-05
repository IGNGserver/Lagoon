package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class TaskReconciliationTest {
  private val root = Session("root", "/repo", "Root", 200, idle = 200, outcome = "succeeded")
  private val child = Session("child", "/repo", "Child", 210, parentId = "root", idle = 210, outcome = "succeeded")
  private fun background(baseline: Long = -1) = TaskState("root", TaskPhase.THINKING,
    TaskState.RUNNING_DETAIL, since = 100, activeAt = baseline)
  private fun read(tasks: Map<String, TaskState>, sessions: List<Session> = listOf(root),
    active: Set<String>? = emptySet(), deleted: Set<String> = emptySet(),
    inbox: PendingInputs.Read = PendingInputs.Read(verified = tasks.keys), now: Long = 20_000) =
    TaskReconciliation.reconcile(tasks, sessions, active, mapOf("child" to "root"), deleted, inbox, now)

  @Test fun foldedRootsSettleWithAnUnknownOrEqualBaseline() {
    listOf(-1L, 199L).forEach { baseline ->
      val task = read(mapOf("root" to background(baseline))).getValue("root")
      assertFalse(task.active)
      assertEquals(TaskPhase.COMPLETED, task.phase)
    }
  }

  @Test fun childrenSettleBeforeParentsRegardlessOfInsertionOrder() {
    val states = read(linkedMapOf("root" to background(), "child" to TaskState("child", TaskPhase.SUBAGENT)), listOf(root, child))
    assertFalse(states.getValue("root").active)
    assertFalse(states.getValue("child").active)
    assertEquals(0, TaskSummary.of(states, parents = mapOf("child" to "root")).running)
  }

  @Test fun freshForegroundActivityClearsBackgroundAndUncertaintyButKeepsPrompts() {
    listOf(TaskPhase.THINKING, TaskPhase.WAITING_PERMISSION).forEach { phase ->
      val states = read(mapOf("root" to background().copy(phase = phase, uncertain = true)), active = setOf("root"))
      assertFalse(states.getValue("root").uncertain)
      assertEquals(phase, states.getValue("root").phase)
    }
  }

  /**
   * The regression behind "状态待确认": rows used to keep a permanent unknown state whenever any
   * directory-scoped read could not be completed. A failed activity read now keeps the claim for the
   * next retry only, and never reaches the home row as a third state.
   */
  @Test fun aFailedActivityReadKeepsTheClaimButNeverShowsAnUnknownRow() {
    val states = read(mapOf("root" to background()), active = null)
    assertTrue(states.getValue("root").active)
    assertTrue(states.getValue("root").uncertain)
    assertEquals(0, TaskSummary.of(states).running)
    val lagoon = LagoonState(sessions = listOf(root), tasks = states, connected = true)
    assertEquals(SessionStatus.NONE, lagoon.sessionStatus(root))
    assertTrue(lagoon.livePresentedTasks.isEmpty())
    // The next successful read settles it without inventing a result.
    assertEquals(TaskPhase.COMPLETED, read(states).getValue("root").phase)
  }

  @Test fun oneUnprovableSiblingCannotFreezeTheOtherRoots() {
    val states = read(mapOf("root" to background(), "other" to TaskState("other", TaskPhase.THINKING, since = 100)),
      sessions = listOf(root), active = emptySet())
    assertFalse(states.getValue("root").active)
    // "other" has no identity in this read: it stays a retry hint, but its own row is not unknown.
    val lagoon = LagoonState(sessions = listOf(root), tasks = states, connected = true)
    assertEquals(SessionStatus.NONE, lagoon.sessionStatus(root))
    assertEquals(0, TaskSummary.of(states).running)
  }

  @Test fun aMissingChildIdentityIsNotGuessedIntoAResult() {
    val states = read(mapOf("root" to background(), "child" to TaskState("child", TaskPhase.SUBAGENT)))
    // The root's own execution is decided by `session.active`, not by descendants.
    assertFalse(states.getValue("root").active)
    // The child has no identity in this read, so it stays a retry hint: never a result, and never a
    // live claim the summary or the home row would render.
    assertTrue(states.getValue("child").uncertain)
    assertEquals(0, TaskSummary.of(states).running)
  }

  @Test fun confirmedDeletedChildNoLongerPinsItsParent() {
    val states = read(mapOf("root" to background(), "child" to TaskState("child", TaskPhase.SUBAGENT)), deleted = setOf("child"))
    assertNull(states["child"])
    assertFalse(states.getValue("root").active)
  }

  @Test fun persistedLegacyRootsConvergeButJustSubmittedTasksGetAGraceWindow() {
    val old = TaskState("root", TaskPhase.THINKING, since = 100)
    assertFalse(read(mapOf("root" to old)).getValue("root").active)
    assertTrue(read(mapOf("root" to old.copy(since = 19_999))).getValue("root").active)
    // A session that never ran has no outcome: it settles quietly instead of claiming success.
    val neverRan = root.copy(idle = 0, outcome = null)
    val noBaseline = read(mapOf("root" to old), sessions = listOf(neverRan))
    assertFalse(noBaseline.getValue("root").active)
    assertNull(neverRan.transitionNotice(old.since))
    assertEquals(0, TaskSummary.of(noBaseline).running)
  }

  @Test fun terminalChildRecordsSurviveRepeatedReadsAndPersistAcrossRestart() {
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    val parents = mapOf("child" to "root")
    val states = read(mapOf("root" to background(), "child" to TaskState("child", TaskPhase.SUBAGENT)), listOf(root, child))
    states.values.forEach { store.rememberTask("a", it, parents[it.sessionId]) }
    val restored = read(store.taskStates("a"), listOf(root, child))
    assertFalse(restored.getValue("child").active)
    assertNull(restored["root"])
    // A failed read is not durable state: the store keeps what this device observed, nothing else.
    store.rememberTask("b", background().copy(uncertain = true))
    assertFalse(store.taskStates("b").getValue("root").uncertain)
  }

  @Test fun trackedShellDirectoriesWinOverMoreThanEightIdleProjects() {
    val projects = (1..10).map { Project("p$it", "/idle/$it", "Idle") }
    val directories = TaskReconciliation.shellDirectories(mapOf("root" to background()), emptyMap(), listOf(root), projects, emptySet())
    assertEquals("/repo", directories.first())
    assertEquals(8, directories.size)
  }

  /**
   * A family sweep that cannot finish only delays descendant identities. It must not hold the root
   * open, and it must not mark anything as unverifiable for display.
   */
  @Test fun aFailedDescendantSweepDoesNotHoldTheRootOpen() {
    val states = read(mapOf("root" to background()))
    assertFalse(states.getValue("root").active)
    assertEquals(0, TaskSummary.of(states).running)
    assertEquals(emptySet<String>(), SessionActivityRules.backgroundRoots(states, emptyMap(), emptyMap()))
  }

  @Test fun persistedActivityWithoutAFreshnessTimestampIsNotRenderedAsUnknownAfterRestart() {
    val states = TaskReconciliation.restored(mapOf("root" to background()))
    val state = LagoonState(sessions = listOf(root), tasks = states, connected = false, lastContactAt = 0)
    assertEquals(SessionStatus.NONE, state.sessionStatus(root))
    assertTrue(state.livePresentedTasks.isEmpty())
    assertEquals(0, TaskSummary.of(states).running)
  }

  @Test fun retiringAMissingSessionDoesNotDestroyItsUnsentDraft() {
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    store.rememberTask("a", background())
    store.rememberDraft("a", "root", "Unsent work")
    store.forgetTask("a", "root")
    assertNull(store.taskStates("a")["root"])
    assertEquals("Unsent work", store.draft("a", "root"))
  }

  @Test fun diskOnlyWaitingPhasesAreNotActionableWithoutTheirRequests() {
    listOf(TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION, TaskPhase.WAITING_SERVER).forEach { phase ->
      val tasks = TaskReconciliation.restored(mapOf("root" to TaskState("root", phase)))
      val state = LagoonState(sessions = listOf(root), tasks = tasks)
      assertTrue(state.livePresentedTasks.isEmpty())
      assertTrue(TaskSummary.of(state.presentedTasks).isEmpty)
      assertEquals(SessionStatus.NONE, state.sessionStatus(root))
    }
  }

  @Test fun acceptedInputCannotInheritOrCompleteAnObsoleteBackgroundRun() {
    val accepted = PendingInputs.accepted("root", background().copy(uncertain = true), root.idle, now = 20_000)
    assertTrue(accepted.pendingInput)
    assertEquals(TaskPhase.WAITING_SERVER, accepted.phase)
    assertEquals(20_000L, accepted.since)
    val queued = TaskReconciliation.reconcile(mapOf("root" to accepted), listOf(root), emptySet(), emptyMap(),
      inbox = PendingInputs.Read(setOf("root"), setOf("root")))
    assertTrue(queued.getValue("root").active)
    assertTrue(queued.getValue("root").pendingInput)
    val failedRead = TaskReconciliation.reconcile(queued, listOf(root), emptySet(), emptyMap(),
      inbox = PendingInputs.Read())
    assertTrue(failedRead.getValue("root").uncertain)
    val terminal = TaskReducer.event("root", "session.execution.succeeded", org.json.JSONObject(), accepted, 210)!!
    assertEquals(TaskPhase.WAITING_SERVER, terminal.phase)
    assertTrue(terminal.pendingInput)
    val started = TaskReducer.status("root", true, accepted, root.idle)
    assertEquals(TaskPhase.THINKING, started.phase)
  }

  @Test fun obsoleteCachedTerminalChildrenDoNotBlockACompleteFreshSweep() {
    val cached = child.copy(idle = 1)
    val states = TaskReconciliation.reconcile(mapOf("root" to background(), "child" to TaskReducer.terminal("child", "succeeded", null)),
      listOf(root, cached), emptySet(), mapOf("child" to "root"))
    assertFalse(states.getValue("root").active)
  }

  @Test fun aShellWithoutAPreviousRootIsOnlyBackgroundActivity() {
    val shell = ShellJob("sh", "root", "sleep", 1, "/repo")
    val states = TaskReconciliation.reconcile(emptyMap(), listOf(root), emptySet(), emptyMap())
    assertNull(states["root"])
    assertEquals(0, TaskSummary.of(states).running)
    assertEquals(setOf("root"), SessionActivityRules.backgroundRoots(states, mapOf("sh" to shell), emptyMap()))
  }
}
