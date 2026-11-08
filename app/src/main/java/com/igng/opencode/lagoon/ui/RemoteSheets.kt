package com.igng.opencode.lagoon.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DEFAULT_REMOTE_ORIGIN = "https://dsh.r2049.cn/"

internal fun formatFingerprint(value: String?): String {
  val raw = value.orEmpty().uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
  return if (raw.length == 12) raw.chunked(4).joinToString(" ") else raw.ifBlank { "未提供" }
}

private fun openLoginUrl(context: android.content.Context, url: String): Boolean = try {
  context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  true
} catch (_: ActivityNotFoundException) { false } catch (_: Exception) { false }

/**
 * DSH Remote account onboarding: browser/QR or email login, Host selection, explicit fingerprint
 * confirmation, then one ServerProfile per selected Host. The account token stays in this screen.
 */
@Composable
internal fun RemoteEnrollSheet(
  controller: LagoonController,
  onDismiss: () -> Unit,
  onDone: (added: Int) -> Unit
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  var origin by rememberSaveable { mutableStateOf(DEFAULT_REMOTE_ORIGIN) }
  var allowHttp by rememberSaveable { mutableStateOf(false) }
  val managerResult = remember(origin, allowHttp) { runCatching { controller.remoteAccountManager(origin.trim(), allowHttp) } }
  val manager = managerResult.getOrNull()
  var providers by remember { mutableStateOf(emptyList<RemoteOAuthProvider>()) }
  var providersLoading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var pending by remember { mutableStateOf<RemoteBrowserLogin?>(null) }
  var candidates by remember { mutableStateOf(emptyList<RemoteHostCandidate>()) }
  var selected by remember { mutableStateOf(setOf<String>()) }
  var review by remember { mutableStateOf(false) }
  var confirmed by remember { mutableStateOf(setOf<String>()) }
  var account by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var showSelfHosted by rememberSaveable { mutableStateOf(false) }
  var resultText by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(manager) {
    providers = emptyList()
    pending = null
    busy = false
    candidates = emptyList()
    selected = emptySet()
    confirmed = emptySet()
    review = false
    error = null
    if (manager == null) {
      error = managerResult.exceptionOrNull()?.message ?: "服务器地址无效"
      return@LaunchedEffect
    }
    providersLoading = true
    try { providers = manager.providers() }
    catch (cancel: CancellationException) { throw cancel }
    catch (e: Exception) { error = userFacingError(e, "没能读取登录方式") }
    finally { providersLoading = false }
  }

  LaunchedEffect(pending?.qrId) {
    val login = pending ?: return@LaunchedEffect
    while (pending?.qrId == login.qrId) {
      delay(2_000)
      if (pending?.qrId != login.qrId) return@LaunchedEffect
      try {
        when (val polled = manager!!.pollBrowserLogin(login)) {
          RemoteQrPoll.Pending -> Unit
          RemoteQrPoll.Expired -> { error = "登录超时，请重新发起"; pending = null }
          is RemoteQrPoll.Complete -> {
            busy = true
            try {
              candidates = manager.completeBrowserLogin(login, polled.accountToken)
              pending = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = userFacingError(e, "登录后读取主机失败"); pending = null }
            finally { busy = false }
          }
        }
      } catch (cancel: CancellationException) { throw cancel }
      catch (e: Exception) { error = userFacingError(e, "登录状态读取失败"); pending = null }
    }
  }

  fun loadCandidates(action: suspend () -> List<RemoteHostCandidate>) {
    if (busy) return
    scope.launch {
      busy = true; error = null
      try {
        candidates = action()
        selected = emptySet()
        confirmed = emptySet()
        review = false
      } catch (cancel: CancellationException) { throw cancel }
      catch (e: Exception) { error = userFacingError(e, "登录 DSH 远程中枢失败") }
      finally { busy = false }
    }
  }

  HyperBottomSheet(title = "DSH 远程中枢", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (candidates.isEmpty()) {
          item {
            Text("登录后选择要接入的电脑。每台主机会在本机注册一个独立设备身份，用于同时保持连接。",
              style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          }
          item { TextField(value = origin, onValueChange = { origin = it; allowHttp = cleartextConsentAfterUrlEdit(origin, it, allowHttp); error = null }, label = "中枢服务器地址") }
          if (origin.startsWith("http://", true)) item {
            HyperSwitchRow(title = "允许此地址使用 HTTP", summary = "只在你信任的网络中开启", checked = allowHttp, onCheckedChange = { allowHttp = it })
          }
          if (providersLoading) item { Text("正在读取登录方式…", style = MiuixTheme.textStyles.footnote2) }
          val browserProviders = providers.filter { it.qrStartUrl != null }
          if (browserProviders.isNotEmpty()) {
            item { Text("在浏览器中登录", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)) }
            items(browserProviders, key = { it.id }) { provider ->
              HyperButton(text = "使用 ${provider.displayName} 登录", onClick = {
                if (pending != null || busy) return@HyperButton
                scope.launch {
                  busy = true; error = null
                  try {
                    val login = manager!!.beginBrowserLogin(provider)
                    pending = login
                    if (!openLoginUrl(context, login.loginUrl)) {
                      error = "没能打开浏览器，可复制登录链接后手动打开"; pending = null
                    }
                  } catch (cancel: CancellationException) { throw cancel }
                  catch (e: Exception) { error = userFacingError(e, "没能发起登录") }
                  finally { busy = false }
                }
              }, modifier = Modifier.fillMaxWidth(), enabled = !busy && pending == null)
            }
          } else if (!providersLoading && manager != null) {
            item {
              Text("这个中枢没有提供浏览器登录；可在下面用邮箱密码登录，或换一个中枢地址。",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
          }
          item {
            HyperButton(text = if (showSelfHosted) "收起自建中枢登录" else "自建中枢 · 邮箱登录", variant = HyperButtonVariant.SECONDARY,
              onClick = { showSelfHosted = !showSelfHosted }, modifier = Modifier.fillMaxWidth())
          }
          if (showSelfHosted) {
            item { TextField(value = account, onValueChange = { account = it }, label = "邮箱 / 账号") }
            item {
              TextField(value = password, onValueChange = { password = it }, label = "密码",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
            item {
              HyperButton(text = "邮箱登录", loading = busy, enabled = manager != null && account.isNotBlank() && password.isNotBlank(),
                onClick = { if (manager != null) loadCandidates { manager.loginWithPassword(account.trim(), password) } },
                modifier = Modifier.fillMaxWidth())
            }
          }
          pending?.let { login ->
            item {
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                InfiniteProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("在浏览器里完成登录…", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2)
                HyperTextAction(text = "取消", onClick = { pending = null })
              }
            }
            item {
              HyperButton(text = "复制登录链接", variant = HyperButtonVariant.SECONDARY, onClick = {
                clipboard.setText(AnnotatedString(login.loginUrl))
                android.widget.Toast.makeText(context, "已复制登录链接", android.widget.Toast.LENGTH_SHORT).show()
              }, modifier = Modifier.fillMaxWidth())
            }
          }
        } else if (!review) {
          item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text("选择主机", style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.Medium))
                Text("已发现 ${candidates.size} 台 · 已选 ${selected.size}", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
              }
              HyperTextAction(text = "重新登录", onClick = { candidates = emptyList(); selected = emptySet() })
            }
          }
          items(candidates, key = { it.hostId }) { candidate ->
            val enabled = candidate.canEnroll
            val checked = candidate.hostId in selected
            Row(Modifier.fillMaxWidth().clip(miuixSquircleShape(14.dp))
              .clickable(enabled = enabled) {
                selected = if (checked) selected - candidate.hostId else selected + candidate.hostId
              }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
              }
              Spacer(Modifier.width(8.dp))
              Column(Modifier.weight(1f)) {
                Text(candidate.meta.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
                Text(listOfNotNull(
                  candidate.meta.platform.ifBlank { null },
                  candidate.meta.harnessVersion?.let { "Harness $it" },
                  if (candidate.meta.online) "在线" else "离线",
                  if (candidate.canEnroll) null else "身份不可用"
                ).joinToString(" · "), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), maxLines = 1)
                Text("指纹 ${formatFingerprint(candidate.meta.fingerprint)}", style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                  color = when (candidate.meta.trust) {
                    RemoteTrustState.CHANGED -> MiuixColorTokens.Error
                    RemoteTrustState.PINNED -> MiuixColorTokens.Success
                    RemoteTrustState.UNKNOWN -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                  })
              }
            }
          }
          item {
            HyperButton(text = "下一步：核对指纹", enabled = selected.isNotEmpty() && !busy, onClick = {
              confirmed = emptySet(); review = true
            }, modifier = Modifier.fillMaxWidth())
          }
        } else {
          item {
            Text("核对远程主机指纹", style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.Medium))
            Text("在电脑的远程插件状态页核对；全部勾选后才能接入。", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          }
          val chosen = candidates.filter { it.hostId in selected }
          items(chosen, key = { it.hostId }) { candidate ->
            val checked = candidate.hostId in confirmed
            Row(Modifier.fillMaxWidth().clip(miuixSquircleShape(14.dp)).clickable {
              confirmed = if (checked) confirmed - candidate.hostId else confirmed + candidate.hostId
            }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Checkbox(checked = checked, onCheckedChange = null)
              }
              Spacer(Modifier.width(8.dp))
              Column(Modifier.weight(1f)) {
                Text(candidate.meta.name, style = MiuixTheme.textStyles.body1)
                Text(formatFingerprint(candidate.meta.fingerprint), style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace))
              }
            }
          }
          item {
            HyperButton(text = "接入选中主机", loading = busy, enabled = confirmed.size == chosen.size && chosen.isNotEmpty(), onClick = {
              scope.launch {
                busy = true; error = null
                try {
                  val enrollment = manager!!.enroll(chosen, chosen.associate { it.hostId to it.meta.fingerprint!! })
                  val added = controller.addRemoteProfiles(enrollment.catalog, chosen.map { it.meta })
                  resultText = if (enrollment.failures.isEmpty()) "已接入 ${added.size} 台远程主机"
                    else "已接入 ${added.size} 台；${enrollment.failures.size} 台失败：${enrollment.failures.values.first()}"
                  onDone(added.size)
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { error = userFacingError(e, "接入远程主机失败") }
                finally { busy = false }
              }
            }, modifier = Modifier.fillMaxWidth())
          }
          item { HyperButton(text = "返回选择", variant = HyperButtonVariant.SECONDARY, onClick = { review = false }, modifier = Modifier.fillMaxWidth()) }
        }
        resultText?.let { text -> item { Text(text, color = MiuixColorTokens.Success, style = MiuixTheme.textStyles.footnote1) } }
        error?.let { text ->
          item {
            Column {
              Text(text, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote1)
              HyperButton(text = "关闭", variant = HyperButtonVariant.SECONDARY, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
          }
        }
      }
    }
  }
}

/** Compact entry shown from Settings; the full account page is the enrollment sheet for now. */
@Composable
internal fun RemoteAccountSummary(
  controller: LagoonController,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier
) {
  val catalogs = remember { controller.remoteCatalogs() }
  val hosts = catalogs.flatMap { it.hosts }.size
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(if (catalogs.isEmpty()) "连接 DSH 远程中枢" else "DSH 远程中枢账号", style = MiuixTheme.textStyles.body1)
        Text(
          if (catalogs.isEmpty()) "用浏览器登录，安全接入名下托管的 DSH 电脑"
          else "${catalogs.first().account} · $hosts 台托管主机",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
          maxLines = 1, overflow = TextOverflow.Ellipsis
        )
      }
      HyperTextAction(text = if (catalogs.isEmpty()) "登录" else "管理", onClick = onOpen)
    }
  }
}

/** Read-only remote server edit panel; keeps the management flow out of the generic form. */
@Composable
internal fun RemoteServerSummary(
  existing: ServerProfile?,
  onDetails: (() -> Unit)? = null,
  onExtensions: (() -> Unit)? = null,
  onModelKey: (() -> Unit)? = null,
  onProvider: (() -> Unit)? = null,
  onManage: () -> Unit,
  onDelete: (() -> Unit)?,
  onCancel: () -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(if (existing == null) "DSH 远程主机" else existing.name, style = MiuixTheme.textStyles.title3)
    Text(
      if (existing == null) "DSH 托管主机通过中枢账号登录接入；登录后选择要连接的电脑。"
      else "${existing.remoteHostFingerprint?.let { formatFingerprint(it) } ?: "指纹待确认"} · ${existing.url}",
      style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    )
    HyperButton(text = if (existing == null) "登录中枢并选择主机" else "管理中枢与主机", onClick = onManage, modifier = Modifier.fillMaxWidth())
    if (existing != null && onDetails != null) {
      HyperButton(text = "网络与连接", variant = HyperButtonVariant.SECONDARY, onClick = onDetails, modifier = Modifier.fillMaxWidth())
    }
    if (existing != null && onExtensions != null) {
      HyperButton(text = "主机扩展", variant = HyperButtonVariant.SECONDARY, onClick = onExtensions, modifier = Modifier.fillMaxWidth())
    }
    if (existing != null && onModelKey != null) {
      HyperButton(text = "主机模型密钥", variant = HyperButtonVariant.SECONDARY, onClick = onModelKey, modifier = Modifier.fillMaxWidth())
    }
    if (existing != null && onProvider != null) {
      HyperButton(text = "添加自定义模型服务商", variant = HyperButtonVariant.SECONDARY, onClick = onProvider, modifier = Modifier.fillMaxWidth())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      HyperTextAction(text = "取消", onClick = onCancel)
      if (onDelete != null) HyperTextAction(text = "从此设备移除", danger = true, onClick = onDelete)
    }
  }
}


/** One diagnostic dialog; the measurement is an encrypted RPC round trip, not a network RTT. */
@Composable
internal fun RemoteConnectionDialog(
  profile: ServerProfile,
  state: LagoonState,
  controller: LagoonController,
  onDismiss: () -> Unit
) {
  val scope = rememberCoroutineScope()
  var probe by remember { mutableStateOf<RemoteProbe?>(null) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var doctor by remember { mutableStateOf<RemoteDoctor?>(null) }
  val clipboard = LocalClipboardManager.current
  val context = LocalContext.current
  suspend fun reloadDoctor() {
    doctor = runCatching { controller.remoteDoctor(profile.id) }.getOrNull()
  }
  LaunchedEffect(profile.id) { reloadDoctor() }
  SuperDialog(title = "网络与连接", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      val snapshot = state.serverSnapshots()[profile.id]
      val rows = buildList {
        add("主机" to profile.name)
        add("连接方式" to "DSH 远程中枢")
        add("地址" to profile.url)
        add("状态" to when {
          !profile.enabled -> "已停用"
          snapshot?.connected == true && snapshot.streamConnected -> "已连接"
          snapshot?.connected == true -> "已连接 · 实时同步恢复中"
          snapshot?.loading == true -> "连接中"
          !snapshot?.error.isNullOrBlank() -> snapshot?.error ?: "需检查"
          else -> "未连接"
        })
        add("当前路径" to "Relay 中继")
        add("加密" to "Noise IK · 端到端加密")
        add("服务版本" to (snapshot?.version?.ifBlank { null } ?: "未知"))
        add("主机指纹" to (profile.remoteHostFingerprint?.let { formatFingerprint(it) } ?: "待确认"))
        add("加密 RPC 往返" to (probe?.let { "${it.durationMillis} 毫秒" } ?: "未测量"))
        add("最近联络" to (snapshot?.lastReadAt?.takeIf { it > 0 }?.let { agoText(it) } ?: "未知"))
      }
      val doctorRows = doctor?.diagnosticLines().orEmpty()
        .filterNot { (label, _) -> rows.any { it.first == label } }
      (rows + doctorRows).forEach { (label, value) ->
        Row(Modifier.fillMaxWidth()) {
          Text(label, Modifier.width(96.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          Text(value, style = MiuixTheme.textStyles.footnote2)
        }
      }
      if (doctor != null) {
        Text("诊断只包含连接阶段、计数与错误码，不包含消息正文或令牌。",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      Text("当前版本使用 Relay；中枢下发独立数据通道时自动附加，LAN / P2P / TURN 尚未启用。测量只反映一次加密 RPC，不代表网络层 RTT。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      error?.let { Text(it, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote2) }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        doctor?.let { report ->
          HyperTextAction(text = "复制诊断", onClick = {
            clipboard.setText(AnnotatedString(report.asText()))
            android.widget.Toast.makeText(context, "已复制诊断", android.widget.Toast.LENGTH_SHORT).show()
          })
        }
        Spacer(Modifier.weight(1f))
        HyperTextAction(text = "关闭", onClick = onDismiss)
      }
      HyperButton(text = "重新测量", loading = busy, enabled = profile.enabled, modifier = Modifier.fillMaxWidth(), onClick = {
        scope.launch {
          busy = true; error = null
          try {
            probe = controller.probeRemote(profile.id)
            reloadDoctor()
          } catch (e: Exception) { error = userFacingError(e, "测量失败") }
          finally { busy = false }
        }
      })
    }
  }
}


/** Official plugin bundle switches over the authenticated Remote settings channel. */
@Composable
internal fun RemoteExtensionsDialog(
  profile: ServerProfile,
  controller: LagoonController,
  onDismiss: () -> Unit
) {
  val scope = rememberCoroutineScope()
  var bundles by remember { mutableStateOf(emptyList<RemotePluginBundle>()) }
  var busy by remember { mutableStateOf(true) }
  var saving by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  fun reload() {
    scope.launch {
      busy = true; error = null
      try { bundles = controller.pluginBundles(profile.id) }
      catch (e: Exception) { error = userFacingError(e, "读取插件失败") }
      finally { busy = false }
    }
  }
  LaunchedEffect(profile.id) { reload() }
  SuperDialog(title = "主机扩展", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("只列出 Host 官方允许远程开关的插件 bundle；安装、卸载和插件配置仍需在电脑上完成。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      if (busy) Text("正在读取插件…", style = MiuixTheme.textStyles.footnote2)
      bundles.forEach { bundle ->
        HyperSwitchRow(
          title = bundle.name,
          summary = listOfNotNull(bundle.version?.let { "v$it" }, bundle.description, bundle.readOnlyReason).joinToString(" · "),
          checked = bundle.enabled,
          enabled = bundle.readOnlyReason == null && saving == null,
          onCheckedChange = { checked ->
            saving = bundle.name
            scope.launch {
              try { controller.setPluginBundle(profile.id, bundle.name, checked); reload() }
              catch (e: Exception) { error = userFacingError(e, "切换插件失败") }
              finally { saving = null }
            }
          })
      }
      if (!busy && bundles.isEmpty() && error == null) Text("没有可远程开关的插件。", style = MiuixTheme.textStyles.footnote2)
      error?.let { Text(it, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote2) }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        HyperTextAction(text = "关闭", onClick = onDismiss)
        HyperButton(text = "刷新", loading = busy, onClick = { reload() })
      }
    }
  }
}


/** Minimal model credential setup for the Host's default DeepSeek key. */
@Composable
internal fun RemoteModelKeyDialog(
  profile: ServerProfile,
  controller: LagoonController,
  onDismiss: () -> Unit
) {
  val scope = rememberCoroutineScope()
  var key by remember { mutableStateOf("") }
  var ref by remember { mutableStateOf("DEEPSEEK_API_KEY") }
  var status by remember { mutableStateOf<RemoteCredentialStatus?>(null) }
  var busy by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var confirmingClear by remember { mutableStateOf(false) }
  fun load() {
    scope.launch {
      busy = true; error = null
      try { status = controller.credentialStatus(profile.id, ref) }
      catch (e: Exception) { error = userFacingError(e, "读取主机密钥失败") }
      finally { busy = false }
    }
  }
  LaunchedEffect(profile.id) { load() }
  SuperDialog(title = "主机模型密钥", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("写入 Host 凭据引用；值不会回显。常见引用是 DEEPSEEK_API_KEY，其他服务商按 Host 配置填写。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextField(value = ref, onValueChange = { ref = it.trim() }, label = "凭据引用", modifier = Modifier.weight(1f))
        HyperTextAction(text = "读取", enabled = !busy && ref.isNotBlank(), onClick = { load() })
      }
      if (busy) Text("正在读取…", style = MiuixTheme.textStyles.footnote2)
      status?.let {
        Text(if (it.configured) "已配置${it.source?.let { source -> " · $source" }.orEmpty()}${if (!it.writable) " · 只读" else ""}" else "未配置",
          style = MiuixTheme.textStyles.footnote2, color = if (it.configured) MiuixColorTokens.Success else MiuixTheme.colorScheme.onSurfaceVariantSummary)
      }
      TextField(value = key, onValueChange = { key = it }, label = "新的 API Key",
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
      error?.let { Text(it, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote2) }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        HyperTextAction(text = "关闭", onClick = onDismiss)
        if (status?.configured == true && status?.writable == true) HyperTextAction(text = "清除", danger = true, enabled = !busy, onClick = { confirmingClear = true })
        HyperButton(text = "保存", loading = busy, enabled = key.isNotBlank(), onClick = {
          scope.launch {
            busy = true; error = null
            try { controller.setCredential(profile.id, ref, key); key = ""; load() }
            catch (e: Exception) { error = userFacingError(e, "保存失败") }
            finally { busy = false }
          }
        })
      }
    }
  }

  if (confirmingClear) {
    SuperDialog(title = "清除主机模型密钥？", show = true, onDismissRequest = { confirmingClear = false }) {
      Column {
        Text("将清除 Host 上 ${ref.ifBlank { "当前引用" }} 的凭据。依赖它的模型可能立即无法使用。")
        DialogActions("取消", { confirmingClear = false }, "确认清除", danger = true) {
          confirmingClear = false
          scope.launch {
            busy = true; error = null
            try { controller.clearCredential(profile.id, ref); key = ""; load() }
            catch (e: Exception) { error = userFacingError(e, "清除失败") }
            finally { busy = false }
          }
        }
      }
    }
  }
}


/** Creates one custom pi-ai route; writes the route first, then its credential if supplied. */
@Composable
internal fun RemoteProviderDialog(
  profile: ServerProfile,
  controller: LagoonController,
  onDismiss: () -> Unit
) {
  val scope = rememberCoroutineScope()
  var route by remember { mutableStateOf("") }
  var displayName by remember { mutableStateOf("") }
  var api by remember { mutableStateOf("openai-completions") }
  var baseUrl by remember { mutableStateOf("") }
  var modelId by remember { mutableStateOf("") }
  var keyRef by remember { mutableStateOf("") }
  var apiKey by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val apiOptions = listOf("openai-completions", "openai-responses", "anthropic-messages")
  fun suggestedRef(value: String) = value.uppercase().replace('-', '_').let { if (it.isBlank()) "" else "${it}_API_KEY" }
  SuperDialog(title = "自定义模型服务商", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("在此设备的高级配置中创建模型连接。请按服务商文档填写；密钥值保存后不会回显。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      TextField(value = route, onValueChange = { route = it.trim(); if (keyRef.isBlank()) keyRef = suggestedRef(it.trim()) }, label = "路由 ID（小写字母/数字/-）")
      TextField(value = displayName, onValueChange = { displayName = it }, label = "显示名称")
      HyperDropdownRow(
        title = "协议",
        items = apiOptions,
        selectedIndex = apiOptions.indexOf(api).coerceAtLeast(0),
        onSelectedIndexChange = { api = apiOptions[it] }
      )
      TextField(value = baseUrl, onValueChange = { baseUrl = it.trim() }, label = "接口地址（Base URL）")
      TextField(value = modelId, onValueChange = { modelId = it.trim() }, label = "模型 ID")
      TextField(value = keyRef, onValueChange = { keyRef = it.trim() }, label = "密钥引用")
      TextField(value = apiKey, onValueChange = { apiKey = it }, label = "API Key（可留空，稍后再填）",
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
      error?.let { Text(it, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote2) }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        HyperTextAction(text = "取消", onClick = onDismiss)
        HyperButton(text = "保存", loading = busy, enabled = route.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank() && keyRef.isNotBlank(), onClick = {
          scope.launch {
            busy = true; error = null
            try {
              controller.createProviderRoute(profile.id, route, displayName, api, baseUrl, keyRef, modelId, apiKey)
              onDismiss()
            } catch (e: Exception) { error = userFacingError(e, "保存模型服务商失败") }
            finally { busy = false }
          }
        })
      }
    }
  }
}


private fun agoText(at: Long): String {
  val seconds = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
  return when {
    seconds < 5 -> "刚刚"
    seconds < 60 -> "${seconds} 秒前"
    seconds < 3600 -> "${seconds / 60} 分钟前"
    else -> "${seconds / 3600} 小时前"
  }
}
