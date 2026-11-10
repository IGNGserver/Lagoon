package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ServerStoreConnectionRuntimeTest {
  /** All preference reads work normally; an accidental runtime graph edit fails the test at once. */
  private class ReadOnlyPreferences(private val backing: FakeSharedPreferences) : SharedPreferences by backing {
    var edits = 0
    val reads = mutableListOf<String>()
    override fun edit(): SharedPreferences.Editor {
      edits++
      error("Runtime graph reader must not edit/migrate/recover preferences")
    }
    override fun contains(key: String): Boolean {
      reads += key
      return backing.contains(key)
    }
    override fun getString(key: String, defValue: String?): String? {
      reads += key
      return backing.getString(key, defValue)
    }
  }

  private class GraphContext(val graph: FakeSharedPreferences = FakeSharedPreferences()) : ContextWrapper(null) {
    val readOnlyGraph = ReadOnlyPreferences(graph)
    val opened = mutableListOf<String>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
      opened += name
      check(name == "connection-graph") { "Runtime gate must not open credentials or other stores" }
      assertEquals(Context.MODE_PRIVATE, mode)
      return readOnlyGraph
    }
  }

  private fun newStore(
    preferences: FakeSharedPreferences = FakeSharedPreferences(),
    secrets: FakeSharedPreferences = FakeSharedPreferences(),
    context: Context? = null,
    graphReader: (() -> ConnectionGraph?)? = null
  ) = ServerStore(preferences, secrets, { "enc:$it" }, { it.removePrefix("enc:") }, context, graphReader)

  private fun profile(id: String = "child", enabled: Boolean = true) = ServerProfile(
    id, id, "https://direct.example/", enabled = enabled
  )

  private fun denyParents(graph: ConnectionGraph): ConnectionGraph = graph.copy(
    sources = graph.sources.map { it.copy(enabled = false) }, hosts = graph.hosts.map { it.copy(enabled = false) }
  )

  @Test fun profilesIsRuntimeOnlyWhileRawReaderAndLeafWritersNeverReadTheGraph() {
    val preferences = FakeSharedPreferences()
    val secrets = FakeSharedPreferences()
    val raw = profile()
    var graph: ConnectionGraph? = null
    var graphReads = 0
    val store = newStore(preferences, secrets, graphReader = { graphReads++; graph })
    store.save(raw, "secret")
    graph = denyParents(MigrationProjection.fromProfiles(listOf(raw)))
    graphReads = 0

    assertEquals(listOf(raw), store.readProfiles())
    assertEquals(0, graphReads)
    val runtime = store.profiles().single()
    assertTrue(runtime.enabled)
    assertFalse(runtime.parentEnabled)
    assertFalse(runtime.effectiveEnabled)
    assertEquals(1, graphReads)

    store.rename(raw.id, "Renamed")
    store.setProfileEnabled(raw.id, false)
    assertEquals(1, graphReads) // Both writers read only raw profiles.
    assertEquals(raw.copy(name = "Renamed", enabled = false), store.readProfiles().single())
    assertFalse(store.profiles().single().effectiveEnabled)
    assertTrue(store.setProfileEnabled(raw.id, true))
    assertTrue(store.readProfiles().single().enabled)
    assertFalse(store.profiles().single().effectiveEnabled)
    assertEquals("secret", store.credentials(raw.id).password)
  }

  @Test fun savingRuntimeCopyDoesNotPersistParentGateOrClobberDisabledSiblingPreference() {
    val preferences = FakeSharedPreferences()
    val secrets = FakeSharedPreferences()
    val raw = listOf(profile("enabled"), profile("disabled", enabled = false))
    var graph: ConnectionGraph? = null
    val store = newStore(preferences, secrets, graphReader = { graph })
    raw.forEach { store.save(it, "secret-${it.id}") }
    graph = denyParents(MigrationProjection.fromProfiles(raw))
    val projected = store.profiles().first { it.id == "enabled" }
    assertFalse(projected.parentEnabled)

    store.save(projected.copy(name = "Renamed"), password = null)
    store.rename("disabled", "Still disabled")
    val document = JSONArray(preferences.getString("profiles", "[]"))
    document.objects().forEach {
      assertFalse(it.has("parentEnabled"))
      assertFalse(it.has("effectiveEnabled"))
    }
    val stored = store.readProfiles().associateBy { it.id }
    assertTrue(stored.getValue("enabled").enabled)
    assertFalse(stored.getValue("disabled").enabled)
    assertTrue(stored.values.all { it.parentEnabled })
    assertEquals("secret-enabled", store.credentials("enabled").password)
    assertEquals("secret-disabled", store.credentials("disabled").password)

    val reopened = newStore(preferences, secrets, graphReader = { graph })
    assertTrue(reopened.profiles().none { it.effectiveEnabled })
    graph = MigrationProjection.fromProfiles(reopened.readProfiles())
    assertEquals(mapOf("enabled" to true, "disabled" to false), reopened.profiles().associate { it.id to it.effectiveEnabled })
    assertEquals(setOf("enabled", "disabled"), reopened.readProfiles().map { it.id }.toSet())
  }

  @Test fun newlySavedSshSiblingInheritsDisabledParentBeforeGraphBindingExists() {
    val preferences = FakeSharedPreferences()
    val old = ServerProfile("old", "DSH", "ssh://alice@example.test:22", username = "alice",
      kind = ServerKind.DSH, sshHostId = "saved-config")
    val graph = denyParents(MigrationProjection.fromProfiles(listOf(old)))
    val store = newStore(preferences, graphReader = { graph })
    store.save(old, null)
    val sibling = old.copy(id = "new", name = "OpenCode", kind = ServerKind.OPENCODE, sshServicePath = "/home/alice/service.json")
    store.save(sibling, null)

    assertEquals(listOf(old, sibling), store.readProfiles())
    assertTrue(store.profiles().all { it.enabled })
    assertTrue(store.profiles().none { it.parentEnabled || it.effectiveEnabled })
    assertEquals(listOf("old"), graph.bindings.map { it.id })
  }

  @Test fun androidDefaultReadsOnlyCommittedPrimaryAndNeverMigratesOrRecoversGraph() {
    val context = GraphContext()
    val preferences = FakeSharedPreferences()
    val store = newStore(preferences, context = context)
    val raw = profile()
    store.save(raw, null)
    val denied = ConnectionGraphCodec.encode(denyParents(MigrationProjection.fromProfiles(listOf(raw))))
    context.graph.values["graph"] = denied
    // Poisoned journal keys would cause ConnectionGraphStore.loadOrMigrate() to fail/recover.
    context.graph.values["graph.marker"] = "not-json"
    context.graph.values["graph.pending"] = "not-json"
    context.graph.values["graph.backup"] = "not-json"
    val before = context.graph.values.toMap()
    repeat(3) {
      assertFalse(store.profiles().single().effectiveEnabled)
      assertEquals(listOf(raw), store.readProfiles())
    }
    assertEquals(before, context.graph.values)
    assertEquals(0, context.readOnlyGraph.edits)
    assertTrue(context.opened.all { it == "connection-graph" })
    assertTrue(context.readOnlyGraph.reads.all { it == "graph" })
  }

  @Test fun missingPrimaryMeansNoGateAndDoesNotPromotePendingOrBackupDocuments() {
    val context = GraphContext()
    val preferences = FakeSharedPreferences()
    val store = newStore(preferences, context = context)
    val raw = profile()
    store.save(raw, null)
    val denied = ConnectionGraphCodec.encode(denyParents(MigrationProjection.fromProfiles(listOf(raw))))
    context.graph.values["graph.pending"] = denied
    context.graph.values["graph.backup"] = denied
    context.graph.values["graph.marker"] = "not-json"
    val before = context.graph.values.toMap()
    repeat(2) { assertEquals(raw, store.profiles().single()) }
    assertEquals(before, context.graph.values)
    assertFalse(context.graph.contains("graph"))
    assertEquals(0, context.readOnlyGraph.edits)
  }

  @Test fun corruptAndFuturePrimaryFailClosedWithoutChangingRawLeavesCredentialsOrGraph() {
    for (rawGraph in listOf("{broken", "[]", "", "{\"version\":999,\"opaque\":\"keep-me\"}")) {
      val context = GraphContext()
      val preferences = FakeSharedPreferences()
      val secrets = FakeSharedPreferences()
      val store = newStore(preferences, secrets, context)
      val raw = listOf(profile("enabled"), profile("disabled", enabled = false))
      raw.forEach { store.save(it, "secret-${it.id}") }
      context.graph.values["graph"] = rawGraph
      val originalProfiles = preferences.getString("profiles", null)
      val originalSecrets = secrets.values.toMap()
      val originalGraph = context.graph.values.toMap()
      val runtime = store.profiles()
      assertEquals(listOf(true, false), runtime.map { it.enabled })
      assertTrue(runtime.none { it.parentEnabled || it.effectiveEnabled })
      assertEquals(raw, store.readProfiles())
      assertEquals(originalProfiles, preferences.getString("profiles", null))
      assertEquals(originalSecrets, secrets.values)
      assertEquals(originalGraph, context.graph.values)

      // Read-time failure must not prohibit editing a healthy raw profile document.
      store.save(runtime.first().copy(name = "Renamed"), password = null)
      assertTrue(store.setProfileEnabled("disabled", true))
      store.rename("disabled", "Preference retained")
      assertEquals(listOf(true, true), store.readProfiles().map { it.enabled })
      assertTrue(store.profiles().none { it.effectiveEnabled })
      assertEquals("secret-enabled", store.credentials("enabled").password)
      assertEquals(originalSecrets, secrets.values)
      assertEquals(originalGraph, context.graph.values)
      assertEquals(0, context.readOnlyGraph.edits)
    }
  }

  @Test fun injectedUnavailableGraphAlsoPreservesRawPreferences() {
    val preferences = FakeSharedPreferences()
    var unavailable = false
    val store = newStore(preferences, graphReader = { if (unavailable) throw IOException("unreadable graph") else null })
    val raw = profile()
    store.save(raw, null)
    unavailable = true
    assertFalse(store.profiles().single().effectiveEnabled)
    assertTrue(store.profiles().single().enabled)
    assertEquals(raw, store.readProfiles().single())
    unavailable = false
    assertEquals(raw, store.profiles().single())
  }

  @Test fun unavailableGraphNeverDialsEvenWhenRawLeafIsEnabledAndExplicitlyIncluded() = runBlocking {
    MockWebServer().use { server ->
      val context = GraphContext()
      val preferences = FakeSharedPreferences()
      val store = newStore(preferences, context = context)
      val raw = profile().copy(url = server.url("/").toString(), allowCleartext = true)
      store.save(raw, null)
      context.graph.values["graph"] = "{broken"
      val source = store.readProfiles().single()
      assertTrue(source.enabled)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, OfflineCache(FakeSharedPreferences(), { it }, { it }), scope,
        { snapshots[it.serverId] = it })
      try {
        manager.start(store.profiles(), include = setOf(raw.id))
        delay(350)
        assertEquals("Unreadable parent gates must suppress every runtime request", 0, server.requestCount)
        assertNull(snapshots[raw.id])
        assertEquals(source, store.readProfiles().single())
      } finally {
        manager.stop()
        scope.cancel()
      }
    }
  }
}
