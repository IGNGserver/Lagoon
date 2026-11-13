package com.igng.opencode.lagoon.core

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Encrypted secret sealed by a biometric-authenticated AndroidKeyStore AES-GCM key. */
internal data class BiometricSealedSecret(val ciphertextBase64: String, val ivBase64: String)

/**
 * Biometric gate backed by Android platform [BiometricPrompt] (API 28+) and user-authenticated
 * AndroidKeyStore keys. Does not require FragmentActivity or extra AppCompat dependencies.
 */
internal object BiometricKeyVault {
  private const val KEYSTORE = "AndroidKeyStore"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val TAG_BITS = 128

  fun isSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val manager = context.getSystemService(BiometricManager::class.java) ?: return false
      val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
      } else {
        @Suppress("DEPRECATION")
        manager.canAuthenticate()
      }
      if (status == BiometricManager.BIOMETRIC_SUCCESS) return true
    }
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val hasFingerprint = context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
    return hasFingerprint && (keyguard?.isDeviceSecure == true)
  }

  private fun aliasFor(keyId: String) = "lagoon-ssh-bio-$keyId"

  private fun getOrCreateKey(keyId: String): SecretKey {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("当前系统版本不支持生物识别解锁")
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    val alias = aliasFor(keyId)
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    val builder = KeyGenParameterSpec.Builder(
      alias,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setUserAuthenticationRequired(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
    } else {
      @Suppress("DEPRECATION")
      builder.setUserAuthenticationValidityDurationSeconds(-1)
    }
    generator.init(builder.build())
    return generator.generateKey()
  }

  fun deleteKey(keyId: String) {
    runCatching {
      val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
      val alias = aliasFor(keyId)
      if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
  }

  suspend fun seal(
    context: Context,
    keyId: String,
    plaintext: String,
    title: String = "启用生物识别锁定",
    subtitle: String = "验证身份以保护 SSH 密钥"
  ): BiometricSealedSecret {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("需要 Android 9 及以上版本才支持生物识别")
    val key = getOrCreateKey(keyId)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val authedCipher = authenticateCrypto(context, cipher, title, subtitle)
    val encrypted = authedCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return BiometricSealedSecret(
      ciphertextBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
      ivBase64 = Base64.encodeToString(authedCipher.iv, Base64.NO_WRAP)
    )
  }

  suspend fun unseal(
    context: Context,
    keyId: String,
    ciphertextBase64: String,
    ivBase64: String,
    title: String = "解锁 SSH 密钥",
    subtitle: String = "验证身份以使用该密钥建立连接"
  ): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("需要 Android 9 及以上版本才支持生物识别")
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    val key = store.getKey(aliasFor(keyId), null) as? SecretKey
      ?: error("生物识别密钥已失效，请重新配置该密钥")
    val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
    val authedCipher = authenticateCrypto(context, cipher, title, subtitle)
    val decrypted = authedCipher.doFinal(Base64.decode(ciphertextBase64, Base64.NO_WRAP))
    return String(decrypted, Charsets.UTF_8)
  }

  /** Unlocks a time-bound hardware-backed signing key in AndroidKeyStore. */
  suspend fun authenticateHardwareKey(
    context: Context,
    title: String = "解锁硬件 SSH 密钥",
    subtitle: String = "验证身份以允许本机硬件密钥签名"
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("需要 Android 9 及以上版本才支持生物识别")
    suspendCancellableCoroutine<Unit> { continuation ->
      val signal = CancellationSignal()
      continuation.invokeOnCancellation { signal.cancel() }
      val prompt = buildPrompt(context, title, subtitle, allowDeviceCredential = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
      prompt.authenticate(signal, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          if (continuation.isActive) continuation.resume(Unit)
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (continuation.isActive) continuation.resumeWithException(IllegalStateException(errString.toString()))
        }
      })
    }
  }

  private suspend fun authenticateCrypto(
    context: Context,
    cipher: Cipher,
    title: String,
    subtitle: String
  ): Cipher {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("需要 Android 9 及以上版本才支持生物识别")
    return suspendCancellableCoroutine { continuation ->
      val signal = CancellationSignal()
      continuation.invokeOnCancellation { signal.cancel() }
      val prompt = buildPrompt(context, title, subtitle, allowDeviceCredential = false)
      val crypto = BiometricPrompt.CryptoObject(cipher)
      prompt.authenticate(crypto, signal, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          val resolved = result.cryptoObject?.cipher ?: cipher
          if (continuation.isActive) continuation.resume(resolved)
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (continuation.isActive) continuation.resumeWithException(IllegalStateException(errString.toString()))
        }
      })
    }
  }

  private fun buildPrompt(
    context: Context,
    title: String,
    subtitle: String,
    allowDeviceCredential: Boolean
  ): BiometricPrompt {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("需要 Android 9 及以上版本")
    val builder = BiometricPrompt.Builder(context)
      .setTitle(title)
      .setSubtitle(subtitle)
    if (allowDeviceCredential && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      builder.setAllowedAuthenticators(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
      )
    } else {
      builder.setNegativeButton("取消", ContextCompat.getMainExecutor(context)) { _, _ -> }
    }
    return builder.build()
  }
}
