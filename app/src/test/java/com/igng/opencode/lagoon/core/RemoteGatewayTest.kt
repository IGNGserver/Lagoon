package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class RemoteGatewayTest {
  @Test fun callIsNotReplayedWhenResultIsUnknown() = runBlocking {
    var calls = 0
    val gateway = RemoteGateway { _, _ -> calls++; throw IOException("response lost") }
    try { gateway.call("session/prompt", JSONObject()); fail() } catch (_: IOException) { }
    assertEquals(1, calls)
  }

  @Test fun callPreservesRemoteErrorCode() = runBlocking {
    val gateway = RemoteGateway { _, _ -> JSONObject().put("ok", false).put("error", JSONObject().put("code", "gateway/denied").put("message", "denied")) }
    try { gateway.call("session/prompt", JSONObject()); fail() } catch (error: RemoteGatewayException) { assertEquals("gateway/denied", error.code) }
  }

  @Test fun streamReceivesFramesEvenBeforeOpenResponse() = runBlocking {
    lateinit var gateway: RemoteGateway
    var closed = false
    gateway = RemoteGateway { method, params ->
      when (method) {
        "harness.remote.stream.open" -> gateway.event(JSONObject().put("event", "harness.remote.frame").put("data",
          JSONObject().put("streamId", params.getString("streamId")).put("hasValue", true).put("value", JSONObject().put("type", "baseline"))))
        "harness.remote.stream.close" -> closed = true
      }
      JSONObject()
    }
    val stream = gateway.open("workspace/follow", JSONObject())
    assertEquals("baseline", (stream.receive() as JSONObject).getString("type"))
    stream.close()
    assertTrue(closed)
  }

  @Test fun largeRequestIsCommittedExactlyOnceAndTransferIsClosed() = runBlocking {
    var opens = 0
    var commits = 0
    var closes = 0
    var chunks = 0
    var bytes = 0
    val gateway = RemoteGateway { method, params ->
      when (method) {
        "harness.remote.transfer.open" -> { opens++; JSONObject() }
        "harness.remote.transfer.chunk" -> {
          assertEquals(chunks++, params.getInt("index"))
          bytes += Base64.getDecoder().decode(params.getString("data")).size
          JSONObject()
        }
        "harness.remote.transfer.commit" -> {
          commits++
          JSONObject().put("kind", "inline").put("response", JSONObject().put("ok", true).put("value", "ok"))
        }
        "harness.remote.transfer.close" -> { closes++; JSONObject() }
        else -> throw AssertionError("unexpected $method")
      }
    }
    val payload = JSONObject().put("args", JSONObject().put("text", "x".repeat(2 * 1024 * 1024)))
    assertEquals("ok", gateway.call("session/prompt", payload))
    assertTrue(bytes > 2 * 1024 * 1024)
    assertEquals(1, opens)
    assertEquals(1, commits)
    assertEquals(1, closes)
  }

  @Test fun oversizedReadIsTransferredExactlyOnce() = runBlocking {
    var direct = 0
    var commits = 0
    val gateway = RemoteGateway { method, _ ->
      when (method) {
        "harness.remote.call" -> { direct++; throw RemoteRpcException("RESPONSE_TOO_LARGE", retryable = true) }
        "harness.remote.transfer.open", "harness.remote.transfer.chunk", "harness.remote.transfer.close" -> JSONObject()
        "harness.remote.transfer.commit" -> {
          commits++
          JSONObject().put("kind", "inline").put("response", JSONObject().put("ok", true).put("value", JSONObject().put("entries", org.json.JSONArray())))
        }
        else -> throw AssertionError("unexpected $method")
      }
    }
    assertEquals(0, (gateway.call("workspaceFiles/list", JSONObject()) as JSONObject).getJSONArray("entries").length())
    assertEquals(1, direct)
    assertEquals(1, commits)
  }

  @Test fun oversizedMutationIsNotTransferred() = runBlocking {
    var calls = 0
    val gateway = RemoteGateway { method, _ ->
      calls++
      if (method == "harness.remote.call") throw RemoteRpcException("RESPONSE_TOO_LARGE", retryable = true)
      throw AssertionError("unexpected $method")
    }
    try { gateway.call("session/prompt", JSONObject()); fail() } catch (error: RemoteRpcException) {
      assertEquals("RESPONSE_TOO_LARGE", error.code)
    }
    assertEquals(1, calls)
  }

  @Test fun eventResultStaysDirectAndKeepsErrorDetails() = runBlocking {
    var transfers = 0
    val gateway = RemoteGateway { method, _ ->
      if (method.startsWith("harness.remote.transfer")) transfers++
      JSONObject().put("ok", false).put("error", JSONObject().put("code", "permission/denied").put("message", "denied")
        .put("details", JSONObject().put("eventId", "evt-1")))
    }
    try { gateway.call("\$events/result", JSONObject()); fail() } catch (error: RemoteGatewayException) {
      assertEquals("permission/denied", error.code)
      assertEquals("evt-1", error.details.getString("eventId"))
    }
    assertEquals(0, transfers)
  }
}
