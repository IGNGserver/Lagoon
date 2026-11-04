package com.igng.opencode.lagoon.core

import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class RemoteRelayTest {
  @Test fun establishesAuthenticatedRelayAndExchangesEncryptedMessages() = runBlocking {
    val client = RemoteIdentity.generate()
    val host = RemoteIdentity.generate()
    val connection = UUID.randomUUID().toString()
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val decoder = Base64.getUrlDecoder()
    val received = CompletableDeferred<String>()
    val hostFailure = CompletableDeferred<Throwable>()
    val responder = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER)
    responder.localKeyPair.setPrivateKey(decoder.decode(host.privateKey), 0)
    val prologue = RemoteNoise.prologue(connection, host.deviceId, client.deviceId)
    responder.setPrologue(prologue, 0, prologue.size)
    responder.start()
    MockWebServer().use { server ->
      server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        private fun send(socket: WebSocket, type: String, payload: JSONObject) {
          socket.send(JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", type)
            .put("timestamp", System.currentTimeMillis()).put("payload", payload).toString())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
          try {
            val frame = JSONObject(text)
            val p = frame.getJSONObject("payload")
            when (frame.getString("type")) {
              "hello" -> send(webSocket, "hello.ack", JSONObject().put("protocol", 1).put("capabilities", JSONArray().put("transport.relay"))
                .put("maxControlFrameBytes", 65536).put("maxRelayFrameBytes", 1048576))
              "connect.request" -> send(webSocket, "connect.accepted", JSONObject().put("connectionId", connection))
              "secure.handshake" -> {
                val input = decoder.decode(p.getString("data"))
                responder.readMessage(input, 0, input.size, ByteArray(input.size), 0)
                val output = ByteArray(256)
                val length = responder.writeMessage(output, 0, byteArrayOf(), 0, 0)
                val actualClient = ByteArray(32).also { responder.remotePublicKey.getPublicKey(it, 0) }
                assertEquals(client.publicKey, encoder.encodeToString(actualClient))
                val pair = responder.split()
                cipherPair = pair
                send(webSocket, "secure.handshake", JSONObject().put("connectionId", connection).put("targetDeviceId", client.deviceId)
                  .put("step", 2).put("data", encoder.encodeToString(output.copyOf(length))))
              }
              "relay" -> {
                val input = decoder.decode(p.getString("ciphertext"))
                val plaintext = ByteArray(input.size)
                val count = cipherPair!!.receiver.decryptWithAd(null, input, 0, plaintext, 0, input.size)
                assertEquals("request", String(plaintext, 0, count))
                val answer = "response".toByteArray()
                val encrypted = ByteArray(answer.size + 16)
                val written = cipherPair!!.sender.encryptWithAd(null, answer, 0, encrypted, 0, answer.size)
                send(webSocket, "relay", JSONObject().put("connectionId", connection).put("targetDeviceId", client.deviceId)
                  .put("counter", 0).put("ciphertext", encoder.encodeToString(encrypted.copyOf(written))))
              }
            }
          } catch (error: Throwable) { hostFailure.complete(error); webSocket.cancel() }
        }
        private var cipherPair: com.southernstorm.noise.protocol.CipherStatePair? = null
      }))
      server.start()
      val relay = RemoteRelay(server.url("/").toString(), client, "device-access-token-123", host.deviceId, host.publicKey, true,
        { received.complete(String(it)) }, { if (!received.isCompleted) received.completeExceptionally(it) })
      try {
        relay.connect()
        relay.send("request".toByteArray())
        assertEquals("response", withTimeout(5000) { received.await() })
        assertFalse(hostFailure.isCompleted)
      } finally { relay.close(); responder.destroy() }
    }
  }
}
