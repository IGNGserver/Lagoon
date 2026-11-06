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

  /** 一个坏帧只让所属流失败：事件通道还要继续承载其他 RPC。 */
  @Test fun malformedStreamFrameFailsOnlyThatStream() = runBlocking {
    var calls = 0
    lateinit var gateway: RemoteGateway
    gateway = RemoteGateway { method, params ->
      calls++
      when (method) {
        "harness.remote.stream.open" -> {
          // 首帧合法，随后是一帧缺少 hasValue 的坏帧。
          gateway.event(JSONObject().put("event", "harness.remote.frame").put("data",
            JSONObject().put("streamId", params.getString("streamId")).put("hasValue", true).put("value", JSONObject().put("type", "first"))))
          gateway.event(JSONObject().put("event", "harness.remote.frame").put("data",
            JSONObject().put("streamId", params.getString("streamId")).put("value", JSONObject().put("type", "broken"))))
        }
        "harness.remote.stream.close" -> JSONObject()
        "harness.remote.call" -> JSONObject().put("ok", true).put("value", "still-alive")
        else -> throw AssertionError("unexpected $method")
      }
    }
    val stream = gateway.open("session/follow", JSONObject())
    assertEquals("first", (stream.receive() as JSONObject).getString("type"))
    // 坏帧之后该流以异常结束，但同一 gateway 的普通调用仍然可用。
    try { stream.receive(); fail() } catch (_: Exception) { }
    assertEquals("still-alive", gateway.call("session/list", JSONObject()))
    assertNotNull(gateway.lastError)
    assertTrue(calls >= 2)
  }

  /** closeNow 同步归还槽位，取消路径不能等协程调度后才让出上限。 */
  @Test fun streamBudgetIsEnforcedAndCloseNowReleasesSlot() = runBlocking {
    val gateway = RemoteGateway { method, _ ->
      when (method) {
        "harness.remote.stream.open" -> JSONObject().put("opened", true)
        "harness.remote.stream.close" -> JSONObject()
        else -> JSONObject()
      }
    }
    val opened = mutableListOf<RemoteGatewayStream>()
    try {
      repeat(gateway.maxStreams) { opened += gateway.open("session/follow", JSONObject()) }
      assertEquals(gateway.maxStreams, gateway.activeStreamCount)
      assertEquals(mapOf("session/follow" to gateway.maxStreams), gateway.streamCounts())
      try {
        gateway.open("session/follow", JSONObject())
        fail("超过上限必须拒绝")
      } catch (_: RemoteStreamLimitException) { }
      opened.first().closeNow()
      assertEquals(gateway.maxStreams - 1, gateway.activeStreamCount)
      val replacement = gateway.open("session/follow", JSONObject())
      assertEquals(gateway.maxStreams, gateway.activeStreamCount)
      replacement.closeNow()
      opened.drop(1).forEach { it.closeNow() }
      assertEquals(0, gateway.activeStreamCount)
    } finally {
      gateway.close()
    }
  }
}
