package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AndroidRemoteCatalogIntegrityTest {
  private fun catalog(account: String = "owner@example.org") = RemoteCatalog(
    "https://hub.example/", account, null, "Phone", emptyList(), 1)

  @Test fun corruptDocumentCannotBeReplacedOrClearedByOneAccountWrite() {
    val preferences = FakeSharedPreferences()
    preferences.edit().putString("catalogs", "{broken").commit()
    val store = AndroidRemoteCatalogStore(preferences)
    assertTrue(store.load().isEmpty())
    assertTrue(runCatching { store.save(catalog()) }.exceptionOrNull() is IOException)
    assertTrue(runCatching { store.clear("https://hub.example/", "owner@example.org") }.exceptionOrNull() is IOException)
    assertEquals("{broken", preferences.getString("catalogs", null))
  }

  @Test fun malformedEntryCannotEraseAnOtherwiseReadableAccount() {
    val preferences = FakeSharedPreferences()
    val raw = JSONArray().put(RemoteCatalogCodec.encode(catalog())).put(42).toString()
    preferences.edit().putString("catalogs", raw).commit()
    val store = AndroidRemoteCatalogStore(preferences)
    assertTrue(runCatching { store.save(catalog("other@example.org")) }.exceptionOrNull() is IOException)
    assertEquals(raw, preferences.getString("catalogs", null))
  }

  @Test fun accountEditsPreserveOtherAccountsAndNeverWriteCredentials() {
    val preferences = FakeSharedPreferences()
    val store = AndroidRemoteCatalogStore(preferences)
    store.save(catalog())
    store.save(catalog("other@example.org"))
    assertEquals(2, store.load().size)
    store.clear("https://hub.example/", "owner@example.org")
    assertEquals("other@example.org", store.load().single().account)
    assertFalse(preferences.getString("catalogs", "")!!.contains("accessToken"))
  }
}
