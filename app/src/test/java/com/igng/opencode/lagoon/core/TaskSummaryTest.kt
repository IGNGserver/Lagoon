package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSummaryTest {
  private fun tasks(vararg phases: Pair<String, TaskPhase>): Map<String, TaskState> =
    phases.associate { (id, phase) -> id to TaskState(id, phase) }

  /**
   * Results are unread ledger entries, not task phases: COMPLETED/FAILED fixtures become notices.
   * Any other phase stays live task state.
   */
  private fun summarize(tasks: Map<String, TaskState>, titles: Map<String, String> = emptyMap(), viewed: Set<String> = emptySet()): TaskSummary {
    val results = tasks.values.filter { it.phase == TaskPhase.COMPLETED || it.phase == TaskPhase.FAILED }
    val notices = results.map { SessionNotice("n-${it.sessionId}", it.sessionId, it.since, it.phase == TaskPhase.FAILED, it.sessionId in viewed) }
    return TaskSummary.of(tasks - results.map { it.sessionId }.toSet(), notices, titles = titles)
  }

  @Test fun emptySummaryHidesIsland() {
    assertTrue(TaskSummary.of(emptyMap()).isEmpty)
    assertNull(TaskSummary.of(emptyMap()).text)
    assertTrue(summarize(tasks("a" to TaskPhase.IDLE, "b" to TaskPhase.DISCONNECTED)).isEmpty)
  }

  @Test fun aRunningChildIsBackgroundActivityNotRootRunning() {
    val tasks = mapOf(
      "root" to TaskState("root", TaskPhase.COMPLETED, since = 10),
      "child" to TaskState("child", TaskPhase.TOOL, since = 20)
    )
    val summary = TaskSummary.of(tasks, parents = mapOf("child" to "root"))
    assertEquals(0, summary.running)
    assertEquals(setOf("root"), SessionActivityRules.backgroundRoots(tasks, emptyMap(), mapOf("child" to "root")))
  }

  @Test fun aWaitingChildRollsUpAsAttentionButARunningOneDoesNot() {
    val parents = mapOf("child" to "root", "other" to "root")
    val tasks = mapOf(
      "root" to TaskState("root", TaskPhase.COMPLETED),
      "child" to TaskState("child", TaskPhase.WAITING_QUESTION),
      "other" to TaskState("other", TaskPhase.THINKING)
    )
    val summary = TaskSummary.of(tasks, parents = parents)
    assertEquals(0, summary.running)
    assertEquals(1, summary.waiting)
  }

  @Test fun alwaysShowsRunningAndCompletedBaseline() {
    val summary = summarize(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.TOOL,
      "c" to TaskPhase.COMPLETED
    ))
    assertEquals(2, summary.running)
    assertEquals(1, summary.completed)
    assertEquals(0, summary.waiting)
    assertEquals("2个运行中，1个已完成", summary.text)
  }

  @Test fun zeroCountsStillRenderBaselineWithoutWaitingOrFailed() {
    val summary = summarize(tasks("a" to TaskPhase.COMPLETED))
    assertEquals("0个运行中，1个已完成", summary.text)
    assertFalse(summary.text!!.contains("待回复"))
    assertFalse(summary.text!!.contains("失败"))
  }

  @Test fun permissionAndQuestionBothCountAsWaiting() {
    val summary = summarize(tasks(
      "a" to TaskPhase.WAITING_PERMISSION,
      "b" to TaskPhase.WAITING_QUESTION,
      "c" to TaskPhase.SUBAGENT,
      "d" to TaskPhase.TESTING
    ))
    assertEquals(2, summary.running)
    assertEquals(2, summary.waiting)
    assertEquals("2个运行中，0个已完成，2个待回复", summary.text)
  }

  @Test fun failedIsReportedSeparatelyFromCompleted() {
    val summary = summarize(tasks(
      "a" to TaskPhase.FAILED,
      "b" to TaskPhase.COMPLETED
    ))
    assertEquals(0, summary.running)
    assertEquals(1, summary.completed)
    assertEquals(0, summary.waiting)
    assertEquals(1, summary.failed)
    assertEquals("0个运行中，1个已完成，1个失败", summary.text)
  }

  @Test fun viewedResultsAreNotCounted() {
    val summary = summarize(tasks("done" to TaskPhase.COMPLETED, "boom" to TaskPhase.FAILED), viewed = setOf("done", "boom"))
    assertTrue(summary.isEmpty)
    assertNull(summary.text)
  }

  @Test fun viewingIsPerSession() {
    val summary = summarize(tasks("done" to TaskPhase.COMPLETED, "other" to TaskPhase.FAILED), viewed = setOf("done"))
    assertEquals(0, summary.completed)
    assertEquals(1, summary.failed)
    assertEquals("0个运行中，0个已完成，1个失败", summary.text)
  }

  @Test fun aSessionRunningAgainCountsOnlyAsRunning() {
    val summary = TaskSummary.of(tasks("s" to TaskPhase.THINKING), listOf(SessionNotice("old", "s", 1, false)))
    assertEquals(1, summary.running)
    assertEquals(0, summary.completed)
  }

  @Test fun severalUnreadResultsOfOneSessionCountOnceAndFailureWins() {
    val summary = TaskSummary.of(emptyMap(), listOf(SessionNotice("a", "s", 1, false), SessionNotice("b", "s", 2, true)))
    assertEquals(0, summary.completed)
    assertEquals(1, summary.failed)
    assertEquals(TaskPhase.FAILED, summary.items.single().phase)
  }

  @Test fun abortedIsNeitherCompletedNorFailed() {
    val summary = summarize(tasks("a" to TaskPhase.ABORTED))
    assertTrue(summary.isEmpty)
  }

  @Test fun shortTextForStatusChipIncludesCompleted() {
    val summary = summarize(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.WAITING_QUESTION,
      "c" to TaskPhase.FAILED
    ))
    assertEquals("1跑·0完·1待·1败", summary.shortText)

    val completedOnly = summarize(tasks("a" to TaskPhase.COMPLETED))
    assertEquals("0跑·1完", completedOnly.shortText)

    val runningAndCompleted = summarize(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.COMPLETED,
      "c" to TaskPhase.COMPLETED
    ))
    assertEquals("1跑·2完", runningAndCompleted.shortText)
  }

  @Test fun countLineCarriesEveryBucketInShortTextOrder() {
    val summary = TaskSummary(running = 2, completed = 1, waiting = 1, processing = 1, failed = 1)
    assertEquals("2 运行中 · 1 未读完成 · 1 待回复 · 1 处理中 · 1 失败", summary.countLine)
    // Baseline buckets are always present, even at zero, so the fallback title never hides them.
    assertEquals("0 运行中 · 0 未读完成", TaskSummary.EMPTY.countLine)
  }

  @Test fun fullSentenceOrderIsRunningCompletedWaitingFailed() {
    val summary = summarize(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.WAITING_PERMISSION,
      "c" to TaskPhase.COMPLETED,
      "d" to TaskPhase.FAILED
    ))
    assertEquals("1个运行中，1个已完成，1个待回复，1个失败", summary.text)
  }

  @Test fun takesAtMostThreeItemsWithPriorityOrder() {
    val tasksMap = mapOf(
      "comp1" to TaskState("comp1", TaskPhase.COMPLETED, since = 100),
      "comp2" to TaskState("comp2", TaskPhase.COMPLETED, since = 200),
      "run1" to TaskState("run1", TaskPhase.THINKING, since = 300),
      "run2" to TaskState("run2", TaskPhase.TOOL, since = 400),
      "wait1" to TaskState("wait1", TaskPhase.WAITING_QUESTION, since = 500),
      "fail1" to TaskState("fail1", TaskPhase.FAILED, since = 600)
    )
    val titles = mapOf(
      "comp1" to "完成任务1",
      "comp2" to "完成任务2",
      "run1" to "运行任务1",
      "run2" to "运行任务2",
      "wait1" to "等待任务1",
      "fail1" to "失败任务1"
    )
    val summary = summarize(tasksMap, titles = titles)
    assertEquals(3, summary.items.size)
    // Priority order: wait(5) > run(4) > fail(3) > comp(2)
    // Top 3 should be wait1, run2, run1
    assertEquals("wait1", summary.items[0].sessionId)
    assertEquals("等待任务1", summary.items[0].title)
    assertEquals("run2", summary.items[1].sessionId)
    assertEquals("run1", summary.items[2].sessionId)
  }
}
