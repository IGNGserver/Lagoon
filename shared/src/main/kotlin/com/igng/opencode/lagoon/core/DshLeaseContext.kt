package com.igng.opencode.lagoon.core

import java.io.Closeable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Explicit caller context survives withContext(IO); reads cannot implicitly select another lease. */
internal class DshLeaseContext(val read: (String) -> Closeable) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<DshLeaseContext>
}
