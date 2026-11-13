package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgyApiTest {
  private class Fake : AgyTransport {
    private data class Packet(val frame: JSONObject?, val barrier: CompletableDeferred<Unit>? = null)
    private val frames = Channel<Packet>(64)
    val calls = mutableListOf<String>()
    val prompts = mutableListOf<JSONObject>()
    val replies = mutableListOf<Pair<Any, JSONObject>>()
    val completions = mutableListOf<CompletableDeferred<JSONObject>>()
    var listed = JSONArray().put(JSONObject().put("sessionId", "one").put("cwd", "/repo").put("title", "Title"))
    var lookupComplete: Boolean? = null
    var configFor: (String) -> JSONArray? = { null }
    var loadCount = 0
    override suspend fun call(method: String, params: JSONObject): JSONObject {
      calls += method
      return when (method) {
        "session/list" -> JSONObject().put("sessions", listed).also { value ->
          lookupComplete?.let { value.put("_lagoonWorkspaceLookupComplete", it) }
        }
        "session/load" -> {
          loadCount++
          emitUpdate("one", "user_message_chunk", "old question")
          emitUpdate("one", "agent_message_chunk", "old answer")
          JSONObject().also { result -> configFor(params.getString("sessionId"))?.let { result.put("configOptions", it) } }
        }
        else -> JSONObject()
      }
    }
    override suspend fun beginPrompt(params: JSONObject): Deferred<JSONObject> {
      prompts += JSONObject(params.toString())
      return CompletableDeferred<JSONObject>().also { completions += it }
    }
    override suspend fun notify(method: String, params: JSONObject) { calls += method }
    override suspend fun respond(requestId: Any, result: JSONObject) { replies += requestId to result }
    override suspend fun respondError(requestId: Any, code: Int, message: String) = Unit
    override suspend fun subscribe(sessionId: String) = Unit
    override suspend fun unsubscribe(sessionId: String) = Unit
    override suspend fun syncEvents() { val done = CompletableDeferred<Unit>(); frames.send(Packet(null, done)); done.await() }
    override fun events(): Flow<JSONObject> = flow {
      for (packet in frames) if (packet.frame != null) emit(packet.frame) else packet.barrier?.complete(Unit)
    }
    suspend fun emit(frame: JSONObject) { frames.send(Packet(frame)); syncEvents() }
    suspend fun emitUpdate(id: String, type: String, text: String) {
      frames.send(Packet(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", id)
        .put("update", JSONObject().put("sessionUpdate", type).put("content", JSONObject().put("type", "text").put("text", text))))))
    }
    override fun close() { frames.close() }
  }
  @Test fun replayIsOrderedAndLiveTurnsDoNotReloadTheRunningSession() = runBlocking {
    val transport = Fake(); val api = AgyApi(transport)
    val session = api.rootSessionsPage().items.single()
    val history = api.messagesPage(session.id).items
    assertEquals(listOf("old question", "old answer"), history.map { it.parts.single().text })
    withTimeout(1000) { api.send(session, "new question", id = "local-1") }
    assertTrue(runCatching { api.activeSessions() }.isFailure)
    transport.emitUpdate(session.id, "user_message_chunk", "new question")
    transport.emitUpdate(session.id, "agent_message_chunk", "new answer")
    transport.syncEvents()
    assertEquals(1, transport.loadCount)
    assertEquals("new answer", api.messagesPage(session.id).items.last().parts.single().text)
    assertEquals(1, api.messagesPage(session.id).items.count { it.role == "user" && it.parts.any { part -> part.text == "new question" } })
    val completed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "session.execution.succeeded" } } }
    transport.completions.single().complete(JSONObject().put("stopReason", "end_turn"))
    completed.await()
    api.close()
  }
  @Test fun permissionUsesActualOptionIdAndCancellationHasExplicitOutcome() = runBlocking {
    val transport = Fake(); val api = AgyApi(transport)
    val session = api.rootSessionsPage().items.single(); api.messagesPage(session.id)
    api.send(session, "go", id = "local-1")
    val asked = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "permission.asked" } } }
    transport.emit(JSONObject().put("id", 7).put("method", "session/request_permission").put("params", JSONObject()
      .put("sessionId", session.id).put("toolCall", JSONObject().put("toolCallId", "t").put("title", "run"))
      .put("options", JSONArray().put(JSONObject().put("kind", "allow_once").put("optionId", "real-allow"))
        .put(JSONObject().put("kind", "reject_once").put("optionId", "real-reject")))))
    asked.await()
    val permission = api.sessionPermissions(session).single()
    api.replyPermission(permission, "once")
    assertEquals("real-allow", transport.replies.single().second.getJSONObject("outcome").getString("optionId"))
    api.close()
  }
  @Test fun structuredFormIsValidatedBeforeReply() = runBlocking {
    val transport = Fake(); val api = AgyApi(transport)
    val session = api.rootSessionsPage().items.single(); api.messagesPage(session.id)
    val created = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { api.events().first { it.type == "form.created" } } }
    transport.emit(JSONObject().put("id", 9).put("method", "elicitation/create").put("params", JSONObject().put("sessionId", session.id)
      .put("mode", "form").put("message", "Choose").put("requestedSchema", JSONObject().put("type", "object")
        .put("properties", JSONObject().put("choice", JSONObject().put("type", "string").put("enum", JSONArray().put("a").put("b"))))
        .put("required", JSONArray().put("choice")))))
    created.await()
    val question = api.forms("/repo").single()
    assertTrue(runCatching { api.replyQuestion(question, listOf(listOf("invalid"))) }.isFailure)
    api.replyQuestion(question, listOf(listOf("b")))
    assertEquals("b", transport.replies.single().second.getJSONObject("content").getString("choice"))
    api.close()
  }
  @Test fun modelsAreReadFromTheSelectedSessionNotAnotherSessionInTheSameDirectory() = runBlocking {
    val transport = Fake()
    transport.listed.put(JSONObject().put("sessionId", "two").put("cwd", "/repo").put("title", "Other"))
    transport.configFor = { id -> JSONArray().put(JSONObject().put("id", "model").put("type", "select").put("currentValue", id)
      .put("options", JSONArray().put(JSONObject().put("value", id).put("name", id)))) }
    val api = AgyApi(transport)
    try {
      val sessions = api.rootSessionsPage().items.associateBy { it.id }
      assertEquals("one", api.modelCatalogForSession("/repo", sessions.getValue("one")).single().choice.modelId)
      assertEquals("two", api.modelCatalogForSession("/repo", sessions.getValue("two")).single().choice.modelId)
      assertTrue(api.modelCatalogForSession("/repo", null).isEmpty())
    } finally { api.close() }
  }
  @Test fun sessionListingEnforcesTheRequestedWorkspaceWhenAgyReturnsAGlobalPage() = runBlocking {
    val transport = Fake()
    transport.listed.getJSONObject(0).put("cwd", "/home/dev")
      .put("_lagoonWorkspaces", JSONArray().put("/home/dev").put("/repo"))
    transport.listed.put(JSONObject().put("sessionId", "other").put("cwd", "/repo-other").put("title", "Other"))
    val api = AgyApi(transport)
    try {
      assertEquals(listOf("one"), api.rootSessionsPage("/repo").items.map { it.id })
      assertEquals("/repo", api.rootSessionsPage("/repo").items.single().directory)
      assertEquals(2, api.rootSessionsPage().items.size)
    } finally { api.close() }
  }
  @Test fun unavailableWorkspaceMetadataIsNotReportedAsAnEmptyProject() = runBlocking {
    val transport = Fake().apply { lookupComplete = false }
    val api = AgyApi(transport)
    try {
      assertTrue(runCatching { api.rootSessionsPage("/other") }.isFailure)
      assertEquals(1, api.rootSessionsPage().items.size)
    } finally { api.close() }
  }
}
