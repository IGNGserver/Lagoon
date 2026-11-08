package com.igng.opencode.lagoon.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级回归：首页在转场里也必须拿到聚合投影。
 *
 * 首页不只在根路由上被组合：打开会话时首页作为离场页仍在画，预见式返回时首页作为预览页、
 * 在 `navigator.navigation.sessions` 仍指向会话页时就已经组合。一旦按"当前路由"决定是否
 * `homeProjection()`，这些帧就退回原始会话状态——聚合首页（全部设备 / 设备分组）的
 * `allSessionStatuses` 恒为空，行状态与未读点在预览里集体消失，落地那一帧才补回来。
 * JVM 单测起不了 Compose，这里守住"首页只能由组合点取投影"的结构。
 */
class HomeStatusProjectionRegressionTest {
  private fun uiSource(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  @Test fun theHomePageIsNeverGatedOnTheCurrentRoute() {
    val main = uiSource("MainActivity.kt")
    assertFalse(
      "不得再按当前路由决定首页是否投影",
      main.contains("navigator.navigation.sessions.isEmpty()) state.homeProjection()")
    )
    assertTrue("首页必须由组合点的投影提供", main.contains("HomeScreen(rememberHomePage(state)"))
    assertTrue("首页范围面板同样使用投影", main.contains("state = rememberHomePage(state)"))
  }

  @Test fun theProjectionIsComputedOnlyWhileTheHomePageIsComposed() {
    val main = uiSource("MainActivity.kt")
    assertTrue("投影必须按 state 实例缓存", main.contains("remember(state) { state.homeProjection() }"))
    // 只允许一个投影调用点：多一处就意味着又有别的路径在重算或绕过投影。
    assertEquals(1, Regex("""homeProjection\(\)""").findAll(main).count())
  }
}
