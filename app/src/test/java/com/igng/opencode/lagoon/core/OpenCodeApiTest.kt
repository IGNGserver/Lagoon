package com.igng.opencode.lagoon.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Requests and payloads against the OpenCode 2.x contract (`packages/protocol`). */
class OpenCodeApiTest {
  private fun api(server: MockWebServer) = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), "opencode", allowCleartext = true), "secret")

  @Test fun healthReadsTheServerVersionFromApiInfoWithBasicAuth() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"version":"2.0.22","pid":1,"urls":[],"paths":{"tmp":"/tmp"}}"""))
      val api = api(server)
      assertEquals("2.0.22", api.health())
      val info = server.takeRequest()
      assertEquals("/api/info", info.requestUrl?.encodedPath)
      assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", info.getHeader("Authorization"))
      // The version is read once per client.
      assertEquals("2.0.22", api.health())
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun aServerWithoutApiInfoIsRejectedAsNotOpenCode2() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      val error = runCatching { api(server).health() }.exceptionOrNull()
      assertTrue(error is IOException && error !is ApiException)
      assertTrue(error!!.message!!, error.message!!.contains("OpenCode 2.x"))
    }
  }

  @Test fun promptCarriesClientIdDeliveryMetadataAndInlineFilesAfterSwitchingSelection() = runBlocking {
    MockWebServer().use { server ->
      repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
      val api = api(server)
      api.send(Session("ses_1", "/repo", "Task", 0), "Fix it", "build", ModelChoice("openai", "gpt", "GPT", "high"),
        inline = listOf(InlineFile("a.png", "image/png", "data:image/png;base64,AAA=")), id = "msg_fixed")
      val agentRequest = server.takeRequest()
      assertEquals("/api/session/ses_1/agent", agentRequest.requestUrl?.encodedPath)
      assertEquals("build", JSONObject(agentRequest.body.readUtf8()).getString("agent"))
      val modelRequest = server.takeRequest()
      assertEquals("/api/session/ses_1/model", modelRequest.requestUrl?.encodedPath)
      val model = JSONObject(modelRequest.body.readUtf8()).getJSONObject("model")
      assertEquals("openai", model.getString("providerID")); assertEquals("gpt", model.getString("id")); assertEquals("high", model.getString("variant"))
      val prompt = server.takeRequest()
      assertEquals("/api/session/ses_1/prompt", prompt.requestUrl?.encodedPath)
      val body = JSONObject(prompt.body.readUtf8())
      assertEquals("msg_fixed", body.getString("id"))
      assertEquals("Fix it", body.getString("text"))
      assertEquals("steer", body.getString("delivery"))
      assertEquals("build", body.getJSONObject("metadata").getString("agent"))
      val file = body.getJSONArray("files").getJSONObject(0)
      assertEquals("data:image/png;base64,AAA=", file.getString("uri")); assertEquals("a.png", file.getString("name"))
    }
  }

  @Test fun defaultVariantIsOmittedFromTheModelReference() = runBlocking {
    MockWebServer().use { server ->
      repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
      api(server).send(Session("ses_1", "/repo", "Task", 0), "hello", null, ModelChoice("openai", "gpt", "GPT"))
      server.takeRequest() // POST /model
      val prompt = server.takeRequest()
      val model = JSONObject(prompt.body.readUtf8()).getJSONObject("metadata").getJSONObject("model")
      assertFalse(model.has("variant"))
    }
  }

  @Test fun followingTheSessionSelectionSendsOnlyThePrompt() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{}}"""))
      api(server).send(Session("ses_1", "/repo", "Task", 0), "hello", null, null)
      assertEquals("/api/session/ses_1/prompt", server.takeRequest().requestUrl?.encodedPath)
      assertEquals(1, server.requestCount)
    }
  }

  @Test fun aRejectedPromptIsNotRetriedButALostResponseIsRetriedOnceWithTheSameId() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"bad"}"""))
      assertTrue(runCatching { api(server).send(Session("ses_1", "/repo", "T", 0), "x", null, null) }.isFailure)
      assertEquals(1, server.requestCount)
    }
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
      server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Prompt message ID conflicts"}"""))
      api(server).send(Session("ses_1", "/repo", "T", 0), "x", null, null, id = "msg_same")
      val first = JSONObject(server.takeRequest().body.readUtf8()).getString("id")
      val second = JSONObject(server.takeRequest().body.readUtf8()).getString("id")
      assertEquals("msg_same", first); assertEquals(first, second)
    }
  }

  @Test fun slashCommandsRunThroughTheCommandRoute() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(204))
      api(server).command(Session("ses_1", "/repo", "T", 0), "review", "src/main", null, null)
      val request = server.takeRequest()
      assertEquals("/api/session/ses_1/command", request.requestUrl?.encodedPath)
      val body = JSONObject(request.body.readUtf8())
      assertEquals("review", body.getString("name")); assertEquals("src/main", body.getString("text"))
    }
  }

  @Test fun messagesUseTheOfficialPageSizeAndComeBackOldestFirst() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":[
        {"id":"msg_2","type":"assistant","time":{"created":4},"agent":"build","model":{"providerID":"openai","id":"gpt"},"content":[{"type":"text","text":"done"}]},
        {"id":"msg_1","type":"user","time":{"created":3},"text":"hello"}
      ],"cursor":{"next":"older"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[],"cursor":{}}"""))
      val api = api(server)
      val page = api.messagesPage("ses_1")
      assertEquals(listOf("msg_1", "msg_2"), page.items.map { it.id })
      assertEquals("older", page.next)
      api.messagesPage("ses_1", page.next)
      val first = server.takeRequest().requestUrl!!
      assertEquals("20", first.queryParameter("limit")); assertEquals("desc", first.queryParameter("order"))
      val second = server.takeRequest().requestUrl!!
      assertEquals("older", second.queryParameter("cursor")); assertNull(second.queryParameter("order"))
    }
  }

  @Test fun projectAtResolvesTheCanonicalProjectForADirectory() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":{"directory":"/repo/sub","project":{"id":"p","canonical":"/repo","name":"Repo","sandboxes":["/trees/task"]}}}"""))
      val project = api(server).projectAt("/repo/sub")
      assertEquals(Project("p", "/repo", "Repo", listOf("/trees/task")), project)
      val request = server.takeRequest().requestUrl!!
      assertEquals("/api/location", request.encodedPath)
      assertEquals("/repo/sub", request.queryParameter("location[directory]"))
    }
  }

  @Test fun modelCatalogKeepsServerVariantIdsIncludingMaxInOrder() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt","providerID":"openai","name":"GPT","enabled":true,"variants":[{"id":"low","headers":{},"body":{}},{"id":"max","headers":{},"body":{}},{"id":"custom","headers":{},"body":{}}]}]}"""))
      server.enqueue(MockResponse().setResponseCode(404))
      val catalog = api(server).modelCatalog("/repo")
      assertEquals(listOf("low", "max", "custom"), catalog.single().variants.map { it.id })
      assertEquals(listOf("low", "max", "custom"), catalog.single().variants.map { it.label })
    }
  }

  @Test fun pendingInboxInputBecomesUserRows() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":[
        {"id":"msg_q","sessionID":"ses_1","type":"user","delivery":"queue","payload":{"text":"next","metadata":{"displayText":"下一步"}},"time":{"created":5}},
        {"id":"msg_c","sessionID":"ses_1","type":"compaction","delivery":"steer","payload":{},"time":{"created":6}}
      ]}"""))
      val pending = api(server).inbox("ses_1")
      assertEquals(listOf("msg_q"), pending.map { it.id })
      assertTrue(pending.single().queued)
      assertEquals("下一步", pending.single().parts.single().text)
      assertEquals("/api/session/ses_1/inbox", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  @Test fun permissionsAreReadPerSessionAndRepliedWithADecision() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"per_a","sessionID":"ses_a","action":"bash","resources":["ls"],"save":["ls *"],"source":{"type":"tool","messageID":"msg","id":"call"}}]}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = api(server)
      val permission = api.sessionPermissions(Session("ses_a", "/repo", "T", 0)).single()
      assertEquals("/api/session/ses_a/permission", server.takeRequest().requestUrl?.encodedPath)
      assertEquals(listOf("ls *"), permission.always); assertEquals("call", permission.toolCallId); assertEquals("bash", permission.action)
      api.replyPermission(permission, "always")
      val reply = server.takeRequest()
      assertEquals("/api/session/ses_a/permission/per_a/reply", reply.requestUrl?.encodedPath)
      assertEquals("always", JSONObject(reply.body.readUtf8()).getString("decision"))
      // Without a server-provided save scope, “always” is refused before any request.
      assertTrue(runCatching { api.replyPermission(permission.copy(always = emptyList()), "always") }.isFailure)
      assertEquals(2, server.requestCount)
    }
  }

  @Test fun formsAreReadAtTheSessionLocation() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"location":{"directory":"/repo"},"data":[{"id":"frm_a","sessionID":"ses_a","title":"Config","fields":[{"key":"enabled","type":"boolean","required":true}]}]}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = api(server)
      val form = api.forms("/repo").single()
      val list = server.takeRequest()
      assertEquals("/api/form", list.requestUrl?.encodedPath)
      assertEquals("/repo", list.requestUrl?.queryParameter("location[directory]"))
      api.replyQuestion(form, listOf(listOf("true")))
      val reply = server.takeRequest()
      assertEquals("/api/session/ses_a/form/frm_a/reply", reply.requestUrl?.encodedPath)
      assertTrue(JSONObject(reply.body.readUtf8()).getJSONObject("answer").getBoolean("enabled"))
      api.rejectQuestion(form)
      assertEquals("DELETE", server.takeRequest().method)
    }
  }

  @Test fun activeSessionsAreTheKeysOfTheActiveMap() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":{"ses_1":{"type":"running"},"ses_2":{"type":"running"}}}"""))
      assertEquals(setOf("ses_1", "ses_2"), api(server).activeSessions())
      assertEquals("/api/session/active", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  @Test fun runningShellsAreReadPerLocationAndCarryTheirSession() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"location":{"directory":"/repo"},"data":[
        {"id":"sh_1","status":"running","command":"sleep 100","cwd":"/repo","shell":"/bin/bash","file":"/tmp/sh_1","metadata":{"sessionID":"ses_1"},"time":{"started":5}},
        {"id":"sh_2","status":"running","command":"make","cwd":"/repo","shell":"/bin/bash","file":"/tmp/sh_2","metadata":{},"time":{"started":6}}
      ]}"""))
      val shells = api(server).shells("/repo")
      assertEquals(1, shells.size)
      assertEquals("sh_1", shells.single().id)
      assertEquals("ses_1", shells.single().sessionId)
      assertEquals("sleep 100", shells.single().command)
      assertEquals("/repo", shells.single().directory)
      val request = server.takeRequest()
      assertEquals("/api/shell", request.requestUrl?.encodedPath)
      assertEquals("/repo", request.requestUrl?.queryParameter("location[directory]"))
    }
  }

  @Test fun renameAndUnrevertFollowTheDocumentedRoutes() = runBlocking {
    MockWebServer().use { server ->
      // An early 2.0.x instance: POST /rename and POST /revert/clear.
      server.enqueue(MockResponse().setBody("""{"paths":{
        "/api/session/{sessionID}/rename":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}}}}}},
        "/api/session/{sessionID}/revert/clear":{"post":{}}
      }}"""))
      repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
      val api = api(server)
      val capabilities = api.discoverCapabilities()
      assertEquals("/openapi.json", server.takeRequest().requestUrl?.encodedPath)
      assertFalse(capabilities.diff); assertNull(capabilities.sessionView)
      api.renameSession(Session("ses_1", "/repo", "Old", 0), "New")
      val rename = server.takeRequest()
      assertEquals("POST", rename.method); assertEquals("/api/session/ses_1/rename", rename.requestUrl?.encodedPath)
      api.unrevert(Session("ses_1", "/repo", "Old", 0))
      val clear = server.takeRequest()
      assertEquals("POST", clear.method); assertEquals("/api/session/ses_1/revert/clear", clear.requestUrl?.encodedPath)
    }
  }

  @Test fun archivingWritesTimeArchivedAndRestoringSendsNull() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":
        {"type":"object","properties":{"time":{"type":"object","properties":{"archived":{"type":["number","null"]}}}}}}}}}}}}"""))
      repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
      val api = api(server)
      api.discoverCapabilities(); server.takeRequest()
      val session = Session("ses_1", "/repo", "Old", 0)
      api.setArchived(session, true, now = 42)
      val archive = server.takeRequest()
      assertEquals("PATCH", archive.method); assertEquals("/api/session/ses_1", archive.requestUrl?.encodedPath)
      assertEquals(42L, JSONObject(archive.body.readUtf8()).getJSONObject("time").getLong("archived"))
      api.setArchived(session, false)
      assertTrue(JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("time").isNull("archived"))
    }
  }

  @Test fun anUnreadableDocumentFallsBackToTheCurrentContract() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
      val api = api(server)
      assertEquals(ApiCapabilities.BASELINE, api.discoverCapabilities())
      server.takeRequest()
      api.renameSession(Session("ses_1", "/repo", "Old", 0), "New")
      val rename = server.takeRequest()
      assertEquals("PATCH", rename.method); assertEquals("/api/session/ses_1", rename.requestUrl?.encodedPath)
      api.unrevert(Session("ses_1", "/repo", "Old", 0))
      assertEquals("DELETE", server.takeRequest().method)
    }
  }

  @Test fun clientIdsSortWithServerIdsAndCarryTheMessagePrefix() {
    val first = MessageIds.ascending(1_700_000_000_000)
    val second = MessageIds.ascending(1_700_000_000_000)
    val later = MessageIds.ascending(1_700_000_000_001)
    assertTrue(first.startsWith("msg_")); assertEquals(30, first.length)
    assertTrue(first < second); assertTrue(second < later)
  }

  @Test fun eventFramesKeepTypeDataLocationAndCreatedTime() {
    val event = """{"id":"evt_1","created":42,"type":"session.text.delta","location":{"directory":"/repo"},"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"delta":"hi"}}""".toServerEvent("")
    assertEquals("evt_1", event.id); assertEquals("session.text.delta", event.type); assertEquals("/repo", event.directory)
    assertEquals(42L, event.created); assertEquals("hi", event.properties.getString("delta"))
    assertEquals("msg_1", TranscriptProjection.messageId(event))
  }

  @Test fun taskPhaseGroupsStayConsistent() {
    // active must be the union of the running and waiting groups used by the UI filters.
    assertTrue(TaskState.ACTIVE_PHASES == TaskState.RUNNING_PHASES + TaskState.WAITING_PHASES)
    assertTrue(TaskState.RUNNING_PHASES.intersect(TaskState.WAITING_PHASES).isEmpty())
    for (phase in TaskPhase.entries) {
      assertEquals(phase in TaskState.ACTIVE_PHASES, TaskState("id", phase).active)
    }
  }

  @Test fun taskReducerRequiresPriorActivityBeforeIdleBecomesCompletion() {
    val idle = TaskReducer.status("id", false)
    assertEquals(TaskPhase.IDLE, idle.phase)
    val busy = TaskReducer.status("id", true, idle, idleBaseline = 0)
    assertEquals(TaskPhase.THINKING, busy.phase)
    val tool = TaskReducer.event("id", "session.tool.called", JSONObject("""{"name":"shell","input":{"command":"gradle test"}}"""), busy, 100)
    assertEquals(TaskPhase.TESTING, tool!!.phase)
    // Every running sub-phase shares one user-facing label.
    assertEquals(TaskState.RUNNING_DETAIL, tool.detail)
    // 不在前台活跃集合（active miss）不等于本轮结束：后台任务可能仍在跑。
    assertEquals(TaskPhase.TESTING, TaskReducer.status("id", false, tool).phase)
    // 服务端 time.idle 越过本轮基线时才是权威结束（对账兜底）。
    val completed = TaskReducer.terminal("id", "succeeded", tool)
    assertEquals(TaskPhase.COMPLETED, completed.phase)
    assertEquals(TaskPhase.COMPLETED, TaskReducer.status("id", false, completed).phase)
    assertEquals(TaskPhase.THINKING, TaskReducer.status("id", true, completed).phase)
  }

  /** Contract validation for TaskReducer phase transitions. */
  @Test fun taskReducerMatchesSharedContract() {
    val fixture = JSONObject(java.io.File("docs/task-event-contract.json").readText())
    val cases = fixture.getJSONArray("cases")
    for (index in 0 until cases.length()) {
      val case = cases.getJSONObject(index)
      val previous = case.optString("previous").takeIf { it.isNotBlank() && it != "null" }?.let { TaskState("s", TaskPhase.valueOf(it)) }
      val result = TaskReducer.event("s", case.getString("type"), case.getJSONObject("data"), previous) ?: previous
      assertEquals(case.getString("name"), case.getString("expectedPhase"), (result?.phase ?: TaskPhase.IDLE).name)
    }
  }

  @Test fun pairTokenResolvesCredentials() = runBlocking {
    val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("opencode:secret".toByteArray())
    val pair = PairLinkResolver.resolve("https://example.test/auth/connect/abc?auth_token=$token")
    assertEquals("https://example.test", pair.serverUrl)
    assertEquals("opencode", pair.credentials.username)
    assertEquals("secret", pair.credentials.password)
    assertTrue(PairLinkResolver.isPairLink("https://example.test/auth/connect/abc"))
    assertFalse(PairLinkResolver.isPairLink("https://example.test/"))
    assertNotNull(pair.credentials)
  }

  @Test fun resolvesOfficialPairRedirectAndPersistsSessionCookie() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/"))
      server.enqueue(MockResponse().setBody("<html>OpenCode</html>").addHeader("Set-Cookie", "opencode-session=session-value; Path=/; HttpOnly"))
      val pair = PairLinkResolver.resolve(server.url("/auth/connect/one-time").toString())
      assertEquals(server.url("/").toString().trimEnd('/'), pair.serverUrl)
      assertEquals("opencode-session=session-value", pair.credentials.cookie)
    }
  }

  @Test fun encodesSessionIdsExactlyOnceInPath() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":[],"cursor":{}}"""))
      api(server).messagesPage("ses 1/2")
      // 'ses 1/2' must be percent-encoded once: %20 for the space and %2F for the slash.
      assertEquals("/api/session/ses%201%2F2/message", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  @Test fun diffMapsFileDiffInfo() {
    val change = JSONObject("""{"file":"a.kt","patch":"@@","additions":3,"deletions":1,"status":"modified"}""").toChange()
    assertEquals("a.kt", change.path); assertEquals(3, change.additions); assertEquals("@@", change.patch)
  }

  /**
   * A01: credentials must never leave the configured origin, not even when the server answers with a
   * redirect. The disallowed target must receive zero requests.
   */
  @Test fun apiDoesNotForwardCredentialsToRedirectTarget() = runBlocking {
    MockWebServer().use { target ->
      MockWebServer().use { origin ->
        target.enqueue(MockResponse().setBody("""{"version":"9"}"""))
        origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/steal")))
        runCatching { api(origin).health() }
        // The redirect must not have been followed at all.
        assertEquals(0, target.requestCount)
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", origin.takeRequest().getHeader("Authorization"))
      }
    }
  }

  /** A01: pairing must reject a redirect to a different host before sending the one-time token. */
  @Test fun pairLinkRedirectRejectsBeforeSendingToDisallowedHost() = runBlocking {
    MockWebServer().use { target ->
      target.enqueue(MockResponse().setBody("<html>stolen</html>"))
      MockWebServer().use { origin ->
        origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/auth/connect/leak")))
        val result = runCatching { PairLinkResolver.resolve(origin.url("/auth/connect/one-time").toString()) }
        assertTrue(result.isFailure)
        assertEquals(0, target.requestCount)
      }
    }
  }

  /** A15: cancelling the caller must abort the in-flight HTTP call instead of waiting it out. */
  @Test fun cancellingApiInterruptsNetworkWait() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"version":"1"}""").setBodyDelay(3, TimeUnit.SECONDS))
      val api = api(server)
      val job = launch(Dispatchers.IO) { runCatching { api.health() } }
      delay(200)
      val started = System.nanoTime()
      job.cancelAndJoin()
      val elapsedMillis = (System.nanoTime() - started) / 1_000_000
      assertTrue("cancel took ${elapsedMillis}ms", elapsedMillis < 1_500)
    }
  }

  /** A01: the cleartext target allow-list only accepts loopback hosts. */
  @Test fun cleartextAllowListIsLoopbackOnly() {
    assertTrue(HttpOrigin.allowsCleartext("localhost"))
    assertTrue(HttpOrigin.allowsCleartext("127.0.0.1"))
    assertTrue(HttpOrigin.allowsCleartext("::1"))
    assertFalse(HttpOrigin.allowsCleartext("example.com"))
    assertFalse(HttpOrigin.allowsCleartext("10.0.0.5"))
  }

  @Test fun degradedStateDefaultsToHealthyAndIsIndependentOfOfflineFlag() {
    val offline = LagoonState(connected = false, cached = true)
    assertTrue(offline.cached)
    assertFalse(offline.degraded)
  }

  /**
   * Adding a server against an address that answers HTTP 200 without a JSON object (a web UI, a
   * proxy page, plain text, an empty body) must surface a friendly Chinese error instead of the
   * raw org.json message "Value ... of type java.lang.String cannot be converted to JSONObject".
   */
  @Test fun nonJsonSuccessResponsesReportFriendlyErrors() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("<!DOCTYPE html><html><body>OpenCode</body></html>").addHeader("Content-Type", "text/html"))
      server.enqueue(MockResponse().setBody("OK"))
      server.enqueue(MockResponse().setResponseCode(200))
      suspend fun healthError(): Throwable? = api(server).let { api -> runCatching { api.health() }.exceptionOrNull() }
      val html = healthError()
      assertTrue("expected IOException, got $html", html is IOException && html !is ApiException)
      assertTrue(html!!.message!!, html.message!!.contains("服务器返回的是网页"))
      assertFalse(html.message!!, html.message!!.contains("cannot be converted"))
      val text = healthError()
      assertTrue(text!!.message!!, text.message!!.contains("不是有效 JSON"))
      val empty = healthError()
      assertTrue(empty!!.message!!, empty.message!!.contains("空响应"))
    }
  }

  @Test fun aSilentEventStreamFailsInsteadOfLookingConnectedForever() = runBlocking {
    MockWebServer().use { server ->
      // One event, then the connection goes quiet without closing: a half-open socket.
      val event = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n"
      server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(event + " ".repeat(64)).throttleBody(event.length.toLong(), 3, TimeUnit.SECONDS))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), "opencode", allowCleartext = true),
        ServerCredentials("opencode", "secret"), streamIdleSeconds = 1)
      val received = mutableListOf<String>()
      val started = System.nanoTime()
      val failure = kotlinx.coroutines.withTimeout(10_000) { runCatching { api.events().collect { received += it.type } }.exceptionOrNull() }
      assertEquals(listOf("server.connected"), received)
      assertTrue("a quiet stream must fail so the caller reconnects: $failure", failure is IOException)
      assertTrue((System.nanoTime() - started) / 1_000_000 < 2_900)
    }
  }

}
