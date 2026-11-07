package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
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
}
