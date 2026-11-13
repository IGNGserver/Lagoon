package com.igng.opencode.lagoon.core

/** Keep backend ownership explicit: a DSH Session must never be inferred from its directory. */
internal data class TerminalTarget(val profile: ServerProfile, val sessionId: String, val directory: String) {
  val scope: TerminalScope get() = if (profile.protocol == HarnessProtocolId.ANTIGRAVITY_ACP) TerminalScope.Session(sessionId, directory) else when (profile.kind) {
    ServerKind.DSH, ServerKind.DSH_REMOTE -> TerminalScope.Session(sessionId, directory)
    ServerKind.OPENCODE, ServerKind.CODEX -> TerminalScope.Location(directory)
  }

  fun matches(state: LagoonState): Boolean = state.server?.let {
    it.id == profile.id && ConnectionRouteAdmission.sameLeafRoute(profile, it) &&
      state.sessionId == sessionId && normalizedDirectory(state.executionDirectory.orEmpty()) == normalizedDirectory(directory)
  } == true
}

internal fun LagoonState.canUseNativeTerminal(): Boolean = connected && !cached && server?.effectiveEnabled == true &&
  capabilities.terminal && session != null && !executionDirectory.isNullOrBlank()
