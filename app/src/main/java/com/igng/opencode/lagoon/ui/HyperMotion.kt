package com.igng.opencode.lagoon.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset

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
 * 澎湃 OS 4 实体按压阻尼反馈修饰符：
 * 按下时平滑微缩至 [scaleDown]，松手以弹性曲线恢复，可选长按震动反馈。
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.hyperPress(
  enabled: Boolean = true,
  scaleDown: Float = 0.968f,
  role: Role = Role.Button,
  onLongClick: (() -> Unit)? = null,
  onClick: () -> Unit
): Modifier = composed {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed && enabled) scaleDown else 1f,
    animationSpec = HyperMotion.pressSpring,
    label = "hyperPressScale"
  )
  val haptics = LocalHapticFeedback.current
  this
    .graphicsLayer {
      scaleX = scale
      scaleY = scale
    }
    .then(
      if (onLongClick != null) {
        Modifier.combinedClickable(
          interactionSource = interactionSource,
          indication = null,
          enabled = enabled,
          role = role,
          onClick = onClick,
          onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
          }
        )
      } else {
        Modifier.clickable(
          interactionSource = interactionSource,
          indication = null,
          enabled = enabled,
          role = role,
          onClick = onClick
        )
      }
    )
}
