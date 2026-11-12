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

  /** 界面在退场/回弹动画播完后会调用 [RootNavigator.finish]；单测里手动模拟这一拍。 */
  private fun settle() { navigator.gesture?.let { navigator.finish(it.id) } }

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
    // 离场动画还没收尾：第二次侧滑仍由应用接住，不能落到系统退出。
    assertTrue(dispatcher.hasEnabledCallbacks())
    settle()
    assertFalse("收尾结束、回到会话列表根上后，下一次返回才交给系统", dispatcher.hasEnabledCallbacks())
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
    // 退场动画播完前回调保持启用：第三次松手落在收尾里，不会交给系统退出。
    settle()
    // 只有到了会话列表根上、且收尾结束，下一次返回才退出应用。
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
        .openEditor(DeviceEditorRoute(mode = DeviceEditorMode.DIRECT_PROFILE, profileId = "dev-1"))
    )
    swipe()
    assertEquals("编辑器之下是设备详情", "dev-1", navigator.gesture?.peek?.device)
    assertEquals("编辑器本身不在预览里", emptyList<DeviceEditorRoute>(), navigator.gesture?.peek?.editors)
    release()
    assertEquals("返回一层只退掉编辑器，落回设备详情", emptyList<DeviceEditorRoute>(), navigator.navigation.editors)
    assertEquals("dev-1", navigator.navigation.device)
    assertEquals(0, systemBacks)
  }

  /*
   * 下面三条守的是"界面能沿着同一个姿态把转场播完"的前提：手势的进度和相位只在 finish 时才清。
   * 界面用 SeekableTransitionState 驱动两页，松手那一刻读的就是这里的 progress；提前清零（旧实现
   * 在 commit 里 snapTo(0f)）会让旧页瞬间回到手势前的姿态，也就是用户看到的闪回。
   */

  @Test fun aCommittedGestureKeepsItsProgressUntilTheExitAnimationFinishes() {
    navigator.navigate(navigator.navigation.open("s1"))
    swipe(progress = 0.55f)
    val gesture = navigator.gesture!!
    release()
    assertEquals("栈先落到底，界面据此决定转场目标", SessionNavigation(), navigator.navigation)
    assertEquals(BackPhase.COMMITTED, navigator.gesture?.phase)
    assertEquals("离场动画结束前进度不能被清掉", 0.55f, navigator.progress)
    navigator.finish(gesture.id)
    assertNull(navigator.gesture)
    assertEquals(0f, navigator.progress)
  }

  @Test fun aCancelledGestureKeepsItsProgressForTheSpringBack() {
    navigator.navigate(navigator.navigation.open("s1"))
    swipe(progress = 0.4f)
    val gesture = navigator.gesture!!
    dispatcher.dispatchOnBackCancelled()
    assertEquals(BackPhase.CANCELLED, navigator.gesture?.phase)
    assertEquals("回弹动画结束前进度不能被清掉", 0.4f, navigator.progress)
    assertEquals("取消不落栈", listOf("s1"), navigator.navigation.sessions)
    navigator.finish(gesture.id)
    assertEquals(0f, navigator.progress)
  }

  @Test fun theGestureTargetIsWhereTheStackLandsOnCommit() {
    navigator.navigate(SessionNavigation(RootPage.DEVICES).open("s1"))
    swipe(progress = 0.3f)
    // 跟手时界面 seek 到这个目标；松手后直接把同一个转场补完到 navigator.route，两者必须同一个值。
    val target = navigator.gesture?.peek
    release()
    assertEquals(target, navigator.route)
  }

  /*
   * 下面两条守的是"栈在手势进行中被改写"的竞态：切服务器/切会话的自动同步会在这时 navigate。
   * 旧实现只改栈、不改手势，回调被同步禁用后系统会撤掉窗口回调；这次手势的 terminal 事件因此
   * 丢失，motion 永久停在 GESTURE，页面冻住、下一次侧滑直接退出应用。
   */

  @Test fun aStackRewriteMidGestureSupersedesThePreviewWithoutDisablingTheCallback() {
    navigator.navigate(navigator.navigation.open("s1"))
    swipe(progress = 0.4f)
    // 跟手期间自动同步把栈改写回根。
    navigator.navigate(SessionNavigation())
    assertEquals(BackPhase.SUPERSEDED, navigator.gesture?.phase)
    assertTrue("手势还没收尾，回调不能中途禁用", dispatcher.hasEnabledCallbacks())
    assertEquals(SessionNavigation(), navigator.navigation)
  }

  @Test fun aSupersededGestureCannotPopOnRelease() {
    navigator.navigate(navigator.navigation.open("s1"))
    swipe(progress = 0.4f)
    navigator.navigate(SessionNavigation())
    release()
    assertEquals("作废手势的松手不能再退一级", SessionNavigation(), navigator.navigation)
    settle()
    assertFalse("收尾结束后回到根上，下一次返回才交给系统", dispatcher.hasEnabledCallbacks())
    assertEquals(0, systemBacks)
  }

  @Test fun aPlainBackDuringTheExitSettleEndsTheOldGestureAndPopsOnce() {
    navigator.navigate(SessionNavigation().open("parent").open("child", child = true))
    swipe(); release()
    assertEquals(listOf("parent"), navigator.navigation.sessions)
    // 三键/实体键在离场动画里再按一次：结束旧收尾，再正常落栈。
    navigator.commit()
    assertEquals(SessionNavigation(), navigator.navigation)
    assertNull(navigator.gesture)
    assertEquals(0f, navigator.progress)
    assertEquals(0, systemBacks)
  }

  @Test fun aLateCancelCannotUndoACommittedRelease() {
    navigator.navigate(navigator.navigation.open("s1"))
    swipe(); release()
    navigator.cancel()
    assertEquals(BackPhase.COMMITTED, navigator.gesture?.phase)
    assertEquals(SessionNavigation(), navigator.navigation)
    assertEquals(0, systemBacks)
  }
}
