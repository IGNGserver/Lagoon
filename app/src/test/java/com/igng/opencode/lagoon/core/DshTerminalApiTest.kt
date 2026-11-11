package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fake logical streams: these tests never start a process, HTTP server or physical socket. */
class DshTerminalApiTest {
  private val scope = TerminalScope.Session("session-one", "/display/workspace")

  @Test fun listUsesInactiveSessionNamedArgsAndPreservesRuntimeCwdAndUnknownPid() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    try {
      val item = api.terminals(scope).single()
      assertEquals(listOf("terminal/list"), fake.calls.map { it.first })
      assertEquals(setOf("sessionId"), fake.calls.single().second.keys().asSequence().toSet())
      assertEquals(scope.sessionId, fake.calls.single().second.getString("sessionId"))
      assertEquals(scope.directory, item.directory)
      assertEquals("/runtime/changed", item.cwd)
      assertEquals(scope.sessionId, item.sessionId)
      assertEquals(0L, item.pid)
      assertFalse(item.pidKnown)
      assertEquals(80, item.cols)
      assertEquals(listOf("-i"), item.args)
    } finally { api.close() }
  }

  @Test fun createUsesFreshUuidDefaultShellAndClampedEnvironmentWithoutRetries() = runBlocking {
    val fake = Fake()
    fake.env.put("maxCols", 40).put("maxRows", 12)
    val api = api(fake)
    try {
      val created = api.createTerminal(scope)
      assertEquals(listOf("terminal/environment", "terminal/create"), fake.calls.map { it.first })
      val args = fake.calls.last().second
      assertEquals(setOf("agentId", "request"), args.keys().asSequence().toSet())
      val request = args.getJSONObject("request")
      assertEquals(setOf("id", "cols", "rows"), request.keys().asSequence().toSet())
      assertEquals(created.id, java.util.UUID.fromString(request.getString("id")).toString())
      assertEquals(40, request.getInt("cols"))
      assertEquals(12, request.getInt("rows"))
      assertFalse(fake.calls.any { it.first == "terminal/shells" })
      fake.callFailure = IllegalStateException("secret")
      assertTrue(runCatching { api.createTerminal(scope) }.isFailure)
      assertEquals(3, fake.calls.size)
    } finally { api.close() }
  }

  @Test fun retainAckGatesAttachmentAndBothHandlesLiveUntilDetachWithoutDelete() = runBlocking {
    val fake = Fake().apply { autoRetain = false }
    val api = api(fake)
    try {
      val result = async(start = CoroutineStart.UNDISPATCHED) { api.attachTerminal(scope, "term-one") }
      assertEquals(listOf("terminal/retain"), fake.streams.map { it.method })
      assertFalse(result.isCompleted)
      fake.hold().emit(JSONObject().put("type", "retained"))
      val attachment = result.await()
      assertEquals(listOf("terminal/retain", "terminal/attach"), fake.streams.map { it.method })
      assertEquals(setOf("sessionId", "id"), fake.hold().args.keys().asSequence().toSet())
      assertEquals(setOf("agentId", "id"), fake.attached().args.keys().asSequence().toSet())
      assertFalse(fake.hold().cancelled)
      attachment.close()
      assertTrue(fake.hold().cancelled)
      assertTrue(fake.attached().cancelled)
      assertFalse(fake.calls.any { it.first == "terminal/close" })
    } finally { api.close() }
  }

  @Test fun snapshotIsRequiredAndStrictOutputSequenceRetainsRawAnsiTail() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    try {
      val attachment = api.attachTerminal(scope, "term-one")
      assertTrue(attachment.state.value.ready)
      assertEquals(10L, attachment.state.value.cursor)
      fake.attached().emit(output(11, "\u001b[31m🙂".repeat(50_000)))
      val state = attachment.state.value
      assertEquals(11L, state.cursor)
      assertTrue(state.truncated)
      assertTrue(state.output.toByteArray().size <= 256 * 1024)
      assertFalse(state.output.first().isLowSurrogate())
      fake.attached().emit(output(13, "gap"))
      assertTrue(attachment.state.value.ended)
      assertFalse(attachment.sendText("never"))
      assertEquals(2, fake.streams.size) // no automatic reattach
    } finally { api.close() }
  }

  @Test fun firstFrameDuplicateSnapshotWrongIdentityAndUnsafeNumbersFailClosed() = runBlocking {
    for (bad in listOf(
      output(1, "not snapshot"), snapshot().put("sequence", "10"), snapshot().put("sequence", 1.5),
      snapshot().put("sequence", 9_007_199_254_740_992L),
      snapshot().put("info", info("different")), snapshot().put("writable", "true"),
      snapshot().put("info", info().put("cols", 1)),
      snapshot().put("inputWindow", JSONObject().put("maxBytes", 10).put("frameOverheadBytes", -1))
    )) {
      val fake = Fake().apply { initial = bad }
      val api = api(fake)
      try {
        assertTrue(runCatching { api.attachTerminal(scope, "term-one") }.isFailure)
        assertTrue(fake.streams.all { it.cancelled })
      } finally { api.close() }
    }
    val fake = Fake()
    val api = api(fake)
    try {
      val attachment = api.attachTerminal(scope, "term-one")
      fake.attached().emit(snapshot())
      assertTrue(attachment.state.value.ended)
    } finally { api.close() }
  }

  @Test fun inputAndResizeShareOrderedSequencesAndAckReleasesPendingBudgets() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    try {
      val attachment = api.attachTerminal(scope, "term-one")
      assertTrue(attachment.sendText("汉🙂\r"))
      assertTrue(attachment.resize(100, 30))
      val commands = fake.attached().sent.map(::JSONObject)
      assertEquals(listOf("write", "resize"), commands.map { it.getString("type") })
      assertEquals(listOf(1L, 2L), commands.map { it.getLong("sequence") })
      assertEquals("汉🙂\r", commands[0].getString("data"))
      assertEquals(2, attachment.state.value.pendingInputs)
      fake.attached().emit(ack(1))
      fake.attached().emit(ack(2))
      assertEquals(0, attachment.state.value.pendingInputs)
      assertEquals(2L, attachment.state.value.acknowledgedSequence)
      assertFalse(attachment.state.value.inputDeliveryUnknown)
      assertFalse(attachment.resize(1, 20))
      assertFalse(attachment.resize(81, Int.MAX_VALUE))
      assertEquals(2, fake.attached().sent.size)
    } finally { api.close() }
  }

  @Test fun unknownOutOfOrderAndDuplicateAcksAreProtocolFailures() = runBlocking {
    for (frames in listOf(listOf(ack(1)), listOf(ack(2)), listOf(ack(1), ack(1)), listOf(ack(1).put("refusal", "other")))) {
      val fake = Fake()
      val api = api(fake)
      try {
        val attachment = api.attachTerminal(scope, "term-one")
        if (frames.size > 1 || frames.first().optLong("sequence") == 2L || frames.first().has("refusal")) {
          assertTrue(attachment.sendText("x"))
        }
        frames.forEach(fake.attached()::emit)
        assertTrue(attachment.state.value.ended)
        assertTrue(fake.hold().cancelled)
      } finally { api.close() }
    }
  }

  @Test fun controlStealAndRefusalDisableWritesWithoutReattachOrReplay() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    try {
      val attachment = api.attachTerminal(scope, "term-one")
      fake.attached().emit(JSONObject().put("type", "state").put("info", info()).put("writable", false))
      assertFalse(attachment.state.value.writable)
      assertFalse(attachment.sendText("read-only"))
      fake.attached().emit(JSONObject().put("type", "state").put("info", info()).put("writable", true))
      assertTrue(attachment.sendText("raced control"))
      fake.attached().emit(ack(1).put("refusal", "read-only"))
      assertEquals("read-only", attachment.state.value.inputRefusal)
      assertFalse(attachment.state.value.writable)
      assertEquals(0, attachment.state.value.pendingInputs)
      fake.attached().emit(JSONObject().put("type", "state").put("info", info()).put("writable", true))
      assertFalse(attachment.sendText("no implicit regain after refusal"))
      assertFalse(attachment.state.value.inputDeliveryUnknown)
      assertEquals(2, fake.streams.size)
    } finally { api.close() }
  }

  @Test fun rawUtf8AndEncodedEnvelopeBudgetsRefuseLocallyWithoutUnknownOrQueue() = runBlocking {
    val fake = Fake().apply { env.put("maxInputBytes", 6) }
    val api = api(fake)
    try {
      val attachment = api.attachTerminal(scope, "term-one")
      assertTrue(attachment.sendText("汉汉")) // six raw UTF-8 bytes
      assertFalse(attachment.sendText("x"))
      assertFalse(attachment.state.value.inputDeliveryUnknown)
      assertFalse(attachment.state.value.ended)
      assertEquals(1, fake.attached().sent.size)
      fake.attached().emit(ack(1))
      assertTrue(attachment.sendText("x"))
    } finally { api.close() }
    val window = Fake().apply {
      initial = snapshot().put("inputWindow", JSONObject().put("maxBytes", 50).put("frameOverheadBytes", 10))
    }
    val windowApi = api(window)
    try {
      val attachment = windowApi.attachTerminal(scope, "term-one")
      // Compact {type:write,sequence:1,data:x} is 40 bytes; ten envelope bytes reaches 50.
      assertTrue(attachment.sendText("x"))
      assertFalse(attachment.sendText("x"))
      assertFalse(attachment.state.value.inputDeliveryUnknown)
      window.attached().emit(ack(1))
      assertFalse(attachment.sendText("\u0000")) // JSON escape bytes, not merely raw input
      assertEquals(1, window.attached().sent.size)
    } finally { windowApi.close() }
  }

  @Test fun synchronousAckInsideSendIsKnownAndFalseSendFailsUnknownWithoutReplay() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    try {
      val attachment = api.attachTerminal(scope, "term-one")
      fake.attached().sendHook = { data -> fake.attached().emit(ack(JSONObject(data).getLong("sequence"))); true }
      assertTrue(attachment.sendText("early"))
      assertEquals(0, attachment.state.value.pendingInputs)
      assertEquals(1L, attachment.state.value.acknowledgedSequence)
      assertFalse(attachment.state.value.ended)
      fake.attached().sendHook = { false }
      assertFalse(attachment.sendText("uncertain"))
      assertTrue(attachment.state.value.ended)
      assertTrue(attachment.state.value.inputDeliveryUnknown)
      assertFalse(attachment.sendText("never replay"))
      assertEquals(2, fake.attached().sent.size)
      assertEquals(2, fake.streams.size)
    } finally { api.close() }
  }

  @Test fun holdEndOrOwnerDisposeCancelsAllLogicalHandlesAndNeverClosesProcess() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    val one = api.attachTerminal(scope, "term-one")
    assertTrue(one.sendText("pending"))
    fake.hold().end(null)
    assertTrue(one.state.value.ended)
    assertTrue(one.state.value.inputDeliveryUnknown)
    val two = api.attachTerminal(scope, "term-one")
    api.close()
    api.close()
    assertTrue(two.state.value.ended)
    assertTrue(fake.streams.all { it.cancelled })
    fake.attached().emit(output(11, "late"))
    assertFalse(two.state.value.output.contains("late"))
    assertFalse(fake.calls.any { it.first == "terminal/close" })
    assertTrue(runCatching { api.terminals(scope) }.isFailure)
  }

  @Test fun explicitRenameAndCloseAreSingleVoidMutationsAndExitedInfoSurvivesCompletion() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    try {
      val attachment = api.attachTerminal(scope, "term-one")
      api.renameTerminal(scope, "term-one", "new title")
      api.closeTerminal(scope, "term-one")
      val mutations = fake.calls.filter { it.first in setOf("terminal/rename", "terminal/close") }
      assertEquals(2, mutations.size)
      assertEquals("new title", mutations.first().second.getString("title"))
      assertEquals(scope.sessionId, mutations.last().second.getString("agentId"))
      fake.attached().emit(JSONObject().put("type", "state")
        .put("info", info().put("state", "exited").put("exitCode", -1)).put("writable", false))
      fake.attached().end(null)
      assertEquals("exited", attachment.state.value.info!!.status)
      assertEquals(-1, attachment.state.value.info!!.exitCode)
      assertTrue(attachment.state.value.ended)
      assertNull(attachment.state.value.error)
    } finally { api.close() }
  }

  @Test fun malformedListAndEnvironmentTypesAreNotCoerced() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    try {
      for (bad in listOf(info().put("id", 1), info().put("shell", JSONObject()), info().put("exitCode", 1.5),
        info().put("state", "unknown"), info().put("rows", "24"))) {
        fake.list = JSONArray().put(bad)
        assertTrue(runCatching { api.terminals(scope) }.isFailure)
      }
      fake.env.put("maxInputBytes", "1024")
      assertTrue(runCatching { api.createTerminal(scope) }.isFailure)
      assertFalse(fake.calls.any { it.first == "terminal/create" })
    } finally { api.close() }
  }

  @Test fun callbackAndSendCarrierGatesDoNotInvertAndDetachCancelsOutsideStateLock() = runBlocking {
    val fake = Fake()
    val api = api(fake)
    try {
      val attachment = api.attachTerminal(scope, "term-one")
      val gate = Any()
      val outputHasGate = CountDownLatch(1)
      val sendEntered = CountDownLatch(1)
      val callbackDone = CountDownLatch(1)
      val sendDone = CountDownLatch(1)
      val accepted = AtomicBoolean()
      fake.attached().sendHook = {
        sendEntered.countDown()
        synchronized(gate) { true }
      }
      val callback = Thread({
        synchronized(gate) {
          outputHasGate.countDown()
          if (sendEntered.await(2, TimeUnit.SECONDS)) fake.attached().emit(ack(1))
        }
        callbackDone.countDown()
      }, "dsh-terminal-callback").apply { isDaemon = true }
      val sender = Thread({ accepted.set(attachment.sendText("x")); sendDone.countDown() }, "dsh-terminal-send").apply { isDaemon = true }
      callback.start()
      assertTrue(outputHasGate.await(2, TimeUnit.SECONDS))
      sender.start()
      assertTrue(callbackDone.await(2, TimeUnit.SECONDS))
      assertTrue(sendDone.await(2, TimeUnit.SECONDS))
      assertTrue(accepted.get())
      assertEquals(1L, attachment.state.value.acknowledgedSequence)
      val cancelDone = CountDownLatch(1)
      fake.attached().cancelHook = {
        val callbackThread = Thread({ fake.attached().emit(output(11, "late")); cancelDone.countDown() }).apply { isDaemon = true }
        callbackThread.start()
        assertTrue(cancelDone.await(2, TimeUnit.SECONDS))
      }
      attachment.close()
      assertFalse(attachment.state.value.output.contains("late"))
    } finally { api.close() }
  }

  private fun api(fake: Fake) = DshTerminalApi(fake::call, fake::open) { false }

  private class Fake {
    val calls = mutableListOf<Pair<String, JSONObject>>()
    val streams = mutableListOf<Stream>()
    val env = JSONObject().put("cwd", "/runtime/changed").put("maxInputBytes", 16 * 1024)
      .put("maxBufferedBytes", 256 * 1024).put("maxCols", 2048).put("maxRows", 1024).put("scrollback", 1000)
    var list = JSONArray().put(info())
    var initial = snapshot()
    var autoRetain = true
    var callFailure: Throwable? = null
    suspend fun call(method: String, args: JSONObject): Any? {
      OfficialDshContract.assertArgs(method, args)
      calls += method to JSONObject(args.toString())
      callFailure?.let { throw it }
      return when (method) {
        "terminal/list" -> list
        "terminal/environment" -> env
        "terminal/create" -> info(args.getJSONObject("request").getString("id"))
        "terminal/rename", "terminal/close" -> null
        else -> error("Unexpected RPC $method")
      }
    }
    suspend fun open(method: String, args: JSONObject, item: (JSONObject) -> Unit, ended: (Throwable?) -> Unit): WebSocket {
      OfficialDshContract.assertArgs(method, args)
      val stream = Stream(method, JSONObject(args.toString()), item, ended)
      streams += stream
      when (method) {
        "terminal/retain" -> if (autoRetain) item(JSONObject().put("type", "retained"))
        "terminal/attach" -> item(initial)
        else -> error("Unexpected stream $method")
      }
      return stream
    }
    fun hold() = streams.last { it.method == "terminal/retain" }
    fun attached() = streams.last { it.method == "terminal/attach" }
  }

  private class Stream(
    val method: String, val args: JSONObject, private val item: (JSONObject) -> Unit, private val ended: (Throwable?) -> Unit
  ) : WebSocket {
    val sent = mutableListOf<String>()
    var cancelled = false
    var sendHook: ((String) -> Boolean)? = null
    var cancelHook: (() -> Unit)? = null
    fun emit(value: JSONObject) = item(value)
    fun end(error: Throwable?) = ended(error)
    override fun request(): Request = Request.Builder().url("https://example.invalid/").build()
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean { sent += text; return sendHook?.invoke(text) ?: true }
    override fun send(bytes: ByteString): Boolean = error("No binary uplink")
    override fun close(code: Int, reason: String?): Boolean = error("Use logical cancel, not physical close")
    override fun cancel() { cancelled = true; cancelHook?.invoke() }
  }

  companion object {
    private fun info(id: String = "term-one") = JSONObject().put("id", id).put("title", "sh")
      .put("shell", JSONObject().put("path", "/bin/sh").put("args", JSONArray().put("-i")).put("name", "sh"))
      .put("cwd", "/runtime/changed").put("cols", 80).put("rows", 24).put("state", "running").put("exitCode", JSONObject.NULL)
    private fun snapshot() = JSONObject().put("type", "snapshot").put("sequence", 10).put("screen", "\u001b[0mready")
      .put("info", info()).put("writable", true)
      .put("inputWindow", JSONObject().put("maxBytes", 256 * 1024).put("frameOverheadBytes", 32))
    private fun output(sequence: Long, data: String) = JSONObject().put("type", "output").put("sequence", sequence).put("data", data)
    private fun ack(sequence: Long) = JSONObject().put("type", "ack").put("sequence", sequence)
  }
}
