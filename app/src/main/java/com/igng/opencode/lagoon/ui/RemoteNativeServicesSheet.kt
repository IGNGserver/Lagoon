package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Discovery reads the Host catalog only; each leaf needs a separate, explicit enable action. */
@Composable
internal fun RemoteNativeServicesSheet(
  controller: LagoonController,
  profile: ServerProfile,
  sourceCatalog: RemoteCatalog? = null,
  sourceHost: RemoteHostMeta? = null,
  onDismiss: () -> Unit
) {
  key(controller, profile.id, profile.url, profile.username, profile.remoteHostId, sourceCatalog?.signedOut) {
    val state by controller.state.collectAsState()
    var catalog by remember { mutableStateOf<NativeServiceCatalog?>(null) }
    var busy by remember { mutableStateOf(true) }
    var unsupported by remember { mutableStateOf(false) }
    var dshAvailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val savedProfiles = state.profiles.filter { it.matchesNativeServiceHost(profile) }
    val savedDsh = state.profiles.firstOrNull { it.remoteServiceId == null && it.kind in setOf(ServerKind.DSH, ServerKind.DSH_REMOTE) &&
      it.remoteHostId == profile.remoteHostId && it.username.trim() == profile.username.trim() &&
      runCatching { canonicalRemoteOrigin(it.url) == canonicalRemoteOrigin(profile.url) }.getOrDefault(false) }

    LaunchedEffect(refresh) {
      busy = true
      error = null
      unsupported = false
      dshAvailable = false
      // A failed refresh must not leave an old descriptor selectable.
      catalog = null
      try {
        check(sourceCatalog?.signedOut != true) { "中枢账号已退出登录，请重新登录后再发现 Harness" }
        val services = if (sourceCatalog != null && sourceHost != null) controller.remoteHostServices(sourceCatalog, sourceHost)
          else controller.remoteHostServices(profile.id)
        catalog = services.native
        dshAvailable = services.dshAvailable
        unsupported = catalog == null
      } catch (cancel: CancellationException) { throw cancel }
      catch (failure: Exception) { error = userFacingError(failure, "读取 Host 原生服务失败") }
      finally { busy = false }
    }

    fun add(service: NativeServiceDescriptor, enabled: Boolean) {
      if (busy || !service.supported || !service.available || "http" !in service.capabilities) return
      error = null
      try {
        val saved = if (sourceCatalog != null && sourceHost != null) controller.addRemoteNativeService(sourceCatalog, sourceHost, service, enabled)
          else controller.addRemoteNativeService(profile.id, service, enabled = enabled)
        // A concurrent save may have reused an existing disabled leaf; enabling is still explicit.
        if (enabled && !saved.enabled) controller.setProfileEnabled(saved.id, true)
      } catch (cancel: CancellationException) { throw cancel }
      catch (failure: Exception) { error = userFacingError(failure, "保存原生服务失败") }
    }

    fun setEnabled(saved: ServerProfile, enabled: Boolean) {
      error = null
      try { controller.setProfileEnabled(saved.id, enabled) }
      catch (cancel: CancellationException) { throw cancel }
      catch (failure: Exception) { error = userFacingError(failure, "保存启用状态失败") }
    }

    HyperBottomSheet(title = "Host 原生 Harness", show = true, onDismissRequest = onDismiss) {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.78f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        item {
          Text(
            "${profile.name} · 只读取 Host 已登记的原生服务，不会自动启用或批量连接。加入后默认停用；认证由 Host 管理。",
            style = MiuixTheme.textStyles.footnote1.copy(color = HyperTheme.colors.textSecondary)
          )
        }
        item {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
              if (busy) "正在读取服务目录…" else "原生服务 · ${catalog?.services?.size ?: 0}",
              modifier = Modifier.weight(1f),
              style = MiuixTheme.textStyles.footnote1.copy(color = HyperTheme.colors.textSecondary)
            )
            HyperButton(
              text = "刷新", variant = HyperButtonVariant.TERTIARY,
              enabled = !busy, loading = busy,
              onClick = { busy = true; refresh++ }
            )
          }
        }
        error?.let { message ->
          item { HyperInlineNotice(message = message, type = MiuixToastType.ERROR) }
        }
        item {
          HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
            HyperRow(title = "DeepSeek Harness · 原生 Typert", summary = "与 OpenCode 独立选择；只使用 Host 声明的 DSH 能力",
              value = if (dshAvailable) "可用" else if (busy) "检测中" else "未声明")
            val existingDsh = savedDsh
            if (existingDsh != null) HyperSwitchRow(title = "启用 DSH Harness", checked = existingDsh.enabled,
              enabled = !busy && (dshAvailable || existingDsh.enabled),
              summary = "停用只改此 Harness，不会删除主机、其他服务或远端任务",
              onCheckedChange = { setEnabled(existingDsh, it) })
            else Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              fun saveDsh(enabled: Boolean) {
                try { controller.addRemoteDshService(profile, enabled) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = userFacingError(failure, "保存 DSH 连接失败") }
              }
              HyperButton(text = "加入（停用）", enabled = !busy && dshAvailable, modifier = Modifier.weight(1f),
                variant = HyperButtonVariant.SECONDARY, onClick = { saveDsh(false) })
              HyperButton(text = "加入并启用", enabled = !busy && dshAvailable, modifier = Modifier.weight(1f),
                onClick = { saveDsh(true) })
            }
          }
        }
        if (unsupported) item {
          HyperInlineNotice(
            message = "此 Host 尚不支持原生服务发现，请升级 Host 与远程插件后刷新。不会改用 ACP，也不会将原生服务当作 DSH。",
            type = MiuixToastType.WARNING
          )
        }
        val observed = catalog
        if (observed != null) {
          if (observed.completeness != DiscoveryCompleteness.COMPLETE || observed.issues.isNotEmpty()) item {
            HyperInlineNotice(
              message = "本次发现不完整，部分服务可能未列出；不会删除已添加的连接或更改启用偏好。" +
                observed.issues.takeIf { it.isNotEmpty() }?.joinToString(prefix = "\nHost 报告：", separator = "、").orEmpty(),
              type = MiuixToastType.WARNING
            )
          }
          if (observed.services.isEmpty()) item {
            HyperInlineNotice(
              message = if (observed.completeness == DiscoveryCompleteness.COMPLETE)
                "Host 当前没有登记原生 Harness。已有本机连接仍会保留。"
              else "本次没有发现服务，不能据此判断 Host 上没有原生 Harness。",
              type = MiuixToastType.INFO
            )
          }
          items(observed.services, key = { it.id }) { service ->
            NativeServiceCard(
              service = service,
              savedProfiles = savedProfiles.filter { it.remoteServiceId == service.id },
              onAdd = { enabled -> add(service, enabled) },
              onEnabledChange = ::setEnabled
            )
          }
          val unobserved = savedProfiles.filter { saved -> observed.services.none { it.id == saved.remoteServiceId } }
          if (unobserved.isNotEmpty()) item {
            HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
              unobserved.forEachIndexed { index, saved ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
                HyperRow(
                  title = saved.name,
                  summary = "已添加 · 本次未发现；已保留连接与偏好，可在设备详情管理",
                  value = if (saved.enabled) "已启用" else "已停用"
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun NativeServiceCard(
  service: NativeServiceDescriptor,
  savedProfiles: List<ServerProfile>,
  onAdd: (Boolean) -> Unit,
  onEnabledChange: (ServerProfile, Boolean) -> Unit
) {
  val selectable = service.supported && service.available && "http" in service.capabilities
  HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
    HyperRow(
      title = service.name,
      summary = "协议：${service.protocol}\n服务 ID：${service.id}\n能力：" +
        service.capabilities.sorted().joinToString(" · ").ifBlank { "未声明" },
      value = if (service.available) "可用" else "不可用"
    )
    if (!selectable) {
      Text(
        when {
          !service.supported -> "当前客户端不支持此协议，仅展示，不可加入或启用。"
          !service.available -> "Host 报告此服务不可用，请确认服务状态后刷新。"
          else -> "此服务未声明 HTTP 能力，当前不可接入。"
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
      )
    }
    savedProfiles.forEach { saved ->
      HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
      HyperSwitchRow(
        title = "已添加 · ${saved.name}",
        summary = when {
          !saved.parentEnabled -> "启用偏好已保留；所属接入源或主机已停用，当前不会连接"
          !selectable -> "已保留启用偏好；可停用，待服务可用且受支持后再启用"
          else -> "只控制此 Harness；启用会连接，停用不删除连接资料"
        },
        checked = saved.enabled,
        enabled = selectable || saved.enabled,
        onCheckedChange = { enabled -> onEnabledChange(saved, enabled) }
      )
    }
    if (savedProfiles.isEmpty()) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        HyperButton(
          text = "加入（停用）", variant = HyperButtonVariant.SECONDARY,
          modifier = Modifier.weight(1f), enabled = selectable,
          onClick = { onAdd(false) }
        )
        HyperButton(
          text = "加入并启用", modifier = Modifier.weight(1f), enabled = selectable,
          onClick = { onAdd(true) }
        )
      }
    }
  }
}

/** Native leaves share a carrier scope with DSH leaves without claiming to be DSH themselves. */
private fun ServerProfile.matchesNativeServiceHost(host: ServerProfile): Boolean =
  remoteServiceId != null && remoteHostId != null && remoteHostId == host.remoteHostId &&
    username.trim() == host.username.trim() &&
    runCatching { canonicalRemoteOrigin(url) == canonicalRemoteOrigin(host.url) }.getOrDefault(false)
