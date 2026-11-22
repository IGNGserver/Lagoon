package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Host inventory is authoritative for both bundles and Loader entries on a DSH SSH link. */
@Composable
internal fun DshExtensionsDialog(
  profile: ServerProfile,
  controller: LagoonController,
  onDismiss: () -> Unit
) {
  // Includes the access gate so disabling the device or Harness cancels in-flight UI operations.
  key(profile) {
    DshExtensionsContent(profile, controller, onDismiss)
  }
}

@Composable
private fun DshExtensionsContent(
  profile: ServerProfile,
  controller: LagoonController,
  onDismiss: () -> Unit
) {
  val scope = rememberCoroutineScope()
  val profileId = profile.id
  var bundles by remember { mutableStateOf(emptyList<DshPluginBundle>()) }
  var entries by remember { mutableStateOf(emptyList<DshPluginEntry>()) }
  var busy by remember { mutableStateOf(true) }
  var saving by remember { mutableStateOf(false) }
  var bundlesError by remember { mutableStateOf<String?>(null) }
  var entriesError by remember { mutableStateOf<String?>(null) }
  var changeError by remember { mutableStateOf<String?>(null) }
  var changeNote by remember { mutableStateOf<String?>(null) }
  val accessible = profile.effectiveEnabled && profile.protocol == HarnessProtocol.DSH

  suspend fun loadInventory() {
    busy = true
    try {
      // The two endpoints may differ in support; a failed one must not hide the other's result.
      try { bundles = controller.pluginBundles(profile); bundlesError = null }
      catch (cancel: CancellationException) { throw cancel }
      catch (e: Exception) { bundlesError = userFacingError(e, "读取插件包失败") }
      try { entries = controller.pluginEntries(profile); entriesError = null }
      catch (cancel: CancellationException) { throw cancel }
      catch (e: Exception) { entriesError = userFacingError(e, "读取插件条目失败") }
    } finally { busy = false }
  }

  fun change(action: suspend () -> Boolean) {
    if (!accessible || busy || saving) return
    saving = true; changeError = null; changeNote = null
    scope.launch {
      try {
        check(action()) { "Host 未确认插件变更；请检查连接、支持情况与权限" }
        changeNote = "Host 已接收变更；是否已应用以 Host 状态为准，部分插件需要重启 DSH。"
        controller.notifyOverviewAction(profileId)
      } catch (cancel: CancellationException) { throw cancel }
      catch (e: Exception) { changeError = userFacingError(e, "切换插件失败") }
      finally {
        try { loadInventory() } finally { saving = false }
      }
    }
  }

  LaunchedEffect(profileId) {
    if (accessible) loadInventory() else busy = false
  }
  SuperDialog(title = "主机扩展", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("列出当前 DSH 返回的插件包与 Loader 条目；只读原因由 Host 提供。安装、卸载与配置仍在电脑上完成，开关可能需要重启 DSH 才生效。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      if (!accessible) Text("此 Harness 或所属设备已停用，无法读取或修改插件。", style = MiuixTheme.textStyles.footnote2)
      if (busy) Text("正在读取插件…", style = MiuixTheme.textStyles.footnote2)
      HyperGroupTitle("插件包")
      bundles.forEach { bundle ->
        HyperSwitchRow(
          title = bundle.name,
          summary = listOfNotNull(bundle.version?.let { "v$it" }, if (bundle.installed) "已安装" else "未安装",
            if (bundle.official) "官方" else null, bundle.description, bundle.readOnlyReason?.let { "只读：$it" }).joinToString(" · "),
          checked = bundle.enabled,
          enabled = accessible && !busy && !saving && bundlesError == null && bundle.readOnlyReason == null,
          onCheckedChange = { checked -> change { controller.setPluginBundle(profile, bundle.name, checked) } })
      }
      if (accessible && !busy && bundles.isEmpty() && bundlesError == null) Text("Host 未返回插件包。", style = MiuixTheme.textStyles.footnote2)
      bundlesError?.let { Text(it, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote2) }
      HyperGroupTitle("插件条目")
      entries.forEach { entry ->
        HyperSwitchRow(
          title = entry.moduleName,
          summary = listOfNotNull(entry.entryId, entry.phase?.let { "阶段：$it" }, entry.readOnlyReason?.let { "只读：$it" }).joinToString(" · "),
          checked = entry.enabled,
          enabled = accessible && !busy && !saving && entriesError == null && entry.readOnlyReason == null,
          onCheckedChange = { checked -> change { controller.setPluginEntry(profile, entry.entryId, checked) } })
      }
      if (accessible && !busy && entries.isEmpty() && entriesError == null) Text("Host 未返回插件条目。", style = MiuixTheme.textStyles.footnote2)
      entriesError?.let { Text(it, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote2) }
      changeNote?.let { Text(it, style = MiuixTheme.textStyles.footnote2) }
      changeError?.let { Text(it, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote2) }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        HyperTextAction(text = "关闭", onClick = onDismiss)
        HyperButton(text = "刷新", loading = busy || saving, enabled = accessible, onClick = {
          if (!busy && !saving) {
            busy = true
            scope.launch { loadInventory() }
          }
        })
      }
    }
  }
}

/** Minimal model credential setup for the Host's default DeepSeek key. */
@Composable
internal fun DshModelKeyDialog(
  profile: ServerProfile,
  controller: LagoonController,
  onDismiss: () -> Unit
) {
  val scope = rememberCoroutineScope()
  val profileId = profile.id
  var key by remember(profileId) { mutableStateOf("") }
  var ref by remember(profileId) { mutableStateOf("DEEPSEEK_API_KEY") }
  var status by remember(profileId) { mutableStateOf<DshCredentialStatus?>(null) }
  var busy by remember(profileId) { mutableStateOf(false) }
  var error by remember(profileId) { mutableStateOf<String?>(null) }
  var confirmingClear by remember(profileId) { mutableStateOf(false) }
  suspend fun readStatus(reference: String) {
    status = controller.credentialStatus(profile, reference)
    check(status != null) { "Host 未返回凭据状态；请检查连接或支持情况" }
  }
  fun load() {
    if (busy || !profile.effectiveEnabled || ref.isBlank()) return
    val reference = ref
    busy = true; error = null; status = null
    scope.launch {
      try { readStatus(reference) }
      catch (cancel: CancellationException) { throw cancel }
      catch (e: Exception) { error = userFacingError(e, "读取主机密钥失败") }
      finally { busy = false }
    }
  }
  LaunchedEffect(profileId, profile.effectiveEnabled) {
    if (profile.effectiveEnabled) load() else { status = null; error = "此 Harness 或所属设备已停用" }
  }
  SuperDialog(title = "主机模型密钥", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("写入 Host 凭据引用；值不会回显。常见引用是 DEEPSEEK_API_KEY，其他服务商按 Host 配置填写。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextField(value = ref, onValueChange = { if (!busy) { ref = it.trim(); status = null; error = null } }, label = "凭据引用", modifier = Modifier.weight(1f))
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
        HyperButton(text = "保存", loading = busy, enabled = profile.effectiveEnabled && status?.writable == true && key.isNotBlank(), onClick = {
          if (busy || status?.writable != true) return@HyperButton
          val reference = ref
          val value = key
          busy = true; error = null
          scope.launch {
            try {
              check(controller.setCredential(profile, reference, value)) { "Host 未确认密钥保存" }
              key = ""
              readStatus(reference)
              controller.notifyOverviewAction(profileId)
            } catch (cancel: CancellationException) { throw cancel }
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
          if (!busy && profile.effectiveEnabled && status?.writable == true) {
            val reference = ref
            busy = true; error = null
            scope.launch {
              try {
                check(controller.clearCredential(profile, reference)) { "Host 未确认密钥清除" }
                key = ""
                readStatus(reference)
                controller.notifyOverviewAction(profileId)
              } catch (cancel: CancellationException) { throw cancel }
              catch (e: Exception) { error = userFacingError(e, "清除失败") }
              finally { busy = false }
            }
          }
        }
      }
    }
  }
}

/** Creates one custom pi-ai route; writes the route first, then its credential if supplied. */
@Composable
internal fun DshProviderDialog(
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
        HyperButton(text = "保存", loading = busy, enabled = !busy && profile.effectiveEnabled && route.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank() && keyRef.isNotBlank(), onClick = {
          if (busy || !profile.effectiveEnabled) return@HyperButton
          scope.launch {
            busy = true; error = null
            try {
              check(controller.createProviderRoute(profile, route, displayName, api, baseUrl, keyRef, modelId, apiKey)) { "Host 未确认模型服务商保存；请检查连接、支持情况与权限" }
              controller.notifyOverviewAction(profile.id)
              onDismiss()
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = userFacingError(e, "保存模型服务商失败") }
            finally { busy = false }
          }
        })
      }
    }
  }
}
