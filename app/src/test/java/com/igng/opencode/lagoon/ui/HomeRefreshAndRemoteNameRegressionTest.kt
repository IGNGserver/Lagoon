package com.igng.opencode.lagoon.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 单测起不了 Compose 组合与手势，沿用源码扫描守住两条用户可感知的回归线：
 * 1. 刷新期间列表必须还能滚动，且刷新开始/结束都回到顶部；
 * 2. Remote Host 的显示名必须走本地 alias/可读名称，不再把 Host 自报的 `???` 直接展示出来。
 *
 * 手势和名称合并的具体算法在 RemoteCatalogTest、RemoteAccountManagerTest 中做数据级验证。
 */
class HomeRefreshAndRemoteNameRegressionTest {
  private fun source(path: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/$path"),
    File("src/main/java/com/igng/opencode/lagoon/$path")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到源文件：$path")

  private fun shared(path: String): String = listOf(
    File("shared/src/main/kotlin/com/igng/opencode/lagoon/$path"),
    File("../shared/src/main/kotlin/com/igng/opencode/lagoon/$path")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到共享源文件：$path")

  @Test fun homeRefreshUsesTheNonBlockingContainerAndReturnsToTop() {
    val text = source("ui/SessionsHomeScreen.kt")
    assertTrue("首页改用自定义下拉刷新", text.contains("LagoonPullToRefresh("))
    assertFalse("不能再用刷新期间消费全部滚动的 MIUIX PullToRefresh",
      Regex("(?<!Lagoon)PullToRefresh\\(").containsMatchIn(text))
    assertEquals("刷新开始和结束各回顶一次", 2, Regex("list\\.scrollToItem\\(0\\)").findAll(text).count())
    assertTrue("下拉和重试共用同一个刷新入口", text.contains("onRefresh = ::refreshFromTop"))
    assertTrue("同步提示的重试也走刷新入口", text.contains("onAction = { refreshFromTop() }"))
  }

  @Test fun refreshContainerNeverConsumesScrollWhileRefreshing() {
    val text = source("ui/LagoonPullToRefresh.kt")
    assertTrue("刷新中必须直接放行滚动", text.contains("if (latestRefreshing) return Offset.Zero"))
    assertTrue("只在列表已经在顶部时接管下拉", text.contains("!listState.canScrollBackward"))
    assertTrue("刷新指示器不占列表布局", text.contains("translationY = pullOffset"))
    assertFalse("不得再引入 MIUIX PullToRefresh", text.contains("top.yukonga.miuix.kmp.basic.PullToRefresh"))
  }

  @Test fun remoteHostDisplayUsesLocalAliasAndReadableFallback() {
    val catalog = shared("core/RemoteCatalog.kt")
    assertTrue("Host 显示名优先使用本地 alias", catalog.contains("val alias: String?"))
    assertTrue("全问号自报名回退为未命名主机", catalog.contains("?: \"未命名主机\""))
    assertTrue("坏上报不覆盖旧的可读名称", catalog.contains("withPreviousDisplay"))

    val projection = shared("core/ConnectionCatalogProjection.kt")
    assertTrue("连接图投影使用 displayName", projection.contains("observed.displayName"))

    val sheet = source("ui/RemoteHostsSheet.kt")
    assertTrue("主机行展示 displayName", sheet.contains("Text(host.displayName"))
    assertTrue("提供本地命名入口", sheet.contains("controller.renameRemoteHost("))
    assertTrue("提示 Host 名称不可用", sheet.contains("host.nameUnavailable"))
  }
}
