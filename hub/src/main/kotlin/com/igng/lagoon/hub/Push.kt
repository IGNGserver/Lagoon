package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.Diagnostics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** P-256 helpers for VAPID (RFC 8292) and message encryption (RFC 8291), on the JDK alone. */
object P256 {
  private val random = SecureRandom()
  val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
    .getParameterSpec(ECParameterSpec::class.java)

  fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

  /** The 65-byte uncompressed point `04 || X || Y`. */
  fun encode(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

  fun publicKey(uncompressed: ByteArray): ECPublicKey {
    require(uncompressed.size == 65 && uncompressed[0] == 4.toByte()) { "无效的 P-256 公钥" }
    val point = ECPoint(BigInteger(1, uncompressed.copyOfRange(1, 33)), BigInteger(1, uncompressed.copyOfRange(33, 65)))
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
  }

  fun privateKey(scalar: ByteArray): ECPrivateKey =
    KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params)) as ECPrivateKey

  fun privateKeyPkcs8(encoded: ByteArray): ECPrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded)) as ECPrivateKey

  fun fixed(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    return when {
      bytes.size == 32 -> bytes
      bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
      else -> ByteArray(32 - bytes.size) + bytes
    }
  }

  fun ecdh(private: ECPrivateKey, public: ECPublicKey): ByteArray =
    KeyAgreement.getInstance("ECDH").apply { init(private); doPhase(public, true) }.generateSecret()
}

object B64 {
  fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value.trim().replace('+', '-').replace('/', '_').trimEnd('='))
}

/** RFC 8291 `aes128gcm` content encoding of one push message. */
object WebPushCrypto {
  private val random = SecureRandom()
  private const val RECORD_SIZE = 4096

  fun encrypt(payload: ByteArray, userPublic: ByteArray, authSecret: ByteArray,
    serverKeys: KeyPair = P256.generate(), salt: ByteArray = ByteArray(16).also(random::nextBytes)): ByteArray {
    require(payload.size <= RECORD_SIZE - 17 - 86) { "推送内容过长" }
    val serverPublic = P256.encode(serverKeys.public as ECPublicKey)
    val shared = P256.ecdh(serverKeys.private as ECPrivateKey, P256.publicKey(userPublic))
    val prkKey = hmac(authSecret, shared)
    val ikm = hmac(prkKey, "WebPush: info".toByteArray() + byteArrayOf(0) + userPublic + serverPublic + byteArrayOf(1))
    val prk = hmac(salt, ikm)
    val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
    val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
    // A single record: the content followed by the last-record delimiter 0x02, no padding.
    val encrypted = cipher.doFinal(payload + byteArrayOf(2))
    return ByteArrayOutputStream().apply {
      write(salt)
      write(ByteBuffer.allocate(4).putInt(RECORD_SIZE).array())
      write(serverPublic.size)
      write(serverPublic)
      write(encrypted)
    }.toByteArray()
  }

  private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}

/** VAPID (RFC 8292) application server identity. */
class Vapid(private val private: ECPrivateKey, val publicKey: ByteArray, private val subject: String) {
  val publicKeyBase64: String get() = B64.url(publicKey)

  /** `Authorization` header value for a push service at [endpoint]'s origin. */
  fun authorization(endpoint: String, now: Long = System.currentTimeMillis()): String {
    val url = requireNotNull(endpoint.toHttpUrlOrNull()) { "推送地址无效" }
    val audience = "${url.scheme}://${url.host}" + (if (url.port != okhttp3.HttpUrl.defaultPort(url.scheme)) ":${url.port}" else "")
    val header = B64.url("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
    val claims = B64.url(JSONObject().put("aud", audience).put("exp", now / 1000 + 12 * 3600).put("sub", subject).toString().toByteArray())
    val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(private); update("$header.$claims".toByteArray()) }
    return "vapid t=$header.$claims.${B64.url(derToRaw(signer.sign()))}, k=$publicKeyBase64"
  }

  companion object {
    /** Loads the hub's VAPID key pair, generating and saving one on first start. */
    fun load(store: HubStore, subject: String): Vapid {
      store.vapid()?.let { (public, private) ->
        return Vapid(P256.privateKeyPkcs8(Base64.getDecoder().decode(private)), B64.decode(public), subject)
      }
      val pair = P256.generate()
      val public = P256.encode(pair.public as ECPublicKey)
      store.saveVapid(B64.url(public), Base64.getEncoder().encodeToString(pair.private.encoded))
      return Vapid(pair.private as ECPrivateKey, public, subject)
    }

    /** JCA signs ES256 as an ASN.1 sequence of two integers; JWS wants R || S, 32 bytes each. */
    fun derToRaw(der: ByteArray): ByteArray {
      var offset = 2
      if (der[1].toInt() and 0x80 != 0) offset += der[1].toInt() and 0x7f
      fun integer(): ByteArray {
        require(der[offset].toInt() == 0x02) { "无效签名" }
        val length = der[offset + 1].toInt() and 0xff
        val value = der.copyOfRange(offset + 2, offset + 2 + length)
        offset += 2 + length
        return P256.fixed(BigInteger(1, value))
      }
      return integer() + integer()
    }
  }
}

/** Delivers notifications to every subscribed browser. */
class PushService(private val store: HubStore, val vapid: Vapid,
  private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()) {

  /** Sends [message] to all subscriptions (or just [only]); dead subscriptions are removed. */
  fun send(message: JSONObject, urgency: String = "normal", topic: String? = null, only: String? = null): Int {
    var delivered = 0
    val payload = message.toString().toByteArray()
    store.subscriptions().filter { only == null || it.endpoint == only }.forEach { subscription ->
      try {
        val body = WebPushCrypto.encrypt(payload, B64.decode(subscription.p256dh), B64.decode(subscription.auth))
        val request = Request.Builder().url(subscription.endpoint)
          .post(body.toRequestBody("application/octet-stream".toMediaType()))
          .header("Content-Encoding", "aes128gcm").header("TTL", "86400").header("Urgency", urgency)
          .header("Authorization", vapid.authorization(subscription.endpoint))
          .apply { topic?.let { header("Topic", it) } }
          .build()
        http.newCall(request).execute().use { response ->
          when {
            response.isSuccessful -> delivered++
            response.code == 404 || response.code == 410 -> {
              store.removeSubscription(subscription.endpoint)
              Diagnostics.info("Push", "订阅已失效，已移除（${subscription.label}）")
            }
            else -> Diagnostics.warn("Push", "推送失败：HTTP ${response.code}（${subscription.label}）")
          }
        }
      } catch (error: Exception) {
        Diagnostics.warn("Push", "推送失败（${subscription.label}）", error)
      }
    }
    return delivered
  }
}
