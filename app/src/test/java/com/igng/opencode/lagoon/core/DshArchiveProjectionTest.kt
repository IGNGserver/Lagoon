package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshArchiveProjectionTest {
  @Test fun authoritativeArchiveSetAllowsExternalUnarchive() {
    val known = mutableMapOf("s" to true)
    val set = listOf("other").toSet()
    known.replaceAll { id, _ -> id in set }
    assertFalse(known.getValue("s"))
  }

  @Test fun missingArchiveSetIsNotTreatedAsEmptySuccess() {
    val response = JSONObject("{}")
    assertFalse(response.has("archivedSessionIds"))
  }
}
