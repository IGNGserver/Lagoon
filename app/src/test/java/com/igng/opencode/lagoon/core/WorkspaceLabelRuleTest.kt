package com.igng.opencode.lagoon.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 行内标签只在列表本身没有说明工作区时出现，避免和范围标题/分组标题重复。 */
class WorkspaceLabelRuleTest {
  @Test fun aScopedListNeverRepeatsItsOwnWorkspace() {
    assertFalse(showWorkspaceLabel("date:today", scopeProjectId = "p1", scopeWorkspaceKey = null, projectKey = "p1"))
    assertFalse(showWorkspaceLabel("pinned", scopeProjectId = null, scopeWorkspaceKey = "group:lagoon", projectKey = null))
  }

  @Test fun aProjectSectionHidesOnlyItsOwnProject() {
    assertFalse(showWorkspaceLabel("project:a", null, null, "a"))
    assertTrue(showWorkspaceLabel("project:a", null, null, "b"))
    // 未归入项目 section 不代表会话目录，所以兜底标签继续显示。
    assertTrue(showWorkspaceLabel("project:unassigned", null, null, null))
  }

  @Test fun workspaceSectionsAndPlainSections() {
    assertFalse(showWorkspaceLabel("workspace:group:lagoon", null, null, null))
    assertTrue(showWorkspaceLabel("date:today", null, null, null))
    assertTrue(showWorkspaceLabel("status:1", null, null, "a"))
    assertTrue(showWorkspaceLabel("pinned", null, null, "a"))
  }
}
