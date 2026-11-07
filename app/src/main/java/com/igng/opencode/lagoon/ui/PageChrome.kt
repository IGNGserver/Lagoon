package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SpinnerDefaults
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.SpinnerItemImpl
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Round 40dp icon button on a filled circle with HyperOS 4 spring press physics, used for back and ⋯ in page headers. */
@Composable
internal fun CircleIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
  Box(
    modifier = Modifier
      .size(40.dp)
      .clip(CircleShape)
      .background(HyperTheme.colors.container, CircleShape)
      .border(0.5.dp, HyperTheme.colors.cardBorder, CircleShape)
      .hyperPress(enabled = enabled, scaleDown = 0.92f, onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center
  ) {
    Icon(
      icon,
      description,
      Modifier.size(21.dp),
      tint = if (enabled) HyperTheme.colors.textPrimary else HyperTheme.colors.textTertiary
    )
  }
}

/**
 * The "›" at the end of a navigation or drill-down row.
 */
@Composable
internal fun ForwardChevron(size: Dp = 16.dp, tint: Color = HyperTheme.colors.textTertiary, description: String? = null) {
  QoderIcon(
    glyph = QoderGlyph.CHEVRON,
    modifier = Modifier.size(size).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    color = tint
  )
}

/**
 * The "⌄" after a selector or dropdown trigger.
 */
@Composable
internal fun DropdownChevron(size: Dp = 16.dp, tint: Color = HyperTheme.colors.textSecondary, description: String? = null) {
  QoderIcon(
    glyph = QoderGlyph.CHEVRON,
    modifier = Modifier.size(size).rotate(90f).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    color = tint
  )
}

/**
 * Header of a pushed page: round back button, a one-line title with an optional subtitle row under it,
 * and trailing actions, backed by HyperOS 4 frosted chrome.
 */
@Composable
internal fun PageTopBar(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: (@Composable RowScope.() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {}
) {
  HyperTopBar(
    title = title,
    onBack = onBack,
    modifier = modifier,
    subtitle = subtitle,
    actions = actions
  )
}

/** One row of a [MenuPopup]; [selected] shows MIUIX's check mark, [danger] uses the error color. */
internal data class MenuAction(
  val label: String,
  val icon: ImageVector? = null,
  val selected: Boolean = false,
  val danger: Boolean = false,
  val onClick: () -> Unit
)

/** A titled or untitled block of a [MenuPopup]; blocks are separated by a hairline. */
internal data class MenuSection(val actions: List<MenuAction>, val title: String? = null)

/**
 * MIUIX's dropdown provider only measures the window's safe area, which excludes the system bars but
 * not the software keyboard. With `adjustResize` the composer's ＋ sits just above the IME while the
 * provider still believes there is room below, so the menu opens behind the keyboard. This mirrors the
 * dropdown placement but measures the bottom against the IME, flipping the menu above the anchor.
 */
@Composable
internal fun rememberImeAwarePopupPositionProvider(active: Boolean = true): PopupPositionProvider {
  val density = LocalDensity.current
  val imeBottom = if (active) WindowInsets.ime.getBottom(density) else 0
  return remember(imeBottom) {
    object : PopupPositionProvider {
      override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
      ): IntOffset {
        val endAligned = when (alignment) {
          PopupPositionProvider.Align.End,
          PopupPositionProvider.Align.TopEnd,
          PopupPositionProvider.Align.BottomEnd -> true
          else -> false
        } xor (layoutDirection == LayoutDirection.Rtl)
        val offsetX = if (endAligned) anchorBounds.right - popupContentSize.width - popupMargin.right
        else anchorBounds.left + popupMargin.left
        val bottom = windowBounds.bottom - imeBottom
        val offsetY = if (bottom - anchorBounds.bottom > popupContentSize.height) {
          anchorBounds.bottom + popupMargin.bottom
        } else if (anchorBounds.top - windowBounds.top > popupContentSize.height) {
          anchorBounds.top - popupContentSize.height - popupMargin.top
        } else {
          anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
        }
        return IntOffset(
          x = offsetX.coerceIn(windowBounds.left, (windowBounds.right - popupContentSize.width - popupMargin.right).coerceAtLeast(windowBounds.left)),
          y = offsetY.coerceIn(
            (windowBounds.top + popupMargin.top).coerceAtMost(bottom - popupContentSize.height - popupMargin.bottom),
            bottom - popupContentSize.height - popupMargin.bottom,
          ),
        )
      }

      override fun getMargins(): PaddingValues = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
    }
  }
}

/**
 * MIUIX list popup with icon rows (the spinner item style), optional section titles and dividers —
 * the ⋯ menus of the home page, a chat, and a long-pressed session. Choosing a row dismisses first.
 */
@Composable
internal fun MenuPopup(
  show: Boolean,
  onDismiss: () -> Unit,
  sections: List<MenuSection>,
  alignment: PopupPositionProvider.Align = PopupPositionProvider.Align.End
) {
  val visible = sections.filter { it.actions.isNotEmpty() }
  val positionProvider = rememberImeAwarePopupPositionProvider(show)
  SuperListPopup(show = show, alignment = alignment, popupPositionProvider = positionProvider, onDismissRequest = onDismiss) {
    ListPopupColumn {
      Column {
        visible.forEachIndexed { sectionIndex, section ->
          if (sectionIndex > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
          section.title?.let {
            Text(
              it,
              Modifier.padding(start = 20.dp, end = 20.dp, top = if (sectionIndex == 0) 18.dp else 10.dp, bottom = 2.dp),
              style = MiuixTheme.textStyles.footnote1.copy(
                fontWeight = FontWeight.SemiBold,
                color = HyperTheme.colors.textSecondary
              )
            )
          }
          section.actions.forEachIndexed { index, action ->
            val first = sectionIndex == 0 && index == 0 && section.title == null
            val last = sectionIndex == visible.lastIndex && index == section.actions.lastIndex
            val position = if (first) 0 else 1
            val colors = if (action.danger) SpinnerDefaults.spinnerColors(contentColor = HyperTheme.colors.error) else SpinnerDefaults.spinnerColors()
            val tint = when {
              action.danger -> HyperTheme.colors.error
              action.selected -> HyperTheme.colors.primary
              else -> HyperTheme.colors.textPrimary
            }
            SpinnerItemImpl(
              entry = SpinnerEntry(icon = action.icon?.let { icon -> { modifier -> Icon(icon, null, modifier, tint = tint) } }, title = action.label),
              entryCount = if (last) position + 1 else position + 2,
              isSelected = action.selected,
              index = position,
              spinnerColors = colors,
              onSelectedIndexChange = { onDismiss(); action.onClick() }
            )
          }
        }
      }
    }
  }
}
