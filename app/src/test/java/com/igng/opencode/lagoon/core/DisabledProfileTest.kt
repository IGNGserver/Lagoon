package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A disabled connection is never dialled and never projected, while its local data stays intact. */
class DisabledProfileTest {
  private fun store() = ServerStore(FakeSharedPreferences(), FakeSharedPreferences(), { "enc:$it" }, { it.removePrefix("enc:") })
  private fun cache() = OfflineCache(FakeSharedPreferences(), { it }, { it })

  @Test fun enabledDefaultsToTrueForLegacyDocumentsAndRoundTrips() {
    val prefs = FakeSharedPreferences()
    val secrets = FakeSharedPreferences()
    val store = ServerStore(prefs, secrets, { "enc:$it" }, { it.removePrefix("enc:") })
    val legacy = JSONObject()
      .put("id", "legacy").put("name", "Legacy").put("url", "https://legacy.example")
      .put("username", "opencode").put("autoConnect", true).put("notifications", true)
      .put("allowCleartext", false).put("kind", "opencode")
    prefs.edit().putString("profiles", JSONArray().put(legacy).toString()).commit()
    assertTrue(store.profiles().single().enabled)

    val profile = ServerProfile("a", "A", "https://a.example")
    store.save(profile, password = "secret")
    assertTrue(store.setProfileEnabled("a", false))
    assertFalse(store.profiles().first { it.id == "a" }.enabled)
    assertEquals("secret", store.credentials("a").password)
    assertEquals(false, JSONArray(prefs.getString("profiles", "[]")).let { array ->
      (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("id") == "a" }.getBoolean("enabled")
    })
    assertTrue(store.setProfileEnabled("a", true))
    assertTrue(store.profiles().first { it.id == "a" }.enabled)
    assertFalse(store.setProfileEnabled("missing", false))
  }

  @Test fun disabledSnapshotIsExcludedFromTheHomeAndGlobalSummary() {
    val disabled = ServerProfile("a", "A", "https://a.example", enabled = false)
    val enabled = ServerProfile("b", "B", "https://b.example")
    val state = LagoonState(
      profiles = listOf(disabled, enabled),
      serverId = "a",
      connected = true,
      sessions = listOf(Session("a-session", "/repo", "A", 1, serverId = "a")),
      tasks = mapOf("a-session" to TaskState("a-session", TaskPhase.THINKING)),
      allServers = true,
      allServerSnapshots = mapOf(
        "a" to snapshot(disabled, "a-session", TaskPhase.THINKING),
        "b" to snapshot(enabled, "b-session", TaskPhase.WAITING_QUESTION)
      )
    )

    val home = state.homeProjection()
    assertNull(home.serverId)
    assertEquals(listOf("b"), home.sessions.map { it.serverId })
    assertEquals(0, state.globalSummary.running)
    assertEquals(1, state.copy(allServers = true).globalSummary.waiting)
    assertEquals(setOf("b"), state.homeReadSnapshots().keys)
  }

  @Test fun singleServerHomeFallsBackToAnEnabledConnection() {
    val disabled = ServerProfile("a", "A", "https://a.example", enabled = false)
    val enabled = ServerProfile("b", "B", "https://b.example")
    val state = LagoonState(
      profiles = listOf(disabled, enabled),
      serverId = "a", homeServerId = "a", allServers = false, connected = true,
      sessions = listOf(Session("a-session", "/repo", "A", 1, serverId = "a")),
      allServerSnapshots = mapOf(
        "a" to snapshot(disabled, "a-session", TaskPhase.THINKING),
        "b" to snapshot(enabled, "b-session", TaskPhase.COMPLETED)
      )
    )

    val home = state.homeProjection()
    assertEquals("b", home.serverId)
    assertEquals(listOf("b"), home.sessions.map { it.serverId })
  }

  @Test fun allDisabledProducesAnEmptyAggregateHome() {
    val disabled = ServerProfile("a", "A", "https://a.example", enabled = false)
    val state = LagoonState(
      profiles = listOf(disabled), serverId = "a", homeServerId = "a", allServers = false,
      connected = true, sessions = listOf(Session("s", "/repo", "A", 1, serverId = "a")),
      allServerSnapshots = mapOf("a" to snapshot(disabled, "s", TaskPhase.THINKING))
    )

    val home = state.homeProjection()
    assertTrue(home.allServers)
    assertNull(home.serverId)
    assertNull(home.homeGroupId)
    assertTrue(home.sessions.isEmpty())
    assertTrue(home.globalSummary.isEmpty)
  }

  @Test fun groupTabsAndProjectionOnlyUseEnabledMembers() {
    val disabled = ServerProfile("a", "A", "https://a.example", enabled = false)
    val enabled = ServerProfile("b", "B", "https://b.example")
    val group = DeviceGroup("g", "Group", listOf("a", "b"))
    val state = LagoonState(
      profiles = listOf(disabled, enabled), deviceGroups = listOf(group), homeGroupId = "g", allServers = false,
      serverId = "a", allServerSnapshots = mapOf(
        "a" to snapshot(disabled, "a-session", TaskPhase.THINKING),
        "b" to snapshot(enabled, "b-session", TaskPhase.THINKING)
      )
    )

    assertEquals(listOf("b"), state.deviceTabs.single().profiles.map { it.id })
    val home = state.homeProjection()
    assertEquals("b", home.serverId)
    assertEquals(listOf("b"), home.sessions.map { it.serverId })
    assertEquals(1, home.summary.running)
  }

  @Test fun disabledProfileIsNeverDialledEvenWhenIncluded() = runBlocking {
    MockWebServer().use { server ->
      val requests = AtomicInteger()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          requests.incrementAndGet()
          return when (request.requestUrl!!.encodedPath) {
            "/api/info" -> MockResponse().setBody("""{"version":"2.0.22"}""")
            "/api/session" -> MockResponse().setBody("""{"data":[],"cursor":{}}""")
            "/api/session/active" -> MockResponse().setBody("""{"data":{}}""")
            "/api/event" -> MockResponse().setHeader("Content-Type", "text/event-stream")
              .setBody("data: {\"type\":\"server.connected\",\"data\":{}}\n\n")
            else -> MockResponse().setBody("""{"data":[]}""")
          }
        }
      }
      val store = store()
      val profile = ServerProfile("a", "A", server.url("/").toString(), allowCleartext = true, enabled = false)
      store.save(profile, null)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val snapshots = ConcurrentHashMap<String, ServerOverviewSnapshot>()
      val manager = AllServersOverview(store, cache(), scope, { snapshots[it.serverId] = it })
      try {
        manager.start(store.profiles(), include = setOf("a"))
        delay(350)
        assertEquals("禁用连接不应发出任何请求", 0, requests.get())
        assertNull(snapshots["a"])

        assertTrue(store.setProfileEnabled("a", true))
        manager.start(store.profiles(), include = setOf("a"))
        withTimeout(8_000) { while (snapshots["a"]?.connected != true) delay(20) }
        assertTrue(requests.get() > 0)
      } finally {
        manager.stop()
        scope.cancel()
      }
    }
  }

  private fun snapshot(profile: ServerProfile, sessionId: String, phase: TaskPhase) = ServerOverviewSnapshot(
    profile.id, profile, connected = true, loading = false,
    sessions = listOf(Session(sessionId, "/repo", profile.name, 1, serverId = profile.id)),
    tasks = mapOf(sessionId to TaskState(sessionId, phase))
  )
}
