package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCatalogProjectionTest {
  private val remoteHostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"

  @Test fun emptySavedSshAndHubSourcesHaveDisplayMetadataButNoFakeLeaves() {
    val config = sshConfig().copy(hostKeyFingerprint = "pinned-ssh-key")
    val catalog = catalog(origin = " HTTPS://HUB.EXAMPLE:443 ", account = " alice@example.test ",
      hosts = listOf(remoteHost()))
    val emptyCatalog = catalog(account = "empty@example.test")

    val graph = merge(ConnectionGraph(), sshHosts = listOf(config), catalogs = listOf(catalog, emptyCatalog))

    assertEquals(3, graph.sources.size)
    assertEquals(2, graph.hosts.size)
    assertTrue(graph.bindings.isEmpty())
    assertTrue(graph.compositions.isEmpty())
    assertTrue(MigrationProjection.compatibilityLeaves(graph).isEmpty())
    val ssh = graph.sources.single { it.type == ConnectionSourceType.SSH }
    assertEquals(MigrationProjection.sshSourceId(config.id), ssh.id)
    assertEquals(config.name, ssh.name)
    assertEquals(config.profileUrl(), ssh.endpoint)
    assertEquals(config.id, ssh.sshHostConfigId)
    assertTrue(ssh.allowCleartext)
    val sshHost = graph.hosts.single { it.sourceId == ssh.id }
    assertEquals(MigrationProjection.hostId(ssh.id, config.id), sshHost.id)
    assertEquals(config.id, sshHost.externalHostId)
    assertEquals(config.name, sshHost.name)
    assertNull(sshHost.fingerprint) // SSH trust is not Remote route evidence.
    val hub = graph.sources.single { it.account == "alice@example.test" }
    assertEquals("https://hub.example/", hub.endpoint)
    assertEquals(hub.endpoint, hub.name)
    assertEquals(MigrationProjection.hubSourceId(catalog.origin, catalog.account), hub.id)
    assertEquals("012345abcdef", graph.hosts.single { it.sourceId == hub.id }.fingerprint)
    assertTrue(graph.sources.all { it.enabled })
    assertTrue(graph.hosts.all { it.enabled })
  }

  @Test fun disabledParentFlagsSurviveLastLeafDeletionAndAddingNewSiblings() {
    val config = sshConfig()
    val catalog = catalog(hosts = listOf(remoteHost()))
    val firstProjection = MigrationProjection.fromProfiles(listOf(sshProfile("old-ssh", config), hubProfile("old-hub", catalog)))
    val previous = merge(firstProjection, sshHosts = listOf(config), catalogs = listOf(catalog)).let {
      it.copy(sources = it.sources.map { source -> source.copy(enabled = false) },
        hosts = it.hosts.map { host -> host.copy(enabled = false) })
    }
    val withoutLeaves = merge(ConnectionGraph(), previous, listOf(config), listOf(catalog))
    assertTrue(withoutLeaves.bindings.isEmpty())
    assertEquals(previous.sources.map { it.id }, withoutLeaves.sources.map { it.id })
    assertEquals(previous.hosts.map { it.id }, withoutLeaves.hosts.map { it.id })
    assertTrue(withoutLeaves.sources.none { it.enabled })
    assertTrue(withoutLeaves.hosts.none { it.enabled })

    val siblings = listOf(
      sshProfile("new-ssh-a", config, "/services/a.json"),
      sshProfile("new-ssh-b", config, "/services/b.json"),
      hubProfile("new-hub-a", catalog),
      hubProfile("new-hub-b", catalog)
    )
    val projected = MigrationProjection.fromProfiles(siblings)
    val restored = merge(projected, withoutLeaves, listOf(config), listOf(catalog))
    assertSame(projected.bindings, restored.bindings)
    assertTrue(restored.bindings.all { it.enabled })
    assertTrue(restored.bindings.none { restored.effectiveEnabled(it.id) })
    assertTrue(restored.sources.none { it.enabled })
    assertTrue(restored.hosts.none { it.enabled })
    assertEquals(siblings.map { it.id }, restored.bindings.map { it.id })
  }

  @Test fun sameKindHarnessPathsAndSameAddressSshConfigsNeverChangeLeafIdentity() {
    val config = sshConfig()
    val otherConfig = config.copy(id = "other-saved-config")
    val profiles = listOf(
      sshProfile("native-a", config, "/services/one.json"),
      sshProfile("native-b", config, "/services/two.json"),
      sshProfile("native-c", otherConfig, "/services/one.json")
    )
    val projected = MigrationProjection.fromProfiles(profiles)
    val first = merge(projected, sshHosts = listOf(config, otherConfig))
    val reversedProjection = MigrationProjection.fromProfiles(profiles.reversed().map { it.copy(name = "Renamed ${it.id}") })
    val changed = merge(reversedProjection, first, listOf(otherConfig, config))

    assertEquals(2, first.sources.size)
    assertEquals(2, first.hosts.size)
    assertEquals(profiles.map { it.id }, first.bindings.map { it.id })
    assertEquals(first.sources.map { it.id }.toSet(), changed.sources.map { it.id }.toSet())
    assertEquals(first.hosts.map { it.id }.toSet(), changed.hosts.map { it.id }.toSet())
    assertEquals(first.bindings.associate { it.id to it.hostBindingId }, changed.bindings.associate { it.id to it.hostBindingId })
    assertEquals(profiles.associate { it.id to it.sshServicePath }, changed.bindings.associate { it.id to it.legacyProfile?.sshServicePath })
    assertTrue(changed.bindings.all { it.protocolId == HarnessProtocolId.OPENCODE_NATIVE })
    assertSame(reversedProjection.bindings, changed.bindings)
  }

  @Test fun fullRawIdsMatchMigrationExactlyWithoutDuplicateParentsOrMetadataReplacement() {
    val config = sshConfig().copy(id = "ssh/raw:full-ID.with+punctuation_and-suffix")
    val catalog = catalog(hosts = listOf(remoteHost()))
    val profiles = listOf(
      sshProfile("raw-leaf:ssh-01", config), hubProfile("raw-leaf:hub-02", catalog),
      ServerProfile("raw-leaf:direct-03", "Direct leaf", "https://direct.example/")
    )
    val projected = MigrationProjection.fromProfiles(profiles,
      compositions = listOf(BindingComposition("all:raw-ID", "Saved group", listOf(profiles[1].id, profiles[0].id))))
    val previous = projected.copy(
      sources = projected.sources.map { source ->
        source.copy(name = "stale source", enabled = false,
          endpoint = if (source.type == ConnectionSourceType.DIRECT) "https://old-direct.example/" else source.endpoint)
      },
      hosts = projected.hosts.map { it.copy(name = "stale Host", fingerprint = "stale", enabled = false) }
    )
    val graph = merge(projected, previous,
      listOf(config.copy(name = "catalog-only name", host = "changed.example.test")),
      listOf(catalog.copy(hosts = listOf(remoteHost().copy(name = "catalog-only Host", fingerprint = "fedcba987654")))))

    assertEquals(projected.sources.map { it.id }, graph.sources.map { it.id })
    assertEquals(projected.hosts.map { it.id }, graph.hosts.map { it.id })
    assertEquals(projected.sources.map { it.copy(enabled = false) }, graph.sources)
    assertEquals(projected.hosts.map { it.copy(enabled = false) }, graph.hosts)
    val sshSource = graph.sources.single { it.type == ConnectionSourceType.SSH }
    assertEquals(MigrationProjection.sshSourceId(config.id), sshSource.id)
    assertEquals(config.id, graph.hosts.single { it.sourceId == sshSource.id }.externalHostId)
    val hubSource = graph.sources.single { it.type == ConnectionSourceType.HUB }
    assertEquals(MigrationProjection.hostId(hubSource.id, remoteHostId), graph.hosts.single { it.sourceId == hubSource.id }.id)
    assertSame(projected.bindings, graph.bindings)
    assertSame(projected.compositions, graph.compositions)
  }

  @Test fun hubAccountsAndOriginsStaySeparateAndFlagsCannotCrossSourceOrHostScope() {
    val catalogs = listOf(
      catalog(hosts = listOf(remoteHost())),
      catalog(account = "bob@example.test", hosts = listOf(remoteHost())),
      catalog(origin = "https://other.example/", hosts = listOf(remoteHost()))
    )
    val first = merge(ConnectionGraph(), catalogs = catalogs)
    assertEquals(3, first.sources.size)
    assertEquals(3, first.hosts.size)
    assertEquals(3, first.sources.map { it.id }.distinct().size)
    assertEquals(3, first.hosts.map { it.id }.distinct().size)

    val wrongScope = first.copy(
      sources = first.sources.mapIndexed { index, source -> when (index) {
        0 -> source.copy(account = "another-account", enabled = false)
        1 -> source.copy(endpoint = "https://moved.example/", enabled = false)
        else -> source
      } },
      hosts = first.hosts.mapIndexed { index, host -> when (index) {
        2 -> host.copy(externalHostId = "another-external-host", enabled = false)
        else -> host.copy(enabled = false)
      } }
    )
    val merged = merge(ConnectionGraph(), wrongScope, catalogs = catalogs)
    assertTrue(merged.sources.all { it.enabled })
    assertTrue(merged.hosts.all { it.enabled })
    assertEquals(first, merged)
  }

  @Test fun omittedHostsRemainDisplayOnlyUnderSavedSourceAndCurrentSshRouteReplacesStaleMetadata() {
    val config = sshConfig()
    val otherHost = remoteHost().copy(hostId = "5fd854ca-ecdd-4652-8546-d7869f5d305e", name = "Omitted Host")
    val originalCatalog = catalog(hosts = listOf(remoteHost(), otherHost))
    val previous = merge(ConnectionGraph(), sshHosts = listOf(config), catalogs = listOf(originalCatalog)).let {
      it.copy(sources = it.sources.map { source -> source.copy(enabled = false) },
        hosts = it.hosts.map { host -> host.copy(enabled = false) })
    }
    val changedConfig = config.copy(name = "Moved saved SSH", host = "new.example.test", port = 2222, user = "new-user")
    val partialCatalog = originalCatalog.copy(hosts = listOf(remoteHost().copy(name = "Current Host", fingerprint = "fedcba987654")))

    val merged = merge(ConnectionGraph(), previous, listOf(changedConfig), listOf(partialCatalog))

    assertTrue(merged.bindings.isEmpty())
    val ssh = merged.sources.single { it.type == ConnectionSourceType.SSH }
    assertEquals(changedConfig.name, ssh.name)
    assertEquals(changedConfig.profileUrl(), ssh.endpoint)
    assertTrue(ssh.allowCleartext)
    assertFalse(ssh.enabled)
    val sshHost = merged.hosts.single { it.sourceId == ssh.id }
    assertEquals(changedConfig.name, sshHost.name)
    assertNull(sshHost.fingerprint)
    assertFalse(sshHost.enabled)
    val oldHost = previous.hosts.single { it.externalHostId == otherHost.hostId }
    assertEquals(oldHost, merged.hosts.single { it.id == oldHost.id })
    val currentHost = merged.hosts.single { it.externalHostId == remoteHostId }
    assertEquals("Current Host", currentHost.name)
    assertEquals("fedcba987654", currentHost.fingerprint)
    assertFalse(currentHost.enabled)
  }

  @Test fun removingSavedSourceDropsEmptyParentsButKeepsParentsOfRealProjectedBindings() {
    val config = sshConfig()
    val catalog = catalog(hosts = listOf(remoteHost(), remoteHost().copy(hostId = "omitted-other-host")))
    val previous = merge(ConnectionGraph(), sshHosts = listOf(config), catalogs = listOf(catalog))

    assertEquals(ConnectionGraph(), merge(ConnectionGraph(), previous))
    val projected = MigrationProjection.fromProfiles(listOf(sshProfile("real-ssh", config), hubProfile("real-hub", catalog)))
    val realProfilesRemain = merge(projected, previous)
    assertEquals(projected, realProfilesRemain)
    assertEquals(2, realProfilesRemain.hosts.size)
    assertFalse(realProfilesRemain.hosts.any { it.externalHostId == "omitted-other-host" })
  }

  @Test fun mergedGraphCodecRoundTripAndRepeatedMergeKeepExactPreferencesAndReferences() {
    val config = sshConfig()
    val catalog = catalog(origin = "http://hub.example/", hosts = listOf(remoteHost())).copy(allowCleartext = true)
    val profile = sshProfile("native:kept-exact", config).copy(enabled = false, autoConnect = false, notifications = false)
    val projected = MigrationProjection.fromProfiles(listOf(profile),
      compositions = listOf(BindingComposition("group-exact", "Only real leaves", listOf(profile.id, profile.id))))
    val merged = merge(projected, sshHosts = listOf(config), catalogs = listOf(catalog))
    val disabled = merged.copy(sources = merged.sources.map { it.copy(enabled = false) },
      hosts = merged.hosts.map { it.copy(enabled = false) })
    val encoded = ConnectionGraphCodec.encode(disabled)
    val decoded = ConnectionGraphCodec.decode(encoded)

    assertEquals(disabled, decoded)
    assertEquals(encoded, ConnectionGraphCodec.encode(decoded))
    assertEquals(decoded, merge(projected, decoded, listOf(config), listOf(catalog)))
    assertSame(projected.bindings, merged.bindings)
    assertSame(projected.compositions, merged.compositions)
    assertEquals(listOf(profile), merged.bindings.map { it.legacyProfile })
    assertEquals(1, merged.bindings.size)
    assertTrue(merged.sources.single { it.type == ConnectionSourceType.HUB }.allowCleartext)
    assertTrue(projected.sources.all { it.enabled }) // Neither input graph was mutated.
    assertTrue(merged.sources.all { it.enabled })
  }

  private fun merge(
    projected: ConnectionGraph,
    previous: ConnectionGraph? = null,
    sshHosts: List<SshHostConfig> = emptyList(),
    catalogs: List<RemoteCatalog> = emptyList()
  ): ConnectionGraph = ConnectionCatalogProjection.merge(projected, previous, sshHosts, catalogs)

  private fun sshConfig(): SshHostConfig = SshHostConfig(
    id = "saved-ssh-config", name = "Saved SSH", host = "ssh.example.test", user = "alice"
  )

  private fun sshProfile(id: String, config: SshHostConfig, path: String = "/services/opencode.json"): ServerProfile = ServerProfile(
    id = id, name = "Projected $id", url = config.profileUrl(), username = config.user,
    kind = ServerKind.OPENCODE, sshHostId = config.id, sshServicePath = path, allowCleartext = true
  )

  private fun catalog(
    origin: String = "https://hub.example/",
    account: String = "alice@example.test",
    hosts: List<RemoteHostMeta> = emptyList()
  ): RemoteCatalog = RemoteCatalog(origin, account, null, "This device", hosts, 123L)

  private fun remoteHost(): RemoteHostMeta = RemoteHostMeta(
    remoteHostId, "Observed Host", "linux", false, null, null, null, "012345abcdef"
  )

  private fun hubProfile(id: String, catalog: RemoteCatalog): ServerProfile = ServerProfile(
    id = id, name = "Projected $id", url = canonicalRemoteOrigin(catalog.origin), username = catalog.account.trim(),
    kind = ServerKind.DSH_REMOTE, remoteHostId = remoteHostId, remoteHostFingerprint = "012345abcdef",
    allowCleartext = catalog.allowCleartext
  )
}
