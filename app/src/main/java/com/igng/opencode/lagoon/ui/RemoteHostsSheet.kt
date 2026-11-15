package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Account-level host list: refresh, re-enroll, sign out. One account per origin/account in P0. */
@Composable
internal fun RemoteHostsSheet(
  controller: LagoonController,
  onDismiss: () -> Unit,
  onEnroll: () -> Unit,
  onOpenPairing: (() -> Unit)? = null,
) {
  val scope = rememberCoroutineScope()
  var catalogState by remember { mutableStateOf(controller.activeHubCatalog()) }
  var pairedState by remember { mutableStateOf(controller.pairedRemoteCatalogs()) }
  val catalog = catalogState
  val managerResult = remember(catalog?.origin, catalog?.account, catalog?.signedOut) {
    catalog?.let { saved ->
      saved.takeUnless { it.signedOut }?.let { active ->
        runCatching { controller.remoteAccountManager(active.origin, active.allowCleartext).also { it.resume(active) } }
      }
    }
  }
  val manager = managerResult?.getOrNull()
  val signedOut = catalog?.signedOut == true
  val managerError = managerResult?.exceptionOrNull()?.message
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var confirmSignOut by remember { mutableStateOf(false) }
  var confirmDeleteHub by remember { mutableStateOf(false) }
  var deletingPaired by remember { mutableStateOf<RemoteCatalog?>(null) }
  val runtime by controller.state.collectAsState()
  val graph by controller.connectionGraph.collectAsState()
  val profiles = runtime.profiles
  var choosingTarget by remember { mutableStateOf<Pair<RemoteCatalog, RemoteHostMeta>?>(null) }

  val authStatus = catalog?.let { controller.hubAuthStatus(it, runtime) } ?: HubAuthStatus.ACTIVE
  val hubSource = catalog?.let { cat ->
    val id = MigrationProjection.hubSourceId(cat.origin, cat.account)
    graph?.sources?.firstOrNull { it.id == id }
  }

  fun addedHost(targetCatalog: RemoteCatalog, hostId: String): Boolean = profiles.any {
    it.remoteHostId == hostId && it.username.trim() == targetCatalog.account.trim() &&
      runCatching { canonicalRemoteOrigin(it.url) == targetCatalog.origin }.getOrDefault(false)
  }

  fun reload() {
    catalogState = controller.activeHubCatalog()
    pairedState = controller.pairedRemoteCatalogs()
  }

  fun triggerRelogin(target: RemoteCatalog) {
    controller.prepareRemoteRelogin(target.origin, target.allowCleartext, target.account)
    onEnroll()
  }

  if (confirmSignOut && catalog != null) {
    SuperDialog(title = "退出 DSH 远程中枢？", show = true, onDismissRequest = { confirmSignOut = false }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("仅注销本机登录态并停止后台重连，本地保存的设备列表、主机指纹和离线聊天缓存会保留（便于下次重新登录后一键恢复）。如需彻底清除本地所有记录，请使用「删除中枢」。",
          style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { confirmSignOut = false }, if (busy) "正在退出…" else "退出登录", !busy, danger = true) {
          scope.launch {
            busy = true; error = null
            try {
              controller.markRemoteSignedOut(catalog.origin, catalog.account)
              manager?.signOut(catalog)
              reload()
              confirmSignOut = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = userFacingError(e, "退出登录失败") }
            finally { busy = false }
          }
        }
      }
    }
  }

  if (confirmDeleteHub && catalog != null) {
    SuperDialog(title = "彻底删除此远程中枢？", show = true, onDismissRequest = { confirmDeleteHub = false }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("将从本机彻底删除 ${catalog.account}（${catalog.origin}）下的所有中枢设备、Harness 配置、主机公钥指纹、发现缓存、离线聊天缓存与设备令牌，并自动吊销远端令牌。远端电脑上的工作区、进程与会话不受影响。",
          style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { confirmDeleteHub = false }, "彻底删除", !busy, danger = true) {
          controller.deleteRemoteSource(catalog.origin, catalog.account)
          reload()
          confirmDeleteHub = false
          if (controller.activeHubCatalog() == null && controller.pairedRemoteCatalogs().isEmpty()) {
            onDismiss()
          }
        }
      }
    }
  }

  deletingPaired?.let { target ->
    SuperDialog(title = "移除扫码配对设备？", show = true, onDismissRequest = { deletingPaired = null }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("将从本机彻底删除扫码配对设备（${target.origin}）的接入配置、主机指纹、发现缓存、离线聊天缓存与设备令牌。",
          style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { deletingPaired = null }, "确认移除", !busy, danger = true) {
          controller.deleteRemoteSource(target.origin, target.account)
          reload()
          deletingPaired = null
          if (controller.activeHubCatalog() == null && controller.pairedRemoteCatalogs().isEmpty()) {
            onDismiss()
          }
        }
      }
    }
  }

  val selectedTarget = choosingTarget
  if (selectedTarget != null) {
    val (targetCatalog, selectedHost) = selectedTarget
    val parent = runCatching { controller.remoteCatalogProfile(targetCatalog, selectedHost) }.getOrNull()
    if (parent != null) RemoteNativeServicesSheet(controller, parent, targetCatalog, selectedHost) { choosingTarget = null }
    else LaunchedEffect(selectedTarget) { choosingTarget = null; error = "主机目录已变化，请刷新" }
  }

  HyperBottomSheet(title = "远程中枢与主机", show = selectedTarget == null, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f)) {
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (catalog == null) {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("还没有登录 DSH 远程中枢。登录后选择要接入的电脑，或直接扫描电脑上的配对二维码。", style = MiuixTheme.textStyles.footnote1)
              HyperButton(text = "登录并接入主机", onClick = onEnroll, modifier = Modifier.fillMaxWidth())
              if (onOpenPairing != null) {
                HyperButton(
                  text = "扫码配对主机",
                  variant = HyperButtonVariant.SECONDARY,
                  onClick = onOpenPairing,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
        } else {
          item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                  Text(catalog.account, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Text(listOfNotNull(catalog.provider?.let { providerLabel(it) }, catalog.origin).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), maxLines = 1)
                }
              }
            }
          }
          if (authStatus != HubAuthStatus.ACTIVE || manager == null) item {
            HyperInlineNotice(
              message = when {
                signedOut -> "已退出登录；本地保存的接入源、主机层级和离线缓存仍保留。重新登录后可自动恢复连接，或点击下方「删除中枢」彻底清除。"
                authStatus == HubAuthStatus.EXPIRED -> "登录态已过期或设备令牌已失效；本地配置与缓存仍保留，点击重新登录即可自动恢复连接。"
                else -> managerError ?: "此连接需要重新登录并明确授权后才能联网"
              },
              type = MiuixToastType.WARNING,
              action = "重新登录",
              onAction = { triggerRelogin(catalog) }
            )
          }
          if (authStatus == HubAuthStatus.ACTIVE && hubSource != null) item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
              HyperSwitchRow(
                title = "启用此中枢连接",
                summary = if (hubSource.enabled) "暂停后停止该中枢下所有主机联网与重连，配置保留"
                else "已暂停：该中枢下所有主机均不联网",
                checked = hubSource.enabled,
                onCheckedChange = { controller.setConnectionSourceEnabled(hubSource.id, it) }
              )
            }
          }
          item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Text("托管主机 · ${catalog.hosts.size}", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
              HyperButton(text = "刷新", variant = HyperButtonVariant.TERTIARY, loading = busy, enabled = manager != null && authStatus == HubAuthStatus.ACTIVE, onClick = {
                scope.launch {
                  busy = true; error = null
                  try { manager?.refresh()?.let(controller::saveRemoteCatalog); reload() }
                  catch (cancel: CancellationException) { throw cancel }
                  catch (e: Exception) { error = userFacingError(e, "刷新中枢主机失败") }
                  finally { busy = false }
                }
              })
            }
          }
          items(catalog.hosts, key = { it.hostId }) { host ->
            val hostExpired = authStatus != HubAuthStatus.ACTIVE ||
              controller.hostAuthExpired(catalog.origin, catalog.account, host.hostId, runtime)
            val statusLabel = when {
              signedOut -> "已退出登录 · 本地缓存记录"
              hostExpired -> "登录态失效 · 本地缓存记录"
              hubSource?.enabled == false -> "中枢已暂停 · 缓存记录"
              host.online -> "在线"
              else -> "离线"
            }
            val statusColor = when {
              signedOut || hostExpired -> MiuixColorTokens.Warning
              hubSource?.enabled == false -> MiuixTheme.colorScheme.onSurfaceVariantSummary
              host.online -> MiuixColorTokens.Success
              else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(host.name, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Spacer(Modifier.width(6.dp))
                  Text(statusLabel, style = MiuixTheme.textStyles.footnote2.copy(color = statusColor))
                }
                Text(listOfNotNull(host.platform.ifBlank { null }, host.harnessVersion?.let { "Harness $it" }, if (addedHost(catalog, host.hostId)) "已接入" else "未接入").joinToString(" · "),
                  style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), maxLines = 1)
                Text(host.fingerprint?.let { formatFingerprint(it) } ?: "指纹待确认",
                  style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                  color = when (host.trust) {
                    RemoteTrustState.CHANGED -> MiuixColorTokens.Error
                    RemoteTrustState.PINNED -> MiuixColorTokens.Success
                    RemoteTrustState.UNKNOWN -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                  })
              }
              if (signedOut || hostExpired) HyperTextAction(text = "重新登录", onClick = { triggerRelogin(catalog) })
              else if (host.trust == RemoteTrustState.PINNED) HyperTextAction(text = "选择 Harness", onClick = { choosingTarget = catalog to host })
              else HyperTextAction(text = "核对并接入", onClick = { triggerRelogin(catalog) })
            }
          }
          item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HyperButton(
                  text = if (authStatus != HubAuthStatus.ACTIVE) "重新登录中枢" else "接入更多主机",
                  onClick = { triggerRelogin(catalog) },
                  modifier = Modifier.weight(1f)
                )
                HyperButton(
                  text = if (signedOut) "已退出登录" else "退出登录",
                  enabled = !signedOut,
                  variant = HyperButtonVariant.SECONDARY,
                  onClick = { confirmSignOut = true },
                  modifier = Modifier.weight(1f)
                )
              }
              HyperButton(
                text = "删除中枢（彻底清除本地数据）",
                variant = HyperButtonVariant.DESTRUCTIVE,
                onClick = { confirmDeleteHub = true },
                modifier = Modifier.fillMaxWidth()
              )
              if (onOpenPairing != null) {
                HyperButton(
                  text = "扫码配对新主机",
                  variant = HyperButtonVariant.SECONDARY,
                  onClick = onOpenPairing,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
        }
        if (pairedState.isNotEmpty()) {
          val pairedEntries = pairedState.flatMap { paired -> paired.hosts.map { host -> paired to host } }
          item {
            Text(
              "扫码直配设备 · ${pairedEntries.size}",
              modifier = Modifier.padding(top = 8.dp),
              style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
            )
          }
          items(pairedEntries, key = { (paired, host) -> "${paired.key}:${host.hostId}" }) { (paired, host) ->
            val pairedExpired = controller.hubAuthStatus(paired, runtime) != HubAuthStatus.ACTIVE
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(host.name, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Spacer(Modifier.width(6.dp))
                  Text(
                    when {
                      pairedExpired -> "凭证已失效 · 本地缓存记录"
                      host.online -> "在线"
                      else -> "离线"
                    },
                    style = MiuixTheme.textStyles.footnote2.copy(
                      color = when {
                        pairedExpired -> MiuixColorTokens.Warning
                        host.online -> MiuixColorTokens.Success
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                      }
                    )
                  )
                }
                Text(
                  listOfNotNull(host.platform.ifBlank { null }, paired.origin, if (addedHost(paired, host.hostId)) "已接入" else "未接入").joinToString(" · "),
                  style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                  maxLines = 1,
                )
                Text(
                  host.fingerprint?.let { formatFingerprint(it) } ?: "指纹已固定",
                  style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                  color = if (host.trust == RemoteTrustState.CHANGED) MiuixColorTokens.Error else MiuixColorTokens.Success,
                )
              }
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!pairedExpired) {
                  HyperTextAction(text = "选择 Harness", onClick = { choosingTarget = paired to host })
                }
                HyperTextAction(text = "移除", danger = true, onClick = { deletingPaired = paired })
              }
            }
          }
        }
        error?.let { text -> item { HyperInlineNotice(message = text, type = MiuixToastType.ERROR, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
      }
    }
  }
}

private fun providerLabel(value: String): String = when (value) {
  "github" -> "GitHub"
  "zhihu" -> "知乎"
  "google" -> "Google"
  "password" -> "邮箱"
  "pairing" -> "扫码配对"
  else -> value
}
