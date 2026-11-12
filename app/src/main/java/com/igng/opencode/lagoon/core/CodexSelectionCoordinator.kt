package com.igng.opencode.lagoon.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes selection mutations so a slow older RPC cannot overtake a newer selection. */
internal class CodexSelectionCoordinator {
  private val mutex = Mutex()

  /** Returns false if the caller was superseded before or during its remote operation. */
  suspend fun apply(current: () -> Boolean, select: suspend () -> Unit): Boolean = mutex.withLock {
    if (!current()) return@withLock false
    select()
    current()
  }
}
