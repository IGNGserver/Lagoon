package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class TerminalTargetTest {
  private val profile = ServerProfile("native", "OpenCode", "https://native.example/")
  private fun state() = LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
    sessionId = "session", sessions = listOf(Session("session", "/repo", "Task", 1)), capabilities = ApiCapabilities(terminal = true))

  @Test fun targetFencesRouteConversationAndActualLocationNotTerminalCwd() {
    val target = TerminalTarget(profile, "session", "/repo")
    assertTrue(target.matches(state()))
    assertTrue(target.matches(state().copy(profiles = listOf(profile.copy(name = "Renamed")))))
    assertFalse(target.matches(state().copy(profiles = listOf(profile.copy(url = "https://other.example/")))))
    assertFalse(target.matches(state().copy(sessionId = "other")))
    assertFalse(TerminalTarget(profile, "session", "/different").matches(state()))
  }

  @Test fun terminalGateRequiresNegotiatedNativeBusinessCapabilityAndSynchronizedRoute() {
    val live = state()
    assertTrue(live.canUseNativeTerminal())
    assertFalse(live.copy(capabilities = ApiCapabilities()).canUseNativeTerminal())
    assertFalse(live.copy(connected = false).canUseNativeTerminal())
    assertFalse(live.copy(cached = true).canUseNativeTerminal())
    assertFalse(live.copy(profiles = listOf(profile.copy(parentEnabled = false))).canUseNativeTerminal())
    assertFalse(live.copy(sessionId = null).canUseNativeTerminal())
  }
}
