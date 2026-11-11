package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Noise and generated native contract; controlled cold search domain, never a model prompt. */
class RemoteSessionContentSearchTest {
  private class Store(private var value: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String) = value
    override fun save(slot: String, record: RemoteDeviceRecord) { value = record }
    override fun delete(slot: String) = Unit
  }
  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }
  private fun backend(host: FakeRemoteHost): Pair<RemoteSession, RemoteBackend> {
    val account = host.accountEmail()
    val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), account, RemoteIdentity.generate(),
      RemoteTokenPair(UUID.randomUUID().toString(), Long.MAX_VALUE / 2, UUID.randomUUID().toString(), Long.MAX_VALUE / 2))
    val session = RemoteSession(host.url, true, host.hostId, RemoteDeviceManager(Store(record), "content-search", host.url, account),
      RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account)
    return session to RemoteBackend(session)
  }

  @Test fun encryptedContentSearchUsesLiteralHostRequestWithoutStartingOrFollowingAgents() = runBlocking {
    FakeRemoteHost().use { host ->
      var listed = true; var searches = 0
      host.call = { endpoint, payload ->
        val args = payload.getJSONObject("args")
        when (endpoint) {
          "session/search" -> {
            searches++; assertEquals(setOf("request"), args.keys().asSequence().toSet())
            val request = args.getJSONObject("request"); assertEquals(setOf("query"), request.keys().asSequence().toSet())
            assertEquals("needle [a.*] + text", request.getString("query"))
            host.ok(JSONObject().put("items", JSONArray().put(JSONObject().put("sessionId", "cold-hit").put("snippet", "native 界😀 snippet"))).put("hasMore", true))
          }
          "session/list" -> host.ok(JSONObject().put("items", if (listed) JSONArray().put(JSONObject()
            .put("sessionId", "cold-hit").put("cwd", "/not-loaded").put("updatedAt", 1).put("running", false)) else JSONArray()))
          else -> error("Unexpected search request $endpoint")
        }
      }
      host.start(); val (session, backend) = backend(host); val api = backend.client
      try {
        assertTrue(api.capabilities().sessionContentSearch)
        val result = api.searchSessionContent("  needle [a.*] + text  ")!!
        assertEquals("needle [a.*] + text", result.query); assertTrue(result.hasMore)
        assertEquals(SessionContentHit("cold-hit", "native 界😀 snippet"), result.items.single())
        assertEquals(1, searches)
        assertTrue(synchronized(host.streamOpens) { host.streamOpens.isEmpty() })
        assertEquals("/not-loaded", api.searchResultSession("cold-hit")!!.directory)
        listed = false; assertNull(api.searchResultSession("cold-hit")) // Never reuses the earlier known cache.
        val calls = synchronized(host.rpcCalls) { host.rpcCalls.toList() }
        assertTrue(calls.none { it.toString().contains("session/prompt") || it.toString().contains("selectModel") })
        assertTrue(synchronized(host.contractViolations) { host.contractViolations.isEmpty() })
      } finally { backend.close(); session.close() }
    }
  }

  @Test fun emptyAuthorizedPageCanStillBePartialAndBadPageIsNeverAuthoritativeEmpty() = runBlocking {
    FakeRemoteHost().use { host ->
      var good = true
      host.call = { endpoint, _ ->
        assertEquals("session/search", endpoint)
        host.ok(JSONObject().put("items", JSONArray()).put("hasMore", if (good) true else "true"))
      }
      host.start(); val (session, backend) = backend(host)
      try {
        val result = backend.client.searchSessionContent("needle")!!
        assertTrue(result.hasMore); assertTrue(result.items.isEmpty())
        good = false
        assertTrue(runCatching { backend.client.searchSessionContent("needle") }.isFailure)
      } finally { backend.close(); session.close() }
    }
  }
}
