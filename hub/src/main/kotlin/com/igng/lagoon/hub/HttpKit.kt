package com.igng.lagoon.hub

import com.sun.net.httpserver.HttpExchange
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder

/** A failure with a status, a stable code and a Chinese message shown to the user as-is. */
class HttpError(val status: Int, val code: String, message: String, val retryAfterSeconds: Long? = null) : RuntimeException(message)

/** One HTTP request as the route handlers see it. */
class Call(val exchange: HttpExchange, val params: Map<String, String>, val secure: Boolean, val clientAddress: String) {
  /** The signed-in browser session and its raw cookie token, set by the server before protected routes run. */
  var login: LoginSession? = null
  var token: String? = null

  val method: String get() = exchange.requestMethod
  val path: String get() = exchange.requestURI.rawPath

  val query: Map<String, String> by lazy {
    exchange.requestURI.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { pair ->
      val key = pair.substringBefore('=')
      val value = if ('=' in pair) pair.substringAfter('=') else ""
      decode(key) to decode(value)
    }
  }

  val cookies: Map<String, String> by lazy {
    exchange.requestHeaders["Cookie"].orEmpty().flatMap { it.split(';') }.mapNotNull { item ->
      val index = item.indexOf('=')
      if (index <= 0) null else item.substring(0, index).trim() to item.substring(index + 1).trim()
    }.toMap()
  }

  fun header(name: String): String? = exchange.requestHeaders.getFirst(name)

  fun param(name: String): String = params[name] ?: throw HttpError(400, "bad-request", "缺少参数 $name")

  fun queryParam(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }

  /** The request body as JSON; [limit] bounds what one request may make the hub buffer. */
  fun json(limit: Int = DEFAULT_BODY_LIMIT): JSONObject {
    val bytes = readBody(exchange.requestBody, limit)
    if (bytes.isEmpty()) return JSONObject()
    return try {
      JSONObject(JSONTokener(String(bytes, Charsets.UTF_8)))
    } catch (_: Exception) {
      throw HttpError(400, "bad-json", "请求内容不是有效的 JSON")
    }
  }

  fun respond(status: Int, body: JSONObject) = respondBytes(status, body.toString().toByteArray(), "application/json; charset=utf-8")
  fun respond(body: JSONObject) = respond(200, body)
  fun respond(body: JSONArray) = respondBytes(200, body.toString().toByteArray(), "application/json; charset=utf-8")
  fun ok() = respond(JSONObject().put("ok", true))

  fun respondBytes(status: Int, bytes: ByteArray, contentType: String, extra: Map<String, String> = emptyMap()) {
    val headers = exchange.responseHeaders
    headers.set("Content-Type", contentType)
    if (!headers.containsKey("Cache-Control")) headers.set("Cache-Control", "no-store")
    extra.forEach { (key, value) -> headers.set(key, value) }
    if (method == "HEAD") {
      exchange.sendResponseHeaders(status, -1)
    } else {
      exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
    exchange.close()
  }

  fun setCookie(value: String) = exchange.responseHeaders.add("Set-Cookie", value)

  companion object {
    const val DEFAULT_BODY_LIMIT = 1 shl 20

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)

    fun readBody(input: InputStream, limit: Int): ByteArray {
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(16 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (out.size() + read > limit) throw HttpError(413, "too-large", "请求内容过大")
        out.write(buffer, 0, read)
      }
      return out.toByteArray()
    }
  }
}

/** Method + path pattern routing; `{name}` captures one path segment. */
class Router {
  private class Route(val method: String, val segments: List<String>, val handler: (Call) -> Unit)

  private val routes = mutableListOf<Route>()

  fun get(pattern: String, handler: (Call) -> Unit) = add("GET", pattern, handler)
  fun post(pattern: String, handler: (Call) -> Unit) = add("POST", pattern, handler)
  fun put(pattern: String, handler: (Call) -> Unit) = add("PUT", pattern, handler)
  fun delete(pattern: String, handler: (Call) -> Unit) = add("DELETE", pattern, handler)

  private fun add(method: String, pattern: String, handler: (Call) -> Unit) {
    routes += Route(method, pattern.trim('/').split('/'), handler)
  }

  /** The handler and captured parameters for a request, or null when no route matches the path. */
  fun match(method: String, rawPath: String): Pair<(Call) -> Unit, Map<String, String>>? {
    val segments = rawPath.trim('/').split('/').map { runCatching { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(it) }
    var pathMatched = false
    for (route in routes) {
      if (route.segments.size != segments.size) continue
      val params = mutableMapOf<String, String>()
      val matches = route.segments.indices.all { i ->
        val expected = route.segments[i]
        if (expected.startsWith('{') && expected.endsWith('}')) { params[expected.substring(1, expected.length - 1)] = segments[i]; true }
        else expected == segments[i]
      }
      if (!matches) continue
      pathMatched = true
      if (route.method == method || (method == "HEAD" && route.method == "GET")) return route.handler to params
    }
    if (pathMatched) throw HttpError(405, "method-not-allowed", "不支持的请求方法")
    return null
  }
}
