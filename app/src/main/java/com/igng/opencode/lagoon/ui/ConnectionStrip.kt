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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ConnectStage
import com.igng.opencode.lagoon.core.ConnectionBanner
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One line under the page header that says what the SSH connection is waiting for:
 * actionable SSH failures (host key verification, key unlock, auth), stage/retry countdown,
 * or offline cache indicator.
 */
@Composable
internal fun ConnectionStrip(
  state: LagoonState,
  controller: LagoonController,
  onOpenDevice: (String) -> Unit = {},
  onSshAction: (deviceId: String, failure: SshFailure) -> Unit = { deviceId, _ -> onOpenDevice(deviceId) },
  modifier: Modifier = Modifier
) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val watching = state.serverId != null && (state.connection.stage != ConnectStage.READY ||
    !state.streamConnected || state.transcriptLoadingSince > 0)
  LaunchedEffect(watching) {
    while (watching) { now = System.currentTimeMillis(); delay(1_000) }
  }
  val activeProfile = state.server
  val sshFailure = state.sshActionRequired
    ?: state.serverId?.let { state.harnessLinkStatuses[it]?.error }?.takeIf(::isActionableSshFailure)
  val banner = if (state.serverId == null) null else ConnectionBanner.of(
    state.connection, activeProfile?.name, now, state.streamConnected, state.streamDownSince,
    transcriptSyncing = state.provisionalMessageIds.isNotEmpty() && state.transcriptLoadingSince > 0,
    transcriptSince = state.transcriptLoadingSince,
    transportDetail = state.transportDetail
  )
  val showingOfflineCache = state.serverId != null && !state.connected && state.sessions.isNotEmpty()
  val notice: Triple<String, MiuixToastType, Pair<String?, (() -> Unit)?>>? = when {
    sshFailure != null && activeProfile != null -> {
      val mapped = sshActionNotice(activeProfile, sshFailure, aggregate = false)
      val deviceId = activeProfile.deviceId
      val onAction: (() -> Unit)? = when {
        deviceId == null -> ({ controller.retryNow() })
        sshFailure.kind == SshFailureKind.AUTH -> ({ onOpenDevice(deviceId) })
        else -> ({ onSshAction(deviceId, sshFailure) })
      }
      val message = if (showingOfflineCache) "${mapped.message} · 当前显示离线缓存" else mapped.message
      Triple(message, mapped.type, mapped.actionLabel to onAction)
    }
    banner != null -> {
      val type = when (banner.tone) {
        ConnectionBanner.Tone.PROGRESS -> MiuixToastType.INFO
        ConnectionBanner.Tone.WARNING -> MiuixToastType.WARNING
        ConnectionBanner.Tone.ERROR -> MiuixToastType.ERROR
      }
      val baseMessage = if (!banner.subtitle.isNullOrBlank()) "${banner.text} · ${banner.subtitle}" else banner.text
      val message = if (showingOfflineCache && !baseMessage.contains("缓存")) "$baseMessage · 当前显示离线缓存" else baseMessage
      val action = if (banner.retry) "立即重试" else null
      val onAction: (() -> Unit)? = if (banner.retry) ({ controller.retryNow() }) else null
      Triple(message, type, action to onAction)
    }
    showingOfflineCache -> {
      val disabled = activeProfile?.effectiveEnabled == false
      val message = if (disabled) "当前连接已停用 · 正在显示本地离线缓存会话"
      else "当前未连接设备 · 正在显示本地离线缓存会话"
      val action = if (disabled) null else "立即重试"
      val onAction: (() -> Unit)? = if (disabled) null else ({ controller.retryNow() })
      Triple(message, MiuixToastType.INFO, action to onAction)
    }
    else -> null
  }
  AnimatedVisibility(
    visible = notice != null,
    modifier = modifier,
    enter = fadeIn(HyperMotion.sheetFloatSpring) + expandVertically(HyperMotion.controlSpring()),
    exit = fadeOut(HyperMotion.sheetFloatSpring) + shrinkVertically(HyperMotion.controlSpring())
  ) {
    val shown = notice ?: return@AnimatedVisibility
    HyperInlineNotice(
      message = shown.first,
      type = shown.second,
      action = shown.third.first,
      onAction = shown.third.second,
      modifier = Modifier
        .padding(horizontal = 14.dp, vertical = 4.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }
    )
  }
}

/** Placeholder bars while a list has nothing to show yet; they pulse so the wait reads as progress. */
@Composable
internal fun LoadingSkeleton(rows: Int = 3, modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "skeleton")
  val alpha = transition.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
  val fill = MiuixTheme.colorScheme.surfaceContainerHigh
  Column(
    modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value }.semantics { liveRegion = LiveRegionMode.Polite },
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
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
  val alpha = transition.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
  val fill = MiuixTheme.colorScheme.surfaceContainerHigh
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).graphicsLayer { this.alpha = alpha.value },
    verticalArrangement = Arrangement.spacedBy(20.dp)
  ) {
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
