package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class RemoteServerApiTest {
  private val id = "a8f582fb-f662-49c6-b7f0-456a797a5302"
  private val hostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
  private val accountToken = "account-token-123456789"
  private val access = "device-access-token-123"
  private val refresh = "device-refresh-token-123"
  private fun pair() = JSONObject().put("accessToken", access).put("accessTokenExpiresAt", 1900000000000L)
    .put("refreshToken", refresh).put("refreshTokenExpiresAt", 1900000001000L)
  private fun server(block: suspend (MockWebServer, RemoteServerApi) -> Unit) = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      block(mock, RemoteServerApi(mock.url("/").toString(), allowCleartext = true))
    }
  }

  @Test fun loginRegisterDiscoverAndRefreshUseUpstreamContract() = server { mock, api ->
    mock.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
    mock.enqueue(MockResponse().setBody(JSONObject().put("token", accountToken).put("account", "user@example.org").put("expiresAt", 1900000000000L).put("isAdmin", false).toString()))
    mock.enqueue(MockResponse().setBody(pair().toString()))
    mock.enqueue(MockResponse().setBody("""{"items":[{"deviceId":"$hostId","name":"Host","platform":"linux","role":"host","membershipId":"member","online":true}],"nextCursor":null}"""))
    mock.enqueue(MockResponse().setBody("""{"deviceId":"$hostId","name":"Host","platform":"linux","role":"host","membershipId":"member","online":true,"identityKey":"$key"}"""))
    mock.enqueue(MockResponse().setBody(pair().toString()))
    api.health()
    assertEquals("user@example.org", api.login(" user@example.org ", "password").account)
    val identity = RemoteClientDescriptor(id, "Lagoon", "android", key, "1.0.0")
    assertEquals(access, api.register(identity, accountToken).accessToken)
    assertNull(api.hosts(access).single().publicKey)
    assertEquals(key, api.host(access, hostId).publicKey)
    assertEquals(refresh, api.refresh(id, refresh).refreshToken)
    assertEquals("/health", mock.takeRequest().path)
    assertEquals("user@example.org", JSONObject(mock.takeRequest().body.readUtf8()).getString("email"))
    val registration = mock.takeRequest()
    assertEquals("Bearer $accountToken", registration.getHeader("Authorization"))
    val descriptor = JSONObject(registration.body.readUtf8()).getJSONObject("device")
    assertEquals("client", descriptor.getString("role"))
    assertEquals(key, descriptor.getString("identityKey"))
    assertEquals("Bearer $access", mock.takeRequest().getHeader("Authorization"))
    assertEquals("/api/v1/devices/$hostId", mock.takeRequest().path)
    val refreshRequest = mock.takeRequest()
    assertNull(refreshRequest.getHeader("Authorization"))
    assertEquals(refresh, JSONObject(refreshRequest.body.readUtf8()).getString("refreshToken"))
  }

  @Test fun redirectNeverForwardsAccountCredentials() = server { mock, api ->
    MockWebServer().use { other ->
      other.start()
      mock.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/stolen")))
      try { api.login("user@example.org", "password"); fail("expected failure") }
      catch (error: RemoteServerException) { assertEquals(307, error.status) }
      assertEquals(0, other.requestCount)
    }
  }

  @Test fun malformedTokenAndWrongHostFailClosed() = server { mock, api ->
    mock.enqueue(MockResponse().setBody(pair().put("unexpected", true).toString()))
    try { api.refresh(id, refresh); fail("expected failure") } catch (_: IOException) { }
    mock.enqueue(MockResponse().setBody("""{"deviceId":"$id","name":"Host","platform":"linux","role":"host","membershipId":"member","identityKey":"$key"}"""))
    try { api.host(access, hostId); fail("expected failure") } catch (_: IOException) { }
    mock.enqueue(MockResponse().setBody("""{"deviceId":"$hostId","name":"Host","platform":"linux","role":"host","membershipId":"member","identityKey":"${key.dropLast(1)}!"}"""))
    try { api.host(access, hostId); fail("expected failure") } catch (_: IOException) { }
  }

  @Test fun rejectsCredentialLeakAndUnapprovedCleartextTargets() {
    try { RemoteServerApi("http://example.org"); fail("expected failure") } catch (_: IllegalArgumentException) { }
    try { RemoteServerApi("https://user:pass@example.org"); fail("expected failure") } catch (_: IllegalArgumentException) { }
    try { RemoteServerApi("https://example.org/prefix"); fail("expected failure") } catch (_: IllegalArgumentException) { }
  }

  @Test fun incompleteDevicePaginationIsNotPublishedAsComplete() = server { mock, api ->
    mock.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":"more"}"""))
    try { api.hosts(access); fail("expected failure") } catch (_: IOException) { }
  }
}
