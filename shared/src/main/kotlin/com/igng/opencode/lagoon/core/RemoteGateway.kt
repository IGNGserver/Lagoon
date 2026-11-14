package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Typert carrier bound to one Noise connection and its owner peer. Never retries business calls. */
internal class RemoteGateway(private val transferFrames: Boolean = false, private val rpc: suspend (String, JSONObject) -> Any?) : AutoCloseable {
  private data class StreamState(val endpoint: String, val output: Channel<Any>, val frames: Channel<JSONObject>,
    var worker: Job? = null, var failure: IOException? = null)
  private val streams = ConcurrentHashMap<String, StreamState>()
  private val transfers = Semaphore(2)
  @Volatile private var closed = false
  /** 最近一次流级失败的原因，供连接详情/日志定位；不参与控制流。 */
  @Volatile internal var lastError: String? = null
  /** 当前占用的逻辑流数量；连接医生用它判断是否接近上限。 */
  internal val activeStreamCount: Int get() = streams.size
  /** 硬上限，供连接医生展示；真正的配额由 [DshApi] 管理。 */
  internal val maxStreams: Int get() = MAX_STREAMS
  /** 按 endpoint 统计当前流占用，用于定位是 follow、后台作业还是瞬态读取占满了预算。 */
  internal fun streamCounts(): Map<String, Int> = streams.values.groupingBy { it.endpoint }.eachCount()

  /** 已经提示过"不在白名单"的端点；每个端点只提示一次，避免刷日志。 */
  private val warnedEndpoints = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * 远程插件只放行 [DshEndpointContract.REMOTE_ALLOWLIST] 里的端点，其余一律
   * `METHOD_NOT_ALLOWED`。直连能用的端点不一定远程能用，这里提前留一条可定位的日志，
   * 让"某个功能在远程模式静默失效"变成日志里能看到的名字。
   */
  private fun warnIfNotAllowlisted(endpoint: String) {
    if (DshEndpointContract.isRemoteEndpoint(endpoint)) return
    if (warnedEndpoints.add(endpoint)) {
      Diagnostics.warn("RemoteGateway", "端点 $endpoint 不在远程白名单内，Host 会以 METHOD_NOT_ALLOWED 拒绝")
    }
  }

  suspend fun call(endpoint: String, payload: JSONObject): Any? {
    if (closed) throw IOException("Remote Gateway 已关闭；没有重放请求")
    require(validEndpoint(endpoint))
    warnIfNotAllowlisted(endpoint)
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

  private suspend fun transfer(encoded: ByteArray): Any? = transfers.withPermit {
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
    if (bytes !in 1..MAX_RESPONSE) throw IOException("Remote 响应 $bytes 字节，超过本机 $MAX_RESPONSE 字节上限")
    if (descriptor.optString("transferId") != id || total != (bytes + CHUNK - 1) / CHUNK)
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
    warnIfNotAllowlisted(endpoint)
    val id = UUID.randomUUID().toString()
    val stream = StreamState(endpoint, Channel(capacity = 256), Channel(capacity = 256))
    synchronized(streams) {
      if (closed) throw IOException("Remote Gateway 已关闭")
      if (streams.size >= MAX_STREAMS) throw RemoteStreamLimitException("Remote 流数量超过上限（$MAX_STREAMS）")
      streams[id] = stream
    }
    stream.worker = SharedHttp.ioScope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        for (frame in stream.frames) {
          val value: Any = (if (frame.has("transfer")) readStreamTransfer(frame.getJSONObject("transfer"), id)
            else frame.opt("value") ?: JSONObject.NULL) ?: JSONObject.NULL
          stream.output.send(value)
        }
        // 坏帧不会切断事件通道：先把已排队的帧交给消费者，再以记录下来的原因收尾。
        stream.output.close(stream.failure)
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

  /**
   * 一条流事件。事件通道同时承载所有 RPC 的响应，所以这里**不能**抛异常：一个坏帧只会让
   * 它所属的那条流失败并记录原因，其他 RPC 与流照常工作。协议漂移时用户看到的是“这条会话读不到”，
   * 而不是“整台主机全部功能消失”。
   */
  fun event(payload: JSONObject) {
    val name = payload.opt("event") as? String
    if (name != "harness.remote.frame" && name != "harness.remote.stream.closed") return
    val data = payload.optJSONObject("data") ?: run { lastError = "流事件缺少 data"; return }
    val id = (data.opt("streamId") as? String) ?: run { lastError = "流事件缺少 streamId"; return }
    val stream = streams[id] ?: return
    if (name == "harness.remote.stream.closed") {
      val failure = if (data.optString("reason") == "failed") IOException(
        "Remote 流失败：${data.optJSONObject("failure")?.optString("message").orEmpty().ifBlank { "主机未给出原因" }}")
      else null
      stream.frames.close(failure); stream.output.close(failure); stream.worker?.cancel()
      if (failure != null) lastError = failure.message
      return
    }
    val hasValue = data.has("value")
    val hasTransfer = data.has("transfer")
    val problem = when {
      data.opt("hasValue") != true -> "流帧缺少 hasValue"
      hasValue && hasTransfer -> "流帧同时带了 value 与 transfer"
      hasTransfer && !transferFrames -> "未协商 transferFrames 却收到分片帧"
      hasTransfer && data.optJSONObject("transfer")?.optString("kind") != "chunked" -> "流分片描述无效"
      else -> null
    }
    if (problem != null) {
      failStream(id, stream, IOException(problem))
      return
    }
    if (stream.frames.trySend(data).isFailure) failStream(id, stream, IOException("Remote 流消费者过慢"))
  }

  private fun failStream(id: String, stream: StreamState, error: IOException) {
    lastError = error.message
    streams.remove(id, stream)
    stream.failure = error
    // 不带 cause 关闭帧队列：worker 会先送完已缓冲的帧，再用 failure 结束输出。
    stream.frames.close()
  }

  internal suspend fun closeStream(id: String) {
    val stream = streams.remove(id) ?: return
    // Let the worker unwind on channel closure; cancelling it inside a transfer RPC would close
    // the whole relay (RemoteRpc treats cancellation as an unknown-delivery failure).
    stream.frames.close(); stream.output.close()
    if (!closed) withContext(NonCancellable) {
      try { withTimeout(5_000) { rpc("harness.remote.stream.close", JSONObject().put("streamId", id)) } }
      catch (_: CancellationException) { } catch (_: Exception) { }
    }
  }

  /**
   * 取消路径的同步释放：先从 [streams] 移除，再异步补发 `stream.close`。
   * `RemoteBackend.StreamSocket.cancel()` 不能等协程调度，否则被淘汰的 follow / 一次性的
   * `job/list` 还会占着槽位，新流打开时就会撞上上限。发送失败不影响本地已释放的事实。
   */
  internal fun closeStreamNow(id: String) {
    val stream = streams.remove(id) ?: return
    // Close both queues and let the worker unwind on its own. Do not cancel it while it may be
    // inside a transfer RPC: RemoteRpc treats cancellation as an unknown-delivery failure and
    // would tear down the whole relay, turning one cancelled stream into a connection outage.
    stream.frames.close(); stream.output.close()
    if (closed) return
    SharedHttp.ioScope.launch {
      try { withTimeout(5_000) { rpc("harness.remote.stream.close", JSONObject().put("streamId", id)) } }
      catch (_: CancellationException) { } catch (_: Exception) { }
    }
  }

  override fun close() {
    synchronized(streams) {
      closed = true
      // 主动关闭不算故障：让消费者正常结束，而不是收到一个“连接已关闭”的异常。
      streams.values.forEach { it.frames.close(); it.output.close(); it.worker?.cancel() }
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
    /**
     * 单条 Host 连接允许同时存在的逻辑流上限，对齐官方 `ds-harness-remote` v0.6.5+ Host 每对等方
     * 16 条流的硬限制；正常预算为 1 条 `$events` + 1 条 `workspace/follow` + 最多
     * [DshApi.REMOTE_WATCH_LIMIT]（8）条 `session/follow` + 最多 [DshApi.JOB_FOLLOW_CONCURRENCY]（2）条
     * `job/follow` + 最多 2 条 `oneShotStreams` = 14 <= 16。
     */
    const val MAX_STREAMS = 16
    const val DIRECT_LIMIT = 2 * 1024 * 1024
    const val MAX_TRANSFER = 288 * 1024 * 1024
    /** Host 允许 288 MiB 的分块响应；设备侧仍留出内存余量，超过就明确报错而不是装作“描述无效”。 */
    const val MAX_RESPONSE = 64 * 1024 * 1024
    const val CHUNK = 512 * 1024
    /**
     * 只读端点：`RESPONSE_TOO_LARGE` 时允许走一次分块路径（`direct()`）。写端点重放会改变主机状态，
     * 因此绝不在这里。表里只保留**本应用真正会调用**的端点；不加未使用的名字——它们会让
     * "换个端点重试"看起来比实际更安全。
     */
    val READS = setOf(
      "agentPresets/list",
      "agentPresets/read",
      "commands/list",
      "credentials/describe",
      "directoryPicker/list",
      "fileReferences/list",
      "job/list",
      "llm/listConfigurableProviders",
      "llm/listProviders",
      "messageFeedback/list",
      "officeToPdf/generation",
      "permissionPresets/catalog",
      "pluginInventory/list",
      "pluginManager/listBundles",
      "pluginManager/listPlugins",
      "pluginManager/listVersions",
      "pluginManager/readBundleDetail",
      "schedule/catalog",
      "schedule/deliveryLimits",
      "schedule/history",
      "schedule/list",
      "session/attachment",
      "session/canOpenWorkspacePath",
      "session/events",
      "session/list",
      "session/modelCatalog",
      "session/page",
      "session/projections",
      "session/search",
      "session/workspacePathApplications",
      "sessionReferenceResolver/candidates",
      "settings/describe",
      "skills/list",
      "subagents/list",
      "workspaceFiles/changes",
      "workspaceFiles/list",
      "workspaceFiles/read",
      "workspaceFiles/readAll",
      "workspaceFiles/readBytes",
      "workspaceFiles/readRelated",
      "workspaceFiles/stat"
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
  /** 同步释放本地槽位；用于 `WebSocket.cancel()` 这类不等待挂起的路径。 */
  fun closeNow() = gateway.closeStreamNow(id)
}

internal class RemoteGatewayException(val code: String, message: String, val details: JSONObject = JSONObject()) : IOException(message.take(512))

/** The local logical-stream budget is full; callers may evict a stale follow and retry once. */
internal class RemoteStreamLimitException(message: String) : IOException(message)
