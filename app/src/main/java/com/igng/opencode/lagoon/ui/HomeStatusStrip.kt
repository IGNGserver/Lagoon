package com.igng.opencode.lagoon.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ConnectionBanner
import com.igng.opencode.lagoon.core.ConnectStage
import com.igng.opencode.lagoon.core.HarnessProtocol
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.homeSyncNotice
import com.igng.opencode.lagoon.core.serverSnapshots
import kotlinx.coroutines.delay

/**
 * 首页唯一的状态条。
 *
 * 按单一优先级最多显示一条状态，避免“正在刷新 + 下拉重试”或重复错误同屏：
 * 1. 需要用户介入的 SSH 故障（首次/变更主机指纹、密钥需解锁、SSH 认证失败）；
 * 2. 传输层失败（OpenCode 服务未运行 → 「启动服务」，其余 → 「重试」或「设备管理」）；
 * 3. 连接阶段（重连倒计时、实时流恢复；离线缓存作为同一句话的补充）；
 * 4. 正在显示离线缓存（没有连接阶段可解释时）；
 * 5. “部分数据待同步 / 离线缓存 / 尚未连接”（下拉刷新进行中隐藏）。
 */
internal data class HomeStatusNotice(
  val message: String,
  val type: MiuixToastType,
  val actionLabel: String? = null,
  val action: HomeStatusAction? = null
)

/** 状态条唯一允许的动作集合；文案与动作分离，便于单测不依赖 Android Controller。 */
internal sealed interface HomeStatusAction {
  data object Retry : HomeStatusAction
  data object Reload : HomeStatusAction
  data object ManageDevices : HomeStatusAction
  data class StartOpenCodeService(val profileId: String) : HomeStatusAction
  data class TrustHostKey(val deviceId: String, val failure: SshFailure) : HomeStatusAction
  data class UnlockSshKey(val deviceId: String, val failure: SshFailure) : HomeStatusAction
  data class OpenDevice(val deviceId: String) : HomeStatusAction
}

internal fun isActionableSshFailure(failure: SshFailure): Boolean = failure.kind in setOf(
  SshFailureKind.HOST_KEY_UNKNOWN,
  SshFailureKind.HOST_KEY_CHANGED,
  SshFailureKind.BIOMETRIC_REQUIRED,
  SshFailureKind.PASSPHRASE_REQUIRED,
  SshFailureKind.AUTH
)

@Composable
internal fun HomeStatusStrip(
  state: LagoonState,
  controller: LagoonController,
  onManageDevices: () -> Unit = {},
  onOpenDevice: (String) -> Unit = { onManageDevices() },
  onSshAction: (deviceId: String, failure: SshFailure) -> Unit = { deviceId, _ -> onOpenDevice(deviceId) },
  modifier: Modifier = Modifier
) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val watching = state.refreshing || state.connection.stage != ConnectStage.READY ||
    !state.streamConnected || state.transcriptLoadingSince > 0
  LaunchedEffect(watching) {
    while (watching) { now = System.currentTimeMillis(); delay(1_000) }
  }
  val notice = homeStatusNotice(state, now)
  val action: (() -> Unit)? = when (val it = notice?.action) {
    null -> null
    HomeStatusAction.Retry -> ({ controller.retryNow() })
    HomeStatusAction.Reload -> ({ controller.reload() })
    HomeStatusAction.ManageDevices -> onManageDevices
    is HomeStatusAction.StartOpenCodeService -> ({ controller.restartOpenCodeService(it.profileId) })
    is HomeStatusAction.TrustHostKey -> ({ onSshAction(it.deviceId, it.failure) })
    is HomeStatusAction.UnlockSshKey -> ({ onSshAction(it.deviceId, it.failure) })
    is HomeStatusAction.OpenDevice -> ({ onOpenDevice(it.deviceId) })
  }
  AnimatedVisibility(
    visible = notice != null,
    modifier = modifier,
    enter = fadeIn(HyperMotion.sheetFloatSpring) + expandVertically(HyperMotion.controlSpring()),
    exit = fadeOut(HyperMotion.sheetFloatSpring) + shrinkVertically(HyperMotion.controlSpring())
  ) {
    val shown = notice ?: return@AnimatedVisibility
    HyperInlineNotice(
      message = shown.message,
      type = shown.type,
      action = shown.actionLabel,
      onAction = action,
      modifier = Modifier
        .padding(horizontal = 14.dp, vertical = 4.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }
    )
  }
}

/**
 * 纯函数式的优先级解析：Composable 只负责计时与渲染。优先级（先到先得）：
 * 需要介入的 SSH 故障 > 传输失败 > 连接阶段 > 离线缓存 > 待同步；刷新中不返回待同步。
 */
internal fun homeStatusNotice(
  state: LagoonState,
  now: Long
): HomeStatusNotice? {
  val snapshots = state.serverSnapshots()
  val scopeProfiles = when {
    state.allServers -> state.enabledProfiles
    state.homeGroup != null -> state.homeGroup!!.memberProfiles(state.enabledProfiles)
    state.homeDevice != null -> state.homeDeviceProfiles
    else -> listOfNotNull(state.server).filter { it.effectiveEnabled }
  }
  val aggregate = state.multiServerView

  // 1) 需要人介入处理的 SSH 故障（确认指纹、解锁密钥、检查认证）。
  scopeProfiles.firstNotNullOfOrNull { profile ->
    val failure = snapshots[profile.id]?.sshActionRequired
      ?: (if (!aggregate && profile.id == state.serverId) state.sshActionRequired else null)
      ?: state.harnessLinkStatuses[profile.id]?.error?.takeIf(::isActionableSshFailure)
    failure?.let { sshActionNotice(profile, it, aggregate) }
  }?.let { return it }

  // 2) 传输层失败。它描述的是“这台设备上的某个 Harness 起不来”，与刷新无关，永远先说出来。
  scopeProfiles.firstNotNullOfOrNull { profile ->
    snapshots[profile.id]?.transportError?.takeIf { it.isNotBlank() }?.let { text ->
      transportFailureNotice(profile, text, aggregate, state.serverId)
    }
  }?.let { return it }

  // 3) 连接阶段（只对单 Harness 视图；聚合页由各设备状态表达）。
  val onlineCount = scopeProfiles.count { snapshots[it.id]?.connected == true }
  val anyLoading = scopeProfiles.any { snapshots[it.id]?.loading == true }
  val showingOfflineCache = if (aggregate) {
    scopeProfiles.isNotEmpty() && onlineCount == 0 && !anyLoading && state.sessions.isNotEmpty()
  } else {
    state.serverId != null && !state.connected && !state.loading && state.sessions.isNotEmpty()
  }
  if (!aggregate) {
    val banner = ConnectionBanner.of(
      state.connection, state.server?.name, now, state.streamConnected, state.streamDownSince,
      transcriptSyncing = state.provisionalMessageIds.isNotEmpty() && state.transcriptLoadingSince > 0,
      transcriptSince = state.transcriptLoadingSince,
      transportDetail = state.transportDetail
    )
    if (banner != null) {
      val base = if (!banner.subtitle.isNullOrBlank()) "${banner.text} · ${banner.subtitle}" else banner.text
      val message = if (showingOfflineCache && !base.contains("缓存")) "$base · 当前显示离线缓存" else base
      val type = when (banner.tone) {
        ConnectionBanner.Tone.PROGRESS -> MiuixToastType.INFO
        ConnectionBanner.Tone.WARNING -> MiuixToastType.WARNING
        ConnectionBanner.Tone.ERROR -> MiuixToastType.ERROR
      }
      return HomeStatusNotice(
        message, type,
        actionLabel = if (banner.retry) "立即重试" else null,
        action = if (banner.retry) HomeStatusAction.Retry else null
      )
    }
  }

  // 4) 离线缓存（没有正在进行的连接阶段可解释时）：说明“你看到的是旧数据”，并直接重连。
  if (showingOfflineCache) {
    val disabled = !aggregate && state.server?.effectiveEnabled == false
    return HomeStatusNotice(
      message = if (disabled) "当前连接已停用 · 正在显示本地离线缓存会话"
      else "当前未连接设备 · 正在显示本地离线缓存会话",
      type = MiuixToastType.INFO,
      actionLabel = if (disabled) null else "立即重试",
      action = if (disabled) null else HomeStatusAction.Retry
    )
  }

  // 5) 待同步 / 离线缓存 / 尚未连接。刷新进行中整条让位给下拉刷新胶囊。
  if (state.refreshing) return null
  val syncNotice = state.homeSyncNotice() ?: return null
  return if (state.connected) HomeStatusNotice(
    message = syncNotice,
    type = MiuixToastType.WARNING,
    actionLabel = "刷新",
    action = HomeStatusAction.Reload
  ) else HomeStatusNotice(
    message = syncNotice,
    type = MiuixToastType.INFO,
    actionLabel = "重试",
    action = HomeStatusAction.Retry
  )
}

internal fun sshActionNotice(
  profile: ServerProfile,
  failure: SshFailure,
  aggregate: Boolean
): HomeStatusNotice {
  val label = profile.deviceName?.takeIf(String::isNotBlank) ?: profile.name
  val prefix = if (aggregate) "$label · " else ""
  val deviceId = profile.deviceId
  return when (failure.kind) {
    SshFailureKind.HOST_KEY_UNKNOWN -> HomeStatusNotice(
      message = "${prefix}首次连接需确认 SSH 主机指纹",
      type = MiuixToastType.WARNING,
      actionLabel = "确认指纹",
      action = deviceId?.let { HomeStatusAction.TrustHostKey(it, failure) } ?: HomeStatusAction.ManageDevices
    )
    SshFailureKind.HOST_KEY_CHANGED -> HomeStatusNotice(
      message = "${prefix}SSH 主机指纹已变更，已暂停连接",
      type = MiuixToastType.WARNING,
      actionLabel = "确认指纹",
      action = deviceId?.let { HomeStatusAction.TrustHostKey(it, failure) } ?: HomeStatusAction.ManageDevices
    )
    SshFailureKind.BIOMETRIC_REQUIRED -> HomeStatusNotice(
      message = "${prefix}SSH 密钥需生物识别解锁后才能连接",
      type = MiuixToastType.WARNING,
      actionLabel = "解锁密钥",
      action = deviceId?.let { HomeStatusAction.UnlockSshKey(it, failure) } ?: HomeStatusAction.ManageDevices
    )
    SshFailureKind.PASSPHRASE_REQUIRED -> HomeStatusNotice(
      message = "${prefix}SSH 密钥需输入口令解锁后才能连接",
      type = MiuixToastType.WARNING,
      actionLabel = "解锁密钥",
      action = deviceId?.let { HomeStatusAction.UnlockSshKey(it, failure) } ?: HomeStatusAction.ManageDevices
    )
    SshFailureKind.AUTH -> HomeStatusNotice(
      message = "${prefix}SSH 认证失败，请检查凭据或密钥",
      type = MiuixToastType.ERROR,
      actionLabel = "检查认证",
      action = deviceId?.let { HomeStatusAction.OpenDevice(it) } ?: HomeStatusAction.ManageDevices
    )
    else -> HomeStatusNotice(
      message = prefix + failure.display(),
      type = MiuixToastType.ERROR,
      actionLabel = "重试",
      action = HomeStatusAction.Retry
    )
  }
}

private const val OPENCODE_SERVICE_DOWN_PREFIX = "OpenCode 服务没有在运行"
private const val OPENCODE_SERVICE_UNRESPONSIVE_PREFIX = "OpenCode 服务没有响应"

/**
 * 传输失败的具体文案。OpenCode 的“注册还在但进程已经退出”可直接一键拉起服务。
 */
private fun transportFailureNotice(
  profile: ServerProfile,
  text: String,
  aggregate: Boolean,
  activeServerId: String?
): HomeStatusNotice {
  val line = text.lineSequence().firstOrNull()?.trim().orEmpty()
  val prefix = if (aggregate) "${profile.name} · " else ""
  val openCodeServiceDown = profile.protocol == HarnessProtocol.OPENCODE_NATIVE &&
    (line.startsWith(OPENCODE_SERVICE_DOWN_PREFIX) || line.startsWith(OPENCODE_SERVICE_UNRESPONSIVE_PREFIX))
  if (openCodeServiceDown) return HomeStatusNotice(
    message = prefix + "OpenCode 服务未运行（注册可能已过期）",
    type = MiuixToastType.ERROR,
    actionLabel = "启动服务",
    action = HomeStatusAction.StartOpenCodeService(profile.id)
  )
  val actionable = !aggregate || profile.id == activeServerId
  return HomeStatusNotice(
    message = prefix + line.ifBlank { "连接失败" },
    type = MiuixToastType.ERROR,
    actionLabel = if (actionable) "重试" else "设备管理",
    action = if (actionable) HomeStatusAction.Retry else HomeStatusAction.ManageDevices
  )
}
