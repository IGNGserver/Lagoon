package com.igng.opencode.lagoon.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStoreTest {
  private fun newStore(
    prefs: FakeSharedPreferences = FakeSharedPreferences(),
    secrets: FakeSharedPreferences = FakeSharedPreferences()
  ): Triple<DeviceStore, FakeSharedPreferences, FakeSharedPreferences> {
    val store = DeviceStore(prefs, secrets, { "enc:$it" }, { it.removePrefix("enc:") })
    return Triple(store, prefs, secrets)
  }

  @Test fun savesLoadsDevicesWithEncryptedSecretsAndClearsStaleSecretKindsOnAuthChange() {
    val (store, _, secretsPrefs) = newStore()
    val dsh = DeviceHarness(
      id = "h-dsh",
      deviceId = "ssh-dev1",
      protocol = HarnessProtocol.DSH,
      config = HarnessConfig.Dsh(launchCommand = "dsh web --port 0", workingDirectory = "/srv/work")
    )
    val oc = DeviceHarness(
      id = "h-oc",
      deviceId = "ssh-dev1",
      protocol = HarnessProtocol.OPENCODE_NATIVE,
      config = HarnessConfig.OpenCodeNative("/home/dev/.opencode/service.json")
    )
    val device = SshDevice(
      id = "ssh-dev1",
      name = "Studio",
      host = "10.0.0.10",
      port = 2222,
      user = "dev",
      auth = SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK,
      keyId = "key-1",
      hostKeyType = "ssh-ed25519",
      hostKeyFingerprint = "SHA256:studio",
      harnesses = listOf(dsh, oc)
    )
    val initialSecrets = SshSecrets(
      password = "pw",
      privateKey = "PEM",
      passphrase = "pass",
      certificate = "CERT"
    )
    store.saveDevice(device, initialSecrets)

    val loaded = store.findDevice("ssh-dev1")!!
    assertEquals(device, loaded)
    assertEquals(initialSecrets, store.secrets("ssh-dev1"))
    assertTrue(secretsPrefs.values.values.all { it.toString().startsWith("enc:") })

    // Switch to PRIVATE_KEY: password must be wiped while key secrets remain.
    store.saveDevice(loaded.copyEndpoint(auth = SshAuthMethod.PRIVATE_KEY), SshSecrets(privateKey = "PEM2", passphrase = "p2"))
    val keyOnly = store.secrets("ssh-dev1")
    assertNull(keyOnly.password)
    assertEquals("PEM2", keyOnly.privateKey)
    assertEquals("p2", keyOnly.passphrase)

    // Switch to PASSWORD: key material must be wiped.
    store.saveDevice(loaded.copyEndpoint(auth = SshAuthMethod.PASSWORD, clearKeyId = true), SshSecrets(password = "pw2"))
    val pwOnly = store.secrets("ssh-dev1")
    assertEquals("pw2", pwOnly.password)
    assertNull(pwOnly.privateKey)
    assertNull(pwOnly.passphrase)
    assertNull(pwOnly.certificate)
  }

  @Test fun hostKeyPinResetAndCasRespectEndpointChanges() {
    val (store, _, _) = newStore()
    val device = SshDevice("ssh-1", "Box", "box.local", 22, "alice")
    store.saveDevice(device, SshSecrets(password = "pw"))

    val key1 = PresentedSshHostKey("ssh-ed25519", "SHA256:first")
    assertTrue(store.pinHostKeyIfUnchanged(device.toHostConfig(), key1))
    assertEquals("SHA256:first", store.findDevice("ssh-1")?.hostKeyFingerprint)

    // Stale expected config (still unpinned) must be rejected by CAS.
    val key2 = PresentedSshHostKey("ssh-ed25519", "SHA256:second")
    assertFalse(store.pinHostKeyIfUnchanged(device.toHostConfig(), key2))
    assertEquals("SHA256:first", store.findDevice("ssh-1")?.hostKeyFingerprint)

    // Endpoint change automatically clears pinned host key.
    val moved = store.findDevice("ssh-1")!!.copyEndpoint(host = "box2.local")
    assertNull(moved.hostKeyFingerprint)
    store.saveDevice(moved)
    assertFalse(store.pinHostKeyIfUnchanged(device.toHostConfig(), key2))
    assertTrue(store.pinHostKeyIfUnchanged(moved.toHostConfig(), key2))
    assertEquals("SHA256:second", store.findDevice("ssh-1")?.hostKeyFingerprint)

    // Explicit reset clears the pin without touching secrets.
    store.resetHostKey("ssh-1")
    assertNull(store.findDevice("ssh-1")?.hostKeyFingerprint)
    assertEquals("pw", store.secrets("ssh-1").password)
  }

  @Test fun deviceAndHarnessEnabledFlagsStayIndependentAndProjectEffectiveEnabled() {
    val (store, _, _) = newStore()
    val dsh = DeviceHarness("h-dsh", "ssh-1", HarnessProtocol.DSH, enabled = true)
    val codex = DeviceHarness("h-codex", "ssh-1", HarnessProtocol.CODEX, enabled = true)
    store.saveDevice(SshDevice("ssh-1", "Box", "box.local", user = "alice", harnesses = listOf(dsh, codex)))

    // Disable only Codex; DSH stays enabled.
    store.setHarnessEnabled("h-codex", false)
    val afterHarnessOff = store.profiles().associateBy { it.id }
    assertTrue(afterHarnessOff.getValue("h-dsh").effectiveEnabled)
    assertFalse(afterHarnessOff.getValue("h-codex").effectiveEnabled)

    // Disable entire device; child harness enabled flags are preserved underneath.
    store.setDeviceEnabled("ssh-1", false)
    val afterDeviceOff = store.profiles().associateBy { it.id }
    assertFalse(afterDeviceOff.getValue("h-dsh").effectiveEnabled)
    assertTrue(afterDeviceOff.getValue("h-dsh").enabled)
    assertFalse(afterDeviceOff.getValue("h-dsh").deviceEnabled)
    assertFalse(afterDeviceOff.getValue("h-codex").enabled)

    // Re-enable device: DSH comes back enabled, Codex remains disabled.
    store.setDeviceEnabled("ssh-1", true)
    val afterDeviceOn = store.profiles().associateBy { it.id }
    assertTrue(afterDeviceOn.getValue("h-dsh").effectiveEnabled)
    assertFalse(afterDeviceOn.getValue("h-codex").effectiveEnabled)
  }

  @Test fun harnessUpsertRemovePreservesDeviceAndDeleteDeviceClearsSecrets() {
    val (store, _, secretsPrefs) = newStore()
    val device = SshDevice("ssh-1", "Box", "box.local", user = "alice")
    store.saveDevice(device, SshSecrets(password = "secret"))

    val dsh = DeviceHarness("h-dsh", "ssh-1", HarnessProtocol.DSH)
    store.upsertHarness("ssh-1", dsh)
    assertEquals(listOf("h-dsh"), store.findDevice("ssh-1")!!.harnesses.map { it.id })

    // Updating DSH launch config replaces in place while preserving harness ID.
    val updatedDsh = dsh.copy(config = HarnessConfig.Dsh("dsh web --custom", "/work"))
    store.upsertHarness("ssh-1", updatedDsh)
    assertEquals(1, store.findDevice("ssh-1")!!.harnesses.size)
    assertEquals("dsh web --custom", store.findDevice("ssh-1")!!.dshConfig("h-dsh").launchCommand)

    // Removing the last harness keeps the SSH device and its secrets intact.
    assertNotNull(store.removeHarness("h-dsh"))
    assertNotNull(store.findDevice("ssh-1"))
    assertTrue(store.findDevice("ssh-1")!!.harnesses.isEmpty())
    assertEquals("secret", store.secrets("ssh-1").password)

    // Deleting the device removes both the device and its encrypted secrets.
    assertNotNull(store.deleteDevice("ssh-1"))
    assertNull(store.findDevice("ssh-1"))
    assertNull(store.secrets("ssh-1").password)
    assertTrue(secretsPrefs.values.isEmpty())
  }

  @Test fun corruptDevicesDocumentRefusesEveryWriterWithoutOverwritingBytesOrSecrets() {
    val invalidDocs = listOf(
      "{broken-json",
      """{"version":99,"devices":[]}""",
      """{"version":2,"devices":[{"id":"dup","name":"A","host":"a","user":"u","harnesses":[]},{"id":"dup","name":"B","host":"b","user":"u","harnesses":[]}]}"""
    )
    for (raw in invalidDocs) {
      val prefs = FakeSharedPreferences().apply { values["devices"] = raw }
      val secrets = FakeSharedPreferences().apply { values["ssh:saved:password"] = "enc:keep" }
      val (store, _, _) = newStore(prefs, secrets)

      val loaded = store.load()
      if (!raw.contains("\"dup\"")) assertEquals(emptyList<SshDevice>(), loaded)
      assertNotNull(store.loadError)

      val candidate = SshDevice("ssh-new", "New", "new.local", user = "u")
      val writers = listOf<() -> Unit>(
        { store.saveDevice(candidate, SshSecrets(password = "new-pw")) },
        { store.deleteDevice("saved") },
        { store.setDeviceEnabled("saved", false) },
        { store.upsertHarness("saved", DeviceHarness("h", "saved", HarnessProtocol.DSH)) },
        { store.setHarnessEnabled("h", false) },
        { store.removeHarness("h") },
        { store.pinHostKey("saved", PresentedSshHostKey("ssh-ed25519", "SHA256:x")) },
        { store.resetHostKey("saved") }
      )
      for (writer in writers) {
        assertTrue("Expected IOException for raw=$raw", runCatching(writer).exceptionOrNull() is IOException)
        assertEquals(raw, prefs.values["devices"])
        assertEquals("enc:keep", secrets.values["ssh:saved:password"])
        assertFalse(secrets.contains("ssh:ssh-new:password"))
      }
    }
  }
}
