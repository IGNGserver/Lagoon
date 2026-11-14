package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class RemotePhase4ProtocolTest {
  private class MemoryDeviceStore(private var record: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String) = record
    override fun save(slot: String, record: RemoteDeviceRecord) { this.record = record }
    override fun delete(slot: String) = Unit
  }

  private class MemoryTrustStore(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }

  private fun newSession(host: FakeRemoteHost, suffix: String = UUID.randomUUID().toString()): RemoteSession {
    val account = host.accountEmail()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    val record = RemoteDeviceRecord(
      canonicalRemoteOrigin(host.url),
      account,
      RemoteIdentity.generate(),
      RemoteTokenPair(token, Long.MAX_VALUE / 2, token, Long.MAX_VALUE / 2)
    )
    val devices = RemoteDeviceManager(MemoryDeviceStore(record), "phase4-$suffix", host.url, account)
    return RemoteSession(host.url, true, host.hostId, devices, RemotePeerTrust(MemoryTrustStore(host.hostIdentity.publicKey)), account)
  }

  @Test fun stockAgyOpenCodeAcpAndPhase4bCapabilitiesAreRecognized() {
    val stockAgy = RemoteCapabilities.parse(JSONObject("""
      {
        "capabilities": ["agent.acp.v1", "agent.acp.antigravity.v1"],
        "workspaceTypes": [
          {"id": "antigravity", "name": "Antigravity", "capability": "agent.acp.antigravity.v1", "available": true}
        ]
      }
    """))
    assertTrue(stockAgy.hasAgy)
    assertFalse(stockAgy.hasOpenCodeAcp)
    assertFalse(stockAgy.hasAsyncCompaction)

    val openCodeAndExtensions = RemoteCapabilities.parse(JSONObject("""
      {
        "capabilities": [
          "harness.remote.v3",
          "agent.acp.v1",
          "agent.acp.opencode.v1",
          "remote.compaction.async.v1",
          "remote.session.events.v1",
          "workspace.upload.v1",
          "workspace.upload.binary.v1"
        ],
        "workspaceTypes": [
          {"id": "opencode", "name": "OpenCode", "capability": "agent.acp.opencode.v1", "available": true}
        ]
      }
    """))
    assertTrue(openCodeAndExtensions.hasOpenCodeAcp)
    assertTrue(openCodeAndExtensions.hasAsyncCompaction)
    assertTrue(openCodeAndExtensions.hasSessionEvents)
    assertTrue(openCodeAndExtensions.supportsWorkspaceUpload)
    assertTrue(openCodeAndExtensions.supportsBinaryWorkspaceUpload)

    val binaryWithoutUpload = RemoteCapabilities.parse(JSONObject("""
      {"capabilities": ["harness.remote.v3", "workspace.upload.binary.v1"]}
    """))
    assertFalse(binaryWithoutUpload.supportsWorkspaceUpload)
    assertFalse(binaryWithoutUpload.supportsBinaryWorkspaceUpload)
  }

  @Test fun openCodeAcpGraphRoundTripsWithoutVersionBumpAndIsolatesSlots() {
    val hostId = java.util.UUID.randomUUID().toString()
    val opencode = ServerProfile(
      "oc-acp", "OpenCode ACP", "https://hub.example/",
      kind = ServerKind.CODEX, remoteHostId = hostId, protocolId = HarnessProtocolId.OPENCODE_ACP
    )
    val agy = ServerProfile(
      "agy-acp", "AGY", "https://hub.example/",
      kind = ServerKind.CODEX, remoteHostId = hostId, protocolId = HarnessProtocolId.ANTIGRAVITY_ACP
    )
    val codex = ServerProfile(
      "codex-app", "Codex", "https://hub.example/",
      kind = ServerKind.CODEX, remoteHostId = hostId
    )
    val graph = MigrationProjection.fromProfiles(listOf(opencode, agy, codex))
    val encoded = ConnectionGraphCodec.encode(graph)
    assertEquals(1, ConnectionGraphCodec.VERSION)
    val decoded = ConnectionGraphCodec.decode(encoded)
    assertEquals(graph, decoded)
    assertEquals(HarnessProtocolId.OPENCODE_ACP, decoded.bindings[0].protocolId)
    assertEquals(HarnessProtocolId.CODEX_APP_SERVER, decoded.bindings[2].protocolId)
    assertEquals("opencode-acp", JSONObject(encoded).getJSONArray("bindings").getJSONObject(0).getJSONObject("legacyProfile").getString("kind"))
    assertEquals(HarnessProtocolId.OPENCODE_ACP, MigrationProjection.storedProtocol("opencode-acp", null))
    assertTrue(runCatching { MigrationProjection.storedProtocol("opencode-acp", "antigravity-acp") }.isFailure)
    assertEquals(3, setOf(DeviceProjection.slotKey(opencode), DeviceProjection.slotKey(agy), DeviceProjection.slotKey(codex)).size)
  }

  @Test fun openCodeAcpTransportUsesGlobalListLimit500OpenCodeScopeAndBackendFilter() = runBlocking {
    val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    val items = JSONArray().apply {
      repeat(105) { i ->
        put(JSONObject().put("conversationId", "oc-$i").put("cwd", "/repo").put("title", "Session $i"))
      }
    }
    lateinit var registry: RemoteAgyRegistry
    registry = RemoteAgyRegistry(transfer = true, files = true, terminal = true, backendId = "opencode") { method, params ->
      calls += method to JSONObject(params.toString())
      when (method) {
        "agent.acp.call" -> when (params.optString("method")) {
          "initialize" -> JSONObject().put("protocolVersion", 1).put("backend", "opencode")
          "dsh/sessionList" -> JSONObject().put("items", items)
          "dsh/sessionHistory" -> JSONObject().put("events", JSONArray())
          "dsh/toolCall" -> {
            val inner = params.getJSONObject("params")
            when (inner.optString("endpoint")) {
              "workspaceFiles/list" -> JSONObject().put("path", ".").put("entries", JSONArray()).put("truncated", false)
              else -> JSONObject()
            }
          }
          else -> JSONObject()
        }
        "agent.acp.transfer.commit" -> JSONObject().put("kind", "inline").put("response", JSONObject().put("events", JSONArray()))
        "agent.acp.stream.open" -> JSONObject().put("opened", true)
        else -> JSONObject()
      }
    }
    val api = AgyApi(registry.acquire(), providerId = "opencode", healthLabel = "OpenCode ACP")
    try {
      assertEquals("OpenCode ACP", api.health())
      val page = api.rootSessionsPage()
      assertEquals(105, page.items.size)
      val listCall = calls.last { it.first == "agent.acp.call" && it.second.optString("method") == "dsh/sessionList" }
      val listParams = listCall.second.getJSONObject("params")
      assertEquals("opencode", listParams.getString("backend"))
      assertEquals("", listParams.getString("path"))
      assertEquals(500, listParams.getInt("limit"))

      api.files("/repo", ".", Session("oc-0", "/repo", "OC", 0))
      val fileCall = calls.last {
        it.first == "agent.acp.call" && it.second.optString("method") == "dsh/toolCall"
      }.second.getJSONObject("params")
      assertEquals("opencode", fileCall.getString("backend"))
      assertEquals("opencode:oc-0", fileCall.getJSONObject("args").getString("workspaceFileScopeId"))

      api.messagesPage("oc-0")
      val streamId = calls.first { it.first == "agent.acp.stream.open" }.second.getString("streamId")
      val eventCollector = async { api.events().first { it.type == "session.execution.succeeded" } }
      delay(50)
      // Ignore another backend's frame even if multiplexed
      registry.event(JSONObject().put("event", "agent.acp.frame").put("data", JSONObject()
        .put("streamId", streamId).put("backend", "antigravity")
        .put("frame", JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", "oc-0")
          .put("update", JSONObject().put("sessionUpdate", "agent_message_chunk"))))))
      // Matching backend's completion frame should complete
      registry.event(JSONObject().put("event", "agent.acp.frame").put("data", JSONObject()
        .put("streamId", streamId).put("backend", "opencode")
        .put("frame", JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", "oc-0")
          .put("update", JSONObject().put("sessionUpdate", "prompt_completed").put("stopReason", "end_turn"))))))
      val event = withTimeout(2_000) { eventCollector.await() }
      assertEquals("session.execution.succeeded", event.type)
    } finally {
      api.close()
      registry.close()
    }
  }

  @Test fun relayAdvertisesHarnessApiV1RepliesPongOnDataChannelWithoutRefreshingTrafficAndMapsCloseCodes() = runBlocking {
    val client = RemoteIdentity.generate()
    val host = RemoteIdentity.generate()
    val helloCaps = CompletableDeferred<String>()
    val dataPong = CompletableDeferred<String>()
    val controlGotDataPong = AtomicInteger(0)
    val connectionId = UUID.randomUUID().toString()
    val server = MockWebServer()
    val handshakeSent = CompletableDeferred<Unit>()
    val dataWsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = JSONObject(text)
        when (frame.optString("type")) {
          "hello" -> {
            helloCaps.complete(frame.getJSONObject("payload").getJSONArray("capabilities").toString())
            webSocket.send(JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", "hello.ack")
              .put("timestamp", System.currentTimeMillis())
              .put("payload", JSONObject().put("protocol", 1)
                .put("capabilities", JSONArray().put("transport.relay").put("transport.relay.attach").put("harness.api.v1"))
                .put("maxControlFrameBytes", 65536).put("maxRelayFrameBytes", 1048576)).toString())
          }
          "connect.request" -> webSocket.send(JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", "connect.accepted")
            .put("timestamp", System.currentTimeMillis())
            .put("payload", JSONObject().put("connectionId", connectionId)
              .put("relayEndpoint", server.url("/relay/v1").toString())
              .put("relayTicket", "ticket-1").put("relayEpoch", 3)).toString())
          "secure.handshake" -> handshakeSent.complete(Unit)
          "pong" -> controlGotDataPong.incrementAndGet()
        }
      }
    }))
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = JSONObject(text)
        when (frame.optString("type")) {
          "relay.attach" -> {
            dataWsRef.set(webSocket)
            webSocket.send(JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", "relay.ready")
              .put("timestamp", System.currentTimeMillis())
              .put("payload", JSONObject().put("connectionId", connectionId).put("epoch", 3)).toString())
          }
          "pong" -> dataPong.complete(frame.getJSONObject("payload").optString("nonce"))
        }
      }
    }))
    server.use {
      server.start()
      val relay = RemoteRelay(server.url("/").toString(), client, "device-access-token-123", host.deviceId, host.publicKey, true, {}, {})
      val connectJob = launch { runCatching { relay.connect(2_000) } }
      try {
        val caps = withTimeout(2_000) { helloCaps.await() }
        assertTrue(caps.contains("harness.api.v1"))
        withTimeout(2_000) { handshakeSent.await() }
        delay(40)
        val beforePing = relay.lastTrafficAt
        assertTrue(beforePing > 0L)
        delay(25)
        dataWsRef.get()!!.send(JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", "ping")
          .put("timestamp", System.currentTimeMillis())
          .put("payload", JSONObject().put("nonce", "data-ping-9")).toString())
        assertEquals("data-ping-9", withTimeout(2_000) { dataPong.await() })
        assertEquals(0, controlGotDataPong.get())
        // Ping/pong must never refresh lastTrafficAt.
        assertEquals(beforePing, relay.lastTrafficAt)
      } finally {
        connectJob.cancel()
        relay.close()
      }
    }

    val revoked4004 = classifyRemoteWsClose(4004, "") as RemoteControlException
    assertEquals("DEVICE_REVOKED", revoked4004.code)
    assertFalse(revoked4004.retryable)

    val connFailed4001 = classifyRemoteWsClose(4001, "CONNECTION_FAILED") as RemoteControlException
    assertEquals("CONNECTION_FAILED", connFailed4001.code)
    assertTrue(connFailed4001.retryable)

    for (code in listOf(4009, 4001, 1013)) {
      val shutdown = classifyRemoteWsClose(code, "server shutdown") as RemoteControlException
      assertEquals("SERVER_SHUTDOWN", shutdown.code)
      assertTrue(shutdown.retryable)
    }

    val hbTimeout = classifyRemoteWsClose(4009, "heartbeat timeout") as RemoteControlException
    assertEquals("HEARTBEAT_TIMEOUT", hbTimeout.code)
    assertTrue(hbTimeout.retryable)
  }

  @Test fun binaryWorkspaceUploadUsesDshupFramesAndEnforcesRelay20MibCap() = runBlocking {
    val openEncodings = CopyOnWriteArrayList<String>()
    val chunkParams = CopyOnWriteArrayList<JSONObject>()
    val reassembled = ByteArrayOutputStream()
    FakeRemoteHost(
      capabilities = listOf("harness.remote.v3", "workspace.upload.v1", "workspace.upload.binary.v1")
    ).use { host ->
      host.topLevelRpc = { method, params, binaryChunk ->
        when (method) {
          "workspace.upload.open" -> {
            openEncodings += params.optString("encoding")
            JSONObject().put("uploadId", params.getString("uploadId")).put("encoding", "binary-v1").put("chunkBytes", 262_144)
          }
          "workspace.upload.chunk.binary" -> {
            assertFalse("Binary DSHUP header must not include base64 data", params.has("data"))
            chunkParams += JSONObject(params.toString())
            val bytes = requireNotNull(binaryChunk) { "Expected raw binary slice" }
            assertEquals(reassembled.size(), params.getInt("offset"))
            reassembled.write(bytes)
            JSONObject().put("uploadId", params.getString("uploadId")).put("receivedBytes", reassembled.size())
          }
          "workspace.upload.commit" -> JSONObject().put("name", "bundle.bin").put("path", "./bundle.bin").put("bytes", reassembled.size())
          else -> JSONObject().put("ok", true)
        }
      }
      host.start()
      val session = newSession(host)
      try {
        val payload = ByteArray(300_000) { ((it * 31) and 0xFF).toByte() }
        val uploaded = session.uploadWorkspaceFile("s1", payload, "bundle.bin")
        assertEquals("bundle.bin", uploaded.name)
        assertEquals("./bundle.bin", uploaded.path)
        assertEquals(payload.size.toLong(), uploaded.bytes)
        assertEquals(listOf("binary-v1"), openEncodings)
        assertEquals(2, chunkParams.size)
        assertArrayEquals(payload, reassembled.toByteArray())

        val tooLargeForRelay = ByteArray(20 * 1024 * 1024 + 1)
        val rejection = runCatching { session.uploadWorkspaceFile("s1", tooLargeForRelay, "huge.bin") }.exceptionOrNull()
        assertTrue(rejection is RemoteFailureException)
        assertEquals(RemoteFailureKind.REJECTED, (rejection as RemoteFailureException).kind)
      } finally {
        session.close()
      }
    }
  }

  @Test fun asyncCompactionSubmitsPollsAcceptsUnconfirmedAndCancelsOnCoroutineCancel() = runBlocking {
    val calls = CopyOnWriteArrayList<String>()
    var nextTerminal = "succeeded"
    var pollsRemaining = 1
    FakeRemoteHost(
      capabilities = listOf("harness.remote.v3", "remote.compaction.async.v1")
    ).use { host ->
      host.topLevelRpc = { method, params, _ ->
        calls += method
        when (method) {
          "harness.remote.compaction.submit" -> {
            assertEquals("s-compact", params.getString("sessionId"))
            assertTrue(params.getString("requestId").isNotBlank())
            JSONObject().put("operationId", "op-1").put("state", "accepted")
          }
          "harness.remote.compaction.status" -> {
            val state = if (pollsRemaining-- > 0) "running" else nextTerminal
            JSONObject().put("operationId", "op-1").put("sessionId", "s-compact").put("state", state)
          }
          "harness.remote.compaction.cancel" -> JSONObject().put("operationId", "op-1").put("state", "cancelling")
          else -> JSONObject().put("ok", true)
        }
      }
      host.start()
      val session = newSession(host)
      val backend = RemoteBackend(session)
      val dsh = backend.client as DshApi
      try {
        val target = Session("s-compact", "/repo", "Compact", 0)
        dsh.compact(target)
        assertTrue(calls.contains("harness.remote.compaction.submit"))
        assertTrue(calls.count { it == "harness.remote.compaction.status" } >= 2)

        calls.clear()
        nextTerminal = "unconfirmed"
        pollsRemaining = 0
        dsh.compact(target)
        assertEquals(listOf("harness.remote.compaction.submit", "harness.remote.compaction.status"), calls.toList())

        calls.clear()
        pollsRemaining = 100
        val job = launch {
          session.compactSessionAsync("s-compact")
        }
        while (!calls.contains("harness.remote.compaction.submit")) delay(20)
        job.cancel()
        job.join()
        assertTrue(calls.contains("harness.remote.compaction.cancel"))
      } finally {
        backend.close()
      }
    }
  }

  @Test fun remoteSendTriesFileUploadsReceiptFirstAndFallsBackToWorkspaceUpload() = runBlocking {
    var failReceiptWithUnsupported = false
    val calledEndpoints = CopyOnWriteArrayList<String>()
    val uploadedPaths = CopyOnWriteArrayList<String>()
    FakeRemoteHost(
      capabilities = listOf("harness.remote.v3", "workspace.upload.v1")
    ).use { host ->
      host.call = { endpoint, payload ->
        calledEndpoints += endpoint
        when (endpoint) {
          "fileUploads/upload" -> if (failReceiptWithUnsupported) {
            host.fail("METHOD_NOT_ALLOWED", "no fileUploads")
          } else {
            assertEquals("s-send", payload.getJSONObject("args").getString("agentId"))
            host.ok(JSONObject().put("receiptId", "rcpt-42"))
          }
          "session/prompt" -> {
            val content = payload.getJSONObject("args").getJSONObject("request").getJSONArray("content")
            uploadedPaths += content.toString()
            host.ok(JSONObject().put("accepted", true))
          }
          else -> host.ok(JSONObject())
        }
      }
      host.topLevelRpc = { method, params, _ ->
        when (method) {
          "workspace.upload.open" -> JSONObject().put("uploadId", params.getString("uploadId")).put("chunkBytes", 65_536)
          "workspace.upload.chunk" -> JSONObject().put("uploadId", params.getString("uploadId"))
            .put("receivedBytes", java.util.Base64.getDecoder().decode(params.getString("data")).size + params.getInt("offset"))
          "workspace.upload.commit" -> JSONObject().put("name", "memo.txt").put("path", "./memo.txt").put("bytes", 5)
          else -> JSONObject().put("ok", true)
        }
      }
      host.start()
      val session = newSession(host)
      val backend = RemoteBackend(session)
      val dsh = backend.client as DshApi
      try {
        val file = InlineFile("memo.txt", "text/plain", AttachmentPolicy.dataUri("text/plain", "hello".toByteArray()))
        val target = Session("s-send", "/repo", "Send", 0)

        dsh.send(target, "first", null, null, inline = listOf(file))
        assertTrue(calledEndpoints.contains("fileUploads/upload"))
        assertTrue(uploadedPaths.last().contains("rcpt-42"))
        assertFalse(uploadedPaths.last().contains("./memo.txt"))

        failReceiptWithUnsupported = true
        dsh.send(target, "fallback", null, null, inline = listOf(file))
        assertTrue(uploadedPaths.last().contains("./memo.txt"))
      } finally {
        backend.close()
      }
    }
  }

  @Test fun remoteCodexReadRetriesResponseTooLargeViaTransferOnceAndAuthCapsFallback405And501() = runBlocking {
    val methods = CopyOnWriteArrayList<String>()
    val transport = RemoteCodexTransport(transferSupported = true, rpc = { method, _ ->
      methods += method
      when (method) {
        "codex.app.call" -> throw RemoteRpcException("RESPONSE_TOO_LARGE", retryable = true)
        "codex.app.transfer.commit" -> JSONObject().put("kind", "inline").put("response", JSONObject().put("data", JSONArray()))
        else -> JSONObject()
      }
    })
    try {
      val result = transport.call("model/list", JSONObject())
      assertEquals(0, result.getJSONArray("data").length())
      assertEquals(1, methods.count { it == "codex.app.call" })
      assertEquals(1, methods.count { it == "codex.app.transfer.commit" })
    } finally {
      transport.close()
    }

    MockWebServer().use { mock ->
      mock.enqueue(MockResponse().setResponseCode(405).setBody("Method Not Allowed"))
      mock.enqueue(MockResponse().setResponseCode(501).setBody("Not Implemented"))
      mock.start()
      val serverApi = RemoteServerApi(mock.url("/").toString(), allowCleartext = true)
      val caps405 = serverApi.authCapabilities()
      assertTrue(caps405.providers.isEmpty())
      assertFalse(caps405.devicePairing)
      val caps501 = serverApi.authCapabilities()
      assertTrue(caps501.providers.isEmpty())
      assertFalse(caps501.devicePairing)
    }
  }

  @Test fun coreStreamsEvictWatchedFollowsAndBoundJobFollowUnder16StreamCap() = runBlocking {
    val ids = (1..20).map { "s%02d".format(it) }
    FakeRemoteHost().use { host ->
      host.call = { endpoint, _ ->
        when (endpoint) {
          "session/list" -> host.ok(JSONObject().put("items", JSONArray().apply {
            ids.forEach { id ->
              put(JSONObject().put("sessionId", id).put("updatedAt", 100)
                .put("running", true).put("cwd", "/repo"))
            }
          }))
          "session/page" -> host.ok(JSONObject().put("records", JSONArray()).put("hasMore", false))
          else -> host.ok(JSONObject())
        }
      }
      host.streamFrames = { endpoint, payload ->
        when (endpoint) {
          "session/follow" -> {
            val sid = payload.optJSONObject("args")?.optJSONObject("address")?.optString("sessionId").orEmpty().ifBlank { "s01" }
            listOf(JSONObject().put("type", "snapshot")
              .put("header", JSONObject().put("version", 4).put("id", sid).put("createdAt", 1).put("cwd", "/repo").put("agentPreset", "standard"))
              .put("cursor", 0).put("records", JSONArray()).put("hasMore", false)
              .put("projections", JSONObject().put("asOfSeq", 0).put("values", JSONObject())))
          }
          else -> emptyList()
        }
      }
      host.start()
      val session = newSession(host, "stream-cap")
      val backend = RemoteBackend(session)
      val jobFollows = mutableListOf<kotlinx.coroutines.Job>()
      try {
        repeat(12) { idx ->
          jobFollows += launch { runCatching { backend.client.followJobOutput("s01", "job-$idx") { _, _, _ -> } } }
        }
        delay(350)
        val initialJobOpens = synchronized(host.streamOpens) { host.streamOpens.count { it.optString("endpoint") == "job/follow" } }
        assertTrue("Expected concurrent job/follow opens bounded to <= ${DshApi.JOB_FOLLOW_CONCURRENCY}, got $initialJobOpens",
          initialJobOpens in 1..DshApi.JOB_FOLLOW_CONCURRENCY)

        backend.client.rootSessionsPage()
        val collector = launch { backend.client.events {}.collect { } }
        withTimeout(5_000) {
          while (true) {
            val opens = synchronized(host.streamOpens) { host.streamOpens.map { it.optString("endpoint") } }
            if ("\$events" in opens && "workspace/follow" in opens) break
            delay(50)
          }
        }
        backend.client.rootSessionsPage()
        delay(400)
        val stats = backend.streamStats()
        assertNotNull(stats)
        assertEquals(16, stats!!.limit)
        assertTrue("Active streams (${stats.active}) exceeded cap ${stats.limit}", stats.active <= stats.limit)
        assertTrue("Active job/follow streams (${stats.byEndpoint["job/follow"] ?: 0}) exceeded ${DshApi.JOB_FOLLOW_CONCURRENCY}",
          (stats.byEndpoint["job/follow"] ?: 0) <= DshApi.JOB_FOLLOW_CONCURRENCY)
        val follows = (backend.client as DshApi).followDiagnostics()
        assertTrue("Remote watched follows (${follows.size}) exceeded ${DshApi.REMOTE_WATCH_LIMIT}",
          follows.size <= DshApi.REMOTE_WATCH_LIMIT)
        assertNull("Opening last session must succeed under 16-stream cap",
          runCatching { backend.client.messagesPage(ids.last()) }.exceptionOrNull())
        collector.cancel()
        collector.join()
      } finally {
        jobFollows.forEach { it.cancel() }
        backend.close()
        session.close()
      }
    }
  }
}
