package com.igng.opencode.lagoon.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshKeyAlgorithm
import com.igng.opencode.lagoon.core.SshKeyProfile
import com.igng.opencode.lagoon.core.SshKeySource
import com.igng.opencode.lagoon.core.SshPassphrasePolicy
import com.igng.opencode.lagoon.core.SshSecrets
import com.igng.opencode.lagoon.core.ssh.ParsedSshCertificate
import com.igng.opencode.lagoon.core.ssh.ParsedSshPrivateKey
import com.igng.opencode.lagoon.core.ssh.SshKeyCodec
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.ByteArrayOutputStream

private const val MAX_SSH_KEY_FILE_BYTES = 64 * 1024

internal fun copyTextToClipboard(context: Context, label: String, text: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal suspend fun readTextDocument(context: Context, uri: android.net.Uri): String = withContext(Dispatchers.IO) {
  context.contentResolver.openInputStream(uri)?.use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      out.write(buffer, 0, read)
      if (out.size() > MAX_SSH_KEY_FILE_BYTES) throw IllegalArgumentException("文件过大（超过 64 KB）")
    }
    out.toString(Charsets.UTF_8.name())
  } ?: throw IllegalArgumentException("无法读取所选文件")
}

@Composable
internal fun SshKeychainManagerSheet(
  show: Boolean,
  state: LagoonState,
  controller: LagoonController,
  onDismiss: () -> Unit,
  selectedKeyId: String? = null,
  onSelectKey: ((SshKeyProfile) -> Unit)? = null
) {
  if (!show) return
  val context = LocalContext.current
  var showGenerate by remember { mutableStateOf(false) }
  var showImport by remember { mutableStateOf(false) }
  var certificateTarget by remember { mutableStateOf<SshKeyProfile?>(null) }
  var unlockTarget by remember { mutableStateOf<SshKeyProfile?>(null) }
  var deleteTarget by remember { mutableStateOf<SshKeyProfile?>(null) }
  var notice by remember { mutableStateOf<String?>(null) }

  HyperBottomSheet(
    title = if (onSelectKey != null) "选择或管理 SSH 密钥" else "SSH 密钥库",
    show = show,
    onDismissRequest = onDismiss
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton(
          text = "生成新密钥",
          onClick = { showGenerate = true },
          modifier = Modifier.weight(1f)
        )
        HyperButton(
          text = "导入私钥",
          variant = HyperButtonVariant.SECONDARY,
          onClick = { showImport = true },
          modifier = Modifier.weight(1f)
        )
      }

      notice?.let { message ->
        Text(
          message,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Success)
        )
      }

      if (state.sshKeys.isEmpty()) {
        HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(16.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("还没有保存的 SSH 密钥", style = MiuixTheme.textStyles.body1)
            Text(
              "可以本机生成 Ed25519 / 硬件密钥，或导入已有私钥；同一把密钥可被多台 SSH 主机复用。",
              style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(state.sshKeys, key = { "${it.id}:${state.sshKeysRevision}" }) { profile ->
            val unlocked = controller.isSshKeyUnlocked(profile)
            val needsUnlockGate = profile.biometricProtected ||
              profile.passphrasePolicy == SshPassphrasePolicy.BIOMETRIC ||
              (profile.passphrasePolicy == SshPassphrasePolicy.PROMPT && profile.encrypted)
            val isSelected = profile.id == selectedKeyId
            val boundHosts = remember(profile.id, state.sshHosts) {
              controller.hostsUsingSshKey(profile.id).size
            }
            HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(14.dp)) {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                  Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                      profile.name + if (isSelected) " · 当前使用" else "",
                      style = MiuixTheme.textStyles.body1
                    )
                    Text(
                      "${profile.source.label} · ${profile.summary}",
                      style = MiuixTheme.textStyles.footnote2.copy(
                        color = HyperTheme.colors.textSecondary,
                        fontFamily = FontFamily.Monospace
                      )
                    )
                    if (boundHosts > 0) {
                      Text(
                        "已绑定 $boundHosts 台 SSH 设备",
                        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
                      )
                    }
                  }
                }
                Row(
                  Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  if (onSelectKey != null) {
                    HyperTextAction(
                      text = if (isSelected) "已选用" else "选用此密钥",
                      enabled = !isSelected,
                      onClick = {
                        onSelectKey(profile)
                        onDismiss()
                      }
                    )
                  }
                  HyperTextAction(
                    text = "复制公钥",
                    onClick = {
                      copyTextToClipboard(context, profile.name, profile.publicKeyOpenSsh)
                      notice = "已复制「${profile.name}」的 OpenSSH 公钥"
                    }
                  )
                  HyperTextAction(
                    text = if (profile.hasCertificate) "证书" else "添加证书",
                    onClick = { certificateTarget = profile }
                  )
                  if (needsUnlockGate) {
                    if (unlocked) {
                      HyperTextAction(
                        text = "锁定",
                        onClick = {
                          controller.lockSshKey(profile.id)
                          notice = "已锁定「${profile.name}」"
                        }
                      )
                    } else {
                      HyperTextAction(
                        text = "解锁",
                        onClick = { unlockTarget = profile }
                      )
                    }
                  }
                  HyperTextAction(
                    text = "删除",
                    danger = true,
                    onClick = { deleteTarget = profile }
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  SshKeyGenerateDialog(
    show = showGenerate,
    controller = controller,
    onDismiss = { showGenerate = false },
    onCreated = { created ->
      showGenerate = false
      notice = "已创建密钥「${created.name}」(${created.algorithm})"
      if (onSelectKey != null) {
        onSelectKey(created)
        onDismiss()
      }
    }
  )

  SshKeyImportDialog(
    show = showImport,
    controller = controller,
    onDismiss = { showImport = false },
    onImported = { imported ->
      showImport = false
      notice = "已导入密钥「${imported.name}」(${imported.algorithm})"
      if (onSelectKey != null) {
        onSelectKey(imported)
        onDismiss()
      }
    }
  )

  certificateTarget?.let { target ->
    SshKeyCertificateDialog(
      profile = target,
      controller = controller,
      onDismiss = { certificateTarget = null },
      onSaved = { updated ->
        certificateTarget = null
        notice = if (updated.hasCertificate) "已更新「${updated.name}」的 OpenSSH 证书" else "已清除「${updated.name}」的证书"
      }
    )
  }

  unlockTarget?.let { target ->
    SshKeyUnlockDialog(
      profile = target,
      controller = controller,
      onDismiss = { unlockTarget = null },
      onUnlocked = {
        unlockTarget = null
        notice = "已解锁「${target.name}」"
      }
    )
  }

  deleteTarget?.let { target ->
    val affected = controller.hostsUsingSshKey(target.id)
    SuperDialog(
      title = "删除密钥「${target.name}」？",
      show = true,
      onDismissRequest = { deleteTarget = null }
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          buildString {
            append("将从本机密钥库删除此密钥（含私密凭据")
            if (target.source == SshKeySource.HARDWARE_KEYSTORE) append("及 AndroidKeyStore 硬件项")
            append("）。")
            if (affected.isNotEmpty()) {
              append("\n注意：有 ${affected.size} 台 SSH 设备正在引用此密钥（${affected.joinToString("、") { it.name }}），删除后这些设备需要重新选择密钥。")
            }
          },
          style = MiuixTheme.textStyles.body2
        )
        DialogActions("取消", { deleteTarget = null }, "确认删除", danger = true) {
          controller.deleteSshKey(target.id)
          notice = "已删除密钥「${target.name}」"
          deleteTarget = null
        }
      }
    }
  }
}

@Composable
internal fun SshKeyGenerateDialog(
  show: Boolean,
  controller: LagoonController,
  onDismiss: () -> Unit,
  onCreated: (SshKeyProfile) -> Unit
) {
  if (!show) return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val biometricSupported = remember { controller.isBiometricSupported() }
  var hardwareMode by remember { mutableStateOf(false) }
  var name by remember { mutableStateOf("") }
  var algorithm by remember { mutableStateOf(SshKeyAlgorithm.ED25519) }
  var policy by remember { mutableStateOf(SshPassphrasePolicy.NONE) }
  var passphrase by remember { mutableStateOf("") }
  var hardwareBiometric by remember { mutableStateOf(false) }
  var working by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }

  val policies = remember(biometricSupported) {
    if (biometricSupported) SshPassphrasePolicy.entries else SshPassphrasePolicy.entries.filter { it != SshPassphrasePolicy.BIOMETRIC }
  }

  SuperDialog(title = "生成 SSH 密钥", show = true, onDismissRequest = { if (!working) onDismiss() }) {
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      HyperSegmentedTabs(
        tabs = listOf("软件密钥", "硬件密钥库"),
        selectedIndex = if (hardwareMode) 1 else 0,
        onTabSelected = { hardwareMode = (it == 1); errorText = null }
      )
      TextField(
        value = name,
        onValueChange = { name = it },
        label = "密钥名称（可选，如「公司跳板机」）",
        modifier = Modifier.fillMaxWidth()
      )
      if (hardwareMode) {
        Text(
          "在设备安全芯片 (AndroidKeyStore) 中生成 ECDSA P-256 密钥。私钥永不出芯片，无法导出或备份，仅限本台手机使用。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
        HyperSwitchRow(
          title = "使用前需生物识别 / 设备锁屏",
          summary = if (biometricSupported) "每次超时后需验证身份才允许签名" else "当前设备未启用生物识别或锁屏保护",
          checked = hardwareBiometric && biometricSupported,
          enabled = biometricSupported,
          onCheckedChange = { hardwareBiometric = it }
        )
      } else {
        HyperDropdownRow(
          title = "密钥算法",
          items = SshKeyAlgorithm.entries.map { it.label },
          selectedIndex = SshKeyAlgorithm.entries.indexOf(algorithm),
          onSelectedIndexChange = { algorithm = SshKeyAlgorithm.entries[it] }
        )
        HyperDropdownRow(
          title = "保护策略",
          summary = when (policy) {
            SshPassphrasePolicy.NONE -> "无额外口令，由 AndroidKeyStore 加密存储，支持后台自动重连"
            SshPassphrasePolicy.SAVED -> "私钥带口令且保存于本机加密库，可自动重连"
            SshPassphrasePolicy.PROMPT -> "私钥口令不落盘，每次应用重启连接前需手动输入"
            SshPassphrasePolicy.BIOMETRIC -> "需指纹/面容解锁后方可使用（重启或锁定后需重新验证）"
          },
          items = policies.map { it.label },
          selectedIndex = policies.indexOf(policy).coerceAtLeast(0),
          onSelectedIndexChange = { policy = policies[it] }
        )
        if (policy != SshPassphrasePolicy.NONE) {
          TextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = if (policy == SshPassphrasePolicy.BIOMETRIC) "私钥口令（可选，留空则直接用生物识别封印私钥）" else "设置私钥口令（必填）",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
      errorText?.let {
        Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error))
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton(
          text = "取消",
          variant = HyperButtonVariant.SECONDARY,
          enabled = !working,
          onClick = onDismiss,
          modifier = Modifier.weight(1f)
        )
        HyperButton(
          text = "生成",
          enabled = !working,
          loading = working,
          onClick = {
            if (working) return@HyperButton
            working = true
            errorText = null
            scope.launch {
              try {
                val created = if (hardwareMode) {
                  controller.generateHardwareSshKey(name, hardwareBiometric && biometricSupported)
                } else {
                  controller.generateSoftwareSshKey(
                    name = name,
                    algorithm = algorithm,
                    passphrase = passphrase.takeIf { it.isNotEmpty() },
                    policy = policy,
                    biometricActivityContext = context
                  )
                }
                onCreated(created)
              } catch (cancel: CancellationException) {
                throw cancel
              } catch (error: Exception) {
                errorText = userFacingError(error, "生成 SSH 密钥失败")
              } finally {
                working = false
              }
            }
          },
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
internal fun SshKeyImportDialog(
  show: Boolean,
  controller: LagoonController,
  onDismiss: () -> Unit,
  onImported: (SshKeyProfile) -> Unit
) {
  if (!show) return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val biometricSupported = remember { controller.isBiometricSupported() }
  var name by remember { mutableStateOf("") }
  var privateKeyText by remember { mutableStateOf("") }
  var keyVisible by remember { mutableStateOf(false) }
  var passphrase by remember { mutableStateOf("") }
  var certText by remember { mutableStateOf("") }
  var policy by remember { mutableStateOf(SshPassphrasePolicy.SAVED) }
  var parsedKey by remember { mutableStateOf<ParsedSshPrivateKey?>(null) }
  var inspectError by remember { mutableStateOf<String?>(null) }
  var submitError by remember { mutableStateOf<String?>(null) }
  var working by remember { mutableStateOf(false) }

  val keyFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      runCatching { readTextDocument(context, uri) }
        .onSuccess { privateKeyText = it.trim(); submitError = null }
        .onFailure { submitError = it.message ?: "读取私钥文件失败" }
    }
  }
  val certFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      runCatching { readTextDocument(context, uri) }
        .onSuccess { certText = it.trim(); submitError = null }
        .onFailure { submitError = it.message ?: "读取证书文件失败" }
    }
  }

  LaunchedEffect(privateKeyText, passphrase) {
    val trimmed = privateKeyText.trim()
    if (trimmed.isEmpty()) {
      parsedKey = null
      inspectError = null
      return@LaunchedEffect
    }
    try {
      val parsed = withContext(Dispatchers.Default) {
        SshKeyCodec.inspectPrivateKey(trimmed, passphrase.takeIf { it.isNotEmpty() })
      }
      parsedKey = parsed
      inspectError = null
      if (!parsed.encrypted && policy != SshPassphrasePolicy.BIOMETRIC) {
        policy = SshPassphrasePolicy.NONE
      } else if (parsed.encrypted && policy == SshPassphrasePolicy.NONE) {
        policy = SshPassphrasePolicy.SAVED
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (failure: SshFailure) {
      parsedKey = null
      inspectError = failure.display()
    } catch (error: Exception) {
      parsedKey = null
      inspectError = error.message ?: "私钥无法解析"
    }
  }

  val encrypted = parsedKey?.encrypted == true
  val availablePolicies = remember(encrypted, biometricSupported) {
    buildList {
      if (encrypted) {
        add(SshPassphrasePolicy.SAVED)
        add(SshPassphrasePolicy.PROMPT)
      } else {
        add(SshPassphrasePolicy.NONE)
      }
      if (biometricSupported) add(SshPassphrasePolicy.BIOMETRIC)
    }
  }
  val effectivePolicy = if (policy in availablePolicies) policy else availablePolicies.first()

  SuperDialog(title = "导入 SSH 私钥", show = true, onDismissRequest = { if (!working) onDismiss() }) {
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      TextField(
        value = name,
        onValueChange = { name = it },
        label = "密钥名称（可选）",
        modifier = Modifier.fillMaxWidth()
      )
      TextField(
        value = privateKeyText,
        onValueChange = { privateKeyText = it; submitError = null },
        label = "私钥全文（支持 OpenSSH / PEM / PKCS#8 / PuTTY .ppk）",
        singleLine = false,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HyperTextAction(
          text = "从文件选择私钥",
          onClick = { keyFilePicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
        )
        HyperTextAction(
          text = if (keyVisible) "隐藏私钥" else "显示私钥",
          enabled = privateKeyText.isNotEmpty(),
          onClick = { keyVisible = !keyVisible }
        )
      }

      parsedKey?.let { info ->
        val summaryLine = buildString {
          append("已识别：${info.algorithm}")
          if (info.fingerprint.isNotBlank()) append(" · ${info.fingerprint}")
          if (info.encrypted && !info.decrypted) append("\n此私钥已加密，请输入下方私钥口令完成校验。")
        }
        Text(
          summaryLine,
          style = MiuixTheme.textStyles.footnote2.copy(
            color = if (info.encrypted && !info.decrypted) MiuixColorTokens.Warning else MiuixColorTokens.Success,
            fontFamily = FontFamily.Monospace
          )
        )
      }
      inspectError?.let {
        Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error))
      }

      if (encrypted || passphrase.isNotEmpty()) {
        TextField(
          value = passphrase,
          onValueChange = { passphrase = it; submitError = null },
          label = "私钥口令",
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }

      if (parsedKey != null) {
        HyperDropdownRow(
          title = "口令 / 解锁策略",
          items = availablePolicies.map { it.label },
          selectedIndex = availablePolicies.indexOf(effectivePolicy).coerceAtLeast(0),
          onSelectedIndexChange = { policy = availablePolicies[it] }
        )
      }

      TextField(
        value = certText,
        onValueChange = { certText = it; submitError = null },
        label = "OpenSSH 证书（-cert.pub，可选）",
        singleLine = false,
        modifier = Modifier.fillMaxWidth()
      )
      HyperTextAction(
        text = "从文件导入 -cert.pub 证书",
        onClick = { certFilePicker.launch(arrayOf("text/plain", "*/*")) }
      )

      submitError?.let {
        Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error))
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton(
          text = "取消",
          variant = HyperButtonVariant.SECONDARY,
          enabled = !working,
          onClick = onDismiss,
          modifier = Modifier.weight(1f)
        )
        HyperButton(
          text = "导入",
          enabled = !working && privateKeyText.isNotBlank(),
          loading = working,
          onClick = {
            if (working) return@HyperButton
            working = true
            submitError = null
            scope.launch {
              try {
                val imported = controller.importSshKey(
                  name = name,
                  privateKeyText = privateKeyText,
                  passphrase = passphrase.takeIf { it.isNotEmpty() },
                  policy = effectivePolicy,
                  certificateText = certText.takeIf { it.isNotBlank() },
                  biometricActivityContext = context
                )
                onImported(imported)
              } catch (cancel: CancellationException) {
                throw cancel
              } catch (error: SshFailure) {
                submitError = error.display()
              } catch (error: Exception) {
                submitError = userFacingError(error, "导入 SSH 私钥失败")
              } finally {
                working = false
              }
            }
          },
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
internal fun SshKeyCertificateDialog(
  profile: SshKeyProfile,
  controller: LagoonController,
  onDismiss: () -> Unit,
  onSaved: (SshKeyProfile) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var certText by remember(profile.id) { mutableStateOf(profile.certificateOpenSsh.orEmpty()) }
  var parsedCert by remember { mutableStateOf<ParsedSshCertificate?>(null) }
  var errorText by remember { mutableStateOf<String?>(null) }

  val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      runCatching { readTextDocument(context, uri) }
        .onSuccess { certText = it.trim(); errorText = null }
        .onFailure { errorText = it.message ?: "读取证书文件失败" }
    }
  }

  LaunchedEffect(certText) {
    val trimmed = certText.trim()
    if (trimmed.isEmpty()) {
      parsedCert = null
      errorText = null
      return@LaunchedEffect
    }
    try {
      parsedCert = SshKeyCodec.parseCertificate(trimmed)
      errorText = null
    } catch (error: SshFailure) {
      parsedCert = null
      errorText = error.display()
    }
  }

  SuperDialog(title = "OpenSSH 证书 (${profile.name})", show = true, onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        "粘贴由 ssh-keygen -s CA 签发的 -cert.pub 内容。配置后连接时将自动携带证书身份，并在兼容列表启用 *-cert-v01@openssh.com 算法。",
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
      )
      TextField(
        value = certText,
        onValueChange = { certText = it },
        label = "-cert.pub 内容（留空并保存即清除证书）",
        singleLine = false,
        modifier = Modifier.fillMaxWidth()
      )
      HyperTextAction(
        text = "选择 -cert.pub 文件",
        onClick = { certPicker.launch(arrayOf("text/plain", "*/*")) }
      )
      parsedCert?.let { info ->
        Text(
          "已解析证书：${info.algorithm} · ${info.fingerprint}",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Success, fontFamily = FontFamily.Monospace)
        )
      }
      errorText?.let {
        Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error))
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton(
          text = "取消",
          variant = HyperButtonVariant.SECONDARY,
          onClick = onDismiss,
          modifier = Modifier.weight(1f)
        )
        HyperButton(
          text = if (certText.isBlank()) "清除证书" else "保存证书",
          enabled = errorText == null,
          onClick = {
            try {
              onSaved(controller.updateSshKeyCertificate(profile.id, certText.takeIf { it.isNotBlank() }))
            } catch (error: Exception) {
              errorText = userFacingError(error, "保存证书失败")
            }
          },
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
internal fun SshKeyUnlockDialog(
  profile: SshKeyProfile,
  controller: LagoonController,
  onDismiss: () -> Unit,
  onUnlocked: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val isBiometric = profile.biometricProtected || profile.passphrasePolicy == SshPassphrasePolicy.BIOMETRIC
  var passphrase by remember { mutableStateOf("") }
  var working by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }

  SuperDialog(title = "解锁 SSH 密钥", show = true, onDismissRequest = { if (!working) onDismiss() }) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        if (isBiometric) "「${profile.name}」受生物识别保护，验证身份后即可用于 SSH 连接。"
        else "「${profile.name}」采用每次连接询问口令策略，口令仅缓存在内存中，不写入磁盘。",
        style = MiuixTheme.textStyles.body2
      )
      if (!isBiometric) {
        TextField(
          value = passphrase,
          onValueChange = { passphrase = it; errorText = null },
          label = "私钥口令",
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
      errorText?.let {
        Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error))
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton(
          text = "取消",
          variant = HyperButtonVariant.SECONDARY,
          enabled = !working,
          onClick = onDismiss,
          modifier = Modifier.weight(1f)
        )
        HyperButton(
          text = if (isBiometric) "验证并解锁" else "解锁",
          enabled = !working && (isBiometric || passphrase.isNotEmpty()),
          loading = working,
          onClick = {
            if (working) return@HyperButton
            working = true
            errorText = null
            scope.launch {
              try {
                if (isBiometric) {
                  controller.unlockSshKeyWithBiometric(profile.id, context)
                } else {
                  controller.unlockSshKeyWithPassphrase(profile.id, passphrase)
                }
                onUnlocked()
              } catch (cancel: CancellationException) {
                throw cancel
              } catch (error: Exception) {
                errorText = userFacingError(error, "解锁失败")
              } finally {
                working = false
              }
            }
          },
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
internal fun DeploySshPublicKeyDialog(
  show: Boolean,
  config: SshHostConfig,
  initialPassword: String?,
  publicKeyOpenSsh: String,
  controller: LagoonController,
  onPinnedHostKey: (PresentedSshHostKey) -> Unit,
  onDismiss: () -> Unit,
  onSuccess: () -> Unit
) {
  if (!show) return
  val scope = rememberCoroutineScope()
  var loginPassword by remember(show) { mutableStateOf(initialPassword.orEmpty()) }
  var pendingHostKey by remember { mutableStateOf<PresentedSshHostKey?>(null) }
  var working by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }

  suspend fun runInstall(pinnedConfig: SshHostConfig) {
    working = true
    errorText = null
    try {
      val installConfig = if (loginPassword.isNotEmpty()) {
        pinnedConfig.copyWith(auth = SshAuthMethod.PASSWORD, clearKeyId = true)
      } else {
        pinnedConfig
      }
      val installSecrets = if (loginPassword.isNotEmpty()) {
        SshSecrets(password = loginPassword)
      } else {
        controller.sshHostSecrets(pinnedConfig.id)
      }
      controller.deploySshPublicKey(installConfig, installSecrets, publicKeyOpenSsh)
      onSuccess()
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: SshFailure) {
      if (error.presentedKey != null) {
        pendingHostKey = error.presentedKey
      } else {
        errorText = error.display()
      }
    } catch (error: Exception) {
      errorText = userFacingError(error, "安装公钥失败")
    } finally {
      working = false
    }
  }

  SuperDialog(title = "安装公钥到服务器", show = true, onDismissRequest = { if (!working) onDismiss() }) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        "将把选定密钥的公钥幂等追加到 ${config.user}@${config.host} 的 ~/.ssh/authorized_keys，并自动修正 .ssh 目录权限。",
        style = MiuixTheme.textStyles.body2
      )
      TextField(
        value = loginPassword,
        onValueChange = { loginPassword = it; errorText = null },
        label = "服务器登录密码（用于本次写入，不会额外保存）",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
      )
      errorText?.let {
        Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Error))
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton(
          text = "取消",
          variant = HyperButtonVariant.SECONDARY,
          enabled = !working,
          onClick = onDismiss,
          modifier = Modifier.weight(1f)
        )
        HyperButton(
          text = "开始安装",
          enabled = !working && loginPassword.isNotEmpty(),
          loading = working,
          onClick = {
            if (working) return@HyperButton
            scope.launch {
              if (!config.hostKeyFingerprint.isNullOrBlank()) {
                runInstall(config)
              } else {
                working = true
                errorText = null
                try {
                  pendingHostKey = controller.probeSshHostKey(config)
                } catch (cancel: CancellationException) {
                  throw cancel
                } catch (error: Exception) {
                  errorText = userFacingError(error, "读取主机指纹失败")
                } finally {
                  working = false
                }
              }
            }
          },
          modifier = Modifier.weight(1f)
        )
      }
    }
  }

  pendingHostKey?.let { key ->
    SuperDialog(title = "信任主机指纹并安装公钥？", show = true, onDismissRequest = { pendingHostKey = null }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${key.shortType}\n${key.fingerprint}", style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace))
        DialogActions("取消", { pendingHostKey = null }, "信任并继续") {
          pendingHostKey = null
          onPinnedHostKey(key)
          scope.launch {
            runInstall(config.copyWith(hostKeyType = key.type, hostKeyFingerprint = key.fingerprint))
          }
        }
      }
    }
  }
}
