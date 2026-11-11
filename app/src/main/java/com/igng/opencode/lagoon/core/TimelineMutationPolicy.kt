package com.igng.opencode.lagoon.core

/** A dialog remains scoped to the exact saved route and conversation the reader selected. */
internal data class TimelineMutationTarget(val profile: ServerProfile, val sessionId: String, val revertMessageId: String? = null) {
  fun matches(state: LagoonState): Boolean = state.server?.let {
    state.sessionId == sessionId && state.session?.revertMessageId == revertMessageId &&
      it.id == profile.id && ConnectionRouteAdmission.sameLeafRoute(profile, it)
  } == true
}

/** Only a synchronized, idle native conversation may change its authoritative history boundary. */
internal fun LagoonState.canMutateTimeline(): Boolean = connected && !cached && !degraded && server?.effectiveEnabled == true &&
  session != null && tasks[sessionId]?.active != true && !pending("send") && !pending("revert") && !pending("unrevert")

internal fun LagoonState.canRevertMessage(messageId: String): Boolean = canMutateTimeline() &&
  capabilities.supports(SessionAction.REVERT) && messages.any {
    it.id == messageId && it.isHumanInput && !it.queued && !it.id.startsWith(PENDING_PREFIX) &&
      (session?.revertMessageId?.let { boundary -> it.id < boundary } != false)
  }

internal fun LagoonState.canRestoreTimeline(): Boolean = canMutateTimeline() &&
  capabilities.supports(SessionAction.UNREVERT) && !session?.revertMessageId.isNullOrBlank()
