package com.igng.opencode.lagoon.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise Server failures through the real REST/Relay/Noise connection owner. */
class RemoteSessionFailureLifecycleTest {
  private class Store(initial: RemoteDeviceRecord) : RemoteDeviceStore {
    @Volatile var record = initial
    override fun load(slot: String): RemoteDeviceRecord = record
    override fun save(slot: String, record: RemoteDeviceRecord) { this.record = record }
    override fun delete(slot: String) = Unit
  }

  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope): String = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = key
  }

  private fun token() = UUID.randomUUID().toString() + UUID.randomUUID().toString()

  private fun session(host: FakeRemoteHost): Pair<RemoteSession, Store> {
    val origin = canonicalRemoteOrigin(host.url)
    val account = host.accountEmail()
    val saved = RemoteDeviceRecord(origin, account, RemoteIdentity.generate(),
      RemoteTokenPair(token(), Long.MAX_VALUE / 2, token(), Long.MAX_VALUE / 2))
    val store = Store(saved)
    val manager = RemoteDeviceManager(store, "lifecycle-${UUID.randomUUID()}", host.url, account)
    return RemoteSession(host.url, true, host.hostId, manager, RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account) to store
  }

  private fun offlineHost(host: FakeRemoteHost): MockResponse = MockResponse().setBody(JSONObject()
    .put("deviceId", host.hostId).put("name", "fake-host").put("platform", "linux").put("role", "host")
    .put("membershipId", "account:test").put("online", false).put("identityKey", host.hostIdentity.publicKey)
    .put("clientVersion", "0.6.0").put("harnessVersion", "0.2.1-rc.5").toString())

  private fun serverError(status: Int, code: String, retryable: Boolean) = MockResponse().setResponseCode(status)
    .setBody(JSONObject().put("error", JSONObject().put("code", code).put("message", code)
      .put("retryable", retryable)).toString())

  @Test fun expiredAccessTokenAndTemporaryRefreshFailuresRemainRetryable() = runBlocking {
    for (networkFailure in listOf(false, true)) {
      FakeRemoteHost().use { host ->
        host.httpOverride = { request ->
          if (request.path == "/api/v1/auth/refresh") {
            if (networkFailure) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else serverError(503, "SERVER_UNAVAILABLE", true)
          } else null
        }
        host.start()
        val (session, store) = session(host)
        val original = store.record.tokens
        try {
          session.transportCall("harness.transport.describe")
          assertEquals(RemotePhase.READY, session.status.value.phase)
          host.failControlConnection("TOKEN_EXPIRED")
          val failure = withTimeout(5_000) { session.status.first { it.phase == RemotePhase.FAILED } }.lastError
          assertNotNull(failure)
          assertEquals(RemoteFailureKind.NETWORK, failure!!.kind)
          assertTrue("temporary refresh failure must allow reconnect", failure.retryable)
          assertFalse("temporary refresh failure must preserve authorization", store.record.invalidated)
          assertEquals(original, store.record.tokens)
        } finally { session.close() }
      }
    }
  }

  @Test fun explicitlyRevokedCredentialStillRequiresLogin() = runBlocking {
    FakeRemoteHost().use { host ->
      host.httpOverride = { request ->
        if (request.path == "/api/v1/auth/refresh") serverError(403, "DEVICE_REVOKED", false) else null
      }
      host.start()
      val (session, store) = session(host)
      try {
        session.transportCall("harness.transport.describe")
        host.failControlConnection("TOKEN_EXPIRED")
        val failure = withTimeout(5_000) { session.status.first { it.phase == RemotePhase.FAILED } }.lastError
        assertNotNull(failure)
        assertEquals(RemoteFailureKind.AUTH_REQUIRED, failure!!.kind)
        assertFalse(failure.retryable)
        assertTrue(store.record.invalidated)
      } finally { session.close() }
    }
  }

  @Test fun unknownPresenceAttemptsTheAuthenticatedHandshake() = runBlocking {
    for (missingEndpoint in listOf(true, false)) {
      FakeRemoteHost().use { host ->
        host.httpOverride = { request ->
          when (request.path) {
            "/api/v1/devices/${host.hostId}" -> offlineHost(host)
            "/api/v1/devices/${host.hostId}/presence" ->
              if (missingEndpoint) MockResponse().setResponseCode(404).setBody("{}") else MockResponse().setBody("{}")
            else -> null
          }
        }
        host.start()
        val (session, _) = session(host)
        try {
          session.transportCall("harness.transport.describe")
          assertEquals(RemotePhase.READY, session.status.value.phase)
          assertTrue("unknown presence must not block connection", host.requests.contains("GET /ws/v1/connect"))
        } finally { session.close() }
      }
    }
  }

  @Test fun confirmedOfflinePresenceAvoidsHandshake() = runBlocking {
    FakeRemoteHost().use { host ->
      host.httpOverride = { request ->
        when (request.path) {
          "/api/v1/devices/${host.hostId}" -> offlineHost(host)
          "/api/v1/devices/${host.hostId}/presence" -> MockResponse().setBody("""{"online":false}""")
          else -> null
        }
      }
      host.start()
      val (session, _) = session(host)
      try {
        val error = runCatching { session.transportCall("harness.transport.describe") }.exceptionOrNull()
        assertTrue(error is RemoteFailureException)
        assertEquals(RemoteFailureKind.OFFLINE, (error as RemoteFailureException).kind)
        assertTrue(error.retryable)
        assertFalse(host.requests.contains("GET /ws/v1/connect"))
      } finally { session.close() }
    }
  }

  @Test fun presenceServerFailureDoesNotPretendHostIsOffline() = runBlocking {
    FakeRemoteHost().use { host ->
      host.httpOverride = { request ->
        when (request.path) {
          "/api/v1/devices/${host.hostId}" -> offlineHost(host)
          "/api/v1/devices/${host.hostId}/presence" -> serverError(503, "SERVER_UNAVAILABLE", true)
          else -> null
        }
      }
      host.start()
      val (session, _) = session(host)
      try {
        val error = runCatching { session.transportCall("harness.transport.describe") }.exceptionOrNull()
        assertTrue(error is RemoteFailureException)
        assertEquals(RemoteFailureKind.NETWORK, (error as RemoteFailureException).kind)
        assertTrue(error.retryable)
        assertFalse(host.requests.contains("GET /ws/v1/connect"))
      } finally { session.close() }
    }
  }

  @Test fun cancellingPresenceReadDoesNotPublishOfflineOrOpenHandshake() = runBlocking {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    FakeRemoteHost().use { host ->
      host.httpOverride = { request ->
        when (request.path) {
          "/api/v1/devices/${host.hostId}" -> offlineHost(host)
          "/api/v1/devices/${host.hostId}/presence" -> {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test did not release presence response" }
            MockResponse().setBody("""{"online":false}""")
          }
          else -> null
        }
      }
      host.start()
      val (session, _) = session(host)
      try {
        val reading = async(Dispatchers.IO) { session.transportCall("harness.transport.describe") }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        reading.cancel()
        release.countDown()
        withTimeout(5_000) { reading.join() }
        assertTrue(reading.isCancelled)
        assertNotEquals(RemoteFailureKind.OFFLINE, session.status.value.lastError?.kind)
        assertFalse(host.requests.contains("GET /ws/v1/connect"))
      } finally {
        release.countDown()
        session.close()
      }
    }
  }
}
