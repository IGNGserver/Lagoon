package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronous protocol construction with one asynchronous, route-bound service admission. A failed
 * admission is not retried by this wrapper and a captured service generation is never switched under
 * a pending operation. Rebuild the client explicitly to select a replacement generation.
 */
internal class LazyNativeHttpTransport(
  private val load: suspend () -> NativeHttpTransport,
  private val releaseLease: () -> Unit = {}
) : NativeHttpTransport {
  override val endpoint = NativeHttpEndpoint(RemoteNativeServices.VIRTUAL_BASE_URL)
  private val gate = Any()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val stopped = AtomicBoolean(false)
  private var delegate: NativeHttpTransport? = null
  private val cancelled = mutableSetOf<Long>()
  private val calls = mutableMapOf<Job, Long>()
  private val handles = mutableSetOf<Handle>()
  private val admission = scope.async(start = CoroutineStart.LAZY) {
    val next = load()
    val deniedGenerations = synchronized(gate) {
      if (stopped.get()) null else cancelled.toList().also { delegate = next }
    }
    if (deniedGenerations == null) {
      next.close()
      throw CancellationException("Native service binding closed")
    }
    // Delegate hooks can synchronously invoke callbacks; never call them under the owner gate.
    deniedGenerations.forEach(next::cancelGeneration)
    next
  }

  private fun requireActive(generation: Long) {
    if (stopped.get() || generation != 0L && generation in cancelled)
      throw NativeHttpGenerationCancelledException(generation)
  }

  override suspend fun request(method: String, relativePath: String, headers: Map<String, String>, body: ByteArray?,
    maxResponseBytes: Long, generation: Long): NativeHttpResponse {
    endpoint.resolve(relativePath)
    requireNativeHttpHeaders(headers)
    requireNativeHttpResponseBudget(maxResponseBytes)
    val task = synchronized(gate) {
      requireActive(generation)
      scope.async(start = CoroutineStart.LAZY) {
        val transport = admission.await()
        synchronized(gate) { requireActive(generation) }
        transport.request(method, relativePath, headers, body, maxResponseBytes, generation)
      }.also { calls[it] = generation }
    }
    try { task.start(); return task.await() }
    finally { synchronized(gate) { calls.remove(task) }; task.cancel() }
  }

  override fun openEventStream(relativePath: String, headers: Map<String, String>, generation: Long,
    callback: NativeHttpEventStreamCallback): NativeHttpEventStream = open(relativePath, headers, generation, callback, null)

  override fun openWebSocket(relativePath: String, headers: Map<String, String>, generation: Long,
    listener: NativeWebSocketListener): NativeWebSocketChannel = open(relativePath, headers, generation, null, listener)

  private fun open(path: String, headers: Map<String, String>, generation: Long,
    callback: NativeHttpEventStreamCallback?, listener: NativeWebSocketListener?): Handle {
    endpoint.resolve(path)
    requireNativeHttpHeaders(headers)
    val handle = Handle(generation, callback, listener)
    synchronized(gate) {
      requireActive(generation)
      check(handles.size < 16) { "原生服务流超过本机配额" }
      handles += handle
    }
    handle.job = scope.launch {
      try {
        val transport = admission.await()
        synchronized(gate) { requireActive(generation) }
        if (handle.done.get()) return@launch
        if (callback != null) {
          val stream = transport.openEventStream(path, headers, generation, object : NativeHttpEventStreamCallback {
            override fun onOpen() { handle.deliver { callback.onOpen() } }
            override fun onEvent(event: NativeHttpEvent) { handle.deliver { callback.onEvent(event) } }
            override fun onFailure(error: Throwable) { handle.fail(error) }
            override fun onClosed() { handle.deliver { callback.onClosed() }; handle.cancel() }
          })
          synchronized(handle.lock) { if (handle.done.get()) stream.cancel() else handle.eventStream = stream }
        } else {
          val socket = transport.openWebSocket(path, headers, generation, object : NativeWebSocketListener {
            override fun onOpen(channel: NativeWebSocketChannel) {
              synchronized(handle.lock) { if (!handle.done.get()) handle.socket = channel }
              handle.deliver { listener!!.onOpen(handle) }
            }
            override fun onText(text: String) { handle.deliver { listener!!.onText(text) } }
            override fun onBytes(bytes: ByteArray) { handle.deliver { listener!!.onBytes(bytes) } }
            override fun onClosing(code: Int, reason: String?) { handle.deliver { listener!!.onClosing(code, reason) } }
            override fun onClosed(code: Int, reason: String?) {
              handle.deliver { listener!!.onClosed(code, reason) }; handle.cancel()
            }
            override fun onFailure(error: Throwable) { handle.fail(error) }
          })
          synchronized(handle.lock) { if (handle.done.get()) socket.cancel() else handle.socket = socket }
        }
      } catch (cancel: CancellationException) { handle.cancel(); throw cancel }
      catch (error: Throwable) { handle.fail(error) }
    }
    if (handle.done.get()) handle.job?.cancel()
    return handle
  }

  override fun cancelGeneration(generation: Long) {
    val children: List<Handle>
    val pending: List<Job>
    val active: NativeHttpTransport?
    synchronized(gate) {
      if (generation != 0L) cancelled += generation
      pending = calls.filterValues { generation == 0L || it == generation }.keys.toList()
      children = handles.filter { generation == 0L || it.generation == generation }
      active = delegate
    }
    pending.forEach { it.cancel(NativeHttpGenerationCancelledException(generation)) }
    children.forEach(Handle::cancel)
    active?.cancelGeneration(generation)
  }

  override fun close() {
    if (!stopped.compareAndSet(false, true)) return
    cancelGeneration(0)
    scope.cancel()
    synchronized(gate) { delegate }?.close()
    releaseLease()
  }

  private inner class Handle(override val generation: Long, private val callback: NativeHttpEventStreamCallback?,
    private val listener: NativeWebSocketListener?) : NativeHttpEventStream, NativeWebSocketChannel {
    val lock = Any()
    val done = AtomicBoolean(false)
    var job: Job? = null
    var eventStream: NativeHttpEventStream? = null
    var socket: NativeWebSocketChannel? = null
    private var closing = false
    fun deliver(action: () -> Unit) = synchronized(lock) {
      if (!done.get() && !stopped.get()) action()
    }
    fun fail(error: Throwable) {
      synchronized(lock) {
        if (done.get() || stopped.get()) return
        try { callback?.onFailure(error); listener?.onFailure(error) } finally { cancel() }
      }
    }
    override fun sendText(text: String): Boolean = synchronized(lock) { !done.get() && !closing && socket?.sendText(text) == true }
    override fun sendBytes(bytes: ByteArray): Boolean = synchronized(lock) { !done.get() && !closing && socket?.sendBytes(bytes) == true }
    override fun close(code: Int, reason: String?): Boolean = synchronized(lock) {
      if (done.get() || closing) false
      else { closing = true; socket?.close(code, reason) ?: false }
    }
    override fun cancel() {
      if (!done.compareAndSet(false, true)) return
      synchronized(lock) { eventStream?.cancel(); socket?.cancel() }
      job?.cancel()
      synchronized(gate) { handles.remove(this) }
    }
    override fun close() = cancel()
  }
}
