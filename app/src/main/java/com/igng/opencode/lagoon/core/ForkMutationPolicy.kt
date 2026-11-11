package com.igng.opencode.lagoon.core

/** Captures the exact native boundary and source route selected by the reader. */
data class ForkMutationTarget(
  val profile: ServerProfile,
  val sessionId: String,
  val messageId: String? = null,
  val boundary: ForkBoundary? = null
) {
  fun sameSource(state: LagoonState): Boolean = state.server?.let {
    state.sessionId == sessionId && it.id == profile.id && ConnectionRouteAdmission.sameLeafRoute(profile, it)
  } == true
  fun matches(state: LagoonState): Boolean = sameSource(state) && when {
    messageId == null -> boundary == null
    else -> state.forkBoundaryFor(messageId) == boundary && boundary != null
  }
}

private fun LagoonState.forkBoundaryFor(messageId: String): ForkBoundary? {
  val message = messages.singleOrNull { it.id == messageId && it.isHumanInput && !it.queued } ?: return null
  if (message.id.isBlank() || message.id.startsWith(PENDING_PREFIX) || message.id.any(Char::isISOControl)) return null
  return when (server?.kind) {
    ServerKind.DSH, ServerKind.DSH_REMOTE -> message.sequence?.takeIf { it in 1..9_007_199_254_740_991L }?.let { ForkBoundary.DshEvent(it) }
    ServerKind.OPENCODE -> message.id.takeIf { it.length <= 512 }?.let { ForkBoundary.OpenCodeBefore(it) }
    else -> null
  }
}

internal fun LagoonState.canForkSession(): Boolean = connected && !cached && !degraded && server?.effectiveEnabled == true &&
  session != null && tasks[sessionId]?.active != true && tasks[sessionId]?.uncertain != true && capabilities.fork && !pending("fork") && !pending("send") &&
  uncertainForks.none { it.sameSource(this) }

internal fun LagoonState.forkTarget(messageId: String): ForkMutationTarget? {
  if (!canForkSession() || !capabilities.historicalFork) return null
  val boundary = forkBoundaryFor(messageId) ?: return null
  return server?.let { profile -> sessionId?.let { ForkMutationTarget(profile, it, messageId, boundary) } }
}
internal fun LagoonState.canForkAt(messageId: String): Boolean = forkTarget(messageId) != null
internal fun LagoonState.canConfirmFork(target: ForkMutationTarget): Boolean = canForkSession() && target.matches(this) &&
  (target.boundary == null || capabilities.historicalFork)

/** Reconnecting or moving to an aggregate view is not acknowledgement of a delivered mutation. */
internal fun LagoonState.withForkUncertaintyFrom(previous: LagoonState): LagoonState = copy(uncertainForks = previous.uncertainForks)

/** A read receipt is issued only for the exact original Host connection, never a retry request. */
internal class ForkInspection internal constructor(
  val target: ForkMutationTarget,
  val page: ApiPage<Session>,
  internal val client: HarnessClient,
  internal val route: ConnectionRouteSnapshot?,
  internal val generation: Int
)
