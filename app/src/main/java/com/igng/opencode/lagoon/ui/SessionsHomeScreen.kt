package com.igng.opencode.lagoon.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 会话首页，结构对齐 Qoder 的任务列表：标题「全部会话 ⌄」选择项目范围，右上角 ⋯ 收纳分组方式、搜索与设置，
 * 标题下一行是服务器胶囊（点按切换、长按编辑、＋ 添加），列表按日期 / 项目 / 状态分组且每组可收起，
 * 长按会话弹出置顶 / 重命名 / 删除，右下角悬浮按钮新建会话。
 * 状态与未读语义沿用 docs/HOME_REDESIGN.md（未读账本、父链汇总），分组从不改变会话的活动时间顺序。
 */
@Composable
internal fun HomeScreen(
  state: LagoonState,
  controller: LagoonController,
  onOpen: (SessionTarget) -> Unit,
  onNewSession: () -> Unit,
  onProjects: () -> Unit,
  onSelectServer: (ServerProfile?) -> Unit,
  onEditServer: (ServerProfile?) -> Unit,
  onPage: (RootPage) -> Unit
) {
  val preferences = LocalContext.current.getSharedPreferences("ui", Context.MODE_PRIVATE)
  var grouping by remember { mutableStateOf(runCatching { HomeGrouping.valueOf(preferences.getString("homeGrouping", null)!!) }.getOrDefault(HomeGrouping.DATE)) }
  var menu by remember { mutableStateOf(false) }
  var searching by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable(state.allServers, state.serverId) { mutableStateOf("") }
  var renaming by remember { mutableStateOf<Session?>(null) }
  var deleting by remember { mutableStateOf<Session?>(null) }
  fun closeSearch() { searching = false; query = "" }
  BackHandler(enabled = searching) { closeSearch() }
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      if (searching) SearchTopBar(query, { query = it }, ::closeSearch)
      else HomeTopBar(
         title = when {
           state.allServers -> "全部服务器"
           else -> state.scopeProjectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name } ?: "全部会话"
         },
        onTitleClick = onProjects,
        menu = {
          Box {
            CircleIconButton(MiuixIcons.More, "更多", { menu = true })
            MenuPopup(menu, { menu = false }, listOf(
              MenuSection(HomeGrouping.entries.map { option ->
                MenuAction(option.label, selected = grouping == option) { grouping = option; preferences.edit().putString("homeGrouping", option.name).apply() }
              }, title = "分组方式"),
              MenuSection(listOf(
                MenuAction("搜索", MiuixIcons.Search) { searching = true },
                MenuAction("已归档", ARCHIVE_ICON) { onPage(RootPage.ARCHIVED) },
                MenuAction("设置", MiuixIcons.Settings) { onPage(RootPage.SETTINGS) }
              ))
            ))
          }
        }
      )
      if (!searching) ServerChips(state, onSelectServer, onEditServer)
      Box(Modifier.weight(1f).fillMaxWidth()) {
        SessionList(state, controller, grouping, query.trim(), onOpen, onNewSession, onEditServer,
          onRename = { renaming = it }, onDelete = { deleting = it })
      }
    }
    if (!searching) FloatingActionButton(onClick = onNewSession, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = 24.dp)) {
      Icon(MiuixIcons.Add, "新建会话", Modifier.size(26.dp), tint = MiuixTheme.colorScheme.onPrimary)
    }
  }
  renaming?.let { session -> RenameSessionDialog(state, controller, session) { renaming = null } }
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

/** Search replaces the header: a MIUIX input field focused on open, and 取消 to leave. */
@Composable
private fun SearchTopBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically) {
    InputField(query = query, onQueryChange = onQuery, onSearch = {}, expanded = true, onExpandedChange = {}, label = "搜索会话标题或项目",
      modifier = Modifier.weight(1f).focusRequester(focus))
    Text("取消", Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClose).padding(horizontal = 12.dp, vertical = 10.dp),
      style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.primary))
  }
}

/** Fixed header: grid glyph + scope title with ⌄ on the left, a round ⋯ on the right. */
@Composable
private fun HomeTopBar(title: String, onTitleClick: () -> Unit, menu: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f)) {
    Row(Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onTitleClick).padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically) {
      Icon(MiuixIcons.GridView, null, Modifier.size(26.dp), tint = MiuixTheme.colorScheme.onSurface)
      Spacer(Modifier.width(10.dp))
      Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface))
      Spacer(Modifier.width(4.dp))
      DropdownChevron(20.dp, MiuixTheme.colorScheme.onSurface, "切换项目范围")
    }
    }
    Spacer(Modifier.width(8.dp))
    menu()
  }
}

/**
 * One capsule per saved server. The current one is tinted with the MIUIX primary and carries the
 * connection dot (connected / recovering / offline); tap switches, long press edits, ＋ adds.
 */
@Composable
private fun ServerChips(state: LagoonState, onSelect: (ServerProfile?) -> Unit, onEdit: (ServerProfile?) -> Unit) {
  LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    item(key = "all-servers") {
      val current = state.allServers
      val dot = when {
        !current -> null
        state.connected && state.streamConnected -> MiuixColorTokens.Success
        state.connected || state.loading -> MiuixColorTokens.Warning
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
      }
      Card(
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 16.dp),
        colors = CardDefaults.defaultColors(color = if (current) MiuixColorTokens.PrimarySubtle else MiuixTheme.colorScheme.secondaryContainer),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { onSelect(null) }
      ) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
          dot?.let { StatusDot(it); Spacer(Modifier.width(8.dp)) }
          Text("全部", style = MiuixTheme.textStyles.body2.copy(fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            color = if (current) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
      }
    }
    items(state.profiles, key = { it.id }) { profile ->
      val current = !state.allServers && profile.id == state.serverId
      val overview = state.allServerSnapshots[profile.id]
      val dot = when {
        overview?.connected == true && overview.streamConnected -> MiuixColorTokens.Success
        overview?.connected == true || overview?.loading == true -> MiuixColorTokens.Warning
        !current -> null
        state.connected && state.streamConnected -> MiuixColorTokens.Success
        state.connected || state.loading -> MiuixColorTokens.Warning
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
      }
      Card(
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 16.dp),
        colors = CardDefaults.defaultColors(color = if (current) MiuixColorTokens.PrimarySubtle else MiuixTheme.colorScheme.secondaryContainer),
        pressFeedbackType = PressFeedbackType.Sink,
         onClick = { onSelect(profile) },
        onLongPress = { onEdit(profile) }
      ) {
        // A fixed height keeps Latin and CJK names on one baseline row.
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
          dot?.let { StatusDot(it); Spacer(Modifier.width(8.dp)) }
          Text(profile.name, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.body2.copy(fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
              color = if (current) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
      }
    }
    item(key = "add-server") {
      Card(
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { onEdit(null) }
      ) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(MiuixIcons.Add, "添加服务器", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          if (state.profiles.isEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text("添加服务器", style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          }
        }
      }
    }
  }
}


@Composable
private fun SessionList(
  state: LagoonState, controller: LagoonController, grouping: HomeGrouping, query: String,
   onOpen: (SessionTarget) -> Unit, onNewSession: () -> Unit, onEditServer: (ServerProfile?) -> Unit,
  onRename: (Session) -> Unit, onDelete: (Session) -> Unit
) {
  var refreshing by remember { mutableStateOf(false) }
  val refreshScope = rememberCoroutineScope()
  val list = rememberLazyListState()
  val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() } }
  val active = remember(state.tasks, state.parents) { state.activeRootTasks }
  val statuses = remember(state.sessions, state.notices, active, state.backgroundRunning, state.allSessionStatuses) { state.sessions.associate { it.key to state.sessionStatus(it, active) } }
  val unseen = remember(state.notices, state.allUnseenSessions, state.allServers) {
    if (state.allServers) state.allUnseenSessions else state.notices.filterNot { it.viewed }.map { it.sessionId }.toSet()
  }
  val roots = HomeScope.roots(state.sessions, state.projects, state.scopeProjectId).filter { session ->
    query.isBlank() || session.displayTitle().contains(query, true) ||
      resolveSessionProject(session, state.projects)?.name?.contains(query, true) == true || session.directory.contains(query, true)
  }
  val sections = HomeSections.build(grouping, roots, state.projects, statuses, state.pinned, now)
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary

  PullToRefresh(isRefreshing = refreshing, onRefresh = { refreshing = true; refreshScope.launch { controller.reload().join(); refreshing = false } },
    refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "已刷新")) {
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)) {
      if (state.cached || state.degraded || !state.connected && !state.loading && state.profiles.isNotEmpty()) item(key = "sync") {
        Text(when { state.cached -> "离线缓存 · 下拉重试"; state.degraded -> "部分数据待同步 · 下拉重试"; else -> "尚未连接 · 点按重试" },
          style = MiuixTheme.textStyles.footnote1, color = MiuixColorTokens.Warning,
          modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { controller.reload() }.padding(horizontal = 24.dp, vertical = 8.dp))
      }
      sections.forEach { section ->
        // While searching every match stays visible, whatever was collapsed.
        val collapsed = query.isBlank() && section.key in state.collapsedSections
        item(key = "section:${section.key}", contentType = "section") {
          SectionHeader(section.title, collapsed) { controller.toggleSection(section.key) }
        }
         if (!collapsed) items(section.sessions, key = { "session:${section.key}:${it.key}" }, contentType = { "session" }) { session ->
          val pinned = session.id in state.pinned
          HomeSessionRow(session, statuses[session.key] ?: SessionStatus.NONE, session.key in unseen,
            serverName = if (state.allServers) session.serverId?.let { id -> state.profiles.firstOrNull { it.id == id }?.name } else null,
            onClick = { (session.target ?: state.serverId?.let { SessionTarget(it, session.id) })?.let(onOpen) }, actions = buildList {
            if (!state.allServers) {
              add(MenuAction(if (pinned) "取消置顶" else "置顶", if (pinned) MiuixIcons.Unpin else MiuixIcons.Pin) { controller.togglePin(session.id) })
              if (state.connected && state.capabilities.supports(SessionAction.RENAME)) add(MenuAction("重命名", MiuixIcons.Rename) { onRename(session) })
              if (state.connected && state.capabilities.archive != null) add(MenuAction("归档", ARCHIVE_ICON) { controller.setArchived(session.id, true) })
              if (state.connected) add(MenuAction("删除", MiuixIcons.Delete, danger = true) { onDelete(session) })
            }
          })
        }
      }
      if (sections.isEmpty()) item(key = "empty") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(when { state.loading -> "正在读取会话…"; query.isNotBlank() -> "没有符合条件的会话"; state.connected -> "暂无会话"; state.profiles.isEmpty() -> "还没有服务器"; else -> "尚未连接" }, color = muted)
          if (!state.loading && query.isBlank()) TextButton(text = if (state.connected) "新建会话" else if (state.profiles.isEmpty()) "添加服务器" else "重新连接",
            onClick = { if (state.connected) onNewSession() else if (state.profiles.isEmpty()) onEditServer(null) else controller.reload() })
        }
      }
      if (state.sessionCursors.isNotEmpty()) item(key = "more") {
        TextButton(text = if (state.pending("sessions-more")) "正在加载…" else "加载更多会话", enabled = !state.pending("sessions-more"),
          onClick = { controller.loadMoreSessions() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
      }
      else if (!state.catalogComplete) item(key = "cached-limit") {
        Text("缓存仅保留最近会话", modifier = Modifier.padding(horizontal = 24.dp), color = muted, style = MiuixTheme.textStyles.footnote2)
      }
    }
  }
}

/** "今天 ⌄" — tap to fold the section; the chevron points right while folded. */
@Composable
private fun SectionHeader(title: String, collapsed: Boolean, onToggle: () -> Unit) {
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onToggle).padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically) {
      Text(title, style = MiuixTheme.textStyles.footnote1.copy(color = muted))
      Spacer(Modifier.width(4.dp))
      Icon(MiuixIcons.ChevronForward, if (collapsed) "展开" else "收起", Modifier.size(14.dp).rotate(if (collapsed) 0f else 90f), tint = muted)
    }
  }
}

/** MIUIX icons have no archive glyph: putting away is the tray with a down arrow, restoring the arrow out. */
internal val ARCHIVE_ICON get() = MiuixIcons.Download
internal val UNARCHIVE_ICON get() = MiuixIcons.Import

/**
 * One session: a 24dp state glyph and the title. Running spins, waiting for a reply shows the warning
 * mark, an unread result is a primary (or error) dot with a medium title; everything else is a quiet dot.
 * Long press opens [actions] (置顶 / 重命名 / 归档 / 删除 on the home list).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeSessionRow(session: Session, status: SessionStatus, unseen: Boolean, serverName: String? = null, onClick: () -> Unit, actions: List<MenuAction>) {
  var menu by remember { mutableStateOf(false) }
  val haptics = LocalHapticFeedback.current
  val highlighted = unseen || status in setOf(SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION)
  Box {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
      .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = if (actions.isEmpty()) null else { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true } })
      .padding(start = 20.dp, end = 24.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StatusGlyph(status, unseen) }
      Spacer(Modifier.width(12.dp))
       Column(Modifier.weight(1f)) {
         Text(session.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
           style = MiuixTheme.textStyles.body1.copy(fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal, color = MiuixTheme.colorScheme.onSurface))
         serverName?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
           style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)) }
       }
       if (status == SessionStatus.BACKGROUND_RUNNING) {
        Spacer(Modifier.width(8.dp))
         Text(status.label, style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.primary))
       } else if (status == SessionStatus.WAITING_SERVER) {
         Spacer(Modifier.width(8.dp))
         Text(status.label, style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else if (status == SessionStatus.WAITING_PERMISSION || status == SessionStatus.WAITING_QUESTION) {
        Spacer(Modifier.width(8.dp))
        Text(status.label, style = MiuixTheme.textStyles.footnote1.copy(color = MiuixColorTokens.Warning))
      }
    }
    MenuPopup(menu, { menu = false }, listOf(MenuSection(actions)))
  }
}

@Composable
private fun StatusGlyph(status: SessionStatus, unseen: Boolean) {
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  when {
    status == SessionStatus.RUNNING ->
      InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary, size = 16.dp, strokeWidth = 2.dp, orbitingDotSize = 2.dp)
    status == SessionStatus.BACKGROUND_RUNNING ->
      Icon(MiuixIcons.Info, status.label, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
    status == SessionStatus.WAITING_SERVER ->
      Icon(MiuixIcons.Info, status.label, Modifier.size(20.dp), tint = muted)
    status == SessionStatus.WAITING_PERMISSION || status == SessionStatus.WAITING_QUESTION ->
      Icon(MiuixIcons.Info, status.label, Modifier.size(20.dp), tint = MiuixColorTokens.Warning)
    status == SessionStatus.FAILED -> Box(Modifier.size(8.dp).background(MiuixColorTokens.Error, CircleShape))
    unseen || status == SessionStatus.COMPLETED -> Box(Modifier.size(8.dp).background(MiuixTheme.colorScheme.primary, CircleShape))
    else -> Box(Modifier.size(6.dp).background(muted.copy(alpha = 0.3f), CircleShape))
  }
}

/** Rename any session from the list; the same dialog the chat ⋯ menu shows. */
@Composable
internal fun RenameSessionDialog(state: LagoonState, controller: LagoonController, session: Session, onDismiss: () -> Unit) {
  var title by rememberSaveable(session.id) { mutableStateOf(session.displayTitle()) }
  SuperDialog(title = "重命名会话", show = true, onDismissRequest = { if (!state.pending("rename")) onDismiss() }) {
    Column {
      TextField(title, { title = it }, label = "会话名称", modifier = Modifier.fillMaxWidth())
      DialogActions("取消", onDismiss, if (state.pending("rename")) "保存中…" else "保存", !state.pending("rename") && title.isNotBlank()) {
        controller.rename(title, session.id, onDismiss)
      }
    }
  }
}
