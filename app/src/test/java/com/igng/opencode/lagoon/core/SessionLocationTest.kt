package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Session.location.directory` is the working directory; `subpath` is that same directory expressed
 * relative to its project root (`path.relative(project.directory, location.directory)`), so it is a
 * grouping label and must never be appended to the path.
 */
class SessionLocationTest {
  private fun session(location: String, subpath: String? = null) = JSONObject().apply {
    put("id", "ses_1")
    put("title", "T")
    put("projectID", "prj_1")
    put("location", JSONObject().put("directory", location))
    put("time", JSONObject().put("created", 1).put("updated", 2))
    if (subpath != null) put("subpath", subpath)
  }.toSession()

  @Test fun aWorktreeLocationKeepsItsOwnPath() {
    // Project /repo, worktree at /repo/worktrees/feat → subpath "worktrees/feat".
    val session = session("/repo/worktrees/feat", "worktrees/feat")
    assertEquals("/repo/worktrees/feat", session.directory)
  }

  @Test fun aSessionInTheProjectRootHasNoSubpathToAppend() {
    assertEquals("/repo", session("/repo", "").directory)
    assertEquals("/repo", session("/repo").directory)
  }

  @Test fun aMoveIntoTheProjectRootStillUsesTheLocation() {
    assertEquals("/repo", session("/repo", "repo").directory)
  }

  @Test fun timeArchivedIsWhatMarksASessionArchived() {
    assertFalse(session("/repo").archived)
    val archived = JSONObject().apply {
      put("id", "ses_1"); put("title", "T"); put("projectID", "prj_1")
      put("location", JSONObject().put("directory", "/repo"))
      put("time", JSONObject().put("created", 1).put("updated", 2).put("archived", 1700000000000L))
    }.toSession()
    assertTrue(archived.archived)
  }
}
