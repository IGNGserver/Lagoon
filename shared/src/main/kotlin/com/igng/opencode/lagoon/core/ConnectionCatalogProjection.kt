package com.igng.opencode.lagoon.core

/** Display-only saved parents. Catalog observations never create a Harness or resolve a route. */
internal object ConnectionCatalogProjection {
  fun merge(
    projected: ConnectionGraph,
    previous: ConnectionGraph?,
    sshHosts: List<SshHostConfig>,
    remoteCatalogs: List<RemoteCatalog>
  ): ConnectionGraph {
    val sources = projected.sources.associateByTo(linkedMapOf()) { it.id }
    val hosts = projected.hosts.associateByTo(linkedMapOf()) { it.id }
    val savedSources = linkedMapOf<String, ConnectionSource>()

    sshHosts.forEach { config ->
      val source = ConnectionSource(
        id = MigrationProjection.sshSourceId(config.id),
        type = ConnectionSourceType.SSH,
        name = config.name,
        endpoint = config.profileUrl(),
        sshHostConfigId = config.id,
        allowCleartext = true
      )
      savedSources.putIfAbsent(source.id, source)
      sources.putIfAbsent(source.id, source)
      val hostId = MigrationProjection.hostId(source.id, config.id)
      hosts.putIfAbsent(
        hostId,
        HostBinding(
          id = hostId,
          sourceId = source.id,
          name = config.name,
          externalHostId = config.id,
          fingerprint = null
        )
      )
    }

    remoteCatalogs.forEach { catalog ->
      // A signed-out catalog is local source/Host metadata only; admission blocks discovery until re-login.
      val origin = canonicalRemoteOrigin(catalog.origin)
      val account = catalog.account.trim()
      val source = ConnectionSource(
        id = MigrationProjection.hubSourceId(origin, account),
        type = ConnectionSourceType.HUB,
        name = origin,
        endpoint = origin,
        account = account,
        allowCleartext = catalog.allowCleartext
      )
      savedSources.putIfAbsent(source.id, source)
      sources.putIfAbsent(source.id, source)
      val adopted = catalog.adoptedHostIds
      catalog.hosts.filter { adopted == null || it.hostId in adopted }.forEach { observed ->
        val hostId = MigrationProjection.hostId(source.id, observed.hostId)
        hosts.putIfAbsent(
          hostId,
          HostBinding(hostId, source.id, observed.name, observed.hostId, observed.fingerprint)
        )
      }
    }

    val oldSources = previous?.sources.orEmpty().associateBy { it.id }
    val oldHosts = previous?.hosts.orEmpty().associateBy { it.id }
    val adoptedBySource = remoteCatalogs.associate { catalog ->
      MigrationProjection.hubSourceId(catalog.origin, catalog.account) to catalog.adoptedHostIds
    }
    // An omitted Host can be a partial catalog, not a deletion. Only a still-saved source keeps it;
    // projected profiles alone must not resurrect other old parents after explicit source removal.
    previous?.hosts.orEmpty().forEach { oldHost ->
      val saved = savedSources[oldHost.sourceId] ?: return@forEach
      val current = sources[oldHost.sourceId] ?: return@forEach
      val oldSource = oldSources[oldHost.sourceId] ?: return@forEach
      val adopted = adoptedBySource[oldHost.sourceId]
      if (sameSourceScope(saved, current) && sameSourceScope(current, oldSource) &&
        (adopted == null || oldHost.externalHostId in adopted)
      ) {
        hosts.putIfAbsent(oldHost.id, oldHost)
      }
    }

    val signedOutSources = remoteCatalogs.filter { it.signedOut }.mapTo(HashSet()) {
      MigrationProjection.hubSourceId(it.origin, it.account)
    }
    val mergedSourcesRaw = sources.values.map { source ->
      val old = oldSources[source.id]?.takeIf { sameSourceScope(source, it) }
      if (source.id in signedOutSources) source.copy(enabled = false)
      else if (old == null) source else source.copy(enabled = old.enabled)
    }
    val mergedHostsRaw = hosts.values.map { host ->
      val source = requireNotNull(sources[host.sourceId])
      val oldSource = oldSources[host.sourceId]
      val old = oldHosts[host.id]?.takeIf {
        it.sourceId == host.sourceId && it.externalHostId == host.externalHostId &&
          oldSource != null && sameSourceScope(source, oldSource)
      }
      if (old == null) host else host.copy(enabled = old.enabled)
    }
    return normalizeSshSources(
      ConnectionGraph(mergedSourcesRaw, mergedHostsRaw, projected.bindings, projected.compositions),
      previous
    )
  }

  /**
   * An SSH source is one saved config and one device (see `docs/DEVICE_MODEL.md`): the source flag is
   * never a second gate. A legacy disabled SSH source keeps its meaning by moving the denial down to
   * its devices once; after that the source stays enabled and the device switch is the only gate.
   */
  fun normalizeSshSources(graph: ConnectionGraph, previous: ConnectionGraph? = null): ConnectionGraph {
    val disabled = (graph.sources + previous?.sources.orEmpty())
      .filter { it.type == ConnectionSourceType.SSH && !it.enabled }
      .mapTo(HashSet()) { it.id }
    if (disabled.isEmpty()) return graph
    return graph.copy(
      sources = graph.sources.map { if (it.id in disabled) it.copy(enabled = true) else it },
      hosts = graph.hosts.map { if (it.sourceId in disabled && it.enabled) it.copy(enabled = false) else it }
    )
  }

  /** IDs must match as well as scope; a name, address or fingerprint is never an identity alias. */
  private fun sameSourceScope(current: ConnectionSource, previous: ConnectionSource): Boolean {
    if (current.id != previous.id || current.type != previous.type) return false
    return when (current.type) {
      ConnectionSourceType.SSH -> current.sshHostConfigId == previous.sshHostConfigId
      ConnectionSourceType.HUB -> current.account?.trim() == previous.account?.trim() &&
        canonicalRemoteOrigin(current.endpoint) == canonicalRemoteOrigin(previous.endpoint)
      ConnectionSourceType.DIRECT -> true // Its stable source ID is scoped to the exact saved profile.
    }
  }
}
