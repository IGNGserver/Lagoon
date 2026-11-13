package com.igng.opencode.lagoon.core

/** A saved route is stale when the durable graph and the raw leaf no longer describe one route. */
internal class StaleConnectionRouteException(message: String) : IllegalStateException(message)

/** Immutable, non-secret route captured from the exact graph binding -> Host -> source chain. */
@ConsistentCopyVisibility
data class ConnectionRouteSnapshot internal constructor(
  val bindingId: String,
  val sourceId: String,
  val hostBindingId: String,
  val protocolId: HarnessProtocolId,
  val carrierId: ConnectionCarrierId,
  val endpoint: String,
  val allowCleartext: Boolean,
  val username: String,
  val dshAuth: DshAuthMode,
  val sshHostId: String?,
  val sshServicePath: String?,
  val remoteHostId: String?,
  val fingerprint: String?,
  val remoteServiceId: String?
) {
  /** Cosmetic preferences remain raw; all connection-bearing fields come from admitted graph data. */
  fun executionProfile(preferences: ServerProfile): ServerProfile = preferences.copy(
    id = bindingId, url = endpoint, allowCleartext = allowCleartext, username = username,
    kind = when (protocolId) {
      HarnessProtocolId.OPENCODE_NATIVE -> ServerKind.OPENCODE
      HarnessProtocolId.CODEX_APP_SERVER, HarnessProtocolId.ANTIGRAVITY_ACP -> ServerKind.CODEX
      HarnessProtocolId.DSH_TYPERT -> if (carrierId == ConnectionCarrierId.REMOTE_NOISE) ServerKind.DSH_REMOTE else ServerKind.DSH
    }, protocolId = protocolId.takeIf { it == HarnessProtocolId.ANTIGRAVITY_ACP }, dshAuth = dshAuth, sshHostId = sshHostId, sshServicePath = sshServicePath,
    remoteHostId = remoteHostId, remoteHostFingerprint = fingerprint, remoteServiceId = remoteServiceId
  )
}

/** Optional strict execution authority. Test stores/legacy callers can remain explicit transient routes. */
internal interface ConnectionRouteStore {
  fun runtimeRoute(expected: ServerProfile): ConnectionRouteSnapshot
}

/**
 * Execution admission is stricter than the display overlay: exact binding, full route tuple and
 * parent permission must agree. Missing/corrupt/future documents never become an automatic fallback.
 * Only a genuinely absent document uses an explicit, non-persisted legacy projection.
 */
internal object ConnectionRouteAdmission {
  fun requireCurrent(profile: ServerProfile, graph: ConnectionGraph?): ConnectionRouteSnapshot {
    fun stale(why: String): Nothing = throw StaleConnectionRouteException(
      "已保存的连接路由与连接图不一致（$why）；请重新保存连接资料后再连接"
    )
    check(profile.effectiveEnabled) { "连接或其所属接入源/主机已停用" }
    val currentGraph = graph ?: MigrationProjection.fromProfiles(listOf(profile))
    val binding = currentGraph.bindings.singleOrNull { it.id == profile.id } ?: stale("binding")
    val legacy = binding.legacyProfile ?: stale("legacy route fingerprint")
    if (!sameLeafRoute(legacy, profile)) stale("leaf")
    if (binding.protocolId != profile.protocol ||
      binding.carrierId != expectedCarrier(profile) || binding.serviceId != profile.remoteServiceId) stale("protocol/carrier/service")
    val host = currentGraph.hosts.singleOrNull { it.id == binding.hostBindingId } ?: stale("host")
    val source = currentGraph.sources.singleOrNull { it.id == host.sourceId } ?: stale("source")
    check(source.enabled && host.enabled && profile.enabled) { "连接或其所属接入源/主机已停用" }
    val expectedType = MigrationProjection.sourceType(profile)
    if (source.type != expectedType || source.allowCleartext != profile.allowCleartext) stale("source identity")
    when (expectedType) {
      ConnectionSourceType.DIRECT -> {
        if (source.endpoint != profile.url || source.account != null) stale("direct endpoint")
        NativeHttpEndpoint(source.endpoint) // rejects userinfo/query/fragment in captured locators
      }
      ConnectionSourceType.HUB -> if (source.endpoint != canonicalRemoteOrigin(profile.url) ||
        source.account != profile.username.trim() || host.externalHostId != profile.remoteHostId ||
        host.fingerprint != profile.remoteHostFingerprint || profile.sshHostId != null) stale("Hub/Host identity")
      ConnectionSourceType.SSH -> if (source.endpoint != profile.url || source.sshHostConfigId != profile.sshHostId ||
        host.externalHostId != profile.sshHostId || profile.remoteHostId != null) stale("SSH identity")
    }
    return ConnectionRouteSnapshot(binding.id, source.id, host.id, binding.protocolId, binding.carrierId,
      source.endpoint, source.allowCleartext, source.account ?: legacy.username, legacy.dshAuth,
      source.sshHostConfigId, legacy.sshServicePath,
      host.externalHostId.takeIf { source.type == ConnectionSourceType.HUB }, host.fingerprint,
      binding.serviceId)
  }

  fun sameLeafRoute(saved: ServerProfile, current: ServerProfile): Boolean {
    val sameEndpoint = if (MigrationProjection.sourceType(current) == ConnectionSourceType.HUB) {
      runCatching { canonicalRemoteOrigin(saved.url) == canonicalRemoteOrigin(current.url) }.getOrDefault(false)
    } else saved.url == current.url
    return saved.id == current.id && MigrationProjection.sourceType(saved) == MigrationProjection.sourceType(current) &&
      saved.protocol == current.protocol && sameEndpoint &&
      saved.username.trim() == current.username.trim() && saved.allowCleartext == current.allowCleartext &&
      saved.dshAuth == current.dshAuth && saved.remoteHostId == current.remoteHostId &&
      saved.remoteHostFingerprint == current.remoteHostFingerprint && saved.sshHostId == current.sshHostId &&
      saved.sshServicePath == current.sshServicePath && saved.remoteServiceId == current.remoteServiceId
  }

  private fun expectedCarrier(profile: ServerProfile) = when (MigrationProjection.sourceType(profile)) {
    ConnectionSourceType.DIRECT -> ConnectionCarrierId.DIRECT_HTTP
    ConnectionSourceType.HUB -> ConnectionCarrierId.REMOTE_NOISE
    ConnectionSourceType.SSH -> ConnectionCarrierId.SSH_FORWARD
  }
}

/** Saved execution uses the admitted carrier first, then the native protocol. No carrier fallback. */
internal fun createRoutedHarnessClient(
  route: ConnectionRouteSnapshot,
  preferences: ServerProfile,
  credentials: ServerCredentials,
  callTimeoutSeconds: Long = 60,
  onCookie: (String) -> Unit = {},
  remoteFactory: ((ServerProfile, ServerCredentials) -> RemoteBackend)? = null,
  sshFactory: ((ServerProfile, ServerCredentials) -> HarnessClient)? = null,
  dshRegistry: DshClientRegistry? = null
): HarnessClient {
  val profile = route.executionProfile(preferences)
  return when (route.carrierId) {
    ConnectionCarrierId.REMOTE_NOISE -> (remoteFactory ?: error("Remote carrier 尚未配置"))(profile, credentials).client
    ConnectionCarrierId.SSH_FORWARD -> (sshFactory ?: error("SSH carrier 尚未配置"))(profile, credentials)
    ConnectionCarrierId.DIRECT_HTTP -> when (route.protocolId) {
      HarnessProtocolId.OPENCODE_NATIVE -> OpenCodeApi(profile, credentials, callTimeoutSeconds)
      HarnessProtocolId.CODEX_APP_SERVER, HarnessProtocolId.ANTIGRAVITY_ACP -> error("此 Harness 需要 SSH 或 Remote 连接")
      HarnessProtocolId.DSH_TYPERT -> dshRegistry?.acquire(profile, credentials, callTimeoutSeconds, onCookie)
         ?: DshApi(profile, credentials, callTimeoutSeconds, onCookie)
    }
  }
}
