package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class TaskReconciliationTest {
  private val root = Session("root", "/repo", "Root", 200, idle = 200, outcome = "succeeded")
  private val child = Session("child", "/repo", "Child", 210, parentId = "root", idle = 210, outcome = "succeeded")
  private fun background(baseline: Long = -1) = TaskState("root", TaskPhase.THINKING,
    TaskState.RUNNING_DETAIL, since = 100, activeAt = baseline)
  private fun read(tasks: Map<String, TaskState>, sessions: List<Session> = listOf(root),
    active: Set<String>? = emptySet(), directories: Set<String> = setOf("/repo"),
    verified: Set<String> = sessions.map { it.id }.toSet(), deleted: Set<String> = emptySet()) =
    TaskReconciliation.reconcile(tasks, sessions, active, emptyMap(), mapOf("child" to "root"), verified, directories, deleted, now = 20_000)

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

  @Test fun failedOrSkippedReadsAreUnknownNotFinishedOrStillSpinning() {
    val previous = mapOf("root" to background())
    listOf(read(previous, active = null), read(previous, directories = emptySet()), read(previous, verified = emptySet())).forEach { states ->
      assertTrue(states.getValue("root").active) // retain evidence for the next retry
      assertTrue(states.getValue("root").uncertain)
      assertEquals(0, TaskSummary.of(states).running)
      val lagoon = LagoonState(sessions = listOf(root), tasks = states, connected = true)
      assertEquals(SessionStatus.UNKNOWN, lagoon.sessionStatus(root))
      assertTrue(lagoon.livePresentedTasks.isEmpty())
      assertFalse(read(states).getValue("root").active)
    }
  }

  @Test fun aMissingChildIdentityPreventsFalseCompletion() {
    val states = read(mapOf("root" to background(), "child" to TaskState("child", TaskPhase.SUBAGENT)))
    assertTrue(states.getValue("root").uncertain)
    assertTrue(states.getValue("child").uncertain)
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
    val noBaseline = read(mapOf("root" to old), sessions = listOf(root.copy(idle = 0, outcome = null)))
    assertTrue(noBaseline.getValue("root").uncertain)
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
    val uncertain = background().copy(uncertain = true)
    store.rememberTask("b", uncertain)
    assertEquals(uncertain, store.taskStates("b")["root"])
  }

  @Test fun trackedShellDirectoriesWinOverMoreThanEightIdleProjects() {
    val projects = (1..10).map { Project("p$it", "/idle/$it", "Idle") }
    val directories = TaskReconciliation.shellDirectories(mapOf("root" to background()), emptyMap(), listOf(root), projects, emptySet())
    assertEquals("/repo", directories.first())
    assertEquals(8, directories.size)
  }

  @Test fun failedDescendantDiscoveryIsNotAnEmptyBackgroundFamily() {
    val states = TaskReconciliation.reconcile(mapOf("root" to background()), listOf(root), emptySet(), emptyMap(),
      emptyMap(), setOf("root"), setOf("/repo"), incompleteRoots = setOf("root"))
    assertTrue(states.getValue("root").uncertain)
    assertEquals(0, TaskSummary.of(states).running)
  }

  @Test fun persistedActivityWithoutAFreshnessTimestampIsUnverifiedAfterRestart() {
    val states = TaskReconciliation.restored(mapOf("root" to background()))
    val state = LagoonState(sessions = listOf(root), tasks = states, connected = false, lastContactAt = 0)
    assertEquals(SessionStatus.UNKNOWN, state.sessionStatus(root))
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
      assertEquals(SessionStatus.UNKNOWN, state.sessionStatus(root))
    }
  }

  @Test fun acceptedInputCannotInheritOrCompleteAnObsoleteBackgroundRun() {
    val accepted = PendingInputs.accepted("root", background().copy(uncertain = true), root.idle, now = 20_000)
    assertTrue(accepted.pendingInput)
    assertEquals(TaskPhase.WAITING_SERVER, accepted.phase)
    assertEquals(20_000L, accepted.since)
    val queued = TaskReconciliation.reconcile(mapOf("root" to accepted), listOf(root), emptySet(), emptyMap(),
      emptyMap(), setOf("root"), setOf("/repo"), inbox = PendingInputs.Read(setOf("root"), setOf("root")))
    assertTrue(queued.getValue("root").active)
    assertTrue(queued.getValue("root").pendingInput)
    val failedRead = TaskReconciliation.reconcile(queued, listOf(root), emptySet(), emptyMap(), emptyMap(),
      setOf("root"), setOf("/repo"), inbox = PendingInputs.Read())
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
      listOf(root, cached), emptySet(), emptyMap(), mapOf("child" to "root"), setOf("root"), setOf("/repo"))
    assertFalse(states.getValue("root").active)
  }

  @Test fun aShellWithoutAPreviousRootIsOnlyBackgroundActivity() {
    val shell = ShellJob("sh", "root", "sleep", 1, "/repo")
    val states = TaskReconciliation.reconcile(emptyMap(), listOf(root), emptySet(), mapOf("sh" to shell),
      emptyMap(), setOf("root"), emptySet())
    assertNull(states["root"])
    assertEquals(0, TaskSummary.of(states).running)
    assertEquals(setOf("root"), SessionActivityRules.backgroundRoots(states, mapOf("sh" to shell), emptyMap()))
  }
}
