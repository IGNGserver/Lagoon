package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.DeviceGroup
import com.igng.opencode.lagoon.core.DeviceGroupState
import com.igng.opencode.lagoon.core.DeviceMemberRole
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.deviceMemberRole
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One place for every saved device / harness: 全部, explicit groups, the DSH Remote Hub account
 * and ungrouped endpoints. Starting a new connection is one tap away; no per-profile top-bar tabs
 * and no add button on the home strip.
 */
@Composable
internal fun DeviceManagementScreen(
  state: LagoonState,
  controller: LagoonController,
  onBack: () -> Unit,
  onAddDevice: () -> Unit,
  onEditGroup: (String?) -> Unit,
  onEditProfile: (ServerProfile) -> Unit,
  onEditSshHost: (String) -> Unit,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit
) {
  val catalog = controller.activeRemoteCatalog()
  val groupOf = state.deviceGroups.flatMap { group -> group.memberIds.map { it to group.name } }.toMap()
  val devices = state.profiles.filter { it.deviceMemberRole == DeviceMemberRole.DEVICE }
  val harnesses = state.profiles.filter { it.deviceMemberRole == DeviceMemberRole.HARNESS }
  val online = state.profiles.count { profile ->
    if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true
  }

  Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
    PageTopBar("设备管理", onBack, actions = {
      CircleIconButton(MiuixIcons.Add, "添加设备", onAddDevice)
    })
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 48.dp)
    ) {
      if (state.deviceGroupReset) item {
        Card(
          Modifier.fillMaxWidth().padding(bottom = 8.dp),
          colors = CardDefaults.defaultColors(color = MiuixColorTokens.WarningSubtle),
          insideMargin = PaddingValues(14.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("设备分组已重置", style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium))
              Text("旧版分组已按新的“全部 + 分组”规则清空一次，设备、凭据和会话都还在。",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
            TextButton(text = "知道了", onClick = { controller.dismissDeviceGroupReset() })
          }
        }
      }

      item {
        SmallTitle("全部设备")
        Card(Modifier.fillMaxWidth().clickable {
          controller.setAllServers(true)
          onBack()
        }, insideMargin = PaddingValues(horizontal = 14.dp, vertical = 14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.GridView, null, Modifier.size(26.dp), tint = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text("全部设备", style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium))
              Text("${devices.size} 个设备 · ${harnesses.size} 个 Harness · $online 在线",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
            DropdownChevron(18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
          }
        }
      }

      item {
        SmallTitle("DSH Remote Hub")
        if (catalog == null) {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("一个 Hub 账号，接入它下面所有电脑上的 DSH。", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
              TextButton(text = "登录 DSH Remote Hub", onClick = onLoginHub, modifier = Modifier.fillMaxWidth())
            }
          }
        } else {
          Card(Modifier.fillMaxWidth().clickable(onClick = onManageHub), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(catalog.account, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                  maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${catalog.origin} · ${catalog.hosts.size} 台主机",
                  style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                  maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
              Text("管理", style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.primary))
              Spacer(Modifier.width(4.dp))
              DropdownChevron(18.dp, MiuixTheme.colorScheme.primary)
            }
          }
        }
      }

      item {
        SmallTitle("设备分组")
        Card(Modifier.fillMaxWidth()) {
          if (state.deviceGroups.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("把同一台电脑上的设备和 Harness 放进一个分组，首页只显示「全部」和这些分组。",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
              TextButton(text = "新建设备组", onClick = { onEditGroup(null) }, modifier = Modifier.fillMaxWidth(),
                enabled = state.profiles.isNotEmpty())
            }
          } else {
            state.deviceGroups.forEach { group ->
              GroupRow(group, state, onClick = { onEditGroup(group.id) })
            }
            TextButton(text = "新建设备组", onClick = { onEditGroup(null) }, modifier = Modifier.fillMaxWidth(),
              enabled = state.profiles.isNotEmpty())
          }
        }
      }

      if (state.profiles.isNotEmpty()) {
        item {
          SmallTitle("全部连接")
          Text("分组只改变首页聚合；这里始终可以编辑每一台设备和 Harness。", Modifier.padding(horizontal = 16.dp),
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
        val allDevices = state.profiles.filter { it.deviceMemberRole == DeviceMemberRole.DEVICE }
        val allHarnesses = state.profiles.filter { it.deviceMemberRole == DeviceMemberRole.HARNESS }
        if (allDevices.isNotEmpty()) {
          item {
            Text("设备", Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
              style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          }
          items(allDevices, key = { "device:${it.id}" }) { profile ->
            ProfileRow(profile, state, groupOf[profile.id]) {
              if (profile.sshHostId != null) onEditSshHost(profile.sshHostId) else onEditProfile(profile)
            }
          }
        }
        if (allHarnesses.isNotEmpty()) {
          item {
            Text("Harness", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
              style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          }
          items(allHarnesses, key = { "harness:${it.id}" }) { profile ->
            ProfileRow(profile, state, groupOf[profile.id]) { onEditProfile(profile) }
          }
        }
      }

      item {
        Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
          SuperArrow(title = "添加设备", summary = "OpenCode、DSH Remote Hub 或 SSH 主机",
            onClick = onAddDevice)
        }
      }
    }
  }
}

@Composable
private fun GroupRow(group: DeviceGroup, state: LagoonState, onClick: () -> Unit) {
  val members = group.memberIds.mapNotNull { id -> state.profiles.firstOrNull { it.id == id } }
  val devices = members.count { it.deviceMemberRole == DeviceMemberRole.DEVICE }
  val harnesses = members.count { it.deviceMemberRole == DeviceMemberRole.HARNESS }
  val online = members.count { profile ->
    if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true
  }
  Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(group.name, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
          maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$devices 个设备 · $harnesses 个 Harness · $online 在线",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      members.take(4).forEach { HarnessBadge(it.kind) }
      Spacer(Modifier.width(4.dp))
      DropdownChevron(18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    if (members.isNotEmpty()) {
      Text(members.joinToString("、") { it.name }, Modifier.padding(top = 4.dp),
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun ProfileRow(profile: ServerProfile, state: LagoonState, groupName: String?, onClick: () -> Unit) {
  val connected = if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true
  val endpoint = when {
    profile.sshHostId != null -> "SSH · ${profile.url.removePrefix("ssh://")}"
    profile.kind == ServerKind.DSH_REMOTE -> "DSH Remote · ${profile.remoteHostId?.take(8).orEmpty()}"
    profile.kind == ServerKind.OPENCODE -> "OpenCode · ${profile.url}"
    else -> "旧版直连 DSH · ${profile.url}"
  }
  val subtitle = if (groupName == null) endpoint else "$endpoint · 分组：$groupName"
  Card(Modifier.fillMaxWidth().padding(bottom = 2.dp), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).clip(RoundedCornerShape(12.dp)),
      verticalAlignment = Alignment.CenterVertically) {
      Icon(
        if (profile.sshHostId != null) MiuixIcons.Link else MiuixIcons.GridView,
        null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(profile.name, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (profile.kind == ServerKind.DSH && profile.sshHostId == null) {
            Spacer(Modifier.width(6.dp))
            Text("旧版", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Warning))
          }
        }
        Text(subtitle, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (connected) {
        StatusDot(MiuixColorTokens.Success)
        Spacer(Modifier.width(6.dp))
        Text("在线", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Success))
      }
      Spacer(Modifier.width(8.dp))
      DropdownChevron(18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
  }
}
