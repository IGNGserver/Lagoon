package com.igng.opencode.lagoon.core

import com.igng.opencode.lagoon.ui.ocEdit
import com.igng.opencode.lagoon.ui.ocValues
import org.junit.Assert.*
import org.junit.Test

class OpenCodeProjectEditorPolicyTest {
  private val original = OpenCodeProjectInfo("project", "/repo", "Old", "git", null, null, "blue", "original setup", 1, 2, 3, null, emptyList())
  @Test fun aDraftRemainsRelativeToTheOpenedSnapshotNotARefreshedServerRecord() {
    val captured = original.copy(sandboxes = original.sandboxes.toList())
    val draft = ocValues(captured).toMutableList().apply { this[1] = "New name" }
    val refreshed = original.copy(startCommand = "another client's setup", updated = 4)
    assertNotEquals(captured, refreshed) // The panel disables submission and requires reopening.
    val patch = ocEdit(captured, draft)
    assertEquals("New name", patch.name)
    assertNull(patch.startCommand); assertNull(patch.canonical); assertNull(patch.iconColor)
  }
  @Test fun clearingOneOptionalFieldDoesNotSendUnchangedSiblingMetadata() {
    val draft = ocValues(original).toMutableList().apply { this[4] = "" }
    val patch = ocEdit(original, draft)
    assertEquals("", patch.startCommand); assertNull(patch.name); assertNull(patch.iconColor)
    assertNull(patch.iconOverride); assertNull(patch.canonical)
    assertEquals(OpenCodeProjectEdit(), ocEdit(original, ocValues(original)))
  }
}
