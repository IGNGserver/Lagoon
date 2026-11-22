package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.DEFAULT_DSH_LAUNCH_COMMAND
import com.igng.opencode.lagoon.core.DeviceView
import com.igng.opencode.lagoon.core.DiscoveryCompleteness
import com.igng.opencode.lagoon.core.DiscoveryValue
import com.igng.opencode.lagoon.core.HarnessConfig
import com.igng.opencode.lagoon.core.HarnessProtocol
import com.igng.opencode.lagoon.core.HarnessSlot
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshKeyProfile
import com.igng.opencode.lagoon.core.SshLinkPhase
import com.igng.opencode.lagoon.core.SshPassphrasePolicy
import com.igng.opencode.lagoon.core.connectionTypeLabel
import com.igng.opencode.lagoon.core.endpointSummary
import com.igng.opencode.lagoon.core.serverSnapshots
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 纯 SSH 设备列表：一台开发机就是一行，不再拆成中枢/直连/SSH 多个区域。
 *
 * 这一页只负责“有哪些 SSH 设备、哪台启用着、全局密钥库与设备组”；
 * Harness 的发现与开关在设备详情页，单条 Harness 的协议专属配置在 Harness 详情页。
 */
@Composable
internal fun DeviceManagementScreen(
  state: LagoonState,
  controller: LagoonController,
  onBack: () -> Unit,
  onAddDevice: () -> Unit,
  onEditGroup: (String?) -> Unit,
  onOpenDevice: (String) -> Unit,
  onOpenKeychain: () -> Unit
) {
  val devices = rememberDevices(state, controller)
  val enabledHarnessCount = devices.sumOf { it.enabledCount }

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar("设备", onBack, actions = {
      CircleIconButton(MiuixIcons.Add, "添加 SSH 设备", onAddDevice)
    })
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        Text(
          "${devices.size} 台设备 · $enabledHarnessCount 个 Harness 已启用",
          Modifier.padding(horizontal = 6.dp),
          style = MiuixTheme.textStyles.body2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
      state.migrationNotice?.let { notice ->
        item(key = "migration-notice") {
          HyperInlineNotice(
            message = notice,
            type = MiuixToastType.INFO,
            action = "知道了",
            onAction = { controller.dismissMigrationNotice() }
          )
        }
      }
      if (state.deviceGroupReset) item {
        HyperInlineNotice(
          message = "旧版设备分组已重置；SSH 设备、密钥和会话均已保留。",
          type = MiuixToastType.INFO,
          action = "知道了",
          onAction = { controller.dismissDeviceGroupReset() }
        )
      }
      item {
        HyperGroupTitle("SSH 接入")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "添加 SSH 设备",
            summary = "填写主机地址、用户名与密码或密钥；保存后只读发现可用 Harness",
            showChevron = true,
            onClick = onAddDevice
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "SSH 密钥库",
            summary = if (state.sshKeys.isEmpty()) "生成 / 导入并统一管理全局 SSH 密钥"
            else "已保存 ${state.sshKeys.size} 把 SSH 密钥（软件 / 硬件 / 证书）",
            showChevron = true,
            onClick = onOpenKeychain
          )
        }
      }
      if (devices.isNotEmpty()) {
        item(key = "device-title", contentType = "groupTitle") { HyperGroupTitle("SSH 设备") }
        val keys = uniqueListKeys(devices) { "device:${it.id.value}" }
        itemsIndexed(
          items = devices,
          key = { index, _ -> keys[index] },
          contentType = { _, _ -> "deviceRow" }
        ) { index, device ->
          HyperGroupItem(
            position = groupRowPosition(index, devices.size),
            horizontalMargin = 0.dp
          ) {
            DeviceRow(device, controller, onOpenDevice)
          }
        }
      } else {
        item {
          HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
            HyperRow(
              title = "还没有 SSH 设备",
              summary = "添加一台开发机即可通过 SSH 连接 DSH / OpenCode / Codex / Antigravity",
              showChevron = true,
              onClick = onAddDevice
            )
          }
        }
      }
      item {
        HyperGroupTitle("设备组")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          state.deviceGroups.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            val memberDevices = devices.count { it.id.value in group.deviceIds }
            HyperRow(
              title = group.name,
              summary = if (memberDevices > 0) "$memberDevices 台设备 · 只影响本机展示与范围"
              else "设备已移除；分组保留",
              trailing = { DropdownChevron() },
              onClick = { onEditGroup(group.id) }
            )
          }
          if (state.deviceGroups.isEmpty()) {
            HyperRow(title = "还没有设备组", summary = "把需要一起使用的 SSH 设备放进一个范围")
          }
        }
        HyperButton(
          text = "新建设备组",
          variant = HyperButtonVariant.SECONDARY,
          enabled = devices.isNotEmpty(),
          onClick = { onEditGroup(null) },
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
      }
    }
  }
}

@Composable
private fun DeviceRow(device: DeviceView, controller: LagoonController, onOpenDevice: (String) -> Unit) {
  val agg = device.aggregateStatus
  val error = agg?.error
  val dotColor = when {
    !device.enabled -> HyperTheme.colors.textTertiary
    device.online -> HyperTheme.colors.success
    error != null && isActionableSshFailure(error) -> HyperTheme.colors.warning
    agg?.phase == SshLinkPhase.FAILED -> HyperTheme.colors.error
    agg?.phase == SshLinkPhase.CONNECTING || agg?.phase == SshLinkPhase.RECONNECTING -> HyperTheme.colors.warning
    else -> HyperTheme.colors.textTertiary
  }
  HyperRow(
    title = device.name,
    summary = buildString {
      append(device.endpoint)
      append(" · ")
      append(if (device.attachedCount == 0) "未添加 Harness" else "${device.attachedCount} 个 Harness · ${device.enabledCount} 启用")
      when {
        !device.enabled -> append(" · 已停用")
        device.attachedCount == 0 -> append(" · 不占用连接")
        device.online -> append(" · 在线")
        error?.kind == SshFailureKind.HOST_KEY_UNKNOWN || error?.kind == SshFailureKind.HOST_KEY_CHANGED -> append(" · 需确认指纹")
        error?.kind == SshFailureKind.BIOMETRIC_REQUIRED || error?.kind == SshFailureKind.PASSPHRASE_REQUIRED -> append(" · 需解锁密钥")
        error?.kind == SshFailureKind.AUTH -> append(" · 认证失败")
        agg?.phase == SshLinkPhase.CONNECTING || agg?.phase == SshLinkPhase.RECONNECTING -> append(" · 连接中")
        agg?.phase == SshLinkPhase.FAILED -> append(" · 连接异常")
        else -> append(" · 未连接")
      }
    },
    leading = { StatusDot(dotColor) },
    showChevron = true,
    onClick = { onOpenDevice(device.id.value) },
    trailing = {
      HyperSwitch(
        checked = device.enabled,
        onCheckedChange = { controller.setDeviceEnabled(device.id.value, it) }
      )
    }
  )
}

/**
 * SSH 设备详情：设备级配置、TOFU 指纹管理、密钥状态与 Harness 发现/开关的唯一入口。
 */
@Composable
internal fun DeviceDetailScreen(
  state: LagoonState,
  controller: LagoonController,
  deviceId: String,
  onBack: () -> Unit,
  onOpenHarness: (String) -> Unit,
  onAddHarness: (DeviceView, HarnessSlot) -> Unit,
  onRefreshDiscovery: (String) -> Unit,
  discoveryError: String?,
  onEditSsh: (String) -> Unit,
  onOpenKeychain: (String?) -> Unit,
  onSshAction: (deviceId: String, failure: SshFailure) -> Unit = { _, _ -> },
  onDeleteDevice: (String) -> Unit
) {
  val context = LocalContext.current
  val devices = rememberDevices(state, controller)
  val device = devices.firstOrNull { it.id.value == deviceId }
  if (device == null) {
    LaunchedEffect(deviceId) { onBack() }
    return
  }
  val sshDevice = device.device
  var confirmingDelete by remember(deviceId) { mutableStateOf(false) }
  var confirmingFingerprintReset by remember(deviceId) { mutableStateOf(false) }
  var unlockKeyTarget by remember(deviceId) { mutableStateOf<SshKeyProfile?>(null) }
  var fingerprintCopied by remember(deviceId) { mutableStateOf(false) }
  val boundSshKey = remember(sshDevice.keyId, state.sshKeys, state.sshKeysRevision) {
    controller.sshKey(sshDevice.keyId)
  }
  val refreshing = deviceId in state.deviceRefreshInFlight
  val agg = device.aggregateStatus
  val actionableError = agg?.error?.takeIf(::isActionableSshFailure)

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(device.name, onBack, subtitle = {
      StatusDot(
        when {
          !device.enabled -> HyperTheme.colors.textTertiary
          device.online -> HyperTheme.colors.success
          actionableError != null -> HyperTheme.colors.warning
          agg?.phase == SshLinkPhase.FAILED -> HyperTheme.colors.error
          agg?.phase == SshLinkPhase.CONNECTING || agg?.phase == SshLinkPhase.RECONNECTING -> HyperTheme.colors.warning
          else -> HyperTheme.colors.textTertiary
        }
      )
      Text(
        when {
          !device.enabled -> device.blockedReason
          device.attachedCount == 0 -> "已启用 · 未添加 Harness"
          device.online -> "在线"
          actionableError?.kind == SshFailureKind.HOST_KEY_UNKNOWN || actionableError?.kind == SshFailureKind.HOST_KEY_CHANGED -> "需确认主机指纹"
          actionableError?.kind == SshFailureKind.BIOMETRIC_REQUIRED || actionableError?.kind == SshFailureKind.PASSPHRASE_REQUIRED -> "SSH 密钥未解锁"
          actionableError?.kind == SshFailureKind.AUTH -> "SSH 认证失败"
          agg?.phase == SshLinkPhase.CONNECTING || agg?.phase == SshLinkPhase.RECONNECTING -> "正在连接…"
          agg?.phase == SshLinkPhase.FAILED -> "连接异常"
          else -> "未连接"
        },
        style = MiuixTheme.textStyles.footnote1.copy(color = HyperTheme.colors.textSecondary)
      )
    })
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      if (actionableError != null && device.enabled) {
        item(key = "ssh-action") {
          val actionLabel = when (actionableError.kind) {
            SshFailureKind.HOST_KEY_UNKNOWN, SshFailureKind.HOST_KEY_CHANGED -> "确认指纹"
            SshFailureKind.BIOMETRIC_REQUIRED, SshFailureKind.PASSPHRASE_REQUIRED -> "解锁密钥"
            SshFailureKind.AUTH -> "编辑 SSH"
            else -> "重试"
          }
          HyperInlineNotice(
            message = actionableError.display(),
            type = if (actionableError.kind == SshFailureKind.AUTH) MiuixToastType.ERROR else MiuixToastType.WARNING,
            action = actionLabel,
            onAction = {
              when (actionableError.kind) {
                SshFailureKind.AUTH -> onEditSsh(deviceId)
                else -> onSshAction(deviceId, actionableError)
              }
            }
          )
        }
      }
      item {
        HyperGroupTitle("SSH 设备")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperSwitchRow(
            title = "启用此设备",
            summary = if (device.enabled) "停用后不再为这台设备建立 SSH 连接，也不做发现；子 Harness 开关保留"
            else "已停用：不建立连接、不刷新；重新启用后自动恢复已启用的 Harness",
            checked = device.enabled,
            onCheckedChange = { controller.setDeviceEnabled(device.id.value, it) }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "地址", summary = device.endpoint)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "验证方式", value = sshDevice.auth.label)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          val fingerprint = device.fingerprint
          Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "主机指纹${device.hostKeyType?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()}",
              style = MiuixTheme.textStyles.body1
            )
            if (fingerprint.isNullOrBlank()) {
              Text(
                "尚未固定；首次连接或发现时会出示远端公钥指纹供核对",
                style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
              )
            } else {
              // 保留完整 OpenSSH `SHA256:...` base64 指纹，不剥离非 hex 字符。
              Text(
                fingerprint,
                style = MiuixTheme.textStyles.footnote2.copy(
                  color = HyperTheme.colors.textSecondary,
                  fontFamily = FontFamily.Monospace
                )
              )
              Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                HyperTextAction(
                  text = if (fingerprintCopied) "已复制" else "复制指纹",
                  onClick = {
                    copyTextToClipboard(context, "${device.name} SSH 指纹", fingerprint)
                    fingerprintCopied = true
                  }
                )
                HyperTextAction(
                  text = "重置指纹",
                  danger = true,
                  onClick = { confirmingFingerprintReset = true }
                )
              }
            }
          }
        }
      }
      item {
        HyperGroupTitle("Harness", trailing = {
          HyperTextAction(
            text = if (refreshing) "正在发现…" else "刷新发现",
            enabled = !refreshing && device.enabled,
            onClick = { onRefreshDiscovery(device.id.value) }
          )
        })
        Text(
          discoverySummary(device) + when {
            !device.enabled -> "；设备停用时只展示上次结果，不会连接"
            device.attachedCount == 0 -> "；没有启用的 Harness 就不占用连接与流量"
            else -> ""
          },
          Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
        discoveryError?.let { message ->
          HyperInlineNotice(
            message = message,
            type = MiuixToastType.WARNING,
            action = "重试检测",
            onAction = { onRefreshDiscovery(device.id.value) }
          )
        }
        device.discovery?.note?.takeIf { it.isNotBlank() }?.let { note ->
          Text(
            note,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.warning)
          )
        }
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          if (device.harnesses.isEmpty()) {
            HyperRow(title = "没有可用的 Harness", summary = "刷新发现，或检查这台设备上的服务是否已安装")
          } else {
            device.harnesses.forEachIndexed { index, slot ->
              if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
              HarnessRow(slot, device, controller, onOpenHarness, onAddHarness)
            }
          }
        }
      }
      item {
        HyperGroupTitle("设备设置")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          if (device.enabled && device.attachedCount > 0) {
            HyperRow(
              title = "重试连接",
              summary = "重新连接此设备上已启用的 Harness",
              trailing = { DropdownChevron() },
              onClick = { controller.retrySshDevice(deviceId) }
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          }
          HyperRow(
            title = "编辑 SSH 连接",
            summary = "验证方式：${sshDevice.auth.label} · 地址、端口、凭据与主机指纹",
            trailing = { DropdownChevron() },
            onClick = { onEditSsh(deviceId) }
          )
          boundSshKey?.let { key ->
            val unlocked = controller.isSshKeyUnlocked(key)
            val gated = key.biometricProtected ||
              key.passphrasePolicy == SshPassphrasePolicy.BIOMETRIC ||
              (key.passphrasePolicy == SshPassphrasePolicy.PROMPT && key.encrypted)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(
              title = "SSH 密钥 · ${key.name}",
              summary = if (gated && !unlocked) "当前未解锁（点击解锁并重连） · ${key.summary}" else key.summary,
              trailing = { DropdownChevron() },
              onClick = {
                if (gated && !unlocked) unlockKeyTarget = key else onOpenKeychain(key.id)
              }
            )
          }
        }
      }
      item {
        HyperGroupTitle("危险操作")
        HyperButton(
          text = "移除这台设备",
          variant = HyperButtonVariant.DESTRUCTIVE,
          onClick = { confirmingDelete = true },
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }

  if (confirmingDelete) {
    SuperDialog(title = "移除 ${device.name}？", show = true, onDismissRequest = { confirmingDelete = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SSH · ${device.endpoint}")
        Text("会删除这台设备的本机 SSH 配置、加密凭据和 ${device.attachedCount} 条 Harness 记录；远端进程、任务与文件不受影响。")
        DialogActions("取消", { confirmingDelete = false }, "确认移除", danger = true) {
          confirmingDelete = false
          onDeleteDevice(device.id.value)
        }
      }
    }
  }

  if (confirmingFingerprintReset) {
    SuperDialog(title = "重置主机指纹？", show = true, onDismissRequest = { confirmingFingerprintReset = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("下次连接或发现时会重新核对并固定远端 SSH 公钥指纹。仅在主机重装系统或更换主机密钥后操作。")
        DialogActions("取消", { confirmingFingerprintReset = false }, "确认重置", danger = true) {
          confirmingFingerprintReset = false
          fingerprintCopied = false
          controller.resetSshDeviceHostKey(deviceId)
        }
      }
    }
  }

  unlockKeyTarget?.let { key ->
    SshKeyUnlockDialog(
      profile = key,
      controller = controller,
      onDismiss = { unlockKeyTarget = null },
      onUnlocked = {
        unlockKeyTarget = null
        controller.retrySshDevice(deviceId)
      }
    )
  }
}

@Composable
private fun HarnessRow(
  slot: HarnessSlot,
  device: DeviceView,
  controller: LagoonController,
  onOpenHarness: (String) -> Unit,
  onAddHarness: (DeviceView, HarnessSlot) -> Unit
) {
  val leafId = slot.harnessId?.value
  val canStartOpenCode = device.enabled && slot.protocol == HarnessProtocol.OPENCODE_NATIVE &&
    (slot.availability.listening == DiscoveryValue.NO || (!slot.online && slot.attached && slot.enabled))
  if (leafId != null) {
    val dotColor = when {
      !device.enabled || !slot.enabled -> HyperTheme.colors.textTertiary
      slot.online -> HyperTheme.colors.success
      slot.linkStatus?.phase == SshLinkPhase.FAILED -> HyperTheme.colors.error
      slot.linkStatus?.phase == SshLinkPhase.CONNECTING || slot.linkStatus?.phase == SshLinkPhase.RECONNECTING -> HyperTheme.colors.warning
      else -> HyperTheme.colors.textTertiary
    }
    HyperRow(
      title = slot.name,
      summary = buildString {
        append(if (slot.enabled) "已启用" else "已停用")
        if (!device.enabled) append(" · 设备已停用")
        else if (slot.online) append(" · 在线")
        else if (slot.linkStatus?.phase == SshLinkPhase.CONNECTING || slot.linkStatus?.phase == SshLinkPhase.RECONNECTING) append(" · 连接中")
        else if (slot.linkStatus?.error != null) append(" · ${slot.linkStatus.error.display().lineSequence().firstOrNull().orEmpty()}")
        if (slot.reason.isNotBlank()) append(" · ${slot.reason}")
      },
      leading = { StatusDot(dotColor) },
      showChevron = true,
      onClick = { onOpenHarness(leafId) },
      trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (canStartOpenCode) {
            HyperTextAction(
              text = "启动服务",
              onClick = { controller.startOpenCodeServiceOnDevice(device.id.value) }
            )
          }
          HyperSwitch(
            checked = slot.enabled,
            onCheckedChange = { controller.setHarnessEnabled(leafId, it) }
          )
        }
      }
    )
  } else {
    HyperRow(
      title = slot.name,
      summary = slot.reason.ifBlank { if (slot.attachable) "尚未添加" else "不可用" },
      trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (canStartOpenCode) {
            HyperTextAction(
              text = "启动服务",
              onClick = { controller.startOpenCodeServiceOnDevice(device.id.value) }
            )
          }
          if (slot.attachable) HyperTextAction(
            text = "添加",
            enabled = device.enabled,
            onClick = { onAddHarness(device, slot) }
          )
        }
      }
    )
  }
}

/**
 * Harness 详情：单条 Harness 的专属配置（自定义名称、通知、新会话默认值、模型显示，以及 DSH / OpenCode 专属配置）。
 * 它挂在设备详情之上，不再提供“编辑设备 SSH 地址”的环状入口（返回一层即是设备详情）。
 */
@Composable
internal fun HarnessDetailScreen(
  state: LagoonState,
  controller: LagoonController,
  leafId: String,
  onBack: () -> Unit,
  onManageModels: (String) -> Unit
) {
  val profile = state.profiles.firstOrNull { it.id == leafId }
  if (profile == null) {
    LaunchedEffect(leafId) { onBack() }
    return
  }
  val device = profile.deviceId?.let { id -> state.devices.firstOrNull { it.id == id } }
  val harness = device?.harnesses?.firstOrNull { it.id == leafId }
  var confirmingDelete by remember(leafId) { mutableStateOf(false) }
  var showDefaults by remember(leafId) { mutableStateOf(false) }
  var showRenameDialog by remember(leafId) { mutableStateOf(false) }
  var showDshConfigDialog by remember(leafId) { mutableStateOf(false) }
  var dshDialog by remember(leafId) { mutableStateOf<String?>(null) }
  val settingsAccess = deviceSettingsAccess(profile)
  val memberships = state.deviceGroups.filter { profile.deviceId != null && profile.deviceId in it.deviceIds }
  val connected = if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true
  val blocked = when {
    device != null && !device.enabled -> "所属设备已停用"
    !profile.enabled -> "此 Harness 已停用"
    else -> ""
  }
  // 覆盖全部 5 种协议的实时 SSH 链接状态。
  val linkFlow = remember(leafId) { controller.harnessLinkStatus(leafId) }
  val sshStatus = linkFlow?.collectAsState()?.value ?: state.harnessLinkStatuses[leafId]
  val sshFailure = sshStatus?.error?.takeIf {
    sshStatus.phase == SshLinkPhase.FAILED || sshStatus.phase == SshLinkPhase.RECONNECTING
  }

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(profile.name, onBack, subtitle = {
      StatusDot(
        when {
          blocked.isNotBlank() -> HyperTheme.colors.textTertiary
          connected -> HyperTheme.colors.success
          sshStatus?.phase == SshLinkPhase.FAILED -> HyperTheme.colors.error
          sshStatus?.phase == SshLinkPhase.CONNECTING || sshStatus?.phase == SshLinkPhase.RECONNECTING -> HyperTheme.colors.warning
          else -> HyperTheme.colors.textTertiary
        }
      )
      Text(
        when {
          blocked.isNotBlank() -> blocked
          connected -> "在线"
          sshStatus?.phase == SshLinkPhase.CONNECTING || sshStatus?.phase == SshLinkPhase.RECONNECTING -> "连接中…"
          sshStatus?.phase == SshLinkPhase.FAILED -> "连接异常"
          else -> "未连接"
        },
        style = MiuixTheme.textStyles.footnote1.copy(color = HyperTheme.colors.textSecondary)
      )
    })
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        HyperGroupTitle("Harness")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperSwitchRow(
            title = "启用此 Harness",
            summary = if (blocked.isBlank()) "停用后不建立它的连接、不订阅事件、不推送通知；资料与缓存保留"
            else "$blocked；开关保留，恢复后生效",
            checked = profile.enabled,
            onCheckedChange = { controller.setHarnessEnabled(profile.id, it) }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "显示名称",
            summary = harness?.label?.takeIf(String::isNotBlank) ?: "默认使用所属设备名（${device?.name ?: profile.name}）",
            trailing = { DropdownChevron() },
            onClick = { showRenameDialog = true }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "协议", value = profile.connectionTypeLabel)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "所属设备", summary = device?.let { "${it.name} · ${it.address}" } ?: profile.endpointSummary)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "SSH 通道状态",
            summary = when {
              sshStatus == null -> "尚未建立 SSH 连接（启用后会按需连接）"
              sshFailure != null -> sshFailure.display()
              sshStatus.detail.isNotBlank() -> sshStatus.detail
              else -> sshStatus.phase.name.lowercase()
            },
            trailing = {
              if (sshFailure != null && profile.deviceId != null) {
                HyperButton(
                  text = "重试",
                  onClick = { controller.retrySshDevice(profile.deviceId) }
                )
              }
            }
          )
        }
      }

      if (profile.protocol == HarnessProtocol.DSH) {
        val dshConfig = (profile.config as? HarnessConfig.Dsh) ?: HarnessConfig.Dsh()
        item {
          HyperGroupTitle("DSH 启动配置")
          HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
            HyperRow(
              title = "启动命令与工作目录",
              summary = buildString {
                append(dshConfig.launchCommand)
                dshConfig.workingDirectory?.takeIf(String::isNotBlank)?.let { append("\n工作目录：$it") }
              },
              trailing = { DropdownChevron() },
              onClick = { showDshConfigDialog = true }
            )
          }
        }
      }

      if (profile.protocol == HarnessProtocol.OPENCODE_NATIVE) {
        val openCodeConfig = profile.config as? HarnessConfig.OpenCodeNative
        item {
          HyperGroupTitle("OpenCode 服务")
          HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
            val transportError = state.serverSnapshots()[profile.id]?.transportError?.takeIf { it.isNotBlank() }
            HyperRow(
              title = "注册路径",
              summary = openCodeConfig?.registrationPath?.takeIf(String::isNotBlank) ?: "远端默认路径（~/.local/state/opencode/service.json）"
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(
              title = "服务状态",
              summary = transportError ?: "应用只附着设备上运行中的服务，不会接管或终止它"
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(
              title = "启动或重启服务",
              summary = "在设备上运行 opencode service，再重新读取注册并重连",
              trailing = { HyperTextAction("执行", onClick = { controller.restartOpenCodeService(profile.id) }) }
            )
          }
        }
      }

      item {
        HyperGroupTitle("使用方式")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "所属设备组",
            summary = if (memberships.isEmpty()) "所属设备尚未加入设备组；返回设备页管理"
            else "已加入 ${memberships.size} 个设备组"
          )
          memberships.forEach { group ->
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(title = group.name, value = "成员")
          }
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperSwitchRow(
            title = "任务通知",
            summary = "任务完成或需要你处理时通知",
            checked = profile.notifications,
            onCheckedChange = { enabled ->
              controller.updateHarnessConfig(profile.id, notifications = enabled)
            }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "新会话默认值",
            summary = "仅作用于此 Harness 上新建的会话，不切换当前会话",
            trailing = { DropdownChevron() },
            onClick = { showDefaults = true }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "模型显示",
            summary = "仅筛选此 Harness 在本机展示的模型，不改变服务器",
            trailing = { DropdownChevron() },
            onClick = { onManageModels(profile.id) }
          )
        }
      }

      if (settingsAccess.dshProtocol) item {
        HyperGroupTitle("主机高级功能")
        Text(
          "通过当前 SSH 隧道中的 DSH 读取；是否支持及能否修改由 Host 决定。",
          Modifier.padding(horizontal = 6.dp),
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "主机扩展",
            summary = "查看插件包与插件条目；仅可修改 Host 允许的开关",
            trailing = { DropdownChevron() },
            onClick = { dshDialog = "extensions" }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "模型密钥",
            summary = "只显示配置状态，不回显密钥",
            trailing = { DropdownChevron() },
            onClick = { dshDialog = "model-key" }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "自定义模型服务商",
            summary = "配置协议、模型与凭据引用",
            trailing = { DropdownChevron() },
            onClick = { dshDialog = "provider" }
          )
        }
      }

      item {
        HyperGroupTitle("危险操作")
        HyperButton(
          text = "从此设备移除这个 Harness",
          variant = HyperButtonVariant.DESTRUCTIVE,
          onClick = { confirmingDelete = true },
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }

  if (showDefaults) {
    HyperBottomSheet(title = "新会话默认值", show = true, onDismissRequest = { showDefaults = false }) {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
          "这些选择只用于 ${profile.name} 上新建的会话，不会切换当前连接。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
        Spacer(Modifier.height(12.dp))
        NewSessionDefaultsCard(state, controller, profile.id)
      }
    }
  }

  if (showRenameDialog) {
    var editedLabel by remember(leafId) { mutableStateOf(harness?.label.orEmpty()) }
    SuperDialog(title = "自定义 Harness 名称", show = true, onDismissRequest = { showRenameDialog = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "留空则恢复跟随所属设备名称（${device?.name ?: profile.name}）。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
        TextField(
          value = editedLabel,
          onValueChange = { editedLabel = it },
          label = "显示名称（可选）",
          modifier = Modifier.fillMaxWidth()
        )
        DialogActions("取消", { showRenameDialog = false }, "保存") {
          val trimmed = editedLabel.trim()
          controller.updateHarnessConfig(
            harnessId = profile.id,
            label = trimmed.takeIf(String::isNotBlank),
            clearLabel = trimmed.isBlank()
          )
          showRenameDialog = false
        }
      }
    }
  }

  if (showDshConfigDialog && profile.protocol == HarnessProtocol.DSH) {
    val current = (profile.config as? HarnessConfig.Dsh) ?: HarnessConfig.Dsh()
    var launchCommand by remember(leafId, current) { mutableStateOf(current.launchCommand) }
    var workingDir by remember(leafId, current) { mutableStateOf(current.workingDirectory.orEmpty()) }
    var configError by remember(leafId) { mutableStateOf<String?>(null) }
    SuperDialog(title = "DSH 启动配置", show = true, onDismissRequest = { showDshConfigDialog = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "仅作用于此 DSH Harness，修改后只重建 DSH 通道，不影响同设备上的其他 SSH 会话。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
        TextField(
          value = launchCommand,
          onValueChange = { launchCommand = it; configError = null },
          label = "启动命令（默认 $DEFAULT_DSH_LAUNCH_COMMAND）",
          modifier = Modifier.fillMaxWidth()
        )
        TextField(
          value = workingDir,
          onValueChange = { workingDir = it; configError = null },
          label = "远端工作目录（可选，例如 ~/code）",
          modifier = Modifier.fillMaxWidth()
        )
        configError?.let { Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error)) }
        DialogActions("取消", { showDshConfigDialog = false }, "保存") {
          try {
            val next = HarnessConfig.Dsh(
              launchCommand = launchCommand.trim().ifBlank { DEFAULT_DSH_LAUNCH_COMMAND },
              workingDirectory = workingDir.trim().takeIf(String::isNotBlank)
            )
            controller.updateHarnessConfig(profile.id, config = next)
            showDshConfigDialog = false
          } catch (error: Exception) {
            configError = error.message ?: "配置无效"
          }
        }
      }
    }
  }

  key(profile) {
    when (dshDialog) {
      "extensions" -> if (settingsAccess.dshProtocol) DshExtensionsDialog(profile, controller) { dshDialog = null }
      "model-key" -> if (settingsAccess.dshProtocol) DshModelKeyDialog(profile, controller) { dshDialog = null }
      "provider" -> if (settingsAccess.dshProtocol) DshProviderDialog(profile, controller) { dshDialog = null }
      else -> Unit
    }
  }

  if (confirmingDelete) {
    SuperDialog(title = "移除 ${profile.name}？", show = true, onDismissRequest = { confirmingDelete = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${profile.connectionTypeLabel} · ${profile.endpointSummary}")
        Text("只删除这条 Harness 的本机记录与缓存；同一设备上的其他 Harness、设备组与远端进程、会话均不受影响。设备本身保留。")
        DialogActions("取消", { confirmingDelete = false }, "确认移除", danger = true) {
          confirmingDelete = false
          controller.deleteHarness(profile.id)
        }
      }
    }
  }
}

/** Protocol support is not a grant of Host permissions; inventory/errors remain authoritative. */
internal data class DeviceSettingsAccess(
  val dshProtocol: Boolean
)

internal fun deviceSettingsAccess(profile: ServerProfile): DeviceSettingsAccess = DeviceSettingsAccess(
  dshProtocol = profile.protocol == HarnessProtocol.DSH
)

@Composable
private fun rememberDevices(
  state: LagoonState,
  controller: LagoonController
): List<DeviceView> = remember(
  state.devices, state.discoveryRevision, state.allServerSnapshots, state.harnessLinkStatuses, state.serverId, state.connected
) {
  controller.deviceViews(
    devices = state.devices,
    isOnline = { id ->
      if (id == state.serverId) state.connected else state.allServerSnapshots[id]?.connected == true
    },
    linkStatuses = state.harnessLinkStatuses
  )
}

private fun discoverySummary(device: DeviceView): String {
  val discovery = device.discovery ?: return "尚未发现；点「刷新发现」后才会连接一次"
  val partial = if (discovery.completeness != DiscoveryCompleteness.COMPLETE) " · 部分结果" else ""
  if (discovery.observedAtMs <= 0L) return "尚未完成完整发现$partial"
  val age = (System.currentTimeMillis() - discovery.observedAtMs).coerceAtLeast(0L)
  val whenText = when {
    age < 60_000 -> "刚刚"
    age < 3_600_000 -> "${age / 60_000} 分钟前"
    age < 86_400_000 -> "${age / 3_600_000} 小时前"
    else -> "${age / 86_400_000} 天前"
  }
  val stale = if (discovery.isFresh(System.currentTimeMillis())) "" else " · 已过期"
  return "$whenText · ${discovery.harnesses.size} 个结果$partial$stale"
}
