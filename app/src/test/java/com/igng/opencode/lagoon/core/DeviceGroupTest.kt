package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGroupTest {
  private fun profile(id: String, name: String = id) = ServerProfile(id, name, "https://example.test", kind = ServerKind.OPENCODE)

  @Test fun codecRoundTripsGroupsAndSelection() {
    val state = DeviceGroupState(
      groups = listOf(DeviceGroup("g1", "MacBook", listOf("p1", "p2"), 42), DeviceGroup("g2", "Studio", listOf("p3"))),
      selectedGroupId = "g2"
    )
    val decoded = DeviceGroupCodec.decode(DeviceGroupCodec.encode(state))
    assertEquals(state, decoded)
  }

  @Test fun corruptCodecFailsWithoutThrowingJson() {
    try {
      DeviceGroupCodec.decode("{not-json")
      assertTrue("expected IOException", false)
    } catch (_: java.io.IOException) { }
  }

  @Test fun sanitizeDropsUnknownDuplicatesAndEmptyGroups() {
    val state = DeviceGroupState(
      groups = listOf(
        DeviceGroup("g1", "A", listOf("p1", "missing", "p1", "p2")),
        DeviceGroup("g1", "duplicate id", listOf("p3")),
        DeviceGroup("g2", "empty", listOf("missing")),
        DeviceGroup("g3", "  B  ", listOf("p3"))
      ),
      selectedGroupId = "g2"
    )
    val clean = state.sanitized(setOf("p1", "p2", "p3"))
    assertEquals(listOf("g1", "g3"), clean.groups.map { it.id })
    assertEquals(listOf("p1", "p2"), clean.groups[0].memberIds)
    assertEquals("B", clean.groups[1].name)
    assertNull(clean.selectedGroupId)
  }

  @Test fun tabsPutExplicitGroupsFirstAndRenderUngroupedAsSingletons() {
    val state = DeviceGroupState(listOf(DeviceGroup("g1", "MacBook", listOf("p1", "p2"))))
    val tabs = state.tabs(listOf(profile("p1"), profile("p2"), profile("p3")))
    assertEquals(listOf("group:g1", "profile:p3"), tabs.map { it.id })
    assertEquals(listOf("p1", "p2"), tabs[0].profiles.map { it.id })
    assertTrue(tabs[0].isGroup)
  }

  @Test fun workspacesMergeSameNamesAcrossHarnessesButKeepMembers() {
    val p1 = Project("p1", "/home/a/lagoon", "Lagoon", serverId = "s1")
    val p2 = Project("p2", "/Users/a/Lagoon", "lagoon", serverId = "s2")
    val s1 = Session("1", "/home/a/lagoon", "one", 1, serverId = "s1")
    val s2 = Session("2", "/Users/a/Lagoon", "two", 2, serverId = "s2")
    val workspaces = deviceWorkspaces(listOf(s1, s2), listOf(p1, p2), "g1")
    assertEquals(1, workspaces.size)
    assertEquals(2, workspaces.single().members.size)
    assertEquals(2, workspaces.single().sessions.size)
    assertEquals("lagoon", normalizedWorkspaceName(workspaces.single().name))
  }

  @Test fun workspacesKeepUnassignedSessionsSeparateFromNamedOnes() {
    val p = Project("p1", "/home/a/lagoon", "Lagoon", serverId = "s1")
    val assigned = Session("1", "/home/a/lagoon", "one", 1, serverId = "s1")
    val orphan = Session("2", "/tmp/scratch", "two", 2, serverId = "s1")
    val workspaces = deviceWorkspaces(listOf(assigned, orphan), listOf(p), "g1")
    assertEquals(2, workspaces.size)
    assertTrue(workspaces.any { it.name == "未归入项目" && it.sessions.single().id == "2" })
  }

  @Test fun workspaceKeysAreStableForSameNormalizedNames() {
    val p = Project("p1", "/home/a/lagoon", "  LAGOON  ", serverId = "s1")
    val session = Session("1", "/home/a/lagoon", "one", 1, serverId = "s1")
    assertEquals(deviceWorkspaceKey(session, listOf(p), "g1"), deviceWorkspaces(listOf(session), listOf(p), "g1").single().key)
  }

  @Test fun workspacesAreScopedPerGroup() {
    val p = Project("p1", "/home/a/lagoon", "Lagoon", serverId = "s1")
    val session = Session("1", "/home/a/lagoon", "one", 1, serverId = "s1")
    val a = deviceWorkspaces(listOf(session), listOf(p), "g1").single().key
    val b = deviceWorkspaces(listOf(session), listOf(p), "g2").single().key
    assertTrue(a != b)
  }

}
