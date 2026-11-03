package com.igng.opencode.lagoon.core

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** X25519 key material; callers must encrypt privateKey at rest and never log it. */
internal class RemoteIdentity(val deviceId: String, val publicKey: String, val privateKey: String) {
  override fun toString(): String = "RemoteIdentity(deviceId=$deviceId, publicKey=$publicKey, privateKey=<redacted>)"
  companion object {
    fun generate(): RemoteIdentity {
      val dh = Noise.createDH("25519")
      try {
        dh.generateKeyPair()
        val privateKey = ByteArray(32).also { dh.getPrivateKey(it, 0) }
        val publicKey = ByteArray(32).also { dh.getPublicKey(it, 0) }
        return try { RemoteIdentity(RemoteClientDescriptor.newId(), base64(publicKey), base64(privateKey)) }
        finally { privateKey.fill(0) }
      } finally { dh.destroy() }
    }
  }
}

/** Client-side Noise IK session. Failure is terminal; never reuse nonce state after an error. */
internal class RemoteNoise(
  identity: RemoteIdentity,
  hostId: String,
  hostPublicKey: String,
  connectionId: String,
  fixedEphemeralForTest: ByteArray? = null
) : AutoCloseable {
  private val handshake = HandshakeState("Noise_IK_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)
  private var transport: CipherStatePair? = null
  private var closed = false

  init {
    require(listOf(identity.deviceId, hostId).all { it.isNotEmpty() && '\u0000' !in it }) { "Remote 设备 ID 无效" }
    require(RemoteServerApi.validPublicKey(identity.publicKey) && RemoteServerApi.validPublicKey(hostPublicKey)) { "Remote 公钥无效" }
    val private = decodeKey(identity.privateKey)
    try {
      handshake.localKeyPair.setPrivateKey(private, 0)
      val derived = ByteArray(32).also { handshake.localKeyPair.getPublicKey(it, 0) }
      require(MessageDigest.isEqual(derived, decodeKey(identity.publicKey))) { "Remote 设备密钥不匹配" }
      handshake.remotePublicKey.setPublicKey(decodeKey(hostPublicKey), 0)
      val prologue = prologue(connectionId, hostId, identity.deviceId)
      handshake.setPrologue(prologue, 0, prologue.size)
      if (fixedEphemeralForTest != null) {
        require(fixedEphemeralForTest.size == 32)
        handshake.fixedEphemeralKey.setPrivateKey(fixedEphemeralForTest, 0)
      }
      handshake.start()
    } catch (error: Throwable) {
      handshake.destroy()
      throw error
    } finally { private.fill(0) }
  }

  @Synchronized fun writeHandshake(payload: ByteArray = byteArrayOf()): ByteArray = guarded {
    if (handshake.action != HandshakeState.WRITE_MESSAGE || payload.size > 65439) throw IOException("Noise 握手状态无效")
    val buffer = ByteArray(payload.size + 128)
    buffer.copyOf(handshake.writeMessage(buffer, 0, payload, 0, payload.size))
  }

  @Synchronized fun readHandshake(message: ByteArray): ByteArray = guarded {
    if (handshake.action != HandshakeState.READ_MESSAGE || message.size > 65535) throw IOException("Noise 握手状态无效")
    val payload = ByteArray(message.size)
    val size = handshake.readMessage(message, 0, message.size, payload, 0)
    if (handshake.action != HandshakeState.SPLIT) throw IOException("Noise 握手未完成")
    transport = handshake.split()
    payload.copyOf(size)
  }

  @Synchronized fun encrypt(plaintext: ByteArray): ByteArray = guarded {
    if (plaintext.size > 65519) throw IOException("Noise 消息超过上限")
    val cipher = transport?.sender ?: throw IOException("Noise 握手未完成")
    val result = ByteArray(plaintext.size + 16)
    result.copyOf(cipher.encryptWithAd(null, plaintext, 0, result, 0, plaintext.size))
  }

  @Synchronized fun decrypt(ciphertext: ByteArray): ByteArray = guarded {
    if (ciphertext.size !in 16..65535) throw IOException("Noise 密文长度无效")
    val cipher = transport?.receiver ?: throw IOException("Noise 握手未完成")
    val result = ByteArray(ciphertext.size)
    result.copyOf(cipher.decryptWithAd(null, ciphertext, 0, result, 0, ciphertext.size))
  }

  private inline fun <T> guarded(block: () -> T): T {
    if (closed) throw IOException("Noise 连接已关闭")
    return try { block() } catch (error: Exception) {
      close()
      throw IOException("Noise 安全通道失败", error)
    }
  }

  @Synchronized override fun close() {
    if (closed) return
    closed = true
    transport?.destroy()
    transport = null
    handshake.destroy()
  }

  companion object {
    internal fun prologue(connectionId: String, hostId: String, clientId: String): ByteArray {
      require(listOf(connectionId, hostId, clientId).all { it.isNotEmpty() && '\u0000' !in it }) { "Remote 连接标识无效" }
      return "DSH-REMOTE\u0000v=1\u0000connection=$connectionId\u0000host=$hostId\u0000client=$clientId".toByteArray(Charsets.UTF_8)
    }
  }
}

private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun decodeKey(value: String): ByteArray {
  require(RemoteServerApi.validPublicKey(value)) { "Remote 密钥无效" }
  return Base64.getUrlDecoder().decode(value)
}
