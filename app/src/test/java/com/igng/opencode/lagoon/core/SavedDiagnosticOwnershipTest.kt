package com.igng.opencode.lagoon.core

import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedDiagnosticOwnershipTest {
  @Test fun savedDiagnosticsBorrowTheirExactOwnerDespiteCosmeticDraftChanges() = runBlocking {
    profiles().forEach { profile ->
      val f = Fixture(profile)
      val cosmetic = profile.copy(name = "Renamed", autoConnect = false, notifications = false)
      var cookies = 0
      assertEquals("fixture-version", f.controller.testServer(cosmetic, f.credentials) { cookies++ })
      assertEquals(1, f.health)
      assertEquals(1, f.projects)
      assertEquals(0, cookies)
      assertSame(f.client, f.overview.client(profile.id))
      f.assertUntouchedOwner()
    }
  }

  @Test fun editedRouteBasePathAuthOrDraftCredentialsCannotProbeTheOldOwner() = runBlocking {
    val profile = direct()
    val f = Fixture(profile)
    val drafts = listOf(profile.copy(url = "https://other.example/dsh"),
      profile.copy(url = "https://direct.example/another-base"),
      profile.copy(dshAuth = DshAuthMode.SECRET), profile.copy(username = "other"),
      profile.copy(allowCleartext = true), profile.copy(remoteHostId = "other-host"))
    drafts.forEach { draft -> rejected { f.controller.testServer(draft, f.credentials) } }
    listOf(f.credentials.copy(password = "edited"), f.credentials.copy(cookie = "edited"),
      f.credentials.copy(username = "edited")).forEach { credentials ->
      rejected { f.controller.testServer(profile, credentials) }
    }
    assertEquals(0, f.health)
    assertEquals(0, f.projects)
    assertSame(f.client, f.overview.client(profile.id))
    f.assertUntouchedOwner()
  }

  @Test fun mintedCookieMustMatchCurrentSavedCredentialsNotThePreLoginDraft() = runBlocking {
    val f = Fixture(direct(), graphPresent = false)
    val beforeLogin = f.credentials
    f.store.rememberCookie(f.profile.id, f.profile.url, "minted-cookie")
    val current = f.store.credentials(f.profile.id)
    assertEquals("minted-cookie", current.cookie)
    assertNotEquals(beforeLogin, current)
    rejected { f.controller.testServer(f.profile, beforeLogin) }
    assertEquals(0, f.health)
    assertEquals("fixture-version", f.controller.testServer(f.profile, current))
    assertEquals(1, f.health)
    assertEquals(1, f.projects)
    assertSame(f.client, f.overview.client(f.profile.id))
    f.assertUntouchedOwner()
  }

  @Test fun disabledSavedSourceOrHostRejectsBeforeAnyExistingClientHealthCall() = runBlocking {
    profiles().forEach { profile ->
      listOf(true, false).forEach { source ->
        val f = Fixture(profile)
        val graph = requireNotNull(f.graph)
        f.graph = if (source) graph.copy(sources = graph.sources.map { it.copy(enabled = false) })
          else graph.copy(hosts = graph.hosts.map { it.copy(enabled = false) })
        assertFalse(f.store.profiles().single().parentEnabled)
        rejected { f.controller.testServer(profile, f.credentials) }
        rejected { f.controller.notificationClient(profile, f.credentials) }
        assertEquals(0, f.health)
        assertEquals(0, f.projects)
        f.assertUntouchedOwner()
      }
    }
  }

  @Test fun savedDirectNotificationLeaseBorrowsAndDoesNotCloseTheActualOverviewOwner() {
    val f = Fixture(direct())
    val lease = f.controller.notificationClient(f.profile, f.credentials)
    assertSame(f.client, lease.client)
    assertSame(f.overview.client(f.profile.id), lease.client)
    lease.close()
    lease.close()
    assertEquals(0, f.health)
    assertEquals(0, f.projects)
    f.assertUntouchedOwner()
  }

  @Test fun rawRouteChangeWithUpdatedGraphRejectsStaleCapturedProfileAndCachedOwner() = runBlocking {
    val f = Fixture(direct())
    assertSame(f.client, f.overview.client(f.profile.id))
    val changed = f.profile.copy(url = "https://changed.example/dsh")
    f.store.save(changed, "new-password", "new-cookie")
    f.graph = MigrationProjection.fromProfiles(listOf(changed))
    rejected { f.controller.testServer(f.profile, f.credentials) }
    rejected { f.controller.notificationClient(f.profile, f.credentials) }
    f.assertUntouchedOwner()
    rejected { f.overview.client(changed.id) }
    rejected { f.controller.testServer(changed, f.store.credentials(changed.id)) }
    assertEquals(0, f.health)
    assertEquals(0, f.projects)
    f.assertUntouchedOwner(expectedCloses = 1) // The real owner disposes the now-stale cached route.
  }

  private class Fixture(val profile: ServerProfile, graphPresent: Boolean = true) {
    var graph: ConnectionGraph? = null
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it }, readConnectionGraph = { graph })
    val credentials: ServerCredentials
    var health = 0
    var projects = 0
    private var closes = 0
    private var presentation = 0
    private var content = 0
    val client = Proxy.newProxyInstance(HarnessClient::class.java.classLoader, arrayOf(HarnessClient::class.java)) { _, method, _ ->
      when (method.name) {
        "health" -> { health++; "fixture-version" }
        "projects" -> { projects++; emptyList<Project>() }
        "isClosed" -> false
        "close" -> { closes++; Unit }
        "questionPresentation" -> { presentation++; Unit }
        "setContentWatching" -> { content++; Unit }
        else -> error("Unexpected owner call: ${method.name}")
      }
    } as HarnessClient
    private val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    val overview = AllServersOverview(store, CatalogCache { null }, scope, {})
    val controller: LagoonController
    init {
      store.save(profile, "saved-password", "saved-cookie")
      credentials = store.credentials(profile.id)
      if (graphPresent) graph = MigrationProjection.fromProfiles(listOf(profile))
      // Cancelled monitor jobs populate real configurations but cannot dial or change presentation.
      overview.start(store.profiles())
      @Suppress("UNCHECKED_CAST")
      val clients = field(overview, "clients") as MutableMap<String, HarnessClient>
      clients[profile.id] = client
      val unsafeClass = Class.forName("sun.misc.Unsafe")
      val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
      controller = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LagoonController::class.java) as LagoonController
      val state = MutableStateFlow(LagoonState(profiles = store.profiles(), serverId = null))
      put(controller, "store", store)
      put(controller, "allOverview", overview)
      put(controller, "mutable", state)
      put(controller, "state", state)
    }
    fun assertUntouchedOwner(expectedCloses: Int = 0) {
      assertEquals("only owner invalidation may close the client", expectedCloses, closes)
      assertEquals("diagnostics must not change question presentation", 0, presentation)
      assertEquals("diagnostics must not change content watching", 0, content)
    }
  }

  private companion object {
    fun direct() = ServerProfile("direct", "DSH", "https://direct.example/dsh", kind = ServerKind.DSH)
    fun profiles(): List<ServerProfile> = listOf(direct(),
      direct().copy(id = "remote", url = "https://hub.example", username = "alice", kind = ServerKind.DSH_REMOTE,
        remoteHostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a", remoteHostFingerprint = "abcdef123456"),
      direct().copy(id = "ssh", url = "ssh://alice@host.example:22", username = "alice", sshHostId = "saved-host"))
    suspend fun rejected(block: suspend () -> Any?) {
      val failure = runCatching { block() }.exceptionOrNull()
      assertTrue("must reject admission, not silently test old settings: $failure", failure is IllegalStateException)
    }
    fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    fun put(target: Any, name: String, value: Any?) {
      target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
  }
}
