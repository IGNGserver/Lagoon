package com.igng.opencode.lagoon.core

/**
 * Mirrors the official per-server notification ledger (`packages/app/src/context/notification.tsx`):
 * completion/failure results of root sessions, 500 entries, 30 days. An entry is created only when this
 * device witnesses a run end; opening the session marks all of its entries viewed.
 */
data class SessionNotice(val id: String, val sessionId: String, val time: Long, val error: Boolean, val viewed: Boolean = false,
  /** Source server when notices are projected into an all-servers view. */
  val serverId: String? = null)

/** Live-event source, as in the official client: a root session's `session.execution.succeeded` / `failed`. */
internal fun ServerEvent.resultNotice(session: Session, now: Long = System.currentTimeMillis()): SessionNotice? {
  if (session.parentId != null) return null
  val failure = type == "session.execution.failed"
  if (!failure && type != "session.execution.succeeded") return null
  // Local clock, like the official `Date.now()`: run bookkeeping (`since`) is local too.
  return SessionNotice(id.ifBlank { "$type:${session.id}:$now" }, session.id, now, failure)
}

/**
 * Mobile compensation for the volatile event stream: this device saw the root's family running (since
 * [runSince]) and an authoritative read now shows it idle. V2's `outcome` tells success from failure;
 * an interrupted run produces nothing, like the official V2 client.
 */
internal fun Session.transitionNotice(runSince: Long, now: Long = System.currentTimeMillis()): SessionNotice? {
  if (parentId != null || outcome == "interrupted") return null
  return SessionNotice("run:$id:$runSince", id, now, outcome == "failed")
}

/** True when a notice for the same run (any source) is already in the ledger. */
internal fun List<SessionNotice>.coversRun(session: String, runSince: Long): Boolean =
  any { it.sessionId == session && it.time >= runSince }

internal fun List<SessionNotice>.unseenFor(session: String): List<SessionNotice> = filter { it.sessionId == session && !it.viewed }

internal fun List<SessionNotice>.pruned(now: Long): List<SessionNotice> =
  filter { it.time >= now - 30L * 24 * 60 * 60 * 1000 }.sortedBy { it.time }.takeLast(500)

/** Marks the given entries of one session viewed. */
internal fun List<SessionNotice>.viewObserved(session: String, ids: Set<String>): List<SessionNotice> =
  map { if (it.sessionId == session && it.id in ids) it.copy(viewed = true) else it }

/** Marks every entry viewed; the home action applies this to a whole server ledger. */
internal fun List<SessionNotice>.viewAllObserved(): List<SessionNotice> =
  map { if (it.viewed) it else it.copy(viewed = true) }
