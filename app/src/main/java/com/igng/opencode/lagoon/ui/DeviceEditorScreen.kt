package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ConnectionRouteAdmission
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备整页编辑器。三层共用这一页，每一层都是 [DeviceEditorRoute]：
 *
 * - 空的 [DeviceEditorRoute]：按接入源选择 Hub / SSH，高级直连保留 harness 选择提示；
 * - 填了 `profileId`：编辑已有连接，OpenCode 直连、旧版直连 DSH、DSH 远程中枢共用同一个表单；
 * - 填了 `sshHostId`：SSH 主机表单（空串 = 新建）。
 *
 * 因为它由根 `AnimatedContent` 按路由渲染，进入/退出和首页进设备页完全一样；[onBack] 只退一层，
 * 所以从添加入口打开 SSH 表单再返回，落回接入源列表而不是整个设备页。
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

/** 添加入口：先选择共用接入源；兼容 HTTP 直连表单明确放在高级区。 */
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
  // The serialized tab remains a compatibility hint for the advanced form, not a source filter.
  var tab by rememberSaveable { mutableStateOf(initialTab) }
  var showAdvanced by rememberSaveable { mutableStateOf(false) }
  val catalog = controller.activeRemoteCatalog()
  val directFormHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp.coerceAtLeast(480.dp)

  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(title = "添加设备", onBack = onBack)
    LazyColumn(
      Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      item {
        Text(
          "先选择接入源，再从主机中选择 Harness。Remote Hub 与 SSH 不按 OpenCode / DSH 分开配置；发现服务不会启动或终止进程。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
      item {
        HyperGroupTitle("Remote Hub · 远程中枢")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = catalog?.account ?: "登录 Remote Hub",
            summary = if (catalog == null) "登录后逐个选择主机；DSH 可通过 Hub 接入，OpenCode 需 Host 升级并显式启用原生服务"
              else "${catalog.origin} · ${catalog.hosts.size} 台可接入主机 · DSH；OpenCode 需 Host 升级并显式启用",
            showChevron = true,
            onClick = if (catalog == null) onLoginHub else onManageHub
          )
        }
      }
      item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HyperButton(
            text = if (catalog == null) "登录并选择主机" else "接入更多主机",
            onClick = onLoginHub,
            modifier = Modifier.weight(1f)
          )
          HyperButton(
            text = "管理中枢",
            variant = HyperButtonVariant.SECONDARY,
            onClick = onManageHub,
            modifier = Modifier.weight(1f)
          )
        }
      }
      item {
        HyperGroupTitle("SSH · 主机接入源")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "添加 SSH 主机",
            summary = "一份 SSH 配置可接入多个原生 Harness；只读发现后逐个选择",
            showChevron = true,
            onClick = { onEditSsh("") }
          )
        }
        Text(
          "DSH SSH 目前由 WebHost 管理，不支持附着正在运行的 Desktop 进程；OpenCode 只附着已运行的官方服务。",
          Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
      if (state.sshHosts.isNotEmpty()) item { HyperGroupTitle("已保存的 SSH 主机") }
      items(state.sshHosts, key = { "ssh:${it.id}" }) { host ->
        val profiles = state.profiles.filter { it.sshHostId == host.id }
        val online = profiles.count { profile ->
          profile.effectiveEnabled && if (profile.id == state.serverId) state.connected
            else state.allServerSnapshots[profile.id]?.connected == true
        }
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = host.name,
            summary = "${host.address} · ${profiles.size} 个 Harness · $online 个在线",
            showChevron = true,
            onClick = { onEditSsh(host.id) }
          )
        }
      }
      item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperActionRow(
            title = "高级 · 兼容 HTTP 直连",
            summary = "已知服务地址或配对链接时使用，不经过 Hub 或 SSH 发现",
            trailing = { ExpandChevron(showAdvanced) },
            onClick = { showAdvanced = !showAdvanced }
          )
        }
      }
      if (showAdvanced) {
        item {
          HyperSegmentedTabs(
            tabs = AddDeviceTab.entries.map { "${it.label} 直连" },
            selectedIndex = AddDeviceTab.entries.indexOf(tab),
            onTabSelected = { tab = AddDeviceTab.entries[it] }
          )
        }
        item {
          // MiuixServerForm owns a scrolling list; give it a bounded viewport inside this section.
          Box(Modifier.fillMaxWidth().height(directFormHeight)) {
            key(tab) {
              MiuixServerForm(
                existing = null,
                fixedKind = if (tab == AddDeviceTab.OPENCODE) ServerKind.OPENCODE else ServerKind.DSH,
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
                      done("已连接 ${actual.kind.label} 直连")
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
        }
      }
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
              if (previous != null && actualProfile.kind == ServerKind.DSH && actualProfile.sshHostId == null &&
                (!ConnectionRouteAdmission.sameLeafRoute(previous, actualProfile) || controller.credentials(profile.id) != credentials)) {
                // This action is explicitly Save. Commit the changed route first so the owner can
                // retire its old mux before constructing a replacement; a diagnostic alone cannot
                // safely probe a competing API-peer cookie beside the live monitor.
                controller.saveServer(actualProfile, credentials.password, credentials.cookie, credentials.username, connect = true)
                done("已保存 $label，正在使用新配置重新连接；原连接不会被并行测试")
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
