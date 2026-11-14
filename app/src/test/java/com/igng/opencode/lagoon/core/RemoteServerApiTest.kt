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

  @Test fun accountDirectoryUsesAccountTokenAndSkipsClients() = server { mock, api ->
    mock.enqueue(MockResponse().setBody("""{"items":[
      {"deviceId":"$hostId","name":"Host","platform":"linux","role":"host","membershipId":"member","online":true,"identityKey":"$key"},
      {"deviceId":"$id","name":"Phone","platform":"android","role":"client","membershipId":"member","online":false,"identityKey":"$key"},
      {"deviceId":"a8f582fb-f662-49c6-b7f0-456a797a5303","name":"NoKey","platform":"linux","role":"host","membershipId":"member","online":false}
    ]}"""))
    val hosts = api.accountHosts(accountToken)
    assertEquals(2, hosts.size)
    assertEquals(hostId, hosts[0].deviceId)
    assertEquals(key, hosts[0].publicKey)
    assertEquals("NoKey", hosts[1].name)
    assertNull(hosts[1].publicKey)
    val request = mock.takeRequest()
    assertEquals("/api/v1/account/devices", request.path)
    assertEquals("Bearer $accountToken", request.getHeader("Authorization"))
  }

  @Test fun accountDirectoryRejectsExpiredAccountSessionWithUserMessage() = server { mock, api ->
    mock.enqueue(MockResponse().setResponseCode(401)
      .setBody("""{"error":{"code":"ACCOUNT_AUTH_REQUIRED","message":"ACCOUNT_AUTH_REQUIRED","retryable":false}}"""))
    try { api.accountHosts(accountToken); fail("expected failure") }
    catch (error: IOException) { assertTrue(error.message!!.contains("重新登录")) }
  }

  @Test fun loginRejectsWrongPasswordWithCredentialMessage() = server { mock, api ->
    mock.enqueue(MockResponse().setResponseCode(401)
      .setBody("""{"error":{"code":"AUTH_INVALID","message":"AUTH_INVALID","retryable":false}}"""))
    try { api.login("user@example.org", "wrong"); fail("expected failure") }
    catch (error: IOException) { assertTrue(error.message!!.contains("账号或密码")) }
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
    val tolerated = api.refresh(id, refresh)
    assertEquals(access, tolerated.accessToken)
    val missingRequired = pair().apply { remove("refreshToken") }
    mock.enqueue(MockResponse().setBody(missingRequired.toString()))
    try { api.refresh(id, refresh); fail("expected failure") } catch (_: IOException) { }
    mock.enqueue(MockResponse().setBody("""{"deviceId":"$id","name":"Host","platform":"linux","role":"host","membershipId":"member","identityKey":"$key"}"""))
    try { api.host(access, hostId); fail("expected failure") } catch (_: IOException) { }
    mock.enqueue(MockResponse().setBody("""{"deviceId":"$hostId","name":"Host","platform":"linux","role":"host","membershipId":"member","identityKey":"${key.dropLast(1)}!"}"""))
    try { api.host(access, hostId); fail("expected failure") } catch (_: IOException) { }
  }

  @Test fun errorDetailsNullPreservesCodeAndAuthCapabilitiesParsesPairingFlags() = server { mock, api ->
    mock.enqueue(MockResponse().setResponseCode(401)
      .setBody("""{"error":{"code":"TOKEN_EXPIRED","message":"expired","retryable":true,"details":null}}"""))
    val err = try {
      api.host(access, hostId)
      throw AssertionError("expected 401")
    } catch (e: RemoteServerException) {
      e
    }
    assertEquals(401, err.status)
    assertEquals("TOKEN_EXPIRED", err.code)
    assertTrue(err.isRecoverableTokenRejection)
    assertTrue(err.isCredentialRejected)

    mock.enqueue(MockResponse().setBody(
      """{"capabilities":{"devicePairing":"dsh-pairing-v1","devicePairingQr":"fragment-ticket-v1"},"items":[]}""",
    ))
    val caps = api.authCapabilities()
    assertTrue(caps.devicePairing)
    assertTrue(caps.fragmentTicketQr)

    mock.enqueue(MockResponse().setResponseCode(404))
    val missingCaps = api.authCapabilities()
    assertFalse(missingCaps.devicePairing)
    assertFalse(missingCaps.fragmentTicketQr)
  }

  @Test fun rejectsCredentialLeakAndUnapprovedCleartextTargets() {
    try { RemoteServerApi("http://example.org"); fail("expected failure") } catch (_: IllegalArgumentException) { }
    try { RemoteServerApi("https://user:pass@example.org"); fail("expected failure") } catch (_: IllegalArgumentException) { }
    try { RemoteServerApi("https://example.org/prefix"); fail("expected failure") } catch (_: IllegalArgumentException) { }
  }

  @Test fun devicePaginationReadsEveryPageWithTheSameDeviceCredential() = server { mock, api ->
    mock.enqueue(MockResponse().setBody("""{"items":[{"deviceId":"$hostId","name":"One","platform":"linux","role":"host","membershipId":"member","online":true}],"nextCursor":"more"}"""))
    mock.enqueue(MockResponse().setBody("""{"items":[{"deviceId":"$id","name":"Two","platform":"linux","role":"host","membershipId":"member","online":false}],"nextCursor":null}"""))
    assertEquals(listOf(hostId, id), api.hosts(access).map { it.deviceId })
    assertEquals("/api/v1/devices", mock.takeRequest().path)
    val next = mock.takeRequest()
    assertEquals("more", next.requestUrl!!.queryParameter("cursor"))
    assertEquals("50", next.requestUrl!!.queryParameter("limit"))
    assertEquals("Bearer $access", next.getHeader("Authorization"))
  }

  @Test fun incompleteDevicePaginationIsNotPublishedAsComplete() = server { mock, api ->
    mock.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":"more"}"""))
    mock.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
    try { api.hosts(access); fail("expected failure") } catch (_: IOException) { }
  }

  @Test fun cyclicDevicePaginationFailsWithoutPublishingAPartialList() = server { mock, api ->
    repeat(2) { mock.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":"same"}""")) }
    assertTrue(runCatching { api.hosts(access) }.exceptionOrNull() is IOException)
    assertEquals(2, mock.requestCount)
  }

  @Test fun malformedAuthorizationResponseIsNotACredentialRevocation() = server { mock, api ->
    for (response in listOf("", "<html>proxy denied</html>", """{"error":{"code":"AUTH_INVALID"}}""")) {
      mock.enqueue(MockResponse().setResponseCode(401).setBody(response))
      val failure = runCatching { api.refresh(id, refresh) }.exceptionOrNull() as RemoteServerException
      assertFalse(failure.isCredentialRejected)
      assertTrue(failure.retryable)
    }
  }
}
