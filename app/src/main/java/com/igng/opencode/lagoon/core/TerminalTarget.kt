package com.igng.opencode.lagoon.core

/** OpenCode simple PTYs belong to a location, not to the conversation's Session id. */
internal data class TerminalTarget(val profile: ServerProfile, val sessionId: String, val directory: String) {
  fun matches(state: LagoonState): Boolean = state.server?.let {
    it.id == profile.id && ConnectionRouteAdmission.sameLeafRoute(profile, it) &&
      state.sessionId == sessionId && normalizedDirectory(state.executionDirectory.orEmpty()) == normalizedDirectory(directory)
  } == true
}

internal fun LagoonState.canUseNativeTerminal(): Boolean = connected && !cached && server?.effectiveEnabled == true &&
  capabilities.terminal && session != null && !executionDirectory.isNullOrBlank()
