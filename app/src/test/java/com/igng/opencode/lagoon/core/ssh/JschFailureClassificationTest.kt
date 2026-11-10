package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import org.junit.Assert.*
import org.junit.Test

/** The key is presented before auth; presentation alone must never rewrite the failure category. */
class JschFailureClassificationTest {
  private fun classify(message: String, accepted: Boolean): SshFailure {
    val type = Class.forName("com.igng.opencode.lagoon.core.ssh.VerifyingHostKeyRepository")
    val constructor = type.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }
    val repository = constructor.newInstance(SshHostKeyVerifier { accepted }, null)
    type.getDeclaredField("presented").apply { isAccessible = true }.set(repository,
      PresentedSshHostKey("ssh-ed25519", "SHA256:fixture"))
    type.getDeclaredField("accepted").apply { isAccessible = true }.setBoolean(repository, accepted)
    val method = JschSshTransportFactory::class.java.getDeclaredMethod("describe", Throwable::class.java, type)
      .apply { isAccessible = true }
    return method.invoke(JschSshTransportFactory(), java.io.IOException(message), repository) as SshFailure
  }

  @Test fun rejectedPasswordAfterAcceptedKeyIsAnAuthenticationFailure() {
    val error = classify("Auth fail for methods password", accepted = true)
    assertEquals(SshFailureKind.AUTH, error.kind)
    assertFalse(error.retryable)
  }

  @Test fun networkTimeoutAfterAcceptedKeyIsNotAHostKeyPrompt() {
    val error = classify("Read timed out", accepted = true)
    assertEquals(SshFailureKind.UNREACHABLE, error.kind)
    assertTrue(error.retryable)
    assertNull(error.presentedKey)
  }

  @Test fun unacceptedPresentedKeyRetainsConfirmationInformation() {
    val error = classify("handshake rejected", accepted = false)
    assertEquals(SshFailureKind.HOST_KEY_UNKNOWN, error.kind)
    assertEquals("SHA256:fixture", error.presentedKey?.fingerprint)
    assertFalse(error.retryable)
  }
}
