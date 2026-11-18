package com.igng.opencode.lagoon.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 澎湃 OS 4（HyperOS 4）物理弹簧动效与触感反馈引擎：
 * - 页面推入/弹出采用临界阻尼弹簧（dampingRatio = 0.94f, stiffness = 380f），干脆厚重；
 * - 预测式返回松手后连续衔接弹簧收尾（dampingRatio = 0.92f, stiffness = 460f），消除硬切跳帧；
 * - 控件按压采用物理缩放阻尼（scaleDown = 0.965f），带来扎实触感。
 */
internal object HyperMotion {
  /** 页面前进推入与返回弹出弹簧（通用泛型） */
  fun <T> pageSpring(): SpringSpec<T> = spring(dampingRatio = 0.94f, stiffness = 380f)

  /** 页面位移弹簧（IntOffset 专用） */
  val pageOffsetSpring: SpringSpec<IntOffset> = spring(dampingRatio = 0.94f, stiffness = 380f)

  /** 页面缩放与遮罩弹簧（Float 专用） */
  val pageFloatSpring: SpringSpec<Float> = spring(dampingRatio = 0.94f, stiffness = 380f)

  /** 预测式返回松手提交弹簧（无缝走完剩余位移） */
  val backCommitSpring: SpringSpec<Float> = spring(dampingRatio = 0.92f, stiffness = 460f)

  /** 预测式返回取消回弹弹簧 */
  val backCancelSpring: SpringSpec<Float> = spring(dampingRatio = 0.88f, stiffness = 520f)

  /**
   * 预测式返回的跟手段：进入/退出动画全部用**等长线性** tween。
   *
   * SeekableTransitionState 的姿态是"进度 × 这条曲线"，只有线性才能让页面和手指 1:1；
   * 松手后的澎湃手感由作用在进度上的弹簧（[backCommitSpring] / [backCancelSpring]）给，
   * 两者相乘仍是弹簧曲线。时长只需彼此一致（决定了转场总时长），不参与观感。
   */
  const val gestureTrackMillis: Int = 320

  /** 跟手段动画规格（IntOffset / Float 共用，泛型由调用处的参数类型推断）。 */
  fun <T> gestureTrack(): TweenSpec<T> =
    tween(durationMillis = gestureTrackMillis, easing = LinearEasing)

  /** 半屏抽屉与浮层弹出弹簧（IntOffset 专用） */
  val sheetOffsetSpring: SpringSpec<IntOffset> = spring(dampingRatio = 0.88f, stiffness = 420f)

  /** 半屏抽屉与浮层透明度弹簧 */
  val sheetFloatSpring: SpringSpec<Float> = spring(dampingRatio = 0.92f, stiffness = 460f)

  /** 控件按压回弹弹簧 */
  val pressSpring: SpringSpec<Float> = spring(dampingRatio = 0.76f, stiffness = 640f)

  /** 开关与胶囊指示器滑动弹簧 */
  fun <T> controlSpring(): SpringSpec<T> = spring(dampingRatio = 0.80f, stiffness = 540f)
}

/**
 * 整页推入 / 弹出的唯一动效规格（澎湃 OS 4 原生级）：
 * 新页从右侧整幅推入，旧页同时向反方向退 28% 并缩到 0.94（返回时方向相反、底层不淡出变透）；
 * 顶层页始终压住底层，避免两页在半透明交界处互相透出。
 *
 * 所有整页都是 [NavRoute] 上的一层，所以首页进设备、设备进详情、详情进编辑器、添加入口进 SSH
 * 表单这四级共用这一份规格——不要另写一份“相似但不相同”的弹簧。
 */
internal fun AnimatedContentTransitionScope<NavRoute>.pageTransform(direction: Int): ContentTransform {
  val spring = HyperMotion.pageOffsetSpring
  // 两页都是满屏：尺寸动画只会往转场里多塞一条时间轴（根转场的进度按总时长归一化），一律关掉。
  return if (direction > 0) {
    ContentTransform(
      targetContentEnter = slideInHorizontally(spring) { it * direction },
      initialContentExit = slideOutHorizontally(spring) { -it * direction * 28 / 100 } +
        scaleOut(HyperMotion.pageFloatSpring, targetScale = 0.94f),
      targetContentZIndex = 2f,
      sizeTransform = null
    )
  } else {
    ContentTransform(
      targetContentEnter = slideInHorizontally(spring) { it * direction * 28 / 100 } +
        scaleIn(HyperMotion.pageFloatSpring, initialScale = 0.94f),
      initialContentExit = slideOutHorizontally(spring) { -it * direction } +
        scaleOut(HyperMotion.pageFloatSpring, targetScale = 0.95f),
      targetContentZIndex = 0f,
      sizeTransform = null
    )
  }
}

private data class HyperPressIndication(
  val scaleDown: Float,
  val surfaceHighlight: Boolean
) : IndicationNodeFactory {
  override fun create(interactionSource: InteractionSource): DelegatableNode =
    HyperPressIndicationNode(interactionSource, scaleDown, surfaceHighlight)
}

private val DefaultRowPressIndication = HyperPressIndication(scaleDown = 0.988f, surfaceHighlight = true)
private val DefaultButtonPressIndication = HyperPressIndication(scaleDown = 0.96f, surfaceHighlight = false)
private val DefaultIconPressIndication = HyperPressIndication(scaleDown = 0.94f, surfaceHighlight = false)

private class HyperPressIndicationNode(
  private val interactionSource: InteractionSource,
  private val scaleDown: Float,
  private val surfaceHighlight: Boolean
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
  private var scaleAnim: Animatable<Float, *>? = null
  private var highlightAnim: Animatable<Float, *>? = null
  private var animJob: Job? = null

  override fun onAttach() {
    coroutineScope.launch {
      var pressCount = 0
      interactionSource.interactions.collect { interaction ->
        when (interaction) {
          is PressInteraction.Press -> pressCount++
          is PressInteraction.Release, is PressInteraction.Cancel ->
            pressCount = (pressCount - 1).coerceAtLeast(0)
        }
        animateToState(pressCount > 0)
      }
    }
  }

  private fun animateToState(pressed: Boolean) {
    val targetScale = if (pressed) scaleDown else 1f
    val targetAlpha = if (pressed && surfaceHighlight) 1f else 0f
    val curScale = scaleAnim?.value ?: 1f
    val curAlpha = highlightAnim?.value ?: 0f
    if (abs(curScale - targetScale) < 0.0005f && abs(curAlpha - targetAlpha) < 0.005f) return
    val sAnim = scaleAnim ?: Animatable(1f).also { scaleAnim = it }
    val hAnim = if (surfaceHighlight) (highlightAnim ?: Animatable(0f).also { highlightAnim = it }) else null
    animJob?.cancel()
    animJob = coroutineScope.launch {
      launch {
        sAnim.animateTo(targetScale, HyperMotion.pressSpring) { invalidateDraw() }
      }
      if (hAnim != null) {
        launch {
          hAnim.animateTo(targetAlpha, HyperMotion.pressSpring) { invalidateDraw() }
        }
      }
    }
  }

  override fun ContentDrawScope.draw() {
    val s = scaleAnim?.value ?: 1f
    val alpha = highlightAnim?.value ?: 0f
    val showHighlight = surfaceHighlight && alpha > 0.005f
    if (abs(s - 1f) > 0.0005f) {
      drawContext.canvas.save()
      val px = size.width * 0.5f
      val py = size.height * 0.5f
      drawContext.canvas.translate(px, py)
      drawContext.canvas.scale(s, s)
      drawContext.canvas.translate(-px, -py)
      if (showHighlight) {
        val pressedColor = currentValueOf(LocalHyperColors).cardPressed
        drawRect(pressedColor.copy(alpha = alpha))
      }
      drawContent()
      drawContext.canvas.restore()
    } else {
      if (showHighlight) {
        val pressedColor = currentValueOf(LocalHyperColors).cardPressed
        drawRect(pressedColor.copy(alpha = alpha))
      }
      drawContent()
    }
  }
}

/**
 * 澎湃 OS 4 分级实体按压阻尼反馈修饰符（基于 [IndicationNodeFactory] + [Modifier.Node]）：
 * - 零 `Modifier.composed` 子组合开销；传入 `interactionSource = null` 使列表静止与高速滑动期间零对象与零协程分配，
 *   仅在用户手指真正触碰该行时按需懒创建交互源与弹簧动画器；
 * - 静止态零 `graphicsLayer` 离屏层开销，按压缩放与表面微高亮在单次 [DrawModifierNode] 绘制通道内完成。
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.hyperPress(
  enabled: Boolean = true,
  scaleDown: Float = 0.96f,
  surfaceHighlight: Boolean = false,
  role: Role = Role.Button,
  onLongClick: (() -> Unit)? = null,
  onClick: () -> Unit
): Modifier {
  val effectiveScaleDown = if (surfaceHighlight && scaleDown == 0.96f) 0.988f else scaleDown
  val indication = when {
    effectiveScaleDown == 0.988f && surfaceHighlight -> DefaultRowPressIndication
    effectiveScaleDown == 0.96f && !surfaceHighlight -> DefaultButtonPressIndication
    effectiveScaleDown == 0.94f && !surfaceHighlight -> DefaultIconPressIndication
    else -> HyperPressIndication(effectiveScaleDown, surfaceHighlight)
  }
  return if (onLongClick != null) {
    this.combinedClickable(
      interactionSource = null,
      indication = indication,
      enabled = enabled,
      role = role,
      onClick = onClick,
      onLongClick = onLongClick
    )
  } else {
    this.clickable(
      interactionSource = null,
      indication = indication,
      enabled = enabled,
      role = role,
      onClick = onClick
    )
  }
}
