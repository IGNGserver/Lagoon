package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionRuntimeProjectionTest {
  private fun ssh(id: String, config: String = "ssh-config", enabled: Boolean = true) = ServerProfile(
    id, id, "ssh://alice@example.test:22", username = "alice", kind = ServerKind.DSH,
    sshHostId = config, enabled = enabled
  )

  private val firstHost = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val secondHost = "7737be45-6dce-4f14-99ab-77951c9a6eb1"

  private fun hub(id: String, account: String = "alice", host: String = firstHost, origin: String = "https://hub.example/") =
    ServerProfile(id, id, origin, username = account, kind = ServerKind.DSH_REMOTE, remoteHostId = host)

  private fun denySource(graph: ConnectionGraph, bindingId: String): ConnectionGraph {
    val host = graph.hosts.first { it.id == graph.bindings.first { it.id == bindingId }.hostBindingId }
    return graph.copy(sources = graph.sources.map { if (it.id == host.sourceId) it.copy(enabled = false) else it })
  }

  private fun denyHost(graph: ConnectionGraph, bindingId: String): ConnectionGraph {
    val hostId = graph.bindings.first { it.id == bindingId }.hostBindingId
    return graph.copy(hosts = graph.hosts.map { if (it.id == hostId) it.copy(enabled = false) else it })
  }

  @Test fun parentDenialBlocksEnabledChildWithoutChangingAnyLeafPreference() {
    val raw = listOf(ssh("enabled-child"), ssh("disabled-child", enabled = false))
    val original = MigrationProjection.fromProfiles(raw)
    val cases = listOf(denySource(original, "enabled-child") to original,
      denyHost(original, "enabled-child") to original)
    for ((deniedGraph, restoredGraph) in cases) {
      val runtime = ConnectionRuntimeProjection.apply(raw, deniedGraph)
      assertEquals(listOf(true, false), runtime.map { it.enabled })
      assertTrue(runtime.none { it.parentEnabled || it.effectiveEnabled })
      assertEquals(listOf(true, true), raw.map { it.parentEnabled })

      // Re-enabling the parents restores only children whose own preference was enabled.
      val restored = ConnectionRuntimeProjection.apply(runtime, restoredGraph)
      assertTrue(restored.all { it.parentEnabled })
      assertEquals(listOf(true, false), restored.map { it.effectiveEnabled })
    }
  }

  @Test fun staleBindingPreferenceNeverOverwritesCurrentLeafPreference() {
    val raw = listOf(ssh("enabled-child"), ssh("disabled-child", enabled = false))
    val graph = MigrationProjection.fromProfiles(raw).let { graph ->
      graph.copy(bindings = graph.bindings.map { it.copy(enabled = !it.enabled) })
    }
    val runtime = ConnectionRuntimeProjection.apply(raw, graph)
    assertEquals(raw, runtime)
    assertTrue(runtime.first().effectiveEnabled)
    assertFalse(runtime.last().effectiveEnabled)
  }

  @Test fun newSiblingsInheritSourceAndHostDenialEvenWithoutPersistedBindings() {
    val originals = listOf(ssh("ssh-old"), hub("hub-old"))
    val newSiblings = listOf(
      ssh("ssh-new").copy(kind = ServerKind.OPENCODE, sshServicePath = "/home/alice/service.json"),
      hub("hub-new")
    )
    val graph = MigrationProjection.fromProfiles(originals)
    for (denied in listOf(
      denySource(denySource(graph, "ssh-old"), "hub-old"),
      denyHost(denyHost(graph, "ssh-old"), "hub-old")
    )) {
      val runtime = ConnectionRuntimeProjection.apply(originals + newSiblings, denied)
      assertEquals(listOf("ssh-old", "hub-old", "ssh-new", "hub-new"), runtime.map { it.id })
      assertTrue(runtime.all { it.enabled })
      assertTrue(runtime.none { it.effectiveEnabled })
      assertFalse(denied.bindings.any { it.id == "ssh-new" || it.id == "hub-new" })
    }
  }

  @Test fun hubParentsAreScopedByCanonicalOriginAccountAndHost() {
    val raw = listOf(
      hub("alice-one"), hub("alice-two", host = secondHost), hub("bob-one", account = "bob"),
      hub("other-origin", origin = "https://other-hub.example/")
    )
    val graph = MigrationProjection.fromProfiles(raw)
    val sourceDenied = ConnectionRuntimeProjection.apply(raw, denySource(graph, "alice-one"))
    assertEquals(listOf(false, false, true, true), sourceDenied.map { it.effectiveEnabled })
    val hostDenied = ConnectionRuntimeProjection.apply(raw, denyHost(graph, "alice-one"))
    assertEquals(listOf(false, true, true, true), hostDenied.map { it.effectiveEnabled })

    // Canonicalization of a Hub origin does not bypass its persisted denial.
    val canonicalSibling = hub("canonical-sibling", origin = "https://HUB.example:443/")
    assertFalse(ConnectionRuntimeProjection.apply(raw + canonicalSibling, denyHost(graph, "alice-one")).last().effectiveEnabled)
  }

  @Test fun matchingUrlsNeverMergeDirectProfilesOrSshConfigScopes() {
    val direct = ServerProfile("direct-one", "Direct", "https://same.example/")
    val raw = listOf(direct, direct.copy(id = "direct-two"), ssh("ssh-one"), ssh("ssh-two", config = "other-config"))
    val graph = MigrationProjection.fromProfiles(raw)
    val runtime = ConnectionRuntimeProjection.apply(raw, denySource(denySource(graph, "direct-one"), "ssh-one"))
    assertEquals(listOf(false, true, false, true), runtime.map { it.effectiveEnabled })
  }

  @Test fun reusedBindingIdCannotApplyAnUnrelatedAccountHostOrSshConfigDenial() {
    val original = listOf(hub("hub"), ssh("ssh"))
    val graph = MigrationProjection.fromProfiles(original).let { denySource(denySource(it, "hub"), "ssh") }
    val changed = listOf(hub("hub", account = "bob"), ssh("ssh", config = "other-config"))
    assertTrue(ConnectionRuntimeProjection.apply(changed, graph).all { it.effectiveEnabled })

    val hostDenied = denyHost(MigrationProjection.fromProfiles(listOf(hub("hub"))), "hub")
    assertTrue(ConnectionRuntimeProjection.apply(listOf(hub("hub", host = secondHost)), hostDenied).single().effectiveEnabled)
  }

  @Test fun exactLeafFallbackRequiresMatchingProtocolCarrierAndParentScope() {
    val raw = listOf(hub("hub"))
    val canonical = MigrationProjection.fromProfiles(raw)
    val source = canonical.sources.single().copy(id = "authored-source", enabled = false)
    val host = canonical.hosts.single().copy(id = "authored-host", sourceId = source.id)
    val binding = canonical.bindings.single().copy(hostBindingId = host.id)
    val authored = ConnectionGraph(listOf(source), listOf(host), listOf(binding))
    assertFalse(ConnectionRuntimeProjection.apply(raw, authored).single().effectiveEnabled)
    assertTrue(ConnectionRuntimeProjection.apply(listOf(hub("hub", account = "bob")), authored).single().effectiveEnabled)
    assertTrue(ConnectionRuntimeProjection.apply(listOf(hub("hub", host = secondHost)), authored).single().effectiveEnabled)

    val sshRaw = listOf(ssh("native"))
    val sshGraph = MigrationProjection.fromProfiles(sshRaw).let { graph ->
      graph.copy(
        sources = graph.sources.map { it.copy(id = "authored-ssh", enabled = false) },
        hosts = graph.hosts.map { it.copy(id = "authored-ssh-host", sourceId = "authored-ssh") },
        bindings = graph.bindings.map { it.copy(hostBindingId = "authored-ssh-host") }
      )
    }
    assertTrue(ConnectionRuntimeProjection.apply(listOf(ssh("native").copy(kind = ServerKind.OPENCODE)), sshGraph).single().effectiveEnabled)
  }

  @Test fun missingGraphClearsOldOverlayWhileUnavailableGraphFailsClosed() {
    val raw = listOf(ssh("enabled"), ssh("disabled", enabled = false))
    val denied = ConnectionRuntimeProjection.apply(raw, graph = null, unavailable = true)
    assertEquals(listOf(true, false), denied.map { it.enabled })
    assertTrue(denied.none { it.parentEnabled || it.effectiveEnabled })
    assertEquals(raw, ConnectionRuntimeProjection.apply(denied, graph = null))
  }

  @Test fun graphCodecNeverPersistsRuntimeParentGate() {
    val profile = ssh("child").copy(parentEnabled = false)
    val graph = MigrationProjection.fromProfiles(listOf(profile))
    val encoded = ConnectionGraphCodec.encode(graph)
    val legacy = JSONObject(encoded).getJSONArray("bindings").getJSONObject(0).getJSONObject("legacyProfile")
    assertFalse(legacy.has("parentEnabled"))
    assertFalse(legacy.has("effectiveEnabled"))
    val decoded = ConnectionGraphCodec.decode(encoded).bindings.single()
    assertTrue(decoded.enabled)
    assertTrue(decoded.legacyProfile!!.enabled)
    assertTrue(decoded.legacyProfile!!.parentEnabled)
  }
}
