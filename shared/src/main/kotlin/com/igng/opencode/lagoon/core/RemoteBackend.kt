package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Adapts the existing Typert projection to a single authenticated Remote owner peer. */
class RemoteBackend internal constructor(
  private val session: RemoteSession,
  private val nativeProfile: ServerProfile? = null,
  /** 释放共享连接（默认直接关闭会话）；见 [RemoteConnectionRegistry]。 */
  private val release: () -> Unit = { session.close() }
) : AutoCloseable {
  private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
  private val harness: HarnessClient = if (nativeProfile?.protocol == HarnessProtocolId.ANTIGRAVITY_ACP) {
    require(nativeProfile.remoteHostId != null)
    AgyApi(LazyRemoteAgyTransport(load = { session.agyTransport() }, release = {
      if (closed.compareAndSet(false, true)) release()
    }))
  } else if (nativeProfile?.protocol == HarnessProtocolId.CODEX_APP_SERVER) {
    require(nativeProfile.remoteHostId != null)
    // The event collector may fail during construction; never dereference the not-yet-assigned
    // harness from its callback. Codex has no Remote terminal handles owned by this wrapper.
    CodexApi(LazyRemoteCodexTransport(load = { session.codexTransport() }, releaseLease = {
      if (closed.compareAndSet(false, true)) release()
    }))
  } else if (nativeProfile != null) {
    require(nativeProfile.kind == ServerKind.OPENCODE && nativeProfile.remoteHostId != null)
    val serviceId = requireNotNull(nativeProfile.remoteServiceId) { "尚未选择 Host 上的原生服务" }
    val carrier = LazyNativeHttpTransport(load = { session.nativeServiceTransport(serviceId) }, releaseLease = { close() })
    OpenCodeApi(nativeProfile.copy(url = RemoteNativeServices.VIRTUAL_BASE_URL, remoteHostId = null,
      remoteHostFingerprint = null, remoteServiceId = null), ServerCredentials(), transport = carrier)
  } else DshApi(
    ServerProfile("remote-backend", "DSH Remote", "https://remote.invalid", kind = ServerKind.DSH_REMOTE),
    ServerCredentials("dsh"), remoteBackend = this
  )

  internal val client: HarnessClient get() = harness
  internal val isClosed: Boolean get() = closed.get() || session.isClosed
  internal val hub: String get() = session.hub
  internal val account: String get() = session.accountId
  internal val hostId: String get() = session.host
  internal fun connectionStatus(): RemoteConnectionStatus = session.status.value
  internal fun credentials(): RemoteCredentialSummary = session.credentials()
  internal fun streamStats(): RemoteStreamStats? = session.streamStats()
  internal suspend fun supportsWorkspaceUpload(): Boolean = session.capabilities().supportsWorkspaceUpload
  internal fun terminalAvailable(): Boolean = !isClosed && session.terminalAvailable() &&
    (pinnedTerminalGateway?.let(session::isCurrentGateway) != false)
  internal suspend fun discoverTerminal(): Boolean = !isClosed && session.capabilities().hasTerminal
  internal fun changesAvailable(): Boolean = !isClosed && session.changesAvailable()
  internal suspend fun discoverChanges(): Boolean = !isClosed && session.capabilities().hasChanges
  internal suspend fun changesReader(): suspend (String, String, Long, Int?) -> JSONObject {
    check(!isClosed) { "Remote backend 已关闭" }
    val reader = session.changesReader()
    return { kind, id, seq, index ->
      check(!isClosed) { "Remote backend 已关闭" }
      reader(kind, id, seq, index).also { check(!isClosed) { "Remote backend 已关闭，改动结果已丢弃" } }
    }
  }
  private val terminalLock = Any()
  @Volatile private var pinnedTerminalGateway: RemoteGateway? = null
  private val terminalSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocket>()

  /** Terminal inputs never migrate to a replacement encrypted connection. Reopen the owner first. */
  private suspend fun terminalGateway(): RemoteGateway {
    check(!isClosed) { "Remote 终端连接已关闭" }
    val current = session.terminalGateway()
    synchronized(terminalLock) {
      val previous = pinnedTerminalGateway
      check(previous == null || previous === current) { "Remote 终端连接代次已变化，请重新连接并打开终端；没有重放输入" }
      check(session.isCurrentGateway(current)) { "Remote 终端连接已失效" }
      pinnedTerminalGateway = current
    }
    return current
  }
  internal suspend fun terminalCall(endpoint: String, payload: JSONObject): Any? {
    require(endpoint in setOf("terminal/environment", "terminal/shells", "terminal/list", "terminal/create", "terminal/rename", "terminal/close"))
    return terminalGateway().call(endpoint, payload)
  }
  internal suspend fun terminalOpen(endpoint: String, payload: JSONObject, onItem: (JSONObject) -> Unit,
    onClosed: (Throwable?) -> Unit): WebSocket {
    val gateway = terminalGateway()
    if (endpoint == "terminal/attach") {
      val socket = RemoteTerminalSocket.open(gateway, payload, onItem, onClosed) { terminalSockets.remove(it) }
      terminalSockets += socket
      if (isClosed || !session.isCurrentGateway(gateway)) { terminalSockets.remove(socket); socket.cancel(); throw IOException("Remote 终端打开时连接已变化") }
      return socket
    }
    require(endpoint == "terminal/retain")
    val stream = gateway.open(endpoint, payload)
    if (isClosed || !session.isCurrentGateway(gateway)) { stream.closeNow(); throw IOException("Remote 终端保留流所属连接已变化") }
    val reference = java.util.concurrent.atomic.AtomicReference<WebSocket>()
    val socket = StreamSocket(stream, onItem) { error -> reference.get()?.let { terminalSockets.remove(it) }; onClosed(error) }
    reference.set(socket); terminalSockets += socket
    if (isClosed || !session.isCurrentGateway(gateway)) { terminalSockets.remove(socket); socket.cancel(); throw IOException("Remote 终端保留流打开时连接已变化") }
    return socket
  }
  internal suspend fun nativeServiceCatalog(): NativeServiceCatalog? = session.nativeServiceCatalog()
  internal suspend fun hostServices(): RemoteHostServices = RemoteHostServices(
    dshAvailable = session.capabilities().sessionFormat != null,
    native = session.nativeServiceCatalog(),
    codexAvailable = session.capabilities().supportsWorkspace("codex", "codex.appserver.v1"),
    agyAvailable = session.capabilities().hasAgy
  )

  internal suspend fun probe(): RemoteProbe {
    val started = System.nanoTime()
    // `harness.transport.describe` 是传输级方法，直接发在加密连接上；它不是网关端点，
    // 不能包进 `harness.remote.call`（既过不了 `ns/method` 形式校验，也不在远程白名单里）。
    session.transportCall("harness.transport.describe")
    val elapsed = (System.nanoTime() - started) / 1_000_000
    return RemoteProbe(elapsed)
  }

  internal suspend fun uploadWorkspaceFile(sessionId: String, bytes: ByteArray, name: String): RemoteUploadedFile =
    session.uploadWorkspaceFile(sessionId, bytes, name)

  internal suspend fun call(endpoint: String, payload: JSONObject): Any? {
    requireHarness()
    return session.gateway().call(endpoint, payload)
  }

  internal suspend fun open(endpoint: String, payload: JSONObject, onItem: (JSONObject) -> Unit,
    onClosed: (Throwable?) -> Unit): WebSocket {
    requireHarness()
    val stream = session.gateway().open(endpoint, payload)
    return StreamSocket(stream, onItem, onClosed)
  }

  private suspend fun requireHarness() {
    if (session.capabilities().sessionFormat == null) throw IOException("Host 没有 DSH Typert 后端，不能切换到其他后端")
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      terminalSockets.toList().forEach { it.cancel() }; terminalSockets.clear()
      try { if (nativeProfile != null) harness.close() } finally { release() }
    }
  }

  internal class StreamSocket(
    private val stream: RemoteGatewayStream,
    onItem: (JSONObject) -> Unit,
    private val onClosed: (Throwable?) -> Unit
  ) : WebSocket {
    private val ended = AtomicBoolean(false)
    private val job: Job = SharedHttp.ioScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
      var failure: Throwable? = null
      try {
        while (true) {
          val value = stream.receive()
          onItem(value as? JSONObject ?: throw IOException("Remote 流帧不是对象"))
        }
      } catch (cancel: CancellationException) { throw cancel }
      catch (error: kotlinx.coroutines.channels.ClosedReceiveChannelException) { }
      catch (error: Throwable) { failure = error }
      finally {
        try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { stream.close() } } finally { finish(failure) }
      }
    }
    private fun finish(cause: Throwable?) { if (ended.compareAndSet(false, true)) onClosed(cause) }
    override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("https://remote.invalid/").build()
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean = false
    override fun send(bytes: ByteString): Boolean = false
    override fun close(code: Int, reason: String?): Boolean { cancel(); return true }
    override fun cancel() {
      // Remove the logical stream before cancelling the pump: the next open must see the freed slot
      // immediately, not after the coroutine scheduler runs.
      stream.closeNow()
      job.cancel()
      finish(null)
    }
  }
}
