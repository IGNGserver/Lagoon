package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStorePoolTest {
  @Test
  fun parentAndChildConversationsStayIsolatedDuringTransitions() {
    val pool = ConversationStorePool(capacity = 4)
    val parentSession = Session(id = "parent", title = "父会话", directory = "/repo", updated = 1000L)
    val childSession = Session(id = "child", title = "子会话", directory = "/repo", updated = 2000L, parentId = "parent")
    val parentMsg = Message(id = "m-parent", role = "user", created = 1000L, parts = listOf(MessagePart(id = "p1", type = "text", text = "重构前端")))
    val childMsg = Message(id = "m-child", role = "assistant", created = 2000L, parts = listOf(MessagePart(id = "p2", type = "text", text = "子任务已完成")))

    val parentState = LagoonState(
      serverId = "srv-1",
      sessionId = "parent",
      sessions = listOf(parentSession, childSession),
      messages = listOf(parentMsg),
      draft = "继续父会话"
    )
    pool.record(parentState)

    val childState = parentState.copy(
      sessionId = "child",
      messages = listOf(childMsg),
      draft = ""
    )
    pool.record(childState)

    // 当前全局状态在子会话时，侧滑预览或回退到父会话仍可零闪烁拿到父会话自己的消息与草稿
    val projectedParent = pool.project(childState, "srv-1", "parent")
    assertEquals("parent", projectedParent.sessionId)
    assertEquals("父会话", projectedParent.session?.title)
    assertEquals(listOf(parentMsg), projectedParent.messages)
    assertEquals("继续父会话", projectedParent.draft)

    // 同时子会话的离场动画仍保持子会话自己的消息
    val projectedChild = pool.project(parentState, "srv-1", "child")
    assertEquals("child", projectedChild.sessionId)
    assertEquals(listOf(childMsg), projectedChild.messages)
  }

  @Test
  fun completedTurnsReuseCachedTranscriptRowsWhileStreamingNewTurn() {
    val turn1User = Message(id = "m1", role = "user", created = 1_000L, parts = listOf(MessagePart(id = "p1", type = "text", text = "第一轮问题")))
    val turn1Assistant = Message(id = "m2", role = "assistant", created = 1_100L, completedAt = 1_500L, parts = listOf(MessagePart(id = "p2", type = "text", text = "第一轮回答")))
    val turn2User = Message(id = "m3", role = "user", created = 2_000L, parts = listOf(MessagePart(id = "p3", type = "text", text = "第二轮问题")))
    val turn2Streaming1 = Message(id = "m4", role = "assistant", created = 2_100L, parts = listOf(MessagePart(id = "p4", type = "text", text = "正在输出第一段")))
    val turn2Streaming2 = Message(id = "m4", role = "assistant", created = 2_100L, parts = listOf(MessagePart(id = "p4", type = "text", text = "正在输出第一段，追加第二段")))

    val firstBuild = TranscriptRows.build(listOf(turn1User, turn1Assistant, turn2User, turn2Streaming1), working = true)
    val secondBuild = TranscriptRows.build(listOf(turn1User, turn1Assistant, turn2User, turn2Streaming2), working = true)

    val firstTurn1Answer = firstBuild.first { it.key.startsWith("answer:m2/") }
    val secondTurn1Answer = secondBuild.first { it.key.startsWith("answer:m2/") }
    assertSame("已完成的历史 Turn 在后续流式刷新时应直接复用同一实例", firstTurn1Answer, secondTurn1Answer)
    assertTrue(secondBuild.any { it.text.contains("追加第二段") })
  }

  @Test
  fun clearingGoalSchedulesAndChangesUpdatesSlotInsteadOfKeepingStaleValues() {
    val pool = ConversationStorePool(capacity = 4)
    val session = Session(id = "s1", title = "会话", directory = "/repo", updated = 1_000L)
    val msg = Message(id = "m1", role = "user", created = 1_000L, parts = listOf(MessagePart(id = "p1", type = "text", text = "开始")))
    val goal = RemoteGoal(id = "g1", revision = 1, objective = "完成优化", phase = "active", maxRounds = 10, roundsStarted = 1, activation = "armed", blockedReason = null)
    val schedule = RemoteSchedule(id = "sch1", kind = "daily", title = "巡检", prompt = "跑巡检", scheduledAt = "2026-11-08T00:00:00Z", detail = "08:00")
    val change = FileChange(path = "a.kt", after = "", additions = 3, deletions = 1)

    val populated = LagoonState(
      serverId = "srv-1",
      sessionId = "s1",
      sessions = listOf(session),
      messages = listOf(msg),
      goal = goal,
      schedules = listOf(schedule),
      changes = listOf(change),
      resources = mapOf("messages" to ResourceStatus(ResourceState.READY))
    )
    pool.record(populated)

    // 初始空加载帧（如刚切回该会话）仍保留旧槽位，防止闪烁
    val loadingFrame = populated.copy(
      messages = emptyList(),
      goal = null,
      schedules = emptyList(),
      changes = emptyList(),
      resources = mapOf("messages" to ResourceStatus(ResourceState.LOADING))
    )
    pool.record(loadingFrame)
    assertEquals(goal, pool.get("srv-1", "s1")?.goal)

    // 正常就绪帧中清空 goal / schedules / changes 时，必须真正清空槽位
    val cleared = populated.copy(goal = null, schedules = emptyList(), changes = emptyList())
    pool.record(cleared)
    val slot = pool.get("srv-1", "s1")!!
    assertNull(slot.goal)
    assertTrue(slot.schedules.isEmpty())
    assertTrue(slot.changes.isEmpty())
  }

  @Test
  fun crossServerProjectionIsolatesServerScopedTasksAndPermissions() {
    val pool = ConversationStorePool(capacity = 4)
    val profileA = ServerProfile("srv-a", "Server A", "https://a.example")
    val profileB = ServerProfile("srv-b", "Server B", "https://b.example")
    val sessionA = Session(id = "sess-a", title = "A 会话", directory = "/repo-a", updated = 1_000L, serverId = "srv-a")
    val sessionB = Session(id = "sess-b", title = "B 会话", directory = "/repo-b", updated = 2_000L, serverId = "srv-b")
    val permB = PermissionRequest("perm-b", "sess-b", "/repo-b", "bash", "rm", emptyList())

    val stateA = LagoonState(
      profiles = listOf(profileA, profileB),
      serverId = "srv-a",
      sessionId = "sess-a",
      connected = true,
      sessions = listOf(sessionA),
      messages = listOf(Message("ma", "user", 1_000L, listOf(MessagePart("pa", "text", text = "Hello A"))))
    )
    pool.record(stateA)

    val snapshotA = ServerOverviewSnapshot(
      serverId = "srv-a",
      profile = profileA,
      connected = false,
      loading = false,
      sessions = listOf(sessionA),
      tasks = emptyMap(),
      permissions = emptyList()
    )
    val stateB = LagoonState(
      profiles = listOf(profileA, profileB),
      serverId = "srv-b",
      sessionId = "sess-b",
      connected = true,
      sessions = listOf(sessionB),
      tasks = mapOf("sess-b" to TaskState("sess-b", TaskPhase.WAITING_PERMISSION)),
      permissions = listOf(permB),
      allServerSnapshots = mapOf("srv-a" to snapshotA)
    )

    val projectedA = pool.project(stateB, "srv-a", "sess-a")
    assertEquals("srv-a", projectedA.serverId)
    assertEquals("sess-a", projectedA.session?.id)
    assertFalse("跨服务器投影必须采用目标服务器的连接状态", projectedA.connected)
    assertTrue("不能把 Server B 的权限请求泄漏到 Server A 的投影中", projectedA.permissions.isEmpty())
    assertTrue("不能把 Server B 的任务状态泄漏到 Server A 的投影中", projectedA.tasks.isEmpty())
  }

  @Test
  fun transcriptTurnCacheRefreshesOnEditedQueuedUserTextAndAvoidsCrossSessionCollision() {
    // 1. 同一消息 ID 下编辑排队中的用户消息文本，必须产出更新后的行而非旧缓存
    val queuedBefore = Message(id = "q1", role = "user", created = 1_000L, queued = true,
      parts = listOf(MessagePart(id = "q1:text", type = "text", text = "旧排队内容")))
    val queuedAfter = queuedBefore.copy(
      parts = listOf(MessagePart(id = "q1:text", type = "text", text = "修改后的排队内容")))

    val rowsBefore = TranscriptRows.build(listOf(queuedBefore))
    val rowsAfter = TranscriptRows.build(listOf(queuedAfter))
    assertEquals("旧排队内容", rowsBefore.single { it.kind == "user" }.text)
    assertEquals("修改后的排队内容", rowsAfter.single { it.kind == "user" }.text)

    // 2. 两个不同会话都以 DSH `msg_1:1` 开头且文本长度相同时，不得碰撞返回对方的内容
    val session1Step = Message(id = "msg_1:1", role = "assistant", created = 1_000L, completedAt = 1_500L,
      parts = listOf(MessagePart(id = "t", type = "text", text = "AAAA")), turnId = "turn:1")
    val session2Step = Message(id = "msg_1:1", role = "assistant", created = 2_000L, completedAt = 1_500L,
      parts = listOf(MessagePart(id = "t", type = "text", text = "BBBB")), turnId = "turn:1")

    val rows1 = TranscriptRows.build(listOf(session1Step))
    val rows2 = TranscriptRows.build(listOf(session2Step))
    assertEquals("AAAA", rows1.single { it.kind == "text" }.text)
    assertEquals("BBBB", rows2.single { it.kind == "text" }.text)
    assertNotSame(rows1.single { it.kind == "text" }, rows2.single { it.kind == "text" })
  }
}
