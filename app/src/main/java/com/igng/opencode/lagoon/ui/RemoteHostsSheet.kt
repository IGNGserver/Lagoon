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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Account-level host list: refresh, re-enroll, sign out. One account per origin/account in P0. */
@Composable
internal fun RemoteHostsSheet(
  controller: LagoonController,
  targetSourceId: String? = null,
  onDismiss: () -> Unit,
  onEnroll: () -> Unit,
  onOpenPairing: (() -> Unit)? = null,
) {
  val scope = rememberCoroutineScope()
  var hubs by remember { mutableStateOf(controller.remoteCatalogs().filterNot(controller::isPairedRemoteCatalog)) }
  var selectedHubKey by remember(targetSourceId) {
    mutableStateOf(
      if (targetSourceId == null) controller.activeHubCatalog()?.key
      else hubs.firstOrNull { MigrationProjection.hubSourceId(it.origin, it.account) == targetSourceId }?.key
    )
  }
  var pairedState by remember { mutableStateOf(controller.pairedRemoteCatalogs()) }
  val catalog = hubs.firstOrNull { it.key == selectedHubKey }
  val managerResult = remember(catalog?.origin, catalog?.account, catalog?.signedOut) {
    catalog?.let { saved ->
      runCatching { controller.remoteAccountManager(saved.origin, saved.allowCleartext).also { if (!saved.signedOut) it.resume(saved) } }
    }
  }
  val signedOut = catalog?.signedOut == true
  val manager = managerResult?.getOrNull()?.takeUnless { signedOut }
  val signOutManager = managerResult?.getOrNull()
  val hasRemainingCredentials = catalog?.let { runCatching { controller.hasRemoteCredentials(it.origin, it.account) }.getOrDefault(true) } == true
  val managerError = managerResult?.exceptionOrNull()?.message
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var revocationNotice by remember { mutableStateOf<String?>(null) }
  var confirmSignOut by remember { mutableStateOf(false) }
  var confirmDeleteHub by remember { mutableStateOf(false) }
  var deletingPaired by remember { mutableStateOf<Pair<RemoteCatalog, RemoteHostMeta>?>(null) }
  val runtime by controller.state.collectAsState()
  val graph by controller.connectionGraph.collectAsState()
  val profiles = runtime.profiles
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) { while (true) { delay(15_000L); now = System.currentTimeMillis() } }
  var choosingTarget by remember { mutableStateOf<Pair<RemoteCatalog, RemoteHostMeta>?>(null) }
  var renamingTarget by remember { mutableStateOf<Pair<RemoteCatalog, RemoteHostMeta>?>(null) }

  val authStatus = catalog?.let { controller.hubAuthStatus(it, runtime) } ?: HubAuthStatus.ACTIVE
  val hubSource = catalog?.let { cat ->
    val id = MigrationProjection.hubSourceId(cat.origin, cat.account)
    graph?.sources?.firstOrNull { it.id == id }
  }

  fun addedHost(targetCatalog: RemoteCatalog, hostId: String): Boolean = profiles.any {
    it.remoteHostId == hostId && it.username.trim() == targetCatalog.account.trim() &&
      runCatching { canonicalRemoteOrigin(it.url) == targetCatalog.origin }.getOrDefault(false)
  }

  fun connectedHost(targetCatalog: RemoteCatalog, hostId: String): Boolean = profiles.any { profile ->
    profile.remoteHostId == hostId && profile.username.trim() == targetCatalog.account.trim() &&
      runCatching { canonicalRemoteOrigin(profile.url) == targetCatalog.origin }.getOrDefault(false) &&
      (runtime.allServerSnapshots[profile.id]?.connected == true || (runtime.serverId == profile.id && runtime.connected))
  }

  fun freshPresence(host: RemoteHostMeta): Boolean =
    host.presenceObservedAt?.let { it <= now && now - it <= 60_000L } == true

  fun reload() {
    hubs = controller.remoteCatalogs().filterNot(controller::isPairedRemoteCatalog)
    if (selectedHubKey != null && hubs.none { it.key == selectedHubKey }) selectedHubKey = null
    pairedState = controller.pairedRemoteCatalogs()
  }

  fun triggerRelogin(target: RemoteCatalog) {
    controller.prepareRemoteRelogin(target.origin, target.allowCleartext, target.account)
    onEnroll()
  }

  fun recoverHost(target: RemoteCatalog, host: RemoteHostMeta) {
    if (controller.remoteHostUsesPairing(target.origin, target.account, host.hostId) && onOpenPairing != null)
      onOpenPairing()
    else triggerRelogin(target)
  }

  if (confirmSignOut && catalog != null) {
    SuperDialog(title = "退出 DSH 远程中枢？", show = true, onDismissRequest = { confirmSignOut = false }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("退出后停止后台重连，并尝试撤销远端授权；设备列表、主机指纹和离线聊天缓存会保留。网络不可用时，远端授权会在联网后继续尝试撤销。",
          style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { confirmSignOut = false }, if (busy) "正在退出…" else "退出登录", !busy, danger = true) {
          scope.launch {
            busy = true; error = null
            try {
              val activeManager = requireNotNull(signOutManager) { "无法读取中枢授权，请稍后重试" }
              controller.markRemoteSignedOut(catalog.origin, catalog.account)
              activeManager.signOut(catalog)
              controller.triggerPendingRemoteRevocationsDrain()
              revocationNotice = try {
                val count = controller.pendingRemoteRevocationCount()
                if (count > 0) "本机已退出，$count 项远端授权仍待撤销；联网后会继续尝试。" else "本机已退出，远端撤销已完成。"
              } catch (_: Exception) { "本机已退出，但无法读取待撤销状态，请稍后重试。" }
              reload()
              confirmSignOut = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
              controller.triggerPendingRemoteRevocationsDrain()
              reload(); confirmSignOut = false
              error = userFacingError(e, "退出未完全结束，请重试撤销授权")
            }
            finally { busy = false }
          }
        }
      }
    }
  }

  if (confirmDeleteHub && catalog != null) {
    SuperDialog(title = "彻底删除此远程中枢？", show = true, onDismissRequest = { confirmDeleteHub = false }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("将从本机删除 ${catalog.account}（${catalog.origin}）下的设备、连接配置、指纹和离线缓存。远端授权会在联网后尝试撤销，撤销完成前可能仍然有效；远端电脑上的工作区和会话不受影响。",
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

  deletingPaired?.let { (target, host) ->
    SuperDialog(title = "移除扫码配对设备？", show = true, onDismissRequest = { deletingPaired = null }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("将从本机移除 ${host.displayName} 的接入配置与离线缓存。远端授权会在联网后尝试撤销，撤销完成前可能仍然有效。",
          style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { deletingPaired = null }, "确认移除", !busy, danger = true) {
          controller.deleteDevice(MigrationProjection.hostId(MigrationProjection.hubSourceId(target.origin, target.account), host.hostId))
          reload()
          deletingPaired = null
          if (controller.activeHubCatalog() == null && controller.pairedRemoteCatalogs().isEmpty()) {
            onDismiss()
          }
        }
      }
    }
  }

  renamingTarget?.let { (targetCatalog, host) ->
    var value by remember(targetCatalog.key, host.hostId) { mutableStateOf(host.alias.orEmpty()) }
    SuperDialog(title = "设置显示名称", show = true, onDismissRequest = { renamingTarget = null }) {
      Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "只改这台手机上的显示名，不改变中枢里的 Host 名称、指纹或连接身份。留空恢复 Host 自报名。",
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        )
        TextField(
          value = value,
          onValueChange = { value = it.take(128) },
          label = "显示名称",
          modifier = Modifier.fillMaxWidth()
        )
        if (host.nameUnavailable) {
          Text(
            "Host 当前自报名称是「${host.name}」，可直接在本地命名，例如“工作站”。",
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Warning)
          )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HyperButton(
            text = "取消",
            variant = HyperButtonVariant.SECONDARY,
            onClick = { renamingTarget = null },
            modifier = Modifier.weight(1f)
          )
          HyperButton(
            text = "保存",
            enabled = !busy,
            onClick = {
              try {
                controller.renameRemoteHost(targetCatalog.origin, targetCatalog.account, host.hostId, value)
                renamingTarget = null
                reload()
              } catch (e: Exception) {
                error = userFacingError(e, "保存显示名失败")
              }
            },
            modifier = Modifier.weight(1f)
          )
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
        revocationNotice?.let { notice -> item { HyperInlineNotice(message = notice, type = MiuixToastType.WARNING) } }
        if (hubs.size > 1) {
          item { Text("选择中枢账号", style = MiuixTheme.textStyles.footnote1) }
          items(hubs, key = { "hub:${it.key}" }) { hub ->
            HyperButton(
              text = "${hub.account} · ${hub.origin}${if (hub.key == selectedHubKey) "（当前）" else ""}",
              variant = if (hub.key == selectedHubKey) HyperButtonVariant.PRIMARY else HyperButtonVariant.SECONDARY,
              onClick = { selectedHubKey = hub.key; error = null },
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
        if (catalog == null) {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(if (hubs.isEmpty()) "还没有登录 DSH 远程中枢。登录后选择要接入的电脑，或直接扫描电脑上的配对二维码。"
                else "请选择要管理的中枢账号，或查看下方扫码设备。", style = MiuixTheme.textStyles.footnote1)
              if (hubs.size == 1) HyperButton(text = "管理 ${hubs.first().account}", onClick = { selectedHubKey = hubs.first().key }, modifier = Modifier.fillMaxWidth())
              HyperButton(text = "登录并接入主机", onClick = { controller.prepareRemoteRelogin(); onEnroll() }, modifier = Modifier.fillMaxWidth())
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
          if (catalog.directoryPartial) item {
            HyperInlineNotice(message = catalog.directoryIssue ?: "部分设备未能核实，正在显示上次记录，请稍后刷新。", type = MiuixToastType.WARNING)
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
                  try { manager?.refresh()?.let(controller::publishRefreshedRemoteCatalog); reload() }
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
            val hostConnected = connectedHost(catalog, host.hostId)
            val fresh = freshPresence(host)
            val statusLabel = when {
              signedOut -> "已退出登录 · 本地缓存记录"
              hostExpired -> "登录态失效 · 本地缓存记录"
              hubSource?.enabled == false -> "中枢已暂停 · 缓存记录"
              hostConnected -> "已连接"
              !fresh -> "状态待确认"
              host.online -> "在线"
              else -> "离线"
            }
            val statusColor = when {
              signedOut || hostExpired -> MiuixColorTokens.Warning
              hubSource?.enabled == false -> MiuixTheme.colorScheme.onSurfaceVariantSummary
              hostConnected || (fresh && host.online) -> MiuixColorTokens.Success
              else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(host.displayName, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Spacer(Modifier.width(6.dp))
                  Text(statusLabel, style = MiuixTheme.textStyles.footnote2.copy(color = statusColor))
                }
                Text(listOfNotNull(host.platform.ifBlank { null }, host.harnessVersion?.let { "Harness $it" }, if (host.nameUnavailable) "Host 名称不可用" else null, if (addedHost(catalog, host.hostId)) "已接入" else "未接入").joinToString(" · "),
                  style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), maxLines = 1)
                Text(host.fingerprint?.let { formatFingerprint(it) } ?: "指纹待确认",
                  style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                  color = when (host.trust) {
                    RemoteTrustState.CHANGED -> MiuixColorTokens.Error
                    RemoteTrustState.PINNED -> MiuixColorTokens.Success
                    RemoteTrustState.UNKNOWN -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                  })
              }
              Column(horizontalAlignment = Alignment.End) {
                if (signedOut || hostExpired) HyperTextAction(
                  text = if (controller.remoteHostUsesPairing(catalog.origin, catalog.account, host.hostId)) "重新扫码配对" else "重新登录",
                  onClick = { recoverHost(catalog, host) }
                )
                else if (host.trust == RemoteTrustState.PINNED) HyperTextAction(text = "选择 Harness", onClick = { choosingTarget = catalog to host })
                else HyperTextAction(text = "核对并接入", onClick = { recoverHost(catalog, host) })
                HyperTextAction(text = "命名", onClick = { renamingTarget = catalog to host })
              }
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
                  text = if (signedOut && hasRemainingCredentials) "继续撤销授权" else if (signedOut) "已退出登录" else "退出登录",
                  enabled = !busy && (!signedOut || hasRemainingCredentials),
                  variant = HyperButtonVariant.SECONDARY,
                  onClick = { confirmSignOut = true },
                  modifier = Modifier.weight(1f)
                )
              }
              HyperButton(
                text = "删除中枢（彻底清除本地数据）",
                variant = HyperButtonVariant.DESTRUCTIVE,
                enabled = !busy,
                onClick = { confirmDeleteHub = true },
                modifier = Modifier.fillMaxWidth()
              )
              HyperButton(
                text = "登录另一个中枢账号",
                variant = HyperButtonVariant.SECONDARY,
                onClick = { controller.prepareRemoteRelogin(); onEnroll() },
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
            val pairedExpired = paired.signedOut || controller.hostAuthExpired(paired.origin, paired.account, host.hostId, runtime)
            val pairedConnected = connectedHost(paired, host.hostId)
            val fresh = freshPresence(host)
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(host.displayName, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Spacer(Modifier.width(6.dp))
                  Text(
                    when {
                      pairedExpired -> "凭证已失效 · 本地缓存记录"
                      pairedConnected -> "已连接"
                      !fresh -> "状态待确认"
                      host.online -> "在线"
                      else -> "离线"
                    },
                    style = MiuixTheme.textStyles.footnote2.copy(
                      color = when {
                        pairedExpired -> MiuixColorTokens.Warning
                        pairedConnected || (fresh && host.online) -> MiuixColorTokens.Success
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                      }
                    )
                  )
                }
                Text(
                  listOfNotNull(host.platform.ifBlank { null }, paired.origin, if (host.nameUnavailable) "Host 名称不可用" else null, if (addedHost(paired, host.hostId)) "已接入" else "未接入").joinToString(" · "),
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
                HyperTextAction(text = "命名", onClick = { renamingTarget = paired to host })
                if (!pairedExpired) HyperTextAction(text = "刷新", onClick = {
                  if (!busy) scope.launch {
                    busy = true; error = null
                    try {
                      val pairManager = controller.remoteAccountManager(paired.origin, paired.allowCleartext)
                      pairManager.resume(paired)
                      controller.publishRefreshedRemoteCatalog(pairManager.refresh())
                      reload()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { error = userFacingError(e, "刷新设备状态失败") }
                    finally { busy = false }
                  }
                })
                if (!pairedExpired) {
                  HyperTextAction(text = "选择 Harness", onClick = { choosingTarget = paired to host })
                } else if (onOpenPairing != null) {
                  HyperTextAction(text = "重新扫码", onClick = onOpenPairing)
                }
                HyperTextAction(text = "移除", danger = true, onClick = { deletingPaired = paired to host })
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
