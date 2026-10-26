package com.igng.lagoon.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebPushTest {
  /** RFC 8291 Appendix A: fixed keys and salt give a known message. */
  @Test fun encryptsTheRfc8291Example() {
    val serverPrivate = P256.privateKey(B64.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"))
    val serverPublic = P256.publicKey(B64.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"))
    val body = WebPushCrypto.encrypt(
      "When I grow up, I want to be a watermelon".toByteArray(),
      B64.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
      B64.decode("BTBZMqHH6r4Tts7J_aSIgg"),
      KeyPair(serverPublic, serverPrivate),
      B64.decode("DGv6ra1nlYgDCS1FRnbzlw"))
    // Header: salt, record size 4096, key id length 65, the server's public key; then the single record.
    assertArrayEquals(B64.decode("DGv6ra1nlYgDCS1FRnbzlw"), body.copyOfRange(0, 16))
    assertEquals(4096, ByteBuffer.wrap(body, 16, 4).int)
    assertEquals(65, body[20].toInt())
    assertArrayEquals(P256.encode(serverPublic), body.copyOfRange(21, 86))
    assertEquals("8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ", B64.url(body.copyOfRange(86, body.size)))
  }

  /** The browser side of RFC 8291 recovers the plaintext from a randomly keyed message. */
  @Test fun aBrowserCanDecryptAFreshMessage() {
    val browser = P256.generate()
    val auth = ByteArray(16) { it.toByte() }
    val payload = """{"title":"任务已完成","url":"/s/a/b"}""".toByteArray()
    val body = WebPushCrypto.encrypt(payload, P256.encode(browser.public as ECPublicKey), auth)
    assertArrayEquals(payload, decrypt(body, browser, auth))
  }

  @Test fun vapidTokensVerifyWithThePublishedKey() {
    val pair = P256.generate()
    val vapid = Vapid(pair.private as ECPrivateKey, P256.encode(pair.public as ECPublicKey), "mailto:test@example.com")
    val header = vapid.authorization("https://fcm.googleapis.com/fcm/send/abc", now = 1_000_000L)
    val token = header.substringAfter("t=").substringBefore(',')
    val (head, claims, signature) = token.split('.')
    val raw = B64.decode(signature)
    assertEquals(64, raw.size)
    val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update("$head.$claims".toByteArray()) }
    assertTrue(verifier.verify(der(raw)))
    val json = org.json.JSONObject(String(B64.decode(claims)))
    assertEquals("https://fcm.googleapis.com", json.getString("aud"))
    assertEquals(1_000L + 12 * 3600, json.getLong("exp"))
    assertTrue(header.endsWith("k=${vapid.publicKeyBase64}"))
  }

  @Test fun rawSignaturesArePaddedToFixedWidth() {
    // r and s with a leading 0x00 (high bit set) and a short value.
    val r = ByteArray(33).also { it[1] = 0x80.toByte() }
    val s = byteArrayOf(5)
    val der = byteArrayOf(0x30, (2 + r.size + 2 + s.size).toByte(), 0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
    val raw = Vapid.derToRaw(der)
    assertEquals(64, raw.size)
    assertEquals(0x80.toByte(), raw[0])
    assertEquals(5.toByte(), raw[63])
  }

  private fun der(raw: ByteArray): ByteArray {
    fun integer(bytes: ByteArray): ByteArray {
      val value = BigInteger(1, bytes).toByteArray()
      return byteArrayOf(0x02, value.size.toByte()) + value
    }
    val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
    return byteArrayOf(0x30, body.size.toByte()) + body
  }

  private fun decrypt(body: ByteArray, browser: KeyPair, auth: ByteArray): ByteArray {
    val salt = body.copyOfRange(0, 16)
    val idLength = body[20].toInt()
    val serverPublic = body.copyOfRange(21, 21 + idLength)
    val ciphertext = body.copyOfRange(21 + idLength, body.size)
    val browserPublic = P256.encode(browser.public as ECPublicKey)
    val shared = P256.ecdh(browser.private as ECPrivateKey, P256.publicKey(serverPublic))
    fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    val ikm = hmac(hmac(auth, shared), "WebPush: info".toByteArray() + byteArrayOf(0) + browserPublic + serverPublic + byteArrayOf(1))
    val prk = hmac(salt, ikm)
    val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
    val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
    assertEquals(4096, ByteBuffer.wrap(body, 16, 4).int)
    val plain = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(ciphertext)
    assertEquals(2.toByte(), plain.last())
    return plain.copyOf(plain.size - 1)
  }

}
