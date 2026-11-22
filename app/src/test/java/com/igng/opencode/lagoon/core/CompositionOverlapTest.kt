package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionOverlapTest {
  @Test fun sanitizerDeduplicatesWithinEachGroupAndKeepsCrossGroupMembership() {
    val state = DeviceGroupState(
      groups = listOf(
        DeviceGroup("a", "A", deviceIds = listOf("d1", "d1", "d2", "missing")),
        DeviceGroup("b", "B", deviceIds = listOf("d2", "d2", "d3"))
      ),
      selectedGroupId = "b"
    )
    val clean = state.sanitized(setOf("d1", "d2", "d3"))
    assertEquals(listOf(listOf("d1", "d2"), listOf("d2", "d3")), clean.groups.map { it.deviceIds })
    assertEquals("b", clean.selectedGroupId)
  }

  @Test fun eachTabProjectsItsOwnMembershipIncludingSharedDevices() {
    val profiles = listOf(profile("p1", "d1"), profile("p2", "d2"), profile("p3", "d3"), profile("p4", "d4"))
    val devices = listOf(device("d1"), device("d2"), device("d3"), device("d4"))
    val state = DeviceGroupState(listOf(
      DeviceGroup("a", "A", deviceIds = listOf("d1", "d2", "d1")),
      DeviceGroup("b", "B", deviceIds = listOf("d2", "d3"))
    ))
    val tabs = state.tabs(profiles)
    assertEquals(listOf(listOf("p1", "p2"), listOf("p2", "p3")), tabs.map { tab -> tab.profiles.map { it.id } })
    assertEquals(listOf("d4"), state.ungroupedDevices(devices).map { it.id })
  }

  @Test fun codecRoundTripsOverlappingGroups() {
    val state = DeviceGroupState(listOf(
      DeviceGroup("a", "A", deviceIds = listOf("d1", "d2")),
      DeviceGroup("b", "B", deviceIds = listOf("d2", "d3"))
    ), selectedGroupId = "b")
    assertEquals(state, DeviceGroupCodec.decode(DeviceGroupCodec.encode(state)).sanitized(setOf("d1", "d2", "d3")))
  }

  @Test fun groupLookupRemainsFirstMatchForSharedDevice() {
    val first = DeviceGroup("a", "A", deviceIds = listOf("d1"))
    val state = DeviceGroupState(listOf(first, DeviceGroup("b", "B", deviceIds = listOf("d1"))))
    assertEquals(first, state.groupOfDevice("d1"))
    assertEquals(first, state.groupOf(profile("p1", "d1")))
  }

  @Test fun controllerCreatePreservesExistingMembershipsAndSelectedGroup() {
    Fixture().use { fixture ->
      val before = fixture.groups()
      val createdId = fixture.controller.createDeviceGroup("New", listOf("d1", "d2", "d2", "missing"))
      assertTrue(createdId.isNotBlank())
      assertEquals(before, fixture.groups().filterNot { it.id == createdId })
      assertEquals(listOf("d1", "d2"), fixture.groups().first { it.id == createdId }.deviceIds)
      fixture.assertSelection("b")
      assertNull(fixture.state.value.error)
    }
  }

  @Test fun controllerUpdateOnlyReplacesTheEditedGroupsMemberships() {
    Fixture().use { fixture ->
      val other = fixture.groups().first { it.id == "b" }
      fixture.controller.updateDeviceGroup("a", "A edited", listOf("d2", "d3", "d3", "missing"))
      assertEquals("A edited", fixture.groups().first { it.id == "a" }.name)
      assertEquals(listOf("d2", "d3"), fixture.groups().first { it.id == "a" }.deviceIds)
      assertEquals(other, fixture.groups().first { it.id == "b" })
      fixture.assertSelection("b")
      assertNull(fixture.state.value.error)
    }
  }

  @Test fun selectingAnOverlappingGroupKeepsItsOwnIdAndCurrentMember() {
    Fixture().use { fixture ->
      val before = fixture.groups()
      fixture.controller.selectGroup("a")
      fixture.assertSelection("a")
      assertEquals(before, fixture.groups())
      assertEquals(listOf("p1", "p2"), fixture.state.value.homeGroupProfiles.map { it.id })
    }
  }

  private class Fixture : AutoCloseable {
    private val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val deviceStore = DeviceStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    private val discoveryStore = DeviceDiscoveryStore(memoryPreferences())
    private val groupStore = DeviceGroupStore(memoryPreferences())
    private val coordinator: DeviceCoordinator
    private val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it }, readProfiles = { deviceStore.profiles() })
    private val initialGroups = listOf(
      DeviceGroup("a", "A", deviceIds = listOf("d1", "d2")),
      DeviceGroup("b", "B", deviceIds = listOf("d2", "d3"))
    )
    val state: MutableStateFlow<LagoonState>
    private val overview = AllServersOverview(store, CatalogCache { null }, scope, {})
    val controller: LagoonController

    init {
      listOf("d1" to "p1", "d2" to "p2", "d3" to "p3").forEach { (did, hid) ->
        deviceStore.saveDevice(
          SshDevice(did, did, "$did.example", user = "me", harnesses = listOf(DeviceHarness(hid, did, HarnessProtocol.DSH))),
          SshSecrets(password = "pw")
        )
      }
      groupStore.save(DeviceGroupState(initialGroups, selectedGroupId = "b"))
      coordinator = DeviceCoordinator(deviceStore, discoveryStore, groupStore)
      state = MutableStateFlow(LagoonState(
        devices = coordinator.devices(),
        profiles = coordinator.profiles(),
        deviceGroups = initialGroups,
        homeGroupId = "b",
        serverId = "p2",
        connected = true,
        allServers = false
      ))
      controller = allocate(LagoonController::class.java)
      put(controller, "store", store)
      put(controller, "deviceCoordinator", coordinator)
      put(controller, "mutable", state)
      put(controller, "state", state)
      put(controller, "scope", scope)
      put(controller, "allOverview", overview)
    }

    fun groups(): List<DeviceGroup> = groupStore.load().state.groups

    fun assertSelection(groupId: String) {
      assertEquals(groupId, state.value.homeGroupId)
      assertEquals(groupId, groupStore.load().state.selectedGroupId)
      assertEquals("p2", state.value.serverId)
      assertTrue(state.value.connected)
      assertTrue(!state.value.allServers)
    }

    override fun close() {
      overview.stop()
      scope.cancel()
    }
  }

  private companion object {
    fun device(id: String) = SshDevice(id, id, "$id.example", user = "me")
    fun profile(id: String, deviceId: String) = ServerProfile(id, id, "ssh://me@$deviceId.example:22", protocol = HarnessProtocol.DSH, deviceId = deviceId)

    fun allocate(type: Class<LagoonController>): LagoonController {
      val unsafeClass = Class.forName("sun.misc.Unsafe")
      val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
      return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    fun put(target: Any, name: String, value: Any?) {
      target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
  }
}
