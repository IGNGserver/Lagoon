package com.igng.opencode.lagoon.core.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Stable binding/registration locator; never a PID or ephemeral local port. */
data class SshServiceKey(val value: String) {
  init { require(value.isNotBlank() && value.length <= 4096) }
  override fun toString(): String = value
}
enum class SshServiceLifecycleOwner { MANAGED, EXTERNAL }
enum class SshServiceState { ATTACHING, READY, FAILED, RELEASED, STALE, CLOSED }
data class SshTransportFence(val sourceRevision: Long, val generation: Long)
data class SshServiceStatus(val key: SshServiceKey, val lifecycleOwner: SshServiceLifecycleOwner,
  val fence: SshTransportFence, val state: SshServiceState, val detail: String = "")

internal interface SshRuntimeChannel : Closeable {
  val isClosed: Boolean
  fun onClosed(listener: () -> Unit) {}
}
internal fun interface SshRuntimeChannelFactory {
  fun open(key: SshServiceKey, transport: SshTransport): SshRuntimeChannel
}
internal class SshTransportSessionStaleException(val expected: SshTransportFence, val actual: SshTransportFence) :
  IllegalStateException("SSH source or transport generation changed")

/**
 * Reference ownership, not process ownership: release only closes local channels and SSH. It never
 * signals a process, including MANAGED ones. Reconnection invalidates every old lease; late release
 * cannot close a replacement child with the same key.
 */
internal class SshTransportSession(initialTransport: SshTransport, sourceRevision: Long = 0,
  initialGeneration: Long = 1) : Closeable {
  init { require(sourceRevision >= 0 && initialGeneration > 0) }
  private val lock = Any()
  private var transport: SshTransport? = initialTransport
  private var fence = SshTransportFence(sourceRevision, initialGeneration)
  private var closed = false
  private var references = 0
  private val children = LinkedHashMap<SshServiceKey, Child>()
  private val statuses = LinkedHashMap<SshServiceKey, SshServiceStatus>()

  val currentFence: SshTransportFence get() = synchronized(lock) { fence }
  val sourceRevision get() = currentFence.sourceRevision
  val generation get() = currentFence.generation
  val isClosed get() = synchronized(lock) { closed }
  val referenceCount get() = synchronized(lock) { references }
  val activeServiceKeys: Set<SshServiceKey> get() = synchronized(lock) { children.keys.toSet() }

  fun acquire(key: SshServiceKey, lifecycleOwner: SshServiceLifecycleOwner,
    expectedSourceRevision: Long = sourceRevision, factory: SshRuntimeChannelFactory): SshServiceLease = synchronized(lock) {
    check(!closed) { "SSH transport session is closed" }
    if (fence.sourceRevision != expectedSourceRevision)
      throw SshTransportSessionStaleException(SshTransportFence(expectedSourceRevision, fence.generation), fence)
    val child = children[key]?.also {
      check(it.lifecycleOwner == lifecycleOwner && it.fence == fence && it.channel?.isClosed == false) {
        "SSH runtime channel is unavailable or has different ownership"
      }
    } ?: Child(key, lifecycleOwner, fence).also { next ->
      val channel = try { factory.open(key, requireNotNull(transport)) } catch (error: Throwable) {
        statuses[key] = next.status.value.copy(state = SshServiceState.FAILED)
        throw error
      }
      check(!channel.isClosed) { "SSH runtime closed while attaching" }
      next.channel = channel
      children[key] = next
      next.status.value = next.status.value.copy(state = SshServiceState.READY)
      statuses[key] = next.status.value
      channel.onClosed {
        synchronized(lock) {
          if (children[key] === next && next.channel === channel) {
            next.status.value = next.status.value.copy(state = SshServiceState.FAILED)
            statuses[key] = next.status.value
          }
        }
      }
    }
    child.references++
    references++
    SshServiceLease(this, child)
  }

  fun status(key: SshServiceKey): SshServiceStatus? = synchronized(lock) { children[key]?.status?.value ?: statuses[key] }
  fun statuses(): List<SshServiceStatus> = synchronized(lock) { statuses.values.toList() }
  fun isCurrent(expected: SshTransportFence): Boolean = synchronized(lock) { !closed && fence == expected }
  fun requireCurrent(expected: SshTransportFence) {
    if (!isCurrent(expected)) throw SshTransportSessionStaleException(expected, currentFence)
  }

  fun advanceSourceRevision(newRevision: Long) {
    val channels = synchronized(lock) {
      check(!closed && newRevision > fence.sourceRevision)
      invalidateLocked(SshTransportFence(newRevision, Math.addExact(fence.generation, 1)))
    }
    channels.forEach { runCatching { it.close() } }
  }

  fun replaceTransport(newTransport: SshTransport, sourceRevision: Long = this.sourceRevision) {
    val old: SshTransport?
    val channels: List<SshRuntimeChannel>
    synchronized(lock) {
      check(!closed && sourceRevision >= fence.sourceRevision)
      old = transport
      channels = invalidateLocked(SshTransportFence(sourceRevision, Math.addExact(fence.generation, 1)))
      transport = newTransport
    }
    channels.forEach { runCatching { it.close() } }
    if (old !== newTransport) runCatching { old?.close() }
  }

  private fun invalidateLocked(next: SshTransportFence): List<SshRuntimeChannel> {
    fence = next
    val channels = children.values.mapNotNull { child ->
      child.status.value = child.status.value.copy(state = SshServiceState.STALE)
      statuses[child.key] = child.status.value
      child.channel.also { child.channel = null }
    }
    children.clear()
    return channels
  }

  private fun release(lease: SshServiceLease) {
    if (!lease.released.compareAndSet(false, true)) return
    var channel: SshRuntimeChannel? = null
    var connection: SshTransport? = null
    synchronized(lock) {
      val child = lease.child
      check(child.references > 0 && references > 0)
      child.references--
      references--
      if (child.references == 0) {
        channel = child.channel
        child.channel = null
        if (children[child.key] === child) {
          children.remove(child.key)
          child.status.value = child.status.value.copy(state = SshServiceState.RELEASED)
          statuses[child.key] = child.status.value
        }
      }
      if (!closed && references == 0) {
        closed = true
        connection = transport
        transport = null
      }
    }
    runCatching { channel?.close() }
    runCatching { connection?.close() }
  }

  override fun close() {
    val connection: SshTransport?
    val channels = synchronized(lock) {
      if (closed) return
      closed = true
      connection = transport
      transport = null
      children.values.mapNotNull { child ->
        child.status.value = child.status.value.copy(state = SshServiceState.CLOSED)
        statuses[child.key] = child.status.value
        child.channel.also { child.channel = null }
      }.also { children.clear() }
    }
    channels.forEach { runCatching { it.close() } }
    runCatching { connection?.close() }
  }

  internal class Child(val key: SshServiceKey, val lifecycleOwner: SshServiceLifecycleOwner, val fence: SshTransportFence) {
    val status = MutableStateFlow(SshServiceStatus(key, lifecycleOwner, fence, SshServiceState.ATTACHING))
    var channel: SshRuntimeChannel? = null
    var references = 0
  }
  internal class SshServiceLease internal constructor(private val owner: SshTransportSession, internal val child: Child) : Closeable {
    internal val released = AtomicBoolean(false)
    val serviceKey get() = child.key
    val lifecycleOwner get() = child.lifecycleOwner
    val fence get() = child.fence
    val generation get() = fence.generation
    val sourceRevision get() = fence.sourceRevision
    val status: StateFlow<SshServiceStatus> get() = child.status.asStateFlow()
    val isReleased get() = released.get()
    val isCurrent get() = !isReleased && owner.isCurrent(fence) && child.channel?.isClosed == false
    fun requireCurrent() { check(!isReleased); owner.requireCurrent(fence); check(child.channel?.isClosed == false) }
    fun updateStatus(state: SshServiceState, detail: String = "") = synchronized(owner.lock) {
      requireCurrent()
      check(owner.children[serviceKey] === child)
      child.status.value = child.status.value.copy(state = state, detail = detail)
      owner.statuses[serviceKey] = child.status.value
    }
    override fun close() = owner.release(this)
  }
}

internal typealias ServiceLease = SshTransportSession.SshServiceLease
