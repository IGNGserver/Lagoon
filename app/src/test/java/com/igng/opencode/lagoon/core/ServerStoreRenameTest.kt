package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerStoreRenameTest {
  @Test fun renameTouchesOnlyTheDisplayName() {
    val prefs = FakeSharedPreferences()
    val secrets = FakeSharedPreferences()
    val store = ServerStore(prefs, secrets, { "enc:$it" }, { it.removePrefix("enc:") })
    val profile = ServerProfile(
      id = "a", name = "ubuntu-dev (2)", url = "https://hub.example/", username = "lviwang",
      autoConnect = false, notifications = false, kind = ServerKind.DSH_REMOTE,
      remoteHostId = "host-1", remoteHostFingerprint = "abcdef012345"
    )
    store.save(profile, password = "token")
    store.rename(profile.id, "ubuntu-dev")
    assertEquals(profile.copy(name = "ubuntu-dev"), store.profiles().single())
    assertEquals("token", store.credentials(profile.id).password)
  }
}
