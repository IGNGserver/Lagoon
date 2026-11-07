package com.igng.opencode.lagoon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.PairLinkResolver
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.profileCredentials
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class AddDeviceTab(val label: String) {
  OPENCODE("OpenCode"),
  DSH_REMOTE("远程中枢"),
  DSH_SSH("SSH 主机")
}

/**
 * 统一添加入口：三大对等接入通道（OpenCode 直连、DSH 远程中枢、DSH SSH 直连），采用自研澎湃分段胶囊。
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

  BackHandler(onBack = ::back)
  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(
      title = when {
        sshEditor != null -> if (sshEditor!!.isBlank()) "添加 SSH 主机" else "编辑 SSH 主机"
        editing -> "编辑 OpenCode 设备"
        else -> "添加设备"
      },
      onBack = ::back
    )

    if (sshEditor == null && !editing) {
      Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HyperSegmentedTabs(
          tabs = AddDeviceTab.entries.map { it.label },
          selectedIndex = AddDeviceTab.entries.indexOf(tab),
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
                    controller.testServer(actual, credentials) { minted = it }
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
  catalogAccount: String?,
  catalogOrigin: String?,
  hostCount: Int,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit
) {
  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Text(
        "登录 DeepSeek Harness 远程中枢，选择要接入的电脑并核对主机指纹。连接采用端到端加密。",
        style = MiuixTheme.textStyles.body2.copy(color = HyperTheme.colors.textSecondary)
      )
    }
    if (catalogAccount == null) {
      item {
        HyperGroupTitle("中枢账号")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(16.dp)) {
          Text("尚未登录。登录后会列出你有权访问的主机，不会自动接入全部主机。",
            style = MiuixTheme.textStyles.body2.copy(color = HyperTheme.colors.textSecondary))
          Spacer(Modifier.height(12.dp))
          HyperButton(text = "登录并选择主机", onClick = onLoginHub, modifier = Modifier.fillMaxWidth())
        }
      }
    } else {
      item {
        HyperGroupTitle("中枢账号")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = catalogAccount,
            summary = "${catalogOrigin.orEmpty()} · $hostCount 台可接入主机",
            trailing = { DropdownChevron() },
            onClick = onManageHub
          )
        }
      }
      item {
        HyperButton(text = "选择并接入主机", onClick = onLoginHub, modifier = Modifier.fillMaxWidth())
        HyperTextAction(text = "管理中枢账号与主机", onClick = onManageHub, modifier = Modifier.fillMaxWidth())
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
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Text(
        "通过 SSH 登录远端 POSIX 电脑（Linux / macOS / WSL），自动在远端启动并隧道直连 dsh web。",
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
      )
    }
    item {
      HyperGroupTitle("SSH 主机")
    }
    if (state.sshHosts.isEmpty()) {
      item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(14.dp)) {
          Text(
            "还没有添加 DSH SSH 主机。添加后会自动通过 SSH 启动远端的 dsh web 并建立加密隧道。",
            style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
          )
        }
      }
    } else {
      items(state.sshHosts, key = { it.id }) { host ->
        val profile = state.profiles.firstOrNull { it.sshHostId == host.id }
        val connected = profile?.let { p ->
          if (p.id == state.serverId) state.connected else state.allServerSnapshots[p.id]?.connected == true
        } == true
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(14.dp), onClick = { onEditSsh(host.id) }) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.Link, null, Modifier.size(22.dp), tint = HyperTheme.colors.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(host.name, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                host.address,
                style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            if (connected) {
              StatusDot(MiuixColorTokens.Success)
              Spacer(Modifier.width(6.dp))
              Text("在线", style = MiuixTheme.textStyles.footnote2.copy(fontWeight = FontWeight.SemiBold, color = MiuixColorTokens.Success))
            }
            Spacer(Modifier.width(6.dp))
            ForwardChevron(16.dp, HyperTheme.colors.textSecondary)
          }
        }
      }
    }
    item {
      HyperButton(
        text = "添加 SSH 主机",
        onClick = onAddSsh,
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}
