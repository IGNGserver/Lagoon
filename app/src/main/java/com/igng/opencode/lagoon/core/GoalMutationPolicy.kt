package com.igng.opencode.lagoon.core

/** Goal dialogs capture a route, conversation and exact optimistic concurrency reference. */
internal data class GoalMutationTarget(
  val profile: ServerProfile,
  val sessionId: String,
  val goalId: String,
  val revision: Int
) {
  fun matches(state: LagoonState): Boolean = state.server?.let { current ->
    current.sameRoute(profile) &&
      state.sessionId == sessionId && state.goal?.id == goalId && state.goal.revision == revision
  } == true
}

/** Unknown activation or budget is not permission to rearm automatic continuation. */
internal fun DshGoal.canResumeGoal(): Boolean =
  (paused || blocked || active && activation == "disarmed") &&
    maxGoalRounds?.let { cap -> roundsStarted?.let { it >= 0 && it < cap } } == true

internal fun LagoonState.canManageGoal(): Boolean = connected && !cached && server?.effectiveEnabled == true &&
  server?.protocol == HarnessProtocol.DSH && session != null

/** An older read cannot roll back an acknowledged edit or clear/replace a changed goal. */
internal fun reconcileGoalRead(current: DshGoal?, started: DshGoal?, observed: DshGoal?): DshGoal? {
  if (current?.id == observed?.id && current != null && observed != null) {
    return if (observed.revision >= current.revision) observed else current
  }
  val unchanged = current?.id == started?.id && current?.revision == started?.revision
  return if (unchanged) observed else current
}

/** A mutation result is authoritative only for its original goal, never a subsequently replaced one. */
internal fun reconcileGoalMutation(current: DshGoal?, expected: GoalMutationTarget, result: DshGoal): DshGoal? =
  if (current?.id == expected.goalId && current.revision <= result.revision) result else current
