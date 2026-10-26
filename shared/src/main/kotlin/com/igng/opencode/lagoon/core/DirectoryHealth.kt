package com.igng.opencode.lagoon.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-directory read health for location-scoped endpoints (`/api/form`, `/api/permission/request`,
 * `/api/session/{id}/permission`, `/api/shell`).
 *
 * OpenCode answers HTTP 500 with an empty body when the directory no longer exists (deleted
 * worktrees, removed scratch folders). Treating those as a global failure pinned the home list on
 * “部分数据待同步 · 下拉重试” forever: every reconciliation re-read the same dead directories. The
 * health map remembers a failing directory with a growing retry window, so the app stops hammering
 * it while still retrying it eventually; a success clears the entry immediately.
 */
internal class DirectoryHealth(private val clock: () -> Long = System::currentTimeMillis) {
  private data class Failure(val retryAt: Long, val attempts: Int)

  private val failures = ConcurrentHashMap<String, Failure>()

  /** True while this directory is inside its backoff window; callers skip the request entirely. */
  fun isDown(directory: String): Boolean {
    val key = normalizedDirectory(directory)
    val failure = failures[key] ?: return false
    if (failure.retryAt > clock()) return true
    failures.remove(key, failure)
    return false
  }

  /** Records a failed read and moves the directory to the next retry window. */
  fun failed(directory: String) {
    val key = normalizedDirectory(directory)
    if (key.isEmpty()) return
    failures.compute(key) { _, previous ->
      val attempts = previous?.attempts ?: 0
      Failure(clock() + backoffMillis(attempts), attempts + 1)
    }
  }

  fun succeeded(directory: String) {
    val key = normalizedDirectory(directory)
    if (key.isNotEmpty()) failures.remove(key)
  }

  internal companion object {
    /** 30 s → 1 min → 2 min → 5 min → 15 min cap. */
    fun backoffMillis(attempts: Int): Long = when (attempts) {
      0 -> 30_000L
      1 -> 60_000L
      2 -> 120_000L
      3 -> 300_000L
      else -> 900_000L
    }
  }
}
