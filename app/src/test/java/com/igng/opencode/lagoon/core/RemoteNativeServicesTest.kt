package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RemoteNativeServicesTest {
  @Test fun catalogRetainsUnknownProtocolsAndExplicitAvailabilityAndCapabilities() = runBlocking {
    val fake = FakeRpc()
    fake.catalog = catalog(service("one"), service("future", protocol = "future-native"),
      service("off", available = false), service("limited", capabilities = listOf("sse")))
    val registry = fake.registry()
    try {
      val result = registry.describe()
      assertEquals(DiscoveryCompleteness.COMPLETE, result.completeness)
      val unknown = result.services.first { it.id == "future" }
      assertEquals("future-native", unknown.protocol)
      assertFalse(unknown.supported)
      assertNull(unknown.nativeProtocol)
      assertEquals("UNSUPPORTED_PROTOCOL", failureCode { registry.transport("future", "g1") })
      assertEquals("SERVICE_UNAVAILABLE", failureCode { registry.transport("off", "g1") })
      assertEquals("UNSUPPORTED_CAPABILITY", failureCode { registry.transport("limited", "g1") })
      assertEquals("STALE_SERVICE", failureCode { registry.transport("one", "g2") })
      val transport = registry.transport("one", "g1")
      assertEquals("https://native.remote.invalid", transport.endpoint.baseUrl)
      assertEquals("https://native.remote.invalid/api/info", transport.endpoint.resolve("/api/info"))
    } finally { registry.close() }
  }

  @Test fun catalogRejectsCoercedOrUnboundedDescriptors() = runBlocking {
    val invalid = listOf(
      catalog(service("one").put("available", "true")),
      catalog(service("one").put("capabilities", JSONObject())),
      catalog(service("one").put("generation", "x".repeat(257))),
      catalog(service("one").put("id", "https://host.invalid")),
      catalog(service("one"), service("one")),
      catalog(service("one")).put("version", "1"),
      catalog(service("one")).put("completeness", "unknown")
    )
    for (value in invalid) {
      val fake = FakeRpc().apply { catalog = value }
      val registry = fake.registry()
      try { assertTrue(runCatching { registry.describe() }.isFailure) }
      finally { registry.close() }
    }
  }

  @Test fun ptyTicketHeaderOnlyCrossesItsExactNativePostRoute() = runBlocking {
    val fake = FakeRpc()
    val registry = fake.registry()
    try {
      val transport = registry.transport("one", "g1")
      transport.request("POST", "/api/pty/pty_safe/connect-token", mapOf("X-OpenCode-Ticket" to "1"), "{}".toByteArray())
      val call = fake.calls.last { it.method == "harness.services.call" }
      assertEquals("1", call.params.getJSONObject("headers").getString("X-OpenCode-Ticket"))
      for ((method, path, value) in listOf(Triple("GET", "/api/pty/pty_safe/connect-token", "1"),
        Triple("POST", "/api/session", "1"), Triple("POST", "/api/pty/pty_safe/connect-token", "secret"))) {
        assertEquals("INVALID_HEADERS", failureCode { transport.request(method, path, mapOf("x-opencode-ticket" to value)) })
      }
    } finally { registry.close() }
  }

  @Test fun multipleServicesShareConnectionWithoutCredentialOrOriginLeaks() = runBlocking {
    val fake = FakeRpc().apply { catalog = catalog(service("one"), service("two", generation = "g2")) }
    val leases = AtomicInteger()
    val registry = fake.registry { leases.incrementAndGet() }
    try {
      val one = registry.transport("one", "g1")
      val two = registry.transport("two", "g2")
      for (name in listOf("Authorization", "authorization", "Cookie", "Proxy-Authorization")) {
        assertEquals("HOST_CREDENTIALS_FORBIDDEN", failureCode {
          one.request("POST", "/api/session", mapOf(name to "secret"), "{}".toByteArray())
        })
      }
      assertTrue(runCatching { one.request("GET", "https://hub.invalid/api/info") }.isFailure)
      assertTrue(fake.calls.none { it.method == "harness.services.call" })
      one.request("get", "/api/info", mapOf("Accept" to "application/json"))
      one.close()
      assertEquals(0, leases.get())
      two.request("GET", "/api/info")
      val calls = fake.calls.filter { it.method == "harness.services.call" }
      assertEquals(listOf("one", "two"), calls.map { it.params.getString("serviceId") })
      assertEquals(listOf("g1", "g2"), calls.map { it.params.getString("generation") })
      assertEquals("GET", calls.first().params.getString("method"))
      assertFalse(calls.any { it.params.toString().contains("secret") || it.params.has("url") })
      registry.close(); registry.close()
      assertEquals(1, leases.get())
      assertEquals("TRANSPORT_CLOSED", failureCode { two.request("GET", "/api/info") })
    } finally { registry.close() }
  }

  @Test fun mutationRunsOnceAndLargeResponseOnlyReadsCachedTransfer() = runBlocking {
    val fake = FakeRpc()
    val bytes = ByteArray(512 * 1024 + 23) { (it % 251).toByte() }
    val released = CompletableDeferred<Unit>()
    fake.handler = { method, params ->
      when (method) {
        "harness.services.call" -> JSONObject().put("status", 201).put("headers", JSONObject()
          .put("Content-Length", bytes.size.toString()).put("Set-Cookie", "never-deliver"))
          .put("transferId", "transfer-1").put("totalBytes", bytes.size)
        "harness.services.transfer.read" -> {
          val index = params.getInt("index")
          val offset = index * 512 * 1024
          JSONObject().put("index", index).put("totalChunks", 2).put("data", encode(
            bytes.copyOfRange(offset, minOf(offset + 512 * 1024, bytes.size))))
        }
        "harness.services.transfer.close" -> JSONObject().put("closed", true).also { released.complete(Unit) }
        else -> null
      }
    }
    val registry = fake.registry()
    try {
      val transport = registry.transport("one", "g1")
      val response = transport.request("POST", "/api/session", body = byteArrayOf(0, 1, -1),
        maxResponseBytes = bytes.size.toLong())
      assertEquals(201, response.statusCode)
      assertArrayEquals(bytes, response.body)
      assertFalse(response.headers.keys.any { it.equals("set-cookie", true) })
      await { released.await() }
      assertEquals(1, fake.calls.count { it.method == "harness.services.call" })
      assertEquals(listOf(0, 1), fake.calls.filter { it.method == "harness.services.transfer.read" }.map { it.params.getInt("index") })
      assertEquals(1, fake.calls.count { it.method == "harness.services.transfer.close" })
      assertEquals("AAH/", fake.calls.first { it.method == "harness.services.call" }.params.getString("body"))
    } finally { registry.close() }
  }

  @Test fun largeRequestUsesOrderedChunksAndCommitsOnceWithoutMutationRetry() = runBlocking {
    val fake = FakeRpc()
    val body = ByteArray(512 * 1024 + 23) { (it % 251).toByte() }
    val uploaded = java.io.ByteArrayOutputStream()
    var chunks = 0
    var commits = 0
    fake.handler = { method, params -> when (method) {
      "harness.services.transfer.open" -> {
        assertEquals(body.size, params.getInt("totalBytes"))
        assertEquals("POST", params.getString("method"))
        assertFalse(params.has("body"))
        JSONObject().put("transferId", "upload-1").put("chunkBytes", 512 * 1024)
      }
      "harness.services.transfer.chunk" -> {
        assertEquals(chunks++, params.getInt("index"))
        uploaded.write(Base64.getDecoder().decode(params.getString("data")))
        JSONObject().put("accepted", true)
      }
      "harness.services.transfer.commit" -> {
        commits++
        JSONObject().put("status", 204).put("headers", JSONObject()).put("body", "")
      }
      "harness.services.transfer.close" -> JSONObject().put("closed", true)
      else -> null
    } }
    fake.registry().use { registry ->
      val transport = registry.transport("one", "g1")
      assertEquals(204, transport.request("POST", "/api/session", body = body).statusCode)
      assertArrayEquals(body, uploaded.toByteArray())
      assertEquals(2, chunks)
      assertEquals(1, commits)
      assertEquals(0, fake.calls.count { it.method == "harness.services.call" })
      assertEquals(1, fake.calls.count { it.method == "harness.services.transfer.close" })
    }
  }

  @Test fun failedUploadCommitIsUnknownAndNeverRetriedOrSwitchedToInline() = runBlocking {
    val fake = FakeRpc()
    fake.handler = { method, _ -> when (method) {
      "harness.services.transfer.open" -> JSONObject().put("transferId", "upload-1").put("chunkBytes", 512 * 1024)
      "harness.services.transfer.chunk" -> JSONObject().put("accepted", true)
      "harness.services.transfer.commit" -> throw RemoteRpcException("RPC_TIMEOUT")
      "harness.services.transfer.close" -> JSONObject().put("closed", true)
      else -> null
    } }
    fake.registry().use { registry ->
      val transport = registry.transport("one", "g1")
      assertEquals("RPC_TIMEOUT", failureCode { transport.request("POST", "/api/session", body = ByteArray(512 * 1024 + 1)) })
      assertEquals(1, fake.calls.count { it.method == "harness.services.transfer.commit" })
      assertEquals(0, fake.calls.count { it.method == "harness.services.call" })
      assertEquals(1, fake.calls.count { it.method == "harness.services.transfer.close" })
    }
  }

  @Test fun badResponseMetadataAndTransferSequenceAreRejectedAndAlwaysReleased() = runBlocking {
    for (variant in 0..4) {
      val fake = FakeRpc()
      val released = CompletableDeferred<Unit>()
      fake.handler = { method, _ -> when (method) {
        "harness.services.call" -> JSONObject().put("status", if (variant == 0) 302 else 200)
          .put("headers", if (variant == 1) JSONObject().put("Content-Length", "9999") else JSONObject())
          .put("transferId", "transfer-1").put("totalBytes", if (variant == 2) 64L * 1024 * 1024 + 1 else 4)
        "harness.services.transfer.read" -> JSONObject().put("index", if (variant == 3) 1 else 0)
          .put("totalChunks", 1).put("data", if (variant == 4) "$$$$" else encode(byteArrayOf(1, 2, 3, 4)))
        "harness.services.transfer.close" -> JSONObject().put("closed", true).also { released.complete(Unit) }
        else -> null
      } }
      val registry = fake.registry()
      try {
        val transport = registry.transport("one", "g1")
        assertTrue(runCatching { transport.request("POST", "/api/session", maxResponseBytes = 4) }.isFailure)
        await { released.await() }
        assertEquals(1, fake.calls.count { it.method == "harness.services.call" })
        if (variant < 3) assertTrue(fake.calls.none { it.method == "harness.services.transfer.read" })
      } finally { registry.close() }
    }
  }

  @Test fun inlineBase64IsBudgetedBeforeDecodingAndRequiresCanonicalEncoding() = runBlocking {
    val fake = FakeRpc()
    var body = encode(byteArrayOf(1, 2, 3, 4))
    fake.handler = { method, _ -> if (method == "harness.services.call") response().put("body", body) else null }
    val registry = fake.registry()
    try {
      val transport = registry.transport("one", "g1")
      assertTrue(runCatching { transport.request("GET", "/api/info", maxResponseBytes = 3) }
        .exceptionOrNull() is NativeHttpResponseTooLargeException)
      body = "YQ" // RFC base64 requires padding on this wire.
      assertEquals("INVALID_BASE64", failureCode { transport.request("GET", "/api/info") })
      body = encode(ByteArray(512 * 1024 + 1))
      assertTrue(runCatching { transport.request("GET", "/api/info") }.isFailure)
    } finally { registry.close() }
  }

  @Test fun framesBeforeOpenReplyWorkAndSequenceGapOnlyStopsItsOwnStream() = runBlocking {
    val fake = FakeRpc().apply { catalog = catalog(service("one"), service("two")) }
    val openReply = CompletableDeferred<Unit>()
    val ids = Channel<Pair<String, String>>(2)
    val firstEvent = CompletableDeferred<String>()
    val failure = CompletableDeferred<Throwable>()
    val siblingEvent = CompletableDeferred<String>()
    lateinit var registry: RemoteNativeServices
    fake.handler = { method, params ->
      if (method == "harness.services.stream.open") {
        val id = params.getString("streamId")
        val service = params.getString("serviceId")
        registry.event(frame(id, 0, "open"))
        if (service == "one") registry.event(frame(id, 1, "event", "early"))
        ids.send(service to id)
        if (service == "one") openReply.await()
        JSONObject().put("streamId", id)
      } else null
    }
    registry = fake.registry()
    try {
      val one = registry.transport("one", "g1")
      val two = registry.transport("two", "g1")
      one.openEventStream("/api/event", callback = object : NativeHttpEventStreamCallback {
        override fun onEvent(event: NativeHttpEvent) { firstEvent.complete(event.data) }
        override fun onFailure(error: Throwable) { failure.complete(error) }
      })
      val firstId = await { ids.receive().second }
      assertEquals("early", await { firstEvent.await() })
      assertFalse(openReply.isCompleted)
      two.openEventStream("/api/event", callback = object : NativeHttpEventStreamCallback {
        override fun onEvent(event: NativeHttpEvent) { siblingEvent.complete(event.data) }
      })
      val secondId = await { ids.receive().second }
      registry.event(frame(firstId, 3, "event", "gap"))
      assertEquals("STREAM_SEQUENCE_GAP", (await { failure.await() } as NativeRemoteException).code)
      registry.event(frame(secondId, 1, "event", "healthy"))
      assertEquals("healthy", await { siblingEvent.await() })
      two.request("GET", "/api/info")
      Unit
    } finally { openReply.complete(Unit); registry.close() }
  }

  @Test fun websocketQueuesBinaryAndTextOnceAndReportsAsyncRejection() = runBlocking {
    val fake = FakeRpc()
    val sent = Channel<JSONObject>(4)
    val received = Channel<String>(4)
    val opened = CompletableDeferred<String>()
    val failed = CompletableDeferred<Throwable>()
    lateinit var registry: RemoteNativeServices
    fake.handler = { method, params -> when (method) {
      "harness.services.stream.open" -> {
        val id = params.getString("streamId")
        registry.event(frame(id, 0, "open"))
        opened.complete(id)
        JSONObject().put("streamId", id)
      }
      "harness.services.stream.send" -> {
        sent.send(JSONObject(params.toString()))
        if (params.optString("data") == "reject") throw RemoteRpcException("SEND_REJECTED")
        JSONObject().put("accepted", true)
      }
      else -> null
    } }
    registry = fake.registry()
    try {
      val transport = registry.transport("one", "g1")
      val socket = transport.openWebSocket("/api/socket", listener = object : NativeWebSocketListener {
        override fun onText(text: String) { received.trySend(text) }
        override fun onBytes(bytes: ByteArray) { received.trySend(encode(bytes)) }
        override fun onFailure(error: Throwable) { failed.complete(error) }
      })
      val id = await { opened.await() }
      assertTrue(socket.sendText("hello"))
      assertTrue(socket.sendBytes(byteArrayOf(0, -1)))
      assertFalse(socket.sendBytes(ByteArray(512 * 1024 + 1)))
      assertEquals("text", await { sent.receive() }.getString("type"))
      val binary = await { sent.receive() }
      assertEquals("bytes", binary.getString("type"))
      assertEquals("AP8=", binary.getString("data"))
      registry.event(frame(id, 1, "text", "hello-back"))
      registry.event(frame(id, 2, "bytes", "AP8="))
      assertEquals("hello-back", await { received.receive() })
      assertEquals("AP8=", await { received.receive() })
      assertTrue(socket.sendText("reject"))
      assertEquals("reject", await { sent.receive() }.getString("data"))
      assertEquals("SEND_REJECTED", (await { failed.await() } as NativeRemoteException).code)
      assertFalse(socket.sendText("no retry"))
      assertEquals(3, fake.calls.count { it.method == "harness.services.stream.send" })
    } finally { registry.close() }
  }

  @Test fun callerAndGenerationCancellationCancelOnlyOwnedUnaryRpcJobs() = runBlocking {
    val fake = FakeRpc().apply { catalog = catalog(service("one"), service("two")) }
    val started = Channel<Unit>(2)
    val cancelled = Channel<Unit>(2)
    fake.handler = { method, params ->
      if (method == "harness.services.call" && params.getString("path") == "/api/wait") {
        started.send(Unit)
        try { awaitCancellation() } finally { cancelled.trySend(Unit) }
      } else null
    }
    val registry = fake.registry()
    try {
      val one = registry.transport("one", "g1")
      val two = registry.transport("two", "g1")
      val caller = launch { one.request("GET", "/api/wait", generation = 7) }
      await { started.receive() }; caller.cancel(); caller.join(); await { cancelled.receive() }
      val generation = launch { one.request("GET", "/api/wait", generation = 8) }
      await { started.receive() }; one.cancelGeneration(8); generation.join(); await { cancelled.receive() }
      assertTrue(runCatching { one.request("GET", "/api/info", generation = 8) }
        .exceptionOrNull() is NativeHttpGenerationCancelledException)
      one.close()
      two.request("GET", "/api/info", generation = 8)
      assertEquals(3, fake.calls.count { it.method == "harness.services.call" })
    } finally { registry.close() }
  }

  @Test fun refreshedHostGenerationCancelsOldChildButNotSibling() = runBlocking {
    val fake = FakeRpc().apply { catalog = catalog(service("one"), service("two")) }
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    fake.handler = { method, params ->
      if (method == "harness.services.call" && params.getString("path") == "/api/wait") {
        started.complete(Unit)
        try { awaitCancellation() } finally { cancelled.complete(Unit) }
      } else null
    }
    val registry = fake.registry()
    try {
      val one = registry.transport("one", "g1")
      val two = registry.transport("two", "g1")
      val pending = launch { one.request("GET", "/api/wait") }
      await { started.await() }
      fake.catalog = catalog(service("one", generation = "g2"), service("two"))
      registry.describe()
      pending.join(); await { cancelled.await() }
      assertEquals("STALE_SERVICE", failureCode { one.request("GET", "/api/info") })
      two.request("GET", "/api/info")
      registry.transport("one", "g2").request("GET", "/api/info")
      Unit
    } finally { registry.close() }
  }

  @Test fun oldHostProducesTypedUpgradeErrorAndSafeNativeErrorsRetainCodeAndMessage() = runBlocking {
    val fake = FakeRpc()
    fake.handler = { _, _ -> throw RemoteRpcException("METHOD_NOT_FOUND") }
    val registry = fake.registry()
    try {
      val error = runCatching { registry.describe() }.exceptionOrNull()
      assertTrue(error is NativeServiceUpgradeRequiredException)
      assertEquals("METHOD_NOT_FOUND", (error as NativeRemoteException).code)
      assertTrue(error.message!!.contains("升级"))
      assertEquals(listOf("harness.services.describe"), fake.calls.map { it.method })
    } finally { registry.close() }
    val other = FakeRpc()
    other.handler = { method, _ -> if (method == "harness.services.call") JSONObject().put("error",
      JSONObject().put("code", "STALE_SERVICE").put("message", "Service generation changed")) else null }
    val current = other.registry()
    try {
      val transport = current.transport("one", "g1")
      val error = runCatching { transport.request("POST", "/api/session") }.exceptionOrNull() as NativeRemoteException
      assertEquals("STALE_SERVICE", error.code)
      assertEquals("Service generation changed", error.message)
      assertEquals(1, other.calls.count { it.method == "harness.services.call" })
      Unit
    } finally { current.close() }
  }

  @Test fun websocketGenerationCancelTerminalCloseAndQueueAcceptanceAreLocal() = runBlocking {
    val fake = FakeRpc().apply { catalog = catalog(service("one"), service("two")) }
    val openReply = CompletableDeferred<Unit>()
    val ids = Channel<String>(2)
    val closed = CompletableDeferred<Int>()
    val firstTexts = AtomicInteger()
    val siblingTexts = AtomicInteger()
    lateinit var registry: RemoteNativeServices
    fake.handler = { method, params -> if (method == "harness.services.stream.open") {
      val id = params.getString("streamId")
      registry.event(frame(id, 0, "open")); ids.send(id)
      openReply.await()
      JSONObject().put("streamId", id)
    } else null }
    registry = fake.registry()
    try {
      val one = registry.transport("one", "g1")
      val two = registry.transport("two", "g1")
      val first = one.openWebSocket("/api/socket", generation = 7, listener = object : NativeWebSocketListener {
        override fun onText(text: String) { firstTexts.incrementAndGet() }
      })
      val firstId = await { ids.receive() }
      val sibling = two.openWebSocket("/api/socket", generation = 7, listener = object : NativeWebSocketListener {
        override fun onText(text: String) { siblingTexts.incrementAndGet() }
        override fun onClosed(code: Int, reason: String?) { closed.complete(code) }
      })
      val secondId = await { ids.receive() }
      repeat(32) { assertTrue(first.sendText("queued-$it")) }
      assertFalse(first.sendText("overflow"))
      one.cancelGeneration(7)
      assertFalse(first.sendText("cancelled"))
      registry.event(frame(firstId, 1, "text", "stale"))
      registry.event(frame(secondId, 1, "text", "healthy"))
      registry.event(frame(secondId, 2, "closed").apply { getJSONObject("data").put("code", 1000) })
      assertEquals(1000, await { closed.await() })
      assertEquals(0, firstTexts.get())
      assertEquals(1, siblingTexts.get())
      assertFalse(sibling.sendText("terminal"))
      assertTrue(fake.calls.none { it.method == "harness.services.stream.send" })
    } finally { openReply.complete(Unit); registry.close() }
  }

  @Test fun blockedConsumerDoesNotBlockRpcReceiverOrOtherServiceCallback() = runBlocking {
    val fake = FakeRpc().apply { catalog = catalog(service("one"), service("two")) }
    val ids = Channel<String>(2)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val sibling = CompletableDeferred<Unit>()
    lateinit var registry: RemoteNativeServices
    fake.handler = { method, params -> if (method == "harness.services.stream.open") {
      val id = params.getString("streamId")
      registry.event(frame(id, 0, "open")); ids.send(id)
      JSONObject().put("streamId", id)
    } else null }
    registry = fake.registry()
    try {
      registry.transport("one", "g1").openEventStream("/api/event", callback = object : NativeHttpEventStreamCallback {
        override fun onEvent(event: NativeHttpEvent) { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
      })
      val first = await { ids.receive() }
      registry.transport("two", "g1").openEventStream("/api/event", callback = object : NativeHttpEventStreamCallback {
        override fun onEvent(event: NativeHttpEvent) { sibling.complete(Unit) }
      })
      val second = await { ids.receive() }
      registry.event(frame(first, 1, "event", "block"))
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      // More than the bounded receive capacity: no suspension, silent drops or global teardown.
      for (sequence in 2L..40L) registry.event(frame(first, sequence, "event", "queued"))
      registry.event(frame(second, 1, "event", "healthy"))
      await { sibling.await() }
    } finally { release.countDown(); registry.close() }
  }

  private data class Call(val method: String, val params: JSONObject)
  private class FakeRpc {
    val calls = CopyOnWriteArrayList<Call>()
    var catalog = catalog(service("one"))
    var handler: suspend (String, JSONObject) -> JSONObject? = { _, _ -> null }
    fun registry(onClose: () -> Unit = {}) = RemoteNativeServices({ method, params ->
      calls += Call(method, JSONObject(params.toString()))
      handler(method, params) ?: when (method) {
        "harness.services.describe" -> JSONObject(catalog.toString())
        "harness.services.call" -> response()
        "harness.services.stream.close", "harness.services.transfer.close" -> JSONObject().put("closed", true)
        else -> error("Unexpected RPC: $method")
      }
    }, onClose)
  }

  companion object {
    private fun service(id: String, generation: String = "g1", protocol: String = "opencode-native",
      available: Boolean = true, capabilities: List<String> = listOf("http", "binary", "sse", "websocket")) = JSONObject()
      .put("id", id).put("name", "Service $id").put("generation", generation).put("protocol", protocol)
      .put("available", available).put("capabilities", JSONArray(capabilities))
    private fun catalog(vararg services: JSONObject) = JSONObject().put("version", 1).put("completeness", "complete")
      .put("services", JSONArray(services.toList()))
    private fun response() = JSONObject().put("status", 200).put("headers", JSONObject()).put("body", encode("ok".toByteArray()))
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun frame(id: String, sequence: Long, type: String, data: String? = null) = JSONObject()
      .put("event", "harness.services.frame").put("data", JSONObject().put("streamId", id).put("seq", sequence)
        .put("type", type).apply { data?.let { put("data", it) } })
    private suspend fun <T> await(block: suspend () -> T): T = withTimeout(3_000) { block() }
    private suspend fun failureCode(block: suspend () -> Unit): String? =
      (runCatching { block() }.exceptionOrNull() as? NativeRemoteException)?.code
  }
}
