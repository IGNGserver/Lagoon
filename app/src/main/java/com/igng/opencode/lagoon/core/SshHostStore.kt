package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import org.json.JSONObject

/**
 * Persistence for saved SSH hosts: the non-secret config in one preferences file and the password /
 * private key / passphrase in a separate Keystore-encrypted file. The config is versioned so a later
 * schema change can migrate it without touching server profiles or credentials.
 */
internal class SshHostStore internal constructor(
  private val preferences: SharedPreferences,
  private val secretPreferences: SharedPreferences,
  private val encrypt: (String) -> String,
  private val decrypt: (String) -> String
) {
  constructor(context: Context) : this(
    context.getSharedPreferences("ssh-hosts", Context.MODE_PRIVATE),
    context.getSharedPreferences("ssh-secrets", Context.MODE_PRIVATE),
    KeystoreCipher.of("lagoon-ssh-secrets")::encrypt,
    KeystoreCipher.of("lagoon-ssh-secrets")::decrypt
  )

  /**
   * Read-only view for the UI. A malformed document must not blank the whole device list: recover
   * every host entry that is individually valid, while writers keep using [readHosts] and refuse to
   * overwrite the original bytes.
   */
  @Synchronized fun load(): List<SshHostConfig> = try {
    readHosts()
  } catch (error: IOException) {
    val recovered = recoverHosts()
    Diagnostics.warn("SshHostStore",
      if (recovered.isEmpty()) "SSH 主机配置读取失败，界面暂不显示" else "SSH 主机配置部分损坏，已显示可恢复的设备",
      error)
    recovered
  }

  private fun recoverHosts(): List<SshHostConfig> = runCatching {
    val text = preferences.getString(KEY, null) ?: return@runCatching emptyList()
    val array = JSONObject(text).optJSONArray("hosts") ?: return@runCatching emptyList()
    (0 until array.length()).mapNotNull { index ->
      array.optJSONObject(index)?.let(SshHostConfig::fromJson)
    }.distinctBy { it.id }
  }.getOrDefault(emptyList())

  /** Throws when the stored document cannot be trusted. Writers must not replace it with a
   *  one-host list, which would erase every other host. */
  private fun readHosts(): List<SshHostConfig> {
    val text = preferences.getString(KEY, null) ?: return emptyList()
    return try {
      SshHostCodec.decode(text).distinctBy { it.id }
    } catch (error: Exception) {
      throw IOException("SSH 主机配置损坏，已停止写入以免覆盖其余主机", error)
    }
  }

  @Synchronized fun save(config: SshHostConfig, secrets: SshSecrets? = null) {
    val current = readHosts()
    if (secrets != null) saveSecrets(config, secrets)
    val next = current.filterNot { it.id == config.id } + config
    if (!preferences.edit().putString(KEY, SshHostCodec.encode(next)).commit()) {
      throw IOException("SSH 主机保存失败")
    }
  }

  @Synchronized fun delete(id: String) {
    val next = readHosts().filterNot { it.id == id }
    if (!preferences.edit().putString(KEY, SshHostCodec.encode(next)).commit()) {
      throw IOException("SSH 主机删除失败")
    }
    val editor = secretPreferences.edit()
    secretKeys(id).forEach(editor::remove)
    editor.commit()
  }

  fun secrets(id: String): SshSecrets = SshSecrets(
    password = readSecret(passwordKey(id)),
    privateKey = readSecret(privateKeyKey(id)),
    passphrase = readSecret(passphraseKey(id)),
    certificate = readSecret(certificateKey(id))
  )

  /** Trust-on-first-use: remembers the key the person accepted. */
  @Synchronized fun pinHostKey(id: String, key: PresentedSshHostKey) {
    val current = load().firstOrNull { it.id == id } ?: return
    save(current.copyWith(hostKeyType = key.type, hostKeyFingerprint = key.fingerprint))
  }

  /** Never trust a key displayed for an endpoint that was edited or deleted in the meantime. */
  @Synchronized fun pinHostKeyIfUnchanged(expected: SshHostConfig, key: PresentedSshHostKey): Boolean {
    val current = readHosts().firstOrNull { it.id == expected.id } ?: return false
    if (current != expected) return false
    save(current.copyWith(hostKeyType = key.type, hostKeyFingerprint = key.fingerprint))
    return true
  }

  /** Forgets the pinned key after the person explicitly confirmed the machine changed. */
  @Synchronized fun resetHostKey(id: String) {
    val current = load().firstOrNull { it.id == id } ?: return
    save(current.copyWith(clearHostKey = true))
  }

  private fun saveSecrets(config: SshHostConfig, secrets: SshSecrets) {
    val editor = secretPreferences.edit()
    when (config.auth) {
      SshAuthMethod.PASSWORD -> {
        writeSecret(editor, passwordKey(config.id), secrets.password)
        editor.remove(privateKeyKey(config.id))
          .remove(passphraseKey(config.id))
          .remove(certificateKey(config.id))
      }
      SshAuthMethod.PRIVATE_KEY -> {
        writeSecret(editor, privateKeyKey(config.id), secrets.privateKey?.trim())
        writeSecret(editor, passphraseKey(config.id), secrets.passphrase)
        writeSecret(editor, certificateKey(config.id), secrets.certificate?.trim())
        editor.remove(passwordKey(config.id))
      }
      SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK -> {
        writeSecret(editor, passwordKey(config.id), secrets.password)
        writeSecret(editor, privateKeyKey(config.id), secrets.privateKey?.trim())
        writeSecret(editor, passphraseKey(config.id), secrets.passphrase)
        writeSecret(editor, certificateKey(config.id), secrets.certificate?.trim())
      }
    }
    if (!editor.commit()) throw IOException("SSH 凭据保存失败")
  }

  private fun writeSecret(editor: SharedPreferences.Editor, key: String, value: String?) {
    if (value.isNullOrEmpty()) editor.remove(key) else editor.putString(key, encrypt(value))
  }

  private fun readSecret(key: String): String? = try {
    secretPreferences.getString(key, null)?.let(decrypt)?.takeIf(String::isNotEmpty)
  } catch (error: Exception) {
    Diagnostics.warn("SshHostStore", "SSH 凭据解密失败", error)
    null
  }

  private fun secretKeys(id: String) = listOf(passwordKey(id), privateKeyKey(id), passphraseKey(id), certificateKey(id))
  private fun passwordKey(id: String) = "ssh:$id:password"
  private fun privateKeyKey(id: String) = "ssh:$id:privateKey"
  private fun passphraseKey(id: String) = "ssh:$id:passphrase"
  private fun certificateKey(id: String) = "ssh:$id:certificate"

  private companion object { const val KEY = "hosts" }
}
