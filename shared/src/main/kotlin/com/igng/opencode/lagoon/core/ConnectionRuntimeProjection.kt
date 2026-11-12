package com.igng.opencode.lagoon.core

/**
 * Read-only parent gates for the legacy runtime API. [ServerProfile.enabled] is always the raw leaf
 * preference: graph leaf snapshots may be stale and must never overwrite it. No credentials are
 * resolved, no graph is migrated or saved, and endpoints alone never merge connection identities.
 */
object ConnectionRuntimeProjection {
  fun apply(
    profiles: List<ServerProfile>,
    graph: ConnectionGraph?,
    unavailable: Boolean = false
  ): List<ServerProfile> {
    if (unavailable) return profiles.map { it.copy(parentEnabled = false) }
    if (graph == null) return profiles.map { it.copy(parentEnabled = true) }

    // Derive identities from today's raw profiles, not the graph's possibly stale binding list.
    // In particular, a newly added sibling must inherit an existing source/Host denial immediately.
    val canonical = try { MigrationProjection.fromProfiles(profiles) } catch (_: Exception) {
      return profiles.map { it.copy(parentEnabled = false) }
    }
    val canonicalSources = canonical.sources.associateBy { it.id }
    val canonicalHosts = canonical.hosts.associateBy { it.id }
    val canonicalBindings = canonical.bindings.associateBy { it.id }
    val sources = graph.sources.associateBy { it.id }
    val hosts = graph.hosts.associateBy { it.id }
    val bindings = graph.bindings.associateBy { it.id }

    return profiles.map { profile ->
      val expectedBinding = canonicalBindings.getValue(profile.id)
      val expectedHost = canonicalHosts.getValue(expectedBinding.hostBindingId)
      val expectedSource = canonicalSources.getValue(expectedHost.sourceId)
      val source = sources[expectedSource.id]?.takeIf { sameSourceScope(it, expectedSource) }
      val host = hosts[expectedHost.id]?.takeIf {
        it.sourceId == source?.id && sameHostScope(it, expectedHost, expectedSource.type)
      }

      // Exact leaf IDs may refer to graph-authored parent IDs. Accept those parents only after
      // checking protocol/carrier AND account/config/Host scope, never merely a matching URL.
      val boundHost = bindings[profile.id]?.takeIf {
        it.protocolId == expectedBinding.protocolId && it.carrierId == expectedBinding.carrierId
      }?.let { hosts[it.hostBindingId] }
      val boundSource = boundHost?.let { sources[it.sourceId] }
      val exactParentsMatch = boundHost != null && boundSource != null &&
        sameSourceScope(boundSource, expectedSource) && sameHostScope(boundHost, expectedHost, expectedSource.type)
      val exactParentsEnabled = !exactParentsMatch ||
        ((boundSource!!.enabled || boundSource.type == ConnectionSourceType.SSH) && boundHost!!.enabled)
      // SSH is one config per device: the device switch is the only gate, never the source flag.
      val sourceEnabled = expectedSource.type == ConnectionSourceType.SSH || source?.enabled != false
      profile.copy(parentEnabled = sourceEnabled && host?.enabled != false && exactParentsEnabled)
    }
  }

  private fun sameSourceScope(actual: ConnectionSource, expected: ConnectionSource): Boolean {
    if (actual.type != expected.type) return false
    return when (expected.type) {
      ConnectionSourceType.DIRECT -> actual.id == expected.id
      ConnectionSourceType.SSH -> actual.sshHostConfigId == expected.sshHostConfigId
      ConnectionSourceType.HUB -> actual.account == expected.account &&
        runCatching { canonicalRemoteOrigin(actual.endpoint) == expected.endpoint }.getOrDefault(false)
    }
  }

  private fun sameHostScope(actual: HostBinding, expected: HostBinding, type: ConnectionSourceType): Boolean =
    when (type) {
      ConnectionSourceType.DIRECT -> actual.id == expected.id
      ConnectionSourceType.SSH, ConnectionSourceType.HUB -> actual.externalHostId == expected.externalHostId
    }
}
