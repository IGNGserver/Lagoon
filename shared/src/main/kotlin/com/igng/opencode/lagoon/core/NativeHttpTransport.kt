package com.igng.opencode.lagoon.core

import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * The small carrier boundary used by native OpenCode services.
 *
 * Implementations may use OkHttp, an SSH forward, or a Remote native-service bridge, but the
 * adapter above this boundary only deals in byte arrays and SSE/WebSocket messages. In particular,
 * no OkHttp [okhttp3.Response], [okhttp3.EventSource], or [okhttp3.WebSocket] type crosses this API.
 *
 * A transport implementation must:
 *  - resolve every route through [endpoint] (never accept an absolute URL from a caller);
 *  - disable HTTP redirects and call [NativeHttpEndpoint.requireNoRedirect] for the final URL;
 *  - stop its underlying request/socket when a suspended request is cancelled or a returned handle
 *    is cancelled; and
 *  - suppress callbacks from a generation after [cancelGeneration] has been called; and
 *  - make [close] terminal, cancelling all work without shutting down shared sibling resources.
 */
internal interface NativeHttpTransport : Closeable {
  val endpoint: NativeHttpEndpoint

  /**
   * Performs one bounded request. [relativePath] may contain a query but not an absolute URL.
   *
   * Cancellation is deliberately part of the suspend contract: an implementation must bind the
   * cancellation of this coroutine to the underlying native call rather than merely abandoning a
   * worker thread. A generation of zero means that the operation is not generation-scoped.
   */
  suspend fun request(
    method: String,
    relativePath: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    maxResponseBytes: Long = DEFAULT_NATIVE_HTTP_RESPONSE_BYTES,
    generation: Long = 0L
  ): NativeHttpResponse

  /** Opens an SSE stream. The returned handle is safe to cancel more than once. */
  fun openEventStream(
    relativePath: String,
    headers: Map<String, String> = emptyMap(),
    generation: Long = 0L,
    callback: NativeHttpEventStreamCallback
  ): NativeHttpEventStream

  /** Opens a native WebSocket channel. The returned channel is safe to cancel more than once. */
  fun openWebSocket(
    relativePath: String,
    headers: Map<String, String> = emptyMap(),
    generation: Long = 0L,
    listener: NativeWebSocketListener
  ): NativeWebSocketChannel

  /** Cancels all requests/streams/channels owned by [generation]; zero cancels current work only. */
  fun cancelGeneration(generation: Long)

  override fun close() = cancelGeneration(0L)
}

/** Encoded slashes are data only in the OpenCode `/api/session/{id}` path segment. */
internal enum class NativeHttpPathPolicy { STRICT, OPENCODE_SESSION_IDS }

/** Fixed origin plus an optional path prefix; it is the only URL builder a native transport needs. */
internal class NativeHttpEndpoint(
  baseUrl: String,
  private val pathPolicy: NativeHttpPathPolicy = NativeHttpPathPolicy.OPENCODE_SESSION_IDS
) {
  private val base: URI = parseBase(baseUrl)
  private val basePath: String = base.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }

  val origin: NativeHttpOrigin = NativeHttpOrigin.from(base)
  val baseUrl: String = base.toASCIIString()

  /** Resolves an API route under the configured origin and path prefix. */
  fun resolve(relativePath: String): String {
    val path = parseRelative(relativePath)
    val suffix = path.rawPath.orEmpty().trimStart('/')
    val joinedPath = if (basePath == "/") "/$suffix" else "$basePath/$suffix"
    val query = path.rawQuery?.let { "?$it" }.orEmpty()
    val candidate = URI("${base.scheme}://${base.rawAuthority}$joinedPath$query")
    requireSameOrigin(candidate.toASCIIString())
    return candidate.toASCIIString()
  }

  /** Rejects a response URL that escaped the configured scheme/host/port. */
  fun requireSameOrigin(url: String) {
    val candidate = parseAbsolute(url)
    if (NativeHttpOrigin.from(candidate) != origin) {
      throw NativeHttpOriginViolationException(url, origin)
    }
  }

  /**
   * Redirects are never followed for authenticated native requests. Implementations call this with
   * the URL they requested and the URL reported by their backend after the call completes.
   */
  fun requireNoRedirect(requestedUrl: String, finalUrl: String) {
    requireSameOrigin(requestedUrl)
    requireSameOrigin(finalUrl)
    if (requestedUrl != finalUrl) throw NativeHttpRedirectException(requestedUrl, finalUrl)
  }

  private fun parseBase(raw: String): URI {
    val value = parseAbsolute(raw.trim())
    require(value.scheme.lowercase(Locale.ROOT) in setOf("http", "https")) {
      "原生 HTTP 地址必须使用 http 或 https"
    }
    require(value.userInfo == null && value.rawQuery == null && value.rawFragment == null) {
      "原生 HTTP 地址不能包含凭据、查询参数或片段"
    }
    validatePath(value.rawPath.orEmpty(), allowSessionIds = false)
    return value
  }

  private fun parseAbsolute(raw: String): URI {
    val value = try { URI(raw) } catch (error: Exception) {
      throw IllegalArgumentException("原生 HTTP 地址无效", error)
    }
    require(value.isAbsolute && value.host != null && value.rawAuthority != null) {
      "原生 HTTP 地址必须包含固定 origin"
    }
    require(value.rawUserInfo == null && value.rawFragment == null) { "原生 HTTP 地址不能包含凭据或片段" }
    require(value.scheme.lowercase(Locale.ROOT) in setOf("http", "https")) { "原生 HTTP 地址协议无效" }
    require(raw.none { it.code < 0x20 || it.code == 0x7f || it == '\\' }) { "原生 HTTP 地址包含非法字符" }
    return value
  }

  private fun parseRelative(raw: String): URI {
    require(raw.isNotBlank()) { "原生 HTTP 路径不能为空" }
    require(raw == raw.trim()) { "原生 HTTP 路径不能包含首尾空白" }
    require(raw.none { it.code < 0x20 || it.code == 0x7f || it == '\\' }) {
      "原生 HTTP 路径包含非法字符"
    }
    val value = try { URI(raw) } catch (error: Exception) {
      throw IllegalArgumentException("原生 HTTP 路径无效", error)
    }
    require(!value.isAbsolute && value.rawAuthority == null && value.rawUserInfo == null) {
      "原生 HTTP 只接受相对路径"
    }
    require(value.rawFragment == null) { "原生 HTTP 路径不能包含片段" }
    validatePath(value.rawPath.orEmpty(), allowSessionIds = pathPolicy == NativeHttpPathPolicy.OPENCODE_SESSION_IDS)
    return value
  }

  private fun validatePath(rawPath: String, allowSessionIds: Boolean) {
    // The route shape is checked BEFORE decoding. An encoded separator can never manufacture the
    // api/session prefix or move the exception to an arbitrary suffix (or to a base URL prefix).
    val segments = rawPath.removePrefix("/").split('/')
    val sessionIndex = if (allowSessionIds && segments.size >= 3 &&
      segments[0] == "api" && segments[1] == "session" && segments[2].isNotEmpty()) 2 else -1
    segments.forEachIndexed { index, segment ->
      var decoded = segment
      // Repeated %25 must not hide traversal or separators from a proxy's second decoding pass.
      // Each successful pass shortens the string, so this loop is bounded by the input length.
      while (true) {
        require(decoded.none { it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
          "原生 HTTP 路径包含非法字符"
        }
        require(decoded.split('/').none { it == "." || it == ".." }) {
          "原生 HTTP 路径不能穿越固定路径前缀"
        }
        require('/' !in decoded || index == sessionIndex) { "原生 HTTP 路径不能编码斜杠" }
        val next = percentDecode(decoded)
        if (next == decoded) break
        decoded = next
      }
    }
  }

  private fun percentDecode(value: String): String {
    // URI checks the original escapes. Later passes may contain a literal '%' from %25; leave
    // incomplete/literal escapes alone. '+' is a path character, never query form data.
    val bytes = java.io.ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
      val high = if (index + 2 < value.length && value[index] == '%') Character.digit(value[index + 1], 16) else -1
      val low = if (high >= 0) Character.digit(value[index + 2], 16) else -1
      if (high >= 0 && low >= 0) {
        bytes.write(high * 16 + low)
        index += 3
      } else {
        val point = value.codePointAt(index)
        String(Character.toChars(point)).toByteArray(Charsets.UTF_8).forEach { byte -> bytes.write(byte.toInt()) }
        index += Character.charCount(point)
      }
    }
    return bytes.toByteArray().toString(Charsets.UTF_8)
  }
}

/** Scheme + host + effective port. Default HTTP(S) ports are normalized. */
internal data class NativeHttpOrigin(val scheme: String, val host: String, val port: Int) {
  companion object {
    fun from(url: URI): NativeHttpOrigin {
      val scheme = url.scheme.lowercase(Locale.ROOT)
      val port = when {
        url.port >= 0 -> url.port
        scheme == "http" -> 80
        scheme == "https" -> 443
        else -> -1
      }
      require(port in 1..65535) { "原生 HTTP origin 端口无效" }
      return NativeHttpOrigin(scheme, url.host.lowercase(Locale.ROOT), port)
    }
  }
}

internal data class NativeHttpResponse(
  val statusCode: Int,
  val headers: Map<String, String>,
  val body: ByteArray,
  val generation: Long = 0L
) {
  init { require(statusCode in 100..599) { "HTTP 状态码无效" } }
}

internal data class NativeHttpEvent(
  val id: String? = null,
  val event: String? = null,
  val data: String,
  val generation: Long
)

internal interface NativeHttpEventStreamCallback {
  fun onOpen() {}
  fun onEvent(event: NativeHttpEvent)
  fun onFailure(error: Throwable) {}
  fun onClosed() {}
}

internal interface NativeHttpEventStream : Closeable {
  val generation: Long
  fun cancel()
  override fun close() = cancel()
}

internal interface NativeWebSocketChannel : Closeable {
  val generation: Long
  fun sendText(text: String): Boolean
  fun sendBytes(bytes: ByteArray): Boolean
  fun close(code: Int = 1000, reason: String? = null): Boolean
  fun cancel()
  override fun close() { cancel() }
}

internal interface NativeWebSocketListener {
  fun onOpen(channel: NativeWebSocketChannel) {}
  fun onText(text: String)
  fun onBytes(bytes: ByteArray) {}
  fun onClosing(code: Int, reason: String?) {}
  fun onClosed(code: Int, reason: String?) {}
  fun onFailure(error: Throwable) {}
}

internal class NativeHttpResponseTooLargeException(
  val limitBytes: Long,
  val actualBytes: Long? = null
) : IOException("服务器响应超过 $limitBytes 字节，已拒绝读取")

internal class NativeHttpOriginViolationException(
  val rejectedUrl: String,
  val expectedOrigin: NativeHttpOrigin
) : IOException("原生 HTTP 响应离开固定 origin")

internal class NativeHttpRedirectException(
  val requestedUrl: String,
  val finalUrl: String
) : IOException("原生 HTTP 重定向被禁止")

internal class NativeHttpGenerationCancelledException(val generation: Long) :
  CancellationException("原生 HTTP generation $generation 已取消")

internal const val DEFAULT_NATIVE_HTTP_RESPONSE_BYTES: Long = 4L * 1024L * 1024L
internal const val MAX_NATIVE_HTTP_RESPONSE_BYTES: Long = 64L * 1024L * 1024L

/** Shared validation for implementations and fakes; it keeps bounds at the carrier boundary. */
internal fun requireNativeHttpResponseBudget(maxResponseBytes: Long) {
  require(maxResponseBytes in 1..MAX_NATIVE_HTTP_RESPONSE_BYTES) {
    "原生 HTTP 响应预算必须在 1..$MAX_NATIVE_HTTP_RESPONSE_BYTES 字节内"
  }
}

internal fun requireNativeHttpMethod(method: String): String {
  require(method == method.trim() && method.isNotEmpty() && method.length <= 16) {
    "原生 HTTP 方法无效"
  }
  require(method.all(::isNativeHttpTokenCharacter)) {
    "原生 HTTP 方法无效"
  }
  return method.uppercase(Locale.ROOT)
}

// RFC 9110 tchar, shared by method and field-name validation. ASCII only.
private fun isNativeHttpTokenCharacter(value: Char): Boolean =
  value in 'a'..'z' || value in 'A'..'Z' || value in '0'..'9' || value in "!#$%&'*+-.^_`|~"

internal fun requireNativeHttpHeaders(headers: Map<String, String>) {
  headers.forEach { (name, value) ->
    require(name.isNotEmpty() && name.all(::isNativeHttpTokenCharacter)) {
      "原生 HTTP 请求头名称无效"
    }
    require(value.none { it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7f }) {
      "原生 HTTP 请求头值无效"
    }
  }
}

internal fun ByteArray.requireNativeHttpResponseBudget(maxResponseBytes: Long): ByteArray {
  com.igng.opencode.lagoon.core.requireNativeHttpResponseBudget(maxResponseBytes)
  if (size.toLong() > maxResponseBytes) {
    throw NativeHttpResponseTooLargeException(maxResponseBytes, size.toLong())
  }
  return this
}
