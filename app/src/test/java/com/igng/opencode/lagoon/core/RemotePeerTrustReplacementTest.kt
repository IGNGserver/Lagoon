package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RemotePeerTrustReplacementTest {
  private val id = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val oldKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
  private val newKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
  private val scope = RemotePeerScope("https://hub.example", "user@example.org", id)
  private fun host(key: String) = RemoteHost(id, "Host", "linux", "membership", true, key, null, null)

  @Test fun replacementRequiresExpectedOldKeyAndChangesAtomically() {
    val values = mutableMapOf<RemotePeerScope, String>(scope to oldKey)
    val trust = RemotePeerTrust(object : RemotePeerTrustStore {
      override fun pinned(scope: RemotePeerScope): String? = values[scope]
      override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = values.getOrPut(scope) { publicKey }
      override fun replaceIfMatches(scope: RemotePeerScope, expectedPublicKey: String, replacementPublicKey: String): Boolean {
        if (values[scope] != expectedPublicKey) return false
        values[scope] = replacementPublicKey
        return true
      }
    })
    assertEquals(newKey, trust.replaceAfterConfirmation(scope, host(newKey), oldKey))
    assertEquals(newKey, values[scope])
    assertTrue(runCatching { trust.replaceAfterConfirmation(scope, host(newKey), oldKey) }.isFailure)
  }
}
