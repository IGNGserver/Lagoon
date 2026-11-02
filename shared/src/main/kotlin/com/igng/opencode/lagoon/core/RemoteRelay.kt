package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Relay over the authenticated control socket. Separate relay endpoints are not yet supported. */
internal class RemoteRelay(
  address: String,
  private val identity: RemoteIdentity,
  private val token: String,
  private val hostId: String,
  private val pinnedHostKey: String,
  allowCleartext: Boolean = false,
  private val onMessage: (ByteArray) -> Unit,
  private val onClose: (IOException) -> Unit
) : RemoteSecureChannel {
  private val base = requireNotNull(address.trim().toHttpUrlOrNull()) { "DSH Hub 地址无效" }
  private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectionPool(SharedHttp.connectionPool).dispatcher(SharedHttp.dispatcher).build()
  private val ready = CompletableDeferred<Unit>()
  private val sendLock = Mutex()
  private val incoming = RemoteSecureFragments()
  private val outgoing = RemoteSecureFragments()
  private var socket: WebSocket? = null
  private var noise: RemoteNoise? = null
  private var connectionId: String? = null
  private var outgoingCounter = 0L
  private var incomingCounter = -1L
  private var incomingHandshakeStep = 0
  private var controlLimit = 65536
  private var relayLimit = 1048576
  private var state = State.NEW
  private enum class State { NEW, HELLO, CONNECT, HANDSHAKE, READY, CLOSED }

  init {
    require(base.isHttps || allowCleartext) { "DSH Hub HTTP 明文连接未获授权" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.encodedPath == "/" && base.query == null && base.fragment == null)
    require(RemoteServerApi.validDeviceId(identity.deviceId) && RemoteServerApi.validDeviceId(hostId))
    require(RemoteServerApi.validToken(token) && RemoteServerApi.validPublicKey(pinnedHostKey))
  }

  suspend fun connect(timeoutMillis: Long = 20_000) {
    require(timeoutMillis in 1..120_000)
    synchronized(this) {
      check(state == State.NEW) { "Remote 连接不可重复使用" }
      state = State.HELLO
      val request = Request.Builder().url(base.newBuilder().addPathSegments("ws/v1/connect").build()).build()
      socket = http.newWebSocket(request, listener)
    }
    try { withTimeout(timeoutMillis) { ready.await() } }
    catch (error: Exception) { fail(IOException("Remote 连接未建立", error)); throw error }
  }

  override suspend fun send(message: ByteArray) = sendLock.withLock {
    try {
      synchronized(this) {
        if (state != State.READY) throw IOException("Remote 连接未就绪")
        val id = connectionId ?: throw IOException("Remote 连接无效")
        val session = noise ?: throw IOException("Remote 安全通道无效")
        for (part in outgoing.encode(message)) {
          if (outgoingCounter > MAX_COUNTER) throw IOException("Remote 计数器耗尽")
          val encrypted = session.encrypt(part)
          sendFrame("relay", JSONObject().put("connectionId", id).put("targetDeviceId", hostId)
            .put("counter", outgoingCounter++).put("ciphertext", encode(encrypted)), relayLimit)
        }
      }
    } catch (error: Exception) { fail(IOException("Remote 发送失败", error)); throw error }
  }

  override fun close() = fail(IOException("Remote 连接已关闭"))

  private val listener = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      try {
        synchronized(this@RemoteRelay) {
          if (state != State.HELLO) return
          socket = webSocket
          sendFrame("hello", JSONObject().put("role", "client").put("deviceId", identity.deviceId)
            .put("accessToken", token).put("protocols", JSONArray().put(1))
            .put("capabilities", JSONArray().put("transport.relay")), controlLimit)
        }
      } catch (error: Exception) { fail(IOException("Remote 认证失败", error)) }
    }
    override fun onMessage(webSocket: WebSocket, text: String) {
      try { handle(text) } catch (error: Exception) { fail(IOException("Remote 协议失败", error)) }
    }
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail(IOException("Remote 不接受二进制控制帧")) }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(IOException("Remote WebSocket 失败", t)) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(IOException("Remote WebSocket 已关闭")) }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); fail(IOException("Remote WebSocket 正在关闭")) }
  }

  private fun handle(text: String) {
    var message: ByteArray? = null
    synchronized(this) {
      if (state == State.CLOSED) return
      if (text.toByteArray(Charsets.UTF_8).size > relayLimit) throw IOException("Remote 帧超过上限")
      val frame = try { JSONObject(text) } catch (_: Exception) { throw IOException("Remote 帧不是有效 JSON") }
      if (frame.keys().asSequence().toSet() != setOf("v", "id", "type", "timestamp", "payload") || frame.opt("v") != 1 ||
        (frame.opt("id") as? String).isNullOrEmpty() || (frame.opt("timestamp") as? Number)?.toLong()?.let { it > 0 } != true)
        throw IOException("Remote 帧信封无效")
      val type = frame.opt("type") as? String ?: throw IOException("Remote 帧类型无效")
      val frameBytes = text.toByteArray(Charsets.UTF_8).size
      if ((state == State.HELLO && frameBytes > 65536) || (state != State.HELLO && frameBytes > controlLimit && type != "relay"))
        throw IOException("Remote 控制帧超过上限")
      if (frameBytes > relayLimit) throw IOException("Remote 帧超过中继上限")
      val payload = frame.optJSONObject("payload") ?: throw IOException("Remote 帧内容无效")
      when (type) {
        "ping" -> sendFrame("pong", JSONObject().put("nonce", textField(payload, "nonce")), controlLimit)
        "error", "connect.rejected" -> throw IOException("DSH Hub 拒绝连接")
        "hello.ack" -> {
          if (state != State.HELLO || payload.opt("protocol") != 1) throw IOException("Remote 握手顺序无效")
          controlLimit = limit(payload, "maxControlFrameBytes", 65536)
          relayLimit = limit(payload, "maxRelayFrameBytes", 1048576)
          val caps = payload.optJSONArray("capabilities")
          if (caps != null && (caps.length() != 1 || caps.opt(0) != "transport.relay")) throw IOException("DSH Hub 的 Relay 协商无效")
          state = State.CONNECT
          sendFrame("connect.request", JSONObject().put("hostDeviceId", hostId).put("preferredTransports", JSONArray().put("relay")), controlLimit)
        }
        "connect.accepted" -> {
          if (state != State.CONNECT) throw IOException("Remote 握手顺序无效")
          if (payload.has("relayEndpoint") || payload.has("relayTicket")) throw IOException("暂不支持独立 Relay 端点")
          val id = textField(payload, "connectionId")
          connectionId = id
          val session = RemoteNoise(identity, hostId, pinnedHostKey, id)
          noise = session
          state = State.HANDSHAKE
          sendFrame("secure.handshake", JSONObject().put("connectionId", id).put("targetDeviceId", hostId)
            .put("step", 1).put("data", encode(session.writeHandshake())), controlLimit)
        }
        "secure.handshake" -> {
          if (state != State.HANDSHAKE || payload.opt("step") != 2 || incomingHandshakeStep != 0) throw IOException("Remote 握手顺序无效")
          checkTarget(payload)
          noise!!.readHandshake(decode(textField(payload, "data")))
          incomingHandshakeStep = 2
          state = State.READY
          ready.complete(Unit)
        }
        "relay" -> {
          if (state != State.READY) throw IOException("Remote 数据帧顺序无效")
          checkTarget(payload)
          val counter = payload.opt("counter") as? Number ?: throw IOException("Remote 计数器无效")
          val n = counter.toLong()
          if (counter.toDouble() != n.toDouble() || n > MAX_COUNTER) throw IOException("Remote 计数器无效")
          if (n == incomingCounter + 1) {
            incomingCounter = n
            message = incoming.decode(noise!!.decrypt(decode(textField(payload, "ciphertext"))))
          } else if (n > incomingCounter + 1) throw IOException("Remote 计数器跳号")
        }
        else -> throw IOException("Remote 收到不支持的控制帧")
      }
    }
    message?.let(onMessage)
  }

  private fun checkTarget(payload: JSONObject) {
    if (payload.opt("connectionId") != connectionId || payload.opt("targetDeviceId") != identity.deviceId) throw IOException("Remote 目标身份不匹配")
  }
  private fun sendFrame(type: String, payload: JSONObject, limit: Int) {
    val text = JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", type)
      .put("timestamp", System.currentTimeMillis()).put("payload", payload).toString()
    val max = if (type == "relay") relayLimit else limit
    if (text.toByteArray(Charsets.UTF_8).size > max || socket?.send(text) != true) throw IOException("Remote 帧发送失败")
  }
  private fun fail(error: IOException) {
    val toClose = synchronized(this) {
      if (state == State.CLOSED) return
      state = State.CLOSED
      ready.completeExceptionally(error)
      noise?.close(); noise = null
      socket.also { socket = null }
    }
    toClose?.cancel()
    onClose(error)
  }
  private fun textField(obj: JSONObject, name: String): String = (obj.opt(name) as? String)
    ?.takeIf { it.isNotEmpty() && it.length <= 100_000 && '\u0000' !in it } ?: throw IOException("Remote 字段无效")
  private fun limit(obj: JSONObject, name: String, max: Int): Int {
    val number = obj.opt(name) as? Number ?: throw IOException("Remote 帧限制无效")
    val n = number.toInt()
    if (n !in 1..max || number.toDouble() != n.toDouble()) throw IOException("Remote 帧限制无效")
    return n
  }
  private fun encode(data: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
  private fun decode(data: String): ByteArray {
    val decoded = try { Base64.getUrlDecoder().decode(data) } catch (_: IllegalArgumentException) { throw IOException("Remote 编码无效") }
    if (encode(decoded) != data) throw IOException("Remote 编码无效")
    return decoded
  }
  private companion object { const val MAX_COUNTER = 9007199254740991L }
}
