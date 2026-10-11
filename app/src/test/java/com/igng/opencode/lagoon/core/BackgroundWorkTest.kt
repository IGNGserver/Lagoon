package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台工作派生（对齐官方 `packages/app/src/session/requests/background.ts`）：后台 shell 不是会话，
 * 不在 `/api/session/active` 里；模型自发 `background=true` 的工具也不会写“转后台”合成消息标记。
 * 这里覆盖折算、家族汇总、对账豁免与 reducer 的后台分支。
 */
class BackgroundWorkTest {
  private fun shell(id: String, session: String, command: String = "sleep 100") =
    ShellJob(id, session, command, 1, "/repo")

  @Test fun settledRootWithRunningShellFoldsToBackgroundRunning() {
    val tasks = mapOf("root" to TaskState("root", TaskPhase.COMPLETED, "任务已完成", since = 10))
    val folded = BackgroundWork.fold(tasks, mapOf("sh_1" to shell("sh_1", "root")), emptyMap())
    val root = folded.getValue("root")
    assertTrue(root.active)
    assertTrue(root.background)
    assertEquals(TaskState.BACKGROUND_DETAIL, root.detail)
    assertEquals(10L, root.since)
  }

  @Test fun aShellOnAChildRollsUpToItsRoot() {
    val tasks = mapOf("root" to TaskState("root", TaskPhase.COMPLETED))
    val parents = mapOf("child" to "root", "grandchild" to "child")
    val folded = BackgroundWork.fold(tasks, mapOf("sh_1" to shell("sh_1", "grandchild")), parents)
    assertTrue(folded.getValue("root").background)
    assertFalse(folded.containsKey("child"))
    assertFalse(folded.containsKey("grandchild"))
  }

  @Test fun aRunningRootIsNeverFoldedEvenWhileAChildRuns() {
    val tasks = mapOf("root" to TaskState("root", TaskPhase.THINKING), "child" to TaskState("child", TaskPhase.THINKING))
    val folded = BackgroundWork.fold(tasks, emptyMap(), mapOf("child" to "root"))
    assertSame(tasks, folded)
    // 根自身仍在跑：子代理活跃只是前台阻塞，不折算“后台运行中”。
    assertFalse(folded.getValue("root").background)
  }

  @Test fun anActiveChildAfterTheRootSettledIsBackgroundWork() {
    val tasks = mapOf("root" to TaskState("root", TaskPhase.COMPLETED), "child" to TaskState("child", TaskPhase.THINKING))
    val parents = mapOf("child" to "root")
    assertTrue(BackgroundWork.hasBackgroundWork("root", tasks, emptyMap(), parents))
    val folded = BackgroundWork.fold(tasks, emptyMap(), parents)
    assertTrue(folded.getValue("root").active)
    assertTrue(folded.getValue("root").background)
  }

  @Test fun multipleActiveChildrenKeepTheSameRootBackgroundUntilTheLastOneStops() {
    val parents = mapOf("child_a" to "root", "child_b" to "root")
    val tasks = mapOf(
      "root" to TaskState("root", TaskPhase.COMPLETED),
      "child_a" to TaskState("child_a", TaskPhase.THINKING),
      "child_b" to TaskState("child_b", TaskPhase.SUBAGENT)
    )
    assertTrue(BackgroundWork.hasBackgroundWork("root", tasks, emptyMap(), parents))
    val folded = BackgroundWork.fold(tasks, emptyMap(), parents)
    assertTrue(folded.getValue("root").background)
    assertEquals(setOf("root"), TaskSummary.backgroundRoots(folded, parents))

    val afterFirstChild = folded - "child_a"
    assertTrue(BackgroundWork.hasBackgroundWork("root", afterFirstChild, emptyMap(), parents))
    assertTrue(afterFirstChild.getValue("root").background)
    assertEquals(setOf("root"), TaskSummary.backgroundRoots(afterFirstChild, parents))
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

  @Test fun foldedTasksAreKeptWhenWorkDisappearsForTheGraceWindow() {
    val folded = mapOf("root" to TaskState("root", TaskPhase.THINKING, TaskState.BACKGROUND_DETAIL, background = true))
    assertSame(folded, BackgroundWork.fold(folded, emptyMap(), emptyMap()))
    assertSame(folded, BackgroundWork.fold(folded, mapOf("sh_1" to shell("sh_1", "root")), emptyMap()))
  }

  @Test fun workRootsListShellOwnersAndSettledRootsWithActiveDescendants() {
    val tasks = mapOf("a" to TaskState("a", TaskPhase.COMPLETED), "b" to TaskState("b", TaskPhase.THINKING))
    val parents = mapOf("b" to "a", "c" to "z")
    val roots = BackgroundWork.workRoots(tasks, mapOf("sh_1" to shell("sh_1", "c")), parents)
    assertEquals(setOf("a", "z"), roots)
  }

  @Test fun derivedBackgroundWorkKeepsSettledRunInBackgroundUntilWake() {
    val started = TaskReducer.event("s", "session.execution.started", JSONObject("{}"), null, 100)!!
    // 后台 shell 仍在跑：根执行 succeed 只是本轮结束，不是任务完成。
    val settled = TaskReducer.event("s", "session.execution.succeeded", JSONObject("{}"), started, 130, backgroundWork = true)!!
    assertTrue(settled.active)
    assertTrue(settled.background)
    assertEquals(TaskState.BACKGROUND_DETAIL, settled.detail)
    // 后台完成唤醒 AI（新的 execution.started）后恢复普通运行，最终正常收尾。
    val resumed = TaskReducer.event("s", "session.execution.started", JSONObject("{}"), settled, 200)!!
    assertFalse(resumed.background)
    assertEquals(TaskPhase.COMPLETED, TaskReducer.event("s", "session.execution.succeeded", JSONObject("{}"), resumed, 240)!!.phase)
  }

  @Test fun withoutDerivedWorkTheRunStillCompletes() {
    val started = TaskReducer.event("s", "session.execution.started", JSONObject("{}"), null, 100)!!
    val settled = TaskReducer.event("s", "session.execution.succeeded", JSONObject("{}"), started, 130)!!
    assertEquals(TaskPhase.COMPLETED, settled.phase)
    assertFalse(settled.background)
  }

  @Test fun anIdleSignalWithDerivedWorkKeepsBackgroundRunning() {
    val running = TaskState("s", TaskPhase.THINKING, since = 10, activeAt = 15)
    val idle = TaskReducer.event("s", "session.idle", JSONObject("{}"), running, 130, backgroundWork = true)!!
    assertTrue(idle.active)
    assertTrue(idle.background)
  }

  @Test fun shellInfoParsesRunningListItemsAndRejectsFinishedOrUnattributedOnes() {
    val running = JSONObject("""{"id":"sh_1","status":"running","command":"make","metadata":{"sessionID":"ses_1"},"time":{"started":5}}""")
      .toShellJob("/repo")!!
    assertEquals("ses_1", running.sessionId)
    assertEquals("make", running.command)
    assertEquals(5L, running.started)
    assertEquals("/repo", running.directory)
    // shell.exited / shell.deleted 事件只有 id，不产生新条目；已完成或没有会话归属的条目也必须拒绝。
    assertNull(JSONObject("""{"id":"sh_1","status":"exited","exit":0}""").toShellJob("/repo"))
    assertNull(JSONObject("""{"id":"sh_1","status":"running","command":"make","time":{"started":5}}""").toShellJob("/repo"))
  }

  @Test fun aggregatePrefersTheBackgroundDetailWhenBothPhasesTie() {
    val tasks = mapOf(
      "root" to TaskState("root", TaskPhase.THINKING, TaskState.BACKGROUND_DETAIL, background = true),
      "child" to TaskState("child", TaskPhase.THINKING)
    )
    val aggregate = TaskSummary.aggregate(tasks, mapOf("child" to "root"))
    assertEquals(TaskState.BACKGROUND_DETAIL, aggregate.getValue("root").detail)
  }

  @Test fun backgroundRootsMarksFoldedRootsAndRolledUpChildren() {
    val folded = mapOf("root" to TaskState("root", TaskPhase.THINKING, TaskState.BACKGROUND_DETAIL, background = true))
    assertEquals(setOf("root"), TaskSummary.backgroundRoots(folded, emptyMap()))
    val childOnly = mapOf("root" to TaskState("root", TaskPhase.COMPLETED), "child" to TaskState("child", TaskPhase.THINKING))
    assertEquals(setOf("root"), TaskSummary.backgroundRoots(childOnly, mapOf("child" to "root")))
  }
}
