package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceDiscoveryStoreTest {
  private val now = 1_700_000_000_000L

  private fun status() = HarnessDiscoveryStatus(
    installed = DiscoveryValue.YES, configured = DiscoveryValue.YES, listening = DiscoveryValue.YES,
    authenticated = DiscoveryValue.YES, compatible = DiscoveryValue.YES, available = DiscoveryValue.YES,
    observedAtMs = now, freshUntilMs = now + 600_000, completeness = DiscoveryCompleteness.COMPLETE
  )

  private fun discovery(deviceId: String = "host-1") = DeviceDiscovery(
    deviceId = deviceId,
    observedAtMs = now,
    harnesses = listOf(
      DiscoveredHarness(HarnessProtocolId.DSH_TYPERT, null, "DeepSeek Harness", status()),
      DiscoveredHarness(HarnessProtocolId.OPENCODE_NATIVE, "svc-1", "OpenCode", status(), detail = "ready")
    )
  )

  @Test fun roundTripsAnObservationThroughANewStoreInstance() {
    val prefs = FakeSharedPreferences()
    DeviceDiscoveryStore(prefs) { now }.put(discovery())

    val restored = DeviceDiscoveryStore(prefs) { now }.get("host-1")
    assertEquals(discovery(), restored)
    assertTrue(restored!!.isFresh(now + 1_000))
    assertTrue(restored.harnesses.any { it.locator == "svc-1" && it.detail == "ready" })
  }

  @Test fun observationsBecomeStaleInsteadOfDisappearing() {
    val prefs = FakeSharedPreferences()
    DeviceDiscoveryStore(prefs) { now }.put(discovery())
    val restored = DeviceDiscoveryStore(prefs) { now }.get("host-1")!!
    assertFalse(restored.isFresh(now + DeviceDiscovery.DEFAULT_TTL_MS + 1))
    // Still readable: a stopped device shows the last observation with an explicit stale marker.
    assertEquals(2, restored.harnesses.size)
  }

  @Test fun corruptDocumentIsKeptAndNeverOverwritten() {
    val prefs = FakeSharedPreferences()
    prefs.values["discovery"] = "{not-json"
    val store = DeviceDiscoveryStore(prefs) { now }

    assertNull(store.get("host-1"))
    var threw = false
    try {
      store.put(discovery())
    } catch (_: IOException) {
      threw = true
    }
    assertTrue("损坏的观测缓存不能被空表覆盖", threw)
    assertEquals("{not-json", prefs.values["discovery"])
  }

  @Test fun futureSchemaIsNotOverwritten() {
    val prefs = FakeSharedPreferences()
    prefs.values["discovery"] = """{"version":9,"devices":{}}"""
    val store = DeviceDiscoveryStore(prefs) { now }

    var threw = false
    try {
      store.put(discovery())
    } catch (_: IOException) {
      threw = true
    }
    assertTrue(threw)
    assertEquals("""{"version":9,"devices":{}}""", prefs.values["discovery"])
  }

  @Test fun clearingOneDeviceKeepsTheOthers() {
    val prefs = FakeSharedPreferences()
    val store = DeviceDiscoveryStore(prefs) { now }
    store.put(discovery("host-1"))
    store.put(discovery("host-2"))

    store.clear("host-1")
    assertNull(DeviceDiscoveryStore(prefs) { now }.get("host-1"))
    assertEquals(discovery("host-2"), DeviceDiscoveryStore(prefs) { now }.get("host-2"))
  }
}
