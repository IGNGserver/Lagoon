package com.igng.opencode.lagoon.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话页在键盘弹出时最新消息必须留在可见区（曾经必须手动下滑，最后几条被键盘挡住），上滑加载历史时
 * 视口不得跳动。JVM 单测起不了 Compose 组合，沿用源码扫描守住这些回归线；滚动判据本身见 TranscriptScrollTest。
 */
class KeyboardScrollRegressionTest {
  private fun source(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  /**
   * 会话列表倒序布局（最新一项在 index 0、贴底）：键盘弹出使视口变矮时，LazyColumn 保持底部那一项不动，
   * 最新消息自然留在键盘上方，不再需要监听 IME 高度去补一次回底。
   */
  @Test fun conversationKeepsTheNewestMessageAboveTheKeyboard() {
    val text = source("TranscriptView.kt")
    assertTrue("会话列表必须倒序布局", text.contains("reverseLayout = true"))
    assertTrue("条目按最新在前的顺序输出", text.contains("items(mainRows.asReversed()"))
    assertFalse("不再靠 IME 高度补滚动", text.contains("WindowInsets.ime.getBottom"))
    assertFalse("手工回底机制已删除", text.contains("scrollBottom()"))
  }

  /** 首屏停在最新消息；跟随时新插入的行在本帧测量前就请求回到 index 0，且不打断用户的拖动或惯性滑动。 */
  @Test fun openingASessionStaysAtTheNewestMessage() {
    val text = source("TranscriptView.kt")
    assertTrue("首次进入应默认跟随最新消息", text.contains("var follow by rememberSaveable { mutableStateOf(true) }"))
    assertTrue("跟随时在测量前请求回到最新", text.contains("if (follow && !list.isScrollInProgress) list.requestScrollToItem(0)"))
    assertTrue("跟随意图只在用户滑动时改变", text.contains("if (list.isScrollInProgress) follow = atBottom"))
  }

  /**
   * 加载更早消息：旧页追加在倒序列表尾部（屏幕上方），不移动视口，因此不再有锚点记录/恢复；
   * 预取不依赖“滑到最顶”和 !follow，短会话不满一屏也能触发。
   */
  @Test fun olderHistoryIsPrefetchedAtTheFarEndWithoutManualAnchors() {
    val text = source("TranscriptView.kt")
    assertTrue("距列表尾部约一屏时预取", text.contains("TranscriptScroll.nearOldest("))
    assertTrue("预取条件走可测的纯函数", text.contains("TranscriptScroll.shouldLoadOlder("))
    listOf("anchorKey", "pendingRestoreAnchor", "expectedItems", "olderArmed").forEach {
      assertFalse("旧的手工锚点机制 $it 已删除", text.contains(it))
    }
    assertTrue("更早消息的加载器放在列表尾部（倒序后在最上方）",
      text.indexOf("item(key = \"older-loader\")") > text.indexOf("items(mainRows.asReversed()"))
  }

  /** 行数据节流重建：流式输出持续更新转录，而不是每次变化都重启防抖、一直等不到重建。 */
  @Test fun rowsAreThrottledNotDebounced() {
    val text = source("TranscriptView.kt")
    assertTrue(text.contains("snapshotFlow { source }.conflate()"))
    assertFalse("不得再用每次变化重启的 delay(32) 防抖", text.contains("delay(32)"))
  }

  /** 图片附件按头部读出的宽高比预留高度，解码结果进 LRU，回滚时不重复解码。 */
  @Test fun inlineImagesReserveTheirHeightBeforeDecoding() {
    val text = source("TranscriptView.kt")
    assertTrue(text.contains("InlineImages.aspect("))
    assertTrue(text.contains("android.util.LruCache"))
    assertTrue(text.contains("minOf(maxWidth / aspect, 280.dp)"))
  }

  /** 混排的工具调用与思考在两段文字之间只折叠成一个组。 */
  @Test fun toolsAndReasoningBetweenTextsCollapseIntoOneGroup() {
    val text = source("TranscriptView.kt")
    assertTrue("分组行仍走半屏明细", text.contains("\"tool-group\" -> Column"))
  }

  @Test fun activityOverlayKeepsListStateAndBackFirstReturnsToTheList() {
    val text = source("ChatScreen.kt")
    assertTrue("列表状态必须留在列表和详情分支外", text.indexOf("val activityListState = rememberLazyListState()") < text.indexOf("if (latestSelected == null)"))
    assertTrue("返回先退出二级详情，再关闭操作层", text.contains("if (latestSelected != null && latestSelected.key != group.key) onBack() else onDismiss()"))
    assertTrue("操作层必须覆盖输入栏", text.contains("fillMaxHeight(0.74f)"))
    assertTrue("详情需随服务器消息更新", text.contains("group.key, messages, working, revertMessageId"))
    assertTrue("工具附件必须显示在二级详情", text.contains("items(latestSelected.attachments"))
  }

  @Test fun qoderThemeIsScopedToExistingChatNotHomeOrSettings() {
    assertTrue(source("ChatScreen.kt").contains("QoderChatTheme {"))
    assertTrue(!source("MainActivity.kt").contains("QoderChatTheme"))
    assertTrue("输入栏应保留系统导航栏安全距离", source("ChatScreen.kt").contains("navigationBarsPadding()"))
  }

  /** 运行中的会话在底部常驻“运行中”指示，且时间每秒刷新。 */
  @Test fun runningSessionKeepsABottomIndicator() {
    val text = source("TranscriptView.kt")
    assertTrue("会话页应有底部运行指示", text.contains("WorkingIndicator(task)"))
    assertTrue("运行指示用“运行中”文案", text.contains("运行中 · "))
  }
}
