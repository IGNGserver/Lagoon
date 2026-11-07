package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
 */
fun miuixSquircleShape(cornerRadius: Dp = 16.dp): Shape = squircles.getOrPut(cornerRadius) {
  RoundedRectangle(cornerRadius = cornerRadius, style = RoundedCornerStyle.Continuous)
}

/**
 * 澎湃 OS 4 半屏抽屉顶部连续曲率圆角（底部直角贴合屏幕边缘，消除底角漏黑边）
 */
fun miuixTopSquircleShape(cornerRadius: Dp = 28.dp): Shape = topSquircles.getOrPut(cornerRadius) {
  UnevenRoundedRectangle(
    topStart = cornerRadius,
    topEnd = cornerRadius,
    bottomEnd = 0.dp,
    bottomStart = 0.dp,
    style = RoundedCornerStyle.Continuous
  )
}

/** One shape per radius: a new instance on every call made each background rebuild its outline. */
private val squircles = java.util.concurrent.ConcurrentHashMap<Dp, Shape>()
private val topSquircles = java.util.concurrent.ConcurrentHashMap<Dp, Shape>()

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
      .background(subtleBg, pillShape)
      .border(0.5.dp, color.copy(alpha = 0.22f), pillShape)
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
