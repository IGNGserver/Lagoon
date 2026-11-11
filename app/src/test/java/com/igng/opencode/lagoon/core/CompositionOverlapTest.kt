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
  @Test fun sanitizerDeduplicatesWithinEachCompositionAndKeepsCrossCompositionMembership() {
    val state = DeviceGroupState(
      groups = listOf(
        DeviceGroup("a", "A", listOf("p1", "p1", "p2", "missing")),
        DeviceGroup("b", "B", listOf("p2", "p2", "p3"))
      ),
      selectedGroupId = "b"
    )

    val clean = state.sanitized(setOf("p1", "p2", "p3"))

    assertEquals(listOf(listOf("p1", "p2"), listOf("p2", "p3")), clean.groups.map { it.memberIds })
    assertEquals("b", clean.selectedGroupId)
  }

  @Test fun eachTabProjectsItsOwnMembershipIncludingSharedLeaves() {
    val profiles = listOf(profile("p1"), profile("p2"), profile("p3"), profile("ungrouped"))
    val state = DeviceGroupState(listOf(
      DeviceGroup("a", "A", listOf("p1", "p2", "p1")),
      DeviceGroup("b", "B", listOf("p2", "p3"))
    ))

    val tabs = state.tabs(profiles)

    assertEquals(listOf(listOf("p1", "p2"), listOf("p2", "p3")), tabs.map { tab -> tab.profiles.map { it.id } })
    assertEquals(listOf("ungrouped"), state.ungrouped(profiles).map { it.id })
  }

  @Test fun codecRoundTripsOverlappingCompositions() {
    val state = DeviceGroupState(listOf(
      DeviceGroup("a", "A", listOf("p1", "p2")),
      DeviceGroup("b", "B", listOf("p2", "p3"))
    ), selectedGroupId = "b")

    assertEquals(state, DeviceGroupCodec.decode(DeviceGroupCodec.encode(state)).sanitized(setOf("p1", "p2", "p3")))
  }

  @Test fun groupLookupRemainsFirstMatchForSharedLeaf() {
    val first = DeviceGroup("a", "A", listOf("p1"))
    val state = DeviceGroupState(listOf(first, DeviceGroup("b", "B", listOf("p1"))))

    assertEquals(first, state.groupOf("p1"))
  }

  @Test fun controllerCreatePreservesExistingMembershipsAndSelectedGroup() {
    Fixture().use { fixture ->
      val before = fixture.groups()
      val createdId = fixture.controller.createDevice("New", listOf("p1", "p2", "p2", "missing"))

      assertTrue(createdId.isNotBlank())
      assertEquals(before, fixture.groups().filterNot { it.id == createdId })
      assertEquals(listOf("p1", "p2"), fixture.groups().first { it.id == createdId }.memberIds)
      fixture.assertSelection("b")
      assertNull(fixture.state.value.error)
    }
  }

  @Test fun controllerUpdateOnlyReplacesTheEditedGroupsMemberships() {
    Fixture().use { fixture ->
      val other = fixture.groups().first { it.id == "b" }

      fixture.controller.updateDevice("a", "A edited", listOf("p2", "p3", "p3", "missing"))

      assertEquals("A edited", fixture.groups().first { it.id == "a" }.name)
      assertEquals(listOf("p2", "p3"), fixture.groups().first { it.id == "a" }.memberIds)
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

  @Test fun explicitMoveStillRemovesTheLeafFromOtherGroups() {
    Fixture().use { fixture ->
      fixture.controller.moveProfile("p1", "b")

      assertEquals(listOf("p2"), fixture.groups().first { it.id == "a" }.memberIds)
      assertEquals(listOf("p2", "p3", "p1"), fixture.groups().first { it.id == "b" }.memberIds)
      fixture.assertSelection("b")
    }
  }

  private class Fixture : AutoCloseable {
    private val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val profilePreferences = memoryPreferences()
    private val groupPreferences = memoryPreferences()
    private val store = ServerStore(profilePreferences, memoryPreferences(), { it }, { it })
    private val groupStore = DeviceGroupStore(groupPreferences)
    private val initialGroups = listOf(
      DeviceGroup("a", "A", listOf("p1", "p2")),
      DeviceGroup("b", "B", listOf("p2", "p3"))
    )
    val state = MutableStateFlow(LagoonState(
      profiles = listOf(profile("p1"), profile("p2"), profile("p3")),
      deviceGroups = initialGroups,
      homeGroupId = "b",
      serverId = "p2",
      connected = true,
      allServers = false
    ))
    private val overview = AllServersOverview(store, CatalogCache { null }, scope, {})
    val controller: LagoonController

    init {
      state.value.profiles.forEach { store.save(it, null) }
      groupStore.save(DeviceGroupState(initialGroups))
      controller = allocate(LagoonController::class.java)
      put(controller, "store", store)
      put(controller, "deviceGroupStore", groupStore)
      put(controller, "mutable", state)
      put(controller, "state", state)
      put(controller, "scope", scope)
      put(controller, "allOverview", overview)
      put(controller, "connectionGraphWritable", false)
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
    fun profile(id: String) = ServerProfile(id, id, "https://$id.example")

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
