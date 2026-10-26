package com.igng.opencode.lagoon.core

/** One authoritative read rule for the selected connection and the all-server monitor. */
internal object TaskReconciliation {
  /** Disk state is a retry hint, not evidence that a server survived an Android process restart. */
  fun restored(tasks: Map<String, TaskState>): Map<String, TaskState> = tasks.mapValues { (_, task) ->
    if (task.active) task.copy(uncertain = true) else task
  }

  fun reconcile(
    previous: Map<String, TaskState>,
    sessions: List<Session>,
    active: Set<String>?,
    shells: Map<String, ShellJob>,
    parents: Map<String, String>,
    verifiedSessions: Set<String>,
    verifiedShellDirectories: Set<String>,
    deleted: Set<String> = emptySet(),
    incompleteRoots: Set<String> = emptySet(),
    inbox: PendingInputs.Read = PendingInputs.Read(verified = previous.keys),
    now: Long = System.currentTimeMillis()
  ): Map<String, TaskState> {
    val identities = sessions.associateBy { it.id }
    val states = previous.filterKeys { it !in deleted }.mapValues { (id, task) ->
      if (id in inbox.verified) task.copy(pendingInput = id in inbox.pending) else task
    }.toMutableMap()
    // A foreground claim is positive evidence, even when a shell/location read failed.
    active.orEmpty().forEach { id ->
      states[id] = TaskReducer.status(id, true, states[id], identities[id]?.idle ?: -1).copy(uncertain = false)
    }
    previous.values.sortedByDescending { BackgroundWork.depth(it.sessionId, parents) }.forEach { original ->
      val id = original.sessionId
      val old = states[id] ?: original
      if (id in deleted || id in active.orEmpty() || !old.active) return@forEach
      val session = identities[id]
      val family = (BackgroundWork.familyOf(id, parents) - deleted).filter {
        it == id || states[it]?.active == true || it in verifiedSessions
      }.toSet()
      val work = BackgroundWork.hasBackgroundWork(id, states, shells, parents)
      val confirmedWork = active != null && (family.any { it != id && it in active } || shells.values.any {
        it.sessionId in family && normalizedDirectory(it.directory) in verifiedShellDirectories
      })
      val verified = active != null && id in inbox.verified && BackgroundWork.rootOf(id, parents) !in incompleteRoots && id in verifiedSessions && session != null && family.all { member ->
        val identity = identities[member]
        identity != null && member in verifiedSessions && normalizedDirectory(identity.directory) in verifiedShellDirectories
      }
      val child = old.phase == TaskPhase.SUBAGENT || parents[id] != null
      val finished = session != null && session.idle > 0 &&
        ((old.activeAt >= 0 && session.idle > old.activeAt) ||
          (old.activeAt < 0 && (child || now - old.since >= 10_000)))
      states[id] = when {
        old.pendingInput && id in inbox.verified && !confirmedWork -> old.copy(phase = TaskPhase.WAITING_SERVER,
          detail = "等待服务器处理", background = false, uncertain = false)
        confirmedWork && old.phase in TaskState.RUNNING_PHASES -> old.copy(phase = TaskPhase.THINKING,
          detail = TaskState.BACKGROUND_DETAIL, background = true, uncertain = false)
        !verified -> old.copy(uncertain = true)
        work && old.phase in TaskState.RUNNING_PHASES -> old.copy(phase = TaskPhase.THINKING,
          detail = TaskState.BACKGROUND_DETAIL, background = true,
          uncertain = states.values.any { it.sessionId != id && it.sessionId in family && it.active && it.uncertain })
        !work && (old.background || finished) -> TaskReducer.terminal(id, session?.outcome, old)
        // No positive running claim and no usable terminal baseline: retain the retry hint, but
        // don't invent either completion or an indefinitely running foreground execution.
        else -> old.copy(uncertain = now - old.since >= 10_000 &&
          (old.phase in TaskState.RUNNING_PHASES || old.phase == TaskPhase.WAITING_SERVER))
      }
    }
    // Keep child terminal records: historical tool metadata must not resurrect completed work.
    val folded = BackgroundWork.fold(states.filterValues {
      it.active || it.sessionId in parents || previous[it.sessionId]?.active == true
    }, shells, parents)
    return folded.mapValues { (id, task) ->
      if (!task.background || !task.active) task else {
        val family = BackgroundWork.familyOf(id, parents)
        val confirmed = family.any { it in active.orEmpty() } || shells.values.any {
          it.sessionId in family && normalizedDirectory(it.directory) in verifiedShellDirectories
        }
        task.copy(uncertain = !confirmed)
      }
    }
  }

  /** A failed/unfinished sweep is unknown; the scanner resumes it on the following poll. */
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
