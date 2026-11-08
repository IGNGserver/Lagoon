package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** Current session goal; the Host owns the durable state. */
data class RemoteGoal(
  val id: String,
  val revision: Int,
  val objective: String,
  val phase: String,
  /** Process-local continuation eligibility; null when only the durable projection was read. */
  val activation: String? = null,
  val blockedReason: String? = null
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
    revision = current.optInt("revision", 0),
    objective = current.str("objective"),
    phase = current.str("phase"),
    blockedReason = current.obj("blockedReason").str("message").takeIf { it.isNotBlank() }
  )
}
