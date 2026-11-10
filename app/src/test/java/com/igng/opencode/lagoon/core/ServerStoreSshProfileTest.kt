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

  @Test fun sshNativeOpenCodeLocatorAndSiblingPreferencesSurviveRouteChange() {
    val prefs = FakeSharedPreferences()
    val secrets = FakeSharedPreferences()
    val store = ServerStore(prefs, secrets, { "enc:$it" }, { it.removePrefix("enc:") })
    val dsh = ServerProfile("dsh", "DSH", "ssh://old@host:22", "old", kind = ServerKind.DSH,
      sshHostId = "same-host", enabled = false, notifications = false)
    val openCode = dsh.copy(id = "opencode", name = "OpenCode", kind = ServerKind.OPENCODE,
      enabled = true, sshServicePath = "/home/user/.local/state/opencode/service-preview.json")
    store.save(dsh, null); store.save(openCode, null)
    assertEquals(openCode, store.profiles().first { it.id == "opencode" })
    store.updateSshSourceRoute("same-host", "ssh://next@new:2222", "next")
    val changed = store.profiles()
    assertEquals(listOf("dsh", "opencode"), changed.map { it.id })
    assertEquals(dsh.copy(url = "ssh://next@new:2222", username = "next"), changed[0])
    assertEquals(openCode.copy(url = "ssh://next@new:2222", username = "next"), changed[1])
    assertEquals(emptyMap<String, Any?>(), secrets.values.filterValues { it != null })
    store.setProfileEnabled("opencode", false)
    assertEquals(openCode.sshServicePath, store.profiles().last().sshServicePath)
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
