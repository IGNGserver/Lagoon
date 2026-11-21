package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.ConnectStage
import com.igng.opencode.lagoon.core.ConnectionStatus
import com.igng.opencode.lagoon.core.HarnessProtocolId
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.RemoteCatalog
import com.igng.opencode.lagoon.core.RemoteRecoveryKind
import com.igng.opencode.lagoon.core.ServerOverviewSnapshot
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页状态条优先级与刷新语义的数据级回归：
 * - 同一时刻只返回一条提示（不再叠加“传输失败 + 待同步 + 正在刷新”）；
 * - 刷新进行中不再返回“下拉重试/点按重试”那一条；
 * - OpenCode 注册过期给出可直接执行的“启动服务”，而不是让人去跑命令。
 */
class HomeStatusNoticeTest {
  private val dsh = ServerProfile("dsh", "ubuntu-dev", "ssh://192.168.5.23", kind = com.igng.opencode.lagoon.core.ServerKind.DSH)
  private val openCode = ServerProfile(
    "oc", "ubuntu-dev", "ssh://192.168.5.23", kind = com.igng.opencode.lagoon.core.ServerKind.OPENCODE,
    sshHostId = "ssh-1", protocolId = HarnessProtocolId.OPENCODE_NATIVE
  )

  private class FakeHost(
    private val expired: Set<String> = emptySet(),
    private val catalog: RemoteCatalog? = null
  ) : HomeStatusHost {
    override fun hostAuthExpired(profile: ServerProfile, state: LagoonState) = profile.id in expired
    override fun recoveryLabel(profile: ServerProfile) = "重新登录"
    override fun degradedRemoteCatalog(state: LagoonState) = catalog
  }

  private fun snapshot(
    profile: ServerProfile,
    connected: Boolean = true,
    loading: Boolean = false,
    degraded: Boolean = false,
    transportError: String? = null,
    recovery: RemoteRecoveryKind? = null,
    sessions: List<Session> = emptyList()
  ) = ServerOverviewSnapshot(
    serverId = profile.id,
    profile = profile,
    connected = connected,
    loading = loading,
    degraded = degraded,
    transportError = transportError,
    remoteRecovery = recovery,
    sessions = sessions
  )

  @Test fun openCodeServiceDownOffersDirectStartInsteadOfTellingTheUserToRunCommands() {
    val text = "OpenCode 服务没有在运行（注册可能已过期）；请在设备上运行 opencode service start"
    val state = LagoonState(
      profiles = listOf(openCode), serverId = "oc",
      allServerSnapshots = mapOf("oc" to snapshot(openCode, connected = false, transportError = text))
    )
    val notice = homeStatusNotice(state, FakeHost(), now = 0L)!!
    assertEquals("OpenCode 服务未运行（注册可能已过期）", notice.message)
    assertEquals(MiuixToastType.ERROR, notice.type)
    assertEquals("启动服务", notice.actionLabel)
    assertEquals(HomeStatusAction.StartOpenCodeService("oc"), notice.action)
  }

  @Test fun refreshingHidesTheStalePullToRetryLine() {
    val session = Session("s", "/repo", "A", 0, serverId = "dsh")
    val base = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = true, degraded = true,
      sessions = listOf(session),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = true, degraded = true, sessions = listOf(session)))
    )
    // 刷新中：下拉胶囊独占“正在刷新”，不再并存“部分数据待同步 · 下拉重试”。
    assertNull(homeStatusNotice(base.copy(refreshing = true), FakeHost(), now = 0L))
    val idle = homeStatusNotice(base, FakeHost(), now = 0L)!!
    assertEquals("部分数据待同步", idle.message)
    assertEquals(HomeStatusAction.Reload, idle.action)
  }

  @Test fun transportFailureOutranksSyncNoticeSoThePageNeverSaysTwoThingsAtOnce() {
    val text = "SSH 认证失败"
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = false,
      sessions = listOf(Session("s", "/repo", "A", 0, serverId = "dsh")),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = false, degraded = true, transportError = text))
    )
    val notice = homeStatusNotice(state, FakeHost(), now = 0L)!!
    assertEquals(text, notice.message)
    assertEquals(HomeStatusAction.Retry, notice.action)
  }

  @Test fun loginExpiryWinsOverCacheWordingAndKeepsItsRecoveryAction() {
    val session = Session("s", "/repo", "A", 0, serverId = "dsh")
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = false, sessions = listOf(session),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = false, recovery = RemoteRecoveryKind.LOGIN,
        sessions = listOf(session)))
    )
    val notice = homeStatusNotice(state, FakeHost(expired = setOf("dsh")), now = 0L)!!
    assertEquals("ubuntu-dev 登录态已失效，无法同步最新数据", notice.message)
    assertEquals(HomeStatusAction.RemoteRecovery("dsh", RemoteRecoveryKind.LOGIN), notice.action)
    assertEquals("重新登录", notice.actionLabel)
  }

  @Test fun offlineCacheExplainsWhyTheListIsOldAndOffersReconnect() {
    val session = Session("s", "/repo", "A", 0, serverId = "dsh")
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = false, loading = false,
      sessions = listOf(session),
      connection = ConnectionStatus(ConnectStage.IDLE),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = false, sessions = listOf(session)))
    )
    val notice = homeStatusNotice(state, FakeHost(), now = 0L)!!
    assertEquals("当前未连接服务器 · 正在显示本地离线缓存会话", notice.message)
    assertEquals(HomeStatusAction.Retry, notice.action)
  }

  @Test fun aHealthyPageShowsNothing() {
    val session = Session("s", "/repo", "A", 0, serverId = "dsh")
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = true, sessions = listOf(session),
      connection = ConnectionStatus(ConnectStage.READY),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = true, sessions = listOf(session)))
    )
    assertNull(homeStatusNotice(state, FakeHost(), now = 0L))
  }

  @Test fun aggregateTransportFailureOnANonWritableLeafGoesToDeviceManagement() {
    val failing = openCode.copy(id = "oc2", name = "macos-dev")
    val state = LagoonState(
      profiles = listOf(dsh, failing), allServers = true, serverId = "dsh",
      allServerSnapshots = mapOf(
        "dsh" to snapshot(dsh, connected = true),
        "oc2" to snapshot(failing, connected = false, transportError = "SSH 连接断开")
      )
    )
    val notice = homeStatusNotice(state, FakeHost(), now = 0L)!!
    assertTrue(notice.message.startsWith("macos-dev · SSH 连接断开"))
    assertEquals(HomeStatusAction.ManageDevices, notice.action)
    assertEquals("设备管理", notice.actionLabel)
  }
}
