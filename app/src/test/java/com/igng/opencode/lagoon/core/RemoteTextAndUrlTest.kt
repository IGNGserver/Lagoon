package com.igng.opencode.lagoon.core

import com.igng.opencode.lagoon.ui.attachmentCanOpen
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class RemoteTextAndUrlTest {

  @Test fun truncateByCodePointsSafeNeverSplitsSurrogatePairs() {
    val emojis = "👨‍👩‍👧‍👦🚀测试"
    for (limit in 0..emojis.length + 2) {
      val cut = truncateByCodePointsSafe(emojis, limit)
      assertTrue("length=${cut.length} > $limit", cut.length <= limit)
      if (cut.isNotEmpty()) {
        assertFalse("orphan high surrogate at limit=$limit", Character.isHighSurrogate(cut.last()))
      }
    }
    // Check a plain astral pair cleanly drops if cut in the middle of the surrogate pair (limit=1 for 2-char 🚀).
    assertEquals("", truncateByCodePointsSafe("🚀", 1))
    assertEquals("🚀", truncateByCodePointsSafe("🚀", 2))
  }

  @Test fun truncateUtf8BytesNeverSplitsMultibyteOrSurrogates() {
    val mixed = "A中文🚀主机名称"
    for (maxBytes in 0..40) {
      val cut = truncateUtf8Bytes(mixed, maxBytes)
      val utf8 = cut.toByteArray(Charsets.UTF_8)
      assertTrue("utf8 bytes=${utf8.size} > $maxBytes", utf8.size <= maxBytes)
      assertEquals("round-trip corrupted at $maxBytes", cut, String(utf8, Charsets.UTF_8))
    }
  }

  @Test fun sanitizeRemoteDisplayNamePreservesUnicodeAndStripsControls() {
    assertEquals("办公室 Mac Mini 🚀", sanitizeRemoteDisplayName("  办公室\t\nMac Mini 🚀\u0000\u202E  "))
    assertEquals("默认设备", sanitizeRemoteDisplayName("\u0007 \r\n", fallback = "默认设备"))
  }

  @Test fun idnUnderscoreAndLocalHostsWorkAcrossOriginParsingAndHttp() {
    assertEquals("https://xn--0zwm56d.example.com:8443/", canonicalRemoteOrigin(" https://测试.example.com:8443/ "))
    assertEquals("http://dev_mac.local:4096/", canonicalRemoteOrigin("http://dev_mac.local:4096"))
    assertEquals("http://[::1]:8787/", canonicalRemoteOrigin("http://[::1]:8787/"))

    assertEquals("dev_mac.local", normalizeSshHost(" DEV_MAC.local "))
    assertEquals("xn--0zwm56d.example.com", normalizeSshHost("测试.example.com"))
    assertEquals("::1", normalizeSshHost("[::1]"))

    assertEquals("http://192.168.1.50:8787", normalizeRemoteHubAddressInput("192.168.1.50:8787"))
    assertEquals("http://dev_mac.local:8787", normalizeRemoteHubAddressInput("dev_mac.local:8787"))
    assertEquals("https://hub.example.com", normalizeRemoteHubAddressInput("hub.example.com"))

    assertTrue(attachmentCanOpen("https://测试.example.com/files/截图.png"))
    assertTrue(attachmentCanOpen("http://dev_mac.local:4096/a.txt"))
  }

  @Test fun openCodeApiSupportsIdnUnderscoreHostsUtf8BasicAuthAndEncodedPaths() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"version":"2.5.0"}"""))
      val profile = ServerProfile("s1", "中文服务器", server.url("/").toString().trimEnd('/'), "admin", allowCleartext = true)
      val creds = ServerCredentials("user", "密码🔑")
      val api = OpenCodeApi(profile, creds)
      try {
        assertEquals("2.5.0", api.health())
        val req = server.takeRequest()
        assertEquals(Credentials.basic("user", "密码🔑", Charsets.UTF_8), req.getHeader("Authorization"))
      } finally {
        api.close()
      }

      // Endpoint parsing works for IDN & underscore LAN hosts too.
      val idnEndpoint = NativeHttpEndpoint("https://测试.example.com:8443/prefix")
      assertEquals("https://xn--0zwm56d.example.com:8443/prefix/api/info", idnEndpoint.resolve("api/info"))

      val underscoreEndpoint = NativeHttpEndpoint("http://dev_mac.local:4096")
      assertEquals(NativeHttpOrigin("http", "dev_mac.local", 4096), underscoreEndpoint.origin)
      val openCodeWithUnderscore = OpenCodeApi(
        ServerProfile("s2", "本地测试机", "http://dev_mac.local:4096", "u", allowCleartext = true),
        ServerCredentials("u", "p")
      )
      openCodeWithUnderscore.close()

      assertEquals("api/%E4%B8%AD%E6%96%87/a%2Fb.kt", encodeUrlPathPreservingEscapes("/api/中文/a%2Fb.kt"))
    }
  }

  @Test fun pairingQrAndServerApiHandleChineseDeviceNamesIdnHostsAndPagination() = runBlocking {
    val ticket = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 43 chars base64url (32 zero bytes)
    val idnQr = parseRemotePairingScan("https://测试.example.com:8443/pair#v1.$ticket", allowCleartext = false) as RemotePairingScan.Ticket
    assertEquals("https://xn--0zwm56d.example.com:8443/", idnQr.origin)

    val underscoreQr = parseRemotePairingScan("http://dev_mac.local:8787/pair#v1.$ticket", allowCleartext = true) as RemotePairingScan.Ticket
    assertEquals("http://dev_mac.local:8787/", underscoreQr.origin)

    val now = System.currentTimeMillis()
    val legacyJson = JSONObject()
      .put("type", "dsh.remote.pairing")
      .put("v", 1)
      .put("serverUrl", "https://测试.example.com:8443")
      .put("sessionId", UUID.randomUUID().toString())
      .put("expiresAt", now + 120_000L)
      .put("serverPublicKey", "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
      .put("scanSecret", ticket)
      .put("host", JSONObject()
        .put("deviceId", "01J00000000000000000000001")
        .put("name", "办公室主机")
        .put("role", "host")
        .put("platform", "darwin")
        .put("identityKey", ticket))
      .toString()
    val legacyScan = parseRemotePairingScan(legacyJson, allowCleartext = false, nowMillis = now) as RemotePairingScan.Legacy
    assertEquals("https://xn--0zwm56d.example.com:8443/", legacyScan.origin)
    assertTrue(runCatching {
      parseRemotePairingScan(legacyJson.replace(":8443\"", ":8443/\""), allowCleartext = false, nowMillis = now)
    }.isFailure)

    MockWebServer().use { server ->
      val longChineseName = "测试工作站".repeat(12) + "🖥️" // > 128 UTF-8 bytes
      val hostKey = ticket
      val page1 = JSONObject().put("items", JSONArray().put(JSONObject()
        .put("deviceId", "01J00000000000000000000001")
        .put("role", "host")
        .put("name", longChineseName)
        .put("platform", "darwin")
        .put("membershipId", "mem-1")
        .put("online", true)
        .put("identityKey", hostKey)))
        .put("nextCursor", "cur_2")
      val page2 = JSONObject().put("items", JSONArray().put(JSONObject()
        .put("deviceId", "01J00000000000000000000002")
        .put("role", "host")
        .put("name", "二号服务器")
        .put("platform", "linux")
        .put("membershipId", "mem-2")
        .put("online", false)
        .put("identityKey", hostKey)))
      server.enqueue(MockResponse().setBody(page1.toString()))
      server.enqueue(MockResponse().setBody(page2.toString()))

      val api = RemoteServerApi(server.url("/").toString(), allowCleartext = true)
      val hosts = api.accountHosts("tok_valid_1234567890")
      assertEquals(2, hosts.size)
      assertFalse(Character.isHighSurrogate(hosts[0].name.last()))
      assertEquals("二号服务器", hosts[1].name)

      val req1 = server.takeRequest()
      val req2 = server.takeRequest()
      assertEquals("/api/v1/account/devices", req1.path)
      assertEquals("/api/v1/account/devices?limit=50&cursor=cur_2", req2.path)
    }
  }
}
