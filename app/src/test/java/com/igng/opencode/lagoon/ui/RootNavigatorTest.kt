package com.igng.opencode.lagoon.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 根返回回调与应用内返回栈同步启停：栈一变，系统在下一次手势开始时就能找到应用的回调。
 *
 * 用真实的 [OnBackPressedDispatcher] 驱动，fallback 即"交给系统返回 = 退出应用"；只有站在会话列表
 * 根上时才允许走到它。`hasEnabledCallbacks()` 就是 Activity 决定是否向窗口登记返回回调的依据。
 */
class RootNavigatorTest {
  private var systemBacks = 0
  private lateinit var dispatcher: OnBackPressedDispatcher
  private lateinit var navigator: RootNavigator
  private val selected = mutableListOf<String>()

  @Before fun setUp() {
    systemBacks = 0
    dispatcher = OnBackPressedDispatcher { systemBacks++ }
    navigator = RootNavigator().apply {
      screenWidth = 1080
      onBack = { _, to -> to.sessions.lastOrNull()?.let(selected::add) }
    }
    dispatcher.addCallback(navigator.callback)
  }

  private fun swipe(progress: Float = 0.6f, edge: Int = BackEventCompat.EDGE_LEFT) {
    dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 900f, 0f, edge))
    dispatcher.dispatchOnBackProgressed(BackEventCompat(400f, 900f, progress, edge))
  }
  private fun release() = dispatcher.onBackPressed()

  @Test fun openingASessionEnablesTheRootCallbackBeforeAnyRecomposition() {
    assertFalse("会话列表根上应交给系统", dispatcher.hasEnabledCallbacks())
    navigator.navigate(navigator.navigation.open("s1"))
    // 没有任何组合/重组参与：栈变化的同一调用里回调就已登记。
    assertTrue(navigator.callback.isEnabled)
    assertTrue(dispatcher.hasEnabledCallbacks())
  }

  @Test fun backImmediatelyAfterOpeningASessionReturnsToTheList() {
    navigator.navigate(navigator.navigation.open("s1"))
    swipe(); release()
    assertEquals(SessionNavigation(), navigator.navigation)
    assertEquals(0, systemBacks)
    assertFalse("回到会话列表后，下一次返回才交给系统", dispatcher.hasEnabledCallbacks())
  }

  @Test fun backWhileTheKeyboardIsCollapsingStillStaysInTheApp() {
    navigator.navigate(navigator.navigation.open("parent").open("child", child = true))
    // 第一次返回被 IME 窗口自己消费（收起键盘），不进入本 Activity 的分发器。
    // 键盘收起动画的约 300ms 里 ime insets 仍大于 0：根回调不再看键盘，照常启用。
    assertTrue(dispatcher.hasEnabledCallbacks())
    swipe(progress = 0.2f); release()
    assertEquals(listOf("parent"), navigator.navigation.sessions)
    assertEquals(listOf("parent"), selected)
    assertEquals(0, systemBacks)
  }

  @Test fun twoBacksInQuickSuccessionEachPopOneLevel() {
    navigator.navigate(SessionNavigation(RootPage.DEVICES).open("parent").open("child", child = true))
    swipe(); release()
    val first = navigator.gesture!!
    assertEquals(BackPhase.COMMITTED, first.phase)
    // 第二次手势在第一次的离场停留（240ms）结束前开始。
    swipe(progress = 0.3f)
    val second = navigator.gesture!!
    assertTrue(second.id > first.id)
    assertEquals(BackPhase.ACTIVE, second.phase)
    assertEquals(0.3f, navigator.progress)
    // 第一次手势迟到的收尾不能清掉第二次手势，也不能把它的进度归零。
    navigator.finish(first.id)
    assertEquals(second, navigator.gesture)
    assertEquals(0.3f, navigator.progress)
    release()
    assertEquals(SessionNavigation(RootPage.DEVICES), navigator.navigation)
    swipe(); release()
    assertEquals(SessionNavigation(), navigator.navigation)
    assertEquals(0, systemBacks)
    // 只有到了会话列表根上，下一次返回才退出应用。
    release()
    assertEquals(1, systemBacks)
  }

  @Test fun aNewGestureDuringTheCancelSpringBackIsNotOverwritten() {
    navigator.navigate(navigator.navigation.open("s1"))
    swipe(progress = 0.5f)
    dispatcher.dispatchOnBackCancelled()
    val cancelled = navigator.gesture!!
    assertEquals(BackPhase.CANCELLED, cancelled.phase)
    assertEquals(listOf("s1"), navigator.navigation.sessions)
    swipe(progress = 0.4f)
    navigator.finish(cancelled.id)
    assertEquals(BackPhase.ACTIVE, navigator.gesture?.phase)
    assertEquals(0.4f, navigator.progress)
    release()
    assertEquals(SessionNavigation(), navigator.navigation)
    navigator.finish(navigator.gesture!!.id)
    assertNull(navigator.gesture)
    assertEquals(0f, navigator.progress)
  }

  @Test fun laterRegisteredOverlaysAndPageHandlersTakePrecedence() {
    navigator.navigate(navigator.navigation.open("s1"))
    var overlayBacks = 0
    // 代表 MIUIX 弹层 / 搜索框 / 活动浮层的 BackHandler：它们在 setContent 之后才注册。
    val overlay = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { overlayBacks++ } }
    dispatcher.addCallback(overlay)
    release()
    assertEquals(1, overlayBacks)
    assertEquals(listOf("s1"), navigator.navigation.sessions)
    overlay.isEnabled = false
    release()
    assertEquals(SessionNavigation(), navigator.navigation)
    assertEquals(0, systemBacks)
  }

  @Test fun everyStackWriteKeepsTheCallbackInStep() {
    navigator.navigate(navigator.navigation.push(RootPage.SETTINGS))
    assertTrue(dispatcher.hasEnabledCallbacks())
    navigator.navigate(navigator.navigation.copy(sessions = listOf(DRAFT_SESSION)))
    assertTrue(dispatcher.hasEnabledCallbacks())
    navigator.navigate(SessionNavigation())
    assertFalse(dispatcher.hasEnabledCallbacks())
    // 根上的直接返回调用（例如页面顶栏）不落栈也不改回调。
    navigator.goBack()
    assertEquals(SessionNavigation(), navigator.navigation)
    assertTrue(selected.isEmpty())
  }

  @Test fun theBlankDraftRouteDoesNotChangeWhenTheServerChanges() {
    navigator.server = "srv-a"
    navigator.navigate(navigator.navigation.copy(sessions = listOf(DRAFT_SESSION)))
    val draftRoute = navigator.route
    assertEquals(NavRoute(RootPage.HOME, DRAFT_SESSION, 1, null), draftRoute)

    // Switching the harness from the blank page must not make AnimatedContent treat the draft as
    // a new page: both old and new draft would otherwise register the same saveable key.
    navigator.server = "srv-b"
    assertEquals(draftRoute, navigator.route)

    // Real conversations still carry their server so snapshots/back gestures stay server-scoped.
    navigator.navigate(navigator.navigation.open("session"))
    assertEquals("srv-b", navigator.route.serverId)
  }

  @Test fun peekShowsTheOriginatingPageOnlyWhenLeavingTheOutermostSession() {
    navigator.server = "srv"
    navigator.navigate(SessionNavigation(RootPage.DEVICES).open("parent"))
    swipe()
    assertEquals(NavRoute(RootPage.DEVICES, "parent", 2, "srv"), navigator.gesture?.route)
    assertEquals(SessionNavigation(RootPage.DEVICES).route, navigator.gesture?.peek)
    dispatcher.dispatchOnBackCancelled()
    navigator.navigate(navigator.navigation.open("child", child = true))
    swipe()
    // New navigation logic includes parentPages in depth calculation.
    assertEquals(NavRoute(RootPage.DEVICES, "child", 3, "srv"), navigator.gesture?.route)
    release()
    assertNull("提交后底层预览立即撤掉", navigator.gesture?.peek)
  }

  /**
   * 设备详情与编辑器现在是路由上的一层，所以侧滑返回时垫在下面的就是真实的上一级
   * （详情 → 列表、编辑器 → 详情），而不是一张空白的“设备”页。
   */
  @Test fun theBackGesturePreviewsTheRealPageUnderneathADevicePage() {
    navigator.navigate(SessionNavigation(RootPage.DEVICES).openDevice("dev-1"))
    swipe()
    assertEquals("dev-1", navigator.gesture?.route?.device)
    assertEquals(2, navigator.gesture?.route?.depth)
    assertEquals(SessionNavigation(RootPage.DEVICES).route, navigator.gesture?.peek)
    release()
    assertEquals(SessionNavigation(RootPage.DEVICES), navigator.navigation)

    navigator.navigate(
      SessionNavigation(RootPage.DEVICES)
        .openDevice("dev-1")
        .openEditor(DeviceEditorRoute(profileId = "dev-1"))
    )
    swipe()
    assertEquals("编辑器之下是设备详情", "dev-1", navigator.gesture?.peek?.device)
    assertEquals("编辑器本身不在预览里", emptyList<DeviceEditorRoute>(), navigator.gesture?.peek?.editors)
    release()
    assertEquals("返回一层只退掉编辑器，落回设备详情", emptyList<DeviceEditorRoute>(), navigator.navigation.editors)
    assertEquals("dev-1", navigator.navigation.device)
    assertEquals(0, systemBacks)
  }
}
