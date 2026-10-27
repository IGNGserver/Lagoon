package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background activity is derived from running shells and descendant sessions, but it never changes
 * the root session's execution state. These tests pin that separation and the metadata helpers that
 * discover child identities from a parent transcript.
 */
class BackgroundWorkTest {
  private fun shell(id: String, session: String, command: String = "sleep 100") =
    ShellJob(id, session, command, 1, "/repo")

  @Test fun aRunningShellAfterTheRootSettledIsBackgroundActivityNotRootRunning() {
    val settled = emptyMap<String, TaskState>()
    val shells = mapOf("sh_1" to shell("sh_1", "root"))
    val roots = SessionActivityRules.backgroundRoots(settled, shells, emptyMap())
    assertEquals(setOf("root"), roots)
    assertTrue(SessionActivityRules.rootStates(settled, emptyMap()).isEmpty())
    assertTrue(TaskSummary.of(settled, emptyList(), emptyMap()).isEmpty)
  }

  @Test fun aRunningChildDoesNotCountAsItsRootRunning() {
    val parents = mapOf("child" to "root")
    val tasks = mapOf(
      "root" to TaskState("root", TaskPhase.COMPLETED, since = 100),
      "child" to TaskState("child", TaskPhase.THINKING, since = 110)
    )
    assertTrue(SessionActivityRules.rootStates(tasks, parents).isEmpty())
    assertEquals(setOf("root"), SessionActivityRules.backgroundRoots(tasks, emptyMap(), parents))
    val summary = TaskSummary.of(tasks, emptyList(), parents)
    assertEquals(0, summary.running)
  }

  @Test fun aWaitingChildRollsUpToItsRootButARunningSiblingDoesNot() {
    val parents = mapOf("child" to "root", "sibling" to "root")
    val tasks = mapOf(
      "root" to TaskState("root", TaskPhase.COMPLETED, since = 100),
      "child" to TaskState("child", TaskPhase.WAITING_PERMISSION, since = 110),
      "sibling" to TaskState("sibling", TaskPhase.THINKING, since = 120)
    )
    val roots = SessionActivityRules.rootStates(tasks, parents)
    assertEquals(setOf("root"), roots.keys)
    assertEquals(TaskPhase.WAITING_PERMISSION, roots.getValue("root").phase)
    val summary = TaskSummary.of(tasks, emptyList(), parents)
    assertEquals(0, summary.running)
    assertEquals(1, summary.waiting)
  }

  @Test fun aRunningRootRemainsRunningWhileAChildAlsoRuns() {
    val parents = mapOf("child" to "root")
    val tasks = mapOf(
      "root" to TaskState("root", TaskPhase.THINKING, since = 100),
      "child" to TaskState("child", TaskPhase.THINKING, since = 110)
    )
    assertEquals(1, TaskSummary.of(tasks, emptyList(), parents).running)
    assertTrue(SessionActivityRules.backgroundRoots(tasks, emptyMap(), parents).isEmpty())
  }

  @Test fun multipleActiveChildrenKeepTheSameRootBackgroundUntilTheLastOneStops() {
    val parents = mapOf("child_a" to "root", "child_b" to "root")
    val tasks = mapOf(
      "root" to TaskState("root", TaskPhase.COMPLETED),
      "child_a" to TaskState("child_a", TaskPhase.THINKING),
      "child_b" to TaskState("child_b", TaskPhase.SUBAGENT)
    )
    assertEquals(setOf("root"), SessionActivityRules.backgroundRoots(tasks, emptyMap(), parents))
    val afterFirstChild = tasks - "child_a"
    assertEquals(setOf("root"), SessionActivityRules.backgroundRoots(afterFirstChild, emptyMap(), parents))
    assertEquals(emptySet<String>(), SessionActivityRules.backgroundRoots(tasks - "child_a" - "child_b", emptyMap(), parents))
  }

  @Test fun backgroundToolMetadataKeepsMultipleSubagentsAndSyntheticResultsClearThem() {
    val parsed = JSONObject("""{"type":"tool","name":"task","state":{"status":"completed","metadata":{"status":"running","sessionID":"child_a"}}}""").toAssistantPart("call_a")
    assertTrue(parsed.background)
    assertEquals("child_a", parsed.target)
    val first = JSONObject("""{"name":"task","metadata":{"status":"running","sessionID":"child_a"}}""")
    val second = JSONObject("""{"name":"subagent","metadata":{"status":"running","sessionID":"child_b"}}""")
    assertEquals("child_a", BackgroundWork.backgroundSubagentId("session.tool.success", first))
    assertEquals("child_b", BackgroundWork.backgroundSubagentId("session.tool.success", second))
    assertNull(BackgroundWork.backgroundSubagentId("session.tool.success", JSONObject("""{"name":"task","metadata":{"status":"completed","sessionID":"child_a"}}""")))

    val messages = listOf(Message("m", "assistant", 20, listOf(
      MessagePart("a", "tool", tool = "task", target = "child_a", background = true),
      MessagePart("b", "tool", tool = "subagent", target = "child_b", background = true)
    )))
    assertEquals(mapOf("child_a" to 20L, "child_b" to 20L), BackgroundWork.messageSubagents("root", messages))
    val completed = messages + Message("n", "notice", 30, emptyList(), notice = Notice("已完成", target = "child_a"))
    assertEquals(mapOf("child_b" to 20L), BackgroundWork.messageSubagents("root", completed))
    assertEquals("child_a", BackgroundWork.completedSubagentId("session.synthetic",
      JSONObject("""{"metadata":{"source":"subagent","childID":"child_a"}}""")))
  }

  @Test fun executorSucceedsEvenWhenBackgroundWorkStillExists() {
    val started = TaskReducer.event("s", "session.execution.started", JSONObject("{}"), null, 100)!!
    val settled = TaskReducer.event("s", "session.execution.succeeded", JSONObject("{}"), started, 130)!!
    assertEquals(TaskPhase.COMPLETED, settled.phase)
    assertFalse(settled.active)
  }

  @Test fun idleSignalAlwaysEndsTheRootExecution() {
    val running = TaskState("s", TaskPhase.THINKING, since = 10, activeAt = 15)
    val idle = TaskReducer.event("s", "session.idle", JSONObject("{}"), running, 130)!!
    assertEquals(TaskPhase.COMPLETED, idle.phase)
    assertFalse(idle.active)
  }

  @Test fun familyTreeContainsRunningAndHistoricalChildrenWithDepth() {
    val root = Session("root", "/repo", "Root", 1)
    val child = Session("child", "/repo", "Child", 2, parentId = "root", outcome = "succeeded", idle = 20)
    val grandchild = Session("g", "/repo", "Grandchild", 3, parentId = "child")
    val sessions = listOf(root, child, grandchild)
    val tasks = mapOf("g" to TaskState("g", TaskPhase.THINKING, since = 10))
    val entries = SessionActivityRules.subSessions("root", sessions, tasks, mapOf("child" to "root", "g" to "child"))
    assertEquals(listOf("child", "g"), entries.map { it.session.id })
    assertEquals(SubSessionPhase.RUNNING, entries.first { it.session.id == "g" }.phase)
    assertEquals(SubSessionPhase.COMPLETED, entries.first { it.session.id == "child" }.phase)
    assertEquals(2, entries.first { it.session.id == "g" }.depth)
  }

  @Test fun shellInfoParsesRunningListItemsAndRejectsFinishedOrUnattributedOnes() {
    val running = JSONObject("""{"id":"sh_1","status":"running","command":"make","metadata":{"sessionID":"ses_1"},"time":{"started":5}}""")
      .toShellJob("/repo")!!
    assertEquals("ses_1", running.sessionId)
    assertEquals("make", running.command)
    assertEquals(5L, running.started)
    assertEquals("/repo", running.directory)
    assertNull(JSONObject("""{"id":"sh_1","status":"exited","exit":0}""").toShellJob("/repo"))
    assertNull(JSONObject("""{"id":"sh_1","status":"running","command":"make","time":{"started":5}}""").toShellJob("/repo"))
    val snapshot = JSONObject("""{"id":"sh_1","status":"exited","command":"make","exit":0,"time":{"started":5,"completed":9}}""")
      .toShellSnapshot("/repo")!!
    assertEquals("exited", snapshot.status)
    assertEquals(0, snapshot.exit)
    assertFalse(snapshot.running)
  }
}
