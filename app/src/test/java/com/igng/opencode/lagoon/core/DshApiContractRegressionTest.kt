package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused rc.5 contract regressions; keep separate from the legacy DshApiTest fixture. */
class DshApiContractRegressionTest {
  private fun profile(server: MockWebServer) =
    ServerProfile("srv", "DSH", server.url("/").toString().trimEnd('/'), username = "dsh", allowCleartext = true, kind = ServerKind.DSH)

  private fun rootOk() = MockResponse().setResponseCode(303)
    .addHeader("Set-Cookie", "dsh-auth-contract=cookie; Path=/")
    .addHeader("Location", "./")

  private fun envelope(value: Any) = MockResponse().setBody(
    JSONObject().put("type", "server-response").put("rpcId", "x")
      .put("result", JSONObject().put("ok", true).put("value", value)).toString()
  )

  private fun failure(code: String, details: JSONObject = JSONObject()) = MockResponse().setBody(
    JSONObject().put("type", "server-response").put("rpcId", "x")
      .put("result", JSONObject().put("ok", false).put("error", JSONObject()
        .put("code", code).put("message", "contract failure").put("details", details))).toString()
  )

  @Test fun coldSessionListUsesFlatModelSelectionProjection() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> rootOk()
          request.path == "/api/session/list" -> envelope(JSONObject().put("items", JSONArray().put(
            JSONObject().put("sessionId", "s1").put("cwd", "/repo").put("updatedAt", 7).put("running", false).put("blank", false)
              .put("projections", JSONObject().put("values", JSONObject().put("title", "cold")
                .put("modelSelection", JSONObject().put("lastUsed", JSONObject().put("provider", "p").put("model", "m-old"))
                  .put("next", JSONObject().put("provider", "p").put("model", "m-next").put("reasoningEffort", "high"))))))))
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val api = DshApi(profile(server), ServerCredentials("dsh", "tok"))
      try {
        val session = api.rootSessionsPage("/repo", null, 20).items.single()
        assertEquals("p", session.model?.providerId)
        assertEquals("m-next", session.model?.modelId)
        assertEquals("high", session.model?.variant)
      } finally { api.close() }
    }
  }

  @Test fun modelSelectionEventUsesFlatSelectionData() = runBlocking {
    val api = DshApi(ServerProfile("srv", "DSH", "http://127.0.0.1:1", allowCleartext = true, kind = ServerKind.DSH), ServerCredentials("dsh", "tok"))
    try {
      val session = Session("s1", "/repo", "T", 1, model = ModelChoice("old", "old", "old"))
      val known = DshApi::class.java.getDeclaredField("known").apply { isAccessible = true }.get(api)
      @Suppress("UNCHECKED_CAST")
      (known as MutableMap<String, Session>)[session.id] = session
      val events = api.translate(session, JSONObject().put("type", "model/selection").put("seq", 1).put("time", 1)
        .put("data", JSONObject().put("provider", "new-provider").put("model", "new-model").put("reasoningEffort", "low")))
      assertEquals("session.model.selected", events.single().type)
      assertEquals("new-provider", events.single().properties.obj("model").str("providerID"))
      assertEquals("new-provider", api.session("s1").model?.providerId)
      assertEquals("new-model", api.session("s1").model?.modelId)
    } finally { api.close() }
  }

  @Test fun archiveUsesAuthoritativeArchiveSetForUnarchive() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> rootOk()
          request.path == "/api/workspace/unarchiveSession" -> envelope(JSONObject().put("archivedSessionIds", JSONArray().put("other")))
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val api = DshApi(profile(server), ServerCredentials("dsh", "tok"))
      try {
        val session = Session("s1", "/repo", "T", 1, archived = true)
        api.setArchived(session, false)
        assertEquals(false, api.session("s1").archived)
      } finally { api.close() }
    }
  }

  @Test fun externalUnarchiveClearsPreviouslyKnownArchive() = runBlocking {
    val api = DshApi(ServerProfile("srv", "DSH", "http://127.0.0.1:1", allowCleartext = true, kind = ServerKind.DSH), ServerCredentials("dsh", "tok"))
    try {
      val field = DshApi::class.java.getDeclaredField("known").apply { isAccessible = true }
      @Suppress("UNCHECKED_CAST")
      val known = field.get(api) as MutableMap<String, Session>
      known["s1"] = Session("s1", "/repo", "Archived", 1, archived = true)
      val apply = DshApi::class.java.getDeclaredMethod("applyWorkspaceFrame", JSONObject::class.java).apply { isAccessible = true }
      apply.invoke(api, JSONObject().put("type", "archived").put("archivedSessionIds", JSONArray()))
      assertEquals(false, api.session("s1").archived)
    } finally { api.close() }
  }

  @Test fun rpcFailurePreservesOfficialCodeAndDetails() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> rootOk()
          request.path == "/api/session/list" -> failure("session/not-found", JSONObject().put("sessionId", "gone"))
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val api = DshApi(profile(server), ServerCredentials("dsh", "tok"))
      try {
        val result = runCatching { api.rootSessionsPage("/repo", null, 20) }
        val error = result.exceptionOrNull() as ApiException
        assertEquals("session/not-found", error.code)
        assertEquals("gone", error.details?.getString("sessionId"))
      } finally { api.close() }
    }
  }

  @Test fun workspaceAttachFailureIsNotReplayed() = runBlocking {
    MockWebServer().use { server ->
      var creates = 0
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> rootOk()
          request.path == "/api/session/create" -> {
            creates++
            failure("session/workspace-attach-failed", JSONObject().put("sessionId", "created-before-attach"))
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val api = DshApi(profile(server), ServerCredentials("dsh", "tok"))
      try {
        val error = runCatching { api.createSession("/repo", "T", "ws-1") }.exceptionOrNull() as ApiException
        assertEquals("session/workspace-attach-failed", error.code)
        assertEquals("created-before-attach", error.details?.getString("sessionId"))
        assertEquals(1, creates)
      } finally { api.close() }
    }
  }

  @Test fun imageHistoryUsesTheFullOfficial022ReferenceWhenAvailable() = runBlocking {
    MockWebServer().use { server ->
      var reads = 0
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> rootOk()
          request.path == "/api/session/attachment" -> {
            reads++
            OfficialDshContract.assertArgs("session/attachment", JSONObject(request.body.readUtf8()).obj("payload").obj("args"), "0.2.2-rc.1")
            envelope(JSONObject("""{"attachment":{"attachmentId":"img","mediaType":"image/png","bytes":2,"width":1,"height":1},"data":"aGk="}"""))
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val api = DshApi(profile(server), ServerCredentials("dsh", "tok"))
      try {
        val data = JSONObject("""{"id":"u","source":{"kind":"user"},"content":[{"type":"image","attachment":{"attachmentId":"img","mediaType":"image/png","bytes":2,"width":1,"height":1}}]}""")
        val events = api.translate(Session("s1", "/repo", "T", 1), JSONObject().put("type", "user/message").put("seq", 1).put("time", 1).put("data", data))
        assertEquals(1, reads)
        assertEquals("data:image/png;base64,aGk=", events.single().properties.obj("input").obj("_blobs").str("img"))
      } finally { api.close() }
    }
  }

  @Test fun commandImageTravelsThroughOfficialSubmittedAttachments() = runBlocking {
    MockWebServer().use { server ->
      var attachment: JSONObject? = null
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
          request.path.orEmpty().startsWith("/?token") -> rootOk()
          request.path == "/api/commands/execute" -> {
            val args = JSONObject(request.body.readUtf8()).obj("payload").obj("args")
            OfficialDshContract.assertArgs("commands/execute", args)
            attachment = args.arr("submittedAttachments").getJSONObject(0)
            envelope(JSONObject().put("result", JSONObject().put("kind", "message")))
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
      server.start()
      val api = DshApi(profile(server), ServerCredentials("dsh", "tok"))
      try {
        api.command(Session("s1", "/repo", "T", 1), "inspect", "", inline = listOf(InlineFile("a.png", "image/png", "data:image/png;base64,aGk=")))
        assertEquals("image", attachment?.str("type"))
        assertEquals("aGk=", attachment?.str("data"))
      } finally { api.close() }
    }
  }

  @Test fun commandReferenceIsRejectedRatherThanSilentlyDropped() = runBlocking {
    val api = DshApi(ServerProfile("srv", "DSH", "http://127.0.0.1:1", allowCleartext = true, kind = ServerKind.DSH), ServerCredentials("dsh", "tok"))
    try {
      val error = runCatching {
        api.command(Session("s1", "/repo", "T", 1), "test", "", references = listOf(FileReference("src/a.kt")))
      }.exceptionOrNull() as ApiException
      assertEquals("command/attachments-unsupported", error.code)
      assertNotNull(error.message)
    } finally { api.close() }
  }
}
