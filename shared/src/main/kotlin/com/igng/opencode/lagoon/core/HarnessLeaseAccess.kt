package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.Flow

/** Every method/property and deferred Flow must enter this gate; no raw owner escapes a lease. */
internal interface HarnessLeaseAccess {
  val isClosed: Boolean
  fun close()
  suspend fun awaitClosed()
  fun <T> use(block: (HarnessClient) -> T): T
  suspend fun <T> useSuspending(block: suspend (HarnessClient) -> T): T
  fun <T> stream(block: (HarnessClient) -> Flow<T>): Flow<T>
  fun callback(block: () -> Unit) { if (!isClosed) block() }
  fun setContentWatching(enabled: Boolean)
  fun questionPresentation(sessionId: String?, foreground: Boolean)
  fun questionFocus(id: String, focused: Boolean)
  fun questionEngage(id: String)
  fun questionTakeTime(id: String)
}
