package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fixtures for the pinned OpenCode simple PTY protocol, not the unrelated mux protocol. */
class OpenCodeTerminalApiTest {
  @Test fun strictEnvelopeUsesResponseLocationNotProcessCwdAndPreservesTypes() = runBlocking {
    val transport = FakeTransport()
    val api = api(transport)
    try {
      transport.response = envelope(JSONArray().put(info().put("pid", 4_294_967_296L).put("exitCode", -1)))
      val terminal = api.terminals("/repo/").single()
      assertEquals("/repo", terminal.directory)
      assertEquals("/repo/subdir", terminal.cwd)
      assertEquals(4_294_967_296L, terminal.pid)
      assertEquals(-1, terminal.exitCode)
      assertEquals(listOf("-l"), terminal.args)
      assertEquals("GET", transport.calls.single().method)
      assertEquals("api/pty?location%5Bdirectory%5D=%2Frepo%2F", transport.calls.single().path)
    } finally { api.close() }
  }

  @Test fun malformedLocationPayloadTypesAndUriDirectoriesAreRejected() = runBlocking {
    val transport = FakeTransport()
    val api = api(transport)
    try {
      for (body in listOf(
        JSONObject().put("data", JSONArray().put(info())),
        JSONObject().put("location", JSONObject().put("directory", "/elsewhere")).put("data", JSONArray().put(info())),
        envelope(JSONArray().put(info().put("pid", "12"))),
        envelope(JSONArray().put(info().put("args", JSONArray().put(2)))),
        envelope(info())
      )) {
        transport.response = body
        assertTrue(runCatching { api.terminals("/repo") }.exceptionOrNull() is ApiException)
      }
      val before = transport.calls.size
      assertTrue(runCatching { api.terminals("file:///repo") }.exceptionOrNull() is IllegalArgumentException)
      assertEquals(before, transport.calls.size)
    } finally { api.close() }
  }

  @Test fun explicitMutationsUseSingleRequestsAndDetachNeverDeletes() = runBlocking {
    val transport = FakeTransport()
    val api = api(transport)
    try {
      transport.response = envelope(info())
      api.createTerminal("/repo")
      api.resizeTerminal("/repo", "pty_1", 80, 24)
      api.renameTerminal("/repo", "pty_1", "renamed")
      assertEquals(listOf("POST", "PUT", "PUT"), transport.calls.map { it.method })
      assertEquals("{}", transport.calls[0].body)
      assertEquals(80, JSONObject(transport.calls[1].body!!).getJSONObject("size").getInt("cols"))
      assertEquals("renamed", JSONObject(transport.calls[2].body!!).getString("title"))
      assertFalse(JSONObject(transport.calls[0].body!!).has("command"))
      val attachment = api.attachTerminal("/repo", "pty_1")
      attachment.close()
      assertEquals(0, transport.calls.count { it.method == "DELETE" })
      transport.status = 204
      api.closeTerminal("/repo", "pty_1")
      assertEquals(1, transport.calls.count { it.method == "DELETE" })
      transport.status = 503
      assertEquals(503, (runCatching { api.renameTerminal("/repo", "pty_1", "x") }.exceptionOrNull() as ApiException).status)
      assertEquals(6, transport.calls.size) // Three writes, ticket, remove, failed rename; no retry.
    } finally { api.close() }
  }

  @Test fun ticketCarriesAuthAndOptInHeaderWhileWebsocketUsesOnlyTicketInUrl() = runBlocking {
    val transport = FakeTransport()
    val api = api(transport)
    try {
      api.attachTerminal("/repo", "pty space").close()
      val ticket = transport.calls.single()
      assertTrue(ticket.path.startsWith("api/pty/pty%20space/connect-token?"))
      assertEquals("1", ticket.headers["x-opencode-ticket"])
      assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", ticket.headers["Authorization"])
      assertEquals("session=cookie", ticket.headers["Cookie"])
      assertTrue(transport.wsPath!!.contains("/pty%20space/connect?"))
      assertTrue(transport.wsPath!!.endsWith("&ticket=single%2Fuse%2Bticket"))
      assertFalse(transport.wsPath!!.contains("secret"))
      assertFalse(transport.wsPath!!.contains("cursor"))
      assertEquals(ticket.headers["Authorization"], transport.wsHeaders["Authorization"])
    } finally { api.close() }
  }

  @Test fun rawReplayPrecedesControlReadinessAndCursorCountsUtf16() = runBlocking {
    val transport = FakeTransport()
    val api = api(transport)
    try {
      val attachment = api.attachTerminal("/repo", "pty_1")
      transport.listener!!.onOpen(transport.channel)
      assertFalse(attachment.state.value.ready)
      assertFalse(attachment.sendText("not yet"))
      transport.listener!!.onText("\u001b[31mreplay🙂")
      assertNull(attachment.state.value.cursor)
      transport.listener!!.onBytes(control(100))
      assertTrue(attachment.state.value.ready)
      transport.listener!!.onText("🙂汉")
      assertEquals(103L, attachment.state.value.cursor)
      assertEquals("\u001b[31mreplay🙂🙂汉", attachment.state.value.output)
      assertTrue(attachment.sendText("echo hi\r"))
      assertEquals(listOf("echo hi\r"), transport.channel.sent)
      assertFalse(attachment.state.value.inputDeliveryUnknown)
      transport.listener!!.onFailure(IllegalStateException("ticket must not leak"))
      assertTrue(attachment.state.value.inputDeliveryUnknown)
      assertFalse(attachment.state.value.error!!.contains("ticket must not leak"))
      assertTrue(attachment.state.value.ended)
    } finally { api.close() }
  }

  @Test fun malformedDuplicateAndUnsafeControlFramesFailClosed() = runBlocking {
    val frames = listOf(
      byteArrayOf(), "not-control".toByteArray(), byteArrayOf(0, 0xc3.toByte()),
      byteArrayOf(0) + "{\"cursor\":-1}".toByteArray(),
      byteArrayOf(0) + "{\"cursor\":1.5}".toByteArray(),
      byteArrayOf(0) + "{\"cursor\":\"1\"}".toByteArray(),
      byteArrayOf(0) + "{\"cursor\":9007199254740992}".toByteArray()
    )
    for (frame in frames) {
      val transport = FakeTransport()
      val api = api(transport)
      try {
        val attachment = api.attachTerminal("/repo", "pty_1")
        transport.listener!!.onBytes(frame)
        assertTrue(attachment.state.value.ended)
        assertNotNull(attachment.state.value.error)
        assertTrue(transport.channel.cancelled)
      } finally { api.close() }
    }
    val transport = FakeTransport()
    val api = api(transport)
    try {
      val attachment = api.attachTerminal("/repo", "pty_1")
      transport.listener!!.onBytes(control(1))
      transport.listener!!.onBytes(control(1))
      assertTrue(attachment.state.value.ended)
    } finally { api.close() }
  }

  @Test fun outputAndInputBudgetsAreUtf8BoundedWithExplicitTruncation() = runBlocking {
    val transport = FakeTransport()
    val api = api(transport)
    try {
      val attachment = api.attachTerminal("/repo", "pty_1")
      transport.listener!!.onOpen(transport.channel)
      transport.listener!!.onBytes(control(0))
      transport.listener!!.onText("🙂".repeat(100_000))
      val state = attachment.state.value
      assertTrue(state.truncated)
      assertTrue(state.output.toByteArray().size <= 256 * 1024)
      assertFalse(state.output.first().isLowSurrogate())
      assertEquals(200_000L, state.cursor)
      assertFalse(attachment.sendText("汉".repeat(6000)))
      assertTrue(transport.channel.sent.isEmpty())
      assertFalse(attachment.state.value.inputDeliveryUnknown)
      transport.channel.acceptInput = false
      assertFalse(attachment.sendText("x"))
      assertTrue(attachment.state.value.inputDeliveryUnknown)
      assertTrue(attachment.state.value.ended)
      assertEquals(listOf("x"), transport.channel.sent)
    } finally { api.close() }
  }

  @Test fun earlyFramesAndCloseBeforeLateOpenCannotResurrectAttachment() = runBlocking {
    val transport = FakeTransport()
    transport.onSocket = { listener, channel ->
      listener.onOpen(channel)
      listener.onText("early")
      listener.onBytes(control(5))
    }
    val api = api(transport)
    val attachment = api.attachTerminal("/repo", "pty_1")
    assertTrue(attachment.state.value.ready)
    assertTrue(attachment.sendText("x"))
    attachment.close()
    transport.listener!!.onOpen(transport.channel)
    transport.listener!!.onText("ignored")
    assertEquals("early", attachment.state.value.output)
    assertFalse(attachment.state.value.connected)
    assertTrue(transport.channel.cancelled)
    api.close()
    assertTrue(transport.cancelledGenerations.isNotEmpty())
    assertFalse(transport.calls.any { it.method == "DELETE" })
  }

  @Test fun inputAndSerializedOutputDoNotInvertCarrierAndAttachmentLocks() = runBlocking {
    val transport = FakeTransport()
    val api = api(transport)
    val attachment = api.attachTerminal("/repo", "pty_1")
    transport.listener!!.onOpen(transport.channel)
    transport.listener!!.onBytes(control(0))
    val carrierGate = Any()
    val outputOwnsGate = CountDownLatch(1)
    val sendEntered = CountDownLatch(1)
    val outputDone = CountDownLatch(1)
    val inputDone = CountDownLatch(1)
    val accepted = AtomicBoolean()
    transport.channel.sendHook = {
      sendEntered.countDown()
      synchronized(carrierGate) { true }
    }
    // Real NativeOkHttp/Remote carriers serialize callbacks under their gate, and send enters it.
    // Signal at send entry so an attachment lock held across send would deterministically deadlock.
    val output = Thread({
      synchronized(carrierGate) {
        outputOwnsGate.countDown()
        if (sendEntered.await(2, TimeUnit.SECONDS)) transport.listener!!.onText("concurrent output")
      }
      outputDone.countDown()
    }, "terminal-output-lock-regression").apply { isDaemon = true }
    val input = Thread({ accepted.set(attachment.sendText("input\r")); inputDone.countDown() },
      "terminal-input-lock-regression").apply { isDaemon = true }
    output.start(); assertTrue(outputOwnsGate.await(2, TimeUnit.SECONDS)); input.start()
    val completed = outputDone.await(2, TimeUnit.SECONDS) && inputDone.await(2, TimeUnit.SECONDS)
    if (completed) { attachment.close(); api.close() }
    assertTrue("carrier callback and terminal input must both finish", completed)
    assertTrue(accepted.get()); assertTrue(attachment.state.value.output.contains("concurrent output"))
    assertEquals(listOf("input\r"), transport.channel.sent)
  }

  @Test fun ownerCloseDuringTokenRequestFencesLateResponseBeforeWebsocket() = runBlocking {
    val transport = FakeTransport()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    transport.beforeResponse = { entered.complete(Unit); release.await() }
    val api = api(transport)
    val pending = async { runCatching { api.attachTerminal("/repo", "pty_1") } }
    entered.await()
    api.close()
    release.complete(Unit)
    assertTrue(pending.await().isFailure)
    assertNull(transport.listener)
  }

  @Test fun traversalAndInvalidDimensionsNeverReachTransport() = runBlocking {
    val transport = FakeTransport()
    val api = api(transport)
    try {
      for (id in listOf("..", "../other", "%2e%2e", "a/b", "a\\b", ""))
        assertTrue(runCatching { api.attachTerminal("/repo", id) }.exceptionOrNull() is IllegalArgumentException)
      assertTrue(runCatching { api.resizeTerminal("/repo", "pty_1", 0, 24) }.isFailure)
      assertTrue(runCatching { api.resizeTerminal("/repo", "pty_1", 2049, 24) }.isFailure)
      assertTrue(transport.calls.isEmpty())
    } finally { api.close() }
  }

  @Test fun discoveryRequiresExactCompatibleRoutesAndDocumentedTicketHeader() {
    val document = terminalDocument()
    assertTrue(ApiCapabilities.fromDocument(document).terminal)
    assertFalse(ApiCapabilities.BASELINE.terminal)
    assertFalse(ApiCapabilities().terminal)
    for (route in listOf("/api/pty", "/api/pty/{ptyID}", "/api/pty/{ptyID}/connect-token", "/api/pty/{ptyID}/connect")) {
      val missing = JSONObject(document.toString())
      missing.getJSONObject("paths").remove(route)
      assertFalse(route, ApiCapabilities.fromDocument(missing).terminal)
    }
    val noHeader = terminalDocument()
    noHeader.getJSONObject("paths").getJSONObject("/api/pty/{ptyID}/connect-token").getJSONObject("post").remove("parameters")
    assertFalse(ApiCapabilities.fromDocument(noHeader).terminal)
    val requiredCommand = terminalDocument()
    requiredCommand.getJSONObject("paths").getJSONObject("/api/pty").getJSONObject("post")
      .put("requestBody", body(JSONObject().put("type", "object").put("required", JSONArray().put("command"))))
    assertFalse(ApiCapabilities.fromDocument(requiredCommand).terminal)
  }

  private class FakeTransport : NativeHttpTransport {
    override val endpoint = NativeHttpEndpoint("https://example.test/opencode")
    data class Call(val method: String, val path: String, val headers: Map<String, String>, val body: String?)
    val calls = mutableListOf<Call>()
    var response = envelope(info())
    var status = 200
    var beforeResponse: (suspend () -> Unit)? = null
    var listener: NativeWebSocketListener? = null
    var wsPath: String? = null
    var wsHeaders = emptyMap<String, String>()
    val channel = Channel()
    var onSocket: ((NativeWebSocketListener, Channel) -> Unit)? = null
    val cancelledGenerations = mutableListOf<Long>()
    override suspend fun request(method: String, relativePath: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, generation: Long): NativeHttpResponse {
      endpoint.resolve(relativePath)
      calls += Call(method, relativePath, headers, body?.toString(Charsets.UTF_8))
      beforeResponse?.invoke()
      val payload = if (status == 204) byteArrayOf() else if (relativePath.substringBefore('?').endsWith("/connect-token"))
        envelope(JSONObject().put("ticket", "single/use+ticket").put("expires_in", 30)).toString().toByteArray()
        else response.toString().toByteArray()
      return NativeHttpResponse(status, emptyMap(), payload, generation)
    }
    override fun openWebSocket(relativePath: String, headers: Map<String, String>, generation: Long, listener: NativeWebSocketListener): NativeWebSocketChannel {
      endpoint.resolve(relativePath)
      this.listener = listener
      wsPath = relativePath
      wsHeaders = headers
      onSocket?.invoke(listener, channel)
      return channel
    }
    override fun openEventStream(relativePath: String, headers: Map<String, String>, generation: Long, callback: NativeHttpEventStreamCallback): NativeHttpEventStream = error("not used")
    override fun cancelGeneration(generation: Long) { cancelledGenerations += generation }
  }

  private class Channel : NativeWebSocketChannel {
    override val generation = 1L
    val sent = mutableListOf<String>()
    var acceptInput = true
    var cancelled = false
    var sendHook: (() -> Boolean)? = null
    override fun sendText(text: String): Boolean { sent += text; return sendHook?.invoke() ?: acceptInput }
    override fun sendBytes(bytes: ByteArray): Boolean = error("input must be raw text")
    override fun close(code: Int, reason: String?): Boolean { cancel(); return true }
    override fun cancel() { cancelled = true }
  }

  companion object {
    private fun api(transport: FakeTransport) = OpenCodeApi(
      ServerProfile("fake", "Fake", "https://example.test/opencode", "opencode"),
      ServerCredentials("opencode", "secret", "session=cookie"), transport = transport)
    private fun info() = JSONObject().put("id", "pty_1").put("title", "Terminal")
      .put("cwd", "/repo/subdir").put("status", "running").put("command", "/bin/sh")
      .put("args", JSONArray().put("-l")).put("pid", 123)
    private fun envelope(data: Any) = JSONObject().put("location", JSONObject().put("directory", "/repo")).put("data", data)
    private fun control(cursor: Long) = byteArrayOf(0) + "{\"cursor\":$cursor}".toByteArray()
    private fun body(schema: JSONObject) = JSONObject().put("content", JSONObject().put("application/json", JSONObject().put("schema", schema)))
    private fun terminalDocument(): JSONObject {
      val size = JSONObject().put("type", "object").put("properties", JSONObject()
        .put("cols", JSONObject().put("type", "number")).put("rows", JSONObject().put("type", "number")))
      val update = JSONObject().put("type", "object").put("properties", JSONObject()
        .put("title", JSONObject().put("type", "string")).put("size", size))
      return JSONObject().put("paths", JSONObject()
        .put("/api/pty", JSONObject().put("get", JSONObject()).put("post", JSONObject()))
        .put("/api/pty/{ptyID}", JSONObject().put("get", JSONObject()).put("delete", JSONObject())
          .put("put", JSONObject().put("requestBody", body(update))))
        .put("/api/pty/{ptyID}/connect-token", JSONObject().put("post", JSONObject().put("parameters", JSONArray().put(JSONObject()
          .put("in", "header").put("name", "x-opencode-ticket").put("schema", JSONObject().put("type", "string"))))))
        .put("/api/pty/{ptyID}/connect", JSONObject().put("get", JSONObject().put("x-websocket", true))))
    }
  }
}
