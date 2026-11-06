package com.igng.opencode.lagoon.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级回归：首页顶部不再有横向设备/分组胶囊，设备、分组、项目与工作区统一由左上角范围面板选择。
 * JVM 单测起不了 Compose，这里守住已经删掉的那一行和新的唯一入口。
 */
class HomeScopeRegressionTest {
  private fun uiSource(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  @Test fun homeNoLongerRendersTheHorizontalDeviceChips() {
    val home = uiSource("SessionsHomeScreen.kt")
    assertFalse("首页不得再出现 DeviceChips", home.contains("DeviceChips"))
    assertFalse("首页不得再自己铺 LazyRow 胶囊", home.contains("LazyRow"))
    assertTrue("左上角标题应打开统一范围面板", home.contains("onScope"))
  }

  @Test fun theTitleSheetOwnsDevicesGroupsAndTheProjectOrWorkspaceFilter() {
    val sheets = uiSource("ProjectSheets.kt")
    assertTrue(sheets.contains("fun HomeScopeSheet("))
    for (label in listOf("全部设备", "设备分组", "单台连接", "全部会话", "设备与分组管理")) {
      assertTrue("范围面板缺少「$label」", sheets.contains(label))
    }
    assertTrue("分组仍可从范围面板编辑", sheets.contains("编辑分组"))
    assertTrue("目录浏览器仍然复用", sheets.contains("DirectoryBrowserSheet"))
  }

  @Test fun mainActivityUsesOnlyTheUnifiedScopeSheet() {
    val main = uiSource("MainActivity.kt")
    assertTrue(main.contains("HomeScopeSheet("))
    assertFalse(main.contains("AllServersScopeSheet"))
    assertFalse(main.contains("ProjectScopeSheet"))
  }
}
