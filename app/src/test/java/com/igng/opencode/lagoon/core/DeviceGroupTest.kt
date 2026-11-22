package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGroupTest {
  private fun profile(id: String, deviceId: String, protocol: HarnessProtocol = HarnessProtocol.OPENCODE_NATIVE) =
    ServerProfile(id, id, "ssh://me@$deviceId:22", username = "me", protocol = protocol, deviceId = deviceId)

  private fun device(id: String) = SshDevice(id, id, "$id.test", user = "me")

  @Test fun codecRoundTripsGroupsAndSelection() {
    val state = DeviceGroupState(
      groups = listOf(
        DeviceGroup("g1", "MacBook", deviceIds = listOf("d1", "d2"), updatedAt = 42),
        DeviceGroup("g2", "Studio", deviceIds = listOf("d3"))
      ),
      selectedGroupId = "g2"
    )
    val decoded = DeviceGroupCodec.decode(DeviceGroupCodec.encode(state))
    assertEquals(state, decoded)
  }

  @Test fun v2GroupsWithoutDeviceIdsDecodeIntoLegacyMemberIdsForMigration() {
    val legacy = """{"version":2,"selected":null,"groups":[{"id":"g1","name":"Old","members":["p1"],"updatedAt":1}]}"""
    val decoded = DeviceGroupCodec.decode(legacy)
    assertEquals(listOf("p1"), decoded.groups.single().legacyMemberIds)
    assertTrue(decoded.groups.single().deviceIds.isEmpty())
  }

  @Test fun aDeviceGroupSurvivesWhileItsDeviceHasNoAttachedHarness() {
    val group = DeviceGroup("g1", "Just added", deviceIds = listOf("ssh-1"))
    val state = DeviceGroupState(listOf(group), "g1")
    val clean = state.sanitized(validDeviceIds = setOf("ssh-1"))
    assertEquals(listOf("g1"), clean.groups.map { it.id })
    assertEquals(listOf("ssh-1"), clean.groups.single().deviceIds)
    assertEquals("g1", clean.selectedGroupId)
    assertTrue(group.memberHarnessIds(emptyList<ServerProfile>()).isEmpty())
  }

  @Test fun groupDynamicallyExpandsWhenHarnessesAreAddedOrRemovedOnMemberDevice() {
    val group = DeviceGroup("g1", "Dev", deviceIds = listOf("ssh-1"))
    val p1 = profile("h1", "ssh-1", HarnessProtocol.DSH)
    val p2 = profile("h2", "ssh-1", HarnessProtocol.CODEX)
    val other = profile("h3", "ssh-2", HarnessProtocol.DSH)
    assertEquals(listOf("h1", "h2"), group.memberHarnessIds(listOf(p1, p2, other)))
    assertEquals(listOf("h1"), group.memberHarnessIds(listOf(p1, other)))
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
        DeviceGroup("g1", "A", deviceIds = listOf("d1", "missing", "d1", "d2")),
        DeviceGroup("g1", "duplicate id", deviceIds = listOf("d3")),
        DeviceGroup("g2", "empty", deviceIds = listOf("missing")),
        DeviceGroup("g3", "  B  ", deviceIds = listOf("d2", "d3", "d2"))
      ),
      selectedGroupId = "g2"
    )
    val clean = state.sanitized(setOf("d1", "d2", "d3"))
    assertEquals(listOf("g1", "g3"), clean.groups.map { it.id })
    assertEquals(listOf("d1", "d2"), clean.groups[0].deviceIds)
    assertEquals(listOf("d2", "d3"), clean.groups[1].deviceIds)
    assertEquals("B", clean.groups[1].name)
    assertNull(clean.selectedGroupId)
  }

  @Test fun tabsOnlyContainExplicitGroupsAndUngroupedDevicesStaySeparate() {
    val state = DeviceGroupState(listOf(DeviceGroup("g1", "MacBook", deviceIds = listOf("d1", "d2"))))
    val profiles = listOf(profile("p1", "d1"), profile("p2", "d2"), profile("p3", "d3"))
    val tabs = state.tabs(profiles)
    assertEquals(listOf("group:g1"), tabs.map { it.id })
    assertEquals(listOf("p1", "p2"), tabs[0].profiles.map { it.id })
    assertTrue(tabs[0].isGroup)
    assertEquals(listOf("d3"), state.ungroupedDevices(listOf(device("d1"), device("d2"), device("d3"))).map { it.id })
  }

  @Test fun connectionTypeLabelsAndSummariesAreUnifiedForAllFiveProtocols() {
    val dsh = profile("dsh", "ssh-1", HarnessProtocol.DSH).copy(url = "ssh://me@server:22")
    val oc = profile("oc", "ssh-1", HarnessProtocol.OPENCODE_NATIVE).copy(url = "ssh://me@server:22")
    val acp = profile("acp", "ssh-1", HarnessProtocol.OPENCODE_ACP).copy(url = "ssh://me@server:22")
    val codex = profile("codex", "ssh-1", HarnessProtocol.CODEX).copy(url = "ssh://me@server:22")
    val agy = profile("agy", "ssh-1", HarnessProtocol.ANTIGRAVITY_ACP).copy(url = "ssh://me@server:22")

    assertEquals("DeepSeek Harness · SSH", dsh.connectionTypeLabel)
    assertEquals("OpenCode · SSH", oc.connectionTypeLabel)
    assertEquals("OpenCode ACP · SSH", acp.connectionTypeLabel)
    assertEquals("Codex · SSH", codex.connectionTypeLabel)
    assertEquals("Antigravity · SSH", agy.connectionTypeLabel)

    assertEquals("DeepSeek Harness · me@server:22", dsh.endpointSummary)
    assertEquals("Codex · me@server:22", codex.endpointSummary)
  }

  @Test fun codecRejectsVersionOne() {
    val legacy = """{"version":1,"groups":[],"selected":null}"""
    assertEquals(1, DeviceGroupCodec.version(legacy))
    try {
      DeviceGroupCodec.decode(legacy)
      assertTrue("expected IOException", false)
    } catch (_: java.io.IOException) { }
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
