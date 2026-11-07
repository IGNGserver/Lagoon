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

  suspend fun refresh(): RemoteCatalog {
    val current = catalogs.load().firstOrNull { it.origin == origin && it.account == account }
      ?: throw IOException("请先登录 DSH Remote")
    val token = deviceToken(current) ?: throw IOException("DSH Remote 登录已失效，请重新登录")
    val hosts = loadHosts(token, current.account)
    val updated = current.copy(hosts = hosts, updatedAt = System.currentTimeMillis())
    catalogs.save(updated)
    return updated
  }

  /** Registers one client identity per selected Host and pins the confirmed fingerprints. */
  suspend fun enroll(selected: List<RemoteHostCandidate>, confirmed: Map<String, String>): RemoteEnrollment {
    val (name, token) = requirePending()
    if (selected.isEmpty()) throw IOException("至少选择一台主机")
    if (selected.size > 16) throw IOException("一次最多接入 16 台主机")
    val registered = LinkedHashMap<String, String>()
    val failures = LinkedHashMap<String, String>()
    val byId = candidates.associateBy { it.hostId }
    val catalogHosts = candidates.map { it.meta }.toMutableList()
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
        RemotePeerTrust(pins).pinAfterConfirmation(scope, host)
        val slot = deviceSlot(host.deviceId)
        val manager = RemoteDeviceManager(devices, slot, origin, name,
          registerer = { descriptor, accountToken -> api.register(descriptor, accountToken) },
          refresher = { id, refresh -> api.refresh(id, refresh) })
        val device = manager.register(deviceName(host), platform, clientVersion, token)
        registered[host.deviceId] = device.identity.deviceId
        val index = catalogHosts.indexOfFirst { it.hostId == host.deviceId }
        if (index >= 0) catalogHosts[index] = catalogHosts[index].copy(trust = RemoteTrustState.PINNED)
      } catch (error: Exception) {
        failures[candidate.hostId] = userFacingError(error, "接入主机失败")
      }
    }
    val catalog = RemoteCatalog(origin, name, provider, deviceLabel, catalogHosts, System.currentTimeMillis(), allowCleartext)
    catalogs.save(catalog)
    accountToken = null
    this.account = name
    return RemoteEnrollment(catalog, registered, failures)
  }

  suspend fun signOut(catalog: RemoteCatalog) {
    val name = catalog.account
    for (host in catalog.hosts) {
      val slot = deviceSlot(host.hostId)
      val record = runCatching { devices.load(slot) }.getOrNull() ?: continue
      val token = try {
        RemoteDeviceManager(devices, slot, origin, name,
          refresher = { id, refresh -> api.refresh(id, refresh) }).access().tokens?.accessToken
      } catch (_: Exception) { null }
      if (token != null) try { api.revokeSelf(token) } catch (_: Exception) { }
      runCatching { devices.delete(slot) }
    }
    catalogs.clear(origin, name)
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
      api.hosts(token).mapNotNull { runCatching { api.host(token, it.deviceId) }.getOrNull() }
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

  private suspend fun deviceToken(catalog: RemoteCatalog): String? {
    for (host in catalog.hosts) {
      val slot = deviceSlot(host.hostId)
      val record = runCatching { devices.load(slot) }.getOrNull() ?: continue
      if (record.account != catalog.account || record.origin != origin) continue
      val token = try {
        RemoteDeviceManager(devices, slot, origin, catalog.account,
          refresher = { id, refresh -> api.refresh(id, refresh) }).access().tokens?.accessToken
      } catch (_: Exception) { null }
      if (token != null) return token
    }
    return null
  }

  private fun deviceSlot(hostId: String): String = "host-${hostId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"

  private fun deviceName(host: RemoteHost): String {
    val raw = "$deviceLabel · ${host.name}".replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").trim()
    val bytes = raw.toByteArray(Charsets.UTF_8)
    if (bytes.size <= 128) return raw
    var end = raw.length
    while (end > 0 && raw.substring(0, end).toByteArray(Charsets.UTF_8).size > 128) end--
    return raw.substring(0, end).trim().ifBlank { deviceLabel }
  }

  private fun equalFingerprint(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.lowercase().toByteArray(Charsets.UTF_8), right.lowercase().toByteArray(Charsets.UTF_8))

  private fun sameKey(left: String, right: String): Boolean = runCatching {
    MessageDigest.isEqual(Base64.getUrlDecoder().decode(left), Base64.getUrlDecoder().decode(right))
  }.getOrDefault(false)
}
