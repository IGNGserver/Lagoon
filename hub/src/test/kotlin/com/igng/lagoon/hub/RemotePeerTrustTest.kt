package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.RemoteHost
import com.igng.opencode.lagoon.core.RemotePeerScope
import com.igng.opencode.lagoon.core.RemotePeerTrust
import com.igng.opencode.lagoon.core.RemotePeerTrustStore
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class RemotePeerTrustTest {
  private val id = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
  private val otherKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
  private val scope = RemotePeerScope("https://hub.example", "user@example.org", id)
  private fun host(publicKey: String?) = RemoteHost(id, "Host", "linux", "membership", true, publicKey, null, null)

  @Test fun pinsFirstAuthenticatedKeyAndRefusesReplacement() {
    val values = mutableMapOf<RemotePeerScope, String>()
    val trust = RemotePeerTrust(object : RemotePeerTrustStore {
      override fun pinned(scope: RemotePeerScope) = values[scope]
      override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = values.getOrPut(scope) { publicKey }
    })
    try { trust.verify(scope, host(key)); fail("pin must be confirmed") } catch (_: IOException) { }
    assertEquals(key, trust.pinAfterConfirmation(scope, host(key)))
    assertEquals(key, trust.verify(scope, host(key)))
    try { trust.verify(scope, host(otherKey)); fail("expected refusal") } catch (_: IOException) { }
    assertEquals(key, values[scope])
    assertEquals(otherKey, trust.pinAfterConfirmation(scope.copy(account = "other"), host(otherKey)))
    try { trust.verify(scope.copy(hostId = "bad"), host(key)); fail("expected refusal") } catch (_: IOException) { }
    try { trust.verify(scope, host(null)); fail("expected refusal") } catch (_: IOException) { }
  }

  @Test fun concurrentFirstTrustCannotReplaceExistingIdentity() {
    val trust = RemotePeerTrust(object : RemotePeerTrustStore {
      override fun pinned(scope: RemotePeerScope): String? = null
      override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = otherKey
    })
    try { trust.pinAfterConfirmation(scope, host(key)); fail("expected refusal") } catch (_: IOException) { }
  }
}
