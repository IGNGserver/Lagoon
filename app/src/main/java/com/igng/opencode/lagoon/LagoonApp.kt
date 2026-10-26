package com.igng.opencode.lagoon

import android.app.Application
import android.util.Log
import com.igng.opencode.lagoon.core.CrashLog
import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.DiagnosticsSink

class LagoonApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // Installed before any activity, service or receiver runs so every entry point is covered.
    CrashLog.install(this)
    Diagnostics.sink = DiagnosticsSink { level, area, message, error ->
      if (level == Diagnostics.Level.WARN) Log.w("Lagoon", "[$area] $message", error) else Log.i("Lagoon", "[$area] $message")
    }
  }
}
