package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
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

  /* ---------------- 设备详情与整页编辑器：每一级都是路由上的一层 ---------------- */

  @Test fun deviceDetailIsOneLevelAboveTheDeviceList() {
    val list = SessionNavigation(RootPage.DEVICES)
    val detail = list.openDevice("dev-1")
    assertTrue(detail.canGoBack)
    assertEquals("dev-1", detail.route.device)
    assertTrue("详情比列表深一层，过渡方向才能判对", detail.route.depth > list.route.depth)
    assertEquals(list, detail.back())
    // 再退一层就是会话首页，下一次返回交给系统。
    assertEquals(SessionNavigation(), list.back())
  }

  @Test fun editorIsPushedOverDetailAndPopsOneLevelAtATime() {
    val detail = SessionNavigation(RootPage.DEVICES).openDevice("dev-1")
    val profileEditor = detail.openEditor(DeviceEditorRoute(mode = DeviceEditorMode.DIRECT_PROFILE, profileId = "dev-1"))
    val sshEditor = profileEditor.openEditor(DeviceEditorRoute(mode = DeviceEditorMode.SSH_HOST, access = AddDeviceAccess.SSH, sshHostId = ""))
    assertEquals(2, sshEditor.editors.size)
    assertEquals("最上层才是屏幕上那一页", "", sshEditor.route.editor?.sshHostId)
    assertTrue(sshEditor.route.depth > profileEditor.route.depth)

    // 从 SSH 表单返回落回 SSH 设备表单，而不是直接跳回设备详情。
    assertEquals(profileEditor, sshEditor.back())
    assertEquals(detail, profileEditor.back())
    assertEquals(SessionNavigation(RootPage.DEVICES), detail.back())
  }

  @Test fun harnessEditorTargetsItsSshDeviceOrDirectProfile() {
    val ssh = ServerProfile(id = "codex-leaf", name = "Codex", url = "ssh://user@host:22",
      kind = ServerKind.CODEX, sshHostId = "ssh-host")
    val direct = ServerProfile(id = "direct-leaf", name = "OpenCode", url = "https://example.invalid")
    val detail = SessionNavigation(RootPage.DEVICES).openDevice("device").openHarness(ssh.id)
    val sshEditor = detail.openEditor(editorForHarness(ssh))
    assertEquals(DeviceEditorMode.SSH_HOST, sshEditor.route.editor?.mode)
    assertEquals("ssh-host", sshEditor.route.editor?.sshHostId)
    assertNull(sshEditor.route.editor?.profileId)
    assertEquals(detail, sshEditor.back())
    val directEditor = editorForHarness(direct)
    assertEquals(DeviceEditorMode.DIRECT_PROFILE, directEditor.mode)
    assertEquals("direct-leaf", directEditor.profileId)
    assertNull(directEditor.sshHostId)
  }

  @Test fun editorOpenedWithoutADeviceReturnsToThePageItCameFrom() {
    val fromHome = SessionNavigation().openEditor(DeviceEditorRoute())
    assertEquals(RootPage.HOME, fromHome.route.page)
    assertEquals(SessionNavigation(), fromHome.back())
    val fromSettings = SessionNavigation(RootPage.SETTINGS).openEditor(DeviceEditorRoute())
    assertEquals(SessionNavigation(RootPage.SETTINGS), fromSettings.back())
  }

  @Test fun openingADeviceDropsAnEditorLeftOpenAndAViceVersa() {
    val stale = SessionNavigation(RootPage.DEVICES).openEditor(DeviceEditorRoute()).openDevice("dev-1")
    assertTrue("进入设备详情不应该把编辑器叠在下面", stale.editors.isEmpty())
    val chat = SessionNavigation(RootPage.DEVICES).openDevice("dev-1").open("s1")
    assertNull("打开会话后设备层不再存在于栈上", chat.device)
    assertEquals(SessionNavigation(RootPage.DEVICES).open("s1"), chat)
  }

  @Test fun editorLevelsSurviveARotationThroughTheirEncodedForm() {
    val editors = listOf(
      DeviceEditorRoute(),
      DeviceEditorRoute(mode = DeviceEditorMode.SSH_HOST, access = AddDeviceAccess.SSH, sshHostId = "")
    )
    assertEquals(editors, decodeDeviceEditors(encodeDeviceEditors(editors)))
    val editing = listOf(DeviceEditorRoute(mode = DeviceEditorMode.SSH_HOST, access = AddDeviceAccess.SSH, sshHostId = "host-1"))
    assertEquals(editing, decodeDeviceEditors(encodeDeviceEditors(editing)))
    assertTrue("坏行不能把整页重建弄崩", decodeDeviceEditors(listOf("nonsense")).isEmpty())
  }

  @Test fun everyEditorOpeningGetsItsOwnSaveableScope() {
    val base = DeviceEditorRoute(mode = DeviceEditorMode.SSH_HOST, access = AddDeviceAccess.SSH)
    val first = base.freshInstance()
    val second = base.freshInstance()
    assertTrue(first.instance.isNotBlank())
    assertNotEquals("两次新建不能共用 saveable 作用域", first.key, second.key)
    assertEquals("已盖章的路由不应该再换实例", first, first.freshInstance())
    assertEquals(first, decodeDeviceEditors(encodeDeviceEditors(listOf(first))).single())
  }

  @Test fun editorLinesFromOlderBuildsWithoutAnInstanceStillDecode() {
    val legacy = decodeDeviceEditors(listOf("SSH_HOST|SSH||ssh:host-1")).single()
    assertEquals("host-1", legacy.sshHostId)
    assertTrue(legacy.instance.isEmpty())
  }
}
