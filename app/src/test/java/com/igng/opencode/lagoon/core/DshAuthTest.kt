package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** DSH Cookie 有效期解码与认证失败区分。 */
class DshAuthTest {
  private fun v1Cookie(issuedAt: Long, expiresAt: Long): String {
    val body = Base64.getUrlEncoder().withoutPadding().encodeToString(
      JSONObject().put("version", 1).put("authority", "127.0.0.1:3080")
        .put("issuedAt", issuedAt).put("expiresAt", expiresAt).toString().toByteArray()
    )
    return "dsh-auth-loopback=v1.$body.sig"
  }

  @Test fun browserCookieLifetimeDurabilityAndFreshnessAreDecoded() {
    val now = 1_800_000_000_000L
    val pair = v1Cookie(issuedAt = now - 60_000L, expiresAt = now + 10 * 60 * 60_000L)
    assertTrue(DshCookie.isAuthCookie(pair))
    val lifetime = DshCookie.lifetime(pair)!!
    assertEquals(1, lifetime.version)
    assertEquals(now + 10 * 60 * 60_000L, lifetime.expiresAt)
    assertTrue(DshCookie.isDurable(pair))
    assertTrue(DshCookie.isFresh(pair, now))
    assertFalse(DshCookie.isFresh(pair, lifetime.expiresAt))
  }

  @Test fun apiPeerCookiesAreNotDurable() {
    val body = Base64.getUrlEncoder().withoutPadding().encodeToString(
      """{"version":2,"authority":"h:1","id":"p","issuedAt":1,"expiresAt":2}""".toByteArray()
    )
    val pair = "dsh-auth-x=v2.$body.sig"
    assertFalse(DshCookie.isDurable(pair))
    assertEquals(2, DshCookie.lifetime(pair)!!.version)
    assertFalse(DshCookie.isDurable("other=v1.a.b"))
  }

  @Test fun authFailuresAreDistinguishedFromNetworkErrors() {
    assertTrue(ApiException(401, "x", DSH_AUTH_EXPIRED).isAuthFailure)
    assertFalse(ApiException(0, "x").isAuthFailure)
    assertFalse(java.io.IOException("x").isAuthFailure)
  }
}
