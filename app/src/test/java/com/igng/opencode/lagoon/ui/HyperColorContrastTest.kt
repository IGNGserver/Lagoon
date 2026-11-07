package com.igng.opencode.lagoon.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x relative luminance checks for text colors actually used by shared components. */
class HyperColorContrastTest {
  private fun composite(foreground: Color, background: Color): Color {
    val alpha = foreground.alpha
    return Color(
      red = foreground.red * alpha + background.red * (1 - alpha),
      green = foreground.green * alpha + background.green * (1 - alpha),
      blue = foreground.blue * alpha + background.blue * (1 - alpha)
    )
  }

  private fun luminance(color: Color): Double {
    fun channel(value: Float): Double = value.toDouble().let {
      if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
  }

  private fun contrast(foreground: Color, background: Color): Double {
    val first = luminance(foreground)
    val second = luminance(background)
    return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
  }

  private fun assertReadable(label: String, foreground: Color, background: Color) {
    val ratio = contrast(foreground, background)
    assertTrue("$label 只有 %.2f:1，低于普通文本 4.5:1".format(ratio), ratio >= 4.5)
  }

  private fun verify(palette: HyperColorPalette) {
    val mode = if (palette.isDark) "深色" else "浅色"
    assertReadable("$mode 主按钮", palette.onPrimary, palette.primary)
    assertReadable("$mode 画布正文", palette.textPrimary, palette.canvas)
    assertReadable("$mode 卡片正文", palette.textPrimary, palette.cardElevated)
    assertReadable("$mode 次级按钮", palette.textPrimary, palette.containerHigh)
    assertReadable("$mode 画布辅助文本", palette.textSecondary, palette.canvas)
    assertReadable("$mode 卡片辅助文本", palette.textSecondary, palette.cardElevated)
    assertReadable("$mode 画布三级文本", palette.textTertiary, palette.canvas)
    assertReadable("$mode 卡片三级文本", palette.textTertiary, palette.cardElevated)
    listOf(
      "主色" to palette.primary, "成功" to palette.success,
      "警告" to palette.warning, "错误" to palette.error
    ).forEach { (label, foreground) ->
      val subtle = when (label) {
        "主色" -> palette.primarySubtle
        "成功" -> palette.successSubtle
        "警告" -> palette.warningSubtle
        else -> palette.errorSubtle
      }
      assertReadable("$mode$label 画布提示", foreground, composite(subtle, palette.canvas))
      assertReadable("$mode$label 卡片提示", foreground, composite(subtle, palette.cardElevated))
    }
  }

  @Test fun lightSharedColorsMeetNormalTextContrast() = verify(LightHyperColors)
  @Test fun darkSharedColorsMeetNormalTextContrast() = verify(DarkHyperColors)
}
