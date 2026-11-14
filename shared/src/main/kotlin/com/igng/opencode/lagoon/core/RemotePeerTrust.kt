package com.igng.opencode.lagoon.core

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** Trust belongs to one Server origin, account and Host ID; persist only after authenticated lookup. */
internal data class RemotePeerScope(val origin: String, val account: String, val hostId: String)

internal interface RemotePeerTrustStore {
  fun pinned(scope: RemotePeerScope): String?
  /** Atomically insert only if absent; return the key actually stored. */
  fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String
  /** Atomically replace [expectedPublicKey] with [replacementPublicKey]. */
  fun replaceIfMatches(scope: RemotePeerScope, expectedPublicKey: String, replacementPublicKey: String): Boolean = false
  fun remove(scope: RemotePeerScope) {}
  fun removeAllForAccount(origin: String, account: String) {}
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

  /**
   * Replace a changed pin only after the caller has explicitly confirmed the new Host key.
   * The expected key is compared atomically by the store; a race never overwrites another key.
   */
  fun replaceAfterConfirmation(
    scope: RemotePeerScope,
    host: RemoteHost,
    expectedPublicKey: String
  ): String {
    validateScope(scope, host)
    val replacement = host.publicKey?.takeIf(RemoteServerApi::validPublicKey)
      ?: throw IOException("Host 身份公钥无效")
    if (!RemoteServerApi.validPublicKey(expectedPublicKey)) throw IOException("旧 Host 身份公钥无效")
    if (sameKey(expectedPublicKey, replacement)) {
      val stored = store.pinned(scope)
      if (stored != null && sameKey(stored, replacement)) return replacement
      throw RemoteTrustException("Host 身份公钥尚未获信任，请重新核对身份", changed = false)
    }
    if (!store.replaceIfMatches(scope, expectedPublicKey, replacement)) {
      throw RemoteTrustException("Host 身份公钥已变化，请重新核对身份", changed = true)
    }
    val stored = store.pinned(scope)
    if (stored == null || !sameKey(stored, replacement)) {
      throw RemoteTrustException("Host 身份公钥替换未生效，请重新核对身份", changed = true)
    }
    return replacement
  }

  fun pinOrReplaceAfterConfirmation(scope: RemotePeerScope, host: RemoteHost): String {
    validateScope(scope, host)
    val key = host.publicKey?.takeIf(RemoteServerApi::validPublicKey) ?: throw IOException("Host 身份公钥无效")
    val existing = store.pinned(scope)
    return when {
      existing == null -> pinAfterConfirmation(scope, host)
      sameKey(existing, key) -> key
      else -> replaceAfterConfirmation(scope, host, existing)
    }
  }

  fun forget(scope: RemotePeerScope) {
    store.remove(scope)
    val normalizedOrigin = runCatching { canonicalRemoteOrigin(scope.origin) }.getOrNull()
    if (normalizedOrigin != null) {
      val normalized = scope.copy(origin = normalizedOrigin, account = scope.account.trim())
      if (normalized != scope) store.remove(normalized)
    }
  }

  fun forgetAccount(origin: String, account: String) {
    val canonical = runCatching { canonicalRemoteOrigin(origin) }.getOrElse { origin.trim() }
    store.removeAllForAccount(canonical, account.trim())
  }

  fun verify(scope: RemotePeerScope, host: RemoteHost): String {
    validateScope(scope, host)
    val key = host.publicKey?.takeIf(RemoteServerApi::validPublicKey) ?: throw IOException("Host 身份公钥无效")
    val pinned = store.pinned(scope) ?: throw RemoteTrustException("该 Host 尚未获信任，请先核对身份指纹", changed = false)
    if (!sameKey(pinned, key)) throw RemoteTrustException("Host 身份公钥已变更，请核实设备身份", changed = true)
    return key
  }

  private fun validateScope(scope: RemotePeerScope, host: RemoteHost) {
    val validOrigin = runCatching { canonicalRemoteOrigin(scope.origin) == scope.origin.trim() }.getOrDefault(false) ||
      runCatching { canonicalRemoteOrigin(scope.origin) }.isSuccess
    val originParts = extractFlexibleHttpOriginParts(scope.origin)
    if (scope.account.isBlank() || '\u0000' in scope.account || !validOrigin || originParts == null ||
      scope.hostId != host.deviceId || !RemoteServerApi.validDeviceId(scope.hostId))
      throw IOException("Host 身份与所选目标不一致")
  }

  private fun sameKey(left: String, right: String): Boolean = RemoteServerApi.validPublicKey(left) &&
    RemoteServerApi.validPublicKey(right) && MessageDigest.isEqual(
      Base64.getUrlDecoder().decode(left), Base64.getUrlDecoder().decode(right))
}
