package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConnectionGraphStoreTest {
  private val hubHost = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"

  private fun profiles(): List<ServerProfile> = listOf(
    ServerProfile("direct", "Direct", "http://127.0.0.1:4096"),
    ServerProfile("ssh", "SSH", "ssh://alice@example.test:22", username = "alice", kind = ServerKind.DSH, sshHostId = "ssh-config"),
    ServerProfile(
      "hub", "Hub", "https://hub.example/", username = "alice@example.test", kind = ServerKind.DSH_REMOTE,
      remoteHostId = hubHost, remoteHostFingerprint = "fingerprint", enabled = false
    )
  )

  @Test fun firstLoadMigratesProfilesAndRerunIsByteAndIdempotent() {
    val preferences = FakeSharedPreferences()
    var sourceProfiles = profiles()
    val store = ConnectionGraphStore(preferences, { sourceProfiles })

    val first = store.loadOrMigrate() as ConnectionGraphStore.LoadResult.Loaded
    val bytes = preferences.values["graph"] as String
    assertTrue(first.migrated)
    assertEquals(sourceProfiles.map { it.id }, first.graph.bindings.map { it.id })
    assertEquals(first.graph, ConnectionGraphCodec.decode(bytes))

    sourceProfiles = sourceProfiles.map { it.copy(name = "changed") }
    val second = store.loadOrMigrate() as ConnectionGraphStore.LoadResult.Loaded
    assertFalse(second.migrated)
    assertEquals(bytes, preferences.values["graph"])
    assertEquals(first.graph, second.graph)
    assertTrue(preferences.values.keys.none { it.endsWith("pending") || it.endsWith("marker") })
  }

  @Test fun corruptGraphBlocksSaveAndLeavesRawDocumentUntouched() {
    val preferences = FakeSharedPreferences()
    val raw = "{not-json"
    preferences.values["graph"] = raw
    val store = ConnectionGraphStore(preferences, ::profiles)

    assertTrue(runCatching { store.loadOrMigrate() }.exceptionOrNull() is IOException)
    assertTrue(runCatching { store.save(MigrationProjection.fromProfiles(profiles())) }.exceptionOrNull() is IOException)
    assertEquals(raw, preferences.values["graph"])
  }

  @Test fun futureGraphVersionIsUnsupportedAndPreserved() {
    val preferences = FakeSharedPreferences()
    val future = JSONObject().put("version", ConnectionGraphCodec.VERSION + 100).put("opaque", "keep-me").toString()
    preferences.values["graph"] = future
    val store = ConnectionGraphStore(preferences, ::profiles)

    val result = store.loadOrMigrate()
    assertTrue(result is ConnectionGraphStore.LoadResult.Unsupported)
    assertEquals(ConnectionGraphCodec.VERSION + 100L, (result as ConnectionGraphStore.LoadResult.Unsupported).version)
    assertTrue(runCatching { store.save(MigrationProjection.fromProfiles(profiles())) }.exceptionOrNull() is ConnectionGraphStore.UnsupportedGraphVersionException)
    assertEquals(future, preferences.values["graph"])
  }

  @Test fun suppliedCompositionsSurviveMigrationWithoutChangingLeafIds() {
    val preferences = FakeSharedPreferences()
    val compositions = listOf(BindingComposition("group-1", "Pinned", listOf("hub", "direct", "hub")))
    val store = ConnectionGraphStore(preferences, ::profiles, { compositions })
    val graph = (store.loadOrMigrate() as ConnectionGraphStore.LoadResult.Loaded).graph

    assertEquals(listOf("hub", "direct"), graph.compositionBindings("group-1").map { it.id })
    assertEquals(profiles().map { it.id }, graph.bindings.map { it.id })
  }

  @Test fun directSshAndHubCredentialReferencesAreExactAndPersisted() {
    val graph = (ConnectionGraphStore(FakeSharedPreferences(), ::profiles).loadOrMigrate()
      as ConnectionGraphStore.LoadResult.Loaded).graph
    val direct = graph.bindings.first { it.id == "direct" }
    val ssh = graph.bindings.first { it.id == "ssh" }
    val hub = graph.bindings.first { it.id == "hub" }

    assertEquals(ConnectionSlotRef(ConnectionSlotStore.SERVER_SECRETS, "direct"), direct.credentials.profileSecret)
    assertEquals(
      listOf("ssh:ssh-config:password", "ssh:ssh-config:privateKey", "ssh:ssh-config:passphrase"),
      ssh.credentials.sshSecrets.map { it.key }
    )
    assertEquals(ConnectionSlotStore.SERVER_SECRETS, hub.credentials.profileSecret?.store)
    assertEquals("hub", hub.credentials.profileSecret?.key)
    assertEquals(ConnectionSlotStore.REMOTE_DEVICES, hub.credentials.remoteIdentity?.candidates?.first()?.store)
    assertEquals(3, hub.credentials.remoteIdentity?.candidates?.size)

    val persisted = ConnectionGraphCodec.decode(ConnectionGraphCodec.encode(graph))
    assertEquals(graph, persisted)
  }

  @Test fun clearRemovesOnlyGraphJournalKeys() {
    val preferences = FakeSharedPreferences()
    preferences.values["legacy"] = "must-stay"
    val store = ConnectionGraphStore(preferences, ::profiles)
    store.loadOrMigrate()
    store.clear()

    assertEquals("must-stay", preferences.values["legacy"])
    assertFalse(preferences.values.containsKey("graph"))
  }
}
