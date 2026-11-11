package com.igng.opencode.lagoon.core

import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/** Explicit, owner-scoped direct DSH acquisition. Raw DshApi constructors remain independent. */
class DshClientRegistry internal constructor(
  private val factory: (ServerProfile, ServerCredentials, Long, (String) -> Unit) -> HarnessClient
) : AutoCloseable {
  constructor() : this({ profile, credentials, timeout, cookie -> DshApi(profile, credentials, timeout, cookie) })
  private val lock = Any()
  private val entries = mutableMapOf<String, Entry>()
  private val retiring = mutableMapOf<String, CompletableDeferred<Unit>>()
  private var closed = false

  private class Entry(val key: String, val owner: HarnessClient, val predecessor: Deferred<Unit>?) {
    val clients = linkedSetOf<Access>()
    val shutdown = CompletableDeferred<Unit>()
    val lifecycle = Any()
    val interests = DshClientInterests(
      apply = { snapshot -> (owner as? DshApi)?.manageLeaseInterests(snapshot) ?: owner.setContentWatching(snapshot.content) },
      present = owner::questionPresentation)
  }

  /** No network work occurs during acquisition. Every returned surface is guarded after close. */
  fun acquire(profile: ServerProfile, credentials: ServerCredentials, callTimeoutSeconds: Long = 60,
    onCookie: (String) -> Unit = {}): HarnessClient {
    require(profile.kind == ServerKind.DSH && profile.sshHostId == null && profile.remoteHostId == null) { "共享 DSH registry 仅接受已确认的 direct 原生路由" }
    check(profile.effectiveEnabled) { "连接或父级已停用" }
    require(callTimeoutSeconds > 0)
    val key = key(profile, credentials, callTimeoutSeconds)
    val client = synchronized(lock) {
      check(!closed) { "DSH client registry 已关闭" }
      var entry = entries[key]
      if (entry == null) {
        // Constructor is local only. The callback is routed by exact Entry identity after creation.
        val target = java.util.concurrent.atomic.AtomicReference<Entry>()
        val owner = factory(profile, credentials, callTimeoutSeconds) { cookie -> target.get()?.let { publishCookie(it, cookie) } }
        entry = Entry(key, owner, retiring[key]); target.set(entry); entries[key] = entry
      }
      check(entry.clients.size < 64) { "共享 DSH 调用者超过上限" }
      Access(this, entry, onCookie).also { entry.clients += it }
    }
    synchronized(client.entry.lifecycle) {
      if (!client.closed.get()) client.entry.interests.add(client)
    }
    return GuardedHarnessClient(client)
  }

  private fun publishCookie(entry: Entry, cookie: String) {
    val clients = synchronized(lock) {
      if (entries[entry.key] !== entry || closed) emptyList() else entry.clients.toList()
    }
    clients.forEach { it.cookie(cookie) }
  }

  private fun release(client: Access): Boolean {
    val entry = client.entry
    val last = synchronized(lock) {
      entry.clients.remove(client)
      if (entry.clients.isEmpty() && entries[entry.key] === entry) {
        entries.remove(entry.key)
        retiring[entry.key] = entry.shutdown
        true
      } else false
    }
    synchronized(entry.lifecycle) { entry.interests.remove(client) }
    if (last) retire(entry)
    return last
  }

  private fun retire(entry: Entry) {
    // Keep the entry out of lookup before beginning bounded question handoff. A successor may be
    // acquired now, but its suspending business operations wait for this owner's exact shutdown.
    try { entry.owner.close() }
    catch (error: Throwable) { entry.shutdown.completeExceptionally(error) }
    SharedHttp.ioScope.launch {
      var confirmed = false
      try {
        entry.predecessor?.await()
        entry.owner.awaitClosed()
        confirmed = entry.shutdown.complete(Unit)
      } catch (error: Throwable) { entry.shutdown.completeExceptionally(error) }
      finally { synchronized(lock) {
        // Failed cleanup is not proof that the old API Peer released authority. Keep the failed
        // predecessor as a fail-closed fence instead of allowing a later acquire to bypass it.
        if (confirmed && retiring[entry.key] === entry.shutdown)
          retiring.remove(entry.key)
      } }
    }
  }

  override fun close() {
    val clients = synchronized(lock) {
      if (closed) return
      closed = true
      entries.values.flatMap { it.clients.toList() }
    }
    clients.forEach { it.close() }
  }

  private class Access(val registry: DshClientRegistry, val entry: Entry,
    private val onCookie: (String) -> Unit) : HarnessLeaseAccess {
    val closed = AtomicBoolean(false)
    private val callbackGate = Any()
    private val resourcesGate = Any()
    private val attachments = mutableSetOf<TerminalAttachment>()
    private val operations = mutableSetOf<Job>()
    private val released = CompletableDeferred<Unit>()
    @Volatile private var last = false
    override val isClosed: Boolean get() = closed.get() || entry.owner.isClosed
    private fun checkOpen() { check(!isClosed) { "DSH client lease 已关闭" } }

    fun cookie(cookie: String) = callback { runCatching { onCookie(cookie) } }
    override fun callback(block: () -> Unit) = synchronized(callbackGate) {
      if (!isClosed) block()
    }
    override fun <T> use(block: (HarnessClient) -> T): T = synchronized(callbackGate) {
      checkOpen()
      block(entry.owner).also { checkOpen() }
    }
    override suspend fun <T> useSuspending(block: suspend (HarnessClient) -> T): T = coroutineScope {
      checkOpen()
      val operation = currentCoroutineContext().job
      synchronized(resourcesGate) { checkOpen(); operations += operation }
      try {
        entry.predecessor?.await()
        checkOpen()
        var acquired: TerminalAttachment? = null
        try {
          val value = withContext(DshLeaseContext { session ->
            synchronized(entry.lifecycle) { checkOpen(); entry.interests.read(this@Access, session) }
          }) { block(entry.owner).also { if (it is TerminalAttachment) acquired = it } }
          currentCoroutineContext().ensureActive(); checkOpen()
          @Suppress("UNCHECKED_CAST")
          if (value is TerminalAttachment) wrap(value) as T else value
        } catch (error: Throwable) { acquired?.close(); throw error }
      } finally { synchronized(resourcesGate) { operations.remove(operation) } }
    }
    override fun <T> stream(block: (HarnessClient) -> Flow<T>): Flow<T> {
      checkOpen()
      return channelFlow {
        useSuspending { owner -> block(owner).collect { value -> checkOpen(); send(value) } }
      }
    }

    private fun wrap(raw: TerminalAttachment): TerminalAttachment {
      val handle = object : TerminalAttachment {
        private val detached = AtomicBoolean(false)
        @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
        private val guardedState = object : StateFlow<TerminalStreamState> {
          override val value: TerminalStreamState get() = this@Access.use { raw.state.value }
          override val replayCache: List<TerminalStreamState> get() = this@Access.use { raw.state.replayCache }
          override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<TerminalStreamState>): Nothing {
            stream { raw.state }.collect(collector)
            throw CancellationException("终端 lease 状态订阅已关闭")
          }
        }
        override val state: StateFlow<TerminalStreamState> get() { checkOpen(); return guardedState }
        override fun sendText(text: String): Boolean = this@Access.use { raw.sendText(text) }
        override fun resize(cols: Int, rows: Int): Boolean = this@Access.use { raw.resize(cols, rows) }
        override fun close() { if (detached.compareAndSet(false, true)) { synchronized(resourcesGate) { attachments.remove(this) }; raw.close() } }
      }
      synchronized(resourcesGate) { checkOpen(); attachments += handle }
      return handle
    }

    override fun setContentWatching(enabled: Boolean) = synchronized(entry.lifecycle) {
      checkOpen(); entry.interests.setContent(this, enabled)
    }
    override fun questionPresentation(sessionId: String?, foreground: Boolean) = synchronized(entry.lifecycle) {
      checkOpen(); entry.interests.presentation(this, sessionId, foreground)
    }
    private fun present(action: () -> Unit) = synchronized(entry.lifecycle) {
      checkOpen(); if (entry.interests.canPresent(this)) action()
    }
    override fun questionFocus(id: String, focused: Boolean) = present { entry.owner.questionFocus(id, focused) }
    override fun questionEngage(id: String) = present { entry.owner.questionEngage(id) }
    override fun questionTakeTime(id: String) = present { entry.owner.questionTakeTime(id) }

    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      // Fence and drain only this lease's callbacks/operations/attachments, never its siblings.
      synchronized(callbackGate) { }
      val owned = synchronized(resourcesGate) { operations.toList() to attachments.toList().also { attachments.clear() } }
      owned.first.forEach { it.cancel(CancellationException("DSH client lease 已关闭；未知 mutation 不会重放")) }
      owned.second.forEach { it.close() }
      last = registry.release(this)
      released.complete(Unit)
    }
    override suspend fun awaitClosed() {
      released.await()
      val pending = synchronized(resourcesGate) { operations.toList() }
      pending.joinAll()
      if (last) entry.shutdown.await()
    }
  }

  private fun key(profile: ServerProfile, credentials: ServerCredentials, timeout: Long): String {
    // Length-safe JSON tuple; plaintext credential material never survives in the map key/logs.
    val tuple = JSONArray(listOf(profile.id, profile.url, profile.kind.wire, profile.username,
      profile.allowCleartext, profile.dshAuth.name, profile.sshHostId, profile.sshServicePath,
      profile.remoteHostId, profile.remoteHostFingerprint, profile.remoteServiceId,
      timeout, credentials.username, credentials.password, credentials.cookie)).toString().toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(tuple).joinToString("") { "%02x".format(it.toInt() and 255) }
  }

  companion object {
    /** Optional process-wide acquisition; raw constructors and custom transport owners are unchanged. */
    val shared: DshClientRegistry by lazy { DshClientRegistry() }
  }
}
