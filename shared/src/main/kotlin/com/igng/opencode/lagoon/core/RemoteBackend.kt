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
  /** 释放共享连接（默认直接关闭会话）；见 [RemoteConnectionRegistry]。 */
  private val release: () -> Unit = { session.close() }
) : AutoCloseable {
  private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
  private val harness = DshApi(
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

  internal suspend fun probe(): RemoteProbe {
    val started = System.nanoTime()
    session.gateway().call("harness.transport.describe", JSONObject())
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
    if (closed.compareAndSet(false, true)) release()
  }

  private class StreamSocket(
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
