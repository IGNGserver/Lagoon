package com.igng.opencode.lagoon.core

/**
 * Semantic buckets used by Android 16's segmented progress rail.
 *
 * The rail is a distribution view, not a claimed percentage of task completion: OpenCode does
 * not expose a reliable total-step count or ETA for an agent run.
 */
enum class LiveUpdateBucket { RUNNING, WAITING, COMPLETED, FAILED }

data class LiveUpdateSegment(
  val bucket: LiveUpdateBucket,
  val count: Int,
  val length: Int
)

/** Platform-independent data for a standard Notification.ProgressStyle. */
data class LiveUpdateProgress(
  val segments: List<LiveUpdateSegment>,
  val position: Int,
  val maxProgress: Int,
  val focus: LiveUpdateBucket?
) {
  companion object {
    private const val TRACK_LENGTH = 100

    val EMPTY = LiveUpdateProgress(emptyList(), 0, TRACK_LENGTH, null)

    fun of(summary: TaskSummary): LiveUpdateProgress {
      val buckets = listOf(
        LiveUpdateBucket.RUNNING to summary.running + summary.processing,
        LiveUpdateBucket.WAITING to summary.waiting,
        LiveUpdateBucket.COMPLETED to summary.completed,
        LiveUpdateBucket.FAILED to summary.failed
      ).filter { it.second > 0 }
      if (buckets.isEmpty()) return EMPTY

      val lengths = normalizedLengths(buckets.map { it.second })
      var offset = 0
      val segments = buckets.mapIndexed { index, (bucket, count) ->
        LiveUpdateSegment(bucket, count, lengths[index]).also { offset += it.length }
      }
      val focus = when {
        summary.waiting > 0 -> LiveUpdateBucket.WAITING
        summary.running > 0 || summary.processing > 0 -> LiveUpdateBucket.RUNNING
        summary.failed > 0 -> LiveUpdateBucket.FAILED
        summary.completed > 0 -> LiveUpdateBucket.COMPLETED
        else -> null
      }
      val focusSegment = segments.firstOrNull { it.bucket == focus }
      val position = if (focusSegment == null) 0 else {
        val start = segments.takeWhile { it.bucket != focus }.sumOf { it.length }
        (start + maxOf(1, focusSegment.length / 2)).coerceAtMost(offset)
      }
      return LiveUpdateProgress(segments, position, offset, focus)
    }

    /** Scale arbitrary task counts to a bounded rail while preserving their relative weight. */
    private fun normalizedLengths(counts: List<Int>): List<Int> {
      val total = counts.sumOf { it.toLong() }
      if (total <= 0) return emptyList()

      val lengths = counts.map { count ->
        maxOf(1, (count.toLong() * TRACK_LENGTH / total).toInt())
      }.toMutableList()
      var difference = TRACK_LENGTH - lengths.sum()

      if (difference > 0) {
        val order = counts.indices.sortedWith(
          compareByDescending<Int> { counts[it].toLong() * TRACK_LENGTH % total }
            .thenByDescending { counts[it] }
        )
        var index = 0
        while (difference > 0) {
          lengths[order[index % order.size]]++
          difference--
          index++
        }
      } else {
        while (difference < 0) {
          val index = lengths.indices
            .filter { lengths[it] > 1 }
            .maxByOrNull { lengths[it] }
            ?: break
          lengths[index]--
          difference++
        }
      }
      return lengths
    }
  }
}
