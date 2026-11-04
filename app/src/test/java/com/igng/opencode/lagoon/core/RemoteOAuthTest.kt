package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteOAuthTest {
  private val qrId = "qr_0123456789abcdef"
  private val accountToken = "account-token-123456789"

  @Test fun providersQrPollAndAccountProfileUseUpstreamContract() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val api = RemoteServerApi(mock.url("/").toString(), allowCleartext = true)
      val scan = mock.url("/login").toString()
      mock.enqueue(MockResponse().setBody("""{"items":[{"id":"github","displayName":"GitHub","startUrl":"/api/v1/auth/oauth/github/start","qrStartUrl":"/api/v1/auth/oauth/github/qr/start"}]}"""))
      mock.enqueue(MockResponse().setBody("""{"qrId":"$qrId","scanUrl":"$scan","expiresIn":120,"provider":"github"}"""))
      mock.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
      mock.enqueue(MockResponse().setBody("""{"status":"complete","token":"$accountToken"}"""))
      mock.enqueue(MockResponse().setBody("""{"account":"user@example.org"}"""))

      val provider = api.oauthProviders().single()
      assertEquals("github", provider.id)
      val login = api.startQrLogin(provider)
      assertEquals(qrId, login.qrId)
      assertEquals(scan, login.loginUrl)
      assertEquals(RemoteQrPoll.Pending, api.pollQrLogin(login.qrId))
      assertEquals(RemoteQrPoll.Complete(accountToken), api.pollQrLogin(login.qrId))
      assertEquals("user@example.org", api.accountMe(accountToken))
      assertEquals("/api/v1/auth/oauth/providers", mock.takeRequest().path)
      assertEquals("POST", mock.takeRequest().method)
      assertEquals("/api/v1/auth/oauth/qr/$qrId", mock.takeRequest().path)
      assertEquals("/api/v1/auth/oauth/qr/$qrId", mock.takeRequest().path)
      assertEquals("Bearer $accountToken", mock.takeRequest().getHeader("Authorization"))
    }
  }

  @Test fun rejectsCrossOriginLoginAndBadEntryPaths() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val api = RemoteServerApi(mock.url("/").toString(), allowCleartext = true)
      mock.enqueue(MockResponse().setBody("""{"items":[{"id":"bad","displayName":"Bad","startUrl":"https://evil.example/auth","qrStartUrl":"/api/v1/auth/oauth/bad/qr/start"}]}"""))
      assertTrue(api.oauthProviders().isEmpty())
      mock.enqueue(MockResponse().setBody("""{"qrId":"$qrId","scanUrl":"https://evil.example/login","expiresIn":120,"provider":"bad"}"""))
      val provider = RemoteOAuthProvider("bad", "Bad", "/api/v1/auth/oauth/bad/start", "/api/v1/auth/oauth/bad/qr/start")
      try { api.startQrLogin(provider); fail("expected failure") } catch (_: IOException) { }
    }
  }

  @Test fun revokeSelfUsesBearerAndAcceptsEmptyBody() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val api = RemoteServerApi(mock.url("/").toString(), allowCleartext = true)
      mock.enqueue(MockResponse().setResponseCode(204))
      api.revokeSelf(accountToken)
      val request = mock.takeRequest()
      assertEquals("DELETE", request.method)
      assertEquals("/api/v1/devices/self", request.path)
      assertEquals("Bearer $accountToken", request.getHeader("Authorization"))
    }
  }
}
