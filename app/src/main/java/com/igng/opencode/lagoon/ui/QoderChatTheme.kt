package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 兼容桥接至统一澎湃 OS 4 色彩系统 [HyperTheme.colors] / [HyperLagoonColors]
 */
internal object QoderColors {
  val dark: Boolean @Composable get() = HyperLagoonColors.dark
  val sheetColor: Color @Composable get() = HyperLagoonColors.sheetColor
  val inputColor: Color @Composable get() = HyperLagoonColors.inputColor
  val codeColor: Color @Composable get() = HyperLagoonColors.codeColor
  val successColor: Color @Composable get() = HyperLagoonColors.successColor
  val readContent: Color @Composable get() = HyperLagoonColors.readContent
  val secondary: Color @Composable get() = HyperLagoonColors.secondary
  val error: Color @Composable get() = HyperLagoonColors.error
  val warning: Color @Composable get() = HyperLagoonColors.warning
}

@Composable
internal fun QoderChatTheme(content: @Composable () -> Unit) {
  HyperLagoonTheme(content = content)
}

internal enum class QoderGlyph { BACK, CLOSE, MORE, THINK, CHECK, ERROR, CHEVRON, DOWN, PLUS, SEND, STOP, MIC, WARNING, COMPUTER }

/** Drawn stroke icons, rather than font glyphs whose weight/shape changes with the phone's font. */
@Composable
internal fun QoderIcon(glyph: QoderGlyph, modifier: Modifier = Modifier.size(22.dp), color: Color = MiuixTheme.colorScheme.onSurface) {
  Canvas(modifier) {
    val u = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, p(x, y), p(xx, yy), 2.0f * u, StrokeCap.Round)
    fun path(points: List<Pair<Float, Float>>, close: Boolean = false) {
      val shape = Path().apply {
        points.forEachIndexed { i, point ->
          if (i == 0) moveTo(point.first * u, point.second * u) else lineTo(point.first * u, point.second * u)
        }
        if (close) close()
      }
      drawPath(shape, color, style = Stroke(2.0f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    when (glyph) {
      QoderGlyph.BACK -> path(listOf(15f to 5f, 8f to 12f, 15f to 19f))
      QoderGlyph.CHEVRON -> path(listOf(9f to 6f, 15f to 12f, 9f to 18f))
      QoderGlyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
      QoderGlyph.MORE -> for (x in listOf(5f, 12f, 19f)) drawCircle(color, 1.8f * u, p(x, 12f))
      QoderGlyph.PLUS -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
      QoderGlyph.DOWN -> { line(12f, 4f, 12f, 20f); path(listOf(5f to 13f, 12f to 20f, 19f to 13f)) }
      QoderGlyph.SEND -> { line(12f, 20f, 12f, 4f); path(listOf(5f to 11f, 12f to 4f, 19f to 11f)) }
      QoderGlyph.STOP -> drawRoundRect(color, p(6f, 6f), androidx.compose.ui.geometry.Size(12f * u, 12f * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u))
      QoderGlyph.CHECK -> { drawCircle(color, 9f * u, p(12f, 12f), style = Stroke(2.0f * u)); path(listOf(7.5f to 12f, 10.5f to 15f, 16f to 9f)) }
      QoderGlyph.ERROR -> { drawCircle(color, 9f * u, p(12f, 12f), style = Stroke(2.0f * u)); line(9f, 9f, 15f, 15f); line(15f, 9f, 9f, 15f) }
      QoderGlyph.THINK -> path(listOf(12f to 2f, 15f to 4f, 18f to 4f, 19f to 7f, 22f to 9f, 21f to 12f, 22f to 15f, 19f to 17f, 18f to 20f, 15f to 20f, 12f to 22f, 9f to 20f, 6f to 20f, 5f to 17f, 2f to 15f, 3f to 12f, 2f to 9f, 5f to 7f, 6f to 4f, 9f to 4f), true)
      QoderGlyph.WARNING -> { path(listOf(12f to 2f, 22f to 21f, 2f to 21f), true); line(12f, 8f, 12f, 13f); drawCircle(color, u, p(12f, 17f)) }
      QoderGlyph.COMPUTER -> { path(listOf(3f to 4f, 21f to 4f, 21f to 17f, 3f to 17f), true); line(2f, 21f, 22f, 21f) }
      QoderGlyph.MIC -> { drawRoundRect(color, p(9f, 2f), androidx.compose.ui.geometry.Size(6f * u, 12f * u), androidx.compose.ui.geometry.CornerRadius(3f * u), style = Stroke(1.9f * u)); drawArc(color, 0f, 180f, false, p(5f, 6f), androidx.compose.ui.geometry.Size(14f * u, 12f * u), style = Stroke(1.9f * u)); line(12f, 18f, 12f, 22f) }
    }
  }
}

@Composable
internal fun QoderCircleButton(
  glyph: QoderGlyph,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  background: Color = HyperTheme.colors.container,
  foreground: Color = MiuixTheme.colorScheme.onSurface,
  enabled: Boolean = true
) {
  Box(
    modifier
      .size(40.dp)
      .clip(CircleShape)
      .background(background, CircleShape)
      .hyperPress(enabled = enabled, scaleDown = 0.92f, onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center
  ) {
    QoderIcon(glyph, color = if (enabled) foreground else foreground.copy(alpha = 0.4f))
  }
}
