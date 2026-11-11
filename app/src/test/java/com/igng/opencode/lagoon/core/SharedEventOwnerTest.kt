package com.igng.opencode.lagoon.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class SharedEventOwnerTest {
  private class Source {
    val starts = AtomicInteger()
    val stops = Channel<Unit>(Channel.UNLIMITED)
    val values = Channel<Int>(Channel.UNLIMITED)
    val ready = Channel<() -> Unit>(Channel.UNLIMITED)
    var cleanup: CompletableDeferred<Unit>? = null
    @Volatile var live = false
    fun flow(onReady: () -> Unit): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flow {
      starts.incrementAndGet(); live = true; ready.send(onReady); onReady()
      try { for (value in values) emit(value) }
      finally { live = false; withContext(NonCancellable) { cleanup?.await() }; stops.send(Unit) }
    }
  }

  @Test fun twoReadersShareOneSourceAndCancellingOneDoesNotStopAnother() = runBlocking {
    val source = Source(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = SharedEventOwner(scope, source::flow, { source.live })
    val a = Channel<Int>(8); val b = Channel<Int>(8); val opens = Channel<Unit>(8)
    val one = launch { hub.events { opens.trySend(Unit) }.collect { a.send(it) } }
    val two = launch { hub.events { opens.trySend(Unit) }.collect { b.send(it) } }
    try {
      withTimeout(2000) { repeat(2) { opens.receive() } }
      assertEquals(1, source.starts.get())
      source.values.send(1)
      assertEquals(1, withTimeout(2000) { a.receive() }); assertEquals(1, withTimeout(2000) { b.receive() })
      one.cancelAndJoin()
      source.values.send(2); assertEquals(2, withTimeout(2000) { b.receive() })
      assertTrue(source.stops.tryReceive().isFailure)
      two.cancelAndJoin(); withTimeout(2000) { source.stops.receive() }
    } finally { hub.close(); one.cancelAndJoin(); two.cancelAndJoin(); scope.cancel() }
  }

  @Test fun slowReaderFailsExplicitlyWithoutDroppingOrBlockingHealthySibling() = runBlocking {
    val source = Source(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = SharedEventOwner(scope, source::flow, { source.live }, capacity = 2)
    val blocked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val fast = Channel<Int>(8); val opens = Channel<Unit>(4)
    val slow = async { runCatching { hub.events { opens.trySend(Unit) }.collect {
      blocked.complete(Unit); release.await()
    } } }
    val quick = launch { hub.events { opens.trySend(Unit) }.collect { fast.send(it) } }
    try {
      withTimeout(2000) { repeat(2) { opens.receive() } }
      source.values.send(1); withTimeout(2000) { blocked.await() }; assertEquals(1, withTimeout(2000) { fast.receive() })
      for (n in 2..4) { source.values.send(n); assertEquals(n, withTimeout(2000) { fast.receive() }) }
      release.complete(Unit)
      assertTrue(withTimeout(2000) { slow.await() }.exceptionOrNull() is IOException)
      source.values.send(5); assertEquals(5, withTimeout(2000) { fast.receive() })
      assertEquals(1, source.starts.get())
    } finally { release.complete(Unit); hub.close(); slow.cancelAndJoin(); quick.cancelAndJoin(); scope.cancel() }
  }

  @Test fun newSubscriberWaitsForOldCleanupAndIgnoresLateOldReady() = runBlocking {
    val source = Source(); val cleanup = CompletableDeferred<Unit>(); source.cleanup = cleanup
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = SharedEventOwner(scope, source::flow, { source.live })
    val firstReady = CompletableDeferred<Unit>()
    val old = launch { hub.events { firstReady.complete(Unit) }.collect {} }
    withTimeout(2000) { firstReady.await() }
    val staleReady = withTimeout(2000) { source.ready.receive() }
    old.cancelAndJoin()
    val nextReady = CompletableDeferred<Unit>()
    val next = launch(start = CoroutineStart.UNDISPATCHED) { hub.events { nextReady.complete(Unit) }.collect {} }
    try {
      staleReady()
      assertFalse(nextReady.isCompleted); assertEquals(1, source.starts.get())
      cleanup.complete(Unit)
      withTimeout(2000) { nextReady.await() }
      assertEquals(2, source.starts.get())
    } finally { cleanup.complete(Unit); hub.close(); next.cancelAndJoin(); scope.cancel() }
  }

  @Test fun finiteOrFailedSourceCompletesSubscribersInsteadOfReopeningForever() = runBlocking {
    for (failure in listOf(false, true)) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); var starts = 0
      val hub = SharedEventOwner<Int>(scope, { ready -> flow { starts++; ready(); emit(1); if (failure) throw IOException("source failed") } }, { true })
      val received = mutableListOf<Int>()
      val result = withTimeout(2000) { runCatching { hub.events {}.collect { received += it } } }
      assertEquals(listOf(1), received); assertEquals(1, starts)
      assertEquals(failure, result.isFailure)
      hub.close(); scope.cancel()
    }
  }

  @Test fun lateJoinDuringReconnectWaitsForTheNewSourceReadyAcknowledgement() = runBlocking {
    val source = Source(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = SharedEventOwner(scope, source::flow, { source.live })
    val firstReady = Channel<Unit>(4); val lateReady = Channel<Unit>(4)
    val first = launch { hub.events { firstReady.trySend(Unit) }.collect {} }
    withTimeout(2000) { firstReady.receive() }
    val ready = withTimeout(2000) { source.ready.receive() }
    hub.notReady() // The physical transport ended; its replacement has not acknowledged yet.
    val late = launch(start = CoroutineStart.UNDISPATCHED) { hub.events { lateReady.trySend(Unit) }.collect {} }
    try {
      assertTrue(lateReady.tryReceive().isFailure)
      ready()
      withTimeout(2000) { firstReady.receive(); lateReady.receive() }
      assertTrue(lateReady.tryReceive().isFailure)
      assertEquals(1, source.starts.get())
    } finally { hub.close(); first.cancelAndJoin(); late.cancelAndJoin(); scope.cancel() }
  }

  @Test fun unconfinedReadyCallbackMayUnsubscribeWithoutInvalidatingFanout() = runBlocking {
    val source = Source(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = SharedEventOwner(scope, source::flow, { source.live })
    val ready = Channel<Unit>(4)
    val first = launch(Dispatchers.Unconfined) {
      val self = currentCoroutineContext().job
      hub.events { ready.trySend(Unit); self.cancel() }.collect {}
    }
    val second = launch { hub.events { ready.trySend(Unit) }.collect {} }
    try {
      withTimeout(2000) { repeat(2) { ready.receive() }; first.join() }
      assertTrue(second.isActive)
    } finally { hub.close(); first.cancelAndJoin(); second.cancelAndJoin(); scope.cancel() }
  }

  @Test fun explicitCloseCompletesAllAndRejectsLaterSubscriptions() = runBlocking {
    val source = Source(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = SharedEventOwner(scope, source::flow, { source.live }); val open = CompletableDeferred<Unit>()
    val active = launch { hub.events { open.complete(Unit) }.collect {} }
    withTimeout(2000) { open.await() }
    hub.close(); withTimeout(2000) { active.join() }
    assertTrue(runCatching { hub.events {}.collect {} }.isFailure)
    withTimeout(2000) { source.stops.receive() }; scope.cancel()
  }
}
