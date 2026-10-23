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
              val previous = state.profiles.firstOrNull { it.id == profile.id }
              val credentials = resolved?.credentials ?: profileCredentials(
                savedUrl, actualProfile.url, controller.credentials(profile.id), actualProfile.username, password,
                // DSH 填了新令牌/密钥或换了认证方式：丢掉旧 Cookie，让这次测试真正验证新凭据。
                cookie = "".takeIf { actualProfile.kind == ServerKind.DSH && (password != null || previous?.dshAuth != actualProfile.dshAuth) }
              )
              var minted: String? = null
              val version = controller.testServer(actualProfile, credentials) { minted = it }
              controller.saveServer(actualProfile, credentials.password, minted ?: credentials.cookie, credentials.username)
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
  var dshAuth by rememberSaveable(existing?.id) { mutableStateOf(existing?.dshAuth ?: DshAuthMode.TOKEN) }
  val isSecret = isDsh && dshAuth == DshAuthMode.SECRET
  var username by rememberSaveable(existing?.id) { mutableStateOf(existing?.username ?: "opencode") }
  var password by remember(existing?.id) { mutableStateOf("") }
  var autoConnect by rememberSaveable(existing?.id) { mutableStateOf(existing?.autoConnect ?: true) }
  var notifications by rememberSaveable(existing?.id) { mutableStateOf(existing?.notifications ?: true) }
  var allowHttp by rememberSaveable(existing?.id) { mutableStateOf(existing?.allowCleartext == true) }
  var showAdvanced by remember { mutableStateOf(existing?.allowCleartext == true) }
  var result by remember { mutableStateOf("") }
  var working by remember { mutableStateOf(false) }

  /** 粘贴 `dsh web` 的完整启动链接：拆成地址与令牌，并切到 DSH。 */
  fun acceptLaunchLink(text: String): Boolean {
    val link = DshLaunchLink.parse(text) ?: return false
    harness = ServerKind.DSH
    dshAuth = DshAuthMode.TOKEN
    username = "dsh"
    url = link.serverUrl
    password = link.token
    if (link.serverUrl.startsWith("http://", true)) allowHttp = true
    if (name.isBlank()) name = "我的 ${ServerKind.DSH.label}"
    return true
  }

  LaunchedEffect(Unit) {
    if (existing == null && url.isBlank()) {
      val clipText = clipboard.getText()?.text?.trim().orEmpty()
      if (acceptLaunchLink(clipText)) return@LaunchedEffect
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
          if (isDsh) "💡 直接粘贴 dsh web 打印的完整启动链接即可配对；配对后 Cookie 会保存在本机，dsh 重启也不用重新配对。想完全不看电脑，可改用「签名密钥」。"
          else "💡 推荐在电脑端运行 opencode pair 并将配对链接直接粘贴到下方，即可免密码一键配置认证。",
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
            if (!acceptLaunchLink(text)) {
              url = text
              if (name.isBlank() && text.isNotBlank()) name = "我的 ${harness.label}"
            }
          },
          label = when {
            url.isNotBlank() -> if (isDsh) "服务器地址 或 启动链接" else "服务器地址 或 配对链接"
            isDsh -> "http://192.168.1.x:3080 或启动链接"
            else -> "http://192.168.1.x:4096 或配对链接"
          },
          useLabelAsPlaceholder = url.isBlank(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth()
        )
        Text(
          text = if (isDsh) {
            "示例：http://192.168.1.100:3080。dsh 默认只监听本机，请用 dsh web --host <电脑的局域网 IP> --no-open 启动；可直接粘贴它打印的整条启动链接，地址和令牌会自动拆开。手机无法通过 localhost 访问电脑。"
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
    if (isDsh) item {
      SuperDropdown(
        title = "认证方式",
        summary = if (isSecret) "填 Cookie 签名密钥，长期有效，配对不需要看电脑" else "填启动令牌，换到 Cookie 后保存在本机",
        items = DshAuthMode.entries.map { it.label },
        selectedIndex = DshAuthMode.entries.indexOf(dshAuth),
        onSelectedIndexChange = { index -> dshAuth = DshAuthMode.entries[index] }
      )
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
            isSecret -> if (existing?.dshAuth == DshAuthMode.SECRET) "新签名密钥（留空保持不变）" else "签名密钥（browser-session 记录的 secret）"
            isDsh -> if (existing?.dshAuth == DshAuthMode.TOKEN) "新启动令牌（留空保持不变）" else "启动令牌（启动链接里 token= 后面的字符）"
            existing == null -> "访问密码（无认证服务器可留空）"
            else -> "新密码（留空保持不变）"
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
        if (isDsh) Text(
          text = if (isSecret) {
            "签名密钥在电脑的 ~/.dsh/.credentials.yaml 里 client-connection/browser-session 记录的 secret（43 个字符）。它相当于这台 dsh 的长期密码：手机用它自行签发 Cookie，dsh 重启、Cookie 过期都不用重新配对。删除该记录并重启 dsh 即可吊销。请只在 HTTPS 或可信局域网中使用。"
          } else {
            "令牌只用来换 Cookie：Cookie 保存在本机、dsh 重启后仍然有效，默认 30 天过期（dsh 未重启时会自动续期）。想更久可在 dsh 配置里调大 cookieMaxAgeDays。"
          },
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
          kind = harness, dshAuth = if (isDsh) dshAuth else DshAuthMode.TOKEN
        )
        // 换了 DSH 认证方式时旧凭据不能沿用：必须重新填写。
        val authChanged = isDsh && existing != null && existing.dshAuth != dshAuth
        if (authChanged && password.isBlank()) {
          result = "切换认证方式后请填写新的${dshAuth.label}"
          working = false
          return@DialogActions
        }
        val secret = if (isDsh) password.trim() else password
        onSave(profile, secret.takeIf { it.isNotBlank() || existing == null }) { message ->
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
  val updater = remember(context) { AppUpdater.get(context) }
  val update by updater.state.collectAsState()
  val updatePreferences = remember(context) { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
  var includePrerelease by remember { mutableStateOf(updatePreferences.getBoolean("updatePrerelease", true)) }
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
        Toast.makeText(context, "无法打开“安装未知应用”设置，请手动在系统设置中授权", Toast.LENGTH_LONG).show()
      }
      return
    }
    pendingInstall = false
    installAllowed = true
    val uri = updater.installerUri(apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
      Toast.makeText(context, "无法打开系统安装器：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
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
  val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
    else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
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
      SmallTitle("版本与更新")
      Card(Modifier.fillMaxWidth()) {
        BasicComponent(
          title = "Lagoon ${packageInfo.versionName} ($versionCode)",
          summary = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · 更新源 GitHub"
        )
        HorizontalDivider()
        SuperSwitch(
          title = "包含预发布版本",
          summary = if (includePrerelease) "包含 rc / beta 等预发布版；关闭后只检查正式版" else "只检查正式版；rc / beta 不会提示",
          checked = includePrerelease,
          enabled = update.phase != AppUpdatePhase.DOWNLOADING && update.phase != AppUpdatePhase.CHECKING,
          onCheckedChange = { enabled ->
            includePrerelease = enabled
            updatePreferences.edit { putBoolean("updatePrerelease", enabled) }
            updater.clearResult()
            updater.check(enabled, force = true)
          }
        )
        HorizontalDivider()
        UpdateControls(
          update = update,
          includePrerelease = includePrerelease,
          installAllowed = installAllowed,
          onCheck = { updater.check(includePrerelease) },
          onRetryCheck = { updater.check(includePrerelease, force = true) },
          onDownload = { updater.download() },
          onCancel = { updater.cancelDownload() },
          onInstall = { requestInstall() },
          onShowNotes = { showReleaseNotes = true }
        )
      }
    }

    item {
      SmallTitle("关于与诊断")
      Card(Modifier.fillMaxWidth()) {
        BasicComponent(
          title = "服务与同步",
          summary = "服务：${state.version.ifBlank { "未知版本" }} · ${if (state.connected) "可连接" else "未连接"}\n实时同步：${if (state.streamConnected) "正常" else "恢复中"} · ${if (state.degraded || state.cached) "数据待更新" else "数据已同步"}"
        )
        var cacheUsage by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
          cacheUsage = controller.transcriptCacheUsage()?.let { "已保存 ${it.sessions} 个会话 · ${formatBytes(it.bytes)} / 上限 ${formatBytes(TranscriptStore.DEFAULT_BUDGET_BYTES)}" }
        }
        SuperArrow(
          title = "清除会话缓存",
          summary = (cacheUsage ?: "本机保存的会话内容") + "\n与服务器核对后使用；清除后打开会话时重新读取",
          onClick = { controller.clearTranscriptCache { cacheUsage = "已清除" } }
        )
        SuperArrow(
          title = "复制诊断信息",
          summary = "版本、系统、连接状态与各阶段耗时，不含凭据",
          onClick = {
            clipboard.setText(AnnotatedString("Lagoon ${packageInfo.versionName}\nAndroid ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nserver=${state.version}, api=${state.connected}, stream=${state.streamConnected}, stale=${state.degraded}, cached=${state.cached}\ncapabilitiesDocumented=${state.capabilities.documented}, sessions=${state.sessions.size}, messages=${state.messages.size}\n连接耗时：\n${controller.connectionDiagnostics().ifBlank { "暂无记录" }}"))
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

  val releaseForNotes = update.release
  if (showReleaseNotes && releaseForNotes != null) {
    SuperBottomSheet(
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
    BasicComponent(
      title = "检查失败",
      summary = failure.userMessage(update.failureDetail),
      endActions = { TextButton(text = "重试", onClick = onRetryCheck) }
    )
    return
  }
  if (release == null) {
    when (update.phase) {
      AppUpdatePhase.CHECKING -> BasicComponent(
        title = "检查更新",
        summary = "正在向 GitHub 查询新版本…",
        endActions = { InfiniteProgressIndicator(size = 16.dp, strokeWidth = 2.dp) }
      )
      AppUpdatePhase.UP_TO_DATE -> SuperArrow(
        title = "已是最新版本",
        summary = updateChannelsText(update.lastCheckedAt, includePrerelease),
        onClick = onCheck
      )
      else -> SuperArrow(
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
      if (release.notes.isNotBlank()) TextButton(text = "查看更新说明", onClick = onShowNotes)
      TextButton(
        text = "重新检查",
        enabled = update.phase != AppUpdatePhase.CHECKING && update.phase != AppUpdatePhase.DOWNLOADING,
        onClick = onRetryCheck
      )
    }
    if (failure != null) {
      Text(
        (if (update.phase == AppUpdatePhase.READY) "检查更新失败：" else "") + failure.userMessage(update.failureDetail),
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
          TextButton(text = "取消", onClick = onCancel)
        }
      }
      AppUpdatePhase.READY -> {
        Text(
          if (installAllowed) "安装包已校验完成，可以覆盖安装" else "安装包已校验完成；需要允许 Lagoon 安装未知应用",
          style = MiuixTheme.textStyles.footnote1,
          color = if (installAllowed) MiuixColorTokens.Success else MiuixColorTokens.Warning
        )
        TextButton(
          text = if (installAllowed) "安装更新" else "允许后安装",
          onClick = onInstall,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.textButtonColorsPrimary()
        )
      }
      AppUpdatePhase.FAILED -> TextButton(
        text = "重新下载",
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColorsPrimary()
      )
      else -> TextButton(
        text = "下载并安装",
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColorsPrimary()
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
