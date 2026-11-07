package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Deeper pages enter from the right and going back enters from the left, whatever the entry point. */
class SlideDirectionTest {
  private val home = SessionNavigation().route

  @Test fun openingAPageOrChatEntersFromTheRight() {
    assertEquals(1, slideDirection(home, SessionNavigation().push(RootPage.SETTINGS).route))
    assertEquals(1, slideDirection(home, SessionNavigation().open("s1").route))
    assertEquals(1, slideDirection(SessionNavigation().open("s1").route, SessionNavigation().open("s1").open("c1", true).route))
  }

  @Test fun goingBackEntersFromTheLeft() {
    val child = SessionNavigation(RootPage.DEVICES).open("s1").open("c1", true)
    assertEquals(-1, slideDirection(child.route, child.back().route))
    assertEquals(-1, slideDirection(child.back().route, child.back().back().route))
    assertEquals(-1, slideDirection(SessionNavigation().push(RootPage.SETTINGS).route, home))
  }

  @Test fun replacingAChatAtTheSameDepthEntersFromTheRight() {
    assertEquals(1, slideDirection(SessionNavigation().open("a").route, SessionNavigation().open("b").route))
  }

  @Test fun backSwipeFollowsTheEdgeTheFingerCameFrom() {
    // 左边缘滑入 → 内容向右；右边缘滑入 → 内容向左。
    assertEquals(1, backSwipeDirection(0, 0f, 1000))
    assertEquals(-1, backSwipeDirection(1, 1000f, 1000))
    // 无法判定边缘时按触点相对屏幕中点兜底。
    assertEquals(1, backSwipeDirection(-1, 100f, 1000))
    assertEquals(-1, backSwipeDirection(-1, 900f, 1000))
  }
}
