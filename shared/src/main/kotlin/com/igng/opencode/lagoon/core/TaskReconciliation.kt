package com.igng.opencode.lagoon.core

/** One authoritative read rule for the selected connection and the all-server monitor. */
internal object TaskReconciliation {
  /** Disk state is a retry hint, not evidence that a server survived an Android process restart. */
  fun restored(tasks: Map<String, TaskState>): Map<String, TaskState> = tasks.mapValues { (_, task) ->
    if (task.active) task.copy(uncertain = true) else task
  }

  /**
   * [active] is `GET /api/session/active`, the single source of "is this session running"; `null`
   * means the read failed. [inbox] reports admitted-but-undrained input. Nothing else — shells,
   * descendant sweeps, directory health — may hold a session open, because a read that cannot cover
   * every directory would then pin rows in a state the server never reported.
   */
  fun reconcile(
    previous: Map<String, TaskState>,
    sessions: List<Session>,
    active: Set<String>?,
    parents: Map<String, String>,
    deleted: Set<String> = emptySet(),
    inbox: PendingInputs.Read = PendingInputs.Read(verified = previous.keys),
    now: Long = System.currentTimeMillis()
  ): Map<String, TaskState> {
    val identities = sessions.associateBy { it.id }
    val states = previous.filterKeys { it !in deleted }.mapValues { (id, task) ->
      if (id in inbox.verified) task.copy(pendingInput = id in inbox.pending) else task
    }.toMutableMap()
    // A foreground claim is positive evidence, whatever else failed this round.
    active.orEmpty().forEach { id ->
      states[id] = TaskReducer.status(id, true, states[id], identities[id]?.idle ?: -1).copy(uncertain = false)
    }
    previous.values.sortedByDescending { BackgroundWork.depth(it.sessionId, parents) }.forEach { original ->
      val id = original.sessionId
      val old = states[id] ?: original
      if (id in deleted || id in active.orEmpty() || !old.active) return@forEach
      val session = identities[id]
      // Official clients settle a session from `session.active` alone: absent from a successful read
      // means idle. Shell listings, descendant sweeps and inbox reads describe background work and
      // queued input; a failure there must not freeze the root as "状态待确认".
      val child = old.phase == TaskPhase.SUBAGENT || parents[id] != null
      val finished = session != null && session.idle > 0 &&
        ((old.activeAt >= 0 && session.idle > old.activeAt) ||
          (old.activeAt < 0 && (child || now - old.since >= 10_000)))
      // A just-submitted turn gets a short grace window in which it is neither running nor settled;
      // anything older settles idle instead of spinning forever.
      val settleIdle = finished || now - old.since >= 10_000
      val inboxKnown = id in inbox.verified
      states[id] = when {
        // Admitted input the server has not drained yet outlives the previous run's outcome.
        inboxKnown && id in inbox.pending -> old.copy(phase = TaskPhase.WAITING_SERVER,
          detail = "等待服务器处理", uncertain = false, pendingInput = true)
        // The inbox read itself failed: keep a previously admitted prompt, don't invent one.
        !inboxKnown && old.pendingInput -> old.copy(uncertain = true)
        // The authoritative activity read failed, or this session's identity is not loaded: keep the
        // last claim for the next retry. It is never guessed into a result, and it already stops
        // counting as live for the summary and the row (see `liveTasks`).
        active == null || session == null -> old.copy(uncertain = true)
        settleIdle -> TaskReducer.terminal(id, session.outcome, old)
        else -> old.copy(uncertain = false)
      }
    }
    // Keep live states and terminal child records (historical tool metadata must not resurrect
    // completed work). Root results live in the unread ledger, not as permanent task phases.
    return states.filterValues { it.active || it.sessionId in parents || previous[it.sessionId]?.active == true }
  }

  /**
   * Descendant sweep for the family list and background marker. An unfinished sweep only delays those
   * identities; it never decides whether the root's own execution is over (that is [reconcile]'s job).
   */
  suspend fun children(client: HarnessClient, roots: List<Session>, parents: Map<String, String>,
    discovery: ChildDiscovery = ChildDiscovery()): ChildDiscovery.Result = discovery.read(client, roots, parents)

  /** Tracked work is verified before idle projects; the request budget must not starve old jobs. */
  fun shellDirectories(tasks: Map<String, TaskState>, shells: Map<String, ShellJob>, sessions: List<Session>,
    projects: List<Project>, active: Set<String>, limit: Int = 8): List<String> {
    val owners = tasks.filterValues { it.active }.keys + active
    val parents = sessions.mapNotNull { it.parentId?.let { parent -> it.id to parent } }.toMap()
    val roots = owners.map { BackgroundWork.rootOf(it, parents) }.toSet()
    return (shells.values.map { it.directory } + sessions.filter { it.id in owners || BackgroundWork.rootOf(it.id, parents) in roots }.map { it.directory } +
      projects.map { it.directory } + sessions.map { it.directory })
      .filter(String::isNotBlank).distinctBy(::normalizedDirectory).take(limit)
  }
}
