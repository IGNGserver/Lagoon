package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
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
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备管理（澎湃 OS 4 分组岛式设计）：
 * 聚合全部连接、显式设备分组、DSH 远程中枢账号与独立端点，全面采用自研 HyperCard / HyperRow / HyperSwitch。
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

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar("设备管理", onBack, actions = {
      CircleIconButton(MiuixIcons.Add, "添加设备", onAddDevice)
    })
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 48.dp)
    ) {
      if (state.deviceGroupReset) item {
        HyperCard(
          Modifier.fillMaxWidth().padding(bottom = 8.dp),
          backgroundColor = HyperTheme.colors.warningSubtle,
          borderColor = HyperTheme.colors.warning.copy(alpha = 0.35f),
          insidePadding = PaddingValues(14.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("设备分组已重置", style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
              Text(
                "旧版分组已按新的“全部 + 分组”规则清空一次，连接记录、凭据和会话都完好保留。",
                style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
              )
            }
            TextButton(text = "知道了", onClick = { controller.dismissDeviceGroupReset() })
          }
        }
      }

      item {
        HyperGroupTitle("全部设备")
        HyperCard(
          Modifier.fillMaxWidth(),
          insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
          onClick = {
            controller.setAllServers(true)
            onBack()
          }
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.GridView, null, Modifier.size(26.dp), tint = HyperTheme.colors.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text("全部设备", style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
              Text(
                "${state.profiles.size} 个连接${if (disabled > 0) " · $disabled 已禁用" else ""} · $online 在线",
                style = HyperTheme.tabular(
                  MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
                )
              )
            }
            ForwardChevron(16.dp, HyperTheme.colors.textSecondary)
          }
        }
      }

      item {
        HyperGroupTitle("DSH 远程中枢账号")
        if (catalog == null) {
          HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                "登录中枢账号，安全接入名下各台托管电脑上的 DSH。",
                style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
              )
              TextButton(text = "登录 DSH 远程中枢", onClick = onLoginHub, modifier = Modifier.fillMaxWidth())
            }
          }
        } else {
          HyperCard(
            Modifier.fillMaxWidth(),
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            onClick = onManageHub
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(
                  catalog.account,
                  style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  "${catalog.origin} · ${catalog.hosts.size} 台托管主机",
                  style = HyperTheme.tabular(
                    MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
                  ),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
              Text("管理中枢与主机", style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold, color = HyperTheme.colors.primary))
              Spacer(Modifier.width(4.dp))
              ForwardChevron(16.dp, HyperTheme.colors.primary)
            }
          }
        }
      }

      item {
        HyperGroupTitle("设备分组")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 6.dp)) {
          if (state.deviceGroups.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                "把同一台电脑上的多个连接放进一个分组，首页左上角范围面板会列出「全部设备」和这些分组。",
                style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
              )
              TextButton(
                text = "新建设备组",
                onClick = { onEditGroup(null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.profiles.isNotEmpty()
              )
            }
          } else {
            state.deviceGroups.forEachIndexed { index, group ->
              if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
              GroupRow(group, state, onClick = { onEditGroup(group.id) })
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            TextButton(
              text = "新建设备组",
              onClick = { onEditGroup(null) },
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
              enabled = state.profiles.isNotEmpty()
            )
          }
        }
      }

      if (state.profiles.isNotEmpty()) {
        item {
          HyperGroupTitle("全部连接")
          Text(
            "分组只改变首页聚合；这里始终可以查看和编辑每一个已保存的连接。禁用后不再连接，也不会出现在会话、归档和通知中；凭据、草稿和缓存保留。",
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
          )
        }
        val opencodeProfiles = state.profiles.filter { it.kind == ServerKind.OPENCODE }
        val dshProfiles = state.profiles.filter { it.kind != ServerKind.OPENCODE }

        if (opencodeProfiles.isNotEmpty()) {
          item {
            HyperGroupTitle("OpenCode")
            HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
              opencodeProfiles.forEachIndexed { index, profile ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
                ProfileRow(
                  profile, state, groupOf[profile.id],
                  onClick = { onEditProfile(profile) },
                  onToggleEnabled = { enabled -> controller.setProfileEnabled(profile.id, enabled) }
                )
              }
            }
          }
        }

        if (dshProfiles.isNotEmpty()) {
          item {
            HyperGroupTitle("DeepSeek Harness")
            HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
              dshProfiles.forEachIndexed { index, profile ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
                ProfileRow(
                  profile, state, groupOf[profile.id],
                  onClick = {
                    if (profile.sshHostId != null) onEditSshHost(profile.sshHostId) else onEditProfile(profile)
                  },
                  onToggleEnabled = { enabled -> controller.setProfileEnabled(profile.id, enabled) }
                )
              }
            }
          }
        }
      }

      item {
        HyperCard(Modifier.fillMaxWidth().padding(top = 14.dp), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "添加设备",
            summary = "OpenCode 直连、DSH 远程中枢或 DSH SSH 直连",
            showChevron = true,
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
  Column(
    Modifier
      .fillMaxWidth()
      .hyperPress(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          group.name,
          style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          "${members.size} 个连接${if (disabled > 0) " · $disabled 已禁用" else ""} · $online 在线",
          style = HyperTheme.tabular(
            MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
          )
        )
      }
      members.take(4).forEach {
        HarnessBadge(it.kind)
        Spacer(Modifier.width(4.dp))
      }
      ForwardChevron(16.dp, HyperTheme.colors.textSecondary)
    }
    if (members.isNotEmpty()) {
      Text(
        members.joinToString("、") { if (it.enabled) it.name else "${it.name}（已禁用）" },
        Modifier.padding(top = 4.dp),
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary),
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
  val snapshot = state.allServerSnapshots[profile.id]
  val connected = profile.enabled &&
    (if (profile.id == state.serverId) state.connected else snapshot?.connected == true)
  val loading = profile.enabled &&
    (if (profile.id == state.serverId) state.loading else snapshot?.loading == true)
  val error = if (profile.id == state.serverId) state.error ?: state.connection.reason else snapshot?.error
  val endpoint = profile.endpointSummary
  val subtitle = buildString {
    append(endpoint)
    if (groupName != null) append(" · 分组：").append(groupName)
    if (!profile.enabled) append(" · 已禁用")
    else if (!connected && !loading && !error.isNullOrBlank()) append(" · ").append(error)
  }
  val muted = HyperTheme.colors.textSecondary
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      Modifier
        .weight(1f)
        .clip(miuixSquircleShape(14.dp))
        .hyperPress(surfaceHighlight = true, onClick = onClick)
        .padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        if (profile.sshHostId != null) MiuixIcons.Link else MiuixIcons.GridView,
        null,
        Modifier.size(22.dp),
        tint = if (profile.enabled) HyperTheme.colors.primary else muted.copy(alpha = 0.55f)
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            profile.name,
            style = MiuixTheme.textStyles.body1.copy(
              fontWeight = FontWeight.SemiBold,
              color = if (profile.enabled) HyperTheme.colors.textPrimary else muted
            ),
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
          style = MiuixTheme.textStyles.footnote2.copy(
            color = if (profile.enabled && !connected && !loading && !error.isNullOrBlank()) HyperTheme.colors.warning else muted
          ),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
      if (connected) {
        StatusDot(MiuixColorTokens.Success)
        Spacer(Modifier.width(6.dp))
        Text("在线", style = MiuixTheme.textStyles.footnote2.copy(fontWeight = FontWeight.SemiBold, color = MiuixColorTokens.Success))
      } else if (loading) {
        Text("连接中", style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.warning))
      } else if (profile.enabled) {
        Text(
          if (!error.isNullOrBlank()) "异常" else "离线",
          style = MiuixTheme.textStyles.footnote2.copy(
            fontWeight = FontWeight.Medium,
            color = if (!error.isNullOrBlank()) HyperTheme.colors.warning else muted
          )
        )
      }
      Spacer(Modifier.width(8.dp))
      ForwardChevron(16.dp, muted)
    }
    Spacer(Modifier.width(8.dp))
    HyperSwitch(checked = profile.enabled, onCheckedChange = onToggleEnabled)
  }
}
