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
  /** 无法识别的消息类型计数（诊断用）。 */
  internal val unknownTypes = java.util.concurrent.atomic.AtomicInteger()
  private var closed = false

  suspend fun call(method: String, params: JSONObject, timeoutMillis: Long = 30_000): Any? {
    require(method.matches(Regex("[a-z][a-zA-Z0-9.]{0,127}"))) { "Remote RPC 方法无效" }
    require(timeoutMillis in 1..2_147_483_647L) { "Remote RPC 超时无效" }
    val id = UUID.randomUUID().toString()
    val envelope = JSONObject().put("v", 1).put("id", id).put("type", "rpc.request")
      .put("timestamp", clock()).put("payload", JSONObject().put("method", method).put("params", params))
      .toString().toByteArray(Charsets.UTF_8)
    return executeRpcFrame(id, envelope, timeoutMillis)
  }

  /** Sends a binary chunk RPC frame using the DSHUP\x01\x00\x00 wire prefix. */
  suspend fun callBinaryUploadChunk(params: JSONObject, chunk: ByteArray, timeoutMillis: Long = 60_000): Any? {
    require(timeoutMillis in 1..2_147_483_647L) { "Remote RPC 超时无效" }
    require(chunk.size in 1..MAX_BINARY_UPLOAD_CHUNK_BYTES) { "二进制上传块大小无效" }
    require(!params.has("data")) { "二进制上传头不能包含 data 字段" }
    val id = UUID.randomUUID().toString()
    val header = JSONObject().put("v", 1).put("id", id).put("type", "rpc.request")
      .put("timestamp", clock())
      .put("payload", JSONObject().put("method", "workspace.upload.chunk.binary").put("params", params))
      .toString().toByteArray(Charsets.UTF_8)
    require(header.size in 1..MAX_BINARY_UPLOAD_HEADER_BYTES) { "二进制上传头大小超限" }
    val frame = ByteArray(BINARY_UPLOAD_PREFIX_BYTES + header.size + chunk.size)
    BINARY_UPLOAD_MAGIC.copyInto(frame, destinationOffset = 0)
    val headerLen = header.size
    frame[8] = ((headerLen ushr 24) and 0xFF).toByte()
    frame[9] = ((headerLen ushr 16) and 0xFF).toByte()
    frame[10] = ((headerLen ushr 8) and 0xFF).toByte()
    frame[11] = (headerLen and 0xFF).toByte()
    header.copyInto(frame, destinationOffset = BINARY_UPLOAD_PREFIX_BYTES)
    chunk.copyInto(frame, destinationOffset = BINARY_UPLOAD_PREFIX_BYTES + header.size)
    return executeRpcFrame(id, frame, timeoutMillis)
  }

  private suspend fun executeRpcFrame(id: String, frame: ByteArray, timeoutMillis: Long): Any? {
    val result = CompletableDeferred<Any?>()
    synchronized(lock) {
      if (closed) throw RemoteRpcException("TRANSPORT_CLOSED")
      if (pending.size >= MAX_PENDING) throw RemoteRpcException("TOO_MANY_PENDING")
      pending[id] = result
    }
    try {
      if (frame.size > RemoteSecureFragments.MAX_MESSAGE_BYTES) throw RemoteRpcException("FRAME_TOO_LARGE")
      return withTimeout(timeoutMillis) {
        writeLock.withLock {
          synchronized(lock) { if (closed) throw RemoteRpcException("TRANSPORT_CLOSED") }
          channel.send(frame)
        }
        result.await()
      }
    } catch (_: TimeoutCancellationException) {
      // A slow or cancelled request must not tear down the whole multiplexer: the Host may still
      // answer this request later, and every other stream on the connection is healthy. The caller
      // treats the failure as "delivery unknown" and never retries a mutation blindly.
      throw RemoteRpcException("RPC_TIMEOUT")
    } catch (error: CancellationException) {
      // A caller that navigated away may already have reached the Host. Drop this request only;
      // the connection and its other streams keep working.
      throw error
    } catch (error: Exception) {
      // A transport-level failure (the channel itself) is the only case that closes the RPC layer.
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
      // 未知类型只可能是对端新增的消息；丢掉它并记录，不能因为一次协议扩展就让整条连接失效。
      else -> unknownTypes.incrementAndGet()
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

  private companion object {
    const val MAX_PENDING = 128
    val BINARY_UPLOAD_MAGIC = byteArrayOf(0x44, 0x53, 0x48, 0x55, 0x50, 1, 0, 0)
    const val BINARY_UPLOAD_PREFIX_BYTES = 12
    const val MAX_BINARY_UPLOAD_HEADER_BYTES = 4096
    const val MAX_BINARY_UPLOAD_CHUNK_BYTES = 256 * 1024
  }
}
