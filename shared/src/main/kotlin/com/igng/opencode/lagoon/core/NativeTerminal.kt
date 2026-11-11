package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/** Native backends disagree on ownership: never infer a DSH Session from a directory. */
sealed interface TerminalScope {
  val directory: String
  data class Location(override val directory: String) : TerminalScope
  data class Session(val sessionId: String, override val directory: String) : TerminalScope
}

/** A native PTY in its authoritative scope; [cwd] is not its workspace identity. */
data class NativeTerminal(
  val id: String,
  val title: String,
  val directory: String,
  val status: String,
  val command: String,
  val args: List<String>,
  val pid: Long,
  val exitCode: Int? = null,
  val cwd: String = directory,
  val sessionId: String? = null,
  val cols: Int? = null,
  val rows: Int? = null,
  val pidKnown: Boolean = true
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
  val truncated: Boolean = false,
  val writable: Boolean = true,
  val pendingInputs: Int = 0,
  val acknowledgedSequence: Long = 0,
  val info: NativeTerminal? = null,
  val inputRefusal: String? = null
)

/** Owns only an attachment, never the process. DSH ACKs acceptance; OpenCode does not. Neither replays input. */
interface TerminalAttachment : Closeable {
  val state: StateFlow<TerminalStreamState>
  fun sendText(text: String): Boolean
  /** DSH orders resize with input and ACKs it; location PTYs use their native HTTP update instead. */
  fun resize(cols: Int, rows: Int): Boolean = false
  override fun close()
}
