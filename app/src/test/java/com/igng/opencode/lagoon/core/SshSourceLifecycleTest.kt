package com.igng.opencode.lagoon.core

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.igng.opencode.lagoon.core.ssh.HardwareSshSigner
import com.igng.opencode.lagoon.core.ssh.SshHostKeyVerifier
import com.igng.opencode.lagoon.core.ssh.SshHostManager
import com.igng.opencode.lagoon.core.ssh.SshTransport
import com.igng.opencode.lagoon.core.ssh.SshTransportFactory
import com.igng.opencode.lagoon.system.TaskNotifications
import com.jcraft.jsch.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real controller Harness & Device deletion entry points with pure-SSH DeviceStore. */
class SshSourceLifecycleTest {
  @Test fun deletingTheLastHarnessPreservesDeviceConfigEncryptedSecretsAndDeviceGroup() {
    SshAuthMethod.entries.forEach { auth -> Fixture(auth, leafCount = 1).use { fixture ->
      val secretBytes = fixture.sshSecretPreferences.all.toMap()
      val siblingSecrets = fixture.serverSecretPreferences.all.toMap()
        .filterKeys { !it.startsWith("draft:leaf-0:") }

      fixture.controller.deleteHarness("leaf-0")

      assertEquals("even the last harness does not own SSH secrets", secretBytes, fixture.sshSecretPreferences.all)
      val remainingDevice = fixture.deviceStore.findDevice(fixture.device.id)
      assertNotNull("empty SSH device must stay manageable", remainingDevice)
      assertTrue(remainingDevice!!.harnesses.isEmpty())
      assertEquals(fixture.hostSecrets, fixture.deviceStore.secrets(fixture.device.id))
      assertEquals(listOf("outside"), fixture.deviceStore.profiles().map { it.id })
      assertEquals(siblingSecrets, fixture.serverSecretPreferences.all)
      assertEquals("outside draft", fixture.store.draft("outside", "session"))
      assertTrue("device group survives last harness deletion", fixture.groupStore.load().state.groups.any { it.id == "g-target" })
      assertTrue(fixture.state.value.devices.any { it.id == fixture.device.id })
      assertEquals(0, fixture.transportCalls)
    } }
  }

  @Test fun deletingOneHarnessLeavesItsSiblingPreferencesAndSharedDeviceUntouched() {
    Fixture(SshAuthMethod.PRIVATE_KEY, leafCount = 2).use { fixture ->
      val secretBytes = fixture.sshSecretPreferences.all.toMap()

      fixture.controller.deleteHarness("leaf-0")

      val remaining = fixture.deviceStore.findDevice(fixture.device.id)!!.harnesses.single()
      assertEquals("leaf-1", remaining.id)
      assertEquals("draft leaf-1", fixture.store.draft("leaf-1", "session"))
      assertEquals(secretBytes, fixture.sshSecretPreferences.all)
      assertFalse(fixture.state.value.profiles.any { it.id == "leaf-0" })
      assertTrue(fixture.state.value.profiles.any { it.id == "leaf-1" })
      assertEquals(0, fixture.transportCalls)
    }
  }

  @Test fun explicitlyDeletingAnSshDeviceRemovesAllItsHarnessesAndSecretsIncludingEmptyDevice() {
    SshAuthMethod.entries.forEach { auth -> listOf(0, 2).forEach { leafCount ->
      Fixture(auth, leafCount).use { fixture ->
        val outsideSecretBytes = fixture.sshSecretPreferences.all.toMap()
          .filterKeys { it.startsWith("ssh:${fixture.outsideDevice.id}:") }
        val outsideServerSecretBytes = fixture.serverSecretPreferences.all.toMap()
          .filterKeys { it.startsWith("draft:outside:") }

        fixture.controller.deleteDevice(fixture.device.id)

        assertEquals(listOf(fixture.outsideDevice.id), fixture.deviceStore.load().map { it.id })
        assertEquals(SshSecrets(), fixture.deviceStore.secrets(fixture.device.id))
        assertFalse(fixture.sshSecretPreferences.all.keys.any { it.startsWith("ssh:${fixture.device.id}:") })
        assertEquals(outsideSecretBytes, fixture.sshSecretPreferences.all)
        assertEquals(listOf("outside"), fixture.deviceStore.profiles().map { it.id })
        assertEquals(outsideServerSecretBytes, fixture.serverSecretPreferences.all)
        assertEquals("outside draft", fixture.store.draft("outside", "session"))
        assertFalse("device group with only deleted device is cleaned up",
          fixture.groupStore.load().state.groups.any { it.id == "g-target" })
        assertEquals(listOf(fixture.outsideDevice.id), fixture.state.value.devices.map { it.id })
        assertEquals(listOf("outside"), fixture.state.value.profiles.map { it.id })
        assertEquals(0, fixture.transportCalls)
      }
    } }
  }

  private class Fixture(auth: SshAuthMethod, leafCount: Int) : AutoCloseable {
    val devicePreferences = FakeSharedPreferences()
    val sshSecretPreferences = FakeSharedPreferences()
    val serverSecretPreferences = FakeSharedPreferences()
    private val serverPreferences = FakeSharedPreferences()
    private val discoveryPreferences = FakeSharedPreferences()
    private val groupPreferences = FakeSharedPreferences()
    private val keyPreferences = FakeSharedPreferences()
    private val keySecretPreferences = FakeSharedPreferences()
    private val otherPreferences = mutableMapOf<String, SharedPreferences>()
    private val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val context = object : ContextWrapper(null) {
      override fun getApplicationContext(): Context = this
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
        "servers" -> serverPreferences
        "secrets" -> serverSecretPreferences
        "devices" -> devicePreferences
        "ssh-secrets" -> sshSecretPreferences
        "device-groups" -> groupPreferences
        "device-discovery" -> discoveryPreferences
        else -> otherPreferences.getOrPut(name) { FakeSharedPreferences() }
      }
    }
    val harnesses = (0 until leafCount).map { index ->
      DeviceHarness(
        id = "leaf-$index",
        deviceId = "ssh-target",
        protocol = if (index == 0) HarnessProtocol.DSH else HarnessProtocol.OPENCODE_NATIVE,
        label = "Harness $index",
        enabled = index == 0,
        notifications = index == 0,
        config = if (index == 0) HarnessConfig.Dsh() else HarnessConfig.OpenCodeNative("/home/alice/service.json")
      )
    }
    val device = SshDevice(
      id = "ssh-target", name = "Saved device", host = "target.invalid", user = "alice", auth = auth,
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:fixture-target",
      harnesses = harnesses
    )
    val outsideDevice = SshDevice(
      id = "ssh-outside", name = "Other device", host = "outside.invalid", user = "bob",
      harnesses = listOf(DeviceHarness("outside", "ssh-outside", HarnessProtocol.DSH, label = "Unrelated Harness"))
    )
    val hostSecrets = if (auth == SshAuthMethod.PASSWORD) SshSecrets(password = "fixture password") else
      SshSecrets(privateKey = "fixture private key", passphrase = "fixture passphrase")
    val deviceStore = DeviceStore(devicePreferences, sshSecretPreferences, { "encrypted:$it" }, { it.removePrefix("encrypted:") })
    val groupStore = DeviceGroupStore(groupPreferences)
    val discoveryStore = DeviceDiscoveryStore(discoveryPreferences)
    val coordinator: DeviceCoordinator
    val keyStore = SshKeyStore(keyPreferences, keySecretPreferences, { "encrypted:$it" }, { it.removePrefix("encrypted:") })
    val keyCoordinator = SshKeyCoordinator(
      keyStore,
      object : HardwareSshSigner {
        override fun generateKey(keyId: String, name: String, requireBiometric: Boolean): SshKeyProfile = error("unused")
        override fun createIdentity(profile: SshKeyProfile): Identity = error("unused")
        override fun deleteKey(keyId: String) = Unit
      },
      scope
    )
    val store = ServerStore(serverPreferences, serverSecretPreferences, { "encrypted:$it" },
      { it.removePrefix("encrypted:") }, readProfiles = { deviceStore.profiles() })
    var transportCalls = 0
      private set
    private val manager = SshHostManager(deviceStore, object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
        transportCalls++
        error("A deletion must not dial SSH")
      }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey {
        transportCalls++
        error("A deletion must not probe SSH")
      }
    })
    private val overview = AllServersOverview(store, CatalogCache { null }, scope, {},
      sshClientFactory = { _, _ -> error("Cancelled overview must not create a client") })
    val state: MutableStateFlow<LagoonState>
    val controller: LagoonController

    init {
      deviceStore.saveDevice(device, hostSecrets)
      deviceStore.saveDevice(outsideDevice, SshSecrets(password = "unrelated password"))
      harnesses.forEach { store.rememberDraft(it.id, "session", "draft ${it.id}") }
      store.rememberDraft("outside", "session", "outside draft")
      groupStore.save(DeviceGroupState(listOf(DeviceGroup("g-target", "Target Group", deviceIds = listOf(device.id)))))
      coordinator = DeviceCoordinator(deviceStore, discoveryStore, groupStore)
      state = MutableStateFlow(LagoonState(
        devices = coordinator.devices(),
        profiles = coordinator.profiles(),
        deviceGroups = coordinator.state.value.deviceGroups,
        allServers = true
      ))
      controller = allocate(LagoonController::class.java)
      put(controller, "appContext", context)
      put(controller, "store", store)
      put(controller, "deviceCoordinator", coordinator)
      put(controller, "sshKeyCoordinator", keyCoordinator)
      put(controller, "sshHostManager", manager)
      put(controller, "mutable", state)
      put(controller, "state", state)
      put(controller, "scope", scope)
      put(controller, "allOverview", overview)
      put(controller, "allSnapshots", java.util.concurrent.ConcurrentHashMap<String, ServerOverviewSnapshot>())
      put(controller, "transcriptMemory", TranscriptMemory())
      put(controller, "sessionViewsStorage", SessionViewCache())
      put(controller, "conversationPoolStorage", ConversationStorePool())
      put(controller, "choicesCacheStorage", ChoicesCache())
      put(controller, "cache", OfflineCache(FakeSharedPreferences(), { it }, { it }, scope))
      val notifications = allocate(TaskNotifications::class.java)
      put(notifications, "manager", allocate(NotificationManager::class.java))
      put(controller, "notifications", notifications)
    }

    override fun close() {
      overview.stop()
      manager.closeAll()
      (field(manager, "scope") as CoroutineScope).cancel()
      scope.cancel()
    }
  }

  private companion object {
    fun <T> allocate(type: Class<T>): T {
      val unsafeClass = Class.forName("sun.misc.Unsafe")
      val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
      return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }
    fun put(target: Any, name: String, value: Any?) {
      target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
  }
}
