package com.igng.opencode.lagoon.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 会话首页（澎湃 OS 4 分组岛式架构）：
 * - 顶栏采用磨砂玻璃层 + 高辨识度范围胶囊（含连接健康度与在线统计），点按打开统一范围面板（onScope）；
 * - 顶部动态「待处理 / 运行中」焦点岛（GlobalTaskStrip），支持一键聚焦或查看全部；
 * - 分组岛式会话卡片列表（HomeSessionRow），内联展示实时执行工具摘要、状态角标与等宽相对时间。
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
  var contentSearchTarget by remember { mutableStateOf<SessionContentSearchTarget?>(null) }
  var searching by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable(state.allServers, state.serverId, state.homeGroupId) { mutableStateOf("") }
  var renaming by remember { mutableStateOf<Session?>(null) }
  var deleting by remember { mutableStateOf<DeleteSessionTarget?>(null) }
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
  Box(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    Column(Modifier.fillMaxSize()) {
      if (searching) SearchTopBar(query, { query = it }, ::closeSearch)
      else HomeTopBar(
        title = state.homeGroup?.name ?: "Lagoon",
        action = if (hasUnread) {
          { HyperTextAction("全部已读", onClick = { controller.markAllRead() }) }
        } else null,
        menu = {
          Box {
            CircleIconButton(MiuixIcons.More, "更多", { menu = true })
            MenuPopup(menu, { menu = false }, buildList {
              add(MenuSection(HomeGrouping.entries.map { option ->
                MenuAction(option.label, selected = grouping == option) {
                  grouping = option
                  preferences.edit().putString("homeGrouping", option.name).apply()
                }
              }, title = "分组方式"))
              if (serverReadCount > 0 || state.serverReadPending) add(MenuSection(listOf(
                MenuAction(if (state.serverReadPending) "正在同步服务端已读…" else "服务端已读 · $serverReadCount") {
                  controller.markServerRead()
                }
              ), title = "已读同步"))
              add(MenuSection(buildList {
                add(MenuAction("本机已加载筛选", MiuixIcons.Search) { searching = true })
                if (state.canSearchSessionContent()) add(MenuAction("服务器正文搜索", MiuixIcons.Search) {
                  if (state.canSearchSessionContent()) state.server?.let { profile ->
                    contentSearchTarget = SessionContentSearchTarget(profile)
                  }
                })
                add(MenuAction("设备管理", MiuixIcons.GridView) { onManageDevices() })
                add(MenuAction("设置", MiuixIcons.Settings) { onPage(RootPage.SETTINGS) })
              }))
            })
          }
        }
      )
      if (!searching) {
        val scopeProfiles = when {
          state.allServers -> state.enabledProfiles
          state.homeGroup != null -> state.enabledProfiles.filter { it.id in state.homeGroup!!.memberIds }
          else -> listOfNotNull(state.server).filter { it.effectiveEnabled }
        }
        val online = scopeProfiles.count { profile ->
          if (!state.multiServerView && profile.id == state.serverId) state.connected
          else state.allServerSnapshots[profile.id]?.connected == true
        }
        HyperCard(
          Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
          insidePadding = PaddingValues(vertical = 4.dp)
        ) {
          HyperRow(
            title = scopeWorkspace?.name ?: if (state.allServers || state.homeGroup != null) "全部项目" else state.server?.name ?: "选择设备",
            summary = buildString {
              if (scopeWorkspace != null) append("当前项目 · ")
              // 设备数按 HostBinding 计：同一台机器上的多个 Harness 不再各算一台设备。
              val deviceCount = scopeProfiles.mapNotNull { controller.deviceOfLeaf(it.id) }.distinct().size
              append("${deviceCount.takeIf { it > 0 } ?: scopeProfiles.size} 台设备")
              if (scopeProfiles.isNotEmpty()) append(" · $online 台在线")
            },
            trailing = { DropdownChevron(18.dp, description = "切换设备与项目") },
            onClick = onScope
          )
        }
        GlobalTaskStrip(state, controller, onOpen = onOpen)
      }
      if (!state.multiServerView) ConnectionStrip(state, controller)
      Box(Modifier.weight(1f).fillMaxWidth()) {
        SessionList(
          state, controller, grouping, query.trim(), onOpen, onNewSession, onManageDevices,
          onRename = { renaming = it }, onDelete = { session ->
            deleting = state.server?.let { DeleteSessionTarget(it, session.id, session.displayTitle()) }
          }
        )
      }
    }
    if (!searching) {
      val fabShape = miuixSquircleShape(20.dp)
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .navigationBarsPadding()
          .padding(end = 22.dp, bottom = 22.dp)
          .size(58.dp)
          .shadow(14.dp, fabShape, ambientColor = HyperTheme.colors.primary.copy(alpha = 0.35f), spotColor = HyperTheme.colors.primary.copy(alpha = 0.35f))
          .clip(fabShape)
          .background(HyperTheme.colors.primary, fabShape)
          .border(0.8.dp, Color.White.copy(alpha = 0.22f), fabShape)
          .hyperPress(scaleDown = 0.92f, onClick = onNewSession),
        contentAlignment = Alignment.Center
      ) {
        Icon(MiuixIcons.Add, "新建会话", Modifier.size(26.dp), tint = HyperTheme.colors.onPrimary)
      }
    }
  }
  contentSearchTarget?.let { captured ->
    ServerContentSearchPanel(state, controller, captured, onDismiss = { contentSearchTarget = null }, onOpen = onOpen)
  }
  renaming?.let { session -> RenameSessionDialog(state, controller, session) { renaming = null } }
  deleting?.let { target ->
    SuperDialog(title = "删除此会话？", show = true, onDismissRequest = { if (!state.pending("delete")) deleting = null }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${target.warning()}\n${target.title}")
        state.resource("action:delete").takeIf { it.state == ResourceState.ERROR }?.error?.let {
          HyperInlineNotice(message = it, type = MiuixToastType.ERROR)
        }
        DialogActions("取消", { deleting = null }, if (state.pending("delete")) "删除中…" else "永久删除", !state.pending("delete") && target.matches(state), danger = true) {
          controller.deleteSession(target.sessionId, expected = target) { deleting = null }
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
  Column(Modifier.fillMaxWidth().background(HyperTheme.colors.glassChrome)) {
    Row(
      Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .heightIn(min = 64.dp)
        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      InputField(
        query = query,
        onQueryChange = onQuery,
        onSearch = {},
        expanded = true,
        onExpandedChange = {},
        label = "筛选已加载标题或项目",
        modifier = Modifier.weight(1f).focusRequester(focus)
      )
      HyperTextAction("取消", onClick = onClose)
    }
    Text("只筛选本机已加载列表，不搜索正文或未缓存会话",
      Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
      color = HyperTheme.colors.textSecondary, fontSize = 12.sp)
    HorizontalDivider(color = HyperTheme.colors.divider)
  }
}

/**
 * 澎湃 OS 4 顶部动态焦点岛（Focus Island）：
 * 聚合跨设备/项目的待处理（权限/问答）与运行中任务摘要，支持一键跳转至最急需处理的会话或切换至全部视图。
 */
@Composable
private fun GlobalTaskStrip(
  state: LagoonState,
  controller: LagoonController,
  onOpen: (SessionTarget) -> Unit = {}
) {
  val summary = if (state.homeGroup != null) state.summary else state.globalSummary
  val silent = state.silentServersWithTasks()
  if (summary.isEmpty && silent.isEmpty()) return
  val target = state.summaryTarget
  val title = when {
    summary.waiting > 0 -> "需要你处理"
    summary.running > 0 -> "正在运行"
    else -> "任务状态"
  }
  val detail = buildList {
    if (!summary.isEmpty) add(summary.countLine)
    if (silent.isNotEmpty()) add("${silent.joinToString("、")}已关闭任务通知")
  }.joinToString("\n")
  HyperCard(
    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    insidePadding = PaddingValues(vertical = 4.dp)
  ) {
    HyperRow(
      title = title,
      summary = detail,
      value = if (summary.waiting > 0) "${summary.waiting} 项" else null,
      showChevron = target != null,
      onClick = if (target != null) ({ onOpen(target) }) else null
    )
    if (state.showsGlobalViewAll()) {
      HyperTextAction(
        text = "查看全部设备",
        onClick = { controller.setScope(null); controller.setAllServers(true) },
        modifier = Modifier.align(Alignment.End).padding(end = 8.dp)
      )
    }
  }
}

@Composable
private fun HomeTopBar(
  title: String,
  action: (@Composable () -> Unit)? = null,
  menu: @Composable () -> Unit
) {
  Column(Modifier.fillMaxWidth().background(HyperTheme.colors.glassChrome)) {
    Row(
      Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        title,
        Modifier.weight(1f).padding(start = 8.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold)
      )
      action?.invoke()
      menu()
    }
    HorizontalDivider(color = HyperTheme.colors.divider)
  }
}

@Composable
private fun SessionList(
  state: LagoonState,
  controller: LagoonController,
  grouping: HomeGrouping,
  query: String,
  onOpen: (SessionTarget) -> Unit,
  onNewSession: () -> Unit,
  onManageDevices: () -> Unit,
  onRename: (Session) -> Unit,
  onDelete: (Session) -> Unit
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
  val workspaceLabels = remember(state.sessions, state.projects, state.workspaceCatalog, state.allServerSnapshots, state.serverId) {
    sessionWorkspaceLabels(state.sessions, state.projects) { session -> state.workspaceCatalogFor(session) }
  }
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
  var touching by remember { mutableStateOf(false) }
  val latestSections by rememberUpdatedState(sections)
  var shownSections by remember { mutableStateOf(sections) }
  LaunchedEffect(list) {
    snapshotFlow { (touching || list.isScrollInProgress) to latestSections }.collect { (held, next) -> if (!held) shownSections = next }
  }
  LaunchedEffect(grouping, query) { shownSections = latestSections }
  val visibleSections = if (touching || list.isScrollInProgress) shownSections else sections
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
  val muted = HyperTheme.colors.textSecondary

  PullToRefresh(
    isRefreshing = refreshing,
    onRefresh = { refreshing = true; refreshScope.launch { controller.reload().join(); refreshing = false } },
    refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "已刷新")
  ) {
    LazyColumn(
      Modifier
        .fillMaxSize()
        .overScrollVertical()
        .pointerInput(Unit) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            touching = true
            try { do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed }) } finally { touching = false }
          }
        },
      state = list,
      contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp)
    ) {
      state.homeSyncNotice()?.takeIf { visibleSections.isNotEmpty() }?.let { notice ->
        item(key = "sync") {
          HyperInlineNotice(
            message = notice,
            type = MiuixToastType.WARNING,
            action = "重试",
            onAction = { controller.reload() },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
          )
        }
      }
      visibleSections.forEach { section ->
        val collapsed = query.isBlank() && section.key in state.collapsedSections
        item(key = "section:${section.key}", contentType = "section") {
          SectionHeader(section.title, section.sessions.size, collapsed) { controller.toggleSection(section.key) }
        }
        if (!collapsed) item(key = "sessions:${section.key}", contentType = "sessionGroup") {
          HyperCard(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
            insidePadding = PaddingValues(vertical = 4.dp)
          ) {
            section.sessions.forEachIndexed { index, session ->
              if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          val pinned = session.id in state.pinned
          val background = if (state.multiServerView) session.serverId?.let { id -> state.allServerSnapshots[id]?.backgroundRunning?.contains(session.id) } == true
            else state.backgroundRunning.contains(session.id)
          val workspace = workspaceLabels[session.key]
          val showWorkspace = showWorkspaceLabel(section.key, state.scopeProjectId, state.scopeWorkspaceKey, workspace?.projectKey)
          val liveTask = if (state.multiServerView) {
            session.serverId?.let { id -> state.allServerSnapshots[id]?.tasks?.get(session.id) }
          } else active[session.id]
          Box(Modifier.animateItem()) {
            HomeSessionRow(
              session = session,
              status = statuses[session.key] ?: SessionStatus.NONE,
              unseen = session.key in unseen,
              pinned = pinned,
              background = background,
              liveDetail = liveTask?.detail?.takeIf { it.isNotBlank() && it != TaskState.RUNNING_DETAIL },
              relativeTime = formatRelative(session.updated, now),
              serverName = if (state.multiServerView) session.serverId?.let { id -> state.profiles.firstOrNull { it.id == id }?.name } else null,
              harness = if (state.multiServerView) session.serverId?.let { id -> state.profiles.firstOrNull { it.id == id } } else null,
              workspace = workspace?.name?.takeIf { showWorkspace },
              onClick = { (session.target ?: state.serverId?.let { SessionTarget(it, session.id) })?.let(onOpen) },
              actions = {
                buildList {
                  if (!state.multiServerView) {
                    add(MenuAction(if (pinned) "取消置顶" else "置顶", if (pinned) MiuixIcons.Unpin else MiuixIcons.Pin) { controller.togglePin(session.id) })
                    if (state.connected && state.capabilities.supports(SessionAction.RENAME)) add(MenuAction("重命名", MiuixIcons.Rename) { onRename(session) })
                    if (state.connected && state.capabilities.archive != null) add(MenuAction("归档", ARCHIVE_ICON) { controller.setArchived(session.id, true) })
                    if (state.connected && state.capabilities.deleteSession) add(MenuAction("删除", MiuixIcons.Delete, danger = true) { onDelete(session) })
                  }
                }
              }
            )
          }
            }
          }
        }
      }
      if (visibleSections.isEmpty()) item(key = "empty") {
        if (state.loading && state.sessions.isEmpty() && query.isBlank()) SessionRowsSkeleton()
        else HyperCard(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
          insidePadding = PaddingValues(horizontal = 24.dp, vertical = 36.dp)
        ) {
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val allDisabled = state.enabledProfiles.isEmpty() && state.profiles.isNotEmpty()
            Text(
              when {
                state.loading -> "正在读取会话…"
                query.isNotBlank() -> "没有符合条件的会话"
                allDisabled -> "所有连接已禁用"
                state.profiles.isEmpty() -> "还没有添加设备"
                state.connected -> "暂无会话"
                else -> "尚未连接"
              },
              style = MiuixTheme.textStyles.headline2.copy(color = muted)
            )
            if (!state.loading && query.isBlank()) {
              val action = when {
                state.connected || state.enabledProfiles.isNotEmpty() -> "新建会话"
                allDisabled || state.profiles.isEmpty() -> "管理设备"
                else -> "重新连接"
              }
              HyperButton(
                text = action,
                onClick = {
                  when {
                    state.connected || state.enabledProfiles.isNotEmpty() -> onNewSession()
                    allDisabled || state.profiles.isEmpty() -> onManageDevices()
                    else -> controller.reload()
                  }
                }
              )
            }
          }
        }
      }
      if (state.sessionCursors.isNotEmpty()) item(key = "more") {
        HyperButton(
          text = "加载更多会话",
          variant = HyperButtonVariant.SECONDARY,
          enabled = !state.pending("sessions-more"),
          loading = state.pending("sessions-more"),
          onClick = { controller.loadMoreSessions() },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
        )
      } else if (!state.catalogComplete) item(key = "cached-limit") {
        Text("缓存仅保留最近会话", modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = muted, style = MiuixTheme.textStyles.footnote2)
      }
    }
  }
}

/** "今天 · N ⌄" — tap to fold the section; the chevron points right while folded. */
@Composable
private fun SectionHeader(title: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
  val muted = HyperTheme.colors.textSecondary
  val angle by animateFloatAsState(
    targetValue = if (collapsed) 0f else 90f,
    animationSpec = HyperMotion.controlSpring(),
    label = "sectionChevronAngle"
  )
  Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(
      Modifier
        .padding(horizontal = 14.dp)
        .clip(CircleShape)
        .heightIn(min = HYPER_TOUCH_TARGET_DP.dp)
        .hyperPress(scaleDown = 0.96f, onClick = onToggle)
        .padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        title,
        style = MiuixTheme.textStyles.footnote1.copy(
          fontWeight = FontWeight.Bold,
          color = HyperTheme.colors.textPrimary
        )
      )
      Spacer(Modifier.width(6.dp))
      Text(
        "$count",
        style = HyperTheme.tabular(
          MiuixTheme.textStyles.footnote2.copy(
            fontWeight = FontWeight.SemiBold,
            color = muted
          )
        )
      )
      Spacer(Modifier.width(4.dp))
      QoderIcon(
        glyph = QoderGlyph.CHEVRON,
        modifier = Modifier.size(14.dp).rotate(angle),
        color = muted
      )
    }
  }
}

/** MIUIX icons have no archive glyph: putting away is the tray with a down arrow, restoring the arrow out. */
internal val ARCHIVE_ICON get() = MiuixIcons.Download
internal val UNARCHIVE_ICON get() = MiuixIcons.Import

/**
 * 澎湃 OS 4 会话卡片行（Session Island Row）：
 * 采用独立圆角卡片容器、高对比度标题字重、等宽相对时间，以及运行中的实时步骤摘要。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeSessionRow(
  session: Session,
  status: SessionStatus,
  unseen: Boolean,
  pinned: Boolean = false,
  background: Boolean = false,
  liveDetail: String? = null,
  relativeTime: String = "",
  serverName: String? = null,
  harness: com.igng.opencode.lagoon.core.ServerProfile? = null,
  workspace: String? = null,
  onClick: () -> Unit,
  actions: () -> List<MenuAction>
) {
  var menuItems by remember { mutableStateOf(emptyList<MenuAction>()) }
  var menu by remember { mutableStateOf(false) }
  val waiting = status in setOf(SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION)
  val highlighted = unseen || waiting || status == SessionStatus.RUNNING
  fun openMenu() {
    val items = actions()
    if (items.isNotEmpty()) { menuItems = items; menu = true }
  }
  Box(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .hyperPress(surfaceHighlight = true, onClick = onClick, onLongClick = ::openMenu)
        .padding(start = 14.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StatusGlyph(status, unseen) }
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            session.displayTitle(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.body1.copy(
              fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
              color = HyperTheme.colors.textPrimary
            )
          )
          if (pinned) {
            Spacer(Modifier.width(6.dp))
            Icon(MiuixIcons.Pin, "已置顶", Modifier.size(13.dp), tint = HyperTheme.colors.primary)
          }
          if (relativeTime.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(relativeTime, style = HyperTheme.tabular(MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textTertiary)), maxLines = 1)
          }
        }
        val meta = listOfNotNull(serverName, workspace, if (background) "后台" else null).joinToString(" · ")
        if (harness != null || meta.isNotBlank()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            harness?.let { HarnessBadge(it); Spacer(Modifier.width(6.dp)) }
            if (meta.isNotBlank()) Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
              style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary))
          }
        }
        val activity = liveDetail?.takeIf { it.isNotBlank() } ?: when (status) {
          SessionStatus.FAILED -> "执行失败"
          SessionStatus.WAITING_SERVER -> status.label
          SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION -> status.label
          else -> null
        }
        if (activity != null) Text(activity, maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(
            fontWeight = FontWeight.Medium,
            color = when {
              status == SessionStatus.FAILED -> HyperTheme.colors.error
              waiting -> HyperTheme.colors.warning
              else -> HyperTheme.colors.primary
            }
          ))
      }
      Spacer(Modifier.width(4.dp))
      CircleIconButton(MiuixIcons.More, "会话操作", ::openMenu)
    }
    MenuPopup(menu, { menu = false }, listOf(MenuSection(menuItems)))
  }
}

@Composable
private fun StatusPillTag(label: String, textColor: Color, bgColor: Color) {
  val shape = miuixSquircleShape(8.dp)
  Box(
    Modifier
      .clip(shape)
      .background(bgColor, shape)
      .padding(horizontal = 8.dp, vertical = 3.dp)
  ) {
    Text(
      label,
      style = MiuixTheme.textStyles.footnote2.copy(
        fontWeight = FontWeight.SemiBold,
        color = textColor
      ),
      maxLines = 1
    )
  }
}

@Composable
private fun StatusGlyph(status: SessionStatus, unseen: Boolean) {
  val muted = HyperTheme.colors.textSecondary
  when {
    status == SessionStatus.RUNNING ->
      InfiniteProgressIndicator(color = HyperTheme.colors.primary, size = 16.dp, strokeWidth = 2.dp, orbitingDotSize = 2.dp)
    status == SessionStatus.WAITING_SERVER ->
      Icon(MiuixIcons.Info, status.label, Modifier.size(20.dp), tint = muted)
    status == SessionStatus.WAITING_PERMISSION || status == SessionStatus.WAITING_QUESTION ->
      Icon(MiuixIcons.Info, status.label, Modifier.size(20.dp), tint = MiuixColorTokens.Warning)
    status == SessionStatus.FAILED -> Box(Modifier.size(9.dp).background(MiuixColorTokens.Error, CircleShape))
    unseen || status == SessionStatus.COMPLETED -> Box(Modifier.size(9.dp).background(HyperTheme.colors.primary, CircleShape))
    else -> Box(Modifier.size(6.dp).background(muted.copy(alpha = 0.32f), CircleShape))
  }
}

/**
 * A deliberately quiet marker: the session's own execution is finished, but its family still has a
 * running shell or child session. It never replaces the unread dot and never animates.
 */
@Composable
private fun BackgroundMarker() {
  val muted = HyperTheme.colors.textSecondary
  Box(Modifier.size(8.dp).clip(CircleShape).background(muted.copy(alpha = 0.42f))) {
    Box(Modifier.fillMaxSize().padding(2.dp).clip(CircleShape).background(HyperTheme.colors.cardElevated))
  }
}

/**
 * 权限模式选择（DSH 的 `permissionPresets/catalog` + `/permission` 命令）。
 * 只在该实例/会话真的带 `permissions` 投影时入口才可见，避免给没有权限目录的 harness 画空面板。
 */
@Composable
internal fun PermissionDialog(state: LagoonState, controller: LagoonController, session: Session, onDismiss: () -> Unit) {
  var options by remember(session.id) { mutableStateOf<List<PermissionPresetChoice>?>(null) }
  val pending = state.pending("permission")
  LaunchedEffect(session.id) { controller.loadPermissionPresets(session.directory) { options = it } }
  SuperDialog(title = "权限模式", show = true, onDismissRequest = { if (!pending) onDismiss() }) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      val current = session.permission
      val list = options
      if (list == null) {
        Text("正在读取可选的权限模式…")
      } else if (list.isEmpty()) {
        Text("该实例没有提供可选的权限目录")
      } else {
        list.forEach { option ->
          Row(
            modifier = Modifier.fillMaxWidth()
              .clickable(enabled = !pending) {
                controller.setPermission(option.value, session.id) { onDismiss() }
              }
              .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            if (option.value == current) {
              Icon(MiuixIcons.Ok, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
            } else {
              Spacer(Modifier.width(26.dp))
            }
            Column(Modifier.weight(1f)) {
              Text(option.name, fontWeight = if (option.value == current) FontWeight.SemiBold else FontWeight.Normal)
              if (option.description.isNotBlank()) {
                Text(option.description, color = MiuixTheme.colorScheme.onSurfaceVariantActions, fontSize = 12.sp)
              }
            }
          }
        }
      }
      state.resource("action:permission").takeIf { it.state == ResourceState.ERROR }?.error?.let {
        HyperInlineNotice(message = it, type = MiuixToastType.ERROR)
      }
      Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HyperButton(text = "关闭", variant = HyperButtonVariant.SECONDARY, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

/** Rename any session from the list; the same dialog the chat ⋯ menu shows. */
@Composable
internal fun RenameSessionDialog(state: LagoonState, controller: LagoonController, session: Session, onDismiss: () -> Unit) {
  var title by rememberSaveable(session.id) { mutableStateOf(session.displayTitle()) }
  SuperDialog(title = "重命名会话", show = true, onDismissRequest = { if (!state.pending("rename")) onDismiss() }) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      TextField(title, { title = it }, label = "会话名称", modifier = Modifier.fillMaxWidth())
      state.resource("action:rename").takeIf { it.state == ResourceState.ERROR }?.error?.let {
        HyperInlineNotice(message = it, type = MiuixToastType.ERROR)
      }
      DialogActions("取消", onDismiss, if (state.pending("rename")) "保存中…" else "保存", !state.pending("rename") && title.isNotBlank()) {
        controller.rename(title, session.id, onDismiss)
      }
    }
  }
}
