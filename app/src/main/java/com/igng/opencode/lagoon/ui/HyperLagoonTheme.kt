package com.igng.opencode.lagoon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 澎湃 OS 4 统一聊天与全局色彩访问桥：
 * 彻底移除旧版割裂的橄榄黑 Qoder 异色体系，聊天页与首页/设置页共用同一套四级表面色阶。
 */
internal object HyperLagoonColors {
  val dark: Boolean @Composable get() = HyperTheme.colors.isDark || MiuixTheme.colorScheme.surface.luminance() < 0.3f
  val canvas: Color @Composable get() = HyperTheme.colors.canvas
  val sheetColor: Color @Composable get() = HyperTheme.colors.sheetSurface
  val cardColor: Color @Composable get() = HyperTheme.colors.cardElevated
  val inputColor: Color @Composable get() = HyperTheme.colors.cardElevated
  val codeColor: Color @Composable get() = HyperTheme.colors.codeSurface
  val readContent: Color @Composable get() = HyperTheme.colors.codeReadSurface
  val userBubble: Color @Composable get() = HyperTheme.colors.userBubble
  val onUserBubble: Color @Composable get() = HyperTheme.colors.onUserBubble
  val text: Color @Composable get() = HyperTheme.colors.textPrimary
  val secondary: Color @Composable get() = HyperTheme.colors.textSecondary
  val faint: Color @Composable get() = HyperTheme.colors.textTertiary
  val successColor: Color @Composable get() = HyperTheme.colors.success
  val warning: Color @Composable get() = HyperTheme.colors.warning
  val error: Color @Composable get() = HyperTheme.colors.error
}

/**
 * 聊天页与全局统一的澎湃 OS 4 主题包装器：
 * 继承外层 [LagoonMiuixTheme] 已注入的四级表面色阶与字重系统，不再覆写为异色主题。
 */
@Composable
fun HyperLagoonTheme(
  content: @Composable () -> Unit
) {
  content()
}
