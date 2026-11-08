package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.SessionActivity
import com.igng.opencode.lagoon.core.ShellJob
import com.igng.opencode.lagoon.core.SubSessionEntry
import com.igng.opencode.lagoon.core.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationActivitySwitcherTest {
  private fun source(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  @Test fun paneDefaultsToTranscriptAndIsScopedToConversation() {
    val chat = source("ChatScreen.kt")
    assertTrue(chat.contains("rememberSaveable(state.serverId, state.sessionId)"))
    assertTrue(chat.contains("mutableStateOf(ConversationPane.TRANSCRIPT)"))
    assertTrue(chat.contains("HyperSegmentedTabs("))
    assertTrue(chat.contains("ConversationPane.ACTIVITY -> ActivityPanel"))
  }

  @Test fun activityPaneLoadsAncillaryDataAndBackReturnsToTranscript() {
    val chat = source("ChatScreen.kt")
    assertTrue(chat.contains("LaunchedEffect(pane, sheet, state.serverId, state.sessionId)"))
    assertTrue(chat.contains("pane == ConversationPane.ACTIVITY || sheet == ChatSheet.CHANGES"))
    assertTrue(chat.contains("BackHandler(enabled = interactive && pane == ConversationPane.ACTIVITY"))
    assertTrue(chat.contains("pane = ConversationPane.TRANSCRIPT"))
    assertTrue(chat.contains("ChatSheet.ACTIVITY").not())
  }

  @Test fun transcriptScrollStateIsPassedThroughThePaneSwitch() {
    val chat = source("ChatScreen.kt")
    val transcript = source("TranscriptView.kt")
    assertTrue(chat.contains("val transcriptList = rememberLazyListState()"))
    assertTrue(chat.contains("listState = transcriptList"))
    assertTrue(transcript.contains("listState: LazyListState? = null"))
    assertTrue(transcript.contains("val list = listState ?: rememberLazyListState()"))
  }

  @Test fun paneLabelsAndIndexSelectionAreStable() {
    val activity = SessionActivity(
      shells = listOf(ShellJob("shell", "root", "sleep", 1, "/repo")),
      subSessions = listOf(SubSessionEntry(Session("child", "/repo", "Child", 1), 1, com.igng.opencode.lagoon.core.SubSessionPhase.RUNNING, 1, null))
    )
    assertEquals(2, conversationActivityCount(activity))
    assertEquals(listOf("主会话", "后台活动（2）"), conversationPaneLabels(activity))
    assertEquals(ConversationPane.TRANSCRIPT, conversationPaneFromIndex(-1))
    assertEquals(ConversationPane.TRANSCRIPT, conversationPaneFromIndex(0))
    assertEquals(ConversationPane.ACTIVITY, conversationPaneFromIndex(1))
    assertEquals(ConversationPane.TRANSCRIPT, conversationPaneFromIndex(99))
  }

  @Test fun segmentedTabsExposeSelectionSemantics() {
    val components = source("HyperComponents.kt")
    assertTrue(components.contains("role = Role.Tab"))
    assertTrue(components.contains("this.selected = selected"))
    assertTrue(components.contains("stateDescription = if (selected) \"已选择\" else \"未选择\""))
    assertTrue(components.contains("height(HYPER_TOUCH_TARGET_DP.dp)"))
  }
}
