package com.igng.opencode.lagoon.core

/** Keep backend ownership explicit: a DSH/ACP Session must never be inferred from its directory. */
internal data class TerminalTarget(val profile: ServerProfile, val sessionId: String, val directory: String) {
  val scope: TerminalScope get() = when (profile.protocol) {
    HarnessProtocol.DSH, HarnessProtocol.ANTIGRAVITY_ACP, HarnessProtocol.OPENCODE_ACP ->
      TerminalScope.Session(sessionId, directory)
    HarnessProtocol.OPENCODE_NATIVE, HarnessProtocol.CODEX ->
      TerminalScope.Location(directory)
  }

  fun matches(state: LagoonState): Boolean = state.server?.let {
    it.sameRoute(profile) &&
      state.sessionId == sessionId && normalizedDirectory(state.executionDirectory.orEmpty()) == normalizedDirectory(directory)
  } == true
}

internal fun LagoonState.canUseNativeTerminal(): Boolean = connected && !cached && server?.effectiveEnabled == true &&
  capabilities.terminal && session != null && !executionDirectory.isNullOrBlank()
