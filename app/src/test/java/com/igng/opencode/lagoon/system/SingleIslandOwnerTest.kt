package com.igng.opencode.lagoon.system

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the server-wide summary may own the Live Update / island slot. Per-session notifications
 * requesting promotion made every running session compete for the status bar chip.
 */
class SingleIslandOwnerTest {
  private fun source(path: String): String = listOf(File("app/$path"), File(path)).firstOrNull { it.isFile }?.readText()
    ?: error("找不到源文件：$path（工作目录 ${File(".").absolutePath}）")

  @Test fun onlyTheSummaryRequestsPromotion() {
    val text = source("src/main/java/com/igng/opencode/lagoon/system/TaskNotifications.kt")
    val summaryStart = text.indexOf("fun buildSummary(")
    val summaryEnd = text.indexOf("private fun summaryDismissed(")
    val summaryBody = text.substring(summaryStart, summaryEnd)
    val outside = text.removeRange(summaryStart, summaryEnd)
    assertEquals(1, Regex("setRequestPromotedOngoing").findAll(summaryBody).count())
    assertFalse(outside.contains("setRequestPromotedOngoing"))
  }

  @Test fun summaryUsesTheStandardProgressRail() {
    val text = source("src/main/java/com/igng/opencode/lagoon/system/TaskNotifications.kt")
    assertTrue(text.contains("NotificationCompat.ProgressStyle"))
    assertTrue(text.contains("setProgressSegments"))
    assertTrue(text.contains("setStyledByProgress(false)"))
    assertFalse(text.contains("setProgressTrackerIcon"))
  }

  @Test fun inAppFakeIslandIsGone() {
    assertFalse(File("app/src/main/java/com/igng/opencode/lagoon/ui/MiuixTaskIsland.kt").exists() || File("src/main/java/com/igng/opencode/lagoon/ui/MiuixTaskIsland.kt").exists())
    assertFalse(source("src/main/java/com/igng/opencode/lagoon/ui/MainActivity.kt").contains("MiuixTaskIsland"))
  }

  @Test fun vendorIslandAdaptersAndExtrasAreGone() {
    val systemDir = listOf(
      File("app/src/main/java/com/igng/opencode/lagoon/system"),
      File("src/main/java/com/igng/opencode/lagoon/system")
    ).first { it.isDirectory }
    assertFalse(File(systemDir, "IslandAdapters.kt").exists())
    val text = systemDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
    for (key in listOf("notification.superx", "miui.focus", "OppoFluidCloud", "islandHonor", "islandOppoFluidCloud")) {
      assertFalse("厂商适配残留：$key", text.contains(key))
    }
  }
}
