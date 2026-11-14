package com.igng.opencode.lagoon.core

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** A Host descriptor plus the display metadata the Remote page needs. */
internal data class RemoteHostCandidate(
  val host: RemoteHost,
  val meta: RemoteHostMeta
) {
  val hostId: String get() = host.deviceId
  val canEnroll: Boolean get() = host.publicKey != null && meta.fingerprint != null
}

internal data class RemoteEnrollment(
  val catalog: RemoteCatalog,
  val registered: Map<String, String>, // hostId -> client deviceId
  val failures: Map<String, String>   // hostId -> user-facing reason
)

/**
 * DSH Remote account flow: OAuth/password login, Host discovery, per-Host device enrollment and
 * token revocation. One manager instance keeps the in-memory account token while the UI selects
 * Hosts; the token is never persisted.
 */
internal class RemoteAccountManager(
  address: String,
  private val allowCleartext: Boolean,
  private val deviceLabel: String,
  private val platform: String,
  private val clientVersion: String,
  private val devices: RemoteDeviceStore,
  private val pins: RemotePeerTrustStore,
  private val catalogs: RemoteCatalogStore
) {
  private val origin = canonicalRemoteOrigin(address)
  private val api = RemoteServerApi(address, allowCleartext)
  private var accountToken: String? = null
  private var account: String? = null
  private var provider: String? = null
  private var candidates: List<RemoteHostCandidate> = emptyList()

  private fun requirePending(): Pair<String, String> {
    val token = accountToken ?: throw IOException("请先登录 DSH Remote")
    val name = account ?: throw IOException("请先登录 DSH Remote")
    return name to token
  }

  suspend fun providers(): List<RemoteOAuthProvider> = api.oauthProviders()

  /** Restores account metadata only; refresh still requires the saved per-device credential. */
  fun resume(catalog: RemoteCatalog) {
    require(canonicalRemoteOrigin(catalog.origin) == origin) { "中枢地址不匹配" }
    require(!catalog.signedOut) { "中枢账号已退出登录" }
    account = catalog.account
    provider = catalog.provider
  }

  suspend fun beginBrowserLogin(source: RemoteOAuthProvider): RemoteBrowserLogin = api.startQrLogin(source)

  suspend fun pollBrowserLogin(login: RemoteBrowserLogin): RemoteQrPoll = api.pollQrLogin(login.qrId)

  /** Completes a browser login and loads the account's Hosts. */
  suspend fun completeBrowserLogin(login: RemoteBrowserLogin, accountToken: String): List<RemoteHostCandidate> {
    val name = api.accountMe(accountToken)
    return adoptAccount(name, accountToken, login.provider, accountScoped = true)
  }

  suspend fun loginWithPassword(name: String, password: String): List<RemoteHostCandidate> {
    val login = api.login(name, password)
    return adoptAccount(login.account, login.token, "password", accountScoped = true)
  }

  /** Checks server auth and native device pairing capabilities. */
  suspend fun authCapabilities(): RemoteAuthCapabilities = api.authCapabilities()

  suspend fun refresh(): RemoteCatalog {
    val current = catalogs.load().firstOrNull { it.origin == origin && it.account == account }
      ?: throw IOException("请先登录 DSH Remote")
    check(!current.signedOut) { "中枢账号已退出登录，请重新登录" }
    if (current.hosts.isEmpty()) throw IOException("当前账号尚未接入远程主机，请先添加主机")
    val previousById = current.hosts.associateBy { it.hostId }
    var lastTransientError: Exception? = null
    for (host in current.hosts) {
      val slot = deviceSlot(host.hostId, current.account)
      val record = runCatching { devices.load(slot) }.getOrNull() ?: continue
      if (record.account != current.account || record.origin != origin || record.invalidated) continue
      val manager = RemoteDeviceManager(
        devices,
        slot,
        origin,
        current.account,
        refresher = { id, refresh -> api.refresh(id, refresh) },
      )
      try {
        val hosts = loadHostsWithReactiveRefresh(manager, current.account)
        val mergedHosts = hosts.map { next ->
          if (next.trust == RemoteTrustState.PINNED) return@map next
          val prev = previousById[next.hostId]
          val matchesPrev = prev?.trust == RemoteTrustState.PINNED &&
            prev.fingerprint != null &&
            next.fingerprint != null &&
            equalFingerprint(prev.fingerprint, next.fingerprint)
          if (matchesPrev) next.copy(trust = RemoteTrustState.PINNED) else next
        }
        val updated = current.copy(hosts = mergedHosts, updatedAt = System.currentTimeMillis())
        catalogs.save(updated)
        return updated
      } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
      } catch (error: RemoteServerException) {
        if (!error.isCredentialRejected && !error.isDeviceRevoked) {
          lastTransientError = error
        }
      } catch (error: IOException) {
        if (!isDefinitiveSlotFailure(error)) {
          lastTransientError = error
        }
      }
    }
    lastTransientError?.let { throw it }
    throw IOException("DSH Remote 登录已失效，请重新登录")
  }

  private suspend fun loadHostsWithReactiveRefresh(manager: RemoteDeviceManager, accountName: String): List<RemoteHostMeta> {
    val token = manager.access().tokens?.accessToken ?: throw IOException("DSH Remote 登录已失效，请重新登录")
    return try {
      loadHosts(token, accountName)
    } catch (error: RemoteServerException) {
      if (!error.isRecoverableTokenRejection) throw error
      val refreshed = manager.refreshAfterRejected(token).tokens?.accessToken
        ?: throw IOException("DSH Remote 登录已失效，请重新登录")
      loadHosts(refreshed, accountName)
    }
  }

  private fun isDefinitiveSlotFailure(error: IOException): Boolean {
    val msg = error.message.orEmpty()
    return msg.contains("失效") || msg.contains("未注册") || msg.contains("未登录")
  }

  /** Registers one client identity per selected Host and pins the confirmed fingerprints. */
  suspend fun enroll(selected: List<RemoteHostCandidate>, confirmed: Map<String, String>): RemoteEnrollment {
    val (name, token) = requirePending()
    if (selected.isEmpty()) throw IOException("至少选择一台主机")
    if (selected.size > 16) throw IOException("一次最多接入 16 台主机")
    val previousCatalog = catalogs.load().firstOrNull { it.origin == origin && it.account == name }
    val previousById = previousCatalog?.hosts?.associateBy { it.hostId }.orEmpty()
    val previousAdopted = previousCatalog?.effectiveAdoptedHostIds()
    val registered = LinkedHashMap<String, String>()
    val failures = LinkedHashMap<String, String>()
    val byId = candidates.associateBy { it.hostId }
    val catalogHosts = candidates.map { next ->
      if (next.meta.trust == RemoteTrustState.PINNED) return@map next.meta
      val prev = previousById[next.meta.hostId]?.takeIf { previousAdopted == null || next.meta.hostId in previousAdopted }
      val matchesPrev = prev?.trust == RemoteTrustState.PINNED &&
        prev.fingerprint != null &&
        next.meta.fingerprint != null &&
        equalFingerprint(prev.fingerprint, next.meta.fingerprint)
      if (matchesPrev) next.meta.copy(trust = RemoteTrustState.PINNED) else next.meta
    }.toMutableList()
    for (candidate in selected) {
      try {
        val host = byId[candidate.hostId]?.host ?: throw IOException("主机信息已过期，请刷新")
        if (!candidate.canEnroll) throw IOException("主机没有提供可核对的身份公钥")
        val expected = confirmed[candidate.hostId]?.trim()
          ?: throw IOException("请先核对主机指纹")
        if (!expected.matches(Regex("(?i)[0-9a-f]{12}"))) throw IOException("主机指纹格式无效")
        val actual = candidate.meta.fingerprint ?: throw IOException("主机没有提供身份指纹")
        if (!equalFingerprint(actual, expected)) throw IOException("主机指纹与确认值不匹配")
        val scope = RemotePeerScope(origin, name, host.deviceId)
        val slot = deviceSlot(host.deviceId, name)
        val manager = RemoteDeviceManager(devices, slot, origin, name,
          registerer = { descriptor, accountToken -> api.register(descriptor, accountToken) },
          refresher = { id, refresh -> api.refresh(id, refresh) })
        val device = manager.register(deviceName(host), platform, clientVersion, token)
        RemotePeerTrust(pins).pinOrReplaceAfterConfirmation(scope, host)
        registered[host.deviceId] = device.identity.deviceId
        val index = catalogHosts.indexOfFirst { it.hostId == host.deviceId }
        if (index >= 0) catalogHosts[index] = catalogHosts[index].copy(trust = RemoteTrustState.PINNED)
      } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
      } catch (error: Exception) {
        failures[candidate.hostId] = userFacingError(error, "接入主机失败")
      }
    }
    repairPinnedHostsIfNeeded(name, token, byId, previousById, previousAdopted, registered)
    val adopted = (previousAdopted.orEmpty() + registered.keys).toSet()
    val catalog = RemoteCatalog(
      origin = origin,
      account = name,
      provider = provider,
      deviceLabel = deviceLabel,
      hosts = catalogHosts,
      updatedAt = System.currentTimeMillis(),
      allowCleartext = allowCleartext,
      adoptedHostIds = adopted,
    )
    catalogs.save(catalog)
    if (registered.isNotEmpty()) accountToken = null
    this.account = name
    return RemoteEnrollment(catalog, registered, failures)
  }

  private suspend fun repairPinnedHostsIfNeeded(
    accountName: String,
    accountAccessToken: String,
    byId: Map<String, RemoteHostCandidate>,
    previousById: Map<String, RemoteHostMeta>,
    previousAdopted: Set<String>?,
    alreadyRegistered: Map<String, String>,
  ) {
    val now = System.currentTimeMillis()
    val refreshMarginMs = 7L * 86_400_000L
    for ((hostId, prev) in previousById) {
      if (hostId in alreadyRegistered) continue
      if (previousAdopted != null && hostId !in previousAdopted) continue
      if (prev.trust != RemoteTrustState.PINNED) continue
      val candidate = byId[hostId] ?: continue
      val currentFingerprint = candidate.meta.fingerprint ?: continue
      val prevFingerprint = prev.fingerprint ?: continue
      if (!equalFingerprint(prevFingerprint, currentFingerprint)) continue
      val slot = deviceSlot(hostId, accountName)
      val existing = runCatching { devices.load(slot) }.getOrNull()
      val tokens = existing?.tokens
      val needsRepair = existing == null ||
        existing.invalidated ||
        tokens == null ||
        tokens.refreshTokenExpiresAt <= now + refreshMarginMs ||
        tokens.accessTokenExpiresAt <= now + 60_000L
      if (!needsRepair) continue
      try {
        val manager = RemoteDeviceManager(
          devices,
          slot,
          origin,
          accountName,
          registerer = { descriptor, acctToken -> api.register(descriptor, acctToken) },
          refresher = { id, refresh -> api.refresh(id, refresh) },
        )
        manager.register(deviceName(candidate.host), platform, clientVersion, accountAccessToken)
      } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
      } catch (_: Exception) {
        // Silent background credential repair for non-selected hosts must not abort primary enrollment.
      }
    }
  }

  /** Adopts a device-only QR pairing grant into pinned trust, device slot store, and catalog. */
  suspend fun adoptPairing(
    host: RemoteHost,
    accountName: String,
    mobileIdentity: RemoteIdentity,
    tokens: RemoteTokenPair,
    label: String = deviceLabel,
  ): RemoteCatalog {
    val key = host.publicKey?.trim().orEmpty()
    require(key.isNotEmpty()) { "主机缺少身份公钥" }
    val scope = RemotePeerScope(origin, accountName, host.deviceId)
    val trust = RemotePeerTrust(pins)
    val existingPin = pins.pinned(scope)
    if (existingPin != null && !sameKey(existingPin, key)) {
      trust.replaceAfterConfirmation(scope, host, existingPin)
    } else {
      trust.pinAfterConfirmation(scope, host)
    }
    val slot = deviceSlot(host.deviceId, accountName)
    RemoteDeviceManager(devices, slot, origin, accountName).savePaired(mobileIdentity, tokens)

    val fingerprint = runCatching { RemoteSetup.fingerprint(key) }.getOrNull()
    val newMeta = RemoteHostMeta(
      hostId = host.deviceId,
      name = host.name,
      platform = host.platform,
      online = host.online,
      lastSeenAt = null,
      harnessVersion = host.harnessVersion,
      pluginVersion = null,
      fingerprint = fingerprint,
      trust = RemoteTrustState.PINNED,
    )
    val existingCatalog = catalogs.load().firstOrNull { it.origin == origin && it.account == accountName }
    val mergedHosts = existingCatalog?.hosts
      ?.filterNot { it.hostId == newMeta.hostId }
      .orEmpty() + newMeta
    val adopted = (existingCatalog?.effectiveAdoptedHostIds().orEmpty() + newMeta.hostId).toSet()
    val updated = (existingCatalog ?: RemoteCatalog(
      origin = origin,
      account = accountName,
      provider = "pairing",
      deviceLabel = label,
      hosts = mergedHosts,
      updatedAt = System.currentTimeMillis(),
      allowCleartext = allowCleartext,
      adoptedHostIds = adopted,
    )).copy(
      hosts = mergedHosts,
      updatedAt = System.currentTimeMillis(),
      allowCleartext = allowCleartext,
      signedOut = false,
      adoptedHostIds = adopted,
    )
    catalogs.save(updated)
    this.account = accountName
    return updated
  }

  suspend fun signOut(catalog: RemoteCatalog) {
    require(canonicalRemoteOrigin(catalog.origin) == origin) { "中枢地址不匹配" }
    val name = catalog.account
    // Commit local sign-out before any network work. Cancellation/unknown revoke delivery must not
    // drop metadata or permit a new discovery using a half-signed-out source after process restart.
    catalogs.save(catalog.copy(signedOut = true, hosts = catalog.hosts.map { it.copy(online = false) },
      updatedAt = System.currentTimeMillis()))
    for (host in catalog.hosts) {
      val slot = deviceSlot(host.hostId, catalog.account)
      val record = runCatching { devices.load(slot) }.getOrNull() ?: continue
      RemoteDeviceManager.invalidateSlot(slot)
      val now = System.currentTimeMillis()
      val cachedToken = record.tokens?.takeIf { !record.invalidated && it.accessTokenExpiresAt > now + 10_000L }?.accessToken
      val token = cachedToken ?: try {
        RemoteDeviceManager(
          devices,
          slot,
          origin,
          name,
          refresher = { id, refresh -> api.refresh(id, refresh) },
        ).access().tokens?.accessToken
      } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
      } catch (_: Exception) {
        null
      }
      if (token != null) try { api.revokeSelf(token) } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { }
      runCatching { devices.delete(slot) }
    }
    accountToken = null
    account = null
    candidates = emptyList()
  }

  fun cachedAccount(): String? = account ?: catalogs.load().firstOrNull { it.origin == origin }?.account

  fun cachedCandidates(): List<RemoteHostCandidate> = candidates

  private suspend fun adoptAccount(name: String, token: String, source: String, accountScoped: Boolean): List<RemoteHostCandidate> {
    account = name
    accountToken = token
    provider = source
    return loadCandidates(token, name, accountScoped)
  }

  /**
   * Account tokens only work on `/api/v1/account/devices`; `/api/v1/devices` requires a registered
   * Client device token. Using the wrong pair yields HTTP 401 AUTH_INVALID right after a successful
   * login, which the UI used to surface as a credential failure.
   */
  private suspend fun loadCandidates(token: String, name: String, accountScoped: Boolean = false): List<RemoteHostCandidate> {
    val details = if (accountScoped) {
      api.accountHosts(token)
    } else {
      api.hosts(token).map { summary ->
        try {
          api.host(token, summary.deviceId)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
          throw cancel
        }
      }
    }
    val out = details.map { candidate(it, name) }
    candidates = out
    return out
  }

  private fun candidate(detail: RemoteHost, name: String): RemoteHostCandidate {
    val fingerprint = detail.publicKey?.let { runCatching { RemoteSetup.fingerprint(it) }.getOrNull() }
    val scope = RemotePeerScope(origin, name, detail.deviceId)
    val pinned = pins.pinned(scope)
    val trust = when {
      detail.publicKey == null || pinned == null -> RemoteTrustState.UNKNOWN
      sameKey(pinned, detail.publicKey) -> RemoteTrustState.PINNED
      else -> RemoteTrustState.CHANGED
    }
    return RemoteHostCandidate(detail, RemoteHostMeta(detail.deviceId, detail.name, detail.platform, detail.online,
      lastSeenAt = null, harnessVersion = detail.harnessVersion, pluginVersion = null, fingerprint = fingerprint, trust = trust))
  }

  private suspend fun loadHosts(token: String, name: String): List<RemoteHostMeta> =
    loadCandidates(token, name, accountScoped = false).map { it.meta }

  private fun deviceSlot(hostId: String, account: String): String =
    resolveRemoteDeviceSlot(devices, origin, account, hostId)

  private fun deviceName(host: RemoteHost): String {
    val label = sanitizeRemoteDisplayName(deviceLabel, fallback = "Lagoon", maxChars = 64)
    val hostName = sanitizeRemoteDisplayName(host.name, fallback = "", maxChars = 64)
    val raw = if (hostName.isBlank()) label else "$label · $hostName"
    val truncated = truncateByCodePointsSafe(truncateUtf8Bytes(raw, 128), 128).trim()
    return truncated.ifBlank { truncateUtf8Bytes(label, 128).ifBlank { "Lagoon" } }
  }

  private fun equalFingerprint(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.lowercase().toByteArray(Charsets.UTF_8), right.lowercase().toByteArray(Charsets.UTF_8))

  private fun sameKey(left: String, right: String): Boolean = runCatching {
    MessageDigest.isEqual(Base64.getUrlDecoder().decode(left), Base64.getUrlDecoder().decode(right))
  }.getOrDefault(false)
}
