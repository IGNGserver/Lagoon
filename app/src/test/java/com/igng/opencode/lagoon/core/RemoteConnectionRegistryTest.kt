package com.igng.opencode.lagoon.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RemoteConnectionRegistryTest {
  private class Store : RemoteDeviceStore {
    override fun load(slot: String): RemoteDeviceRecord? = null
    override fun save(slot: String, record: RemoteDeviceRecord) { }
    override fun delete(slot: String) { }
  }

  private class Pins : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope): String? = null
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = publicKey
  }

  private fun session(): RemoteSession {
    val manager = RemoteDeviceManager(Store(), "slot", "https://hub.example", "user@example.com")
    return RemoteSession("https://hub.example", false, "0b9d2c4e-1111-4222-8333-444455556666", manager,
      RemotePeerTrust(Pins()), "user@example.com")
  }

  @After fun cleanup() { RemoteConnectionRegistry.clearForTest() }

  @Test fun sharesOneSessionAndClosesOnLastRelease() {
    var created = 0
    val key = "remote|https://hub.example/|user@example.com|host"
    val first = RemoteConnectionRegistry.acquire(key) { created++; session() }
    val second = RemoteConnectionRegistry.acquire(key) { created++; session() }
    assertSame(first, second)
    assertEquals(1, created)
    assertEquals(1, RemoteConnectionRegistry.size())

    RemoteConnectionRegistry.release(key)
    assertEquals("还有持有者时不能关闭", 1, RemoteConnectionRegistry.size())

    RemoteConnectionRegistry.release(key)
    assertEquals(0, RemoteConnectionRegistry.size())

    // 释放之后再取会新建一条（不会复用已关闭会话）。
    RemoteConnectionRegistry.acquire(key) { created++; session() }
    assertEquals(2, created)
  }

  @Test fun differentHostsKeepSeparateSessions() {
    val a = RemoteConnectionRegistry.acquire("a") { session() }
    val b = RemoteConnectionRegistry.acquire("b") { session() }
    assertEquals(2, RemoteConnectionRegistry.size())
    assertEquals(false, a === b)
  }
}
