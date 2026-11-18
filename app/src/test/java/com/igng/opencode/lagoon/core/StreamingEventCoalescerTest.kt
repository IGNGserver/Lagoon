package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingEventCoalescerTest {
  @Test
  fun mergesAdjacentTextDeltasAndPreservesBarriers() {
    fun textDelta(id: String, delta: String, at: Long, messageId: String = "m1") =
      "srv1" to ServerEvent(
        id = id,
        directory = "/repo",
        type = "session.text.delta",
        properties = JSONObject()
          .put("sessionID", "s1")
          .put("assistantMessageID", messageId)
          .put("delta", delta),
        created = at
      )

    val toolCalled = "srv1" to ServerEvent(
      id = "e3",
      directory = "/repo",
      type = "session.tool.called",
      properties = JSONObject().put("sessionID", "s1").put("assistantMessageID", "m1").put("id", "t1"),
      created = 103
    )

    val batch = listOf(
      textDelta("e1", "Hello", 101),
      textDelta("e2", " world", 102),
      toolCalled,
      textDelta("e4", "!", 104),
      textDelta("e5", "?", 105)
    )

    val coalesced = StreamingEventCoalescer.coalesce(batch)
    assertEquals(3, coalesced.size)
    assertEquals("Hello world", coalesced[0].second.properties.str("delta"))
    assertEquals(102L, coalesced[0].second.created)
    assertEquals("session.tool.called", coalesced[1].second.type)
    assertEquals("!?", coalesced[2].second.properties.str("delta"))
    assertEquals(105L, coalesced[2].second.created)
  }

  @Test
  fun doesNotMergeAcrossDifferentToolIdsOrSessions() {
    val d1 = "srv1" to ServerEvent(
      id = "1",
      directory = "/repo",
      type = "session.tool.input.delta",
      properties = JSONObject().put("sessionID", "s1").put("assistantMessageID", "m1").put("id", "toolA").put("delta", "{"),
      created = 1
    )
    val d2 = "srv1" to ServerEvent(
      id = "2",
      directory = "/repo",
      type = "session.tool.input.delta",
      properties = JSONObject().put("sessionID", "s1").put("assistantMessageID", "m1").put("id", "toolB").put("delta", "}"),
      created = 2
    )
    assertEquals(2, StreamingEventCoalescer.coalesce(listOf(d1, d2)).size)
  }
}
