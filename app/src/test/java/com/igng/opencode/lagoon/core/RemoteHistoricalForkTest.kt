package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Noise + generated native contract; controlled Host fork domain, no Agent or filesystem work. */
class RemoteHistoricalForkTest {
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
    val session = RemoteSession(host.url, true, host.hostId, RemoteDeviceManager(Store(record), "historical-fork", host.url, account),
      RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account)
    return session to RemoteBackend(session)
  }
  @Test fun encryptedForkCarriesExactInclusiveEventAndNeverGuessesMessageIdsOrUsesOtherMethods() = runBlocking {
    FakeRemoteHost().use { host ->
      var writes = 0
      host.call = { endpoint, payload ->
        assertEquals("session/fork", endpoint)
        val args = payload.getJSONObject("args"); OfficialDshContract.assertArgs(endpoint, args)
        val request = args.getJSONObject("request")
        assertEquals(setOf("sessionId", "atSeq"), request.keys().asSequence().toSet())
        assertEquals("source", request.getString("sessionId")); assertEquals(42, request.getInt("atSeq"))
        writes++; host.ok(JSONObject().put("sessionId", "child"))
      }
      host.start(); val (session, backend) = backend(host)
      try {
        assertTrue(backend.client.capabilities().historicalFork)
        val fork = backend.client.forkSession(Session("source", "/repo", "Source", 1), ForkBoundary.DshEvent(42))
        assertEquals("child", fork.id); assertEquals(1, writes)
        assertTrue(synchronized(host.streamOpens) { host.streamOpens.isEmpty() })
      } finally { backend.close(); session.close() }
    }
  }
  @Test fun rejectedOrOversizedMutationIsNotReissuedOnAnotherRemotePath() = runBlocking {
    FakeRemoteHost().use { host ->
      var calls = 0
      host.call = { endpoint, _ -> assertEquals("session/fork", endpoint); calls++; host.fail("RESPONSE_TOO_LARGE", "Committed but response lost") }
      host.start(); val (session, backend) = backend(host)
      try {
        assertTrue(runCatching { backend.client.forkSession(Session("source", "/repo", "Source", 1), ForkBoundary.DshEvent(4)) }.isFailure)
        assertEquals(1, calls)
        val records = synchronized(host.rpcCalls) { host.rpcCalls.toList() }
        assertEquals(1, records.count { it.optString("method") == "harness.remote.call" })
        assertTrue(records.none { it.optString("method").startsWith("harness.remote.request.") })
      } finally { backend.close(); session.close() }
    }
  }
}
