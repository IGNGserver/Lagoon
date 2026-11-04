package com.igng.opencode.lagoon.core

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Trust belongs to one Server origin, account and Host ID; persist only after authenticated lookup. */
internal data class RemotePeerScope(val origin: String, val account: String, val hostId: String)

internal interface RemotePeerTrustStore {
  fun pinned(scope: RemotePeerScope): String?
  /** Atomically insert only if absent; return the key actually stored. */
  fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String
}

internal class RemoteTrustException(message: String, val changed: Boolean) : IOException(message)

internal class RemotePeerTrust(private val store: RemotePeerTrustStore) {
  fun pinAfterConfirmation(scope: RemotePeerScope, host: RemoteHost): String {
    validateScope(scope, host)
    val key = host.publicKey?.takeIf(RemoteServerApi::validPublicKey) ?: throw IOException("Host 身份公钥无效")
    val stored = store.pinIfAbsent(scope, key)
    if (!sameKey(stored, key)) throw RemoteTrustException("Host 身份公钥已变更，请先移除旧信任记录", changed = true)
    return key
  }

  fun verify(scope: RemotePeerScope, host: RemoteHost): String {
    validateScope(scope, host)
    val key = host.publicKey?.takeIf(RemoteServerApi::validPublicKey) ?: throw IOException("Host 身份公钥无效")
    val pinned = store.pinned(scope) ?: throw RemoteTrustException("该 Host 尚未获信任，请先核对身份指纹", changed = false)
    if (!sameKey(pinned, key)) throw RemoteTrustException("Host 身份公钥已变更，请核实设备身份", changed = true)
    return key
  }

  private fun validateScope(scope: RemotePeerScope, host: RemoteHost) {
    val origin = scope.origin.toHttpUrlOrNull()
    if (scope.account.isBlank() || '\u0000' in scope.account || origin == null || origin.username.isNotEmpty() || origin.password.isNotEmpty() ||
      origin.encodedPath != "/" || origin.query != null || origin.fragment != null ||
      scope.hostId != host.deviceId || !RemoteServerApi.validDeviceId(scope.hostId))
      throw IOException("Host 身份与所选目标不一致")
  }

  private fun sameKey(left: String, right: String): Boolean = RemoteServerApi.validPublicKey(left) &&
    RemoteServerApi.validPublicKey(right) && MessageDigest.isEqual(
      Base64.getUrlDecoder().decode(left), Base64.getUrlDecoder().decode(right))
}
