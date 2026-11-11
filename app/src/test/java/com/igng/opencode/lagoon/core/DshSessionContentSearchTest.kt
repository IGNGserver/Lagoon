package com.igng.opencode.lagoon.core

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DshSessionContentSearchTest {
  private fun rootOk() = MockResponse().setResponseCode(303)
    .addHeader("Set-Cookie", "dsh-auth-abc=cookie-value; Path=/; HttpOnly; SameSite=Strict")
    .addHeader("Location", "./")

  private fun envelope(value: Any?) = MockResponse().setBody(JSONObject()
    .put("type", "server-response").put("rpcId", "x")
    .put("result", JSONObject().put("ok", true).put("value", value ?: JSONObject.NULL)).toString())

  private fun hit(id: Any = "session-1", snippet: Any = "literal match") =
    JSONObject().put("sessionId", id).put("snippet", snippet)

  private fun result(items: JSONArray = JSONArray(), hasMore: Boolean = false) =
    JSONObject().put("items", items).put("hasMore", hasMore)

  private fun row(id: Any = "session-1", directory: Any = "/repo", title: String = "Fresh title") = JSONObject()
    .put("sessionId", id).put("cwd", directory).put("updatedAt", 100)
    .put("running", false).put("blank", false)
    .put("projections", JSONObject().put("values", JSONObject().put("title", title)))

  private fun listing(vararg rows: JSONObject) = JSONObject().put("items", JSONArray(rows.toList()))
  private fun request(server: MockWebServer): RecordedRequest = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))

  private suspend fun withApi(block: suspend (DshApi, MockWebServer) -> Unit) {
    MockWebServer().use { server ->
      val profile = ServerProfile("srv", "DSH", server.url("/").toString().trimEnd('/'),
        username = "dsh", allowCleartext = true, kind = ServerKind.DSH)
      val api = DshApi(profile, ServerCredentials("dsh", "tok"))
      try { block(api, server) } finally { api.close(); api.awaitClosed() }
    }
  }

  private suspend fun failure(status: Int, block: suspend () -> Unit) {
    try { block(); fail("Expected failure $status") }
    catch (error: ApiException) { assertEquals(status, error.status) }
  }

  @Test fun literalSearchUsesOfficialStrictRequestAndNoListFollowOrAgentRpc() = runBlocking {
    withApi { api, server ->
      val value = result(JSONArray().put(hit("opaque:/session?x", "A [literal].* \\ text")), true)
      OfficialDshContract.assertResult("session/search", value)
      server.enqueue(rootOk()); server.enqueue(envelope(value))
      val found = api.searchSessionContent(" \n[literal].* \\ \t")
      assertEquals("[literal].* \\", found.query)
      assertEquals(listOf(SessionContentHit("opaque:/session?x", "A [literal].* \\ text")), found.items)
      assertTrue(found.hasMore)
      assertTrue(request(server).path.orEmpty().startsWith("/?token="))
      val search = request(server)
      assertEquals("POST", search.method)
      assertEquals("/api/session/search", search.path)
      assertTrue(search.getHeader("Cookie").orEmpty().contains("dsh-auth-abc="))
      val rpc = JSONObject(search.body.readUtf8())
      assertEquals("client-request", rpc.getString("type"))
      assertEquals("session/search", rpc.getString("method"))
      assertTrue(rpc.getString("rpcId").isNotBlank())
      val payload = rpc.getJSONObject("payload")
      assertEquals(setOf("args"), payload.keys().asSequence().toSet())
      val args = payload.getJSONObject("args")
      OfficialDshContract.assertArgs("session/search", args)
      assertEquals(setOf("request"), args.keys().asSequence().toSet())
      assertEquals(setOf("query"), args.getJSONObject("request").keys().asSequence().toSet())
      assertEquals(found.query, args.getJSONObject("request").getString("query"))
      assertEquals(2, server.requestCount)
    }
  }

  @Test fun invalidQueriesFailBeforeAuthenticationOrRpcAndUseUtf16Limit() = runBlocking {
    withApi { api, server ->
      for (query in listOf("", " \t\n", "before\u0000after")) failure(400) { api.searchSessionContent(query) }
      for (query in listOf("x".repeat(501), "😀".repeat(251))) failure(413) { api.searchSessionContent(query) }
      assertEquals(0, server.requestCount)
      server.enqueue(rootOk()); server.enqueue(envelope(result()))
      val boundary = "😀".repeat(250)
      assertEquals(boundary, api.searchSessionContent(" $boundary ").query)
      request(server)
      val args = JSONObject(request(server).body.readUtf8()).getJSONObject("payload").getJSONObject("args")
      assertEquals(500, args.getJSONObject("request").getString("query").length)
      assertEquals(2, server.requestCount)
    }
  }

  @Test fun authorizedEmptyWithHasMoreTrueIsNotRewrittenOrFollowed() = runBlocking {
    withApi { api, server ->
      val value = result(hasMore = true)
      OfficialDshContract.assertResult("session/search", value)
      server.enqueue(rootOk()); server.enqueue(envelope(value))
      assertEquals(SessionContentSearchResult("needle", emptyList(), true), api.searchSessionContent("needle"))
      assertEquals(2, server.requestCount)
    }
  }

  @Test fun missingOrWrongRootItemsHasMoreAndRowsFailClosed() = runBlocking {
    withApi { api, server ->
      server.enqueue(rootOk())
      val malformed = listOf(
        JSONObject.NULL, JSONArray(), "not an object", JSONObject(),
        JSONObject().put("items", JSONArray()),
        JSONObject().put("hasMore", false),
        JSONObject().put("items", JSONObject()).put("hasMore", false),
        JSONObject().put("items", JSONArray()).put("hasMore", "false"),
        JSONObject().put("items", JSONArray()).put("hasMore", JSONObject.NULL),
        result(JSONArray().put("not a row")),
        result(JSONArray().put(JSONObject().put("sessionId", "s"))),
        result(JSONArray().put(hit(snippet = 42)))
      )
      for (value in malformed) {
        server.enqueue(envelope(value))
        failure(502) { api.searchSessionContent("needle") }
      }
      assertEquals(1 + malformed.size, server.requestCount)
    }
  }

  @Test fun idsAreOpaqueBoundedStringsAndDuplicateRowsAreRejectedNotTruncated() = runBlocking {
    withApi { api, server ->
      server.enqueue(rootOk())
      val malformed = listOf(
        result(JSONArray().put(hit(id = ""))),
        result(JSONArray().put(hit(id = 1))),
        result(JSONArray().put(hit(id = "s\n1"))),
        result(JSONArray().put(hit(id = "s\u007f1"))),
        result(JSONArray().put(hit(id = "s".repeat(513)))),
        result(JSONArray().put(hit()).put(hit(snippet = "another match")))
      )
      for (value in malformed) {
        server.enqueue(envelope(value)); failure(502) { api.searchSessionContent("needle") }
      }
      val id = "?".repeat(512)
      server.enqueue(envelope(result(JSONArray().put(hit(id)))))
      assertEquals(id, api.searchSessionContent("needle").items.single().sessionId)
    }
  }

  @Test fun itemLimitAcceptsTwentyAndRejectsTwentyOneWithoutTruncation() = runBlocking {
    withApi { api, server ->
      server.enqueue(rootOk())
      fun hits(count: Int) = JSONArray().also { rows -> repeat(count) { rows.put(hit("s$it")) } }
      server.enqueue(envelope(result(hits(20))))
      assertEquals(20, api.searchSessionContent("needle").items.size)
      server.enqueue(envelope(result(hits(21))))
      failure(413) { api.searchSessionContent("needle") }
    }
  }

  @Test fun snippetsCountUnicodeCodePointsNotUtf16AndRejectInvalidUnicode() = runBlocking {
    withApi { api, server ->
      server.enqueue(rootOk())
      val snippet = "😀".repeat(240)
      server.enqueue(envelope(result(JSONArray().put(hit(snippet = snippet)))))
      assertEquals(snippet, api.searchSessionContent("needle").items.single().snippet)
      for (oversized in listOf("x".repeat(241), "😀".repeat(241))) {
        server.enqueue(envelope(result(JSONArray().put(hit(snippet = oversized)))))
        failure(413) { api.searchSessionContent("needle") }
      }
      for (invalid in listOf("\uD800", "\uDC00", "\uD800x")) {
        // Preserve the escaped unpaired surrogate instead of an HTTP UTF-8 encoder replacing it.
        val escaped = invalid.map { "\\u%04x".format(it.code) }.joinToString("")
        server.enqueue(MockResponse().setBody("""{"type":"server-response","result":{"ok":true,"value":{"items":[{"sessionId":"s","snippet":"$escaped"}],"hasMore":false}}}"""))
        failure(502) { api.searchSessionContent("needle") }
      }
    }
  }

  @Test fun httpErrorsPropagateAndCancellationDoesNotBecomeEmptyOrTriggerFallback() = runBlocking {
    withApi { api, server ->
      server.enqueue(rootOk()); server.enqueue(MockResponse().setResponseCode(500))
      failure(500) { api.searchSessionContent("needle") }
      request(server); request(server)
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
      val pending = async { api.searchSessionContent("cancel me") }
      // The request needs this runBlocking event loop to start before the bounded blocking read.
      kotlinx.coroutines.yield()
      val sent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { request(server) }
      assertEquals("/api/session/search", sent.path)
      pending.cancelAndJoin()
      assertTrue(pending.isCancelled)
      assertEquals(3, server.requestCount)
    }
  }

  @Test fun clickedHitAlwaysReadsFreshAuthorizedRowsAndNeverFallsBackToKnown() = runBlocking {
    withApi { api, server ->
      server.enqueue(rootOk()); server.enqueue(envelope(listing(row(title = "Cached title"))))
      assertEquals("Cached title", api.session("session-1").title)
      server.enqueue(envelope(listing()))
      assertNull(api.searchResultSession("session-1"))
      server.enqueue(envelope(listing(row(id = "other"))))
      assertNull(api.searchResultSession("session-1"))
      for (invalidDirectory in listOf("", "  ", 42, JSONObject.NULL)) {
        server.enqueue(envelope(listing(row(directory = invalidDirectory))))
        assertNull(api.searchResultSession("session-1"))
      }
      server.enqueue(envelope(listing(row(directory = "/fresh", title = "Current title"))))
      val fresh = requireNotNull(api.searchResultSession("session-1"))
      assertEquals("session-1", fresh.id)
      assertEquals("/fresh", fresh.directory)
      assertEquals("Current title", fresh.title)
      request(server)
      repeat(8) { assertEquals("/api/session/list", request(server).path) }
      assertEquals(9, server.requestCount)
    }
  }

  @Test fun capabilityIsDshProtocolSpecificAndOpenCodeDefaultDoesNotInventAnEndpoint() = runBlocking {
    withApi { api, server ->
      assertTrue(api.capabilities().sessionContentSearch)
      assertTrue(DshApi.TUNNEL_CAPABILITIES.sessionContentSearch)
      assertTrue(api.capabilities().copy(diff = false, terminal = false).sessionContentSearch)
      assertFalse(ApiCapabilities().sessionContentSearch)
      assertFalse(ApiCapabilities.BASELINE.sessionContentSearch)
      assertFalse(ApiCapabilities.fromDocument(JSONObject().put("paths", JSONObject())).sessionContentSearch)
      val profile = ServerProfile("oc", "OpenCode", server.url("/").toString(), allowCleartext = true, kind = ServerKind.OPENCODE)
      val openCode = OpenCodeApi(profile, ServerCredentials())
      try {
        assertFalse(openCode.capabilities().sessionContentSearch)
        assertNull(openCode.searchSessionContent("needle"))
        assertEquals(0, server.requestCount)
      } finally { openCode.close() }
    }
  }
}
