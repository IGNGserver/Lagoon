package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.igng.opencode.lagoon.core.ServerCredentials
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.profileCredentials
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备整页编辑器。三层共用这一页，每一层都是 [DeviceEditorRoute]：
 *
 * - 空的 [DeviceEditorRoute]：添加入口，先按 harness 分两段（OpenCode / DeepSeek Harness）；
 * - 填了 `profileId`：编辑已有连接，OpenCode 直连、旧版直连 DSH、DSH 远程中枢共用同一个表单；
 * - 填了 `sshHostId`：SSH 主机表单（空串 = 新建）。
 *
 * 因为它由根 `AnimatedContent` 按路由渲染，进入/退出和首页进设备页完全一样；[onBack] 只退一层，
 * 所以从添加入口打开 SSH 表单再返回，落回的是 DSH 段而不是整个设备页。
 */
@Composable
internal fun DeviceEditorScreen(
  state: LagoonState,
  controller: LagoonController,
  route: DeviceEditorRoute,
  onBack: () -> Unit,
  onEditSsh: (String) -> Unit,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit
) {
  val sshHostId = route.sshHostId
  val profileId = route.profileId
  when {
    sshHostId != null -> SshHostPage(
      state = state,
      controller = controller,
      initial = sshHostId.takeIf(String::isNotBlank)?.let { id -> state.sshHosts.firstOrNull { it.id == id } },
      creating = sshHostId.isBlank(),
      onBack = onBack
    )
    profileId != null -> {
      val existing = state.profiles.firstOrNull { it.id == profileId }
      // 设备在别处被删除：这一层已无内容，退回上一层，而不是留一张空白页在栈上。
      if (existing == null) LaunchedEffect(profileId) { onBack() }
      else ProfileEditorPage(
        state = state,
        controller = controller,
        existing = existing,
        onBack = onBack,
        onManageHub = onManageHub
      )
    }
    else -> AddDevicePage(
      state = state,
      controller = controller,
      initialTab = route.tab,
      onBack = onBack,
      onEditSsh = onEditSsh,
      onLoginHub = onLoginHub,
      onManageHub = onManageHub
    )
  }
}

/** 添加入口：OpenCode 直连表单，或 DSH 段里的中枢登录与 SSH 主机列表。 */
@Composable
private fun AddDevicePage(
  state: LagoonState,
  controller: LagoonController,
  initialTab: AddDeviceTab,
  onBack: () -> Unit,
  onEditSsh: (String) -> Unit,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit
) {
  val scope = rememberCoroutineScope()
  var tab by rememberSaveable { mutableStateOf(initialTab) }
  val catalog = controller.activeRemoteCatalog()

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(title = "添加设备", onBack = onBack)
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
      HyperSegmentedTabs(
        tabs = AddDeviceTab.entries.map { it.label },
        selectedIndex = AddDeviceTab.entries.indexOf(tab),
        onTabSelected = { tab = AddDeviceTab.entries[it] }
      )
    }
    if (tab == AddDeviceTab.OPENCODE) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        MiuixServerForm(
          existing = null,
          fixedKind = ServerKind.OPENCODE,
          onCancel = onBack,
          onDelete = null,
          onSave = { profile, password, done ->
            scope.launch {
              try {
                val pair = PairLinkResolver.isPairLink(profile.url)
                val resolved = if (pair) PairLinkResolver.resolve(profile.url) else null
                val actual = if (resolved == null) profile
                else profile.copy(url = resolved.serverUrl, username = resolved.credentials.username)
                val credentials = resolved?.credentials ?: profileCredentials(
                  null, actual.url, controller.credentials(profile.id), actual.username, password
                )
                var minted: String? = null
                controller.testServer(actual, credentials) { minted = it }
                controller.saveServer(actual, credentials.password, minted ?: credentials.cookie, credentials.username)
                done("已连接 OpenCode 直连")
                onBack()
              } catch (cancel: CancellationException) {
                throw cancel
              } catch (error: Exception) {
                done(userFacingError(error, "连接失败"))
              }
            }
          }
        )
      }
    } else {
      DshTab(
        state = state,
        catalogAccount = catalog?.account,
        catalogOrigin = catalog?.origin,
        hostCount = catalog?.hosts?.size ?: 0,
        onLoginHub = onLoginHub,
        onManageHub = onManageHub,
        onAddSsh = { onEditSsh("") },
        onEditSsh = onEditSsh
      )
    }
  }
}

/**
 * 编辑一条已有连接。无论 OpenCode 直连、旧版直连 DSH 还是 DSH 远程中枢都用这一个表单：
 * 以前远程中枢走的是半屏抽屉、OpenCode 走的是整页，同一件事有两种动效。
 */
@Composable
private fun ProfileEditorPage(
  state: LagoonState,
  controller: LagoonController,
  existing: ServerProfile,
  onBack: () -> Unit,
  onManageHub: () -> Unit
) {
  var deleting by remember { mutableStateOf(false) }
  var remoteDetails by remember { mutableStateOf(false) }
  var remoteExtensions by remember { mutableStateOf(false) }
  var remoteModelKey by remember { mutableStateOf(false) }
  var remoteProvider by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val title = when (existing.kind) {
    ServerKind.DSH_REMOTE -> "DSH 远程主机详情"
    ServerKind.OPENCODE -> "编辑 OpenCode 直连"
    ServerKind.DSH -> if (existing.sshHostId != null) "编辑 SSH 直连设备" else "编辑 DeepSeek Harness"
  }

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(title = title, onBack = onBack)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      MiuixServerForm(
        existing = existing,
        onCancel = onBack,
        onDelete = { deleting = true },
        onRemoteDetails = { remoteDetails = true },
        onRemoteExtensions = { remoteExtensions = true },
        onRemoteModelKey = { remoteModelKey = true },
        onRemoteProvider = { remoteProvider = true },
        onRemoteHosts = onManageHub,
        onPairRemote = { profile, account, password, publishHosts ->
          publishHosts(controller.registerRemote(profile, account, password))
        },
        onSave = { profile, password, done ->
          scope.launch {
            try {
              val pair = PairLinkResolver.isPairLink(profile.url)
              val resolved = if (pair) PairLinkResolver.resolve(profile.url) else null
              val actualProfile = if (resolved == null) profile else profile.copy(url = resolved.serverUrl, username = resolved.credentials.username)
              val savedUrl = state.profiles.firstOrNull { it.id == profile.id }?.url
              val previous = state.profiles.firstOrNull { it.id == profile.id }
              val credentials = if (actualProfile.kind == ServerKind.DSH_REMOTE) {
                ServerCredentials(actualProfile.username)
              } else resolved?.credentials ?: profileCredentials(
                savedUrl, actualProfile.url, controller.credentials(profile.id), actualProfile.username, password,
                // DSH 填了新令牌/密钥或换了认证方式：丢掉旧 Cookie，让这次测试真正验证新凭据。
                cookie = "".takeIf { actualProfile.kind == ServerKind.DSH && (password != null || previous?.dshAuth != actualProfile.dshAuth) }
              )
              if (actualProfile.kind == ServerKind.DSH_REMOTE && previous?.enabled != false) {
                val hostId = actualProfile.remoteHostId ?: error("请先选择 DSH Host")
                val fingerprint = actualProfile.remoteHostFingerprint ?: error("请先确认 Host 指纹")
                controller.confirmRemoteHost(actualProfile, actualProfile.username, hostId, fingerprint)
              }
              val label = when (actualProfile.kind) {
                ServerKind.OPENCODE -> "OpenCode 直连"
                ServerKind.DSH_REMOTE -> "DSH 远程中枢"
                ServerKind.DSH -> if (actualProfile.sshHostId != null) "DSH SSH 直连" else "DeepSeek Harness"
              }
              if (previous?.enabled == false) {
                // A disabled connection must not be dialled just because the user edited it. Save the
                // new settings without testing; enabling it later performs the first connection.
                controller.saveServer(actualProfile, credentials.password, null, credentials.username, connect = false)
                done("已保存 $label（连接已禁用，未连接）")
                onBack()
                return@launch
              }
              var minted: String? = null
              val version = controller.testServer(actualProfile, credentials) { minted = it }
              controller.saveServer(actualProfile, credentials.password, minted ?: credentials.cookie, credentials.username)
              val detail = if (version.isBlank() || version.equals(actualProfile.kind.label, ignoreCase = true) || version == "DeepSeek Harness") "" else " $version"
              done("已连接 $label$detail")
              onBack()
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

  if (deleting) {
    SuperDialog(title = "移除 ${existing.name}？", show = true, onDismissRequest = { deleting = false }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("仅从此设备删除连接记录和存储凭据，不会对远程服务器数据产生任何影响。", style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { deleting = false }, "确认删除", danger = true) {
          controller.deleteServer(existing.id)
          deleting = false
          onBack()
        }
      }
    }
  }
  if (remoteDetails) RemoteConnectionDialog(existing, state, controller) { remoteDetails = false }
  if (remoteExtensions) RemoteExtensionsDialog(existing, controller) { remoteExtensions = false }
  if (remoteModelKey) RemoteModelKeyDialog(existing, controller) { remoteModelKey = false }
  if (remoteProvider) RemoteProviderDialog(existing, controller) { remoteProvider = false }
}

/** SSH 主机表单：从设备详情进来只有这一层，从添加入口进来是第二层，返回都只退一层。 */
@Composable
private fun SshHostPage(
  state: LagoonState,
  controller: LagoonController,
  initial: SshHostConfig?,
  creating: Boolean,
  onBack: () -> Unit
) {
  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(title = if (creating) "添加 SSH 主机" else "编辑 SSH 主机", onBack = onBack)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      SshHostForm(
        initial = initial,
        state = state,
        controller = controller,
        onCancel = onBack,
        onSaved = onBack
      )
    }
  }
}

@Composable
private fun DshTab(
  state: LagoonState,
  catalogAccount: String?,
  catalogOrigin: String?,
  hostCount: Int,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit,
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
        "DeepSeek Harness 有两种接入：登录远程中枢批量接入电脑，或直接 SSH 直连一台 POSIX 主机。",
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
      )
    }
    item { HyperGroupTitle("远程中枢") }
    if (catalogAccount == null) {
      item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(16.dp)) {
          Text(
            "尚未登录。登录后会列出你有权访问的主机，不会自动接入全部主机。",
            style = MiuixTheme.textStyles.body2.copy(color = HyperTheme.colors.textSecondary)
          )
          Spacer(Modifier.height(12.dp))
          HyperButton(text = "登录并选择主机", onClick = onLoginHub, modifier = Modifier.fillMaxWidth())
        }
      }
    } else {
      item {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HyperButton(text = "接入更多主机", onClick = onLoginHub, modifier = Modifier.weight(1f))
          HyperButton(
            text = "管理中枢",
            variant = HyperButtonVariant.SECONDARY,
            onClick = onManageHub,
            modifier = Modifier.weight(1f)
          )
        }
      }
    }
    item { HyperGroupTitle("SSH 主机") }
    if (state.sshHosts.isEmpty()) {
      item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(14.dp)) {
          Text(
            "还没有 SSH 主机。添加后会自动在远端启动 dsh web 并建立加密隧道。",
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
        variant = if (state.sshHosts.isEmpty()) HyperButtonVariant.PRIMARY else HyperButtonVariant.SECONDARY,
        onClick = onAddSsh,
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}
