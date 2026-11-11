package com.igng.opencode.lagoon.core

/** Durable seq fences historical events; epochs fence in-flight reads across new executions. */
internal class DshExecutionTracker {
  private data class Entry(var running: Boolean = false, var epoch: Long = 0, var startSeq: Long = -1,
    var endSeq: Long = -1, var outcome: String? = null, var awaitingStart: Boolean = false)
  private val entries = HashMap<String, Entry>()

  @Synchronized fun status(id: String, running: Boolean): Boolean {
    val entry = entries.getOrPut(id) { Entry() }
    if (!running) { entry.running = false; return false }
    if (entry.running) return false
    entry.running = true
    entry.epoch++
    entry.outcome = null
    entry.awaitingStart = true
    return true
  }

  @Synchronized fun start(id: String, seq: Long): Boolean {
    val entry = entries.getOrPut(id) { Entry() }
    if (seq <= entry.startSeq || seq <= entry.endSeq) return false
    entry.startSeq = seq
    entry.outcome = null
    entry.running = true
    if (!entry.awaitingStart) entry.epoch++
    entry.awaitingStart = false
    return true
  }

  @Synchronized fun settle(id: String, seq: Long, outcome: String, expectedEpoch: Long? = null): Boolean {
    val entry = entries.getOrPut(id) { Entry() }
    if (expectedEpoch != null && expectedEpoch != entry.epoch) return false
    if (seq <= entry.endSeq || seq <= entry.startSeq) return false
    entry.endSeq = seq
    entry.outcome = outcome
    entry.running = false
    entry.awaitingStart = false
    return true
  }

  @Synchronized fun epoch(id: String): Long = entries[id]?.epoch ?: 0
  @Synchronized fun outcome(id: String): String? = entries[id]?.outcome
  @Synchronized fun running(id: String): Boolean = entries[id]?.running == true
  @Synchronized fun remove(id: String) { entries.remove(id) }
  @Synchronized fun clear() { entries.clear() }
}
