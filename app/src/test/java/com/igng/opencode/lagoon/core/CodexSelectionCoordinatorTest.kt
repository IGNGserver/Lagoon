package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodexSelectionCoordinatorTest {
  @Test fun slowOldSelectionCannotOverwriteNewForeground() = runBlocking {
    val coordinator = CodexSelectionCoordinator()
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()
    var current = "old"
    var selected = ""
    val old = async(start = CoroutineStart.UNDISPATCHED) {
      coordinator.apply({ current == "old" }) {
        started.complete(Unit)
        release.await()
        calls += "old"; selected = "old"
      }
    }
    started.await()
    current = "new"
    val latest = async(start = CoroutineStart.UNDISPATCHED) {
      coordinator.apply({ current == "new" }) { calls += "new"; selected = "new" }
    }
    release.complete(Unit)
    assertFalse(old.await())
    assertTrue(latest.await())
    assertEquals(listOf("old", "new"), calls)
    assertEquals("new", selected)
  }

  @Test fun supersededQueuedSelectionNeverReachesCarrier() = runBlocking {
    val coordinator = CodexSelectionCoordinator()
    val release = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()
    var generation = 1
    val first = async(start = CoroutineStart.UNDISPATCHED) {
      coordinator.apply({ generation == 1 }) { release.await(); calls += "first" }
    }
    generation = 2
    val obsolete = async(start = CoroutineStart.UNDISPATCHED) {
      coordinator.apply({ generation == 2 }) { calls += "obsolete" }
    }
    generation = 3
    val newest = async(start = CoroutineStart.UNDISPATCHED) {
      coordinator.apply({ generation == 3 }) { calls += "newest" }
    }
    release.complete(Unit)
    assertFalse(first.await())
    assertFalse(obsolete.await())
    assertTrue(newest.await())
    assertEquals(listOf("first", "newest"), calls)
  }
}
