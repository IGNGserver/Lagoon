package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the official DSH PTC durable event contract. */
class DshPtcProjectionTest {
  private fun record(type: String, seq: Int, data: JSONObject) = JSONObject()
    .put("type", "event")
    .put("event", JSONObject().put("type", type).put("seq", seq).put("time", 1000L + seq).put("data", data))

  private fun root(callId: String = "root-call", turn: Any = 2, step: Any = 3) = record(
    "tool/call", 1, JSONObject()
      .put("turn", turn).put("step", step).put("callId", callId).put("name", "run_code")
      .put("arguments", JSONObject().put("code", "dispatch()"))
  )

  private fun start(root: String, parent: String, child: String, name: String = "read") = record(
    "tool/ptc-dispatch-start", 2, JSONObject()
      .put("rootCallId", root).put("parentCallId", parent).put("subCallId", child)
      .put("name", name).put("arguments", JSONObject().put("path", "a.kt"))
  )

  private fun result(root: String, parent: String, child: String, failed: Boolean = false) = record(
    "tool/ptc-dispatch", 3, JSONObject()
      .put("rootCallId", root).put("parentCallId", parent).put("subCallId", child)
      .put("name", "read").put("arguments", JSONObject().put("path", "a.kt"))
      .put("isError", failed).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "output")))
      .put("meta", JSONObject().put("exit", if (failed) 1 else 0))
      .apply { if (failed) put("error", JSONObject().put("name", "Failure").put("code", "E_READ").put("reason", "no file")) }
  )

  @Test fun officialRootAndNestedChildrenPreserveIdentityAndMetadata() {
    val rootId = "root-call"
    val childId = "root-call:ptc:0"
    val nestedId = "$childId:ptc:0"
    val projection = DshPtcProjection()
    projection.indexRoots(listOf(root(rootId)))

    val child = projection.process(start(rootId, rootId, childId))!!
    val nested = projection.process(start(rootId, childId, nestedId, "grep"))!!

    assertEquals(DshPtcEvent.Kind.START, child.kind)
    assertEquals("msg_2:3", child.assistantMessageId)
    assertEquals(childId, child.subCallId)
    assertEquals(rootId, child.parentCallId)
    assertEquals(rootId, child.metadata.str("rootCallId"))
    assertEquals(rootId, child.metadata.str("parentCallId"))
    assertEquals(nestedId, nested.metadata.str("subCallId"))
    assertEquals(2, nested.state.depth)
    assertEquals(listOf(childId), projection.childrenOf(rootId).map { it.subCallId })
    assertEquals(listOf(nestedId), projection.childrenOf(childId).map { it.subCallId })
    // Child records do not need turn/step; the root is the only location anchor.
    assertTrue(child.state.metadata.optString("assistantMessageID") == "msg_2:3")
  }

  @Test fun failedResultCarriesOfficialContentMetaErrorAndMessagePartMetadata() {
    val projection = DshPtcProjection()
    projection.indexRoots(listOf(root()))
    projection.process(start("root-call", "root-call", "root-call:ptc:0"))
    val settled = projection.process(result("root-call", "root-call", "root-call:ptc:0", failed = true))!!

    assertEquals(DshPtcEvent.Kind.RESULT, settled.kind)
    assertEquals("error", settled.state.status)
    assertTrue(settled.state.isError)
    assertEquals("no file", settled.state.error!!.str("reason"))
    assertEquals(1, settled.state.metadata.obj("meta").optInt("exit"))
    assertEquals("error", settled.state.messagePart().status)
    assertEquals("root-call", JSONObject(settled.state.messagePart().metadata).str("rootCallId"))
    assertEquals("no file", settled.state.messagePart().error)
  }

  @Test fun missingRootNeverCreatesPhantomAssistantOrChild() {
    val projection = DshPtcProjection()
    assertNull(projection.process(start("missing", "missing", "missing:ptc:0")))
    assertNull(projection.childProjection("missing:ptc:0"))
    assertTrue(projection.indexedRoots().roots.isEmpty())

    // A root with only one coordinate is invalid: never fall back to msg_0:0.
    projection.indexRoots(listOf(root("bad", turn = 0, step = 0)))
    assertNull(projection.process(start("bad", "bad", "bad:ptc:0")))
  }

  @Test fun coldHistoryAndLiveOrderProduceTheSameChildProjection() {
    val rootRecord = root()
    val childStart = start("root-call", "root-call", "root-call:ptc:0")
    val childResult = result("root-call", "root-call", "root-call:ptc:0")
    val records = listOf(rootRecord, childStart, childResult)

    val cold = DshPtcProjection().also { it.indexRoots(records) }
    val live = DshPtcProjection().also { it.indexRoots(listOf(rootRecord)) }
    val coldEvents = records.drop(1).mapNotNull { cold.process(it) }
    val liveEvents = records.drop(1).mapNotNull { live.process(it) }

    assertEquals(liveEvents.map { it.kind }, coldEvents.map { it.kind })
    assertEquals(live.childProjection("root-call:ptc:0")!!.status,
      cold.childProjection("root-call:ptc:0")!!.status)
    assertEquals(live.childProjection("root-call:ptc:0")!!.messagePart(),
      cold.childProjection("root-call:ptc:0")!!.messagePart())
  }

  @Test fun outOfOrderResultCanSettleKnownParentButInvalidEdgesAreRejected() {
    val rootId = "root-call"
    val parentId = "$rootId:ptc:0"
    val childId = "$parentId:ptc:0"
    val projection = DshPtcProjection()
    projection.indexRoots(listOf(root()))
    projection.process(start(rootId, rootId, parentId))

    // Official UI accepts a result before its matching start when the parent edge is known.
    assertEquals(DshPtcEvent.Kind.RESULT, projection.process(result(rootId, parentId, childId))!!.kind)
    assertNull(projection.process(start(rootId, rootId, childId))) // same id, wrong parent
    assertNull(projection.process(start("other-root", parentId, "other-child"))) // cross-root
    assertNull(projection.process(start(rootId, childId, parentId))) // cycle / reused id
  }

  @Test fun depthAndEntryBoundsAreFinite() {
    val projection = DshPtcProjection(maxDepth = 2, maxEntries = 2)
    projection.indexRoots(listOf(root()))
    assertTrue(projection.process(start("root-call", "root-call", "a")) != null)
    assertTrue(projection.process(start("root-call", "a", "b")) != null) // depth 2 is allowed
    // The first two children consumed the entry bound; a different sibling is rejected.
    assertNull(projection.process(start("root-call", "root-call", "c")))
  }
}
