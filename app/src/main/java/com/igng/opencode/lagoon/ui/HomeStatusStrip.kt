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
import com.igng.opencode.lagoon.core.HarnessProtocolId
import com.igng.opencode.lagoon.core.HubAuthStatus
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.RemoteCatalog
import com.igng.opencode.lagoon.core.RemoteRecoveryKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.homeSyncNotice
import com.igng.opencode.lagoon.core.serverSnapshots
import kotlinx.coroutines.delay

/**
 * 首页唯一的状态条。
 *
 * 首页过去同时挂着连接阶段条（[ConnectionStrip]）、聚合恢复条、传输失败条和列表里的“待同步”行，
 * 一次刷新里最多能出现三四条互相矛盾的话：一边转圈“正在刷新”，一边提示“下拉重试”，刷新结束后
 * 还会弹一句“仍在刷新”。这里把四类输入收敛成一档优先级、至多一条：
 *
 * 1. 传输层失败（SSH / OpenCode / Codex / AGY）——能直接修的直接给动作（OpenCode 服务未运行 →
 *    「启动服务」），其余给「重试」；
 * 2. 需要人处理的登录 / 配对 / 指纹恢复；
 * 3. 连接阶段（重连倒计时、实时流恢复；离线缓存作为同一句话的补充）；
 * 4. 正在显示离线缓存（没有连接阶段可解释时）；
 * 5. “部分数据待同步 / 离线缓存 / 尚未连接”。
 *
 * 刷新进行中（`LagoonState.refreshing`）时第 5 档整条隐藏，由下拉刷新胶囊独占“正在刷新”的表达，
 * 因此不会再出现“正在刷新 + 下拉重试”同屏。
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
  data class RemoteRecovery(val profileId: String, val kind: RemoteRecoveryKind) : HomeStatusAction
  data class RemoteRelogin(val origin: String, val allowCleartext: Boolean, val account: String) : HomeStatusAction
}

/** 解析优先级时需要的、来自 Controller 的少量查询；单测可以给假实现。 */
internal interface HomeStatusHost {
  fun hostAuthExpired(profile: ServerProfile, state: LagoonState): Boolean
  fun recoveryLabel(profile: ServerProfile): String
  fun degradedRemoteCatalog(state: LagoonState): RemoteCatalog?
}

@Composable
internal fun HomeStatusStrip(
  state: LagoonState,
  controller: LagoonController,
  onManageDevices: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  // 只有会随时间变化的文案（重连倒计时、慢连接提示）需要每秒重算。
  val watching = state.refreshing || state.connection.stage != ConnectStage.READY ||
    !state.streamConnected || state.transcriptLoadingSince > 0
  LaunchedEffect(watching) {
    while (watching) { now = System.currentTimeMillis(); delay(1_000) }
  }
  val host = object : HomeStatusHost {
    override fun hostAuthExpired(profile: ServerProfile, state: LagoonState) =
      profile.remoteHostId?.let { controller.hostAuthExpired(profile.url, profile.username, it, state) } == true
    override fun recoveryLabel(profile: ServerProfile) = controller.remoteRecoveryLabel(profile)
    override fun degradedRemoteCatalog(state: LagoonState) = controller.remoteCatalogs()
      .filterNot(controller::isPairedRemoteCatalog)
      .firstOrNull { controller.hubAuthStatus(it, state) != HubAuthStatus.ACTIVE }
  }
  val notice = homeStatusNotice(state, host, now)
  val action: (() -> Unit)? = when (val it = notice?.action) {
    null -> null
    HomeStatusAction.Retry -> ({ controller.retryNow() })
    HomeStatusAction.Reload -> ({ controller.reload() })
    HomeStatusAction.ManageDevices -> onManageDevices
    is HomeStatusAction.StartOpenCodeService -> ({ controller.restartOpenCodeService(it.profileId) })
    is HomeStatusAction.RemoteRecovery -> ({ controller.openRemoteRecoveryFor(it.profileId, it.kind) })
    is HomeStatusAction.RemoteRelogin -> ({ controller.openRemoteRelogin(it.origin, it.allowCleartext, it.account) })
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
 * 传输失败 > 登录/恢复 > 中枢失效 > 连接阶段 > 离线缓存 > 待同步；刷新中不返回待同步。
 */
internal fun homeStatusNotice(
  state: LagoonState,
  host: HomeStatusHost,
  now: Long
): HomeStatusNotice? {
  val snapshots = state.serverSnapshots()
  val scopeProfiles = when {
    state.allServers -> state.enabledProfiles
    state.homeGroup != null -> state.enabledProfiles.filter { it.id in state.homeGroup!!.memberIds }
    else -> listOfNotNull(state.server).filter { it.effectiveEnabled }
  }
  val aggregate = state.multiServerView

  // 1) 传输层失败。它描述的是“这台设备上的某个 Harness 起不来”，与刷新无关，永远先说出来。
  scopeProfiles.firstNotNullOfOrNull { profile ->
    snapshots[profile.id]?.transportError?.takeIf { it.isNotBlank() }?.let { text ->
      transportFailureNotice(profile, text, aggregate, state.serverId)
    }
  }?.let { return it }

  // 2) 登录 / 配对 / 指纹 / 主机：需要人操作，不能只说“连接失败”。
  scopeProfiles.firstNotNullOfOrNull { profile ->
    val recovery = snapshots[profile.id]?.remoteRecovery
    val loginExpired = recovery == RemoteRecoveryKind.LOGIN || host.hostAuthExpired(profile, state)
    when {
      loginExpired -> HomeStatusNotice(
        message = "${profile.name} 登录态已失效，无法同步最新数据",
        type = MiuixToastType.WARNING,
        actionLabel = host.recoveryLabel(profile),
        action = HomeStatusAction.RemoteRecovery(profile.id, RemoteRecoveryKind.LOGIN)
      )
      recovery != null -> HomeStatusNotice(
        message = "${profile.name} 连接需要处理",
        type = MiuixToastType.WARNING,
        actionLabel = recovery.label,
        action = HomeStatusAction.RemoteRecovery(profile.id, recovery)
      )
      else -> null
    }
  }?.let { return it }

  // 2b) 聚合页没有可挂靠的 profile 时，仍要说清中枢登录态。
  if (aggregate && scopeProfiles.isEmpty() && state.profiles.isNotEmpty()) {
    val catalog = host.degradedRemoteCatalog(state)
    if (catalog != null) return HomeStatusNotice(
      message = "远程中枢（${catalog.account}）已退出或登录失效",
      type = MiuixToastType.WARNING,
      actionLabel = "重新登录",
      action = HomeStatusAction.RemoteRelogin(catalog.origin, catalog.allowCleartext, catalog.account)
    )
  }

  // 3) 连接阶段（只对当前可写连接；聚合页由每台设备的 chip 表达）。先给原因与倒计时，
  //    离线缓存只作为同一句话的补充，不再单独占一行。
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
      return HomeStatusNotice(message, type,
        actionLabel = if (banner.retry) "立即重试" else null,
        action = if (banner.retry) HomeStatusAction.Retry else null)
    }
  }

  // 4) 离线缓存（没有正在进行的连接阶段可解释时）：说明“你看到的是旧数据”，并直接重连。
  if (showingOfflineCache) {
    val disabled = !aggregate && state.server?.effectiveEnabled == false
    return HomeStatusNotice(
      message = if (disabled) "当前连接已停用 · 正在显示本地离线缓存会话"
      else "当前未连接服务器 · 正在显示本地离线缓存会话",
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

private const val OPENCODE_SERVICE_DOWN_PREFIX = "OpenCode 服务没有在运行"
private const val OPENCODE_SERVICE_UNRESPONSIVE_PREFIX = "OpenCode 服务没有响应"

/**
 * 传输失败的具体文案。OpenCode 的“注册还在但进程已经退出”是本应用唯一能一键修复的传输失败：
 * 设备页已有显式「启动或重启服务」，这里把它提到首页，用户不必先跑命令再回来。
 */
private fun transportFailureNotice(
  profile: ServerProfile,
  text: String,
  aggregate: Boolean,
  activeServerId: String?
): HomeStatusNotice {
  val line = text.lineSequence().firstOrNull()?.trim().orEmpty()
  val prefix = if (aggregate) "${profile.name} · " else ""
  val openCodeServiceDown = profile.sshHostId != null &&
    profile.protocol == HarnessProtocolId.OPENCODE_NATIVE &&
    (line.startsWith(OPENCODE_SERVICE_DOWN_PREFIX) || line.startsWith(OPENCODE_SERVICE_UNRESPONSIVE_PREFIX))
  if (openCodeServiceDown) return HomeStatusNotice(
    message = prefix + "OpenCode 服务未运行（注册可能已过期）",
    type = MiuixToastType.ERROR,
    actionLabel = "启动服务",
    action = HomeStatusAction.StartOpenCodeService(profile.id)
  )
  // 聚合页里失败的可能不是可写连接：这时“重试”只重连当前连接，给不出帮助，直接去设备页。
  val actionable = !aggregate || profile.id == activeServerId
  return HomeStatusNotice(
    message = prefix + line.ifBlank { "连接失败" },
    type = MiuixToastType.ERROR,
    actionLabel = if (actionable) "重试" else "设备管理",
    action = if (actionable) HomeStatusAction.Retry else HomeStatusAction.ManageDevices
  )
}
