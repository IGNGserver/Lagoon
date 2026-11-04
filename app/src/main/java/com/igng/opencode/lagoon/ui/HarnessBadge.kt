package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ServerKind
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A compact harness marker for a session row. DSH and DSH Remote both read "DSH" because the
 * distinction users care about in a mixed device list is the harness family; the profile name
 * carries the instance detail.
 */
@Composable
internal fun HarnessBadge(kind: ServerKind, modifier: Modifier = Modifier) {
  val label = when (kind) {
    ServerKind.OPENCODE -> "OpenCode"
    ServerKind.DSH, ServerKind.DSH_REMOTE -> "DSH"
  }
  val color = when (kind) {
    ServerKind.OPENCODE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    ServerKind.DSH, ServerKind.DSH_REMOTE -> MiuixTheme.colorScheme.primary
  }
  Text(
    text = label,
    modifier = modifier
      .clip(RoundedCornerShape(4.dp))
      .background(color.copy(alpha = 0.12f))
      .padding(horizontal = 4.dp, vertical = 1.dp),
    style = MiuixTheme.textStyles.footnote2.copy(color = color, fontWeight = FontWeight.Medium),
    maxLines = 1
  )
}
