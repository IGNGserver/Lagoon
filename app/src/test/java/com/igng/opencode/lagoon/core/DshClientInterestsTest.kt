package com.igng.opencode.lagoon.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class DshClientInterestsTest {
  private class Fixture {
    val snapshots = mutableListOf<DshInterestSnapshot>()
    val presentations = mutableListOf<Pair<String?, Boolean>>()
    val interests = DshClientInterests({ snapshots.add(it) }) { session, foreground ->
      presentations.add(session to foreground)
    }
    val snapshot: DshInterestSnapshot get() = snapshots.last()
  }

  @Test fun backgroundReadOfAnotherSessionCannotStealForeground() {
    val f = Fixture()
    val visible = Any(); val background = Any()
    f.interests.add(visible); f.interests.add(background)
    f.interests.presentation(visible, "visible", true)
    f.interests.setContent(background, false)
    f.interests.read(background, "background").use {
      assertEquals("visible", f.snapshot.foregroundSession)
      assertEquals(setOf("visible", "background"), f.snapshot.watched)
      assertEquals(setOf("background"), f.snapshot.reading)
      assertTrue(f.interests.canPresent(visible))
      assertFalse(f.interests.canPresent(background))
      assertEquals(listOf("visible" to true), f.presentations)
    }
    assertEquals(setOf("visible"), f.snapshot.watched)
  }

  @Test fun disabledLeasesAndBackgroundReadsDoNotEnableContent() {
    val f = Fixture(); val first = Any(); val second = Any()
    f.interests.add(first); f.interests.add(second)
    assertTrue(f.snapshot.content)
    f.interests.setContent(first, false)
    assertTrue(f.snapshot.content)
    f.interests.setContent(second, false)
    f.interests.read(first, "read-only").use {
      assertFalse(f.snapshot.content)
      assertEquals(setOf("read-only"), f.snapshot.watched)
      assertEquals(setOf("read-only"), f.snapshot.reading)
    }
    assertTrue(f.snapshot.watched.isEmpty())
    f.interests.presentation(first, "explicit", true)
    assertTrue(f.snapshot.content)
    assertTrue(f.interests.canPresent(first))
    f.interests.setContent(first, false)
    assertFalse(f.snapshot.content)
    assertNull(f.snapshot.foregroundSession)
    assertFalse(f.interests.canPresent(first))
    assertEquals(listOf("explicit" to true, null to false), f.presentations)
  }

  @Test fun closingBackgroundLeaseDoesNotClearVisiblePresentation() {
    val f = Fixture(); val foreground = Any(); val background = Any()
    f.interests.add(foreground); f.interests.add(background)
    f.interests.presentation(foreground, "visible", true)
    val read = f.interests.read(background, "other")
    f.interests.remove(background)
    read.close()
    assertEquals("visible", f.snapshot.foregroundSession)
    assertEquals(setOf("visible"), f.snapshot.watched)
    assertTrue(f.interests.canPresent(foreground))
    assertEquals(listOf("visible" to true), f.presentations)
  }

  @Test fun latestForegroundIdentityWinsWithoutRestoringAnEarlierLease() {
    val f = Fixture()
    // Equal values are still distinct opaque lease identities.
    val first = String(charArrayOf('t')); val second = String(charArrayOf('t'))
    assertEquals(first, second); assertNotSame(first, second)
    f.interests.add(first); f.interests.add(second)
    f.interests.presentation(first, "same", true)
    f.interests.presentation(second, "same", true)
    assertFalse(f.interests.canPresent(first)); assertTrue(f.interests.canPresent(second))
    // A same-session ownership change must not synthesize a false/true handoff.
    assertEquals(listOf("same" to true), f.presentations)
    f.interests.remove(second)
    assertNull(f.snapshot.foregroundSession)
    assertFalse(f.interests.canPresent(first))
    assertTrue(f.interests.contains(first))
    assertEquals(listOf("same" to true, null to false), f.presentations)
  }

  @Test fun lateOldPresentationFalseAndInvalidPresentationCannotClearNewOwner() {
    val f = Fixture(); val old = Any(); val current = Any()
    f.interests.add(old); f.interests.add(current)
    f.interests.presentation(old, "old", true)
    f.interests.presentation(current, "current", true)
    f.interests.presentation(old, "old", false)
    f.interests.presentation(old, "   ", true)
    f.interests.setContent(old, false)
    assertEquals("current", f.snapshot.foregroundSession)
    assertTrue(f.interests.canPresent(current)); assertFalse(f.interests.canPresent(old))
    assertEquals(listOf("old" to true, "current" to true), f.presentations)
  }

  @Test fun readReferenceCountsRetainAllActiveReadsAndLastPersistentWatch() {
    val f = Fixture(); val token = Any()
    f.interests.add(token)
    val first = f.interests.read(token, "first")
    val again = f.interests.read(token, "first")
    val second = f.interests.read(token, "last")
    val captured = f.snapshot
    first.close()
    assertEquals(setOf("first", "last"), f.snapshot.reading)
    again.close()
    assertEquals(setOf("last"), f.snapshot.reading)
    second.close()
    assertTrue(f.snapshot.reading.isEmpty())
    assertEquals(setOf("last"), f.snapshot.watched)
    assertNull(f.snapshot.foregroundSession)
    assertEquals(setOf("first", "last"), captured.reading)
    assertEquals(setOf("first", "last"), captured.watched)
    assertTrue(f.presentations.isEmpty())
  }

  @Test fun readCloseAfterRemovalIsIdempotentAndCannotAffectReregisteredLease() {
    val f = Fixture(); val token = Any()
    f.interests.add(token)
    val staleRead = f.interests.read(token, "stale")
    f.interests.remove(token); f.interests.remove(token)
    assertFalse(f.interests.contains(token)); assertFalse(f.interests.canPresent(token))
    assertClosed { f.interests.setContent(token, true) }
    assertClosed { f.interests.presentation(token, "stale", true) }
    assertClosed { f.interests.read(token, "stale") }
    val callbacksAfterRemoval = f.snapshots.size
    f.interests.add(token)
    f.interests.read(token, "fresh").use {
      val callbacksBeforeStaleClose = f.snapshots.size
      staleRead.close(); staleRead.close()
      assertEquals(callbacksBeforeStaleClose, f.snapshots.size)
      assertEquals(callbacksAfterRemoval + 2, f.snapshots.size)
      assertEquals(setOf("fresh"), f.snapshot.reading)
      assertEquals(setOf("fresh"), f.snapshot.watched)
    }
    assertEquals(setOf("fresh"), f.snapshot.watched)
  }

  @Test fun callbacksRemainOrderedWhenAnotherThreadRemovesDuringApply() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val removing = CountDownLatch(1)
    val removed = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val callbacks = Collections.synchronizedList(mutableListOf<String>())
    val snapshots = Collections.synchronizedList(mutableListOf<DshInterestSnapshot>())
    val interests = DshClientInterests({ snapshot ->
      snapshots.add(snapshot)
      callbacks.add("apply:${snapshot.foregroundSession}")
      if (snapshot.foregroundSession == "visible") {
        entered.countDown()
        assertTrue(release.await(5, TimeUnit.SECONDS))
      }
    }, { session, foreground -> callbacks.add("present:$session:$foreground") })
    val token = Any(); interests.add(token); callbacks.clear(); snapshots.clear()
    val claim = Thread {
      try { interests.presentation(token, "visible", true) }
      catch (error: Throwable) { failure.compareAndSet(null, error) }
    }
    val remove = Thread {
      removing.countDown()
      try { interests.remove(token) }
      catch (error: Throwable) { failure.compareAndSet(null, error) }
      finally { removed.countDown() }
    }
    try {
      claim.start()
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      remove.start()
      assertTrue(removing.await(5, TimeUnit.SECONDS))
      assertEquals(1L, removed.count)
      // Readers use only the state monitor, not the callback dispatch gate.
      assertTrue(interests.contains(token)); assertTrue(interests.canPresent(token))
    } finally {
      release.countDown()
      claim.join(5_000)
      remove.join(5_000)
    }
    assertFalse(claim.isAlive); assertFalse(remove.isAlive)
    failure.get()?.let { throw AssertionError("Worker failed", it) }
    assertEquals(listOf("apply:visible", "present:visible:true", "apply:null", "present:null:false"), callbacks)
    assertFalse(interests.contains(token)); assertFalse(interests.canPresent(token))
    assertEquals(DshInterestSnapshot(false, emptySet(), emptySet(), null), snapshots.last())
  }

  private fun assertClosed(action: () -> Unit) {
    try {
      action()
      fail("Closed token must reject mutations")
    } catch (_: IllegalStateException) {
      // Expected; no token details are included in the error.
    }
  }
}
