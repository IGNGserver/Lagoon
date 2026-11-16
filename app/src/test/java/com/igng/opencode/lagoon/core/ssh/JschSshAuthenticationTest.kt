package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import java.security.KeyPairGenerator
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.session.ServerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real SSH packets against an ephemeral loopback server; no system accounts or host keys are used. */
class JschSshAuthenticationTest {
  private fun server() = SshServer.setUpDefaultServer().apply {
    host = "127.0.0.1"
    port = 0
    keyPairProvider = KeyPairProvider.wrap(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())
  }

  private fun config(port: Int) = SshHostConfig(id = "test", name = "test", host = "127.0.0.1",
    port = port, user = "fixture-user", auth = SshAuthMethod.PASSWORD)

  private fun interactiveServer(received: AtomicReference<List<String>?>) = server().apply {
    userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
    keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
      override fun generateChallenge(session: ServerSession, username: String, lang: String, subMethods: String) =
        InteractiveChallenge().apply {
          interactionName = "One-time verification"
          addPrompt("One-time password:", false)
        }

      override fun authenticate(session: ServerSession, username: String, responses: List<String>): Boolean {
        received.set(responses.toList())
        return username == "fixture-user" && responses == listOf("fixture-otp")
      }
    }
  }

  @Test fun normalPasswordAuthenticationStillUsesTheStoredLoginPassword() {
    val received = AtomicReference<String?>()
    val server = server().apply {
      userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
      passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
        received.set(password)
        username == "fixture-user" && password == "fixture-login"
      }
      start()
    }
    try {
      val transport = JschSshTransportFactory().connect(config(server.port), SshSecrets(password = "fixture-login")) { true }
      try {
        assertFalse(transport.isClosed)
        assertEquals("fixture-login", received.get())
      } finally {
        transport.close()
      }
    } finally {
      server.stop(true)
    }
  }

  @Test fun oneTimePasswordChallengeNeverReceivesTheSavedLoginPassword() {
    val received = AtomicReference<List<String>?>()
    val server = interactiveServer(received).apply { start() }
    val broker = SshKeyboardInteractiveBroker().apply { setForeground(true) }
    val executor = Executors.newSingleThreadExecutor()
    try {
      val connection = executor.submit(Callable {
        JschSshTransportFactory(keyboardInteractive = broker)
          .connect(config(server.port), SshSecrets(password = "fixture-login")) { true }
      })
      val challenge = runBlocking { withTimeout(5_000) { broker.challenges.first { it.isNotEmpty() }.first() } }
      assertEquals("One-time password:", challenge.prompts.single().text)
      assertFalse(challenge.prompts.single().echo)
      assertNull("The server must not receive the stored password before the user responds", received.get())
      assertTrue(broker.respond(challenge.id, listOf("fixture-otp")))
      val transport = connection.get(5, TimeUnit.SECONDS)
      try {
        assertFalse(transport.isClosed)
        assertEquals(listOf("fixture-otp"), received.get())
      } finally {
        transport.close()
      }
    } finally {
      broker.setForeground(false)
      executor.shutdownNow()
      server.stop(true)
    }
  }

  @Test fun cancellingInteractiveConnectionRemovesPromptWithoutSendingAnAnswer() = runBlocking {
    val received = AtomicReference<List<String>?>()
    val server = interactiveServer(received).apply { start() }
    val broker = SshKeyboardInteractiveBroker().apply { setForeground(true) }
    try {
      val connection = launch {
        runInterruptible(Dispatchers.IO) {
          JschSshTransportFactory(keyboardInteractive = broker)
            .connect(config(server.port), SshSecrets(password = "fixture-login")) { true }.close()
        }
      }
      withTimeout(5_000) { broker.challenges.first { it.isNotEmpty() } }
      withTimeout(5_000) { connection.cancelAndJoin() }
      assertTrue(broker.challenges.value.isEmpty())
      assertNull(received.get())
    } finally {
      broker.setForeground(false)
      server.stop(true)
    }
  }

  @Test fun backgroundInteractiveChallengeReturnsARecoverableFailureWithoutSendingAnAnswer() {
    val received = AtomicReference<List<String>?>()
    val server = interactiveServer(received).apply { start() }
    try {
      val failure = runCatching {
        JschSshTransportFactory(keyboardInteractive = SshKeyboardInteractiveBroker())
          .connect(config(server.port), SshSecrets(password = "fixture-login")) { true }
      }.exceptionOrNull()
      assertTrue(failure is SshFailure)
      assertEquals(SshFailureKind.AUTH_INTERACTION_REQUIRED, (failure as SshFailure).kind)
      assertNull(received.get())
    } finally {
      server.stop(true)
    }
  }
}
