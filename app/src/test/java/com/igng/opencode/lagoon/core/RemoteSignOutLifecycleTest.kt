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
import java.util.concurrent.atomic.AtomicReference

class RemoteSignOutLifecycleTest {
  private val account = "user@example.org"
  private val hostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val omittedId = "a8f582fb-f662-49c6-b7f0-456a797a5302"
  private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
  private val access = "device-access-token-123"
  private val refresh = "device-refresh-token-123"
  private val accountToken = "account-token-123456789"

  private class Devices : RemoteDeviceStore {
    val values = mutableMapOf<String, RemoteDeviceRecord>()
    override fun load(slot: String): RemoteDeviceRecord? = values[slot]
    override fun save(slot: String, record: RemoteDeviceRecord) { values[slot] = record }
    override fun delete(slot: String) { values.remove(slot) }
  }
  private class Pins : RemotePeerTrustStore {
    val values = mutableMapOf<RemotePeerScope, String>()
    override fun pinned(scope: RemotePeerScope): String? = values[scope]
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = values.getOrPut(scope) { publicKey }
  }
  private class Catalog : RemoteCatalogStore {
    @Volatile var value: RemoteCatalog? = null
    override fun load(): List<RemoteCatalog> = listOfNotNull(value)
    override fun save(catalog: RemoteCatalog) { value = catalog }
    override fun clear(origin: String, account: String) { value = null }
  }
  private fun saved(origin: String, hosts: Boolean = true) = RemoteCatalog(origin, account, "password", "Lagoon",
    if (hosts) listOf(RemoteHostMeta(hostId, "Mac", "darwin", true, 123L, "0.2.1", "0.6.2",
      RemoteSetup.fingerprint(key), RemoteTrustState.PINNED)) else emptyList(), 123L, allowCleartext = true)
  private fun pair() = RemoteTokenPair(access, 4_000_000_000_000L, refresh, 4_000_000_000_000L)
  private fun record(origin: String, name: String = account) = RemoteDeviceRecord(origin, name, RemoteIdentity.generate(), pair())
  private fun manager(mock: MockWebServer, devices: Devices, pins: Pins, catalogs: Catalog) =
    RemoteAccountManager(mock.url("/").toString(), true, "Lagoon", "android", "1.0", devices, pins, catalogs)
  private fun merge(catalog: RemoteCatalog, previous: ConnectionGraph? = null) =
    ConnectionCatalogProjection.merge(ConnectionGraph(), previous, emptyList(), listOf(catalog))

  @Test fun signOutRetainsMetadataBeforeOneRevokeAndDeletesOnlyItsCredentialScope() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val devices = Devices(); val pins = Pins(); val catalogs = Catalog()
      val before = saved(mock.url("/").toString()).also(catalogs::save)
      val slot = scopedRemoteDeviceSlot(before.origin, account, hostId)
      devices.save(slot, record(before.origin))
      val otherAccount = scopedRemoteDeviceSlot(before.origin, "other@example.org", hostId)
      val otherOrigin = scopedRemoteDeviceSlot("https://other.example/", account, hostId)
      devices.save(otherAccount, record(before.origin, "other@example.org"))
      devices.save(otherOrigin, record("https://other.example/"))
      val untouched = devices.values.filterKeys { it != slot }
      pins.pinIfAbsent(RemotePeerScope(before.origin, account, hostId), key)
      val originalPins = pins.values.toMap()
      val atRevoke = AtomicReference<RemoteCatalog?>()
      mock.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          atRevoke.set(catalogs.value)
          return MockResponse().setResponseCode(204)
        }
      }
      val started = System.currentTimeMillis()
      manager(mock, devices, pins, catalogs).signOut(before)
      val retained = requireNotNull(catalogs.value)
      assertEquals(before.copy(signedOut = true, hosts = before.hosts.map { it.copy(online = false) },
        updatedAt = retained.updatedAt), retained)
      assertTrue(retained.updatedAt in started..System.currentTimeMillis())
      assertEquals(retained, atRevoke.get())
      assertEquals(untouched, devices.values)
      assertEquals(originalPins, pins.values)
      assertFalse(RemoteCatalogCodec.encode(retained).contains("token", ignoreCase = true))
      assertEquals(1, mock.requestCount)
      val request = mock.takeRequest()
      assertEquals("DELETE", request.method)
      assertEquals("/api/v1/devices/self", request.path)
      assertEquals("Bearer $access", request.getHeader("Authorization"))
    }
  }

  @Test fun emptySourceSignOutKeepsAllSourceMetadataWithoutNetworkOrCredentials() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val devices = Devices(); val pins = Pins(); val catalogs = Catalog()
      val before = saved(mock.url("/").toString(), hosts = false).also(catalogs::save)
      manager(mock, devices, pins, catalogs).signOut(before)
      val after = requireNotNull(catalogs.value)
      assertEquals(before.copy(signedOut = true, updatedAt = after.updatedAt), after)
      assertTrue(after.updatedAt >= before.updatedAt)
      assertTrue(devices.values.isEmpty())
      assertTrue(pins.values.isEmpty())
      assertEquals(0, mock.requestCount)
    }
  }

  @Test fun disabledParentFlagsAndOmittedHostSurviveSignOutAndExplicitRelogin() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val devices = Devices(); val pins = Pins(); val catalogs = Catalog()
      val original = saved(mock.url("/").toString())
      val before = original.copy(hosts = original.hosts + original.hosts.single().copy(hostId = omittedId, name = "Omitted"))
      catalogs.save(before)
      val projected = merge(before)
      val previous = projected.copy(hosts = projected.hosts.mapIndexed { index, host -> host.copy(enabled = index == 0) })
      val disabled = previous.copy(sources = previous.sources.map { it.copy(enabled = false) })
      val manager = manager(mock, devices, pins, catalogs)
      manager.signOut(before)
      val signedOut = requireNotNull(catalogs.value)
      assertTrue(signedOut.signedOut)
      val signedOutGraph = merge(signedOut, previous)
      assertEquals(disabled, signedOutGraph)
      assertEquals(0, mock.requestCount)
      mock.enqueue(MockResponse().setBody(JSONObject().put("account", account).put("token", accountToken)
        .put("expiresAt", 4_000_000_000_000L).toString()))
      mock.enqueue(MockResponse().setBody("""{"items":[{"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true,"identityKey":"$key"}]}"""))
      mock.enqueue(MockResponse().setBody(JSONObject().put("accessToken", access).put("accessTokenExpiresAt", 4_000_000_000_000L)
        .put("refreshToken", refresh).put("refreshTokenExpiresAt", 4_000_000_000_000L).toString()))
      val candidate = manager.loginWithPassword(account, "pw").single()
      val enrollment = manager.enroll(listOf(candidate), mapOf(hostId to candidate.meta.fingerprint!!))
      assertTrue(enrollment.failures.isEmpty())
      assertFalse(enrollment.catalog.signedOut)
      assertEquals(listOf(hostId), enrollment.catalog.hosts.map { it.hostId })
      assertEquals(disabled, merge(enrollment.catalog, signedOutGraph))
      assertEquals(enrollment.catalog, catalogs.value)
      assertEquals(3, mock.requestCount)
    }
  }

  @Test fun foreignOriginSignOutAndSignedOutResumeOrRefreshRejectWithoutRequests() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val devices = Devices(); val pins = Pins(); val catalogs = Catalog()
      val active = saved(mock.url("/").toString()).also(catalogs::save)
      val signingOut = manager(mock, devices, pins, catalogs)
      assertNotNull(runCatching { signingOut.signOut(active.copy(origin = "https://other.example/")) }.exceptionOrNull())
      assertEquals(active, catalogs.value)
      val resumed = manager(mock, devices, pins, catalogs).apply { resume(active) }
      signingOut.signOut(active)
      val signedOut = requireNotNull(catalogs.value)
      assertTrue(signedOut.signedOut)
      // A stale credential cannot bypass the signed-out catalog's admission gate.
      val slot = scopedRemoteDeviceSlot(active.origin, account, hostId)
      val stale = record(active.origin).also { devices.save(slot, it) }
      assertNotNull(runCatching { signingOut.resume(signedOut) }.exceptionOrNull())
      assertNotNull(runCatching { resumed.refresh() }.exceptionOrNull())
      assertSame(stale, devices.load(slot))
      assertEquals(signedOut, catalogs.value)
      assertEquals(0, mock.requestCount)
    }
  }
}
