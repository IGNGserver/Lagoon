package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.PairLinkResolver
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.profileCredentials
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class AddDeviceTab(val label: String) {
  OPENCODE("OpenCode 直连"),
  DSH_REMOTE("DSH 远程中枢"),
  DSH_SSH("DSH SSH 直连")
}

/**
 * 统一添加入口：三大对等接入通道（OpenCode 直连、DSH 远程中枢、DSH SSH 直连）。
 */
@Composable
internal fun AddDeviceSheet(
  state: LagoonState,
  controller: LagoonController,
  existingOpenCode: ServerProfile? = null,
  initialSshHostId: String? = null,
  onDismiss: () -> Unit,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit
) {
  val scope = rememberCoroutineScope()
  var tab by rememberSaveable {
    mutableStateOf(
      when {
        existingOpenCode != null -> AddDeviceTab.OPENCODE
        initialSshHostId != null -> AddDeviceTab.DSH_SSH
        else -> AddDeviceTab.OPENCODE
      }
    )
  }
  var sshEditor by rememberSaveable(initialSshHostId) { mutableStateOf(initialSshHostId) }
  var deleting by remember { mutableStateOf(false) }
  val catalog = controller.activeRemoteCatalog()
  val editing = existingOpenCode != null

  fun back() {
    when {
      sshEditor != null -> sshEditor = null
      else -> onDismiss()
    }
  }

  SuperBottomSheet(title = "", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).background(MiuixTheme.colorScheme.surface)) {
      Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        CircleIconButton(
          if (sshEditor != null) MiuixIcons.Back else MiuixIcons.Close,
          "返回",
          onClick = { back() }
        )
        Spacer(Modifier.width(8.dp))
        Text(
          when {
            sshEditor != null -> if (sshEditor!!.isBlank()) "添加 DSH SSH 直连" else "编辑 DSH SSH 直连"
            editing -> "编辑 OpenCode 直连"
            else -> "添加设备"
          },
          Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Medium)
        )
      }

      if (sshEditor == null && !editing) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
          TabRow(
            tabs = AddDeviceTab.entries.map { it.label },
            selectedTabIndex = AddDeviceTab.entries.indexOf(tab),
            onTabSelected = { tab = AddDeviceTab.entries[it] }
          )
        }
      }

      when {
        sshEditor != null -> {
          val config = sshEditor?.takeIf { it.isNotBlank() }?.let { id -> state.sshHosts.firstOrNull { it.id == id } }
          SshHostForm(
            initial = config,
            state = state,
            controller = controller,
            onCancel = { sshEditor = null },
            onSaved = { onDismiss() }
          )
        }
        editing || tab == AddDeviceTab.OPENCODE -> {
          Box(Modifier.weight(1f).fillMaxWidth()) {
            MiuixServerForm(
              existing = existingOpenCode,
              fixedKind = ServerKind.OPENCODE,
              onCancel = onDismiss,
              onDelete = existingOpenCode?.let { { deleting = true } },
              onSave = { profile, password, done ->
                scope.launch {
                  try {
                    val pair = PairLinkResolver.isPairLink(profile.url)
                    val resolved = if (pair) PairLinkResolver.resolve(profile.url) else null
                    val actual = if (resolved == null) profile
                    else profile.copy(url = resolved.serverUrl, username = resolved.credentials.username)
                    val savedUrl = state.profiles.firstOrNull { it.id == profile.id }?.url
                    val previous = state.profiles.firstOrNull { it.id == profile.id }
                    val credentials = resolved?.credentials ?: profileCredentials(
                      savedUrl, actual.url, controller.credentials(profile.id), actual.username, password
                    )
                    if (previous?.enabled == false) {
                      controller.saveServer(actual, credentials.password, null, credentials.username, connect = false)
                      done("已保存 OpenCode 直连（连接已禁用，未连接）")
                      onDismiss()
                      return@launch
                    }
                    var minted: String? = null
                    val version = controller.testServer(actual, credentials) { minted = it }
                    controller.saveServer(actual, credentials.password, minted ?: credentials.cookie, credentials.username)
                    done("已连接 OpenCode 直连")
                    onDismiss()
                  } catch (cancel: CancellationException) {
                    throw cancel
                  } catch (error: Exception) {
                    done(userFacingError(error, "连接失败"))
                  }
                }
              }
            )
          }
        }
        tab == AddDeviceTab.DSH_REMOTE -> {
          DshRemoteTab(
            state = state,
            catalogAccount = catalog?.account,
            catalogOrigin = catalog?.origin,
            hostCount = catalog?.hosts?.size ?: 0,
            onLoginHub = onLoginHub,
            onManageHub = onManageHub
          )
        }
        else -> {
          DshSshTab(
            state = state,
            onAddSsh = { sshEditor = "" },
            onEditSsh = { sshEditor = it }
          )
        }
      }
    }
  }

  if (deleting && existingOpenCode != null) {
    SuperDialog(title = "移除 ${existingOpenCode.name}？", show = true, onDismissRequest = { deleting = false }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("仅从此设备删除连接记录和存储凭据，不会对远程服务器数据产生任何影响。", style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { deleting = false }, "确认删除", danger = true) {
          controller.deleteServer(existingOpenCode.id)
          deleting = false
          onDismiss()
        }
      }
    }
  }
}

@Composable
private fun DshRemoteTab(
  state: LagoonState,
  catalogAccount: String?,
  catalogOrigin: String?,
  hostCount: Int,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit
) {
  val remoteProfiles = state.profiles.filter { it.kind == ServerKind.DSH_REMOTE }
  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Text(
        "通过 DSH 远程中枢账号登录，安全接入名下各台电脑；每台托管主机独立保持端到端加密连接。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      )
    }
    item {
      SmallTitle("DSH 远程中枢账号", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp))
      if (catalogAccount == null) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              "登录一次即可接入中枢名下的多台电脑。每台主机在本地注册独立安全身份，并保持端到端加密连接。",
              style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            )
            TextButton(text = "登录 DSH 远程中枢", onClick = onLoginHub, modifier = Modifier.fillMaxWidth())
          }
        }
      } else {
        Card(Modifier.fillMaxWidth().clickable(onClick = onManageHub), insideMargin = PaddingValues(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(
                catalogAccount,
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                "$catalogOrigin · $hostCount 台托管主机",
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

    if (remoteProfiles.isNotEmpty()) {
      item {
        SmallTitle("已接入的主机", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp))
      }
      items(remoteProfiles, key = { it.id }) { profile ->
        val connected = if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true
        Card(Modifier.fillMaxWidth().clickable(onClick = onManageHub), insideMargin = PaddingValues(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(profile.name, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                "指纹 ${formatFingerprint(profile.remoteHostFingerprint)}",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            if (connected) {
              StatusDot(MiuixColorTokens.Success)
              Spacer(Modifier.width(6.dp))
              Text("在线", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Success))
            }
          }
        }
      }
    }

    if (catalogAccount != null) {
      item {
        Card(Modifier.fillMaxWidth().clickable(onClick = onLoginHub), insideMargin = PaddingValues(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.Add, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
              Text("接入更多中枢主机", style = MiuixTheme.textStyles.body1)
              Text("在中枢名下的电脑列表中勾选并接入", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
            DropdownChevron(18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
          }
        }
      }
    }

    item {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Text(
          "直连 DSH（地址 + 启动令牌）已下线；原有历史资料可在设备管理中移除。",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        )
      }
    }
  }
}

@Composable
private fun DshSshTab(
  state: LagoonState,
  onAddSsh: () -> Unit,
  onEditSsh: (String) -> Unit
) {
  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Text(
        "通过 SSH 登录远端 POSIX 电脑（Linux / macOS / WSL），自动在远端启动并隧道直连 dsh web。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      )
    }
    item {
      SmallTitle("DSH SSH 主机", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp))
    }
    if (state.sshHosts.isEmpty()) {
      item {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
          Text(
            "还没有添加 DSH SSH 主机。添加后会自动通过 SSH 启动远端的 dsh web 并建立加密隧道。",
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          )
        }
      }
    } else {
      items(state.sshHosts, key = { it.id }) { host ->
        val profile = state.profiles.firstOrNull { it.sshHostId == host.id }
        val connected = profile?.let { p ->
          if (p.id == state.serverId) state.connected else state.allServerSnapshots[p.id]?.connected == true
        } == true
        Card(Modifier.fillMaxWidth().clickable { onEditSsh(host.id) }, insideMargin = PaddingValues(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.Link, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(host.name, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                host.address,
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            if (connected) {
              StatusDot(MiuixColorTokens.Success)
              Spacer(Modifier.width(6.dp))
              Text("在线", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Success))
            }
            Spacer(Modifier.width(6.dp))
            DropdownChevron(18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
          }
        }
      }
    }
    item {
      Card(Modifier.fillMaxWidth().clickable(onClick = onAddSsh), insideMargin = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(MiuixIcons.Add, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
            Text("添加 DSH SSH 直连", style = MiuixTheme.textStyles.body1)
            Text(
              "不经过第三方服务器，直接通过 SSH 隧道直连",
              style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            )
          }
          DropdownChevron(18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
      }
    }
    item {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Text(
          "直连 DSH（地址 + 启动令牌）已下线；原有历史资料可在设备管理中移除。",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        )
      }
    }
  }
}
