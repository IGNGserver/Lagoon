package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The single home scope picker behind the 会话 title. It replaces the old horizontal device/group
 * capsules: devices, groups, projects and workspaces all live here, and the list itself keeps only
 * the current scope title. Selecting a row switches the projection and closes the sheet; group
 * editing and device management stay reachable without a second top-bar row.
 */
@Composable
internal fun HomeScopeSheet(
  state: LagoonState,
  allSnapshots: Map<String, ServerOverviewSnapshot>,
  controller: LagoonController,
  onDismiss: () -> Unit,
  onEditGroup: (DeviceGroup) -> Unit,
  onManageDevices: () -> Unit
) {
  if (state.browsePath != null) { DirectoryBrowserSheet(state, controller); return }
  var removing by remember { mutableStateOf<Project?>(null) }
  val online = state.enabledProfiles.count { profile ->
    // Aggregate/group projections set `connected` to "any member connected"; only a single-server
    // home can trust it for the current profile. Every other case reads that profile's snapshot.
    if (!state.multiServerView && profile.id == state.serverId) state.connected
    else allSnapshots[profile.id]?.connected == true
  }
  SuperBottomSheet(title = "选择范围", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      // In a scoped home the title names the project/workspace, so its filter stays first; the
      // device switch follows. The aggregate home has no project filter and leads with devices.
      if (!state.allServers) item {
        if (state.homeGroup != null) WorkspaceScopeSection(state, controller, onDismiss)
        else ProjectScopeSection(state, controller, onDismiss, onRemove = { removing = it })
      }
      item { SmallTitle(if (state.allServers) "设备" else "切换设备", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("全部设备", "${state.enabledProfiles.size} 个连接 · $online 在线", state.allServers) {
            controller.setAllServers(true)
            onDismiss()
          }
        }
      }
      if (state.deviceTabs.isNotEmpty()) {
        item { SmallTitle("设备分组", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
        items(state.deviceTabs, key = { "group:${it.id}" }) { tab ->
          GroupScopeRow(tab, state, allSnapshots, selected = state.homeGroupId == tab.group?.id,
            onSelect = {
              if (state.homeGroupId != tab.group?.id) tab.group?.let { group -> controller.selectGroup(group.id) }
              onDismiss()
            },
            onEdit = { tab.group?.let(onEditGroup) })
        }
      }
      item { SmallTitle("单台连接", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
      if (state.enabledProfiles.isEmpty()) item {
        Text("没有启用的连接。请先在设备管理中启用一个连接。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else items(state.enabledProfiles, key = { "profile:${it.id}" }) { profile ->
        ProfileScopeRow(profile, state, allSnapshots,
          selected = !state.allServers && state.homeGroupId == null && state.serverId == profile.id) {
          // The selected single-server row needs no reconnect; group/all mode still enters it.
          if (state.multiServerView || profile.id != state.serverId) controller.connect(profile.id)
          onDismiss()
        }
      }
      item { Spacer(Modifier.height(8.dp)) }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("设备与分组管理", "管理连接、分组与启用状态", false) {
            onManageDevices()
            onDismiss()
          }
        }
      }
    }
  }
  removing?.let { project -> RemoveProjectDialog(project, controller) { removing = null } }
}

/** One selectable device group; the trailing ⋯ opens the same editor the old long-press did. */
@Composable
private fun GroupScopeRow(
  tab: DeviceTab,
  state: LagoonState,
  allSnapshots: Map<String, ServerOverviewSnapshot>,
  selected: Boolean,
  onSelect: () -> Unit,
  onEdit: () -> Unit
) {
  val online = tab.profiles.count { profile ->
    if (!state.multiServerView && profile.id == state.serverId) state.connected
    else allSnapshots[profile.id]?.connected == true
  }
  Card(Modifier.fillMaxWidth()) {
    BasicComponent(
      title = tab.label,
      summary = "${tab.profiles.size} 个连接 · $online 在线",
      onClick = onSelect,
      endActions = {
        if (selected) Icon(MiuixIcons.Ok, "已选择", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
        IconButton(onClick = onEdit, minWidth = 36.dp, minHeight = 36.dp) {
          Icon(MiuixIcons.More, "编辑分组", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
      }
    )
  }
}

/** One single-server entry; selecting it enters that server's normal single-server home. */
@Composable
private fun ProfileScopeRow(
  profile: ServerProfile,
  state: LagoonState,
  allSnapshots: Map<String, ServerOverviewSnapshot>,
  selected: Boolean,
  onSelect: () -> Unit
) {
  val connected = if (!state.multiServerView && profile.id == state.serverId) state.connected
  else allSnapshots[profile.id]?.connected == true
  val loading = if (!state.multiServerView && profile.id == state.serverId) state.loading
  else allSnapshots[profile.id]?.loading == true
  // In a group/all projection `sessions` is the aggregate, so count this server from its own snapshot.
  val sessions = if (state.multiServerView) allSnapshots[profile.id]?.sessions.orEmpty() else state.sessions
  val count = sessions.count { it.visibleOnHome }
  val status = when { connected -> "已连接"; loading -> "连接中…"; else -> "未连接" }
  Card(Modifier.fillMaxWidth()) {
    ChoiceRow(profile.name, "${profile.connectionTypeLabel} · $status · $count 个会话", selected, onSelect)
  }
}

/** Project filter of a single-server home; the server's catalog is never auto-imported. */
@Composable
private fun ProjectScopeSection(
  state: LagoonState,
  controller: LagoonController,
  onDismiss: () -> Unit,
  onRemove: (Project) -> Unit
) {
  var query by rememberSaveable { mutableStateOf("") }
  val counts = HomeScope.counts(state.sessions, state.projects)
  val projects = HomeScope.byActivity(state.sessions, state.projects).filter {
    query.isBlank() || it.name.contains(query.trim(), true) || it.directory.contains(query.trim(), true)
  }
  Column(Modifier.fillMaxWidth()) {
    if (state.projects.size > 8) TextField(query, { query = it }, label = "搜索项目",
      useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
    Card(Modifier.fillMaxWidth()) {
      ChoiceRow("全部会话", "${state.sessions.count { it.visibleOnHome }} 个会话", state.scopeProjectId == null) {
        controller.setScope(null)
        onDismiss()
      }
    }
    SmallTitle("项目", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp))
    if (projects.isEmpty()) Text("尚未添加项目。点下面的「添加服务器目录」，浏览服务器并选一个目录加入。",
      Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    else Card(Modifier.fillMaxWidth()) {
      projects.forEach { project ->
        ProjectChoiceRow(project, "${HomeScope.displayPath(project.directory, null)} · ${counts[project.key] ?: 0} 个会话",
          state.scopeProjectId == project.id,
          onSelect = { controller.setScope(project.id); onDismiss() },
          onRemove = { onRemove(project) })
      }
    }
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth()) {
      ChoiceRow("添加服务器目录…", "浏览服务器目录并添加为项目", false) { controller.openDirectoryBrowser() }
    }
  }
}

/** Workspace filter of a device group: same-named projects from its harnesses are one entry. */
@Composable
private fun WorkspaceScopeSection(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  val group = state.homeGroup ?: return
  val workspaces = remember(state.sessions, state.projects, group.id) {
    deviceWorkspaces(state.sessions, state.projects, group.id)
  }
  Column(Modifier.fillMaxWidth()) {
    Card(Modifier.fillMaxWidth()) {
      ChoiceRow("全部会话", "${state.sessions.count { it.visibleOnHome }} 个会话", state.scopeWorkspaceKey == null) {
        controller.setWorkspaceScope(null)
        onDismiss()
      }
    }
    SmallTitle("工作区", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp))
    if (workspaces.isEmpty()) Text("这个设备还没有工作区。先在对应 harness 中添加项目目录。", Modifier.padding(16.dp),
      style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    else Card(Modifier.fillMaxWidth()) {
      workspaces.forEach { workspace ->
        ChoiceRow(
          title = workspace.name,
          summary = listOfNotNull(
            "${workspace.sessions.count { it.visibleOnHome }} 个会话",
            "${workspace.members.size} 个 harness".takeIf { workspace.members.size > 1 },
            workspace.members.firstOrNull()?.directory
          ).joinToString(" · "),
          selected = state.scopeWorkspaceKey == workspace.key
        ) {
          controller.setWorkspaceScope(workspace.key)
          onDismiss()
        }
      }
    }
  }
}

/** 项目行：点按选择，行尾「⋯」只提供本机移除；服务器目录与其中的数据不受影响。 */
@Composable
private fun ProjectChoiceRow(project: Project, summary: String, selected: Boolean, onSelect: () -> Unit, onRemove: (() -> Unit)? = null) {
  var menu by remember { mutableStateOf(false) }
  Box {
    BasicComponent(
      title = project.name,
      summary = summary,
      onClick = onSelect,
      endActions = {
        if (selected) Icon(MiuixIcons.Ok, "已选择", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
        onRemove?.let {
          IconButton(onClick = { menu = true }, minWidth = 36.dp, minHeight = 36.dp) {
            Icon(MiuixIcons.More, "项目操作", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          }
        }
      }
    )
    onRemove?.let {
      MenuPopup(menu, { menu = false }, listOf(MenuSection(listOf(
        MenuAction("从本机移除", MiuixIcons.Delete, danger = true) { menu = false; it() }
      ))))
    }
  }
}

/** 项目只在本机注册：移除是设备级动作，不触碰服务器上的目录与数据。 */
@Composable
private fun RemoveProjectDialog(project: Project, controller: LagoonController, onDismiss: () -> Unit) {
  SuperDialog(title = "从本机移除项目？", show = true, onDismissRequest = onDismiss) {
    Column {
      Text("只删除此设备上的项目记录，不会删除服务器上的任何目录或数据。\n${project.name}")
      DialogActions("取消", onDismiss, "移除", danger = true) {
        controller.removeProject(project.id)
        onDismiss()
      }
    }
  }
}

/** Joins a remote directory with a listed entry; the server may return either a name or a full path. */
private fun childPath(parent: String, entry: String): String =
  if (entry.startsWith("/")) entry else parent.trimEnd('/') + "/" + entry.trimStart('/')

/**
 * Remote directory browser used to add a project. Directories come first, the current path is a
 * tappable breadcrumb, and search is scoped to the current directory. Only directories can be added.
 */
@Composable
internal fun DirectoryBrowserSheet(state: LagoonState, controller: LagoonController) {
  var query by rememberSaveable { mutableStateOf("") }
  val path = state.browsePath.orEmpty()
  val crumbs = remember(path) { if (path.isBlank()) emptyList() else HomeScope.crumbs(path) }
  val searching = query.trim().length >= 2
  val entries = remember(state.browseEntries, state.browseShowHidden) { BrowseFilter.entries(state.browseEntries, state.browseShowHidden) }
  val results = remember(state.browseSearch, state.browseShowHidden) { BrowseFilter.results(state.browseSearch, state.browseShowHidden) }
  SuperBottomSheet(title = "选择服务器目录", show = true, onDismissRequest = { controller.closeDirectoryBrowser() }) {
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { index, (label, target) ->
          if (index > 0) Text("/", Modifier.padding(horizontal = 4.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          Text(label.ifBlank { "/" }, Modifier.clickable { controller.browseDirectory(target) },
            style = MiuixTheme.textStyles.footnote2.copy(color = if (index == crumbs.lastIndex) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
        if (crumbs.isEmpty()) Text("正在定位服务器目录…", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      TextField(query, { query = it; controller.searchDirectories(it) }, label = "搜索目录或文件", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("显示隐藏目录", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        Switch(checked = state.browseShowHidden, onCheckedChange = { controller.setBrowseShowHidden(it) })
      }
      if (searching) {
        val searchStatus = state.resource("browse-search")
        when {
          searchStatus.state == ResourceState.LOADING || searchStatus.state == ResourceState.ERROR ->
            ResourceHint(searchStatus, "没有匹配项", retry = { controller.searchDirectories(query) })
          results.isNotEmpty() -> LazyColumn(Modifier.fillMaxWidth()) {
            items(results, key = { it }) { result ->
              val name = result.trimEnd('/').substringAfterLast('/')
              BasicComponent(title = name.ifBlank { result }, summary = result, onClick = { controller.browseDirectory(childPath(path, result)) })
            }
          }
          state.browseSearch.isNotEmpty() && !state.browseShowHidden -> Text("匹配项均为隐藏项，开启「显示隐藏目录」后可见",
            Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          else -> Text("没有匹配项", Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
      } else {
        ResourceHint(state.resource("browse"), "这个目录是空的", retry = { if (path.isNotBlank()) controller.browseDirectory(path) })
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
          if (path.isNotBlank() && path.trimEnd('/') != "") item(key = "..") {
            BasicComponent(title = "上级目录", summary = HomeScope.parent(path), onClick = { controller.browseDirectory(HomeScope.parent(path)) })
          }
          if (entries.isEmpty() && state.browseEntries.isNotEmpty() && !state.browseShowHidden) item(key = "hidden-only") {
            Text("此目录只有隐藏项，开启「显示隐藏目录」后可见", Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
              style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          }
          items(entries, key = { it.path }) { node ->
            val directory = node.type == "directory"
            if (directory) BasicComponent(title = node.path.trimEnd('/').substringAfterLast('/').ifBlank { node.path }, summary = "文件夹",
              onClick = { controller.browseDirectory(childPath(path, node.path)) })
            else BasicComponent(title = node.path.substringAfterLast('/').ifBlank { node.path }, summary = node.path)
          }
        }
      }
      if (path.isNotBlank() && !searching) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
          ChoiceRow("添加「${path.trimEnd('/').substringAfterLast('/').ifBlank { path }}」为项目", HomeScope.displayPath(path, null), false) {
            controller.addProject(path)
          }
        }
      }
    }
  }
}
