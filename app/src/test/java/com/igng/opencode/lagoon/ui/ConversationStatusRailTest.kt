package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归：目标条只占一行；预算与编辑放在详情面板，创建目标时可填写轮次上限。
 * 任务清单以“n/m”小胶囊出现并在点开后列出明细。
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
    assertTrue("创建目标时可填写可选上限", chat.contains("parseGoalRoundCap(goalCapText)"))
    assertTrue("创建成功前保留草稿", chat.contains("onCreated = {"))
    assertTrue("上限通过 Long 参数传递", chat.contains("maxRounds = cap.getOrNull()"))
  }

  @Test fun goalCapsuleIsOneLineAndCompletedGoalsDoNotReserveSpace() {
    val rail = source("ConversationStatusRail.kt")
    assertTrue(rail.contains("state.goal?.takeUnless(RemoteGoal::complete)"))
    assertTrue(rail.contains("GoalCapsule("))
    // 胶囊内只允许单行阶段与截断后的目标，完整目标只在面板里出现。
    assertTrue(rail.contains("maxLines = 1"))
    assertTrue(rail.contains("overflow = TextOverflow.Ellipsis"))
    assertTrue(rail.contains("HyperBottomSheet(title = \"长期目标\""))
    assertTrue("详情显示预算而非胶囊占多行", rail.contains("Text(goalRoundSummary(goal)"))
    assertTrue("恢复自动续跑须走核心显式策略", rail.contains("goal.canResumeGoal()"))
    assertTrue("编辑不会重新恢复自动续跑", rail.contains("保留阶段和自动续跑状态；不会自动恢复"))
    assertTrue("清除确认绑定打开时的目标", rail.contains("controller.goalAction(\"clear\", expected = captured)"))
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
