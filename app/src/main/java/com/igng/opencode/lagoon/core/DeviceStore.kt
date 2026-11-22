package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/**
 * 唯一真源的 SSH 设备持久化存储。
 *
 * - 非敏感设备配置与挂载的 Harness 列表以 version 2 JSON 单键原子落盘在 `devices` SP；
 * - SSH 密码 / 内联私钥 / 口令 / 证书加密存放在既有的 `ssh-secrets` SP（沿用 `lagoon-ssh-secrets`
 *   Keystore 密钥，确保升级后既有凭据无缝可读）。
 */
class DeviceStore internal constructor(
  private val preferences: SharedPreferences,
  private val secretPreferences: SharedPreferences,
  private val encrypt: (String) -> String,
  private val decrypt: (String) -> String
) {
  constructor(context: Context) : this(
    context.getSharedPreferences(PREFS_DEVICES, Context.MODE_PRIVATE),
    context.getSharedPreferences(PREFS_SSH_SECRETS, Context.MODE_PRIVATE),
    KeystoreCipher.also { it.install(context) }.of("lagoon-ssh-secrets")::encrypt,
    KeystoreCipher.of("lagoon-ssh-secrets")::decrypt
  )

  @Volatile var loadError: String? = null
    private set

  fun hasStoredDevices(): Boolean = preferences.contains(KEY_DEVICES)

  fun migrationVersion(): Int = preferences.getInt(KEY_MIGRATION_VERSION, 0)

  @Synchronized fun markMigrationVersion(version: Int) {
    if (!preferences.edit().putInt(KEY_MIGRATION_VERSION, version).commit()) {
      throw IOException("无法记录迁移状态")
    }
  }

  /**
   * 面向 UI 的只读加载。文档部分损坏时尽量恢复每条合法设备记录，但不覆盖原始字节；
   * 所有写操作走 [readStrict]，遇到损坏文档时拒绝覆盖以免丢失其他设备。
   */
  @Synchronized fun load(): List<SshDevice> = try {
    val devices = readStrict()
    loadError = null
    devices
  } catch (error: IOException) {
    val raw = preferences.getString(KEY_DEVICES, null)
    val recovered = raw?.let(SshDeviceCodec::recover).orEmpty()
    loadError = error.message ?: "设备配置读取失败"
    Diagnostics.warn(
      "DeviceStore",
      if (recovered.isEmpty()) "设备配置读取失败，界面暂不显示" else "设备配置部分损坏，已显示可恢复的设备",
      error
    )
    recovered
  }

  /** 严格读取：仅在文档完好时返回，写前校验用。 */
  @Synchronized fun readStrict(): List<SshDevice> {
    val text = preferences.getString(KEY_DEVICES, null) ?: return emptyList()
    return try {
      SshDeviceCodec.decode(text)
    } catch (error: Exception) {
      throw IOException("设备配置损坏，已停止写入以免覆盖其余设备", error)
    }
  }

  fun findDevice(deviceId: String): SshDevice? = load().firstOrNull { it.id == deviceId }

  fun findHarness(harnessId: String): Pair<SshDevice, DeviceHarness>? {
    for (device in load()) {
      val harness = device.harnesses.firstOrNull { it.id == harnessId }
      if (harness != null) return device to harness
    }
    return null
  }

  /** 运行态投影：全部设备的全部 Harness，顺序稳定。 */
  fun profiles(): List<ServerProfile> = load().flatMap { it.toProfiles() }

  /** 一次性整体写入（用于首次迁移）；编码后二次解码自检，确保永不落盘损坏文档。 */
  @Synchronized fun replaceAll(devices: List<SshDevice>) {
    commitDevices(devices, "设备配置写入失败")
  }

  /** 保存或更新一台 SSH 设备（保留其既有 Harness 列表，除非显式传入新列表）。 */
  @Synchronized fun saveDevice(device: SshDevice, secrets: SshSecrets? = null) {
    val current = readStrict()
    if (secrets != null) saveSecrets(device, secrets)
    val next = if (current.any { it.id == device.id }) {
      current.map { if (it.id == device.id) device else it }
    } else {
      current + device
    }
    commitDevices(next, "SSH 设备保存失败")
  }

  @Synchronized fun deleteDevice(deviceId: String): SshDevice? {
    val current = readStrict()
    val removed = current.firstOrNull { it.id == deviceId } ?: return null
    val next = current.filterNot { it.id == deviceId }
    commitDevices(next, "SSH 设备删除失败")
    val editor = secretPreferences.edit()
    secretKeys(deviceId).forEach(editor::remove)
    editor.commit()
    return removed
  }

  @Synchronized fun setDeviceEnabled(deviceId: String, enabled: Boolean): SshDevice? {
    val current = readStrict()
    val existing = current.firstOrNull { it.id == deviceId } ?: return null
    if (existing.enabled == enabled) return existing
    val updated = existing.copy(enabled = enabled)
    commitDevices(current.map { if (it.id == deviceId) updated else it }, "更新设备启用状态失败")
    return updated
  }

  @Synchronized fun upsertHarness(deviceId: String, harness: DeviceHarness): SshDevice {
    require(harness.deviceId == deviceId) { "Harness 所属设备不匹配" }
    val current = readStrict()
    val device = current.firstOrNull { it.id == deviceId } ?: throw IOException("设备已删除，请刷新")
    val updatedHarnesses = if (device.harnesses.any { it.id == harness.id }) {
      device.harnesses.map { if (it.id == harness.id) harness else it }
    } else {
      device.harnesses + harness
    }
    val updated = device.copy(harnesses = updatedHarnesses)
    commitDevices(current.map { if (it.id == deviceId) updated else it }, "保存 Harness 失败")
    return updated
  }

  @Synchronized fun setHarnessEnabled(harnessId: String, enabled: Boolean): Pair<SshDevice, DeviceHarness>? {
    val current = readStrict()
    val device = current.firstOrNull { d -> d.harnesses.any { it.id == harnessId } } ?: return null
    val harness = device.harnesses.first { it.id == harnessId }
    if (harness.enabled == enabled) return device to harness
    val nextHarness = harness.copy(enabled = enabled)
    val nextDevice = device.copy(harnesses = device.harnesses.map { if (it.id == harnessId) nextHarness else it })
    commitDevices(current.map { if (it.id == device.id) nextDevice else it }, "更新 Harness 开关失败")
    return nextDevice to nextHarness
  }

  @Synchronized fun removeHarness(harnessId: String): Pair<SshDevice, DeviceHarness>? {
    val current = readStrict()
    val device = current.firstOrNull { d -> d.harnesses.any { it.id == harnessId } } ?: return null
    val removed = device.harnesses.first { it.id == harnessId }
    val nextDevice = device.copy(harnesses = device.harnesses.filterNot { it.id == harnessId })
    commitDevices(current.map { if (it.id == device.id) nextDevice else it }, "移除 Harness 失败")
    return nextDevice to removed
  }

  fun secrets(deviceId: String): SshSecrets = SshSecrets(
    password = readSecret(passwordKey(deviceId)),
    privateKey = readSecret(privateKeyKey(deviceId)),
    passphrase = readSecret(passphraseKey(deviceId)),
    certificate = readSecret(certificateKey(deviceId))
  )

  /** TOFU：记住用户明确信任的主机密钥。 */
  @Synchronized fun pinHostKey(deviceId: String, key: PresentedSshHostKey): SshDevice? {
    val current = readStrict()
    val device = current.firstOrNull { it.id == deviceId } ?: return null
    val updated = device.copyEndpoint(hostKeyType = key.type, hostKeyFingerprint = key.fingerprint)
    commitDevices(current.map { if (it.id == deviceId) updated else it }, "固定主机指纹失败")
    return updated
  }

  /** CAS 固定主机密钥：端点在展示指纹期间被修改或删除时拒绝写入。 */
  @Synchronized fun pinHostKeyIfUnchanged(expected: SshHostConfig, key: PresentedSshHostKey): Boolean {
    val current = readStrict()
    val device = current.firstOrNull { it.id == expected.id } ?: return false
    if (device.host != expected.host || device.port != expected.port ||
      device.hostKeyType != expected.hostKeyType || device.hostKeyFingerprint != expected.hostKeyFingerprint
    ) return false
    val updated = device.copyEndpoint(hostKeyType = key.type, hostKeyFingerprint = key.fingerprint)
    commitDevices(current.map { if (it.id == expected.id) updated else it }, "固定主机指纹失败")
    return true
  }

  /** 清除已固定的主机指纹（机器重装或更换密钥后由用户显式确认）。 */
  @Synchronized fun resetHostKey(deviceId: String): SshDevice? {
    val current = readStrict()
    val device = current.firstOrNull { it.id == deviceId } ?: return null
    val updated = device.copyEndpoint(clearHostKey = true)
    commitDevices(current.map { if (it.id == deviceId) updated else it }, "重置主机指纹失败")
    return updated
  }

  private fun commitDevices(devices: List<SshDevice>, failureMessage: String) {
    val encoded = SshDeviceCodec.encode(devices)
    // Round-trip validation before writing prevents persisting an unreadable document.
    SshDeviceCodec.decode(encoded)
    if (!preferences.edit().putString(KEY_DEVICES, encoded).commit()) {
      throw IOException(failureMessage)
    }
    loadError = null
  }

  private fun saveSecrets(device: SshDevice, secrets: SshSecrets) {
    val editor = secretPreferences.edit()
    when (device.auth) {
      SshAuthMethod.PASSWORD -> {
        writeSecret(editor, passwordKey(device.id), secrets.password)
        editor.remove(privateKeyKey(device.id))
          .remove(passphraseKey(device.id))
          .remove(certificateKey(device.id))
      }
      SshAuthMethod.PRIVATE_KEY -> {
        writeSecret(editor, privateKeyKey(device.id), secrets.privateKey?.trim())
        writeSecret(editor, passphraseKey(device.id), secrets.passphrase)
        writeSecret(editor, certificateKey(device.id), secrets.certificate?.trim())
        editor.remove(passwordKey(device.id))
      }
      SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK -> {
        writeSecret(editor, passwordKey(device.id), secrets.password)
        writeSecret(editor, privateKeyKey(device.id), secrets.privateKey?.trim())
        writeSecret(editor, passphraseKey(device.id), secrets.passphrase)
        writeSecret(editor, certificateKey(device.id), secrets.certificate?.trim())
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
    Diagnostics.warn("DeviceStore", "SSH 凭据解密失败", error)
    null
  }

  private fun secretKeys(id: String) = listOf(passwordKey(id), privateKeyKey(id), passphraseKey(id), certificateKey(id))
  private fun passwordKey(id: String) = "ssh:$id:password"
  private fun privateKeyKey(id: String) = "ssh:$id:privateKey"
  private fun passphraseKey(id: String) = "ssh:$id:passphrase"
  private fun certificateKey(id: String) = "ssh:$id:certificate"

  companion object {
    const val PREFS_DEVICES = "devices"
    const val PREFS_SSH_SECRETS = "ssh-secrets"
    private const val KEY_DEVICES = "devices"
    private const val KEY_MIGRATION_VERSION = "migrationVersion"
  }
}
