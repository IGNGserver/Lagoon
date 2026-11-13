package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence for the global SSH keychain: non-secret metadata ([SshKeyProfile]) in `ssh-keys`
 * and Keystore-encrypted key material ([SshKeySecrets]) in `ssh-key-secrets`.
 *
 * Also holds an in-memory [runtimeSecrets] cache for keys whose policy is [SshPassphrasePolicy.PROMPT]
 * or [SshPassphrasePolicy.BIOMETRIC] so unlocked keys remain usable across reconnects until locked
 * or process exit without ever persisting plaintext secrets.
 */
internal class SshKeyStore internal constructor(
  private val preferences: SharedPreferences,
  private val secretPreferences: SharedPreferences,
  private val encrypt: (String) -> String,
  private val decrypt: (String) -> String
) {
  constructor(context: Context) : this(
    context.getSharedPreferences("ssh-keys", Context.MODE_PRIVATE),
    context.getSharedPreferences("ssh-key-secrets", Context.MODE_PRIVATE),
    KeystoreCipher.of("lagoon-ssh-keys")::encrypt,
    KeystoreCipher.of("lagoon-ssh-keys")::decrypt
  )

  private val runtimeSecrets = ConcurrentHashMap<String, String>()
  @Volatile var revision: Long = 0L
    private set

  fun load(): List<SshKeyProfile> = try {
    readKeys()
  } catch (error: IOException) {
    Diagnostics.warn("SshKeyStore", "SSH 密钥库读取失败，界面暂不显示", error)
    emptyList()
  }

  private fun readKeys(): List<SshKeyProfile> {
    val text = preferences.getString(KEY, null) ?: return emptyList()
    return try {
      SshKeyCodec.decode(text).distinctBy { it.id }
    } catch (error: Exception) {
      throw IOException("SSH 密钥库损坏，已停止写入以免覆盖其余密钥", error)
    }
  }

  fun find(id: String): SshKeyProfile? = load().firstOrNull { it.id == id }

  fun save(profile: SshKeyProfile, secrets: SshKeySecrets? = null) {
    val current = readKeys()
    if (secrets != null) saveSecrets(profile, secrets)
    val next = current.filterNot { it.id == profile.id } + profile
    if (!preferences.edit().putString(KEY, SshKeyCodec.encode(next)).commit()) {
      throw IOException("SSH 密钥保存失败")
    }
    revision += 1
  }

  fun delete(id: String) {
    val next = readKeys().filterNot { it.id == id }
    if (!preferences.edit().putString(KEY, SshKeyCodec.encode(next)).commit()) {
      throw IOException("SSH 密钥删除失败")
    }
    val editor = secretPreferences.edit()
    secretKeys(id).forEach(editor::remove)
    editor.commit()
    runtimeSecrets.remove(id)
    revision += 1
  }

  fun secrets(id: String): SshKeySecrets = SshKeySecrets(
    privateKey = readSecret(privateKeyKey(id)),
    passphrase = readSecret(passphraseKey(id)),
    biometricCiphertext = secretPreferences.getString(biometricCipherKey(id), null),
    biometricIv = secretPreferences.getString(biometricIvKey(id), null)
  )

  fun runtimeSecret(id: String): String? = runtimeSecrets[id]

  fun unlockRuntimeSecret(id: String, secret: String) {
    runtimeSecrets[id] = secret
    revision += 1
  }

  fun lockRuntimeSecret(id: String) {
    runtimeSecrets.remove(id)
    revision += 1
  }

  fun lockAllRuntimeSecrets() {
    runtimeSecrets.clear()
    revision += 1
  }

  fun isUnlocked(profile: SshKeyProfile): Boolean = when {
    profile.source == SshKeySource.HARDWARE_KEYSTORE -> !profile.biometricProtected || runtimeSecrets.containsKey(profile.id)
    profile.passphrasePolicy == SshPassphrasePolicy.BIOMETRIC -> runtimeSecrets.containsKey(profile.id)
    profile.passphrasePolicy == SshPassphrasePolicy.PROMPT && profile.encrypted -> runtimeSecrets.containsKey(profile.id)
    else -> true
  }

  /**
   * Computes an in-memory revision token for [SshTransportPool] so connections are never reused
   * after a key in the keychain is rotated, locked, or unlocked.
   */
  fun credentialFingerprint(keyId: String): String {
    val profile = find(keyId) ?: return "missing:$keyId"
    val stored = secrets(keyId)
    val runtime = runtimeSecrets[keyId]
    return listOf(
      profile.id,
      profile.fingerprint,
      profile.certificateOpenSsh.orEmpty(),
      profile.passphrasePolicy.name,
      stored.privateKey?.length?.toString().orEmpty(),
      stored.passphrase?.length?.toString().orEmpty(),
      runtime ?: ""
    ).joinToString("\u0000")
  }

  private fun saveSecrets(profile: SshKeyProfile, secrets: SshKeySecrets) {
    val editor = secretPreferences.edit()
    when (profile.source) {
      SshKeySource.HARDWARE_KEYSTORE -> {
        secretKeys(profile.id).forEach(editor::remove)
      }
      SshKeySource.GENERATED, SshKeySource.IMPORTED -> {
        if (profile.passphrasePolicy == SshPassphrasePolicy.BIOMETRIC && secrets.hasBiometricBlob) {
          if (!profile.encrypted) {
            // Unencrypted key guarded by biometric: store private key only inside the biometric ciphertext.
            editor.remove(privateKeyKey(profile.id))
            editor.remove(passphraseKey(profile.id))
          } else {
            writeSecret(editor, privateKeyKey(profile.id), secrets.privateKey?.trim())
            editor.remove(passphraseKey(profile.id))
          }
          editor.putString(biometricCipherKey(profile.id), secrets.biometricCiphertext)
          editor.putString(biometricIvKey(profile.id), secrets.biometricIv)
        } else {
          writeSecret(editor, privateKeyKey(profile.id), secrets.privateKey?.trim())
          if (profile.passphrasePolicy == SshPassphrasePolicy.PROMPT) {
            editor.remove(passphraseKey(profile.id))
          } else {
            writeSecret(editor, passphraseKey(profile.id), secrets.passphrase)
          }
          editor.remove(biometricCipherKey(profile.id)).remove(biometricIvKey(profile.id))
        }
      }
    }
    if (!editor.commit()) throw IOException("SSH 密钥凭据保存失败")
  }

  private fun writeSecret(editor: SharedPreferences.Editor, key: String, value: String?) {
    if (value.isNullOrEmpty()) editor.remove(key) else editor.putString(key, encrypt(value))
  }

  private fun readSecret(key: String): String? = try {
    secretPreferences.getString(key, null)?.let(decrypt)?.takeIf(String::isNotEmpty)
  } catch (error: Exception) {
    Diagnostics.warn("SshKeyStore", "SSH 密钥解密失败", error)
    null
  }

  private fun secretKeys(id: String) = listOf(
    privateKeyKey(id),
    passphraseKey(id),
    biometricCipherKey(id),
    biometricIvKey(id)
  )

  private fun privateKeyKey(id: String) = "key:$id:privateKey"
  private fun passphraseKey(id: String) = "key:$id:passphrase"
  private fun biometricCipherKey(id: String) = "key:$id:bioCipher"
  private fun biometricIvKey(id: String) = "key:$id:bioIv"

  private companion object { const val KEY = "keys" }
}
