package com.igng.opencode.lagoon.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.edit
import androidx.core.net.toUri
import com.igng.opencode.lagoon.core.*
import com.igng.opencode.lagoon.system.AppUpdatePhase
import com.igng.opencode.lagoon.system.AppUpdateState
import com.igng.opencode.lagoon.system.AppUpdater
import com.igng.opencode.lagoon.system.LiveUpdateSupport
import com.igng.opencode.lagoon.system.TaskNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.*
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/* ------------------------------------------------------------------ *
 * 信息架构主线：Server → Project/Directory → Session → Conversation
 * 「会话」是唯一的根页面；设置从首页 ⋯ 菜单整页推入。
 * ------------------------------------------------------------------ */

private class DateFormatterCache {
  private var locale: java.util.Locale? = null
  private var formatter: DateFormat? = null
  fun get(current: java.util.Locale): DateFormat {
    val cached = formatter
    if (cached != null && locale == current) return cached
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, current).also {
      locale = current
      formatter = it
    }
  }
}
private val sessionDateFormatters = DateFormatterCache()

/** 相对时间：会话列表需要“刚刚 / 12 分钟前”这类高信息密度时间。 */
internal fun formatRelative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
  if (timestamp <= 0) return ""
  val diff = now - timestamp
  val minute = 60_000L
  val hour = 60 * minute
  val day = 24 * hour
  return when {
    diff < minute -> "刚刚"
    diff < hour -> "${diff / minute} 分钟前"
    diff < day -> "${diff / hour} 小时前"
    diff < 7 * day -> "${diff / day} 天前"
    else -> sessionDateFormatters.get(java.util.Locale.getDefault()).format(Date(timestamp))
  }
}

@Composable
fun SettingsScreen(
  state: LagoonState,
  controller: LagoonController,
  themeMode: ThemeMode,
  onTheme: (ThemeMode) -> Unit,
  previewBack: Boolean,
  onPreviewBack: (Boolean) -> Unit,
  refreshMode: RefreshMode,
  onRefreshMode: (RefreshMode) -> Unit,
  onNotifications: () -> Unit,
  onManageDevices: () -> Unit = {},
  onBack: () -> Unit,
  onMessage: (String, MiuixToastType) -> Unit,
  highRefreshPolicy: HighRefreshPolicy = HighRefreshPolicy.MAX,
  onHighRefreshPolicy: (HighRefreshPolicy) -> Unit = {}
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val updater = remember(context) { AppUpdater.get(context) }
  val update by updater.state.collectAsState()
  val updatePreferences = remember(context) { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
  var includePrerelease by remember {
    val saved = updatePreferences.getString("updateChannel", null)
    val preview = when (saved) { "stable" -> false; "preview" -> true
      else -> updatePreferences.getBoolean("updatePrerelease", true) }
    updatePreferences.edit { putString("updateChannel", if (preview) "preview" else "stable") }
    mutableStateOf(preview)
  }
  LaunchedEffect(updater) { updater.check(includePrerelease, automatic = true) }
  var installAllowed by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
  var pendingInstall by rememberSaveable { mutableStateOf(false) }
  var showReleaseNotes by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(update.release?.version) { showReleaseNotes = false }
  // 只展示系统标准实时更新的状态；不检测任何厂商通道。检测放到 IO 线程执行。
  var notificationsEnabled by remember { mutableStateOf(true) }
  var liveUpdate by remember { mutableStateOf<LiveUpdateSupport?>(null) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  fun refreshSupport() {
    scope.launch {
      val (enabled, support) = withContext(Dispatchers.IO) {
        NotificationManagerCompat.from(context).areNotificationsEnabled() to LiveUpdateSupport.detect(context)
      }
      notificationsEnabled = enabled
      liveUpdate = support
    }
  }
  fun requestInstall() {
    val apk = updater.readyApk()
    if (apk == null) return
    if (!context.packageManager.canRequestPackageInstalls()) {
      pendingInstall = true
      installAllowed = false
      val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
      }.isSuccess
      if (!opened) {
        pendingInstall = false
        onMessage("无法打开“安装未知应用”设置，请手动在系统设置中授权", MiuixToastType.ERROR)
      }
      return
    }
    pendingInstall = false
    installAllowed = true
    val uri = updater.installerUri(apk) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
      onMessage("无法打开系统安装器：${it.message ?: "未知错误"}", MiuixToastType.ERROR)
    }
  }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        refreshSupport()
        installAllowed = context.packageManager.canRequestPackageInstalls()
        if (pendingInstall && installAllowed) {
          pendingInstall = false
          requestInstall()
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer); refreshSupport()
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
  val clipboard = LocalClipboardManager.current
  var crashReport by remember { mutableStateOf<String?>(null) }
  var confirmingCacheClear by remember { mutableStateOf(false) }
  var confirmingCrashClear by remember { mutableStateOf(false) }
  var cacheUsage by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    crashReport = withContext(Dispatchers.IO) { CrashLog.read(context) }
    cacheUsage = controller.transcriptCacheUsage()?.let { "已保存 ${it.sessions} 个会话 · ${formatBytes(it.bytes)} / 上限 ${formatBytes(TranscriptStore.DEFAULT_BUDGET_BYTES)}" }
  }

  // HyperOS 4 settings: grouped squircle island cards on the mineral canvas surface.
  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
  PageTopBar("设置", onBack)
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .overScrollVertical(),
    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 48.dp)
  ) {
    item {
      HyperGroupTitle("设备")
      HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
        HyperRow(
          title = "设备管理",
          summary = buildString {
            append("${state.devices.size} 台设备")
            append(" · ${state.enabledProfiles.size} 个 Harness 已启用")
            if (state.deviceGroups.isNotEmpty()) append(" · ${state.deviceGroups.size} 个设备组")
          },
          showChevron = true,
          onClick = onManageDevices
        )
      }
    }

    item {
      HyperGroupTitle("显示与交互")
      HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
        HyperDropdownRow(
          title = "深浅色外观",
          items = ThemeMode.entries.map { it.label },
          selectedIndex = ThemeMode.entries.indexOf(themeMode),
          onSelectedIndexChange = { onTheme(ThemeMode.entries[it]) }
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
        HyperSwitchRow(
          title = "返回手势预览",
          summary = "拖动返回手势时轻微预览上一级页面",
          checked = previewBack,
          onCheckedChange = onPreviewBack
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
        HyperDropdownRow(
          title = "高刷新率策略",
          summary = "在高刷屏（120Hz / 144Hz）上请求当前分辨率的最高刷新率档位",
          items = HighRefreshPolicy.entries.map { it.label },
          selectedIndex = HighRefreshPolicy.entries.indexOf(highRefreshPolicy),
          onSelectedIndexChange = { onHighRefreshPolicy(HighRefreshPolicy.entries[it]) }
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
        HyperDropdownRow(
          title = "自动刷新",
          items = RefreshMode.entries.map { it.label },
          selectedIndex = RefreshMode.entries.indexOf(refreshMode),
          onSelectedIndexChange = { onRefreshMode(RefreshMode.entries[it]) }
        )
      }
    }

    item {
      HyperGroupTitle("授权")
      HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
        HyperDropdownRow(
          title = "权限请求处理",
          items = listOf("每次询问（推荐）", "隐藏授权提示", "始终允许一次（高风险）"),
          selectedIndex = when {
            state.permissionPolicy.autoApprove -> 2
            state.permissionPolicy.hide -> 1
            else -> 0
          },
          onSelectedIndexChange = { index ->
            controller.setPermissionPolicy(state.permissionPolicy.copy(hide = index == 1, autoApprove = index == 2))
          }
        )
      }
      if (state.permissionPolicy.autoApprove || state.permissionPolicy.hide) {
        HyperInlineNotice(
          message = if (state.permissionPolicy.autoApprove) "高风险模式：所有设备的权限请求会自动按“允许一次”处理，不会保存永久规则。" else "授权提示已隐藏，任务可能停在等待权限状态；建议恢复“每次询问”。",
          type = MiuixToastType.WARNING,
          action = "恢复询问",
          onAction = { controller.setPermissionPolicy(state.permissionPolicy.copy(hide = false, autoApprove = false)) },
          modifier = Modifier.padding(top = 8.dp)
        )
      }
    }

    item {
      HyperGroupTitle("任务通知")
      HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
        HyperRow(
          title = "通知权限",
          summary = if (notificationsEnabled) "已开启；待处理与任务结果会通知，点按打开系统通知设置" else "已关闭，后台不会显示系统通知；打开首页仍可查看任务概览，点按开启",
          showChevron = true,
          onClick = onNotifications
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
        val item = liveUpdate
        if (item == null) {
          HyperRow(title = "系统实时更新", summary = "正在检测本机实时更新能力…")
        } else {
          // 只有系统标准实时更新一条通道，不再罗列任何品牌。
          HyperRow(
            title = "系统实时更新",
            summary = item.note,
            trailing = {
              Text(
                when {
                  !item.supported -> "普通通知"
                  item.granted -> "已就绪"
                  else -> "待授权"
                },
                style = MiuixTheme.textStyles.footnote1.copy(
                  fontWeight = FontWeight.SemiBold,
                  color = when {
                    !item.supported -> HyperTheme.colors.textSecondary
                    item.granted -> MiuixColorTokens.Success
                    else -> MiuixColorTokens.Warning
                  }
                )
              )
            }
          )
          if (item.supported && !item.granted) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
            HyperRow(
              title = "开启实时更新权限",
              summary = "Android 16 实时更新需要单独授权",
              showChevron = true,
              onClick = { TaskNotifications(context).promotedNotificationSettingsIntent()?.let { runCatching { context.startActivity(it) } } }
            )
          }
        }
      }
    }

    item {
      HyperGroupTitle("版本与更新")
      HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
        HyperRow(
          title = "Lagoon",
          value = packageInfo.versionName.orEmpty(),
          summary = "通过 GitHub 检查更新"
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
        HyperSwitchRow(
          title = "预发布通道",
          summary = if (includePrerelease) "包含正式版与 rc / beta；只升级，不降级" else "正式通道：只检查正式版，只升级，不降级",
          checked = includePrerelease,
          enabled = update.phase != AppUpdatePhase.DOWNLOADING && update.phase != AppUpdatePhase.CHECKING,
          onCheckedChange = { enabled ->
            includePrerelease = enabled
            updatePreferences.edit { putString("updateChannel", if (enabled) "preview" else "stable") }
            updater.selectChannel(enabled)
          }
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
        UpdateControls(
          update = update,
          includePrerelease = includePrerelease,
          installAllowed = installAllowed,
          onCheck = { updater.check(includePrerelease) },
          onRetryCheck = { updater.check(includePrerelease) },
          onDownload = { updater.download() },
          onCancel = { updater.cancelDownload() },
          onInstall = { requestInstall() },
          onShowNotes = { showReleaseNotes = true }
        )
      }
    }

    item {
      HyperGroupTitle("关于与诊断")
      HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
        HyperRow(
          title = "清除会话缓存",
          summary = (cacheUsage ?: "本机保存的会话内容") + "\n与服务器核对后使用；清除后打开会话时重新读取",
          showChevron = false,
          danger = true,
          onClick = { confirmingCacheClear = true }
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
        HyperRow(
          title = "复制诊断信息",
          summary = "版本、系统、连接状态与各阶段耗时，不含凭据",
          showChevron = false,
          onClick = {
            clipboard.setText(AnnotatedString("Lagoon ${packageInfo.versionName}\nAndroid ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nserver=${state.version}, api=${state.connected}, stream=${state.streamConnected}, stale=${state.degraded}, cached=${state.cached}\ncapabilitiesDocumented=${state.capabilities.documented}, sessions=${state.sessions.size}, messages=${state.messages.size}\n连接耗时：\n${controller.connectionDiagnostics().ifBlank { "暂无记录" }}\n帧耗时（本次启动）：\n${FrameStats.summary().ifBlank { "暂无记录" }}"))
            onMessage("诊断信息已复制", MiuixToastType.SUCCESS)
          }
        )
        crashReport?.let { report ->
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "上次崩溃", summary = "${report.lineSequence().firstOrNull().orEmpty()}\n${CrashLog.headline(report)}")
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "复制崩溃日志", showChevron = false, onClick = {
            clipboard.setText(AnnotatedString(report))
            onMessage("崩溃日志已复制", MiuixToastType.SUCCESS)
          })
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)
          HyperRow(title = "清除崩溃记录", danger = true, onClick = { confirmingCrashClear = true })
        }
      }
    }
  }
  }

  if (confirmingCacheClear) {
    SuperDialog(title = "清除本机会话缓存？", show = true, onDismissRequest = { confirmingCacheClear = false }) {
      Column {
        Text("服务器上的会话和消息不会删除；下次打开时需要重新读取，离线期间将暂时无法查看未同步内容。")
        DialogActions("取消", { confirmingCacheClear = false }, "确认清除", danger = true) {
          controller.clearTranscriptCache {
            cacheUsage = "已清除"
            onMessage("本机会话缓存已清除", MiuixToastType.SUCCESS)
          }
          confirmingCacheClear = false
        }
      }
    }
  }

  if (confirmingCrashClear) {
    SuperDialog(title = "清除崩溃记录？", show = true, onDismissRequest = { confirmingCrashClear = false }) {
      Column {
        Text("清除后将无法再从本机复制这份崩溃日志。")
        DialogActions("取消", { confirmingCrashClear = false }, "确认清除", danger = true) {
          CrashLog.clear(context)
          crashReport = null
          confirmingCrashClear = false
          onMessage("崩溃记录已清除", MiuixToastType.SUCCESS)
        }
      }
    }
  }

  val releaseForNotes = update.release

  if (showReleaseNotes && releaseForNotes != null) {
    HyperBottomSheet(
      title = "Lagoon ${releaseForNotes.version} 更新说明",
      show = true,
      onDismissRequest = { showReleaseNotes = false }
    ) {
      Column(
        Modifier.fillMaxWidth().fillMaxHeight(0.8f).verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 12.dp)
      ) {
        if (releaseForNotes.notes.isBlank()) {
          Text("本次发布没有附加说明。", style = MiuixTheme.textStyles.body2)
        } else {
          DetailMarkdown(releaseForNotes.notes)
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun UpdateControls(
  update: AppUpdateState,
  includePrerelease: Boolean,
  installAllowed: Boolean,
  onCheck: () -> Unit,
  onRetryCheck: () -> Unit,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onInstall: () -> Unit,
  onShowNotes: () -> Unit
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val release = update.release
  val failure = update.failure
  if (failure != null && release == null) {
    HyperActionRow(
      title = "检查失败",
      summary = failure.userMessage(update.failureDetail),
      trailing = { HyperTextAction(text = "重试", onClick = onRetryCheck) }
    )
    return
  }
  if (release == null) {
    when (update.phase) {
      AppUpdatePhase.CHECKING -> HyperActionRow(
        title = "检查更新",
        summary = "正在向 GitHub 查询新版本…",
        trailing = { InfiniteProgressIndicator(size = 16.dp, strokeWidth = 2.dp) }
      )
      AppUpdatePhase.UP_TO_DATE -> HyperActionRow(
        title = "已是最新版本",
        summary = updateChannelsText(update.lastCheckedAt, includePrerelease),
        onClick = onCheck
      )
      else -> HyperActionRow(
        title = "检查更新",
        summary = "从 GitHub Releases 检查 Lagoon 新版本",
        onClick = onCheck
      )
    }
    return
  }

  Column(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        if (release.prerelease) "发现预发布版" else "发现新版本",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
      )
      Spacer(Modifier.width(8.dp))
      Text(
        "v${release.version}",
        style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Medium),
        color = MiuixTheme.colorScheme.primary
      )
    }
    val meta = buildList {
      formatUpdateDate(release.publishedAt)?.let { add("发布 $it") }
      release.assetSize?.let { add(Formatter.formatFileSize(context, it)) }
      add(if (release.prerelease) "预发布" else "正式版")
    }.joinToString(" · ")
    Text(meta, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (release.notes.isNotBlank()) HyperTextAction(text = "查看更新说明", onClick = onShowNotes)
      HyperTextAction(
        text = "重新检查",
        enabled = update.phase != AppUpdatePhase.CHECKING && update.phase != AppUpdatePhase.DOWNLOADING,
        onClick = onRetryCheck
      )
    }
    if (failure != null) {
      Text(
        failure.userMessage(update.failureDetail),
        style = MiuixTheme.textStyles.footnote1,
        color = if (update.phase == AppUpdatePhase.READY) MiuixColorTokens.Warning else MiuixColorTokens.Error
      )
    }
    when (update.phase) {
      AppUpdatePhase.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
        InfiniteProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
          "正在检查新版本…",
          style = MiuixTheme.textStyles.footnote1,
          color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
      }
      AppUpdatePhase.DOWNLOADING -> {
        LinearProgressIndicator(
          progress = update.progress,
          modifier = Modifier.fillMaxWidth(),
          height = 4.dp
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            downloadProgressText(context, update),
            Modifier.weight(1f),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
          )
          HyperTextAction(text = "取消下载", onClick = onCancel)
        }
      }
      AppUpdatePhase.READY -> {
        Text(
          if (installAllowed) "安装包已校验完成，可以覆盖安装" else "安装包已校验完成；需要允许 Lagoon 安装未知应用",
          style = MiuixTheme.textStyles.footnote1,
          color = if (installAllowed) MiuixColorTokens.Success else MiuixColorTokens.Warning
        )
        HyperButton(
          text = if (installAllowed) "安装更新" else "允许后安装",
          onClick = onInstall,
          modifier = Modifier.fillMaxWidth()
        )
      }
      AppUpdatePhase.FAILED -> HyperButton(
        text = "重新下载",
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth()
      )
      else -> HyperButton(
        text = "下载并安装",
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

private fun updateChannelsText(lastCheckedAt: Long, includePrerelease: Boolean): String {
  val channel = if (includePrerelease) "预发布（含正式版）" else "仅正式版"
  return if (lastCheckedAt <= 0) "更新通道：$channel"
  else "更新通道：$channel · 上次检查 ${formatRelative(lastCheckedAt)}"
}

private fun formatUpdateDate(value: String?): String? {
  if (value.isNullOrBlank()) return null
  return runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault())
      .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
  }.getOrNull()
}

private fun downloadProgressText(context: Context, state: AppUpdateState): String {
  val downloaded = Formatter.formatFileSize(context, state.downloadedBytes)
  val total = state.totalBytes?.let { Formatter.formatFileSize(context, it) }
  val percent = state.progress?.let { (it * 100).toInt() }
  return when {
    percent != null && total != null -> "下载中 $percent% · $downloaded / $total"
    percent != null -> "下载中 $percent% · $downloaded"
    total != null -> "正在下载 · $downloaded / $total"
    else -> "正在下载 · $downloaded"
  }
}
