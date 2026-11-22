package com.igng.opencode.lagoon.core

/** A task is scoped by its original Host route and Session owner, never the currently visible name. */
internal data class ScheduleMutationTarget(
  val profile: ServerProfile,
  val sessionId: String,
  val scheduleId: String,
  val record: ScheduleRecord
) {
  fun routeMatches(state: LagoonState): Boolean = state.server?.let {
    state.sessionId == sessionId && it.sameRoute(profile)
  } == true

  fun matches(state: LagoonState): Boolean = routeMatches(state) && record.id == scheduleId &&
    state.schedules.any { it.id == scheduleId && it.sessionId == sessionId && it.record == record }
}

internal fun LagoonState.canManageSchedules(): Boolean = connected && !cached && server?.effectiveEnabled == true &&
  server?.protocol == HarnessProtocol.DSH && session != null

internal fun ScheduleMutationTarget.canEdit(state: LagoonState): Boolean = state.canManageSchedules() && matches(state) &&
  state.schedules.any { it.id == scheduleId && it.sessionId == sessionId && it.status == "active" }
