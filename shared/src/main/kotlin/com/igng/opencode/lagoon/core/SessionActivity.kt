package com.igng.opencode.lagoon.core

/**
 * 子会话在 UI 里的状态。它与根会话的 [TaskState] 分开：子会话运行不再把根会话算成“运行中”，
 * 只通过 [SessionActivityRules.backgroundRoots] 在列表上留下一个低调标记，并在会话内提供入口。
 */
enum class SubSessionPhase {
  RUNNING, WAITING_PERMISSION, WAITING_QUESTION, WAITING_SERVER, COMPLETED, FAILED, INTERRUPTED, UNKNOWN;

  val active: Boolean get() = this in ACTIVE

  companion object {
    val ACTIVE: Set<SubSessionPhase> = setOf(RUNNING, WAITING_PERMISSION, WAITING_QUESTION, WAITING_SERVER)
  }
}

/** One descendant session in the family tree, with its live/terminal state and indentation depth. */
data class SubSessionEntry(
  val session: Session,
  val depth: Int,
  val phase: SubSessionPhase,
  val started: Long,
  val ended: Long?
) {
  val active: Boolean get() = phase.active
}

/**
 * Everything that is “background activity” for one session family:
 * - running shell jobs (they are not sessions and never appear in `/api/session/active`);
 * - descendant sessions, including historical ones, for the session/sub-session list.
 */
data class SessionActivity(
  val shells: List<ShellJob> = emptyList(),
  val subSessions: List<SubSessionEntry> = emptyList()
) {
  val activeSubSessions: Int get() = subSessions.count { it.active }
  val hasActive: Boolean get() = shells.isNotEmpty() || activeSubSessions > 0
  val total: Int get() = shells.size + activeSubSessions
}

/**
 * Pure rules that keep “the root is running” separate from “the family still has background work”.
 *
 * Official clients derive background work from shell jobs and child session status; they do not
 * change the root session's execution status. The same distinction is applied here so the island,
 * notifications, home list, and foreground service all agree.
 */
object SessionActivityRules {
  /**
   * Live state rolled up to each root for summary/status purposes:
   * - a root's own active execution is included;
   * - a descendant is included only when it waits on the user (or the server), never merely because
   *   it is running in the background.
   */
  fun rootStates(tasks: Map<String, TaskState>, parents: Map<String, String>): Map<String, TaskState> {
    val result = linkedMapOf<String, TaskState>()
    tasks.values.filter { it.active }.forEach { task ->
      val root = BackgroundWork.rootOf(task.sessionId, parents)
      val own = root == task.sessionId
      if (!own && task.phase !in TaskState.WAITING_PHASES) return@forEach
      val candidate = task.copy(sessionId = root)
      val previous = result[root]
      if (previous == null || priority(candidate) > priority(previous) ||
        (priority(candidate) == priority(previous) && own)) {
        result[root] = candidate
      }
    }
    return result
  }

  /**
   * Roots that have background activity while their own execution is not running. This is a display
   * marker only: it never contributes to [TaskSummary.running] and never keeps the Live Update alive.
   */
  fun backgroundRoots(tasks: Map<String, TaskState>, shells: Map<String, ShellJob>, parents: Map<String, String>): Set<String> {
    fun rootRunning(id: String): Boolean = tasks[id]?.active == true
    val roots = linkedSetOf<String>()
    shells.values.forEach { job ->
      val root = BackgroundWork.rootOf(job.sessionId, parents)
      if (!rootRunning(root)) roots += root
    }
    tasks.values.filter { it.active && !it.uncertain }.forEach { task ->
      val root = BackgroundWork.rootOf(task.sessionId, parents)
      if (root != task.sessionId && !rootRunning(root)) roots += root
    }
    return roots
  }

  /** All descendants of [root] from the sessions already loaded on this device, in tree order. */
  fun subSessions(
    root: String,
    sessions: List<Session>,
    tasks: Map<String, TaskState>,
    parents: Map<String, String>
  ): List<SubSessionEntry> {
    val children = mutableMapOf<String, MutableList<Session>>()
    sessions.forEach { session ->
      val parent = session.parentId ?: return@forEach
      if (parent.isNotBlank() && parent != session.id) children.getOrPut(parent) { mutableListOf() }.add(session)
    }
    val reachable = mutableSetOf<String>()
    fun walk(parent: String, depth: Int): List<SubSessionEntry> {
      val group = children[parent].orEmpty()
        .filter { it.id != root && reachable.add(it.id) }
        .sortedWith(compareByDescending<Session> { tasks[it.id]?.active == true }.thenByDescending { it.activityAt })
      return group.flatMap { session ->
        val entry = entry(session, depth, tasks[session.id])
        listOf(entry) + walk(session.id, depth + 1)
      }
    }
    // A child row can be present without its parent row after catalog paging; start at root anyway.
    val result = walk(root, 1)
    // Include any loaded descendant whose parent chain names root but which the tree walk missed
    // because an intermediate identity was not loaded.
    val byKnownRoot = sessions.filter { it.id != root && it.id !in reachable && BackgroundWork.rootOf(it.id, parents) == root }
      .sortedByDescending { it.activityAt }
      .map { entry(it, BackgroundWork.depth(it.id, parents).coerceAtLeast(1), tasks[it.id]) }
    return (result + byKnownRoot).distinctBy { it.session.id }
  }

  /** Running shells and descendant sessions for one family. */
  fun activity(
    sessionId: String?,
    sessions: List<Session>,
    tasks: Map<String, TaskState>,
    shells: Map<String, ShellJob>,
    parents: Map<String, String>
  ): SessionActivity {
    val id = sessionId ?: return SessionActivity()
    val root = BackgroundWork.rootOf(id, parents)
    val family = BackgroundWork.familyOf(root, parents)
    val jobs = shells.values.filter { it.sessionId in family }.sortedBy { it.started }
    return SessionActivity(jobs, subSessions(root, sessions, tasks, parents))
  }

  private fun entry(session: Session, depth: Int, task: TaskState?): SubSessionEntry {
    val phase = when {
      task?.active == true && task.uncertain -> SubSessionPhase.UNKNOWN
      task?.active == true -> when (task.phase) {
        TaskPhase.WAITING_PERMISSION -> SubSessionPhase.WAITING_PERMISSION
        TaskPhase.WAITING_QUESTION -> SubSessionPhase.WAITING_QUESTION
        TaskPhase.WAITING_SERVER -> SubSessionPhase.WAITING_SERVER
        else -> SubSessionPhase.RUNNING
      }
      task?.phase == TaskPhase.FAILED || session.outcome == "failed" -> SubSessionPhase.FAILED
      task?.phase == TaskPhase.ABORTED || session.outcome == "interrupted" -> SubSessionPhase.INTERRUPTED
      task?.phase == TaskPhase.COMPLETED || session.outcome == "succeeded" || session.idle > 0 -> SubSessionPhase.COMPLETED
      else -> SubSessionPhase.UNKNOWN
    }
    val started = task?.since?.takeIf { it > 0 } ?: session.created.takeIf { it > 0 } ?: session.activityAt
    val ended = task?.finishedAt?.takeIf { it > 0 } ?: session.idle.takeIf { it > 0 }
    return SubSessionEntry(session, depth, phase, started, ended)
  }

  private fun priority(task: TaskState): Int = when (task.phase) {
    TaskPhase.WAITING_SERVER -> 5
    in TaskState.WAITING_PHASES -> 6
    in TaskState.RUNNING_PHASES -> 4
    TaskPhase.FAILED -> 3
    TaskPhase.COMPLETED -> 2
    else -> 1
  }
}
