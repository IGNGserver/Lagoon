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
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.metrics.performance.FrameDataApi24
import androidx.metrics.performance.JankStats
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*

private data class PendingSshDeviceTrust(
  val deviceId: String,
  val expected: SshHostConfig,
  val key: PresentedSshHostKey,
  val refreshAfterTrust: Boolean = false
)

private data class PendingSshKeyUnlock(
  val deviceId: String,
  val key: SshKeyProfile
)

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
    val restoredParents = savedInstanceState?.getStringArrayList(KEY_NAV_PARENT_PAGES).orEmpty()
      .mapNotNull { runCatching { RootPage.valueOf(it) }.getOrNull() }
    navigator = RootNavigator(
      SessionNavigation(
        page = savedInstanceState?.getString(KEY_NAV_PAGE)?.let { runCatching { RootPage.valueOf(it) }.getOrNull() } ?: RootPage.HOME,
        sessions = savedInstanceState?.getStringArrayList(KEY_NAV_SESSIONS).orEmpty(),
        parentPages = restoredParents,
        device = savedInstanceState?.getString(KEY_NAV_DEVICE),
        harness = savedInstanceState?.getString(KEY_NAV_HARNESS),
        editors = decodeDeviceEditors(savedInstanceState?.getStringArrayList(KEY_NAV_EDITORS))
      ),
      if (savedInstanceState?.containsKey(KEY_NAV_SERVER) == true) savedInstanceState.getString(KEY_NAV_SERVER) else controller.state.value.serverId
    )
    onBackPressedDispatcher.addCallback(navigator.callback)
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) {
        controller.setSshInteractiveForeground(true)
        controller.onForeground()
      } else if (event == Lifecycle.Event.ON_STOP) {
        controller.setSshInteractiveForeground(false)
        controller.onBackground()
      }
    })
    val initialUiPrefs = getSharedPreferences("ui", MODE_PRIVATE)
    val initialRefreshPolicy = runCatching {
      HighRefreshPolicy.valueOf(initialUiPrefs.getString("highRefreshPolicy", HighRefreshPolicy.MAX.name)!!)
    }.getOrDefault(HighRefreshPolicy.MAX)
    applyHighRefreshPolicy(initialRefreshPolicy)
    val jank = JankStats.createAndTrack(window) { frame ->
      val cpuNanos = (frame as? FrameDataApi24)?.frameDurationCpuNanos ?: 0L
      FrameStats.record(maxOf(frame.frameDurationUiNanos, cpuNanos), frame.isJank)
    }
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) jank.isTrackingEnabled = true
      else if (event == Lifecycle.Event.ON_PAUSE) jank.isTrackingEnabled = false
    })

    setContent {
      val state by controller.state.collectAsState()
      val sshChallenges by controller.sshKeyboardChallenges.collectAsState()
      val draftScope = when {
        state.homeGroup != null -> state.homeGroup!!.memberHarnessIds(state.enabledProfiles).toSet()
        state.homeDevice != null -> state.homeDeviceProfiles.map { it.id }.toSet()
        else -> null
      }
      val scope = rememberCoroutineScope()
      val preferences = remember { getSharedPreferences("ui", MODE_PRIVATE) }
      var themeMode by remember { mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString("themeMode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)) }
      val systemDark = isSystemInDarkTheme()
      val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.SYSTEM && systemDark
      var previewBack by remember { mutableStateOf(preferences.getBoolean("previewBack", true)) }
      var highRefreshPolicy by remember { mutableStateOf(initialRefreshPolicy) }
      ObserveDisplayRefreshPolicy(this@MainActivity, highRefreshPolicy)
      var refreshMode by remember { mutableStateOf(controller.currentRefreshMode()) }

      var showingScope by rememberSaveable { mutableStateOf(false) }
      var modelManagementHarnessId by rememberSaveable { mutableStateOf<String?>(null) }
      var showingKeychain by rememberSaveable { mutableStateOf(false) }
      var keychainSelectedId by rememberSaveable { mutableStateOf<String?>(null) }
      var showingDeviceGroups by rememberSaveable { mutableStateOf(false) }
      var editingGroupId by rememberSaveable { mutableStateOf<String?>(null) }
      var draftNonce by rememberSaveable { mutableIntStateOf(0) }
      var globalMessage by remember { mutableStateOf<String?>(null) }
      var globalMessageType by remember { mutableStateOf(MiuixToastType.INFO) }
      var pendingSshTrust by remember { mutableStateOf<PendingSshDeviceTrust?>(null) }
      var pendingSshUnlock by remember { mutableStateOf<PendingSshKeyUnlock?>(null) }
      val deviceDiscoveryErrors = remember { mutableStateMapOf<String, String>() }
      val holder = rememberSaveableStateHolder()
      val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
      val focusManager = LocalFocusManager.current
      val density = LocalDensity.current
      val inChatDetail = navigator.navigation.sessions.isNotEmpty()
      val route = navigator.route

      fun openSession(target: SessionTarget, child: Boolean = false) {
        focusManager.clearFocus()
        navigator.server = target.serverId
        val lineage = controller.state.value.lineage(target.sessionId)
        navigator.navigate(
          if (lineage != null) navigator.navigation.openLineage(lineage)
          else navigator.navigation.open(target.sessionId, child)
        )
        controller.openConversation(target)
      }

      fun openEditor(target: DeviceEditorRoute) {
        focusManager.clearFocus()
        navigator.navigate(navigator.navigation.openEditor(target.freshInstance()))
      }

      fun addDevice() = openEditor(DeviceEditorRoute())
      fun openSshEditor(deviceId: String) = openEditor(DeviceEditorRoute(deviceId = deviceId))

      fun openDevice(deviceId: String) {
        focusManager.clearFocus()
        navigator.navigate(navigator.navigation.copy(page = RootPage.DEVICES).openDevice(deviceId))
      }

      fun refreshDevice(deviceId: String) {
        scope.launch {
          val expected = controller.device(deviceId)?.toHostConfig()
          try {
            deviceDiscoveryErrors.remove(deviceId)
            controller.refreshDeviceDiscovery(deviceId)
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (error: Exception) {
            val failure = error as? SshFailure
            if (expected != null && failure != null &&
              (failure.kind == SshFailureKind.HOST_KEY_UNKNOWN || failure.kind == SshFailureKind.HOST_KEY_CHANGED) &&
              controller.device(deviceId)?.toHostConfig() == expected
            ) {
              try {
                val key = failure.presentedKey ?: if (failure.kind == SshFailureKind.HOST_KEY_UNKNOWN &&
                  expected.hostKeyFingerprint.isNullOrBlank()
                ) controller.probeSshHostKey(expected) else null
                if (key != null && controller.device(deviceId)?.toHostConfig() == expected &&
                  navigator.navigation.device == deviceId && pendingSshTrust == null
                ) {
                  pendingSshTrust = PendingSshDeviceTrust(deviceId, expected, key, refreshAfterTrust = true)
                  return@launch
                }
              } catch (cancel: CancellationException) {
                throw cancel
              } catch (probe: Exception) {
                val message = userFacingError(probe, "读取主机指纹失败")
                deviceDiscoveryErrors[deviceId] = message
                globalMessage = message
                globalMessageType = MiuixToastType.ERROR
                return@launch
              }
            }
            val message = userFacingError(error, "发现失败")
            deviceDiscoveryErrors[deviceId] = message
            globalMessage = message
            globalMessageType = MiuixToastType.ERROR
          }
        }
      }

      /** 首页 / 聊天页 / 设备页内联 SSH 故障条的一键就地修复入口。 */
      fun handleSshAction(deviceId: String, failure: SshFailure) {
        val device = controller.device(deviceId)
        if (device == null) {
          openDevice(deviceId)
          return
        }
        when (failure.kind) {
          SshFailureKind.HOST_KEY_UNKNOWN, SshFailureKind.HOST_KEY_CHANGED -> {
            val expected = device.toHostConfig()
            val presented = failure.presentedKey
            if (presented != null) {
              pendingSshTrust = PendingSshDeviceTrust(deviceId, expected, presented, refreshAfterTrust = false)
            } else {
              scope.launch {
                try {
                  val probed = controller.probeSshHostKey(expected)
                  if (controller.device(deviceId)?.toHostConfig() == expected) {
                    pendingSshTrust = PendingSshDeviceTrust(deviceId, expected, probed, refreshAfterTrust = false)
                  }
                } catch (cancel: CancellationException) {
                  throw cancel
                } catch (error: Exception) {
                  globalMessage = userFacingError(error, "读取主机指纹失败")
                  globalMessageType = MiuixToastType.ERROR
                }
              }
            }
          }
          SshFailureKind.BIOMETRIC_REQUIRED, SshFailureKind.PASSPHRASE_REQUIRED -> {
            val key = controller.sshKey(device.keyId)
            if (key != null) pendingSshUnlock = PendingSshKeyUnlock(deviceId, key)
            else openDevice(deviceId)
          }
          else -> openDevice(deviceId)
        }
      }

      fun openHarness(leafId: String) {
        focusManager.clearFocus()
        navigator.navigate(navigator.navigation.openHarness(leafId))
      }

      fun openDeviceFromEditor(deviceId: String) {
        focusManager.clearFocus()
        navigator.navigate(
          navigator.navigation.copy(page = RootPage.DEVICES, editors = emptyList()).openDevice(deviceId)
        )
      }

      fun openDraftFor(profile: ServerProfile) {
        focusManager.clearFocus()
        navigator.server = profile.id
        controller.enterDraft(profile.id)
        draftNonce += 1
        navigator.navigate(navigator.navigation.copy(sessions = listOf(DRAFT_SESSION)))
      }

      fun switchDraftServer(profile: ServerProfile) {
        navigator.server = profile.id
        controller.enterDraft(profile.id, keepComposer = true)
      }

      fun openDraft() {
        focusManager.clearFocus()
        val target = state.defaultDraftServer(draftScope)
        if (target == null) {
          when {
            state.devices.isEmpty() -> addDevice()
            state.profiles.isEmpty() -> {
              globalMessage = "请先在设备页启用至少一个 Harness"
              globalMessageType = MiuixToastType.INFO
              navigator.navigate(navigator.navigation.push(RootPage.DEVICES))
            }
            draftScope != null -> {
              globalMessage = "当前范围内没有启用的 Harness"
              globalMessageType = MiuixToastType.INFO
            }
            else -> {
              globalMessage = "所有 Harness 均已停用，请先在设备管理中启用"
              globalMessageType = MiuixToastType.INFO
            }
          }
          return
        }
        openDraftFor(target.profile)
      }

      fun openPage(page: RootPage) {
        focusManager.clearFocus()
        navigator.navigate(navigator.navigation.push(page))
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
            val homeDevice = current.homeDevice
            when {
              group != null -> controller.selectGroup(group.id)
              homeDevice != null -> controller.selectHomeDevice(homeDevice.id)
              home != null && home != current.serverId -> controller.connect(home)
            }
          }
        }
      }

      LaunchedEffect(state.serverId) {
        if (navigator.server != state.serverId) {
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
          navigator.navigate(
            if (lineage != null) navigator.navigation.openLineage(lineage)
            else navigator.navigation.copy(
              sessions = if (state.session?.parentId == stack.last()) stack + id else stack.dropLast(1) + id
            )
          )
        }
      }

      SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
          isAppearanceLightStatusBars = !dark
          isAppearanceLightNavigationBars = !dark
        }
      }

      DisposableEffect(state.serverId, state.sessionId, inChatDetail) {
        val server = state.serverId
        val session = state.sessionId
        fun visible(value: Boolean) {
          if (server != null && session != null) controller.conversationVisible(server, session, value && inChatDetail)
        }
        val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) visible(true)
          else if (event == Lifecycle.Event.ON_PAUSE) visible(false)
        }
        lifecycle.addObserver(observer)
        visible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); visible(false) }
      }

      // 仅当还没有任何 SSH 设备时引导打开新建 SSH 设备页；保存设备后立即落入设备详情页（不再死循环）。
      LaunchedEffect(state.devices.isEmpty()) {
        if (state.devices.isEmpty() && navigator.navigation.editors.isEmpty()) {
          val editor = DeviceEditorRoute().freshInstance()
          navigator.navigate(SessionNavigation(page = RootPage.DEVICES, editors = listOf(editor)))
        }
      }

      LaunchedEffect(state.error) {
        state.error?.let {
          globalMessage = it
          globalMessageType = MiuixToastType.ERROR
          controller.clearError()
        }
      }

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
        if (profile == null) {
          globalMessage = "这个 Harness 已移除"
          globalMessageType = MiuixToastType.ERROR
          deepLink = null
        } else if (!profile.effectiveEnabled) {
          globalMessage = "这个连接已停用，请先在设备管理中启用"
          globalMessageType = MiuixToastType.ERROR
          deepLink = null
        } else {
          navigator.navigate(SessionNavigation())
          openSession(SessionTarget(server, session))
          deepLink = null
        }
      }

      val transitionState = remember { SeekableTransitionState(navigator.route) }
      val pageTransition = rememberTransition(transitionState, label = "page")
      var motion by remember { mutableStateOf(PageMotion.FORWARD) }
      val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
      SideEffect {
        navigator.screenWidth = screenWidthPx
        FrameStats.page = when {
          route.editors.isNotEmpty() -> "SSH 设备编辑器"
          route.session == DRAFT_SESSION -> "新会话"
          route.session != null -> "会话"
          route.harness != null -> "Harness 详情"
          route.device != null -> "设备详情"
          route.page == RootPage.DEVICES -> "设备管理"
          route.page == RootPage.SETTINGS -> "设置"
          else -> "首页"
        }
      }

      LaunchedEffect(navigator.route, navigator.gesture?.id) {
        val target = navigator.route
        val gesture = navigator.gesture
        if (gesture?.phase == BackPhase.ACTIVE) return@LaunchedEffect
        if (motion == PageMotion.GESTURE) {
          if (gesture != null) return@LaunchedEffect
          if (transitionState.targetState == target) {
            transitionState.animateTo(target, HyperMotion.backCommitSpring)
          } else {
            transitionState.snapTo(target)
          }
          motion = PageMotion.BACK
          return@LaunchedEffect
        }
        if (transitionState.targetState == target) return@LaunchedEffect
        motion = pageMotion(transitionState.currentState, target)
        transitionState.animateTo(target)
      }

      LaunchedEffect(navigator.gesture?.id, navigator.gesture?.phase) {
        val gesture = navigator.gesture ?: return@LaunchedEffect
        when (gesture.phase) {
          BackPhase.ACTIVE -> {
            val backTo = gesture.peek ?: return@LaunchedEffect
            if (transitionState.currentState != navigator.route) transitionState.snapTo(navigator.route)
            motion = if (previewBack) PageMotion.GESTURE else PageMotion.BACK
            if (!previewBack) return@LaunchedEffect
            transitionState.seekTo(0f, backTo)
            snapshotFlow { navigator.progress }.collect { transitionState.seekTo(it) }
          }
          BackPhase.COMMITTED -> {
            if (motion == PageMotion.GESTURE) {
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
          BackPhase.SUPERSEDED -> {
            transitionState.snapTo(navigator.route)
            motion = PageMotion.BACK
            navigator.finish(gesture.id)
          }
        }
      }

      LagoonMiuixTheme(dark) {
        Scaffold(
          contentWindowInsets = WindowInsets(0, 0, 0, 0),
          topBar = {},
          bottomBar = {}
        ) { _ ->
          Box(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
            @Composable
            fun Page(target: NavRoute, active: Boolean) {
              val liveDisplayed = when {
                target.session == DRAFT_SESSION -> state
                target.session != null -> controller.conversationState(state, target.serverId, target.session)
                else -> state
              }
              val frozenState = remember { arrayOfNulls<LagoonState>(1) }
              val displayed = if (!active && pageTransition.isRunning && frozenState[0] != null) {
                frozenState[0]!!
              } else {
                liveDisplayed.also { frozenState[0] = it }
              }
              val pageState = displayed
              when {
                target.editors.isNotEmpty() -> DeviceEditorScreen(
                  state = pageState,
                  controller = controller,
                  route = target.editors.last(),
                  onBack = { goBack() },
                  onSshDeviceSaved = { deviceId, discover ->
                    openDeviceFromEditor(deviceId)
                    if (discover) refreshDevice(deviceId)
                  }
                )
                target.session == DRAFT_SESSION -> DraftScreen(
                  pageState,
                  controller,
                  onBack = { goBack() },
                  onModal = {},
                  interactive = active,
                  profileIds = draftScope,
                  onSwitchServer = { profile -> switchDraftServer(profile) }
                )
                target.session != null -> ChatScreen(
                  state = displayed,
                  controller = controller,
                  onBack = { goBack() },
                  onOpenChild = { child ->
                    controller.state.value.serverId?.let { server -> openSession(SessionTarget(server, child), child = true) }
                  },
                  onModal = {},
                  interactive = active,
                  onOpenDevice = { id -> openDevice(id) },
                  onSshAction = ::handleSshAction
                )
                target.page == RootPage.DEVICES && target.harness != null -> HarnessDetailScreen(
                  state = pageState,
                  controller = controller,
                  leafId = target.harness,
                  onBack = { if (navigator.navigation.editors.isEmpty()) goBack() },
                  onManageModels = { harnessId -> modelManagementHarnessId = harnessId }
                )
                target.page == RootPage.DEVICES && target.device != null -> DeviceDetailScreen(
                  state = pageState,
                  controller = controller,
                  deviceId = target.device,
                  onBack = { if (navigator.navigation.editors.isEmpty()) goBack() },
                  onOpenHarness = { leafId -> openHarness(leafId) },
                  onAddHarness = { device, slot ->
                    try {
                      controller.attachHarness(device.id.value, slot.protocol, slot.locator)
                    } catch (cancel: CancellationException) {
                      throw cancel
                    } catch (error: Exception) {
                      globalMessage = userFacingError(error, "添加 Harness 失败")
                      globalMessageType = MiuixToastType.ERROR
                    }
                  },
                  onRefreshDiscovery = ::refreshDevice,
                  discoveryError = deviceDiscoveryErrors[target.device],
                  onEditSsh = { deviceId -> openSshEditor(deviceId) },
                  onOpenKeychain = { selected ->
                    keychainSelectedId = selected
                    showingKeychain = true
                  },
                  onSshAction = ::handleSshAction,
                  onDeleteDevice = { deviceId -> controller.deleteDevice(deviceId) }
                )
                target.page == RootPage.DEVICES -> DeviceManagementScreen(
                  state = pageState,
                  controller = controller,
                  onBack = { goBack() },
                  onAddDevice = { addDevice() },
                  onEditGroup = { groupId -> editingGroupId = groupId; showingDeviceGroups = true },
                  onOpenDevice = { id -> openDevice(id) },
                  onOpenKeychain = {
                    keychainSelectedId = null
                    showingKeychain = true
                  }
                )
                target.page == RootPage.SETTINGS -> SettingsScreen(
                  pageState,
                  controller,
                  themeMode,
                  { themeMode = it; preferences.edit().putString("themeMode", it.name).apply() },
                  previewBack,
                  { previewBack = it; preferences.edit().putBoolean("previewBack", it).apply() },
                  refreshMode,
                  { refreshMode = it; controller.setRefreshMode(it) },
                  {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                      startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }
                  },
                  onManageDevices = { openPage(RootPage.DEVICES) },
                  onBack = { goBack() },
                  onMessage = { msg, type -> globalMessage = msg; globalMessageType = type },
                  highRefreshPolicy = highRefreshPolicy,
                  onHighRefreshPolicy = {
                    highRefreshPolicy = it
                    preferences.edit().putString("highRefreshPolicy", it.name).apply()
                  }
                )
                else -> HomeScreen(
                  state = rememberHomePage(pageState),
                  controller = controller,
                  onOpen = { openSession(it) },
                  onNewSession = { openDraft() },
                  onScope = { showingScope = true },
                  onManageDevices = { openPage(RootPage.DEVICES) },
                  onAddDevice = { addDevice() },
                  onOpenDevice = { id -> openDevice(id) },
                  onSshAction = ::handleSshAction,
                  onPage = ::openPage
                )
              }
            }

            pageTransition.AnimatedContent(
              transitionSpec = {
                if (motion == PageMotion.GESTURE) {
                  ContentTransform(
                    targetContentEnter = slideInHorizontally(HyperMotion.gestureTrack()) { -it * navigator.direction * 28 / 100 } +
                      scaleIn(HyperMotion.gestureTrack(), initialScale = 0.94f),
                    initialContentExit = slideOutHorizontally(HyperMotion.gestureTrack()) { it * navigator.direction } +
                      scaleOut(HyperMotion.gestureTrack(), targetScale = 0.95f),
                    sizeTransform = null
                  )
                } else {
                  pageTransform(slideDirection(initialState, targetState))
                }
              },
              contentKey = { target ->
                // Content identity matches SaveableStateProvider key strictly, including Device vs Harness.
                when {
                  target.editors.isNotEmpty() -> "editor:${target.editors.size}:${target.editors.last().key}"
                  target.session == DRAFT_SESSION -> "draft:$draftNonce"
                  target.session != null -> "chat:${target.serverId}:${target.session}"
                  target.harness != null -> "harness:${target.device}:${target.harness}"
                  target.device != null -> "device:${target.device}"
                  else -> "page:${target.page}"
                }
              }
            ) { target ->
              val active = target == route
              val front = frontRoute(motion, transitionState.currentState, transitionState.targetState) == target
              val receding = motion == PageMotion.GESTURE && target == transitionState.currentState
              Box(
                Modifier.fillMaxSize()
                  .zIndex(if (front) 1f else 0f)
                  .graphicsLayer {
                    if (receding) {
                      val p = transitionState.fraction.coerceIn(0f, 1f)
                      shadowElevation = 24.dp.toPx() * (1f - p * 0.5f)
                      shape = gestureRoundedShape(p)
                      clip = true
                    }
                  }
                  .then(
                    if (active) Modifier.imePadding()
                    else Modifier.pointerInput(Unit) {
                      awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
                    }
                  )
                  .background(HyperTheme.colors.canvas)
              ) {
                val key = when {
                  target.editors.isNotEmpty() -> "editor:${target.editors.size}:${target.editors.last().key}"
                  target.harness != null -> "harness:${target.device}:${target.harness}"
                  target.device != null -> "device:${target.device}"
                  target.session == DRAFT_SESSION -> "draft:$draftNonce"
                  target.session != null -> "chat:${target.serverId}:${target.session}"
                  else -> "page:${if (state.allServers) "all" else state.homeDeviceId ?: state.homeServerId}:${target.page}"
                }
                holder.SaveableStateProvider(key) { Page(target, active) }
                if (motion == PageMotion.GESTURE && !front) {
                  Box(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawRect(Color.Black, alpha = (1f - transitionState.fraction.coerceIn(0f, 1f)) * 0.22f)
                  })
                }
              }
            }

            MiuixToastHost(
              globalMessage,
              globalMessageType,
              { globalMessage = null },
              Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 66.dp)
            )

            // MIUIX popups stay mounted once inside this Scaffold content's popup registry.
            if (showingScope) HomeScopeSheet(
              state = rememberHomePage(state),
              allSnapshots = state.allServerSnapshots,
              controller = controller,
              onDismiss = { showingScope = false },
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
            if (showingDeviceGroups) DeviceGroupsSheet(
              state = state,
              controller = controller,
              initialGroupId = editingGroupId,
              onDismiss = { showingDeviceGroups = false; editingGroupId = null }
            )
            modelManagementHarnessId?.let { targetHarnessId ->
              ModelManagementSheet(state, controller, targetServerId = targetHarnessId) {
                modelManagementHarnessId = null
              }
            }
            if (showingKeychain) SshKeychainManagerSheet(
              show = true,
              state = state,
              controller = controller,
              selectedKeyId = keychainSelectedId,
              onDismiss = { showingKeychain = false; keychainSelectedId = null }
            )
            pendingSshTrust?.let { pending ->
              SshHostKeyDialog(
                presented = pending.key,
                saved = pending.expected.hostKeyFingerprint?.takeIf(String::isNotBlank),
                savedType = pending.expected.hostKeyType,
                destination = pending.expected.address,
                onDismiss = {
                  if (pending.refreshAfterTrust) {
                    deviceDiscoveryErrors[pending.deviceId] = "尚未确认主机指纹，检测未完成"
                  }
                  pendingSshTrust = null
                },
                onAccept = {
                  pendingSshTrust = null
                  try {
                    if (pending.refreshAfterTrust) {
                      controller.confirmSshDeviceHostKey(pending.deviceId, pending.expected, pending.key)
                      refreshDevice(pending.deviceId)
                    } else {
                      controller.trustPresentedHostKeyAndRetry(pending.deviceId, pending.key)
                    }
                  } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    globalMessage = userFacingError(error, "保存主机指纹失败")
                    globalMessageType = MiuixToastType.ERROR
                  }
                }
              )
            }
            pendingSshUnlock?.let { pending ->
              SshKeyUnlockDialog(
                profile = pending.key,
                controller = controller,
                onDismiss = { pendingSshUnlock = null },
                onUnlocked = {
                  pendingSshUnlock = null
                  controller.retrySshDevice(pending.deviceId)
                }
              )
            }
            sshChallenges.firstOrNull()?.let { challenge ->
              SshKeyboardChallengeDialog(
                challenge,
                onCancel = { controller.answerSshKeyboardChallenge(challenge.id, null) },
                onSubmit = { answers -> controller.answerSshKeyboardChallenge(challenge.id, answers) }
              )
            }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    parseDeepLink(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    handledDeepLink?.let { outState.putString(KEY_HANDLED_DEEP_LINK, it) }
    navigator.navigation.let {
      outState.putString(KEY_NAV_PAGE, it.page.name)
      outState.putStringArrayList(KEY_NAV_SESSIONS, ArrayList(it.sessions))
      if (it.parentPages.isNotEmpty()) {
        outState.putStringArrayList(KEY_NAV_PARENT_PAGES, ArrayList(it.parentPages.map { p -> p.name }))
      }
      it.device?.let { id -> outState.putString(KEY_NAV_DEVICE, id) }
      it.harness?.let { id -> outState.putString(KEY_NAV_HARNESS, id) }
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
    const val KEY_NAV_PARENT_PAGES = "navParentPages"
    const val KEY_NAV_DEVICE = "navDevice"
    const val KEY_NAV_HARNESS = "navHarness"
    const val KEY_NAV_EDITORS = "navEditors"
    const val KEY_NAV_SERVER = "navServer"
  }
}

@Composable
private fun rememberHomePage(state: LagoonState): LagoonState = remember(state) { state.homeProjection() }

private val gestureRoundedShapes = Array(57) { step -> RoundedCornerShape((step * 0.5f).dp) }

private fun gestureRoundedShape(progress: Float): RoundedCornerShape {
  val step = ((28f * (progress.coerceIn(0f, 0.4f) / 0.4f)) * 2f + 0.5f).toInt().coerceIn(0, 56)
  return gestureRoundedShapes[step]
}
