package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyboardInteractiveBrokerTest {
  private val host = SshHostConfig(id = "test", name = "Test", host = "example.invalid", user = "user", auth = SshAuthMethod.PASSWORD)

  private fun challenge(broker: SshKeyboardInteractiveBroker) = runBlocking {
    withTimeout(5_000) { broker.challenges.first { it.isNotEmpty() }.first() }
  }

  @Test fun promptsRemainSeparateRespectEchoAndDoNotPublishAnswers() {
    val broker = SshKeyboardInteractiveBroker().apply { setForeground(true) }
    val executor = Executors.newSingleThreadExecutor()
    try {
      val result = executor.submit(Callable {
        broker.request(host, "Sign in", "Continue", arrayOf("Password:", "One-time code:", "Name:"),
          booleanArrayOf(false, false, true))
      })
      val pending = challenge(broker)
      assertEquals(listOf(false, false, true), pending.prompts.map { it.echo })
      assertEquals(listOf("Password:", "One-time code:", "Name:"), pending.prompts.map { it.text })
      assertTrue(broker.respond(pending.id, listOf("fixture-password", "fixture-otp", "fixture-name")))
      assertArrayEquals(arrayOf("fixture-password", "fixture-otp", "fixture-name"), result.get(5, TimeUnit.SECONDS))
      assertTrue(broker.challenges.value.isEmpty())
      assertFalse(broker.respond(pending.id, listOf("stale", "stale", "stale")))
    } finally {
      broker.setForeground(false)
      executor.shutdownNow()
    }
  }

  @Test fun backgroundCancelAndTimeoutReleaseTheWaitingConnection() {
    val broker = SshKeyboardInteractiveBroker(timeoutMillis = 1_000)
    val failure = runCatching { broker.request(host, "", "", arrayOf("Code:"), booleanArrayOf(false)) }.exceptionOrNull()
    assertEquals(SshFailureKind.AUTH_INTERACTION_REQUIRED, (failure as SshFailure).kind)
    val executor = Executors.newSingleThreadExecutor()
    try {
      broker.setForeground(true)
      val cancelled = executor.submit(Callable { broker.request(host, "", "", arrayOf("Code:"), booleanArrayOf(false)) })
      val first = challenge(broker)
      assertTrue(broker.respond(first.id, null))
      assertEquals(SshFailureKind.AUTH_INTERACTION_REQUIRED, (runCatching { cancelled.get(5, TimeUnit.SECONDS) }
        .exceptionOrNull() as ExecutionException).cause.let { (it as SshFailure).kind })
      assertFalse(broker.respond(first.id, listOf("stale")))

      val background = executor.submit(Callable { broker.request(host, "", "", arrayOf("Code:"), booleanArrayOf(false)) })
      val second = challenge(broker)
      broker.setForeground(false)
      assertEquals(SshFailureKind.AUTH_INTERACTION_REQUIRED, (runCatching { background.get(5, TimeUnit.SECONDS) }
        .exceptionOrNull() as ExecutionException).cause.let { (it as SshFailure).kind })
      assertFalse(broker.respond(second.id, listOf("stale")))

      broker.setForeground(true)
      val timedOut = executor.submit(Callable { broker.request(host, "", "", arrayOf("Code:"), booleanArrayOf(false)) })
      val third = challenge(broker)
      val timeout = (runCatching { timedOut.get(5, TimeUnit.SECONDS) }.exceptionOrNull() as ExecutionException).cause as SshFailure
      assertEquals(SshFailureKind.AUTH_INTERACTION_REQUIRED, timeout.kind)
      assertTrue(timeout.message.contains("超时"))
      assertFalse(broker.respond(third.id, listOf("stale")))
      assertTrue(broker.challenges.value.isEmpty())
    } finally {
      broker.setForeground(false)
      executor.shutdownNow()
    }
  }

  @Test fun malformedChallengeAndAnswerCannotBeMisapplied() {
    val broker = SshKeyboardInteractiveBroker().apply { setForeground(true) }
    val malformed = runCatching { broker.request(host, "", "", arrayOf("A", "B"), booleanArrayOf(false)) }.exceptionOrNull()
    assertEquals(SshFailureKind.AUTH_INTERACTION_REQUIRED, (malformed as SshFailure).kind)
    assertTrue(broker.challenges.value.isEmpty())
    val executor = Executors.newSingleThreadExecutor()
    try {
      val result = executor.submit(Callable { broker.request(host, "", "", arrayOf("A", "B"), booleanArrayOf(false, true)) })
      val pending = challenge(broker)
      assertFalse(broker.respond(pending.id, listOf("only-one")))
      assertTrue(broker.respond(pending.id, listOf("first", "second")))
      assertArrayEquals(arrayOf("first", "second"), result.get(5, TimeUnit.SECONDS))
    } finally {
      broker.setForeground(false)
      executor.shutdownNow()
    }
  }
}
