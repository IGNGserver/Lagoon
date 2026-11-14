package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteAccountManagerTest {
  private val hostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val clientId = "8a1b0c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d"
  private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
  private val accountToken = "account-token-123456789"
  private val access = "device-access-token-123"
  private val refresh = "device-refresh-token-123"

  private class Devices : RemoteDeviceStore {
    val values = mutableMapOf<String, RemoteDeviceRecord>()
    override fun load(slot: String): RemoteDeviceRecord? = values[slot]
    override fun save(slot: String, record: RemoteDeviceRecord) { values[slot] = record }
    override fun delete(slot: String) { values.remove(slot) }
  }

  private class Pins : RemotePeerTrustStore {
    val values = mutableMapOf<String, String>()
    override fun pinned(scope: RemotePeerScope): String? = values["${scope.origin}|${scope.account}|${scope.hostId}"]
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String =
      values.getOrPut("${scope.origin}|${scope.account}|${scope.hostId}") { publicKey }
  }

  private class Catalog : RemoteCatalogStore {
    var value: RemoteCatalog? = null
    override fun load(): List<RemoteCatalog> = listOfNotNull(value)
    override fun save(catalog: RemoteCatalog) { value = catalog }
    override fun clear(origin: String, account: String) { value = null }
  }

  private fun pair() = JSONObject().put("accessToken", access).put("accessTokenExpiresAt", 1900000000000L)
    .put("refreshToken", refresh).put("refreshTokenExpiresAt", 1900000001000L)

  private fun loginBody() = JSONObject().put("account", "user@example.org").put("token", accountToken)
    .put("expiresAt", 1900000000000L).toString()

  private fun accountDirectory() = """{"items":[
    {"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true,"identityKey":"$key","harnessVersion":"0.2.1"},
    {"deviceId":"$clientId","name":"Phone","platform":"android","role":"client","membershipId":"member","online":false,"identityKey":"$key"}
  ]}"""

  /**
   * Account tokens are only accepted by /api/v1/account/devices. /api/v1/devices requires a
   * registered Client token and used to return 401 AUTH_INVALID right after a valid password login.
   */
  @Test fun passwordLoginDiscoversFromAccountDirectoryAndEnrollsOneHostWithHostStableSlot() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(accountDirectory()))
      mock.enqueue(MockResponse().setBody(pair().toString()))

      val devices = Devices()
      val catalog = Catalog()
      val manager = RemoteAccountManager(mock.url("/").toString(), true, "Lagoon · Pixel", "android", "1.0",
        devices, Pins(), catalog)
      val candidates = manager.loginWithPassword("user@example.org", "pw")
      val host = candidates.single()
      assertEquals(hostId, host.hostId)
      assertEquals(RemoteSetup.fingerprint(key), host.meta.fingerprint)
      assertTrue(host.canEnroll)

      val result = manager.enroll(listOf(host), mapOf(hostId to host.meta.fingerprint!!))
      assertTrue(result.failures.isEmpty())
      assertEquals(setOf(hostId), result.registered.keys)
      assertNotNull(devices.values[scopedRemoteDeviceSlot(mock.url("/").toString(), "user@example.org", hostId)])
      assertEquals(RemoteTrustState.PINNED, catalog.value!!.hosts.single().trust)

      assertEquals("/api/v1/auth/login", mock.takeRequest().path)
      val directory = mock.takeRequest()
      assertEquals("/api/v1/account/devices", directory.path)
      assertEquals("Bearer $accountToken", directory.getHeader("Authorization"))
      val registration = mock.takeRequest()
      assertEquals("/api/v1/devices/register", registration.path)
      assertEquals("Bearer $accountToken", registration.getHeader("Authorization"))
    }
  }

  @Test fun failedRegistrationIsReportedWithoutPublishingRegistration() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(accountDirectory()))
      mock.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
      val devices = Devices()
      val manager = RemoteAccountManager(mock.url("/").toString(), true, "Lagoon", "android", "1.0", devices, Pins(), Catalog())
      val host = manager.loginWithPassword("user@example.org", "pw").single()
      val result = manager.enroll(listOf(host), mapOf(hostId to host.meta.fingerprint!!))
      assertTrue(result.registered.isEmpty())
      assertTrue(result.failures.containsKey(hostId))
      val retryable = devices.values[scopedRemoteDeviceSlot(mock.url("/").toString(), "user@example.org", hostId)]
      assertNotNull(retryable)
      assertNull(retryable?.tokens)
    }
  }

  @Test fun detailFailureDoesNotPublishPartialCandidateCatalog() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(accountDirectory()))
      mock.enqueue(MockResponse().setBody(pair().toString()))
      mock.enqueue(MockResponse().setBody("""{"items":[{"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true}],"nextCursor":null}"""))
      mock.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
      val devices = Devices()
      val catalogs = Catalog()
      val manager = RemoteAccountManager(mock.url("/").toString(), true, "Lagoon", "android", "1.0", devices, Pins(), catalogs)
      val initial = manager.loginWithPassword("user@example.org", "pw")
      assertEquals(hostId, initial.single().hostId)
      assertTrue(manager.enroll(initial, mapOf(hostId to initial.single().meta.fingerprint!!)).failures.isEmpty())
      val failure = runCatching { manager.refresh() }.exceptionOrNull()
      assertNotNull(failure)
      assertEquals(hostId, manager.cachedCandidates().single().hostId)
    }
  }

  @Test fun refreshUsesRegisteredClientTokenDirectory() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(accountDirectory()))
      mock.enqueue(MockResponse().setBody(pair().toString()))
      mock.enqueue(MockResponse().setBody("""{"items":[{"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true}],"nextCursor":null}"""))
      mock.enqueue(MockResponse().setBody("""{"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true,"identityKey":"$key"}"""))

      val manager = RemoteAccountManager(mock.url("/").toString(), true, "Lagoon · Pixel", "android", "1.0",
        Devices(), Pins(), Catalog())
      val host = manager.loginWithPassword("user@example.org", "pw").single()
      assertTrue(manager.enroll(listOf(host), mapOf(hostId to host.meta.fingerprint!!)).failures.isEmpty())

      val refreshed = manager.refresh()
      assertEquals(hostId, refreshed.hosts.single().hostId)

      mock.takeRequest() // login
      mock.takeRequest() // account directory
      mock.takeRequest() // per-Host device registration
      val list = mock.takeRequest()
      assertEquals("/api/v1/devices", list.path)
      assertEquals("Bearer $access", list.getHeader("Authorization"))
      val detail = mock.takeRequest()
      assertEquals("/api/v1/devices/$hostId", detail.path)
      assertEquals("Bearer $access", detail.getHeader("Authorization"))
    }
  }

  @Test fun refreshPropagatesTransientErrorInsteadOfMaskingAsExpiredLogin() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(accountDirectory()))
      mock.enqueue(MockResponse().setBody(pair().toString()))
      mock.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":{"code":"UPSTREAM_DOWN","message":"bad gateway","retryable":true}}"""))

      val manager = RemoteAccountManager(mock.url("/").toString(), true, "Lagoon · Pixel", "android", "1.0",
        Devices(), Pins(), Catalog())
      val host = manager.loginWithPassword("user@example.org", "pw").single()
      assertTrue(manager.enroll(listOf(host), mapOf(hostId to host.meta.fingerprint!!)).failures.isEmpty())

      val failure = runCatching { manager.refresh() }.exceptionOrNull()
      assertTrue("Expected transient RemoteServerException, got $failure", failure is RemoteServerException)
      assertFalse(failure?.message.orEmpty().contains("登录已失效"))
    }
  }

  @Test fun refreshRecoversOn401TokenExpiredViaSingleRefreshAndRetry() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val futureAccess = System.currentTimeMillis() + 3_600_000L
      val futureRefresh = System.currentTimeMillis() + 86_400_000L
      val initialPair = JSONObject().put("accessToken", access).put("accessTokenExpiresAt", futureAccess)
        .put("refreshToken", refresh).put("refreshTokenExpiresAt", futureRefresh).toString()
      val rotatedAccess = "rotated-access-token-456"
      val rotatedRefresh = "rotated-refresh-token-456"
      val rotatedPair = JSONObject().put("accessToken", rotatedAccess).put("accessTokenExpiresAt", futureAccess + 3_600_000L)
        .put("refreshToken", rotatedRefresh).put("refreshTokenExpiresAt", futureRefresh).toString()

      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(accountDirectory()))
      mock.enqueue(MockResponse().setBody(initialPair))
      // 1) Initial /api/v1/devices rejects apparently-unexpired token with 401 TOKEN_EXPIRED
      mock.enqueue(MockResponse().setResponseCode(401)
        .setBody("""{"error":{"code":"TOKEN_EXPIRED","message":"expired","retryable":true,"details":null}}"""))
      // 2) Reactive singleflight refresh succeeds
      mock.enqueue(MockResponse().setBody(rotatedPair))
      // 3) Retry succeeds with rotated bearer
      mock.enqueue(MockResponse().setBody("""{"items":[{"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true}],"nextCursor":null}"""))
      mock.enqueue(MockResponse().setBody("""{"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true,"identityKey":"$key"}"""))

      val manager = RemoteAccountManager(mock.url("/").toString(), true, "Lagoon · Pixel", "android", "1.0",
        Devices(), Pins(), Catalog())
      val host = manager.loginWithPassword("user@example.org", "pw").single()
      assertTrue(manager.enroll(listOf(host), mapOf(hostId to host.meta.fingerprint!!)).failures.isEmpty())

      val refreshed = manager.refresh()
      assertEquals(hostId, refreshed.hosts.single().hostId)

      mock.takeRequest() // login
      mock.takeRequest() // account/devices
      mock.takeRequest() // register
      assertEquals("/api/v1/devices", mock.takeRequest().path)
      assertEquals("/api/v1/auth/refresh", mock.takeRequest().path)
      val retriedList = mock.takeRequest()
      assertEquals("/api/v1/devices", retriedList.path)
      assertEquals("Bearer $rotatedAccess", retriedList.getHeader("Authorization"))
    }
  }

  @Test fun reEnrollRetainsPinnedTrustAndRepairsInvalidatedHostSlot() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val host2Id = "b1c2d3e4-f5a6-47b8-89c0-1d2e3f4a5b6c"
      val key2 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 21).toByte() })
      val twoHostDir = """{"items":[
        {"deviceId":"$hostId","name":"Mac1","platform":"darwin","role":"host","membershipId":"m1","online":true,"identityKey":"$key"},
        {"deviceId":"$host2Id","name":"Mac2","platform":"linux","role":"host","membershipId":"m2","online":true,"identityKey":"$key2"}
      ]}"""
      val origin = mock.url("/").toString()
      val futureAccess = System.currentTimeMillis() + 3_600_000L
      val futureRefresh = System.currentTimeMillis() + 30L * 86_400_000L
      val validPairJson = JSONObject().put("accessToken", access).put("accessTokenExpiresAt", futureAccess)
        .put("refreshToken", refresh).put("refreshTokenExpiresAt", futureRefresh).toString()
      val devices = Devices()
      val pins = Pins()
      val catalogs = Catalog()
      val manager = RemoteAccountManager(origin, true, "Lagoon · Pixel", "android", "1.0", devices, pins, catalogs)

      // Initial enroll of both hosts
      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(twoHostDir))
      mock.enqueue(MockResponse().setBody(validPairJson))
      mock.enqueue(MockResponse().setBody(validPairJson))
      val initialCandidates = manager.loginWithPassword("user@example.org", "pw")
      val initialEnroll = manager.enroll(
        initialCandidates,
        initialCandidates.associate { it.hostId to it.meta.fingerprint!! },
      )
      assertTrue(initialEnroll.failures.isEmpty())

      // Mark host2's device slot as invalidated
      val slot2 = scopedRemoteDeviceSlot(origin, "user@example.org", host2Id)
      val broken2 = devices.load(slot2)!!
      devices.save(slot2, RemoteDeviceRecord(origin, "user@example.org", broken2.identity, broken2.tokens, invalidated = true))

      // Re-login and explicitly enroll ONLY host1 -> host2 remains PINNED and gets silently repaired!
      val repairedAccess = "repaired-access-token-789"
      val repairedPairJson = JSONObject().put("accessToken", repairedAccess).put("accessTokenExpiresAt", futureAccess)
        .put("refreshToken", "repaired-refresh-789").put("refreshTokenExpiresAt", futureRefresh).toString()
      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(twoHostDir))
      mock.enqueue(MockResponse().setBody(validPairJson))
      mock.enqueue(MockResponse().setBody(repairedPairJson))

      val secondCandidates = manager.loginWithPassword("user@example.org", "pw")
      val onlyHost1 = secondCandidates.filter { it.hostId == hostId }
      val secondEnroll = manager.enroll(onlyHost1, mapOf(hostId to onlyHost1.single().meta.fingerprint!!))
      assertTrue(secondEnroll.failures.isEmpty())
      assertEquals(2, secondEnroll.catalog.hosts.size)
      assertTrue(secondEnroll.catalog.hosts.all { it.trust == RemoteTrustState.PINNED })

      val restored2 = devices.load(slot2)!!
      assertFalse(restored2.invalidated)
      assertEquals(repairedAccess, restored2.tokens?.accessToken)
    }
  }

  @Test fun adoptPairingPinsHostSavesDeviceCredentialsAndSetsPairingProvider() = runBlocking {
    val origin = "https://dsh.r2049.cn/"
    val pairingAccount = "dsh-device:019a0000-0000-7000-8000-000000000099"
    val devices = Devices()
    val pins = Pins()
    val catalogs = Catalog()
    val manager = RemoteAccountManager(origin, false, "Lagoon · Pixel", "android", "1.0", devices, pins, catalogs)
    val host = RemoteHost(hostId, "Desk", "linux", "pairing", online = true, publicKey = key, clientVersion = null, harnessVersion = "0.6.4")
    val mobileIdentity = RemoteIdentity.generate()
    val tokens = RemoteTokenPair(access, 1_900_000_000_000L, refresh, 1_900_000_001_000L)

    val catalog = manager.adoptPairing(host, pairingAccount, mobileIdentity, tokens)
    assertEquals("pairing", catalog.provider)
    assertEquals(pairingAccount, catalog.account)
    assertFalse(catalog.signedOut)
    assertEquals(RemoteTrustState.PINNED, catalog.hosts.single().trust)
    assertEquals(key, pins.pinned(RemotePeerScope(origin, pairingAccount, hostId)))

    val saved = devices.load(scopedRemoteDeviceSlot(origin, pairingAccount, hostId))!!
    assertFalse(saved.invalidated)
    assertEquals(mobileIdentity.deviceId, saved.identity.deviceId)
    assertEquals(access, saved.tokens?.accessToken)
  }
}
