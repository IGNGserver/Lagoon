package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** Largest exact integer accepted by the official JavaScript goal service. */
const val MAX_GOAL_ROUNDS = 9007199254740991L

/** Current session goal; the Host owns the durable state. */
data class RemoteGoal(
  val id: String,
  val revision: Int,
  val objective: String,
  val phase: String,
  /** Process-local continuation eligibility; null when only the durable projection was read. */
  val activation: String? = null,
  val blockedReason: String? = null,
  /** Null means the Host did not provide a cap/counter, not a zero-round budget. */
  val maxGoalRounds: Long? = null,
  val roundsStarted: Long? = null
) {
  val active: Boolean get() = phase == "active"
  val paused: Boolean get() = phase == "paused"
  val complete: Boolean get() = phase == "complete"
  val blocked: Boolean get() = phase == "blocked"
  /** An unknown activation is not a paused continuation: only an explicit `disarmed` says so. */
  val label: String get() = when (phase) {
    "active" -> if (activation == "disarmed") "目标已暂停续跑" else "目标进行中"
    "paused" -> "目标已暂停"
    "blocked" -> "目标被阻塞"
    "complete" -> "目标已完成"
    else -> "目标"
  }
}

/**
 * The `goal` session projection (`session/projections` and `session/list` rows):
 * `{goal:{id,revision,objective,phase,blockedReason?}, roundsStarted, createdAt, updatedAt}`.
 *
 * It is absent (`null` value) before the first create and after a clear tombstone, and it never
 * carries the process-local activation — [HarnessClient.goal] supplies that while the Agent is live.
 */
internal fun JSONObject.goalProjection(key: String = "goal"): RemoteGoal? {
  if (!has(key)) return null
  val value = opt(key)
  if (value == null || value == JSONObject.NULL) return null
  val entry = obj(key)
  val current = entry.obj("goal")
  val id = current.str("id")
  if (id.isBlank()) return null
  return RemoteGoal(
    id = id,
    revision = current.goalRoundNumber("revision", 1)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
    objective = current.str("objective"),
    phase = current.str("phase"),
    blockedReason = current.obj("blockedReason").str("message").takeIf { it.isNotBlank() },
    maxGoalRounds = current.goalRoundNumber("maxGoalRounds", 1),
    roundsStarted = entry.goalRoundNumber("roundsStarted", 0)
  )
}

/** Strict numeric parsing keeps omitted counters unknown and never truncates fractional values. */
internal fun JSONObject.goalRoundNumber(key: String, minimum: Long): Long? {
  val number = opt(key) as? Number ?: return null
  val value = number.toDouble()
  if (!value.isFinite() || value < minimum || value > MAX_GOAL_ROUNDS.toDouble() || value % 1.0 != 0.0) return null
  return number.toLong().takeIf { it in minimum..MAX_GOAL_ROUNDS }
}

/** Official non-clear mutation/get response: a flat, full GoalView (not a create ref ack). */
internal fun JSONObject.goalView(): RemoteGoal? {
  val id = str("id").takeIf { it.isNotBlank() } ?: return null
  val revision = goalRoundNumber("revision", 1)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
  if (!has("objective") || !has("phase")) return null
  return RemoteGoal(
    id = id,
    revision = revision,
    objective = str("objective"),
    phase = str("phase"),
    activation = str("activation").takeIf { it == "armed" || it == "disarmed" },
    blockedReason = obj("blockedReason").str("message").takeIf { it.isNotBlank() },
    maxGoalRounds = goalRoundNumber("maxGoalRounds", 1),
    roundsStarted = goalRoundNumber("roundsStarted", 0)
  )
}
