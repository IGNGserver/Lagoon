package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 已归档：the server's archived root sessions (`time.archived`), newest first. They open like any
 * session; long press restores (only where the instance documents a nullable `time.archived`) or deletes.
 */
@Composable
internal fun ArchivedScreen(state: LagoonState, controller: LagoonController, onOpen: (SessionTarget) -> Unit, onBack: () -> Unit) {
  var deleting by remember { mutableStateOf<Session?>(null) }
  val archived = state.sessions.filter { it.parentId == null && it.archived }.sortedWith(sessionActivityOrder)
  val active = remember(state.tasks, state.parents, state.permissionPolicy, state.autoApprovalFailures, state.connected, state.lastContactAt) { state.activeRootTasks }
  val statuses = remember(state.sessions, state.notices, active, state.backgroundRunning, state.allSessionStatuses, state.allServers) {
    state.sessions.associate { it.key to state.sessionStatus(it, active) }
  }
  val unseen = remember(state.notices, state.allUnseenSessions, state.allServers) {
    if (state.allServers) state.allUnseenSessions else state.notices.filterNot { it.viewed }.map { it.sessionId }.toSet()
  }
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  Column(Modifier.fillMaxSize()) {
    PageTopBar("已归档", onBack)
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(), contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp)) {
      items(archived, key = { it.key }) { session ->
        HomeSessionRow(session, statuses[session.key] ?: SessionStatus.NONE, unseen = session.key in unseen,
          serverName = if (state.allServers) session.serverId?.let { id -> state.profiles.firstOrNull { it.id == id }?.name } else null,
          onClick = { (session.target ?: state.serverId?.let { SessionTarget(it, session.id) })?.let(onOpen) }, actions = buildList {
          if (!state.allServers && state.connected && state.capabilities.archive?.restorable == true) add(MenuAction("取消归档", UNARCHIVE_ICON) { controller.setArchived(session.id, false) })
          if (!state.allServers && state.connected && state.capabilities.deleteSession) add(MenuAction("删除", MiuixIcons.Delete, danger = true) { deleting = session })
        })
      }
      if (archived.isEmpty()) item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(if (state.loading) "正在读取会话…" else "没有已归档的会话", color = muted)
          if (state.capabilities.archive == null && !state.loading) Text("这台服务器未提供归档接口；在其他客户端归档的会话仍会显示在这里。",
            Modifier.padding(top = 8.dp), color = muted, style = MiuixTheme.textStyles.footnote1)
        }
      }
      if (state.sessionCursors.isNotEmpty()) item {
        TextButton(text = if (state.pending("sessions-more")) "正在加载…" else "加载更多会话", enabled = !state.pending("sessions-more"),
          onClick = { controller.loadMoreSessions() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
      }
    }
  }
  deleting?.let { session ->
    SuperDialog(title = "删除此会话？", show = true, onDismissRequest = { if (!state.pending("delete")) deleting = null }) {
      Column {
        Text("会话消息将从服务器永久删除。\n${session.displayTitle()}")
        DialogActions("取消", { deleting = null }, if (state.pending("delete")) "删除中…" else "永久删除", !state.pending("delete"), danger = true) {
          controller.deleteSession(session.id) { deleting = null }
        }
      }
    }
  }
}
