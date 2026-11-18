package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

internal enum class QoderGlyph { BACK, CLOSE, MORE, THINK, CHECK, ERROR, INFO, CHEVRON, DOWN, PLUS, SEND, STOP, MIC, WARNING, COMPUTER, GOAL, LIST, RING }

/**
 * Drawn stroke icons, with geometry and [Path] objects pre-built per layout size via [drawWithCache]
 * so `onDraw` performs zero allocations at 144Hz.
 */
@Composable
internal fun QoderIcon(
  glyph: QoderGlyph,
  modifier: Modifier = Modifier.size(22.dp),
  color: Color = MiuixTheme.colorScheme.onSurface
) {
  Spacer(
    modifier.drawWithCache {
      val u = size.minDimension / 24f
      fun p(x: Float, y: Float) = Offset(x * u, y * u)
      fun buildPath(coords: FloatArray, close: Boolean = false): Path = Path().apply {
        var i = 0
        while (i < coords.size) {
          val x = coords[i] * u
          val y = coords[i + 1] * u
          if (i == 0) moveTo(x, y) else lineTo(x, y)
          i += 2
        }
        if (close) close()
      }
      val stroke = Stroke(2.0f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
      val thinStroke = Stroke(1.9f * u)
      val cachedPath: Path? = when (glyph) {
        QoderGlyph.BACK -> buildPath(floatArrayOf(15f, 5f, 8f, 12f, 15f, 19f))
        QoderGlyph.CHEVRON -> buildPath(floatArrayOf(9f, 6f, 15f, 12f, 9f, 18f))
        QoderGlyph.DOWN -> buildPath(floatArrayOf(5f, 13f, 12f, 20f, 19f, 13f))
        QoderGlyph.SEND -> buildPath(floatArrayOf(5f, 11f, 12f, 4f, 19f, 11f))
        QoderGlyph.CHECK -> buildPath(floatArrayOf(7.5f, 12f, 10.5f, 15f, 16f, 9f))
        QoderGlyph.THINK -> buildPath(
          floatArrayOf(
            12f, 2f, 15f, 4f, 18f, 4f, 19f, 7f, 22f, 9f, 21f, 12f, 22f, 15f, 19f, 17f,
            18f, 20f, 15f, 20f, 12f, 22f, 9f, 20f, 6f, 20f, 5f, 17f, 2f, 15f, 3f, 12f,
            2f, 9f, 5f, 7f, 6f, 4f, 9f, 4f
          ),
          close = true
        )
        QoderGlyph.WARNING -> buildPath(floatArrayOf(12f, 2f, 22f, 21f, 2f, 21f), close = true)
        QoderGlyph.COMPUTER -> buildPath(floatArrayOf(3f, 4f, 21f, 4f, 21f, 17f, 3f, 17f), close = true)
        else -> null
      }

      onDrawBehind {
        fun line(x: Float, y: Float, xx: Float, yy: Float) =
          drawLine(color, p(x, y), p(xx, yy), 2.0f * u, StrokeCap.Round)
        when (glyph) {
          QoderGlyph.BACK, QoderGlyph.CHEVRON, QoderGlyph.THINK ->
            drawPath(cachedPath!!, color, style = stroke)
          QoderGlyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
          QoderGlyph.MORE -> {
            drawCircle(color, 1.8f * u, p(5f, 12f))
            drawCircle(color, 1.8f * u, p(12f, 12f))
            drawCircle(color, 1.8f * u, p(19f, 12f))
          }
          QoderGlyph.PLUS -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
          QoderGlyph.DOWN -> { line(12f, 4f, 12f, 20f); drawPath(cachedPath!!, color, style = stroke) }
          QoderGlyph.SEND -> { line(12f, 20f, 12f, 4f); drawPath(cachedPath!!, color, style = stroke) }
          QoderGlyph.STOP -> drawRoundRect(color, p(6.5f, 6.5f), Size(11f * u, 11f * u), CornerRadius(2.2f * u))
          QoderGlyph.CHECK -> {
            drawCircle(color, 9f * u, p(12f, 12f), style = stroke)
            drawPath(cachedPath!!, color, style = stroke)
          }
          QoderGlyph.ERROR -> {
            drawCircle(color, 9f * u, p(12f, 12f), style = stroke)
            line(12f, 7.5f, 12f, 13f)
            drawCircle(color, 1.15f * u, p(12f, 16.5f))
          }
          QoderGlyph.INFO -> {
            drawCircle(color, 9f * u, p(12f, 12f), style = stroke)
            drawCircle(color, 1.15f * u, p(12f, 8f))
            line(12f, 11.5f, 12f, 16.5f)
          }
          QoderGlyph.WARNING -> {
            drawPath(cachedPath!!, color, style = stroke)
            line(12f, 8f, 12f, 13.5f)
            drawCircle(color, 1.1f * u, p(12f, 17f))
          }
          QoderGlyph.COMPUTER -> {
            drawPath(cachedPath!!, color, style = stroke)
            line(2f, 21f, 22f, 21f)
          }
          QoderGlyph.MIC -> {
            drawRoundRect(color, p(9f, 2f), Size(6f * u, 12f * u), CornerRadius(3f * u), style = thinStroke)
            drawArc(color, 0f, 180f, false, p(5f, 6f), Size(14f * u, 12f * u), style = thinStroke)
            line(12f, 18f, 12f, 22f)
          }
          QoderGlyph.GOAL -> {
            drawCircle(color, 8.5f * u, p(12f, 12f), style = stroke)
            drawCircle(color, 2.2f * u, p(12f, 12f))
          }
          QoderGlyph.LIST -> {
            line(3.5f, 6.5f, 5.5f, 8.5f); line(5.5f, 8.5f, 8.5f, 4.5f); line(11.5f, 6.5f, 20.5f, 6.5f)
            line(3.5f, 13f, 5.5f, 15f); line(5.5f, 15f, 8.5f, 11f); line(11.5f, 13f, 20.5f, 13f)
            line(11.5f, 19.5f, 20.5f, 19.5f)
          }
          QoderGlyph.RING -> drawCircle(color, 6.5f * u, p(12f, 12f), style = thinStroke)
        }
      }
    }
  )
}
