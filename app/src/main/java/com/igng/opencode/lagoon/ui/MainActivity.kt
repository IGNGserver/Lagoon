package com.igng.opencode.lagoon.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.metrics.performance.JankStats
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
  private var deepLink by mutableStateOf<Pair<String, String>?>(null)
  /** 已处理的深链目标（随实例状态保存）：旋转/改字体触发重建时不再重复跳回同一会话。 */
  private var handledDeepLink: String? = null
  private lateinit var navigator: RootNavigator
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handledDeepLink = savedInstanceState?.getString(KEY_HANDLED_DEEP_LINK)
    parseDeepLink(intent)
    val controller = LagoonController.get(this)
    navigator = RootNavigator(
      SessionNavigation(
        page = savedInstanceState?.getString(KEY_NAV_PAGE)?.let { runCatching { RootPage.valueOf(it) }.getOrNull() } ?: RootPage.HOME,
        sessions = savedInstanceState?.getStringArrayList(KEY_NAV_SESSIONS).orEmpty(),
        device = savedInstanceState?.getString(KEY_NAV_DEVICE),
        editors = decodeDeviceEditors(savedInstanceState?.getStringArrayList(KEY_NAV_EDITORS))
      ),
      if (savedInstanceState?.containsKey(KEY_NAV_SERVER) == true) savedInstanceState.getString(KEY_NAV_SERVER) else controller.state.value.serverId
    )
    // 先于 setContent 注册：优先级最低，键盘、MIUIX 弹层与各页面的 BackHandler 都排在它前面。
    onBackPressedDispatcher.addCallback(navigator.callback)
    // Opening the app refreshes from the server; leaving it lets the live stream wind down.
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) controller.onForeground()
      else if (event == Lifecycle.Event.ON_STOP) controller.onBackground()
    })
    // Frame timing per page for the copied diagnostics (FrameStats); paused while not visible.
    val jank = JankStats.createAndTrack(window) { frame -> FrameStats.record(frame.frameDurationUiNanos, frame.isJank) }
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) jank.isTrackingEnabled = true
      else if (event == Lifecycle.Event.ON_PAUSE) jank.isTrackingEnabled = false
    })
    setContent {
      val state by controller.state.collectAsState()
      // Chat/draft pages do not need the home aggregate; computing it on every streamed token was
      // pure CPU. Only root/list pages (no session in the stack) use it.
      val home = if (navigator.navigation.sessions.isEmpty()) state.homeProjection() else state
      // A device group scopes the new-session server list; 全部设备 leaves every enabled profile open.
      val draftScope = home.homeGroup?.memberIds?.toSet()
      val preferences = remember { getSharedPreferences("ui", MODE_PRIVATE) }
      var themeMode by remember { mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString("themeMode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)) }
      val systemDark = isSystemInDarkTheme()
      val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.SYSTEM && systemDark
      var previewBack by remember { mutableStateOf(preferences.getBoolean("previewBack", true)) }
      var refreshMode by remember { mutableStateOf(controller.currentRefreshMode()) }
      // 设备详情与整页编辑器是路由上的一层（见 SessionNavigation.openDevice / openEditor）：以前用
      // showingAddDevice / detailId 这类布尔量覆盖整页，切换只是同一页里的内容替换，AnimatedContent
      // 收不到路由变化，设备子树的进退场就全都没有动画。
      var showingScope by rememberSaveable { mutableStateOf(false) }
       var showingModelManagement by rememberSaveable { mutableStateOf(false) }
       var showingRemoteEnroll by rememberSaveable { mutableStateOf(false) }
       var showingRemoteHosts by rememberSaveable { mutableStateOf(false) }
       LaunchedEffect(controller) {
         controller.remoteRecoveryHandler = { showingRemoteHosts = false; showingRemoteEnroll = true }
       }
       var showingDeviceGroups by rememberSaveable { mutableStateOf(false) }
       var editingGroupId by rememberSaveable { mutableStateOf<String?>(null) }
      // Each blank draft gets a fresh saveable scope so a sent draft never reappears.
      var draftNonce by rememberSaveable { mutableIntStateOf(0) }
      var globalMessage by remember { mutableStateOf<String?>(null) }
      var globalMessageType by remember { mutableStateOf(MiuixToastType.INFO) }
       val holder = rememberSaveableStateHolder()
       val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
      val focusManager = LocalFocusManager.current
      val density = LocalDensity.current
      val inChatDetail = navigator.navigation.sessions.isNotEmpty()
      val route = navigator.route
      fun openSession(target: SessionTarget, child: Boolean = false) {
        focusManager.clearFocus()
        // 先落栈（同步启用根返回回调），再打开会话：打开过程占住主线程也不会让返回落到系统。
        navigator.server = target.serverId
        val lineage = controller.state.value.lineage(target.sessionId)
        navigator.navigate(if (lineage != null) navigator.navigation.openLineage(lineage)
          else navigator.navigation.open(target.sessionId, child))
        controller.openConversation(target)
      }
       // 整页编辑器：添加入口、编辑已有连接、SSH 主机表单都是路由上的一层，进出场走同一套页面弹簧。
       fun openEditor(target: DeviceEditorRoute) {
         focusManager.clearFocus()
         navigator.navigate(navigator.navigation.openEditor(target))
       }
       fun addDevice(tab: AddDeviceTab = AddDeviceTab.OPENCODE) = openEditor(DeviceEditorRoute(tab = tab))
       fun editDevice(profile: ServerProfile) = openEditor(
         DeviceEditorRoute(
           tab = if (profile.kind == ServerKind.OPENCODE) AddDeviceTab.OPENCODE else AddDeviceTab.DSH,
           profileId = profile.id,
           sshHostId = profile.sshHostId
         )
       )
       // A blank draft always opens immediately: connecting is the page's job, not a gate on this
       // button. The server is the only mandatory choice; its workspace is picked on the page.
       fun openDraftFor(profile: ServerProfile) {
         focusManager.clearFocus()
         navigator.server = profile.id
         controller.enterDraft(profile.id)
         draftNonce += 1
         navigator.navigate(navigator.navigation.copy(sessions = listOf(DRAFT_SESSION)))
       }
       // Switching inside the blank page keeps what the reader typed; only the connection changes.
       fun switchDraftServer(profile: ServerProfile) {
         navigator.server = profile.id
         controller.enterDraft(profile.id, keepComposer = true)
       }
       fun openDraft() {
         focusManager.clearFocus()
         // 加号直接进入空白页：默认选当前可写设备（不在首页范围时选第一台在线/启用的设备），
         // 进入后仍可在标题的「服务器与工作区」面板里改。没有可用设备时才退回提示。
         val target = state.defaultDraftServer(draftScope)
         if (target == null) {
           when {
             state.profiles.isEmpty() -> addDevice()
             draftScope != null -> { globalMessage = "这个设备组没有启用的连接"; globalMessageType = MiuixToastType.INFO }
             else -> { globalMessage = "所有连接均已禁用，请先在设备管理中启用"; globalMessageType = MiuixToastType.INFO }
           }
           return
         }
         openDraftFor(target.profile)
       }
      fun openPage(page: RootPage) { focusManager.clearFocus(); navigator.navigate(navigator.navigation.push(page)) }
      fun goBack() = navigator.goBack()
      SideEffect {
        navigator.onBack = { from, to ->
          focusManager.clearFocus()
          if (from.sessions.lastOrNull() == DRAFT_SESSION && to.sessions.isEmpty()) controller.endDraft()
          to.sessions.lastOrNull()?.let(controller::selectSession)
          val current = controller.state.value
          val home = current.homeServerId
          if (leavesSession(from, to) && !current.allServers) {
            val group = current.homeGroup
            if (group != null) controller.selectGroup(group.id)
            else if (home != null && home != current.serverId) controller.connect(home)
          }
        }
      }
      LaunchedEffect(state.serverId) {
        if (navigator.server != state.serverId) {
          // A blank draft belongs to whichever server is current, so it follows the switch instead of
          // being popped. A chat is bound to one server's session id and must never be left dangling.
          if (navigator.navigation.sessions.isNotEmpty() && navigator.navigation.sessions.last() != DRAFT_SESSION) {
            navigator.navigate(SessionNavigation())
          }
          navigator.server = state.serverId
        }
      }
       LaunchedEffect(state.sessionId) {
        val id = state.sessionId
        val stack = navigator.navigation.sessions
         if (stack.isNotEmpty() && id != null && stack.last() != id) {
           val lineage = controller.state.value.lineage(id)
           navigator.navigate(if (lineage != null) navigator.navigation.openLineage(lineage)
             else navigator.navigation.copy(sessions = if (state.session?.parentId == stack.last()) stack + id else stack.dropLast(1) + id))
         }
       }
       SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
      }
      DisposableEffect(state.serverId, state.sessionId, inChatDetail) {
        val server = state.serverId; val session = state.sessionId
        fun visible(value: Boolean) { if (server != null && session != null) controller.conversationVisible(server, session, value && inChatDetail) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) visible(true) else if (event == Lifecycle.Event.ON_PAUSE) visible(false) }
        lifecycle.addObserver(observer); visible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); visible(false) }
      }
      LaunchedEffect(state.profiles.isEmpty()) { if (state.profiles.isEmpty()) addDevice() }
      LaunchedEffect(state.error) { state.error?.let { globalMessage = it; globalMessageType = MiuixToastType.ERROR; controller.clearError() } }
      LaunchedEffect(state.message, state.messageTone) {
        state.message?.let {
          globalMessage = it
          globalMessageType = when (state.messageTone) {
            FeedbackTone.SUCCESS -> MiuixToastType.SUCCESS
            FeedbackTone.WARNING -> MiuixToastType.WARNING
            FeedbackTone.INFO -> MiuixToastType.INFO
          }
          controller.clearMessage()
        }
      }
       LaunchedEffect(deepLink) {
         val (server, session) = deepLink ?: return@LaunchedEffect
         handledDeepLink = "$server:$session"
          val profile = state.profiles.firstOrNull { it.id == server }
          if (profile == null) { globalMessage = "这个设备已移除"; globalMessageType = MiuixToastType.ERROR; deepLink = null }
          else if (!profile.enabled) { globalMessage = "这个连接已禁用，请先在设备管理中启用"; globalMessageType = MiuixToastType.ERROR; deepLink = null }
          else {
            navigator.navigate(SessionNavigation()); openSession(SessionTarget(server, session)); deepLink = null
          }
       }
      // —— 页面转场：只有一条时间轴 ——
      // 拖动、松手、取消都只改 SeekableTransitionState 的进度（fraction），两页由同一个转场驱动。
      // 松手那一帧因此不存在"手势轨 → 转场轨"的交接：旧实现正是在那里拆掉 peek、清掉旧页姿态、
      // 又把目标页作为进入内容排在最后（层级翻转 + 进入首帧透明），于是先闪回手势前的页面再闪到目标页。
      val transitionState = remember { SeekableTransitionState(navigator.route) }
      val pageTransition = rememberTransition(transitionState, label = "page")
      var motion by remember { mutableStateOf(PageMotion.FORWARD) }
      val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
      SideEffect {
        navigator.screenWidth = screenWidthPx
        FrameStats.page = when {
          route.editors.isNotEmpty() -> "设备编辑器"
          route.session == DRAFT_SESSION -> "新会话"
          route.session != null -> "会话"
          route.device != null -> "设备详情"
          route.page == RootPage.DEVICES -> "设备管理"
          route.page == RootPage.SETTINGS -> "设置"
          else -> "首页"
        }
      }
      // 普通路由变化（点会话、菜单、深链、实体返回键、设置里关掉预览后的侧滑）由这里播；
      // 预测式返回的转场由下面那条驱动，两个写入者不会抢同一个进度。
      LaunchedEffect(navigator.route, navigator.gesture?.id) {
        val target = navigator.route
        if (motion == PageMotion.GESTURE) return@LaunchedEffect
        if (transitionState.targetState == target) return@LaunchedEffect
        motion = pageMotion(transitionState.currentState, target)
        transitionState.animateTo(target)
      }
      // 手势只写进度：跟手期间 seek 到手指位置，松手后把同一段转场用弹簧补完，取消时把进度弹回 0。
      LaunchedEffect(navigator.gesture?.id, navigator.gesture?.phase) {
        val gesture = navigator.gesture ?: return@LaunchedEffect
        when (gesture.phase) {
          BackPhase.ACTIVE -> {
            val backTo = gesture.peek ?: return@LaunchedEffect
            // 上一次收尾还在播：先让它落地，别把它的目标页一起改掉。
            if (transitionState.currentState != navigator.route) transitionState.snapTo(navigator.route)
            // 关掉预览时不做跟手，也不留 GESTURE 模式：松手后交给上面那条普通路由转场播返回动画。
            motion = if (previewBack) PageMotion.GESTURE else PageMotion.BACK
            if (!previewBack) return@LaunchedEffect
            transitionState.seekTo(0f, backTo)
            snapshotFlow { navigator.progress }.collect { transitionState.seekTo(it) }
          }
          // 栈已经在 commit 里落到目标页，这里只把同一段转场从手指进度补到 1，姿态一路连续。
          BackPhase.COMMITTED -> {
            if (motion == PageMotion.GESTURE) {
              // 补上最后一帧手指位置：seek 走帧回调，可能比输入事件慢一帧。
              transitionState.seekTo(navigator.progress.coerceIn(0f, 1f))
              transitionState.animateTo(navigator.route, HyperMotion.backCommitSpring)
            }
            motion = PageMotion.BACK
            navigator.finish(gesture.id)
          }
          BackPhase.CANCELLED -> {
            if (motion == PageMotion.GESTURE) {
              settleFraction(transitionState, 0f, HyperMotion.backCancelSpring)
              transitionState.snapTo(navigator.route)
            }
            motion = PageMotion.BACK
            navigator.finish(gesture.id)
          }
        }
      }
      LagoonMiuixTheme(dark) {
        Scaffold(
          modifier = Modifier.imePadding(),
          contentWindowInsets = WindowInsets(0, 0, 0, 0),
          topBar = {},
          bottomBar = {}
        ) { insets ->
          Box(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
            @Composable
            fun Page(target: NavRoute, active: Boolean) {
              val displayed = when {
                // The blank page is not a server-scoped session id: it always follows the live
                // writable connection, including the moment a server switch is connecting.
                target.session == DRAFT_SESSION -> state
                target.session != null -> controller.conversationState(state, target.serverId, target.session)
                else -> state
              }
              when {
                // 整页编辑器压在设备列表/详情之上，与其它页一样由根路由驱动，所以进退场自动有动画。
                target.editors.isNotEmpty() -> DeviceEditorScreen(
                  state = state, controller = controller, route = target.editors.last(),
                  onBack = { goBack() },
                  onEditSsh = { hostId -> openEditor(DeviceEditorRoute(tab = AddDeviceTab.DSH, sshHostId = hostId)) },
                  onLoginHub = { goBack(); showingRemoteEnroll = true },
                  onManageHub = { goBack(); showingRemoteHosts = true }
                )
                target.session == DRAFT_SESSION -> DraftScreen(state, controller, onBack = { goBack() }, onModal = {}, interactive = active,
                  profileIds = draftScope, onSwitchServer = { profile -> switchDraftServer(profile) })
                 target.session != null -> ChatScreen(displayed, controller, onBack = { goBack() }, onOpenChild = { child ->
                   controller.state.value.serverId?.let { server -> openSession(SessionTarget(server, child), child = true) }
                 }, onModal = {}, interactive = active)
                 target.page == RootPage.DEVICES && target.device != null -> DeviceDetailScreen(
                   state = state, controller = controller, deviceId = target.device, onBack = { goBack() },
                   onEditProfile = { profile -> editDevice(profile) },
                   onManageHub = { showingRemoteHosts = true },
                   onManageModels = { showingModelManagement = true }
                 )
                 target.page == RootPage.DEVICES -> DeviceManagementScreen(
                   state = state, controller = controller, onBack = { goBack() },
                   onAddDevice = { addDevice() },
                   onEditGroup = { groupId -> editingGroupId = groupId; showingDeviceGroups = true },
                   onOpenDevice = { id -> navigator.navigate(navigator.navigation.openDevice(id)) },
                   onManageHub = { showingRemoteHosts = true },
                   onManageModels = { showingModelManagement = true }
                 )
                 target.page == RootPage.SETTINGS -> SettingsScreen(state, controller, themeMode, { themeMode = it; preferences.edit().putString("themeMode", it.name).apply() }, previewBack, { previewBack = it; preferences.edit().putBoolean("previewBack", it).apply() }, refreshMode, { refreshMode = it; controller.setRefreshMode(it) }, {
                   if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                   else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                 },
                   onManageDevices = { openPage(RootPage.DEVICES) }, onBack = { goBack() },
                    onMessage = { msg, type -> globalMessage = msg; globalMessageType = type })
                 else -> HomeScreen(home, controller, onOpen = { openSession(it) }, onNewSession = { openDraft() }, onScope = { showingScope = true },
                    onManageDevices = { openPage(RootPage.DEVICES) },
                    onPage = ::openPage)
              }
            }
            pageTransition.AnimatedContent(transitionSpec = {
              if (motion == PageMotion.GESTURE) {
                // 跟手：进入/退出全部等长线性，进度与手指 1:1；澎湃手感来自松手后作用在进度上的弹簧。
                ContentTransform(
                  targetContentEnter = slideInHorizontally(HyperMotion.gestureTrack()) { -it * navigator.direction * 28 / 100 } +
                    scaleIn(HyperMotion.gestureTrack(), initialScale = 0.94f),
                  initialContentExit = slideOutHorizontally(HyperMotion.gestureTrack()) { it * navigator.direction } +
                    scaleOut(HyperMotion.gestureTrack(), targetScale = 0.95f),
                  // 两页都满屏，尺寸动画只会多一条时间轴，必须关掉才能让进度与手指一一对应。
                  sizeTransform = null
                )
              } else {
                // 所有整页（含设备详情与整页编辑器）共用 HyperMotion.pageTransform 这一份弹簧规格：
                // 全幅位移 + 底层 -28% 视差与 0.94 缩放（底层不淡出变透）。
                pageTransform(slideDirection(initialState, targetState))
              }
            }, contentKey = { target ->
              // Content identity must match the saveable-scope identity. A draft is one page no
              // matter which server it is currently connected to; keying it by server made
              // AnimatedContent keep the old and new draft composed at once, which collides on
              // the shared "draft:<nonce>" saveable key.
              when {
                target.editors.isNotEmpty() -> "editor:${target.editors.size}:${target.editors.last().key}"
                target.session == DRAFT_SESSION -> "draft:$draftNonce"
                target.session != null -> "chat:${target.serverId}:${target.session}"
                target.device != null -> "device:${target.device}"
                else -> "page:${target.page}"
              }
            }) { target ->
              val active = target == route
              // 层级由转场模式决定，不靠 AnimatedContent"目标页最后画"的默认顺序。
              val front = frontRoute(motion, transitionState.currentState, transitionState.targetState) == target
              // 跟手时正在离开的那一页：投影与连续曲率圆角跟进度走，位移/缩放交给转场动画，避免叠加。
              val receding = motion == PageMotion.GESTURE && target == transitionState.currentState
              Box(
                Modifier.fillMaxSize()
                  .zIndex(if (front) 1f else 0f)
                  .graphicsLayer {
                    if (receding) {
                      val p = transitionState.fraction.coerceIn(0f, 1f)
                      shadowElevation = 24.dp.toPx() * (1f - p * 0.5f)
                      shape = miuixSquircleShape((28f * p.coerceAtMost(0.4f) / 0.4f).dp)
                      clip = true
                    }
                  }
                  .then(if (active) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } })
                  .background(HyperTheme.colors.canvas)
              ) {
                val key = when {
                  target.editors.isNotEmpty() -> "editor:${target.editors.size}:${target.editors.last().key}"
                  // 每台设备详情各有一份 saveable 作用域：过渡时新旧两页会同时组合，共用 key 会直接崩。
                  target.device != null -> "device:${target.device}"
                  // The server is deliberately not part of this key: switching harness from the
                  // blank page must keep the composer text and its saveable state.
                  target.session == DRAFT_SESSION -> "draft:$draftNonce"
                  target.session != null -> "chat:${target.serverId}:${target.session}"
                  else -> "page:${if (state.allServers) "all" else state.homeServerId}:${target.page}"
                }
                holder.SaveableStateProvider(key) { Page(target, active) }
                if (motion == PageMotion.GESTURE && !front) {
                  // 底层页随进度被打暗：遮罩与页面同层，松手前后亮度不会跳一下。
                  Box(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawRect(Color.Black, alpha = (1f - transitionState.fraction.coerceIn(0f, 1f)) * 0.22f)
                  })
                }
              }
            }
            // Per-page first-load states own their own placeholders. A global bar here meant any
            // background server's read made the whole app look busy.
            MiuixToastHost(globalMessage, globalMessageType, { globalMessage = null }, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 66.dp))
            // MIUIX popups must stay inside this Scaffold content's popup registry.
            // 整页编辑器不再挂在这里：它是路由上的一层，由上面的 AnimatedContent 渲染并在进退场时有动画；
            // 它内部的对话框仍然落在这个作用域里（见 PopupScopeRegressionTest）。
             if (showingScope) HomeScopeSheet(
               state = home,
               allSnapshots = state.allServerSnapshots,
               controller = controller,
               onDismiss = { showingScope = false },
               // Opening the group editor replaces the scope sheet instead of stacking two sheets.
               onEditGroup = { group ->
                 showingScope = false
                 editingGroupId = group.id
                 showingDeviceGroups = true
               },
               onManageDevices = {
                 showingScope = false
                 openPage(RootPage.DEVICES)
               }
             )
             if (showingDeviceGroups) DeviceGroupsSheet(state, controller, editingGroupId, onDismiss = {
               showingDeviceGroups = false; editingGroupId = null
             })
            if (showingModelManagement) ModelManagementSheet(state, controller) { showingModelManagement = false }
            if (showingRemoteHosts) RemoteHostsSheet(controller, onDismiss = { showingRemoteHosts = false }, onEnroll = {
              showingRemoteHosts = false; showingRemoteEnroll = true
            })
            if (showingRemoteEnroll) RemoteEnrollSheet(controller, onDismiss = { showingRemoteEnroll = false }, onDone = { added ->
              showingRemoteEnroll = false
              if (added > 0) { globalMessage = "已接入 $added 台远程主机"; globalMessageType = MiuixToastType.SUCCESS }
            })
          }
        }
      }
    }
  }
  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); parseDeepLink(intent) }
  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    handledDeepLink?.let { outState.putString(KEY_HANDLED_DEEP_LINK, it) }
    navigator.navigation.let {
      outState.putString(KEY_NAV_PAGE, it.page.name)
      outState.putStringArrayList(KEY_NAV_SESSIONS, ArrayList(it.sessions))
      // 设备详情与编辑器也是路由上的一层：旋转回来后仍停在同一页，不退回设备列表。
      it.device?.let { id -> outState.putString(KEY_NAV_DEVICE, id) }
      if (it.editors.isNotEmpty()) outState.putStringArrayList(KEY_NAV_EDITORS, encodeDeviceEditors(it.editors))
    }
    outState.putString(KEY_NAV_SERVER, navigator.server)
  }
  private fun parseDeepLink(intent: Intent?) {
    val uri = intent?.data ?: return
    if ((uri.scheme != "lagoon" && uri.scheme != "opencode-lagoon") || uri.host != "server") return
    val parts = uri.pathSegments
    if (parts.size >= 3 && parts[1] == "session") {
      val target = parts[0] to parts[2]
      deepLink = target.takeIf { "${it.first}:${it.second}" != handledDeepLink }
    }
  }

  private companion object {
    const val KEY_HANDLED_DEEP_LINK = "handledDeepLink"
    const val KEY_NAV_PAGE = "navPage"
    const val KEY_NAV_SESSIONS = "navSessions"
    const val KEY_NAV_DEVICE = "navDevice"
    const val KEY_NAV_EDITORS = "navEditors"
    const val KEY_NAV_SERVER = "navServer"
  }
}
