package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SpinnerDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.SpinnerItemImpl
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Round 40dp icon button on a filled circle, used for back and ⋯ in page headers. */
@Composable
internal fun CircleIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
  IconButton(onClick = onClick, enabled = enabled, backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
    cornerRadius = 20.dp, minWidth = 40.dp, minHeight = 40.dp) {
    Icon(icon, description, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurface)
  }
}

/**
 * The "⌄" after a selector. MIUIX's `ExpandMore` glyph is the expand-to-fullscreen corners, not a
 * chevron, so the down arrow is the forward chevron turned a quarter.
 */
@Composable
internal fun DropdownChevron(size: Dp = 16.dp, tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary, description: String? = null) {
  Icon(MiuixIcons.ChevronForward, description, Modifier.size(size).rotate(90f), tint = tint)
}

/**
 * Header of a pushed page: round back button, a one-line title with an optional subtitle row under it,
 * and trailing actions. It never collapses, so the title stays readable while the content scrolls.
 */
@Composable
internal fun PageTopBar(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: (@Composable RowScope.() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {}
) {
  Row(modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically) {
    CircleIconButton(MiuixIcons.Back, "返回上一级", onBack)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface))
      if (subtitle != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, content = subtitle)
    }
    Spacer(Modifier.width(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
  }
}

/** One row of a [MenuPopup]; [selected] shows MIUIX's check mark, [danger] uses the error color. */
internal data class MenuAction(val label: String, val icon: ImageVector? = null, val selected: Boolean = false,
  val danger: Boolean = false, val onClick: () -> Unit)

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
  // Only an open popup follows the keyboard. Every list row owns a closed menu, and reading the IME
  // inset in all of them recomposed the whole list on every frame of the keyboard animation.
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
          // Room below the anchor and above the keyboard.
          anchorBounds.bottom + popupMargin.bottom
        } else if (anchorBounds.top - windowBounds.top > popupContentSize.height) {
          // No room below: flip above the anchor.
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
internal fun MenuPopup(show: Boolean, onDismiss: () -> Unit, sections: List<MenuSection>,
  alignment: PopupPositionProvider.Align = PopupPositionProvider.Align.End) {
  val visible = sections.filter { it.actions.isNotEmpty() }
  val positionProvider = rememberImeAwarePopupPositionProvider(show)
  SuperListPopup(show = show, alignment = alignment, popupPositionProvider = positionProvider, onDismissRequest = onDismiss) {
    ListPopupColumn {
      visible.forEachIndexed { sectionIndex, section ->
        if (sectionIndex > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        section.title?.let {
          Text(it, Modifier.padding(start = 20.dp, end = 20.dp, top = if (sectionIndex == 0) 18.dp else 10.dp, bottom = 2.dp),
            style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
        section.actions.forEachIndexed { index, action ->
          // SpinnerItemImpl pads only the first and last rows of a list; map our rows onto that rule.
          val first = sectionIndex == 0 && index == 0 && section.title == null
          val last = sectionIndex == visible.lastIndex && index == section.actions.lastIndex
          val position = if (first) 0 else 1
          val colors = if (action.danger) SpinnerDefaults.spinnerColors(contentColor = MiuixColorTokens.Error) else SpinnerDefaults.spinnerColors()
          val tint = when {
            action.danger -> MiuixColorTokens.Error
            action.selected -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.onSurface
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
