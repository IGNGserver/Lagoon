package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 行内工作区标签只是展示：已登记项目优先，其次服务端标题，最后是目录名。 */
class SessionWorkspaceTest {
  private fun session(id: String, directory: String, projectId: String? = null, serverId: String? = null) =
    Session(id, directory, id, 1, projectId = projectId, serverId = serverId)

  @Test fun registeredProjectWinsOverServerCatalogAndDirectory() {
    val registered = Project("p1", "/work/app", "App", serverId = "s1")
    val catalog = Project("p1", "/work/app", "Renamed on server", serverId = "s1")
    val workspace = resolveSessionWorkspace(session("a", "/work/app", "p1", "s1"), listOf(registered), listOf(catalog))!!
    assertEquals("App", workspace.name)
    assertEquals(WorkspaceSource.PROJECT, workspace.source)
    assertEquals(registered.key, workspace.projectKey)
  }

  @Test fun serverCatalogNamesRenamedWorkspacesAndWorktrees() {
    val catalog = listOf(Project("w1", "/work/repo", "Lagoon", sandboxes = listOf("/work/repo/.wt/feature"), serverId = "s1"))
    val worktree = resolveSessionWorkspace(session("a", "/work/repo/.wt/feature", serverId = "s1"), emptyList(), catalog)!!
    assertEquals("Lagoon", worktree.name)
    assertEquals(WorkspaceSource.SERVER, worktree.source)
    val subdirectory = resolveSessionWorkspace(session("b", "/work/repo/pkg/src", serverId = "s1"), emptyList(), catalog)!!
    assertEquals("Lagoon", subdirectory.name)
  }

  @Test fun directoryNameFallsBackToTheLastSegment() {
    assertEquals("Lagoon", resolveSessionWorkspace(session("a", "/home/me/work/Lagoon/"), emptyList())!!.name)
    assertEquals("Lagoon", resolveSessionWorkspace(session("b", "C:\\work\\Lagoon"), emptyList())!!.name)
    assertEquals(WorkspaceSource.DIRECTORY, resolveSessionWorkspace(session("c", "/srv/repo"), emptyList())!!.source)
  }

  @Test fun rootsBlankAndHomeHaveNoLabel() {
    for (path in listOf("", " ", "/", "~", "C:\\", "C:/")) {
      assertNull(path, resolveSessionWorkspace(session("a", path), emptyList()))
    }
  }

  @Test fun registryAndCatalogNeverLeakAcrossServers() {
    val otherProject = Project("p1", "/work/app", "Other", serverId = "s2")
    val otherCatalog = Project("w1", "/work/repo", "Other", serverId = "s2")
    val local = session("a", "/work/app", "p1", "s1")
    // Same project id and the same directory on another server must not be borrowed.
    assertEquals("app", resolveSessionWorkspace(local, listOf(otherProject), listOf(otherCatalog))!!.name)
    val sameDirectory = resolveSessionWorkspace(session("b", "/work/repo", serverId = "s1"), emptyList(), listOf(otherCatalog))!!
    assertEquals("repo", sameDirectory.name)
    assertEquals(WorkspaceSource.DIRECTORY, sameDirectory.source)
  }

  @Test fun labelsQualifySameNamedWorkspacesOnOneServer() {
    val sessions = listOf(
      session("a", "/work/one/Lagoon", serverId = "s1"),
      session("b", "/work/two/Lagoon", serverId = "s1"),
      session("c", "/opt/Lagoon", serverId = "s2")
    )
    val labels = sessionWorkspaceLabels(sessions, emptyList())
    assertEquals("one/Lagoon", labels["s1\u0000a"]!!.name)
    assertEquals("two/Lagoon", labels["s1\u0000b"]!!.name)
    assertEquals("Lagoon", labels["s2\u0000c"]!!.name)
  }

  @Test fun sessionsInTheSameDirectoryKeepOneUnqualifiedName() {
    val labels = sessionWorkspaceLabels(listOf(session("a", "/work/Lagoon"), session("b", "/work/Lagoon")), emptyList())
    assertEquals(setOf("Lagoon"), labels.values.map { it.name }.toSet())
  }

  @Test fun catalogLookupIsPerSessionSource() {
    val sessions = listOf(Session("a", "/work/repo", "A", 1, serverId = "s1"), Session("b", "/work/other", "B", 2, serverId = "s2"))
    val labels = sessionWorkspaceLabels(sessions, emptyList()) { session ->
      if (session.serverId == "s1") listOf(Project("w1", "/work/repo", "Server title", serverId = "s1")) else emptyList()
    }
    assertEquals("Server title", labels["s1\u0000a"]!!.name)
    assertEquals("other", labels["s2\u0000b"]!!.name)
  }
}
