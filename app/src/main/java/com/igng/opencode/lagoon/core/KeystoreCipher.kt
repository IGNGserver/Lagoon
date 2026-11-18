package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope AES-256-GCM encryption:
 * - Generates a 256-bit random data encryption key (DEK) per alias, wraps it once via the
 *   hardware-backed AndroidKeyStore key, and caches the unwrapped [SecretKeySpec] in process RAM.
 * - Subsequent [encrypt]/[decrypt] operations run in-memory via JCE AES/GCM/NoPadding (~0.01ms,
 *   lock-free, zero Binder IPC to KeyMint/TrustZone HAL), prefixed with `v2:`.
 * - Pre-existing ciphertexts without `v2:` transparently fall back to direct AndroidKeyStore
 *   decryption so upgrades preserve 100% of stored credentials, drafts, previews, and caches.
 */
internal class KeystoreCipher internal constructor(
  private val alias: String,
  private val legacyAlias: String? = null,
  private val envelopePrefsProvider: () -> SharedPreferences? = { globalEnvelopePrefs },
  private val hardwareEncryptor: ((ByteArray) -> ByteArray)? = null,
  private val hardwareDecryptor: ((ByteArray) -> ByteArray)? = null
) {
  private val hardwareKey: SecretKey by lazy {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val existing = (store.getKey(alias, null) as? SecretKey)
      ?: legacyAlias?.let { store.getKey(it, null) as? SecretKey }
    existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(
        KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .build()
      )
      generateKey()
    }
  }

  @Volatile
  private var cachedDataKey: SecretKeySpec? = null

  private fun resolveDataKey(): SecretKeySpec? {
    cachedDataKey?.let { return it }
    val prefs = envelopePrefsProvider() ?: return null
    synchronized(this) {
      cachedDataKey?.let { return it }
      val prefKey = "dek:$alias"
      val existingWrapped = prefs.getString(prefKey, null)
      if (!existingWrapped.isNullOrBlank()) {
        val unwrapped = runCatching {
          hardwareDecryptBytes(decodeBase64(existingWrapped))
        }.getOrNull()
        if (unwrapped != null && unwrapped.size == DEK_BYTES) {
          return SecretKeySpec(unwrapped, "AES").also { cachedDataKey = it }
        }
        // Never overwrite an existing envelope if hardware unwrap transiently failed.
        return null
      }
      val freshRaw = ByteArray(DEK_BYTES).also(secureRandom::nextBytes)
      val wrapped = encodeBase64(hardwareEncryptBytes(freshRaw))
      if (!prefs.edit().putString(prefKey, wrapped).commit()) return null
      return SecretKeySpec(freshRaw, "AES").also { cachedDataKey = it }
    }
  }

  @Synchronized
  private fun hardwareEncryptBytes(plain: ByteArray): ByteArray {
    hardwareEncryptor?.let { return it(plain) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, hardwareKey)
    return cipher.iv + cipher.doFinal(plain)
  }

  @Synchronized
  private fun hardwareDecryptBytes(payload: ByteArray): ByteArray {
    hardwareDecryptor?.let { return it(payload) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      hardwareKey,
      GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES))
    )
    return cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size))
  }

  fun encrypt(value: String): String {
    val plainBytes = value.toByteArray(Charsets.UTF_8)
    val dek = runCatching { resolveDataKey() }.getOrNull()
    if (dek != null) {
      val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
      val cipher = threadCipher.get()!!
      cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(TAG_BITS, iv))
      val encrypted = cipher.doFinal(plainBytes)
      val combined = ByteArray(IV_BYTES + encrypted.size)
      System.arraycopy(iv, 0, combined, 0, IV_BYTES)
      System.arraycopy(encrypted, 0, combined, IV_BYTES, encrypted.size)
      return V2_PREFIX + encodeBase64(combined)
    }
    return encodeBase64(hardwareEncryptBytes(plainBytes))
  }

  fun decrypt(encoded: String): String {
    if (encoded.startsWith(V2_PREFIX)) {
      val dek = checkNotNull(resolveDataKey()) { "Envelope DEK unavailable for $alias" }
      val payload = decodeBase64(encoded.substring(V2_PREFIX.length))
      val cipher = threadCipher.get()!!
      cipher.init(
        Cipher.DECRYPT_MODE,
        dek,
        GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES))
      )
      return String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
    }
    return String(hardwareDecryptBytes(decodeBase64(encoded)), Charsets.UTF_8)
  }

  companion object {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val V2_PREFIX = "v2:"
    private const val IV_BYTES = 12
    private const val DEK_BYTES = 32
    private const val TAG_BITS = 128
    private val secureRandom = SecureRandom()
    private val threadCipher = object : ThreadLocal<Cipher>() {
      override fun initialValue(): Cipher = Cipher.getInstance(TRANSFORMATION)
    }
    @Volatile
    private var globalEnvelopePrefs: SharedPreferences? = null
    private val instances = ConcurrentHashMap<String, KeystoreCipher>()

    fun install(context: Context) {
      if (globalEnvelopePrefs == null) {
        globalEnvelopePrefs = context.applicationContext.getSharedPreferences(
          "keystore-envelopes",
          Context.MODE_PRIVATE
        )
      }
    }

    private fun encodeBase64(bytes: ByteArray): String =
      Base64.encodeToString(bytes, Base64.NO_WRAP)
        ?: java.util.Base64.getEncoder().encodeToString(bytes)

    private fun decodeBase64(text: String): ByteArray =
      Base64.decode(text, Base64.NO_WRAP)
        ?: java.util.Base64.getDecoder().decode(text)

    fun of(alias: String, legacyAlias: String? = null): KeystoreCipher =
      instances.computeIfAbsent(alias) { KeystoreCipher(alias, legacyAlias) }
  }
}
