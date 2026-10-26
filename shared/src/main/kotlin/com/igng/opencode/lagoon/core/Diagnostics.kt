package com.igng.opencode.lagoon.core

/** Where [Diagnostics] lines go: `android.util.Log` on the phone, standard error on the hub. */
fun interface DiagnosticsSink {
  fun log(level: Diagnostics.Level, area: String, message: String, error: Throwable?)
}

/**
 * Minimal structured logging for the previously silent failure paths.
 *
 * Deliberately never logs credentials, cookies, tokens or message/file content: callers pass only a
 * short reason and the throwable. Crash reporting is intentionally not wired here (it needs an
 * external backend and would introduce a new dependency/secret), so this is the baseline that makes
 * field failures diagnosable via `adb logcat` or the hub container log.
 */
object Diagnostics {
  enum class Level { INFO, WARN }

  /** Replaced once at process start by the platform; the default suits JVM tests and the hub. */
  @Volatile var sink: DiagnosticsSink = DiagnosticsSink { level, area, message, error ->
    System.err.println("${level.name} [$area] $message" + (error?.let { " — ${it::class.java.simpleName}: ${it.message}" } ?: ""))
  }

  fun warn(area: String, message: String, error: Throwable? = null) = sink.log(Level.WARN, area, message, error)

  fun info(area: String, message: String) = sink.log(Level.INFO, area, message, null)
}
