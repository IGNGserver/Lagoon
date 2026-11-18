package com.igng.opencode.lagoon.core

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreCipherTest {
  @Test
  fun wrapsDataKeyOnceAndDecryptsBothV2AndLegacyPayloads() {
    val prefs = memoryPreferences()
    val hwKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    val hwCalls = AtomicInteger(0)
    val hwEncrypt: (ByteArray) -> ByteArray = { plain ->
      hwCalls.incrementAndGet()
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.ENCRYPT_MODE, hwKey, GCMParameterSpec(128, iv))
      iv + c.doFinal(plain)
    }
    val hwDecrypt: (ByteArray) -> ByteArray = { payload ->
      hwCalls.incrementAndGet()
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.DECRYPT_MODE, hwKey, GCMParameterSpec(128, payload.copyOfRange(0, 12)))
      c.doFinal(payload.copyOfRange(12, payload.size))
    }

    val legacyEncoded = java.util.Base64.getEncoder().encodeToString(
      hwEncrypt("legacy-secret-token".toByteArray(Charsets.UTF_8))
    )
    hwCalls.set(0)

    val cipher = KeystoreCipher(
      alias = "test-alias",
      envelopePrefsProvider = { prefs },
      hardwareEncryptor = hwEncrypt,
      hardwareDecryptor = hwDecrypt
    )

    // 50 encrypt/decrypt cycles should touch the simulated hardware keystore only ONCE (initial DEK wrap).
    repeat(50) { index ->
      val enc = cipher.encrypt("message-$index")
      assertTrue(enc.startsWith("v2:"))
      assertEquals("message-$index", cipher.decrypt(enc))
    }
    assertEquals("Hardware Keystore HAL should only be invoked once to wrap the DEK", 1, hwCalls.get())

    // Restart simulation: new KeystoreCipher instance unwraps the existing DEK once and reads old + new ciphertexts.
    val restarted = KeystoreCipher(
      alias = "test-alias",
      envelopePrefsProvider = { prefs },
      hardwareEncryptor = hwEncrypt,
      hardwareDecryptor = hwDecrypt
    )
    val sampleV2 = cipher.encrypt("across-restart")
    hwCalls.set(0)
    assertEquals("across-restart", restarted.decrypt(sampleV2))
    assertEquals("Restarted instance unwraps DEK once", 1, hwCalls.get())

    // Legacy pre-v2 payload still decrypts transparently via hardware fallback.
    assertEquals("legacy-secret-token", restarted.decrypt(legacyEncoded))
    assertEquals(2, hwCalls.get())
  }
}
