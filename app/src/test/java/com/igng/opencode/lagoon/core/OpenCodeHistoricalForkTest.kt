package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Real OpenCode discovery, fork requests and the native carrier's single-send guarantee. */
class OpenCodeHistoricalForkTest {
  private val source = Session("ses_source", "/repo", "Source", 0)

  private fun api(server: MockWebServer, prefix: String = "/") = OpenCodeApi(
    ServerProfile("local", "Local", server.url(prefix).toString().trimEnd('/'), "opencode", allowCleartext = true),
    ServerCredentials(password = "secret"), callTimeoutSeconds = 3
  )

  private fun forkDocument() = """{"paths":{"/api/session/{sessionID}/fork":{"post":{
    "requestBody":{"required":true,"content":{"application/json":{"schema":{
      "type":"object","properties":{"before":{"anyOf":[{"type":"null"},{"${'$'}ref":"#/components/schemas/MessageID"}]}},
      "additionalProperties":false}}}}}}},"components":{"schemas":{"MessageID":{"type":"string"}}}}"""

  private fun ack(id: String = "ses_fork") = JSONObject().put("data", JSONObject()
    .put("id", id).put("location", JSONObject().put("directory", "/repo"))
    .put("title", "Fork").put("time", JSONObject().put("updated", 20))).toString()

  @Test fun instanceDiscoverySelectsExactBeforeBodyAndKeepsEncodedSourceRoute() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(forkDocument()))
      server.enqueue(MockResponse().setBody(ack()))
      val client = api(server, "/native/")
      try {
        assertFalse(client.capabilities().historicalFork)
        assertTrue(client.discoverCapabilities().historicalFork)
        assertEquals("/native/openapi.json", server.takeRequest().path)
        // IDs are opaque. Do not impose a guessed msg_ prefix or trim the selected boundary.
        val before = "opaque /?# +边界"
        val fork = client.forkSession(source.copy(id = "ses/source ?#"), ForkBoundary.OpenCodeBefore(before))
        assertEquals("ses_fork", fork.id)
        assertEquals("/repo", fork.directory)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/native/api/session/ses%2Fsource%20%3F%23/fork", request.requestUrl?.encodedPath)
        assertNull(request.requestUrl?.query)
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(setOf("before"), body.keys().asSequence().toSet())
        assertEquals(before, body.getString("before"))
        assertEquals(2, server.requestCount)
      } finally { client.close() }
    }
  }

  @Test fun wrongBoundaryInvalidOpaqueIdAndUnavailableCapabilityNeverSendAnRpc() = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        val wrong = runCatching { client.forkSession(source, ForkBoundary.DshEvent(3)) }.exceptionOrNull()
        assertEquals(400, (wrong as? ApiException)?.status)
        for (id in listOf("", "   ", "x".repeat(513), "msg_\n", "msg_\u007f", "msg_\u0085")) {
          val error = runCatching { client.forkSession(source, ForkBoundary.OpenCodeBefore(id)) }.exceptionOrNull()
          assertEquals(id, 400, (error as? ApiException)?.status)
        }
        val unsupported = runCatching { client.forkSession(source, ForkBoundary.OpenCodeBefore("opaque")) }.exceptionOrNull()
        assertEquals(501, (unsupported as? ApiException)?.status)
        assertEquals(0, server.requestCount)
      } finally { client.close() }
    }
  }

  @Test fun legacyMissingAndIncompatibleOpenApiNeverEnableHistoricalFork() = runBlocking {
    val incompatible = forkDocument().replace("\"type\":\"string\"", "\"type\":\"integer\"")
    val requiredBefore = forkDocument().replace("\"additionalProperties\":false", "\"additionalProperties\":false,\"required\":[\"before\"]")
    for (response in listOf(
      MockResponse().setResponseCode(404),
      MockResponse().setBody("{}"),
      MockResponse().setBody("""{"paths":{"/api/session/{id}/fork":{"post":{}}}}"""),
      MockResponse().setBody(incompatible),
      MockResponse().setBody(requiredBefore)
    )) MockWebServer().use { server ->
      server.enqueue(response)
      val client = api(server)
      try {
        assertFalse(client.discoverCapabilities().historicalFork)
        val error = runCatching { client.forkSession(source, ForkBoundary.OpenCodeBefore("msg_selected")) }.exceptionOrNull()
        assertEquals(501, (error as? ApiException)?.status)
        assertEquals(1, server.requestCount)
      } finally { client.close() }
    }
  }

  @Test fun basicForkRequiresCapabilityButTheUndiscoveredBaselineStillUsesEmptyObject() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(ack()))
      val client = api(server)
      try {
        assertEquals("ses_fork", client.forkSession(source).id)
        val request = server.takeRequest()
        assertEquals("/api/session/ses_source/fork", request.path)
        assertEquals(0, JSONObject(request.body.readUtf8()).length())
      } finally { client.close() }
    }
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"paths":{}}"""))
      val client = api(server)
      try {
        assertFalse(client.discoverCapabilities().fork)
        assertEquals(501, (runCatching { client.forkSession(source) }.exceptionOrNull() as? ApiException)?.status)
        assertEquals(1, server.requestCount)
      } finally { client.close() }
    }
  }

  @Test fun bothForkKindsRejectMissingBlankNumericAndSourceSessionAcknowledgements() = runBlocking {
    for (historical in listOf(false, true)) for (body in listOf(
      """{"data":{}}""", """{"data":{"id":" "}}""", """{"data":{"id":42}}""", ack(source.id)
    )) MockWebServer().use { server ->
      if (historical) server.enqueue(MockResponse().setBody(forkDocument()))
      server.enqueue(MockResponse().setBody(body))
      val client = api(server)
      try {
        if (historical) client.discoverCapabilities()
        val error = runCatching {
          if (historical) client.forkSession(source, ForkBoundary.OpenCodeBefore("msg_selected")) else client.forkSession(source)
        }.exceptionOrNull()
        assertEquals(body, 502, (error as? ApiException)?.status)
        assertEquals(if (historical) 2 else 1, server.requestCount)
      } finally { client.close() }
    }
  }

  @Test fun basicAndHistoricalForkNeverReplayAfterDisconnectOrHttpFollowupStatus() = runBlocking {
    for (historical in listOf(false, true)) for (status in listOf(0, 401, 403, 408, 503)) {
      MockWebServer().use { server ->
        if (historical) server.enqueue(MockResponse().setBody(forkDocument()))
        server.enqueue(if (status == 0) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
          else MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
        // A replay would return success and turn an unknown/rejected operation into a second fork.
        server.enqueue(MockResponse().setBody(ack("ses_duplicate")))
        val client = api(server)
        try {
          if (historical) { client.discoverCapabilities(); server.takeRequest() }
          val error = runCatching {
            if (historical) client.forkSession(source, ForkBoundary.OpenCodeBefore("msg_selected")) else client.forkSession(source)
          }.exceptionOrNull()
          assertTrue("historical=$historical status=$status: $error", error is IOException)
          if (status != 0) assertEquals(status, (error as? ApiException)?.status)
          assertEquals("POST", server.takeRequest().method)
          assertEquals(if (historical) 2 else 1, server.requestCount)
          assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { client.close() }
      }
    }
  }

  @Test fun emptyDeleteAndAllOtherNativeWritesAlsoRefuse503Replay() = runBlocking {
    for (method in listOf("POST", "PUT", "PATCH", "DELETE")) MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
      server.enqueue(MockResponse().setResponseCode(204))
      val transport = NativeOkHttpTransport(NativeHttpEndpoint(server.url("/native/").toString()), callTimeoutSeconds = 3)
      try {
        val response = transport.request(method, "api/resource?opaque=value", mapOf("Authorization" to "Bearer secret"),
          body = if (method == "DELETE") null else "{}".toByteArray(), maxResponseBytes = 1024)
        assertEquals(503, response.statusCode)
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals("/native/api/resource?opaque=value", request.path)
        assertEquals(if (method == "DELETE") "" else "{}", request.body.readUtf8())
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
      } finally { transport.close() }
    }
  }
}
