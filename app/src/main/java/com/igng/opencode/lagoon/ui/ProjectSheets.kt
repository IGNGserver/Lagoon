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
 * The 会话 title's scope picker: 全部会话 (default) or one project. Projects are registered on this
 * device by browsing a remote server directory; the server's own project list is never auto-imported.
 */
@Composable
internal fun ProjectScopeSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  if (state.browsePath != null) { DirectoryBrowserSheet(state, controller); return }
  var query by rememberSaveable { mutableStateOf("") }
  var removing by remember { mutableStateOf<Project?>(null) }
  val counts = HomeScope.counts(state.sessions, state.projects)
  val projects = HomeScope.byActivity(state.sessions, state.projects).filter {
    query.isBlank() || it.name.contains(query.trim(), true) || it.directory.contains(query.trim(), true)
  }
  SuperBottomSheet(title = "选择项目", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      if (state.projects.size > 8) item {
        TextField(query, { query = it }, label = "搜索项目", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
      }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("全部会话", "${state.sessions.count { it.parentId == null }} 个会话", state.scopeProjectId == null) { controller.setScope(null); onDismiss() }
        }
      }
      item { SmallTitle("项目", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
      if (projects.isEmpty()) item {
        Text("尚未添加项目。点下面的「添加服务器目录」，浏览服务器并选一个目录加入。",
          Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else item {
        Card(Modifier.fillMaxWidth()) {
          projects.forEach { project ->
            ProjectChoiceRow(project, "${HomeScope.displayPath(project.directory, null)} · ${counts[project.key] ?: 0} 个会话", state.scopeProjectId == project.id,
              onSelect = { controller.setScope(project.id); onDismiss() },
              onRemove = { removing = project })
          }
        }
      }
      item { Spacer(Modifier.height(12.dp)) }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("添加服务器目录…", "浏览服务器目录并添加为项目", false) { controller.openDirectoryBrowser() }
        }
      }
    }
  }
  removing?.let { project -> RemoveProjectDialog(project, controller) { removing = null } }
}

/** Scope picker used by the aggregate home. Project filtering stays server-local, so selecting one
 * here intentionally returns to that server's normal context instead of inventing a cross-server
 * project id. */
@Composable
internal fun AllServersScopeSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  val counts = state.sessions.filter { it.visibleOnHome }.groupingBy { it.serverId.orEmpty() }.eachCount()
  SuperBottomSheet(title = "全部服务器", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("全部服务器", "${state.sessions.count { it.parentId == null }} 个会话", true, onClick = onDismiss)
        }
      }
      item { SmallTitle("服务器", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
      items(state.profiles, key = { it.id }) { profile ->
        val snapshot = state.allServerSnapshots[profile.id]
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow(profile.name, "${counts[profile.id] ?: 0} 个会话${if (snapshot?.connected == true) " · 已连接" else " · 未连接"}", false) {
            controller.connect(profile.id)
            onDismiss()
          }
        }
      }
    }
  }
}

/** Lightweight project chooser for a draft: picks where the new session starts, without changing the home scope. */
@Composable
internal fun DraftTargetSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  if (state.browsePath != null) { DirectoryBrowserSheet(state, controller); return }
  var removing by remember { mutableStateOf<Project?>(null) }
  SuperBottomSheet(title = "在哪个项目里新建", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item {
        if (state.projects.isEmpty()) {
          Text("尚未添加项目。点下面的「添加服务器目录」，浏览服务器并选一个目录加入。",
            Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        } else Card(Modifier.fillMaxWidth()) {
          HomeScope.byActivity(state.sessions, state.projects).forEach { project ->
            ProjectChoiceRow(project, HomeScope.displayPath(project.directory, null), state.projectId == project.id,
              onSelect = { if (project.id != state.projectId) controller.selectProject(project.id); onDismiss() },
              onRemove = { removing = project })
          }
        }
      }
      item { Spacer(Modifier.height(12.dp)) }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("添加服务器目录…", "浏览服务器目录并添加为项目", false) { controller.openDirectoryBrowser() }
        }
      }
    }
  }
  removing?.let { project -> RemoveProjectDialog(project, controller) { removing = null } }
}

/** Chooses both sides of a new-session target while the home is aggregating servers. */
@Composable
internal fun AllServersDraftTargetSheet(
  state: LagoonState,
  onSelect: (ServerProfile, Project) -> Unit,
  onDismiss: () -> Unit
) {
  val grouped = state.projects.filter { it.serverId != null }.groupBy { it.serverId!! }
  SuperBottomSheet(title = "在哪台服务器新建", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      if (grouped.isEmpty()) item {
        Text("没有可用项目。请先在对应服务器中添加一个项目目录。", Modifier.padding(16.dp),
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      state.profiles.forEach { profile ->
        val projects = grouped[profile.id].orEmpty()
        if (projects.isNotEmpty()) {
          item { SmallTitle(profile.name, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
          item {
            Card(Modifier.fillMaxWidth()) {
              projects.forEach { project ->
                ProjectChoiceRow(project, HomeScope.displayPath(project.directory, null), false,
                  onSelect = { onSelect(profile, project) })
              }
            }
          }
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
      ResourceHint(state.resource("browse"), "这个目录是空的", retry = { if (path.isNotBlank()) controller.browseDirectory(path) })
      if (searching) {
        when {
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
