package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Device list: identity and health only. Configuration lives in the pushed device detail.
 *
 * 这一页只负责“哪台设备”，不负责“打开哪一页”：详情是路由上的一层（[SessionNavigation.openDevice]），
 * 由根 AnimatedContent 渲染，所以进入与返回都有与首页进设备页完全相同的整页过渡。
 */
@Composable
internal fun DeviceManagementScreen(
  state: LagoonState,
  controller: LagoonController,
  onBack: () -> Unit,
  onAddDevice: () -> Unit,
  onEditGroup: (String?) -> Unit,
  onOpenDevice: (String) -> Unit,
  onManageHub: () -> Unit,
  onManageModels: () -> Unit
) {
  val graph by controller.connectionGraph.collectAsState()
  val profilesById = state.profiles.associateBy { it.id }
  val hostsBySource = graph?.hosts.orEmpty().groupBy { it.sourceId }
  val bindingsByHost = graph?.bindings.orEmpty().groupBy { it.hostBindingId }
  val graphProfileIds = graph?.bindings.orEmpty().map { it.id }.toSet()
  val remainingProfiles = state.profiles.filter { it.id !in graphProfileIds }
  val unboundSshHosts = state.sshHosts.filter { ssh ->
    state.profiles.none { it.sshHostId == ssh.id } &&
      graph?.sources.orEmpty().none { it.sshHostConfigId == ssh.id }
  }
  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar("设备", onBack, actions = {
      CircleIconButton(MiuixIcons.Add, "添加设备", onAddDevice)
    })
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        Text(
          "${state.profiles.size} 个 Harness · ${graph?.hosts?.size ?: 0} 个主机绑定",
          Modifier.padding(horizontal = 6.dp),
          style = MiuixTheme.textStyles.body2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
      if (state.deviceGroupReset) item {
        HyperInlineNotice(
          message = "旧版设备分组已重置；设备、凭据和会话均已保留。",
          type = MiuixToastType.INFO,
          action = "知道了",
          onAction = { controller.dismissDeviceGroupReset() }
        )
      }
      item {
        HyperGroupTitle("接入源管理")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "Remote Hub · 远程中枢",
            summary = "登录或管理账号、主机与原生 DSH / OpenCode 接入",
            showChevron = true,
            onClick = onManageHub
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "添加或管理 SSH 接入源",
            summary = "共用主机配置，再逐个选择原生 Harness",
            showChevron = true,
            onClick = onAddDevice
          )
        }
      }
      if (graph != null) {
        items(graph?.sources.orEmpty(), key = { "source:${it.id}" }) { source ->
          val hosts = hostsBySource[source.id].orEmpty()
          HyperGroupTitle(when (source.type) {
            ConnectionSourceType.HUB -> "Remote Hub · ${source.name}"
            ConnectionSourceType.SSH -> "SSH · ${source.name}"
            ConnectionSourceType.DIRECT -> "兼容直连 · ${source.name}"
          })
          HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
            HyperSwitchRow(
              title = "启用接入源",
              summary = listOfNotNull(source.endpoint, source.account).joinToString(" · ") + "；不改主机与 Harness 自身选择",
              checked = source.enabled,
              onCheckedChange = { controller.setConnectionSourceEnabled(source.id, it) }
            )
            if (source.type != ConnectionSourceType.DIRECT) {
              HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
              HyperRow(
                title = if (source.type == ConnectionSourceType.HUB) "管理中枢与主机" else "配置 SSH 与发现 Harness",
                summary = if (source.type == ConnectionSourceType.SSH) "进入添加页，选择已保存的 SSH 主机" else "查看账号或接入更多主机",
                showChevron = true,
                onClick = if (source.type == ConnectionSourceType.HUB) onManageHub else onAddDevice
              )
            }
            hosts.forEach { host ->
              val bindings = bindingsByHost[host.id].orEmpty()
              HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
              Column(Modifier.padding(start = 12.dp)) {
                HyperSwitchRow(
                  title = "主机 · ${host.name}",
                  summary = "${bindings.size} 个 Harness · " + if (source.enabled) "只影响此主机绑定" else "接入源已停用，保留此主机自身选择",
                  checked = host.enabled,
                  onCheckedChange = { controller.setConnectionHostEnabled(host.id, it) }
                )
                Column(Modifier.padding(start = 12.dp)) {
                  bindings.forEach { binding ->
                    val profile = profilesById[binding.id]
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
                    if (profile != null) {
                      DeviceHarnessRow(profile, state, onOpenDevice)
                    } else {
                      // Graph-only metadata is never used as a new canonical connection route.
                      HyperRow(title = binding.name, summary = "连接记录暂不可用；未尝试连接", value = "未载入")
                    }
                  }
                  if (bindings.isEmpty()) HyperRow(
                    title = "尚未选择 Harness",
                    summary = if (source.type == ConnectionSourceType.HUB) "管理中枢以选择此主机的服务" else "进入接入源配置发现服务",
                    showChevron = source.type != ConnectionSourceType.DIRECT,
                    onClick = when (source.type) {
                      ConnectionSourceType.HUB -> onManageHub
                      ConnectionSourceType.SSH -> onAddDevice
                      ConnectionSourceType.DIRECT -> null
                    }
                  )
                }
              }
            }
            if (hosts.isEmpty()) HyperRow(title = "尚未绑定主机", summary = "配置已保留，选择主机后再接入 Harness")
          }
        }
      }
      if (remainingProfiles.isNotEmpty()) item {
        HyperGroupTitle(if (graph == null) "Harness · 连接图暂不可用" else "其他已保存的 Harness")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          remainingProfiles.forEachIndexed { index, profile ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            DeviceHarnessRow(profile, state, onOpenDevice)
          }
        }
      }
      if (unboundSshHosts.isNotEmpty()) item {
        HyperGroupTitle("SSH · 尚未接入 Harness")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          unboundSshHosts.forEachIndexed { index, host ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(
              title = host.name,
              summary = "${host.address} · 已保存配置；进入添加页选择此 SSH 主机",
              showChevron = true,
              onClick = onAddDevice
            )
          }
        }
      }
      if (state.profiles.isEmpty() && graph?.sources.isNullOrEmpty() && unboundSshHosts.isEmpty()) item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(title = "还没有接入源", summary = "添加 Remote Hub、SSH 主机，或展开高级 HTTP 直连", showChevron = true, onClick = onAddDevice)
        }
      }
      item {
        HyperGroupTitle("设备组")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          state.deviceGroups.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(
              title = group.name,
              summary = "${group.memberIds.size} 台设备 · 只影响本机展示与连接方式",
              trailing = { DropdownChevron() },
              onClick = { onEditGroup(group.id) }
            )
          }
          if (state.deviceGroups.isEmpty()) HyperRow(title = "还没有设备组", summary = "把需要一起使用的设备放进一个范围")
        }
        HyperButton(
          text = "新建设备组",
          variant = HyperButtonVariant.SECONDARY,
          enabled = state.profiles.isNotEmpty(),
          onClick = { onEditGroup(null) },
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
      }
    }
  }
}

/**
 * 设备详情：设备级配置的唯一入口。它和列表一样是路由上的一层，所以压栈/出栈都走同一套整页过渡，
 * 侧滑返回时下面垫着的是真实的设备列表。
 */
@Composable
internal fun DeviceDetailScreen(
  state: LagoonState,
  controller: LagoonController,
  deviceId: String,
  onBack: () -> Unit,
  onEditProfile: (ServerProfile) -> Unit,
  onManageHub: () -> Unit,
  onManageModels: () -> Unit
) {
  val graph by controller.connectionGraph.collectAsState()
  val profile = state.profiles.firstOrNull { it.id == deviceId }
  if (profile == null) {
    // 设备在别处被删掉：这一层已经没有内容，退回列表，而不是把一张空白页留在栈上。
    LaunchedEffect(deviceId) { onBack() }
    return
  }
  var confirmingDelete by remember(deviceId) { mutableStateOf(false) }
  var showDefaults by remember(deviceId) { mutableStateOf(false) }
  var showNative by remember(deviceId) { mutableStateOf(false) }
  var remoteDialog by remember(deviceId) { mutableStateOf<String?>(null) }
  val settingsAccess = deviceSettingsAccess(profile)
  val memberships = state.deviceGroups.filter { profile.id in it.memberIds }
  val binding = graph?.bindings?.firstOrNull { it.id == profile.id }
  val parentHost = graph?.hosts?.firstOrNull { it.id == binding?.hostBindingId }
  val parentSource = graph?.sources?.firstOrNull { it.id == parentHost?.sourceId }

  fun savePreference(next: ServerProfile) {
    controller.saveServer(next, password = null, cookie = null, credentialUsername = next.username, connect = false)
  }

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(profile.name, onBack, subtitle = {
      StatusDot(if (deviceConnected(profile, state)) HyperTheme.colors.success else HyperTheme.colors.textTertiary)
      Text(deviceStatusLabel(profile, state), style = MiuixTheme.textStyles.footnote1.copy(color = HyperTheme.colors.textSecondary))
    })
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        HyperGroupTitle("设备状态")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperSwitchRow(
            title = "启用此 Harness",
            summary = if (profile.parentEnabled) "只控制此 Harness；停用不删除凭据、草稿或会话"
              else "自身选择已保留，但所属接入源或主机已停用，当前不会连接",
            checked = profile.enabled,
            onCheckedChange = { controller.setProfileEnabled(profile.id, it) }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "连接地址", summary = profile.endpointSummary)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "类型", value = profile.connectionTypeLabel)
        }
      }
      if (parentSource != null && parentHost != null) item {
        HyperGroupTitle("连接层级")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperSwitchRow(title = "启用接入源", summary = "${parentSource.type.wire} · ${parentSource.name}；影响这个源下所有主机，不改子项选择",
            checked = parentSource.enabled,
            onCheckedChange = { controller.setConnectionSourceEnabled(parentSource.id, it) })
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperSwitchRow(title = "启用此主机绑定", summary = "${parentHost.name}；影响该绑定下的 Harness，不删除远端任务",
            checked = parentHost.enabled,
            onCheckedChange = { controller.setConnectionHostEnabled(parentHost.id, it) })
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "实际连接许可", value = if (profile.effectiveEnabled) "允许" else "已暂停")
        }
      }
      item {
        HyperGroupTitle("使用方式")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "组合成员",
            summary = if (memberships.isEmpty()) "尚未加入组合；返回设备页管理组合，不改变此 Harness 的接入源"
              else "已加入 ${memberships.size} 个组合；返回设备页管理成员，不改变接入源"
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
            onCheckedChange = { savePreference(profile.copy(notifications = it)) }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "新会话默认值",
            summary = "只保存在这台手机，不改变当前会话",
            trailing = { DropdownChevron() },
            onClick = { showDefaults = true }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "模型显示",
            summary = "只筛选这台手机展示的模型，不改变服务器，也不切换当前会话",
            trailing = { DropdownChevron() },
            onClick = onManageModels
          )
        }
      }
      item {
        HyperGroupTitle("连接设置")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          if (profile.remoteServiceId == null) HyperRow(title = "编辑连接信息", summary = "地址、账号、认证与传输安全", trailing = { DropdownChevron() }, onClick = { onEditProfile(profile) })
          else HyperRow(title = "原生服务路由", summary = "Host 管理服务地址与认证；本机只保存服务标识", value = "Remote")
          if (settingsAccess.remoteHub) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(title = "发现 Host 原生 Harness", summary = "只读发现；逐个选择 OpenCode 原生服务", showChevron = true, onClick = { showNative = true })
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(title = "中枢与远程主机", summary = "查看账号、接入其他主机或退出中枢", trailing = { DropdownChevron() }, onClick = onManageHub)
          }
          if (settingsAccess.remoteDiagnostics) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(title = "网络与诊断", summary = "连接延迟、错误详情与重试", trailing = { DropdownChevron() }, onClick = { remoteDialog = "connection" })
          }
        }
      }
      if (settingsAccess.dshProtocol) item {
        HyperGroupTitle("主机高级功能")
        Text("通过当前 DSH 连接读取；是否支持及能否修改由 Host 决定。",
          Modifier.padding(horizontal = 6.dp), style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary))
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(title = "主机扩展", summary = "查看插件包与插件条目；仅可修改 Host 允许的开关", trailing = { DropdownChevron() }, onClick = { remoteDialog = "extensions" })
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "模型密钥", summary = "只显示配置状态，不回显密钥", trailing = { DropdownChevron() }, onClick = { remoteDialog = "model-key" })
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "自定义模型服务商", summary = "配置协议、模型与凭据引用", trailing = { DropdownChevron() }, onClick = { remoteDialog = "provider" })
        }
      }
      item {
        HyperGroupTitle("危险操作")
        HyperButton(
          text = "删除此设备",
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
        Text("这些选择只用于 ${profile.name} 上新建的会话，不会切换当前连接。", style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary))
        Spacer(Modifier.height(12.dp))
        NewSessionDefaultsCard(state, controller, profile.id)
      }
    }
  }

  if (showNative) RemoteNativeServicesSheet(controller, profile) { showNative = false }

  // A detail route can be reused for another profile: never carry inventories or keys across it.
  key(profile) {
    when (remoteDialog) {
      "connection" -> if (settingsAccess.remoteDiagnostics) RemoteConnectionDialog(profile, state, controller) { remoteDialog = null }
      "extensions" -> if (settingsAccess.dshProtocol) RemoteExtensionsDialog(profile, controller) { remoteDialog = null }
      "model-key" -> if (settingsAccess.dshProtocol) RemoteModelKeyDialog(profile, controller) { remoteDialog = null }
      "provider" -> if (settingsAccess.dshProtocol) RemoteProviderDialog(profile, controller) { remoteDialog = null }
      else -> Unit
    }
  }

  if (confirmingDelete) {
    val type = if (profile.sshHostId != null) "SSH 主机" else profile.kind.label
    SuperDialog(title = "删除 ${profile.name}？", show = true, onDismissRequest = { confirmingDelete = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$type · ${profile.endpointSummary}")
        Text("只删除这个 Harness 的本机连接资料；同一主机上的其他 Harness 与远端进程、会话均不受影响。")
        DialogActions("取消", { confirmingDelete = false }, "确认删除", danger = true) {
          controller.deleteServer(profile.id)
          confirmingDelete = false
          onBack()
        }
      }
    }
  }
}

/** Protocol support is not a grant of Host permissions; inventory/errors remain authoritative. */
internal data class DeviceSettingsAccess(
  val dshProtocol: Boolean,
  val remoteHub: Boolean,
  val remoteDiagnostics: Boolean
)

internal fun deviceSettingsAccess(profile: ServerProfile): DeviceSettingsAccess = DeviceSettingsAccess(
  dshProtocol = profile.kind == ServerKind.DSH || profile.kind == ServerKind.DSH_REMOTE,
  remoteHub = profile.remoteHostId != null,
  // This dialog measures an encrypted Remote RPC, not direct HTTP or an SSH tunnel.
  remoteDiagnostics = profile.kind == ServerKind.DSH_REMOTE
)

@Composable
private fun DeviceHarnessRow(profile: ServerProfile, state: LagoonState, onOpenDevice: (String) -> Unit) {
  HyperRow(
    title = profile.name,
    summary = "${profile.kind.label} · ${profile.endpointSummary}" + when {
      !profile.enabled -> " · Harness 已停用"
      !profile.parentEnabled -> " · 接入源或主机已停用"
      else -> ""
    },
    value = deviceStatusLabel(profile, state),
    showChevron = true,
    onClick = { onOpenDevice(profile.id) }
  )
}

private fun deviceSnapshot(profile: ServerProfile, state: LagoonState): ServerOverviewSnapshot? =
  state.serverSnapshots()[profile.id]

private fun deviceConnected(profile: ServerProfile, state: LagoonState): Boolean =
  profile.effectiveEnabled && deviceSnapshot(profile, state)?.connected == true

private fun deviceStatusLabel(profile: ServerProfile, state: LagoonState): String {
  val snapshot = deviceSnapshot(profile, state)
  val authExpired = snapshot?.error?.let { message ->
    message.contains("重新登录") || message.contains("凭据已过期") || message.contains("登录已失效")
  } == true
  return resolveDeviceStatusLabel(
    enabled = profile.effectiveEnabled,
    connected = deviceConnected(profile, state),
    loading = snapshot?.loading == true,
    hasError = !snapshot?.error.isNullOrBlank(),
    authExpired = authExpired
  )
}
