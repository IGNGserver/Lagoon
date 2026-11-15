package com.igng.opencode.lagoon.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    assertSame(first.session, second.session)
    assertEquals(1, created)
    assertEquals(1, RemoteConnectionRegistry.size())

    first.close()
    assertEquals("还有持有者时不能关闭", 1, RemoteConnectionRegistry.size())
    first.close()
    assertFalse("同一租约只能释放一次", second.session.isClosed)

    second.close()
    assertEquals(0, RemoteConnectionRegistry.size())
    assertTrue(second.session.isClosed)

    // 释放之后再取会新建一条（不会复用已关闭会话）。
    val replacement = RemoteConnectionRegistry.acquire(key) { created++; session() }
    assertEquals(2, created)
    replacement.close()
  }

  @Test fun differentHostsKeepSeparateSessions() {
    val a = RemoteConnectionRegistry.acquire("a") { session() }
    val b = RemoteConnectionRegistry.acquire("b") { session() }
    assertEquals(2, RemoteConnectionRegistry.size())
    assertEquals(false, a.session === b.session)
    a.close()
    b.close()
  }

  @Test fun oldHostLeasesCannotReleaseAReplacementAfterEviction() {
    val origin = "https://hub.example"
    val account = "user@example.com"
    val host = "0b9d2c4e-1111-4222-8333-444455556666"
    val key = "remote|${canonicalRemoteOrigin(origin)}|$account|$host"
    val oldFirst = RemoteConnectionRegistry.acquire(key) { session() }
    val oldSecond = RemoteConnectionRegistry.acquire(key) { session() }

    RemoteConnectionRegistry.evictHost(origin, account, host)
    assertTrue(oldFirst.session.isClosed)
    val replacement = RemoteConnectionRegistry.acquire(key) { session() }
    oldFirst.close()
    oldFirst.close()
    oldSecond.close()

    assertEquals(1, RemoteConnectionRegistry.size())
    assertFalse("旧连接释放不能影响同一 host 的新连接", replacement.session.isClosed)
    replacement.close()
    assertEquals(0, RemoteConnectionRegistry.size())
    assertTrue(replacement.session.isClosed)
  }

  @Test fun oldAccountLeaseCannotCloseReconnectedHost() {
    val origin = "https://hub.example"
    val account = "user@example.com"
    val host = "0b9d2c4e-1111-4222-8333-444455556666"
    val key = "remote|${canonicalRemoteOrigin(origin)}|$account|$host"
    val old = RemoteConnectionRegistry.acquire(key) { session() }
    RemoteConnectionRegistry.evictAccount(origin, account)
    val fresh = RemoteConnectionRegistry.acquire(key) { session() }

    old.close()
    assertFalse(fresh.session.isClosed)
    assertEquals(1, RemoteConnectionRegistry.size())
    fresh.close()
    assertTrue(fresh.session.isClosed)
  }
}
