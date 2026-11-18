package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.TaskPhase
import com.igng.opencode.lagoon.core.TaskState
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 澎湃 OS 4 连续曲率平滑圆角（HyperOS Continuous Squircle Shape）
 * 对任意浮点半径做 0.5dp 量化并限制缓存上限，防止手势插值半径无限堆积 Outline/Path。
 */
fun miuixSquircleShape(cornerRadius: Dp = 16.dp): Shape {
  val halfDpSteps = (cornerRadius.value * 2f + 0.5f).toInt().coerceAtLeast(0)
  squircles[halfDpSteps]?.let { return it }
  if (squircles.size >= MAX_SQUIRCLE_CACHE) squircles.clear()
  val quantized = (halfDpSteps * 0.5f).dp
  return squircles.computeIfAbsent(halfDpSteps) {
    RoundedRectangle(cornerRadius = quantized, style = RoundedCornerStyle.Continuous)
  }
}

/**
 * 澎湃 OS 4 半屏抽屉顶部连续曲率圆角（底部直角贴合屏幕边缘，消除底角漏黑边）
 */
fun miuixTopSquircleShape(cornerRadius: Dp = 28.dp): Shape {
  val halfDpSteps = (cornerRadius.value * 2f + 0.5f).toInt().coerceAtLeast(0)
  topSquircles[halfDpSteps]?.let { return it }
  if (topSquircles.size >= MAX_SQUIRCLE_CACHE) topSquircles.clear()
  val quantized = (halfDpSteps * 0.5f).dp
  return topSquircles.computeIfAbsent(halfDpSteps) {
    UnevenRoundedRectangle(
      topStart = quantized,
      topEnd = quantized,
      bottomEnd = 0.dp,
      bottomStart = 0.dp,
      style = RoundedCornerStyle.Continuous
    )
  }
}

private const val MAX_SQUIRCLE_CACHE = 128

/** One shape per 0.5dp radius step: bounded so continuous gesture fractions never leak memory. */
private val squircles = java.util.concurrent.ConcurrentHashMap<Int, Shape>()
private val topSquircles = java.util.concurrent.ConcurrentHashMap<Int, Shape>()

/**
 * 单次 Outline 缓存绘制修饰符：
 * 将原本 `.clip(shape).background(bg, shape).border(w, border, shape)` 的 3 次连续曲率贝塞尔 Path
 * 计算与硬件 Stencil Clip 图层收敛为单次 `drawWithCache` Outline 复用，仅在确实需要裁剪子内容时才开启 `clip`。
 */
internal fun Modifier.hyperSurface(
  shape: Shape,
  backgroundColor: Color,
  borderWidth: Dp = 0.dp,
  borderColor: Color = Color.Transparent,
  clipContent: Boolean = false
): Modifier = this
  .then(if (clipContent) Modifier.clip(shape) else Modifier)
  .drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val strokePx = if (borderWidth > 0.dp && borderColor.alpha > 0f) borderWidth.toPx() else 0f
    val stroke = if (strokePx > 0f) Stroke(width = strokePx) else null
    onDrawBehind {
      if (backgroundColor.alpha > 0f) {
        drawOutline(outline, color = backgroundColor)
      }
      if (stroke != null) {
        drawOutline(outline, color = borderColor, style = stroke)
      }
    }
  }

/**
 * 澎湃 OS 4 语义色彩系统：
 * 统一绑定至 [HyperTheme.colors] 四级表面色阶与高对比度语义色。
 */
object MiuixColorTokens {
  val Primary: Color @Composable get() = HyperTheme.colors.primary
  val PrimaryVariant: Color @Composable get() = HyperTheme.colors.primary
  val PrimarySubtle: Color @Composable get() = HyperTheme.colors.primarySubtle
  val Success: Color @Composable get() = HyperTheme.colors.success
  val Warning: Color @Composable get() = HyperTheme.colors.warning
  val Error: Color @Composable get() = HyperTheme.colors.error
  val Info: Color @Composable get() = HyperTheme.colors.primary
  val SuccessSubtle: Color @Composable get() = HyperTheme.colors.successSubtle
  val WarningSubtle: Color @Composable get() = HyperTheme.colors.warningSubtle
  val ErrorSubtle: Color @Composable get() = HyperTheme.colors.errorSubtle
  val NeutralSubtle: Color @Composable get() = HyperTheme.colors.container
  val NeutralSubtleDark: Color @Composable get() = HyperTheme.colors.containerHigh
}

/**
 * 澎湃 OS 4 风格状态胶囊 (State Badge / Pill)
 * 采用连续曲率胶囊形态、微描边与语义圆点
 */
@Composable
fun MiuixStatePill(
  phase: TaskPhase,
  detail: String? = null,
  modifier: Modifier = Modifier
) {
  val (color, subtleBg) = when (phase) {
    TaskPhase.COMPLETED -> MiuixColorTokens.Success to MiuixColorTokens.SuccessSubtle
    TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION -> MiuixColorTokens.Warning to MiuixColorTokens.WarningSubtle
    TaskPhase.FAILED, TaskPhase.DISCONNECTED -> MiuixColorTokens.Error to MiuixColorTokens.ErrorSubtle
    TaskPhase.ABORTED, TaskPhase.IDLE -> HyperTheme.colors.textSecondary to HyperTheme.colors.container
    else -> MiuixColorTokens.Primary to MiuixColorTokens.PrimarySubtle
  }

  // Running sub-phases (thinking / tool / subagent / testing) are one user-facing state.
  val label = if (phase in TaskState.RUNNING_PHASES) TaskState.RUNNING_DETAIL else detail ?: when (phase) {
    TaskPhase.IDLE -> "空闲"
    TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING -> TaskState.RUNNING_DETAIL
    TaskPhase.WAITING_PERMISSION -> "等待授权"
    TaskPhase.WAITING_QUESTION -> "等待回答"
    TaskPhase.WAITING_SERVER -> "等待服务器处理"
    TaskPhase.COMPLETED -> "已完成"
    TaskPhase.FAILED -> "失败"
    TaskPhase.ABORTED -> "已停止"
    TaskPhase.DISCONNECTED -> "已断开"
  }

  val pillShape = miuixSquircleShape(10.dp)
  Row(
    modifier = modifier
      .hyperSurface(pillShape, subtleBg, 0.5.dp, color.copy(alpha = 0.22f))
      .padding(horizontal = 9.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Box(
      Modifier
        .size(6.dp)
        .background(color, CircleShape)
    )
    Text(
      text = label,
      style = HyperTheme.tabular(
        MiuixTheme.textStyles.footnote2.copy(
          fontWeight = FontWeight.SemiBold,
          color = color
        )
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * 澎湃 OS 4 规范分节标题 (Section Title)
 */
@Composable
fun MiuixSectionHeader(
  title: String,
  count: Int? = null,
  action: String? = null,
  onAction: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 6.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      style = MiuixTheme.textStyles.headline2.copy(
        fontWeight = FontWeight.Bold,
        color = HyperTheme.colors.textPrimary
      ),
      modifier = Modifier.weight(1f)
    )
    if (count != null) {
      Text(
        text = "$count",
        style = HyperTheme.tabular(
          MiuixTheme.textStyles.footnote1.copy(
            color = HyperTheme.colors.textSecondary,
            fontWeight = FontWeight.SemiBold
          )
        ),
        modifier = Modifier.padding(end = 8.dp)
      )
    }
    if (action != null && onAction != null) {
      HyperTextAction(text = action, onClick = onAction)
    }
  }
}
