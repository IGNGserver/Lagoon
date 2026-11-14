package com.igng.opencode.lagoon.core

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

internal data class RemoteSetupState(val account: String, val deviceId: String, val hosts: List<RemoteHost>)

/** Explicit account registration and Host trust ceremony; pinning is never automatic. */
internal class RemoteSetup(
  private val api: RemoteServerApi,
  private val devices: RemoteDeviceStore,
  private val pins: RemotePeerTrustStore,
  private val slot: String,
  private val origin: String,
  private val name: String,
  private val platform: String,
  private val version: String
) {
  suspend fun register(account: String, password: String): RemoteSetupState {
    val login = api.login(account, password)
    if (!login.account.equals(account.trim(), ignoreCase = true)) throw IOException("DSH Hub 返回的账号与登录账号不一致")
    val manager = manager(login.account)
    val device = manager.register(name, platform, version, login.token) { descriptor, token -> api.register(descriptor, token) }
    val token = device.tokens?.accessToken ?: throw IOException("DSH Hub 没有返回设备访问令牌")
    val hosts = api.hosts(token).map { host ->
      val detail = api.host(token, host.deviceId)
      detail.copy(fingerprint = RemoteSetup.fingerprint(detail.publicKey!!))
    }
    return RemoteSetupState(login.account, device.identity.deviceId, hosts)
  }

  suspend fun confirmHost(account: String, hostId: String, expectedFingerprint: String): RemoteHost {
    require(expectedFingerprint.matches(Regex("(?i)[0-9a-f]{12}"))) { "Host 指纹格式无效" }
    val device = manager(account).access { id, token -> api.refresh(id, token) }
    val token = device.tokens?.accessToken ?: throw IOException("DSH Hub 设备尚未注册")
    val host = api.host(token, hostId)
    val actual = fingerprint(host.publicKey ?: throw IOException("Host 未提供身份公钥"))
    if (!MessageDigest.isEqual(actual.lowercase().toByteArray(Charsets.UTF_8), expectedFingerprint.lowercase().toByteArray(Charsets.UTF_8)))
      throw IOException("Host 指纹与确认值不匹配")
    val scope = RemotePeerScope(origin, account.trim(), host.deviceId)
    RemotePeerTrust(pins).pinAfterConfirmation(scope, host)
    return host
  }

  private fun manager(account: String) = RemoteDeviceManager(devices, slot, origin, account,
    registerer = { descriptor, token -> api.register(descriptor, token) },
    refresher = { id, token -> api.refresh(id, token) })

  companion object {
    fun fingerprint(publicKey: String): String {
      if (!RemoteServerApi.validPublicKey(publicKey)) throw IOException("Host 身份公钥无效")
      return MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(publicKey)).take(6)
        .joinToString("") { "%02X".format(it) }
    }
  }
}

internal interface RemoteBackendFactory {
  fun create(profile: ServerProfile, credentials: ServerCredentials): RemoteBackend
}
