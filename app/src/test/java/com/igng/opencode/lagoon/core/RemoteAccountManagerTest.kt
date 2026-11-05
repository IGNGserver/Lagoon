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
      assertNotNull(devices.values["host-$hostId"])
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

  /** A saved catalog refreshes with the registered Client token, not the account token. */
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
}
