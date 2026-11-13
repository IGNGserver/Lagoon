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
import com.igng.opencode.lagoon.core.DEFAULT_DSH_LAUNCH_COMMAND
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.SSH_DEFAULT_PORT
import com.igng.opencode.lagoon.core.SshAddress
import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshKeyProfile
import com.igng.opencode.lagoon.core.SshPassphrasePolicy
import com.igng.opencode.lagoon.core.SshSecrets
import com.igng.opencode.lagoon.core.ssh.ParsedSshPrivateKey
import com.igng.opencode.lagoon.core.ssh.SshKeyCodec
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Add or edit one SSH host. The first screen covers address, user, auth mode (`密码` / `密钥` /
 * `密钥优先`), and credentials/keychain selection; port, the remote `dsh web` command, the
 * display name and the pinned host key live under 「更多选项」. A saved host whose hidden values
 * differ from their defaults opens that section by itself, so a custom port or launch command can
 * never hide from the person editing it.
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
  var selectedKeyId by rememberSaveable(initial?.id) { mutableStateOf(initial?.keyId) }
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
  var showInlineKeyEditor by rememberSaveable(initial?.id) { mutableStateOf(false) }
  var clearedSecrets by remember { mutableStateOf(setOf<SshSecretField>()) }
  var parsedInlineKey by remember { mutableStateOf<ParsedSshPrivateKey?>(null) }
  var inlineInspectError by remember { mutableStateOf<String?>(null) }
  var hostKeyType by rememberSaveable(initial?.id) { mutableStateOf(initial?.hostKeyType.orEmpty()) }
  var hostKeyFingerprint by rememberSaveable(initial?.id) { mutableStateOf(initial?.hostKeyFingerprint.orEmpty()) }
  var showMore by rememberSaveable(initial?.id) {
    mutableStateOf(
      initial != null && moreOptionsSummary(
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
  var resultIsError by remember { mutableStateOf(false) }
  var working by remember { mutableStateOf(false) }
  var pendingKey by remember { mutableStateOf<PresentedSshHostKey?>(null) }
  var pendingAction by remember { mutableStateOf<((SshHostConfig) -> Unit)?>(null) }
  var pendingConfig by remember { mutableStateOf<SshHostConfig?>(null) }
  var importError by remember { mutableStateOf<String?>(null) }
  var confirmingDelete by remember { mutableStateOf(false) }
  var confirmingSecretClear by remember { mutableStateOf<SshSecretField?>(null) }
  var confirmingFingerprintReset by remember { mutableStateOf(false) }

  // Keychain & Phase 2/3 modals
  var showKeychainSheet by remember { mutableStateOf(false) }
  var showGenerateDialog by remember { mutableStateOf(false) }
  var showImportDialog by remember { mutableStateOf(false) }
  var certificateTarget by remember { mutableStateOf<SshKeyProfile?>(null) }
  var unlockTarget by remember { mutableStateOf<SshKeyProfile?>(null) }
  var deployPublicKey by remember { mutableStateOf<String?>(null) }
  var retryTestAfterUnlock by remember { mutableStateOf(false) }

  val selectedKeyProfile = remember(selectedKeyId, state.sshKeys, state.sshKeysRevision) {
    selectedKeyId?.let { id -> state.sshKeys.firstOrNull { it.id == id } }
  }
  val hasLegacyInlineKey = initialSecrets.privateKey != null &&
    SshSecretField.PRIVATE_KEY !in clearedSecrets &&
    selectedKeyProfile == null

  // A pinned key belongs to the address it was read from. Clear the display as soon as the user
  // edits host/port so an old fingerprint can never be sent to a different machine.
  LaunchedEffect(address, port) {
    val base = initial ?: return@LaunchedEffect
    if (address.trim() != base.host || port.trim().toIntOrNull() != base.port) {
      hostKeyType = ""
      hostKeyFingerprint = ""
    }
  }

  // Real JSch/BouncyCastle inspection for direct inline key edits.
  LaunchedEffect(privateKey, passphrase) {
    val trimmed = privateKey.trim()
    if (trimmed.isEmpty()) {
      parsedInlineKey = null
      inlineInspectError = null
      return@LaunchedEffect
    }
    try {
      parsedInlineKey = withContext(Dispatchers.Default) {
        SshKeyCodec.inspectPrivateKey(trimmed, passphrase.takeIf { it.isNotEmpty() })
      }
      inlineInspectError = null
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (failure: SshFailure) {
      parsedInlineKey = null
      inlineInspectError = failure.display()
    } catch (error: Exception) {
      parsedInlineKey = null
      inlineInspectError = error.message ?: "私钥无法解析"
    }
  }

  val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      try {
        val text = readTextDocument(context, uri)
        // Validate using real SSH key codec instead of substring matching.
        withContext(Dispatchers.Default) { SshKeyCodec.inspectPrivateKey(text.trim(), passphrase.takeIf { it.isNotEmpty() }) }
        selectedKeyId = null
        showInlineKeyEditor = true
        privateKey = text.trim()
        clearedSecrets = clearedSecrets - SshSecretField.PRIVATE_KEY
        privateKeyVisible = false
        importError = null
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (failure: SshFailure) {
        importError = failure.display()
      } catch (error: Exception) {
        importError = error.message ?: "读取私钥失败"
      }
    }
  }

  fun secrets(): SshSecrets = SshSecrets(
    password = if (auth == SshAuthMethod.PRIVATE_KEY) null else resolveSshSecret(
      password, initialSecrets.password, SshSecretField.PASSWORD in clearedSecrets
    ),
    privateKey = if (auth == SshAuthMethod.PASSWORD || selectedKeyProfile != null) null else resolveSshSecret(
      privateKey, initialSecrets.privateKey, SshSecretField.PRIVATE_KEY in clearedSecrets
    ),
    passphrase = if (auth == SshAuthMethod.PASSWORD || selectedKeyProfile != null) null else resolveSshSecret(
      passphrase, initialSecrets.passphrase, SshSecretField.PASSPHRASE in clearedSecrets
    ),
    certificate = if (auth == SshAuthMethod.PASSWORD || selectedKeyProfile != null) null else initialSecrets.certificate
  )

  fun hostEndpointOrNull(): SshHostConfig? {
    val parts = SshAddress.parse(address)
    val host = parts.host.trim()
    val userName = (parts.user ?: user).trim()
    val portValue = parts.port ?: port.trim().toIntOrNull()
    if (host.isBlank()) {
      result = "填写地址"; resultIsError = true
      return null
    }
    if (userName.isBlank()) {
      result = "填写用户名"; resultIsError = true
      return null
    }
    if (portValue == null || portValue !in 1..65535) {
      result = "端口需在 1–65535"; resultIsError = true
      return null
    }
    val pinStillBelongs = initial == null || (host == initial.host && portValue == initial.port)
    return SshHostConfig(
      id = hostId,
      name = name.trim().ifBlank { host },
      host = host,
      port = portValue,
      user = userName,
      auth = auth,
      keyId = if (auth == SshAuthMethod.PASSWORD) null else selectedKeyId?.takeIf { selectedKeyProfile != null },
      launchCommand = command.trim().ifBlank { DEFAULT_DSH_LAUNCH_COMMAND },
      workingDirectory = workingDirectory.trim().takeIf { it.isNotEmpty() },
      hostKeyType = hostKeyType.takeIf { pinStillBelongs && it.isNotBlank() },
      hostKeyFingerprint = hostKeyFingerprint.takeIf { pinStillBelongs && it.isNotBlank() }
    )
  }

  fun configOrNull(): SshHostConfig? {
    val base = hostEndpointOrNull() ?: return null
    if (auth == SshAuthMethod.PASSWORD &&
      !hasUsableSshSecret(password, initialSecrets.password, SshSecretField.PASSWORD in clearedSecrets)
    ) {
      result = "填写 SSH 密码，或保留已保存的密码"; resultIsError = true
      return null
    }
    if (auth == SshAuthMethod.PRIVATE_KEY || auth == SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK) {
      val hasKeychainKey = selectedKeyProfile != null
      val hasInlineKey = !hasKeychainKey && hasUsableSshSecret(
        privateKey, initialSecrets.privateKey, SshSecretField.PRIVATE_KEY in clearedSecrets
      )
      if (!hasKeychainKey && !hasInlineKey) {
        result = "请从密钥库生成 / 选择密钥，或粘贴 SSH 私钥"; resultIsError = true
        return null
      }
      if (!hasKeychainKey && inlineInspectError != null) {
        result = inlineInspectError; resultIsError = true
        return null
      }
    }
    if (auth == SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK &&
      !hasUsableSshSecret(password, initialSecrets.password, SshSecretField.PASSWORD in clearedSecrets)
    ) {
      result = "「密钥优先」模式需填写备用密码，或切换为「密钥」模式"; resultIsError = true
      return null
    }
    return base
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
    resultIsError = false
    try {
      controller.saveSshSourceOnly(config, secrets())
      result = if (offline) "已保存 SSH 设备；首次连接时会再核对主机指纹" else "已保存 SSH 设备"
      resultIsError = false
      onSaved(hostId)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      result = if (error is SshFailure) error.display() else userFacingError(error, "SSH 保存失败")
      resultIsError = true
      working = false
    }
  }

  fun withPinnedKey(config: SshHostConfig, action: (SshHostConfig) -> Unit) {
    if (!config.hostKeyFingerprint.isNullOrBlank()) {
      action(config)
      return
    }
    if (working) return
    working = true; result = null; resultIsError = false; pendingKey = null
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
        resultIsError = true
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
      working = true; result = null; resultIsError = false
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
          resultIsError = false
          hostKeyType = pinned.hostKeyType.orEmpty()
          hostKeyFingerprint = pinned.hostKeyFingerprint.orEmpty()
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (error: Exception) {
          result = errorText(error)
          resultIsError = true
          (error as? SshFailure)?.presentedKey?.let { pendingKey = it }
          if (error is SshFailure &&
            (error.kind == SshFailureKind.PASSPHRASE_REQUIRED || error.kind == SshFailureKind.BIOMETRIC_REQUIRED) &&
            selectedKeyProfile != null
          ) {
            retryTestAfterUnlock = true
            unlockTarget = selectedKeyProfile
          }
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
    Modifier.fillMaxWidth().heightIn(max = 760.dp),
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
    item {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          "验证方式",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
        HyperSegmentedTabs(
          tabs = SshAuthMethod.entries.map { it.label },
          selectedIndex = SshAuthMethod.entries.indexOf(auth).coerceAtLeast(0),
          onTabSelected = { index ->
            auth = SshAuthMethod.entries[index]
            result = null
          }
        )
        Text(
          when (auth) {
            SshAuthMethod.PASSWORD -> "使用用户名和密码（含 keyboard-interactive）连接"
            SshAuthMethod.PRIVATE_KEY -> "仅使用 SSH 密钥验证，不发送密码"
            SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK -> "优先尝试 SSH 密钥，未命中时自动回退到密码"
          },
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
    }

    if (auth == SshAuthMethod.PRIVATE_KEY || auth == SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK) {
      item {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(14.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedKeyProfile != null) {
              val unlocked = controller.isSshKeyUnlocked(selectedKeyProfile)
              val hasUnlockGate = selectedKeyProfile.biometricProtected ||
                selectedKeyProfile.passphrasePolicy == SshPassphrasePolicy.BIOMETRIC ||
                (selectedKeyProfile.passphrasePolicy == SshPassphrasePolicy.PROMPT && selectedKeyProfile.encrypted)
              Text(
                "已选密钥：${selectedKeyProfile.name}",
                style = MiuixTheme.textStyles.body1
              )
              Text(
                "${selectedKeyProfile.source.label} · ${selectedKeyProfile.summary}",
                style = MiuixTheme.textStyles.footnote2.copy(
                  color = HyperTheme.colors.textSecondary,
                  fontFamily = FontFamily.Monospace
                )
              )
              if (hasUnlockGate) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                  Text(
                    if (unlocked) "当前会话已解锁" else "未解锁（连接前需验证）",
                    style = MiuixTheme.textStyles.footnote2.copy(
                      color = if (unlocked) MiuixColorTokens.Success else MiuixColorTokens.Warning
                    )
                  )
                  HyperTextAction(
                    text = if (unlocked) "锁定" else "立即解锁",
                    onClick = {
                      if (unlocked) controller.lockSshKey(selectedKeyProfile.id)
                      else unlockTarget = selectedKeyProfile
                    }
                  )
                }
              }
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                HyperTextAction(
                  text = "更换密钥",
                  onClick = { showKeychainSheet = true }
                )
                HyperTextAction(
                  text = "复制公钥",
                  onClick = {
                    copyTextToClipboard(context, selectedKeyProfile.name, selectedKeyProfile.publicKeyOpenSsh)
                    result = "已复制「${selectedKeyProfile.name}」的 OpenSSH 公钥"
                    resultIsError = false
                  }
                )
                HyperTextAction(
                  text = if (selectedKeyProfile.hasCertificate) "证书" else "添加证书",
                  onClick = { certificateTarget = selectedKeyProfile }
                )
              }
              HyperButton(
                text = "一键安装公钥到服务器",
                variant = HyperButtonVariant.SECONDARY,
                enabled = !working,
                onClick = {
                  if (hostEndpointOrNull() != null) {
                    deployPublicKey = selectedKeyProfile.publicKeyOpenSsh
                  }
                },
                modifier = Modifier.fillMaxWidth()
              )
            } else {
              Text(
                when {
                  hasLegacyInlineKey -> "当前使用旧版独立私钥（已加密保存）"
                  state.sshKeys.isNotEmpty() -> "请选择密钥库中的密钥，或新建 / 导入私钥"
                  else -> "生成一把本机 SSH 密钥（推荐 Ed25519 或硬件密钥），或导入已有私钥"
                },
                style = MiuixTheme.textStyles.body2
              )
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HyperButton(
                  text = if (state.sshKeys.isNotEmpty()) "从密钥库选择 (${state.sshKeys.size})" else "生成新密钥",
                  onClick = { if (state.sshKeys.isNotEmpty()) showKeychainSheet = true else showGenerateDialog = true },
                  modifier = Modifier.weight(1f)
                )
                HyperButton(
                  text = "导入私钥",
                  variant = HyperButtonVariant.SECONDARY,
                  onClick = { showImportDialog = true },
                  modifier = Modifier.weight(1f)
                )
              }
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                if (state.sshKeys.isNotEmpty()) {
                  HyperTextAction(
                    text = "生成新密钥",
                    onClick = { showGenerateDialog = true }
                  )
                }
                if (hasLegacyInlineKey) {
                  HyperTextAction(
                    text = "转为全局密钥库密钥",
                    onClick = {
                      val legacyKey = initialSecrets.privateKey ?: return@HyperTextAction
                      val legacyPass = initialSecrets.passphrase
                      val legacyCert = initialSecrets.certificate
                      scope.launch {
                        try {
                          val imported = controller.importSshKey(
                            name = name.ifBlank { address.ifBlank { "导入密钥" } },
                            privateKeyText = legacyKey,
                            passphrase = legacyPass,
                            policy = if (legacyPass.isNullOrEmpty()) SshPassphrasePolicy.NONE else SshPassphrasePolicy.SAVED,
                            certificateText = legacyCert,
                            biometricActivityContext = context
                          )
                          selectedKeyId = imported.id
                          privateKey = ""
                          passphrase = ""
                          clearedSecrets = clearedSecrets + SshSecretField.PRIVATE_KEY + SshSecretField.PASSPHRASE
                          result = "已迁移到全局密钥库「${imported.name}」，点击保存即可生效"
                          resultIsError = false
                        } catch (cancel: CancellationException) {
                          throw cancel
                        } catch (error: Exception) {
                          result = errorText(error)
                          resultIsError = true
                        }
                      }
                    }
                  )
                }
                HyperTextAction(
                  text = if (showInlineKeyEditor) "收起手动粘贴" else "直接粘贴到此主机",
                  onClick = { showInlineKeyEditor = !showInlineKeyEditor }
                )
              }
            }
          }
        }
      }

      if (selectedKeyProfile == null && (showInlineKeyEditor || hasLegacyInlineKey || privateKey.isNotEmpty())) {
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
              else -> "私钥（OpenSSH / PEM / PKCS#8 / PuTTY .ppk）"
            },
            singleLine = false,
            visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
          )
        }
        item {
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            parsedInlineKey?.let { info ->
              Text(
                buildString {
                  append("已识别：${info.algorithm}")
                  if (info.fingerprint.isNotBlank()) append(" · ${info.fingerprint}")
                  if (info.encrypted && !info.decrypted) append("（已加密，请输入私钥密码）")
                },
                style = MiuixTheme.textStyles.footnote2.copy(
                  color = if (info.encrypted && !info.decrypted) MiuixColorTokens.Warning else MiuixColorTokens.Success,
                  fontFamily = FontFamily.Monospace
                )
              )
            }
            inlineInspectError?.let {
              Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              HyperTextAction(
                text = "导入私钥文件",
                onClick = { keyPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
              )
              HyperTextAction(
                text = if (privateKeyVisible) "隐藏新私钥" else "显示新私钥",
                enabled = privateKey.isNotEmpty(),
                onClick = { privateKeyVisible = !privateKeyVisible }
              )
              parsedInlineKey?.publicKeyOpenSsh?.takeIf { it.isNotBlank() }?.let { pubKey ->
                HyperTextAction(
                  text = "安装公钥",
                  onClick = {
                    if (hostEndpointOrNull() != null) deployPublicKey = pubKey
                  }
                )
              }
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
        val shouldShowInlinePassphrase = parsedInlineKey?.encrypted == true ||
          passphrase.isNotEmpty() ||
          initialSecrets.passphrase != null
        if (shouldShowInlinePassphrase) {
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
                else -> "私钥密码"
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
      }
    }

    if (auth == SshAuthMethod.PASSWORD || auth == SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK) {
      val passwordFieldTitle = if (auth == SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK) "备用密码" else "密码"
      item {
        TextField(
          value = password,
          onValueChange = {
            password = it
            clearedSecrets = clearedSecrets - SshSecretField.PASSWORD
          },
          label = when {
            SshSecretField.PASSWORD in clearedSecrets -> "新${passwordFieldTitle}（留空即清除已保存）"
            initialSecrets.password != null -> "新${passwordFieldTitle}（留空沿用已保存）"
            else -> passwordFieldTitle
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
        summary = if (showMore) "端口、启动命令、工作目录与主机指纹" else moreOptionsSummary(
          port = effectivePort,
          command = command,
          workingDirectory = workingDirectory,
          name = name,
          host = address,
          fingerprint = hostKeyFingerprint
        ) ?: "端口 $port · 启动命令与主机指纹",
        trailing = { ExpandChevron(showMore) },
        onClick = { showMore = !showMore }
      )
    }
    if (showMore) {
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
            color = if (!resultIsError) MiuixColorTokens.Success else MiuixColorTokens.Error
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

  SshKeychainManagerSheet(
    show = showKeychainSheet,
    state = state,
    controller = controller,
    onDismiss = { showKeychainSheet = false },
    selectedKeyId = selectedKeyId,
    onSelectKey = { picked ->
      selectedKeyId = picked.id
      showInlineKeyEditor = false
      privateKey = ""
      passphrase = ""
      result = null
    }
  )

  SshKeyGenerateDialog(
    show = showGenerateDialog,
    controller = controller,
    onDismiss = { showGenerateDialog = false },
    onCreated = { created ->
      showGenerateDialog = false
      selectedKeyId = created.id
      showInlineKeyEditor = false
      privateKey = ""
      passphrase = ""
      result = "已生成密钥「${created.name}」(${created.algorithm})，可复制或直接安装公钥到服务器"
      resultIsError = false
    }
  )

  SshKeyImportDialog(
    show = showImportDialog,
    controller = controller,
    onDismiss = { showImportDialog = false },
    onImported = { imported ->
      showImportDialog = false
      selectedKeyId = imported.id
      showInlineKeyEditor = false
      privateKey = ""
      passphrase = ""
      result = "已导入密钥「${imported.name}」(${imported.algorithm})"
      resultIsError = false
    }
  )

  certificateTarget?.let { target ->
    SshKeyCertificateDialog(
      profile = target,
      controller = controller,
      onDismiss = { certificateTarget = null },
      onSaved = { updated ->
        certificateTarget = null
        result = if (updated.hasCertificate) "已附加证书到「${updated.name}」" else "已清除「${updated.name}」的证书"
        resultIsError = false
      }
    )
  }

  unlockTarget?.let { target ->
    SshKeyUnlockDialog(
      profile = target,
      controller = controller,
      onDismiss = { unlockTarget = null; retryTestAfterUnlock = false },
      onUnlocked = {
        val shouldRetry = retryTestAfterUnlock
        unlockTarget = null
        retryTestAfterUnlock = false
        result = "已解锁密钥「${target.name}」"
        resultIsError = false
        if (shouldRetry) testConnection()
      }
    )
  }

  deployPublicKey?.let { pubKey ->
    val endpoint = hostEndpointOrNull()
    if (endpoint != null) {
      DeploySshPublicKeyDialog(
        show = true,
        config = endpoint,
        initialPassword = password.ifEmpty { initialSecrets.password },
        publicKeyOpenSsh = pubKey,
        controller = controller,
        onPinnedHostKey = { key ->
          hostKeyType = key.type
          hostKeyFingerprint = key.fingerprint
        },
        onDismiss = { deployPublicKey = null },
        onSuccess = {
          deployPublicKey = null
          result = "公钥已安装到 ${endpoint.user}@${endpoint.host} 的 ~/.ssh/authorized_keys"
          resultIsError = false
        }
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
  port: String,
  command: String,
  workingDirectory: String,
  name: String,
  host: String,
  fingerprint: String
): String? {
  val portValue = port.trim().toIntOrNull() ?: SSH_DEFAULT_PORT
  val parts = buildList {
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
