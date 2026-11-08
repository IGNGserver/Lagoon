package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshHostStoreTest {
  private fun store(config: FakeSharedPreferences = FakeSharedPreferences(), secrets: FakeSharedPreferences = FakeSharedPreferences()) =
    SshHostStore(config, secrets, { "enc:$it" }, { it.removePrefix("enc:") }) to secrets

  private val host = SshHostConfig(
    id = "ssh-1", name = "工作站", host = "192.168.1.10", port = 22, user = "me",
    auth = SshAuthMethod.PASSWORD
  )

  @Test fun savesConfigSeparatelyFromEncryptedSecrets() {
    val (store, secrets) = store()
    store.save(host, SshSecrets(password = "hunter2"))
    assertEquals("hunter2", store.secrets("ssh-1").password)
    assertEquals(1, store.load().size)
    assertFalse(secrets.values.values.any { it == "hunter2" })
    assertTrue(secrets.values.values.filterIsInstance<String>().all { it.startsWith("enc:") })
  }

  @Test fun switchingAuthDeletesTheOtherSecretKind() {
    val (store, _) = store()
    store.save(host, SshSecrets(password = "pw"))
    store.save(host.copyWith(auth = SshAuthMethod.PRIVATE_KEY), SshSecrets(privateKey = "key", passphrase = "pass"))
    val secrets = store.secrets("ssh-1")
    assertNull(secrets.password)
    assertEquals("key", secrets.privateKey)
    assertEquals("pass", secrets.passphrase)
  }

  @Test fun pinAndResetHostKey() {
    val (store, _) = store()
    store.save(host)
    store.pinHostKey("ssh-1", PresentedSshHostKey("ssh-ed25519", "SHA256:abc"))
    assertEquals("SHA256:abc", store.load().single().hostKeyFingerprint)
    store.resetHostKey("ssh-1")
    assertNull(store.load().single().hostKeyFingerprint)
  }

  @Test fun corruptConfigRefusesToOverwriteTheOtherHosts() {
    val prefs = FakeSharedPreferences()
    val (store, _) = store(prefs)
    store.save(host, SshSecrets(password = "pw"))
    prefs.edit().putString("hosts", "{not-json").commit()
    val failure = runCatching { store.save(host.copy(id = "ssh-2", name = "另一台")) }.exceptionOrNull()
    assertTrue(failure is java.io.IOException)
    assertEquals("{not-json", prefs.getString("hosts", null))
  }

  @Test fun deleteRemovesConfigAndSecrets() {
    val (store, secrets) = store()
    store.save(host, SshSecrets(password = "pw"))
    store.delete("ssh-1")
    assertTrue(store.load().isEmpty())
    assertTrue(secrets.values.isEmpty())
  }
}
