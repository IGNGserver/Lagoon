package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ParentOnlyRouteTest {
  private val source = ConnectionSource("source:exact", ConnectionSourceType.HUB, "Not an endpoint",
    "https://committed.example/base/", account = "committed-account")
  private val host = HostBinding("host:exact", source.id, "Not a fingerprint", "external:exact", "012345abcdef")
  private fun graph() = ConnectionGraph(listOf(source), listOf(host))

  @Test fun emptySshAndHubParentsAllowDiscoveryWithoutAnyHarnessOrCredential() {
    val config = SshHostConfig("ssh:config", "Saved SSH", "ssh.example.test", user = "alice")
    val remote = RemoteHostMeta("f2b08ff1-afd6-46a6-8dd4-4de1f026f60a", "Saved Host", "linux",
      false, null, null, null, "012345abcdef")
    val catalog = RemoteCatalog("https://hub.example/", "alice", null, "Device", listOf(remote), 123L)
    val projected = ConnectionCatalogProjection.merge(ConnectionGraph(), null, listOf(config), listOf(catalog))
    val fixture = Fixture(projected)
    assertTrue(projected.bindings.isEmpty())
    assertEquals(2, projected.hosts.size)
    projected.hosts.forEach { parent ->
      assertEquals(projected.sources.single { it.id == parent.sourceId } to parent,
        fixture.store.requireParentRoute(parent.sourceId, parent.id))
    }
    assertEquals(0, fixture.readOnly.edits)
  }

  @Test fun eitherDisabledParentBlocksEvenWhenNoHarnessExists() {
    val fixture = Fixture(graph())
    listOf(graph().copy(sources = listOf(source.copy(enabled = false))),
      graph().copy(hosts = listOf(host.copy(enabled = false)))).forEach { denied ->
      fixture.commit(denied)
      assertTrue(runCatching { fixture.store.requireParentRoute(source.id, host.id) }
        .exceptionOrNull() is IllegalStateException)
    }
    fixture.commit(graph())
    assertEquals(source to host, fixture.store.requireParentRoute(source.id, host.id))
  }

  @Test fun absentGraphMissingExactIdsAndWrongSourceHostLinkageFailClosed() {
    val fixture = Fixture(null)
    fixture.graphPreferences.values["graph.pending"] = ConnectionGraphCodec.encode(graph())
    fixture.graphPreferences.values["graph.backup"] = ConnectionGraphCodec.encode(graph())
    assertTrue(runCatching { fixture.store.requireParentRoute(source.id, host.id) }.isFailure)
    fixture.commit(graph())
    listOf("missing" to host.id, source.endpoint to host.id, source.id to "missing",
      source.id to host.externalHostId!!).forEach { (sourceId, hostId) ->
      assertTrue(runCatching { fixture.store.requireParentRoute(sourceId, hostId) }.isFailure)
    }
    val other = source.copy(id = "source:other")
    fixture.commit(ConnectionGraph(listOf(source, other), listOf(host.copy(sourceId = other.id))))
    assertTrue(runCatching { fixture.store.requireParentRoute(source.id, host.id) }.isFailure)
    assertEquals(0, fixture.readOnly.edits)
  }

  @Test fun corruptAndFutureGraphReadsPreserveRawPreferencesSecretsAndJournalWithoutEdits() {
    listOf("{broken", "[]", "{\"version\":999,\"opaque\":\"retain\"}").forEach { raw ->
      val fixture = Fixture(graph())
      fixture.graphPreferences.values["graph"] = raw
      listOf("graph.pending", "graph.backup", "graph.marker").forEach {
        fixture.graphPreferences.values[it] = "untouched journal bytes"
      }
      val before = fixture.bytes()
      repeat(2) {
        assertTrue(runCatching { fixture.store.requireParentRoute(source.id, host.id) }
          .exceptionOrNull() is IOException)
      }
      assertEquals(before, fixture.bytes())
      assertEquals(0, fixture.readOnly.edits)
    }
  }

  @Test fun committedParentTogglesNeverRewriteRawProfilesOrSecretBytes() {
    val fixture = Fixture(graph())
    val raw = fixture.store.readProfiles()
    val profileBytes = fixture.preferences.values.toMap()
    val secretBytes = fixture.secrets.values.toMap()
    val graphStore = ConnectionGraphStore(fixture.graphPreferences, { error("No migration allowed") })
    listOf<(Boolean) -> ConnectionGraph>(
      { graphStore.setSourceEnabled(source.id, it) }, { graphStore.setHostEnabled(host.id, it) }
    ).forEach { toggle ->
      toggle(false)
      assertTrue(runCatching { fixture.store.requireParentRoute(source.id, host.id) }.isFailure)
      toggle(true)
      assertEquals(source to host, fixture.store.requireParentRoute(source.id, host.id))
      assertEquals(raw, fixture.store.readProfiles())
      assertEquals(profileBytes, fixture.preferences.values)
      assertEquals(secretBytes, fixture.secrets.values)
    }
  }

  @Test fun returnedEndpointAccountAndFingerprintAreExactCommittedMetadataNotGuessedAliases() {
    val fixture = Fixture(graph())
    val (savedSource, savedHost) = fixture.store.requireParentRoute(source.id, host.id)
    assertEquals(source, savedSource)
    assertEquals(host, savedHost)
    assertEquals("https://committed.example/base/", savedSource.endpoint)
    assertEquals("committed-account", savedSource.account)
    assertEquals("external:exact", savedHost.externalHostId)
    assertEquals("012345abcdef", savedHost.fingerprint)
    val injected = ServerStore(FakeSharedPreferences(), FakeSharedPreferences(), { error("encrypt") },
      { error("decrypt") }, readConnectionGraph = { graph() })
    assertSame(source, injected.requireParentRoute(source.id, host.id).first)
    assertSame(host, injected.requireParentRoute(source.id, host.id).second)
  }

  private class ReadOnly(private val backing: FakeSharedPreferences) : SharedPreferences by backing {
    var edits = 0
    override fun edit(): SharedPreferences.Editor { edits++; error("Parent admission must never edit") }
  }

  private class Fixture(graph: ConnectionGraph?) {
    val preferences = FakeSharedPreferences().apply {
      values["projectedPreviewFormatVersion"] = 3
      values["profiles"] = "[{\"id\":\"raw\",\"name\":\"Raw\",\"url\":\"https://decoy.example/\",\"enabled\":false}]"
    }
    val secrets = FakeSharedPreferences().apply { values["password:raw"] = "opaque encrypted bytes" }
    val graphPreferences = FakeSharedPreferences()
    val readOnly = ReadOnly(graphPreferences)
    private val context = object : ContextWrapper(null) {
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "connection-graph") { "Parent admission must not open authentication stores" }
        return readOnly
      }
    }
    val store = ServerStore(preferences, secrets, { error("No encryption") }, { error("No decryption") }, context)
    init { graph?.let(::commit) }
    fun commit(graph: ConnectionGraph) { graphPreferences.values["graph"] = ConnectionGraphCodec.encode(graph) }
    fun bytes() = listOf(preferences.values.toMap(), secrets.values.toMap(), graphPreferences.values.toMap())
  }
}
