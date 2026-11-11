package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.RemoteDshEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteDshBootstrapParsingTest {
  @Test fun acceptsOnlyHttpLoopbackWithExplicitValidPort() {
    val parsed = RemoteDshBootstrap.parseLaunchLine("dsh web: http://127.0.0.1:45678/?token=abc")
    assertEquals(RemoteDshEndpoint(45678, "abc"), parsed)
    assertEquals(RemoteDshEndpoint(45679, "abc"),
      RemoteDshBootstrap.parseLaunchLine("http://localhost:45679/?token=abc"))
  }

  @Test fun rejectsPublicHostHttpsDefaultAndInvalidPorts() {
    listOf(
      "https://127.0.0.1:45678/?token=abc",
      "http://10.0.0.1:45678/?token=abc",
      "http://127.0.0.1/?token=abc",
      "http://127.0.0.1:0/?token=abc",
      "http://127.0.0.1:65536/?token=abc",
      "http://user@127.0.0.1:45678/?token=abc",
      "http://127.0.0.1:45678/path?token=abc",
      "http://127.0.0.1:45678/?token=abc&x=y",
      "http://127.0.0.1:45678/?token=abc&token=other",
      "http://127.0.0.1:45678/?x=y&token=abc",
      "http://127.0.0.1:45678/?token=abc%26x=y",
      "http://127.0.0.1:45678/?token=abc#fragment"
    ).forEach { assertNull(it, RemoteDshBootstrap.parseLaunchLine(it)) }
  }
}
