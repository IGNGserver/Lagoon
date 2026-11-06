package com.igng.opencode.lagoon.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coarse request budget counters for the loading paths. They are deliberately not per-endpoint:
 * the question this answers is "what is still refreshing in the background?" rather than tracing one
 * HTTP call. Values are process-local and safe to read from the diagnostics screen at any time.
 */
object LoadMetrics {
  private val counters = ConcurrentHashMap<String, AtomicInteger>()

  fun record(kind: String, amount: Int = 1) {
    counters.getOrPut(kind) { AtomicInteger() }.addAndGet(amount)
  }

  fun snapshot(): Map<String, Int> = counters.entries.sortedBy { it.key }.associate { it.key to it.value.get() }

  fun line(): String = snapshot().entries.joinToString(" · ") { (key, value) -> "$key=$value" }
}
