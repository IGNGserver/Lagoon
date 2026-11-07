package com.igng.opencode.lagoon.core

/** Only a successful complete directory read may remove a known job. Failures retain uncertainty. */
internal fun reconcileShellJobs(
  previous: Map<String, ShellJob>,
  fetched: Map<String, ShellJob>,
  verifiedDirectories: Set<String>,
  deletedSessions: Set<String> = emptySet(),
  now: Long = System.currentTimeMillis(),
  failedDirectories: Set<String> = emptySet()
): Map<String, ShellJob> = previous.filterValues {
  normalizedDirectory(it.directory) !in verifiedDirectories
}.mapValues { (_, job) -> job.copy(uncertain = job.uncertain || normalizedDirectory(job.directory) in failedDirectories) }
  .plus(fetched.mapValues { (_, job) -> job.copy(observedAt = now, uncertain = false) })
  .filterValues { it.sessionId !in deletedSessions }
