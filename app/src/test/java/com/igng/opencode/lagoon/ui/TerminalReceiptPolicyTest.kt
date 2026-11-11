package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.TerminalStreamState
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalReceiptPolicyTest {
  @Test fun refusedAckIsNotSuccessfulAcceptanceEvenIfItsSequenceAdvanced() {
    assertEquals(TerminalReceipt.REFUSED, terminalReceipt(TerminalStreamState(acknowledgedSequence = 1, inputRefusal = "read-only", writable = false), 1))
    assertEquals(TerminalReceipt.REFUSED, terminalReceipt(TerminalStreamState(acknowledgedSequence = 2, inputRefusal = "not-running", ended = true), 2))
  }
  @Test fun pendingInputOnDetachOrControlLossRemainsUnknownWithoutReplay() {
    assertEquals(TerminalReceipt.UNKNOWN, terminalReceipt(TerminalStreamState(pendingInputs = 1, ended = true), 1))
    assertEquals(TerminalReceipt.UNKNOWN, terminalReceipt(TerminalStreamState(pendingInputs = 1, writable = false), 1))
    assertEquals(TerminalReceipt.UNKNOWN, terminalReceipt(TerminalStreamState(inputDeliveryUnknown = true, acknowledgedSequence = 1), 1))
  }
  @Test fun acceptedAndPendingReceiptsAreDistinctFromNoSubmission() {
    assertEquals(TerminalReceipt.NONE, terminalReceipt(TerminalStreamState(inputRefusal = "read-only"), null))
    assertEquals(TerminalReceipt.PENDING, terminalReceipt(TerminalStreamState(pendingInputs = 1), 1))
    assertEquals(TerminalReceipt.ACCEPTED, terminalReceipt(TerminalStreamState(acknowledgedSequence = 1), 1))
  }
}
