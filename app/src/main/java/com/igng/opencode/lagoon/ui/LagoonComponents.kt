package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape

/** A single-choice list row in HyperOS 4 style: title, optional summary, check mark when selected. */
@Composable
internal fun ChoiceRow(title: String, summary: String? = null, selected: Boolean, onClick: () -> Unit) {
  HyperRow(
    title = title,
    summary = summary,
    onClick = onClick,
    trailing = if (selected) {
      {
        Icon(MiuixIcons.Ok, "已选择", Modifier.size(20.dp), tint = HyperTheme.colors.primary)
      }
    } else null
  )
}

/**
 * HyperOS 4 capsule used for in-place selectors (model, device, project): subtle surface fill +
 * hairline border + spring press feedback.
 */
@Composable
internal fun CapsuleSelector(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  leading: (@Composable () -> Unit)? = null,
  maxTextWidth: Dp = 200.dp
) {
  val shape = miuixCapsuleShape()
  Row(
    modifier
      .clip(shape)
      .background(HyperTheme.colors.container, shape)
      .border(0.6.dp, HyperTheme.colors.cardBorder, shape)
      .hyperPress(enabled = enabled, scaleDown = 0.95f, onClick = onClick)
      .heightIn(min = HYPER_TOUCH_TARGET_DP.dp)
      .padding(start = if (leading == null) 12.dp else 10.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    leading?.let { it(); Spacer(Modifier.width(6.dp)) }
    Text(
      text,
      Modifier.widthIn(max = maxTextWidth),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MiuixTheme.textStyles.footnote1.copy(
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) HyperTheme.colors.textPrimary else HyperTheme.colors.textTertiary
      )
    )
    Spacer(Modifier.width(2.dp))
    DropdownChevron(14.dp, if (enabled) HyperTheme.colors.textSecondary else HyperTheme.colors.textTertiary)
  }
}

/** Status dot used next to server names: connected / recovering / offline. */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
  Box(modifier.size(8.dp).background(color, miuixCapsuleShape()))
}

/** Circular icon action (send / stop / attach) with HyperOS 4 spring press physics. */
@Composable
internal fun RoundAction(
  icon: ImageVector?,
  description: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
  container: Color = HyperTheme.colors.primary,
  content: Color = HyperTheme.colors.onPrimary,
  glyph: (@Composable () -> Unit)? = null
) {
  Box(
    Modifier
      .size(HYPER_TOUCH_TARGET_DP.dp)
      .hyperPress(enabled = enabled, scaleDown = 0.94f, onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center
  ) {
    Box(
      Modifier
        .size(HYPER_ICON_VISUAL_DP.dp)
        .clip(miuixCapsuleShape())
        .background(if (enabled) container else HyperTheme.colors.container),
      contentAlignment = Alignment.Center
    ) {
      if (glyph != null) glyph()
      else if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) content else HyperTheme.colors.textTertiary)
    }
  }
}
