package com.igng.opencode.lagoon.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteDeviceStoreTest {
  /** In-memory equivalent of the platform store: the codec and manager semantics are what matter here. */
  private class TestStore : RemoteDeviceStore {
    val values = mutableMapOf<String, String>()
    override fun load(slot: String): RemoteDeviceRecord? = values[slot]?.let(RemoteDeviceCodec::decode)
    override fun save(slot: String, record: RemoteDeviceRecord) { values[slot] = RemoteDeviceCodec.encode(record) }
  }

  private fun tokens(accessExpiry: Long = 200_000) = RemoteTokenPair("device-access-token-123", accessExpiry, "device-refresh-token-123", 900_000)

  @Test fun storesSealedIdentityAndSerializesRefreshAcrossManagers() = runBlocking {
    val store = TestStore()
    val first = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com",
      { _, _ -> tokens(100_000) }, { _, _ -> tokens(300_000) })
    val second = RemoteDeviceManager(store, "phone", "https://hub.example/", "user@example.com",
      { _, _ -> tokens(100_000) }, { _, _ -> tokens(300_000) })
    val registerer: suspend (RemoteClientDescriptor, String) -> RemoteTokenPair = { _, _ -> tokens(100_000) }
    val registered = first.register("Lagoon", "jvm", "1.0", "account-token-123", registerer)
    val stored = store.load("phone")!!
    assertEquals(registered.identity.privateKey, stored.identity.privateKey)
    assertEquals(registered.tokens!!.refreshToken, stored.tokens!!.refreshToken)
    var refreshes = 0
    val results = listOf(first, second).map { manager -> async {
      manager.access(90_000) { _, _ -> refreshes++; delay(30); tokens(300_000) }
    } }.awaitAll()
    assertEquals(1, refreshes)
    assertEquals(results[0].identity.deviceId, results[1].identity.deviceId)
    assertEquals(RemoteDeviceRecord.Transition.NONE, store.load("phone")!!.transition)
  }

  @Test fun unknownRefreshResultNeverReplaysAndRequiresReauthentication() = runBlocking {
    val store = TestStore()
    val manager = RemoteDeviceManager(store, "hub", "https://hub.example", "user@example.com",
      { _, _ -> tokens(100_000) }, { _, _ -> tokens() })
    val before = manager.register("Lagoon", "jvm", "1.0", "account-token-123") { _, _ -> tokens(100_000) }
    try { manager.access(90_000) { _, _ -> throw IOException("response lost") }; fail() } catch (_: IOException) { }
    assertEquals(RemoteDeviceRecord.Transition.REFRESHING, store.load("hub")!!.transition)
    try { manager.access(90_000) { _, _ -> fail("must not replay"); tokens() }; fail() } catch (_: IOException) { }
    val recovered = manager.register("Lagoon", "jvm", "1.0", "account-token-123") { descriptor, _ ->
      assertEquals(before.identity.deviceId, descriptor.deviceId)
      tokens()
    }
    assertEquals(RemoteDeviceRecord.Transition.NONE, recovered.transition)
    try { RemoteDeviceManager(store, "hub", "https://other.example", "user@example.com").access { _, _ -> tokens() }; fail() }
    catch (_: IOException) { }
  }

  @Test fun corruptionNeverCreatesANewIdentity() {
    val store = TestStore()
    store.values["hub"] = "invalid"
    try { store.load("hub"); fail() } catch (_: IOException) { }
  }
}
