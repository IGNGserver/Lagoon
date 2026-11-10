package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class NotificationClientLeaseTest {
  @Test fun temporaryHandleIsReleasedExactlyOnceByItsOwner() {
    var releases = 0
    val client = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HarnessClient::class.java)) { _, method, _ ->
      if (method.name == "close") { releases++; Unit } else error("Unexpected ${method.name}")
    } as HarnessClient
    val owned = LagoonController.NotificationClientLease(client, owned = true)
    owned.use { }
    owned.close()
    assertEquals(1, releases)
    LagoonController.NotificationClientLease(client, owned = false).use { }
    assertEquals("Borrowed monitor handle must not be closed", 1, releases)
  }
}
