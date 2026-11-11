package com.igng.opencode.lagoon.core

/** Protocol-specific native positions. These are not interchangeable transcript row indices. */
sealed interface ForkBoundary {
  /** Copy through this exact original DSH durable event, inclusively. */
  data class DshEvent(val sequence: Long) : ForkBoundary
  /** Copy OpenCode projected history strictly before this native message id. */
  data class OpenCodeBefore(val messageId: String) : ForkBoundary
}

/** Do not turn fractional, string, negative or non-safe native event numbers into fork positions. */
internal fun exactDshSequence(value: Any?): Long? {
  if (value !is Number) return null
  return try {
    java.math.BigDecimal(value.toString()).longValueExact().takeIf { it in 1..9_007_199_254_740_991L }
  } catch (_: Exception) { null }
}
