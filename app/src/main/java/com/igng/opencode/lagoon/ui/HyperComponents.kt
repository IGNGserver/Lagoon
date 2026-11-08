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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape

/**
 * 澎湃 OS 4 自研组件底座：
 * 提供具备四级表面色阶、连续曲率圆角（Squircle）、微高光描边与弹簧按压触感的核心组件。
 */

enum class HyperButtonVariant { PRIMARY, SECONDARY, TERTIARY, DESTRUCTIVE }

/**
 * The single button entry point for product UI. Visual chrome may be 40dp, but every action keeps
 * at least a 48dp touch target. Loading preserves the label and width and blocks duplicate submits.
 */
@Composable
fun HyperButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: HyperButtonVariant = HyperButtonVariant.PRIMARY,
  enabled: Boolean = true,
  loading: Boolean = false
) {
  val actionable = hyperButtonActionable(enabled, loading)
  val background = when {
    !actionable -> HyperTheme.colors.container
    variant == HyperButtonVariant.PRIMARY -> HyperTheme.colors.primary
    variant == HyperButtonVariant.SECONDARY -> HyperTheme.colors.containerHigh
    variant == HyperButtonVariant.DESTRUCTIVE -> HyperTheme.colors.errorSubtle
    else -> Color.Transparent
  }
  val foreground = when {
    !actionable -> HyperTheme.colors.textTertiary
    variant == HyperButtonVariant.PRIMARY -> HyperTheme.colors.onPrimary
    variant == HyperButtonVariant.DESTRUCTIVE -> HyperTheme.colors.error
    variant == HyperButtonVariant.TERTIARY -> HyperTheme.colors.primary
    else -> HyperTheme.colors.textPrimary
  }
  Box(
    modifier = modifier
      .heightIn(min = HYPER_TOUCH_TARGET_DP.dp)
      .widthIn(min = HYPER_TOUCH_TARGET_DP.dp)
      .clip(miuixCapsuleShape())
      .background(background, miuixCapsuleShape())
      .hyperPress(enabled = actionable, scaleDown = 0.96f, onClick = onClick)
      .semantics { if (loading) stateDescription = "正在处理" }
      .padding(horizontal = 18.dp, vertical = 12.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = text,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      style = MiuixTheme.textStyles.button.copy(color = foreground)
    )
  }
}

@Composable
fun HyperTextAction(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  danger: Boolean = false
) = HyperButton(
  text = text,
  onClick = onClick,
  modifier = modifier,
  variant = if (danger) HyperButtonVariant.DESTRUCTIVE else HyperButtonVariant.TERTIARY,
  enabled = enabled
)

/**
 * 澎湃 OS 4 连接悬浮岛（HyperConnectionIsland）：
 * 用于在屏幕顶部显示连接状态、正在运行的任务、透出网络或设备故障，取代原本的简单 Toast。
 */
@Composable
fun HyperConnectionIsland(
  message: String,
  modifier: Modifier = Modifier,
  type: MiuixToastType = MiuixToastType.INFO,
  onClick: (() -> Unit)? = null
) {
  val (color, bgColor) = when (type) {
    MiuixToastType.ERROR -> Pair(HyperTheme.colors.error, HyperTheme.colors.errorSubtle)
    MiuixToastType.SUCCESS -> Pair(HyperTheme.colors.success, HyperTheme.colors.successSubtle)
    MiuixToastType.WARNING -> Pair(HyperTheme.colors.warning, HyperTheme.colors.warningSubtle)
    else -> Pair(HyperTheme.colors.primary, HyperTheme.colors.primarySubtle)
  }

  // Simplified version of the island for now.
  Box(
    modifier = modifier
      .clip(miuixSquircleShape(24.dp))
      .background(bgColor, miuixSquircleShape(24.dp))
      .border(0.8.dp, color.copy(alpha = 0.35f), miuixSquircleShape(24.dp))
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .then(if (onClick != null) Modifier.heightIn(min = HYPER_TOUCH_TARGET_DP.dp).hyperPress(onClick = onClick) else Modifier),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = message,
      style = MiuixTheme.textStyles.footnote1.copy(color = color, fontWeight = FontWeight.SemiBold)
    )
  }
}

/**
 * 澎湃 OS 4 行内提示（HyperInlineNotice）：
 * 用于在页面内透出错误、警告或操作指引，修正旧版松散的报错样式。
 */
@Composable
fun HyperInlineNotice(
  message: String,
  modifier: Modifier = Modifier,
  type: MiuixToastType = MiuixToastType.INFO,
  action: String? = null,
  onAction: (() -> Unit)? = null
) {
  val (color, bgColor, glyph) = when (type) {
    MiuixToastType.ERROR -> Triple(HyperTheme.colors.error, HyperTheme.colors.errorSubtle, QoderGlyph.ERROR)
    MiuixToastType.SUCCESS -> Triple(HyperTheme.colors.success, HyperTheme.colors.successSubtle, QoderGlyph.CHECK)
    MiuixToastType.WARNING -> Triple(HyperTheme.colors.warning, HyperTheme.colors.warningSubtle, QoderGlyph.WARNING)
    else -> Triple(HyperTheme.colors.primary, HyperTheme.colors.primarySubtle, QoderGlyph.INFO)
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(miuixSquircleShape(12.dp))
      .background(bgColor, miuixSquircleShape(12.dp))
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    QoderIcon(glyph, Modifier.size(18.dp), color = color)
    Text(
      text = message,
      style = MiuixTheme.textStyles.body2.copy(color = color, fontWeight = FontWeight.Medium),
      modifier = Modifier.weight(1f)
    )
    if (action != null && onAction != null) {
      HyperTextAction(text = action, onClick = onAction)
    }
  }
}

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
  surfaceHighlight: Boolean = true,
  content: @Composable ColumnScope.() -> Unit
) {
  val shape = miuixSquircleShape(cornerRadius)
  Column(
    modifier = modifier
      .clip(shape)
      .background(backgroundColor, shape)
      .border(0.6.dp, borderColor, shape)
      .then(if (onClick != null) Modifier.heightIn(min = HYPER_TOUCH_TARGET_DP.dp).hyperPress(onClick = onClick, surfaceHighlight = surfaceHighlight) else Modifier)
      .padding(insidePadding),
    content = content
  )
}

/**
 * 澎湃 OS 4 胶囊容器（用于 Dock、状态栏等）
 */
@Composable
fun HyperPill(
  modifier: Modifier = Modifier,
  background: Color = HyperTheme.colors.cardElevated,
  content: @Composable RowScope.() -> Unit
) {
  Row(
    modifier = modifier
      .clip(miuixSquircleShape(16.dp))
      .background(background)
      .border(0.6.dp, HyperTheme.colors.cardBorder, miuixSquircleShape(16.dp))
      .padding(horizontal = 14.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content
  )
}
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
  // Keep the 46×26dp HyperOS track, but move interaction to a standalone 52×48dp hit box.
  Box(
    modifier = modifier
      .size(width = 52.dp, height = HYPER_TOUCH_TARGET_DP.dp)
      .then(if (onCheckedChange != null) Modifier.semantics {
        toggleableState = ToggleableState(checked)
        stateDescription = if (checked) "已开启" else "已关闭"
      } else Modifier)
      .then(
        if (onCheckedChange != null) {
          Modifier.hyperPress(enabled = enabled, scaleDown = 0.96f, role = Role.Switch) {
            onCheckedChange(!checked)
          }
        } else Modifier
      ),
    contentAlignment = Alignment.Center
  ) {
    Box(
      Modifier
        .width(46.dp)
        .height(26.dp)
        .clip(CircleShape)
        .background(trackColor),
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
      .heightIn(min = HYPER_TOUCH_TARGET_DP.dp)
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
      ForwardChevron(16.dp, HyperTheme.colors.textTertiary)
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
    modifier = modifier.semantics(mergeDescendants = true) {
      role = Role.Switch
      toggleableState = ToggleableState(checked)
      stateDescription = if (checked) "已开启" else "已关闭"
    },
    enabled = enabled,
    onClick = { onCheckedChange(!checked) },
    trailing = {
      HyperSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
  )
}

/**
 * 自研澎湃 OS 4 动作行（替代 BasicComponent / SuperArrow）
 */
@Composable
fun HyperActionRow(
  title: String,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true,
  showChevron: Boolean = false,
  onClick: (() -> Unit)? = null,
  trailing: @Composable (RowScope.() -> Unit)? = null
) {
  HyperRow(
    title = title,
    summary = summary,
    modifier = modifier,
    enabled = enabled,
    onClick = onClick,
    showChevron = showChevron && onClick != null && trailing == null,
    trailing = trailing
  )
}
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
      showChevron = false,
      enabled = enabled && items.isNotEmpty(),
      onClick = { if (enabled && items.isNotEmpty()) open = true },
      trailing = { DropdownChevron(16.dp) }
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
  if (tabs.isEmpty()) return
  val containerShape = miuixSquircleShape(16.dp)
  val pillShape = miuixSquircleShape(12.dp)
  val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)

  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .clip(containerShape)
      .background(HyperTheme.colors.container, containerShape)
      .padding(4.dp)
  ) {
    val tabWidth = maxWidth / tabs.size
    val indicatorOffset by animateDpAsState(
      targetValue = tabWidth * safeIndex,
      animationSpec = HyperMotion.controlSpring(),
      label = "tabIndicatorOffset"
    )

    Box(
      modifier = Modifier
        .offset(x = indicatorOffset)
        .width(tabWidth)
        .height(HYPER_TOUCH_TARGET_DP.dp)
        .clip(pillShape)
        .background(HyperTheme.colors.cardElevated, pillShape)
        .border(0.5.dp, HyperTheme.colors.cardBorder, pillShape)
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      tabs.forEachIndexed { index, label ->
        val selected = index == safeIndex
        Box(
          modifier = Modifier
            .weight(1f)
            .height(HYPER_TOUCH_TARGET_DP.dp)
            .clip(pillShape)
            .hyperPress(scaleDown = 0.96f) { onTabSelected(index) }
            .semantics(mergeDescendants = true) {
              contentDescription = label
              role = Role.Tab
              this.selected = selected
              stateDescription = if (selected) "已选择" else "未选择"
            }
            .padding(horizontal = 8.dp),
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
        backgroundColor = Color.Transparent
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
 * 澎湃 OS 4 半屏抽屉底座（HyperBottomSheet）：
 * 强制统一顶部圆角为 28dp 连续曲率，并配合背景色与入场动效。
 */
@Composable
fun HyperBottomSheet(
  title: String,
  show: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit
) {
  SuperBottomSheet(
    title = title,
    show = show,
    onDismissRequest = onDismissRequest,
    backgroundColor = HyperTheme.colors.sheetSurface,
    modifier = modifier.clip(miuixTopSquircleShape(28.dp))
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth(),
      content = content
    )
  }
}

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
      .size(HYPER_TOUCH_TARGET_DP.dp)
      .hyperPress(enabled = enabled, scaleDown = 0.94f, onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center
  ) {
    Box(
      Modifier
        .size(HYPER_ICON_VISUAL_DP.dp)
        .clip(CircleShape)
        .background(backgroundColor, CircleShape),
      contentAlignment = Alignment.Center
    ) {
      QoderIcon(
        glyph = glyph,
        modifier = Modifier.size(20.dp),
        color = if (enabled) tint else tint.copy(alpha = 0.38f)
      )
    }
  }
}
