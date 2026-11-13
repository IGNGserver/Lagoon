package com.igng.opencode.lagoon.core

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyStoreTest {
  private class MapPreferences : SharedPreferences {
    private val map = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
      (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
      private val pending = linkedMapOf<String, Any?>()
      private val removals = hashSetOf<String>()
      private var clear = false

      override fun putString(key: String?, value: String?) = apply { if (key != null) { removals.remove(key); pending[key] = value } }
      override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) { removals.remove(key); pending[key] = values?.toSet() } }
      override fun putInt(key: String?, value: Int) = apply { if (key != null) { removals.remove(key); pending[key] = value } }
      override fun putLong(key: String?, value: Long) = apply { if (key != null) { removals.remove(key); pending[key] = value } }
      override fun putFloat(key: String?, value: Float) = apply { if (key != null) { removals.remove(key); pending[key] = value } }
      override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) { removals.remove(key); pending[key] = value } }
      override fun remove(key: String?) = apply { if (key != null) { pending.remove(key); removals.add(key) } }
      override fun clear() = apply { clear = true; pending.clear(); removals.clear() }
      override fun commit(): Boolean {
        if (clear) map.clear()
        removals.forEach(map::remove)
        map.putAll(pending)
        return true
      }
      override fun apply() { commit() }
    }
  }

  private fun newKeyStore(): Pair<SshKeyStore, MapPreferences> {
    val prefs = MapPreferences()
    val secretPrefs = MapPreferences()
    val store = SshKeyStore(prefs, secretPrefs, encrypt = { "enc:$it" }, decrypt = { it.removePrefix("enc:") })
    return store to secretPrefs
  }

  @Test fun sshHostConfigRoundTripsKeyIdAndFallbackAuth() {
    val original = SshHostConfig(
      id = "h1",
      name = "devbox",
      host = "10.0.0.5",
      port = 2222,
      user = "alice",
      auth = SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK,
      keyId = "key-123"
    )
    val decoded = SshHostCodec.decode(SshHostCodec.encode(listOf(original))).single()
    assertEquals(original, decoded)
    assertEquals(SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK, decoded.auth)
    assertEquals("key-123", decoded.keyId)
    assertNotEquals(
      original.connectionKey,
      original.copyWith(keyId = "key-456").connectionKey
    )
    assertNotEquals(
      original.connectionKey,
      original.copyWith(auth = SshAuthMethod.PRIVATE_KEY).connectionKey
    )
  }

  @Test fun sshHostStorePreservesBothKeyAndPasswordInFallbackMode() {
    val prefs = MapPreferences()
    val secretsPrefs = MapPreferences()
    val store = SshHostStore(prefs, secretsPrefs, { "enc:$it" }, { it.removePrefix("enc:") })
    val host = SshHostConfig(
      id = "h-fb",
      name = "fb",
      host = "host",
      user = "u",
      auth = SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK,
      keyId = "k1"
    )
    val secrets = SshSecrets(
      password = "pw",
      privateKey = "priv",
      passphrase = "pass",
      certificate = "ssh-ed25519-cert-v01@openssh.com AAAA test"
    )
    store.save(host, secrets)
    assertEquals(secrets, store.secrets("h-fb"))

    // Switching to PASSWORD removes private key, passphrase and certificate.
    store.save(host.copyWith(auth = SshAuthMethod.PASSWORD, clearKeyId = true), SshSecrets(password = "pw2"))
    val afterPw = store.secrets("h-fb")
    assertEquals("pw2", afterPw.password)
    assertNull(afterPw.privateKey)
    assertNull(afterPw.passphrase)
    assertNull(afterPw.certificate)
  }

  @Test fun promptPolicyNeverPersistsPassphraseAndTracksRuntimeUnlock() {
    val (store, secretPrefs) = newKeyStore()
    val profile = SshKeyProfile(
      id = "key-prompt",
      name = "Work key",
      algorithm = "ED25519",
      fingerprint = "SHA256:abc",
      publicKeyOpenSsh = "ssh-ed25519 AAAA work",
      encrypted = true,
      passphrasePolicy = SshPassphrasePolicy.PROMPT,
      source = SshKeySource.IMPORTED
    )
    store.save(profile, SshKeySecrets(privateKey = "PEM-BODY", passphrase = "should-not-be-saved"))

    assertFalse(store.isUnlocked(profile))
    assertEquals("PEM-BODY", store.secrets(profile.id).privateKey)
    assertNull(store.secrets(profile.id).passphrase)
    assertFalse(secretPrefs.all.values.any { it.toString().contains("should-not-be-saved") })

    val beforeFp = store.credentialFingerprint(profile.id)
    store.unlockRuntimeSecret(profile.id, "runtime-pass")
    assertTrue(store.isUnlocked(profile))
    assertEquals("runtime-pass", store.runtimeSecret(profile.id))
    assertNotEquals(beforeFp, store.credentialFingerprint(profile.id))

    store.lockRuntimeSecret(profile.id)
    assertFalse(store.isUnlocked(profile))
    assertNull(store.runtimeSecret(profile.id))
  }

  @Test fun biometricPolicySealsSecretsWithoutPlaintextPrefsCopy() {
    val (store, _) = newKeyStore()
    val unencryptedBio = SshKeyProfile(
      id = "key-bio-raw",
      name = "Bio Raw",
      algorithm = "ED25519",
      fingerprint = "SHA256:def",
      publicKeyOpenSsh = "ssh-ed25519 AAAA bio",
      encrypted = false,
      passphrasePolicy = SshPassphrasePolicy.BIOMETRIC,
      source = SshKeySource.GENERATED,
      biometricProtected = true
    )
    store.save(
      unencryptedBio,
      SshKeySecrets(privateKey = "RAW-PRIVATE", biometricCiphertext = "cipher", biometricIv = "iv")
    )
    assertFalse(store.isUnlocked(unencryptedBio))
    val stored = store.secrets(unencryptedBio.id)
    assertNull(stored.privateKey)
    assertEquals("cipher", stored.biometricCiphertext)
    assertEquals("iv", stored.biometricIv)

    store.unlockRuntimeSecret(unencryptedBio.id, "RAW-PRIVATE")
    assertTrue(store.isUnlocked(unencryptedBio))
    assertEquals("RAW-PRIVATE", store.runtimeSecret(unencryptedBio.id))
  }

  @Test fun hardwareKeystoresTrackBiometricRuntimeGateWithoutSecretPrefs() {
    val (store, secretPrefs) = newKeyStore()
    val hw = SshKeyProfile(
      id = "key-hw",
      name = "StrongBox EC",
      algorithm = "ECDSA",
      fingerprint = "SHA256:hw",
      publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA hw",
      encrypted = false,
      passphrasePolicy = SshPassphrasePolicy.BIOMETRIC,
      source = SshKeySource.HARDWARE_KEYSTORE,
      biometricProtected = true
    )
    store.save(hw, SshKeySecrets(privateKey = "ignored"))
    assertTrue(secretPrefs.all.isEmpty())
    assertFalse(store.isUnlocked(hw))
    store.unlockRuntimeSecret(hw.id, "unlocked")
    assertTrue(store.isUnlocked(hw))
    assertEquals(hw, SshKeyCodec.decode(SshKeyCodec.encode(listOf(hw))).single())
  }
}
