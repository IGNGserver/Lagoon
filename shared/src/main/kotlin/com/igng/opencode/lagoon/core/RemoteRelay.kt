package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
  private val allowCleartext: Boolean = false,
  private val onMessage: (ByteArray) -> Unit,
  private val onClose: (IOException) -> Unit,
  /** Sent in `hello`; the Hub stores it as the peer version and shows it in the device list. */
  private val clientVersion: String = ""
) : RemoteSecureChannel {
  private val base = requireNotNull(address.trim().toHttpUrlOrNull()) { "DSH Hub 地址无效" }
  private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    // Control/data sockets are long-lived. The bounded HTTP dispatcher is for finite RPCs only;
    // sharing it lets several Remote hosts starve ordinary requests and prevents liveness pings.
    .connectionPool(SharedHttp.connectionPool).dispatcher(SharedHttp.streamDispatcher).build()
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
  /** 无法识别的控制帧计数（诊断用）。 */
  internal val unsupportedFrames = java.util.concurrent.atomic.AtomicInteger()
  /** `hello.ack` 上报的中枢版本，仅用于诊断。 */
  @Volatile internal var serverVersion: String? = null
    private set
  /** 与本链路实际协商出的能力（永远是客户端所报能力的子集）。 */
  @Volatile internal var negotiatedCapabilities: Set<String> = emptySet()
    private set
  /** `relay` 表示在控制通道上中继；独立数据通道就绪后仍是 `relay`，由 [relayAttached] 区分。 */
  @Volatile internal var transport: String = "relay"
    private set
  @Volatile internal var relayAttached: Boolean = false
    private set
  /** 中枢通过 `connect.accepted` 下发的独立 Relay 数据通道（`relayEndpoint` + `relayTicket`）。 */
  private var dataSocket: WebSocket? = null
  private var dataReady = false
  private var relayTicket: String? = null
  private var relayEpoch: Long? = null
  private var attachSignal: CompletableDeferred<Unit>? = null
  private enum class State { NEW, HELLO, CONNECT, ASSIGNED, HANDSHAKE, READY, CLOSED }

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
          // 独立数据通道就绪后，业务密文只走它；控制通道继续承载 ping/握手等控制帧。
          sendFrameOn(if (dataReady) dataSocket else socket, "relay", JSONObject().put("connectionId", id)
            .put("targetDeviceId", hostId).put("counter", outgoingCounter++).put("ciphertext", encode(encrypted)), relayLimit)
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
            .put("capabilities", JSONArray().also { array -> CLIENT_CAPABILITIES.forEach(array::put) })
            .apply { if (clientVersion.isNotBlank()) put("clientVersion", clientVersion.take(64)) }, controlLimit)
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
      // 必需字段齐全即可；中枢后续新增字段不应让整条连接失效（通道本身已认证并加密）。
      if (!listOf("v", "id", "type", "timestamp", "payload").all { frame.has(it) } || frame.opt("v") != 1 ||
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
          serverVersion = (payload.opt("serverVersion") as? String)?.take(128)?.takeIf { it.isNotBlank() }
          negotiatedCapabilities = negotiateCapabilities(payload.optJSONArray("capabilities"))
          if ("transport.relay" !in negotiatedCapabilities) throw IOException("DSH Hub 没有协商 Relay 传输")
          state = State.CONNECT
          sendFrame("connect.request", JSONObject().put("hostDeviceId", hostId).put("preferredTransports", JSONArray().put("relay")), controlLimit)
        }
        "connect.accepted" -> {
          if (state != State.CONNECT) throw IOException("Remote 握手顺序无效")
          val id = textField(payload, "connectionId")
          connectionId = id
          val session = RemoteNoise(identity, hostId, pinnedHostKey, id)
          noise = session
          val endpoint = (payload.opt("relayEndpoint") as? String)?.trim()?.takeIf { it.isNotEmpty() }
          val ticket = (payload.opt("relayTicket") as? String)?.takeIf { it.isNotEmpty() }
          if (endpoint != null && ticket != null) {
            // 中枢要求把密文搬到独立数据通道：先附加，拿到 relay.ready 后再发 transport.selected 与握手。
            relayTicket = ticket
            relayEpoch = (payload.opt("relayEpoch") as? Number)?.toLong()
            state = State.ASSIGNED
            SharedHttp.ioScope.launch { attachRelay(endpoint) }
          } else {
            state = State.HANDSHAKE
            sendFrame("secure.handshake", JSONObject().put("connectionId", id).put("targetDeviceId", hostId)
              .put("step", 1).put("data", encode(session.writeHandshake())), controlLimit)
          }
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
        // 未知控制帧（新版本中枢的扩展）忽略并计数，不影响已建立的其他流程。
        else -> unsupportedFrames.incrementAndGet()
      }
    }
    message?.let(onMessage)
  }

  /**
   * 独立 Relay 数据通道：按参考实现 `relay.attach → relay.ready → transport.selected → secure.handshake`
   * 的顺序建立。失败必须明确报出来，不能再以“暂不支持”直接放弃整条连接。
   */
  private suspend fun attachRelay(endpoint: String) {
    try {
      val url = requireNotNull(endpoint.toHttpUrlOrNull()) { "中枢下发的 Relay 地址无效" }
      require(url.scheme == "https" || (url.scheme == "http" && allowCleartext)) { "Relay 数据通道要求 HTTPS（或显式允许明文）" }
      require(url.username.isEmpty() && url.password.isEmpty()) { "Relay 地址不能包含凭据" }
      val signal = CompletableDeferred<Unit>()
      synchronized(this) {
        if (state != State.ASSIGNED) throw IOException("Relay 数据通道状态无效")
        attachSignal = signal
      }
      val socket = http.newWebSocket(Request.Builder().url(url).build(), dataListener)
      synchronized(this) {
        if (state != State.ASSIGNED) { socket.cancel(); throw IOException("Relay 数据通道状态无效") }
        dataSocket = socket
      }
      withTimeout(RELAY_ATTACH_TIMEOUT_MILLIS) { signal.await() }
      synchronized(this) {
        if (state != State.ASSIGNED) throw IOException("Relay 数据通道状态无效")
        val id = connectionId ?: throw IOException("Relay 数据通道没有连接标识")
        val session = noise ?: throw IOException("Relay 数据通道没有安全会话")
        state = State.HANDSHAKE
        sendFrame("transport.selected", JSONObject().put("connectionId", id)
          .put("targetDeviceId", hostId).put("transport", "relay"), controlLimit)
        sendFrame("secure.handshake", JSONObject().put("connectionId", id).put("targetDeviceId", hostId)
          .put("step", 1).put("data", encode(session.writeHandshake())), controlLimit)
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      fail(IOException("Relay 数据通道建立失败", error))
    }
  }

  /** 数据通道只承载 `relay`/`relay.ready`/`relay.detach`/`ping`/`pong`，其余交给统一控制帧处理。 */
  private val dataListener = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      try {
        synchronized(this@RemoteRelay) {
          val id = connectionId ?: throw IOException("Relay 数据通道没有连接标识")
          val ticket = relayTicket ?: throw IOException("Relay 数据通道没有票据")
          sendFrameOn(webSocket, "relay.attach", JSONObject().put("connectionId", id)
            .put("deviceId", identity.deviceId).put("peerDeviceId", hostId).put("ticket", ticket), controlLimit)
        }
      } catch (error: Exception) { fail(IOException("Relay 数据通道附加失败", error)) }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      try {
        val probe = try { JSONObject(text) } catch (_: Exception) { throw IOException("Relay 数据帧不是有效 JSON") }
        if (probe.optString("type") == "relay.ready") {
          val payload = probe.optJSONObject("payload") ?: throw IOException("Relay 数据通道应答无效")
          synchronized(this@RemoteRelay) {
            if (state != State.ASSIGNED) throw IOException("Relay 数据通道状态无效")
            if (payload.opt("connectionId") != connectionId) throw IOException("Relay 数据通道连接标识不一致")
            val epoch = (payload.opt("epoch") as? Number)?.toLong()
            if (relayEpoch != null && epoch != relayEpoch) throw IOException("Relay 数据通道代次不一致")
            dataReady = true
            relayAttached = true
            attachSignal?.complete(Unit)
          }
          return
        }
        if (probe.optString("type") == "relay.detach") throw IOException("Relay 数据通道被对端断开")
        handle(text)
      } catch (error: Exception) { fail(IOException("Relay 数据通道失败", error)) }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      attachSignal?.completeExceptionally(IOException("Relay 数据通道连接失败", t))
      fail(IOException("Relay 数据通道连接失败", t))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      if (state != State.CLOSED) fail(IOException("Relay 数据通道已关闭（$code）"))
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(code, null)
      if (state != State.CLOSED) fail(IOException("Relay 数据通道正在关闭"))
    }
  }

  /**
   * The Hub may select a subset of what we offered, and older Hubs omit the field entirely
   * (which means plain Relay). Never accept a capability we did not offer: that would let a
   * broken peer select a transport this client cannot actually use.
   */
  private fun negotiateCapabilities(raw: JSONArray?): Set<String> {
    if (raw == null) return setOf("transport.relay")
    if (raw.length() > MAX_CAPABILITIES) throw IOException("DSH Hub 的能力列表超过上限")
    val selected = LinkedHashSet<String>()
    for (index in 0 until raw.length()) {
      val value = raw.opt(index) as? String ?: throw IOException("DSH Hub 的能力列表无效")
      if (value.length !in 1..128 || value !in CLIENT_CAPABILITIES) throw IOException("DSH Hub 选择了未提供的能力")
      if (!selected.add(value)) throw IOException("DSH Hub 的能力列表重复")
    }
    return selected
  }

  private fun checkTarget(payload: JSONObject) {
    if (payload.opt("connectionId") != connectionId || payload.opt("targetDeviceId") != identity.deviceId) throw IOException("Remote 目标身份不匹配")
  }
  private fun sendFrame(type: String, payload: JSONObject, limit: Int) = sendFrameOn(socket, type, payload, limit)

  private fun sendFrameOn(target: WebSocket?, type: String, payload: JSONObject, limit: Int) {
    val text = JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", type)
      .put("timestamp", System.currentTimeMillis()).put("payload", payload).toString()
    val max = if (type == "relay") relayLimit else limit
    if (text.toByteArray(Charsets.UTF_8).size > max || target?.send(text) != true) throw IOException("Remote 帧发送失败")
  }

  private fun fail(error: IOException) {
    val closing = synchronized(this) {
      if (state == State.CLOSED) return
      state = State.CLOSED
      ready.completeExceptionally(error)
      attachSignal?.completeExceptionally(error)
      dataReady = false
      noise?.close(); noise = null
      (socket.also { socket = null }) to (dataSocket.also { dataSocket = null })
    }
    closing.first?.cancel()
    closing.second?.cancel()
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
  private companion object {
    val CLIENT_CAPABILITIES = setOf("transport.relay", "transport.relay.attach")
    const val MAX_CAPABILITIES = 128
    const val MAX_COUNTER = 9007199254740991L
    /** 与参考实现一致：数据通道附加超时 15 秒。 */
    const val RELAY_ATTACH_TIMEOUT_MILLIS = 15_000L
  }
}
