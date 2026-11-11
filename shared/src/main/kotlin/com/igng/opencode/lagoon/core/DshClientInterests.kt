package com.igng.opencode.lagoon.core

import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

internal data class DshInterestSnapshot(
  val content: Boolean,
  val watched: Set<String>,
  val reading: Set<String>,
  val foregroundSession: String?,
)

/** Aggregates local client leases without letting a background read claim presentation. */
internal class DshClientInterests(
  private val apply: (DshInterestSnapshot) -> Unit,
  private val present: (String?, Boolean) -> Unit,
) {
  private class Lease {
    var content = true
    var watchedSession: String? = null
    var presentationSession: String? = null
    val reads = mutableMapOf<String, Int>()
  }

  // The gate orders mutations together with their callbacks. Callbacks never hold stateLock.
  private val dispatchGate = Any()
  private val stateLock = Any()
  private val leases = IdentityHashMap<Any, Lease>()
  private var foregroundToken: Any? = null
  private var generation = 0L
  private var presentedSession: String? = null

  fun add(token: Any) = mutate {
    if (leases.containsKey(token)) false else {
      leases[token] = Lease()
      true
    }
  }

  fun remove(token: Any) = mutate {
    if (leases.remove(token) == null) false else {
      if (foregroundToken === token) foregroundToken = null
      true
    }
  }

  fun setContent(token: Any, enabled: Boolean) = mutate {
    val lease = requireLease(token)
    val changed = lease.content != enabled
    lease.content = enabled
    if (!enabled && foregroundToken === token) foregroundToken = null
    changed
  }

  fun presentation(token: Any, sessionId: String?, foreground: Boolean) = mutate {
    val lease = requireLease(token)
    lease.presentationSession = sessionId?.takeIf { it.isNotBlank() }
    if (foreground && lease.presentationSession != null) {
      // An explicit visible presentation enables its own content; reads never do this.
      lease.content = true
      foregroundToken = token
    } else if (foregroundToken === token) {
      foregroundToken = null
    }
    true
  }

  fun read(token: Any, sessionId: String): Closeable {
    lateinit var lease: Lease
    mutate {
      lease = requireLease(token)
      lease.watchedSession = sessionId
      lease.reads[sessionId] = (lease.reads[sessionId] ?: 0) + 1
      true
    }
    return object : Closeable {
      private var closed = false

      override fun close() = mutate {
        if (closed) false else {
          closed = true
          // A removed token can be registered again, but its old readers cannot touch it.
          if (leases[token] !== lease) false else {
            val count = lease.reads[sessionId] ?: 0
            if (count <= 1) lease.reads.remove(sessionId) else lease.reads[sessionId] = count - 1
            true
          }
        }
      }
    }
  }

  fun canPresent(token: Any): Boolean = synchronized(stateLock) {
    foregroundToken === token && leases[token]?.let {
      it.content && it.presentationSession != null
    } == true
  }

  fun contains(token: Any): Boolean = synchronized(stateLock) { leases.containsKey(token) }

  private fun requireLease(token: Any): Lease = checkNotNull(leases[token]) { "Client lease is closed" }

  private fun foregroundSessionLocked(): String? = foregroundToken?.let { token ->
    leases[token]?.takeIf { it.content }?.presentationSession
  }

  private fun snapshotLocked(): DshInterestSnapshot {
    val reading = linkedSetOf<String>()
    val watched = linkedSetOf<String>()
    var content = false
    for (lease in leases.values) {
      if (lease.content) {
        content = true
        lease.watchedSession?.let(watched::add)
      }
      reading.addAll(lease.reads.keys)
    }
    watched.addAll(reading)
    val foreground = foregroundSessionLocked()
    foreground?.let(watched::add)
    return DshInterestSnapshot(content, immutableCopy(watched), immutableCopy(reading), foreground)
  }

  private fun immutableCopy(values: Set<String>): Set<String> =
    Collections.unmodifiableSet(LinkedHashSet(values))

  private fun mutate(change: () -> Boolean) {
    synchronized(dispatchGate) {
      val update = synchronized(stateLock) {
        if (!change()) null else {
          generation += 1
          snapshotLocked() to generation
        }
      } ?: return
      val (snapshot, version) = update
      apply(snapshot)
      // A local callback may reenter this helper. Never publish the superseded presentation.
      val current = synchronized(stateLock) { generation == version }
      if (current && presentedSession != snapshot.foregroundSession) {
        presentedSession = snapshot.foregroundSession
        present(snapshot.foregroundSession, snapshot.foregroundSession != null)
      }
    }
  }
}
