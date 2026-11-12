package com.igng.opencode.lagoon.core

import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexOverviewMonitoringTest {
  private val profile = ServerProfile("remote-codex", "Codex", "https://hub.example/", username = "a@example.test",
    kind = ServerKind.CODEX, remoteHostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a", remoteHostFingerprint = "abcdef012345")

  @Test fun remoteActiveTasksAreWatchedAndExpiredWatchesReleaseWithoutClosingForeground() = runBlocking {
    val carrier = FakeRemote()
    val client = CodexApi(carrier)
    val foreground = Session("foreground", "/repo", "Foreground", 1)
    try {
      client.selectCodexSession(foreground)
      carrier.active = setOf("one", "two")
      var snapshot = read(client)
      assertTrue(carrier.subscribed.containsAll(setOf("one", "two", "foreground")))
      assertFalse(snapshot.degraded)
      carrier.active = setOf("two")
      snapshot = read(client, snapshot)
      assertTrue("expired watch should be released", "one" in carrier.unsubscribed)
      assertFalse("foreground watch must survive a background refresh", "foreground" in carrier.unsubscribed)
      assertFalse("still active watch must survive", "two" in carrier.unsubscribed)
      assertFalse(snapshot.degraded)
    } finally { client.close() }
  }

  @Test fun remoteMonitorFailureDegradesSnapshotRatherThanClaimingFullCoverage() = runBlocking {
    val carrier = FakeRemote().apply { active = setOf("one"); failSubscription = "one" }
    val client = CodexApi(carrier)
    try {
      val snapshot = read(client)
      assertTrue(snapshot.connected)
      assertTrue(snapshot.degraded)
    } finally { client.close() }
  }

  private suspend fun read(client: HarnessClient, previous: ServerOverviewSnapshot = ServerOverviewSnapshot(profile.id, profile)): ServerOverviewSnapshot {
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    val scope = CoroutineScope(Job().apply { cancel() })
    val overview = AllServersOverview(store, OfflineCache(memoryPreferences(), { it }, { it }), scope,
      {}, selectedServer = { profile.id }, selectedSession = { "foreground" })
    return suspendCoroutine { continuation ->
      try {
        val method = AllServersOverview::class.java.getDeclaredMethod("read", ServerProfile::class.java,
          HarnessClient::class.java, ServerOverviewSnapshot::class.java, Function0::class.java, Continuation::class.java)
          .apply { isAccessible = true }
        val result = method.invoke(overview, profile, client, previous, { true }, continuation)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(result as ServerOverviewSnapshot)
      } catch (error: Throwable) { continuation.resumeWithException(error.cause ?: error) }
    }
  }

  private class FakeRemote : CodexTransport {
    override val remote: Boolean get() = true
    var active: Set<String> = emptySet()
    var failSubscription: String? = null
    val subscribed = mutableSetOf<String>()
    val unsubscribed = mutableListOf<String>()
    override suspend fun call(method: String, params: JSONObject): JSONObject = when (method) {
      "account/read" -> JSONObject().put("requiresOpenaiAuth", false).put("account", JSONObject.NULL)
      "thread/list" -> {
        val rows = JSONArray()
        if (!params.optBoolean("archived")) listOf("foreground", "one", "two").forEach { id ->
          rows.put(JSONObject().put("id", id).put("cwd", "/repo").put("name", id)
            .put("status", JSONObject().put("type", if (id in active) "active" else "idle")))
        }
        JSONObject().put("data", rows)
      }
      "project/list", "model/list" -> JSONObject().put("data", JSONArray())
      else -> JSONObject()
    }
    override suspend fun respond(requestId: Any, result: JSONObject) = Unit
    override suspend fun subscribe(threadId: String) {
      if (threadId == failSubscription) throw IOException("Host stream limit")
      subscribed += threadId
    }
    override suspend fun unsubscribe(threadId: String) { unsubscribed += threadId; subscribed -= threadId }
    override fun events() = flow<JSONObject> { awaitCancellation() }
    override fun close() = Unit
  }
}
