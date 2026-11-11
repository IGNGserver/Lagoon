package com.igng.opencode.lagoon.core

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Direct HTTP + real mux framing, matching the installed official rc.5 workspace Typert contract. */
class DshWorkspaceManagementTest {
  private fun row(id: String = "w1", path: String = "/repo", title: String = "Repo",
    updated: String = "2026-11-01T00:00:00Z") = JSONObject()
    .put("workspaceId", id).put("path", path).put("title", title)
    .put("sessionIds", JSONArray().put("s1"))
    .put("createdAt", "2026-11-01T00:00:00Z").put("updatedAt", updated)

  private fun baseline(vararg rows: JSONObject) = JSONObject().put("type", "baseline")
    .put("value", JSONObject().put("items", JSONArray(rows.toList()))
      .put("archivedSessionIds", JSONArray()).put("pinnedSessionIds", JSONArray()))

  private fun ok(value: Any) = MockResponse().setBody(JSONObject().put("type", "server-response")
    .put("rpcId", "test").put("result", JSONObject().put("ok", true).put("value", value)).toString())

  private class Fixture(initial: JSONObject) : Closeable {
    val server = MockWebServer()
    @Volatile var frame = initial
    val reads = AtomicInteger()
    @Volatile var beforeBaseline: (() -> Unit)? = null
    val writes = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    @Volatile var mutation: (String, JSONObject) -> MockResponse = { _, _ -> MockResponse().setResponseCode(500) }
    val client: DshApi
    init {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.path.orEmpty().startsWith("/?token") || request.path == "/")
            return MockResponse().setResponseCode(303).addHeader("Set-Cookie", "dsh-auth-test=value; Path=/; HttpOnly")
              .addHeader("Location", "./")
          if (request.path == "/api/remote.mux") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
              val open = JSONObject(text)
              if (open.optString("type") != "open") return
              if (open.optString("endpoint") != "workspace/follow") error("Unexpected stream: $text")
              OfficialDshContract.assertArgs("workspace/follow", open.getJSONObject("payload").getJSONObject("args"))
              reads.incrementAndGet()
              beforeBaseline?.invoke()
              webSocket.send(JSONObject().put("type", "item").put("streamId", open.getString("streamId"))
                .put("value", frame).toString())
            }
          })
          val body = JSONObject(request.body.readUtf8())
          val endpoint = body.getString("method")
          val args = body.getJSONObject("payload").getJSONObject("args")
          OfficialDshContract.assertArgs(endpoint, args)
          check(args.keys().asSequence().toSet() == setOf("request"))
          val input = args.getJSONObject("request")
          writes += endpoint to input
          return mutation(endpoint, input)
        }
      }
      server.start()
      val profile = ServerProfile("srv", "DSH", server.url("/").toString().trimEnd('/'),
        username = "dsh", allowCleartext = true, kind = ServerKind.DSH)
      client = DshApi(profile, ServerCredentials("dsh", "token"))
    }
    override fun close() { client.close(); server.shutdown() }
  }

  private suspend fun failure(status: Int, block: suspend () -> Any?): ApiException {
    try { block(); fail("Expected ApiException($status)") } catch (error: ApiException) {
      assertEquals(status, error.status)
      return error
    }
    error("unreachable")
  }

  @Test fun catalogIsFreshTypedAndNotTheProjectCache() = runBlocking {
    Fixture(baseline(row())).use { f ->
      val first = f.client.serverWorkspaces().single()
      assertEquals(ServerWorkspace("w1", "/repo", "Repo", listOf("s1"),
        "2026-11-01T00:00:00Z", "2026-11-01T00:00:00Z"), first)
      f.frame = baseline(row(title = "Updated", updated = "2026-11-02T00:00:00Z"))
      assertEquals("Updated", f.client.serverWorkspaces().single().title)
      assertEquals(2, f.reads.get())
      assertEquals("Updated", f.client.projectsForDisplay().single().name)
      assertTrue(f.writes.isEmpty())
    }
  }

  @Test fun wrongFirstFrameNeverFallsBackToPreviouslyCachedCatalog() = runBlocking {
    Fixture(baseline(row())).use { f ->
      f.client.serverWorkspaces()
      f.frame = JSONObject().put("type", "order").put("workspaceIds", JSONArray().put("w1"))
      assertEquals("workspace/baseline-invalid", failure(502) { f.client.serverWorkspaces() }.code)
      assertEquals("Repo", f.client.projectsForDisplay().single().name)
      assertTrue(f.writes.isEmpty())
    }
  }

  @Test fun strictMalformedCatalogFailsBeforeUpdatingProjectCache() = runBlocking {
    Fixture(baseline(row())).use { f ->
      f.client.serverWorkspaces()
      val invalid = listOf(
        row().put("createdAt", 123), row().put("updatedAt", "yesterday"),
        row().put("updatedAt", "2026-10-01T00:00:00Z"), row().put("sessionIds", -1),
        row().put("sessionIds", JSONArray().put("s1").put("s1")), row().put("title", true),
        row().put("path", "relative"), row().put("workspaceId", ""))
      for (item in invalid) {
        f.frame = baseline(item)
        failure(502) { f.client.serverWorkspaces() }
        assertEquals("Repo", f.client.projectsForDisplay().single().name)
      }
      f.frame = baseline(row(), row(title = "Duplicate"))
      failure(502) { f.client.serverWorkspaces() }
      f.frame = baseline(row()).also { it.getJSONObject("value").getJSONArray("items").put("not an object") }
      failure(502) { f.client.serverWorkspaces() }
      assertTrue(f.writes.isEmpty())
    }
  }

  @Test fun boundsAndCrossPlatformAbsolutePathsAreExplicit() {
    for (path in listOf("/", "/home/repo", "C:\\repo", "d:/repo", "\\\\host\\share\\repo"))
      assertTrue(path, ServerWorkspaceCodec.validPath(path))
    for (path in listOf("repo", "~/repo", "C:repo", " /repo", "/repo ", "/a\nb", ""))
      assertFalse(path, ServerWorkspaceCodec.validPath(path))
    for (value in listOf(JSONArray(List(5_001) { row("w$it") }), JSONArray().put(row().put("sessionIds", JSONArray(List(50_001) { "s$it" }))))) {
      try { ServerWorkspaceCodec.catalog(value); fail("Oversized catalog admitted") }
      catch (expected: ApiException) { assertEquals(502, expected.status) }
    }
  }

  @Test fun createUsesNativeRequestAndCanonicalHostPathWithoutBackgroundEvents() = runBlocking {
    Fixture(baseline()).use { f ->
      val canonical = row(path = "/canonical/repo")
      f.mutation = { endpoint, input ->
        assertEquals("workspace/create", endpoint)
        assertEquals(setOf("path"), input.keys().asSequence().toSet())
        assertEquals("/symlink/repo", input.getString("path"))
        f.frame = baseline(canonical)
        ok(JSONObject().put("workspace", canonical).put("created", true))
      }
      val result = f.client.registerWorkspace("/symlink/repo")
      assertTrue(result.created)
      assertEquals("/canonical/repo", result.workspace.path)
      assertEquals("w1", f.client.projectAt("/canonical/repo")!!.id)
      assertEquals(1, f.writes.size)
      assertEquals(1, f.reads.get())
    }
  }

  @Test fun duplicateRegistrationPreservesNativeCreatedFalse() = runBlocking {
    Fixture(baseline(row())).use { f ->
      f.mutation = { _, _ -> ok(JSONObject().put("workspace", row()).put("created", false)) }
      assertFalse(f.client.registerWorkspace("/repo").created)
      assertEquals(1, f.writes.size)
    }
  }

  @Test fun renameHasFreshFullSnapshotPreflightAndOnlyOfficialArguments() = runBlocking {
    Fixture(baseline(row())).use { f ->
      val expected = f.client.serverWorkspaces().single()
      f.mutation = { endpoint, input ->
        assertEquals("workspace/rename", endpoint)
        assertEquals(setOf("workspaceId", "title"), input.keys().asSequence().toSet())
        assertEquals("w1", input.getString("workspaceId"))
        assertEquals("New title", input.getString("title"))
        ok(JSONObject().put("workspace", row(title = "New title", updated = "2026-11-02T00:00:00Z")))
      }
      assertEquals("New title", f.client.renameWorkspace(expected, " New title ").title)
      assertEquals(2, f.reads.get())
      assertEquals(1, f.writes.size)
    }
  }

  @Test fun staleMissingTargetAndChangedSessionOrderRejectBeforeAnyMutation() = runBlocking {
    Fixture(baseline(row())).use { f ->
      val expected = f.client.serverWorkspaces().single()
      f.frame = baseline(row().put("sessionIds", JSONArray().put("other")))
      assertEquals("workspace/snapshot-conflict", failure(409) { f.client.renameWorkspace(expected, "New") }.code)
      failure(409) { f.client.deleteWorkspace(expected) }
      f.frame = baseline()
      assertEquals("workspace/not-found", failure(404) { f.client.moveWorkspace(expected, null) }.code)
      assertTrue(f.writes.isEmpty())
    }
  }

  @Test fun deleteOnlyRemovesRegistrationAndSendsNoSessionOrFilesystemRequests() = runBlocking {
    Fixture(baseline(row())).use { f ->
      val expected = f.client.serverWorkspaces().single()
      f.mutation = { endpoint, input ->
        assertEquals("workspace/delete", endpoint)
        assertEquals(setOf("workspaceId"), input.keys().asSequence().toSet())
        f.frame = baseline()
        ok(JSONObject().put("deleted", true))
      }
      assertTrue(f.client.deleteWorkspace(expected))
      assertTrue(f.client.projects().isEmpty())
      assertEquals(listOf("workspace/delete"), f.writes.map { it.first })
    }
  }

  @Test fun moveChecksAnchorAndOmitsNullAnchorToMeanEnd() = runBlocking {
    Fixture(baseline(row(), row("w2", "/two", "Two"))).use { f ->
      val catalog = f.client.serverWorkspaces()
      val expected = catalog[0]; val anchor = catalog[1]
      f.frame = baseline(row(), row("w2", "/two", "Changed"))
      failure(409) { f.client.moveWorkspace(expected, anchor) }
      assertTrue(f.writes.isEmpty())
      f.frame = baseline(row(), row("w2", "/two", "Two"))
      f.mutation = { endpoint, _ ->
        assertEquals("workspace/insertBefore", endpoint)
        ok(JSONObject().put("workspaceIds", JSONArray().put("w2").put("w1")))
      }
      assertEquals(listOf("w2", "w1"), f.client.moveWorkspace(expected, anchor))
      assertEquals("w2", f.writes.last().second.getString("beforeWorkspaceId"))
      f.client.moveWorkspace(expected, null)
      assertFalse(f.writes.last().second.has("beforeWorkspaceId"))
      assertEquals(setOf("workspaceId"), f.writes.last().second.keys().asSequence().toSet())
    }
  }

  private fun applyFrame(client: DshApi, frame: JSONObject) {
    DshApi::class.java.getDeclaredMethod("applyWorkspaceFrame", JSONObject::class.java).apply { isAccessible = true }.invoke(client, frame)
  }

  @Test fun incrementAfterMutationInvalidationCannotBecomeAPartialCompleteCatalog() = runBlocking {
    Fixture(baseline(row(), row("w2", "/two", "Two"))).use { f ->
      val expected = f.client.serverWorkspaces().first()
      f.mutation = { _, _ -> ok(JSONObject().put("workspace", row(title = "Renamed"))) }
      f.client.renameWorkspace(expected, "Renamed")
      applyFrame(f.client, JSONObject().put("type", "upsert").put("workspace", row(title = "Renamed")))
      assertTrue(f.client.projectsForDisplay().isEmpty())
      f.frame = baseline(row(title = "Renamed"), row("w2", "/two", "Two"))
      assertEquals(listOf("w1", "w2"), f.client.projects().map { it.id })
      assertEquals(3, f.reads.get())
    }
  }

  @Test fun knownLiveChangeDuringFreshBaselineCannotAuthorizeAnAlreadyStaleTarget() = runBlocking {
    Fixture(baseline(row())).use { f ->
      val expected = f.client.serverWorkspaces().single()
      f.beforeBaseline = {
        applyFrame(f.client, JSONObject().put("type", "upsert").put("workspace", row(title = "Live newer", updated = "2026-11-02T00:00:00Z")))
      }
      failure(409) { f.client.deleteWorkspace(expected) }
      assertTrue(f.writes.isEmpty())
      assertEquals("Live newer", f.client.projectsForDisplay().single().name)
    }
  }

  @Test fun mutation401IsNotReauthenticatedAndReplayed() = runBlocking {
    Fixture(baseline()).use { f ->
      f.mutation = { _, _ -> MockResponse().setResponseCode(401) }
      failure(401) { f.client.registerWorkspace("/repo") }
      assertEquals(1, f.writes.size)
    }
  }

  @Test fun retryAfterZeroStatusCannotReplayAWorkspaceMutation() = runBlocking {
    Fixture(baseline()).use { f ->
      f.mutation = { _, _ -> MockResponse().setResponseCode(503).addHeader("Retry-After", "0") }
      failure(503) { f.client.registerWorkspace("/repo") }
      assertEquals(1, f.writes.size)
    }
  }

  @Test fun connectionLossAfterMutationIsNotRetried() = runBlocking {
    Fixture(baseline()).use { f ->
      f.mutation = { _, _ -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
      failure(0) { f.client.registerWorkspace("/repo") }
      assertEquals(1, f.writes.size)
    }
  }

  @Test fun malformedAcknowledgementIsNotSuccessAndDoesNotPatchCacheOrRetry() = runBlocking {
    Fixture(baseline(row())).use { f ->
      f.client.serverWorkspaces()
      f.mutation = { _, _ -> ok(JSONObject().put("workspace", row()).put("created", "true")) }
      failure(502) { f.client.registerWorkspace("/repo") }
      assertTrue(f.client.projectsForDisplay().isEmpty())
      f.frame = baseline(row(title = "Host latest", updated = "2026-11-03T00:00:00Z"))
      assertEquals("Host latest", f.client.projectAt("/repo")!!.name)
      assertEquals(1, f.writes.size)
    }
  }

  @Test fun invalidInputsNeverSendMutationAndCapabilitiesStayProtocolSpecific() = runBlocking {
    Fixture(baseline(row())).use { f ->
      assertTrue(f.client.capabilities().workspaceManagement)
      assertTrue(DshApi.TUNNEL_CAPABILITIES.workspaceManagement)
      assertFalse(ApiCapabilities.BASELINE.workspaceManagement)
      assertFalse(ApiCapabilities.fromDocument(JSONObject().put("paths", JSONObject())).workspaceManagement)
      val expected = ServerWorkspaceCodec.workspace(row())
      failure(400) { f.client.registerWorkspace("relative") }
      failure(400) { f.client.renameWorkspace(expected, "\n") }
      failure(400) { f.client.moveWorkspace(expected, expected) }
      assertTrue(f.writes.isEmpty())
      assertEquals(0, f.reads.get())
    }
  }
}
