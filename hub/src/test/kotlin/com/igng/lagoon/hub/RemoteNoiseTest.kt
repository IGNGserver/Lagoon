package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.RemoteIdentity
import com.igng.opencode.lagoon.core.RemoteNoise
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** Values from upstream fixtures/crypto/v1/noise-ik.json at the pinned Remote commit. */
class RemoteNoiseTest {
  private val decoder = Base64.getUrlDecoder()
  private val encoder = Base64.getUrlEncoder().withoutPadding()
  private val identity = RemoteIdentity("noise-vector-client", "j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
  private val host = "NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ"
  private val ephemeral = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8"
  private val answer = "Z13VdO13iTELPS52gfN5C0ZsdzsVIf7PNld5WDcepS_C2QNpZJIbEbA4SFnxGH_ZLHRrScqJPg8"
  private fun session() = RemoteNoise(identity, "noise-vector-host", host, "noise-vector-connection", decoder.decode(ephemeral))

  @Test fun matchesUpstreamHandshakeAndTransportVector() {
    session().use { noise ->
      assertEquals("eaYx7t4b-cmPEgMs3q3Q56B5OY_HhriMyEbsia-FpRp-kjJIiJmQmwjR-nKoDj1SL3vwITlzJcTi-x6Vk919oaRIWCFMjsMQmpXAzkidWgV1MTVaK2PLopKyb8sR-tY1Foz-zbR5aoVUwtc",
        encoder.encodeToString(noise.writeHandshake(decoder.decode("Y2xpZW50LWluaXQ"))))
      assertEquals("host-ack", String(noise.readHandshake(decoder.decode(answer))))
      assertEquals("sG_tz-hUa8eiulwCZPVQoe-Pg0FsJ-AN7VvOtq724CfMNw", encoder.encodeToString(noise.encrypt("client transport 0".toByteArray())))
      assertEquals("host transport 0", String(noise.decrypt(decoder.decode("7CXuhyRZeDoDWOw3AhC6OyqK3Nc6WcuWWlxcBluZ-vA"))))
      assertEquals("YL7rVvuJNGzziHSdDihBUtjcQqjUB2SkqwdfybPbcSqPAQ", encoder.encodeToString(noise.encrypt("client transport 1".toByteArray())))
    }
  }

  @Test fun corruptHandshakeClosesSession() {
    session().use { noise ->
      noise.writeHandshake()
      val corrupt = decoder.decode(answer).apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
      try { noise.readHandshake(corrupt); fail("expected failure") } catch (_: IOException) { }
      try { noise.writeHandshake(); fail("expected closed") } catch (_: IOException) { }
    }
  }

  @Test fun generatedIdentityIsUsableAndRedactsPrivateKey() {
    val generated = RemoteIdentity.generate()
    assertFalse(generated.toString().contains(generated.privateKey))
    RemoteNoise(generated, "host", host, "connection").use { assertTrue(it.writeHandshake().isNotEmpty()) }
  }
}
