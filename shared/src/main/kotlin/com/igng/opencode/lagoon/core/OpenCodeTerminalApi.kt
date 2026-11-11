package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong

/** OpenCode's simple PTY protocol, not JSON messages and not the mux protocol. */
internal class OpenCodeTerminalApi(
  private val transport: NativeHttpTransport,
  private val headers: (String) -> Map<String, String>,
  private val ownerClosed: () -> Boolean = { false }
) : Closeable {
  private val lock = Any()
  private val generation = generations.incrementAndGet()
  private var closed = false
  private val attachments = mutableSetOf<Attachment>()

  suspend fun terminals(directory: String): List<NativeTerminal> {
    val wanted = directoryKey(directory)
    val root = request("GET", "api/pty", directory)
    val location = responseDirectory(root, wanted)
    val data = root.opt("data") as? JSONArray ?: invalid("终端列表 data 必须为数组")
    return (0 until data.length()).map { index ->
      terminal(data.opt(index) as? JSONObject ?: invalid("终端列表条目无效"), location)
    }
  }

  suspend fun createTerminal(directory: String): NativeTerminal =
    terminalResponse(request("POST", "api/pty", directory, JSONObject()), directory)

  suspend fun resizeTerminal(directory: String, id: String, cols: Int, rows: Int): NativeTerminal {
    require(cols in 1..2048 && rows in 1..1024) { "终端尺寸必须在 1..2048 列、1..1024 行内" }
    return terminalResponse(request("PUT", path(id), directory,
      JSONObject().put("size", JSONObject().put("cols", cols).put("rows", rows))), directory, id)
  }

  suspend fun renameTerminal(directory: String, id: String, title: String): NativeTerminal =
    terminalResponse(request("PUT", path(id), directory, JSONObject().put("title", title)), directory, id)

  suspend fun closeTerminal(directory: String, id: String) {
    request("DELETE", path(id), directory, noContent = true)
  }

  suspend fun attachTerminal(directory: String, id: String): TerminalAttachment {
    val route = path(id)
    // One short-lived, single-use ticket. Never retry it or put Basic credentials in a URL.
    val root = request("POST", "$route/connect-token", directory, JSONObject(), ticket = true)
    responseDirectory(root, directoryKey(directory))
    val data = root.opt("data") as? JSONObject ?: invalid("终端票据 data 无效")
    val ticket = string(data, "ticket").takeIf { it.isNotBlank() && it.length <= 8192 && it.none { c -> c.code < 0x20 || c.code == 0x7f } }
      ?: invalid("终端票据无效")
    if (integer(data, "expires_in") <= 0L) invalid("终端票据有效期无效")
    currentCoroutineContext().ensureActive()
    val attachment = Attachment()
    synchronized(lock) {
      checkOpen()
      attachments += attachment
    }
    try {
      currentCoroutineContext().ensureActive()
      // No cursor means replay all retained output. The binary control marks the live boundary.
      val channel = transport.openWebSocket(
        query("$route/connect", directory) + "&ticket=${encode(ticket)}",
        headers("application/json"), generation, attachment
      )
      attachment.install(channel)
      currentCoroutineContext().ensureActive()
      synchronized(lock) { checkOpen() }
      return attachment
    } catch (cancel: CancellationException) {
      attachment.close()
      throw cancel
    } catch (error: Exception) {
      attachment.close()
      // Underlying errors can include the ticket URL; deliberately do not chain or log them.
      throw ApiException(502, "无法连接原生终端；没有重试或重放输入")
    }
  }

  override fun close() {
    val active = synchronized(lock) {
      if (closed) return
      closed = true
      attachments.toList().also { attachments.clear() }
    }
    active.forEach { it.close() }
    transport.cancelGeneration(generation)
  }

  private fun checkOpen() { check(!closed && !ownerClosed()) { "原生终端连接已关闭" } }

  private suspend fun request(
    method: String, route: String, directory: String, body: JSONObject? = null, ticket: Boolean = false,
    noContent: Boolean = false
  ): JSONObject {
    directoryKey(directory)
    synchronized(lock) { checkOpen() }
    val requestHeaders = headers("application/json").toMutableMap()
    if (body != null) requestHeaders["Content-Type"] = "application/json; charset=utf-8"
    if (ticket) requestHeaders["x-opencode-ticket"] = "1"
    val response = transport.request(method, query(route, directory), requestHeaders,
      body?.toString()?.toByteArray(Charsets.UTF_8), RESPONSE_BYTES, generation)
    currentCoroutineContext().ensureActive()
    synchronized(lock) { checkOpen() }
    response.body.requireNativeHttpResponseBudget(RESPONSE_BYTES)
    if (response.statusCode !in 200..299) throw ApiException(response.statusCode, "原生终端请求失败（HTTP ${response.statusCode}）；没有自动重试")
    if (noContent) {
      if (response.statusCode != 204 || response.body.isNotEmpty()) invalid("终端删除必须返回 204 空响应")
      return JSONObject()
    }
    return try { JSONObject(decodeUtf8(response.body)) } catch (_: Exception) { invalid("终端响应不是有效 JSON") }
  }

  private fun terminalResponse(root: JSONObject, directory: String, expectedId: String? = null): NativeTerminal {
    val location = responseDirectory(root, directoryKey(directory))
    val data = root.opt("data") as? JSONObject ?: invalid("终端 data 必须为对象")
    return terminal(data, location).also { if (expectedId != null && it.id != expectedId) invalid("终端响应身份不匹配") }
  }

  private fun responseDirectory(root: JSONObject, wanted: String): String {
    val location = root.opt("location") as? JSONObject ?: invalid("终端响应缺少 location")
    val directory = string(location, "directory")
    val key = try { directoryKey(directory) } catch (_: IllegalArgumentException) { invalid("终端响应目录无效") }
    if (key != wanted) invalid("终端响应不属于请求的工作目录")
    return directory
  }

  private fun terminal(data: JSONObject, directory: String): NativeTerminal {
    val id = string(data, "id")
    try { path(id) } catch (_: IllegalArgumentException) { invalid("终端 ID 无效") }
    val args = data.opt("args") as? JSONArray ?: invalid("终端 args 必须为数组")
    val status = string(data, "status")
    if (status !in setOf("running", "exited")) invalid("终端状态无效")
    val exit = if (!data.has("exitCode") || data.isNull("exitCode")) null else integer(data, "exitCode", Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    return NativeTerminal(id, string(data, "title"), directory, status, string(data, "command"),
      (0 until args.length()).map { args.opt(it) as? String ?: invalid("终端参数必须为字符串") },
      integer(data, "pid"), exit, string(data, "cwd"))
  }

  private fun query(route: String, directory: String) = "$route?${encode("location[directory]")}=${encode(directory)}"

  private fun path(id: String): String {
    require(id.isNotBlank() && id.length <= 1024) { "终端 ID 无效" }
    val route = "api/pty/${encode(id)}"
    // Carrier validation rejects encoded separators and all repeated-encoding traversal variants.
    transport.endpoint.resolve(route)
    return route
  }

  private fun directoryKey(directory: String): String {
    require(directory.isNotBlank() && directory.none { it.code < 0x20 || it.code == 0x7f }) { "终端目录无效" }
    require(directory.startsWith('/') || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(directory)) { "终端目录必须为路径而非 URI" }
    return normalizedDirectory(directory)
  }

  private inner class Attachment : TerminalAttachment, NativeWebSocketListener {
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(TerminalStreamState())
    override val state: StateFlow<TerminalStreamState> = mutableState.asStateFlow()
    private var channel: NativeWebSocketChannel? = null
    private var ended = false
    private var controlSeen = false
    private var inputSent = false

    // Carrier callbacks hold their own serialization gate. Never call a carrier under stateLock:
    // otherwise input owns stateLock -> carrier gate while output owns carrier gate -> stateLock.
    fun install(value: NativeWebSocketChannel) {
      val reject = synchronized(stateLock) {
        if (ended || ownerClosed()) true else { if (channel == null) channel = value; false }
      }
      if (reject) value.cancel()
    }

    override fun onOpen(channel: NativeWebSocketChannel) {
      val reject = synchronized(stateLock) {
        if (ended || ownerClosed()) true else {
          this.channel = channel
          mutableState.value = mutableState.value.copy(connected = true)
          false
        }
      }
      if (reject) channel.cancel()
    }

    override fun onText(text: String) {
      val overflow = synchronized(stateLock) {
        if (ended || ownerClosed()) return@synchronized false
        val old = mutableState.value
        if (old.cursor?.let { text.length.toLong() > MAX_SAFE_INTEGER - it } == true) return@synchronized true
        val cursor = old.cursor?.plus(text.length) // JavaScript/UTF-16 units, NOT UTF-8 bytes.
        val (tail, dropped) = boundedTail(text)
        val remaining = OUTPUT_BYTES - tail.toByteArray(Charsets.UTF_8).size
        val (prefix, prefixDropped) = boundedTail(old.output, remaining)
        mutableState.value = old.copy(output = prefix + tail, cursor = cursor,
          truncated = old.truncated || dropped || prefixDropped)
        false
      }
      if (overflow) finish("终端游标超出安全范围")
    }

    override fun onBytes(bytes: ByteArray) {
      val malformed = synchronized(stateLock) {
        if (ended || ownerClosed()) return@synchronized false
        if (controlSeen || bytes.isEmpty() || bytes[0] != 0.toByte() || bytes.size > CONTROL_BYTES) return@synchronized true
        val cursor = try {
          val control = JSONObject(decodeUtf8(bytes.copyOfRange(1, bytes.size)))
          if (control.length() != 1 || !control.has("cursor")) invalid("终端控制帧无效")
          integer(control, "cursor")
        } catch (_: Exception) { return@synchronized true }
        controlSeen = true
        mutableState.value = mutableState.value.copy(ready = true, cursor = cursor)
        false
      }
      if (malformed) finish("终端控制帧无效或重复")
    }

    override fun sendText(text: String): Boolean {
      val socket = synchronized(stateLock) {
        val old = mutableState.value
        if (ended || ownerClosed() || !old.connected || !old.ready) return@synchronized null
        if (text.toByteArray(Charsets.UTF_8).size > INPUT_BYTES) {
          mutableState.value = old.copy(error = "一次终端输入不能超过 16 KiB；未发送")
          return@synchronized null
        }
        // Mark before releasing the lock: an asynchronous failure may race local queue acceptance.
        inputSent = true
        channel
      } ?: return false
      val sent = try { socket.sendText(text) } catch (_: Exception) { false }
      if (!sent) finish("终端输入交付状态未知；未重试，请先检查终端", inputUnknown = true)
      return sent
    }

    override fun onClosing(code: Int, reason: String?) { finish() }
    override fun onClosed(code: Int, reason: String?) { finish() }
    override fun onFailure(error: Throwable) {
      val unknown = synchronized(stateLock) { inputSent }
      finish("原生终端连接中断；没有自动重连", inputUnknown = unknown)
    }

    private fun finish(message: String? = null, inputUnknown: Boolean = false) {
      val socket = synchronized(stateLock) {
        if (ended) return
        ended = true
        mutableState.value = mutableState.value.copy(connected = false, ready = false, ended = true,
          error = message ?: mutableState.value.error,
          inputDeliveryUnknown = mutableState.value.inputDeliveryUnknown || inputUnknown)
        channel.also { channel = null }
      }
      socket?.cancel()
      synchronized(lock) { attachments.remove(this) }
    }

    override fun close() = finish()
  }

  companion object {
    private val generations = AtomicLong(0L)
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val RESPONSE_BYTES = 1024L * 1024L
    private const val OUTPUT_BYTES = 256 * 1024
    private const val INPUT_BYTES = 16 * 1024
    private const val CONTROL_BYTES = 1024

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun string(value: JSONObject, name: String): String = value.opt(name) as? String ?: invalid("终端 $name 必须为字符串")
    private fun integer(value: JSONObject, name: String, min: Long = 0L, max: Long = MAX_SAFE_INTEGER): Long {
      val number = value.opt(name) as? Number ?: invalid("终端 $name 必须为整数")
      val result = try { java.math.BigDecimal(number.toString()).longValueExact() } catch (_: Exception) { invalid("终端 $name 必须为安全整数") }
      if (result !in min..max) invalid("终端 $name 超出安全范围")
      return result
    }
    private fun invalid(message: String): Nothing = throw ApiException(502, message)
    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString()

    /** Walk backward by Unicode code point so no retained boundary splits a surrogate pair. */
    private fun boundedTail(value: String, maxBytes: Int = OUTPUT_BYTES): Pair<String, Boolean> {
      var start = value.length
      var bytes = 0
      while (start > 0) {
        val point = value.codePointBefore(start)
        val width = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
        if (bytes + width > maxBytes) break
        bytes += width
        start -= Character.charCount(point)
      }
      return value.substring(start) to (start > 0)
    }
  }
}
