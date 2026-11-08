package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the durable conversation projections the composer status rail renders. */
class ComposerContextTest {
  @Test fun absentTodosKeyMeansNoCapabilityWhileExplicitNullMeansCleared() {
    // 旧 Host 没有 todos 投影：不能把它当成“清单为空”而覆盖已经读到的列表。
    assertNull(JSONObject("""{"inbox":{}}""").todoProjection())
    assertEquals(emptyList<RemoteTodo>(), JSONObject("""{"todos":null}""").todoProjection())
    assertEquals(emptyList<RemoteTodo>(), JSONObject("""{"todos":[]}""").todoProjection())
  }

  @Test fun todoRowsTolerateUnknownStatusesAndBlankContent() {
    val todos = JSONObject(
      """{"todos":[{"content":" 写测试 ","status":"completed"},{"content":"改 UI","status":"in_progress"},{"content":"旧 Host","status":"weird"},{"content":"   ","status":"pending"}]}"""
    ).todoProjection()!!
    assertEquals(listOf("写测试", "改 UI", "旧 Host"), todos.map { it.content })
    assertEquals(listOf("completed", "in_progress", "pending"), todos.map { it.status })
    assertTrue(todos[0].done)
    assertTrue(todos[1].active)
  }

  @Test fun progressCountsCompletedItemsOnly() {
    val todos = listOf(
      RemoteTodo("a", RemoteTodo.STATUS_DONE),
      RemoteTodo("b", RemoteTodo.STATUS_ACTIVE),
      RemoteTodo("c", RemoteTodo.STATUS_DONE)
    )
    assertEquals(TodoProgress(done = 2, total = 3), todos.todoProgress())
    assertFalse(todos.todoProgress().complete)
    assertTrue(listOf(RemoteTodo("a", RemoteTodo.STATUS_DONE)).todoProgress().complete)
    assertFalse(emptyList<RemoteTodo>().todoProgress().complete)
  }

  @Test fun goalProjectionCarriesDurableStateWithoutActivation() {
    val goal = JSONObject(
      """{"goal":{"goal":{"id":"g1","revision":2,"objective":"收尾","phase":"blocked","blockedReason":{"code":"missing","message":"缺少凭据"}},"roundsStarted":3,"createdAt":1,"updatedAt":2}}"""
    ).goalProjection()!!
    assertEquals("g1", goal.id)
    assertEquals(2, goal.revision)
    assertEquals("收尾", goal.objective)
    assertEquals("缺少凭据", goal.blockedReason)
    assertNull(goal.activation)
    assertTrue(goal.blocked)
    assertEquals("目标被阻塞", goal.label)
  }

  @Test fun absentOrNullGoalProjectionMeansNoCurrentGoal() {
    assertNull(JSONObject("""{"inbox":{}}""").goalProjection())
    assertNull(JSONObject("""{"goal":null}""").goalProjection())
    // 空对象不是目标：不能渲染一条没有 id 的“目标”胶囊。
    assertNull(JSONObject("""{"goal":{}}""").goalProjection())
  }

  @Test fun onlyAnExplicitlyDisarmedActivationReadsAsPausedContinuation() {
    // 投影没有 activation；把它误读成 disarmed 会让活动中的目标显示“已暂停续跑”。
    assertEquals("目标进行中", RemoteGoal("g1", 1, "收尾", "active", activation = null).label)
    assertEquals("目标进行中", RemoteGoal("g1", 1, "收尾", "active", activation = "armed").label)
    assertEquals("目标已暂停续跑", RemoteGoal("g1", 1, "收尾", "active", activation = "disarmed").label)
    assertEquals("目标已完成", RemoteGoal("g1", 1, "收尾", "complete").label)
  }
}
