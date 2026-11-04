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

  @Test fun passwordLoginDiscoversAndEnrollsOneHostWithHostStableSlot() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val pair = JSONObject().put("accessToken", access).put("accessTokenExpiresAt", 1900000000000L)
        .put("refreshToken", refresh).put("refreshTokenExpiresAt", 1900000001000L)
      mock.enqueue(MockResponse().setBody(JSONObject().put("account", "user@example.org").put("token", accountToken)
        .put("expiresAt", 1900000000000L).toString()))
      mock.enqueue(MockResponse().setBody("""{"items":[{"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true}],"nextCursor":null}"""))
      mock.enqueue(MockResponse().setBody("""{"deviceId":"$hostId","name":"Mac","platform":"darwin","role":"host","membershipId":"member","online":true,"identityKey":"$key","harnessVersion":"0.2.1"}"""))
      mock.enqueue(MockResponse().setBody(pair.toString()))

      val devices = Devices()
      val catalog = Catalog()
      val manager = RemoteAccountManager(mock.url("/").toString(), true, "Lagoon · Pixel", "android", "1.0",
        devices, Pins(), catalog)
      val candidates = manager.loginWithPassword("user@example.org", "pw")
      val host = candidates.single()
      assertEquals(RemoteSetup.fingerprint(key), host.meta.fingerprint)
      assertEquals(hostId, host.hostId)

      val result = manager.enroll(listOf(host), mapOf(hostId to host.meta.fingerprint!!))
      assertTrue(result.failures.isEmpty())
      assertEquals(setOf(hostId), result.registered.keys)
      assertEquals(1, result.registered.size)
      assertNotNull(devices.values["host-$hostId"])
      assertEquals(RemoteTrustState.PINNED, catalog.value!!.hosts.single().trust)
      assertEquals("/api/v1/auth/login", mock.takeRequest().path)
      assertEquals("/api/v1/devices", mock.takeRequest().path)
      assertEquals("/api/v1/devices/$hostId", mock.takeRequest().path)
      assertEquals("/api/v1/devices/register", mock.takeRequest().path)
    }
  }
}
