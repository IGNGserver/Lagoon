package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-level back stack: side-swipe/back returns one level (child chat → parent chat → the page it
 * was opened from → 会话) and only leaves the app from 会话.
 */
class SessionNavigationTest {
  @Test fun homeIsTheOnlyRootAndDefersBackToTheSystem() {
    assertFalse(SessionNavigation().canGoBack)
    assertEquals(NavRoute(RootPage.HOME, null, 0), SessionNavigation().route)
  }
  @Test fun pushedPagesReturnHome() {
    val settings = SessionNavigation().push(RootPage.SETTINGS)
    assertTrue(settings.canGoBack)
    assertEquals(SessionNavigation(), settings.back())
    assertEquals(SessionNavigation(), SessionNavigation().push(RootPage.ARCHIVED).back())
  }
  @Test fun pushingAPageLeavesAnyOpenChat() {
    assertEquals(SessionNavigation(RootPage.SETTINGS), SessionNavigation().open("s1").push(RootPage.SETTINGS))
  }
  @Test fun childBackRestoresItsParentBeforeTheOriginatingPage() {
    val nav = SessionNavigation(RootPage.ARCHIVED).open("parent").open("child", child = true).open("grandchild", child = true)
    assertEquals(listOf("parent", "child"), nav.back().sessions)
    assertEquals(listOf("parent"), nav.back().back().sessions)
    assertEquals(SessionNavigation(RootPage.ARCHIVED), nav.back().back().back())
    assertEquals(SessionNavigation(), nav.back().back().back().back())
    assertFalse(nav.back().back().back().back().canGoBack)
  }
  @Test fun leavingTheOutermostSessionReturnsToItsOriginPage() {
    val chat = SessionNavigation(RootPage.ARCHIVED).open("session")
    assertTrue(leavesSession(chat, chat.back()))
    assertFalse(leavesSession(chat.open("child", child = true), chat))
    assertFalse(leavesSession(SessionNavigation(RootPage.ARCHIVED), SessionNavigation()))
  }
  @Test fun unrelatedSessionDoesNotInheritAnotherSessionsBackChain() {
    val nav = SessionNavigation().open("parent").open("child", true).open("other")
    assertEquals(listOf("other"), nav.sessions)
    assertTrue(nav.back().sessions.isEmpty())
  }
  @Test fun reopeningAnAncestorDoesNotDuplicateOrCycleTheStack() {
    val nav = SessionNavigation().open("parent").open("child", true).open("parent", true)
    assertEquals(listOf("parent"), nav.sessions)
  }
  @Test fun routeDepthCountsPagesAndChats() {
    assertEquals(1, SessionNavigation().open("s1").route.depth)
    assertEquals(1, SessionNavigation(RootPage.SETTINGS).route.depth)
    assertEquals(3, SessionNavigation(RootPage.ARCHIVED).open("p").open("c", true).route.depth)
  }
}
