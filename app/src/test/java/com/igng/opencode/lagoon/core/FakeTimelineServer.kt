package com.igng.opencode.lagoon.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A message timeline served the way OpenCode 2.x `GET /api/session/{id}/message` does
 * (`packages/server/src/handlers/message.ts` + `packages/core/src/session/store.ts`): rows are ordered
 * by an insertion sequence, cursors are opaque base64url JSON anchored on one message id, and a cursor
 * whose anchor no longer exists yields an empty page. [latencyMs] delays each reply like a weak link.
 */
internal class FakeTimelineServer(var latencyMs: Long = 0) : AutoCloseable {
  val server = MockWebServer()
  /** Messages in server sequence order; tests mutate this to simulate new turns, edits and deletions. */
  val timeline = mutableListOf<JSONObject>()
  val messageRequests = AtomicInteger()
  val queries = java.util.Collections.synchronizedList(mutableListOf<Map<String, String?>>())

  init {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val path = url.encodedPath
        val response = when {
          path.endsWith("/message") -> { messageRequests.incrementAndGet(); messages(url.queryParameter("limit"), url.queryParameter("order"), url.queryParameter("cursor")) }
          path.endsWith("/inbox") -> MockResponse().setBody("""{"data":[]}""")
          else -> MockResponse().setResponseCode(404).setBody("{}")
        }
        queries += url.queryParameterNames.associateWith { url.queryParameter(it) }
        return if (latencyMs > 0) response.setHeadersDelay(latencyMs, TimeUnit.MILLISECONDS) else response
      }
    }
    server.start()
  }

  fun api(): OpenCodeApi = OpenCodeApi(ServerProfile("fake", "Fake", server.url("/").toString().trimEnd('/'), "opencode", allowCleartext = true), "secret")

  @Synchronized private fun messages(limitText: String?, orderText: String?, cursorText: String?): MockResponse {
    if (cursorText != null && orderText != null) return MockResponse().setResponseCode(400).setBody("""{"name":"InvalidCursorError"}""")
    val limit = limitText?.toInt() ?: 50
    val cursor = cursorText?.let { JSONObject(String(Base64.getUrlDecoder().decode(it))) }
    val requested = cursor?.getString("order") ?: orderText ?: "desc"
    val direction = cursor?.getString("direction") ?: "next"
    val order = if (direction == "previous") (if (requested == "asc") "desc" else "asc") else requested
    val anchor = cursor?.let { c -> timeline.indexOfFirst { it.getString("id") == c.getString("id") } }
    if (anchor == -1) return page(emptyList(), requested)
    val ordered = timeline.indices.let { if (order == "asc") it.toList() else it.reversed() }
      .filter { index -> anchor == null || (if (order == "asc") index > anchor else index < anchor) }
      .take(limit).map { timeline[it] }
    return page(if (direction == "previous") ordered.reversed() else ordered, requested)
  }

  private fun page(items: List<JSONObject>, order: String): MockResponse {
    fun encode(item: JSONObject, direction: String) = Base64.getUrlEncoder().withoutPadding()
      .encodeToString(JSONObject().put("id", item.getString("id")).put("order", order).put("direction", direction).toString().toByteArray())
    val cursor = JSONObject()
    items.firstOrNull()?.let { cursor.put("previous", encode(it, "previous")) }
    items.lastOrNull()?.let { cursor.put("next", encode(it, "next")) }
    return MockResponse().setBody(JSONObject().put("data", JSONArray(items)).put("cursor", cursor).toString())
  }

  override fun close() = server.shutdown()

  companion object {
    private var sequence = 0L
    /** Monotonic ids shaped like the server's (`msg_` + ascending). */
    @Synchronized fun nextId(): String = "msg_" + (++sequence).toString().padStart(12, '0')

    fun user(text: String, id: String = nextId()): JSONObject =
      JSONObject().put("id", id).put("type", "user").put("text", text).put("time", JSONObject().put("created", sequence))

    fun assistant(text: String, id: String = nextId(), completed: Boolean = true, toolStatus: String? = null): JSONObject {
      val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
      if (toolStatus != null) content.put(JSONObject().put("type", "tool").put("id", "call_$id").put("name", "read")
        .put("state", JSONObject().put("status", toolStatus).put("input", JSONObject().put("path", "a.kt"))
          .apply { if (toolStatus == "completed") put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "file body"))) })
        .put("time", JSONObject().put("created", sequence)))
      val time = JSONObject().put("created", sequence)
      if (completed) time.put("completed", sequence + 1)
      return JSONObject().put("id", id).put("type", "assistant").put("agent", "build")
        .put("model", JSONObject().put("providerID", "p").put("id", "m")).put("content", content).put("time", time)
        .apply { if (completed) put("finish", "stop") }
    }

    /** [turns] whole turns of one prompt followed by [steps] assistant steps, each carrying tool output. */
    fun conversation(turns: Int, steps: Int, outputChars: Int = 600): List<JSONObject> = (0 until turns).flatMap { turn ->
      listOf(user("question $turn")) + (0 until steps).map { step -> assistant("step $turn.$step " + "y".repeat(outputChars), toolStatus = "completed") }
    }
  }
}
