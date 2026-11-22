package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.ConnectStage
import com.igng.opencode.lagoon.core.ConnectionStatus
import com.igng.opencode.lagoon.core.HarnessProtocol
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.ServerOverviewSnapshot
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页状态条优先级与纯 SSH 故障恢复语义的数据级回归：
 * - 同一时刻只返回一条提示；
 * - 刷新进行中隐藏待同步提示；
 * - 需要介入的 SSH 故障（确认指纹 / 解锁密钥 / 检查认证）优先给出直接操作按钮；
 * - OpenCode 注册过期给出可直接执行的“启动服务”。
 */
class HomeStatusNoticeTest {
  private val dsh = ServerProfile(
    "dsh", "ubuntu-dev", "ssh://dev@192.168.5.23:22",
    protocol = HarnessProtocol.DSH,
    deviceId = "ssh-1",
    deviceName = "ubuntu-dev"
  )
  private val openCode = ServerProfile(
    "oc", "ubuntu-dev", "ssh://dev@192.168.5.23:22",
    protocol = HarnessProtocol.OPENCODE_NATIVE,
    deviceId = "ssh-1",
    deviceName = "ubuntu-dev"
  )

  private fun snapshot(
    profile: ServerProfile,
    connected: Boolean = true,
    loading: Boolean = false,
    degraded: Boolean = false,
    transportError: String? = null,
    sshActionRequired: SshFailure? = null,
    sessions: List<Session> = emptyList()
  ) = ServerOverviewSnapshot(
    serverId = profile.id,
    profile = profile,
    connected = connected,
    loading = loading,
    degraded = degraded,
    transportError = transportError,
    sshActionRequired = sshActionRequired,
    sessions = sessions
  )

  @Test fun openCodeServiceDownOffersDirectStartInsteadOfTellingTheUserToRunCommands() {
    val text = "OpenCode 服务没有在运行（注册可能已过期）；请在设备上运行 opencode service start"
    val state = LagoonState(
      profiles = listOf(openCode), serverId = "oc",
      allServerSnapshots = mapOf("oc" to snapshot(openCode, connected = false, transportError = text))
    )
    val notice = homeStatusNotice(state, now = 0L)!!
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
    assertNull(homeStatusNotice(base.copy(refreshing = true), now = 0L))
    val idle = homeStatusNotice(base, now = 0L)!!
    assertEquals("部分数据待同步", idle.message)
    assertEquals(HomeStatusAction.Reload, idle.action)
  }

  @Test fun transportFailureOutranksSyncNoticeSoThePageNeverSaysTwoThingsAtOnce() {
    val text = "SSH 通道已断开"
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = false,
      sessions = listOf(Session("s", "/repo", "A", 0, serverId = "dsh")),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = false, degraded = true, transportError = text))
    )
    val notice = homeStatusNotice(state, now = 0L)!!
    assertEquals(text, notice.message)
    assertEquals(HomeStatusAction.Retry, notice.action)
  }

  @Test fun sshHostKeyChangeWinsOverCacheAndOffersTrustAction() {
    val session = Session("s", "/repo", "A", 0, serverId = "dsh")
    val key = PresentedSshHostKey("ssh-ed25519", "SHA256:abc123")
    val failure = SshFailure(SshFailureKind.HOST_KEY_CHANGED, "主机指纹变了", presentedKey = key)
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = false, sessions = listOf(session),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = false, sshActionRequired = failure, sessions = listOf(session)))
    )
    val notice = homeStatusNotice(state, now = 0L)!!
    assertTrue(notice.message.contains("主机指纹"))
    assertEquals("确认指纹", notice.actionLabel)
    assertEquals(HomeStatusAction.TrustHostKey("ssh-1", failure), notice.action)
  }

  @Test fun sshBiometricLockOffersUnlockKeyAction() {
    val failure = SshFailure(SshFailureKind.BIOMETRIC_REQUIRED, "需要生物识别解锁密钥")
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = false,
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = false, sshActionRequired = failure))
    )
    val notice = homeStatusNotice(state, now = 0L)!!
    assertEquals("解锁密钥", notice.actionLabel)
    assertEquals(HomeStatusAction.UnlockSshKey("ssh-1", failure), notice.action)
  }

  @Test fun offlineCacheExplainsWhyTheListIsOldAndOffersReconnect() {
    val session = Session("s", "/repo", "A", 0, serverId = "dsh")
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = false, loading = false,
      sessions = listOf(session),
      connection = ConnectionStatus(ConnectStage.IDLE),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = false, sessions = listOf(session)))
    )
    val notice = homeStatusNotice(state, now = 0L)!!
    assertEquals("当前未连接设备 · 正在显示本地离线缓存会话", notice.message)
    assertEquals(HomeStatusAction.Retry, notice.action)
  }

  @Test fun aHealthyPageShowsNothing() {
    val session = Session("s", "/repo", "A", 0, serverId = "dsh")
    val state = LagoonState(
      profiles = listOf(dsh), serverId = "dsh", connected = true, sessions = listOf(session),
      connection = ConnectionStatus(ConnectStage.READY),
      allServerSnapshots = mapOf("dsh" to snapshot(dsh, connected = true, sessions = listOf(session)))
    )
    assertNull(homeStatusNotice(state, now = 0L))
  }

  @Test fun aggregateTransportFailureOnANonWritableLeafGoesToDeviceManagement() {
    val failing = openCode.copy(id = "oc2", name = "macos-dev", deviceId = "ssh-2", deviceName = "macos-dev")
    val state = LagoonState(
      profiles = listOf(dsh, failing), allServers = true, serverId = "dsh",
      allServerSnapshots = mapOf(
        "dsh" to snapshot(dsh, connected = true),
        "oc2" to snapshot(failing, connected = false, transportError = "SSH 连接断开")
      )
    )
    val notice = homeStatusNotice(state, now = 0L)!!
    assertTrue(notice.message.startsWith("macos-dev · SSH 连接断开"))
    assertEquals(HomeStatusAction.ManageDevices, notice.action)
    assertEquals("设备管理", notice.actionLabel)
  }
}
