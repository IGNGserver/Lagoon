package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real in-memory RemoteGateway plus a fresh native helper. No network, process or reconnect. */
class RemoteTerminalSocketTest {
  @Test fun earlySnapshotUsesUniqueAttachmentReplaysScreenAndSanitizesRequest() = runBlocking {
    val first = Fixture()
    val second = Fixture()
    try {
      val one = first.attach()
      val two = second.attach()
      assertEquals("\u001b[31mexisting", one.state.value.output)
      assertTrue(one.state.value.writable)
      assertNotEquals(first.attachmentId, second.attachmentId)
      assertEquals(first.attachmentId, UUID.fromString(first.attachmentId).toString())
      val args = first.follow.getJSONObject("payload").getJSONObject("args")
      assertEquals(setOf("agentId", "id", "attachmentId"), args.keys().asSequence().toSet())
      assertEquals("session-one", args.getString("agentId"))
      assertEquals("https://remote.invalid/", first.socket!!.request().url.toString())
      assertEquals(0, first.socket!!.request().headers.size)
      assertFalse(first.socket!!.send(ByteString.EMPTY))
      assertTrue(two.state.value.ready)
    } finally { first.close(); second.close() }
  }

  @Test fun writesAndResizesAreSequentialProviderAcksIncludingVoidResult() = runBlocking {
    val f = Fixture()
    try {
      val attachment = f.attach()
      assertTrue(attachment.sendText("汉🙂\r"))
      assertTrue(attachment.resize(90, 30))
      assertEquals(1L, f.nextAck().getLong("sequence"))
      assertEquals(2L, f.nextAck().getLong("sequence"))
      assertEquals(listOf("terminal/write", "terminal/resize"), f.calls.map { it.getString("endpoint") })
      val args = f.calls[0].getJSONObject("payload").getJSONObject("args")
      assertEquals("汉🙂\r", args.getString("data"))
      assertFalse(args.has("sequence"))
      assertEquals(f.attachmentId, args.getString("attachmentId"))
      assertEquals(2L, attachment.state.value.acknowledgedSequence)
      assertEquals(0, attachment.state.value.pendingInputs)
      assertEquals(0L, f.socket!!.queueSize())
    } finally { f.close() }
  }

  @Test fun foreignControllerCannotGrantWritesAndControlLossNeverRetries() = runBlocking {
    val f = Fixture().apply { foreign = true }
    try {
      val attachment = f.attach()
      assertFalse(attachment.state.value.writable)
      assertFalse(attachment.sendText("denied"))
      assertFalse(f.socket!!.send(write(1, "denied")))
      assertTrue(f.calls.isEmpty())
      f.publish(state(f.attachmentId, false))
      f.nextFrame("state")
      assertTrue(attachment.state.value.writable) // untrusted false is ignored, ownership wins
      f.publish(state("other-controller", true))
      f.nextFrame("state")
      assertFalse(attachment.state.value.writable)
      assertFalse(attachment.sendText("lost"))
      assertTrue(f.calls.isEmpty())
    } finally { f.close() }
  }

  @Test fun permissionDeniedIsKnownReadOnlyRefusalAndCannotRegainImplicitly() = runBlocking {
    for (gatewayError in listOf(false, true)) {
      val f = Fixture().apply {
        callHook = { if (gatewayError) JSONObject().put("ok", false).put("error", JSONObject()
          .put("code", "PERMISSION_DENIED").put("message", "private")) else throw RemoteRpcException("PERMISSION_DENIED") }
      }
      try {
        val attachment = f.attach()
        assertTrue(attachment.sendText("raced"))
        assertEquals("read-only", f.nextAck().getString("refusal"))
        assertEquals("read-only", attachment.state.value.inputRefusal)
        assertFalse(attachment.state.value.inputDeliveryUnknown)
        assertFalse(attachment.state.value.writable)
        f.publish(state(f.attachmentId, true)); f.nextFrame("state")
        assertFalse(f.socket!!.send(write(2, "never")))
        assertEquals(1, f.calls.size)
      } finally { f.close() }
    }
  }

  @Test fun unknownRpcFailureEmitsNoAckMarksUnknownAndIsNotRetried() = runBlocking {
    val f = Fixture().apply { callHook = { throw IOException("https://user:secret@private.invalid") } }
    try {
      val attachment = f.attach()
      assertTrue(attachment.sendText("unknown"))
      val error = withTimeout(2000) { f.closed.receive() }
      assertFalse(error!!.message!!.contains("secret"))
      assertTrue(attachment.state.value.ended)
      assertTrue(attachment.state.value.inputDeliveryUnknown)
      assertEquals(1, f.calls.size)
      assertTrue(f.items.none { it.optString("type") == "ack" })
      assertEquals(0L, f.socket!!.queueSize())
    } finally { f.close() }
  }

  @Test fun cancelDiscardsQueueImmediatelyAndLateRpcCannotPublishAckOrOutput() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val exited = CompletableDeferred<Unit>()
    val f = Fixture().apply { callHook = {
      entered.complete(Unit)
      withContext(NonCancellable) { release.await() }
      exited.complete(Unit)
      JSONObject().put("ok", true)
    } }
    try {
      val attachment = f.attach()
      assertTrue(attachment.sendText("in flight")); withTimeout(2000) { entered.await() }
      assertTrue(attachment.sendText("queued"))
      val seen = f.items.size
      f.socket!!.cancel()
      assertEquals(0, f.gateway.activeStreamCount)
      assertEquals(0L, f.socket!!.queueSize())
      f.socket!!.cancel()
      release.complete(Unit); withTimeout(2000) { exited.await() }
      f.publish(JSONObject().put("type", "output").put("sequence", 1).put("data", "late"))
      assertEquals(seen, f.items.size)
      assertEquals(1, f.disposeCount)
      assertEquals(1, f.closedCount)
      assertEquals(1, f.calls.size)
      assertFalse(f.methods.contains("terminal/close"))
      assertTrue(attachment.state.value.inputDeliveryUnknown)
    } finally { release.complete(Unit); f.close() }
  }

  @Test fun delayedRpcBoundsItemsAndFullEscapedUtf8EnvelopeBytes() = runBlocking {
    for (large in listOf(false, true)) {
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val f = Fixture().apply { callHook = { entered.complete(Unit); release.await(); JSONObject().put("ok", true) } }
      try {
        f.attach()
        val data = if (large) "\u0000汉🙂".repeat(3000) else "x"
        assertTrue(f.socket!!.send(write(1, data))); withTimeout(2000) { entered.await() }
        var sequence = 2L
        while (sequence <= 40 && f.socket!!.send(write(sequence, data))) sequence++
        assertTrue(sequence <= 33)
        if (!large) assertEquals(33L, sequence) else assertTrue(sequence < 33)
        assertTrue(f.socket!!.queueSize() in 1..(256 * 1024L))
        assertEquals(1, f.calls.size)
        f.socket!!.cancel()
        assertEquals(0L, f.socket!!.queueSize())
      } finally { release.complete(Unit); f.close() }
    }
  }

  @Test fun openReturnsBeforeSnapshotButInputIsNotAllowedUntilReady() = runBlocking {
    val f = Fixture().apply { autoSnapshot = false }
    try {
      val opening = async(start = CoroutineStart.UNDISPATCHED) { f.attach() }
      withTimeout(2000) { f.opened.await() }
      assertFalse(opening.isCompleted)
      assertFalse(f.socket!!.send(write(1, "too early")))
      f.publish(f.snapshot())
      val attachment = withTimeout(2000) { opening.await() }
      assertTrue(attachment.state.value.ready)
      assertTrue(f.calls.isEmpty())
    } finally { f.close() }
  }

  @Test fun cancellationWhileOpenPendingClosesFollowAndNeverReopens() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val f = Fixture().apply { openHook = { entered.complete(Unit); release.await() } }
    try {
      val opening = async(start = CoroutineStart.UNDISPATCHED) { f.attach() }
      withTimeout(2000) { entered.await() }
      opening.cancelAndJoin()
      assertEquals(0, f.gateway.activeStreamCount)
      assertEquals(1, f.methods.count { it == "harness.remote.stream.open" })
      assertEquals(1, f.closedCount)
      assertEquals(1, f.disposeCount)
      assertTrue(f.calls.isEmpty())
    } finally { release.complete(Unit); f.close() }
  }

  @Test fun invalidInputsAndSequencesFailClosedWithoutProviderCall() = runBlocking {
    for (bad in listOf(write(2, "gap"), write(1, "extra").dropLast(1) + ",\"extra\":true}",
      "{\"type\":\"write\",\"sequence\":1,\"data\":1}",
      "{\"type\":\"resize\",\"sequence\":1,\"cols\":1001,\"rows\":24}",
      "{\"type\":\"resize\",\"sequence\":1.5,\"cols\":80,\"rows\":24}",
      "{\"type\":\"write\",\"sequence\":9007199254740992,\"data\":\"x\"}")) {
      val f = Fixture()
      try {
        f.attach()
        assertFalse(f.socket!!.send(bad))
        assertTrue(f.calls.isEmpty())
        assertEquals(1, f.closedCount)
      } finally { f.close() }
    }
  }

  @Test fun remoteAckUnknownFrameAndNativeOutputGapAreProtocolFailures() = runBlocking {
    for (bad in listOf(JSONObject().put("type", "ack").put("sequence", 1), JSONObject().put("type", "alien"),
      JSONObject().put("type", "output").put("sequence", 2).put("data", "gap"))) {
      val f = Fixture()
      try {
        val attachment = f.attach()
        f.publish(bad)
        withTimeout(2000) { f.closed.receive() }
        assertTrue(attachment.state.value.ended)
        assertFalse(attachment.state.value.output.contains("gap"))
        assertTrue(f.calls.isEmpty())
      } finally { f.close() }
    }
  }

  @Test fun invalidPayloadIdsAreRejectedBeforeAnyRemoteOpen() = runBlocking {
    val f = Fixture()
    try {
      for (bad in listOf(payload("bad/id"), payload("a".repeat(257)), payload("").put("extra", true),
        JSONObject().put("args", JSONObject().put("agentId", "session-one").put("id", "term-one").put("attachmentId", "foreign")))) {
        assertTrue(runCatching { RemoteTerminalSocket.open(f.gateway, bad, {}, {}) }.isFailure)
      }
      assertTrue(f.methods.isEmpty())
    } finally { f.close() }
  }

  private class Fixture {
    val calls = CopyOnWriteArrayList<JSONObject>()
    val methods = CopyOnWriteArrayList<String>()
    val items = CopyOnWriteArrayList<JSONObject>()
    val frames = Channel<JSONObject>(Channel.UNLIMITED)
    val closed = Channel<Throwable?>(Channel.UNLIMITED)
    val opened = CompletableDeferred<Unit>()
    var foreign = false
    var autoSnapshot = true
    var callHook: suspend () -> JSONObject = { JSONObject().put("ok", true) }
    var openHook: suspend () -> Unit = {}
    @Volatile var closedCount = 0
    @Volatile var disposeCount = 0
    lateinit var follow: JSONObject
    var socket: RemoteTerminalSocket? = null
    val attachmentId: String get() = follow.getJSONObject("payload").getJSONObject("args").getString("attachmentId")
    val gateway = RemoteGateway { method, request ->
      methods += method
      when (method) {
        "harness.remote.stream.open" -> {
          follow = JSONObject(request.toString())
          openHook()
          if (autoSnapshot) publish(snapshot())
          JSONObject().put("opened", true)
        }
        "harness.remote.stream.close" -> JSONObject()
        "harness.remote.call" -> { calls += JSONObject(request.toString()); callHook() }
        else -> error("Unexpected fake wire method")
      }
    }
    val api = DshTerminalApi(call = { method, _ ->
      check(method == "terminal/environment")
      JSONObject().put("cwd", "/runtime").put("scrollback", 1000).put("maxInputBytes", 512 * 1024)
        .put("maxBufferedBytes", 1024 * 1024).put("maxCols", 1000).put("maxRows", 1000)
    }, open = { method, args, onItem, onClosed ->
      if (method == "terminal/retain") {
        onItem(JSONObject().put("type", "retained"))
        Hold()
      } else {
        check(method == "terminal/attach")
        RemoteTerminalSocket.open(gateway, JSONObject().put("args", args), { value ->
          onItem(value)
          items += value
          frames.trySend(value)
        }, { cause -> onClosed(cause); closedCount++; closed.trySend(cause) }, { disposeCount++ })
          .also { socket = it; opened.complete(Unit) }
      }
    }, ownerClosed = { false })
    suspend fun attach(): TerminalAttachment {
      val result = api.attachTerminal(TerminalScope.Session("session-one", "/display"), "term-one")
      nextFrame("snapshot") // Join the serialized callback, not only the helper's ready deferred.
      return result
    }
    fun snapshot() = JSONObject().put("type", "snapshot").put("sequence", 0).put("screen", "\u001b[31mexisting")
      .put("info", info(if (foreign) "another" else attachmentId)).put("writable", foreign)
      .put("inputWindow", JSONObject().put("maxBytes", 1024 * 1024).put("frameOverheadBytes", 0))
    fun publish(value: JSONObject) { gateway.event(JSONObject().put("event", "harness.remote.frame").put("data",
      JSONObject().put("streamId", follow.getString("streamId")).put("hasValue", true).put("value", value))) }
    suspend fun nextFrame(type: String): JSONObject = withTimeout(2000) {
      var item = frames.receive()
      while (item.optString("type") != type) item = frames.receive()
      item
    }
    suspend fun nextAck() = nextFrame("ack")
    fun close() { api.close(); socket?.cancel(); gateway.close() }
  }
  private class Hold : WebSocket {
    override fun request() = Request.Builder().url("https://remote.invalid/").build()
    override fun queueSize() = 0L
    override fun send(text: String) = false
    override fun send(bytes: ByteString) = false
    override fun close(code: Int, reason: String?) = true
    override fun cancel() {}
  }
  companion object {
    private fun payload(agent: String = "session-one") = JSONObject().put("args", JSONObject().put("agentId", agent).put("id", "term-one"))
    private fun write(sequence: Long, data: String) = JSONObject().put("type", "write").put("sequence", sequence).put("data", data).toString()
    private fun state(controller: String, writable: Boolean) = JSONObject().put("type", "state").put("info", info(controller)).put("writable", writable)
    private fun info(controller: String) = JSONObject().put("id", "term-one").put("title", "shell").put("state", "running")
      .put("shell", JSONObject().put("name", "sh").put("path", "/bin/sh").put("args", JSONArray()))
      .put("cwd", "/runtime").put("cols", 80).put("rows", 24).put("exitCode", JSONObject.NULL).put("controllerId", controller)
  }
}
