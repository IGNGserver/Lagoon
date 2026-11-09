package com.igng.opencode.lagoon.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级回归：预见式返回只允许**一条**时间轴。
 *
 * 旧实现同时跑两套渲染——手势期的 `backProgress` graphicsLayer 舞台，和松手后 `AnimatedContent`
 * 的 fade 收尾。两者在松手那一帧必须交接：peek 舞台被拆、旧页姿态被清零、目标页作为进入内容
 * 首帧 alpha=0 且被排到最上层，于是先闪回手势前的页面、再瞬间闪到目标页。JVM 单测起不了 Compose，
 * 这里守住"不许把双轨写回来"。
 */
class PredictiveBackRegressionTest {
  private fun uiSource(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  @Test fun theRootStageIsOneSeekableTransition() {
    val main = uiSource("MainActivity.kt")
    assertTrue("根转场必须由 SeekableTransitionState 驱动", main.contains("SeekableTransitionState"))
    assertTrue("手势只能写转场进度", main.contains("seekTo("))
    assertTrue("普通导航也要落到同一条转场上", main.contains("animateTo("))
  }

  @Test fun theReleaseHandoffOfTheOldTwoTrackStageIsGone() {
    val main = uiSource("MainActivity.kt")
    assertFalse("松手后不得再靠 fade 收尾交接两套渲染", main.contains("delayMillis = 360"))
    assertFalse("不得再为手势单独维护一份进度副本", main.contains("Animatable(0f)"))
    assertFalse("不得再有垫在下层的 peek 舞台", main.contains("peekRoute"))
  }

  @Test fun theGesturePoseHasSingleOwner() {
    val transition = uiSource("PageTransition.kt")
    assertTrue("层级由 frontRoute 统一决定", transition.contains("fun frontRoute("))
    assertTrue("取消回弹由进度自己走帧", transition.contains("fun settleFraction("))
  }

  /**
   * 手势消失后转场必须能自己走回栈顶。旧实现的 route effect 只认 `motion`，一旦 `motion` 停在
   * GESTURE 而手势对象已被清掉，之后所有路由变化都会被守卫吞掉：页面冻在离场页、左上角返回无效、
   * 栈却已经回到根，下一次侧滑直接退出应用。
   */
  @Test fun aFinishedGestureCannotLeaveTheStageInGestureMode() {
    val main = uiSource("MainActivity.kt")
    assertTrue("跟手才让路，终态收尾仍由相位 effect 接管", main.contains("if (gesture?.phase == BackPhase.ACTIVE) return@LaunchedEffect"))
    assertTrue("没有活手势时必须把转场收敛回栈顶", main.contains("if (gesture != null) return@LaunchedEffect"))
    assertTrue("收敛后必须交回普通返回模式", main.contains("motion = PageMotion.BACK"))
    assertTrue("栈在手势中改写时手势要作废", uiSource("RootNavigator.kt").contains("BackPhase.SUPERSEDED"))
  }
}
