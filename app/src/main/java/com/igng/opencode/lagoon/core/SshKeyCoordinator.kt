package com.igng.opencode.lagoon.core

import android.content.Context
import androidx.compose.runtime.Immutable
import com.igng.opencode.lagoon.core.ssh.HardwareSshSigner
import com.igng.opencode.lagoon.core.ssh.SshKeyCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class SshKeychainState(
  val keys: List<SshKeyProfile> = emptyList(),
  val revision: Long = 0L,
  val loadError: String? = null
)

/**
 * 独立拥有 SSH 密钥库领域状态的协调器。
 *
 * - 监听 [SshKeyStore.revisionFlow]，当后台 SSH 握手因 30 秒生物识别窗口到期自动锁定时，
 *   界面立即感知锁定态变化；
 * - 独立于会话连接状态（[LagoonController.connect] 切换会话永远不会清空密钥列表）。
 */
internal class SshKeyCoordinator(
  private val keyStore: SshKeyStore,
  private val hardwareSigner: HardwareSshSigner,
  scope: CoroutineScope,
  private val onKeyInvalidated: (String) -> Unit = {}
) {
  private val mutableState = MutableStateFlow(snapshot())
  val state: StateFlow<SshKeychainState> = mutableState.asStateFlow()

  init {
    scope.launch {
      keyStore.revisionFlow.collect {
        publish()
      }
    }
  }

  private fun snapshot(): SshKeychainState {
    val keys = keyStore.load()
    return SshKeychainState(keys = keys, revision = keyStore.revision, loadError = keyStore.loadError)
  }

  private fun publish() {
    mutableState.value = snapshot()
  }

  fun keys(): List<SshKeyProfile> = mutableState.value.keys
  fun find(id: String?): SshKeyProfile? = id?.let { keyId -> mutableState.value.keys.firstOrNull { it.id == keyId } }
  fun secrets(id: String): SshKeySecrets = keyStore.secrets(id)
  fun loadError(): String? = keyStore.loadError
  fun isUnlocked(profile: SshKeyProfile): Boolean = keyStore.isUnlocked(profile)
  fun isBiometricSupported(context: Context): Boolean = BiometricKeyVault.isSupported(context)

  suspend fun generateSoftwareKey(
    name: String,
    algorithm: SshKeyAlgorithm,
    passphrase: String?,
    policy: SshPassphrasePolicy,
    biometricActivityContext: Context? = null,
    comment: String = "lagoon"
  ): SshKeyProfile {
    val keyId = SshKeyProfile.newId()
    val cleanName = name.trim().ifBlank { "${algorithm.label} 密钥" }.take(128)
    val cleanPassphrase = passphrase?.takeIf { it.isNotEmpty() }
    if (policy == SshPassphrasePolicy.SAVED || policy == SshPassphrasePolicy.PROMPT) {
      require(!cleanPassphrase.isNullOrEmpty()) { "请输入私钥口令，或切换为「无口令」" }
    }
    val effectivePolicy = if (cleanPassphrase == null && policy != SshPassphrasePolicy.BIOMETRIC) {
      SshPassphrasePolicy.NONE
    } else {
      policy
    }
    val generated = withContext(Dispatchers.Default) {
      SshKeyCodec.generateKeyPair(
        algorithm = algorithm,
        comment = comment,
        passphrase = if (effectivePolicy == SshPassphrasePolicy.NONE || (effectivePolicy == SshPassphrasePolicy.BIOMETRIC && cleanPassphrase == null)) {
          null
        } else {
          cleanPassphrase
        }
      )
    }
    val encrypted = !cleanPassphrase.isNullOrEmpty()
    val secrets = when (effectivePolicy) {
      SshPassphrasePolicy.BIOMETRIC -> {
        val uiContext = biometricActivityContext ?: error("需要界面上下文以触发系统生物识别")
        val payloadToSeal = cleanPassphrase ?: generated.privateKeyPem
        val sealed = BiometricKeyVault.seal(
          context = uiContext,
          keyId = keyId,
          plaintext = payloadToSeal,
          title = "启用生物识别保护",
          subtitle = "验证身份以保护 SSH 密钥「$cleanName」"
        )
        keyStore.unlockRuntimeSecret(keyId, payloadToSeal)
        SshKeySecrets(
          privateKey = if (encrypted) generated.privateKeyPem else null,
          passphrase = null,
          biometricCiphertext = sealed.ciphertextBase64,
          biometricIv = sealed.ivBase64
        )
      }
      SshPassphrasePolicy.PROMPT -> {
        if (cleanPassphrase != null) keyStore.unlockRuntimeSecret(keyId, cleanPassphrase)
        SshKeySecrets(privateKey = generated.privateKeyPem, passphrase = null)
      }
      SshPassphrasePolicy.SAVED, SshPassphrasePolicy.NONE -> {
        SshKeySecrets(privateKey = generated.privateKeyPem, passphrase = cleanPassphrase)
      }
    }
    val profile = SshKeyProfile(
      id = keyId,
      name = cleanName,
      algorithm = generated.algorithm,
      fingerprint = generated.fingerprint,
      publicKeyOpenSsh = generated.publicKeyOpenSsh,
      encrypted = encrypted,
      passphrasePolicy = effectivePolicy,
      source = SshKeySource.GENERATED,
      biometricProtected = effectivePolicy == SshPassphrasePolicy.BIOMETRIC,
      createdAt = System.currentTimeMillis()
    )
    keyStore.save(profile, secrets)
    publish()
    onKeyInvalidated(keyId)
    return profile
  }

  fun generateHardwareKey(context: Context, name: String, requireBiometric: Boolean): SshKeyProfile {
    val keyId = SshKeyProfile.newId()
    val cleanName = name.trim().ifBlank { "本机硬件密钥" }.take(128)
    if (requireBiometric) require(isBiometricSupported(context)) { "当前设备未启用生物识别或系统锁屏" }
    val profile = hardwareSigner.generateKey(keyId, cleanName, requireBiometric)
    keyStore.save(profile, SshKeySecrets())
    publish()
    onKeyInvalidated(keyId)
    return profile
  }

  suspend fun importKey(
    name: String,
    privateKeyText: String,
    passphrase: String?,
    policy: SshPassphrasePolicy,
    certificateText: String? = null,
    biometricActivityContext: Context? = null
  ): SshKeyProfile {
    val cleanKey = privateKeyText.trim()
    val cleanPass = passphrase?.takeIf { it.isNotEmpty() }
    val parsed = withContext(Dispatchers.Default) {
      SshKeyCodec.inspectPrivateKey(cleanKey, cleanPass)
    }
    if (parsed.encrypted && !parsed.decrypted) {
      throw SshFailure(SshFailureKind.PASSPHRASE_REQUIRED, "此私钥已加密，请先填写私钥口令校验后再导入", retryable = false)
    }
    val cert = certificateText?.trim()?.takeIf { it.isNotEmpty() }?.let {
      SshKeyCodec.parseCertificate(it).normalizedLine
    }
    val keyId = SshKeyProfile.newId()
    val cleanName = name.trim().ifBlank { "${parsed.algorithm} 导入密钥" }.take(128)
    val effectivePolicy = when {
      policy == SshPassphrasePolicy.BIOMETRIC -> SshPassphrasePolicy.BIOMETRIC
      !parsed.encrypted -> SshPassphrasePolicy.NONE
      policy == SshPassphrasePolicy.NONE -> SshPassphrasePolicy.SAVED
      else -> policy
    }
    val secrets = when (effectivePolicy) {
      SshPassphrasePolicy.BIOMETRIC -> {
        val uiContext = biometricActivityContext ?: error("需要界面上下文以触发系统生物识别")
        val payloadToSeal = if (parsed.encrypted) cleanPass!! else cleanKey
        val sealed = BiometricKeyVault.seal(
          context = uiContext,
          keyId = keyId,
          plaintext = payloadToSeal,
          title = "启用生物识别保护",
          subtitle = "验证身份以保护 SSH 密钥「$cleanName」"
        )
        keyStore.unlockRuntimeSecret(keyId, payloadToSeal)
        SshKeySecrets(
          privateKey = if (parsed.encrypted) cleanKey else null,
          passphrase = null,
          biometricCiphertext = sealed.ciphertextBase64,
          biometricIv = sealed.ivBase64
        )
      }
      SshPassphrasePolicy.PROMPT -> {
        if (cleanPass != null) keyStore.unlockRuntimeSecret(keyId, cleanPass)
        SshKeySecrets(privateKey = cleanKey, passphrase = null)
      }
      SshPassphrasePolicy.SAVED, SshPassphrasePolicy.NONE -> {
        SshKeySecrets(privateKey = cleanKey, passphrase = cleanPass)
      }
    }
    val profile = SshKeyProfile(
      id = keyId,
      name = cleanName,
      algorithm = parsed.algorithm,
      fingerprint = parsed.fingerprint,
      publicKeyOpenSsh = parsed.publicKeyOpenSsh,
      certificateOpenSsh = cert,
      encrypted = parsed.encrypted,
      passphrasePolicy = effectivePolicy,
      source = SshKeySource.IMPORTED,
      biometricProtected = effectivePolicy == SshPassphrasePolicy.BIOMETRIC,
      createdAt = System.currentTimeMillis()
    )
    keyStore.save(profile, secrets)
    publish()
    onKeyInvalidated(keyId)
    return profile
  }

  fun updateCertificate(keyId: String, certificateText: String?): SshKeyProfile {
    val existing = keyStore.find(keyId) ?: error("SSH 密钥不存在")
    val normalized = certificateText?.trim()?.takeIf { it.isNotEmpty() }?.let {
      SshKeyCodec.parseCertificate(it).normalizedLine
    }
    val updated = existing.copy(certificateOpenSsh = normalized)
    keyStore.save(updated)
    publish()
    onKeyInvalidated(keyId)
    return updated
  }

  fun renameKey(keyId: String, newName: String) {
    val existing = keyStore.find(keyId) ?: return
    val clean = newName.trim().takeIf { it.isNotEmpty() }?.take(128) ?: return
    keyStore.save(existing.copy(name = clean))
    publish()
  }

  suspend fun unlockWithPassphrase(keyId: String, passphrase: String) {
    keyStore.find(keyId) ?: error("SSH 密钥不存在")
    val stored = keyStore.secrets(keyId)
    val privateKey = stored.privateKey ?: error("私钥内容缺失")
    require(passphrase.isNotEmpty()) { "请输入私钥口令" }
    val parsed = withContext(Dispatchers.Default) {
      SshKeyCodec.inspectPrivateKey(privateKey, passphrase)
    }
    require(!parsed.encrypted || parsed.decrypted) { "私钥口令不正确" }
    keyStore.unlockRuntimeSecret(keyId, passphrase)
    publish()
    onKeyInvalidated(keyId)
  }

  suspend fun unlockWithBiometric(keyId: String, activityContext: Context) {
    val profile = keyStore.find(keyId) ?: error("SSH 密钥不存在")
    when (profile.source) {
      SshKeySource.HARDWARE_KEYSTORE -> {
        BiometricKeyVault.authenticateHardwareKey(
          context = activityContext,
          title = "解锁硬件 SSH 密钥",
          subtitle = "验证身份以使用「${profile.name}」"
        )
        keyStore.unlockRuntimeSecret(keyId, "hardware-unlocked")
      }
      SshKeySource.GENERATED, SshKeySource.IMPORTED -> {
        val stored = keyStore.secrets(keyId)
        val cipher = stored.biometricCiphertext ?: error("生物识别密文缺失，请重新导入密钥")
        val iv = stored.biometricIv ?: error("生物识别 IV 缺失，请重新导入密钥")
        val plain = BiometricKeyVault.unseal(
          context = activityContext,
          keyId = keyId,
          ciphertextBase64 = cipher,
          ivBase64 = iv,
          title = "解锁 SSH 密钥",
          subtitle = "验证身份以使用「${profile.name}」"
        )
        keyStore.unlockRuntimeSecret(keyId, plain)
      }
    }
    publish()
    onKeyInvalidated(keyId)
  }

  fun lockKey(keyId: String) {
    keyStore.lockRuntimeSecret(keyId)
    publish()
    onKeyInvalidated(keyId)
  }

  fun deleteKey(keyId: String) {
    val existing = keyStore.find(keyId)
    if (existing?.source == SshKeySource.HARDWARE_KEYSTORE) {
      hardwareSigner.deleteKey(keyId)
    }
    BiometricKeyVault.deleteKey(keyId)
    keyStore.delete(keyId)
    publish()
    onKeyInvalidated(keyId)
  }
}
