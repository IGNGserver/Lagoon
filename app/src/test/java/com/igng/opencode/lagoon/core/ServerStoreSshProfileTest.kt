package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerStoreSshProfileTest {
  @Test fun sshHostIdRoundTripsWithoutTouchingHttpCredentialOrigins() {
    val prefs = FakeSharedPreferences()
    val secrets = FakeSharedPreferences()
    val store = ServerStore(prefs, secrets, { "enc:$it" }, { it.removePrefix("enc:") })
    val profile = ServerProfile(
      id = "ssh-profile", name = "工作站", url = "ssh://me@192.168.1.10:22", username = "me",
      kind = ServerKind.DSH, sshHostId = "ssh-1"
    )
    store.save(profile, password = null)
    assertEquals(listOf(profile), store.profiles())
    assertEquals("me", store.credentials(profile.id).username)
    assertEquals("", store.credentials(profile.id).password)
    assertNull(secrets.values["ssh-profile"])
  }

  @Test fun deletingOneProfileKeepsAnotherSshHostId() {
    val store = ServerStore(FakeSharedPreferences(), FakeSharedPreferences(), { "enc:$it" }, { it.removePrefix("enc:") })
    val first = ServerProfile("a", "A", "ssh://me@a:22", "me", kind = ServerKind.DSH, sshHostId = "ssh-a")
    val second = ServerProfile("b", "B", "ssh://me@b:22", "me", kind = ServerKind.DSH, sshHostId = "ssh-b")
    store.save(first, null)
    store.save(second, null)
    store.delete("a")
    assertEquals(listOf(second), store.profiles())
  }
}
