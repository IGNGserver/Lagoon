package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ServerKind
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A compact harness marker for a session row, styled with HyperOS 4 continuous squircle and subtle border.
 */
@Composable
internal fun HarnessBadge(kind: ServerKind, modifier: Modifier = Modifier) {
  val label = when (kind) {
    ServerKind.OPENCODE -> "OpenCode"
    ServerKind.DSH, ServerKind.DSH_REMOTE -> "DSH"
  }
  val color = when (kind) {
    ServerKind.OPENCODE -> HyperTheme.colors.textSecondary
    ServerKind.DSH, ServerKind.DSH_REMOTE -> HyperTheme.colors.primary
  }
  val shape = miuixSquircleShape(6.dp)
  Text(
    text = label,
    modifier = modifier
      .clip(shape)
      .background(color.copy(alpha = 0.12f), shape)
      .border(0.5.dp, color.copy(alpha = 0.24f), shape)
      .padding(horizontal = 5.dp, vertical = 1.5.dp),
    style = MiuixTheme.textStyles.footnote2.copy(color = color, fontWeight = FontWeight.SemiBold),
    maxLines = 1
  )
}
