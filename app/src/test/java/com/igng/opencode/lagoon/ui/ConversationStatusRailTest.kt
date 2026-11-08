package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归：目标条只占一行、不再展示自动续跑轮次，任务清单以“n/m”小胶囊出现并在点开后列出明细。
 * 这些是产品决策，源码级断言能防止有人把整块卡片或轮次字段加回来。
 */
class ConversationStatusRailTest {
  private fun source(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  @Test fun chatUsesTheStatusRailAndNoLongerOwnsTheGoalCard() {
    val chat = source("ChatScreen.kt")
    assertTrue(chat.contains("ConversationStatusRail(state, controller)"))
    assertFalse("目标卡片应由 ConversationStatusRail 承载", chat.contains("private fun GoalBar("))
    assertFalse("轮次字段已移除，不能再渲染", chat.contains("roundsStarted"))
    assertFalse("轮次字段已移除，不能再渲染", chat.contains("maxRounds"))
  }

  @Test fun goalCapsuleIsOneLineAndCompletedGoalsDoNotReserveSpace() {
    val rail = source("ConversationStatusRail.kt")
    assertTrue(rail.contains("state.goal?.takeUnless(RemoteGoal::complete)"))
    assertTrue(rail.contains("GoalCapsule("))
    // 胶囊内只允许单行阶段与截断后的目标，完整目标只在面板里出现。
    assertTrue(rail.contains("maxLines = 1"))
    assertTrue(rail.contains("overflow = TextOverflow.Ellipsis"))
    assertTrue(rail.contains("HyperBottomSheet(title = \"长期目标\""))
  }

  @Test fun todoCapsuleShowsProgressAndOpensTheList() {
    val rail = source("ConversationStatusRail.kt")
    assertTrue(rail.contains("TodoCapsule("))
    assertTrue(rail.contains("todoProgress()"))
    // 胶囊正文是“已完成/总数”，与官方清单的 n/m 口径一致。
    assertTrue(rail.contains("\"\${progress.done}/\${progress.total}\""))
    assertTrue(rail.contains("HyperBottomSheet(title = \"任务清单\""))
    assertTrue(rail.contains("下一轮开始时清空"))
  }
}
