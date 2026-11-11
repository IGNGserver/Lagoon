package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ConnectionRouteAdmissionTest {
  private val remoteHost = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"

  private fun routes() = listOf(
    ServerProfile("direct-oc", "Direct OC", "https://oc.example.test/opencode"),
    ServerProfile("direct-dsh", "Direct DSH", "https://dsh.example.test/", username = "dsh-user",
      kind = ServerKind.DSH, dshAuth = DshAuthMode.SECRET),
    ServerProfile("ssh-oc", "SSH OC", "ssh://alice@build.example:22", username = "alice",
      sshHostId = "ssh-oc-config", sshServicePath = "/home/alice/opencode.json"),
    ServerProfile("ssh-dsh", "SSH DSH", "ssh://bob@build.example:22", username = "bob", kind = ServerKind.DSH,
      sshHostId = "ssh-dsh-config", sshServicePath = "/home/bob/dsh.json", dshAuth = DshAuthMode.SECRET),
    ServerProfile("remote-oc", "Remote OC", "https://hub.example/", username = "alice@example.test",
      remoteHostId = remoteHost, remoteHostFingerprint = "abcdef012345", remoteServiceId = "opencode-service"),
    ServerProfile("remote-dsh", "Remote DSH", "https://hub.example/", username = "alice@example.test",
      kind = ServerKind.DSH_REMOTE, remoteHostId = remoteHost, remoteHostFingerprint = "abcdef012345")
  )

  @Test fun sixProtocolCarrierPairsReturnExactGraphIdsScalarsAndExecutionProfiles() {
    val profiles = routes()
    val graph = MigrationProjection.fromProfiles(profiles)
    val protocols = listOf(HarnessProtocolId.OPENCODE_NATIVE, HarnessProtocolId.DSH_TYPERT)
    val carriers = listOf(ConnectionCarrierId.DIRECT_HTTP, ConnectionCarrierId.SSH_FORWARD, ConnectionCarrierId.REMOTE_NOISE)
    profiles.forEachIndexed { index, profile ->
      val binding = graph.bindings.single { it.id == profile.id }
      val host = graph.hosts.single { it.id == binding.hostBindingId }
      val source = graph.sources.single { it.id == host.sourceId }
      val route = ConnectionRouteAdmission.requireCurrent(profile, graph)
      assertEquals(profile.id, route.bindingId)
      assertEquals(host.id, route.hostBindingId)
      assertEquals(source.id, route.sourceId)
      assertEquals(protocols[index % 2], route.protocolId)
      assertEquals(carriers[index / 2], route.carrierId)
      assertEquals(source.endpoint, route.endpoint)
      assertEquals(profile.allowCleartext, route.allowCleartext)
      assertEquals(profile.username, route.username)
      assertEquals(profile.dshAuth, route.dshAuth)
      assertEquals(profile.sshHostId, route.sshHostId)
      assertEquals(profile.sshServicePath, route.sshServicePath)
      assertEquals(profile.remoteHostId, route.remoteHostId)
      assertEquals(profile.remoteHostFingerprint, route.fingerprint)
      assertEquals(profile.remoteServiceId, route.remoteServiceId)
      // Route-bearing preferences passed later cannot replace this admitted immutable tuple.
      val later = profile.copy(url = "https://stale.example/", username = "intruder",
        dshAuth = if (profile.dshAuth == DshAuthMode.TOKEN) DshAuthMode.SECRET else DshAuthMode.TOKEN,
        name = "Cosmetic", autoConnect = false, notifications = false)
      assertEquals(profile.copy(url = source.endpoint, name = "Cosmetic", autoConnect = false, notifications = false),
        route.executionProfile(later))
    }
  }

  @Test fun nativeDshRemoteLegacyAliasRetainsProtocolAndCarrierWhenExecutionKindNormalizes() {
    val saved = routes().last().copy(kind = ServerKind.DSH)
    val graph = MigrationProjection.fromProfiles(listOf(saved))
    val prefs = memoryPreferences()
    val store = ServerStore(prefs, memoryPreferences(), { it }, { it }, readConnectionGraph = { graph })
    store.save(saved, null)
    val execution = store.runtimeRoute(saved).executionProfile(saved)
    assertEquals(ServerKind.DSH_REMOTE, execution.kind)
    assertTrue(ConnectionRouteAdmission.sameLeafRoute(saved, execution))
    assertEquals(store.runtimeRoute(saved), store.runtimeRoute(execution))
  }

  @Test fun cosmeticPreferencesStayRawAndCurrentEnabledWinsOverStaleBindingFalse() {
    val original = routes().first()
    val current = original.copy(name = "Renamed", autoConnect = false, notifications = false)
    val graph = MigrationProjection.fromProfiles(listOf(original.copy(enabled = false))).let { saved ->
      saved.copy(bindings = saved.bindings.map { it.copy(name = "Old label", autoConnect = true, notifications = true) })
    }
    assertTrue(ConnectionRouteAdmission.sameLeafRoute(original, current))
    assertEquals(current, ConnectionRouteAdmission.requireCurrent(current, graph).executionProfile(current))
    assertTrue(runCatching { ConnectionRouteAdmission.requireCurrent(current.copy(enabled = false), graph) }.isFailure)
    assertTrue(runCatching { ConnectionRouteAdmission.requireCurrent(current.copy(parentEnabled = false), graph) }.isFailure)
    listOf(
      graph.copy(sources = graph.sources.map { it.copy(enabled = false) }),
      graph.copy(hosts = graph.hosts.map { it.copy(enabled = false) })
    ).forEach { denied -> assertTrue(runCatching { ConnectionRouteAdmission.requireCurrent(current, denied) }.isFailure) }
  }

  @Test fun directBasePathUsernameAuthAndCleartextChangesAreStale() {
    routes().take(2).forEach { saved ->
      listOf(saved.copy(url = saved.url.trimEnd('/') + "/changed"), saved.copy(username = "other"),
        saved.copy(dshAuth = if (saved.dshAuth == DshAuthMode.TOKEN) DshAuthMode.SECRET else DshAuthMode.TOKEN),
        saved.copy(allowCleartext = true)).forEach { assertStale(saved, it) }
    }
  }

  @Test fun sshPathConfigUsernameAndLocatorChangesAreStale() {
    routes().slice(2..3).forEach { saved ->
      listOf(saved.copy(sshServicePath = "/other/config.json"), saved.copy(sshHostId = "different-config"),
        saved.copy(username = "mallory"), saved.copy(url = "ssh://bob@other.example:22"))
        .forEach { assertStale(saved, it) }
    }
  }

  @Test fun remoteAccountOriginHostFingerprintAndNativeServiceChangesAreStale() {
    routes().takeLast(2).forEach { saved ->
      listOf(saved.copy(username = "other@example.test"), saved.copy(url = "https://other-hub.example/"),
        saved.copy(remoteHostId = "7737be45-6dce-4f14-99ab-77951c9a6eb1"),
        saved.copy(remoteHostFingerprint = "123456abcdef")).forEach { assertStale(saved, it) }
    }
    val native = routes()[4]
    assertStale(native, native.copy(remoteServiceId = "another-service"))
  }

  @Test fun authoredIdsAreExactMatchingUrlsNeverMergeAndSameIdWrongSourceIsStale() {
    val profiles = listOf(ServerProfile("leaf-a", "A", "https://same.example/"),
      ServerProfile("leaf-b", "B", "https://same.example/"))
    val projected = MigrationProjection.fromProfiles(profiles)
    val sourceIds = projected.sources.associate { it.id to "authored-${it.id}" }
    val hostIds = projected.hosts.associate { it.id to "authored-${it.id}" }
    val authored = ConnectionGraph(
      sources = projected.sources.map { it.copy(id = sourceIds.getValue(it.id)) },
      hosts = projected.hosts.map { it.copy(id = hostIds.getValue(it.id), sourceId = sourceIds.getValue(it.sourceId)) },
      bindings = projected.bindings.map { it.copy(hostBindingId = hostIds.getValue(it.hostBindingId)) }
    )
    val admitted = profiles.map { ConnectionRouteAdmission.requireCurrent(it, authored) }
    assertEquals(profiles.map { it.id }, admitted.map { it.bindingId })
    assertEquals(authored.sources.map { it.id }, admitted.map { it.sourceId })
    assertEquals(authored.hosts.map { it.id }, admitted.map { it.hostBindingId })
    assertEquals(2, admitted.map { it.sourceId }.distinct().size)
    val wrong = authored.copy(sources = authored.sources.map { it.copy(endpoint = "https://other.example/") })
    assertTrue(runCatching { ConnectionRouteAdmission.requireCurrent(profiles.first(), wrong) }
      .exceptionOrNull() is StaleConnectionRouteException)
  }

  @Test fun missingBindingFailsButAbsentGraphUsesExplicitLegacyProjection() {
    routes().forEach { profile ->
      assertTrue(runCatching { ConnectionRouteAdmission.requireCurrent(profile, ConnectionGraph()) }
        .exceptionOrNull() is StaleConnectionRouteException)
      val legacy = ConnectionRouteAdmission.requireCurrent(profile, null)
      assertEquals(profile.id, legacy.bindingId)
      assertEquals(profile, legacy.executionProfile(profile))
    }
  }

  @Test fun nativeEndpointUserinfoInvalidUrlsQueryAndFragmentAreRejected() {
    listOf("not a URL", "ftp://example.test/", "https://alice:secret@example.test/",
      "https://example.test/?token=secret", "https://example.test/#fragment").forEach { url ->
      routes().take(2).forEach { saved ->
        val profile = saved.copy(url = url)
        assertTrue("Expected invalid endpoint to fail: $url", runCatching {
          ConnectionRouteAdmission.requireCurrent(profile, MigrationProjection.fromProfiles(listOf(profile)))
        }.isFailure)
      }
    }
  }

  @Test fun futureCorruptAndThrowingGraphReadersPreserveRawPreferencesAndSecrets() {
    val preferences = memoryPreferences()
    val secrets = memoryPreferences()
    var rawGraph: String? = null
    var readerFailure: IOException? = null
    val store = ServerStore(preferences, secrets, { "enc:$it" }, { it.removePrefix("enc:") },
      readConnectionGraph = { readerFailure?.let { throw it }; rawGraph?.let(ConnectionGraphCodec::decode) })
    val profile = routes().first()
    store.save(profile, "private-password", cookie = "private-cookie", credentialUsername = "saved-user")
    val preferencesBefore = preferences.all.toMap()
    val secretsBefore = secrets.all.toMap()
    listOf("{broken", JSONObject().put("version", ConnectionGraphCodec.VERSION + 1).toString(), "reader-throws").forEach { raw ->
      rawGraph = raw
      readerFailure = if (raw == "reader-throws") IOException("unavailable") else null
      assertTrue(runCatching { store.runtimeRoute(profile) }.exceptionOrNull() is IOException)
      assertTrue(runCatching { store.runtimeProfile(profile) }.exceptionOrNull() is IOException)
      assertEquals(raw, rawGraph)
      assertEquals(listOf(profile), store.readProfiles())
      assertEquals(preferencesBefore, preferences.all)
      assertEquals(secretsBefore, secrets.all)
    }
    readerFailure = null
    rawGraph = null
    assertEquals(profile, store.runtimeProfile(profile))
    assertEquals(preferencesBefore, preferences.all)
    assertEquals(secretsBefore, secrets.all)
  }

  @Test fun realStoreStaleRawRouteFailsBeforeHttpThenRefreshedGraphUsesOnlyNewEndpoint() = runBlocking {
    MockWebServer().use { oldServer -> MockWebServer().use { newServer ->
      val preferences = memoryPreferences()
      val secrets = memoryPreferences()
      val old = routes().first().copy(url = oldServer.url("/old").toString(), allowCleartext = true)
      var graph: ConnectionGraph? = MigrationProjection.fromProfiles(listOf(old))
      val store = ServerStore(preferences, secrets, { it }, { it }, readConnectionGraph = { graph })
      store.save(old, "private-password", cookie = "private-cookie")
      // Change raw route without a graph refresh, preserving secret bytes exactly as written.
      val rows = org.json.JSONArray(preferences.getString("profiles", "[]"))
      val updated = old.copy(url = newServer.url("/new").toString())
      rows.getJSONObject(0).put("url", updated.url)
      preferences.edit().putString("profiles", rows.toString()).commit()
      val preferencesBefore = preferences.all.toMap()
      val secretsBefore = secrets.all.toMap()
      assertTrue(runCatching { store.runtimeRoute(old) }.exceptionOrNull() is StaleConnectionRouteException)
      assertTrue(runCatching {
        val execution = store.runtimeProfile(updated)
        OpenCodeApi(execution, ServerCredentials("opencode")).health()
      }.exceptionOrNull() is StaleConnectionRouteException)
      assertEquals(0, oldServer.requestCount)
      assertEquals(0, newServer.requestCount)
      graph = MigrationProjection.fromProfiles(listOf(updated))
      val route = store.runtimeRoute(updated)
      assertEquals(updated, route.executionProfile(updated))
      assertEquals(updated, store.runtimeProfile(updated))
      newServer.enqueue(MockResponse().setBody("""{"version":"2.0.0"}"""))
      val client = OpenCodeApi(route.executionProfile(updated), ServerCredentials("opencode"))
      try { assertEquals("2.0.0", client.health()) } finally { client.close() }
      assertEquals(0, oldServer.requestCount)
      assertEquals(1, newServer.requestCount)
      assertEquals("/new/api/info", newServer.takeRequest().path)
      assertEquals(preferencesBefore, preferences.all)
      assertEquals(secretsBefore, secrets.all)
    } }
  }

  private fun assertStale(saved: ServerProfile, changed: ServerProfile) {
    assertFalse(ConnectionRouteAdmission.sameLeafRoute(saved, changed))
    val error = runCatching {
      ConnectionRouteAdmission.requireCurrent(changed, MigrationProjection.fromProfiles(listOf(saved)))
    }.exceptionOrNull()
    assertTrue("Expected stale route: ${saved.id} -> $changed", error is StaleConnectionRouteException)
  }
}
