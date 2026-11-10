package com.igng.opencode.lagoon.core

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.igng.opencode.lagoon.core.ssh.SshHostKeyVerifier
import com.igng.opencode.lagoon.core.ssh.SshHostManager
import com.igng.opencode.lagoon.core.ssh.SshTransport
import com.igng.opencode.lagoon.core.ssh.SshTransportFactory
import com.igng.opencode.lagoon.system.TaskNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real controller deletion entry points, not just the store's delete implementation. */
class SshSourceLifecycleTest {
  @Test fun deletingTheLastHarnessPreservesExactSourceConfigAndEncryptedSecretBytes() {
    SshAuthMethod.entries.forEach { auth -> Fixture(auth, leafCount = 1).use { fixture ->
      val configBytes = fixture.sshPreferences.all.toMap()
      val secretBytes = fixture.sshSecretPreferences.all.toMap()
      val source = fixture.graphStore.requireGraph().sources.single { it.sshHostConfigId == fixture.host.id }
      val sibling = fixture.store.readProfiles().single { it.id == "outside" }
      val siblingSecrets = fixture.serverSecretPreferences.all.toMap()
        .filterKeys { !it.startsWith("draft:leaf-0:") }

      fixture.controller.deleteServer("leaf-0")

      assertEquals("removing a leaf must not rewrite shared SSH configuration", configBytes, fixture.sshPreferences.all)
      assertEquals("even the last leaf does not own SSH credentials", secretBytes, fixture.sshSecretPreferences.all)
      assertEquals(fixture.host, fixture.sshStore.load().single { it.id == fixture.host.id })
      assertEquals(fixture.hostSecrets, fixture.sshStore.secrets(fixture.host.id))
      assertEquals(listOf(sibling), fixture.store.readProfiles())
      assertEquals(siblingSecrets, fixture.serverSecretPreferences.all)
      assertEquals("outside draft", fixture.store.draft("outside", "session"))
      assertTrue(fixture.state.value.sshHosts.any { it.id == fixture.host.id })
      val graph = fixture.graphStore.requireGraph()
      assertTrue("empty saved sources remain manageable", graph.sources.any { it.id == source.id })
      assertTrue(graph.hosts.any { it.sourceId == source.id })
      assertFalse(graph.bindings.any { it.id == "leaf-0" })
      assertEquals(graph, fixture.controller.connectionGraph.value)
      assertEquals(0, fixture.transportCalls)
    } }
  }

  @Test fun deletingOneHarnessLeavesItsSiblingPreferencesAndSharedSourceUntouched() {
    Fixture(SshAuthMethod.PRIVATE_KEY, leafCount = 2).use { fixture ->
      val sibling = fixture.store.readProfiles().single { it.id == "leaf-1" }
      val siblingCredentials = fixture.store.credentials(sibling.id)
      val configBytes = fixture.sshPreferences.all.toMap()
      val secretBytes = fixture.sshSecretPreferences.all.toMap()
      val siblingBinding = fixture.graphStore.requireGraph().bindings.single { it.id == sibling.id }

      fixture.controller.deleteServer("leaf-0")

      assertEquals(sibling, fixture.store.readProfiles().single { it.id == sibling.id })
      assertEquals(siblingCredentials, fixture.store.credentials(sibling.id))
      assertEquals("draft leaf-1", fixture.store.draft(sibling.id, "session"))
      assertEquals(siblingBinding, fixture.graphStore.requireGraph().bindings.single { it.id == sibling.id })
      assertEquals(configBytes, fixture.sshPreferences.all)
      assertEquals(secretBytes, fixture.sshSecretPreferences.all)
      assertFalse(fixture.state.value.profiles.any { it.id == "leaf-0" })
      assertTrue(fixture.state.value.profiles.any { it.id == sibling.id })
      assertEquals(0, fixture.transportCalls)
    }
  }

  @Test fun explicitlyDeletingAnSshSourceRemovesAllItsLeavesAndSecretsIncludingAnAlreadyEmptySource() {
    SshAuthMethod.entries.forEach { auth -> listOf(0, 2).forEach { leafCount ->
      Fixture(auth, leafCount).use { fixture ->
        val before = fixture.graphStore.requireGraph()
        val sourceId = before.sources.single { it.sshHostConfigId == fixture.host.id }.id
        val outsideProfile = fixture.store.readProfiles().single { it.id == "outside" }
        val outsideSource = before.sources.single { it.sshHostConfigId == fixture.outsideHost.id }
        val outsideBinding = before.bindings.single { it.id == "outside" }
        val outsideSecretBytes = fixture.sshSecretPreferences.all.toMap()
          .filterKeys { it.startsWith("ssh:${fixture.outsideHost.id}:") }
        val outsideServerSecretBytes = fixture.serverSecretPreferences.all.toMap()
          .filterKeys { it.startsWith("draft:outside:") }

        fixture.controller.deleteSshHost(fixture.host.id)

        assertEquals(listOf(fixture.outsideHost), fixture.sshStore.load())
        assertEquals(SshSecrets(), fixture.sshStore.secrets(fixture.host.id))
        assertFalse(fixture.sshSecretPreferences.all.keys.any { it.startsWith("ssh:${fixture.host.id}:") })
        assertEquals(outsideSecretBytes, fixture.sshSecretPreferences.all)
        assertEquals(listOf(outsideProfile), fixture.store.readProfiles())
        assertEquals(outsideServerSecretBytes, fixture.serverSecretPreferences.all)
        assertEquals("outside draft", fixture.store.draft("outside", "session"))
        val graph = fixture.graphStore.requireGraph()
        assertFalse("explicit deletion must not leave a ghost saved source", graph.sources.any { it.id == sourceId })
        assertFalse(graph.hosts.any { it.sourceId == sourceId })
        assertEquals(listOf(outsideBinding), graph.bindings)
        assertEquals(outsideSource, graph.sources.single())
        assertEquals(graph, fixture.controller.connectionGraph.value)
        assertEquals(listOf(fixture.outsideHost), fixture.state.value.sshHosts)
        assertEquals(listOf(outsideProfile), fixture.state.value.profiles)
        assertEquals(0, fixture.transportCalls)
      }
    } }
  }

  private class Fixture(auth: SshAuthMethod, leafCount: Int) : AutoCloseable {
    val sshPreferences = FakeSharedPreferences()
    val sshSecretPreferences = FakeSharedPreferences()
    val serverSecretPreferences = FakeSharedPreferences()
    private val profilePreferences = FakeSharedPreferences()
    private val graphPreferences = FakeSharedPreferences()
    private val groupPreferences = FakeSharedPreferences()
    private val otherPreferences = mutableMapOf<String, SharedPreferences>()
    private val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val context = object : ContextWrapper(null) {
      override fun getApplicationContext(): Context = this
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
        "servers" -> profilePreferences
        "secrets" -> serverSecretPreferences
        "ssh-hosts" -> sshPreferences
        "ssh-secrets" -> sshSecretPreferences
        "connection-graph" -> graphPreferences
        "device-groups" -> groupPreferences
        else -> otherPreferences.getOrPut(name) { FakeSharedPreferences() }
      }
    }
    val host = SshHostConfig("ssh-target", "Saved source", "target.invalid", user = "alice", auth = auth,
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:fixture-target")
    val outsideHost = SshHostConfig("ssh-outside", "Other source", "outside.invalid", user = "bob")
    val hostSecrets = if (auth == SshAuthMethod.PASSWORD) SshSecrets(password = "fixture password") else
      SshSecrets(privateKey = "fixture private key", passphrase = "fixture passphrase")
    val sshStore = SshHostStore(sshPreferences, sshSecretPreferences, { "encrypted:$it" }, { it.removePrefix("encrypted:") })
    val store = ServerStore(profilePreferences, serverSecretPreferences, { "encrypted:$it" },
      { it.removePrefix("encrypted:") }, context)
    val graphStore = ConnectionGraphStore(graphPreferences, { store.readProfiles() })
    var transportCalls = 0
      private set
    private val manager = SshHostManager(sshStore, object : SshTransportFactory {
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
      sshStore.save(host, hostSecrets)
      sshStore.save(outsideHost, SshSecrets(password = "unrelated password"))
      repeat(leafCount) { index ->
        store.save(ServerProfile("leaf-$index", "Harness $index", host.profileUrl(), host.user,
          kind = if (index == 0) ServerKind.DSH else ServerKind.OPENCODE,
          sshHostId = host.id, sshServicePath = if (index == 0) null else "/home/alice/service.json",
          enabled = index == 0, notifications = index == 0), null)
        store.rememberDraft("leaf-$index", "session", "draft leaf-$index")
      }
      store.save(ServerProfile("outside", "Unrelated Harness", outsideHost.profileUrl(), outsideHost.user,
        kind = ServerKind.DSH, sshHostId = outsideHost.id), null)
      store.rememberDraft("outside", "session", "outside draft")
      val graph = ConnectionCatalogProjection.merge(MigrationProjection.fromProfiles(store.readProfiles()),
        null, sshStore.load(), emptyList())
      graphStore.save(graph)
      state = MutableStateFlow(LagoonState(profiles = store.profiles(), sshHosts = sshStore.load(), allServers = true))
      controller = allocate(LagoonController::class.java)
      put(controller, "appContext", context)
      put(controller, "store", store)
      put(controller, "sshHostStore", sshStore)
      put(controller, "sshHostManager", manager)
      put(controller, "deviceGroupStore", DeviceGroupStore(groupPreferences))
      put(controller, "connectionGraphStore", graphStore)
      put(controller, "connectionGraphMutable", MutableStateFlow<ConnectionGraph?>(graph))
      put(controller, "connectionGraph", field(controller, "connectionGraphMutable"))
      put(controller, "connectionGraphWritable", true)
      put(controller, "mutable", state)
      put(controller, "state", state)
      put(controller, "scope", scope)
      put(controller, "allOverview", overview)
      put(controller, "allSnapshots", linkedMapOf<String, ServerOverviewSnapshot>())
      put(controller, "transcriptMemory", TranscriptMemory())
      put(controller, "cache", OfflineCache(FakeSharedPreferences(), { it }, { it }, scope))
      // Android stubs safely accept cancel(); bypass only notification channel initialization.
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
