package com.igng.opencode.lagoon.core

/** Current session goal; the Host owns the durable state. */
data class RemoteGoal(
  val id: String,
  val revision: Int,
  val objective: String,
  val phase: String,
  val maxRounds: Int,
  val roundsStarted: Int,
  val activation: String?,
  val blockedReason: String?
) {
  val active: Boolean get() = phase == "active"
  val paused: Boolean get() = phase == "paused"
  val complete: Boolean get() = phase == "complete"
  val blocked: Boolean get() = phase == "blocked"
  val label: String get() = when (phase) {
    "active" -> if (activation == "armed") "目标进行中" else "目标已暂停续跑"
    "paused" -> "目标已暂停"
    "blocked" -> "目标被阻塞"
    "complete" -> "目标已完成"
    else -> "目标"
  }
}
