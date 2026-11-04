package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Runs against a real instance of the pinned upstream self-hosted Server, started in local scratch. */
class RemoteServerIntegrationTest {
  @Test fun accountRegistrationRefreshAndHostDiscoveryInteroperateWithUpstreamServer() = runBlocking {
    val serverPort = System.getenv("DSH_REMOTE_TEST_PORT")?.toIntOrNull()
      ?: throw org.junit.AssumptionViolatedException("point DSH_REMOTE_TEST_PORT at the pinned upstream smoke server")
    val address = System.getenv("DSH_REMOTE_TEST_URL") ?: "http://127.0.0.1:$serverPort"
    val account = System.getenv("DSH_REMOTE_TEST_ACCOUNT") ?: "lagoon-poc@example.invalid"
    val password = System.getenv("DSH_REMOTE_TEST_PASSWORD") ?: throw org.junit.AssumptionViolatedException("DSH_REMOTE_TEST_PASSWORD is required")
    val api = RemoteServerApi(address, allowCleartext = true)
    val deviceId = UUID.randomUUID().toString()
    val keyPair = com.southernstorm.noise.protocol.Noise.createDH("25519")
    keyPair.generateKeyPair()
    val publicKey = ByteArray(32).also { keyPair.getPublicKey(it, 0) }
      .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    keyPair.destroy()
    val login = api.login(account, password)
    val tokens = api.register(RemoteClientDescriptor(deviceId, "Lagoon REST smoke", "lagoon-test", publicKey, "0.1.0"), login.token)
    assertTrue(tokens.accessTokenExpiresAt > System.currentTimeMillis())
    val devices = api.hosts(tokens.accessToken)
    assertTrue(devices.none { it.publicKey != null }) // identity keys are never trusted from list responses
    val rotated = api.refresh(deviceId, tokens.refreshToken)
    assertNotEquals(tokens.refreshToken, rotated.refreshToken)
    try { api.refresh(deviceId, tokens.refreshToken); fail("one-time refresh token must be consumed") }
    catch (error: com.igng.opencode.lagoon.core.RemoteServerException) { assertEquals(401, error.status) }
  }
}
