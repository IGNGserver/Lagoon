package com.igng.opencode.lagoon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/**
 * 澎湃 OS 4（HyperOS 4）四级表面色阶与语义色彩系统：
 * - Level 0 [canvas]：全局底层画布（浅色微冷矿石灰 #F3F5F9 / 深色深邃黑曜 #090B10），拉开与卡片的物理层级；
 * - Level 1 [cardElevated]：分组大卡片岛（浅色纯白 #FFFFFF / 深色 #151821），配微高光描边 [cardBorder]；
 * - Level 2 [container] / [codeSurface] / [userBubble]：交互容器、代码块、用户气泡；
 * - Level 3 [glassChrome] / [sheetSurface]：顶栏、悬浮输入舱、半屏抽屉与浮层菜单。
 */
@Immutable
data class HyperColorPalette(
  val isDark: Boolean,
  val canvas: Color,
  val cardElevated: Color,
  val cardPressed: Color,
  val cardBorder: Color,
  val divider: Color,
  val container: Color,
  val containerHigh: Color,
  val codeSurface: Color,
  val codeReadSurface: Color,
  val codeBorder: Color,
  val userBubble: Color,
  val onUserBubble: Color,
  val glassChrome: Color,
  val sheetSurface: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val textTertiary: Color,
  val primary: Color,
  val primarySubtle: Color,
  val onPrimary: Color,
  val success: Color,
  val successSubtle: Color,
  val warning: Color,
  val warningSubtle: Color,
  val error: Color,
  val errorSubtle: Color,
  val scrim: Color
)

val LightHyperColors = HyperColorPalette(
  isDark = false,
  canvas = Color(0xFFF3F5F9),
  cardElevated = Color(0xFFFFFFFF),
  cardPressed = Color(0xFFEFF2F8),
  cardBorder = Color(0x100F172A),
  divider = Color(0x120F172A),
  container = Color(0xFFEBEEF5),
  containerHigh = Color(0xFFE2E6F0),
  codeSurface = Color(0xFFF0F3F8),
  codeReadSurface = Color(0xFFE6F4EC),
  codeBorder = Color(0x140F172A),
  userBubble = Color(0xFFDEE9FF),
  onUserBubble = Color(0xFF0D1B3E),
  glassChrome = Color(0xEBFAFBFE),
  sheetSurface = Color(0xFFF8F9FC),
  textPrimary = Color(0xFF11151F),
  textSecondary = Color(0xFF555E6F),
  textTertiary = Color(0xFF848D9F),
  primary = Color(0xFF246BFF),
  primarySubtle = Color(0x1F246BFF),
  onPrimary = Color(0xFFFFFFFF),
  success = Color(0xFF129A56),
  successSubtle = Color(0x1F129A56),
  warning = Color(0xFFD97706),
  warningSubtle = Color(0x1FD97706),
  error = Color(0xFFDC2626),
  errorSubtle = Color(0x1FDC2626),
  scrim = Color(0x66060911)
)

val DarkHyperColors = HyperColorPalette(
  isDark = true,
  canvas = Color(0xFF090B10),
  cardElevated = Color(0xFF151821),
  cardPressed = Color(0xFF1E222E),
  cardBorder = Color(0x14FFFFFF),
  divider = Color(0x14FFFFFF),
  container = Color(0xFF1F2430),
  containerHigh = Color(0xFF282E3D),
  codeSurface = Color(0xFF11141C),
  codeReadSurface = Color(0xFF12261C),
  codeBorder = Color(0x1AFFFFFF),
  userBubble = Color(0xFF1B3260),
  onUserBubble = Color(0xFFECF2FF),
  glassChrome = Color(0xEB12151E),
  sheetSurface = Color(0xFF161A24),
  textPrimary = Color(0xFFF1F3F9),
  textSecondary = Color(0xFF949CAE),
  textTertiary = Color(0xFF666E80),
  primary = Color(0xFF4788FF),
  primarySubtle = Color(0x264788FF),
  onPrimary = Color(0xFFFFFFFF),
  success = Color(0xFF38D388),
  successSubtle = Color(0x2438D388),
  warning = Color(0xFFFBBF24),
  warningSubtle = Color(0x24FBBF24),
  error = Color(0xFFF87171),
  errorSubtle = Color(0x24F87171),
  scrim = Color(0x8C000000)
)

val LocalHyperColors = staticCompositionLocalOf { LightHyperColors }

object HyperTheme {
  val colors: HyperColorPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalHyperColors.current

  /** 为计时器、数量角标开启等宽数字（Tabular Figures），避免每秒跳字抖动 */
  fun tabular(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")
}

internal fun buildMiuixColors(palette: HyperColorPalette): Colors {
  val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
  return base.copy(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryVariant = palette.primary,
    disabledPrimary = palette.containerHigh,
    disabledOnPrimary = palette.textTertiary,
    disabledPrimaryButton = palette.container,
    disabledOnPrimaryButton = palette.textTertiary,
    background = palette.canvas,
    onBackground = palette.textPrimary,
    surface = palette.canvas,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.cardElevated,
    onSurfaceSecondary = palette.textSecondary,
    onSurfaceVariantSummary = palette.textSecondary,
    onSurfaceVariantActions = palette.textSecondary,
    disabledOnSecondaryVariant = palette.textTertiary,
    surfaceContainer = palette.cardElevated,
    surfaceContainerHigh = palette.container,
    surfaceContainerHighest = palette.containerHigh,
    secondaryContainer = palette.container,
    onSecondaryContainer = palette.textPrimary,
    outline = palette.cardBorder,
    dividerLine = palette.divider,
    windowDimming = palette.scrim
  )
}

/**
 * 澎湃 OS 4 排版字重阶梯：
 * 强化大标题与分组标题的字重锚点，正文采用 15.5sp / 23sp 舒适阅读行高。
 */
internal fun buildHyperTextStyles(): TextStyles {
  val base = defaultTextStyles()
  return base.copy(
    main = base.main.copy(fontSize = 15.5.sp, lineHeight = 23.sp, letterSpacing = (-0.1).sp),
    paragraph = base.paragraph.copy(fontSize = 15.5.sp, lineHeight = 24.sp),
    body1 = base.body1.copy(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.1).sp),
    body2 = base.body2.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    button = base.button.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    footnote1 = base.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    footnote2 = base.footnote2.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    headline1 = base.headline1.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    headline2 = base.headline2.copy(fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
    title1 = base.title1.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    title2 = base.title2.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    title3 = base.title3.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    title4 = base.title4.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    subtitle = base.subtitle.copy(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
  )
}

/**
 * 全局澎湃 OS 4 主题提供器：
 * 同时注入自研 [LocalHyperColors] 四级表面色阶与定制后的 [MiuixTheme] 色彩/字重系统。
 */
@Composable
fun LagoonMiuixTheme(
  dark: Boolean,
  content: @Composable () -> Unit
) {
  val palette = if (dark) DarkHyperColors else LightHyperColors
  val colors = remember(dark) { buildMiuixColors(palette) }
  val textStyles = remember { buildHyperTextStyles() }

  CompositionLocalProvider(LocalHyperColors provides palette) {
    MiuixTheme(
      colors = colors,
      textStyles = textStyles,
      content = content
    )
  }
}

@Composable
fun OpenCodeMiuixTheme(
  dark: Boolean,
  content: @Composable () -> Unit
) = LagoonMiuixTheme(dark, content)
