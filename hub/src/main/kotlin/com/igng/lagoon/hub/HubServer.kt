package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.ApiException
import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.userFacingError
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP(S) front door: `/healthz`, the `/api/v1` routes behind the session cookie, and the web client.
 * Each request runs on its own pooled thread; event streams hold theirs for as long as the tab is open.
 */
class HubServer(
  private val config: HubConfig,
  private val router: Router,
  private val api: HubApi,
  private val auth: Authenticator,
  private val static: StaticFiles,
  private val tls: TlsSetup?
) : AutoCloseable {
  private val counter = AtomicInteger()
  private val executor: ExecutorService = Executors.newCachedThreadPool { Thread(it, "hub-http-${counter.incrementAndGet()}").apply { isDaemon = true } }
  private val servers = mutableListOf<HttpServer>()

  /** The HTTP port actually bound (the configured one, or an ephemeral one in tests). */
  val port: Int get() = servers.first().address.port

  fun start() {
    servers += HttpServer.create(InetSocketAddress(config.bind, config.port), 64).apply {
      createContext("/") { handle(it, secure = false) }
      executor = this@HubServer.executor
      start()
    }
    if (tls != null) servers += HttpsServer.create(InetSocketAddress(config.bind, tls.port), 64).apply {
      httpsConfigurator = HttpsConfigurator(tls.context)
      createContext("/") { handle(it, secure = true) }
      executor = this@HubServer.executor
      start()
    }
  }

  override fun close() {
    servers.forEach { it.stop(0) }
    executor.shutdownNow()
  }

  private fun handle(exchange: HttpExchange, secure: Boolean) {
    val forwardedHttps = config.trustProxy && exchange.requestHeaders.getFirst("X-Forwarded-Proto")?.equals("https", true) == true
    val address = clientAddress(exchange)
    try {
      val path = exchange.requestURI.rawPath
      if (path == "/healthz") {
        Call(exchange, emptyMap(), secure, address).respondBytes(200, "ok".toByteArray(), "text/plain; charset=utf-8")
        return
      }
      if (path.startsWith("/api/")) {
        val (handler, params) = router.match(exchange.requestMethod, path) ?: throw HttpError(404, "not-found", "接口不存在")
        val call = Call(exchange, params, secure || forwardedHttps, address)
        if (path !in api.publicPaths) {
          val token = call.cookies[Authenticator.COOKIE]
          call.login = auth.session(token) ?: throw HttpError(401, "unauthorized", "请先登录")
          call.token = token
        }
        if (call.method !in setOf("GET", "HEAD")) checkOrigin(call)
        handler(call)
      } else {
        if (exchange.requestMethod !in setOf("GET", "HEAD")) throw HttpError(405, "method-not-allowed", "不支持的请求方法")
        static.serve(Call(exchange, emptyMap(), secure || forwardedHttps, address))
      }
    } catch (error: Throwable) {
      respondError(exchange, error)
    } finally {
      runCatching { exchange.close() }
    }
  }

  /** Writes go only from this site's own pages (SameSite=Strict already keeps the cookie off cross-site requests). */
  private fun checkOrigin(call: Call) {
    val source = call.header("Origin")?.takeIf { it != "null" } ?: call.header("Referer") ?: return
    val origin = runCatching { URI(source) }.getOrNull() ?: throw HttpError(403, "bad-origin", "请求来源无效")
    val host = call.header("X-Forwarded-Host")?.takeIf { config.trustProxy } ?: call.header("Host") ?: return
    val authority = origin.rawAuthority ?: throw HttpError(403, "bad-origin", "请求来源无效")
    if (!authority.equals(host.substringBefore(',').trim(), ignoreCase = true)) throw HttpError(403, "bad-origin", "请求来源与站点不一致")
  }

  private fun clientAddress(exchange: HttpExchange): String {
    if (config.trustProxy) exchange.requestHeaders.getFirst("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    return exchange.remoteAddress?.address?.hostAddress ?: "unknown"
  }

  private fun respondError(exchange: HttpExchange, error: Throwable) {
    if (error is IOException && error !is ApiException) return
    val (status, code, message) = when (error) {
      is HttpError -> Triple(error.status, error.code, error.message.orEmpty())
      is ApiException -> Triple(502, error.code ?: "harness-error", userFacingError(error, "服务器返回错误"))
      is CancellationException -> Triple(503, "cancelled", "操作已取消")
      is IllegalArgumentException, is IllegalStateException -> Triple(400, "invalid", userFacingError(error, "请求无效"))
      else -> {
        Diagnostics.warn("Hub", "请求处理失败：${exchange.requestMethod} ${exchange.requestURI.rawPath}", error)
        Triple(502, "failed", userFacingError(error, "操作失败"))
      }
    }
    // A stream or download that already started cannot change its status any more.
    if (exchange.responseCode != -1) return
    runCatching {
      val call = Call(exchange, emptyMap(), false, "")
      val headers = (error as? HttpError)?.retryAfterSeconds?.let { mapOf("Retry-After" to it.toString()) }.orEmpty()
      call.respondBytes(status, JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString().toByteArray(),
        "application/json; charset=utf-8", headers)
    }
  }
}
