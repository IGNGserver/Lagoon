package com.igng.opencode.lagoon.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
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
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
  private var deepLink by mutableStateOf<Pair<String, String>?>(null)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    parseDeepLink(intent)
    val controller = LagoonController.get(this)
    // Opening the app refreshes from the server; leaving it lets the live stream wind down.
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) controller.onForeground()
      else if (event == Lifecycle.Event.ON_STOP) controller.onBackground()
    })
    setContent {
      val state by controller.state.collectAsState()
      val preferences = remember { getSharedPreferences("ui", MODE_PRIVATE) }
      var themeMode by remember { mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString("themeMode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)) }
      val systemDark = isSystemInDarkTheme()
      val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.SYSTEM && systemDark
      var previewBack by remember { mutableStateOf(preferences.getBoolean("previewBack", true)) }
      var refreshMode by remember { mutableStateOf(controller.currentRefreshMode()) }
      var currentPage by rememberSaveable { mutableStateOf(RootPage.HOME) }
      var sessionStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
      var navigationServer by rememberSaveable { mutableStateOf(state.serverId) }
      // The server sheet edits one profile (null = add a new one); switching happens on the home capsules.
       var showingServerForm by remember { mutableStateOf(false) }
       var editingServerId by remember { mutableStateOf<String?>(null) }
       var showingProjects by remember { mutableStateOf(false) }
       var showingAllDraftTarget by remember { mutableStateOf(false) }
       var pendingDraftTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
       var showingModelManagement by remember { mutableStateOf(false) }
      // Each blank draft gets a fresh saveable scope so a sent draft never reappears.
      var draftNonce by rememberSaveable { mutableIntStateOf(0) }
      var chatModal by remember { mutableStateOf(false) }
      var globalMessage by remember { mutableStateOf<String?>(null) }
      var globalMessageType by remember { mutableStateOf(MiuixToastType.INFO) }
       val holder = rememberSaveableStateHolder()
        val snapshots = remember { mutableMapOf<String, LagoonState>() }
        var pendingOpen by remember { mutableStateOf<SessionTarget?>(null) }
       var restoreAllServersOnBack by rememberSaveable { mutableStateOf(false) }
       val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
      val focusManager = LocalFocusManager.current
      val density = LocalDensity.current
      val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
      val navigation = SessionNavigation(currentPage, sessionStack)
      val inChatDetail = sessionStack.isNotEmpty()
      val route = navigation.route
       fun openSessionLocal(id: String, child: Boolean = false) {
         focusManager.clearFocus()
         if (state.session != null) snapshots["${state.serverId}:${state.sessionId}"] = state
        val known = controller.state.value.sessions.firstOrNull { it.id == id }
        if (child || known?.parentId == null && known != null) {
          sessionStack = SessionNavigation(currentPage, sessionStack).open(id, child).sessions
          if (controller.state.value.sessionId != id) controller.selectSession(id)
        } else {
          val originPage = currentPage; val originStack = sessionStack
          controller.resolveSession(id) { lineage ->
            if (currentPage != originPage || sessionStack != originStack) return@resolveSession
            sessionStack = lineage
            if (controller.state.value.sessionId != id) controller.selectSession(id)
          }
         }
       }
        fun openSession(target: SessionTarget, child: Boolean = false) {
          if (state.allServers || state.serverId != target.serverId) {
            if (state.allServers) restoreAllServersOnBack = true
            pendingOpen = target
            controller.connect(target.serverId)
            return
         }
         openSessionLocal(target.sessionId, child)
       }
       fun openDraft() {
         focusManager.clearFocus()
         if (state.allServers) { showingAllDraftTarget = true; return }
         if (!state.connected) { globalMessage = if (state.profiles.isEmpty()) "先添加一个 OpenCode 服务器" else "服务器尚未连接，连接后才能新建会话"; globalMessageType = MiuixToastType.INFO; return }
        if (state.projects.isEmpty()) { globalMessage = "先添加一个项目"; globalMessageType = MiuixToastType.INFO; showingProjects = true; return }
        controller.beginDraft()
        draftNonce += 1
         sessionStack = listOf(DRAFT_SESSION)
       }
      fun openPage(page: RootPage) { focusManager.clearFocus(); val next = SessionNavigation(currentPage, sessionStack).push(page); currentPage = next.page; sessionStack = next.sessions }
      fun editServer(profile: ServerProfile?) { editingServerId = profile?.id; showingServerForm = true }
       fun goBack() {
         focusManager.clearFocus()
         val current = SessionNavigation(currentPage, sessionStack)
         val next = current.back()
         sessionStack = next.sessions; currentPage = next.page
         next.sessions.lastOrNull()?.let(controller::selectSession)
         if (restoreAllServersOnBack && leavesSession(current, next)) {
           restoreAllServersOnBack = false
           controller.setAllServers(true)
         }
       }
      LaunchedEffect(state.serverId) {
        if (navigationServer != state.serverId) { sessionStack = emptyList(); snapshots.clear(); navigationServer = state.serverId }
      }
       LaunchedEffect(state.sessionId) {
        val id = state.sessionId
         if (inChatDetail && id != null && sessionStack.lastOrNull() != id) {
           sessionStack = if (state.session?.parentId == sessionStack.lastOrNull()) sessionStack + id else sessionStack.dropLast(1) + id
         }
       }
       LaunchedEffect(state.allServers, state.serverId, state.connected, pendingOpen) {
         val target = pendingOpen
         if (target != null && !state.allServers && state.serverId == target.serverId && state.connected) {
           pendingOpen = null
           openSessionLocal(target.sessionId)
         }
       }
       LaunchedEffect(state.allServers, state.serverId, state.connected, pendingDraftTarget) {
         val target = pendingDraftTarget
         if (target != null && !state.allServers && state.serverId == target.first && state.connected) {
           pendingDraftTarget = null
           if (state.projects.any { it.id == target.second }) controller.selectProject(target.second)
           draftNonce += 1
           sessionStack = listOf(DRAFT_SESSION)
         }
        }
       SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        if (state.sessionId != null) {
          snapshots["${state.serverId}:${state.sessionId}"] = state
          if (snapshots.size > 8) snapshots.keys.firstOrNull { it != "${state.serverId}:${state.sessionId}" }?.let(snapshots::remove)
        }
      }
      DisposableEffect(state.serverId, state.sessionId, inChatDetail) {
        val server = state.serverId; val session = state.sessionId
        fun visible(value: Boolean) { if (server != null && session != null) controller.conversationVisible(server, session, value && inChatDetail) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) visible(true) else if (event == Lifecycle.Event.ON_PAUSE) visible(false) }
        lifecycle.addObserver(observer); visible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); visible(false) }
      }
      LaunchedEffect(state.profiles.isEmpty()) { if (state.profiles.isEmpty()) editServer(null) }
      LaunchedEffect(state.error) { state.error?.let { globalMessage = it; globalMessageType = MiuixToastType.ERROR; controller.clearError() } }
      LaunchedEffect(state.message) { state.message?.let { globalMessage = it; globalMessageType = MiuixToastType.SUCCESS; controller.clearMessage() } }
       LaunchedEffect(deepLink, state.serverId, state.connected, state.allServers) {
         val (server, session) = deepLink ?: return@LaunchedEffect
          if (state.profiles.none { it.id == server }) { globalMessage = "这个服务器已移除"; globalMessageType = MiuixToastType.ERROR; deepLink = null }
          else if (state.connected && !state.allServers && state.serverId == server) {
            currentPage = RootPage.HOME; openSession(SessionTarget(server, session)); deepLink = null
          } else if (state.allServers || state.serverId != server) {
            if (state.allServers) restoreAllServersOnBack = true
            controller.connect(server)
          }
       }
      val backProgress = remember { Animatable(0f) }
      var gestureActive by remember { mutableStateOf(false) }
      var gestureRoute by remember { mutableStateOf<NavRoute?>(null) }
      // 手势方向：页面朝手指来向移动（左边缘 +1 / 右边缘 -1）。
      var backDirection by remember { mutableStateOf(1) }
      // The real page a back gesture returns to, drawn under the outgoing one while the finger moves.
      var peekRoute by remember { mutableStateOf<NavRoute?>(null) }
      val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
      PredictiveBackHandler(enabled = navigation.canGoBack && !keyboardOpen && !chatModal && !showingServerForm && !showingProjects && !showingModelManagement) { progress ->
        gestureActive = true; gestureRoute = route
        peekRoute = navigation.back().route.takeIf { previewBack && it.session == null }
        try {
          progress.collect { event ->
            backDirection = backSwipeDirection(event.swipeEdge, event.touchX, screenWidthPx)
            if (previewBack) backProgress.snapTo(event.progress.coerceIn(0f, 1f))
          }
          peekRoute = null
          goBack()
          // Leave the outgoing surface at its gesture position until its exit transition finishes.
          delay(240); backProgress.snapTo(0f)
        } catch (cancel: CancellationException) {
          withContext(NonCancellable) { backProgress.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 500f)) }
          throw cancel
        } finally { gestureActive = false; peekRoute = null }
      }
      LagoonMiuixTheme(dark) {
        Scaffold(
          modifier = Modifier.imePadding(),
          contentWindowInsets = WindowInsets(0, 0, 0, 0),
          topBar = {},
          bottomBar = {}
        ) { insets ->
          Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
            @Composable
            fun Page(target: NavRoute, active: Boolean) {
              val displayed = if (target.session == state.sessionId) state else snapshots["${state.serverId}:${target.session}"] ?: state
              when {
                target.session == DRAFT_SESSION -> DraftScreen(state, controller, onBack = { goBack() }, onModal = { if (active) chatModal = it }, interactive = active)
                 target.session != null -> ChatScreen(displayed, controller, onBack = { goBack() }, onOpenChild = { child ->
                   controller.state.value.serverId?.let { server -> openSession(SessionTarget(server, child), child = controller.state.value.sessions.any { it.id == child }) }
                 }, onModal = { if (active) chatModal = it }, interactive = active)
                 target.page == RootPage.ARCHIVED -> ArchivedScreen(state, controller, onOpen = { openSession(it) }, onBack = { goBack() })
                 target.page == RootPage.SETTINGS -> SettingsScreen(state, controller, themeMode, { themeMode = it; preferences.edit().putString("themeMode", it.name).apply() }, previewBack, { previewBack = it; preferences.edit().putBoolean("previewBack", it).apply() }, refreshMode, { refreshMode = it; controller.setRefreshMode(it) }, {
                   if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                   else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                 }, ::editServer, onManageModels = { showingModelManagement = true }, onBack = { goBack() })
                 else -> HomeScreen(state, controller, onOpen = { openSession(it) }, onNewSession = { openDraft() }, onProjects = { showingProjects = true },
                    onSelectServer = { profile ->
                      restoreAllServersOnBack = false
                      if (profile == null) controller.setAllServers(true)
                      else if (profile.id != state.serverId || !state.connected || state.allServers) controller.connect(profile.id)
                    },
                   onEditServer = ::editServer, onPage = ::openPage)
              }
            }
            peekRoute?.let { peek ->
              Box(Modifier.fillMaxSize().graphicsLayer {
                if (gestureActive) translationX = -backDirection * (1f - backProgress.value) * size.width * 0.25f
              }.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } }) { Page(peek, active = false) }
            }
            AnimatedContent(route, transitionSpec = {
              // The first send turns the draft into the real session in place, without a page change.
              if (initialState.session == DRAFT_SESSION && targetState.depth == initialState.depth && targetState.session != DRAFT_SESSION) {
                EnterTransition.None togetherWith ExitTransition.None
              } else {
                // Deeper pages enter from the right, going back enters from the left.
                val direction = slideDirection(initialState, targetState)
                val motion = tween<IntOffset>(300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                (fadeIn(tween(220, delayMillis = 40)) + slideInHorizontally(motion) { it * direction / 4 }) togetherWith
                  (fadeOut(tween(160)) + slideOutHorizontally(motion) { -it * direction / 4 })
              }
            }, label = "navigation") { target ->
              val active = target == route
              Box(Modifier.fillMaxSize().graphicsLayer {
                if (target == gestureRoute && gestureActive) {
                  translationX = backDirection * backProgress.value * size.width * 0.28f
                  scaleX = 1f - backProgress.value * 0.04f; scaleY = scaleX
                  alpha = 1f - backProgress.value * 0.12f
                }
              }.then(if (active) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } })
                .background(MiuixTheme.colorScheme.surface)) {
                val key = when {
                  target.session == DRAFT_SESSION -> "draft:${state.serverId}:$draftNonce"
                  target.session != null -> "chat:${state.serverId}:${target.session}"
                  else -> "page:${state.serverId}:${target.page}"
                }
                holder.SaveableStateProvider(key) { Page(target, active) }
              }
            }
            if (state.loading || state.pendingOperations.any { ":open:" in it }) InfiniteProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).height(3.dp))
            MiuixToastHost(globalMessage, globalMessageType, { globalMessage = null }, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp))
            // MIUIX popups must stay inside this Scaffold content's popup registry.
            if (showingServerForm) ServersModal(state, controller, state.profiles.firstOrNull { it.id == editingServerId }) { showingServerForm = false; editingServerId = null }
             if (showingProjects) {
               if (state.allServers) AllServersScopeSheet(state, controller) { showingProjects = false }
               else ProjectScopeSheet(state, controller) { showingProjects = false }
             }
             if (showingAllDraftTarget) AllServersDraftTargetSheet(state, onSelect = { profile, project ->
                showingAllDraftTarget = false
                restoreAllServersOnBack = true
                pendingDraftTarget = profile.id to project.id
                controller.connect(profile.id, project.id)
             }, onDismiss = { showingAllDraftTarget = false })
            if (showingModelManagement) ModelManagementSheet(state, controller) { showingModelManagement = false }
          }
        }
      }
    }
  }
  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); parseDeepLink(intent) }
  private fun parseDeepLink(intent: Intent?) {
    val uri = intent?.data ?: return
    if ((uri.scheme != "lagoon" && uri.scheme != "opencode-lagoon") || uri.host != "server") return
    val parts = uri.pathSegments
    if (parts.size >= 3 && parts[1] == "session") deepLink = parts[0] to parts[2]
  }
}
