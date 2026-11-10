package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Exercise the adapter/reducer boundary, rather than the PTC helper in isolation. */
class DshPtcIntegrationTest {
  private val session = Session("ptc-session", "/repo", "PTC", 1)
  private fun client() = DshApi(ServerProfile("srv", "DSH", "http://127.0.0.1:1",
    kind = ServerKind.DSH, allowCleartext = true), ServerCredentials("dsh", ""))

  private fun record(type: String, seq: Int, data: JSONObject) = JSONObject().put("type", "event")
    .put("event", JSONObject().put("type", type).put("seq", seq).put("time", 1000L + seq).put("data", data))
  private fun step(type: String, seq: Int) = record(type, seq, JSONObject().put("turn", 2).put("step", 3))
  private fun root(seq: Int = 2) = record("tool/call", seq, JSONObject().put("turn", 2).put("step", 3)
    .put("callId", "root").put("name", "run_code").put("arguments", "{\"code\":\"dispatch()\"}"))
  private fun child(seq: Int, id: String = "child", parent: String = "root", result: Boolean = false,
    failed: Boolean = false, legacy: Boolean = false, rootId: String = "root"): JSONObject {
    val data = JSONObject().put("rootCallId", rootId).put("parentCallId", parent).put("subCallId", id)
      .put("name", "read").put("arguments", JSONObject().put("path", "a.kt"))
    if (result) {
      data.put("isError", failed).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "output")))
        .put("meta", JSONObject().put("exit", if (failed) 1 else 0)
          .put("files", JSONArray().put(JSONObject().put("file", "a.kt").put("patch", "diff"))))
      if (failed) data.put("error", JSONObject().put("name", "ReadFailure").put("code", "E_READ")
        .put("reason", "file is unavailable").put("details", JSONObject().put("path", "a.kt")))
    }
    val prefix = if (legacy) "tool/code-dispatch" else "tool/ptc-dispatch"
    return record(if (result) prefix else "$prefix-start", seq, data)
  }
  private suspend fun live(api: DshApi, records: List<JSONObject>, initial: List<Message> = emptyList()): List<Message> {
    var messages = initial
    for (record in records) for (event in api.translate(session, record.obj("event"))) {
      messages = TranscriptProjection.apply(messages, event)?.messages ?: messages
    }
    return messages
  }

  @Test fun liveAndColdAdapterProjectNestedCallsAndStructuredFailureIdentically() = runBlocking {
    val records = listOf(step("step/start", 1), root(), child(3), child(4, "nested", "child"),
      child(5, "nested", "child", result = true, failed = true), child(6, result = true), step("step/end", 7))
    val liveApi = client()
    val coldApi = client()
    try {
      val streamed = live(liveApi, records)
      val cold = coldApi.recordsToMessages(session, records)
      assertEquals(cold, streamed)
      val message = cold.single()
      assertEquals("msg_2:3", message.id)
      assertEquals(listOf("root", "child", "nested"), message.parts.map { it.id })
      val nested = message.parts.last()
      assertEquals("error", nested.status)
      assertEquals("file is unavailable", nested.error)
      assertEquals(listOf("a.kt"), nested.files)
      assertEquals("diff", nested.patch)
      val metadata = JSONObject(nested.metadata)
      assertEquals("root", metadata.str("rootCallId"))
      assertEquals("child", metadata.str("parentCallId"))
      assertEquals("nested", metadata.str("subCallId"))
      assertEquals(1, metadata.obj("meta").optInt("exit"))
      assertEquals("E_READ", metadata.obj("error").str("code"))
      assertEquals("a.kt", metadata.obj("error").obj("details").str("path"))
    } finally { liveApi.close(); coldApi.close() }
  }

  @Test fun runningStartRetainsNormalizedInputAndAncestryInReducer() = runBlocking {
    val api = client()
    try {
      val records = listOf(step("step/start", 1), root(), child(3))
      val streamed = live(api, records).single()
      val cold = api.recordsToMessages(session, records).single()
      assertEquals(cold.parts, streamed.parts)
      val part = streamed.parts.last()
      assertEquals("running", part.status)
      assertEquals("a.kt", JSONObject(part.input).str("path"))
      assertEquals("root", JSONObject(part.metadata).str("parentCallId"))
      assertEquals("msg_2:3", JSONObject(part.metadata).str("assistantMessageID"))
    } finally { api.close() }
  }

  @Test fun rootsAndAncestorsSurviveSeparatePagesAndRepeatedColdProjection() = runBlocking {
    val api = client()
    try {
      api.recordsToMessages(session, listOf(root()))
      val parentPage = listOf(child(3))
      assertEquals("child", api.recordsToMessages(session, parentPage).single().parts.single().id)
      val resultPage = listOf(child(4, "nested", "child", result = true, failed = true))
      val first = api.recordsToMessages(session, resultPage)
      assertEquals("msg_2:3", first.single().id)
      assertEquals("nested", first.single().parts.single().id)
      assertEquals("error", first.single().parts.single().status)
      assertEquals(first, api.recordsToMessages(session, resultPage))
      // Replaying an older start page must still show running, not the cached completed result.
      assertEquals("running", api.recordsToMessages(session, parentPage).single().parts.single().status)
    } finally { api.close() }
  }

  @Test fun coldPrepassFindsRootEvenWhenItAppearsAfterChildInWindow() = runBlocking {
    val api = client()
    try {
      val messages = api.recordsToMessages(session, listOf(child(3), root()))
      assertEquals(listOf("msg_2:3"), messages.map { it.id })
      assertEquals(listOf("root", "child"), messages.single().parts.map { it.id })
      assertTrue(messages.none { it.id == "msg_0:0" })
    } finally { api.close() }
  }

  @Test fun resultWithoutStartAndLegacyNamesUseSameAdapterPath() = runBlocking {
    val records = listOf(step("step/start", 1), root(), child(3, result = true, failed = true, legacy = true), step("step/end", 4))
    val liveApi = client()
    val coldApi = client()
    try {
      val streamed = live(liveApi, records)
      assertEquals(coldApi.recordsToMessages(session, records), streamed)
      assertEquals("file is unavailable", streamed.single().parts.last().error)
      assertEquals("E_READ", JSONObject(streamed.single().parts.last().metadata).obj("error").str("code"))
    } finally { liveApi.close(); coldApi.close() }
  }

  @Test fun missingOrForeignRootsSkipChildrenAndNeverCreateZeroCoordinateMessage() = runBlocking {
    val api = client()
    try {
      val orphan = child(3)
      assertTrue(api.translate(session, orphan.obj("event")).isEmpty())
      assertTrue(api.recordsToMessages(session, listOf(orphan)).isEmpty())
      api.recordsToMessages(session, listOf(root()))
      val other = session.copy(id = "other-session")
      assertTrue(api.translate(other, orphan.obj("event")).isEmpty())
      assertTrue(api.recordsToMessages(other, listOf(orphan)).isEmpty())
      val malformed = root().apply { obj("event").obj("data").remove("step") }
      assertTrue(api.translate(other, malformed.obj("event")).isEmpty())
      assertTrue(api.recordsToMessages(other, listOf(malformed, orphan)).isEmpty())
    } finally { api.close() }
  }

  @Test fun sessionCacheIsBoundedAndCloseReleasesPtcState() = runBlocking {
    val api = client()
    val field = DshApi::class.java.getDeclaredField("ptcProjections").apply { isAccessible = true }
    try {
      repeat(DshApi.WATCH_LIMIT + 2) { index ->
        api.recordsToMessages(session.copy(id = "session-$index"), listOf(root()))
      }
      val cache = field.get(api) as Map<*, *>
      assertEquals(DshApi.WATCH_LIMIT, cache.size)
      assertTrue(api.translate(session.copy(id = "session-0"), child(3).obj("event")).isEmpty())
    } finally { api.close() }
    assertTrue((field.get(api) as Map<*, *>).isEmpty())
  }

  @Test fun liveAndColdNativeCallsRemainVisiblePastThePtcRootIndexLimit() = runBlocking {
    val liveApi = client()
    val coldApi = client()
    try {
      val calls = (1..(DshPtcProjection.MAX_ENTRIES + 1)).map { index ->
        record("tool/call", index, JSONObject().put("turn", 9).put("step", index)
          .put("callId", "native-$index").put("name", "read").put("arguments", JSONObject().put("path", "$index.kt")))
      }
      calls.forEach { call ->
        val translated = liveApi.translate(session, call.obj("event"))
        assertEquals(2, translated.size)
        assertEquals(call.obj("event").obj("data").str("callId"), translated.last().properties.str("id"))
      }
      val freshCallId = "native-${DshPtcProjection.MAX_ENTRIES + 1}"
      val freshRootChild = child(DshPtcProjection.MAX_ENTRIES + 2, id = "fresh-child", parent = freshCallId, rootId = freshCallId)
      val lastLive = liveApi.translate(session, freshRootChild.obj("event"))
      assertEquals(2, lastLive.size)
      assertEquals("msg_9:${DshPtcProjection.MAX_ENTRIES + 1}", lastLive.last().properties.str("assistantMessageID"))
      val messages = coldApi.recordsToMessages(session, calls)
      assertEquals(DshPtcProjection.MAX_ENTRIES + 1, messages.size)
      assertEquals("msg_9:1", messages.first().id)
      assertEquals("msg_9:${DshPtcProjection.MAX_ENTRIES + 1}", messages.last().id)
      assertEquals("native-${DshPtcProjection.MAX_ENTRIES + 1}", messages.last().parts.single().id)
      val coldChild = coldApi.recordsToMessages(session, listOf(freshRootChild))
      assertEquals("msg_9:${DshPtcProjection.MAX_ENTRIES + 1}", coldChild.single().id)
      assertEquals("fresh-child", coldChild.single().parts.single().id)
    } finally { liveApi.close(); coldApi.close() }
  }

  @Test fun cachedParentStartAnchorsChildResultBeforeParentResultInPage() = runBlocking {
    val api = client()
    try {
      api.recordsToMessages(session, listOf(root(), child(3)))
      val childResult = child(4, "nested", "child", result = true, failed = true)
      val parentResult = child(5, "child", "root", result = true)
      val messages = api.recordsToMessages(session, listOf(childResult, parentResult))
      assertEquals(listOf("msg_2:3"), messages.map { it.id })
      assertEquals(listOf("nested", "child"), messages.single().parts.map { it.id })
      assertEquals("error", messages.single().parts.first().status)
    } finally { api.close() }
  }

  @Test fun completeColdWindowKeepsNativeAndPtcToolInsertionOrder() = runBlocking {
    val records = listOf(step("step/start", 1), root(), child(3), child(4, result = true),
      root(seq = 5).apply { obj("event").obj("data").put("callId", "root-b").put("name", "bash") },
      record("tool/result", 6, JSONObject().put("turn", 2).put("step", 3)
        .put("message", JSONObject().put("toolCallId", "root").put("isError", false).put("content", JSONArray()))
        .put("meta", JSONObject().put("tool", "run_code"))), step("step/end", 7))
    val liveApi = client()
    val coldApi = client()
    try {
      val streamed = live(liveApi, records)
      val cold = coldApi.recordsToMessages(session, records)
      assertEquals(cold, streamed)
      assertEquals(listOf("root", "child", "root-b"), cold.single().parts.map { it.id })
    } finally { liveApi.close(); coldApi.close() }
  }

  @Test fun boundedPtcCachesRotateRootsAndNodesButRejectOneOversizedLiveTree() {
    val projection = DshPtcProjection(maxDepth = 8, maxEntries = 2)
    fun customRoot(id: String, step: Int) = record("tool/call", step, JSONObject().put("turn", 4).put("step", step)
      .put("callId", id).put("name", "run_code").put("arguments", JSONObject()))
    fun start(rootId: String, parentId: String, childId: String) = projection.process("tool/ptc-dispatch-start",
      JSONObject().put("rootCallId", rootId).put("parentCallId", parentId).put("subCallId", childId)
        .put("name", "read").put("arguments", JSONObject()))
    projection.indexRoots(listOf(customRoot("root-1", 1), customRoot("root-2", 2)))
    assertNotNull(start("root-1", "root-1", "child-1"))
    // A fresh root evicts the oldest root and its complete child subtree.
    projection.indexRoots(listOf(customRoot("root-3", 3)))
    assertNull(start("root-1", "root-1", "late-old"))
    assertNotNull(start("root-3", "root-3", "child-3a"))
    assertNotNull(start("root-3", "root-3", "child-3b"))
    // A single tree cannot be split across parents when it exceeds the per-session node cap.
    assertNull(start("root-3", "root-3", "child-3c"))
    // Capacity is bounded per session, but a later fresh root can rotate the old subtree.
    projection.indexRoots(listOf(customRoot("root-4", 4)))
    assertNotNull(start("root-4", "root-4", "child-4"))
    assertTrue(projection.indexedRoots().roots.size <= 2)
  }

  @Test fun followSnapshotSeedsRootAndParentBeforeAnyMessageRead() = runBlocking {
    val api = client()
    try {
      val records = listOf(step("step/start", 1), root(), child(3))
      val stateClass = DshApi::class.java.declaredClasses.single { it.simpleName == "FollowState" }
      val state = stateClass.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(session.id)
      val process = DshApi::class.java.getDeclaredMethod("processFollowFrame", Session::class.java,
        stateClass, JSONObject::class.java, Continuation::class.java).apply { isAccessible = true }
      val snapshot = JSONObject().put("type", "snapshot").put("cursor", 3).put("records", JSONArray(records))
      suspendCoroutineUninterceptedOrReturn<Unit> { continuation -> process.invoke(api, session, state, snapshot, continuation) }
      val nested = api.translate(session, child(4, "nested", "child").obj("event"))
      assertEquals(2, nested.size)
      assertTrue(nested.all { it.properties.str("assistantMessageID") == "msg_2:3" })
      assertEquals("child", nested.last().properties.obj("metadata").str("parentCallId"))
    } finally { api.close() }
  }
}
