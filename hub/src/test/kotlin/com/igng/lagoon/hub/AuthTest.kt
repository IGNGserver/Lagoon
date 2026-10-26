package com.igng.lagoon.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AuthTest {
  private fun store(clock: () -> Long = System::currentTimeMillis) = Files.createTempDirectory("hub-auth").let { HubStore(it, SecretBox.load(it), clock) }

  @Test fun passwordHashesVerifyAndRejectTampering() {
    val hash = PasswordHash.hash("1", iterations = 1_000)
    assertTrue(PasswordHash.verify("1", hash))
    assertFalse(PasswordHash.verify("2", hash))
    assertFalse(PasswordHash.verify("1", hash.dropLast(2) + "AA"))
    assertFalse(PasswordHash.verify("1", "plain"))
  }

  @Test fun repeatedFailuresLockOneAddressWithGrowingWaits() {
    var now = 0L
    val throttle = LoginThrottle { now }
    repeat(LoginThrottle.FREE_ATTEMPTS - 1) { throttle.failed("a") }
    assertEquals(0, throttle.retryAfter("a"))
    throttle.failed("a")
    assertEquals(1_000, throttle.retryAfter("a"))
    now += 1_000
    throttle.failed("a")
    assertEquals(2_000, throttle.retryAfter("a"))
    // Another address is unaffected, and success clears the record.
    assertEquals(0, throttle.retryAfter("b"))
    throttle.succeeded("a")
    assertEquals(0, throttle.retryAfter("a"))
  }

  @Test fun lockoutsAreCappedAtFifteenMinutes() {
    val throttle = LoginThrottle { 0L }
    repeat(60) { throttle.failed("a") }
    assertEquals(LoginThrottle.MAX_LOCK_MS, throttle.retryAfter("a"))
  }

  @Test fun aDistributedAttackSlowsEveryAddress() {
    var now = 0L
    val throttle = LoginThrottle { now }
    repeat(LoginThrottle.GLOBAL_LIMIT + 1) { throttle.failed("10.0.0.$it") }
    assertTrue(throttle.retryAfter("fresh") > 0)
    now += LoginThrottle.GLOBAL_SPACING_MS
    assertEquals(0, throttle.retryAfter("fresh"))
  }

  @Test fun firstPasswordNeedsTheSetupCode() {
    val auth = Authenticator(store(), envPassword = null)
    assertTrue(auth.setupRequired)
    val code = auth.setupCode!!
    assertFalse(auth.setup("WRONG-CODE", "x"))
    assertTrue(auth.setup(code.lowercase().replace("-", ""), "x"))
    assertFalse(auth.setupRequired)
    assertNull(auth.setupCode)
    assertTrue(auth.verify("x"))
  }

  @Test fun environmentPasswordWinsAndSignsEveryoneOutWhenChanged() {
    val dir = Files.createTempDirectory("hub-env")
    val box = SecretBox.load(dir)
    val first = Authenticator(HubStore(dir, box).also { it.flush() }, envPassword = "a")
    val token = first.issue("browser")
    assertNotNull(first.session(token))
    val second = Authenticator(HubStore(dir, box), envPassword = "b")
    assertFalse(second.verify("a"))
    assertTrue(second.verify("b"))
    assertNull(second.session(token))
  }

  @Test fun sessionsExpireAfterNinetyIdleDays() {
    var now = 1_000_000L
    val auth = Authenticator(store { now }, envPassword = "p") { now }
    val token = auth.issue("browser")
    now += Authenticator.SESSION_TTL_MS - 1
    assertNotNull(auth.session(token))
    now += Authenticator.SESSION_TTL_MS + 1
    assertNull(auth.session(token))
  }
}
