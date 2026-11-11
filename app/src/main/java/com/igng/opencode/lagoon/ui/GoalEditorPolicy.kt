package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.GoalMutationTarget
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.canManageGoal

internal const val MAX_GOAL_ROUND_CAP = 9007199254740991L

/** Blank means keep the Host's current/default cap, not unlimited and not zero. */
internal fun parseGoalRoundCap(text: String): Result<Long?> {
  val value = text.trim()
  if (value.isEmpty()) return Result.success(null)
  if (!value.all { it in '0'..'9' }) return Result.failure(IllegalArgumentException("轮次上限须为正整数"))
  val cap = value.toLongOrNull()
  return if (cap != null && cap in 1..MAX_GOAL_ROUND_CAP) Result.success(cap)
  else Result.failure(IllegalArgumentException("轮次上限须在 1–$MAX_GOAL_ROUND_CAP 之间"))
}

internal fun LagoonState.goalControlsAvailable(): Boolean = canManageGoal() && !pending("goal") && !pending("goal-refresh")

/** A changed route, goal, or revision never silently rebases an open editor's CAS target. */
internal fun goalEditorCanSave(state: LagoonState, expected: GoalMutationTarget?, objective: String, capText: String): Boolean =
  state.goalControlsAvailable() && expected?.matches(state) == true && objective.isNotBlank() && parseGoalRoundCap(capText).isSuccess
