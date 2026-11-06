package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGroupProjectionTest {
  private fun profile(id: String, name: String, kind: ServerKind = ServerKind.OPENCODE) =
    ServerProfile(id, name, "https://example.test", kind = kind)

  private fun snapshot(id: String, kind: ServerKind, connected: Boolean, sessions: List<Session>, projects: List<Project>) =
    ServerOverviewSnapshot(
      serverId = id,
      profile = profile(id, id, kind),
      connected = connected,
      sessions = sessions.map { it.copy(serverId = id) },
      projects = projects.map { it.copy(serverId = id) }
    )

  @Test fun groupProjectionAggregatesMembersAndKeepsOneWritableProfile() {
    val a = profile("a", "A", ServerKind.OPENCODE)
    val b = profile("b", "B", ServerKind.DSH_REMOTE)
    val group = DeviceGroup("g", "MacBook", listOf("a", "b"))
    val sa = snapshot("a", ServerKind.OPENCODE, true,
      listOf(Session("s1", "/repo", "one", 1)), listOf(Project("p1", "/repo", "Repo")))
    val sb = snapshot("b", ServerKind.DSH_REMOTE, true,
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
    val a = profile("a", "A")
    val b = profile("b", "B")
    val c = profile("c", "C")
    val group = DeviceGroup("g", "A", listOf("a"))
    val state = LagoonState(profiles = listOf(a, b, c), deviceGroups = listOf(group), homeGroupId = "g", serverId = "a",
      allServerSnapshots = mapOf(
        "a" to snapshot("a", ServerKind.OPENCODE, true, listOf(Session("s1", "/repo", "one", 1)), emptyList()),
        "b" to snapshot("b", ServerKind.OPENCODE, true, listOf(Session("s2", "/repo", "two", 2)), emptyList())
      ))
    val home = state.homeProjection()
    assertEquals(listOf("s1"), home.sessions.map { it.id })
    assertFalse(home.sessions.any { it.id == "s2" })
    assertEquals(setOf("a"), home.allServerSnapshots.keys)
  }

  @Test fun groupWorkspaceCatalogFollowsTheWritableProfileNotTheOpenConversation() {
    val a = profile("a", "A")
    val b = profile("b", "B")
    val group = DeviceGroup("g", "MacBook", listOf("a", "b"))
    val off = snapshot("a", ServerKind.OPENCODE, false, listOf(Session("s1", "/work/a", "one", 1)), emptyList())
      .copy(workspaceCatalog = listOf(Project("pa", "/work/a", "A title", serverId = "a")))
    val on = snapshot("b", ServerKind.DSH_REMOTE, true, listOf(Session("s2", "/work/b", "two", 2)), emptyList())
      .copy(workspaceCatalog = listOf(Project("pb", "/work/b", "B title", serverId = "b")))
    val state = LagoonState(profiles = listOf(a, b), deviceGroups = listOf(group), homeGroupId = "g", serverId = "a",
      allServerSnapshots = mapOf("a" to off, "b" to on))
    val home = state.homeProjection()
    // a 掉线后组内可写连接切到 b，行内标签必须跟着 b，而不是当前打开会话所在的 a。
    assertEquals("b", home.serverId)
    assertEquals(listOf("B title"), home.workspaceCatalog.map { it.name })
    val session = home.sessions.single { it.id == "s2" }
    assertEquals("B title", resolveSessionWorkspace(session, home.projects, home.workspaceCatalogFor(session))!!.name)
  }

  @Test fun noGroupKeepsSingleServerBehavior() {
    val a = profile("a", "A")
    val state = LagoonState(profiles = listOf(a), serverId = "a",
      allServerSnapshots = mapOf("a" to snapshot("a", ServerKind.OPENCODE, true, listOf(Session("s1", "/repo", "one", 1)), emptyList())))
    val home = state.homeProjection()
    assertFalse(home.multiServerView)
    assertEquals(null, home.homeGroupId)
    assertEquals("a", home.serverId)
  }

  @Test fun tabsOnlyContainExplicitGroups() {
    val a = profile("a", "A")
    val b = profile("b", "B")
    val c = profile("c", "C")
    val state = LagoonState(profiles = listOf(a, b, c), deviceGroups = listOf(DeviceGroup("g", "MacBook", listOf("a", "b"))))
    assertEquals(listOf("group:g"), state.deviceTabs.map { it.id })
  }

  @Test fun allDevicesModeAggregatesEveryProfile() {
    val a = profile("a", "A")
    val b = profile("b", "B")
    val state = LagoonState(profiles = listOf(a, b), serverId = "a", allServers = true,
      allServerSnapshots = mapOf(
        "a" to snapshot("a", ServerKind.OPENCODE, true, listOf(Session("s1", "/repo", "one", 1)), emptyList()),
        "b" to snapshot("b", ServerKind.OPENCODE, true, listOf(Session("s2", "/repo", "two", 2)), emptyList())
      ))
    val home = state.homeProjection()
    assertTrue(home.multiServerView)
    assertEquals(listOf("s1", "s2"), home.sessions.map { it.id }.sorted())
  }
}
