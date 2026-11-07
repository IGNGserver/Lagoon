package com.igng.opencode.lagoon.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape

/**
 * 澎湃 OS 4 自研组件底座：
 * 提供具备四级表面色阶、连续曲率圆角（Squircle）、微高光描边与弹簧按压触感的核心组件。
 */

/**
 * Level 1 连续曲率分组大卡片岛（Grouped Squircle Island）
 */
@Composable
fun HyperCard(
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 22.dp,
  backgroundColor: Color = HyperTheme.colors.cardElevated,
  borderColor: Color = HyperTheme.colors.cardBorder,
  insidePadding: PaddingValues = PaddingValues(16.dp),
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit
) {
  val shape = miuixSquircleShape(cornerRadius)
  Column(
    modifier = modifier
      .clip(shape)
      .background(backgroundColor, shape)
      .border(0.6.dp, borderColor, shape)
      .then(if (onClick != null) Modifier.hyperPress(onClick = onClick) else Modifier)
      .padding(insidePadding),
    content = content
  )
}

/**
 * 澎湃 OS 4 分组标题（替代松散的灰字小标题）
 */
@Composable
fun HyperGroupTitle(
  text: String,
  modifier: Modifier = Modifier,
  trailing: (@Composable RowScope.() -> Unit)? = null
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = text,
      style = MiuixTheme.textStyles.footnote1.copy(
        fontWeight = FontWeight.SemiBold,
        color = HyperTheme.colors.textSecondary
      ),
      modifier = Modifier.weight(1f)
    )
    trailing?.invoke(this)
  }
}

/**
 * 自研澎湃 OS 4 物理弹簧开关（HyperSwitch）
 */
@Composable
fun HyperSwitch(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)?,
  modifier: Modifier = Modifier,
  enabled: Boolean = true
) {
  val trackColor by animateColorAsState(
    targetValue = when {
      !enabled -> HyperTheme.colors.containerHigh.copy(alpha = 0.5f)
      checked -> HyperTheme.colors.primary
      else -> HyperTheme.colors.containerHigh
    },
    animationSpec = HyperMotion.controlSpring(),
    label = "switchTrack"
  )
  val thumbOffset by animateDpAsState(
    targetValue = if (checked) 21.dp else 3.dp,
    animationSpec = HyperMotion.controlSpring(),
    label = "switchThumb"
  )
  val thumbSize by animateDpAsState(
    targetValue = if (checked) 22.dp else 20.dp,
    animationSpec = HyperMotion.controlSpring(),
    label = "switchThumbSize"
  )
  Box(
    modifier = modifier
      .width(46.dp)
      .height(26.dp)
      .clip(CircleShape)
      .background(trackColor)
      .then(
        if (onCheckedChange != null) {
          Modifier.hyperPress(enabled = enabled, scaleDown = 0.92f, role = Role.Switch) {
            onCheckedChange(!checked)
          }
        } else Modifier
      ),
    contentAlignment = Alignment.CenterStart
  ) {
    Box(
      Modifier
        .offset(x = thumbOffset)
        .size(thumbSize)
        .clip(CircleShape)
        .background(Color.White.copy(alpha = if (enabled) 1f else 0.6f))
    )
  }
}

/**
 * 自研澎湃 OS 4 通用列表行（支持前导图标、主副标题、尾部值、箭头或自定义动作）
 */
@Composable
fun HyperRow(
  title: String,
  modifier: Modifier = Modifier,
  summary: String? = null,
  leading: (@Composable () -> Unit)? = null,
  value: String? = null,
  showChevron: Boolean = false,
  enabled: Boolean = true,
  danger: Boolean = false,
  onClick: (() -> Unit)? = null,
  trailing: (@Composable RowScope.() -> Unit)? = null
) {
  val titleColor = when {
    !enabled -> HyperTheme.colors.textTertiary
    danger -> HyperTheme.colors.error
    else -> HyperTheme.colors.textPrimary
  }
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(miuixSquircleShape(16.dp))
      .then(if (onClick != null) Modifier.hyperPress(enabled = enabled, onClick = onClick) else Modifier)
      .padding(horizontal = 16.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (leading != null) {
      leading()
      Spacer(Modifier.width(12.dp))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = title,
        style = MiuixTheme.textStyles.body1.copy(
          fontWeight = FontWeight.Medium,
          color = titleColor
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      if (!summary.isNullOrBlank()) {
        Text(
          text = summary,
          style = MiuixTheme.textStyles.footnote2.copy(
            color = if (enabled) HyperTheme.colors.textSecondary else HyperTheme.colors.textTertiary
          ),
          maxLines = 4,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    if (!value.isNullOrBlank()) {
      Spacer(Modifier.width(8.dp))
      Text(
        text = value,
        style = MiuixTheme.textStyles.footnote1.copy(
          fontWeight = FontWeight.Medium,
          color = HyperTheme.colors.textSecondary
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
    if (trailing != null) {
      Spacer(Modifier.width(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
    if (showChevron) {
      Spacer(Modifier.width(4.dp))
      QoderIcon(
        glyph = QoderGlyph.CHEVRON,
        modifier = Modifier.size(16.dp),
        color = HyperTheme.colors.textTertiary
      )
    }
  }
}

/**
 * 自研澎湃 OS 4 开关行（替代 SuperSwitch）
 */
@Composable
fun HyperSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true
) {
  HyperRow(
    title = title,
    summary = summary,
    modifier = modifier,
    enabled = enabled,
    onClick = { onCheckedChange(!checked) },
    trailing = {
      HyperSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
  )
}

/**
 * 自研澎湃 OS 4 下拉选择行（替代 SuperDropdown）
 */
@Composable
fun HyperDropdownRow(
  title: String,
  items: List<String>,
  selectedIndex: Int,
  onSelectedIndexChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true
) {
  var open by remember { mutableStateOf(false) }
  val currentLabel = items.getOrNull(selectedIndex) ?: items.firstOrNull().orEmpty()
  Box(modifier) {
    HyperRow(
      title = title,
      summary = summary,
      value = currentLabel,
      showChevron = true,
      enabled = enabled && items.isNotEmpty(),
      onClick = { if (enabled && items.isNotEmpty()) open = true }
    )
    MenuPopup(
      show = open,
      onDismiss = { open = false },
      sections = listOf(
        MenuSection(
          items.mapIndexed { index, label ->
            MenuAction(label = label, selected = index == selectedIndex) {
              onSelectedIndexChange(index)
            }
          }
        )
      ),
      alignment = PopupPositionProvider.Align.End
    )
  }
}

/**
 * 自研澎湃 OS 4 分段胶囊控制器（替代生硬 TabRow）
 */
@Composable
fun HyperSegmentedTabs(
  tabs: List<String>,
  selectedIndex: Int,
  onTabSelected: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val containerShape = miuixSquircleShape(16.dp)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(containerShape)
      .background(HyperTheme.colors.container, containerShape)
      .padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    tabs.forEachIndexed { index, label ->
      val selected = index == selectedIndex
      val pillShape = miuixSquircleShape(12.dp)
      val bg by animateColorAsState(
        targetValue = if (selected) HyperTheme.colors.cardElevated else Color.Transparent,
        animationSpec = HyperMotion.controlSpring(),
        label = "tabBg"
      )
      Box(
        modifier = Modifier
          .weight(1f)
          .heightIn(min = 36.dp)
          .clip(pillShape)
          .background(bg, pillShape)
          .then(if (selected) Modifier.border(0.5.dp, HyperTheme.colors.cardBorder, pillShape) else Modifier)
          .hyperPress(scaleDown = 0.96f) { onTabSelected(index) }
          .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = label,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote1.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) HyperTheme.colors.textPrimary else HyperTheme.colors.textSecondary
          )
        )
      }
    }
  }
}

/**
 * 澎湃 OS 4 沉浸式磨砂顶栏（带底部分割微光与返回按钮）
 */
@Composable
fun HyperTopBar(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: (@Composable RowScope.() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {}
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(HyperTheme.colors.glassChrome)
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .heightIn(min = 60.dp)
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      HyperIconButton(
        glyph = QoderGlyph.BACK,
        description = "返回上一级",
        onClick = onBack,
        backgroundColor = HyperTheme.colors.container
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(
          text = title,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.title4.copy(
            fontWeight = FontWeight.SemiBold,
            color = HyperTheme.colors.textPrimary
          )
        )
        if (subtitle != null) {
          Row(
            Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = subtitle
          )
        }
      }
      Spacer(Modifier.width(8.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = actions
      )
    }
    HorizontalDivider(color = HyperTheme.colors.divider)
  }
}

/**
 * 澎湃 OS 4 圆形图标按钮（带弹簧按压反馈）
 */
@Composable
internal fun HyperIconButton(
  glyph: QoderGlyph,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  backgroundColor: Color = HyperTheme.colors.container,
  tint: Color = HyperTheme.colors.textPrimary,
  enabled: Boolean = true
) {
  Box(
    modifier = modifier
      .size(40.dp)
      .clip(CircleShape)
      .background(backgroundColor, CircleShape)
      .hyperPress(enabled = enabled, scaleDown = 0.92f, onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center
  ) {
    QoderIcon(glyph = glyph, color = if (enabled) tint else tint.copy(alpha = 0.38f))
  }
}
