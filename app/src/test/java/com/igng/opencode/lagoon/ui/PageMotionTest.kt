package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 转场模式与层级：预测式返回期间必须是"正在离开的那一页"压在上面，其余转向由目标页压在上面。
 *
 * 这两条以前散落在 `graphicsLayer` 的条件里，松手时层级会跟着渲染路径翻转（peek 在下、目标页在上），
 * 是闪回的一环。现在层级只由 [frontRoute] 决定，可以在这里钉死。
 */
class PageMotionTest {
  private val home = SessionNavigation().route
  private val devices = SessionNavigation(RootPage.DEVICES).route
  private val chat = SessionNavigation().open("s1").route
  private val child = SessionNavigation().open("s1").open("s2", child = true).route

  @Test fun deeperDestinationIsForwardAndShallowerIsBack() {
    assertEquals(PageMotion.FORWARD, pageMotion(home, devices))
    assertEquals(PageMotion.FORWARD, pageMotion(home, chat))
    assertEquals(PageMotion.FORWARD, pageMotion(chat, child))
    assertEquals(PageMotion.BACK, pageMotion(devices, home))
    assertEquals(PageMotion.BACK, pageMotion(chat, home))
    assertEquals(PageMotion.BACK, pageMotion(child, chat))
  }

  @Test fun theGestureKeepsTheLeavingPageOnTop() {
    // 跟手时旧页在上：若让它排在下层，收起过程会被正在展开的底页盖住。
    assertEquals(chat, frontRoute(PageMotion.GESTURE, chat, home))
    assertEquals(child, frontRoute(PageMotion.GESTURE, child, chat))
  }

  @Test fun ordinaryTransitionsKeepTheTargetOnTop() {
    assertEquals(chat, frontRoute(PageMotion.FORWARD, home, chat))
    assertEquals(devices, frontRoute(PageMotion.BACK, chat, devices))
  }
}
