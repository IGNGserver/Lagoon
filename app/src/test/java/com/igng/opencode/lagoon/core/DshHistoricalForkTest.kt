package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshHistoricalForkTest {
  private fun root() = MockResponse().setResponseCode(303).addHeader("Set-Cookie", "dsh-auth-test=value; Path=/").addHeader("Location", "./")
  private fun response(id: Any) = MockResponse().setBody(JSONObject().put("type", "server-response")
    .put("result", JSONObject().put("ok", true).put("value", JSONObject().put("sessionId", id))).toString())
  private fun client(server: MockWebServer) = DshApi(ServerProfile("host", "Host", server.url("/").toString(),
    allowCleartext = true, kind = ServerKind.DSH), ServerCredentials("dsh", "token"))
  private val session = Session("source", "/repo", "Source", 1, parentId = "parent", archived = true)

  @Test fun exactInclusiveNativeSequenceAndDefaultPrefixHaveDistinctOfficialRequests() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(root()); server.enqueue(response("child-a")); server.enqueue(response("child-b"))
      val api = client(server)
      try {
        val exact = api.forkSession(session, ForkBoundary.DshEvent(9_007_199_254_740_991L))
        val latest = api.forkSession(session)
        assertEquals("child-a", exact.id); assertEquals("child-b", latest.id)
        assertNull(exact.parentId); assertFalse(exact.archived)
        server.takeRequest()
        val a = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("payload").getJSONObject("args")
        val b = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("payload").getJSONObject("args")
        OfficialDshContract.assertArgs("session/fork", a); OfficialDshContract.assertArgs("session/fork", b)
        assertEquals(9_007_199_254_740_991L, a.getJSONObject("request").getLong("atSeq"))
        assertFalse(b.getJSONObject("request").has("atSeq")); assertEquals(3, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun wrongOrUnsafeBoundaryNeverAuthenticatesOrSendsMutation() = runBlocking {
    MockWebServer().use { server ->
      val api = client(server)
      try {
        for (boundary in listOf(ForkBoundary.OpenCodeBefore("message"), ForkBoundary.DshEvent(0), ForkBoundary.DshEvent(-1), ForkBoundary.DshEvent(9_007_199_254_740_992L))) {
          assertTrue(runCatching { api.forkSession(session, boundary) }.isFailure)
        }
        assertEquals(0, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun authenticationStatusConnectionLossAndBadAcknowledgementNeverReplay() = runBlocking {
    for (failure in listOf(MockResponse().setResponseCode(401), MockResponse().setResponseCode(503).addHeader("Retry-After", "0"),
      MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST), response("source"), response(7))) {
      MockWebServer().use { server ->
        server.enqueue(root()); server.enqueue(failure)
        val api = client(server)
        try { assertTrue(runCatching { api.forkSession(session, ForkBoundary.DshEvent(4)) }.isFailure); assertEquals(2, server.requestCount) }
        finally { api.close() }
      }
    }
  }

  @Test fun onlyExactNumericDurableSequencesCanBecomeHistoricalPositions() = runBlocking {
    val api = DshApi(ServerProfile("host", "Host", "https://fixture.invalid", kind = ServerKind.DSH), "")
    try {
      for (raw in listOf<Any>("7", 7.5, -1, 0, 9_007_199_254_740_992L)) {
        val event = JSONObject().put("type", "user/message").put("seq", raw).put("time", 1)
          .put("data", JSONObject("""{"id":"native-input","source":{"kind":"user"},"content":[{"type":"text","text":"hi"}]}"""))
        val cold = api.recordsToMessages(session, listOf(JSONObject().put("type", "event").put("event", event))).single()
        assertFalse(cold.sequence in 1L..9_007_199_254_740_991L)
      }
      assertEquals(7L, exactDshSequence(7.0)); assertNull(exactDshSequence("7"))
    } finally { api.close() }
  }
}
