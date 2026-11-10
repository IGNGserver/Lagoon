package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RemoteIdentitySlotsTest {
  private val origin = "https://hub.example/"
  private val account = "user@example.org"
  private val host = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"

  private class Store : RemoteDeviceStore {
    val records = mutableMapOf<String, RemoteDeviceRecord>()
    override fun load(slot: String): RemoteDeviceRecord? = records[slot]
    override fun save(slot: String, record: RemoteDeviceRecord) { records[slot] = record }
    override fun delete(slot: String) { records.remove(slot) }
  }

  private fun record(origin: String, account: String) = RemoteDeviceRecord(
    origin, account, RemoteIdentity.generate(), tokens = null
  )

  @Test fun newSlotIsStableAndScopedByOriginAccountAndHost() {
    val one = scopedRemoteDeviceSlot(origin, account, host)
    assertEquals(one, scopedRemoteDeviceSlot("https://hub.example", account, host))
    assertNotEquals(one, scopedRemoteDeviceSlot(origin, "other@example.org", host))
    assertNotEquals(one, scopedRemoteDeviceSlot("https://other.example", account, host))
  }

  @Test fun matchingLegacyHostSlotIsReused() {
    val store = Store()
    val legacy = RemoteIdentitySlots.legacyHostSlot(host)
    store.records[legacy] = record(canonicalRemoteOrigin(origin), account)
    assertEquals(legacy, resolveRemoteDeviceSlot(store, origin, account, host))
  }

  @Test fun legacyHostSlotForOtherAccountIsSkippedAndScopedSlotCreated() {
    val store = Store()
    val legacy = RemoteIdentitySlots.legacyHostSlot(host)
    store.records[legacy] = record(canonicalRemoteOrigin(origin), "other@example.org")
    val resolved = resolveRemoteDeviceSlot(store, origin, account, host)
    assertEquals(scopedRemoteDeviceSlot(origin, account, host), resolved)
  }

  @Test fun explicitLegacyProfileSlotIsReusedWhenScopeMatches() {
    val store = Store()
    val profile = "profile-legacy"
    store.records[profile] = record(canonicalRemoteOrigin(origin), account)
    assertEquals(profile, resolveRemoteDeviceSlot(store, origin, account, host, listOf(profile)))
  }

  @Test fun scopedSlotBoundToAnotherAccountFailsClosed() {
    val store = Store()
    val scoped = scopedRemoteDeviceSlot(origin, account, host)
    store.records[scoped] = record(canonicalRemoteOrigin(origin), "other@example.org")
    assertThrows(IllegalStateException::class.java) {
      resolveRemoteDeviceSlot(store, origin, account, host)
    }
  }
}
