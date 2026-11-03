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
class RemoteBackend internal constructor(private val session: RemoteSession) : AutoCloseable {
  private val harness = DshApi(
    ServerProfile("remote-backend", "DSH Remote", "https://remote.invalid", kind = ServerKind.DSH_REMOTE),
    ServerCredentials("dsh"), remoteBackend = this
  )

  internal val client: HarnessClient get() = harness
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

  override fun close() = session.close()

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
      job.cancel()
      finish(null)
    }
  }
}
