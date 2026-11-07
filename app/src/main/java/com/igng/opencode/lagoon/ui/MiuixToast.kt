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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class MiuixToastType {
  INFO, SUCCESS, ERROR
}

/**
 * 澎湃 OS 4 悬浮胶囊轻提示 (HyperOS 4 Floating Island Toast)
 */
@Composable
fun MiuixToastHost(
  message: String?,
  type: MiuixToastType = MiuixToastType.INFO,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  LaunchedEffect(message) {
    if (message != null) {
      delay(if (type == MiuixToastType.ERROR) 4_000 else 2_600)
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
      val iconVector: ImageVector = when (type) {
        MiuixToastType.SUCCESS -> MiuixIcons.Ok
        MiuixToastType.ERROR -> MiuixIcons.Close
        MiuixToastType.INFO -> MiuixIcons.Info
      }
      val iconColor: Color = when (type) {
        MiuixToastType.SUCCESS -> HyperTheme.colors.success
        MiuixToastType.ERROR -> HyperTheme.colors.error
        MiuixToastType.INFO -> HyperTheme.colors.primary
      }
      val shape = miuixSquircleShape(18.dp)
      val bg = HyperTheme.colors.cardElevated
      val borderColor = if (type == MiuixToastType.ERROR) HyperTheme.colors.error.copy(alpha = 0.35f) else HyperTheme.colors.cardBorder

      // Not clickable: the toast sits over the page header, and tapping it to dismiss used to eat the
      // tap meant for the back / ⋯ button underneath for its whole lifetime.
      Row(
        modifier = Modifier
          .padding(horizontal = 24.dp, vertical = 8.dp)
          .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
          .clip(shape)
          .background(bg, shape)
          .border(0.8.dp, borderColor, shape)
          .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(
          imageVector = iconVector,
          contentDescription = null,
          tint = iconColor,
          modifier = Modifier.size(18.dp)
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
