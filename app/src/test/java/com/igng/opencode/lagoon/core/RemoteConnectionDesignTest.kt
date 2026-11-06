package com.igng.opencode.lagoon.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Connection state, single-follow generation, resilient event flow and the doctor report. */
class RemoteConnectionDesignTest {
  private class Store(private val record: RemoteDeviceRecord) : RemoteDeviceStore {
    private var current = record
    override fun load(slot: String): RemoteDeviceRecord? = current
    override fun save(slot: String, record: RemoteDeviceRecord) { current = record }
    override fun delete(slot: String) { }
  }

  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope): String = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = key
  }

  private fun token(): String = UUID.randomUUID().toString() + UUID.randomUUID().toString()

  private fun backend(host: FakeRemoteHost): Pair<RemoteSession, RemoteBackend> {
    val identity = RemoteIdentity.generate()
    val origin = canonicalRemoteOrigin(host.url)
    val record = RemoteDeviceRecord(origin, host.accountEmail(), identity,
      RemoteTokenPair(token(), Long.MAX_VALUE / 2, token(), Long.MAX_VALUE / 2))
    val manager = RemoteDeviceManager(Store(record), "slot-${UUID.randomUUID()}", host.url, host.accountEmail())
    val trust = RemotePeerTrust(Pins(host.hostIdentity.publicKey))
    val session = RemoteSession(host.url, true, host.hostId, manager, trust, host.accountEmail(), "4.2.0-test")
    return session to RemoteBackend(session)
  }

  private fun snapshot(id: String): JSONObject = JSONObject().put("type", "snapshot")
    .put("header", JSONObject().put("version", 4).put("id", id).put("createdAt", 1).put("cwd", "/repo").put("agentPreset", "standard"))
    .put("cursor", 3).put("hasMore", false)
    .put("records", JSONArray().put(JSONObject().put("type", "event").put("event", JSONObject().put("type", "user/message")
      .put("seq", 1).put("time", 100).put("data", JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "hello")))
        .put("role", "user").put("id", "u1")))))
    .put("projections", JSONObject().put("asOfSeq", 3).put("values", JSONObject()))

  private fun row(id: String) = JSONObject().put("sessionId", id).put("updatedAt", 100)
    .put("running", true).put("cwd", "/repo")

  @Test fun statusReachesReadyWithGenerationAndCapabilities() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        if (endpoint == "session/list") host.ok(JSONObject().put("items", JSONArray().put(row(id))))
        else host.ok(JSONObject())
      }
      host.start()
      val session = RemoteSession(host.url, true, host.hostId, manager(host), RemotePeerTrust(Pins(host.hostIdentity.publicKey)),
        host.accountEmail(), "4.2.0-test")
      val backend = RemoteBackend(session)
      try {
        backend.client.health()
        val status = session.status.value
        assertEquals(RemotePhase.READY, status.phase)
        assertEquals(1, status.generation)
        assertTrue(status.readyAt > 0)
        assertTrue(status.capabilities.contains("harness.remote.v3"))
        assertEquals("relay", status.transport)
      } finally { backend.close(); session.close() }
    }
  }

  @Test fun oneFollowServesRepeatedSnapshots() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(row(id))))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, _ -> if (endpoint == "session/follow") listOf(snapshot(id)) else emptyList() }
      host.start()
      val session = RemoteSession(host.url, true, host.hostId, manager(host), RemotePeerTrust(Pins(host.hostIdentity.publicKey)),
        host.accountEmail(), "4.2.0-test")
      val backend = RemoteBackend(session)
      try {
        backend.client.messagesPage(id)
        backend.client.messagesPage(id)
        val opens = host.streamOpens.count { it.optString("endpoint") == "session/follow" }
        assertEquals("同一会话只应打开一条 follow", 1, opens)
      } finally { backend.close(); session.close() }
    }
  }

  /** `$events` 失败只降级状态实时性，不能把整条事件流关掉（否则内容和状态会一起消失）。 */
  @Test fun eventFlowSurvivesGlobalStreamFailure() = runBlocking {
    val id = "session-${UUID.randomUUID()}"
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        if (endpoint == "session/list") host.ok(JSONObject().put("items", JSONArray().put(row(id))))
        else host.ok(JSONObject())
      }
      host.streamOpenError = { endpoint, _ -> if (endpoint == "\$events") "gateway/lookup-unavailable" else null }
      host.start()
      val (session, backend) = backend(host)
      try {
        backend.client.health()
        val job = launch { backend.client.events {}.collect { } }
        delay(1_500)
        assertTrue("events() 不应因 \$events 失败而结束", job.isActive)
        job.cancelAndJoin()
      } finally { backend.close(); session.close() }
    }
  }

  @Test fun credentialSummaryRecoversStaleRefresh() {
    val now = System.currentTimeMillis()
    val record = RemoteDeviceRecord("https://hub.example/", "a@b.c", RemoteIdentity.generate(),
      RemoteTokenPair(token(), now + 3_600_000, token(), now + 86_400_000),
      RemoteDeviceRecord.Transition.REFRESHING, now - 120_000)
    val manager = RemoteDeviceManager(Store(record), "cred-test", "https://hub.example/", "a@b.c")
    assertEquals("刷新待重试", manager.status(now).state)
  }

  @Test fun doctorTextCarriesCountsButNoSecrets() {
    val now = System.currentTimeMillis()
    val doctor = RemoteDoctor(
      hub = "https://hub.example/", account = "a@b.c", hostName = "ubuntu-dev", hostId = "host-1234",
      appVersion = "4.2.0-test",
      status = RemoteConnectionStatus(phase = RemotePhase.READY, generation = 2, readyAt = now,
        hostName = "ubuntu-dev", serverVersion = "self-hosted/0.1.0", pluginVersion = "0.6.0",
        harnessVersion = "0.2.1-rc.5", transport = "relay", capabilities = setOf("harness.remote.v3")),
      credentials = RemoteCredentialSummary("正常", now + 3_600_000, now + 86_400_000),
      snapshots = listOf(RemoteSnapshotStats("session-1", 5, 2, 9, false)),
      follows = listOf(RemoteFollowStats("session-1", true, 9, now, 0)),
      events = RemoteEventStats(true, "client-1", 1, now),
      streams = RemoteStreamStats(14, 32, mapOf("session/follow" to 12, "\$events" to 1, "workspace/follow" to 1)),
      capturedAt = now
    )
    val text = doctor.asText()
    assertTrue(text.contains("records=5"))
    assertTrue(text.contains("self-hosted/0.1.0"))
    assertTrue("流预算应展示占用与上限", text.contains("14/32"))
    assertTrue("流预算应展示 endpoint 占用", text.contains("session/follow 12"))
    assertFalse(text.lowercase().contains("token"))
    assertTrue(doctor.diagnosticLines().any { it.first == "路径" })
  }

  private fun manager(host: FakeRemoteHost): RemoteDeviceManager {
    val identity = RemoteIdentity.generate()
    val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), host.accountEmail(), identity,
      RemoteTokenPair(token(), Long.MAX_VALUE / 2, token(), Long.MAX_VALUE / 2))
    return RemoteDeviceManager(Store(record), "slot-${UUID.randomUUID()}", host.url, host.accountEmail())
  }
}
