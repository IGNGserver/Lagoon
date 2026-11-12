package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ConnectionGraph
import com.igng.opencode.lagoon.core.DeviceAccess
import com.igng.opencode.lagoon.core.DeviceView
import com.igng.opencode.lagoon.core.HarnessSlot
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.connectionTypeLabel
import com.igng.opencode.lagoon.core.endpointSummary
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备列表：一个设备一行，接入方式只是它的属性。
 *
 * 这一页只负责“有哪几台设备、哪台开着”，Harness 的开关在设备页，Harness 自己的配置在 Harness 详情。
 * 设备详情与 Harness 详情都是路由上的一层（见 `SessionNavigation.openDevice` / `openHarness`），
 * 由根 `AnimatedContent` 渲染，所以进入与返回都有与首页进设备页完全相同的整页过渡。
 */
@Composable
internal fun DeviceManagementScreen(
  state: LagoonState,
  controller: LagoonController,
  onBack: () -> Unit,
  onAddDevice: () -> Unit,
  onEditGroup: (String?) -> Unit,
  onOpenDevice: (String) -> Unit,
  onManageHub: () -> Unit
) {
  val graph by controller.connectionGraph.collectAsState()
  val devices = rememberDevices(state, controller, graph)
  val catalog = controller.activeRemoteCatalog()
  val hubDevices = devices.filter { it.access == DeviceAccess.HUB }
  val sshDevices = devices.filter { it.access == DeviceAccess.SSH }
  val directDevices = devices.filter { it.access == DeviceAccess.DIRECT }
  val enabledHarnessCount = devices.sumOf { it.enabledCount }

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
          "${devices.size} 台设备 · $enabledHarnessCount 个 Harness 已启用",
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
        HyperGroupTitle("接入源")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = catalog?.account ?: "登录 Remote Hub",
            summary = when {
              catalog == null -> "登录后可以接入这个账号下的开发机；Host 与 Harness 分开选择"
              catalog.signedOut -> "${catalog.origin} · 已退出登录，${catalog.hosts.size} 台主机保留"
              else -> "${catalog.origin} · ${catalog.hosts.size} 台可接入主机"
            },
            showChevron = true,
            onClick = onManageHub
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "添加设备",
            summary = "经由中枢选主机，或经由 SSH 填地址；两条路径产出同一种设备",
            showChevron = true,
            onClick = onAddDevice
          )
        }
      }
      deviceSection("由中枢管理", hubDevices, state, controller, onOpenDevice)
      deviceSection("由 SSH 管理", sshDevices, state, controller, onOpenDevice)
      if (directDevices.isNotEmpty()) deviceSection("直连（兼容）", directDevices, state, controller, onOpenDevice)
      if (devices.isEmpty()) item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(title = "还没有设备", summary = "经由中枢接入一台开发机，或添加一个 SSH 设备", showChevron = true, onClick = onAddDevice)
        }
      }
      item {
        HyperGroupTitle("设备组")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          state.deviceGroups.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            val memberDevices = devices.count { it.id.value in group.memberIds }
            HyperRow(
              title = group.name,
              summary = if (memberDevices > 0) "$memberDevices 台设备 · 只影响本机展示与范围"
                else "设备已移除；分组保留",
              trailing = { DropdownChevron() },
              onClick = { onEditGroup(group.id) }
            )
          }
          if (state.deviceGroups.isEmpty()) HyperRow(title = "还没有设备组", summary = "把需要一起使用的设备放进一个范围")
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

private fun androidx.compose.foundation.lazy.LazyListScope.deviceSection(
  title: String,
  devices: List<DeviceView>,
  state: LagoonState,
  controller: LagoonController,
  onOpenDevice: (String) -> Unit
) {
  if (devices.isEmpty()) return
  item { HyperGroupTitle(title) }
  item {
    HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
      devices.forEachIndexed { index, device ->
        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
        DeviceRow(device, controller, onOpenDevice)
      }
    }
  }
}

@Composable
private fun DeviceRow(device: DeviceView, controller: LagoonController, onOpenDevice: (String) -> Unit) {
  HyperRow(
    title = device.name,
    summary = buildString {
      append(device.access.label)
      append(" · ")
      append(if (device.attachedCount == 0) "未添加 Harness" else "${device.attachedCount} 个 Harness · ${device.enabledCount} 启用")
      val reason = device.blockedReason
      when {
        reason.isNotBlank() -> append(" · $reason")
        device.attachedCount == 0 -> append(" · 不占用连接")
        device.online -> append(" · 在线")
        else -> append(" · 未连接")
      }
    },
    leading = {
      StatusDot(
        when {
          !device.effectiveEnabled -> HyperTheme.colors.textTertiary
          device.online -> HyperTheme.colors.success
          else -> HyperTheme.colors.textTertiary
        }
      )
    },
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
 * 设备详情：设备级配置的唯一入口。它和列表一样是路由上的一层，所以压栈/出栈都走同一套整页过渡，
 * 侧滑返回时下面垫着的是真实的设备列表。
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
  onEditSsh: (String) -> Unit,
  onManageHub: () -> Unit,
  onDeleteDevice: (String) -> Unit
) {
  val graph by controller.connectionGraph.collectAsState()
  val devices = rememberDevices(state, controller, graph)
  val device = devices.firstOrNull { it.id.value == deviceId }
  if (device == null) {
    // 设备在别处被删掉：这一层已经没有内容，退回列表，而不是把一张空白页留在栈上。
    LaunchedEffect(deviceId) { onBack() }
    return
  }
  var confirmingDelete by remember(deviceId) { mutableStateOf(false) }
  val sshHostId = sshHostOfDevice(graph, device.id.value)
  val refreshing = state.deviceRefreshInFlight == deviceId

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(device.name, onBack, subtitle = {
      StatusDot(if (device.online) HyperTheme.colors.success else HyperTheme.colors.textTertiary)
      Text(
        when {
          !device.effectiveEnabled -> device.blockedReason
          device.attachedCount == 0 -> "已启用 · 未添加 Harness"
          device.online -> "在线"
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
        HyperGroupTitle("设备")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperSwitchRow(
            title = "启用此设备",
            summary = when {
              !device.sourceEnabled -> "所属接入源已暂停；打开只恢复本设备，仍需恢复接入源"
              device.enabled -> "停用后不再为这台设备建立中枢/SSH 连接，也不做发现；Harness 开关保留"
              else -> "已停用：不建立连接、不刷新；重新启用会立即连接一次"
            },
            checked = device.enabled,
            onCheckedChange = { controller.setDeviceEnabled(device.id.value, it) }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "接入方式", value = device.access.label)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "地址", summary = device.endpoint)
          device.fingerprint?.let { fingerprint ->
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(title = "主机指纹", summary = formatFingerprint(fingerprint))
          }
        }
      }
      item {
        HyperGroupTitle("Harness", trailing = {
          HyperTextAction(
            text = if (refreshing) "正在发现…" else "刷新发现",
            enabled = !refreshing && device.effectiveEnabled,
            onClick = { onRefreshDiscovery(device.id.value) }
          )
        })
        Text(
          discoverySummary(device) + when {
            !device.effectiveEnabled -> "；设备停用时只用上次结果，不会连接"
            device.attachedCount == 0 -> "；没有启用的 Harness 就不占用连接与流量"
            else -> ""
          },
          Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          if (device.harnesses.isEmpty()) {
            HyperRow(title = "没有可用的 Harness", summary = "刷新发现，或检查这台设备上的服务是否已运行")
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
          when {
            sshHostId != null -> HyperRow(
              title = "编辑 SSH 连接",
              summary = "地址、账号、凭据、启动命令与主机指纹",
              trailing = { DropdownChevron() },
              onClick = { onEditSsh(sshHostId) }
            )
            device.access == DeviceAccess.HUB -> HyperRow(
              title = "中枢与账号",
              summary = "查看账号、接入其他主机或退出中枢",
              trailing = { DropdownChevron() },
              onClick = onManageHub
            )
            else -> HyperRow(title = "连接信息", summary = "兼容直连资料在 Harness 详情里编辑")
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
        Text("${device.access.label} · ${device.endpoint}")
        Text("会删除这台设备的本机接入配置、身份和 ${device.attachedCount} 条 Harness 记录；远端任务与文件不受影响。")
        DialogActions("取消", { confirmingDelete = false }, "确认移除", danger = true) {
          confirmingDelete = false
          onDeleteDevice(device.id.value)
          onBack()
        }
      }
    }
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
  val leafId = slot.leafId?.value
  if (leafId != null) {
    HyperRow(
      title = slot.name,
      summary = buildString {
        append(if (slot.enabled) "已启用" else "已停用")
        if (!device.effectiveEnabled) append(" · 设备已停用")
        if (slot.reason.isNotBlank() && !slot.enabled) append(" · ${slot.reason}")
      },
      showChevron = true,
      onClick = { onOpenHarness(leafId) },
      trailing = {
        HyperSwitch(
          checked = slot.enabled,
          onCheckedChange = { controller.setProfileEnabled(leafId, it) }
        )
      }
    )
  } else {
    HyperRow(
      title = slot.name,
      summary = slot.reason.ifBlank { if (slot.attachable) "尚未添加" else "不可用" },
      trailing = {
        if (slot.attachable) HyperTextAction(
          text = "添加",
          enabled = device.effectiveEnabled,
          onClick = { onAddHarness(device, slot) }
        )
      }
    )
  }
}

/**
 * Harness 详情：一条 leaf 自己的配置。它挂在设备详情之上，是设备子树里最深的一层。
 */
@Composable
internal fun HarnessDetailScreen(
  state: LagoonState,
  controller: LagoonController,
  leafId: String,
  onBack: () -> Unit,
  onEditProfile: (ServerProfile) -> Unit,
  onManageHub: () -> Unit,
  onManageModels: () -> Unit
) {
  val graph by controller.connectionGraph.collectAsState()
  val profile = state.profiles.firstOrNull { it.id == leafId }
  if (profile == null) {
    LaunchedEffect(leafId) { onBack() }
    return
  }
  var confirmingDelete by remember(leafId) { mutableStateOf(false) }
  var showDefaults by remember(leafId) { mutableStateOf(false) }
  var showNative by remember(leafId) { mutableStateOf(false) }
  var remoteDialog by remember(leafId) { mutableStateOf<String?>(null) }
  val settingsAccess = deviceSettingsAccess(profile)
  val memberships = state.deviceGroups.filter { profile.id in it.memberIds }
  val device = rememberDevices(state, controller, graph).firstOrNull { candidate ->
    candidate.harnesses.any { it.leafId?.value == leafId }
  }
  val connected = if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true
  val blocked = when {
    device == null -> ""
    !device.sourceEnabled -> "接入源已暂停"
    !device.enabled -> "设备已停用"
    !profile.enabled -> "此 Harness 已停用"
    else -> ""
  }

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(profile.name, onBack, subtitle = {
      StatusDot(if (connected && blocked.isBlank()) HyperTheme.colors.success else HyperTheme.colors.textTertiary)
      Text(
        when {
          blocked.isNotBlank() -> blocked
          connected -> "在线"
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
              else "$blocked；开关保留，恢复父级后生效",
            checked = profile.enabled,
            onCheckedChange = { controller.setProfileEnabled(profile.id, it) }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "协议", value = profile.connectionTypeLabel)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "所属设备", summary = device?.name ?: "设备记录暂不可用")
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "连接地址", summary = profile.endpointSummary)
        }
      }
      item {
        HyperGroupTitle("使用方式")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "组合成员",
            summary = if (memberships.isEmpty()) "尚未加入组合；返回设备页管理组合" else "已加入 ${memberships.size} 个组合"
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
              controller.saveServer(profile.copy(notifications = enabled), password = null, cookie = null,
                credentialUsername = profile.username, connect = false)
            }
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
            summary = "只筛选这台手机展示的模型，不改变服务器",
            trailing = { DropdownChevron() },
            onClick = onManageModels
          )
        }
      }
      item {
        HyperGroupTitle("连接与高级功能")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          if (profile.remoteServiceId == null) HyperRow(
            title = "编辑连接信息",
            summary = "地址、账号与认证",
            trailing = { DropdownChevron() },
            onClick = { onEditProfile(profile) }
          ) else HyperRow(title = "原生服务路由", summary = "Host 管理服务地址与认证；本机只保存服务标识", value = "Remote")
          if (settingsAccess.remoteHub) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(title = "发现 Host 原生 Harness", summary = "只读发现；在设备页逐个启用", showChevron = true, onClick = { showNative = true })
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
        Text("这些选择只用于 ${profile.name} 上新建的会话，不会切换当前连接。", style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary))
        Spacer(Modifier.height(12.dp))
        NewSessionDefaultsCard(state, controller, profile.id)
      }
    }
  }

  if (showNative) RemoteNativeServicesSheet(controller, profile) { showNative = false }

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
    SuperDialog(title = "移除 ${profile.name}？", show = true, onDismissRequest = { confirmingDelete = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${profile.connectionTypeLabel} · ${profile.endpointSummary}")
        Text("只删除这条 Harness 的本机连接资料；同一设备上的其他 Harness 与远端进程、会话均不受影响。设备本身保留。")
        DialogActions("取消", { confirmingDelete = false }, "确认移除", danger = true) {
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
private fun rememberDevices(
  state: LagoonState,
  controller: LagoonController,
  graph: ConnectionGraph?
): List<DeviceView> = remember(graph, state.profiles, state.discoveryRevision, state.allServerSnapshots, state.serverId, state.connected) {
  controller.deviceViews(graph, state.profiles) { id ->
    if (id == state.serverId) state.connected else state.allServerSnapshots[id]?.connected == true
  }
}

private fun sshHostOfDevice(graph: ConnectionGraph?, deviceId: String): String? {
  val host = graph?.hosts?.firstOrNull { it.id == deviceId } ?: return null
  val source = graph.sources.firstOrNull { it.id == host.sourceId } ?: return null
  return source.sshHostConfigId
}

private fun discoverySummary(device: DeviceView): String {
  val discovery = device.discovery ?: return "尚未发现；点「刷新发现」后才会连接一次"
  val age = System.currentTimeMillis() - discovery.observedAtMs
  val whenText = when {
    age < 60_000 -> "刚刚"
    age < 3_600_000 -> "${age / 60_000} 分钟前"
    age < 86_400_000 -> "${age / 3_600_000} 小时前"
    else -> "${age / 86_400_000} 天前"
  }
  val stale = if (discovery.isFresh(System.currentTimeMillis())) "" else " · 已过期"
  return "$whenText · ${discovery.harnesses.size} 个结果$stale"
}
