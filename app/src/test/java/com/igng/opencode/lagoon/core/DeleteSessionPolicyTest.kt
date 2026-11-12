package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class DeleteSessionPolicyTest {
  private val profile = ServerProfile("host", "Host", "ssh://alice@host.example:22", username = "alice", kind = ServerKind.CODEX, sshHostId = "ssh-host")
  private fun state() = LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
    sessionId = "parent", sessions = listOf(Session("parent", "/repo", "Parent", 1)),
    capabilities = ApiCapabilities(deleteSession = true))

  @Test fun confirmationRemainsBoundToOriginalRouteAndSession() {
    val target = DeleteSessionTarget(profile, "parent", "Parent", requireSelected = true)
    val state = state()
    assertTrue(target.matches(state))
    assertFalse(target.matches(state.copy(sessionId = "other")))
    assertFalse(target.matches(state.copy(sessions = emptyList())))
    assertFalse(target.matches(state.copy(connected = false)))
    assertFalse(target.matches(state.copy(capabilities = ApiCapabilities(deleteSession = false))))
    assertFalse(target.matches(state.copy(profiles = listOf(profile.copy(url = "ssh://alice@replacement.example:22")))))
    assertTrue(target.matches(state.copy(profiles = listOf(profile.copy(name = "Renamed")))))
  }

  @Test fun codexConfirmationDescribesCascadingAndForkBlock() {
    val warning = DeleteSessionTarget(profile, "parent", "Parent").warning()
    assertTrue(warning.contains("子代理"))
    assertTrue(warning.contains("永久删除"))
    assertTrue(warning.contains("分叉可能阻止"))
    assertFalse(DeleteSessionTarget(profile.copy(kind = ServerKind.OPENCODE), "parent", "Parent").warning().contains("子代理"))
  }
}
