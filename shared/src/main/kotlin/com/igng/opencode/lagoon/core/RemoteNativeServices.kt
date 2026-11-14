package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Host-owned locators, not URLs or credentials. Unknown protocols stay visible but unsupported. */
internal data class NativeServiceDescriptor(
  val id: String,
  val name: String,
  val protocol: String,
  val generation: String,
  val available: Boolean,
  val capabilities: Set<String>
) {
  val supported: Boolean get() = protocol == "opencode-native"
  val nativeProtocol: HarnessProtocolId? get() = if (supported) HarnessProtocolId.OPENCODE_NATIVE else null
}

internal data class NativeServiceCatalog(
  val version: Int,
  val completeness: DiscoveryCompleteness,
  val services: List<NativeServiceDescriptor>,
  val issues: List<String> = emptyList()
)

/** No arbitrary Host exception or URL is included in locally generated diagnostics. */
internal open class NativeRemoteException(val code: String, message: String) : IOException(message)
internal class NativeServiceUpgradeRequiredException : NativeRemoteException(
  "METHOD_NOT_FOUND", "Remote Host 不支持原生服务协议，请升级 Host 及远程插件"
)

/** One registry per authenticated Noise connection. Closing a child never closes this connection. */
internal class RemoteNativeServices(
  private val rpc: suspend (method: String, params: JSONObject) -> Any?,
  private val onCloseLease: () -> Unit = {}
) : Closeable {
  private val gate = Any()
  private val describeLock = Mutex()
  private val children = mutableSetOf<RemoteNativeTransport>()
  private val streams = ConcurrentHashMap<String, RemoteNativeTransport.RemoteStream>()
  private val cleanupJob = SupervisorJob()
  private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupJob)
  private val registryJob = SupervisorJob()
  private val registryScope = CoroutineScope(Dispatchers.IO + registryJob)
  @Volatile private var closed = false
  @Volatile private var catalog: NativeServiceCatalog? = null

  suspend fun describe(): NativeServiceCatalog = describeLock.withLock {
    checkOpen()
    val request = synchronized(gate) {
      checkOpen()
      registryScope.async(start = CoroutineStart.LAZY) { invoke("harness.services.describe", JSONObject()) }
    }
    val next = try { request.start(); parseCatalog(request.await()) } finally { request.cancel() }
    val obsolete = synchronized(gate) {
      checkOpen()
      catalog = next
      children.filter { child ->
        val current = next.services.firstOrNull { it.id == child.service.id }
        // A partial observation cannot authoritatively delete an unobserved service.
        current?.let { it.generation != child.service.generation || !it.available || !it.supported } == true ||
          (current == null && next.completeness == DiscoveryCompleteness.COMPLETE)
      }
    }
    obsolete.forEach { it.invalidate() }
    next
  }

  suspend fun transport(serviceId: String, generation: String): NativeHttpTransport {
    requireLocator(serviceId); requireLocator(generation)
    val observed = catalog ?: describe()
    val descriptor = observed.services.firstOrNull { it.id == serviceId }
      ?: throw nativeError("SERVICE_NOT_FOUND")
    if (descriptor.generation != generation) throw nativeError("STALE_SERVICE")
    if (!descriptor.supported) throw nativeError("UNSUPPORTED_PROTOCOL")
    if (!descriptor.available) throw nativeError("SERVICE_UNAVAILABLE")
    if ("http" !in descriptor.capabilities) throw nativeError("UNSUPPORTED_CAPABILITY")
    return synchronized(gate) {
      checkOpen()
      // A concurrent describe must not resurrect a stale descriptor.
      if (catalog?.services?.firstOrNull { it.id == serviceId } != descriptor) throw nativeError("STALE_SERVICE")
      RemoteNativeTransport(descriptor).also { children += it }
    }
  }

  /** Called on the shared RPC receiver. Never waits for callbacks, RPCs or a full child queue. */
  fun event(payload: JSONObject) {
    if (closed || payload.opt("event") != "harness.services.frame") return
    val data = payload.optJSONObject("data") ?: return
    val id = data.opt("streamId") as? String ?: return
    if (id.length > 256) return
    streams[id]?.offer(data)
  }

  override fun close() {
    val owned = synchronized(gate) {
      if (closed) return
      closed = true
      children.toList().also { children.clear() }
    }
    owned.forEach { it.close() }
    registryJob.cancel()
    // Release the one connection lease after detaching every child, never once per wrapper.
    try { onCloseLease() } finally { cleanupJob.cancel() }
  }

  private fun checkOpen() { if (closed) throw nativeError("TRANSPORT_CLOSED") }

  private suspend fun invoke(method: String, params: JSONObject): JSONObject {
    val result = try { rpc(method, params) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: NativeRemoteException) { throw error }
    catch (error: RemoteRpcException) {
      if (error.code == "METHOD_NOT_FOUND") throw NativeServiceUpgradeRequiredException()
      throw nativeError(error.code)
    }
    val value = result as? JSONObject ?: throw nativeError("INVALID_RESPONSE")
    // Accept explicit errors from native peers as well as the connection's rpc.error envelope.
    value.optJSONObject("error")?.let { throw parseError(it) }
    return value
  }

  private suspend fun release(method: String, field: String, id: String) {
    withContext(NonCancellable) {
      try { withTimeout(2_000) { invoke(method, JSONObject().put(field, id)) } }
      catch (_: Exception) { /* Releasing a handle never retries its business operation. */ }
    }
  }

  private fun closeStream(id: String) {
    cleanupScope.launch { release("harness.services.stream.close", "streamId", id) }
  }

  private fun parseCatalog(value: JSONObject): NativeServiceCatalog {
    if (strictLong(value, "version") != 1L) throw nativeError("UNSUPPORTED_VERSION")
    val completeness = when (value.opt("completeness")) {
      "complete" -> DiscoveryCompleteness.COMPLETE
      "partial" -> DiscoveryCompleteness.PARTIAL
      else -> throw nativeError("INVALID_CATALOG")
    }
    val raw = value.optJSONArray("services") ?: throw nativeError("INVALID_CATALOG")
    if (raw.length() > 64) throw nativeError("INVALID_CATALOG")
    val services = (0 until raw.length()).map { index ->
      val item = raw.optJSONObject(index) ?: throw nativeError("INVALID_CATALOG")
      val id = strictString(item, "id", 256).also(::requireLocator)
      val generation = strictString(item, "generation", 256).also(::requireLocator)
      val name = strictString(item, "name", 256)
      val protocol = strictString(item, "protocol", 64).also {
        if (!it.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) throw nativeError("INVALID_CATALOG")
      }
      val available = item.opt("available") as? Boolean ?: throw nativeError("INVALID_CATALOG")
      val caps = item.optJSONArray("capabilities") ?: throw nativeError("INVALID_CATALOG")
      if (caps.length() > 16) throw nativeError("INVALID_CATALOG")
      val capabilities = (0 until caps.length()).map { cap ->
        (caps.opt(cap) as? String)?.takeIf { it.matches(Regex("[a-z][a-z0-9.-]{0,63}")) }
          ?: throw nativeError("INVALID_CATALOG")
      }
      if (capabilities.distinct().size != capabilities.size) throw nativeError("INVALID_CATALOG")
      NativeServiceDescriptor(id, name, protocol, generation, available, capabilities.toSet())
    }
    if (services.distinctBy { it.id }.size != services.size) throw nativeError("INVALID_CATALOG")
    val rawIssues = if (!value.has("issues")) JSONArray() else
      value.optJSONArray("issues") ?: throw nativeError("INVALID_CATALOG")
    if (rawIssues.length() > 64) throw nativeError("INVALID_CATALOG")
    val issues = (0 until rawIssues.length()).map { index ->
      val issue = rawIssues.optJSONObject(index) ?: throw nativeError("INVALID_CATALOG")
      strictString(issue, "code", 128).also(::requireCode)
    }
    return NativeServiceCatalog(1, completeness, services, issues)
  }

  private inner class RemoteNativeTransport(val service: NativeServiceDescriptor) : NativeHttpTransport {
    override val endpoint = NativeHttpEndpoint(VIRTUAL_BASE_URL)
    private val lock = Any()
    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + ownerJob)
    private val operations = mutableMapOf<Job, Long>()
    private val ownedStreams = mutableSetOf<RemoteStream>()
    private val cancelledGenerations = mutableSetOf<Long>()
    @Volatile private var stopped = false
    private var stale = false

    override suspend fun request(
      method: String, relativePath: String, headers: Map<String, String>, body: ByteArray?,
      maxResponseBytes: Long, generation: Long
    ): NativeHttpResponse {
      requireNativeHttpResponseBudget(maxResponseBytes)
      val normalized = requireNativeHttpMethod(method)
      val path = checkedPath(relativePath)
      val checkedHeaders = requestHeaders(headers)
      headers.entries.firstOrNull { it.key.equals("x-opencode-ticket", true) }?.let {
        if (normalized != "POST" || it.value != "1" || !Regex("^/api/pty/[^/?]+/connect-token(?:\\?|$)").containsMatchIn(path))
          throw nativeError("INVALID_HEADERS")
      }
      if (body != null && body.size > MAX_NATIVE_HTTP_RESPONSE_BYTES) throw nativeError("REQUEST_TOO_LARGE")
      if (body != null && "binary" !in service.capabilities) throw nativeError("UNSUPPORTED_CAPABILITY")
      return operation(generation) {
        val params = identity().put("method", normalized).put("path", path)
          .put("headers", checkedHeaders).put("maxResponseBytes", maxResponseBytes)
        // The upload commit consumes its handle before executing once; unknown delivery is never
        // retried. Small bodies stay inline, and every response uses the same bounded reader.
        val response = if (body != null && body.size > CHUNK) uploadRequest(params, body) else {
          body?.let { params.put("body", Base64.getEncoder().encodeToString(it)) }
          invoke("harness.services.call", params)
        }
        val transferId = if (response.has("transferId")) strictString(response, "transferId", 256)
          .also(::requireLocator) else null
        try {
          val status = strictLong(response, "status").toInt()
          if (status !in 100..599 || strictLong(response, "status") != status.toLong()) throw nativeError("INVALID_RESPONSE")
          if (status in 300..399) throw nativeError("REDIRECT_FORBIDDEN")
          val responseHeaders = responseHeaders(response.optJSONObject("headers") ?: throw nativeError("INVALID_RESPONSE"))
          val length = responseHeaders.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.let {
            it.toLongOrNull()?.takeIf { size -> size >= 0 } ?: throw nativeError("INVALID_RESPONSE")
          }
          if (length != null && length > maxResponseBytes) throw NativeHttpResponseTooLargeException(maxResponseBytes, length)
          val bytes = if (transferId != null) {
            if (response.has("body")) throw nativeError("INVALID_RESPONSE")
            val total = strictLong(response, "totalBytes")
            if (total !in 1..MAX_NATIVE_HTTP_RESPONSE_BYTES) throw nativeError("INVALID_TRANSFER")
            if (total > maxResponseBytes) throw NativeHttpResponseTooLargeException(maxResponseBytes, total)
            readTransfer(transferId, total.toInt())
          } else {
            if (response.has("totalBytes")) throw nativeError("INVALID_RESPONSE")
            decodeBase64(response, "body", minOf(maxResponseBytes, CHUNK.toLong()).toInt())
          }
          currentCoroutineContext().ensureActive()
          NativeHttpResponse(status, responseHeaders, bytes, generation)
        } finally {
          if (transferId != null) release("harness.services.transfer.close", "transferId", transferId)
        }
      }
    }

    private suspend fun uploadRequest(params: JSONObject, body: ByteArray): JSONObject {
      val opened = invoke("harness.services.transfer.open", JSONObject(params.toString()).put("totalBytes", body.size))
      val id = strictString(opened, "transferId", 256).also(::requireLocator)
      try {
        if (strictLong(opened, "chunkBytes") != CHUNK.toLong()) throw nativeError("INVALID_TRANSFER")
        var index = 0
        var offset = 0
        while (offset < body.size) {
          currentCoroutineContext().ensureActive()
          val end = minOf(offset + CHUNK, body.size)
          val chunk = invoke("harness.services.transfer.chunk", JSONObject().put("transferId", id).put("index", index)
            .put("data", Base64.getEncoder().encodeToString(body.copyOfRange(offset, end))))
          if (chunk.opt("accepted") != true) throw nativeError("INVALID_TRANSFER")
          offset = end
          index++
        }
        return invoke("harness.services.transfer.commit", JSONObject().put("transferId", id))
      } finally { release("harness.services.transfer.close", "transferId", id) }
    }

    private suspend fun readTransfer(id: String, total: Int): ByteArray {
      val output = ByteArray(total)
      var offset = 0
      var index = 0
      var totalChunks: Int? = null
      while (offset < total) {
        val part = invoke("harness.services.transfer.read", JSONObject().put("transferId", id).put("index", index))
        if (strictLong(part, "index") != index.toLong()) throw nativeError("INVALID_TRANSFER")
        val count = strictLong(part, "totalChunks")
        // Chunks may be smaller than 512 KiB, but cannot be empty; bound the read count too.
        if (count !in 1..MAX_TRANSFER_CHUNKS.toLong() || count > total || count < (total + CHUNK - 1) / CHUNK)
          throw nativeError("INVALID_TRANSFER")
        if (totalChunks == null) totalChunks = count.toInt()
        if (count.toInt() != totalChunks || index >= count) throw nativeError("INVALID_TRANSFER")
        val data = decodeBase64(part, "data", minOf(CHUNK, total - offset))
        if (data.isEmpty()) throw nativeError("INVALID_TRANSFER")
        data.copyInto(output, offset)
        offset += data.size
        index++
        if ((offset == total) != (index == totalChunks)) throw nativeError("INVALID_TRANSFER")
      }
      return output
    }

    override fun openEventStream(
      relativePath: String, headers: Map<String, String>, generation: Long, callback: NativeHttpEventStreamCallback
    ): NativeHttpEventStream = openStream("sse", relativePath, headers, generation, callback, null)

    override fun openWebSocket(
      relativePath: String, headers: Map<String, String>, generation: Long, listener: NativeWebSocketListener
    ): NativeWebSocketChannel = openStream("websocket", relativePath, headers, generation, null, listener)

    private fun openStream(
      kind: String, relativePath: String, headers: Map<String, String>, generation: Long,
      callback: NativeHttpEventStreamCallback?, listener: NativeWebSocketListener?
    ): RemoteStream {
      if (kind !in service.capabilities) throw nativeError("UNSUPPORTED_CAPABILITY")
      val path = checkedPath(relativePath)
      val checkedHeaders = requestHeaders(headers)
      if (headers.keys.any { it.equals("x-opencode-ticket", true) }) throw nativeError("INVALID_HEADERS")
      val stream = RemoteStream(UUID.randomUUID().toString(), kind, generation, callback, listener)
      synchronized(lock) {
        requireActive(generation)
        synchronized(gate) {
          checkOpen()
          if (streams.size >= MAX_STREAMS) throw nativeError("STREAM_LIMIT")
          // Registration MUST precede the open RPC: the Host may emit open immediately.
          if (streams.putIfAbsent(stream.id, stream) != null) throw nativeError("STREAM_ID_COLLISION")
        }
        ownedStreams += stream
        stream.start(path, checkedHeaders)
      }
      return stream
    }

    override fun cancelGeneration(generation: Long) {
      val (jobs, handles) = synchronized(lock) {
        if (generation != 0L) cancelledGenerations += generation
        operations.filterValues { generation == 0L || it == generation }.keys.toList() to
          ownedStreams.filter { generation == 0L || it.generation == generation }
      }
      jobs.forEach { it.cancel(NativeHttpGenerationCancelledException(generation)) }
      handles.forEach { it.stop() }
    }

    fun invalidate() { synchronized(lock) { stale = true }; close() }

    override fun close() {
      val handles = synchronized(lock) {
        if (stopped) return
        stopped = true
        ownedStreams.toList()
      }
      ownerJob.cancel()
      handles.forEach { it.stop() }
      synchronized(gate) { children.remove(this) }
    }

    private fun requireActive(generation: Long) = synchronized(lock) {
      if (stale) throw nativeError("STALE_SERVICE")
      if (closed || stopped) throw nativeError("TRANSPORT_CLOSED")
      if (generation != 0L && generation in cancelledGenerations) throw NativeHttpGenerationCancelledException(generation)
    }

    private suspend fun <T> operation(generation: Long, action: suspend () -> T): T = coroutineScope {
      val task = synchronized(lock) {
        requireActive(generation)
        scope.async(start = CoroutineStart.LAZY) { action() }.also { operations[it] = generation }
      }
      try { task.start(); task.await() }
      finally { synchronized(lock) { operations.remove(task) }; task.cancel() }
    }

    private fun checkedPath(path: String): String {
      if (path.length > 16_384) throw nativeError("INVALID_PATH")
      endpoint.resolve(path)
      return if (path.startsWith('/')) path else "/$path"
    }

    private fun identity() = JSONObject().put("serviceId", service.id).put("generation", service.generation)

    /** One actor per logical stream; a slow callback never blocks the connection's receiver. */
    inner class RemoteStream(
      val id: String,
      val kind: String,
      override val generation: Long,
      private val callback: NativeHttpEventStreamCallback?,
      private val listener: NativeWebSocketListener?
    ) : NativeHttpEventStream, NativeWebSocketChannel {
      private val callbackGate = Any()
      private val frames = Channel<JSONObject>(STREAM_QUEUE)
      private val sends = Channel<JSONObject>(SEND_QUEUE)
      private val terminal = AtomicBoolean(false)
      private val failed = AtomicBoolean(false)
      private val opened = CompletableDeferred<Unit>()
      private var reader: Job? = null
      private var opener: Job? = null
      private var sender: Job? = null
      private var nextSeq = 0L
      private var receivedOpen = false
      private var sentClose = false

      fun start(path: String, headers: JSONObject) {
        reader = scope.launch {
          try { for (frame in frames) process(frame) }
          catch (cancel: CancellationException) { throw cancel }
          catch (error: Throwable) { fail(error) }
          finally { stop() }
        }
        sender = scope.launch {
          try {
            opened.await()
            for (frame in sends) {
              if (terminal.get()) break
              val result = invoke("harness.services.stream.send", frame)
              if (result.opt("accepted") != true) throw nativeError("SEND_REJECTED")
            }
          } catch (cancel: CancellationException) { throw cancel }
          catch (error: Throwable) { fail(error) }
        }
        opener = scope.launch {
          try {
            val result = invoke("harness.services.stream.open", identity().put("streamId", id)
              .put("kind", kind).put("path", path).put("headers", headers))
            if (result.opt("streamId") != id) throw nativeError("INVALID_STREAM")
            opened.complete(Unit)
          } catch (cancel: CancellationException) { throw cancel }
          catch (error: Throwable) { fail(error) }
          finally { if (terminal.get()) release("harness.services.stream.close", "streamId", id) }
        }
      }

      fun offer(frame: JSONObject) {
        if (terminal.get() || failed.get()) return
        if (frame.length() > 10) { failAsync(nativeError("FRAME_TOO_LARGE")); return }
        val keys = frame.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          val value = frame.opt(key)
          val limit = if (key == "data") MAX_BASE64_CHARS else 1024
          if (key.length > 32 || value !is String && value !is Number ||
            value is String && value.length > limit) {
            failAsync(nativeError("FRAME_TOO_LARGE")); return
          }
        }
        if (frames.trySend(frame).isFailure) failAsync(nativeError("STREAM_BACKPRESSURE"))
      }

      private fun failAsync(error: Throwable) {
        if (terminal.get() || !failed.compareAndSet(false, true)) return
        scope.launch { fail(error) }
      }

      private fun fail(error: Throwable) {
        synchronized(callbackGate) {
          if (terminal.get()) return
          try { callback?.onFailure(error); listener?.onFailure(error) }
          catch (_: Throwable) { /* A user callback cannot tear down siblings. */ }
          finally { stop() }
        }
      }

      private fun process(frame: JSONObject) = synchronized(callbackGate) {
        val seq = strictLong(frame, "seq")
        if (seq != nextSeq || seq < 0) throw nativeError("STREAM_SEQUENCE_GAP")
        nextSeq++
        val type = strictString(frame, "type", 16)
        if (terminal.get()) return@synchronized
        requireActive(generation)
        if (!receivedOpen && type != "open" && type != "error") throw nativeError("INVALID_STREAM")
        when (type) {
          "open" -> {
            if (receivedOpen) throw nativeError("INVALID_STREAM")
            receivedOpen = true
            callback?.onOpen(); listener?.onOpen(this)
          }
          "event" -> {
            if (kind != "sse") throw nativeError("INVALID_STREAM")
            val data = boundedText(frame, "data", CHUNK)
            callback?.onEvent(NativeHttpEvent(optionalString(frame, "id", 1024),
              optionalString(frame, "event", 1024), data, generation))
          }
          "text" -> {
            if (kind != "websocket") throw nativeError("INVALID_STREAM")
            listener?.onText(boundedText(frame, "data", CHUNK))
          }
          "bytes" -> {
            if (kind != "websocket" || "binary" !in service.capabilities) throw nativeError("UNSUPPORTED_CAPABILITY")
            listener?.onBytes(decodeBase64(frame, "data", CHUNK))
          }
          "closing" -> {
            if (kind != "websocket") throw nativeError("INVALID_STREAM")
            listener?.onClosing(closeCode(frame), optionalString(frame, "reason", 123))
          }
          "closed" -> {
            try {
              callback?.onClosed()
              listener?.onClosed(closeCode(frame), optionalString(frame, "reason", 123))
            } finally { stop() }
          }
          "error" -> throw parseError(frame)
          else -> throw nativeError("INVALID_STREAM")
        }
      }

      override fun sendText(text: String): Boolean {
        if (text.length > CHUNK || text.toByteArray(Charsets.UTF_8).size > CHUNK) return false
        return enqueue(JSONObject().put("streamId", id).put("type", "text").put("data", text))
      }

      override fun sendBytes(bytes: ByteArray): Boolean {
        if (bytes.size > CHUNK || "binary" !in service.capabilities) return false
        return enqueue(JSONObject().put("streamId", id).put("type", "bytes")
          .put("data", Base64.getEncoder().encodeToString(bytes)))
      }

      /** true means local bounded-queue acceptance, not a delivery receipt; failures reach listener. */
      private fun enqueue(frame: JSONObject, close: Boolean = false): Boolean = synchronized(callbackGate) {
        if (kind != "websocket" || terminal.get() || sentClose || stopped) return@synchronized false
        if (sends.trySend(frame).isFailure) return@synchronized false
        if (close) sentClose = true
        true
      }

      override fun close(code: Int, reason: String?): Boolean {
        if (!validCloseCode(code) || (reason?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 123 ||
          reason?.any { it.code < 0x20 || it.code == 0x7f } == true) return false
        return enqueue(JSONObject().put("streamId", id).put("type", "close").put("code", code)
          .apply { reason?.let { put("reason", it) } }, close = true)
      }

      override fun cancel() { stop() }
      override fun close() = cancel()

      fun stop() {
        synchronized(callbackGate) {
          if (!terminal.compareAndSet(false, true)) return
          frames.cancel(); sends.cancel(); opened.cancel()
        }
        synchronized(lock) { ownedStreams.remove(this) }
        streams.remove(id, this)
        reader?.cancel(); sender?.cancel(); opener?.cancel()
        closeStream(id)
      }
    }
  }

  companion object {
    const val VIRTUAL_BASE_URL = "https://native.remote.invalid"
    private const val CHUNK = 512 * 1024
    private const val MAX_BASE64_CHARS = ((CHUNK + 2) / 3) * 4
    private const val MAX_TRANSFER_CHUNKS = 4096
    private const val MAX_STREAMS = 16
    private const val STREAM_QUEUE = 32
    private const val SEND_QUEUE = 32
    private val LOCATOR = Regex("[A-Za-z0-9\\u0080-\\uFFFF][A-Za-z0-9._:\\-\\u0080-\\uFFFF]{0,255}")
    private val CODE = Regex("[A-Za-z0-9_./-]{1,128}")
    private val REQUEST_HEADERS = setOf("accept", "content-type", "last-event-id", "sec-websocket-protocol", "x-opencode-ticket")
    private val SENSITIVE_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "cookie2", "set-cookie", "set-cookie2")

    private fun requireLocator(value: String) {
      if (!LOCATOR.matches(value)) throw nativeError("INVALID_SERVICE_ID")
    }

    private fun requireCode(value: String) { if (!CODE.matches(value)) throw nativeError("INVALID_ERROR") }

    private fun strictLong(value: JSONObject, field: String): Long {
      val number = value.opt(field) as? Number ?: throw nativeError("INVALID_RESPONSE")
      val long = number.toLong()
      if (!number.toDouble().isFinite() || number.toDouble() != long.toDouble()) throw nativeError("INVALID_RESPONSE")
      return long
    }

    private fun strictString(value: JSONObject, field: String, max: Int): String =
      (value.opt(field) as? String)?.takeIf { it.isNotEmpty() && it.length <= max &&
        it.none { char -> char.code < 0x20 || char.code == 0x7f } } ?: throw nativeError("INVALID_RESPONSE")

    private fun optionalString(value: JSONObject, field: String, max: Int): String? {
      if (!value.has(field)) return null
      return (value.opt(field) as? String)?.takeIf { it.length <= max &&
        it.none { char -> char.code < 0x20 || char.code == 0x7f } } ?: throw nativeError("INVALID_RESPONSE")
    }

    private fun boundedText(value: JSONObject, field: String, max: Int): String {
      val text = value.opt(field) as? String ?: throw nativeError("INVALID_RESPONSE")
      if (text.length > max || text.toByteArray(Charsets.UTF_8).size > max) throw nativeError("FRAME_TOO_LARGE")
      return text
    }

    private fun requestHeaders(headers: Map<String, String>): JSONObject {
      requireNativeHttpHeaders(headers)
      if (headers.size > 16) throw nativeError("INVALID_HEADERS")
      val result = JSONObject()
      val seen = mutableSetOf<String>()
      var bytes = 0
      headers.forEach { (name, value) ->
        val lower = name.lowercase(Locale.ROOT)
        if (lower in SENSITIVE_HEADERS) throw nativeError("HOST_CREDENTIALS_FORBIDDEN")
        if (lower !in REQUEST_HEADERS || !seen.add(lower)) throw nativeError("INVALID_HEADERS")
        bytes += name.length + value.toByteArray(Charsets.UTF_8).size
        if (bytes > 16_384) throw nativeError("INVALID_HEADERS")
        result.put(name, value)
      }
      return result
    }

    private fun responseHeaders(value: JSONObject): Map<String, String> {
      if (value.length() > 128) throw nativeError("INVALID_HEADERS")
      val headers = linkedMapOf<String, String>()
      val seen = mutableSetOf<String>()
      var bytes = 0
      val keys = value.keys()
      while (keys.hasNext()) {
        val name = keys.next()
        val content = value.opt(name) as? String ?: throw nativeError("INVALID_HEADERS")
        if (name.length > 256 || content.length > 16_384) throw nativeError("INVALID_HEADERS")
        requireNativeHttpHeaders(mapOf(name to content))
        val lower = name.lowercase(Locale.ROOT)
        if (!seen.add(lower)) throw nativeError("INVALID_HEADERS")
        bytes += name.length + content.toByteArray(Charsets.UTF_8).size
        if (bytes > 65_536) throw nativeError("INVALID_HEADERS")
        // Defense in depth if a peer forgets to strip a sensitive response header.
        if (lower !in SENSITIVE_HEADERS) headers[name] = content
      }
      return headers
    }

    private fun decodeBase64(value: JSONObject, field: String, maxBytes: Int): ByteArray {
      val encoded = value.opt(field) as? String ?: throw nativeError("INVALID_BASE64")
      val maxChars = ((maxBytes.toLong() + 2) / 3) * 4
      if (encoded.length.toLong() > maxChars) throw NativeHttpResponseTooLargeException(maxBytes.toLong())
      if (encoded.length % 4 != 0) throw nativeError("INVALID_BASE64")
      var padding = 0
      if (encoded.endsWith("=")) padding++
      if (encoded.endsWith("==")) padding++
      val decodedBytes = (encoded.length.toLong() / 4) * 3 - padding
      if (decodedBytes > maxBytes) throw NativeHttpResponseTooLargeException(maxBytes.toLong(), decodedBytes)
      if (encoded.dropLast(padding).any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' })
        throw nativeError("INVALID_BASE64")
      val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw nativeError("INVALID_BASE64") }
      if (decoded.size.toLong() != decodedBytes || Base64.getEncoder().encodeToString(decoded) != encoded) throw nativeError("INVALID_BASE64")
      return decoded
    }

    private fun parseError(value: JSONObject): NativeRemoteException {
      val code = strictString(value, "code", 128).also(::requireCode)
      if (code == "METHOD_NOT_FOUND") return NativeServiceUpgradeRequiredException()
      // Native protocol only permits safe static messages. Do not attach peer payloads or causes.
      val message = strictString(value, "message", 512)
      return NativeRemoteException(code, message)
    }

    private fun nativeError(code: String): NativeRemoteException {
      val safeCode = code.takeIf { CODE.matches(it) } ?: "NATIVE_REMOTE_ERROR"
      val message = when (safeCode) {
        "STALE_SERVICE" -> "原生服务已变化，请重新选择服务；操作未自动重试"
        "HOST_CREDENTIALS_FORBIDDEN" -> "Remote 原生服务凭据由 Host 管理，客户端不能发送凭据"
        "TRANSPORT_CLOSED" -> "Remote 原生服务连接已关闭"
        "UNSUPPORTED_PROTOCOL" -> "客户端不支持此原生服务协议"
        "STREAM_SEQUENCE_GAP" -> "Remote 原生服务流序号不连续，已关闭该流"
        "STREAM_BACKPRESSURE" -> "Remote 原生服务流消费过慢，已关闭该流"
        else -> "Remote 原生服务调用失败：$safeCode"
      }
      return NativeRemoteException(safeCode, message)
    }

    private fun validCloseCode(code: Int) = code in 1000..4999 && code !in setOf(1004, 1005, 1006, 1015) && code !in 1016..2999
    private fun closeCode(value: JSONObject): Int {
      if (!value.has("code")) return 1000
      val code = strictLong(value, "code")
      if (code !in 1000..4999 || !validCloseCode(code.toInt())) throw nativeError("INVALID_STREAM")
      return code.toInt()
    }
  }
}
