package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.DraftServer
import com.igng.opencode.lagoon.core.DraftWorkspace
import com.igng.opencode.lagoon.core.HomeScope
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.draftServers
import com.igng.opencode.lagoon.core.draftWorkspaces
import com.igng.opencode.lagoon.core.normalizedDirectory
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * First step of a new session: choose the harness/server. Every enabled connection is listed —
 * online, connecting, offline and even with zero registered workspaces — because the blank page is
 * where the workspace is chosen and where a new directory can be added. This replaces the old
 * "server list derived from registered projects" sheet, which went blank whenever the reader had
 * never registered a project on this device.
 */
@Composable
internal fun NewSessionServerSheet(
  state: LagoonState,
  profileIds: Set<String>?,
  onSelect: (DraftServer) -> Unit,
  onDismiss: () -> Unit
) {
  val servers = remember(state.profiles, state.serverId, state.connected, state.loading,
    state.allServerSnapshots, state.projects, state.workspaceCatalog, profileIds) {
    state.draftServers(profileIds)
  }
  SuperBottomSheet(title = "在哪台服务器新建", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item {
        Text("先选服务器（harness），工作区在空白页里选择；没有工作区的服务器也可以进去添加。",
          Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      if (servers.isEmpty()) item {
        Text("没有启用的连接。请先在设备管理中启用一个连接。", Modifier.padding(20.dp),
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      items(servers, key = { it.id }) { server ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
          DraftServerRow(server, current = server.id == state.serverId) { onSelect(server) }
        }
      }
    }
  }
}

/**
 * 空白页里的目的地选择：上半部分是服务器，下半部分是当前服务器的工作区。服务器行切换时不关闭面板，
 * 工作区列表随新服务器就地更新；点工作区才关闭面板并选中它。
 */
@Composable
internal fun DraftDestinationSheet(
  state: LagoonState,
  controller: LagoonController,
  profileIds: Set<String>?,
  onSwitchServer: (ServerProfile) -> Unit,
  onDismiss: () -> Unit
) {
  // The directory browser replaces the sheet body; adding a directory returns to this sheet.
  if (state.browsePath != null) { DirectoryBrowserSheet(state, controller); return }
  val currentId = state.serverId
  val servers = remember(state.profiles, state.serverId, state.connected, state.loading,
    state.allServerSnapshots, state.projects, state.workspaceCatalog, profileIds) {
    state.draftServers(profileIds)
  }
  val workspaces = remember(state.projects, state.workspaceCatalog, state.allServerSnapshots, currentId) {
    currentId?.let { state.draftWorkspaces(it) }.orEmpty()
  }
  val registered = workspaces.filter { it.registered }
  val serverOnly = workspaces.filterNot { it.registered }
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  SuperBottomSheet(title = "服务器与工作区", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item { SmallTitle("服务器", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
      items(servers, key = { "server:${it.id}" }) { server ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
          DraftServerRow(server, current = server.id == currentId) { onSwitchServer(server.profile) }
        }
      }
      item {
        SmallTitle(
          listOfNotNull("工作区", state.server?.name?.takeIf { it.isNotBlank() }).joinToString(" · "),
          insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        )
      }
      if (workspaces.isEmpty()) item {
        Text(emptyWorkspaceHint(state), Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
          style = MiuixTheme.textStyles.footnote2.copy(color = muted))
      }
      items(registered, key = { "workspace:${it.id}" }) { workspace ->
        DraftWorkspaceRow(state, workspace) { controller.chooseWorkspace(workspace.project); onDismiss() }
      }
      if (serverOnly.isNotEmpty()) item {
        SmallTitle("服务器上的工作区 · 点按登记", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp))
      }
      items(serverOnly, key = { "server-workspace:${it.directory}" }) { workspace ->
        DraftWorkspaceRow(state, workspace) { controller.chooseWorkspace(workspace.project); onDismiss() }
      }
      item { Spacer(Modifier.height(10.dp)) }
      if (state.connected) {
        item {
          Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            ChoiceRow("浏览服务器目录…", "添加为本机工作区", false) { controller.openDirectoryBrowser() }
          }
        }
      } else {
        item {
          Text(
            if (state.loading) "正在连接…连接后可以浏览服务器目录并添加工作区。"
            else "这台服务器尚未连接；连接后可以浏览服务器目录并添加工作区。",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MiuixTheme.textStyles.footnote2.copy(color = if (state.loading) MiuixColorTokens.Warning else muted)
          )
        }
      }
    }
  }
}

@Composable
private fun DraftWorkspaceRow(state: LagoonState, workspace: DraftWorkspace, onClick: () -> Unit) {
  val selected = workspace.registered && workspace.project.id == state.projectId ||
    !workspace.registered && normalizedDirectory(workspace.directory) == normalizedDirectory(state.project?.directory.orEmpty())
  val summary = listOfNotNull(
    HomeScope.displayPath(workspace.directory, null),
    "点按登记为工作区".takeIf { !workspace.registered }
  ).joinToString(" · ")
  ChoiceRow(workspace.name, summary, selected, onClick)
}

@Composable
private fun DraftServerRow(server: DraftServer, current: Boolean, onClick: () -> Unit) {
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  val dot = when {
    server.connected -> MiuixColorTokens.Success
    server.loading -> MiuixColorTokens.Warning
    else -> muted
  }
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(dot)
        Spacer(Modifier.width(8.dp))
        Text(
          server.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.body1.copy(fontWeight = if (current) FontWeight.Medium else FontWeight.Normal)
        )
        Spacer(Modifier.width(6.dp))
        HarnessBadge(server.profile.kind)
        if (current) {
          Spacer(Modifier.width(6.dp))
          Text("当前", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary))
        }
      }
      Text(
        listOf(server.connectionLabel, server.statusLabel, workspaceSummary(server)).joinToString(" · "),
        style = MiuixTheme.textStyles.footnote2.copy(color = muted), maxLines = 1, overflow = TextOverflow.Ellipsis
      )
    }
    DropdownChevron(16.dp, muted)
  }
}

private fun workspaceSummary(server: DraftServer): String = when {
  server.registered.isNotEmpty() -> "${server.registered.size} 个工作区"
  server.serverWorkspaces.isNotEmpty() -> "服务器有 ${server.serverWorkspaces.size} 个工作区，尚未登记"
  else -> "还没有工作区"
}

private fun emptyWorkspaceHint(state: LagoonState): String = when {
  state.loading -> "正在读取这台服务器的工作区…"
  !state.connected -> "这台服务器尚未连接。本机登记的工作区会显示在这里；连接后可浏览服务器目录。"
  else -> "还没有工作区。用下面的「浏览服务器目录」添加一个，或先在服务器上创建项目/工作区。"
}
