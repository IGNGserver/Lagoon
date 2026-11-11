package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ServerStoreIntegrityTest {
  @Test fun unreadableProfilesCannotBeOverwrittenByAnyProfileWriter() {
    for (raw in listOf("{broken", "[42]", """[{"id":"same"},{"id":"same"}]""",
      """[{"id":"future","kind":"unknown-native-protocol","url":"https://future.example"}]""")) {
      val preferences = FakeSharedPreferences()
      val secrets = FakeSharedPreferences()
      val store = ServerStore(preferences, secrets, { "enc:$it" }, { it.removePrefix("enc:") })
      preferences.edit().putString("profiles", raw).commit()
      secrets.edit().putString("saved", "keep-this-secret").commit()
      assertTrue(store.profiles().isEmpty())
      val profile = ServerProfile("new", "New", "https://new.example")
      val writers = listOf<() -> Unit>(
        { store.save(profile, "new-password") },
        { store.rename("saved", "Renamed") },
        { store.setProfileEnabled("saved", false); Unit },
        { store.delete("saved") }
      )
      for (writer in writers) {
        assertTrue(runCatching(writer).exceptionOrNull() is IOException)
        assertEquals(raw, preferences.getString("profiles", null))
        assertEquals("keep-this-secret", secrets.getString("saved", null))
        assertFalse(secrets.contains("new"))
      }
    }
  }
}
