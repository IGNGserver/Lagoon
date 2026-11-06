package com.igng.opencode.lagoon.core

import kotlinx.coroutines.Job

/**
 * A bounded, time-aware cache of the transcript that was on screen. Selecting a session paints this
 * immediately instead of clearing the page and waiting for a network read. It is not authoritative:
 * a stale/mismatched entry is painted and then verified in the background.
 */
internal data class CachedSessionView(
  val messages: List<Message>,
  val olderCursor: String?,
  val loadedOlder: Boolean,
  val sessionUpdated: Long,
  val cacheComplete: Boolean,
  val at: Long
)

internal class SessionViewCache(
  private val capacity: Int = 8,
  private val ttlMillis: Long = 90_000L
) {
  private val entries = object : LinkedHashMap<String, CachedSessionView>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSessionView>?) = size > capacity
  }

  private fun key(server: String, session: String) = "$server\u0000$session"

  @Synchronized fun get(server: String, session: String): CachedSessionView? = entries[key(server, session)]

  @Synchronized fun put(server: String, session: String, view: CachedSessionView) {
    entries[key(server, session)] = view
  }

  @Synchronized fun forget(server: String, session: String) { entries.remove(key(server, session)) }

  @Synchronized fun forgetServer(server: String) {
    entries.keys.removeAll { it.startsWith("$server\u0000") }
  }

  @Synchronized fun clear() = entries.clear()

  /** The entry still describes the same server revision and is recent enough to skip a network read. */
  fun fresh(view: CachedSessionView?, session: Session, now: Long = System.currentTimeMillis()): Boolean =
    view != null && now - view.at <= ttlMillis && session.updated <= view.sessionUpdated
}

internal data class CachedChoices(
  val directory: String,
  val agents: List<AgentChoice>,
  val models: List<ModelInfo>,
  val commands: List<CommandChoice>,
  val at: Long,
  val complete: Boolean
)

internal class ChoicesCache(private val ttlMillis: Long = 10 * 60_000L) {
  private val entries = HashMap<String, CachedChoices>()

  private fun key(server: String, directory: String) = "$server\u0000${normalizedDirectory(directory)}"

  @Synchronized fun get(server: String, directory: String, now: Long = System.currentTimeMillis()): CachedChoices? =
    entries[key(server, directory)]?.takeIf { now - it.at <= ttlMillis }

  @Synchronized fun put(server: String, value: CachedChoices) {
    entries[key(server, value.directory)] = value
  }

  @Synchronized fun invalidateServer(server: String) {
    entries.keys.removeAll { it.startsWith("$server\u0000") }
  }

  @Synchronized fun clear() = entries.clear()
}

/**
 * One cancellable job per key. Selecting another session cancels the previous network read instead
 * of letting it finish and merely discarding its result.
 */
internal class KeyedJobs {
  private val jobs = HashMap<String, Job>()

  @Synchronized fun replace(key: String, job: Job) {
    jobs.remove(key)?.cancel()
    jobs[key] = job
  }

  @Synchronized fun remove(key: String, job: Job) {
    if (jobs[key] === job) jobs.remove(key)
  }

  @Synchronized fun cancel(key: String) { jobs.remove(key)?.cancel() }

  @Synchronized fun cancelWhere(predicate: (String) -> Boolean) {
    val matched = jobs.keys.filter(predicate)
    matched.forEach { jobs.remove(it)?.cancel() }
  }

  @Synchronized fun clear() {
    jobs.values.forEach { it.cancel() }
    jobs.clear()
  }
}
