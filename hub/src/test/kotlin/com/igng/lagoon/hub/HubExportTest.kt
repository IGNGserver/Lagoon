package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.DshAuthMode
import com.igng.opencode.lagoon.core.ExportedServer
import com.igng.opencode.lagoon.core.HubAccessKey
import com.igng.opencode.lagoon.core.HubExport
import com.igng.opencode.lagoon.core.HubExportCodec
import com.igng.opencode.lagoon.core.HubExportCrypto
import com.igng.opencode.lagoon.core.HubImportLink
import com.igng.opencode.lagoon.core.ServerCredentials
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.isLocalOnlyAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The client export format shared with the Android app: keys, import links, signatures and sealing. */
class HubExportTest {
  private val key = HubAccessKey("abcd2345", ByteArray(32) { it.toByte() })

  @Test fun keysRoundTripThroughTheirText() {
    val generated = HubAccessKey.generate()
    assertEquals(generated, HubAccessKey.parse("  ${generated.encoded}\n"))
    assertEquals(key, HubAccessKey.parse(key.encoded))
    assertTrue(key.encoded.startsWith("lgk1.abcd2345."))
    assertNull(HubAccessKey.parse("lgk2.abcd2345.${key.encoded.substringAfterLast('.')}"))
    assertNull(HubAccessKey.parse("lgk1.ABCD2345.${key.encoded.substringAfterLast('.')}"))
    assertNull(HubAccessKey.parse("lgk1.abcd2345.AAAA"))
    assertNull(HubAccessKey.parse("hunter2"))
  }

  @Test fun importLinksCarryTheKeyInTheFragment() {
    val link = HubImportLink.build("https://hub.lan:8443/", key)
    assertEquals("https://hub.lan:8443/#key=${key.encoded}", link)
    val target = HubImportLink.parse(link)!!
    assertEquals("https://hub.lan:8443", target.hubUrl)
    assertEquals(key, target.key)
    assertEquals("http://10.0.0.5:8080/lagoon", HubImportLink.parse("http://10.0.0.5:8080/lagoon/?x=1#key=${key.encoded}")!!.hubUrl)
    assertNull(HubImportLink.parse("https://hub.lan:8443/"))
    assertNull(HubImportLink.parse("not a url#key=${key.encoded}"))
  }

  @Test fun hkdfMatchesRfc5869() {
    val okm = HubExportCrypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() })
    assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", okm.joinToString("") { "%02x".format(it) })
  }

  @Test fun signaturesBindKeyTimeAndNonce() {
    val mac = HubExportCrypto.mac(key, 1_000, "nonce-0123456789ab")
    assertEquals(GOLDEN_MAC, mac)
    assertTrue(HubExportCrypto.verify(key, 1_000, "nonce-0123456789ab", mac))
    assertFalse(HubExportCrypto.verify(key, 1_001, "nonce-0123456789ab", mac))
    assertFalse(HubExportCrypto.verify(key, 1_000, "nonce-0123456789ac", mac))
    assertFalse(HubExportCrypto.verify(HubAccessKey("abcd2346", key.secret), 1_000, "nonce-0123456789ab", mac))
  }

  @Test fun sealedExportsOpenOnlyForTheirRequest() {
    val sealed = HubExportCrypto.seal(key, "nonce-0123456789ab", "{\"secret\":\"pw\"}")
    assertFalse(sealed.toString().contains("pw"))
    assertEquals("{\"secret\":\"pw\"}", HubExportCrypto.open(key, "nonce-0123456789ab", sealed))
    assertThrows { HubExportCrypto.open(key, "nonce-other-123456", sealed) }
    assertThrows { HubExportCrypto.open(HubAccessKey(key.id, HubAccessKey.generate().secret), "nonce-0123456789ab", sealed) }
    val data = sealed.getString("data")
    val flipped = (if (data[0] == 'A') 'B' else 'A') + data.substring(1)
    assertThrows { HubExportCrypto.open(key, "nonce-0123456789ab", sealed.put("data", flipped)) }
  }

  @Test fun exportDocumentsRoundTrip() {
    val export = HubExport("hub-1", 42, listOf(
      ExportedServer(ServerProfile("s1", "Dev", "http://10.0.0.2:4096", "opencode", allowCleartext = true), ServerCredentials("opencode", "pw", "c=1")),
      ExportedServer(ServerProfile("s2", "Mac", "https://mac.lan:8443", "dsh", autoConnect = false, kind = ServerKind.DSH, dshAuth = DshAuthMode.SECRET),
        ServerCredentials("dsh", "c2VjcmV0"), localOnly = true)))
    assertEquals(export, HubExportCodec.decode(HubExportCodec.encode(export)))
  }

  @Test fun exportDocumentsRejectNewerFormatsAndSkipBrokenEntries() {
    assertThrows { HubExportCodec.decode(HubExportCodec.encode(HubExport("h", 0, emptyList())).put("version", 2)) }
    assertThrows { HubExportCodec.decode(HubExportCodec.encode(HubExport("", 0, emptyList()))) }
    val json = HubExportCodec.encode(HubExport("h", 0, listOf(ExportedServer(ServerProfile("s", "", "ftp://x", ""), ServerCredentials()))))
    assertTrue(HubExportCodec.decode(json).servers.isEmpty())
    // A cleartext flag on an HTTPS address and a DSH auth mode on OpenCode are normalized away.
    val odd = HubExportCodec.encode(HubExport("h", 0, listOf(ExportedServer(
      ServerProfile("s", "", "https://x.lan", "", allowCleartext = true, dshAuth = DshAuthMode.SECRET), ServerCredentials()))))
    val decoded = HubExportCodec.decode(odd).servers.single().profile
    assertFalse(decoded.allowCleartext)
    assertEquals(DshAuthMode.TOKEN, decoded.dshAuth)
    assertEquals("OpenCode", decoded.name)
  }

  @Test fun containerOnlyAddressesAreFlagged() {
    listOf("http://localhost:4096", "http://127.0.0.1:4096", "http://[::1]:4096", "http://host.docker.internal:4096", "http://opencode:4096")
      .forEach { assertTrue(it, isLocalOnlyAddress(it)) }
    listOf("http://10.0.0.2:4096", "https://mac.lan", "http://[fd00::2]:4096", "https://dev.example.com")
      .forEach { assertFalse(it, isLocalOnlyAddress(it)) }
  }

  @Test fun distinctKeysProduceDistinctSecrets() {
    assertNotEquals(HubAccessKey.generate().encoded, HubAccessKey.generate().encoded)
  }

  private fun assertThrows(block: () -> Unit) {
    try { block() } catch (_: Exception) { return }
    fail("应当抛出异常")
  }

  companion object {
    /** Also asserted by the Android app's tests: both sides must sign identically. */
    const val GOLDEN_MAC = "pCKvzHb0bYwxglMls4r0yogURejjtPLHNr_gQsnqHqs"
  }
}
