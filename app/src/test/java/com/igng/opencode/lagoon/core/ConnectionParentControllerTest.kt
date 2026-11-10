package com.igng.opencode.lagoon.core

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.igng.opencode.lagoon.system.TaskNotifications
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Actual controller entry points with real persistence and overview selection, without Android startup. */
class ConnectionParentControllerTest {
  private enum class Parent { SOURCE, HOST }

  @Test fun parentDisableAndReenablePreserveLeavesAndSelectedGroupWithoutRestartingDisabledChildren() {
    Parent.entries.forEach { parent -> Fixture().use { fixture ->
      val raw = fixture.store.readProfiles()
      val profileBytes = fixture.profilePreferences.getString("profiles", null)
      val secrets = fixture.secretPreferences.all.toMap()
      val groups = fixture.groupPreferences.all.toMap()
      val graph = fixture.graphStore.requireGraph()
      val credentials = fixture.store.credentials("on")

      fixture.change(parent, false)

      assertTrue(fixture.state.value.profiles.first { it.id == "on" }.enabled)
      assertFalse(fixture.state.value.profiles.first { it.id == "on" }.effectiveEnabled)
      assertFalse(fixture.state.value.profiles.first { it.id == "off" }.enabled)
      assertTrue(fixture.state.value.profiles.first { it.id == "outside" }.effectiveEnabled)
      assertNull("a denied group must not fall back to an unrelated enabled server", fixture.state.value.serverId)
      assertEquals("group", fixture.state.value.homeGroupId)
      assertFalse(fixture.state.value.allServers)
      assertTrue(fixture.state.value.homeGroupProfiles.isEmpty())
      assertEquals(listOf("on", "off"), fixture.state.value.homeGroup!!.memberIds)
      assertEquals(setOf("outside"), fixture.monitoredIds())
      assertEquals(graph.bindings, fixture.graphStore.requireGraph().bindings)
      assertEquals(graph.compositions, fixture.graphStore.requireGraph().compositions)

      fixture.change(parent, true)

      assertEquals(setOf("on", "outside"), fixture.monitoredIds())
      assertFalse("parent reenable must not schedule the raw-disabled child", "off" in fixture.overviewJobIds())
      assertTrue(fixture.state.value.profiles.first { it.id == "on" }.effectiveEnabled)
      assertFalse(fixture.state.value.profiles.first { it.id == "off" }.effectiveEnabled)
      assertEquals("group", fixture.state.value.homeGroupId)
      assertEquals(listOf("on"), fixture.state.value.homeGroupProfiles.map { it.id })
      assertEquals(raw, fixture.store.readProfiles())
      assertEquals(profileBytes, fixture.profilePreferences.getString("profiles", null))
      assertEquals(secrets, fixture.secretPreferences.all)
      assertEquals(groups, fixture.groupPreferences.all)
      assertEquals(credentials, fixture.store.credentials("on"))
      assertEquals("unsent on", fixture.store.draft("on", "session"))
      assertEquals("unsent off", fixture.store.draft("off", "session"))
      assertEquals(graph, fixture.graphStore.requireGraph())
    } }
  }

  @Test fun failedCleanupAfterCommittedParentDisableStillAppliesThePersistedDenial() {
    Parent.entries.forEach { parent -> Fixture().use { fixture ->
      val raw = fixture.store.readProfiles()
      val secrets = fixture.secretPreferences.all.toMap()
      val groups = fixture.groupPreferences.all.toMap()
      // prepare succeeds, primary succeeds, cleanup reports failure: the denial is already durable.
      fixture.graphPreferences.failCommit = fixture.graphPreferences.commits + 3

      fixture.change(parent, false)

      val persisted = fixture.graphStore.requireGraph()
      assertFalse(persisted.effectiveEnabled("on"))
      assertEquals(persisted, fixture.controller.connectionGraph.value)
      assertFalse("a failed journal cleanup must not leave a live selected child", fixture.state.value.connected)
      assertFalse(fixture.state.value.profiles.first { it.id == "on" }.effectiveEnabled)
      assertNull(fixture.state.value.serverId)
      assertEquals("group", fixture.state.value.homeGroupId)
      assertFalse(fixture.state.value.allServers)
      assertEquals(setOf("outside"), fixture.monitoredIds())
      assertEquals(raw, fixture.store.readProfiles())
      assertEquals(secrets, fixture.secretPreferences.all)
      assertEquals(groups, fixture.groupPreferences.all)
    } }
  }

  @Test fun staleNotificationProfileAndConnectedStateCannotBypassAFreshPersistedParentGate() {
    Parent.entries.forEach { parent -> Fixture().use { fixture ->
      val stale = fixture.state.value.profiles.first { it.id == "on" }
      val credentials = fixture.store.credentials(stale.id)
      assertTrue(stale.effectiveEnabled)
      assertTrue(fixture.state.value.connected)
      // Another writer changes the committed graph, while controller/UI and caller retain old profiles.
      when (parent) {
        Parent.SOURCE -> fixture.graphStore.setSourceEnabled(fixture.sourceId, false)
        Parent.HOST -> fixture.graphStore.setHostEnabled(fixture.hostId, false)
      }
      assertTrue("the fixture deliberately retains a stale runtime snapshot", fixture.state.value.server!!.effectiveEnabled)
      assertFalse(fixture.store.profiles().first { it.id == stale.id }.effectiveEnabled)

      val notificationError = runCatching { fixture.controller.notificationClient(stale, credentials).close() }.exceptionOrNull()
      assertTrue("notificationClient must reject before obtaining any transport: $notificationError", notificationError is IllegalStateException)
      assertTrue(notificationError!!.message.orEmpty().contains("停用"))

      val method = LagoonController::class.java.getDeclaredMethod("beginOperation", String::class.java).apply { isAccessible = true }
      val operationError = runCatching { method.invoke(fixture.controller, null) }.exceptionOrNull()
      val cause = (operationError as? InvocationTargetException)?.cause ?: operationError
      assertTrue("beginOperation must re-read the persisted gate, not trust current.connected: $cause", cause is IllegalStateException)
      assertTrue(cause!!.message.orEmpty().contains("停用"))
    } }
  }

  @Test fun failedPrepareDoesNotPublishOrApplyAnUncommittedParentPreference() {
    Parent.entries.forEach { parent -> Fixture().use { fixture ->
      val before = fixture.state.value
      val graph = fixture.graphStore.requireGraph()
      val bytes = fixture.graphPreferences.all.toMap()
      val secrets = fixture.secretPreferences.all.toMap()
      fixture.graphPreferences.failCommit = fixture.graphPreferences.commits + 1

      fixture.change(parent, false)

      assertEquals(graph, fixture.controller.connectionGraph.value)
      assertEquals(bytes, fixture.graphPreferences.all)
      assertEquals(before.profiles, fixture.state.value.profiles)
      assertEquals(before.serverId, fixture.state.value.serverId)
      assertEquals(before.homeGroupId, fixture.state.value.homeGroupId)
      assertTrue(fixture.state.value.connected)
      assertEquals(FeedbackTone.WARNING, fixture.state.value.messageTone)
      assertEquals(setOf("on", "outside"), fixture.monitoredIds())
      assertEquals(secrets, fixture.secretPreferences.all)
    } }
  }

  private class Fixture : AutoCloseable {
    val profilePreferences = FakeSharedPreferences()
    val secretPreferences = FakeSharedPreferences()
    val groupPreferences = FakeSharedPreferences()
    val graphPreferences = FailingPreferences()
    private val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val context = object : ContextWrapper(null) {
      override fun getApplicationContext(): Context = this
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
        "servers" -> profilePreferences
        "secrets" -> secretPreferences
        "connection-graph" -> graphPreferences
        "device-groups" -> groupPreferences
        else -> error("Unexpected Android preference store: $name")
      }
    }
    val store = ServerStore(profilePreferences, secretPreferences, { "encrypted:$it" },
      { it.removePrefix("encrypted:") }, context)
    private val group = DeviceGroup("group", "Both children", listOf("on", "off"))
    private val groups = DeviceGroupState(listOf(group), selectedGroupId = group.id)
    val graphStore: ConnectionGraphStore
    val sourceId: String
    val hostId: String
    val state: MutableStateFlow<LagoonState>
    val controller: LagoonController
    private val overview: AllServersOverview

    init {
      // Same Hub/account/Host gives two distinct leaves under the same source and Host.
      val on = ServerProfile("on", "On", "https://hub.example", username = "alice", kind = ServerKind.DSH_REMOTE,
        remoteHostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a", remoteHostFingerprint = "fingerprint", autoConnect = false)
      val off = on.copy(id = "off", name = "Off", enabled = false, notifications = false)
      val outside = ServerProfile("outside", "Outside the selected group", "https://outside.example")
      listOf(on, off, outside).forEach { profile ->
        store.save(profile, "password-${profile.id}", "cookie-${profile.id}")
        store.rememberDraft(profile.id, "session", "unsent ${profile.id}")
      }
      DeviceGroupStore(groupPreferences).save(groups)
      graphStore = ConnectionGraphStore(graphPreferences, { store.readProfiles() }, {
        listOf(BindingComposition(group.id, group.name, group.memberIds))
      })
      val graph = graphStore.requireGraph()
      hostId = graph.bindings.first { it.id == on.id }.hostBindingId
      sourceId = graph.hosts.first { it.id == hostId }.sourceId
      state = MutableStateFlow(LagoonState(profiles = store.profiles(), deviceGroups = groups.groups,
        homeGroupId = group.id, allServers = false, serverId = on.id, homeServerId = on.id, connected = true))
      // Keep real overview selection/scheduling logic, but cancel network loops before they start.
      overview = AllServersOverview(store, CatalogCache { null }, scope, {})
      overview.start(store.profiles(), include = setOf(on.id))
      controller = allocate(LagoonController::class.java)
      put(controller, "appContext", context)
      put(controller, "store", store)
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
      put(controller, "queuedSends", mutableListOf<Any>())
      put(controller, "generation", 1)
      // The Android unit-test stubs return defaults for cancel(); bypass channel creation only.
      val notifications = allocate(TaskNotifications::class.java)
      put(notifications, "manager", allocate(NotificationManager::class.java))
      put(controller, "notifications", notifications)
      // beginOperation must have a non-null client so only its persisted gate can reject it.
      val client = Proxy.newProxyInstance(HarnessClient::class.java.classLoader, arrayOf(HarnessClient::class.java)) { _, method, _ ->
        when (method.name) {
          "isClosed" -> false
          "close", "questionPresentation" -> Unit
          else -> error("Unexpected transport call: ${method.name}")
        }
      } as HarnessClient
      put(controller, "api", client)
    }

    fun change(parent: Parent, enabled: Boolean) = when (parent) {
      Parent.SOURCE -> controller.setConnectionSourceEnabled(sourceId, enabled)
      Parent.HOST -> controller.setConnectionHostEnabled(hostId, enabled)
    }
    fun monitoredIds(): Set<String> = (field(overview, "configurations") as Map<*, *>).keys.map { it as String }.toSet()
    fun overviewJobIds(): Set<String> = (field(overview, "jobs") as Map<*, *>).keys.map { it as String }.toSet()
    override fun close() { overview.stop(); scope.cancel() }
  }

  /** Fail exactly one durable editor operation without leaking its pending values into memory. */
  private class FailingPreferences(private val delegate: FakeSharedPreferences = FakeSharedPreferences()) : SharedPreferences by delegate {
    var commits = 0
    var failCommit = -1
    override fun edit(): SharedPreferences.Editor {
      val editor = delegate.edit()
      return object : SharedPreferences.Editor by editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
        override fun remove(key: String): SharedPreferences.Editor = apply { editor.remove(key) }
        override fun commit(): Boolean {
          commits += 1
          return if (commits == failCommit) false else editor.commit()
        }
      }
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
