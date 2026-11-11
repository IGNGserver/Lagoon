package com.igng.opencode.lagoon.core

import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class OverviewAuthenticationFenceTest {
  @Test fun sameRoutePasswordEditRejectsOldCookieRotationAndInvalidation() {
    val f = Fixture()
    val token = f.token()
    f.store.save(f.profile, "replacement-password", "replacement-cookie")
    val replacement = f.store.credentials(f.profile.id)
    assertSame(token, f.token()) // Route and owner are unchanged; the auth fence must reject.
    f.cookie("late-cookie", token)
    assertEquals(replacement, f.store.credentials(f.profile.id))
    f.cookie("", token)
    assertEquals(replacement, f.store.credentials(f.profile.id))
    assertEquals(f.profile, f.store.profiles().single())
  }

  @Test fun replacedOwnerRejectsItsPredecessorsCookieCallbacks() {
    val f = Fixture()
    val oldToken = f.token()
    f.overview.invalidate(f.profile.id)
    f.overview.start(f.store.profiles())
    assertNotSame(oldToken, f.token())
    f.cookie("current-owner-cookie")
    val current = f.store.credentials(f.profile.id)
    assertEquals("current-owner-cookie", current.cookie)
    f.cookie("obsolete-owner-cookie", oldToken)
    assertEquals(current, f.store.credentials(f.profile.id))
    f.cookie("", oldToken)
    assertEquals(current, f.store.credentials(f.profile.id))
  }

  @Test fun currentOwnerRotatesAndClearsCookiesWithoutChangingPasswordOrClient() {
    val f = Fixture()
    val client = f.cacheClient()
    assertSame(client, f.overview.client(f.profile.id))
    f.cookie("rotated-cookie")
    assertEquals(f.issuedWith.copy(cookie = "rotated-cookie"), f.store.credentials(f.profile.id))
    // The cancelled scope cannot update configurations: cookie-only changes must still admit.
    assertSame(client, f.overview.client(f.profile.id))
    f.cookie("")
    assertEquals(f.issuedWith.copy(cookie = ""), f.store.credentials(f.profile.id))
    assertSame(client, f.overview.client(f.profile.id))
    f.cookie("")
    assertEquals(f.issuedWith.copy(cookie = ""), f.store.credentials(f.profile.id))
    assertEquals(0, f.closes)
  }

  @Test fun passwordChangeRejectsAndDisposesEvenAnAlreadyCachedDirectClient() {
    val f = Fixture()
    val client = f.cacheClient()
    assertSame(client, f.overview.client(f.profile.id))
    f.store.save(f.profile, "changed-password", "changed-cookie")
    val failure = runCatching { f.overview.client(f.profile.id) }.exceptionOrNull()
    assertTrue("cached client must not bypass authentication admission: $failure", failure is IllegalStateException)
    assertEquals(1, f.closes)
    assertTrue(client.isClosed)
    assertFalse(f.clients().containsKey(f.profile.id))
    assertFalse(f.tokens().containsKey(f.profile.id))
    assertEquals("changed-password", f.store.credentials(f.profile.id).password)
    assertEquals("changed-cookie", f.store.credentials(f.profile.id).cookie)
  }

  private class Fixture {
    val profile = ServerProfile("direct", "DSH", "https://direct.example/dsh", kind = ServerKind.DSH)
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    private val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    val overview = AllServersOverview(store, CatalogCache { null }, scope, {})
    val issuedWith: ServerCredentials
    var closes = 0
    init {
      store.save(profile, "saved-password", "saved-cookie")
      issuedWith = store.credentials(profile.id)
      // Populate real owner tokens/configurations, but never run monitor or create a transport.
      overview.start(store.profiles())
      check(tokens().containsKey(profile.id))
    }
    fun token(): Any = checkNotNull(tokens()[profile.id])
    fun cookie(value: String, token: Any = token()) {
      AllServersOverview::class.java.getDeclaredMethod("rememberCookie", String::class.java,
        ServerProfile::class.java, ServerCredentials::class.java, Any::class.java, String::class.java)
        .apply { isAccessible = true }.invoke(overview, profile.id, profile, issuedWith, token, value)
    }
    fun cacheClient(): HarnessClient {
      val client = Proxy.newProxyInstance(HarnessClient::class.java.classLoader,
        arrayOf(HarnessClient::class.java)) { _, method, _ ->
        when (method.name) {
          "close" -> { closes++; Unit }
          "isClosed" -> closes > 0
          else -> error("Unexpected client call: ${method.name}")
        }
      } as HarnessClient
      clients()[profile.id] = client
      return client
    }
    fun clients(): MutableMap<String, HarnessClient> = field("clients")
    fun tokens(): MutableMap<String, Any> = field("ownerTokens")
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = AllServersOverview::class.java.getDeclaredField(name)
      .apply { isAccessible = true }.get(overview) as T
  }
}
