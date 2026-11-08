package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SessionActivity
import com.igng.opencode.lagoon.core.ShellJob
import com.igng.opencode.lagoon.core.SubSessionEntry
import com.igng.opencode.lagoon.core.SubSessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 后台活动只有一个入口：⋯ 菜单里那一项。它开的是半屏抽屉（与代码改动 / 文件 / 定时任务同款），
 * 不再是顶栏那行「主会话 / 后台活动」分段卡；抽屉里的子会话整页打开，后台命令换成自己的输出抽屉。
 */
class ConversationActivitySheetTest {
  private fun source(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  @Test fun topBarKeepsOnlyTheMenuEntry() {
    val chat = source("ChatScreen.kt")
    assertFalse("顶栏不再有主会话 / 后台活动的分段切换", chat.contains("ConversationPane"))
    assertFalse("会话页不再用分段胶囊", chat.contains("HyperSegmentedTabs("))
    assertFalse("切 pane 的标签助手已删除", chat.contains("conversationPaneLabels"))
    assertFalse("不再为 pane 单独拦返回", chat.contains("BackHandler(enabled = interactive && pane"))
    assertTrue("入口只保留 ⋯ 里的那一项", chat.contains("MenuAction(conversationActivityLabel(activity), MiuixIcons.MindMap) { showActivitySheet() }"))
    assertTrue("对话流底部胶囊仍能打开同一个抽屉", chat.contains("onOpenActivity = ::showActivitySheet"))
  }

  @Test fun activityOpensAsAHalfHeightSheet() {
    val chat = source("ChatScreen.kt")
    assertTrue("抽屉是 ChatSheet 的一员", chat.contains("private enum class ChatSheet { CHANGES, FILES, SCHEDULES, ACTIVITY }"))
    assertTrue("抽屉标题与菜单同源", chat.contains("ChatSheet.ACTIVITY -> conversationActivityLabel(activity)"))
    assertTrue("抽屉内容是后台活动面板", chat.contains("ChatSheet.ACTIVITY -> ActivityPanel("))
    assertTrue("走统一的半屏抽屉底座", chat.contains("HyperBottomSheet(title = title, show = true, onDismissRequest = { sheet = null })"))
    assertTrue("与其他抽屉同一档高度", chat.contains("Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(vertical = 8.dp))"))
    assertTrue("打开时按需补齐子会话与改动", chat.contains("if (sheet == ChatSheet.CHANGES || sheet == ChatSheet.ACTIVITY) controller.loadAncillary()"))
  }

  @Test fun sheetHandsOffToFullPageChildAndOwnShellSheet() {
    val chat = source("ChatScreen.kt")
    assertTrue("子会话先收起抽屉再整页打开", chat.contains("onOpenChild = { child -> sheet = null; onOpenChild(child) }"))
    assertTrue("后台命令先收起抽屉再弹自己的输出抽屉", chat.contains("onOpenShell = { job -> sheet = null; controller.openShellOutput(job) }"))
    assertTrue("子会话页仍由会话栈整页推入", source("AppBackStack.kt").contains("fun open(id: String, child: Boolean = false)"))
  }

  @Test fun countAppearsOnlyWhileSomethingIsRunningOrKnown() {
    val activity = SessionActivity(
      shells = listOf(ShellJob("shell", "root", "sleep", 1, "/repo")),
      subSessions = listOf(SubSessionEntry(Session("child", "/repo", "Child", 1), 1, SubSessionPhase.RUNNING, 1, null))
    )
    assertEquals(2, conversationActivityCount(activity))
    assertEquals("子会话与后台（2）", conversationActivityLabel(activity))
    assertEquals("子会话与后台", conversationActivityLabel(SessionActivity()))
  }

  @Test fun segmentedTabsRemainForTheDeviceForm() {
    val components = source("HyperComponents.kt")
    assertTrue("分段胶囊仍由设备表单使用", source("AddDeviceSheet.kt").contains("HyperSegmentedTabs("))
    assertTrue("分段胶囊的选中语义保留", components.contains("role = Role.Tab") && components.contains("this.selected = selected"))
  }
}
