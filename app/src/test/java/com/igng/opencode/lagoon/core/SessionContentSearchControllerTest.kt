package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class SessionContentSearchControllerTest {
  private val profile = ServerProfile("host", "Host", "https://host.example/", kind = ServerKind.DSH)
  private fun result(query: String = "needle") = SessionContentSearchResult(query, listOf(SessionContentHit("hit", "server snippet")), false)
  private fun base(): HarnessClient = OpenCodeApi(profile, "fixture")
  private class Fixture(client: HarnessClient, profile: ServerProfile) {
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val controller = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LagoonController::class.java) as LagoonController
    val state = MutableStateFlow(LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
      capabilities = ApiCapabilities(sessionContentSearch = true)))
    val plain = FakeSharedPreferences(); val secrets = FakeSharedPreferences()
    val store = ServerStore(plain, secrets, { it }, { it })
    init {
      store.save(profile, "saved-password", "saved-cookie"); store.rememberDraft(profile.id, "old", "unsent")
      fun set(name: String, value: Any?) { LagoonController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value) }
      set("mutable", state); set("state", state); set("store", store); set("generation", 1); set("api", client)
    }
  }

  @Test fun searchIsReadOnlyAndUncachedHitIsResolvedOnlyOnExplicitOpen() = runBlocking {
    var searches = 0; var lookups = 0
    val client = object : HarnessClient by base() {
      override suspend fun searchSessionContent(query: String): SessionContentSearchResult { searches++; return result(query) }
      override suspend fun searchResultSession(id: String): Session { lookups++; return Session(id, "/server-only", "Uncached", 1) }
    }
    val f = Fixture(client, profile); val before = f.state.value; val plain = f.plain.all.toMap(); val secrets = f.secrets.all.toMap()
    val page = f.controller.searchSessionContent(SessionContentSearchTarget(profile), "needle")
    assertEquals(1, searches); assertEquals(0, lookups); assertEquals(before, f.state.value)
    assertEquals(plain, f.plain.all); assertEquals(secrets, f.secrets.all)
    var opened: SessionTarget? = null
    f.controller.openSearchHit(page, "hit") { opened = it }
    assertEquals(SessionTarget(profile.id, "hit"), opened); assertEquals(1, lookups)
    assertEquals("/server-only", f.state.value.sessions.single().directory)
    assertNull(f.state.value.sessionId); assertTrue(f.state.value.projects.isEmpty())
    assertEquals(plain, f.plain.all); assertEquals(secrets, f.secrets.all)
    client.close()
  }

  @Test fun staleSameIdRouteCannotSearchOrOpenBeforeClientLookup() = runBlocking {
    var searches = 0; var lookups = 0
    val client = object : HarnessClient by base() {
      override suspend fun searchSessionContent(query: String): SessionContentSearchResult { searches++; return result(query) }
      override suspend fun searchResultSession(id: String): Session { lookups++; return Session(id, "/repo", "T", 1) }
    }
    val f = Fixture(client, profile); val expected = SessionContentSearchTarget(profile)
    val page = f.controller.searchSessionContent(expected, "needle")
    f.store.save(profile.copy(url = "https://replacement.example/"), "new-password", "")
    assertTrue(runCatching { f.controller.searchSessionContent(expected, "later") }.isFailure)
    assertTrue(runCatching { f.controller.openSearchHit(page, "hit") { fail("retargeted") } }.isFailure)
    assertEquals(1, searches); assertEquals(0, lookups)
    client.close()
  }

  @Test fun newerSearchInvalidatesPreviousPageAndLateCompletionCannotWin() = runBlocking {
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val client = object : HarnessClient by base() {
      override suspend fun searchSessionContent(query: String): SessionContentSearchResult {
        if (query == "old") { entered.complete(Unit); release.await() }
        return result(query)
      }
      override suspend fun searchResultSession(id: String) = Session(id, "/repo", "T", 1)
    }
    val f = Fixture(client, profile); val target = SessionContentSearchTarget(profile)
    val old = async { runCatching { f.controller.searchSessionContent(target, "old") } }; entered.await()
    val latest = f.controller.searchSessionContent(target, "new")
    release.complete(Unit); assertTrue(old.await().isFailure)
    val next = f.controller.searchSessionContent(target, "newer")
    assertTrue(runCatching { f.controller.openSearchHit(latest, "hit") { fail("obsolete page opened") } }.isFailure)
    var opened = false; f.controller.openSearchHit(next, "hit") { opened = true }; assertTrue(opened)
    client.close()
  }

  @Test fun missingOrMismatchedHitAndLateLookupNeverNavigateOrImportAnotherSession() = runBlocking {
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var mode = "missing"; var lookups = 0
    val client = object : HarnessClient by base() {
      override suspend fun searchSessionContent(query: String) = result(query)
      override suspend fun searchResultSession(id: String): Session? {
        lookups++
        if (mode == "missing") return null
        if (mode == "wrong") return Session("other", "/repo", "Wrong", 1)
        entered.complete(Unit); release.await(); return Session(id, "/repo", "Late", 1)
      }
    }
    val f = Fixture(client, profile); val target = SessionContentSearchTarget(profile)
    val page = f.controller.searchSessionContent(target, "needle")
    assertTrue(runCatching { f.controller.openSearchHit(page, "not-in-page") { fail("forged") } }.isFailure); assertEquals(0, lookups)
    assertTrue(runCatching { f.controller.openSearchHit(page, "hit") { fail("missing") } }.isFailure)
    mode = "wrong"; assertTrue(runCatching { f.controller.openSearchHit(page, "hit") { fail("wrong") } }.isFailure)
    mode = "late"; val opening = async { runCatching { f.controller.openSearchHit(page, "hit") { fail("late") } } }; entered.await()
    f.store.save(profile.copy(url = "https://replacement.example/"), "new-password", ""); release.complete(Unit)
    assertTrue(opening.await().isFailure); assertTrue(f.state.value.sessions.isEmpty())
    client.close()
  }

  @Test fun capabilityAndOnlineScopeAreNotInferredFromTheHostDisplayName() {
    val state = LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true, capabilities = ApiCapabilities(sessionContentSearch = true))
    assertTrue(state.canSearchSessionContent())
    assertFalse(state.copy(cached = true).canSearchSessionContent()); assertFalse(state.copy(degraded = true).canSearchSessionContent())
    assertFalse(state.copy(allServers = true).canSearchSessionContent()); assertFalse(state.copy(connected = false).canSearchSessionContent())
    assertFalse(state.copy(capabilities = ApiCapabilities()).canSearchSessionContent())
    assertTrue(SessionContentSearchTarget(profile).matches(state.copy(profiles = listOf(profile.copy(name = "Renamed")))))
  }
}
