package com.igng.opencode.lagoon.ui

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
import com.igng.opencode.lagoon.core.*
import com.igng.opencode.lagoon.system.LiveUpdateSupport
import com.igng.opencode.lagoon.system.TaskNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
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
 * 「会话」是唯一的根页面；设置与已归档从首页 ⋯ 菜单整页推入。
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

/**
 * Add ([existing] = null) or edit one server. Switching servers happens on the home capsules; this
 * sheet only owns the profile itself, including removing it from the device.
 */
@Composable
fun ServersModal(
  state: LagoonState,
  controller: LagoonController,
  existing: ServerProfile?,
  onDismiss: () -> Unit
) {
  var deleting by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  SuperBottomSheet(
    title = if (existing == null) "添加服务器" else "编辑服务器",
    show = true,
    onDismissRequest = onDismiss
  ) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
      MiuixServerForm(
        existing = existing,
        onCancel = onDismiss,
        onDelete = existing?.let { { deleting = true } },
        onSave = { profile, password, done ->
          scope.launch {
            try {
              val pair = PairLinkResolver.isPairLink(profile.url)
              val resolved = if (pair) PairLinkResolver.resolve(profile.url) else null
              val actualProfile = if (resolved == null) profile else profile.copy(url = resolved.serverUrl, username = resolved.credentials.username)
              val savedUrl = state.profiles.firstOrNull { it.id == profile.id }?.url
              val credentials = resolved?.credentials ?: profileCredentials(
                savedUrl, actualProfile.url, controller.credentials(profile.id), actualProfile.username, password
              )
              val version = controller.testServer(actualProfile, credentials)
              controller.saveServer(actualProfile, credentials.password, credentials.cookie, credentials.username)
              val label = actualProfile.kind.label
              val detail = if (version.isBlank() || version.equals(label, ignoreCase = true) || version == "DeepSeek Harness") "" else " $version"
              done("已连接 $label$detail")
              onDismiss()
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

  if (deleting && existing != null) {
    SuperDialog(
      title = "移除 ${existing.name}？",
      show = true,
      onDismissRequest = { deleting = false }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        Text(
          "仅从此设备删除连接记录和存储凭据，不会对远程服务器数据产生任何影响。",
          style = MiuixTheme.textStyles.body1
        )
        DialogActions("取消", { deleting = false }, "确认删除", danger = true) {
          controller.deleteServer(existing.id)
          deleting = false
          onDismiss()
        }
      }
    }
  }
}

@Composable
private fun MiuixServerForm(
  existing: ServerProfile?,
  onCancel: () -> Unit,
  onDelete: (() -> Unit)?,
  onSave: (ServerProfile, String?, (String) -> Unit) -> Unit
) {
  val clipboard = LocalClipboardManager.current
  val id = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
  var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
  var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
  var harness by rememberSaveable(existing?.id) { mutableStateOf(existing?.kind ?: ServerKind.OPENCODE) }
  val isDsh = harness == ServerKind.DSH
  var username by rememberSaveable(existing?.id) { mutableStateOf(existing?.username ?: "opencode") }
  var password by remember(existing?.id) { mutableStateOf("") }
  var autoConnect by rememberSaveable(existing?.id) { mutableStateOf(existing?.autoConnect ?: true) }
  var notifications by rememberSaveable(existing?.id) { mutableStateOf(existing?.notifications ?: true) }
  var allowHttp by rememberSaveable(existing?.id) { mutableStateOf(existing?.allowCleartext == true) }
  var showAdvanced by remember { mutableStateOf(existing?.allowCleartext == true) }
  var result by remember { mutableStateOf("") }
  var working by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    if (existing == null && url.isBlank()) {
      val clipText = clipboard.getText()?.text?.trim().orEmpty()
      if (clipText.isNotBlank() && (PairLinkResolver.isPairLink(clipText) || clipText.startsWith("http://") || clipText.startsWith("https://"))) {
        url = clipText
        if (name.isBlank()) name = "我的服务器"
      }
    }
  }

  val normalizedInput = url.trim()
  val isPair = PairLinkResolver.isPairLink(normalizedInput)

  LazyColumn(
    modifier = Modifier.fillMaxSize().overScrollVertical(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Card(
        colors = CardDefaults.defaultColors(color = MiuixColorTokens.PrimarySubtle),
        insideMargin = PaddingValues(12.dp)
      ) {
        Text(
          "💡 推荐在电脑端运行 opencode pair 并将配对链接直接粘贴到下方，即可免密码一键配置认证。",
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.primary)
        )
      }
    }

    item {
      SuperDropdown(
        title = "服务类型",
        items = ServerKind.entries.map { it.label },
        selectedIndex = ServerKind.entries.indexOf(harness),
        onSelectedIndexChange = { index ->
          harness = ServerKind.entries[index]
          if (name.isBlank()) name = "我的 ${harness.label}"
          if (harness == ServerKind.DSH) username = "dsh"
        }
      )
    }

    item {
      TextField(
        value = name,
        onValueChange = { text: String -> name = text },
        label = "服务器名称标识",
        modifier = Modifier.fillMaxWidth()
      )
    }

    item {
      Column(Modifier.fillMaxWidth()) {
        TextField(
          value = url,
          onValueChange = { text: String ->
            url = text
            if (name.isBlank() && text.isNotBlank()) name = "我的 OpenCode"
          },
          label = when {
            url.isNotBlank() -> "服务器地址 或 配对链接"
            isDsh -> "http://192.168.1.x:3080"
            else -> "http://192.168.1.x:4096 或配对链接"
          },
          useLabelAsPlaceholder = url.isBlank(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth()
        )
        Text(
          text = if (isDsh) {
            "示例：http://192.168.1.100:3080。默认只监听本机，请用 dsh web --host <电脑的局域网 IP> 启动（端口默认 3080），令牌填启动链接里 token= 后面的那串字符；手机无法通过 localhost 访问电脑。"
          } else {
            "示例：http://192.168.1.100:4096 或 https://oc.example.com。手机无法通过 localhost 访问电脑，请填电脑的局域网 IP 或直接粘贴 opencode pair 配对链接。"
          },
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
          modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp)
        )
      }
    }

    if (normalizedInput.startsWith("http://", true)) item {
      SuperSwitch(title = "允许此地址使用 HTTP", summary = "请仅在你信任的网络中开启", checked = allowHttp, onCheckedChange = { allowHttp = it })
    }
    if (!isPair) {
      if (!isDsh) item {
        TextField(
          value = username,
          onValueChange = { text: String -> username = text },
          label = "用户名 (Basic Auth)",
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        TextField(
          value = password,
          onValueChange = { text: String -> password = text },
          label = when {
            isDsh -> if (existing == null) "访问令牌（dsh web 启动链接里的 token）" else "新令牌（留空保持不变）"
            existing == null -> "访问密码（无认证服务器可留空）"
            else -> "新密码（留空保持不变）"
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
        if (isDsh) Text(
          text = "在电脑上运行 dsh web --host <局域网 IP>，把启动链接里 token= 后面的字符填在这里；换取会话 Cookie 后默认 30 天内无需重填（换地址或清除资料后需要新令牌）。",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
          modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp)
        )
      }
    }

    item {
      SuperArrow(
        title = "高级与网络设置",
        summary = if (showAdvanced) "收起额外配置" else "连接与通知行为",
        onClick = { showAdvanced = !showAdvanced }
      )
    }

    if (showAdvanced) {
      item {
        SuperSwitch(
          title = "自动连接此服务器",
          checked = autoConnect,
          onCheckedChange = { autoConnect = it }
        )
      }
      item {
        SuperSwitch(
          title = "接收任务完成与等待通知",
          checked = notifications,
          onCheckedChange = { notifications = it }
        )
      }
    }

    if (result.isNotBlank()) {
      item {
        Text(
          text = result,
          color = if (result.startsWith("已连接")) MiuixColorTokens.Success else MiuixColorTokens.Error,
          style = MiuixTheme.textStyles.body2
        )
      }
    }

    item {
      DialogActions(
        "取消", onCancel, if (working) "正在测试…" else "保存并连接",
        enabled = !working && name.isNotBlank() &&
          (normalizedInput.startsWith("https://", true) || (normalizedInput.startsWith("http://", true) && allowHttp))
      ) {
        working = true
        val normalizedUrl = normalizedInput.trimEnd('/')
        val profile = (existing ?: ServerProfile(id, "", "")).copy(
          name = name.trim().ifBlank { harness.label }, url = normalizedUrl,
          username = if (isDsh) "dsh" else username.trim().ifBlank { "opencode" },
          autoConnect = autoConnect, notifications = notifications,
          allowCleartext = normalizedUrl.startsWith("http://", true) && allowHttp,
          kind = harness
        )
        onSave(profile, password.takeIf { it.isNotBlank() || existing == null }) { message ->
          result = message
          working = false
        }
      }
    }

    if (onDelete != null) item {
      TextButton(text = "从此设备移除", onClick = onDelete, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error))
    }
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
  onEditServer: (ServerProfile?) -> Unit,
  onManageModels: () -> Unit,
  onBack: () -> Unit
) {
  val context = androidx.compose.ui.platform.LocalContext.current
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
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshSupport() }
    lifecycleOwner.lifecycle.addObserver(observer); refreshSupport()
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
  val clipboard = LocalClipboardManager.current
  var crashReport by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) { crashReport = withContext(Dispatchers.IO) { CrashLog.read(context) } }

  // MIUIX settings: white cards on the grey page surface.
  Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
  PageTopBar("设置", onBack)
  // MIUIX settings layout: 12dp card margin + SmallTitle's 28dp inset aligns titles with row content.
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .overScrollVertical(),
    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 48.dp)
  ) {
    item {
      SmallTitle("服务器")
      Card(Modifier.fillMaxWidth()) {
        state.profiles.forEach { profile ->
          val current = profile.id == state.serverId
          SuperArrow(
            title = profile.name,
            summary = if (!current) profile.url else if (state.connected) "当前 · ${profile.kind.label}${state.version.takeIf { it.isNotBlank() && !it.equals(profile.kind.label, true) }?.let { " $it" }.orEmpty()} · ${profile.url}" else "当前 · 未连接 · ${profile.url}",
            onClick = { onEditServer(profile) }
          )
        }
        SuperArrow(title = "添加服务器", summary = "OpenCode 地址 / pair 链接，或 DeepSeek Harness 令牌", onClick = { onEditServer(null) })
        SuperArrow(
          title = "模型显示",
          summary = when {
            state.serverId == null -> "连接服务器后管理模型下拉框"
            state.modelVisibilityConfigured -> "已配置 · ${state.visibleModels.size}/${state.modelCatalog.size} 个模型"
            else -> "未配置过滤 · 显示服务器返回的全部模型"
          },
          onClick = onManageModels
        )
      }
    }

    item {
      SmallTitle("显示与交互")
      Card(Modifier.fillMaxWidth()) {
        SuperDropdown(
          title = "深浅色外观",
          items = ThemeMode.entries.map { it.label },
          selectedIndex = ThemeMode.entries.indexOf(themeMode),
          onSelectedIndexChange = { onTheme(ThemeMode.entries[it]) }
        )
        SuperSwitch(
          title = "返回手势预览",
          summary = "拖动返回手势时轻微预览上一级页面",
          checked = previewBack,
          onCheckedChange = onPreviewBack
        )
        SuperDropdown(
          title = "自动刷新",
          items = RefreshMode.entries.map { it.label },
          selectedIndex = RefreshMode.entries.indexOf(refreshMode),
          onSelectedIndexChange = { onRefreshMode(RefreshMode.entries[it]) }
        )
      }
    }

    item {
      SmallTitle("授权")
      Card(Modifier.fillMaxWidth()) {
        SuperSwitch(title = "不显示授权窗口", summary = "本手机全部服务器：隐藏授权窗口、列表标记和通知；不会因此允许操作，任务可能仍等待电脑端处理",
          checked = state.permissionPolicy.hide,
          onCheckedChange = { controller.setPermissionPolicy(state.permissionPolicy.copy(hide = it)) })
        SuperSwitch(title = "自动授权", summary = "本手机全部服务器：自动对每个权限请求“允许一次”，不保存永久规则；请仅连接可信服务器",
          checked = state.permissionPolicy.autoApprove,
          onCheckedChange = { controller.setPermissionPolicy(state.permissionPolicy.copy(autoApprove = it)) })
      }
    }

    item {
      SmallTitle("任务通知")
      Card(Modifier.fillMaxWidth()) {
        SuperArrow(
          title = "通知权限",
          summary = if (notificationsEnabled) "已开启；待处理与任务结果会通知，点按打开系统通知设置" else "已关闭，后台不会显示系统通知；打开首页仍可查看任务概览，点按开启",
          onClick = onNotifications
        )
        val item = liveUpdate
        if (item == null) {
          BasicComponent(title = "系统实时更新", summary = "正在检测本机实时更新能力…")
        } else {
          // 只有系统标准实时更新一条通道，不再罗列任何品牌。
          BasicComponent(
            title = "系统实时更新",
            summary = item.note,
            endActions = {
              Text(
                when {
                  !item.supported -> "普通通知"
                  item.granted -> "已就绪"
                  else -> "待授权"
                },
                style = MiuixTheme.textStyles.footnote1.copy(
                  color = when {
                    !item.supported -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    item.granted -> MiuixColorTokens.Success
                    else -> MiuixColorTokens.Warning
                  }
                )
              )
            }
          )
          if (item.supported && !item.granted) SuperArrow(
            title = "开启实时更新权限",
            summary = "Android 16 实时更新需要单独授权",
            onClick = { TaskNotifications(context).promotedNotificationSettingsIntent()?.let { runCatching { context.startActivity(it) } } }
          )
        }
      }
    }

    item {
      SmallTitle("关于与诊断")
      Card(Modifier.fillMaxWidth()) {
        BasicComponent(
          title = "Lagoon ${packageInfo.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()})",
          summary = "服务：${state.version.ifBlank { "未知版本" }} · ${if (state.connected) "可连接" else "未连接"}\n实时同步：${if (state.streamConnected) "正常" else "恢复中"} · ${if (state.degraded || state.cached) "数据待更新" else "数据已同步"}"
        )
        SuperArrow(
          title = "复制诊断信息",
          summary = "版本、系统与连接状态，不含凭据",
          onClick = {
            clipboard.setText(AnnotatedString("Lagoon ${packageInfo.versionName}\nAndroid ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nserver=${state.version}, api=${state.connected}, stream=${state.streamConnected}, stale=${state.degraded}, cached=${state.cached}\ncapabilitiesDocumented=${state.capabilities.documented}, sessions=${state.sessions.size}, messages=${state.messages.size}"))
          }
        )
        crashReport?.let { report ->
          BasicComponent(title = "上次崩溃", summary = "${report.lineSequence().firstOrNull().orEmpty()}\n${CrashLog.headline(report)}")
          SuperArrow(title = "复制崩溃日志", onClick = { clipboard.setText(AnnotatedString(report)) })
          SuperArrow(title = "清除崩溃记录", onClick = { CrashLog.clear(context); crashReport = null })
        }
      }
    }
  }

  }
}
