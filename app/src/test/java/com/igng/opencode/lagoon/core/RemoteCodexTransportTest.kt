package com.igng.opencode.lagoon.core

import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCodexTransportTest {
  private data class Call(val method: String, val params: JSONObject)

  @Test fun approvalsAreScopedToTheirSubscribedStreamAndOnlyPermitSingleDecisions() = runBlocking {
    val calls = CopyOnWriteArrayList<Call>()
    val registry = RemoteCodexRegistry(true) { method, params ->
      calls += Call(method, JSONObject(params.toString()))
      when (method) {
        "codex.app.stream.open" -> JSONObject().put("opened", true).put("streamId", params.getString("streamId"))
          .put("threadId", params.getString("threadId"))
        "codex.app.respond" -> JSONObject().put("resolved", true)
        else -> JSONObject()
      }
    }
    val one = registry.acquire()
    val two = registry.acquire()
    try {
      one.subscribe("thread-1")
      two.subscribe("thread-2")
      val stream = calls.first { it.method == "codex.app.stream.open" }.params.getString("streamId")
      val params = JSONObject().put("threadId", "thread-1").put("requestHandle", "opaque-handle")
      registry.event(JSONObject().put("event", "codex.app.frame").put("data", JSONObject().put("streamId", stream)
        .put("frame", JSONObject().put("method", "item/commandExecution/requestApproval").put("params", params))))
      val event = withTimeout(1_000) { one.events().first() }
      assertEquals("opaque-handle", event.getString("id"))
      assertEquals("thread-1", event.getJSONObject("params").getString("threadId"))
      one.respond(event.get("id"), JSONObject().put("decision", "accept"))
      assertEquals("opaque-handle", calls.last().params.getString("requestHandle"))
      assertTrue(runCatching { one.respond("opaque-handle", JSONObject().put("decision", "acceptForSession")) }.isFailure)
      assertTrue(runCatching { one.call("process/spawn", JSONObject()) }.isFailure)
      assertEquals(1, calls.count { it.method == "codex.app.respond" })
      one.close()
      two.call("model/list", JSONObject())
      assertTrue(calls.any { it.method == "codex.app.call" && it.params.optString("method") == "model/list" })
    } finally { registry.close() }
  }

  @Test fun transferUsesOrderedChunksAndNeverRepeatsMutation() = runBlocking {
    val calls = mutableListOf<Call>()
    val received = java.io.ByteArrayOutputStream()
    val result = JSONObject().put("thread", JSONObject().put("id", "t")).toString().toByteArray()
    val registry = RemoteCodexRegistry(true) { method, params ->
      calls += Call(method, JSONObject(params.toString()))
      when (method) {
        "codex.app.transfer.chunk" -> { received.write(Base64.getDecoder().decode(params.getString("data"))); JSONObject() }
        "codex.app.transfer.commit" -> JSONObject().put("kind", "chunked").put("transferId", params.getString("transferId"))
          .put("totalBytes", result.size).put("totalChunks", 1)
        "codex.app.transfer.read" -> JSONObject().put("transferId", params.getString("transferId"))
          .put("index", params.getInt("index")).put("data", Base64.getEncoder().encodeToString(result))
        else -> JSONObject()
      }
    }
    val transport = registry.acquire()
    try {
      val input = org.json.JSONArray().put(JSONObject().put("type", "image").put("mediaType", "image/png")
        .put("data", "A".repeat(600_000)))
      assertEquals("t", transport.call("turn/start", JSONObject().put("threadId", "t").put("input", input))
        .getJSONObject("thread").getString("id"))
      assertEquals(listOf(0, 1), calls.filter { it.method == "codex.app.transfer.chunk" }.map { it.params.getInt("index") })
      val envelope = JSONObject(received.toString("UTF-8"))
      assertEquals("turn/start", envelope.getString("method"))
      assertEquals(1, calls.count { it.method == "codex.app.transfer.commit" })
      assertEquals(1, calls.count { it.method == "codex.app.transfer.close" })
      assertFalse(calls.any { it.method == "codex.app.call" })
    } finally { registry.close() }
  }

  @Test fun failedTransferDoesNotReplayAndClosesContainer() = runBlocking {
    val calls = mutableListOf<String>()
    val registry = RemoteCodexRegistry(true) { method, _ ->
      calls += method
      if (method == "codex.app.transfer.commit") throw IOException("delivery unknown")
      JSONObject()
    }
    val carrier = registry.acquire()
    try {
      assertTrue(runCatching { carrier.call("dsh/sessionHistory", JSONObject().put("threadId", "t")) }.isFailure)
      assertEquals(1, calls.count { it == "codex.app.transfer.commit" })
      assertEquals(1, calls.count { it == "codex.app.transfer.close" })
      assertFalse(calls.contains("codex.app.call"))
    } finally { registry.close() }
  }

  @Test fun eventsAreBroadcastToEveryObserver() = runBlocking {
    var stream = ""
    val registry = RemoteCodexRegistry(false) { method, params ->
      if (method == "codex.app.stream.open") {
        stream = params.getString("streamId")
        JSONObject().put("opened", true).put("streamId", stream).put("threadId", params.getString("threadId"))
      } else JSONObject()
    }
    val carrier = registry.acquire()
    try {
      val first = async(start = CoroutineStart.UNDISPATCHED) { carrier.events().first() }
      val second = async(start = CoroutineStart.UNDISPATCHED) { carrier.events().first() }
      carrier.subscribe("t")
      registry.event(JSONObject().put("event", "codex.app.frame").put("data", JSONObject().put("streamId", stream)
        .put("frame", JSONObject().put("method", "turn/started").put("params", JSONObject().put("threadId", "t")))))
      assertEquals("turn/started", withTimeout(1_000) { first.await() }.getString("method"))
      assertEquals("turn/started", withTimeout(1_000) { second.await() }.getString("method"))
    } finally { registry.close() }
  }

  @Test fun hostClosingStreamRetiresOnlyItsCodexCarrier() = runBlocking {
    val calls = CopyOnWriteArrayList<Call>()
    val registry = RemoteCodexRegistry(false) { method, params ->
      calls += Call(method, JSONObject(params.toString()))
      if (method == "codex.app.stream.open") JSONObject().put("opened", true)
        .put("streamId", params.getString("streamId")).put("threadId", params.getString("threadId"))
      else JSONObject()
    }
    val failed = registry.acquire()
    val sibling = registry.acquire()
    try {
      failed.subscribe("thread-1")
      val waiting = async(start = CoroutineStart.UNDISPATCHED) { failed.events().first() }
      val stream = calls.first { it.method == "codex.app.stream.open" }.params.getString("streamId")
      registry.event(JSONObject().put("event", "codex.app.stream.closed")
        .put("data", JSONObject().put("streamId", stream).put("reason", "failed")))
      assertEquals("lagoon/streamClosed", withTimeout(1_000) { waiting.await() }.getString("method"))
      assertTrue(failed.isClosed)
      assertFalse(sibling.isClosed)
      sibling.call("model/list", JSONObject())
      Unit
    } finally { registry.close() }
  }

  @Test fun lazyLeaseClosedDuringConnectionReleasesAcquiredChildOnce() = runBlocking {
    val loading = CompletableDeferred<Unit>()
    val completeLoad = CompletableDeferred<Unit>()
    var childClosed = 0
    var leaseReleased = 0
    val child = object : CodexTransport {
      override suspend fun call(method: String, params: JSONObject) = JSONObject()
      override suspend fun respond(requestId: Any, result: JSONObject) = Unit
      override suspend fun subscribe(threadId: String) = Unit
      override suspend fun unsubscribe(threadId: String) = Unit
      override fun events() = kotlinx.coroutines.flow.emptyFlow<JSONObject>()
      override fun close() { childClosed++ }
    }
    val lazy = LazyRemoteCodexTransport(load = { loading.complete(Unit); completeLoad.await(); child },
      releaseLease = { leaseReleased++ })
    val operation = async { runCatching { lazy.call("model/list", JSONObject()) } }
    loading.await()
    lazy.close()
    completeLoad.complete(Unit)
    assertTrue(operation.await().isFailure)
    lazy.close()
    assertEquals(1, childClosed)
    assertEquals(1, leaseReleased)
  }
}
