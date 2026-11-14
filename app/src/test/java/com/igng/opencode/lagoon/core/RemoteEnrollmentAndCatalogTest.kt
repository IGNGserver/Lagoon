package com.igng.opencode.lagoon.core

import com.igng.opencode.lagoon.ui.cleartextConsentAfterUrlEdit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteEnrollmentAndCatalogTest {
  private val hostId1 = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val hostId2 = "b1c2d3e4-f5a6-47b8-89c0-1d2e3f4a5b6c"
  private val key1 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
  private val key2 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 21).toByte() })
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
    override fun replaceIfMatches(scope: RemotePeerScope, expectedPublicKey: String, replacementPublicKey: String): Boolean {
      val k = "${scope.origin}|${scope.account}|${scope.hostId}"
      if (values[k] != expectedPublicKey) return false
      values[k] = replacementPublicKey
      return true
    }
    override fun remove(scope: RemotePeerScope) {
      values.remove("${scope.origin}|${scope.account}|${scope.hostId}")
    }
  }

  private class Catalogs : RemoteCatalogStore {
    val items = linkedMapOf<String, RemoteCatalog>()
    override fun load(): List<RemoteCatalog> = items.values.toList()
    override fun save(catalog: RemoteCatalog) { items[catalog.key] = catalog }
    override fun clear(origin: String, account: String) { items.remove("$origin\u0000$account") }
  }

  private fun pairJson() = JSONObject()
    .put("accessToken", access)
    .put("accessTokenExpiresAt", System.currentTimeMillis() + 3_600_000L)
    .put("refreshToken", refresh)
    .put("refreshTokenExpiresAt", System.currentTimeMillis() + 86_400_000L)
    .toString()

  private fun loginBody() = JSONObject()
    .put("account", "owner@example.org")
    .put("token", accountToken)
    .put("expiresAt", System.currentTimeMillis() + 3_600_000L)
    .toString()

  private fun twoHostDir() = """{"items":[
    {"deviceId":"$hostId1","name":"开发台式机","platform":"linux","role":"host","membershipId":"m1","online":true,"identityKey":"$key1"},
    {"deviceId":"$hostId2","name":"备用电脑","platform":"darwin","role":"host","membershipId":"m2","online":true,"identityKey":"$key2"}
  ]}"""

  @Test fun trustPinIsOnlyWrittenAfterRegisterSucceeds() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val origin = mock.url("/").toString()
      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(twoHostDir()))
      mock.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
      mock.enqueue(MockResponse().setBody(pairJson()))

      val pins = Pins()
      val catalogs = Catalogs()
      val manager = RemoteAccountManager(origin, true, "Lagoon · 中文手机", "android", "1.0", Devices(), pins, catalogs)
      val candidates = manager.loginWithPassword("owner@example.org", "pw")
      val host1 = candidates.first { it.hostId == hostId1 }

      val failedEnroll = manager.enroll(listOf(host1), mapOf(hostId1 to host1.meta.fingerprint!!))
      assertTrue(failedEnroll.registered.isEmpty())
      assertNull("Failed register must NOT pin host trust prematurely",
        pins.pinned(RemotePeerScope(canonicalRemoteOrigin(origin), "owner@example.org", hostId1)))

      val succeededEnroll = manager.enroll(listOf(host1), mapOf(hostId1 to host1.meta.fingerprint!!))
      assertEquals(setOf(hostId1), succeededEnroll.registered.keys)
      assertEquals(key1, pins.pinned(RemotePeerScope(canonicalRemoteOrigin(origin), "owner@example.org", hostId1)))
      assertEquals(setOf(hostId1), succeededEnroll.catalog.adoptedHostIds)
    }
  }

  @Test fun unenrolledPinnedHostDoesNotBecomeAdoptedAfterRemovingAll() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val origin = mock.url("/").toString()
      val canonical = canonicalRemoteOrigin(origin)
      val pins = Pins()
      // Host 2 had an older pin or leftover pin; only Host 1 is newly enrolled now.
      pins.pinIfAbsent(RemotePeerScope(canonical, "owner@example.org", hostId2), key2)

      mock.enqueue(MockResponse().setBody(loginBody()))
      mock.enqueue(MockResponse().setBody(twoHostDir()))
      mock.enqueue(MockResponse().setBody(pairJson()))

      val catalogs = Catalogs()
      val manager = RemoteAccountManager(origin, true, "Lagoon", "android", "1.0", Devices(), pins, catalogs)
      val candidates = manager.loginWithPassword("owner@example.org", "pw")
      val host1 = candidates.first { it.hostId == hostId1 }

      val result = manager.enroll(listOf(host1), mapOf(hostId1 to host1.meta.fingerprint!!))
      assertEquals(setOf(hostId1), result.catalog.adoptedHostIds)
      assertFalse("Unenrolled host must not be added to adoptedHostIds", hostId2 in result.catalog.adoptedHostIds!!)
      assertEquals(RemoteTrustState.PINNED, result.catalog.hosts.first { it.hostId == hostId1 }.trust)
      assertEquals(setOf(hostId1), result.catalog.effectiveAdoptedHostIds())
    }
  }

  @Test fun remoteCatalogCodecRoundTripsAdoptedHostIdsAndEmptyAdoption() {
    val origin = "https://hub.example.org/"
    val catalog = RemoteCatalog(
      origin = origin,
      account = "owner@example.org",
      provider = "github",
      deviceLabel = "我的手机",
      hosts = listOf(
        RemoteHostMeta(hostId1, "工作站", "linux", online = true, lastSeenAt = 1000L, harnessVersion = "0.6.5", pluginVersion = null, fingerprint = RemoteSetup.fingerprint(key1), trust = RemoteTrustState.PINNED),
        RemoteHostMeta(hostId2, "未选择", "darwin", online = false, lastSeenAt = null, harnessVersion = null, pluginVersion = null, fingerprint = null, trust = RemoteTrustState.UNKNOWN),
      ),
      updatedAt = 2000L,
      allowCleartext = false,
      signedOut = false,
      adoptedHostIds = emptySet(),
    )
    val decoded = RemoteCatalogCodec.decode(RemoteCatalogCodec.encode(catalog))
    assertEquals(emptySet<String>(), decoded.adoptedHostIds)
    assertEquals(emptySet<String>(), decoded.effectiveAdoptedHostIds())

    val partial = catalog.copy(adoptedHostIds = setOf(hostId1))
    val decodedPartial = RemoteCatalogCodec.decode(RemoteCatalogCodec.encode(partial))
    assertEquals(setOf(hostId1), decodedPartial.adoptedHostIds)
    assertEquals(setOf(hostId1), decodedPartial.effectiveAdoptedHostIds())
  }

  @Test fun bareLanAddressNormalizationAndCleartextConsentResetOnUrlChange() {
    assertEquals("http://192.168.1.50:8787", normalizeRemoteHubAddressInput("192.168.1.50:8787"))
    assertEquals("http://macbook.local:8080", normalizeRemoteHubAddressInput("macbook.local:8080"))
    assertEquals("https://dsh.r2049.cn", normalizeRemoteHubAddressInput("dsh.r2049.cn"))

    assertTrue(cleartextConsentAfterUrlEdit("http://192.168.1.50:8787", "http://192.168.1.50:8787", explicitlyAllowed = true))
    assertFalse(cleartextConsentAfterUrlEdit("http://192.168.1.50:8787", "http://192.168.1.99:8787", explicitlyAllowed = true))
  }
}
