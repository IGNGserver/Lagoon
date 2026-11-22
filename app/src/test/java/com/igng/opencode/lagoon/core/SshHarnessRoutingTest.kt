package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SshHarnessRoutingTest {
  @Test fun everyNativeProtocolUsesSshFactoryRatherThanParsingTheDisplayUrlAsHttp() {
    for (protocol in HarnessProtocol.entries) {
      val config = if (protocol == HarnessProtocol.OPENCODE_NATIVE) {
        HarnessConfig.OpenCodeNative("/state/service.json")
      } else {
        HarnessConfig.defaultFor(protocol)
      }
      val profile = ServerProfile(
        id = "h-${protocol.wire}",
        name = protocol.label,
        url = "ssh://user@example:22",
        protocol = protocol,
        deviceId = "ssh-1",
        config = config
      )
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
}
