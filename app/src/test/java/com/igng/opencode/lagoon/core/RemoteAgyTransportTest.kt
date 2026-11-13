package com.igng.opencode.lagoon.core

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteAgyTransportTest {
  private data class Call(val method: String, val params: JSONObject)

  @Test fun healthChecksTheSelectedBackendWithoutEnumeratingAllWorkspaces() = runBlocking {
    val calls = CopyOnWriteArrayList<Call>()
    val transport = RemoteAgyTransport(true, false, { method, params ->
      calls += Call(method, JSONObject(params.toString()))
      JSONObject().put("protocolVersion", 1).put("backend", "antigravity")
    })
    val api = AgyApi(transport)
    try {
      assertEquals("Antigravity ACP", api.health())
      assertEquals("initialize", calls.single().params.getString("method"))
      assertEquals("antigravity", calls.single().params.getJSONObject("params").getString("backend"))
    } finally { api.close() }
  }

  @Test fun anOverloadedAgyChildDoesNotBreakItsNoiseConnectionOrSibling() = runBlocking {
    val streams = mutableMapOf<String, String>()
    val closedStream = CompletableDeferred<String>()
    val registry = RemoteAgyRegistry(transfer = true, files = false) { method, params ->
      when (method) {
        "agent.acp.stream.open" -> {
          streams[params.getString("sessionId")] = params.getString("streamId")
          JSONObject().put("opened", true)
        }
        "agent.acp.stream.close" -> {
          closedStream.complete(params.getString("streamId"))
          JSONObject()
        }
        "agent.acp.call" -> JSONObject().put("protocolVersion", 1).put("backend", "antigravity")
        else -> JSONObject()
      }
    }
    val overloaded = registry.acquire()
    val sibling = registry.acquire()
    try {
      overloaded.subscribe("one")
      sibling.subscribe("two")
      repeat(257) { index ->
        registry.event(JSONObject().put("event", "agent.acp.frame").put("data", JSONObject().put("streamId", streams.getValue("one"))
          .put("frame", JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", "one")
            .put("update", JSONObject().put("sessionUpdate", "agent_message_chunk").put("index", index))))))
      }
      assertTrue(overloaded.isClosed)
      assertFalse(sibling.isClosed)
      assertEquals(streams.getValue("one"), withTimeout(5_000) { closedStream.await() })
      sibling.health()
    } finally { registry.close() }
  }

  @Test fun replayPreservesDistinctMessagesAndAppliesBackpressure() = runBlocking {
    val events = JSONArray()
    repeat(300) { index ->
      events.put(JSONObject().put("event", JSONObject().put("type", "assistant/message").put("data", JSONObject()
        .put("message", JSONObject().put("id", "message-$index").put("content", JSONArray()
          .put(JSONObject().put("type", "text").put("text", "answer-$index")))))))
    }
    val calls = CopyOnWriteArrayList<Call>()
    lateinit var registry: RemoteAgyRegistry
    registry = RemoteAgyRegistry(transfer = true, files = true) { method, params ->
      calls += Call(method, JSONObject(params.toString()))
      when (method) {
        "agent.acp.stream.open" -> {
          // A newly subscribed Host may replay a live frame before open returns.
          registry.event(JSONObject().put("event", "agent.acp.frame").put("data", JSONObject()
            .put("streamId", params.getString("streamId")).put("frame", JSONObject().put("method", "session/update")
              .put("params", JSONObject().put("sessionId", "one").put("update", JSONObject().put("sessionUpdate", "agent_message_chunk")
                .put("content", JSONObject().put("type", "text").put("text", "live")))))))
          JSONObject().put("opened", true)
        }
        "agent.acp.transfer.commit" -> JSONObject().put("kind", "inline").put("response", JSONObject().put("events", events))
        "agent.acp.call" -> when (params.getString("method")) {
          "dsh/sessionList" -> JSONObject().put("items", JSONArray().put(JSONObject().put("conversationId", "one").put("title", "History")))
          else -> error("Unexpected Host call: ${params.getString("method")}")
        }
        else -> JSONObject()
      }
    }
    val api = AgyApi(registry.acquire())
    try {
      val session = api.rootSessionsPage("/repo").items.single()
      val last = withTimeout(5_000) { api.messagesPage(session.id, size = 200) }
      val first = api.messagesPage(session.id, cursor = last.next, size = 200)
      val messages = first.items + last.items
      assertEquals(301, messages.size)
      assertEquals((0 until 300).map { "answer-$it" } + "live", messages.map { it.parts.single().text })
      assertEquals(301, messages.map { it.id }.toSet().size)
      assertEquals(1, calls.count { it.method == "agent.acp.transfer.commit" })
      assertFalse(calls.any { it.method == "agent.acp.call" && it.params.optString("method") == "session/load" })
    } finally { api.close(); registry.close() }
  }

  @Test fun confirmedCreationSurvivesOptionalModelLookupFailure() = runBlocking {
    val calls = CopyOnWriteArrayList<Call>()
    val registry = RemoteAgyRegistry(transfer = true, files = true) { method, params ->
      calls += Call(method, JSONObject(params.toString()))
      when (method) {
        "agent.acp.stream.open" -> JSONObject().put("opened", true)
        "agent.acp.call" -> when (params.getString("method")) {
          "dsh/directoryList" -> JSONObject().put("path", "/repo")
          "session/new" -> JSONObject().put("sessionId", "created")
          "dsh/sessionModels" -> throw IOException("model lookup unavailable")
          else -> error("Unexpected Host call")
        }
        else -> JSONObject()
      }
    }
    val api = AgyApi(registry.acquire())
    try {
      assertEquals("created", api.createSession("/repo", "Test").id)
      assertEquals(1, calls.count { it.method == "agent.acp.call" && it.params.optString("method") == "session/new" })
      assertFalse(calls.any { it.method == "agent.acp.call" && it.params.optString("method") == "dsh/sessionModels" })
    } finally { api.close(); registry.close() }
  }

  @Test fun cancellationUsesTheRestrictedEnvelope() = runBlocking {
    val calls = CopyOnWriteArrayList<Call>()
    val transport = RemoteAgyTransport(true, false, { method, params ->
      calls += Call(method, JSONObject(params.toString()))
      JSONObject()
    })
    try {
      transport.notify("session/cancel", JSONObject().put("sessionId", "one"))
      val arguments = calls.single().params.getJSONObject("params")
      assertEquals("one", arguments.getString("sessionId"))
      assertFalse(arguments.has("backend"))
    } finally { transport.close() }
  }

  @Test fun remoteFileReadsRejectTruncationAndRecognizeBinaryBytes() = runBlocking {
    var data = byteArrayOf(0x61, 0x62)
    var truncated = false
    val transport = RemoteAgyTransport(true, true, { method, params ->
      assertEquals("agent.acp.call", method)
      val input = params.getJSONObject("params")
      assertEquals("acp:one", input.getJSONObject("args").getString("workspaceFileScopeId"))
      when (input.getString("endpoint")) {
        "workspaceFiles/list" -> JSONObject().put("path", ".").put("entries", JSONArray()).put("truncated", truncated)
        "workspaceFiles/readBytes" -> JSONObject().put("offset", 0).put("bytes", data.size).put("eof", !truncated)
          .put("data", java.util.Base64.getEncoder().encodeToString(data))
        else -> error("Unexpected file endpoint")
      }
    })
    val session = Session("one", "/repo", "", 0)
    try {
      assertEquals("ab", transport.fileContent(session, "file").content)
      data = byteArrayOf(0xff.toByte())
      assertEquals("binary", transport.fileContent(session, "file").type)
      truncated = true
      assertTrue(runCatching { transport.fileContent(session, "file") }.isFailure)
      assertTrue(runCatching { transport.files(session, ".") }.isFailure)
    } finally { transport.close() }
  }

  @Test fun remoteTerminalUsesTheOwnedAcpScopeAndDetachingDoesNotCloseTheProcess() = runBlocking {
    val calls = CopyOnWriteArrayList<Call>()
    lateinit var registry: RemoteAgyRegistry
    fun info(controller: String? = null) = JSONObject().put("id", "term").put("title", "Terminal")
      .put("shell", JSONObject().put("path", "/bin/sh").put("args", JSONArray()).put("name", "sh"))
      .put("cwd", "/repo").put("cols", 80).put("rows", 24).put("state", "running").put("exitCode", JSONObject.NULL)
      .also { if (controller != null) it.put("controllerId", controller) }
    registry = RemoteAgyRegistry(transfer = true, files = true, terminal = true) { method, params ->
      calls += Call(method, JSONObject(params.toString()))
      when (method) {
        "agent.acp.call" -> when (params.getString("method")) {
          "dsh/directoryList" -> JSONObject().put("path", "/repo")
          "session/new" -> JSONObject().put("sessionId", "one")
          "dsh/sessionModels" -> throw IOException("optional model lookup unavailable")
          "dsh/toolCall" -> {
            val input = params.getJSONObject("params")
            val args = input.getJSONObject("args")
            assertEquals("antigravity", input.getString("backend"))
            assertEquals("one", input.getString("sessionId"))
            assertEquals("acp:one", args.optString("agentId").ifBlank { args.optString("sessionId") })
            when (input.getString("endpoint")) {
              "terminal/environment" -> JSONObject().put("cwd", "/repo").put("scrollback", 1000)
                .put("maxInputBytes", 65536).put("maxBufferedBytes", 262144).put("maxCols", 1000).put("maxRows", 1000)
              "terminal/create" -> info().put("id", args.getJSONObject("request").getString("id"))
              "terminal/list" -> JSONArray().put(info())
              "terminal/write", "terminal/close" -> null
              else -> error("Unexpected terminal call")
            }
          }
          else -> error("Unexpected Host call")
        }
        "agent.acp.stream.open" -> {
          val tool = params.optJSONObject("tool")
          if (tool != null) {
            val args = tool.getJSONObject("args")
            assertEquals("acp:one", args.optString("agentId").ifBlank { args.optString("sessionId") })
            val value = when (tool.getString("endpoint")) {
              "terminal/retain" -> JSONObject().put("type", "retained")
              "terminal/follow" -> JSONObject().put("type", "snapshot").put("sequence", 0).put("screen", "ready")
                .put("info", info(args.getString("attachmentId"))).put("writable", true)
              else -> error("Unexpected stream")
            }
            registry.event(JSONObject().put("event", "agent.acp.frame").put("data", JSONObject().put("streamId", params.getString("streamId"))
              .put("frame", JSONObject().put("method", "dsh/workspaceTool").put("params", JSONObject().put("value", value)))))
          }
          JSONObject().put("opened", true)
        }
        else -> JSONObject()
      }
    }
    val api = AgyApi(registry.acquire())
    try {
      val session = api.createSession("/repo", "Test")
      assertTrue(api.capabilities().terminal)
      val scope = TerminalScope.Session(session.id, session.directory)
      val created = api.createTerminal(scope)
      assertNotNull(created.id)
      val attachment = api.attachTerminal(scope, "term")
      assertTrue(attachment.state.value.writable)
      assertTrue(attachment.sendText("pwd\n"))
      withTimeout(3_000) { attachment.state.first { it.acknowledgedSequence == 1L } }
      attachment.close()
      assertFalse(calls.any { it.method == "agent.acp.call" && it.params.optJSONObject("params")?.optString("endpoint") == "terminal/close" })
      api.closeTerminal(scope, "term")
      assertEquals(1, calls.count { it.method == "agent.acp.call" && it.params.optJSONObject("params")?.optString("endpoint") == "terminal/close" })
    } finally { api.close(); registry.close() }
  }
}
