package com.igng.opencode.lagoon.core

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一台按 `ds-harness-remote` Remote Protocol v1 说话的假 Host：
 * Noise IK + 控制帧 + `relay` 加密帧 + DSHF 分片 + `harness.remote.*` 业务方法。
 * 用来在没有真中枢/真 Host 的情况下跑通 Lagoon 的 Remote 客户端链路。
 */
internal class FakeRemoteHost(
  val hostId: String = UUID.randomUUID().toString(),
  val hostIdentity: RemoteIdentity = RemoteIdentity.generate(),
  private val account: String = "tester@example.com",
  /** 为 true 时在 `stream.open` 响应之前就推送首帧（真 Host 的行为）。 */
  private val framesBeforeOpenResponse: Boolean = true,
  private val capabilities: List<String> = listOf(
    "harness.remote.v3", "harness.remote.transfer.v1", "workspace.upload.v1"
  )
) : AutoCloseable {
  val server = MockWebServer()
  val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val streamOpens: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
  val rpcCalls: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
  private val counters = AtomicInteger(0)
  private val fragmentIds = AtomicInteger(0)
  private var cipher: CipherStatePair? = null

  /** endpoint → 该流要推送的帧（值为 JSON 字符串或 JSONObject）。 */
  var streamFrames: (endpoint: String, payload: JSONObject) -> List<Any> = { _, _ -> emptyList() }
  /** `harness.remote.call` 的实现。 */
  var call: (endpoint: String, payload: JSONObject) -> JSONObject = { endpoint, _ -> ok(JSONObject()) }
  /** 设成非空时，该 endpoint 的流在 open 响应后立刻以 failed 关闭（模拟主机侧读失败）。 */
  var streamFailure: String? = null

  private val connectionId = UUID.randomUUID().toString()

  fun ok(value: Any?): JSONObject = JSONObject().put("ok", true).put("value", value ?: JSONObject.NULL)
  fun fail(code: String, message: String = code): JSONObject =
    JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message).put("details", JSONObject()))

  fun start() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        requests += "${request.method} ${request.path}"
        val path = request.path.orEmpty()
        return when {
          path.startsWith("/api/v1/devices/") -> MockResponse().setBody(JSONObject()
            .put("deviceId", hostId).put("name", "fake-host").put("platform", "linux").put("role", "host")
            .put("membershipId", "account:test").put("online", true)
            .put("identityKey", hostIdentity.publicKey).put("clientVersion", "0.6.0").put("harnessVersion", "0.2.1-rc.5")
            .put("fingerprint", "0000 1111 2222").toString())
          path == "/ws/v1/connect" -> MockResponse().withWebSocketUpgrade(listener)
          else -> MockResponse().setResponseCode(404).setBody("{}")
        }
      }
    }
    server.start()
  }

  val url: String get() = server.url("/").toString().trimEnd('/')

  private fun send(socket: WebSocket, type: String, payload: JSONObject) {
    socket.send(JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", type)
      .put("timestamp", System.currentTimeMillis()).put("payload", payload).toString())
  }

  private fun sendMessage(socket: WebSocket, message: JSONObject) {
    val pair = cipher ?: return
    val encoder = Base64.getUrlEncoder().withoutPadding()
    for (fragment in fragments(message.toString().toByteArray(Charsets.UTF_8))) {
      val encrypted = ByteArray(fragment.size + 16)
      val written = pair.sender.encryptWithAd(null, fragment, 0, encrypted, 0, fragment.size)
      send(socket, "relay", JSONObject().put("connectionId", connectionId)
        .put("targetDeviceId", clientDeviceId).put("counter", counters.getAndIncrement())
        .put("ciphertext", encoder.encodeToString(encrypted.copyOf(written))))
    }
  }

  private fun publish(socket: WebSocket, event: String, data: JSONObject) {
    sendMessage(socket, JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", "event")
      .put("timestamp", System.currentTimeMillis())
      .put("payload", JSONObject().put("event", event).put("data", data)))
  }

  private lateinit var clientDeviceId: String

  private val listener = object : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) {
      try {
        val frame = JSONObject(text)
        val payload = frame.getJSONObject("payload")
        when (frame.getString("type")) {
          "hello" -> {
            clientDeviceId = payload.getString("deviceId")
            send(webSocket, "hello.ack", JSONObject().put("protocol", 1)
              .put("capabilities", JSONArray().put("transport.relay"))
              .put("maxControlFrameBytes", 65536).put("maxRelayFrameBytes", 1048576)
              .put("heartbeatIntervalMs", 30000))
          }
          "connect.request" -> send(webSocket, "connect.accepted", JSONObject().put("connectionId", connectionId))
          "secure.handshake" -> {
            val responder = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER)
            responder.localKeyPair.setPrivateKey(Base64.getUrlDecoder().decode(hostIdentity.privateKey), 0)
            val prologue = RemoteNoise.prologue(connectionId, hostId, clientDeviceId)
            responder.setPrologue(prologue, 0, prologue.size)
            responder.start()
            val input = Base64.getUrlDecoder().decode(payload.getString("data"))
            responder.readMessage(input, 0, input.size, ByteArray(input.size), 0)
            val output = ByteArray(256)
            val length = responder.writeMessage(output, 0, byteArrayOf(), 0, 0)
            cipher = responder.split()
            send(webSocket, "secure.handshake", JSONObject().put("connectionId", connectionId)
              .put("targetDeviceId", clientDeviceId).put("step", 2)
              .put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(output.copyOf(length))))
          }
          "relay" -> {
            val pair = cipher ?: return
            val input = Base64.getUrlDecoder().decode(payload.getString("ciphertext"))
            val plaintext = ByteArray(input.size)
            val count = pair.receiver.decryptWithAd(null, input, 0, plaintext, 0, input.size)
            val message = JSONObject(String(plaintext, 0, count, Charsets.UTF_8))
            handleMessage(webSocket, message)
          }
          "ping" -> send(webSocket, "pong", JSONObject().put("nonce", payload.opt("nonce") ?: ""))
          else -> Unit
        }
      } catch (error: Throwable) {
        error.printStackTrace()
        webSocket.cancel()
      }
    }
  }

  private fun handleMessage(socket: WebSocket, message: JSONObject) {
    if (message.optString("type") != "rpc.request") return
    val id = message.getString("id")
    val payload = message.getJSONObject("payload")
    val method = payload.getString("method")
    val params = payload.optJSONObject("params") ?: JSONObject()
    rpcCalls += JSONObject().put("method", method).put("params", params)
    fun respond(result: JSONObject) {
      sendMessage(socket, JSONObject().put("v", 1).put("id", id).put("type", "rpc.response")
        .put("timestamp", System.currentTimeMillis())
        .put("payload", JSONObject().put("requestId", id).put("result", result)))
    }
    when (method) {
      "harness.transport.describe" -> respond(JSONObject().put("capabilities", JSONArray(capabilities)))
      "harness.remote.call" -> {
        val endpoint = params.getString("endpoint")
        respond(call(endpoint, params.optJSONObject("payload") ?: JSONObject()))
      }
      "harness.remote.stream.open" -> {
        streamOpens += params
        val streamId = params.getString("streamId")
        val endpoint = params.getString("endpoint")
        val frames = streamFrames(endpoint, params.optJSONObject("payload") ?: JSONObject())
        val failure = streamFailure
        if (failure != null) {
          respond(JSONObject().put("opened", true).put("streamId", streamId))
          publish(socket, "harness.remote.stream.closed", JSONObject().put("streamId", streamId)
            .put("reason", "failed")
            .put("failure", JSONObject().put("code", "session/read-failed").put("message", failure)))
        } else {
          if (framesBeforeOpenResponse) publishFrames(socket, streamId, frames)
          respond(JSONObject().put("opened", true).put("streamId", streamId))
          if (!framesBeforeOpenResponse) publishFrames(socket, streamId, frames)
        }
      }
      "harness.remote.stream.close", "harness.remote.transfer.close" ->
        respond(JSONObject().put("closed", true))
      "harness.remote.transfer.open", "harness.remote.transfer.chunk", "harness.remote.transfer.commit" -> respond(JSONObject())
      else -> respond(JSONObject().put("ok", true))
    }
  }

  private fun publishFrames(socket: WebSocket, streamId: String, frames: List<Any>) {
    for (value in frames) {
      publish(socket, "harness.remote.frame", JSONObject().put("streamId", streamId).put("hasValue", true)
        .put("value", value))
    }
  }

  /** DSHF：4 字节 magic + 版本 + messageId + index + total + 原始长度，块 48 KiB。 */
  private fun fragments(message: ByteArray): List<ByteArray> {
    if (message.size <= CHUNK) return listOf(message)
    val id = fragmentIds.incrementAndGet()
    val total = (message.size + CHUNK - 1) / CHUNK
    return (0 until total).map { index ->
      val offset = index * CHUNK
      val length = minOf(CHUNK, message.size - offset)
      ByteBuffer.allocate(17 + length).put(byteArrayOf(0x44, 0x53, 0x48, 0x46)).put(1)
        .putInt(id).putShort(index.toShort()).putShort(total.toShort()).putInt(message.size)
        .put(message, offset, length).array()
    }
  }

  fun accountEmail(): String = account

  override fun close() { server.shutdown() }

  private companion object { const val CHUNK = 48 * 1024 }
}
