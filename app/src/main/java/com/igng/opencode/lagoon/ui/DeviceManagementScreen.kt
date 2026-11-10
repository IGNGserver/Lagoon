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
  val online = state.profiles.count { profile -> deviceConnected(profile, state) }
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
          "${state.profiles.size} 台设备 · $online 台在线",
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
        HyperGroupTitle("设备")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          state.profiles.forEachIndexed { index, profile ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(
              title = profile.name,
              summary = profile.endpointSummary + if (profile.enabled) "" else " · 已停用",
              value = deviceStatusLabel(profile, state),
              showChevron = true,
              onClick = { onOpenDevice(profile.id) }
            )
          }
          if (state.profiles.isEmpty()) {
            HyperRow(title = "还没有设备", summary = "添加 OpenCode、DeepSeek Harness 中枢或 SSH 主机")
          }
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
  val profile = state.profiles.firstOrNull { it.id == deviceId }
  if (profile == null) {
    // 设备在别处被删掉：这一层已经没有内容，退回列表，而不是把一张空白页留在栈上。
    LaunchedEffect(deviceId) { onBack() }
    return
  }
  var confirmingDelete by remember { mutableStateOf(false) }
  var showDefaults by remember { mutableStateOf(false) }
  var remoteDialog by remember { mutableStateOf<String?>(null) }
  val isRemote = profile.kind == ServerKind.DSH_REMOTE
  val isDsh = profile.kind == ServerKind.DSH || isRemote
  val currentGroup = state.deviceGroups.firstOrNull { profile.id in it.memberIds }

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
            title = "启用此设备",
            summary = "启用后保持连接。停用会断开并停止任务通知，不会删除设备、凭据或会话",
            checked = profile.enabled,
            onCheckedChange = { controller.setProfileEnabled(profile.id, it) }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "连接地址", summary = profile.endpointSummary)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "类型", value = if (profile.sshHostId != null) "SSH 直连" else profile.kind.label)
        }
      }
      item {
        HyperGroupTitle("使用方式")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperDropdownRow(
            title = "所属设备组",
            items = listOf("不分组") + state.deviceGroups.map { it.name },
            selectedIndex = state.deviceGroups.indexOfFirst { it.id == currentGroup?.id }.let { if (it < 0) 0 else it + 1 },
            onSelectedIndexChange = { index -> controller.moveProfile(profile.id, if (index == 0) null else state.deviceGroups[index - 1].id) }
          )
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
          HyperRow(title = "编辑连接信息", summary = "地址、账号、认证与传输安全", trailing = { DropdownChevron() }, onClick = { onEditProfile(profile) })
          if (isRemote) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(title = "中枢与远程主机", summary = "查看账号、接入其他主机或退出中枢", trailing = { DropdownChevron() }, onClick = onManageHub)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(title = "网络与诊断", summary = "连接延迟、错误详情与重试", trailing = { DropdownChevron() }, onClick = { remoteDialog = "connection" })
          }
        }
      }
      if (isRemote) item {
        HyperGroupTitle("主机高级功能")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(title = "主机扩展", summary = "管理此 Host 的插件开关", trailing = { DropdownChevron() }, onClick = { remoteDialog = "extensions" })
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

  when (remoteDialog) {
    "connection" -> RemoteConnectionDialog(profile, state, controller) { remoteDialog = null }
    "extensions" -> RemoteExtensionsDialog(profile, controller) { remoteDialog = null }
    "model-key" -> RemoteModelKeyDialog(profile, controller) { remoteDialog = null }
    "provider" -> RemoteProviderDialog(profile, controller) { remoteDialog = null }
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

private fun deviceSnapshot(profile: ServerProfile, state: LagoonState): ServerOverviewSnapshot? =
  state.serverSnapshots()[profile.id]

private fun deviceConnected(profile: ServerProfile, state: LagoonState): Boolean =
  profile.enabled && deviceSnapshot(profile, state)?.connected == true

private fun deviceStatusLabel(profile: ServerProfile, state: LagoonState): String {
  val snapshot = deviceSnapshot(profile, state)
  val authExpired = snapshot?.error?.let { message ->
    message.contains("重新登录") || message.contains("凭据已过期") || message.contains("登录已失效")
  } == true
  return resolveDeviceStatusLabel(
    enabled = profile.enabled,
    connected = deviceConnected(profile, state),
    loading = snapshot?.loading == true,
    hasError = !snapshot?.error.isNullOrBlank(),
    authExpired = authExpired
  )
}
