package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Concrete carrier fakes deliberately permit callbacks before open returns and after cancellation. */
class LazyNativeHttpTransportTest {
  @Test fun concurrentRequestsShareOneAdmissionAndKeepItsCapturedDelegate() = runBlocking {
    val first = FakeTransport()
    val replacement = FakeTransport()
    val admission = Admission(first)
    LazyNativeHttpTransport(admission::load).use { transport ->
      val requests = (1L..4L).map { generation ->
        async(start = CoroutineStart.UNDISPATCHED) {
          transport.request("POST", "request/$generation", mapOf("X-Test" to "one"),
            byteArrayOf(1, 2), maxResponseBytes = 32, generation = generation)
        }
      }
      admission.started.awaitBounded()
      assertEquals(1, admission.attempts.get())
      assertTrue(first.requests.isEmpty())
      admission.allow.complete(Unit)
      val calls = List(4) { first.nextCall() }
      // A later registry selection must not move already captured admission to another generation.
      admission.selected = replacement
      calls.forEach { call ->
        assertEquals("POST", call.method)
        assertEquals(mapOf("X-Test" to "one"), call.headers)
        assertArrayEquals(byteArrayOf(1, 2), call.body)
        assertEquals(32L, call.budget)
        call.reply("first")
      }
      requests.forEachIndexed { index, request ->
        val response = request.awaitBounded()
        assertEquals("first", response.body.decodeToString())
        assertEquals(index + 1L, response.generation)
      }
      val later = async(start = CoroutineStart.UNDISPATCHED) { transport.request("GET", "later", generation = 5) }
      first.nextCall().reply("same binding")
      assertEquals("same binding", later.awaitBounded().body.decodeToString())
      assertEquals(1, admission.attempts.get())
      assertTrue(replacement.requests.isEmpty())
    }
  }

  @Test fun cancellingOldGenerationDuringAdmissionStopsOnlyItsOwnRequestsAndHandles() = runBlocking {
    val delegate = FakeTransport()
    val admission = Admission(delegate)
    LazyNativeHttpTransport(admission::load).use { transport ->
      val old = async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { transport.request("GET", "old", generation = 7) }
      }
      val next = async(start = CoroutineStart.UNDISPATCHED) {
        transport.request("GET", "next", generation = 8)
      }
      val unscoped = async(start = CoroutineStart.UNDISPATCHED) { transport.request("GET", "unscoped") }
      val oldEvents = EventRecorder()
      transport.openEventStream("old-events", generation = 7, callback = oldEvents)
      admission.started.awaitBounded()
      transport.cancelGeneration(7)
      assertTrue(old.awaitBounded().exceptionOrNull() is CancellationException)
      assertFalse(next.isCompleted)
      assertFalse(unscoped.isCompleted)
      admission.allow.complete(Unit)
      val admitted = List(2) { delegate.nextCall() }.associateBy { it.generation }
      assertEquals(setOf(0L, 8L), admitted.keys)
      assertEquals(listOf(7L), delegate.cancelledGenerations.toList())
      assertTrue(delegate.events.isEmpty())
      assertTrue(oldEvents.notifications.isEmpty())
      assertTrue(runCatching { transport.request("GET", "stale", generation = 7) }
        .exceptionOrNull() is NativeHttpGenerationCancelledException)
      transport.cancelGeneration(8)
      admitted.getValue(8).finished.awaitBounded()
      assertTrue(runCatching { next.awaitBounded() }.exceptionOrNull() is CancellationException)
      assertTrue(admitted.getValue(8).cancelled)
      assertFalse(admitted.getValue(0).finished.isCompleted)
      admitted.getValue(0).reply("unscoped survives")
      assertEquals("unscoped survives", unscoped.awaitBounded().body.decodeToString())
      assertEquals(1, admission.attempts.get())
    }
  }

  @Test fun terminalCloseCancelsAllWorkAndReleasesDelegateAndLeaseExactlyOnce() = runBlocking {
    val delegate = FakeTransport()
    val admission = Admission(delegate).apply { allow.complete(Unit) }
    val releases = AtomicInteger()
    val transport = LazyNativeHttpTransport(admission::load) { releases.incrementAndGet() }
    try {
      val pending = listOf(0L, 1L, 2L).map { generation ->
        async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { transport.request("GET", "pending/$generation", generation = generation) }
        }
      }
      val calls = List(3) { delegate.nextCall() }
      val events = EventRecorder()
      val stream = transport.openEventStream("events", generation = 3, callback = events)
      val nativeStream = delegate.nextEvent()
      val socketEvents = SocketRecorder()
      val socket = transport.openWebSocket("socket", generation = 4, listener = socketEvents)
      val nativeSocket = delegate.nextSocket()
      socketEvents.opened.awaitBounded()
      transport.close()
      transport.close()
      stream.cancel()
      socket.cancel()
      pending.forEach { assertTrue(it.awaitBounded().exceptionOrNull() is CancellationException) }
      calls.forEach { it.finished.awaitBounded(); assertTrue(it.cancelled) }
      assertEquals(1, delegate.closeCalls.get())
      assertEquals(1, releases.get())
      assertEquals(1, nativeStream.cancelCalls.get())
      assertEquals(1, nativeSocket.cancelEffects.get())
      nativeStream.callback.onEvent(NativeHttpEvent(data = "late", generation = 3))
      nativeStream.callback.onFailure(IOException("late"))
      nativeSocket.listener.onText("late")
      nativeSocket.listener.onFailure(IOException("late"))
      assertTrue(events.notifications.isEmpty())
      assertTrue(socketEvents.texts.isEmpty())
      assertTrue(socketEvents.failures.isEmpty())
      assertFalse(socket.sendText("closed"))
      assertTrue(runCatching { transport.request("GET", "after-close") }.exceptionOrNull() is CancellationException)
      assertTrue(runCatching { transport.openEventStream("after-close", callback = EventRecorder()) }.isFailure)
      assertTrue(runCatching { transport.openWebSocket("after-close", listener = SocketRecorder()) }.isFailure)
      assertEquals(1, admission.attempts.get())
    } finally { transport.close() }
  }

  @Test fun delegateReturnedAfterCloseIsDisposedWithoutPublishingWorkOrReleasingLeaseAgain() = runBlocking {
    val delegate = FakeTransport()
    val admission = Admission(delegate, ignoreCancellation = true)
    val releases = AtomicInteger()
    val transport = LazyNativeHttpTransport(admission::load) { releases.incrementAndGet() }
    try {
      val pending = async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { transport.request("GET", "waiting", generation = 11) }
      }
      admission.started.awaitBounded()
      transport.close()
      transport.close()
      assertTrue(pending.awaitBounded().exceptionOrNull() is CancellationException)
      assertEquals(1, releases.get())
      assertEquals(0, delegate.closeCalls.get())
      admission.allow.complete(Unit)
      delegate.closed.awaitBounded()
      assertEquals(1, delegate.closeCalls.get())
      assertEquals(1, releases.get())
      assertTrue(delegate.requests.isEmpty())
      assertTrue(runCatching { transport.request("GET", "never-admit") }.exceptionOrNull() is CancellationException)
      assertEquals(1, admission.attempts.get())
    } finally {
      admission.allow.complete(Unit)
      transport.close()
    }
  }

  @Test fun synchronousSseCallbackCanCancelBeforeNativeHandleAdoptionAndSuppressLaterCallbacks() = runBlocking {
    val delegate = FakeTransport()
    val admission = Admission(delegate)
    val observed = CopyOnWriteArrayList<String>()
    lateinit var transport: LazyNativeHttpTransport
    delegate.beforeEventReturn = { stream ->
      stream.callback.onOpen()
      stream.callback.onEvent(NativeHttpEvent(data = "first", generation = stream.generation))
      stream.callback.onEvent(NativeHttpEvent(data = "buffered", generation = stream.generation))
      stream.callback.onClosed()
    }
    transport = LazyNativeHttpTransport(admission::load)
    transport.use {
      val stream = transport.openEventStream("events", generation = 12, callback = object : NativeHttpEventStreamCallback {
        override fun onOpen() { observed += "open" }
        override fun onEvent(event: NativeHttpEvent) {
          observed += event.data
          transport.cancelGeneration(12)
        }
        override fun onClosed() { observed += "closed" }
        override fun onFailure(error: Throwable) { observed += "failed" }
      })
      admission.started.awaitBounded()
      admission.allow.complete(Unit)
      val nativeStream = delegate.nextEvent()
      nativeStream.cancelled.awaitBounded()
      stream.cancel()
      stream.cancel()
      nativeStream.callback.onEvent(NativeHttpEvent(data = "late", generation = 12))
      nativeStream.callback.onFailure(IOException("late"))
      assertEquals(listOf("open", "first"), observed.toList())
      assertEquals(1, nativeStream.cancelCalls.get())
    }
  }

  @Test fun synchronousWebSocketOnOpenReceivesTheStableOuterHandleBeforeNativeOpenReturns() = runBlocking {
    val delegate = FakeTransport()
    val admission = Admission(delegate)
    val opened = CompletableDeferred<NativeWebSocketChannel>()
    val texts = CopyOnWriteArrayList<String>()
    LazyNativeHttpTransport(admission::load).use { transport ->
      val outer = transport.openWebSocket("socket", generation = 13, listener = object : NativeWebSocketListener {
        override fun onOpen(channel: NativeWebSocketChannel) {
          assertEquals(13L, channel.generation)
          assertTrue(channel.sendText("during open"))
          assertTrue(channel.sendBytes(byteArrayOf(3, 4)))
          opened.complete(channel)
        }
        override fun onText(text: String) { texts += text }
      })
      assertFalse(outer.sendText("not admitted"))
      admission.started.awaitBounded()
      admission.allow.complete(Unit)
      assertSame(outer, opened.awaitBounded())
      val nativeSocket = delegate.nextSocket()
      assertEquals(listOf("during open"), nativeSocket.texts.toList())
      assertArrayEquals(byteArrayOf(3, 4), nativeSocket.bytes.single())
      nativeSocket.listener.onText("reply")
      assertEquals(listOf("reply"), texts.toList())
      outer.cancel()
      outer.cancel()
      nativeSocket.listener.onText("late")
      assertEquals(listOf("reply"), texts.toList())
      assertFalse(outer.sendText("cancelled"))
      assertEquals(1, nativeSocket.cancelEffects.get())
    }
  }

  @Test fun failedAdmissionIsNotRetriedAndCannotPoisonASiblingBinding() = runBlocking {
    val failedDelegate = FakeTransport()
    val failure = IOException("admission denied")
    val failedAdmission = Admission(failedDelegate, failure = failure)
    val siblingDelegate = FakeTransport()
    val siblingAdmission = Admission(siblingDelegate).apply { allow.complete(Unit) }
    val failedReleases = AtomicInteger()
    val siblingReleases = AtomicInteger()
    val failed = LazyNativeHttpTransport(failedAdmission::load) { failedReleases.incrementAndGet() }
    val sibling = LazyNativeHttpTransport(siblingAdmission::load) { siblingReleases.incrementAndGet() }
    try {
      val pending = List(2) {
        async(start = CoroutineStart.UNDISPATCHED) { runCatching { failed.request("GET", "denied") } }
      }
      failedAdmission.started.awaitBounded()
      failedAdmission.allow.complete(Unit)
      pending.forEach { assertAdmissionFailure(it.awaitBounded().exceptionOrNull()) }
      assertAdmissionFailure(runCatching { failed.request("GET", "no-retry") }.exceptionOrNull())
      val events = EventRecorder()
      failed.openEventStream("events", callback = events)
      assertAdmissionFailure(events.failed.awaitBounded())
      val socket = SocketRecorder()
      failed.openWebSocket("socket", listener = socket)
      assertAdmissionFailure(socket.failed.awaitBounded())
      val siblingRequest = async(start = CoroutineStart.UNDISPATCHED) { sibling.request("GET", "sibling", generation = 21) }
      val siblingCall = siblingDelegate.nextCall()
      failed.cancelGeneration(21)
      failed.close()
      failed.close()
      assertFalse(siblingCall.finished.isCompleted)
      assertTrue(siblingDelegate.cancelledGenerations.isEmpty())
      assertEquals(0, siblingDelegate.closeCalls.get())
      assertEquals(0, siblingReleases.get())
      siblingCall.reply("independent registry")
      assertEquals("independent registry", siblingRequest.awaitBounded().body.decodeToString())
      assertEquals(1, failedAdmission.attempts.get())
      assertEquals(1, siblingAdmission.attempts.get())
      assertEquals(1, failedReleases.get())
      assertEquals(0, failedDelegate.closeCalls.get())
    } finally {
      failed.close()
      sibling.close()
    }
    assertEquals(1, siblingReleases.get())
  }

  private class Admission(
    @Volatile var selected: FakeTransport,
    private val ignoreCancellation: Boolean = false,
    private val failure: Throwable? = null
  ) {
    val attempts = AtomicInteger()
    val started = CompletableDeferred<Unit>()
    val allow = CompletableDeferred<Unit>()
    suspend fun load(): NativeHttpTransport {
      attempts.incrementAndGet()
      val captured = selected
      started.complete(Unit)
      if (ignoreCancellation) withContext(NonCancellable) { allow.await() } else allow.await()
      failure?.let { throw it }
      return captured
    }
  }

  private class Call(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val budget: Long,
    val generation: Long
  ) {
    val response = CompletableDeferred<NativeHttpResponse>()
    val finished = CompletableDeferred<Unit>()
    @Volatile var cancelled = false
    fun reply(text: String) { response.complete(NativeHttpResponse(200, emptyMap(), text.toByteArray(), generation)) }
  }

  private class FakeTransport : NativeHttpTransport {
    override val endpoint = NativeHttpEndpoint("https://native.test")
    val requests = CopyOnWriteArrayList<Call>()
    val events = CopyOnWriteArrayList<EventHandle>()
    val sockets = CopyOnWriteArrayList<SocketHandle>()
    val cancelledGenerations = CopyOnWriteArrayList<Long>()
    val closeCalls = AtomicInteger()
    val closed = CompletableDeferred<Unit>()
    private val callsStarted = Channel<Call>(Channel.UNLIMITED)
    private val eventsStarted = Channel<EventHandle>(Channel.UNLIMITED)
    private val socketsStarted = Channel<SocketHandle>(Channel.UNLIMITED)
    var beforeEventReturn: (EventHandle) -> Unit = {}

    override suspend fun request(method: String, relativePath: String, headers: Map<String, String>, body: ByteArray?,
      maxResponseBytes: Long, generation: Long): NativeHttpResponse {
      val call = Call(method, relativePath, headers, body, maxResponseBytes, generation)
      requests += call
      callsStarted.send(call)
      try { return call.response.await() }
      finally {
        call.cancelled = currentCoroutineContext()[kotlinx.coroutines.Job]?.isCancelled == true
        call.finished.complete(Unit)
      }
    }

    override fun openEventStream(relativePath: String, headers: Map<String, String>, generation: Long,
      callback: NativeHttpEventStreamCallback): NativeHttpEventStream {
      val handle = EventHandle(generation, callback)
      beforeEventReturn(handle)
      // Publish only after synchronous callbacks: the wrapper has not adopted this handle yet.
      events += handle
      eventsStarted.trySend(handle)
      return handle
    }

    override fun openWebSocket(relativePath: String, headers: Map<String, String>, generation: Long,
      listener: NativeWebSocketListener): NativeWebSocketChannel {
      val handle = SocketHandle(generation, listener)
      listener.onOpen(handle)
      sockets += handle
      socketsStarted.trySend(handle)
      return handle
    }

    // Intentionally only record: wrapper-owned coroutine cancellation, not fake cooperation, must stop requests.
    override fun cancelGeneration(generation: Long) { cancelledGenerations += generation }
    override fun close() { closeCalls.incrementAndGet(); closed.complete(Unit) }
    suspend fun nextCall() = withTimeout(3_000) { callsStarted.receive() }
    suspend fun nextEvent() = withTimeout(3_000) { eventsStarted.receive() }
    suspend fun nextSocket() = withTimeout(3_000) { socketsStarted.receive() }
  }

  private class EventHandle(override val generation: Long, val callback: NativeHttpEventStreamCallback) : NativeHttpEventStream {
    val cancelCalls = AtomicInteger()
    val cancelled = CompletableDeferred<Unit>()
    override fun cancel() { cancelCalls.incrementAndGet(); cancelled.complete(Unit) }
  }

  private class SocketHandle(override val generation: Long, val listener: NativeWebSocketListener) : NativeWebSocketChannel {
    val cancelEffects = AtomicInteger()
    val texts = CopyOnWriteArrayList<String>()
    val bytes = CopyOnWriteArrayList<ByteArray>()
    @Volatile private var cancelled = false
    override fun sendText(text: String): Boolean {
      if (cancelled) return false
      texts += text
      return true
    }
    override fun sendBytes(bytes: ByteArray): Boolean {
      if (cancelled) return false
      this.bytes += bytes
      return true
    }
    override fun close(code: Int, reason: String?): Boolean {
      if (cancelled) return false
      cancel()
      return true
    }
    override fun cancel() { if (!cancelled) { cancelled = true; cancelEffects.incrementAndGet() } }
  }

  private class EventRecorder : NativeHttpEventStreamCallback {
    val notifications = CopyOnWriteArrayList<String>()
    val failed = CompletableDeferred<Throwable>()
    override fun onEvent(event: NativeHttpEvent) { notifications += event.data }
    override fun onFailure(error: Throwable) { notifications += "failure"; failed.complete(error) }
    override fun onClosed() { notifications += "closed" }
  }

  private class SocketRecorder : NativeWebSocketListener {
    val opened = CompletableDeferred<NativeWebSocketChannel>()
    val texts = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val failed = CompletableDeferred<Throwable>()
    override fun onOpen(channel: NativeWebSocketChannel) { opened.complete(channel) }
    override fun onText(text: String) { texts += text }
    override fun onFailure(error: Throwable) { failures += error; failed.complete(error) }
  }

  private fun assertAdmissionFailure(error: Throwable?) {
    // Coroutines may copy exceptions for stacktrace recovery; identity is not a wire contract.
    assertTrue(error is IOException)
    assertEquals("admission denied", error?.message)
  }

  private suspend fun <T> Deferred<T>.awaitBounded(): T = withTimeout(3_000) { await() }
}
