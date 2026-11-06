package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级回归：新建会话不能再因为“当前连接没连上 / 本机没登记项目”而把入口判死。
 *
 * JVM 单测起不了 Compose，这里守住已付出过代价的结构：
 * - 加号直接选一台默认设备进入空白页，不再先弹设备选择窗；
 * - 空白页的「服务器 · 工作区」始终可点，且切换服务器不重建输入框；
 * - 旧的“没有可用项目 / 没有可用工作区 / 设备尚未连接”死路文案不得回到新建流程。
 */
class NewSessionFlowRegressionTest {
  private fun uiSource(name: String): String {
    val candidates = listOf(
      File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
      File("src/main/java/com/igng/opencode/lagoon/ui/$name")
    )
    return candidates.firstOrNull { it.isFile }?.readText()
      ?: error("找不到 UI 源文件：$name（工作目录 ${File(".").absolutePath}）")
  }

  @Test fun newSessionButtonOpensTheDefaultDeviceWithoutAChooser() {
    val main = uiSource("MainActivity.kt")
    assertFalse("旧的未连接提示不得拦住新建", main.contains("设备尚未连接，连接后才能新建会话"))
    assertFalse("旧的 45 秒草稿等待不得回到入口", main.contains("pendingDraftTarget"))
    assertFalse("加号不得再弹出设备选择窗", main.contains("showingNewSession"))
    assertTrue("默认设备必须由纯函数决定", main.contains("state.defaultDraftServer(draftScope)"))
    assertTrue("默认设备必须直接进入空白页", main.contains("openDraftFor(target.profile)"))
    for (name in listOf("ProjectSheets.kt", "DraftSheets.kt")) {
      val text = uiSource(name)
      assertFalse("$name 不得再出现“没有可用项目”死路", text.contains("没有可用项目"))
      assertFalse("$name 不得再出现“没有可用工作区”死路", text.contains("没有可用工作区"))
    }
    assertFalse("入口选择窗已被页内面板取代", uiSource("DraftSheets.kt").contains("NewSessionServerSheet"))
  }

  @Test fun blankPageKeepsTheDestinationPickerAndComposerAcrossServerSwitches() {
    val chat = uiSource("ChatScreen.kt")
    assertTrue("空白页必须能打开服务器 + 工作区面板", chat.contains("DraftDestinationSheet"))
    assertTrue("切换服务器不得重建输入框", chat.contains("resetKey = DRAFT_SESSION"))
    val draft = chat.substring(chat.indexOf("fun DraftScreen("))
    val beforeComposer = draft.substring(0, draft.indexOf("ChatComposer("))
    assertFalse("标题选择器不得再依赖 state.connected", beforeComposer.contains("clickable(enabled = state.connected)"))
  }

  @Test fun aggregatePickerListsServersEvenWhenTheyHaveNoWorkspace() {
    val sheets = uiSource("DraftSheets.kt")
    // The old sheet skipped every profile without a registered project; the in-page picker iterates servers.
    assertTrue(sheets.contains("items(servers, key = { \"server:\${it.id}\" })"))
    assertTrue(sheets.contains("还没有工作区"))
    assertTrue(sheets.contains("浏览服务器目录"))
  }
}
