package com.igng.opencode.lagoon.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** DSH 配对凭据：启动链接拆分、Cookie 有效期解码与密钥模式签发（对齐 dsh `browser-auth.ts`）。 */
class DshAuthTest {
  private val secret = ByteArray(32) { it.toByte() }
  private val secretText = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)

  @Test fun launchLinkSplitsIntoServerAndToken() {
    val link = DshLaunchLink.parse("  http://192.168.1.20:3080/?token=AbC-12_x  ")!!
    assertEquals("http://192.168.1.20:3080", link.serverUrl)
    assertEquals("AbC-12_x", link.token)
    assertEquals("https://dsh.example/ui", DshLaunchLink.parse("https://dsh.example/ui/?token=t")!!.serverUrl)
  }

  @Test fun plainAddressesAndOtherLinksAreNotLaunchLinks() {
    assertNull(DshLaunchLink.parse("http://192.168.1.20:3080"))
    assertNull(DshLaunchLink.parse("http://192.168.1.20:3080/?token="))
    assertNull(DshLaunchLink.parse("http://192.168.1.20:3080/?token=a&token=b"))
    assertNull(DshLaunchLink.parse("http://192.168.1.20:3080/?token=a&x=1"))
    assertNull(DshLaunchLink.parse("https://oc.example/?auth_token=abc"))
    assertNull(DshLaunchLink.parse("不是链接"))
  }

  @Test fun authorityMatchesDshRequestAudience() {
    assertEquals("192.168.1.20:3080", DshCookie.authority("http://192.168.1.20:3080/".toHttpUrl()))
    assertEquals("lab.internal", DshCookie.authority("http://lab.internal/".toHttpUrl()))
    assertEquals("[::1]:3080", DshCookie.authority("http://[::1]:3080/".toHttpUrl()))
    assertEquals("https://dsh.example", DshCookie.authority("https://dsh.example/ui".toHttpUrl()))
    assertEquals("https://dsh.example:8443", DshCookie.authority("https://dsh.example:8443/".toHttpUrl()))
  }

  @Test fun httpsBehindProxyUsesPlainListenerAudience() {
    val url = "https://dsh.example".toHttpUrl()
    // 直连 TLS 监听：audience 是 origin；HTTPS 反向代理后面的 HTTP 监听：audience 只是 host。
    assertEquals("https://dsh.example", DshCookie.authority(url))
    assertEquals("dsh.example", DshCookie.authority(url, secureListener = false))
    val plain = DshCookie.mint(secret, url, 1_800_000_000_000L, secureListener = false)
    assertTrue(plain.startsWith(DshCookie.name("dsh.example") + "=v1."))
    assertEquals("dsh.example", JSONObject(String(Base64.getUrlDecoder().decode(plain.substringAfter('=').split('.')[1]))).getString("authority"))
  }

  @Test fun cookieNameIsSha256OfAuthority() {
    val digest = MessageDigest.getInstance("SHA-256").digest("192.168.1.20:3080".toByteArray())
    assertEquals("dsh-auth-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest), DshCookie.name("192.168.1.20:3080"))
  }

  @Test fun mintedCookieIsSignedV1WithinLifetimeLimits() {
    val now = 1_800_000_000_000L
    val url = "http://192.168.1.20:3080".toHttpUrl()
    val pair = DshCookie.mint(secret, url, now)
    assertTrue(pair.startsWith(DshCookie.name("192.168.1.20:3080") + "=v1."))
    val (_, body, signature) = pair.substringAfter('=').split('.')
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
    assertArrayEquals(mac.doFinal(body.toByteArray()), Base64.getUrlDecoder().decode(signature))
    val payload = JSONObject(String(Base64.getUrlDecoder().decode(body)))
    assertEquals(1, payload.getInt("version"))
    assertEquals("192.168.1.20:3080", payload.getString("authority"))
    val issuedAt = payload.getLong("issuedAt")
    val expiresAt = payload.getLong("expiresAt")
    // dsh 拒绝 issuedAt > now 与超过 cookieMaxAgeDays（至少 1 天）的有效期。
    assertTrue(issuedAt < now)
    assertTrue(expiresAt > now)
    assertTrue(expiresAt - issuedAt < 24 * 60 * 60_000L)
    val lifetime = DshCookie.lifetime(pair)!!
    assertEquals(1, lifetime.version)
    assertEquals(expiresAt, lifetime.expiresAt)
    assertTrue(DshCookie.isDurable(pair))
    assertTrue(DshCookie.isFresh(pair, now))
    assertFalse(DshCookie.isFresh(pair, expiresAt))
  }

  @Test fun apiPeerCookiesAreNotDurable() {
    val body = Base64.getUrlEncoder().withoutPadding().encodeToString(
      """{"version":2,"authority":"h:1","id":"p","issuedAt":1,"expiresAt":2}""".toByteArray())
    val pair = "dsh-auth-x=v2.$body.sig"
    assertFalse(DshCookie.isDurable(pair))
    assertEquals(2, DshCookie.lifetime(pair)!!.version)
    assertFalse(DshCookie.isDurable("other=v1.a.b"))
  }

  @Test fun secretMustBe32BytesOfBase64Url() {
    assertArrayEquals(secret, DshCookie.decodeSecret(" $secretText "))
    assertNull(DshCookie.decodeSecret(""))
    assertNull(DshCookie.decodeSecret("abc"))
    assertNull(DshCookie.decodeSecret(Base64.getEncoder().encodeToString(ByteArray(32) { -1 })))
  }

  @Test fun authModeRoundTripsAndDefaultsToToken() {
    assertEquals(DshAuthMode.SECRET, DshAuthMode.fromWire("secret"))
    assertEquals(DshAuthMode.TOKEN, DshAuthMode.fromWire(null))
    assertEquals(DshAuthMode.TOKEN, DshAuthMode.fromWire("unknown"))
  }

  @Test fun authFailuresAreDistinguishedFromNetworkErrors() {
    assertTrue(ApiException(401, "x", DSH_AUTH_EXPIRED).isAuthFailure)
    assertFalse(ApiException(0, "x").isAuthFailure)
    assertFalse(java.io.IOException("x").isAuthFailure)
  }
}
