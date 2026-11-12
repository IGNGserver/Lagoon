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
      catalog.hosts.forEach { observed ->
        val hostId = MigrationProjection.hostId(source.id, observed.hostId)
        hosts.putIfAbsent(
          hostId,
          HostBinding(hostId, source.id, observed.name, observed.hostId, observed.fingerprint)
        )
      }
    }

    val oldSources = previous?.sources.orEmpty().associateBy { it.id }
    val oldHosts = previous?.hosts.orEmpty().associateBy { it.id }
    // An omitted Host can be a partial catalog, not a deletion. Only a still-saved source keeps it;
    // projected profiles alone must not resurrect other old parents after explicit source removal.
    previous?.hosts.orEmpty().forEach { oldHost ->
      val saved = savedSources[oldHost.sourceId] ?: return@forEach
      val current = sources[oldHost.sourceId] ?: return@forEach
      val oldSource = oldSources[oldHost.sourceId] ?: return@forEach
      if (sameSourceScope(saved, current) && sameSourceScope(current, oldSource)) {
        hosts.putIfAbsent(oldHost.id, oldHost)
      }
    }

    val signedOutSources = remoteCatalogs.filter { it.signedOut }.mapTo(HashSet()) {
      MigrationProjection.hubSourceId(it.origin, it.account)
    }
    // An SSH source is one config per device, so it never carries a second gate. A legacy disabled
    // SSH source keeps its meaning by moving the denial down to the device, once.
    val disabledSshSources = oldSources.values
      .filter { it.type == ConnectionSourceType.SSH && !it.enabled }
      .mapTo(HashSet()) { it.id }
    val mergedSources = sources.values.map { source ->
      val old = oldSources[source.id]?.takeIf { sameSourceScope(source, it) }
      when {
        source.type == ConnectionSourceType.SSH -> source.copy(enabled = true)
        source.id in signedOutSources -> source.copy(enabled = false)
        old == null -> source
        else -> source.copy(enabled = old.enabled)
      }
    }
    val mergedHosts = hosts.values.map { host ->
      val source = requireNotNull(sources[host.sourceId])
      val oldSource = oldSources[host.sourceId]
      val old = oldHosts[host.id]?.takeIf {
        it.sourceId == host.sourceId && it.externalHostId == host.externalHostId &&
          oldSource != null && sameSourceScope(source, oldSource)
      }
      when {
        host.sourceId in disabledSshSources && host.enabled -> host.copy(enabled = false)
        old == null -> host
        else -> host.copy(enabled = old.enabled)
      }
    }
    return ConnectionGraph(mergedSources, mergedHosts, projected.bindings, projected.compositions)
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
