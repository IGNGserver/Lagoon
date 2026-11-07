package com.igng.opencode.lagoon.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class MiuixToastType {
  INFO, SUCCESS, ERROR, WARNING
}

/**
 * 澎湃 OS 4 悬浮胶囊轻提示 (HyperOS 4 Floating Island Toast)：
 * 区分 INFO / SUCCESS / WARNING / ERROR 四种语义色阶，使用感叹号圆环（QoderGlyph.ERROR）替代误导点击的 × 图标。
 */
@Composable
fun MiuixToastHost(
  message: String?,
  type: MiuixToastType = MiuixToastType.INFO,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  LaunchedEffect(message, type) {
    if (message != null) {
      delay(if (type == MiuixToastType.ERROR || type == MiuixToastType.WARNING) 3_800 else 2_500)
      onDismiss()
    }
  }

  AnimatedVisibility(
    visible = message != null,
    enter = fadeIn(HyperMotion.sheetFloatSpring) +
      scaleIn(initialScale = 0.88f, animationSpec = HyperMotion.sheetFloatSpring) +
      slideInVertically(spring(dampingRatio = 0.78f, stiffness = 380f)) { -it },
    exit = fadeOut(spring(stiffness = Spring.StiffnessHigh)) +
      scaleOut(targetScale = 0.92f, animationSpec = spring(stiffness = Spring.StiffnessHigh)) +
      slideOutVertically(spring(stiffness = Spring.StiffnessHigh)) { -it },
    modifier = modifier
  ) {
    if (message != null) {
      val glyph = when (type) {
        MiuixToastType.SUCCESS -> QoderGlyph.CHECK
        MiuixToastType.WARNING -> QoderGlyph.WARNING
        MiuixToastType.ERROR -> QoderGlyph.ERROR
        MiuixToastType.INFO -> QoderGlyph.INFO
      }
      val accentColor = when (type) {
        MiuixToastType.SUCCESS -> HyperTheme.colors.success
        MiuixToastType.WARNING -> HyperTheme.colors.warning
        MiuixToastType.ERROR -> HyperTheme.colors.error
        MiuixToastType.INFO -> HyperTheme.colors.primary
      }
      val shape = miuixSquircleShape(18.dp)
      val bg = HyperTheme.colors.cardElevated
      val borderColor = when (type) {
        MiuixToastType.ERROR -> HyperTheme.colors.error.copy(alpha = 0.35f)
        MiuixToastType.WARNING -> HyperTheme.colors.warning.copy(alpha = 0.35f)
        else -> HyperTheme.colors.cardBorder
      }

      Row(
        modifier = Modifier
          .padding(horizontal = 24.dp, vertical = 6.dp)
          .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
          .clip(shape)
          .background(bg, shape)
          .border(0.8.dp, borderColor, shape)
          .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        QoderIcon(
          glyph = glyph,
          modifier = Modifier.size(18.dp),
          color = accentColor
        )
        Text(
          text = message,
          style = MiuixTheme.textStyles.body2.copy(
            fontWeight = FontWeight.SemiBold,
            color = if (type == MiuixToastType.ERROR) HyperTheme.colors.error else HyperTheme.colors.textPrimary
          ),
          maxLines = 2
        )
      }
    }
  }
}
