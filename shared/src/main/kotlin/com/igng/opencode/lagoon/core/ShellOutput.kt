package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** One shell command's current state (`GET /api/shell/{id}`), including exited commands. */
data class ShellSnapshot(
  val id: String,
  val command: String,
  val directory: String,
  val status: String,
  val exit: Int? = null,
  val started: Long = 0,
  val completed: Long? = null
) {
  val running: Boolean get() = status == "running"
}

/** One page of captured shell output (`GET /api/shell/{id}/output`). */
data class ShellOutput(
  val output: String = "",
  /** Absolute byte cursor after this page; equals [size] once fully caught up. */
  val cursor: Long = 0,
  val size: Long = 0,
  val truncated: Boolean = false
)

/** UI state for the shell output viewer; one viewer is open at a time. */
data class ShellOutputView(
  val shellId: String,
  val command: String,
  val directory: String,
  val snapshot: ShellSnapshot? = null,
  val text: String = "",
  val cursor: Long = 0,
  val size: Long = 0,
  val omitted: Boolean = false,
  val loading: Boolean = false,
  val error: String? = null
) {
  val running: Boolean get() = snapshot?.running == true
  val finished: Boolean get() = snapshot != null && !running && cursor >= size && !loading
}

internal fun JSONObject.toShellSnapshot(directory: String): ShellSnapshot? {
  val id = str("id")
  if (id.isBlank()) return null
  val time = obj("time")
  return ShellSnapshot(
    id = id,
    command = str("command"),
    directory = directory,
    status = str("status").ifBlank { "exited" },
    exit = (opt("exit") as? Number)?.toInt(),
    started = time.optLong("started", 0),
    completed = time.optLong("completed", 0).takeIf { it > 0 }
  )
}

internal fun JSONObject.toShellOutput(): ShellOutput = ShellOutput(
  output = str("output"),
  cursor = optLong("cursor", 0),
  size = optLong("size", 0),
  truncated = optBoolean("truncated")
)
