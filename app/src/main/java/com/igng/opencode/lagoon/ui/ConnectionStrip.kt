package com.igng.opencode.lagoon.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ConnectionBanner
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One line under the page header that says what the connection is waiting for: the stage, how long it
 * has taken, the retry countdown, and a “立即重试” action once waiting stops being routine. It pushes
 * the content down instead of covering the header, and stays hidden for quick, uneventful connects.
 */
@Composable
internal fun ConnectionStrip(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  // The copy depends on elapsed time (quiet period, slow hint, countdown): tick while anything could show.
  val watching = state.serverId != null && (state.connection.stage != com.igng.opencode.lagoon.core.ConnectStage.READY ||
    !state.streamConnected || state.transcriptLoadingSince > 0)
  LaunchedEffect(watching) {
    while (watching) { now = System.currentTimeMillis(); delay(1_000) }
  }
  val banner = if (state.serverId == null) null else ConnectionBanner.of(
    state.connection, state.server?.name, now, state.streamConnected, state.streamDownSince,
    transcriptSyncing = state.provisionalMessageIds.isNotEmpty() && state.transcriptLoadingSince > 0,
    transcriptSince = state.transcriptLoadingSince)
  AnimatedVisibility(banner != null, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
    val shown = banner ?: return@AnimatedVisibility
    val color = when (shown.tone) {
      ConnectionBanner.Tone.PROGRESS -> MiuixTheme.colorScheme.onSurfaceVariantSummary
      ConnectionBanner.Tone.WARNING -> MiuixColorTokens.Warning
      ConnectionBanner.Tone.ERROR -> MiuixColorTokens.Error
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (shown.tone == ConnectionBanner.Tone.PROGRESS) InfiniteProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
      Text(shown.text, style = MiuixTheme.textStyles.footnote1, color = color, maxLines = 2, modifier = Modifier.weight(1f))
      if (shown.retry) TextButton(text = "立即重试", onClick = { controller.retryNow() })
    }
  }
}

/** Placeholder bars while a list has nothing to show yet; they pulse so the wait reads as progress. */
@Composable
internal fun LoadingSkeleton(rows: Int = 3, modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "skeleton")
  val alpha by transition.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
  val fill = MiuixTheme.colorScheme.surfaceContainerHigh
  Column(modifier.fillMaxWidth().alpha(alpha).semantics { liveRegion = LiveRegionMode.Polite },
    verticalArrangement = Arrangement.spacedBy(14.dp)) {
    repeat(rows) { index ->
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Bar(fill, if (index % 2 == 0) 0.62f else 0.48f, 14.dp)
        Bar(fill, if (index % 2 == 0) 0.9f else 0.8f, 10.dp)
      }
    }
  }
}

@Composable
private fun Bar(color: androidx.compose.ui.graphics.Color, fraction: Float, height: Dp) {
  Box(Modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(4.dp)).background(color))
}

/** Home rows placeholder: avatar dot plus two lines, matching the session row rhythm. */
@Composable
internal fun SessionRowsSkeleton(rows: Int = 6) {
  val transition = rememberInfiniteTransition(label = "rows")
  val alpha by transition.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
  val fill = MiuixTheme.colorScheme.surfaceContainerHigh
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).alpha(alpha),
    verticalArrangement = Arrangement.spacedBy(20.dp)) {
    repeat(rows) { index ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(fill))
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Bar(fill, if (index % 3 == 0) 0.55f else 0.7f, 14.dp)
          Bar(fill, 0.35f, 10.dp)
        }
      }
    }
  }
}
