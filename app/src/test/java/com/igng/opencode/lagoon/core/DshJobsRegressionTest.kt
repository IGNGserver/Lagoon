package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class DshJobsRegressionTest {
  @Test fun allKnownOwnersAreScannedAndFailedListsNeverBecomeCachedEmptySuccess() = runBlocking {
    MockWebServer().use { server ->
      var now = 1L
      var fail = false
      var malformed = false
      val queried = ConcurrentHashMap.newKeySet<String>()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> MockResponse().setResponseCode(303)
            .addHeader("Set-Cookie", "dsh-auth-test=fixture; Path=/").addHeader("Location", "./")
          request.path == "/api/session/list" -> MockResponse().setBody(JSONObject().put("result", JSONObject()
            .put("ok", true).put("value", JSONObject().put("items", JSONArray((1..5).map { n ->
              JSONObject().put("sessionId", "s$n").put("cwd", "/repo").put("updatedAt", n)
                .put("projections", JSONObject().put("values", JSONObject().put("title", "S$n")))
            })))).toString())
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val open = JSONObject(text)
              if (open.str("type") != "open" || open.str("endpoint") != "job/list") return
              val owner = open.obj("payload").obj("args").obj("request").str("sessionId")
              queried += owner
              val stream = open.str("streamId")
              if (fail && owner == "s1") {
                webSocket.send(JSONObject().put("type", "error").put("streamId", stream)
                  .put("error", JSONObject().put("code", "fixture/failure").put("message", "network fault")).toString())
              } else {
                val jobs = JSONArray()
                if (owner == "s1") jobs.put(JSONObject().put("id", "old-job").put("owner", "s1")
                  .put("kind", "shell").put("label", "long command").put("startedAt", 1).put("status", "running"))
                // A frame must not associate another session's global/foreign job with this directory.
                jobs.put(JSONObject().put("id", "foreign").put("owner", "elsewhere").put("status", "running"))
                val rows = if (malformed) JSONObject().put("type", "unexpected") else JSONObject().put("type", "rows").put("jobs", jobs)
                webSocket.send(JSONObject().put("type", "item").put("streamId", stream).put("value", rows).toString())
              }
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val profile = ServerProfile("s", "DSH", server.url("/").toString(), kind = ServerKind.DSH, allowCleartext = true)
      val api = DshApi(profile, ServerCredentials("dsh", "token"), clock = { now })
      try {
        api.rootSessionsPage("/repo")
        assertEquals("old-job", api.shells("/repo").single().id)
        assertEquals((1..5).map { "s$it" }.toSet(), queried.toSet())
        now += DshApi.JOB_CACHE_MILLIS + 1
        fail = true
        assertTrue(runCatching { api.shells("/repo") }.isFailure)
        fail = false
        // Failed owner was not cached as empty for another 20 seconds.
        assertEquals("old-job", api.shells("/repo").single().id)
        now += DshApi.JOB_CACHE_MILLIS + 1
        malformed = true
        assertTrue(runCatching { api.shells("/repo") }.isFailure)
        malformed = false
        assertEquals("old-job", api.shells("/repo").single().id)
        assertTrue(api.capabilities().jobOutputStream)
        assertTrue(DshApi.TUNNEL_CAPABILITIES.jobOutputStream)
        assertFalse(ApiCapabilities.BASELINE.jobOutputStream)
      } finally { api.close() }
    }
  }

  @Test fun familyActivityDoesNotLeakAcrossRootsInTheSameDirectory() {
    val sessions = listOf(Session("a", "/repo", "A", 1), Session("b", "/repo", "B", 1),
      Session("c", "/repo", "child", 1, parentId = "a"), Session("g", "/repo", "grandchild", 1, parentId = "c"))
    val parents = mapOf("c" to "a", "g" to "c")
    val jobs = listOf("a", "b", "g").associate { it to ShellJob(it, it, "command", 1, "/repo") }
    val activity = SessionActivityRules.activity("a", sessions, emptyMap(), jobs, parents)
    assertEquals(setOf("a", "g"), activity.shells.map { it.sessionId }.toSet())
    assertEquals(listOf("c", "g"), activity.subSessions.map { it.session.id })
  }
  @Test fun failedReadsRetainJobsAsUncertainUntilASuccessfulEmptyRead() {
    val job = ShellJob("j", "s", "cmd", 1, "/repo", observedAt = 1)
    val old = mapOf("j" to job)
    val stale = reconcileShellJobs(old, emptyMap(), emptySet(), now = 999_999, failedDirectories = setOf(normalizedDirectory("/repo")))
    assertEquals(job.copy(uncertain = true), stale["j"])
    assertTrue(SessionActivityRules.backgroundRoots(emptyMap(), stale, emptyMap()).isEmpty())
    val activity = SessionActivity(shells = stale.values.toList())
    assertFalse(activity.hasActive)
    assertEquals(1, activity.uncertainShells)
    assertTrue(reconcileShellJobs(stale, emptyMap(), setOf(normalizedDirectory("/repo"))).isEmpty())
    assertEquals(false, reconcileShellJobs(stale, old, emptySet())["j"]?.uncertain)
    assertTrue(reconcileShellJobs(stale, emptyMap(), emptySet(), setOf("s")).isEmpty())
  }

  @Test fun jobOutputDrainsCompletedJobsAndPropagatesStreamErrors() = runBlocking {
    for (fail in listOf(false, true)) MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> MockResponse().setResponseCode(303)
            .addHeader("Set-Cookie", "dsh-auth-test=fixture; Path=/").addHeader("Location", "./")
          request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val open = JSONObject(text)
              if (open.str("type") != "open") return
              assertEquals("job/follow", open.str("endpoint"))
              assertEquals("owner", open.obj("payload").obj("args").obj("request").str("sessionId"))
              val stream = open.str("streamId")
              fun send(value: JSONObject) { webSocket.send(JSONObject().put("type", "item").put("streamId", stream).put("value", value).toString()) }
              if (fail) webSocket.send(JSONObject().put("type", "error").put("streamId", stream)
                .put("error", JSONObject().put("code", "job/fault").put("message", "fault")).toString())
              else {
                val job = JSONObject().put("id", "job").put("status", "completed")
                send(JSONObject().put("type", "opened").put("job", job))
                send(JSONObject().put("type", "output").put("chunks", JSONArray().put(JSONObject().put("text", "retained output"))))
                send(JSONObject().put("type", "status").put("job", job))
                webSocket.send(JSONObject().put("type", "end").put("streamId", stream).toString())
              }
            }
          })
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val api = DshApi(ServerProfile("s", "DSH", server.url("/").toString(), kind = ServerKind.DSH, allowCleartext = true), ServerCredentials("dsh", "token"))
      try {
        val updates = mutableListOf<Triple<String, Boolean, Boolean>>()
        val result = runCatching { api.followJobOutput("owner", "job") { text, running, done -> updates += Triple(text, running, done) } }
        if (fail) assertTrue(result.isFailure)
        else {
          assertEquals(true, result.getOrThrow())
          assertEquals("retained output", updates.joinToString("") { it.first })
          assertTrue(updates.none { it.second })
          assertEquals(listOf(false, false, true), updates.map { it.third })
        }
      } finally { api.close() }
    }
  }

}
