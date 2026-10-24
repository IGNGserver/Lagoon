package com.igng.opencode.lagoon.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 根返回手势的相位：跟手中 → 已提交（离场页停在手势位置等退场动画）或已取消（回弹）。 */
internal enum class BackPhase { ACTIVE, COMMITTED, CANCELLED }

/**
 * 一次根返回手势。[id] 单调递增：旧手势的收尾（提交后的停留、取消后的回弹）只能结束它自己，
 * 不能把紧接着开始的新手势一起清掉。[peek] 是手指移动时垫在下面的上一级页面。
 */
internal data class BackGesture(val id: Int, val route: NavRoute, val peek: NavRoute?, val phase: BackPhase = BackPhase.ACTIVE)

/**
 * Activity 持有的应用内返回栈与根返回回调。
 *
 * 系统在返回手势开始的那一刻决定交给谁：此刻窗口上没有已启用的回调就直接退出应用。所以 [callback]
 * 的启停必须和栈变化在同一调用里同步完成（[navigate]），不能等重组后再由 `enabled` 参数生效——
 * 点开会话的那几百毫秒里主线程可能还没来得及重组。
 *
 * [callback] 在 `setContent` 之前注册，优先级最低：键盘自己先消费返回，MIUIX 弹层、搜索框、活动浮层
 * 的 BackHandler 都注册得更晚、先于它生效；它只在 [SessionNavigation.canGoBack] 时启用。
 */
internal class RootNavigator(initial: SessionNavigation = SessionNavigation(), server: String? = null) {
  var navigation by mutableStateOf(initial); private set
  /** 当前会话链所属的服务器；回到会话列表后路由不再带服务器。 */
  var server by mutableStateOf(server)
  val route get() = navigation.route.copy(serverId = if (navigation.sessions.isNotEmpty()) server else null)

  var gesture by mutableStateOf<BackGesture?>(null); private set
  /** 手势进度与方向每帧都变，只应在绘制阶段（graphicsLayer）读取，避免整棵树随手指重组。 */
  var progress by mutableFloatStateOf(0f); private set
  var direction by mutableIntStateOf(1); private set
  var screenWidth = 0
  /** 一次返回落地后的界面副作用（清焦点、选中上一级会话、切回首页服务器），由界面层提供。 */
  var onBack: (from: SessionNavigation, to: SessionNavigation) -> Unit = { _, _ -> }
  private var gestures = 0

  val callback = object : OnBackPressedCallback(initial.canGoBack) {
    override fun handleOnBackStarted(backEvent: BackEventCompat) = start(backEvent.swipeEdge, backEvent.touchX)
    override fun handleOnBackProgressed(backEvent: BackEventCompat) = move(backEvent.progress, backEvent.swipeEdge, backEvent.touchX)
    override fun handleOnBackPressed() = commit()
    override fun handleOnBackCancelled() = cancel()
  }

  /** 唯一的栈写入口：栈与根回调的启停一起变，不留"栈已可返回、回调还没启用"的窗口。 */
  fun navigate(next: SessionNavigation) {
    navigation = next
    callback.isEnabled = next.canGoBack
  }

  fun goBack() {
    val from = navigation
    if (!from.canGoBack) return
    val to = from.back()
    navigate(to)
    onBack(from, to)
  }

  fun start(edge: Int, touchX: Float) {
    direction = backSwipeDirection(edge, touchX, screenWidth)
    progress = 0f
    gesture = BackGesture(++gestures, route, navigation.back().route.takeIf { it.session == null })
  }

  fun move(fraction: Float, edge: Int, touchX: Float) {
    if (gesture?.phase != BackPhase.ACTIVE) return
    direction = backSwipeDirection(edge, touchX, screenWidth)
    progress = fraction.coerceIn(0f, 1f)
  }

  /** 手势松手或非手势返回（三键导航、实体返回键）：先落栈，离场页由界面层停留片刻后 [finish]。 */
  fun commit() {
    // 非手势返回没有 start：顺手结束上一个手势残留的停留/回弹，不能让它挂在新页面上。
    gesture = gesture?.takeIf { it.phase == BackPhase.ACTIVE }?.copy(phase = BackPhase.COMMITTED, peek = null)
    goBack()
  }

  fun cancel() {
    gesture = gesture?.takeIf { it.phase == BackPhase.ACTIVE }?.copy(phase = BackPhase.CANCELLED)
  }

  /** 只结束编号为 [id] 的手势：它的收尾晚于新手势开始时什么都不做。 */
  fun finish(id: Int) {
    if (gesture?.id != id) return
    gesture = null
    progress = 0f
  }
}
