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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.DeviceGroup
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.endpointSummary
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备管理：聚合全部连接、显式设备分组、DSH 远程中枢账号与独立端点。
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
  val disabled = state.disabledProfileCount
  val online = state.profiles.count { profile ->
    profile.enabled && (if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true)
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
              Text(
                "旧版分组已按新的“全部 + 分组”规则清空一次，连接记录、凭据和会话都完好保留。",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
              )
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
              Text(
                "${state.profiles.size} 个连接${if (disabled > 0) " · $disabled 已禁用" else ""} · $online 在线",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
              )
            }
            DropdownChevron(18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
          }
        }
      }

      item {
        SmallTitle("DSH 远程中枢账号")
        if (catalog == null) {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                "登录中枢账号，安全接入名下各台托管电脑上的 DSH。",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
              )
              TextButton(text = "登录 DSH 远程中枢", onClick = onLoginHub, modifier = Modifier.fillMaxWidth())
            }
          }
        } else {
          Card(Modifier.fillMaxWidth().clickable(onClick = onManageHub), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(
                  catalog.account,
                  style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  "${catalog.origin} · ${catalog.hosts.size} 台托管主机",
                  style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
              Text("管理中枢与主机", style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.primary))
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
              Text(
                "把同一台电脑上的多个连接放进一个分组，首页只显示「全部」和这些分组。",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
              )
              TextButton(
                text = "新建设备组",
                onClick = { onEditGroup(null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.profiles.isNotEmpty()
              )
            }
          } else {
            state.deviceGroups.forEach { group ->
              GroupRow(group, state, onClick = { onEditGroup(group.id) })
            }
            TextButton(
              text = "新建设备组",
              onClick = { onEditGroup(null) },
              modifier = Modifier.fillMaxWidth(),
              enabled = state.profiles.isNotEmpty()
            )
          }
        }
      }

      if (state.profiles.isNotEmpty()) {
        item {
          SmallTitle("全部连接")
          Text(
            "分组只改变首页聚合；这里始终可以查看和编辑每一个已保存的连接。禁用后不再连接，也不会出现在会话、归档和通知中；凭据、草稿和缓存保留。",
            Modifier.padding(horizontal = 16.dp),
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          )
        }
        val opencodeProfiles = state.profiles.filter { it.kind == ServerKind.OPENCODE }
        val dshProfiles = state.profiles.filter { it.kind != ServerKind.OPENCODE }

        if (opencodeProfiles.isNotEmpty()) {
          item {
            Text(
              "OpenCode",
              Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp),
              style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            )
          }
          items(opencodeProfiles, key = { "profile:${it.id}" }) { profile ->
            ProfileRow(profile, state, groupOf[profile.id], onClick = { onEditProfile(profile) },
              onToggleEnabled = { enabled -> controller.setProfileEnabled(profile.id, enabled) })
          }
        }

        if (dshProfiles.isNotEmpty()) {
          item {
            Text(
              "DeepSeek Harness",
              Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
              style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            )
          }
          items(dshProfiles, key = { "profile:${it.id}" }) { profile ->
            ProfileRow(profile, state, groupOf[profile.id], onClick = {
              if (profile.sshHostId != null) onEditSshHost(profile.sshHostId) else onEditProfile(profile)
            }, onToggleEnabled = { enabled -> controller.setProfileEnabled(profile.id, enabled) })
          }
        }
      }

      item {
        Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
          SuperArrow(
            title = "添加设备",
            summary = "OpenCode 直连、DSH 远程中枢或 DSH SSH 直连",
            onClick = onAddDevice
          )
        }
      }
    }
  }
}

@Composable
private fun GroupRow(group: DeviceGroup, state: LagoonState, onClick: () -> Unit) {
  val members = group.memberIds.mapNotNull { id -> state.profiles.firstOrNull { it.id == id } }
  val enabledMembers = members.filter { it.enabled }
  val disabled = members.size - enabledMembers.size
  val online = enabledMembers.count { profile ->
    if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true
  }
  Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          group.name,
          style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          "${members.size} 个连接${if (disabled > 0) " · $disabled 已禁用" else ""} · $online 在线",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        )
      }
      members.take(4).forEach { HarnessBadge(it.kind) }
      Spacer(Modifier.width(4.dp))
      DropdownChevron(18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    if (members.isNotEmpty()) {
      Text(
        members.joinToString("、") { if (it.enabled) it.name else "${it.name}（已禁用）" },
        Modifier.padding(top = 4.dp),
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun ProfileRow(
  profile: ServerProfile,
  state: LagoonState,
  groupName: String?,
  onClick: () -> Unit,
  onToggleEnabled: (Boolean) -> Unit
) {
  val connected = profile.enabled &&
    (if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true)
  val endpoint = profile.endpointSummary
  val subtitle = buildString {
    append(endpoint)
    if (groupName != null) append(" · 分组：").append(groupName)
    if (!profile.enabled) append(" · 已禁用")
  }
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  Card(Modifier.fillMaxWidth().padding(bottom = 2.dp), insideMargin = PaddingValues(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Row(
        Modifier.weight(1f).clickable(onClick = onClick).clip(RoundedCornerShape(12.dp)).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          if (profile.sshHostId != null) MiuixIcons.Link else MiuixIcons.GridView,
          null,
          Modifier.size(22.dp),
          tint = if (profile.enabled) muted else muted.copy(alpha = 0.55f)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              profile.name,
              style = MiuixTheme.textStyles.body1.copy(color = if (profile.enabled) MiuixTheme.colorScheme.onSurface else muted),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (profile.kind == ServerKind.DSH && profile.sshHostId == null) {
              Spacer(Modifier.width(6.dp))
              Text("旧版", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Warning))
            }
          }
          Text(
            subtitle,
            style = MiuixTheme.textStyles.footnote2.copy(color = muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        if (connected) {
          StatusDot(MiuixColorTokens.Success)
          Spacer(Modifier.width(6.dp))
          Text("在线", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Success))
        }
        Spacer(Modifier.width(8.dp))
        DropdownChevron(18.dp, muted)
      }
      Switch(checked = profile.enabled, onCheckedChange = onToggleEnabled)
    }
  }
}
