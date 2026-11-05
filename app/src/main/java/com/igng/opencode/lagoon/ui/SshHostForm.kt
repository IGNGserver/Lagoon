package com.igng.opencode.lagoon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshLinkStatus
import com.igng.opencode.lagoon.core.SshSecrets
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Add or edit one SSH host. The backend mirrors dsh-Go: SSH auth + host-key pinning, start/reuse
 * `dsh web`, local forwarding, then DSH login. "测试连接" runs the same flow as a real connection.
 */
@Composable
internal fun SshHostForm(
  initial: SshHostConfig?,
  state: LagoonState,
  controller: LagoonController,
  onCancel: () -> Unit,
  onSaved: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val hostId = remember(initial?.id) { initial?.id ?: SshHostConfig.newId() }
  val initialSecrets = remember(initial?.id) { initial?.let { controller.sshHostSecrets(it.id) } ?: SshSecrets() }
  var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
  var host by rememberSaveable(initial?.id) { mutableStateOf(initial?.host.orEmpty()) }
  var port by rememberSaveable(initial?.id) { mutableStateOf((initial?.port ?: 22).toString()) }
  var user by rememberSaveable(initial?.id) { mutableStateOf(initial?.user.orEmpty()) }
  var auth by rememberSaveable(initial?.id) { mutableStateOf(initial?.auth ?: SshAuthMethod.PASSWORD) }
  var password by remember(initial?.id) { mutableStateOf(initialSecrets.password.orEmpty()) }
  var privateKey by remember(initial?.id) { mutableStateOf(initialSecrets.privateKey.orEmpty()) }
  var passphrase by remember(initial?.id) { mutableStateOf(initialSecrets.passphrase.orEmpty()) }
  var command by rememberSaveable(initial?.id) { mutableStateOf(initial?.launchCommand ?: com.igng.opencode.lagoon.core.DEFAULT_DSH_LAUNCH_COMMAND) }
  var workingDirectory by rememberSaveable(initial?.id) { mutableStateOf(initial?.workingDirectory.orEmpty()) }
  var autoConnect by rememberSaveable(initial?.id) {
    mutableStateOf(state.profiles.firstOrNull { initial?.id != null && it.sshHostId == initial.id }?.autoConnect ?: true)
  }
  var notifications by rememberSaveable(initial?.id) {
    mutableStateOf(state.profiles.firstOrNull { initial?.id != null && it.sshHostId == initial.id }?.notifications ?: true)
  }
  var hostKeyType by rememberSaveable(initial?.id) { mutableStateOf(initial?.hostKeyType.orEmpty()) }
  var hostKeyFingerprint by rememberSaveable(initial?.id) { mutableStateOf(initial?.hostKeyFingerprint.orEmpty()) }
  var groupId by rememberSaveable(initial?.id) {
    mutableStateOf(state.deviceGroups.firstOrNull { initial?.id != null && initial.id in it.memberIds }?.id)
  }
  var result by remember { mutableStateOf<String?>(null) }
  var working by remember { mutableStateOf(false) }
  var testStatus by remember { mutableStateOf<SshLinkStatus?>(null) }
  var pendingKey by remember { mutableStateOf<PresentedSshHostKey?>(null) }
  var pendingAction by remember { mutableStateOf<((SshHostConfig) -> Unit)?>(null) }
  var pendingConfig by remember { mutableStateOf<SshHostConfig?>(null) }
  var importError by remember { mutableStateOf<String?>(null) }
  var confirmingDelete by remember { mutableStateOf(false) }

  // A pinned key belongs to the address it was read from. Clear the display as soon as the user
  // edits host/port so an old fingerprint can never be sent to a different machine.
  LaunchedEffect(host, port) {
    val base = initial ?: return@LaunchedEffect
    if (host.trim() != base.host || port.trim().toIntOrNull() != base.port) {
      hostKeyType = ""
      hostKeyFingerprint = ""
    }
  }

  val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      try {
        val text = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              out.write(buffer, 0, read)
              if (out.size() > MAX_KEY_BYTES) throw IllegalArgumentException("私钥文件过大")
            }
            out.toString(Charsets.UTF_8.name())
          } ?: throw IllegalArgumentException("无法读取文件")
        }
        if (!text.contains("PRIVATE KEY-----")) throw IllegalArgumentException("这不是 OpenSSH/PEM 私钥文件")
        privateKey = text.trim()
        importError = null
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        importError = error.message ?: "读取私钥失败"
      }
    }
  }

  fun secrets(): SshSecrets = SshSecrets(
    password = password.takeIf { it.isNotEmpty() } ?: initialSecrets.password,
    privateKey = privateKey.takeIf { it.isNotBlank() } ?: initialSecrets.privateKey,
    passphrase = passphrase.takeIf { it.isNotEmpty() } ?: initialSecrets.passphrase
  )

  fun configOrNull(): SshHostConfig? {
    val portValue = port.trim().toIntOrNull()
    if (host.isBlank() || user.isBlank() || portValue == null || portValue !in 1..65535) {
      result = "填写主机地址、用户名和 1–65535 的端口"
      return null
    }
    if (auth == SshAuthMethod.PASSWORD && password.isEmpty()) {
      result = "填写 SSH 密码，或改用私钥"
      return null
    }
    if (auth == SshAuthMethod.PRIVATE_KEY && privateKey.isBlank()) {
      result = "粘贴或导入 SSH 私钥"
      return null
    }
    // A pinned key belongs to one address; moving the host must force a fresh confirmation.
    val pinStillBelongs = initial == null || (host.trim() == initial.host && portValue == initial.port)
    return SshHostConfig(
      id = hostId,
      name = name.trim().ifBlank { host.trim() },
      host = host.trim(),
      port = portValue,
      user = user.trim(),
      auth = auth,
      launchCommand = command.trim().ifBlank { com.igng.opencode.lagoon.core.DEFAULT_DSH_LAUNCH_COMMAND },
      workingDirectory = workingDirectory.trim().takeIf { it.isNotEmpty() },
      hostKeyType = hostKeyType.takeIf { pinStillBelongs && it.isNotBlank() },
      hostKeyFingerprint = hostKeyFingerprint.takeIf { pinStillBelongs && it.isNotBlank() }
    )
  }

  fun errorText(error: Throwable): String =
    if (error is SshFailure) error.display() else userFacingError(error, "SSH 连接失败")

  fun withPinnedKey(config: SshHostConfig, action: (SshHostConfig) -> Unit) {
    if (!config.hostKeyFingerprint.isNullOrBlank()) {
      action(config)
      return
    }
    if (working) return
    working = true; result = null; pendingKey = null
    scope.launch {
      try {
        val key = controller.probeSshHostKey(config)
        pendingKey = key
        pendingConfig = config
        pendingAction = action
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        result = errorText(error)
      } finally {
        working = false
      }
    }
  }

  fun test() {
    val config = configOrNull() ?: return
    withPinnedKey(config) { pinned ->
      working = true; result = null; testStatus = null
      scope.launch {
        try {
          val message = controller.testSshHost(pinned, secrets()) { status ->
            scope.launch { testStatus = status }
          }
          result = message
          hostKeyType = pinned.hostKeyType.orEmpty()
          hostKeyFingerprint = pinned.hostKeyFingerprint.orEmpty()
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (error: Exception) {
          result = errorText(error)
          (error as? SshFailure)?.presentedKey?.let { pendingKey = it }
        } finally {
          working = false
        }
      }
    }
  }

  fun save() {
    val config = configOrNull() ?: return
    withPinnedKey(config) { pinned ->
      working = true; result = null
      try {
        controller.saveSshHost(
          config = pinned,
          secrets = secrets(),
          autoConnect = autoConnect,
          notifications = notifications,
          groupId = groupId,
          connect = true
        )
        onSaved()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        result = errorText(error)
        working = false
      }
    }
  }

  LazyColumn(
    Modifier.fillMaxWidth().heightIn(max = 720.dp),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Card(colors = CardDefaults.defaultColors(color = MiuixColorTokens.PrimarySubtle), insideMargin = PaddingValues(12.dp)) {
        Text(
          "通过 SSH 连接远端电脑（Linux / macOS / WSL），连接时会自动在远端启动或复用 dsh web，再经本地加密隧道连接。",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary)
        )
      }
    }
    item { TextField(value = name, onValueChange = { name = it }, label = "名称（可选）", modifier = Modifier.fillMaxWidth()) }
    item { TextField(value = host, onValueChange = { host = it }, label = "主机地址（IP 或域名）", modifier = Modifier.fillMaxWidth()) }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(value = user, onValueChange = { user = it }, label = "用户名", modifier = Modifier.weight(1f))
        TextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = "端口", modifier = Modifier.width(96.dp))
      }
    }
    item {
      SuperDropdown(
        title = "认证方式",
        items = SshAuthMethod.entries.map { it.label },
        selectedIndex = SshAuthMethod.entries.indexOf(auth),
        onSelectedIndexChange = { auth = SshAuthMethod.entries[it] }
      )
    }
    if (auth == SshAuthMethod.PASSWORD) {
      item {
        TextField(
          value = password,
          onValueChange = { password = it },
          label = if (initial?.auth == SshAuthMethod.PASSWORD && initialSecrets.password != null) "SSH 密码（留空保持不变）" else "SSH 密码",
          keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    } else {
      item {
        TextField(
          value = privateKey,
          onValueChange = { privateKey = it },
          label = "私钥（OpenSSH / PEM 全文）",
          singleLine = false,
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(text = "导入私钥文件", onClick = { keyPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) })
          Text(if (initialSecrets.privateKey != null) "已保存，可留空沿用" else "只保存在本机加密存储中",
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
        importError?.let { Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error)) }
      }
      item {
        TextField(
          value = passphrase,
          onValueChange = { passphrase = it },
          label = "私钥密码（没有可留空）",
          keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
    item { SmallTitle("高级", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) }
    item {
      TextField(value = command, onValueChange = { command = it }, label = "启动命令", modifier = Modifier.fillMaxWidth())
    }
    item {
      TextField(value = workingDirectory, onValueChange = { workingDirectory = it }, label = "远端工作目录（可选，例如 ~/code）", modifier = Modifier.fillMaxWidth())
    }
    item {
      SuperSwitch(title = "自动连接", summary = "保存后在后台保持这条 SSH 连接", checked = autoConnect, onCheckedChange = { autoConnect = it })
    }
    item {
      SuperSwitch(title = "接收任务通知", checked = notifications, onCheckedChange = { notifications = it })
    }
    item {
      val groups = state.deviceGroups
      if (groups.isNotEmpty()) {
        SuperDropdown(
          title = "加入设备分组",
          items = listOf("不加入分组") + groups.map { it.name },
          selectedIndex = (groups.indexOfFirst { it.id == groupId } + 1).coerceAtLeast(0),
          onSelectedIndexChange = { index -> groupId = if (index <= 0) null else groups.getOrNull(index - 1)?.id }
        )
      }
    }
    item { SmallTitle("主机指纹", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) }
    item {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (hostKeyFingerprint.isBlank()) {
            Text("首次连接会读出主机指纹，请和电脑上的 ssh-keygen 结果核对后再信任。",
              style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          } else {
            Text(hostKeyType.ifBlank { "SSH" }, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            Text(hostKeyFingerprint, style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
              maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(text = "重置主机指纹", onClick = {
              hostKeyType = ""; hostKeyFingerprint = ""; result = null
            }, colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error))
          }
        }
      }
    }
    item {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(text = if (working) "正在测试…" else "测试连接", enabled = !working, onClick = { test() })
        Text(testStatus?.let(::sshStepText).orEmpty(), style = MiuixTheme.textStyles.footnote2.copy(
          color = when (testStatus?.phase) {
            com.igng.opencode.lagoon.core.SshLinkPhase.CONNECTED -> MiuixColorTokens.Success
            com.igng.opencode.lagoon.core.SshLinkPhase.FAILED -> MiuixColorTokens.Error
            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
          }
        ))
      }
    }
    result?.let { text ->
      item {
        Text(text, style = MiuixTheme.textStyles.footnote1.copy(
          color = if (text.startsWith("已连接")) MiuixColorTokens.Success else MiuixColorTokens.Error
        ))
      }
    }
    item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
        TextButton(text = if (working) "正在保存…" else "保存并连接", enabled = !working, onClick = { save() }, modifier = Modifier.weight(1f))
      }
    }
    if (initial != null) item {
      TextButton(
        text = "删除 DSH SSH 主机",
        onClick = { confirmingDelete = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error)
      )
    }
  }

  if (confirmingDelete && initial != null) {
    SuperDialog(title = "删除 ${initial.name}？", show = true, onDismissRequest = { confirmingDelete = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("会删除本机的 SSH 配置、加密凭据和对应的 DSH 连接资料；远端的 dsh web 与会话不会受到影响。",
          style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { confirmingDelete = false }, "确认删除", danger = true) {
          controller.deleteSshHost(initial.id)
          confirmingDelete = false
          onSaved()
        }
      }
    }
  }

  val key = pendingKey
  if (key != null) {
    SshHostKeyDialog(
      presented = key,
      saved = hostKeyFingerprint.takeIf { it.isNotBlank() && it != key.fingerprint },
      onDismiss = { pendingKey = null; pendingAction = null; pendingConfig = null },
      onAccept = {
        hostKeyType = key.type
        hostKeyFingerprint = key.fingerprint
        val action = pendingAction
        val base = pendingConfig
        pendingKey = null; pendingAction = null; pendingConfig = null
        if (action != null && base != null) {
          action(base.copyWith(hostKeyType = key.type, hostKeyFingerprint = key.fingerprint))
        }
      }
    )
  }
}

@Composable
private fun SshHostKeyDialog(
  presented: PresentedSshHostKey,
  saved: String?,
  onDismiss: () -> Unit,
  onAccept: () -> Unit
) {
  val changed = saved != null
  SuperDialog(
    title = if (changed) "主机指纹变了" else "信任这台主机？",
    show = true,
    onDismissRequest = onDismiss
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        if (changed) "已记住的指纹和这次收到的不同。只有确认主机重装或更换过，才应该重新信任。"
        else "首次连接会记住这台主机。请和电脑上的 ssh-keygen 结果核对：",
        style = MiuixTheme.textStyles.body2
      )
      Text("${presented.shortType}\n${presented.fingerprint}",
        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace))
      Text(
        "在主机上运行：ssh-keygen -lf /etc/ssh/ssh_host_${presented.shortType.lowercase()}_key.pub\n之后指纹一旦变化，App 会拒绝连接。",
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      )
      DialogActions(
        "取消", onDismiss,
        if (changed) "重新信任" else "信任并连接",
        danger = changed,
        onConfirm = onAccept
      )
    }
  }
}

private fun sshStepText(status: SshLinkStatus): String = when (status.phase) {
  com.igng.opencode.lagoon.core.SshLinkPhase.CONNECTED -> "已连接"
  com.igng.opencode.lagoon.core.SshLinkPhase.FAILED -> status.error?.message ?: "连接失败"
  com.igng.opencode.lagoon.core.SshLinkPhase.RECONNECTING -> "重连中：${status.detail}"
  com.igng.opencode.lagoon.core.SshLinkPhase.CONNECTING -> status.detail.ifBlank { "正在连接…" }
  com.igng.opencode.lagoon.core.SshLinkPhase.IDLE -> ""
}

private const val MAX_KEY_BYTES = 64 * 1024
