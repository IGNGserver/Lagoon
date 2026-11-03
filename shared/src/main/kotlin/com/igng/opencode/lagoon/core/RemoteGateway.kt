package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Typert carrier bound to one Noise connection and its owner peer. Never retries business calls. */
internal class RemoteGateway(private val transferFrames: Boolean = false, private val rpc: suspend (String, JSONObject) -> Any?) : AutoCloseable {
  private data class StreamState(val output: Channel<Any>, val frames: Channel<JSONObject>, var worker: Job? = null)
  private val streams = ConcurrentHashMap<String, StreamState>()
  private val transfers = Mutex()
  @Volatile private var closed = false

  suspend fun call(endpoint: String, payload: JSONObject): Any? {
    require(validEndpoint(endpoint))
    val request = JSONObject().put("endpoint", endpoint).put("payload", payload)
    if (endpoint == "\$events/result") {
      val encoded = request.toString().toByteArray(Charsets.UTF_8)
      if (encoded.size > DIRECT_LIMIT) throw IOException("Remote 事件结果超过上限")
      val result = rpc("harness.remote.call", request) as? JSONObject ?: throw IOException("Remote Gateway 响应无效")
      return when (result.opt("ok")) {
        true -> result.opt("value").takeUnless { it == JSONObject.NULL }
        false -> throw gatewayError(result)
        else -> throw IOException("Remote Gateway 响应无效")
      }
    }
    val raw = request.toString()
    if (raw.length > MAX_TRANSFER) throw IOException("Remote 传输请求超过上限")
    val encoded = raw.toByteArray(Charsets.UTF_8)
    val response = if (encoded.size > DIRECT_LIMIT) transfer(encoded) else direct(endpoint, request)
    val result = response as? JSONObject ?: throw IOException("Remote Gateway 响应无效")
    return when (result.opt("ok")) {
      true -> result.opt("value").takeUnless { it == JSONObject.NULL }
      false -> {
        throw gatewayError(result)
      }
      else -> throw IOException("Remote Gateway 响应无效")
    }
  }

  /**
   * A direct call that only returns `RESPONSE_TOO_LARGE` may use the chunked path once.
   * Host emits that code after the endpoint has already run, so anything that can change
   * session, workspace, permission, or question state is never replayed.
   */
  private suspend fun direct(endpoint: String, request: JSONObject): Any? = try {
    rpc("harness.remote.call", request)
  } catch (error: RemoteRpcException) {
    if (error.code != "RESPONSE_TOO_LARGE" || endpoint !in READS) throw error
    transfer(request.toString().toByteArray(Charsets.UTF_8))
  }

  private fun gatewayError(result: JSONObject): RemoteGatewayException {
    val error = result.optJSONObject("error") ?: throw IOException("Remote Gateway 错误无效")
    val code = (error.opt("code") as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
      ?: throw IOException("Remote Gateway 错误码无效")
    val message = (error.opt("message") as? String)?.takeIf { it.length <= 2048 } ?: "Remote Host 调用失败"
    val details = error.optJSONObject("details") ?: JSONObject()
    return RemoteGatewayException(code, message, details)
  }

  private suspend fun transfer(encoded: ByteArray): Any? = transfers.withLock {
    if (encoded.size > MAX_TRANSFER) throw IOException("Remote 传输请求超过上限")
    val id = UUID.randomUUID().toString()
    val total = (encoded.size + CHUNK - 1) / CHUNK
    var opened = false
    try {
      rpc("harness.remote.transfer.open", JSONObject().put("transferId", id).put("totalBytes", encoded.size).put("totalChunks", total))
      opened = true
      for (index in 0 until total) {
        val start = index * CHUNK
        val part = encoded.copyOfRange(start, minOf(start + CHUNK, encoded.size))
        rpc("harness.remote.transfer.chunk", JSONObject().put("transferId", id).put("index", index)
          .put("data", Base64.getEncoder().encodeToString(part)))
      }
      val result = rpc("harness.remote.transfer.commit", JSONObject().put("transferId", id)) as? JSONObject
        ?: throw IOException("Remote 传输响应无效")
      when (result.optString("kind")) {
        "inline" -> result.opt("response")
        "chunked" -> readTransfer(id, result)
        else -> throw IOException("Remote 传输响应无效")
      }
    } finally {
      if (opened) withContext(NonCancellable) {
        try { withTimeout(5_000) { rpc("harness.remote.transfer.close", JSONObject().put("transferId", id)) } } catch (_: Exception) { }
      }
    }
  }

  private suspend fun readTransfer(id: String, descriptor: JSONObject): Any? {
    val bytes = descriptor.optInt("totalBytes", -1)
    val total = descriptor.optInt("totalChunks", -1)
    if (descriptor.optString("transferId") != id || bytes !in 1..MAX_RESPONSE || total != (bytes + CHUNK - 1) / CHUNK)
      throw IOException("Remote 传输描述无效")
    val output = ByteArray(bytes)
    for (index in 0 until total) {
      val part = rpc("harness.remote.transfer.read", JSONObject().put("transferId", id).put("index", index)) as? JSONObject
        ?: throw IOException("Remote 传输分片无效")
      if (part.optString("transferId") != id || part.optInt("index", -1) != index) throw IOException("Remote 传输分片顺序无效")
      val data = try { Base64.getDecoder().decode(part.getString("data")) } catch (_: Exception) { throw IOException("Remote 传输编码无效") }
      val offset = index * CHUNK
      if (data.size != minOf(CHUNK, bytes - offset)) throw IOException("Remote 传输分片长度无效")
      data.copyInto(output, offset)
    }
    return try {
      val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
      val tokener = org.json.JSONTokener(decoder.decode(java.nio.ByteBuffer.wrap(output)).toString())
      val value = tokener.nextValue()
      if (tokener.nextClean() != '\u0000') throw IOException("Remote 传输包含多余数据")
      value
    } catch (_: Exception) { throw IOException("Remote 传输 JSON 无效") }
  }

  suspend fun open(endpoint: String, payload: JSONObject): RemoteGatewayStream {
    if (closed) throw IOException("Remote Gateway 已关闭")
    require(validEndpoint(endpoint))
    val id = UUID.randomUUID().toString()
    val stream = StreamState(Channel(capacity = 256), Channel(capacity = 256))
    synchronized(streams) {
      if (closed) throw IOException("Remote Gateway 已关闭")
      if (streams.size >= 16) throw IOException("Remote 流数量超过上限")
      streams[id] = stream
    }
    stream.worker = SharedHttp.ioScope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        for (frame in stream.frames) {
          val value: Any = (if (frame.has("transfer")) readStreamTransfer(frame.getJSONObject("transfer"), id)
            else frame.opt("value") ?: JSONObject.NULL) ?: JSONObject.NULL
          stream.output.send(value)
        }
        stream.output.close()
      } catch (cancel: CancellationException) { stream.output.close(cancel); throw cancel }
      catch (error: Exception) { stream.output.close(error) }
      finally { streams.remove(id, stream) }
    }
    try {
      val request = JSONObject().put("streamId", id).put("endpoint", endpoint).put("payload", payload)
      if (transferFrames) request.put("transferFrames", true)
      rpc("harness.remote.stream.open", request)
      return RemoteGatewayStream(id, stream.output, this)
    } catch (error: Exception) {
      streams.remove(id); stream.frames.close(error); stream.output.close(error); stream.worker?.cancel()
      // Close is idempotent and releases a stream registered before an open error. Never replay open.
      withContext(NonCancellable) {
        try { withTimeout(5_000) { rpc("harness.remote.stream.close", JSONObject().put("streamId", id)) } } catch (_: Exception) { }
      }
      throw error
    }
  }

  fun event(payload: JSONObject) {
    val name = payload.opt("event") as? String ?: throw IOException("Remote 事件无效")
    if (name != "harness.remote.frame" && name != "harness.remote.stream.closed") return
    val data = payload.optJSONObject("data") ?: throw IOException("Remote 流事件无效")
    val id = data.opt("streamId") as? String ?: throw IOException("Remote 流标识无效")
    val stream = streams[id] ?: return
    if (name == "harness.remote.frame") {
      val hasValue = data.has("value")
      val hasTransfer = data.has("transfer")
      if (data.opt("hasValue") != true || (hasValue && hasTransfer)) throw IOException("Remote 流帧内容无效")
      if (hasTransfer && (!transferFrames || data.optJSONObject("transfer")?.optString("kind") != "chunked")) throw IOException("Remote 流传输描述无效")
      if (stream.frames.trySend(data).isFailure) {
        streams.remove(id)
        stream.frames.close(IOException("Remote 流消费者过慢")); stream.output.close(IOException("Remote 流消费者过慢")); stream.worker?.cancel()
        throw IOException("Remote 流消费者过慢")
      }
    } else {
      if (data.optString("reason") == "failed") {
        stream.frames.close(IOException("Remote 流失败")); stream.output.close(IOException("Remote 流失败")); stream.worker?.cancel()
      } else stream.frames.close()
    }
  }

  internal suspend fun closeStream(id: String) {
    val stream = streams.remove(id) ?: return
    stream.frames.close(); stream.output.close(); stream.worker?.cancel()
    if (!closed) withContext(NonCancellable) {
      try { withTimeout(5_000) { rpc("harness.remote.stream.close", JSONObject().put("streamId", id)) } }
      catch (_: CancellationException) { } catch (_: Exception) { }
    }
  }

  override fun close() {
    synchronized(streams) {
      closed = true
      streams.values.forEach { it.frames.close(IOException("Remote 连接已关闭")); it.output.close(IOException("Remote 连接已关闭")); it.worker?.cancel() }
      streams.clear()
    }
  }

  private fun validEndpoint(endpoint: String) = endpoint == "\$events" ||
    endpoint.matches(Regex("[A-Za-z0-9$._-]+/[A-Za-z0-9$._-]+"))

  private suspend fun readStreamTransfer(descriptor: JSONObject, expectedStreamId: String): Any? {
    if (descriptor.optString("kind") != "chunked") throw IOException("Remote 流传输描述无效")
    val transferId = descriptor.optString("transferId").takeIf { runCatching { UUID.fromString(it) }.isSuccess }
      ?: throw IOException("Remote 流传输 ID 无效")
    try {
      val frame = readTransfer(transferId, descriptor) as? JSONObject ?: throw IOException("Remote 流分片内容无效")
      if (frame.optString("streamId") != expectedStreamId || frame.opt("hasValue") != true || frame.has("transfer")) throw IOException("Remote 流分片内容无效")
      return frame.opt("value") ?: JSONObject.NULL
    }
    finally { withContext(NonCancellable) {
      try { withTimeout(5_000) { rpc("harness.remote.transfer.close", JSONObject().put("transferId", transferId)) } } catch (_: Exception) { }
    } }
  }

  private companion object {
    const val DIRECT_LIMIT = 2 * 1024 * 1024
    const val MAX_TRANSFER = 288 * 1024 * 1024
    const val MAX_RESPONSE = 16 * 1024 * 1024
    const val CHUNK = 512 * 1024
    val READS = setOf(
      "session/list", "session/page", "session/projections", "session/modelCatalog",
      "agentPresets/list", "agentPresets/read", "commands/list", "credentials/describe",
      "directoryPicker/list", "fileReferences/list", "job/list", "llm/listConfigurableProviders",
      "permissionPresets/catalog", "workspaceFiles/list", "workspaceFiles/stat",
      "workspaceFiles/read", "workspaceFiles/readBytes", "workspaceFiles/readAll",
      "workspaceFiles/readRelated"
    )
  }
}

internal class RemoteGatewayStream internal constructor(
  private val id: String,
  private val queue: Channel<Any>,
  private val gateway: RemoteGateway
) {
  suspend fun receive(): Any = queue.receive()
  suspend fun close() = gateway.closeStream(id)
}

internal class RemoteGatewayException(val code: String, message: String, val details: JSONObject = JSONObject()) : IOException(message.take(512))
