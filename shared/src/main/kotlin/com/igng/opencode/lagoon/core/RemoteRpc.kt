package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Only an authenticated, encrypted connection may implement this transport. */
internal interface RemoteSecureChannel {
  suspend fun send(message: ByteArray)
  fun close()
}

internal class RemoteRpcException(val code: String, val retryable: Boolean = false) : IOException("Remote RPC 失败：$code")

/** One RPC multiplexer per authenticated connection. A closed instance cannot be reused. */
internal class RemoteRpc(
  private val channel: RemoteSecureChannel,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onEvent: (JSONObject) -> Unit = {}
) {
  private val lock = Any()
  private val writeLock = Mutex()
  private val pending = mutableMapOf<String, CompletableDeferred<Any?>>()
  private var closed = false

  suspend fun call(method: String, params: JSONObject, timeoutMillis: Long = 30_000): Any? {
    require(method.matches(Regex("[a-z][a-zA-Z0-9.]{0,127}"))) { "Remote RPC 方法无效" }
    require(timeoutMillis in 1..2_147_483_647L) { "Remote RPC 超时无效" }
    val id = UUID.randomUUID().toString()
    val result = CompletableDeferred<Any?>()
    synchronized(lock) {
      if (closed) throw RemoteRpcException("TRANSPORT_CLOSED")
      if (pending.size >= MAX_PENDING) throw RemoteRpcException("TOO_MANY_PENDING")
      pending[id] = result
    }
    val envelope = JSONObject().put("v", 1).put("id", id).put("type", "rpc.request")
      .put("timestamp", clock()).put("payload", JSONObject().put("method", method).put("params", params))
      .toString().toByteArray(Charsets.UTF_8)
    try {
      if (envelope.size > RemoteSecureFragments.MAX_MESSAGE_BYTES) throw RemoteRpcException("FRAME_TOO_LARGE")
      return withTimeout(timeoutMillis) {
        writeLock.withLock {
          synchronized(lock) { if (closed) throw RemoteRpcException("TRANSPORT_CLOSED") }
          channel.send(envelope)
        }
        result.await()
      }
    } catch (_: TimeoutCancellationException) {
      // A blocked write may still be unwinding. A timed-out channel cannot safely send another
      // mutation with an unknown delivery result; close it and force a fresh handshake/resync.
      close()
      throw RemoteRpcException("RPC_TIMEOUT")
    } catch (error: CancellationException) {
      // The request may have reached the Host even if its caller was cancelled.
      close()
      throw error
    } catch (error: Exception) {
      if (error !is RemoteRpcException) close()
      throw error
    } finally {
      synchronized(lock) { pending.remove(id) }
    }
  }

  /** Called after authenticated decryption and strict wire-envelope validation by the connection. */
  fun receive(message: JSONObject) {
    if (message.opt("v") != 1 || message.optString("id").isBlank() ||
      (message.opt("timestamp") as? Number)?.let { it.toLong() > 0 && it.toDouble() == it.toLong().toDouble() } != true)
      throw IOException("Remote 消息信封无效")
    val payload = message.optJSONObject("payload") ?: throw IOException("Remote 消息内容无效")
    when (message.optString("type")) {
      "rpc.response" -> {
        val request = requestId(payload)
        if (!payload.has("result")) throw IOException("Remote 响应缺少结果")
        synchronized(lock) { pending.remove(request) }?.complete(payload.opt("result"))
      }
      "rpc.error" -> {
        val request = requestId(payload)
        val code = (payload.opt("code") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_./-]{1,128}")) }
          ?: throw IOException("Remote 错误码无效")
        synchronized(lock) { pending.remove(request) }?.completeExceptionally(RemoteRpcException(code, payload.optBoolean("retryable")))
      }
      "event" -> onEvent(payload)
      else -> throw IOException("Remote RPC 收到未知消息类型")
    }
  }

  fun close() {
    val waiting = synchronized(lock) {
      if (closed) return
      closed = true
      pending.values.toList().also { pending.clear() }
    }
    waiting.forEach { it.completeExceptionally(RemoteRpcException("TRANSPORT_CLOSED")) }
    channel.close()
  }

  private fun requestId(payload: JSONObject): String = (payload.opt("requestId") as? String)
    ?.takeIf { it.isNotBlank() && it.length <= 128 } ?: throw IOException("Remote 响应关联 ID 无效")

  private companion object { const val MAX_PENDING = 128 }
}
