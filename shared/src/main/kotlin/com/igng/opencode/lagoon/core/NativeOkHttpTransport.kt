package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Stock fixed-origin HTTP, SSE and WebSocket carrier for native services. */
internal class NativeOkHttpTransport(
  override val endpoint: NativeHttpEndpoint,
  callTimeoutSeconds: Long = 60,
  streamIdleSeconds: Long = SharedHttp.STREAM_IDLE_SECONDS
) : NativeHttpTransport {
  private val client = OkHttpClient.Builder()
    .connectTimeout(SharedHttp.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
    .connectionPool(SharedHttp.connectionPool)
    .dispatcher(SharedHttp.dispatcher)
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
      endpoint.requireSameOrigin(chain.request().url.toString())
      chain.proceed(chain.request())
    }
    .addInterceptor(HttpMetrics.interceptor)
    .build()

  // Mutations can have succeeded even when their response was lost. Connection recovery must
  // never replay authenticated writes; one-shot bodies below also stop HTTP 408/503 follow-ups.
  private val writeClient = client.newBuilder()
    .retryOnConnectionFailure(false)
    .build()

  private val streamClient = client.newBuilder()
    // Long-lived streams must not pin the unary in-flight metric or dispatcher slots.
    .apply { interceptors().remove(HttpMetrics.interceptor) }
    .dispatcher(SharedHttp.streamDispatcher)
    .readTimeout(streamIdleSeconds, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()

  // Registration, native-handle attachment, cancellation and callback delivery share ONE monitor.
  // In particular, cancelGeneration/close returning guarantees no stale callback can start later.
  // Callbacks may synchronously cancel their own logical handle (JVM monitors are reentrant).
  private val gate = Any()
  private var closed = false
  private val cancelledGenerations = mutableSetOf<Long>()
  private val operations = mutableSetOf<Operation>()

  override suspend fun request(
    method: String,
    relativePath: String,
    headers: Map<String, String>,
    body: ByteArray?,
    maxResponseBytes: Long,
    generation: Long
  ): NativeHttpResponse {
    val normalizedMethod = requireNativeHttpMethod(method)
    requireNativeHttpResponseBudget(maxResponseBytes)
    val mutation = normalizedMethod !in setOf("GET", "HEAD", "OPTIONS")
    val requestBody = if (mutation) {
      // Even an empty DELETE needs a one-shot body: a null body permits OkHttp to replay a 503.
      val bytes = body ?: ByteArray(0)
      object : RequestBody() {
        override fun contentType(): okhttp3.MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: okio.BufferedSink) { sink.write(bytes) }
      }
    } else body?.toRequestBody(null)
    val request = requestBuilder(relativePath, headers)
      .method(normalizedMethod, requestBody).build()
    val requestedUrl = request.url.toString()
    val requestClient = if (mutation) writeClient else client
    return suspendCancellableCoroutine { continuation ->
      val operation = HttpCall(generation, requestClient.newCall(request), continuation)
      continuation.invokeOnCancellation {
        synchronized(gate) { operation.cancelLocked(NativeHttpGenerationCancelledException(generation)) }
      }
      synchronized(gate) {
        if (operation.stopped || !registerLocked(operation)) return@synchronized
        try {
          operation.call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
              synchronized(gate) { operation.finishLocked(Result.failure(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
              // Retain the operation until the ENTIRE bounded body has been read: cancelling a
              // generation also cancels calls blocked while consuming their response bodies.
              val result = runCatching {
                response.use {
                  validateResponse(requestedUrl, it)
                  NativeHttpResponse(
                    it.code, it.headersMap(), it.body?.readBounded(maxResponseBytes) ?: ByteArray(0), generation
                  )
                }
              }
              synchronized(gate) { operation.finishLocked(result) }
            }
          })
        } catch (error: Throwable) {
          operation.call.cancel()
          operation.finishLocked(Result.failure(error))
        }
      }
    }
  }

  override fun openEventStream(
    relativePath: String,
    headers: Map<String, String>,
    generation: Long,
    callback: NativeHttpEventStreamCallback
  ): NativeHttpEventStream {
    val request = requestBuilder(relativePath, headers).build()
    val requestedUrl = request.url.toString()
    val handle = EventStream(generation, callback)
    synchronized(gate) {
      if (!registerLocked(handle)) return handle
      try {
        val source = EventSources.createFactory(streamClient).newEventSource(request, object : EventSourceListener() {
          override fun onOpen(eventSource: EventSource, response: Response) {
            synchronized(gate) {
              if (handle.stopped) return
              val error = runCatching { validateResponse(requestedUrl, response) }.exceptionOrNull()
              if (error != null) {
                handle.failureLocked(error)
                eventSource.cancel()
              } else handle.emitLocked { callback.onOpen() }
            }
          }

          override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            synchronized(gate) { handle.emitLocked { callback.onEvent(NativeHttpEvent(id, type, data, generation)) } }
          }

          override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            synchronized(gate) {
              val error = response?.let { runCatching { validateResponse(requestedUrl, it) }.exceptionOrNull() }
                ?: t ?: IOException("SSE 已断开")
              handle.failureLocked(error)
            }
          }

          override fun onClosed(eventSource: EventSource) {
            synchronized(gate) { handle.emitLocked(terminal = true) { callback.onClosed() } }
          }
        })
        handle.attachLocked(source)
      } catch (error: Throwable) {
        handle.failureLocked(error)
      }
    }
    return handle
  }

  override fun openWebSocket(
    relativePath: String,
    headers: Map<String, String>,
    generation: Long,
    listener: NativeWebSocketListener
  ): NativeWebSocketChannel {
    // Use the same HTTP(S) endpoint builder as unary/SSE. OkHttp performs the upgrade; callers
    // cannot substitute a ws(s) absolute URL, authority, redirect, authenticator or cookie jar.
    val request = requestBuilder(relativePath, headers).build()
    val requestedUrl = request.url.toString()
    val handle = SocketChannel(generation, listener)
    synchronized(gate) {
      if (!registerLocked(handle)) return handle
      try {
        val socket = streamClient.newWebSocket(request, object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(gate) {
              if (handle.stopped) { webSocket.cancel(); return }
              val error = runCatching { validateResponse(requestedUrl, response) }.exceptionOrNull()
              if (error != null) {
                handle.failureLocked(error)
                webSocket.cancel()
              } else handle.emitLocked { listener.onOpen(handle) }
            }
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(gate) { handle.emitLocked { listener.onText(text) } }
          }

          override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(gate) { handle.emitLocked { listener.onBytes(bytes.toByteArray()) } }
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(gate) {
              if (handle.stopped) return
              handle.closing = true
              // A peer close still needs its close-frame acknowledgement to release the socket.
              // 1005 means the peer sent no status; it is not a legal code in an outgoing frame.
              webSocket.close(if (code == 1005) 1000 else code, reason)
              handle.emitLocked { listener.onClosing(code, reason) }
            }
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(gate) { handle.emitLocked(terminal = true) { listener.onClosed(code, reason) } }
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(gate) {
              val error = response?.let { runCatching { validateResponse(requestedUrl, it) }.exceptionOrNull() } ?: t
              handle.failureLocked(error)
            }
          }
        })
        handle.attachLocked(socket)
      } catch (error: Throwable) {
        handle.failureLocked(error)
      }
    }
    return handle
  }

  override fun cancelGeneration(generation: Long) {
    synchronized(gate) {
      if (generation != 0L) cancelledGenerations += generation
      operations.filter { generation == 0L || it.generation == generation }.forEach {
        it.cancelLocked(NativeHttpGenerationCancelledException(it.generation))
      }
    }
  }

  override fun close() {
    synchronized(gate) {
      if (closed) return
      closed = true
      operations.toList().forEach { it.cancelLocked(CancellationException("原生 HTTP 传输已关闭")) }
    }
    // Clients share process-wide pools/dispatchers. Never cancelAll or shut down siblings here.
  }

  private fun requestBuilder(relativePath: String, headers: Map<String, String>): Request.Builder {
    requireNativeHttpHeaders(headers)
    val builder = Request.Builder().url(endpoint.resolve(relativePath))
    endpoint.requireSameOrigin(builder.build().url.toString())
    headers.forEach { (name, value) -> builder.header(name, value) }
    return builder
  }

  private fun validateResponse(requestedUrl: String, response: Response) {
    // A disabled redirect is still a rejected response, not a successful HTTP/SSE/WS payload.
    if (response.code in 300..399 || response.priorResponse != null) {
      throw NativeHttpRedirectException(requestedUrl, response.header("Location") ?: response.request.url.toString())
    }
    endpoint.requireNoRedirect(requestedUrl, response.request.url.toString())
  }

  /** Called with gate held, including for operations whose native handle is not attached yet. */
  private fun registerLocked(operation: Operation): Boolean {
    val error = when {
      closed -> CancellationException("原生 HTTP 传输已关闭")
      operation.generation != 0L && operation.generation in cancelledGenerations ->
        NativeHttpGenerationCancelledException(operation.generation)
      else -> null
    }
    if (error != null) { operation.cancelLocked(error); return false }
    operations += operation
    return true
  }

  private abstract inner class Operation(open val generation: Long) {
    var stopped = false
      private set

    fun stopLocked(): Boolean {
      if (stopped) return false
      stopped = true
      operations -= this
      return true
    }

    abstract fun cancelLocked(error: CancellationException)

    fun emitLocked(terminal: Boolean = false, callback: () -> Unit) {
      if (stopped) return
      if (terminal) stopLocked()
      try { callback() } catch (_: Throwable) {
        // A consumer exception must not leave a stream/socket running forever on OkHttp's thread.
        cancelLocked(CancellationException("原生传输回调失败"))
      }
    }
  }

  private inner class HttpCall(
    generation: Long,
    val call: Call,
    private val continuation: CancellableContinuation<NativeHttpResponse>
  ) : Operation(generation) {
    fun finishLocked(result: Result<NativeHttpResponse>) {
      if (stopLocked()) continuation.resumeWith(result)
    }

    override fun cancelLocked(error: CancellationException) {
      if (!stopLocked()) return
      call.cancel()
      continuation.cancel(error)
    }
  }

  private inner class EventStream(
    override val generation: Long,
    private val callback: NativeHttpEventStreamCallback
  ) : Operation(generation), NativeHttpEventStream {
    private var source: EventSource? = null

    fun attachLocked(source: EventSource) {
      if (stopped) source.cancel() else this.source = source
    }

    fun failureLocked(error: Throwable) {
      if (stopped) return
      source?.cancel()
      emitLocked(terminal = true) { callback.onFailure(error) }
    }

    override fun cancelLocked(error: CancellationException) {
      if (stopLocked()) source?.cancel()
    }

    override fun cancel() { synchronized(gate) { cancelLocked(NativeHttpGenerationCancelledException(generation)) } }
  }

  private inner class SocketChannel(
    override val generation: Long,
    private val listener: NativeWebSocketListener
  ) : Operation(generation), NativeWebSocketChannel {
    private var socket: WebSocket? = null
    var closing = false

    fun attachLocked(socket: WebSocket) {
      if (stopped) socket.cancel() else this.socket = socket
    }

    fun failureLocked(error: Throwable) {
      if (stopped) return
      socket?.cancel()
      emitLocked(terminal = true) { listener.onFailure(error) }
    }

    override fun sendText(text: String): Boolean = synchronized(gate) {
      !stopped && !closing && socket?.send(text) == true
    }

    override fun sendBytes(bytes: ByteArray): Boolean = synchronized(gate) {
      !stopped && !closing && socket?.send(bytes.toByteString()) == true
    }

    override fun close(code: Int, reason: String?): Boolean = synchronized(gate) {
      if (stopped || closing) return@synchronized false
      val accepted = socket?.close(code, reason) == true
      if (accepted) closing = true
      accepted
    }

    override fun cancelLocked(error: CancellationException) {
      if (stopLocked()) socket?.cancel()
    }

    override fun cancel() { synchronized(gate) { cancelLocked(NativeHttpGenerationCancelledException(generation)) } }
  }

  private fun Response.headersMap(): Map<String, String> = headers.names().associateWith { name -> header(name).orEmpty() }

  private fun okhttp3.ResponseBody.readBounded(limit: Long): ByteArray {
    val contentLength = contentLength()
    if (contentLength > limit) throw NativeHttpResponseTooLargeException(limit, contentLength)
    val source = source()
    if (source.request(limit + 1)) throw NativeHttpResponseTooLargeException(limit, limit + 1)
    return source.readByteArray().requireNativeHttpResponseBudget(limit)
  }
}
