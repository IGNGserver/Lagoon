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
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Account-level host list: refresh, re-enroll, sign out. One account per origin/account in P0. */
@Composable
internal fun RemoteHostsSheet(
  controller: LagoonController,
  onDismiss: () -> Unit,
  onEnroll: () -> Unit
) {
  val scope = rememberCoroutineScope()
  var catalogState by remember { mutableStateOf(controller.activeRemoteCatalog()) }
  val catalog = catalogState
  val manager = remember(catalog?.origin, catalog?.account) {
    catalog?.let { runCatching { controller.remoteAccountManager(it.origin, it.origin.startsWith("http://", true)) }.getOrNull() }
  }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var confirmSignOut by remember { mutableStateOf(false) }
  val profiles = remember(catalog) { controller.state.value.profiles }

  fun added(hostId: String): Boolean = profiles.any { it.kind == ServerKind.DSH_REMOTE && it.remoteHostId == hostId }

  fun reload() { catalogState = controller.activeRemoteCatalog() }

  if (confirmSignOut && catalog != null) {
    SuperDialog(title = "退出 DSH 远程中枢？", show = true, onDismissRequest = { confirmSignOut = false }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("会断开并停止本机后台重连；本地保存的配置、聊天缓存和主机指纹仍保留在本机。可在主机上重新登录。",
          style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { confirmSignOut = false }, if (busy) "正在退出…" else "退出登录", !busy, danger = true) {
          scope.launch {
            busy = true; error = null
            try {
              manager?.signOut(catalog)
              controller.markRemoteSignedOut(catalog.origin, catalog.account)
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

  SuperBottomSheet(title = "DSH 远程主机", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f)) {
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (catalog == null) {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("还没有登录 DSH 远程中枢。登录后选择要接入的电脑。", style = MiuixTheme.textStyles.footnote1)
              TextButton(text = "登录并接入主机", onClick = onEnroll, modifier = Modifier.fillMaxWidth())
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
                if (catalog.provider != null) Text(providerLabel(catalog.provider), style = MiuixTheme.textStyles.footnote2)
              }
            }
          }
          item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Text("托管主机 · ${catalog.hosts.size}", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
              TextButton(text = if (busy) "刷新中…" else "刷新", enabled = !busy, onClick = {
                scope.launch {
                  busy = true; error = null
                  try { manager?.refresh(); reload() }
                  catch (cancel: CancellationException) { throw cancel }
                  catch (e: Exception) { error = userFacingError(e, "刷新中枢主机失败") }
                  finally { busy = false }
                }
              })
            }
          }
          items(catalog.hosts, key = { it.hostId }) { host ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(host.name, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Spacer(Modifier.width(6.dp))
                  Text(if (host.online) "在线" else "离线", style = MiuixTheme.textStyles.footnote2.copy(
                    color = if (host.online) MiuixColorTokens.Success else MiuixTheme.colorScheme.onSurfaceVariantSummary))
                }
                Text(listOfNotNull(host.platform.ifBlank { null }, host.harnessVersion?.let { "Harness $it" }, if (added(host.hostId)) "已接入" else "未接入").joinToString(" · "),
                  style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), maxLines = 1)
                Text(host.fingerprint?.let { formatFingerprint(it) } ?: "指纹待确认",
                  style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                  color = when (host.trust) {
                    RemoteTrustState.CHANGED -> MiuixColorTokens.Error
                    RemoteTrustState.PINNED -> MiuixColorTokens.Success
                    RemoteTrustState.UNKNOWN -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                  })
              }
              if (!added(host.hostId)) TextButton(text = "接入", onClick = onEnroll)
            }
          }
          item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(text = "接入更多主机", onClick = onEnroll, modifier = Modifier.weight(1f))
              TextButton(text = "退出登录", onClick = { confirmSignOut = true }, modifier = Modifier.weight(1f))
            }
          }
        }
        error?.let { text -> item { Text(text, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote1) } }
      }
    }
  }
}

private fun providerLabel(value: String): String = when (value) {
  "github" -> "GitHub"
  "zhihu" -> "知乎"
  "google" -> "Google"
  "password" -> "邮箱"
  else -> value
}
