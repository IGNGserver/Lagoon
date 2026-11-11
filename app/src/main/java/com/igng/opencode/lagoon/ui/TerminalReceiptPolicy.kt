package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.TerminalStreamState

internal enum class TerminalReceipt { NONE, PENDING, ACCEPTED, REFUSED, UNKNOWN }

/** An ACK can advance the sequence and still refuse input. It never means execution completed. */
internal fun terminalReceipt(state: TerminalStreamState, sequence: Long?): TerminalReceipt = when {
  sequence == null -> TerminalReceipt.NONE
  state.inputDeliveryUnknown -> TerminalReceipt.UNKNOWN
  state.inputRefusal != null -> TerminalReceipt.REFUSED
  state.acknowledgedSequence >= sequence -> TerminalReceipt.ACCEPTED
  state.ended || !state.writable -> TerminalReceipt.UNKNOWN
  else -> TerminalReceipt.PENDING
}
