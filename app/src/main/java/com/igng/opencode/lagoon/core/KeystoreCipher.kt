package com.igng.opencode.lagoon.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM encryption backed by a per-alias AndroidKeyStore key, storing `iv || ciphertext` as Base64.
 *
 * Extracted from ServerStore and OfflineCache, which had byte-identical copies of this routine.
 * Shared per alias via [of] and synchronized per key so concurrent callers never race KeyGenerator
 * or AndroidKeyStore's Binder-backed Cipher HAL.
 */
internal class KeystoreCipher(private val alias: String, private val legacyAlias: String? = null) {
  private val key: SecretKey by lazy {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val existing = (store.getKey(alias, null) as? SecretKey)
      ?: legacyAlias?.let { store.getKey(it, null) as? SecretKey }
    existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
      generateKey()
    }
  }

  @Synchronized
  fun encrypt(value: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
  }

  @Synchronized
  fun decrypt(encoded: String): String {
    val payload = Base64.decode(encoded, Base64.NO_WRAP)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
    return String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
  }

  companion object {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val instances = ConcurrentHashMap<String, KeystoreCipher>()

    fun of(alias: String, legacyAlias: String? = null): KeystoreCipher =
      instances.computeIfAbsent(alias) { KeystoreCipher(alias, legacyAlias) }
  }
}
