package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteDevicePairingTest {
  private val sessionId = "019a0000-0000-7000-8000-000000000001"
  private val hostId = "019a0000-0000-7000-8000-000000000002"
  private val devicePrivateKeyB64 = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
  private val devicePublicKeyB64 = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9_AsrhtHHw"
  private val serverPublicKeyB64 = "WGmv9FBUlzLLqu1eXfmzCm2jHLDldCutWtShp2jxpns"
  private val hostKeyB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 11).toByte() })
  private val ticketB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 31).toByte() })

  @Test fun upstreamProofJsonVectorsMatchExactly() {
    val decoder = Base64.getUrlDecoder()
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val priv = decoder.decode(devicePrivateKeyB64)
    val derivedPub = ByteArray(32)
    X25519.scalarMultBase(priv, 0, derivedPub, 0)
    assertEquals(devicePublicKeyB64, encoder.encodeToString(derivedPub))

    // Vector 1: host/status
    val hostKey = remotePairingProofKey(priv, serverPublicKeyB64, sessionId, "host")
    assertEquals("agYkw6R_tCbGdoMvyfLpezYAYzBr7JM2pKse6XfOmR0", encoder.encodeToString(hostKey))
    assertEquals(
      "91fmfY_gpU9ToA2cU13yoyRb9IdFqoqChvDUu11Befg",
      remotePairingProof(
        priv,
        serverPublicKeyB64,
        sessionId,
        "host",
        "/api/v1/device-pairing/sessions/$sessionId/host/status",
        "{}",
      ),
    )

    // Vector 2: mobile/status
    val mobileKey = remotePairingProofKey(priv, serverPublicKeyB64, sessionId, "mobile")
    assertEquals("ODTTY63GYngXCFPEeyAxH0CBCHSisdMUYwzkVSlv_HI", encoder.encodeToString(mobileKey))
    assertEquals(
      "3izg2tEapIkukkVFqJc7cPhHHtGLUEgHzs8f_O0r-Ag",
      remotePairingProof(
        priv,
        serverPublicKeyB64,
        sessionId,
        "mobile",
        "/api/v1/device-pairing/sessions/$sessionId/mobile/status",
        "{}",
      ),
    )

    // Vector 3: host/confirm with exact body JSON
    assertEquals(
      "G3Ujz3E507BAwAtWVyPZCN8KJNb4-sGy84ff4nLGi18",
      remotePairingProof(
        priv,
        serverPublicKeyB64,
        sessionId,
        "host",
        "/api/v1/device-pairing/sessions/$sessionId/host/confirm",
        """{"submissionId":"fixed-submission-0001","verificationCode":"00123456"}""",
      ),
    )
  }

  @Test fun lowOrderX25519PublicKeyRejected() {
    val priv = Base64.getUrlDecoder().decode(devicePrivateKeyB64)
    val zeroKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    val error = try {
      remotePairingProof(priv, zeroKey, sessionId, "mobile", "/api/v1/device-pairing/sessions/$sessionId/mobile/status", "{}")
      null
    } catch (e: RemotePairingException) {
      e
    }
    assertNotNull(error)
    assertEquals("PAIRING_PROOF_INVALID", error!!.code)
  }

  @Test fun fingerprintUsesFull64CharSha256InGroupsOfFour() {
    val fp = remotePairingFingerprint(hostKeyB64)
    assertEquals(64, fp.length)
    assertTrue(fp.matches(Regex("[0-9a-f]{64}")))
    val formatted = formatRemotePairingFingerprint(fp)
    assertEquals(16, formatted.split(' ').size)
    assertTrue(formatted.split(' ').all { it.length == 4 })
  }

  @Test fun parseScanAcceptsTicketAndLegacyAndRejectsUnsafeInputs() {
    val now = 1_790_000_000_000L

    // Valid short HTTPS ticket URL
    val httpsQr = "https://dsh.r2049.cn/pair#v1.$ticketB64"
    val ticketScan = parseRemotePairingScan(httpsQr, allowCleartext = false, nowMillis = now)
    assertTrue(ticketScan is RemotePairingScan.Ticket)
    assertEquals("https://dsh.r2049.cn/", ticketScan.origin)
    assertFalse(ticketScan.isCleartext)
    assertEquals(ticketB64, (ticketScan as RemotePairingScan.Ticket).ticket)
    assertFalse(ticketScan.toString().contains(ticketB64))

    // Cleartext HTTP requires explicit consent
    val httpQr = "http://192.168.1.10:8080/pair#v1.$ticketB64"
    val httpRejected = runCatching { parseRemotePairingScan(httpQr, allowCleartext = false, nowMillis = now) }.exceptionOrNull()
    assertTrue(httpRejected is RemotePairingException)
    assertEquals("CLEARTEXT_HTTP_DISABLED", (httpRejected as RemotePairingException).code)
    val httpAccepted = parseRemotePairingScan(httpQr, allowCleartext = true, nowMillis = now) as RemotePairingScan.Ticket
    assertTrue(httpAccepted.isCleartext)
    assertEquals("http://192.168.1.10:8080/", httpAccepted.origin)

    // Valid legacy JSON QR
    val legacyJson = JSONObject()
      .put("type", "dsh.remote.pairing")
      .put("v", 1)
      .put("serverUrl", "https://dsh.r2049.cn")
      .put("sessionId", sessionId)
      .put("expiresAt", now + 180_000L)
      .put("serverPublicKey", serverPublicKeyB64)
      .put("scanSecret", ticketB64)
      .put(
        "host",
        JSONObject()
          .put("deviceId", hostId)
          .put("name", "Mac Studio")
          .put("role", "host")
          .put("platform", "darwin")
          .put("identityKey", hostKeyB64),
      )
      .toString()
    val legacy = parseRemotePairingScan(legacyJson, allowCleartext = false, nowMillis = now)
    assertTrue(legacy is RemotePairingScan.Legacy)
    assertEquals(sessionId, (legacy as RemotePairingScan.Legacy).offer.sessionId)
    assertFalse(legacy.offer.toString().contains(ticketB64))

    // Malformed / unsafe cases must fail closed
    val invalidInputs = listOf(
      "https://dsh.r2049.cn/other#v1.$ticketB64",
      "https://dsh.r2049.cn/pair?foo=bar#v1.$ticketB64",
      "https://user:pass@dsh.r2049.cn/pair#v1.$ticketB64",
      // Non-canonical 43rd base64url char ('B' has low unused bits set compared to 'A')
      "https://dsh.r2049.cn/pair#v1.${ticketB64.dropLast(1)}B",
      "a".repeat(4097),
      // Expired legacy QR
      legacyJson.replace("${now + 180_000L}", "${now - 121_000L}"),
      // Far future legacy QR
      legacyJson.replace("${now + 180_000L}", "${now + 601_000L}"),
      // Legacy serverUrl with trailing slash
      legacyJson.replace("\"https://dsh.r2049.cn\"", "\"https://dsh.r2049.cn/\""),
      // host identityKey == serverPublicKey
      legacyJson.replace(hostKeyB64, serverPublicKeyB64),
    )
    for (bad in invalidInputs) {
      assertTrue("Expected failure for: $bad", runCatching {
        parseRemotePairingScan(bad, allowCleartext = false, nowMillis = now)
      }.isFailure)
    }
  }

  @Test fun endToEndResolveClaimPollAndFailClosedIdentityMismatch() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val origin = mock.url("/").toString()
      val now = System.currentTimeMillis()
      val mobileIdentity = RemoteIdentity.generate()
      val mobileDevice = RemotePairingDevice(
        deviceId = mobileIdentity.deviceId,
        name = "Lagoon Phone",
        role = "client",
        platform = "android",
        identityKey = mobileIdentity.publicKey,
        clientVersion = "1.0",
      )
      val hostJson = JSONObject()
        .put("deviceId", hostId)
        .put("name", "Linux Workstation")
        .put("role", "host")
        .put("platform", "linux")
        .put("identityKey", hostKeyB64)
      val mobileJson = mobileDevice.toJson()

      // 1) Resolve
      mock.enqueue(
        MockResponse().setBody(
          JSONObject()
            .put("v", 1)
            .put("sessionId", sessionId)
            .put("expiresAt", now + 180_000L)
            .put("serverPublicKey", serverPublicKeyB64)
            .put("scanSecret", ticketB64)
            .put("host", hostJson)
            .toString(),
        ),
      )
      // 2) Claim + retry with identical frozen body
      val claimResponse = JSONObject()
        .put("state", "claimed")
        .put("verificationCode", "00123456")
        .put("expiresAt", now + 180_000L)
        .toString()
      mock.enqueue(MockResponse().setBody(claimResponse))
      mock.enqueue(MockResponse().setBody(claimResponse))
      // 3) Status: approved
      mock.enqueue(
        MockResponse().setBody(
          JSONObject()
            .put("v", 1)
            .put("sessionId", sessionId)
            .put("state", "approved")
            .put("expiresAt", now + 180_000L)
            .put("host", hostJson)
            .put("mobile", mobileJson)
            .toString(),
        ),
      )
      // 4) Status: completed
      val deviceOnlyAccount = "dsh-device:019a0000-0000-7000-8000-000000000099"
      mock.enqueue(
        MockResponse().setBody(
          JSONObject()
            .put("v", 1)
            .put("sessionId", sessionId)
            .put("state", "completed")
            .put("expiresAt", now + 180_000L)
            .put("recoverUntil", now + 480_000L)
            .put("host", hostJson)
            .put("mobile", mobileJson)
            .put(
              "credentials",
              JSONObject()
                .put("account", deviceOnlyAccount)
                .put("accountKind", "device_only")
                .put("device", mobileJson)
                .put("inboundOnly", false)
                .put("pairingRestricted", true)
                .put(
                  "tokens",
                  JSONObject()
                    .put("accessToken", "paired-access-token-123456")
                    .put("accessTokenExpiresAt", now + 3_600_000L)
                    .put("refreshToken", "paired-refresh-token-123456")
                    .put("refreshTokenExpiresAt", now + 30L * 86_400_000L),
                ),
            )
            .toString(),
        ),
      )
      // 5) Status tampering: Host key changed -> must fail closed!
      val swappedHostKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 77).toByte() })
      mock.enqueue(
        MockResponse().setBody(
          JSONObject()
            .put("v", 1)
            .put("sessionId", sessionId)
            .put("state", "claimed")
            .put("host", JSONObject(hostJson.toString()).put("identityKey", swappedHostKey))
            .put("mobile", mobileJson)
            .toString(),
        ),
      )
      // 6) 429 rate limited -> carries retryAfterMs
      mock.enqueue(
        MockResponse().setResponseCode(429).setBody(
          """{"error":{"code":"RATE_LIMITED","message":"slow down","retryable":true}}""",
        ),
      )
      // 7) 404 on resolve
      mock.enqueue(
        MockResponse().setResponseCode(404).setBody(
          """{"error":{"code":"PAIRING_NOT_FOUND","message":"not found","retryable":false}}""",
        ),
      )

      val client = RemotePairingClient(RemoteServerApi(origin, allowCleartext = true))
      val scan = parseRemotePairingScan("${origin}pair#v1.$ticketB64", allowCleartext = true, nowMillis = now)
      val offer = client.resolve(scan, nowMillis = now)
      assertEquals(hostId, offer.host.deviceId)

      val claim1 = client.claim(offer, mobileIdentity, mobileDevice)
      assertEquals("00123456", claim1.verificationCode)
      val claim2 = client.claim(offer, mobileIdentity, mobileDevice, claim1.frozenRequestBody)
      assertEquals(claim1.verificationCode, claim2.verificationCode)

      val reqResolve = mock.takeRequest()
      assertEquals("/api/v1/device-pairing/resolve", reqResolve.path)
      val reqClaim1 = mock.takeRequest()
      val reqClaim2 = mock.takeRequest()
      assertEquals(reqClaim1.getHeader("X-DSH-Pairing-Proof"), reqClaim2.getHeader("X-DSH-Pairing-Proof"))
      assertEquals(reqClaim1.body.readUtf8(), reqClaim2.body.readUtf8())

      val approved = client.pollStatus(offer, mobileIdentity, mobileDevice, nowMillis = now)
      assertEquals(RemotePairingState.APPROVED, approved.state)
      assertNull(approved.credentials)

      val completed = client.pollStatus(offer, mobileIdentity, mobileDevice, nowMillis = now)
      assertEquals(RemotePairingState.COMPLETED, completed.state)
      assertEquals(deviceOnlyAccount, completed.credentials?.account)
      assertTrue(completed.credentials?.pairingRestricted == true)

      val mismatch = runCatching {
        client.pollStatus(offer, mobileIdentity, mobileDevice, nowMillis = now)
      }.exceptionOrNull()
      assertTrue(mismatch is RemotePairingException)
      assertEquals("PAIRING_IDENTITY_MISMATCH", (mismatch as RemotePairingException).code)

      val rateLimited = runCatching {
        client.pollStatus(offer, mobileIdentity, mobileDevice, nowMillis = now)
      }.exceptionOrNull()
      assertTrue(rateLimited is RemotePairingException)
      assertNotNull((rateLimited as RemotePairingException).retryAfterMs)

      val notFound = runCatching { client.resolve(scan, nowMillis = now) }.exceptionOrNull()
      assertTrue(notFound is RemotePairingException)
      assertEquals("PAIRING_NOT_FOUND", (notFound as RemotePairingException).code)
    }
  }
}
