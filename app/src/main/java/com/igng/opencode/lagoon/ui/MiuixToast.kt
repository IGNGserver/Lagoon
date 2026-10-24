package com.igng.opencode.lagoon.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
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
 * 遵循小米 HyperOS / MIUIX 规范的全局轻提示 (Miuix Toast)
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
    enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
      slideInVertically(spring(dampingRatio = 0.78f, stiffness = 350f)) { -it },
    exit = fadeOut(spring(stiffness = Spring.StiffnessHigh)) +
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
        MiuixToastType.SUCCESS -> MiuixColorTokens.Success
        MiuixToastType.ERROR -> MiuixColorTokens.Error
        MiuixToastType.INFO -> MiuixColorTokens.Primary
      }

      Card(
        // Not clickable: the toast sits over the page header, and tapping it to dismiss used to eat the
        // tap meant for the back / ⋯ button underneath for its whole lifetime.
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        cornerRadius = 14.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = CardDefaults.defaultColors(
          color = if (type == MiuixToastType.ERROR) MiuixColorTokens.ErrorSubtle else MiuixTheme.colorScheme.surfaceContainerHighest
        )
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
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
              fontWeight = FontWeight.Medium,
              color = if (type == MiuixToastType.ERROR) MiuixColorTokens.Error else MiuixTheme.colorScheme.onSurface
            ),
            maxLines = 2
          )
        }
      }
    }
  }
}
