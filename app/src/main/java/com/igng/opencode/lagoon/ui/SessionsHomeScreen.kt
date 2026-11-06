package com.igng.opencode.lagoon.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 会话首页，结构对齐 Qoder 的任务列表：标题「全部设备 ⌄」打开统一范围面板（全部设备 / 设备分组 /
 * 单台连接，以及当前服务器的项目或工作区），右上角 ⋯ 收纳分组方式、搜索与设置。首页不再有横向的
 * 服务器胶囊行：标题旁的小圆点聚合当前范围的连接健康。列表按日期 / 项目 / 状态分组且每组可收起，
 * 长按会话弹出置顶 / 重命名 / 删除，右下角悬浮按钮新建会话。
 * 状态与未读语义沿用 docs/HOME_REDESIGN.md（未读账本、父链汇总）；日期分组按状态优先级排序，
 * 其他分组仍保持活动时间顺序。
 */
@Composable
internal fun HomeScreen(
  state: LagoonState,
  controller: LagoonController,
  onOpen: (SessionTarget) -> Unit,
  onNewSession: () -> Unit,
  onScope: () -> Unit,
  onManageDevices: () -> Unit = {},
  onPage: (RootPage) -> Unit
) {
  val preferences = LocalContext.current.getSharedPreferences("ui", Context.MODE_PRIVATE)
  var grouping by remember { mutableStateOf(runCatching { HomeGrouping.valueOf(preferences.getString("homeGrouping", null)!!) }.getOrDefault(HomeGrouping.DATE)) }
  var menu by remember { mutableStateOf(false) }
  var searching by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable(state.allServers, state.serverId, state.homeGroupId) { mutableStateOf("") }
  var renaming by remember { mutableStateOf<Session?>(null) }
  var deleting by remember { mutableStateOf<Session?>(null) }
  val hasUnread = state.homeUnseenSessions().isNotEmpty()
  val serverReadCount = state.homeReadSnapshots().values.sumOf { it.serverReadCandidates().size }
  val groupWorkspaces = remember(state.sessions, state.projects, state.homeGroupId) {
    state.homeGroup?.let { group -> deviceWorkspaces(state.sessions, state.projects, group.id) }.orEmpty()
  }
  val scopeWorkspace = state.scopeWorkspaceKey?.let { key -> groupWorkspaces.firstOrNull { it.key == key } }
  fun closeSearch() { searching = false; query = "" }
  LaunchedEffect(groupWorkspaces, state.scopeWorkspaceKey) {
    val key = state.scopeWorkspaceKey
    if (key != null && groupWorkspaces.none { it.key == key }) controller.setWorkspaceScope(null)
  }
  BackHandler(enabled = searching) { closeSearch() }
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      if (searching) SearchTopBar(query, { query = it }, ::closeSearch)
      else HomeTopBar(
         title = when {
           state.allServers -> "全部设备"
           scopeWorkspace != null -> scopeWorkspace.name
           state.homeGroup != null -> state.homeGroup!!.name
           else -> state.scopeProjectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name }
             ?: state.server?.name ?: "全部会话"
         },
        statusDot = homeScopeDot(state),
        onTitleClick = onScope,
        action = if (hasUnread) {
          {
            Text("全部已读", Modifier.clip(CircleShape).clickable(role = Role.Button) { controller.markAllRead() }
              .padding(horizontal = 12.dp, vertical = 10.dp), maxLines = 1,
              style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.primary))
          }
        } else null,
        menu = {
          Box {
            CircleIconButton(MiuixIcons.More, "更多", { menu = true })
            MenuPopup(menu, { menu = false }, buildList {
              add(MenuSection(HomeGrouping.entries.map { option ->
                MenuAction(option.label, selected = grouping == option) { grouping = option; preferences.edit().putString("homeGrouping", option.name).apply() }
              }, title = "分组方式"))
              if (serverReadCount > 0 || state.serverReadPending) add(MenuSection(listOf(
                MenuAction(if (state.serverReadPending) "正在同步服务端已读…" else "服务端已读 · $serverReadCount") {
                  controller.markServerRead()
                }
              ), title = "已读同步"))
              add(MenuSection(listOf(
                MenuAction("搜索", MiuixIcons.Search) { searching = true },
                MenuAction("设备管理", MiuixIcons.GridView) { onManageDevices() },
                MenuAction("已归档", ARCHIVE_ICON) { onPage(RootPage.ARCHIVED) },
                MenuAction("设置", MiuixIcons.Settings) { onPage(RootPage.SETTINGS) }
              )))
            })
          }
        }
      )
      if (!searching) GlobalTaskStrip(state, controller, onArchived = {
        controller.setAllServers(true)
        onPage(RootPage.ARCHIVED)
      })
      if (!state.multiServerView) ConnectionStrip(state, controller)
      Box(Modifier.weight(1f).fillMaxWidth()) {
        SessionList(state, controller, grouping, query.trim(), onOpen, onNewSession, onManageDevices,
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

/**
 * The in-app counterpart of the system island. The island is server-wide and project-wide; the
 * session list below is filtered (single server / one project / archived hidden), so this strip is
 * the only place where the two scopes are visible next to each other. It uses the exact same
 * `globalSummary` (notification-enabled servers, offline TTL applied) as the notification.
 */
@Composable
private fun GlobalTaskStrip(state: LagoonState, controller: LagoonController, onArchived: () -> Unit) {
  val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
  val grouped = state.homeGroup != null
  val summary = if (grouped) state.summary else state.globalSummary
  val archived = state.archivedTaskCount(now)
  val silent = state.silentServersWithTasks(now)
  if (summary.isEmpty && archived == 0 && silent.isEmpty()) return
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp)
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(if (grouped) "${state.homeGroup?.name.orEmpty()} · 全部项目" else "全部设备 · 全部项目",
        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote2.copy(color = muted))
      if (state.showsGlobalViewAll()) {
        Text("查看全部", Modifier.clip(CircleShape).clickable(role = Role.Button) {
          controller.setScope(null)
          controller.setAllServers(true)
        }.padding(horizontal = 9.dp, vertical = 6.dp), maxLines = 1,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary))
      }
    }
    if (!summary.isEmpty) Text(summary.countLine, maxLines = 2, overflow = TextOverflow.Ellipsis,
      style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface))
    if (archived > 0) {
      Text("已归档会话另有 $archived 个任务 · 查看",
        Modifier.clip(CircleShape).clickable(role = Role.Button) {
          controller.setAllServers(true)
          onArchived()
        }.padding(vertical = 3.dp), maxLines = 2,
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary))
    }
    if (silent.isNotEmpty()) {
      Text("通知已关闭：${silent.joinToString("、")}（未计入灵动岛）", maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Warning))
    }
  }
}

@Composable
private fun HomeTopBar(
  title: String,
  statusDot: Color?,
  onTitleClick: () -> Unit,
  action: (@Composable () -> Unit)? = null,
  menu: @Composable () -> Unit
) {
  Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f)) {
    Row(Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onTitleClick).padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically) {
      Icon(MiuixIcons.GridView, null, Modifier.size(26.dp), tint = MiuixTheme.colorScheme.onSurface)
      Spacer(Modifier.width(10.dp))
      Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface))
      statusDot?.let {
        Spacer(Modifier.width(6.dp))
        StatusDot(it)
      }
      Spacer(Modifier.width(4.dp))
      DropdownChevron(20.dp, MiuixTheme.colorScheme.onSurface, "切换范围")
    }
    }
    Spacer(Modifier.width(8.dp))
    if (action != null) { action(); Spacer(Modifier.width(2.dp)) }
    menu()
  }
}

/**
 * Aggregate connection health of the current scope, replacing the dots the old capsule row showed.
 * Green means at least one member is streaming, amber means connected or connecting, and muted means
 * nothing is reachable. It is one small dot, not another selector row.
 */
@Composable
private fun homeScopeDot(state: LagoonState): Color? {
  val profiles = when {
    state.allServers -> state.enabledProfiles
    state.homeGroupId != null -> state.deviceTabs.firstOrNull { it.group?.id == state.homeGroupId }?.profiles.orEmpty()
    else -> listOfNotNull(state.server)
  }
  // Aggregate/group projections expose "any member connected", not the active profile's state; the
  // per-profile snapshot is the only truthful source there. A single-server home can use its live one.
  fun connected(profile: ServerProfile): Boolean =
    if (!state.multiServerView && profile.id == state.serverId) state.connected
    else state.allServerSnapshots[profile.id]?.connected == true
  fun streaming(profile: ServerProfile): Boolean =
    if (!state.multiServerView && profile.id == state.serverId) state.streamConnected
    else state.allServerSnapshots[profile.id]?.streamConnected == true
  fun loading(profile: ServerProfile): Boolean =
    if (!state.multiServerView && profile.id == state.serverId) state.loading
    else state.allServerSnapshots[profile.id]?.loading == true
  val anyConnected = profiles.any(::connected)
  val anyStreaming = profiles.any(::streaming)
  val anyLoading = profiles.any(::loading)
  return when {
    anyConnected && anyStreaming -> MiuixColorTokens.Success
    anyConnected || anyLoading -> MiuixColorTokens.Warning
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
  }
}

@Composable
private fun SessionList(
  state: LagoonState, controller: LagoonController, grouping: HomeGrouping, query: String,
   onOpen: (SessionTarget) -> Unit, onNewSession: () -> Unit, onManageDevices: () -> Unit,
  onRename: (Session) -> Unit, onDelete: (Session) -> Unit
) {
  var refreshing by remember { mutableStateOf(false) }
  val refreshScope = rememberCoroutineScope()
  val list = rememberLazyListState()
  val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() } }
  val active = remember(state.tasks, state.parents, state.permissionPolicy, state.autoApprovalFailures, state.connected, state.lastContactAt, now) { state.activeRootTasks }
  val statuses = remember(state.sessions, state.notices, active, state.backgroundRunning, state.allSessionStatuses, state.multiServerView, state.allServerSnapshots, state.permissionPolicy, state.autoApprovalFailures, now) {
    if (state.multiServerView) state.allSessionStatuses
    else state.sessions.associate { it.key to state.sessionStatus(it, active) }
  }
  val unseen = remember(state.notices, state.allUnseenSessions, state.multiServerView) {
    if (state.multiServerView) state.allUnseenSessions else state.notices.filterNot { it.viewed }.map { it.sessionId }.toSet()
  }
  // Display-only workspace labels. They never filter, sort, or choose a new-session target.
  val workspaceLabels = remember(state.sessions, state.projects, state.workspaceCatalog, state.allServerSnapshots, state.serverId) {
    sessionWorkspaceLabels(state.sessions, state.projects) { session -> state.workspaceCatalogFor(session) }
  }
  // Remembered on their inputs: the home list used to regroup on every state change (each streamed
  // event of any open conversation), not only when sessions, statuses or the filter changed.
  val roots = remember(state.sessions, state.projects, state.scopeProjectId, state.scopeWorkspaceKey, state.homeGroupId, query, workspaceLabels) {
    val workspaceScope = state.scopeWorkspaceKey
    val groupId = state.homeGroupId
    HomeScope.roots(state.sessions, state.projects, state.scopeProjectId)
      .filter { session -> state.allServers || workspaceScope == null || groupId == null || deviceWorkspaceKey(session, state.projects, groupId) == workspaceScope }
      .filter { session ->
        query.isBlank() || session.displayTitle().contains(query, true) ||
          resolveSessionProject(session, state.projects)?.name?.contains(query, true) == true ||
          session.directory.contains(query, true) ||
          workspaceLabels[session.key]?.name?.contains(query, true) == true
      }
  }
  // Status is an input: the DATE view promotes live/unread rows and ranks each date section by it,
  // so a status change must reorder immediately instead of waiting for the 60-second clock tick.
  val sections = remember(grouping, roots, state.projects, statuses, state.pinned, state.homeGroup?.id, now) {
    val group = state.homeGroup
    if (group != null && grouping == HomeGrouping.PROJECT) {
      val visible = roots.mapTo(HashSet()) { it.key }
      deviceWorkspaces(roots, state.projects, group.id).mapNotNull { workspace ->
        val sessions = workspace.sessions.filter { it.key in visible }
        if (sessions.isEmpty()) null
        else HomeSection("workspace:${workspace.key.replace('\u0000', ':')}", workspace.name, sessions)
      }
    } else HomeSections.build(grouping, roots, state.projects, statuses, state.pinned, now)
  }
  // Priority changes can move rows (or promote an unread result into 今天). While the reader touches
  // or scrolls the list the order is held, so the row under the finger is still the one they meant
  // when they lift it; statuses stay live. Moves then animate instead of jumping.
  var touching by remember { mutableStateOf(false) }
  val latestSections by rememberUpdatedState(sections)
  var shownSections by remember { mutableStateOf(sections) }
  LaunchedEffect(list) {
    snapshotFlow { (touching || list.isScrollInProgress) to latestSections }.collect { (held, next) -> if (!held) shownSections = next }
  }
  // Grouping or the search changing is the reader's own action: show it at once.
  LaunchedEffect(grouping, query) { shownSections = latestSections }
  val visibleSections = if (touching || list.isScrollInProgress) shownSections else sections
  // Older sessions load as the end of the list comes near; a failed page waits until the reader scrolls back.
  val nearEnd by remember { derivedStateOf {
    val info = list.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    last != null && last.index >= info.totalItemsCount - 4
  } }
  var attemptedCursors by remember { mutableStateOf<Map<String, String>?>(null) }
  LaunchedEffect(nearEnd) { if (!nearEnd) attemptedCursors = null }
  val loadingMore = state.pending("sessions-more")
  LaunchedEffect(nearEnd, state.sessionCursors, loadingMore, query) {
    if (nearEnd && query.isBlank() && !loadingMore && state.sessionCursors.isNotEmpty() && state.sessionCursors != attemptedCursors) {
      attemptedCursors = state.sessionCursors
      controller.loadMoreSessions()
    }
  }
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary

  PullToRefresh(isRefreshing = refreshing, onRefresh = { refreshing = true; refreshScope.launch { controller.reload().join(); refreshing = false } },
    refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "已刷新")) {
    LazyColumn(Modifier.fillMaxSize().overScrollVertical().pointerInput(Unit) {
      awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        touching = true
        try { do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed }) } finally { touching = false }
      }
    }, state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)) {
      state.homeSyncNotice()?.let { notice -> item(key = "sync") {
        Text(notice, style = MiuixTheme.textStyles.footnote1, color = MiuixColorTokens.Warning,
          modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { controller.reload() }.padding(horizontal = 24.dp, vertical = 8.dp))
      } }
      visibleSections.forEach { section ->
        // While searching every match stays visible, whatever was collapsed.
        val collapsed = query.isBlank() && section.key in state.collapsedSections
        item(key = "section:${section.key}", contentType = "section") {
          SectionHeader(section.title, collapsed) { controller.toggleSection(section.key) }
        }
         if (!collapsed) items(section.sessions, key = { "session:${section.key}:${it.key}" }, contentType = { "session" }) { session ->
          val pinned = session.id in state.pinned
          val background = if (state.multiServerView) session.serverId?.let { id -> state.allServerSnapshots[id]?.backgroundRunning?.contains(session.id) } == true
            else state.backgroundRunning.contains(session.id)
          val workspace = workspaceLabels[session.key]
          val showWorkspace = showWorkspaceLabel(section.key, state.scopeProjectId, state.scopeWorkspaceKey, workspace?.projectKey)
          Box(Modifier.animateItem()) { HomeSessionRow(session, statuses[session.key] ?: SessionStatus.NONE, session.key in unseen,
            background = background,
            serverName = if (state.multiServerView) session.serverId?.let { id -> state.profiles.firstOrNull { it.id == id }?.name } else null,
            harness = if (state.multiServerView) session.serverId?.let { id -> state.profiles.firstOrNull { it.id == id }?.kind } else null,
            workspace = workspace?.name?.takeIf { showWorkspace },
            onClick = { (session.target ?: state.serverId?.let { SessionTarget(it, session.id) })?.let(onOpen) }, actions = { buildList {
            if (!state.multiServerView) {
              add(MenuAction(if (pinned) "取消置顶" else "置顶", if (pinned) MiuixIcons.Unpin else MiuixIcons.Pin) { controller.togglePin(session.id) })
              if (state.connected && state.capabilities.supports(SessionAction.RENAME)) add(MenuAction("重命名", MiuixIcons.Rename) { onRename(session) })
              if (state.connected && state.capabilities.archive != null) add(MenuAction("归档", ARCHIVE_ICON) { controller.setArchived(session.id, true) })
              if (state.connected && state.capabilities.deleteSession) add(MenuAction("删除", MiuixIcons.Delete, danger = true) { onDelete(session) })
            }
          } }) }
        }
      }
      if (visibleSections.isEmpty()) item(key = "empty") {
        if (state.loading && state.sessions.isEmpty() && query.isBlank()) SessionRowsSkeleton()
        else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          val allDisabled = state.enabledProfiles.isEmpty() && state.profiles.isNotEmpty()
          Text(when {
            state.loading -> "正在读取会话…"
            query.isNotBlank() -> "没有符合条件的会话"
            allDisabled -> "所有连接已禁用"
            state.profiles.isEmpty() -> "还没有添加设备"
            state.connected -> "暂无会话"
            else -> "尚未连接"
          }, color = muted)
          if (!state.loading && query.isBlank()) {
            val action = when {
              state.connected || state.enabledProfiles.isNotEmpty() -> "新建会话"
              allDisabled || state.profiles.isEmpty() -> "管理设备"
              else -> "重新连接"
            }
            TextButton(text = action, onClick = {
              when {
                state.connected || state.enabledProfiles.isNotEmpty() -> onNewSession()
                allDisabled || state.profiles.isEmpty() -> onManageDevices()
                else -> controller.reload()
              }
            })
          }
        }
      }
      if (state.sessionCursors.isNotEmpty()) item(key = "more") {
        TextButton(text = if (state.pending("sessions-more")) "正在加载…" else "加载更多会话", enabled = !state.pending("sessions-more"),
          onClick = { controller.loadMoreSessions() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
      } else if (!state.catalogComplete) item(key = "cached-limit") {
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
 * One session: a 24dp state glyph and the title, plus an optional muted meta line (workspace, and on
 * the aggregate home the harness + device). Running spins, waiting for a reply shows the warning mark,
 * an unread result is a primary (or error) dot with a medium title; everything else is a quiet dot.
 * Long press opens [actions] (置顶 / 重命名 / 归档 / 删除 on the home list).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeSessionRow(session: Session, status: SessionStatus, unseen: Boolean, background: Boolean = false,
  serverName: String? = null, harness: ServerKind? = null, workspace: String? = null,
  onClick: () -> Unit, actions: () -> List<MenuAction>) {
  // Built only on long press: a list of actions (and their lambdas) per row per recomposition was waste.
  var menuItems by remember { mutableStateOf(emptyList<MenuAction>()) }
  var menu by remember { mutableStateOf(false) }
  val haptics = LocalHapticFeedback.current
  val highlighted = unseen || status in setOf(SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION)
  Box {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
      .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = {
        val items = actions()
        if (items.isNotEmpty()) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menuItems = items; menu = true }
      })
      .padding(start = 20.dp, end = 24.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StatusGlyph(status, unseen) }
      Spacer(Modifier.width(12.dp))
       Column(Modifier.weight(1f)) {
         Text(session.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
           style = MiuixTheme.textStyles.body1.copy(fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal, color = MiuixTheme.colorScheme.onSurface))
         val meta = listOfNotNull(serverName, workspace).joinToString(" · ")
         if (harness != null || meta.isNotBlank()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
           harness?.let { HarnessBadge(it); Spacer(Modifier.width(6.dp)) }
           if (meta.isNotBlank()) Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
             style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
         }
       }
       if (background && status != SessionStatus.RUNNING) {
         Spacer(Modifier.width(8.dp))
         BackgroundMarker()
       }
       if (status == SessionStatus.WAITING_SERVER) {
         Spacer(Modifier.width(8.dp))
         Text(status.label, style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else if (status == SessionStatus.FAILED) {
        Spacer(Modifier.width(8.dp))
        Text(status.label, style = MiuixTheme.textStyles.footnote1.copy(color = MiuixColorTokens.Error))
      } else if (status == SessionStatus.WAITING_PERMISSION || status == SessionStatus.WAITING_QUESTION) {
        Spacer(Modifier.width(8.dp))
        Text(status.label, style = MiuixTheme.textStyles.footnote1.copy(color = MiuixColorTokens.Warning))
      }
    }
    MenuPopup(menu, { menu = false }, listOf(MenuSection(menuItems)))
  }
}

@Composable
private fun StatusGlyph(status: SessionStatus, unseen: Boolean) {
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  when {
    status == SessionStatus.RUNNING ->
      InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary, size = 16.dp, strokeWidth = 2.dp, orbitingDotSize = 2.dp)
    status == SessionStatus.WAITING_SERVER ->
      Icon(MiuixIcons.Info, status.label, Modifier.size(20.dp), tint = muted)
    status == SessionStatus.WAITING_PERMISSION || status == SessionStatus.WAITING_QUESTION ->
      Icon(MiuixIcons.Info, status.label, Modifier.size(20.dp), tint = MiuixColorTokens.Warning)
    status == SessionStatus.FAILED -> Box(Modifier.size(8.dp).background(MiuixColorTokens.Error, CircleShape))
    unseen || status == SessionStatus.COMPLETED -> Box(Modifier.size(8.dp).background(MiuixTheme.colorScheme.primary, CircleShape))
    else -> Box(Modifier.size(6.dp).background(muted.copy(alpha = 0.3f), CircleShape))
  }
}

/**
 * A deliberately quiet marker: the session's own execution is finished, but its family still has a
 * running shell or child session. It never replaces the unread dot and never animates.
 */
@Composable
private fun BackgroundMarker() {
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  Box(Modifier.size(7.dp).clip(CircleShape).background(muted.copy(alpha = 0.38f))) {
    Box(Modifier.fillMaxSize().padding(2.dp).clip(CircleShape).background(MiuixTheme.colorScheme.surface))
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
