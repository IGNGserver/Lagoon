package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class SshHarnessRoutingTest {
  @Test fun everyNativeProtocolUsesSshFactoryRatherThanParsingTheDisplayUrlAsHttp() {
    for (kind in listOf(ServerKind.OPENCODE, ServerKind.DSH)) {
      val profile = ServerProfile("binding", "native", "ssh://user@example:22", kind = kind,
        sshHostId = "source", sshServicePath = if (kind == ServerKind.OPENCODE) "/state/service.json" else null)
      val marker = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HarnessClient::class.java)) { _, _, _ ->
        error("routing must not invoke protocol methods")
      } as HarnessClient
      var called = 0
      val client = createHarnessClient(profile, ServerCredentials("user"), sshClientFactory = { actual, credentials ->
        assertEquals(profile, actual)
        assertEquals("user", credentials.username)
        called++
        marker
      })
      assertSame(marker, client)
      assertEquals(1, called)
      assertTrue(runCatching { createHarnessClient(profile, ServerCredentials()) }.exceptionOrNull() is IllegalStateException)
    }
  }

  @Test fun sshBindingCannotSilentlySelectRemoteCarrier() {
    val profile = ServerProfile("binding", "wrong carrier", "ssh://user@example:22",
      kind = ServerKind.DSH_REMOTE, sshHostId = "source")
    var calls = 0
    val error = runCatching {
      createHarnessClient(profile, ServerCredentials(), sshClientFactory = { _, _ -> calls++; error("not expected") })
    }.exceptionOrNull()
    assertTrue(error is IllegalArgumentException)
    assertEquals(0, calls)
  }
}
