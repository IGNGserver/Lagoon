package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Noise/Relay stack with request validation sourced from the official generated DSH face. */
class RemotePublishedContractTest {
  private class Devices(private var record: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String): RemoteDeviceRecord = record
    override fun save(slot: String, record: RemoteDeviceRecord) { this.record = record }
    override fun delete(slot: String) = Unit
  }
  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope): String = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = key
  }

  private fun session(host: FakeRemoteHost): RemoteSession {
    val origin = canonicalRemoteOrigin(host.url)
    val record = RemoteDeviceRecord(origin, host.accountEmail(), RemoteIdentity.generate(),
      RemoteTokenPair(UUID.randomUUID().toString(), Long.MAX_VALUE / 2, UUID.randomUUID().toString(), Long.MAX_VALUE / 2))
    return RemoteSession(host.url, true, host.hostId,
      RemoteDeviceManager(Devices(record), "fixture-${UUID.randomUUID()}", origin, host.accountEmail()),
      RemotePeerTrust(Pins(host.hostIdentity.publicKey)), host.accountEmail())
  }

  @Test fun nativePromptAndQueueWritesUseOfficialArgsThroughEncryption() = runBlocking {
    FakeRemoteHost(strictOfficialContracts = true).use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().put(JSONObject()
            .put("sessionId", "s").put("updatedAt", 1).put("running", false).put("blank", false)
            .put("agentAvailable", true).put("cwd", "/repo")
            .put("projections", JSONObject().put("values", JSONObject().put("title", "Task"))))))
          "session/prompt", "session/updateQueue" -> host.ok(JSONObject().put("accepted", true))
          else -> host.ok(JSONObject())
        }
      }
      host.start()
      val remote = session(host)
      val backend = RemoteBackend(remote)
      try {
        val client = backend.client
        val target = client.rootSessionsPage().items.single()
        client.send(target, "Hello", id = "request-1", delivery = Delivery.QUEUE)
        assertTrue(client.updateQueue(target.id, "queued-1", QueueAction.Steer))
        assertTrue(host.contractViolations.toString(), host.contractViolations.isEmpty())
        assertEquals(1, host.rpcCalls.count { it.str("method") == "harness.remote.call" &&
          it.obj("params").str("endpoint") == "session/prompt" })
      } finally { backend.close(); remote.close() }
    }
  }

  @Test fun wrongMethodShapeFailsInsteadOfPassingTheFakeHost() = runBlocking {
    FakeRemoteHost(strictOfficialContracts = true).use { host ->
      host.start()
      val remote = session(host)
      try {
        val failure = runCatching { remote.gateway().call("job/kill", JSONObject().put("args", JSONObject().put("jobId", "j"))) }.exceptionOrNull()
        assertTrue(failure is RemoteGatewayException)
        assertEquals("gateway/arguments-invalid", (failure as RemoteGatewayException).code)
        assertEquals(1, host.contractViolations.size)
      } finally { remote.close() }
    }
  }
}
