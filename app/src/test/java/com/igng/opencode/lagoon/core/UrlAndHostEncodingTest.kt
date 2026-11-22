package com.igng.opencode.lagoon.core

import com.igng.opencode.lagoon.ui.attachmentCanOpen
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlAndHostEncodingTest {

  @Test fun idnUnderscoreAndLocalHostsWorkAcrossSshAndAttachments() {
    assertEquals("dev_mac.local", normalizeSshHost(" DEV_MAC.local "))
    assertEquals("xn--0zwm56d.example.com", normalizeSshHost("测试.example.com"))
    assertEquals("::1", normalizeSshHost("[::1]"))

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
}
