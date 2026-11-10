package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ConnectionGraphTest {
  private val hostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"

  private fun profiles(): List<ServerProfile> = listOf(
    ServerProfile("direct-1", "OpenCode", "http://127.0.0.1:4096", enabled = true),
    ServerProfile("ssh-1", "SSH DSH", "ssh://alice@example.test:22", username = "alice", kind = ServerKind.DSH, sshHostId = "ssh-host-1"),
    ServerProfile("remote-1", "Remote DSH", "https://hub.example/", username = "owner@example.org",
      kind = ServerKind.DSH_REMOTE, remoteHostId = hostId, remoteHostFingerprint = "abc123", enabled = false)
  )

  private fun graph(): ConnectionGraph = MigrationProjection.fromProfiles(
    profiles(), compositions = listOf(BindingComposition("all", "All", listOf("remote-1", "direct-1", "remote-1")))
  ).let { graph ->
    val binding = graph.bindings.first { it.id == "remote-1" }
    val status = HarnessDiscoveryStatus(
      installed = DiscoveryValue.YES, configured = DiscoveryValue.YES, listening = DiscoveryValue.YES,
      authenticated = DiscoveryValue.YES, compatible = DiscoveryValue.YES, available = DiscoveryValue.YES,
      observedAtMs = 100, freshUntilMs = 200, completeness = DiscoveryCompleteness.COMPLETE, reason = "health ok"
    )
    ConnectionGraph(graph.sources, graph.hosts, graph.bindings.map { if (it.id == binding.id) it.copy(discovery = status) else it }, graph.compositions)
  }

  @Test fun codecRoundTripRetainsGraphAndExactLegacyBindingIds() {
    val original = graph()
    val decoded = ConnectionGraphCodec.decode(ConnectionGraphCodec.encode(original))
    assertEquals(original, decoded)
    assertEquals(listOf("remote-1", "direct-1"), decoded.compositionBindings("all").map { it.id })
    assertEquals("remote-1", decoded.bindings.first { it.id == "remote-1" }.legacyProfile?.id)
    assertEquals(DiscoveryFreshness.FRESH, decoded.bindings.first { it.id == "remote-1" }.discovery.freshness(150))
    assertEquals(DiscoveryFreshness.STALE, decoded.bindings.first { it.id == "remote-1" }.discovery.freshness(201))
  }

  @Test fun corruptUnknownAndDuplicateDocumentsAreRejectedWithoutFallback() {
    val encoded = ConnectionGraphCodec.encode(graph())
    val corrupt = listOf("{broken", encoded + " trailing", "[]")
    corrupt.forEach { raw -> assertTrue(runCatching { ConnectionGraphCodec.decode(raw) }.exceptionOrNull() is IOException) }

    val unknown = JSONObject(encoded).apply {
      getJSONArray("bindings").getJSONObject(0).put("protocolId", "future-protocol")
    }.toString()
    assertTrue(runCatching { ConnectionGraphCodec.decode(unknown) }.exceptionOrNull() is IOException)

    val duplicate = JSONObject(encoded).apply {
      val sources = getJSONArray("sources")
      sources.put(sources.getJSONObject(0))
    }.toString()
    assertTrue(runCatching { ConnectionGraphCodec.decode(duplicate) }.exceptionOrNull() is IOException)
  }

  @Test fun effectiveEnabledIsSourceHostBindingHierarchy() {
    val original = graph()
    val source = original.sources.first { it.type == ConnectionSourceType.DIRECT }
    val host = original.hosts.first { it.sourceId == source.id }
    assertTrue(original.effectiveEnabled("direct-1"))
    val hostDisabled = ConnectionGraph(original.sources, original.hosts.map { if (it.id == host.id) it.copy(enabled = false) else it }, original.bindings, original.compositions)
    assertFalse(hostDisabled.effectiveEnabled("direct-1"))
    val sourceDisabled = ConnectionGraph(original.sources.map { if (it.id == source.id) it.copy(enabled = false) else it }, original.hosts, original.bindings, original.compositions)
    assertFalse(sourceDisabled.effectiveEnabled("direct-1"))
    assertFalse(original.effectiveEnabled("remote-1")) // migrated profile preference is disabled
    assertTrue(original.effectiveEnabled("ssh-1"))
  }

  @Test fun migrationRetainsProfileIdsAndExactCredentialSlotReferences() {
    val migrated = MigrationProjection.fromProfiles(profiles())
    assertEquals(profiles().map { it.id }, migrated.bindings.map { it.id })
    val direct = migrated.bindings.first { it.id == "direct-1" }
    assertEquals(ConnectionSlotRef(ConnectionSlotStore.SERVER_SECRETS, "direct-1"), direct.credentials.profileSecret)
    val ssh = migrated.bindings.first { it.id == "ssh-1" }
    assertEquals(
      listOf("ssh:ssh-host-1:password", "ssh:ssh-host-1:privateKey", "ssh:ssh-host-1:passphrase"),
      ssh.credentials.sshSecrets.map { it.key }
    )
    val remote = migrated.bindings.first { it.id == "remote-1" }
    val refs = requireNotNull(remote.credentials.remoteIdentity)
    assertFalse(refs.resolved)
    assertEquals(3, refs.candidates.size)
    assertEquals("remote-1", remote.legacyProfile?.id)
    val selected = MigrationProjection.fromProfiles(profiles(), mapOf("remote-1" to refs.candidates[1].key))
      .bindings.first { it.id == "remote-1" }.credentials.remoteIdentity?.selected
    assertEquals(refs.candidates[1], selected)
  }

  @Test fun migrationIdsAreStableAcrossOrderAndDisplayChanges() {
    val first = MigrationProjection.fromProfiles(profiles())
    val changed = MigrationProjection.fromProfiles(profiles().reversed().map { it.copy(name = "renamed ${it.id}") })
    assertEquals(first.sources.map { it.id }.toSet(), changed.sources.map { it.id }.toSet())
    assertEquals(first.hosts.map { it.id }.toSet(), changed.hosts.map { it.id }.toSet())
    assertEquals(first.bindings.map { it.id }, changed.bindings.map { it.id }.reversed())
  }

  @Test fun migrationNeverMergesDirectProfilesBySameUrlAndRejectsDuplicateLegacyIds() {
    val duplicates = profiles() + profiles().first().copy(name = "same endpoint, different legacy id", id = "direct-2")
    val migrated = MigrationProjection.fromProfiles(duplicates)
    assertEquals(4, migrated.bindings.size)
    assertEquals(2, migrated.sources.count { it.type == ConnectionSourceType.DIRECT })
    assertTrue(runCatching { MigrationProjection.fromProfiles(profiles() + profiles().first()) }.isFailure)
  }

  @Test fun sshNativeHarnessesShareHostButKeepLeafIdsAndLocators() {
    val dsh = profiles().first { it.sshHostId != null }
    val native = dsh.copy(id = "ssh-opencode", name = "OpenCode", kind = ServerKind.OPENCODE,
      sshServicePath = "/home/alice/.local/state/opencode/service.json", enabled = false)
    val graph = MigrationProjection.fromProfiles(listOf(dsh, native))
    assertEquals(1, graph.sources.size)
    assertEquals(1, graph.hosts.size)
    assertEquals(listOf(dsh.id, native.id), graph.bindings.map { it.id })
    assertEquals(listOf(HarnessProtocolId.DSH_TYPERT, HarnessProtocolId.OPENCODE_NATIVE), graph.bindings.map { it.protocolId })
    assertTrue(graph.effectiveEnabled(dsh.id)); assertFalse(graph.effectiveEnabled(native.id))
    assertEquals(graph, ConnectionGraphCodec.decode(ConnectionGraphCodec.encode(graph)))
  }

  @Test fun availabilityRequiresVerifiedObservationAndReasonSurvivesCodec() {
    assertTrue(runCatching { HarnessDiscoveryStatus(available = DiscoveryValue.YES) }.isFailure)
    val unavailable = HarnessDiscoveryStatus(available = DiscoveryValue.NO, reason = "not listening")
    assertEquals(DiscoveryFreshness.UNKNOWN, unavailable.freshness(50))
    assertEquals("health ok", graph().bindings.first { it.id == "remote-1" }.discovery.reason)
  }
}
