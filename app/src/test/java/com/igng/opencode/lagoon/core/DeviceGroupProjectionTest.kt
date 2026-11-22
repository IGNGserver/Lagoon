package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGroupProjectionTest {
  private fun profile(id: String, name: String, deviceId: String = "dev-$id", protocol: HarnessProtocol = HarnessProtocol.OPENCODE_NATIVE) =
    ServerProfile(id, name, "ssh://me@$deviceId:22", protocol = protocol, deviceId = deviceId)

  private fun snapshot(id: String, protocol: HarnessProtocol, connected: Boolean, sessions: List<Session>, projects: List<Project>, deviceId: String = "dev-$id") =
    ServerOverviewSnapshot(
      serverId = id,
      profile = profile(id, id, deviceId, protocol),
      connected = connected,
      sessions = sessions.map { it.copy(serverId = id) },
      projects = projects.map { it.copy(serverId = id) }
    )

  @Test fun groupProjectionAggregatesMembersAndKeepsOneWritableProfile() {
    val a = profile("a", "A", "dev-a", HarnessProtocol.OPENCODE_NATIVE)
    val b = profile("b", "B", "dev-b", HarnessProtocol.DSH)
    val group = DeviceGroup("g", "MacBook", deviceIds = listOf("dev-a", "dev-b"))
    val sa = snapshot("a", HarnessProtocol.OPENCODE_NATIVE, true,
      listOf(Session("s1", "/repo", "one", 1)), listOf(Project("p1", "/repo", "Repo")))
    val sb = snapshot("b", HarnessProtocol.DSH, true,
      listOf(Session("s2", "/repo", "two", 2)), listOf(Project("p2", "/repo", "Repo")))
    val state = LagoonState(profiles = listOf(a, b), deviceGroups = listOf(group), homeGroupId = "g",
      serverId = "a", allServerSnapshots = mapOf("a" to sa, "b" to sb))
    val home = state.homeProjection()
    assertTrue(home.multiServerView)
    assertEquals("g", home.homeGroupId)
    assertEquals(listOf("s1", "s2"), home.sessions.map { it.id }.sorted())
    assertEquals(2, home.projects.size)
    assertTrue(home.connected)
    assertTrue(home.allSessionStatuses.isNotEmpty())
  }

  @Test fun groupProjectionDoesNotLeakNonMembers() {
    val a = profile("a", "A", "dev-a")
    val b = profile("b", "B", "dev-b")
    val c = profile("c", "C", "dev-c")
    val group = DeviceGroup("g", "A", deviceIds = listOf("dev-a"))
    val state = LagoonState(profiles = listOf(a, b, c), deviceGroups = listOf(group), homeGroupId = "g", serverId = "a",
      allServerSnapshots = mapOf(
        "a" to snapshot("a", HarnessProtocol.OPENCODE_NATIVE, true, listOf(Session("s1", "/repo", "one", 1)), emptyList()),
        "b" to snapshot("b", HarnessProtocol.OPENCODE_NATIVE, true, listOf(Session("s2", "/repo", "two", 2)), emptyList())
      ))
    val home = state.homeProjection()
    assertEquals(listOf("s1"), home.sessions.map { it.id })
    assertFalse(home.sessions.any { it.id == "s2" })
    assertEquals(setOf("a"), home.allServerSnapshots.keys)
  }

  @Test fun groupWorkspaceCatalogFollowsTheWritableProfileNotTheOpenConversation() {
    val a = profile("a", "A", "dev-a")
    val b = profile("b", "B", "dev-b", HarnessProtocol.DSH)
    val group = DeviceGroup("g", "MacBook", deviceIds = listOf("dev-a", "dev-b"))
    val off = snapshot("a", HarnessProtocol.OPENCODE_NATIVE, false, listOf(Session("s1", "/work/a", "one", 1)), emptyList())
      .copy(workspaceCatalog = listOf(Project("pa", "/work/a", "A title", serverId = "a")))
    val on = snapshot("b", HarnessProtocol.DSH, true, listOf(Session("s2", "/work/b", "two", 2)), emptyList())
      .copy(workspaceCatalog = listOf(Project("pb", "/work/b", "B title", serverId = "b")))
    val state = LagoonState(profiles = listOf(a, b), deviceGroups = listOf(group), homeGroupId = "g", serverId = "a",
      allServerSnapshots = mapOf("a" to off, "b" to on))
    val home = state.homeProjection()
    assertEquals("b", home.serverId)
    assertEquals(listOf("B title"), home.workspaceCatalog.map { it.name })
    val session = home.sessions.single { it.id == "s2" }
    assertEquals("B title", resolveSessionWorkspace(session, home.projects, home.workspaceCatalogFor(session))!!.name)
  }

  @Test fun oneOfflineCachedMemberDoesNotPinTheWholeGroup() {
    val a = profile("a", "A", "dev-a")
    val b = profile("b", "B", "dev-b", HarnessProtocol.DSH)
    val group = DeviceGroup("g", "MacBook", deviceIds = listOf("dev-a", "dev-b"))
    val online = snapshot("a", HarnessProtocol.OPENCODE_NATIVE, true, listOf(Session("s1", "/repo", "one", 1)), emptyList())
      .copy(cached = true, loading = false)
    val offline = snapshot("b", HarnessProtocol.DSH, false, listOf(Session("s2", "/repo", "two", 2)), emptyList())
      .copy(cached = true, loading = false, degraded = true)
    val state = LagoonState(profiles = listOf(a, b), deviceGroups = listOf(group), homeGroupId = "g",
      serverId = "a", allServerSnapshots = mapOf("a" to online, "b" to offline))
    val home = state.homeProjection()
    assertTrue(home.connected)
    assertFalse("一台设备离线不能把整个设备组标成缓存页", home.cached)
    assertFalse("离线设备的读失败不能点亮全组的待同步横幅", home.degraded)
    assertNull(home.homeSyncNotice())
  }

  @Test fun aGroupWithEveryMemberOfflineStillShowsItsCache() {
    val a = profile("a", "A", "dev-a")
    val b = profile("b", "B", "dev-b", HarnessProtocol.DSH)
    val group = DeviceGroup("g", "MacBook", deviceIds = listOf("dev-a", "dev-b"))
    val state = LagoonState(profiles = listOf(a, b), deviceGroups = listOf(group), homeGroupId = "g",
      serverId = "a", allServerSnapshots = mapOf(
        "a" to snapshot("a", HarnessProtocol.OPENCODE_NATIVE, false, listOf(Session("s1", "/repo", "one", 1)), emptyList()).copy(cached = true, loading = false, degraded = true),
        "b" to snapshot("b", HarnessProtocol.DSH, false, listOf(Session("s2", "/repo", "two", 2)), emptyList()).copy(cached = true, loading = false, degraded = true)
      ))
    val home = state.homeProjection()
    assertTrue(home.cached)
    assertEquals("离线缓存", home.homeSyncNotice())
  }

  @Test fun noGroupKeepsSingleServerBehavior() {
    val a = profile("a", "A", "dev-a")
    val state = LagoonState(profiles = listOf(a), serverId = "a",
      allServerSnapshots = mapOf("a" to snapshot("a", HarnessProtocol.OPENCODE_NATIVE, true, listOf(Session("s1", "/repo", "one", 1)), emptyList())))
    val home = state.homeProjection()
    assertFalse(home.multiServerView)
    assertEquals(null, home.homeGroupId)
    assertEquals("a", home.serverId)
  }

  @Test fun tabsOnlyContainExplicitGroups() {
    val a = profile("a", "A", "dev-a")
    val b = profile("b", "B", "dev-b")
    val c = profile("c", "C", "dev-c")
    val state = LagoonState(profiles = listOf(a, b, c), deviceGroups = listOf(DeviceGroup("g", "MacBook", deviceIds = listOf("dev-a", "dev-b"))))
    assertEquals(listOf("group:g"), state.deviceTabs.map { it.id })
  }

  @Test fun allDevicesModeAggregatesEveryProfile() {
    val a = profile("a", "A", "dev-a")
    val b = profile("b", "B", "dev-b")
    val state = LagoonState(profiles = listOf(a, b), serverId = "a", allServers = true,
      allServerSnapshots = mapOf(
        "a" to snapshot("a", HarnessProtocol.OPENCODE_NATIVE, true, listOf(Session("s1", "/repo", "one", 1)), emptyList()),
        "b" to snapshot("b", HarnessProtocol.OPENCODE_NATIVE, true, listOf(Session("s2", "/repo", "two", 2)), emptyList())
      ))
    val home = state.homeProjection()
    assertTrue(home.multiServerView)
    assertEquals(listOf("s1", "s2"), home.sessions.map { it.id }.sorted())
  }

  @Test fun singleDeviceHomeAggregatesOnlyThatDevicesHarnesses() {
    val dev1 = SshDevice("dev-1", "Linux Dev", "10.0.0.1", user = "me")
    val a = profile("a", "DSH", "dev-1", HarnessProtocol.DSH)
    val b = profile("b", "Codex", "dev-1", HarnessProtocol.CODEX)
    val c = profile("c", "Other", "dev-2", HarnessProtocol.DSH)
    val state = LagoonState(
      devices = listOf(dev1),
      profiles = listOf(a, b, c),
      homeDeviceId = "dev-1",
      serverId = "a",
      allServerSnapshots = mapOf(
        "a" to snapshot("a", HarnessProtocol.DSH, true, listOf(Session("s1", "/repo", "one", 1)), emptyList(), "dev-1"),
        "b" to snapshot("b", HarnessProtocol.CODEX, true, listOf(Session("s2", "/repo", "two", 2)), emptyList(), "dev-1"),
        "c" to snapshot("c", HarnessProtocol.DSH, true, listOf(Session("s3", "/repo", "three", 3)), emptyList(), "dev-2")
      )
    )
    val home = state.homeProjection()
    assertTrue(home.multiServerView)
    assertEquals("dev-1", home.homeDeviceId)
    assertEquals(listOf("s1", "s2"), home.sessions.map { it.id }.sorted())
    assertEquals(setOf("a", "b"), home.allServerSnapshots.keys)
  }
}
