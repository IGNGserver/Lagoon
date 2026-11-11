package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshChangeAnnouncementsTest {
  private fun event(seq: Any) = JSONObject().put("type", "workspace/changes").put("seq", seq).put("data", JSONObject().put("turn", 1))
  private fun snapshot(vararg seq: Any) = JSONObject().put("type", "snapshot").put("records", JSONArray(seq.map { JSONObject().put("type", "event").put("event", event(it)) }))
  @Test fun liveAnnouncementsExtendTheInitialSnapshotWithoutMutatingOrDuplicatingIt() {
    val cache = DshChangeAnnouncements(); val original = snapshot(3)
    cache.record("session", event(7)); cache.record("session", event(7))
    val merged = cache.snapshot("session", original).getJSONArray("records")
    assertEquals(listOf(3L, 7L), merged.objects().map { it.getJSONObject("event").getLong("seq") })
    assertEquals(1, original.getJSONArray("records").length())
    assertEquals(1, cache.snapshot("other", original).getJSONArray("records").length())
  }
  @Test fun onlyNewestEightSafeNativeAnnouncementsAreRetainedPerSession() {
    val cache = DshChangeAnnouncements()
    (1..12).reversed().forEach { cache.record("session", event(it)) }
    for (bad in listOf<Any>("13", 1.5, -1, 9_007_199_254_740_992L)) cache.record("session", event(bad))
    assertEquals((5L..12L).toList(), cache.snapshot("session", snapshot()).getJSONArray("records").objects().map { it.getJSONObject("event").getLong("seq") })
    cache.clear(); assertEquals(0, cache.snapshot("session", snapshot()).getJSONArray("records").length())
  }
  @Test fun malformedInitialDataRemainsVisibleToStrictParserAndPayloadsAreCopied() {
    val cache = DshChangeAnnouncements(); val value = event(7)
    cache.record("session", value); value.put("seq", 500)
    val result = cache.snapshot("session", snapshot("bad"))
    assertEquals("bad", result.getJSONArray("records").getJSONObject(0).getJSONObject("event").getString("seq"))
    assertEquals(7, result.getJSONArray("records").getJSONObject(1).getJSONObject("event").getInt("seq"))
    assertTrue(runCatching { kotlinx.coroutines.runBlocking { DshChangesReadApi { _, _, _, _ -> error("no read") }.changes(Session("session", "/repo", "T", 1), result) } }.isFailure)
  }
}
