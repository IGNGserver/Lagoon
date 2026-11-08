package com.igng.opencode.lagoon.ui

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.withFrameNanos

/**
 * 一次页面转场的视觉模式。它只描述"两页之间怎么动、谁在上面"，不含手势状态，
 * 因此可以在纯函数里验证（见 PageMotionTest）。
 */
internal enum class PageMotion {
  /** 前进：新页从右侧整幅推入，压在旧页上。 */
  FORWARD,

  /**
   * 非手势返回：实体返回键、三键导航、页面顶栏的返回按钮，以及设置里关掉预览后的侧滑。
   * 底页从 -28% 处以 0.94 展开，旧页整幅右移。
   */
  BACK,

  /**
   * 预测式返回：进度由手指驱动（松手后由弹簧补完），底页在 -28%/0.94 的底层跟着展开，
   * 旧页压在上面右移。整条转场只有一个进度源，所以松手那一帧不存在"手势轨 → 转场轨"的交接。
   */
  GESTURE
}

/** 没有手势时的转场模式：深度变小就是返回，其余都是前进。 */
internal fun pageMotion(from: NavRoute, to: NavRoute): PageMotion =
  if (slideDirection(from, to) > 0) PageMotion.FORWARD else PageMotion.BACK

/**
 * 两页里哪一页画在上面。
 *
 * 预测式返回时必须是**正在离开**的那一页：底页是垫在下面被揭开的，若让它压在上层，收起过程中
 * 会盖住旧页；其余情况都是目标页在上。层级由这里统一决定，不再依赖 AnimatedContent
 * "目标页最后画"的默认顺序——那正是旧实现松手时层级翻转、内容闪一帧的原因之一。
 */
internal fun frontRoute(motion: PageMotion, from: NavRoute, to: NavRoute): NavRoute =
  if (motion == PageMotion.GESTURE) from else to

/**
 * 把转场进度按弹簧推回 [target]（取消路径）。
 *
 * [SeekableTransitionState] 只能把进度朝 target 播，收不回来，所以这里自己走帧：每帧取一次弹簧值，
 * 再 seek 回同一个 target。弹簧作用在进度上、位移是进度的线性函数，得到的运动就是弹簧本身。
 */
internal suspend fun settleFraction(
  state: SeekableTransitionState<NavRoute>,
  target: Float,
  spec: SpringSpec<Float>
) {
  val from = state.fraction
  if (from == target) return
  val animation = TargetBasedAnimation(spec, Float.VectorConverter, from, target)
  val start = withFrameNanos { it }
  while (true) {
    val playTime = withFrameNanos { it } - start
    state.seekTo(animation.getValueFromNanos(playTime).coerceIn(0f, 1f))
    if (animation.isFinishedFromNanos(playTime)) return
  }
}
