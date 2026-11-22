package com.igng.opencode.lagoon.core

import com.igng.opencode.lagoon.core.ssh.HardwareSshSigner
import com.jcraft.jsch.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCoordinatorTest {
  private fun coordinator(): DeviceCoordinator = DeviceCoordinator(
    deviceStore = DeviceStore(FakeSharedPreferences(), FakeSharedPreferences(), { it }, { it }),
    discoveryStore = DeviceDiscoveryStore(FakeSharedPreferences()),
    groupStore = DeviceGroupStore(FakeSharedPreferences())
  )

  @Test fun deviceGroupSurvivesRemovingLastHarnessAndDynamicallyIncludesNewHarnesses() {
    val coord = coordinator()
    val dsh = DeviceHarness("h-dsh", "ssh-a", HarnessProtocol.DSH)
    val devA = SshDevice("ssh-a", "Mac", "mac.local", user = "alice", harnesses = listOf(dsh))
    val devB = SshDevice("ssh-b", "Linux", "linux.local", user = "bob")
    coord.saveDevice(devA, SshSecrets(password = "pw"))
    coord.saveDevice(devB, SshSecrets(password = "pw"))

    val groupId = coord.createGroup("Workstations", listOf("ssh-a"))
    assertTrue(groupId.isNotBlank())
    assertEquals(listOf("ssh-a"), coord.state.value.deviceGroups.single().deviceIds)

    // Remove the ONLY harness on ssh-a: the group must survive because ssh-a still exists (Bug #4).
    assertNotNull(coord.removeHarness("h-dsh"))
    assertEquals(listOf("ssh-a"), coord.state.value.deviceGroups.single().deviceIds)
    assertTrue(coord.state.value.profiles.isEmpty())

    // Attach a new Codex harness on ssh-a: group memberHarnessIds picks it up dynamically.
    val codex = DeviceHarness("h-codex", "ssh-a", HarnessProtocol.CODEX)
    coord.upsertHarness("ssh-a", codex)
    val group = coord.state.value.deviceGroups.single()
    assertEquals(listOf("h-codex"), group.memberHarnessIds(coord.devices()))
    assertEquals(listOf("h-codex"), group.memberHarnessIds(coord.profiles()))

    // Deleting the SSH device itself prunes it from the group (and drops the now-empty group).
    assertNotNull(coord.deleteDevice("ssh-a"))
    assertTrue(coord.state.value.deviceGroups.isEmpty())
    assertEquals(listOf("ssh-b"), coord.devices().map { it.id })
  }

  @Test fun saveDevicePreservesAttachedHarnessesWhenUpdatingEndpointMetadata() {
    val coord = coordinator()
    val dsh = DeviceHarness("h-dsh", "ssh-a", HarnessProtocol.DSH)
    coord.saveDevice(
      SshDevice("ssh-a", "Old Name", "old.local", 22, "alice", auth = SshAuthMethod.PRIVATE_KEY, keyId = "key-1", harnesses = listOf(dsh)),
      SshSecrets()
    )
    assertEquals(listOf("ssh-a"), coord.devicesUsingKey("key-1").map { it.id })

    // Saving an endpoint edit without re-passing harnesses keeps existing harnesses attached.
    val edited = SshDevice("ssh-a", "New Name", "new.local", 2222, "alice", auth = SshAuthMethod.PRIVATE_KEY, keyId = "key-1")
    coord.saveDevice(edited)
    val saved = coord.findDevice("ssh-a")!!
    assertEquals("New Name", saved.name)
    assertEquals(2222, saved.port)
    assertEquals(listOf("h-dsh"), saved.harnesses.map { it.id })
    assertEquals("ssh://alice@new.local:2222", coord.profiles().single().url)
  }

  @Test fun sshKeyCoordinatorTracksBackgroundRevisionUpdatesIndependently() = runBlocking {
    val keyStore = SshKeyStore(FakeSharedPreferences(), FakeSharedPreferences(), { it }, { it })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val fakeSigner = object : HardwareSshSigner {
      override fun generateKey(keyId: String, name: String, requireBiometric: Boolean): SshKeyProfile = error("unused")
      override fun createIdentity(profile: SshKeyProfile): Identity = error("unused")
      override fun deleteKey(keyId: String) = Unit
    }
    val keyCoordinator = SshKeyCoordinator(keyStore, fakeSigner, scope)
    try {
      val profile = SshKeyProfile(
        id = "key-prompt",
        name = "Prompt Key",
        algorithm = "ED25519",
        fingerprint = "SHA256:prompt",
        publicKeyOpenSsh = "ssh-ed25519 AAAA prompt",
        encrypted = true,
        passphrasePolicy = SshPassphrasePolicy.PROMPT,
        source = SshKeySource.IMPORTED
      )
      keyStore.save(profile, SshKeySecrets(privateKey = "PEM"))
      withTimeout(2_000) { keyCoordinator.state.first { it.keys.any { k -> k.id == "key-prompt" } } }
      val revAfterSave = keyCoordinator.state.value.revision
      assertFalse(keyCoordinator.isUnlocked(profile))

      // Unlocking updates revisionFlow and triggers coordinator state republish (Bugs #1 & #12).
      keyStore.unlockRuntimeSecret("key-prompt", "secret-pass")
      withTimeout(2_000) { keyCoordinator.state.first { it.revision > revAfterSave } }
      val revAfterUnlock = keyCoordinator.state.value.revision
      assertTrue(keyCoordinator.isUnlocked(profile))

      // Background auto-lock (e.g., 30-second biometric expiry) increments revisionFlow immediately.
      keyStore.lockRuntimeSecret("key-prompt")
      withTimeout(2_000) { keyCoordinator.state.first { it.revision > revAfterUnlock } }
      assertFalse(keyCoordinator.isUnlocked(profile))
      assertEquals(listOf("key-prompt"), keyCoordinator.keys().map { it.id })
    } finally {
      scope.cancel()
    }
  }
}
