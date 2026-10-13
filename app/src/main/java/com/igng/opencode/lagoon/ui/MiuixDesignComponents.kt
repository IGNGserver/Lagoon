package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.TaskPhase
import com.igng.opencode.lagoon.core.TaskState
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MIUIX 连续曲率平滑圆角（HyperOS Squircle Shape）
 */
fun miuixSquircleShape(cornerRadius: Dp = 16.dp): Shape {
  return RoundedRectangle(
    cornerRadius = cornerRadius,
    style = RoundedCornerStyle.Continuous
  )
}

/**
 * MIUIX 语义色彩系统：
 * 主色采用小米 HyperOS 官方经典科技蓝 #3482FF，搭配全套层次色阶。
 */
object MiuixColorTokens {
  private val dark: Boolean @Composable get() = MiuixTheme.colorScheme.background.luminance() < 0.3f
  val Primary: Color @Composable get() = MiuixTheme.colorScheme.primary
  val PrimaryVariant: Color @Composable get() = Primary
  val PrimarySubtle: Color @Composable get() = Primary.copy(alpha = 0.12f)
  val Success: Color @Composable get() = if (dark) Color(0xFF91CEAA) else Color(0xFF246D46)
  val Warning: Color @Composable get() = if (dark) Color(0xFFE4BE86) else Color(0xFF82581F)
  val Error: Color @Composable get() = if (dark) Color(0xFFE7AEA6) else Color(0xFFA23932)
  val Info: Color @Composable get() = Primary
  val SuccessSubtle: Color @Composable get() = Success.copy(alpha = 0.12f)
  val WarningSubtle: Color @Composable get() = Warning.copy(alpha = 0.12f)
  val ErrorSubtle: Color @Composable get() = Error.copy(alpha = 0.12f)
  val NeutralSubtle: Color @Composable get() = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
  val NeutralSubtleDark: Color @Composable get() = NeutralSubtle
}

/**
 * MIUIX 风格状态胶囊 (State Badge / Pill)
 * 采用连续曲率胶囊形态与语义呼吸圆点
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
    TaskPhase.ABORTED, TaskPhase.IDLE -> MiuixTheme.colorScheme.onSurfaceVariantSummary to MiuixTheme.colorScheme.secondaryContainer
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

  Row(
    modifier = modifier
      .background(subtleBg, miuixSquircleShape(8.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
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
      style = MiuixTheme.textStyles.footnote2.copy(
        fontWeight = FontWeight.Medium,
        color = color
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * MIUIX 规范分节标题 (Section Title)
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
      .padding(horizontal = 4.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      style = MiuixTheme.textStyles.headline2.copy(
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurface
      ),
      modifier = Modifier.weight(1f)
    )
    if (count != null) {
      Text(
        text = "$count",
        style = MiuixTheme.textStyles.footnote1.copy(
          color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
          fontWeight = FontWeight.SemiBold
        ),
        modifier = Modifier.padding(end = 8.dp)
      )
    }
    if (action != null && onAction != null) {
      TextButton(
        text = action,
        onClick = onAction,
        colors = ButtonDefaults.textButtonColorsPrimary()
      )
    }
  }
}
