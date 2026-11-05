package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGroupStoreTest {
  @Test fun versionOneResetsExactlyOnceAndWritesVersionTwo() {
    val prefs = FakeSharedPreferences()
    prefs.values["state"] = """{"version":1,"selected":"g1","groups":[{"id":"g1","name":"旧分组","members":["p1"]}]}"""
    val store = DeviceGroupStore(prefs)

    val first = store.load()
    assertTrue(first.resetLegacy)
    assertTrue(first.state.groups.isEmpty())
    assertNull(first.state.selectedGroupId)
    assertEquals(2, DeviceGroupCodec.version(prefs.values["state"] as String))

    val second = store.load()
    assertFalse(second.resetLegacy)
    assertTrue(second.state.groups.isEmpty())
  }

  @Test fun versionTwoRoundTripsAndSelectionMustExist() {
    val prefs = FakeSharedPreferences()
    val store = DeviceGroupStore(prefs)
    store.save(DeviceGroupState(listOf(DeviceGroup("g", "MacBook", listOf("p1"))), "g"))
    val loaded = store.load()
    assertFalse(loaded.resetLegacy)
    assertEquals("g", loaded.state.selectedGroupId)
    assertEquals(listOf("p1"), loaded.state.groups.single().memberIds)

    store.save(DeviceGroupState(listOf(DeviceGroup("g", "MacBook", listOf("p1"))), "missing"))
    assertNull(store.load().state.selectedGroupId)
  }

  @Test fun corruptDocumentFallsBackWithoutResetting() {
    val prefs = FakeSharedPreferences()
    prefs.values["state"] = "{not-json"
    val loaded = DeviceGroupStore(prefs).load()
    assertFalse(loaded.resetLegacy)
    assertTrue(loaded.state.groups.isEmpty())
  }
}
