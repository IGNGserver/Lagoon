package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    assertEquals(SessionNavigation(), SessionNavigation().push(RootPage.DEVICES).back())
  }

  @Test fun pushingAPageLeavesAnyOpenChat() {
    assertEquals(SessionNavigation(RootPage.SETTINGS), SessionNavigation().open("s1").push(RootPage.SETTINGS))
  }

  @Test fun childBackRestoresItsParentBeforeTheOriginatingPage() {
    val nav = SessionNavigation(RootPage.DEVICES).open("parent").open("child", child = true).open("grandchild", child = true)
    assertEquals(listOf("parent", "child"), nav.back().sessions)
    assertEquals(listOf("parent"), nav.back().back().sessions)
    assertEquals(SessionNavigation(RootPage.DEVICES), nav.back().back().back())
    assertEquals(SessionNavigation(), nav.back().back().back().back())
    assertFalse(nav.back().back().back().back().canGoBack)
  }

  @Test fun leavingTheOutermostSessionReturnsToItsOriginPage() {
    val chat = SessionNavigation(RootPage.DEVICES).open("session")
    assertTrue(leavesSession(chat, chat.back()))
    assertFalse(leavesSession(chat.open("child", child = true), chat))
    assertFalse(leavesSession(SessionNavigation(RootPage.DEVICES), SessionNavigation()))
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

  @Test fun authoritativeLineageKeepsSiblingsAtTheSameDepth() {
    val nav = SessionNavigation().openLineage(listOf("root", "child", "grandchild"))
    assertEquals(listOf("root", "child", "grandchild"), nav.sessions)
    assertEquals(listOf("root", "sibling"), nav.openLineage(listOf("root", "sibling")).sessions)
  }

  @Test fun routeDepthCountsPagesAndChats() {
    assertEquals(1, SessionNavigation().open("s1").route.depth)
    assertEquals(1, SessionNavigation(RootPage.SETTINGS).route.depth)
    assertEquals(3, SessionNavigation(RootPage.DEVICES).open("p").open("c", true).route.depth)
  }

  @Test fun deviceDetailIsOneLevelAboveTheDeviceList() {
    val list = SessionNavigation(RootPage.DEVICES)
    val detail = list.openDevice("ssh-1")
    assertTrue(detail.canGoBack)
    assertEquals("ssh-1", detail.route.device)
    assertTrue("详情比列表深一层，过渡方向才能判对", detail.route.depth > list.route.depth)
    assertEquals(list, detail.back())
    assertEquals(SessionNavigation(), list.back())
  }

  @Test fun editorIsPushedOverDetailAndPopsOneLevelAtATime() {
    val detail = SessionNavigation(RootPage.DEVICES).openDevice("ssh-1")
    val editDevice = detail.openEditor(DeviceEditorRoute(deviceId = "ssh-1"))
    val newDevice = editDevice.openEditor(DeviceEditorRoute())
    assertEquals(2, newDevice.editors.size)
    assertTrue(newDevice.route.editor!!.isNew)
    assertTrue(newDevice.route.depth > editDevice.route.depth)

    assertEquals(editDevice, newDevice.back())
    assertEquals(detail, editDevice.back())
    assertEquals(SessionNavigation(RootPage.DEVICES), detail.back())
  }

  @Test fun harnessDetailPopsBackToDeviceDetail() {
    val detail = SessionNavigation(RootPage.DEVICES).openDevice("ssh-1")
    val harness = detail.openHarness("leaf-codex")
    assertEquals("leaf-codex", harness.route.harness)
    assertTrue(harness.route.depth > detail.route.depth)
    assertEquals(detail, harness.back())
  }

  @Test fun editorOpenedWithoutADeviceReturnsToThePageItCameFrom() {
    val fromHome = SessionNavigation().openEditor(DeviceEditorRoute())
    assertEquals(RootPage.HOME, fromHome.route.page)
    assertEquals(SessionNavigation(), fromHome.back())
    val fromSettings = SessionNavigation(RootPage.SETTINGS).openEditor(DeviceEditorRoute())
    assertEquals(SessionNavigation(RootPage.SETTINGS), fromSettings.back())
  }

  @Test fun openingADeviceDropsAnEditorLeftOpenAndAViceVersa() {
    val stale = SessionNavigation(RootPage.DEVICES).openEditor(DeviceEditorRoute()).openDevice("ssh-1")
    assertTrue("进入设备详情不应该把编辑器叠在下面", stale.editors.isEmpty())
    val chat = SessionNavigation(RootPage.DEVICES).openDevice("ssh-1").open("s1")
    assertNull("打开会话后设备层不再存在于栈上", chat.device)
    assertEquals(SessionNavigation(RootPage.DEVICES).open("s1"), chat)
  }

  @Test fun editorLevelsSurviveARotationThroughTheirEncodedForm() {
    val editors = listOf(
      DeviceEditorRoute(),
      DeviceEditorRoute(deviceId = "ssh-host-1")
    )
    assertEquals(editors, decodeDeviceEditors(encodeDeviceEditors(editors)))
    assertTrue("坏行不能把整页重建弄崩", decodeDeviceEditors(listOf("nonsense")).isEmpty())
  }

  @Test fun everyEditorOpeningGetsItsOwnSaveableScope() {
    val base = DeviceEditorRoute()
    val first = base.freshInstance()
    val second = base.freshInstance()
    assertTrue(first.instance.isNotBlank())
    assertNotEquals("两次新建不能共用 saveable 作用域", first.key, second.key)
    assertEquals("已盖章的路由不应该再换实例", first, first.freshInstance())
    assertEquals(first, decodeDeviceEditors(encodeDeviceEditors(listOf(first))).single())
  }

  @Test fun editorLinesFromOlderBuildsStillDecodeGracefully() {
    val legacy = decodeDeviceEditors(listOf("SSH_HOST|SSH||ssh:host-1")).single()
    assertEquals("host-1", legacy.deviceId)
    assertTrue(legacy.instance.isEmpty())
    val legacyDirect = decodeDeviceEditors(listOf("DIRECT_PROFILE|HUB|p1|"))
    assertTrue("已下线的直连编辑器路由应安全丢弃", legacyDirect.isEmpty())
  }
}
