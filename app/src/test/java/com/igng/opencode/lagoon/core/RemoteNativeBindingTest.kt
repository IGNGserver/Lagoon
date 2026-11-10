package com.igng.opencode.lagoon.core

import java.lang.reflect.Proxy
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteNativeBindingTest {
  private val hostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val otherHostId = "7737be45-6dce-4f14-99ab-77951c9a6eb1"
  private val savedId = "a7df0c61-7a2e-4d40-941a-86f7f41f1e25"

  private fun native(id: String = savedId, serviceId: String = "opencode-main") = ServerProfile(
    id, "OpenCode", "https://hub.example/", username = "owner@example.test",
    kind = ServerKind.OPENCODE, remoteHostId = hostId, remoteHostFingerprint = "abc123def456",
    remoteServiceId = serviceId
  )

  private fun dsh() = native("saved-dsh").copy(name = "Host", kind = ServerKind.DSH_REMOTE, remoteServiceId = null)

  @Test fun remoteNativeProjectsAsHubNoiseOpenCodeRatherThanDirectOrDsh() {
    val profile = native()
    val graph = MigrationProjection.fromProfiles(listOf(profile))
    val source = graph.sources.single()
    val host = graph.hosts.single()
    val binding = graph.bindings.single()

    assertEquals(ConnectionSourceType.HUB, MigrationProjection.sourceType(profile))
    assertEquals(ConnectionSourceType.HUB, source.type)
    assertEquals(canonicalRemoteOrigin(profile.url), source.endpoint)
    assertEquals(profile.username, source.account)
    assertEquals(source.id, host.sourceId)
    assertEquals(hostId, host.externalHostId)
    assertEquals(profile.remoteHostFingerprint, host.fingerprint)
    assertEquals(host.id, binding.hostBindingId)
    assertEquals(savedId, binding.id)
    assertEquals(HarnessProtocolId.OPENCODE_NATIVE, binding.protocolId)
    assertEquals(ConnectionCarrierId.REMOTE_NOISE, binding.carrierId)
    assertEquals("opencode-main", binding.serviceId)
    assertEquals(ConnectionSlotRef(ConnectionSlotStore.SERVER_SECRETS, savedId), binding.credentials.profileSecret)
    assertEquals(MigrationProjection.remoteSlotRefs(profile), binding.credentials.remoteIdentity)
    val invalid = listOf<() -> ServerProfile>(
      { profile.copy(remoteServiceId = null) },
      { profile.copy(remoteHostId = null) },
      { profile.copy(kind = ServerKind.DSH_REMOTE) },
      { profile.copy(sshHostId = "ssh-source") },
      { profile.copy(remoteServiceId = "https://host.invalid/service") },
      { profile.copy(remoteServiceId = "") }
    )
    invalid.forEach { construct -> assertTrue(runCatching(construct).exceptionOrNull() is IllegalArgumentException) }
  }

  @Test fun dshAndTwoNativeLeavesShareParentsButKeepDistinctIdsAndGroupMembershipThroughCodec() {
    val profiles = listOf(dsh(), native(), native("native-two", "opencode-secondary").copy(enabled = false))
    val ids = profiles.map { it.id }
    val graph = MigrationProjection.fromProfiles(profiles,
      compositions = listOf(BindingComposition("saved-group", "Work", ids)))
    val encoded = ConnectionGraphCodec.encode(graph)
    val decoded = ConnectionGraphCodec.decode(encoded)

    assertEquals(graph, decoded)
    assertEquals(1, decoded.sources.size)
    assertEquals(1, decoded.hosts.size)
    assertEquals(ids, decoded.bindings.map { it.id })
    assertEquals(3, decoded.bindings.map { it.id }.distinct().size)
    assertEquals(listOf(HarnessProtocolId.DSH_TYPERT, HarnessProtocolId.OPENCODE_NATIVE, HarnessProtocolId.OPENCODE_NATIVE),
      decoded.bindings.map { it.protocolId })
    assertEquals(listOf(null, "opencode-main", "opencode-secondary"), decoded.bindings.map { it.serviceId })
    assertEquals(listOf(null, "opencode-main", "opencode-secondary"), decoded.bindings.map { it.legacyProfile?.remoteServiceId })
    assertEquals(ids, decoded.compositionBindings("saved-group").map { it.id })
    assertEquals(ids.take(2), decoded.compositionBindings("saved-group", enabledOnly = true).map { it.id })
    val nativeJson = JSONObject(encoded).getJSONArray("bindings").getJSONObject(1)
    assertEquals("opencode-main", nativeJson.getString("serviceId"))
    assertEquals("opencode-main", nativeJson.getJSONObject("legacyProfile").getString("remoteServiceId"))
    assertEquals(ids, MigrationProjection.compatibilityLeaves(decoded).map { it.id })
  }

  @Test fun rawStoreRoundTripKeepsSavedUuidAndAccountWithoutNativePasswordOrGeneration() {
    val preferences = memoryPreferences()
    val secrets = memoryPreferences()
    fun store() = ServerStore(preferences, secrets, { "enc:$it" }, { it.removePrefix("enc:") })
    val original = native().copy(autoConnect = false, notifications = false, enabled = false)
    store().save(dsh(), "hub-password", credentialUsername = "hub-login")
    store().save(original, password = null, credentialUsername = original.username)
    store().rename(savedId, "Renamed native")
    val expected = original.copy(name = "Renamed native")
    val reopened = store()

    assertEquals(savedId, UUID.fromString(reopened.readProfiles().last().id).toString())
    assertEquals(expected, reopened.readProfiles().last())
    assertEquals(ServerCredentials(original.username), reopened.credentials(savedId))
    assertEquals("hub-login", reopened.credentials("saved-dsh").username)
    assertEquals("hub-password", reopened.credentials("saved-dsh").password)
    assertFalse(secrets.contains(savedId))
    val raw = JSONArray(preferences.getString("profiles", "[]")).getJSONObject(1)
    assertEquals(savedId, raw.getString("id"))
    assertEquals(original.username, raw.getString("username"))
    assertEquals("opencode-main", raw.getString("remoteServiceId"))
    for (key in listOf("password", "cookie", "generation", "remoteServiceGeneration", "parentEnabled", "effectiveEnabled")) {
      assertFalse("raw native profile must not contain $key", raw.has(key))
    }
    assertFalse(preferences.getString("profiles", "")!!.contains("hub-password"))
  }

  @Test fun newlySavedNativeLeavesInheritDeniedDshSourceOrHostWithoutChangingLeafPreferences() {
    val parent = dsh()
    val graph = MigrationProjection.fromProfiles(listOf(parent))
    val deniedGraphs = listOf(
      graph.copy(sources = graph.sources.map { it.copy(enabled = false) }),
      graph.copy(hosts = graph.hosts.map { it.copy(enabled = false) })
    )
    for (denied in deniedGraphs) {
      val preferences = memoryPreferences()
      var currentGraph = denied
      val store = ServerStore(preferences, memoryPreferences(), { it }, { it }, readConnectionGraph = { currentGraph })
      val profiles = listOf(parent, native(), native("native-off", "opencode-secondary").copy(enabled = false))
      profiles.forEach { store.save(it, null) }
      val before = preferences.all.toMap()

      assertEquals(listOf(true, true, false), store.profiles().map { it.enabled })
      assertTrue(store.profiles().none { it.parentEnabled || it.effectiveEnabled })
      assertEquals(listOf(parent.id), denied.bindings.map { it.id }) // Native leaves have no persisted binding yet.
      assertEquals(profiles, store.readProfiles())
      assertEquals(before, preferences.all)
      currentGraph = graph
      assertEquals(listOf(true, true, false), store.profiles().map { it.effectiveEnabled })
      assertEquals(before, preferences.all)
    }
  }

  @Test fun sameServiceIdNeverMergesAcrossAccountOriginOrHost() {
    val profiles = listOf(
      native(),
      native("other-account").copy(username = "other@example.test"),
      native("other-origin").copy(url = "https://other-hub.example/"),
      native("other-host").copy(remoteHostId = otherHostId)
    )
    val graph = MigrationProjection.fromProfiles(profiles)
    assertEquals(3, graph.sources.size)
    assertEquals(4, graph.hosts.size)
    assertEquals(4, graph.bindings.size)
    assertEquals(4, graph.bindings.map { it.hostBindingId }.distinct().size)
    assertEquals(listOf("opencode-main"), graph.bindings.map { it.serviceId }.distinct())
    assertEquals(profiles.map { it.id }, graph.bindings.map { it.id })

    val firstHost = graph.hosts.first { it.id == graph.bindings.first().hostBindingId }
    val sourceDenied = graph.copy(sources = graph.sources.map { if (it.id == firstHost.sourceId) it.copy(enabled = false) else it })
    val hostDenied = graph.copy(hosts = graph.hosts.map { if (it.id == firstHost.id) it.copy(enabled = false) else it })
    assertEquals(listOf(false, true, true, false), ConnectionRuntimeProjection.apply(profiles, sourceDenied).map { it.effectiveEnabled })
    assertEquals(listOf(false, true, true, true), ConnectionRuntimeProjection.apply(profiles, hostDenied).map { it.effectiveEnabled })
  }

  @Test fun remoteNativeFactoryRequiresBackendAndNeverFallsBackToDirectHttp() {
    val profile = native()
    var sshCalls = 0
    val error = runCatching {
      createHarnessClient(profile, ServerCredentials("must-not-use", "native-password"), sshClientFactory = { _, _ ->
        sshCalls++
        error("Remote native must not use SSH")
      })
    }.exceptionOrNull()
    assertTrue("a valid Hub HTTPS URL must not silently become an OpenCode HTTP connection: $error", error is IllegalStateException)
    assertEquals(0, sshCalls)

    val marker = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HarnessClient::class.java)) { _, _, _ ->
      error("routing must not invoke protocol methods")
    } as HarnessClient
    val backend = allocate(RemoteBackend::class.java)
    put(backend, "harness", marker)
    assertSame(marker, createHarnessClient(profile, ServerCredentials(), remoteBackend = backend))
    val direct = createHarnessClient(profile.copy(remoteHostId = null, remoteHostFingerprint = null, remoteServiceId = null), ServerCredentials())
    try { assertTrue(direct is OpenCodeApi) } finally { direct.close() }
  }

  @Test fun rediscoveryReusesSavedRemoteUuidWithoutClobberingPreferencesOrDuplicatingLeaf() {
    val preferences = memoryPreferences()
    val secrets = memoryPreferences()
    val store = ServerStore(preferences, secrets, { "enc:$it" }, { it.removePrefix("enc:") })
    val saved = native().copy(name = "User name", enabled = false, autoConnect = false, notifications = false)
    store.save(dsh(), "hub-password", credentialUsername = "hub-login")
    store.save(saved, null)
    store.select(saved.id, "/repo", "session")
    preferences.edit().putStringSet("pinned:${saved.id}", setOf("session")).apply()
    val before = preferences.all.toMap()
    val beforeSecrets = secrets.all.toMap()
    // Existing-leaf admission needs only the real store, not Android startup or a transport.
    val controller = allocate(LagoonController::class.java)
    put(controller, "store", store)
    val observed = NativeServiceDescriptor("opencode-main", "Rediscovered name", "opencode-native", "process-generation-1",
      available = true, capabilities = setOf("http", "sse", "websocket"))

    assertEquals(saved, controller.addRemoteNativeService("saved-dsh", observed, enabled = true))
    assertEquals(saved, controller.addRemoteNativeService("saved-dsh", observed.copy(generation = "process-generation-2"), enabled = true))
    assertEquals(listOf("saved-dsh", savedId), store.readProfiles().map { it.id })
    assertEquals(2, store.readProfiles().map { it.id }.distinct().size)
    assertEquals(before, preferences.all)
    assertEquals(beforeSecrets, secrets.all)
    assertEquals("hub-login", store.credentials("saved-dsh").username)
    assertEquals("hub-password", store.credentials("saved-dsh").password)
    assertEquals(ServerCredentials(saved.username), store.credentials(savedId))
    assertFalse(preferences.all.toString().contains("process-generation"))
  }

  private fun <T> allocate(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
  }

  private fun put(target: Any, name: String, value: Any) {
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
  }
}
