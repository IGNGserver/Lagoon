package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.RemoteRpc
import com.igng.opencode.lagoon.core.RemoteRpcException
import com.igng.opencode.lagoon.core.RemoteSecureChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteRpcTest {
  private class FakeChannel : RemoteSecureChannel {
    val sent = Channel<JSONObject>(Channel.UNLIMITED)
    var closed = false
    override suspend fun send(message: ByteArray) { sent.send(JSONObject(String(message, Charsets.UTF_8))) }
    override fun close() { closed = true }
  }
  private fun response(id: String, value: Any) = JSONObject().put("v", 1).put("id", "response")
    .put("type", "rpc.response").put("timestamp", 1000L).put("payload", JSONObject().put("requestId", id).put("result", value))

  @Test fun concurrentResponsesAreMatchedByRequestId() = runBlocking {
    val transport = FakeChannel()
    val rpc = RemoteRpc(transport) { 1000L }
    val a = async { rpc.call("harness.remote.call", JSONObject().put("number", 1)) }
    val b = async { rpc.call("harness.remote.call", JSONObject().put("number", 2)) }
    val first = transport.sent.receive()
    val second = transport.sent.receive()
    assertEquals("rpc.request", first.getString("type"))
    rpc.receive(response(second.getString("id"), "second"))
    rpc.receive(response(first.getString("id"), "first"))
    assertEquals("first", a.await())
    assertEquals("second", b.await())
    rpc.close()
    assertTrue(transport.closed)
  }

  @Test fun timeoutDoesNotRetryMutationAndLateResponseIsIgnored() = runBlocking {
    val transport = FakeChannel()
    val rpc = RemoteRpc(transport)
    val result = async { try { rpc.call("harness.remote.call", JSONObject(), 25); "unexpected" } catch (error: RemoteRpcException) { error.code } }
    val sent = transport.sent.receive()
    assertEquals("RPC_TIMEOUT", result.await())
    rpc.receive(response(sent.getString("id"), "late"))
    assertTrue(transport.sent.tryReceive().isFailure)
    rpc.close()
  }

  @Test fun closingRejectsPendingAndPreventsNewCalls() = runBlocking {
    val transport = FakeChannel()
    val rpc = RemoteRpc(transport)
    val result = async { try { rpc.call("harness.transport.describe", JSONObject()); "unexpected" } catch (error: RemoteRpcException) { error.code } }
    transport.sent.receive()
    rpc.close()
    assertEquals("TRANSPORT_CLOSED", result.await())
    try { rpc.call("harness.transport.describe", JSONObject()); fail("expected failure") } catch (error: RemoteRpcException) {
      assertEquals("TRANSPORT_CLOSED", error.code)
    }
  }
}
