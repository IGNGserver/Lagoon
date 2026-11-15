package com.igng.opencode.lagoon.core

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class DshRemoteCompactionRoutingTest {
  private class DeviceStore(private var record: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String) = record
    override fun save(slot: String, record: RemoteDeviceRecord) { this.record = record }
    override fun delete(slot: String) = Unit
  }
  private class TrustStore(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }

  @Test fun slashCompactUsesAsyncAndAbortWaitsForConfirmedCancellation() = runBlocking {
    compactAbort(confirmed = true)
  }

  @Test fun unknownCompactionCancellationDoesNotFallBackToSessionCancel() = runBlocking {
    compactAbort(confirmed = false)
  }

  private suspend fun compactAbort(confirmed: Boolean) {
    val rpc = CopyOnWriteArrayList<String>()
    val gateway = CopyOnWriteArrayList<String>()
    FakeRemoteHost(capabilities = listOf("harness.remote.v3", "remote.compaction.async.v1")).use { host ->
      host.topLevelRpc = { method, _, _ ->
        rpc += method
        when (method) {
          "harness.remote.compaction.submit" -> JSONObject().put("operationId", "op-1").put("state", "accepted")
          "harness.remote.compaction.status" -> JSONObject().put("operationId", "op-1").put("sessionId", "s-compact").put("state", "running")
          "harness.remote.compaction.cancel" -> JSONObject().put("operationId", "op-1").put("sessionId", "s-compact")
            .put("state", if (confirmed) "cancelled" else "unconfirmed")
          else -> JSONObject()
        }
      }
      host.call = { endpoint, _ -> gateway += endpoint; host.ok(JSONObject().put("result", JSONObject().put("kind", "success"))) }
      host.start()
      val identity = RemoteIdentity.generate()
      val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
      val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), host.accountEmail(), identity,
        RemoteTokenPair(token, Long.MAX_VALUE / 2, token, Long.MAX_VALUE / 2))
      val manager = RemoteDeviceManager(DeviceStore(record), "compact-${UUID.randomUUID()}", host.url, host.accountEmail())
      val store = MemoryRemoteCompactionStore()
      val session = RemoteSession(host.url, true, host.hostId, manager, RemotePeerTrust(TrustStore(host.hostIdentity.publicKey)),
        host.accountEmail(), compactionStore = store)
      val backend = RemoteBackend(session)
      val client: HarnessClient = backend.client
      val target = Session("s-compact", "/repo", "Compaction", 0)
      val scope = JSONArray(listOf(canonicalRemoteOrigin(host.url), host.accountEmail(), host.hostId)).toString()
      val task = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
        runCatching { client.command(target, "compact", "") }
      }
      try {
        withTimeout(8_000) { while (store.load(scope, target.id)?.operationId != "op-1") delay(20) }
        val error = runCatching { client.abort(target) }.exceptionOrNull()
        if (confirmed) assertNull(error) else {
          assertTrue(error is RemoteFailureException)
          assertTrue(error!!.message.orEmpty().contains("确认"))
        }
        assertTrue(rpc.contains("harness.remote.compaction.submit"))
        assertTrue(rpc.contains("harness.remote.compaction.cancel"))
        assertFalse("unknown/successful compaction cancellation must not send session/cancel", gateway.contains("session/cancel"))
        assertFalse("the slash command must not run a second synchronous /compact", gateway.contains("commands/execute"))
      } finally { task.cancelAndJoin(); backend.close(); session.close() }
    }
  }

  @Test fun abortWithoutTrackedCompactionUsesSessionCancel() = runBlocking {
    val endpoints = CopyOnWriteArrayList<String>()
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ -> endpoints += endpoint; host.ok(JSONObject()) }
      host.start()
      val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
      val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), host.accountEmail(), RemoteIdentity.generate(),
        RemoteTokenPair(token, Long.MAX_VALUE / 2, token, Long.MAX_VALUE / 2))
      val manager = RemoteDeviceManager(DeviceStore(record), "abort-${UUID.randomUUID()}", host.url, host.accountEmail())
      val session = RemoteSession(host.url, true, host.hostId, manager,
        RemotePeerTrust(TrustStore(host.hostIdentity.publicKey)), host.accountEmail())
      val backend = RemoteBackend(session)
      try {
        backend.client.abort(Session("s-normal", "/repo", "Normal", 0))
        assertEquals(listOf("session/cancel"), endpoints.toList())
      } finally { backend.close(); session.close() }
    }
  }
}
