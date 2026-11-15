package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteAccountScopeRegressionTest {
  private val hostA = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val hostB = "b1c2d3e4-f5a6-47b8-89c0-1d2e3f4a5b6c"
  private val account = "owner@example.org"
  private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
  private val accessA = "access_a_123456789012345"
  private val accessB = "access_b_123456789012345"
  private val refreshA = "refresh_a_12345678901234"
  private val refreshB = "refresh_b_12345678901234"

  private class Devices : RemoteDeviceStore {
    val slots = linkedMapOf<String, RemoteDeviceRecord>()
    val queue = mutableListOf<PendingRemoteRevocation>()
    var rejectQueue = false
    override fun load(slot: String): RemoteDeviceRecord? = slots[slot]
    override fun save(slot: String, record: RemoteDeviceRecord) { slots[slot] = record }
    override fun delete(slot: String) { slots.remove(slot) }
    override fun listSlots(): Set<String> = slots.keys.toSet()
    override fun pendingRevocations(): List<PendingRemoteRevocation> = queue.toList()
    override fun enqueueRevocation(item: PendingRemoteRevocation) {
      if (rejectQueue) throw java.io.IOException("queue unavailable")
      queue += item
    }
    override fun updateRevocation(item: PendingRemoteRevocation) {
      val index = queue.indexOfFirst { it.id == item.id }
      if (index >= 0) queue[index] = item
    }
    override fun removeRevocation(id: String) { queue.removeAll { it.id == id } }
  }
  private class Pins : RemotePeerTrustStore {
    val pins = mutableMapOf<RemotePeerScope, String>()
    override fun pinned(scope: RemotePeerScope): String? = pins[scope]
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = pins.getOrPut(scope) { publicKey }
  }
  private class Catalog : RemoteCatalogStore {
    var catalog: RemoteCatalog? = null
    var beforeReplace: (() -> Unit)? = null
    override fun load(): List<RemoteCatalog> = listOfNotNull(catalog)
    override fun save(catalog: RemoteCatalog) { this.catalog = catalog }
    override fun clear(origin: String, account: String) { catalog = null }
    override fun replaceIfUnchanged(expected: RemoteCatalog, updated: RemoteCatalog): Boolean {
      beforeReplace?.also { beforeReplace = null; it() }
      return super.replaceIfUnchanged(expected, updated)
    }
  }
  private fun pair(access: String, refresh: String) = RemoteTokenPair(access, 4_000_000_000_000L, refresh, 4_000_000_000_000L)
  private fun host(id: String, name: String) = RemoteHost(id, name, "linux", "membership", true, key, null, null)
  private fun meta(id: String, name: String) = RemoteHostMeta(id, name, "linux", true, null, null, null, RemoteSetup.fingerprint(key), RemoteTrustState.PINNED)
  private fun hostJson(id: String, name: String, detail: Boolean) = JSONObject().put("deviceId", id).put("name", name)
    .put("platform", "linux").put("role", "host").put("membershipId", "membership").put("online", true)
    .apply { if (detail) put("identityKey", key) }
  private fun manager(origin: String, devices: Devices, catalogs: Catalog, pins: Pins = Pins()) =
    RemoteAccountManager(origin, origin.startsWith("http:"), "Lagoon", "android", "1.0", devices, pins, catalogs)

  @Test fun twoRestrictedIdentitiesAreMergedAndPartialFailurePreservesOtherHost() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val origin = canonicalRemoteOrigin(server.url("/").toString())
      val devices = Devices(); val catalogs = Catalog()
      val idA = RemoteIdentity.generate(); val idB = RemoteIdentity.generate()
      devices.save(RemoteIdentitySlots.scoped(origin, account, hostA), RemoteDeviceRecord(origin, account, idA,
        pair(accessA, refreshA), accountKind = "standard", pairingRestricted = true, enrollmentMethod = "pairing"))
      devices.save(RemoteIdentitySlots.scoped(origin, account, hostB), RemoteDeviceRecord(origin, account, idB,
        pair(accessB, refreshB), accountKind = "standard", pairingRestricted = true, enrollmentMethod = "pairing"))
      val initial = RemoteCatalog(origin, account, "password", "Phone", listOf(meta(hostA, "A"), meta(hostB, "B")), 1,
        allowCleartext = true, adoptedHostIds = setOf(hostA, hostB))
      catalogs.save(initial)
      var failB = false
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val bearer = request.getHeader("Authorization")
          val path = request.requestUrl!!.encodedPath
          if (path == "/api/v1/devices") {
            if (bearer == "Bearer $accessB" && failB) return MockResponse().setResponseCode(503)
            val id = if (bearer == "Bearer $accessA") hostA else hostB
            return MockResponse().setBody(JSONObject().put("items", org.json.JSONArray().put(hostJson(id, id, false)))
              .put("nextCursor", JSONObject.NULL).toString())
          }
          if (path == "/api/v1/devices/$hostA") return MockResponse().setBody(hostJson(hostA, "A", true).toString())
          if (path == "/api/v1/devices/$hostB") return MockResponse().setBody(hostJson(hostB, "B", true).toString())
          return MockResponse().setResponseCode(404)
        }
      }
      val manager = manager(origin, devices, catalogs).apply { resume(initial) }
      val both = manager.refresh()
      assertEquals(setOf(hostA, hostB), both.hosts.map { it.hostId }.toSet())
      assertFalse(both.directoryPartial)
      failB = true
      val partial = manager.refresh()
      assertEquals(setOf(hostA, hostB), partial.hosts.map { it.hostId }.toSet())
      assertTrue(partial.directoryPartial)
      assertNotNull(partial.directoryIssue)
    }
  }

  @Test fun oneUnrestrictedIdentityAvoidsRepeatingTheWholeDirectoryForEveryHost() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val origin = canonicalRemoteOrigin(server.url("/").toString())
      val devices = Devices(); val catalogs = Catalog()
      devices.save(RemoteIdentitySlots.scoped(origin, account, hostA), RemoteDeviceRecord(origin, account,
        RemoteIdentity.generate(), pair(accessA, refreshA), accountKind = "standard", pairingRestricted = false))
      devices.save(RemoteIdentitySlots.scoped(origin, account, hostB), RemoteDeviceRecord(origin, account,
        RemoteIdentity.generate(), pair(accessB, refreshB), accountKind = "standard", pairingRestricted = true))
      val initial = RemoteCatalog(origin, account, "password", "Phone", listOf(meta(hostB, "B"), meta(hostA, "A")), 1,
        allowCleartext = true, adoptedHostIds = setOf(hostA, hostB))
      catalogs.save(initial)
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          assertEquals("Bearer $accessA", request.getHeader("Authorization"))
          return when (request.requestUrl!!.encodedPath) {
            "/api/v1/devices" -> MockResponse().setBody(JSONObject().put("items", org.json.JSONArray()
              .put(hostJson(hostA, "A", false)).put(hostJson(hostB, "B", false))).put("nextCursor", JSONObject.NULL).toString())
            "/api/v1/devices/$hostA" -> MockResponse().setBody(hostJson(hostA, "A", true).toString())
            "/api/v1/devices/$hostB" -> MockResponse().setBody(hostJson(hostB, "B", true).toString())
            else -> MockResponse().setResponseCode(404)
          }
        }
      }
      val refreshed = manager(origin, devices, catalogs).apply { resume(initial) }.refresh()
      assertEquals(setOf(hostA, hostB), refreshed.hosts.map { it.hostId }.toSet())
      assertEquals(3, server.requestCount)
      assertFalse(refreshed.directoryPartial)
    }
  }

  @Test fun directoryRefreshCannotRestoreAnAccountSignedOutDuringTheRequest() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val origin = canonicalRemoteOrigin(server.url("/").toString())
      val devices = Devices(); val catalogs = Catalog()
      devices.save(RemoteIdentitySlots.scoped(origin, account, hostA), RemoteDeviceRecord(origin, account,
        RemoteIdentity.generate(), pair(accessA, refreshA), pairingRestricted = false))
      val initial = RemoteCatalog(origin, account, "password", "Phone", listOf(meta(hostA, "A")), 1,
        allowCleartext = true, adoptedHostIds = setOf(hostA))
      catalogs.save(initial)
      server.enqueue(MockResponse().setBody(JSONObject().put("items", org.json.JSONArray().put(hostJson(hostA, "A", false)))
        .put("nextCursor", JSONObject.NULL).toString()))
      server.enqueue(MockResponse().setBody(hostJson(hostA, "A", true).toString()))
      catalogs.beforeReplace = { catalogs.save(initial.copy(signedOut = true)) }
      val failure = runCatching { manager(origin, devices, catalogs).apply { resume(initial) }.refresh() }.exceptionOrNull()
      assertNotNull(failure)
      assertTrue(requireNotNull(catalogs.catalog).signedOut)
    }
  }

  @Test fun pairingRecoveryPreservesRotatedTokensAndMetadataAcrossCodec() = runBlocking {
    val origin = "https://hub.example/"
    val devices = Devices(); val catalogs = Catalog(); val identity = RemoteIdentity.generate()
    val slot = RemoteIdentitySlots.scoped(origin, account, hostA)
    val rotated = pair(accessB, refreshB)
    devices.save(slot, RemoteDeviceRecord(origin, account, identity, rotated,
      accountKind = "standard", pairingRestricted = true, enrollmentMethod = "pairing"))
    val manager = manager(origin, devices, catalogs)
    manager.adoptPairing(host(hostA, "A"), account, identity, pair(accessA, refreshA),
      accountKind = "standard", pairingRestricted = true)
    val saved = requireNotNull(devices.load(slot))
    assertEquals(rotated, saved.tokens)
    assertEquals(true, RemoteIdentitySlots.pairingStatus(devices, origin, account, hostA))
    val decoded = RemoteDeviceCodec.decode(RemoteDeviceCodec.encode(saved))
    assertEquals("standard", decoded.accountKind)
    assertEquals(true, decoded.pairingRestricted)
    assertEquals("pairing", decoded.enrollmentMethod)
    val legacy = JSONObject(RemoteDeviceCodec.encode(saved)).apply {
      remove("accountKind"); remove("pairingRestricted"); remove("enrollmentMethod")
    }
    val restoredLegacy = RemoteDeviceCodec.decode(legacy.toString())
    assertNull(restoredLegacy.accountKind)
    assertNull(restoredLegacy.pairingRestricted)
    assertNull(restoredLegacy.enrollmentMethod)
  }

  @Test fun replacementMustQueueOldIdentityBeforeOverwritingIt() = runBlocking {
    val origin = "https://hub.example/"
    val devices = Devices(); val catalogs = Catalog()
    val old = RemoteIdentity.generate(); val replacement = RemoteIdentity.generate()
    val slot = RemoteIdentitySlots.scoped(origin, account, hostA)
    devices.save(slot, RemoteDeviceRecord(origin, account, old, pair(accessA, refreshA)))
    val manager = manager(origin, devices, catalogs)
    devices.rejectQueue = true
    assertNotNull(runCatching { manager.adoptPairing(host(hostA, "A"), account, replacement, pair(accessB, refreshB),
      accountKind = "standard", pairingRestricted = true) }.exceptionOrNull())
    assertEquals(old.deviceId, devices.load(slot)?.identity?.deviceId)
    devices.rejectQueue = false
    manager.adoptPairing(host(hostA, "A"), account, replacement, pair(accessB, refreshB),
      accountKind = "standard", pairingRestricted = true)
    assertEquals(old.deviceId, devices.pendingRevocations().single().clientDeviceId)
    assertEquals(replacement.deviceId, devices.load(slot)?.identity?.deviceId)
  }

  @Test fun removingOneSharedLegacyIdentityRevokesOnlyItsMembership() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val origin = canonicalRemoteOrigin(server.url("/").toString())
      val devices = Devices(); val identity = RemoteIdentity.generate()
      val slotA = RemoteIdentitySlots.scoped(origin, account, hostA)
      val slotB = RemoteIdentitySlots.scoped(origin, account, hostB)
      val record = RemoteDeviceRecord(origin, account, identity, pair(accessA, refreshA))
      devices.save(slotA, record); devices.save(slotB, record)
      RemoteIdentitySlots.queueAndWipeHostSlots(devices, origin, account, hostA, true)
      assertNull(devices.load(slotA))
      assertNotNull(devices.load(slotB))
      assertTrue(devices.pendingRevocations().single().membershipOnly)
      server.enqueue(MockResponse().setResponseCode(204))
      drainPendingRemoteRevocations(devices)
      assertTrue(devices.pendingRevocations().isEmpty())
      assertEquals(1, server.requestCount)
      assertEquals("/api/v1/device-memberships/$hostA", server.takeRequest().path)
      assertNotNull(devices.load(slotB))
    }
  }
  @Test fun sharedMembershipRefreshUpdatesSurvivingIdentityBeforeRevocation() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val origin = canonicalRemoteOrigin(server.url("/").toString())
      val devices = Devices(); val identity = RemoteIdentity.generate()
      val slotA = RemoteIdentitySlots.scoped(origin, account, hostA)
      val slotB = RemoteIdentitySlots.scoped(origin, account, hostB)
      val expired = RemoteTokenPair(accessA, 1L, refreshA, 4_000_000_000_000L)
      val record = RemoteDeviceRecord(origin, account, identity, expired, enrollmentMethod = "pairing")
      devices.save(slotA, record); devices.save(slotB, record)
      RemoteIdentitySlots.queueAndWipeHostSlots(devices, origin, account, hostA, true)
      server.enqueue(MockResponse().setBody(JSONObject().put("accessToken", accessB).put("accessTokenExpiresAt", 4_000_000_000_000L)
        .put("refreshToken", refreshB).put("refreshTokenExpiresAt", 4_000_000_000_000L).toString()))
      server.enqueue(MockResponse().setResponseCode(204))
      drainPendingRemoteRevocations(devices)
      assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
      assertEquals("/api/v1/device-memberships/$hostA", server.takeRequest().path)
      assertEquals(accessB, devices.load(slotB)?.tokens?.accessToken)
      assertEquals("pairing", devices.load(slotB)?.enrollmentMethod)
      assertTrue(devices.pendingRevocations().isEmpty())
      assertEquals(2, server.requestCount)
    }
  }

  @Test fun queuedOrTransitioningIdentityIsNotResurrectedByPairingRecovery() = runBlocking {
    val origin = "https://hub.example/"
    val devices = Devices(); val catalogs = Catalog(); val identity = RemoteIdentity.generate()
    val slot = RemoteIdentitySlots.scoped(origin, account, hostA)
    val saved = RemoteDeviceRecord(origin, account, identity, pair(accessA, refreshA),
      transition = RemoteDeviceRecord.Transition.REFRESHING, transitionAt = System.currentTimeMillis(),
      accountKind = "standard", pairingRestricted = true, enrollmentMethod = "pairing")
    devices.save(slot, saved)
    val manager = manager(origin, devices, catalogs)
    assertNotNull(runCatching { manager.adoptPairing(host(hostA, "A"), account, identity, pair(accessB, refreshB),
      accountKind = "standard", pairingRestricted = true) }.exceptionOrNull())
    assertEquals(saved, devices.load(slot))
    devices.save(slot, saved.copy(transition = RemoteDeviceRecord.Transition.NONE))
    devices.enqueueRevocation(PendingRemoteRevocation("pending", origin, account, hostA, identity.deviceId,
      accessA, refreshA))
    assertNotNull(runCatching { manager.adoptPairing(host(hostA, "A"), account, identity, pair(accessB, refreshB),
      accountKind = "standard", pairingRestricted = true) }.exceptionOrNull())
    assertEquals(accessA, devices.load(slot)?.tokens?.accessToken)
  }

  @Test fun emptyLoginCanRefreshCandidatesUsingTheSameAccountSession() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val origin = canonicalRemoteOrigin(server.url("/").toString())
      val devices = Devices(); val catalogs = Catalog()
      val accountToken = "account_token_123456789012"
      server.enqueue(MockResponse().setBody(JSONObject().put("account", account).put("token", accountToken)
        .put("expiresAt", 4_000_000_000_000L).toString()))
      server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))
      server.enqueue(MockResponse().setBody(JSONObject().put("items", org.json.JSONArray().put(hostJson(hostA, "A", true)))
        .put("nextCursor", JSONObject.NULL).toString()))
      val manager = manager(origin, devices, catalogs)
      assertTrue(manager.loginWithPassword(account, "test-password").isEmpty())
      assertEquals(hostA, manager.refreshCandidates().single().hostId)
      assertEquals(hostA, manager.cachedCandidates().single().hostId)
      assertEquals(3, server.requestCount)
    }
  }

  @Test fun encryptedPairingRecoveryKeyIsNotTreatedAsDeviceSlot() {
    val prefs = FakeSharedPreferences()
    prefs.values["__pending_remote_pairing_v1__"] = "encrypted-pairing-context"
    val store = AndroidRemoteDeviceStore(prefs, { it }, { it })
    assertTrue(store.listSlots().isEmpty())
    assertEquals("encrypted-pairing-context", prefs.values["__pending_remote_pairing_v1__"])
  }

  @Test fun fullRevocationSupersedesQueuedMembershipOnlyForSameIdentity() {
    val store = AndroidRemoteDeviceStore(FakeSharedPreferences(), { it }, { it })
    val origin = "https://hub.example/"; val identity = RemoteIdentity.generate()
    val membership = PendingRemoteRevocation("membership", origin, account, hostA, identity.deviceId,
      accessA, refreshA, membershipOnly = true)
    store.enqueueRevocation(membership)
    val full = membership.copy(id = "self", peerHostId = hostB, membershipOnly = false)
    store.enqueueRevocation(full)
    assertEquals(listOf(full), store.pendingRevocations())
  }

}
