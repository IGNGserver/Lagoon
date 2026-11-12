package com.igng.opencode.lagoon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import com.igng.opencode.lagoon.core.DEFAULT_DSH_LAUNCH_COMMAND
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.SSH_DEFAULT_PORT
import com.igng.opencode.lagoon.core.SshAddress
import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Add or edit one SSH host. The first screen is only address + user + secret; port, auth method,
 * private-key options, the remote `dsh web` command, the display name and the pinned host key live
 * under 「更多选项」. A saved host whose hidden values differ from their defaults opens that section
 * by itself, so a custom port or launch command can never hide from the person editing it.
 */
@Composable
internal fun SshHostForm(
  initial: SshHostConfig?,
  state: LagoonState,
  controller: LagoonController,
  onCancel: () -> Unit,
  onSaved: (String) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val hostId = remember(initial?.id) { initial?.id ?: SshHostConfig.newId() }
  val initialSecrets = remember(initial?.id) { initial?.let { controller.sshHostSecrets(it.id) } ?: SshSecrets() }

  var address by rememberSaveable(initial?.id) { mutableStateOf(initial?.host.orEmpty()) }
  var user by rememberSaveable(initial?.id) { mutableStateOf(initial?.user.orEmpty()) }
  var port by rememberSaveable(initial?.id) { mutableStateOf((initial?.port ?: SSH_DEFAULT_PORT).toString()) }
  var auth by rememberSaveable(initial?.id) { mutableStateOf(initial?.auth ?: SshAuthMethod.PASSWORD) }
  var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
  var command by rememberSaveable(initial?.id) {
    mutableStateOf(initial?.launchCommand ?: DEFAULT_DSH_LAUNCH_COMMAND)
  }
  var workingDirectory by rememberSaveable(initial?.id) { mutableStateOf(initial?.workingDirectory.orEmpty()) }
  // Saved secrets stay in Android Keystore. Empty fields mean “keep saved”, never “show saved”.
  var password by remember(initial?.id) { mutableStateOf("") }
  var privateKey by remember(initial?.id) { mutableStateOf("") }
  var passphrase by remember(initial?.id) { mutableStateOf("") }
  var privateKeyVisible by remember { mutableStateOf(false) }
  var clearedSecrets by remember { mutableStateOf(setOf<SshSecretField>()) }
  var hostKeyType by rememberSaveable(initial?.id) { mutableStateOf(initial?.hostKeyType.orEmpty()) }
  var hostKeyFingerprint by rememberSaveable(initial?.id) { mutableStateOf(initial?.hostKeyFingerprint.orEmpty()) }
  var showMore by rememberSaveable(initial?.id) {
    mutableStateOf(
      initial != null && moreOptionsSummary(
        auth = initial.auth,
        port = initial.port.toString(),
        command = initial.launchCommand,
        workingDirectory = initial.workingDirectory.orEmpty(),
        name = initial.name,
        host = initial.host,
        fingerprint = initial.hostKeyFingerprint.orEmpty()
      ) != null
    )
  }
  var result by remember { mutableStateOf<String?>(null) }
  var working by remember { mutableStateOf(false) }
  var pendingKey by remember { mutableStateOf<PresentedSshHostKey?>(null) }
  var pendingAction by remember { mutableStateOf<((SshHostConfig) -> Unit)?>(null) }
  var pendingConfig by remember { mutableStateOf<SshHostConfig?>(null) }
  var importError by remember { mutableStateOf<String?>(null) }
  var confirmingDelete by remember { mutableStateOf(false) }
  var confirmingSecretClear by remember { mutableStateOf<SshSecretField?>(null) }
  var confirmingFingerprintReset by remember { mutableStateOf(false) }

  // A pinned key belongs to the address it was read from. Clear the display as soon as the user
  // edits host/port so an old fingerprint can never be sent to a different machine.
  LaunchedEffect(address, port) {
    val base = initial ?: return@LaunchedEffect
    if (address.trim() != base.host || port.trim().toIntOrNull() != base.port) {
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
        clearedSecrets = clearedSecrets - SshSecretField.PRIVATE_KEY
        privateKeyVisible = false
        importError = null
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        importError = error.message ?: "读取私钥失败"
      }
    }
  }

  fun secrets(): SshSecrets = SshSecrets(
    password = resolveSshSecret(password, initialSecrets.password, SshSecretField.PASSWORD in clearedSecrets),
    privateKey = resolveSshSecret(privateKey, initialSecrets.privateKey, SshSecretField.PRIVATE_KEY in clearedSecrets),
    passphrase = resolveSshSecret(passphrase, initialSecrets.passphrase, SshSecretField.PASSPHRASE in clearedSecrets)
  )

  fun configOrNull(): SshHostConfig? {
    // The address may still carry `user@` / `:port` if it was typed rather than pasted.
    val parts = SshAddress.parse(address)
    val host = parts.host.trim()
    val userName = (parts.user ?: user).trim()
    val portValue = parts.port ?: port.trim().toIntOrNull()
    if (host.isBlank()) {
      result = "填写地址"
      return null
    }
    if (userName.isBlank()) {
      result = "填写用户名"
      return null
    }
    if (portValue == null || portValue !in 1..65535) {
      result = "端口需在 1–65535"
      return null
    }
    if (auth == SshAuthMethod.PASSWORD &&
      !hasUsableSshSecret(password, initialSecrets.password, SshSecretField.PASSWORD in clearedSecrets)
    ) {
      result = "填写 SSH 密码，或保留已保存的密码"
      return null
    }
    if (auth == SshAuthMethod.PRIVATE_KEY &&
      !hasUsableSshSecret(privateKey, initialSecrets.privateKey, SshSecretField.PRIVATE_KEY in clearedSecrets)
    ) {
      result = "粘贴或导入 SSH 私钥，或保留已保存的私钥"
      return null
    }
    // A pinned key belongs to one address; moving the host must force a fresh confirmation.
    val pinStillBelongs = initial == null || (host == initial.host && portValue == initial.port)
    return SshHostConfig(
      id = hostId,
      name = name.trim().ifBlank { host },
      host = host,
      port = portValue,
      user = userName,
      auth = auth,
      launchCommand = command.trim().ifBlank { DEFAULT_DSH_LAUNCH_COMMAND },
      workingDirectory = workingDirectory.trim().takeIf { it.isNotEmpty() },
      hostKeyType = hostKeyType.takeIf { pinStillBelongs && it.isNotBlank() },
      hostKeyFingerprint = hostKeyFingerprint.takeIf { pinStillBelongs && it.isNotBlank() }
    )
  }

  fun errorText(error: Throwable): String =
    if (error is SshFailure) error.display() else userFacingError(error, "SSH 连接失败")

  /**
   * 只保存设备本身的接入配置。这个表单不再预选 Harness：设备页会做一次只读发现，再由用户逐个启用；
   * 「仅保存，不连接」与普通保存产出的都是同一台设备，只是不校验主机指纹。
   */
  fun commit(config: SshHostConfig, offline: Boolean = false) {
    working = true
    result = null
    try {
      controller.saveSshSourceOnly(config, secrets())
      result = if (offline) "已保存 SSH 设备；首次连接时会再核对主机指纹" else "已保存 SSH 设备"
      onSaved(hostId)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      result = if (error is SshFailure) error.display() else userFacingError(error, "SSH 保存失败")
      working = false
    }
  }

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

  /**
   * 只读连接测试：读一次主机指纹与目录标记，不启动、不终止任何远端服务，也不挑选 Harness。
   * Harness 的可用性由设备页的「刷新发现」给出。
   */
  fun testConnection() {
    val config = configOrNull() ?: return
    withPinnedKey(config) { pinned ->
      working = true; result = null
      scope.launch {
        try {
          val found = controller.discoverSshHarnesses(pinned, secrets(), null)
          val codex = controller.discoverSshCodex(pinned, secrets())
          result = buildString {
            append("SSH 可用")
            append(" · OpenCode 注册：${found.openCode.state}")
            append(" · DSH 标记：${found.dshLegacy.state}")
            append(" · Codex：${when (codex.installed) { true -> codex.version ?: "已安装"; false -> "未安装"; null -> "未知" }}")
            append("\n发现不会启动服务；在那台设备的页面启用 Harness 后才会连接。")
          }
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

  /** [offline] stores the host without reaching it; the pin is confirmed on the first real connect. */
  fun save(offline: Boolean) {
    val config = configOrNull() ?: return
    if (offline) {
      commit(config, offline = true)
      return
    }
    withPinnedKey(config) { pinned -> commit(pinned) }
  }

  // The address can still carry a port when it was typed rather than pasted; the form always
  // shows and saves the effective value so the hidden 更多选项 field never contradicts it.
  val effectivePort = SshAddress.parse(address).port?.toString() ?: port.trim()

  LazyColumn(
    Modifier.fillMaxWidth().heightIn(max = 720.dp),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      TextField(
        value = address,
        onValueChange = { raw ->
          // Pasting a whole `ssh -p 22 user@host` line splits it once; typing is never rewritten,
          // so a hand-typed ":2222" keeps its digits in the field until the form is saved.
          val pasted = raw.length - address.length > 1
          val parts = SshAddress.parse(raw)
          val filled = if (pasted && (parts.user != null || parts.port != null)) {
            if (parts.user != null) user = parts.user
            if (parts.port != null) port = parts.port.toString()
            true
          } else false
          address = if (filled) parts.host else raw
        },
        label = "地址（IP 或域名）",
        modifier = Modifier.fillMaxWidth()
      )
    }
    if (initial == null && address.isBlank()) item {
      Text(
        "也可以直接粘贴 user@主机 或 ssh -p 端口 user@主机",
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
      )
    }
    item {
      TextField(
        value = user,
        onValueChange = { typed ->
          user = typed
          // The dedicated field wins: drop an inline `user@` the address was still carrying.
          address = SshAddress.parse(address).let { parts -> if (parts.user != null) parts.host else address }
        },
        label = "用户名",
        modifier = Modifier.fillMaxWidth()
      )
    }
    if (auth == SshAuthMethod.PASSWORD) {
      item {
        TextField(
          value = password,
          onValueChange = {
            password = it
            clearedSecrets = clearedSecrets - SshSecretField.PASSWORD
          },
          label = when {
            SshSecretField.PASSWORD in clearedSecrets -> "新密码（留空即清除已保存）"
            initialSecrets.password != null -> "新密码（留空沿用已保存）"
            else -> "密码"
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
      if (initialSecrets.password != null) item {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            if (SshSecretField.PASSWORD in clearedSecrets) "保存后清除已保存密码" else "已保存密码，不会显示原值",
            style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
          )
          if (SshSecretField.PASSWORD !in clearedSecrets) {
            HyperTextAction(
              text = "清除已保存密码",
              danger = true,
              onClick = { confirmingSecretClear = SshSecretField.PASSWORD }
            )
          }
        }
      }
    } else {
      item {
        TextField(
          value = privateKey,
          onValueChange = {
            privateKey = it
            clearedSecrets = clearedSecrets - SshSecretField.PRIVATE_KEY
          },
          label = when {
            SshSecretField.PRIVATE_KEY in clearedSecrets -> "新私钥（留空即清除已保存）"
            initialSecrets.privateKey != null -> "新私钥（留空沿用已保存）"
            else -> "私钥（OpenSSH / PEM 全文）"
          },
          singleLine = false,
          visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            when {
              SshSecretField.PRIVATE_KEY in clearedSecrets -> "保存后清除已保存私钥"
              initialSecrets.privateKey != null -> "已保存私钥，不会显示原值"
              else -> "私钥只保存在本机加密存储中"
            },
            style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
          )
          Row(verticalAlignment = Alignment.CenterVertically) {
            HyperTextAction(
              text = "导入私钥文件",
              onClick = { keyPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
            )
            HyperTextAction(
              text = if (privateKeyVisible) "隐藏新私钥" else "显示新私钥",
              enabled = privateKey.isNotEmpty(),
              onClick = { privateKeyVisible = !privateKeyVisible }
            )
          }
          if (initialSecrets.privateKey != null && SshSecretField.PRIVATE_KEY !in clearedSecrets) {
            HyperTextAction(
              text = "清除已保存私钥",
              danger = true,
              onClick = { confirmingSecretClear = SshSecretField.PRIVATE_KEY }
            )
          }
          importError?.let {
            Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error))
          }
        }
      }
      item {
        TextField(
          value = passphrase,
          onValueChange = {
            passphrase = it
            clearedSecrets = clearedSecrets - SshSecretField.PASSPHRASE
          },
          label = when {
            SshSecretField.PASSPHRASE in clearedSecrets -> "新的私钥密码（留空即清除已保存）"
            initialSecrets.passphrase != null -> "新的私钥密码（留空沿用已保存）"
            else -> "私钥密码（可选）"
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
      if (initialSecrets.passphrase != null) item {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            if (SshSecretField.PASSPHRASE in clearedSecrets) "保存后清除已保存的私钥密码" else "已保存私钥密码，不会显示原值",
            style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
          )
          if (SshSecretField.PASSPHRASE !in clearedSecrets) {
            HyperTextAction(
              text = "清除已保存的私钥密码",
              danger = true,
              onClick = { confirmingSecretClear = SshSecretField.PASSPHRASE }
            )
          }
        }
      }
    }
    item {
      Text(
        "保存后这台主机会作为一台设备出现在「设备」里；到那台设备的页面刷新发现，再逐个启用 DSH / OpenCode / Codex。",
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
      )
    }
    item {
      HyperActionRow(
        title = "更多选项",
        summary = if (showMore) "端口、认证方式、启动命令与主机指纹" else moreOptionsSummary(
          auth = auth,
          port = effectivePort,
          command = command,
          workingDirectory = workingDirectory,
          name = name,
          host = address,
          fingerprint = hostKeyFingerprint
        ) ?: "端口 $port · 密码登录",
        trailing = { ExpandChevron(showMore) },
        onClick = { showMore = !showMore }
      )
    }
    if (showMore) {
      item {
        HyperDropdownRow(
          title = "认证方式",
          items = SshAuthMethod.entries.map { it.label },
          selectedIndex = SshAuthMethod.entries.indexOf(auth),
          onSelectedIndexChange = { auth = SshAuthMethod.entries[it] }
        )
      }
      item {
        TextField(
          // A hand-typed ":2222" stays in the address field, so show the effective value here.
          value = effectivePort,
          onValueChange = { typed ->
            port = typed.filter(Char::isDigit)
            // The dedicated field wins: drop a `:port` the address was still carrying.
            address = SshAddress.parse(address).let { parts -> if (parts.port != null) parts.host else address }
          },
          label = "端口（默认 $SSH_DEFAULT_PORT）",
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        TextField(
          value = command,
          onValueChange = { command = it },
          label = "启动命令（默认 $DEFAULT_DSH_LAUNCH_COMMAND）",
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        TextField(
          value = workingDirectory,
          onValueChange = { workingDirectory = it },
          label = "远端工作目录（可选，例如 ~/code）",
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        TextField(
          value = name,
          onValueChange = { name = it },
          label = "名称（可选，默认用地址）",
          modifier = Modifier.fillMaxWidth()
        )
      }
      item { HyperGroupTitle("主机指纹") }
      item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(14.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hostKeyFingerprint.isBlank()) {
              Text(
                "首次连接会读出主机指纹，核对后固定；之后指纹变化会直接拒绝连接。",
                style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
              )
            } else {
              Text(
                hostKeyType.ifBlank { "SSH" },
                style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
              )
              Text(hostKeyFingerprint, style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace))
              HyperTextAction(
                text = "重置主机指纹",
                danger = true,
                onClick = { confirmingFingerprintReset = true }
              )
            }
          }
        }
      }
      item {
        HyperButton(
          text = "只读测试 SSH 连接",
          variant = HyperButtonVariant.SECONDARY,
          enabled = !working,
          loading = working,
          onClick = { testConnection() },
          modifier = Modifier.fillMaxWidth()
        )
      }
      if (initial != null) item {
        HyperTextAction(
          text = "删除这台主机",
          danger = true,
          onClick = { confirmingDelete = true },
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
    result?.let { text ->
      item {
        Text(
          text,
          style = MiuixTheme.textStyles.footnote1.copy(
            color = if (text.startsWith("已连接")) MiuixColorTokens.Success else MiuixColorTokens.Error
          )
        )
      }
    }
    item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton(
          text = "取消",
          variant = HyperButtonVariant.SECONDARY,
          onClick = onCancel,
          modifier = Modifier.weight(1f)
        )
        HyperButton(
          text = if (initial == null) "保存 SSH 设备" else "保存设备配置",
          enabled = !working,
          loading = working,
          onClick = { save(offline = false) },
          modifier = Modifier.weight(1f)
        )
      }
    }
    if (initial == null) item {
      HyperTextAction(
        text = "跳过指纹核对，仅保存",
        enabled = !working,
        onClick = { save(offline = true) },
        modifier = Modifier.fillMaxWidth()
      )
    }
  }

  if (confirmingDelete && initial != null) {
    SuperDialog(title = "删除 ${initial.name}？", show = true, onDismissRequest = { confirmingDelete = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "会删除本机的 SSH 配置、加密凭据和该主机下全部 Harness 连接资料；远端进程与会话不会受到影响。",
          style = MiuixTheme.textStyles.body1
        )
        DialogActions("取消", { confirmingDelete = false }, "确认删除", danger = true) {
          controller.deleteSshHost(initial.id)
          confirmingDelete = false
          onSaved(initial.id)
        }
      }
    }
  }

  confirmingSecretClear?.let { field ->
    val label = when (field) {
      SshSecretField.PASSWORD -> "SSH 密码"
      SshSecretField.PRIVATE_KEY -> "SSH 私钥"
      SshSecretField.PASSPHRASE -> "私钥密码"
    }
    SuperDialog(title = "清除已保存的$label？", show = true, onDismissRequest = { confirmingSecretClear = null }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("保存主机后，本机加密存储中的${label}将被删除。远端主机不会受影响。", style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { confirmingSecretClear = null }, "确认清除", danger = true) {
          clearedSecrets = clearedSecrets + field
          when (field) {
            SshSecretField.PASSWORD -> password = ""
            SshSecretField.PRIVATE_KEY -> privateKey = ""
            SshSecretField.PASSPHRASE -> passphrase = ""
          }
          confirmingSecretClear = null
        }
      }
    }
  }

  if (confirmingFingerprintReset) {
    SuperDialog(title = "重置主机指纹？", show = true, onDismissRequest = { confirmingFingerprintReset = false }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "下次连接会重新显示远端指纹，必须重新核对并信任后才能继续。仅在主机确实重装或更换时操作。",
          style = MiuixTheme.textStyles.body1
        )
        DialogActions("取消", { confirmingFingerprintReset = false }, "确认重置", danger = true) {
          hostKeyType = ""
          hostKeyFingerprint = ""
          result = null
          confirmingFingerprintReset = false
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

/** One-line summary of the values under 「更多选项」; null when every one of them is still default. */
private fun moreOptionsSummary(
  auth: SshAuthMethod,
  port: String,
  command: String,
  workingDirectory: String,
  name: String,
  host: String,
  fingerprint: String
): String? {
  val portValue = port.trim().toIntOrNull() ?: SSH_DEFAULT_PORT
  val parts = buildList {
    if (auth != SshAuthMethod.PASSWORD) add("私钥登录")
    if (portValue != SSH_DEFAULT_PORT) add("端口 $portValue")
    if (command.trim() != DEFAULT_DSH_LAUNCH_COMMAND) add("自定义启动命令")
    if (workingDirectory.isNotBlank()) add("工作目录")
    if (name.isNotBlank() && name.trim() != host.trim()) add("自定义名称")
    if (fingerprint.isNotBlank()) add("已固定指纹")
  }
  return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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
      Text(
        "${presented.shortType}\n${presented.fingerprint}",
        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace)
      )
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

private const val MAX_KEY_BYTES = 64 * 1024
