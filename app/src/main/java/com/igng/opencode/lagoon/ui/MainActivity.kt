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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.metrics.performance.JankStats
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
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
        savedInstanceState?.getString(KEY_NAV_PAGE)?.let { runCatching { RootPage.valueOf(it) }.getOrNull() } ?: RootPage.HOME,
        savedInstanceState?.getStringArrayList(KEY_NAV_SESSIONS).orEmpty()
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
      // The server sheet edits one profile (null = add a new one); switching happens on the home capsules.
       // Saveable: rotating or switching the system theme recreates the activity, which closed these sheets.
       var showingServerForm by rememberSaveable { mutableStateOf(false) }
       var editingServerId by rememberSaveable { mutableStateOf<String?>(null) }
       var showingAddDevice by rememberSaveable { mutableStateOf(false) }
       var addingOpenCodeId by rememberSaveable { mutableStateOf<String?>(null) }
       var addingSshHostId by rememberSaveable { mutableStateOf<String?>(null) }
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
             state.profiles.isEmpty() -> showingAddDevice = true
             draftScope != null -> { globalMessage = "这个设备组没有启用的连接"; globalMessageType = MiuixToastType.INFO }
             else -> { globalMessage = "所有连接均已禁用，请先在设备管理中启用"; globalMessageType = MiuixToastType.INFO }
           }
           return
         }
         openDraftFor(target.profile)
       }
      fun openPage(page: RootPage) { focusManager.clearFocus(); navigator.navigate(navigator.navigation.push(page)) }
      fun editServer(profile: ServerProfile?) {
        showingAddDevice = false; addingOpenCodeId = null; addingSshHostId = null
        editingServerId = profile?.id; showingServerForm = true
      }
      fun editDevice(profile: ServerProfile) {
        showingServerForm = false; editingServerId = null
        when {
          profile.sshHostId != null -> { addingSshHostId = profile.sshHostId; addingOpenCodeId = null; showingAddDevice = true }
          profile.kind == ServerKind.OPENCODE -> { addingOpenCodeId = profile.id; addingSshHostId = null; showingAddDevice = true }
          else -> editServer(profile)
        }
      }
      fun addDevice() {
        showingServerForm = false; editingServerId = null
        addingOpenCodeId = null; addingSshHostId = null; showingAddDevice = true
      }
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
      LaunchedEffect(state.message) { state.message?.let { globalMessage = it; globalMessageType = MiuixToastType.SUCCESS; controller.clearMessage() } }
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
      val backProgress = remember { Animatable(0f) }
      // 手势由 navigator.callback 驱动；这里只按（手势编号, 相位）播放跟手、停留与回弹。
      // 换键即取消上一段，旧手势的收尾既不会改写新手势的进度，也结束不了新手势（finish 校验编号）。
      val gesture = navigator.gesture
      // The real page a back gesture returns to, drawn under the outgoing one while the finger moves.
      val peekRoute = gesture?.peek?.takeIf { previewBack }
      val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
      SideEffect {
        navigator.screenWidth = screenWidthPx
        FrameStats.page = when {
          route.session == DRAFT_SESSION -> "新会话"
          route.session != null -> "会话"
          route.page == RootPage.DEVICES -> "设备管理"
          route.page == RootPage.SETTINGS -> "设置"
          else -> "首页"
        }
      }
      LaunchedEffect(gesture?.id, gesture?.phase) {
        val current = gesture ?: return@LaunchedEffect
        when (current.phase) {
          BackPhase.ACTIVE -> {
            backProgress.snapTo(0f)
            if (previewBack) snapshotFlow { navigator.progress }.collect { backProgress.snapTo(it) }
          }
          // 手势松手后以澎湃弹簧无缝走完剩余路程；非手势返回则等待弹出动画自然结束。
          BackPhase.COMMITTED -> {
            if (previewBack && backProgress.value > 0.01f) {
              backProgress.animateTo(1f, HyperMotion.backCommitSpring)
            } else {
              delay(260)
            }
            backProgress.snapTo(0f)
            navigator.finish(current.id)
          }
          BackPhase.CANCELLED -> {
            backProgress.animateTo(0f, HyperMotion.backCancelSpring)
            navigator.finish(current.id)
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
                target.session == DRAFT_SESSION -> DraftScreen(state, controller, onBack = { goBack() }, onModal = {}, interactive = active,
                  profileIds = draftScope, onSwitchServer = { profile -> switchDraftServer(profile) })
                 target.session != null -> ChatScreen(displayed, controller, onBack = { goBack() }, onOpenChild = { child ->
                   controller.state.value.serverId?.let { server -> openSession(SessionTarget(server, child), child = true) }
                 }, onModal = {}, interactive = active)
                 target.page == RootPage.DEVICES -> DeviceManagementScreen(
                   state = state, controller = controller, onBack = { goBack() },
                   onAddDevice = { addDevice() },
                   onEditGroup = { groupId -> editingGroupId = groupId; showingDeviceGroups = true },
                   onEditProfile = { profile -> editDevice(profile) },
                   onEditSshHost = { hostId -> addingSshHostId = hostId; addingOpenCodeId = null; showingAddDevice = true },
                   onLoginHub = { showingRemoteEnroll = true },
                   onManageHub = { showingRemoteHosts = true }
                 )
                 target.page == RootPage.SETTINGS -> SettingsScreen(state, controller, themeMode, { themeMode = it; preferences.edit().putString("themeMode", it.name).apply() }, previewBack, { previewBack = it; preferences.edit().putBoolean("previewBack", it).apply() }, refreshMode, { refreshMode = it; controller.setRefreshMode(it) }, {
                   if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                   else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                 }, onManageModels = { showingModelManagement = true },
                   onManageDevices = { openPage(RootPage.DEVICES) }, onAddDevice = { addDevice() }, onBack = { goBack() })
                 else -> HomeScreen(home, controller, onOpen = { openSession(it) }, onNewSession = { openDraft() }, onScope = { showingScope = true },
                    onManageDevices = { openPage(RootPage.DEVICES) },
                    onPage = ::openPage)
              }
            }
            peekRoute?.let { peek ->
              Box(Modifier.fillMaxSize().graphicsLayer {
                val p = backProgress.value.coerceIn(0f, 1f)
                translationX = -navigator.direction * (1f - p) * size.width * 0.28f
                val s = 0.94f + 0.06f * p
                scaleX = s; scaleY = s
              }.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } }) {
                Page(peek, active = false)
                Box(Modifier.fillMaxSize().graphicsLayer {
                  alpha = (1f - backProgress.value.coerceIn(0f, 1f)) * 0.22f
                }.background(androidx.compose.ui.graphics.Color.Black))
              }
            }
            AnimatedContent(route, transitionSpec = {
              // The first send turns the draft into the real session in place, without a page change.
              if (initialState.session == DRAFT_SESSION && targetState.depth == initialState.depth && targetState.session != DRAFT_SESSION) {
                EnterTransition.None togetherWith ExitTransition.None
              } else if (navigator.gesture?.phase == BackPhase.COMMITTED && previewBack && backProgress.value > 0.01f) {
                // 预测式返回松手后由 backProgress 弹簧连续驱动新旧两页，AnimatedContent 仅保持离场页存活至动画完成。
                (fadeIn(tween(1)) togetherWith fadeOut(tween(1, delayMillis = 260))).apply {
                  targetContentZIndex = 0f
                }
              } else {
                // 澎湃 OS 4 原生级推入/弹出：全幅弹簧位移 + 底层 -28% 视差与 0.94 缩放（底层不淡出变透）。
                val direction = slideDirection(initialState, targetState)
                if (direction > 0) {
                  (slideInHorizontally(HyperMotion.pageOffsetSpring) { it * direction } togetherWith
                    (slideOutHorizontally(HyperMotion.pageOffsetSpring) { -it * direction * 28 / 100 } +
                      scaleOut(HyperMotion.pageFloatSpring, targetScale = 0.94f))).apply {
                    targetContentZIndex = 2f
                  }
                } else {
                  ((slideInHorizontally(HyperMotion.pageOffsetSpring) { it * direction * 28 / 100 } +
                    scaleIn(HyperMotion.pageFloatSpring, initialScale = 0.94f)) togetherWith
                    (slideOutHorizontally(HyperMotion.pageOffsetSpring) { -it * direction } +
                      scaleOut(HyperMotion.pageFloatSpring, targetScale = 0.95f))).apply {
                    targetContentZIndex = 0f
                  }
                }
              }
            }, contentKey = { target ->
              // Content identity must match the saveable-scope identity. A draft is one page no
              // matter which server it is currently connected to; keying it by server made
              // AnimatedContent keep the old and new draft composed at once, which collides on
              // the shared "draft:<nonce>" saveable key.
              when {
                target.session == DRAFT_SESSION -> "draft:$draftNonce"
                target.session != null -> "chat:${target.serverId}:${target.session}"
                else -> "page:${target.page}"
              }
            }, label = "navigation") { target ->
              val active = target == route
              val currentGesture = navigator.gesture
              Box(Modifier.fillMaxSize().graphicsLayer {
                val p = backProgress.value.coerceIn(0f, 1f)
                if (target == currentGesture?.route && p > 0f) {
                  translationX = navigator.direction * p * size.width
                  val s = 1f - p.coerceAtMost(0.35f) * 0.14f
                  scaleX = s; scaleY = s
                  shadowElevation = 24.dp.toPx() * (1f - p * 0.5f)
                  shape = miuixSquircleShape((28f * p.coerceAtMost(0.4f) / 0.4f).dp)
                  clip = true
                } else if (target == route && currentGesture?.phase == BackPhase.COMMITTED && p > 0f) {
                  translationX = -navigator.direction * (1f - p) * size.width * 0.28f
                  val s = 0.94f + 0.06f * p
                  scaleX = s; scaleY = s
                }
              }.then(if (active) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } })
                .background(HyperTheme.colors.canvas)) {
                val key = when {
                  // The server is deliberately not part of this key: switching harness from the
                  // blank page must keep the composer text and its saveable state.
                  target.session == DRAFT_SESSION -> "draft:$draftNonce"
                  target.session != null -> "chat:${target.serverId}:${target.session}"
                  else -> "page:${if (state.allServers) "all" else state.homeServerId}:${target.page}"
                }
                holder.SaveableStateProvider(key) { Page(target, active) }
              }
            }
            // Per-page first-load states own their own placeholders. A global bar here meant any
            // background server's read made the whole app look busy.
            MiuixToastHost(globalMessage, globalMessageType, { globalMessage = null }, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp))
            // MIUIX popups must stay inside this Scaffold content's popup registry.
            if (showingServerForm) ServersModal(state, controller, state.profiles.firstOrNull { it.id == editingServerId },
              onDismiss = { showingServerForm = false; editingServerId = null },
              onRemoteHosts = {
                val add = editingServerId == null
                showingServerForm = false; editingServerId = null
                if (add) showingRemoteEnroll = true else showingRemoteHosts = true
              })
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
            if (showingAddDevice) AddDeviceSheet(
              state = state,
              controller = controller,
              existingOpenCode = state.profiles.firstOrNull { it.id == addingOpenCodeId },
              initialSshHostId = addingSshHostId,
              onDismiss = { showingAddDevice = false; addingOpenCodeId = null; addingSshHostId = null },
              onLoginHub = { showingAddDevice = false; addingOpenCodeId = null; addingSshHostId = null; showingRemoteEnroll = true },
              onManageHub = { showingAddDevice = false; addingOpenCodeId = null; addingSshHostId = null; showingRemoteHosts = true }
            )
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
    navigator.navigation.let { outState.putString(KEY_NAV_PAGE, it.page.name); outState.putStringArrayList(KEY_NAV_SESSIONS, ArrayList(it.sessions)) }
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
    const val KEY_NAV_SERVER = "navServer"
  }
}
