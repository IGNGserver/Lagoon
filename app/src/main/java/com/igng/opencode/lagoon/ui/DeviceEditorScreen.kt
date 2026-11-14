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
import androidx.compose.runtime.collectAsState
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
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备整页编辑器。三层共用这一页，每一层都是 [DeviceEditorRoute]：
 *
 * - [DeviceEditorMode.ADD_DEVICE]：添加设备的唯一入口，先选「经由中枢 / 经由 SSH」；兼容直连收在高级区；
 * - [DeviceEditorMode.SSH_HOST]：SSH 设备表单（空串 = 新建），只保存设备本身的接入配置；
 * - [DeviceEditorMode.DIRECT_PROFILE]：兼容直连资料编辑。
 *
 * 因为它由根 `AnimatedContent` 按路由渲染，进入/退出和首页进设备页完全一样；[onBack] 只退一层，
 * 所以从添加入口打开 SSH 表单再返回，落回添加入口而不是整个设备页。
 */
@Composable
internal fun DeviceEditorScreen(
  state: LagoonState,
  controller: LagoonController,
  route: DeviceEditorRoute,
  onBack: () -> Unit,
  onOpenSshEditor: (String) -> Unit,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit,
  onDeviceSaved: (String) -> Unit
) {
  when (route.mode) {
    DeviceEditorMode.SSH_HOST -> SshHostPage(
      state = state,
      controller = controller,
      initial = route.sshHostId?.takeIf(String::isNotBlank)?.let { id -> state.sshHosts.firstOrNull { it.id == id } },
      creating = route.sshHostId.isNullOrBlank(),
      onBack = onBack,
      onSaved = { hostId ->
        val deviceId = controller.deviceIdOfSshHost(hostId)
        if (deviceId != null) onDeviceSaved(deviceId) else onBack()
      }
    )
    DeviceEditorMode.DIRECT_PROFILE -> {
      val existing = state.profiles.firstOrNull { it.id == route.profileId }
      if (existing == null) LaunchedEffect(route.profileId) { onBack() }
      else DirectProfilePage(state, controller, existing, onBack, onManageHub)
    }
    DeviceEditorMode.ADD_DEVICE -> AddDevicePage(
      state = state,
      controller = controller,
      onBack = onBack,
      onOpenSshEditor = onOpenSshEditor,
      onLoginHub = onLoginHub,
      onManageHub = onManageHub,
      onDeviceSaved = onDeviceSaved
    )
  }
}

/**
 * 添加设备：只有接入分支不同，产物都是同一台设备。
 *
 * 中枢分支复用账号登录与主机勾选（选完即成为设备，不默认启用任何 Harness）；SSH 分支只收地址与凭据，
 * 保存后同样是一台设备。Harness 的选择统一放在设备页，因此这里没有 Harness 开关。
 */
@Composable
private fun AddDevicePage(
  state: LagoonState,
  controller: LagoonController,
  onBack: () -> Unit,
  onOpenSshEditor: (String) -> Unit,
  onLoginHub: () -> Unit,
  onManageHub: () -> Unit,
  onDeviceSaved: (String) -> Unit
) {
  var showAdvanced by rememberSaveable { mutableStateOf(false) }
  var showKeychainSheet by rememberSaveable { mutableStateOf(false) }
  var directKind by rememberSaveable { mutableStateOf(ServerKind.OPENCODE) }
  val scope = rememberCoroutineScope()
  // 登录/接入会改写连接图；跟着它重组，目录和"已保存的 SSH 设备"才会即时更新。
  val graph by controller.connectionGraph.collectAsState()
  val catalog = remember(graph) { controller.activeHubCatalog() }
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
          "两种接入方式产出的都是同一种设备。设备出现在列表里之后，再逐个启用它上面的 Harness；未启用的 Harness 不占用连接与流量。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
      item {
        HyperGroupTitle("经由中枢")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = if (catalog == null) "登录 Remote Hub" else "接入更多主机",
            summary = when {
              catalog == null -> "登录后勾选要接入的开发机并核对指纹；不会自动启用 Harness"
              catalog.signedOut -> "${catalog.origin} · 已退出登录，请重新登录后再接入主机"
              else -> "${catalog.origin} · 已接入 ${catalog.hosts.size} 台；可再次勾选"
            },
            showChevron = true,
            onClick = onLoginHub
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "管理中枢与主机",
            summary = "查看账号、暂停整个中枢或退出登录",
            showChevron = true,
            onClick = onManageHub
          )
        }
      }
      item {
        HyperGroupTitle("经由 SSH")
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = "添加 SSH 设备",
            summary = "地址、用户名、验证方式（密码 / 密钥 / 密钥优先）；保存后刷新发现",
            showChevron = true,
            onClick = { onOpenSshEditor("") }
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(
            title = "SSH 密钥库",
            summary = if (state.sshKeys.isEmpty()) "本机生成 Ed25519 / 硬件密钥，或导入已有私钥" else "已保存 ${state.sshKeys.size} 把密钥 · 支持多主机复用与一键部署公钥",
            showChevron = true,
            onClick = { showKeychainSheet = true }
          )
        }
        Text(
          "SSH 表单不会预选 Harness，也不会在保存时连接或启动远端服务。",
          Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
      if (state.sshHosts.isNotEmpty()) item {
        HyperGroupTitle("已保存的 SSH 设备")
      }
      items(state.sshHosts, key = { "ssh:${it.id}" }) { host ->
        val deviceId = controller.deviceIdOfSshHost(host.id)
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperRow(
            title = host.name,
            summary = "${host.address} · 打开设备页选择 Harness",
            showChevron = true,
            onClick = { if (deviceId != null) onDeviceSaved(deviceId) else onOpenSshEditor(host.id) }
          )
        }
      }
      item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
          HyperActionRow(
            title = "高级 · 兼容 HTTP 直连",
            summary = "已知服务地址或配对链接时使用，不经过中枢或 SSH 发现",
            trailing = { ExpandChevron(showAdvanced) },
            onClick = { showAdvanced = !showAdvanced }
          )
        }
      }
      if (showAdvanced) {
        item {
          HyperSegmentedTabs(
            tabs = listOf("OpenCode 直连", "DSH 直连（旧）"),
            selectedIndex = if (directKind == ServerKind.OPENCODE) 0 else 1,
            onTabSelected = { directKind = if (it == 0) ServerKind.OPENCODE else ServerKind.DSH }
          )
        }
        item {
          // MiuixServerForm owns a scrolling list; give it a bounded viewport inside this section.
          Box(Modifier.fillMaxWidth().height(directFormHeight)) {
            key(directKind) {
              MiuixServerForm(
                existing = null,
                fixedKind = directKind,
                onCancel = onBack,
                onDelete = null,
                onSave = { profile, password, done ->
                  scope.launch {
                    saveDirectProfile(controller, profile, password, done) { saved ->
                      val deviceId = controller.deviceOfLeaf(saved.id)
                      if (deviceId != null) onDeviceSaved(deviceId) else onBack()
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

  SshKeychainManagerSheet(
    show = showKeychainSheet,
    state = state,
    controller = controller,
    onDismiss = { showKeychainSheet = false }
  )
}

/** 兼容直连资料：一保存就是一台 1:1 的设备，直接进它的设备页。 */
private suspend fun saveDirectProfile(
  controller: LagoonController,
  profile: ServerProfile,
  password: String?,
  done: (String) -> Unit,
  onSaved: (ServerProfile) -> Unit
) {
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
      val saved = controller.state.value.profiles.firstOrNull { it.id == actual.id } ?: actual
      done("已添加 ${actual.kind.label} 直连设备")
      onSaved(saved)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
    done(userFacingError(error, "连接失败"))
  }
}

/**
 * 编辑一条兼容直连资料。中枢与 SSH 设备的接入配置在设备页里改，这里只剩历史直连资料。
 */
@Composable
private fun DirectProfilePage(
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
  val title = if (existing.protocol == com.igng.opencode.lagoon.core.HarnessProtocolId.ANTIGRAVITY_ACP) "Antigravity 连接详情" else if (existing.protocol == com.igng.opencode.lagoon.core.HarnessProtocolId.OPENCODE_ACP) "OpenCode ACP 连接详情" else when (existing.kind) {
    ServerKind.DSH_REMOTE -> "DSH 远程主机详情"
    ServerKind.OPENCODE -> "编辑 OpenCode 直连"
    ServerKind.CODEX -> "Codex 连接详情"
    ServerKind.DSH -> "编辑 DeepSeek Harness"
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
              val previous = state.profiles.firstOrNull { it.id == profile.id }
              val credentials = when {
                resolved != null -> resolved.credentials
                actualProfile.kind == ServerKind.DSH_REMOTE -> ServerCredentials(actualProfile.username)
                else -> profileCredentials(
                  state.profiles.firstOrNull { it.id == profile.id }?.url, actualProfile.url,
                  controller.credentials(profile.id), actualProfile.username, password,
                  cookie = "".takeIf { actualProfile.kind == ServerKind.DSH && (password != null || previous?.dshAuth != actualProfile.dshAuth) }
                )
              }
              if (actualProfile.kind == ServerKind.DSH_REMOTE && previous?.enabled != false) {
                val hostId = actualProfile.remoteHostId ?: error("请先选择 DSH Host")
                val fingerprint = actualProfile.remoteHostFingerprint ?: error("请先确认 Host 指纹")
                controller.confirmRemoteHost(actualProfile, actualProfile.username, hostId, fingerprint)
              }
              if (previous?.enabled == false) {
                controller.saveServer(actualProfile, credentials.password, null, credentials.username, connect = false)
                done("已保存（连接已禁用，未连接）")
                onBack()
                return@launch
              }
              if (previous != null && actualProfile.kind == ServerKind.DSH && actualProfile.sshHostId == null &&
                (!ConnectionRouteAdmission.sameLeafRoute(previous, actualProfile) || controller.credentials(profile.id) != credentials)
              ) {
                controller.saveServer(actualProfile, credentials.password, credentials.cookie, credentials.username, connect = true)
                done("已保存，正在使用新配置重新连接；原连接不会被并行测试")
                onBack()
                return@launch
              }
              var minted: String? = null
              val version = controller.testServer(actualProfile, credentials) { minted = it }
              controller.saveServer(actualProfile, credentials.password, minted ?: credentials.cookie, credentials.username)
              val detail = if (version.isBlank() || version.equals(actualProfile.kind.label, ignoreCase = true) || version == "DeepSeek Harness") "" else " $version"
              done("已保存${detail}")
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

/** SSH 设备表单：从设备页进来只有这一层，从添加入口进来是第二层，返回都只退一层。 */
@Composable
private fun SshHostPage(
  state: LagoonState,
  controller: LagoonController,
  initial: SshHostConfig?,
  creating: Boolean,
  onBack: () -> Unit,
  onSaved: (String) -> Unit
) {
  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(title = if (creating) "添加 SSH 设备" else "编辑 SSH 设备", onBack = onBack)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      SshHostForm(
        initial = initial,
        state = state,
        controller = controller,
        onCancel = onBack,
        onSaved = onSaved
      )
    }
  }
}
