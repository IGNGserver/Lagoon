package com.igng.opencode.lagoon.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 协程取消不是业务错误：`catch (error: Exception)` 必须让 `CancellationException` 继续向上传播，
 * 否则被取代的刷新会把取消消息（StandaloneCoroutine was cancelled）写进用户可见的 error。
 * JVM 单测起不了完整控制器，沿用仓库既有的源码扫描回归（见 ui/PopupScopeRegressionTest）。
 */
class CancellationSafetyTest {
  private val files = listOf(
    "core/LagoonController.kt",
    "core/AllServersOverview.kt",
    "core/PermissionPolicy.kt",
    "core/OfflineCache.kt",
    "core/OpenCodeApi.kt",
    "ui/Screens.kt",
    "ui/MainActivity.kt",
    "system/NotificationActionReceiver.kt",
    "system/BackgroundSyncWorker.kt"
  )

  private fun source(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/$name"),
    File("src/main/java/com/igng/opencode/lagoon/$name"),
    // Platform-neutral sources live in the shared source set compiled into both the app and the hub.
    File("shared/src/main/kotlin/com/igng/opencode/lagoon/$name"),
    File("../shared/src/main/kotlin/com/igng/opencode/lagoon/$name")
  ).firstOrNull { it.isFile }?.readText()
    ?: error("找不到源文件：$name（工作目录 ${File(".").absolutePath}）")

  @Test fun everyBroadExceptionCatchLetsCancellationThrough() {
    for (name in files) {
      val lines = source(name).lines()
      val missing = mutableListOf<Int>()
      lines.forEachIndexed { index, line ->
        if (!line.contains("catch (error: Exception)")) return@forEachIndexed
        // 允许两种既有写法：块内重抛取消，或同一 try 的前一个 catch 已重抛（跨行也算）。
        val region = lines.subList((index - 2).coerceAtLeast(0), (index + 4).coerceAtMost(lines.size - 1)).joinToString("\n")
        if (!region.contains("CancellationException") && !region.contains("throw error")) missing += index + 1
      }
      assertTrue("$name 的 catch (Exception) 吞掉了取消异常：行 $missing", missing.isEmpty())
    }
  }
}
