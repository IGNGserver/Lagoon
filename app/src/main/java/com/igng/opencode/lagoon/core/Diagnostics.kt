package com.igng.opencode.lagoon.core

import android.util.Log

/**
 * Minimal structured logging for the previously silent failure paths.
 *
 * Deliberately never logs credentials, cookies, tokens or message/file content: callers pass only a
 * short reason and the throwable. Crash reporting is intentionally not wired here (it needs an
 * external backend and would introduce a new dependency/secret), so this is the baseline that makes
 * field failures diagnosable via `adb logcat`.
 */
internal object Diagnostics {
  private const val TAG = "Lagoon"

  fun warn(area: String, message: String, error: Throwable? = null) = Log.w(TAG, "[$area] $message", error)

  fun info(area: String, message: String) = Log.i(TAG, "[$area] $message")
}
