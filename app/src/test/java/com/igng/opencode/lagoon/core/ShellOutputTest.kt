package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellOutputTest {
  @Test fun shellOutputPagesCarryAbsoluteCursorAndSize() {
    val page = JSONObject("""{"output":"hello\n","cursor":6,"size":10,"truncated":true}""").toShellOutput()
    assertEquals("hello\n", page.output)
    assertEquals(6L, page.cursor)
    assertEquals(10L, page.size)
    assertTrue(page.truncated)
  }

  @Test fun shellSnapshotDistinguishesRunningAndExitedCommands() {
    val running = JSONObject("""{"id":"sh_1","status":"running","command":"make","time":{"started":5}}""").toShellSnapshot("/repo")!!
    assertTrue(running.running)
    assertNull(running.exit)
    val exited = JSONObject("""{"id":"sh_1","status":"exited","command":"make","exit":2,"time":{"started":5,"completed":9}}""").toShellSnapshot("/repo")!!
    assertFalse(exited.running)
    assertEquals(2, exited.exit)
    assertEquals(9L, exited.completed)
  }

  @Test fun aFinishedViewNeedsCaughtUpOutput() {
    val snapshot = ShellSnapshot("sh", "make", "/repo", "exited", 0, 1, 2)
    assertFalse(ShellOutputView("sh", "make", "/repo", snapshot = snapshot, cursor = 0, size = 10, loading = false).finished)
    assertTrue(ShellOutputView("sh", "make", "/repo", snapshot = snapshot, cursor = 10, size = 10, loading = false).finished)
  }
}
