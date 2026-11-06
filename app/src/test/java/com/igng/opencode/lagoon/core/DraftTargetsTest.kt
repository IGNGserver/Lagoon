package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftTargetsTest {
  private fun profile(id: String, name: String, kind: ServerKind = ServerKind.OPENCODE, sshHostId: String? = null,
    enabled: Boolean = true) =
    ServerProfile(id, name, "https://$id.test", kind = kind, sshHostId = sshHostId, enabled = enabled)

  private fun snapshot(
    id: String,
    profile: ServerProfile,
    connected: Boolean,
    loading: Boolean = false,
    projects: List<Project> = emptyList(),
    catalog: List<Project> = emptyList(),
    sessions: List<Session> = emptyList()
  ) = ServerOverviewSnapshot(
    serverId = id,
    profile = profile,
    connected = connected,
    loading = loading,
    sessions = sessions.map { it.copy(serverId = id) },
    projects = projects.map { it.copy(serverId = id) },
    workspaceCatalog = catalog.map { it.copy(serverId = id) }
  )

  @Test fun aggregateListsEveryEnabledProfileEvenWithoutProjects() {
    val a = profile("a", "MacBook", ServerKind.OPENCODE)
    val b = profile("b", "Desktop", ServerKind.DSH, sshHostId = "ssh-1")
    val c = profile("c", "Hub", ServerKind.DSH_REMOTE)
    val state = LagoonState(
      profiles = listOf(a, b, c), serverId = "a", connected = true,
      allServerSnapshots = mapOf(
        "b" to snapshot("b", b, connected = true),
        "c" to snapshot("c", c, connected = false)
      )
    )

    val servers = state.draftServers()
    assertEquals(listOf("a", "b", "c"), servers.map { it.id })
    assertEquals(listOf("OpenCode 直连", "DSH SSH 直连", "DSH 远程中枢"), servers.map { it.connectionLabel })
    assertEquals("已连接", servers[0].statusLabel)
    assertEquals("已连接", servers[1].statusLabel)
    assertEquals("未连接", servers[2].statusLabel)
    assertTrue(servers.all { it.registered.isEmpty() && it.serverWorkspaces.isEmpty() })
    assertEquals(0, servers[2].workspaceCount)
  }

  @Test fun currentServerComesFirstEvenWhenAnotherIsOffline() {
    val a = profile("a", "A")
    val b = profile("b", "B")
    val state = LagoonState(
      profiles = listOf(b, a), serverId = "a", connected = false,
      allServerSnapshots = mapOf("b" to snapshot("b", b, connected = true))
    )

    // The writable connection stays first even while offline: the blank page can wait for it.
    assertEquals(listOf("a", "b"), state.draftServers().map { it.id })
  }

  @Test fun groupScopeKeepsOnlyItsMembers() {
    val a = profile("a", "A")
    val b = profile("b", "B")
    val c = profile("c", "C")
    val group = DeviceGroup("g", "Group", listOf("a", "b"))
    val state = LagoonState(
      profiles = listOf(a, b, c), deviceGroups = listOf(group), homeGroupId = "g",
      serverId = "a", connected = true,
      allServerSnapshots = mapOf(
        "a" to snapshot("a", a, connected = true),
        "b" to snapshot("b", b, connected = false),
        "c" to snapshot("c", c, connected = true)
      )
    )

    assertEquals(listOf("a", "b"), state.draftServers(setOf("a", "b")).map { it.id })
  }

  @Test fun disabledProfilesAreNeverCandidates() {
    val a = profile("a", "A")
    val disabled = profile("b", "B", enabled = false)
    val state = LagoonState(profiles = listOf(a, disabled), serverId = "a", connected = true)

    assertEquals(listOf("a"), state.draftServers().map { it.id })
  }

  @Test fun workspaceChoicesMergeRegisteredAndServerCatalogWithoutDuplicates() {
    val a = profile("a", "A")
    val registered = Project("p1", "/repo/a", "Repo A")
    val duplicate = Project("p1-dup", "/repo/a/", "Repo A (duplicate)")
    val serverOnly = Project("p2", "/repo/b", "Repo B")
    val state = LagoonState(
      profiles = listOf(a), serverId = "a", connected = true, projects = listOf(registered),
      workspaceCatalog = listOf(duplicate, serverOnly).map { it.copy(serverId = "a") }
    )

    val workspaces = state.draftWorkspaces("a")
    assertEquals(listOf("p1", "p2"), workspaces.map { it.id })
    assertTrue(workspaces[0].registered)
    assertFalse(workspaces[1].registered)
    assertEquals("/repo/b", workspaces[1].directory)
  }

  @Test fun otherServersReadTheirOwnSnapshot() {
    val a = profile("a", "A")
    val b = profile("b", "B")
    val state = LagoonState(
      profiles = listOf(a, b), serverId = "a", connected = true, projects = listOf(Project("pa", "/a", "A")),
      allServerSnapshots = mapOf(
        "b" to snapshot("b", b, connected = true,
          projects = listOf(Project("pb", "/work/b", "B work")),
          catalog = listOf(Project("pc", "/work/c", "C work")))
      )
    )

    assertEquals(listOf("pb", "pc"), state.draftWorkspaces("b").map { it.id })
    assertTrue(state.draftWorkspaces("b").first().registered)
    assertFalse(state.draftWorkspaces("b").last().registered)
    assertTrue(state.serverConnected("b"))
    assertEquals(listOf("pa"), state.draftWorkspaces("a").map { it.id })
  }

  @Test fun serverCatalogIsScopedToItsServer() {
    val a = profile("a", "A")
    val state = LagoonState(
      profiles = listOf(a), serverId = "a",
      workspaceCatalog = listOf(
        Project("x", "/x", "X", serverId = "other"),
        Project("y", "/y", "Y", serverId = "a")
      )
    )

    assertEquals(listOf("y"), state.serverWorkspaceCatalogOn("a").map { it.id })
  }

  @Test fun directTargetIsUsedOnlyWhenTheChoiceIsUnambiguous() {
    val a = profile("a", "A")
    val b = profile("b", "B")
    val alone = LagoonState(profiles = listOf(a), serverId = "a", connected = false, allServers = true)
    assertEquals("a", alone.directDraftServer()?.id)

    val aggregate = LagoonState(
      profiles = listOf(a, b), serverId = "a", connected = true, allServers = true,
      allServerSnapshots = mapOf("b" to snapshot("b", b, connected = true))
    )
    assertNull(aggregate.directDraftServer())

    val singleConnected = aggregate.copy(allServers = false)
    assertEquals("a", singleConnected.directDraftServer()?.id)

    val singleOffline = singleConnected.copy(connected = false)
    assertNull(singleOffline.directDraftServer())

    val group = aggregate.copy(allServers = false, homeGroupId = "g",
      deviceGroups = listOf(DeviceGroup("g", "G", listOf("a", "b"))))
    assertNull(group.directDraftServer(setOf("a", "b")))
  }

  @Test fun preferredWorkspaceUsesExplicitChoiceThenHomeScopeThenRecent() {
    val a = profile("a", "A")
    val first = Project("p1", "/repo/a", "Repo A")
    val recent = Project("p2", "/repo/b", "Repo B")
    val session = Session("s1", "/repo/b", "Task", 100, projectId = "p2", serverId = "a")
    val state = LagoonState(
      profiles = listOf(a), serverId = "a", connected = true,
      projects = listOf(first, recent), sessions = listOf(session)
    )

    assertEquals("p2", state.preferredDraftProject("a")?.id)
    assertEquals("p1", state.preferredDraftProject("a", preferred = "p1")?.id)
    assertNull(state.preferredDraftProject("other"))
  }

  @Test fun preferredWorkspaceFallsBackWhenHomeScopePointsElsewhere() {
    val a = profile("a", "A")
    val inScope = Project("p1", "/repo/a", "Repo A")
    val other = Project("p2", "/repo/b", "Repo B")
    val state = LagoonState(
      profiles = listOf(a), serverId = "a", connected = true,
      projects = listOf(inScope, other), scopeProjectId = "p1"
    )

    assertEquals("p1", state.preferredDraftProject("a")?.id)
  }
}
