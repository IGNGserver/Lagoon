package com.igng.opencode.lagoon.core

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** One business reader, bounded independent subscriptions, and serialized generation teardown. */
internal class SharedEventOwner<T>(
  private val scope: CoroutineScope,
  private val source: (onReady: () -> Unit) -> Flow<T>,
  private val isReady: () -> Boolean,
  private val capacity: Int = 256,
  private val copyForSubscriber: (T) -> T = { it }
) : AutoCloseable {
  init { require(capacity > 0) { "事件订阅缓冲区必须为正数" } }
  private sealed interface Item<out T> {
    data class Value<T>(val value: T) : Item<T>
    data object Ready : Item<Nothing>
  }
  private class Reader<T>(val queue: Channel<Item<T>>)
  private class Run { lateinit var job: Job; var stopping = false; var ready = false }
  private val lock = Any()
  private val readers = linkedSetOf<Reader<T>>()
  private var run: Run? = null
  private var closed = false

  fun events(onReady: () -> Unit): Flow<T> = flow {
    val reader = Reader(Channel<Item<T>>(capacity))
    val start = synchronized(lock) {
      check(!closed) { "事件 owner 已关闭" }
      readers += reader
      if (run == null) prepareLocked() else {
        // A pending/stopping generation is never proof of readiness for a new subscriber.
        if (run?.stopping == false && run?.ready == true && isReady()) reader.queue.trySend(Item.Ready)
        null
      }
    }
    start?.job?.start()
    try {
      for (item in reader.queue) when (item) {
        Item.Ready -> onReady()
        is Item.Value -> emit(item.value)
      }
    } finally { release(reader) }
  }

  private fun prepareLocked(): Run {
    val next = Run()
    next.job = scope.launch(start = CoroutineStart.LAZY) {
      var failure: Throwable? = null
      try {
        source { ready(next) }.collect { value -> publish(next, value) }
      } catch (error: kotlinx.coroutines.CancellationException) { failure = error; throw error }
      catch (error: Throwable) { failure = error }
      finally {
        val affected = synchronized(lock) {
          if (run !== next || next.stopping) emptyList() else {
            next.stopping = true
            readers.toList().also { readers.clear() }
          }
        }
        affected.forEach { it.queue.close(failure) }
      }
    }
    run = next
    next.job.invokeOnCompletion {
      val restart = synchronized(lock) {
        if (run !== next) null else {
          run = null
          // Cleanup is finished before a new generation may touch the business reader's state.
          if (!closed && readers.isNotEmpty()) prepareLocked() else null
        }
      }
      restart?.job?.start()
    }
    return next
  }

  private fun ready(current: Run) = distribute(current, Item.Ready)
  private fun publish(current: Run, value: T) = distribute(current, Item.Value(value))

  private fun distribute(current: Run, item: Item<T>) {
    val stalled = mutableListOf<Reader<T>>()
    val stop = synchronized(lock) {
      if (run !== current || current.stopping || closed) return
      if (item === Item.Ready) current.ready = true
      // An Unconfined subscriber may resume synchronously and unsubscribe/re-enter this owner.
      // Snapshot iteration cannot be invalidated by that callback while the monitor is reentrant.
      for (reader in readers.toList()) {
        val delivered = if (item is Item.Value) Item.Value(copyForSubscriber(item.value)) else item
        if (reader.queue.trySend(delivered).isFailure) { readers.remove(reader); stalled += reader }
      }
      if (readers.isEmpty()) { current.stopping = true; current.job } else null
    }
    // A slow consumer fails explicitly; it never drops question/control events or blocks siblings.
    stalled.forEach { it.queue.close(IOException("事件订阅消费者过慢；请重新同步，未静默丢弃事件")) }
    stop?.cancel()
  }

  private fun release(reader: Reader<T>) {
    val stop = synchronized(lock) {
      readers.remove(reader)
      if (readers.isEmpty()) run?.takeUnless { it.stopping }?.also { it.stopping = true }?.job else null
    }
    reader.queue.cancel()
    stop?.cancel()
  }

  /** Clear readiness on a physical disconnect before that source can acknowledge a reconnect. */
  fun notReady() { synchronized(lock) { run?.ready = false } }

  suspend fun awaitStopped() {
    val previous = synchronized(lock) { run?.job }
    previous?.join()
  }

  override fun close() {
    val owned = synchronized(lock) {
      if (closed) return
      closed = true
      val previous = readers.toList(); readers.clear()
      run?.stopping = true
      previous to run?.job
    }
    owned.first.forEach { it.queue.close() }
    owned.second?.cancel()
  }
}
