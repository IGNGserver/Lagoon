package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RemoteDeletionAndRevocationTest {

  private class MemoryDeviceStore : RemoteDeviceStore {
    val slots = linkedMapOf<String, RemoteDeviceRecord>()
    val queue = mutableListOf<PendingRemoteRevocation>()

    override fun load(slot: String): RemoteDeviceRecord? = slots[slot]
    override fun save(slot: String, record: RemoteDeviceRecord) { slots[slot] = record }
    override fun delete(slot: String) {
      RemoteDeviceManager.invalidateSlot(slot)
      slots.remove(slot)
    }
    override fun listSlots(): Set<String> = slots.keys.toSet()
    override fun pendingRevocations(): List<PendingRemoteRevocation> = queue.toList()
    override fun enqueueRevocation(item: PendingRemoteRevocation) {
      queue.removeAll { it.id == item.id }
      queue += item
    }
    override fun updateRevocation(item: PendingRemoteRevocation) {
      val idx = queue.indexOfFirst { it.id == item.id }
      if (idx >= 0) queue[idx] = item
    }
    override fun removeRevocation(id: String) {
      queue.removeAll { it.id == id }
    }
  }

  private class MemoryTrustStore : RemotePeerTrustStore {
    val pins = linkedMapOf<RemotePeerScope, String>()
    override fun pinned(scope: RemotePeerScope): String? = pins[scope]
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String =
      pins.getOrPut(scope) { publicKey }
    override fun replaceIfMatches(scope: RemotePeerScope, expectedPublicKey: String, replacementPublicKey: String): Boolean {
      if (pins[scope] != expectedPublicKey) return false
      pins[scope] = replacementPublicKey
      return true
    }
    override fun remove(scope: RemotePeerScope) {
      pins.remove(scope)
    }
    override fun removeAllForAccount(origin: String, account: String) {
      pins.keys.removeAll { it.origin == origin && it.account == account }
    }
  }

  private val sampleKeyA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  private val sampleKeyB = "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  private val hostId1 = "01J00000000000000000000001"
  private val hostId2 = "01J00000000000000000000002"

  @Test fun pendingRevocationCodecRoundTripsAndRedactsTokens() {
    val item = PendingRemoteRevocation(
      id = UUID.randomUUID().toString(),
      origin = "http://192.168.1.10:8787/",
      account = "alice@example.com",
      peerHostId = hostId1,
      clientDeviceId = hostId2,
      accessToken = "secret_access_token_12345",
      refreshToken = "secret_refresh_token_67890",
      allowCleartext = true,
      createdAt = 1_700_000_000_000L,
      attempts = 2,
      lastAttemptAt = 1_700_000_005_000L
    )
    assertFalse(item.toString().contains("secret_access_token_12345"))
    assertFalse(item.toString().contains("secret_refresh_token_67890"))

    val encoded = PendingRemoteRevocationCodec.encodeList(listOf(item))
    val decoded = PendingRemoteRevocationCodec.decodeList(encoded)
    assertEquals(listOf(item), decoded)
  }

  @Test fun drainPendingRemoteRevocationsRetriesAfterOfflineAndRefreshesExpiredToken() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val origin = canonicalRemoteOrigin(server.url("/").toString())
      val store = MemoryDeviceStore()
      val item = PendingRemoteRevocation(
        id = "rev-1",
        origin = origin,
        account = "alice@example.com",
        peerHostId = hostId1,
        clientDeviceId = hostId2,
        accessToken = "old_access_token_123456",
        refreshToken = "old_refresh_token_123456",
        allowCleartext = true,
        createdAt = 1_700_000_000_000L
      )
      store.enqueueRevocation(item)

      // 1st attempt: transient 503 on deleteMembership -> stays in queue with attempts=1
      server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"UNAVAILABLE","message":"down","retryable":true}}"""))
      drainPendingRemoteRevocations(store, nowMillis = { 1_700_000_010_000L })
      assertEquals(1, store.pendingRevocations().size)
      assertEquals(1, store.pendingRevocations().first().attempts)
      val firstReq = server.takeRequest()
      assertEquals("DELETE", firstReq.method)
      assertEquals("/api/v1/device-memberships/$hostId1", firstReq.path)

      // 2nd attempt: expired token -> refresh -> deleteMembership -> revokeSelf
      server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"TOKEN_EXPIRED","message":"expired","retryable":false}}"""))
      server.enqueue(MockResponse().setBody(JSONObject()
        .put("accessToken", "new_access_token_123456")
        .put("accessTokenExpiresAt", 2_000_000_000_000L)
        .put("refreshToken", "new_refresh_token_123456")
        .put("refreshTokenExpiresAt", 2_100_000_000_000L)
        .toString()))
      server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
      server.enqueue(MockResponse().setResponseCode(204).setBody(""))

      drainPendingRemoteRevocations(store, nowMillis = { 1_700_000_020_000L })
      assertTrue("queue must empty after revokeSelf succeeds", store.pendingRevocations().isEmpty())

      assertEquals("/api/v1/device-memberships/$hostId1", server.takeRequest().path)
      val refreshReq = server.takeRequest()
      assertEquals("/api/v1/auth/refresh", refreshReq.path)
      val retryMembershipReq = server.takeRequest()
      assertEquals("/api/v1/device-memberships/$hostId1", retryMembershipReq.path)
      assertEquals("Bearer new_access_token_123456", retryMembershipReq.getHeader("Authorization"))
      val revokeSelfReq = server.takeRequest()
      assertEquals("DELETE", revokeSelfReq.method)
      assertEquals("/api/v1/devices/self", revokeSelfReq.path)
      assertEquals("Bearer new_access_token_123456", revokeSelfReq.getHeader("Authorization"))
    }
  }

  @Test fun wipeHostSlotsDeletesScopedAndMatchingLegacyWithoutTouchingOtherAccounts() {
    val origin = "https://hub.example.com/"
    val store = MemoryDeviceStore()
    val tokensA = RemoteTokenPair("acc_tok_a_12345678", 9_999_999_999_000L, "ref_tok_a_12345678", 9_999_999_999_000L)
    val tokensB = RemoteTokenPair("acc_tok_b_12345678", 9_999_999_999_000L, "ref_tok_b_12345678", 9_999_999_999_000L)

    val slotA = RemoteIdentitySlots.scoped(origin, "alice", hostId1)
    val legacyA = RemoteIdentitySlots.legacyHostSlot(hostId1)
    val slotB = RemoteIdentitySlots.scoped(origin, "bob", hostId1)
    store.save(slotA, RemoteDeviceRecord(origin, "alice", RemoteIdentity(hostId2, sampleKeyA, sampleKeyB), tokensA))
    store.save(legacyA, RemoteDeviceRecord(origin, "alice", RemoteIdentity(hostId2, sampleKeyA, sampleKeyB), tokensA))
    store.save(slotB, RemoteDeviceRecord(origin, "bob", RemoteIdentity(hostId2, sampleKeyA, sampleKeyB), tokensB))

    val wiped = RemoteIdentitySlots.wipeHostSlotsReturningTokens(store, origin, "alice", hostId1)
    assertEquals(1, wiped.size) // deduplicated by client deviceId while both slots removed
    assertNull(store.load(slotA))
    assertNull(store.load(legacyA))
    assertNotNull("other account slot for same hostId must remain untouched", store.load(slotB))
  }

  @Test fun connectionCatalogProjectionDoesNotResurrectHostRemovedFromAdoptedSet() {
    val origin = "https://hub.example.com/"
    val account = "alice"
    val sourceId = MigrationProjection.hubSourceId(origin, account)
    val source = ConnectionSource(sourceId, ConnectionSourceType.HUB, origin, origin, account)
    val h1 = HostBinding(MigrationProjection.hostId(sourceId, hostId1), sourceId, "One", hostId1, "fp1")
    val h2 = HostBinding(MigrationProjection.hostId(sourceId, hostId2), sourceId, "Two", hostId2, "fp2")
    val previous = ConnectionGraph(listOf(source), listOf(h1, h2), emptyList(), emptyList())

    val updatedCatalog = RemoteCatalog(
      origin = origin,
      account = account,
      provider = null,
      deviceLabel = "Phone",
      hosts = listOf(RemoteHostMeta(hostId2, "Two", "linux", true, null, null, null, "fp2")),
      updatedAt = 2L,
      adoptedHostIds = setOf(hostId2)
    )
    val merged = ConnectionCatalogProjection.merge(
      ConnectionGraph(),
      previous,
      emptyList(),
      listOf(updatedCatalog)
    )
    assertEquals(listOf(hostId2), merged.hosts.map { it.externalHostId })
  }

  @Test fun remotePeerTrustPinReplaceAndForgetWorkAtomically() {
    val store = MemoryTrustStore()
    val trust = RemotePeerTrust(store)
    val scope = RemotePeerScope("https://hub.example.com/", "alice", hostId1)
    val hostA = RemoteHost(hostId1, "HostA", "linux", "m1", true, sampleKeyA, null, null, "aaaaaaaaaaaa")
    val hostB = RemoteHost(hostId1, "HostA", "linux", "m1", true, sampleKeyB, null, null, "bbbbbbbbbbbb")

    assertEquals(sampleKeyA, trust.pinOrReplaceAfterConfirmation(scope, hostA))
    assertEquals(sampleKeyA, trust.verify(scope, hostA))
    assertEquals(sampleKeyB, trust.pinOrReplaceAfterConfirmation(scope, hostB))
    assertEquals(sampleKeyB, trust.verify(scope, hostB))

    trust.forget(scope)
    assertNull(store.pinned(scope))
    val afterForget = runCatching { trust.verify(scope, hostB) }.exceptionOrNull() as RemoteTrustException
    assertFalse(afterForget.changed)
  }
}
