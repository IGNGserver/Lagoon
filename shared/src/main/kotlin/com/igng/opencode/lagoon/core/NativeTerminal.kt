package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/** A native PTY in its authoritative response location; [cwd] is not its workspace identity. */
data class NativeTerminal(
  val id: String,
  val title: String,
  val directory: String,
  val status: String,
  val command: String,
  val args: List<String>,
  val pid: Long,
  val exitCode: Int? = null,
  val cwd: String = directory
)

/** Raw UTF-8 terminal output, bounded to the newest 256 KiB; sanitize VT sequences for display. */
data class TerminalStreamState(
  val ready: Boolean = false,
  val connected: Boolean = false,
  val output: String = "",
  val cursor: Long? = null,
  val error: String? = null,
  val ended: Boolean = false,
  val inputDeliveryUnknown: Boolean = false,
  val truncated: Boolean = false
)

/** Owns only a channel, never the terminal process. Input is unacknowledged and never replayed. */
interface TerminalAttachment : Closeable {
  val state: StateFlow<TerminalStreamState>
  fun sendText(text: String): Boolean
  override fun close()
}
