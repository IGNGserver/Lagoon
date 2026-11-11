package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Direct and Remote callers share this parser; reads here never open a socket or process. */
class DshChangesReadApiTest {
  private val session = Session("session-one", "/repo", "Test", 1)
  private data class Call(val kind: String, val sessionId: String, val seq: Long, val index: Int?)
  private class Fake {
    val calls = mutableListOf<Call>()
    val summaries = mutableMapOf<Long, JSONObject>()
    val diffs = mutableMapOf<Pair<Long, Int>, JSONObject>()
    var failure: Throwable? = null
    val api = DshChangesReadApi { kind, sessionId, seq, index ->
      calls += Call(kind, sessionId, seq, index)
      failure?.let { throw it }
      if (kind == "summary") summaries.getValue(seq) else diffs.getValue(seq to index!!)
    }
  }

  @Test fun latestRawPathWinsWhileDisplaySortAndNativeIndicesArePreserved() = runBlocking {
    val fake = Fake()
    fake.summaries[3] = summary(file("a.kt", "old.kt", 1, 0))
    fake.summaries[9] = summary(file("b.kt", "a-label.kt", 4, 2), file("a.kt", "z-label.kt", 7, 3).put("kind", "modified"))
    fake.diffs[9L to 0] = diff("b.kt", "binary")
    fake.diffs[9L to 1] = diff("a.kt", "oversized")
    val result = fake.api.changes(session, snapshot(9, 3, 9))
    assertEquals(listOf("a-label.kt", "z-label.kt"), result.map { it.path })
    assertEquals(listOf(4, 7), result.map { it.additions })
    assertEquals(listOf(2, 3), result.map { it.deletions })
    assertEquals("modified", result[1].status)
    assertEquals(listOf(Call("summary", session.id, 3, null), Call("summary", session.id, 9, null),
      Call("diff", session.id, 9, 0), Call("diff", session.id, 9, 1)), fake.calls)
  }

  @Test fun binaryAndOversizedUseMessagesInsteadOfFakeTextPatches() = runBlocking {
    val fake = Fake()
    fake.summaries[1] = summary(file("binary"), file("oversized"))
    fake.diffs[1L to 0] = diff("binary", "binary").put("futureField", true)
    fake.diffs[1L to 1] = diff("oversized", "oversized")
    val result = fake.api.changes(session, snapshot(1))
    assertEquals(listOf("二进制文件，无法预览对比", "文件过大，无法预览对比"), result.map { it.after })
    assertTrue(result.all { it.patch.isEmpty() })
  }

  @Test fun textPatchKeepsZeroCoordinatesAndEmptyHunksAreValid() = runBlocking {
    val fake = Fake()
    fake.summaries[1] = summary(file("a"), file("b"))
    fake.diffs[1L to 0] = diff("a").put("hunks", JSONArray().put(hunk().put("oldStart", 0).put("oldLines", 0)))
    fake.diffs[1L to 1] = diff("b")
    val result = fake.api.changes(session, snapshot(1))
    assertEquals("--- a/a\n+++ b/a\n@@ -0,0 +1,1 @@\n+new\n", result[0].patch)
    assertEquals("", result[1].patch)
  }

  @Test fun sequenceMustBeNumericPositiveSafeIntegerAndOnlyRecentEightAreRead() = runBlocking {
    for (seq in listOf<Any>("1", 0, -1, 1.25, java.math.BigDecimal("1.0000000000000000001"), 9_007_199_254_740_992L)) {
      val fake = Fake()
      assertStatus(502) { fake.api.changes(session, snapshot(seq)) }
      assertTrue(fake.calls.isEmpty())
    }
    val fake = Fake()
    for (seq in 1L..10L) fake.summaries[seq] = summary()
    assertTrue(fake.api.changes(session, snapshot(*(1..10).toList().toTypedArray())).isEmpty())
    assertEquals((3L..10L).toList(), fake.calls.map { it.seq })
  }

  @Test fun recordsMustBeAnArrayButEmptyRecordsAndEmptySummaryAreAuthoritative() = runBlocking {
    val fake = Fake()
    assertStatus(502) { fake.api.changes(session, JSONObject()) }
    assertStatus(502) { fake.api.changes(session, snapshot().put("type", "delta")) }
    assertStatus(502) { fake.api.changes(session, JSONObject().put("type", "snapshot")) }
    assertStatus(502) { fake.api.changes(session, snapshot().put("records", "[]")) }
    assertTrue(fake.api.changes(session, snapshot()).isEmpty())
    fake.summaries[1] = summary()
    assertTrue(fake.api.changes(session, snapshot(1)).isEmpty())
    assertEquals(1, fake.calls.size)
  }

  @Test fun malformedSummaryCountsTypesAndIncompleteOrExcessiveListsAreRejected() = runBlocking {
    val invalid = listOf(
      summary().put("files", "[]") to 502,
      summary().put("turn", "1") to 502,
      summary(file("a").put("added", 1.5)) to 502,
      summary(file("a").put("deleted", -1)) to 502,
      summary(file("a").put("added", 2_147_483_648L)) to 502,
      summary(file("a")).put("total", 0) to 502,
      summary().put("total", 1) to 413,
      summary(*(1..61).map { file("f$it") }.toTypedArray()) to 413,
      summary().put("files", JSONArray().apply { repeat(5_001) { put(file("f$it")) } }).put("total", 5_001) to 413
    )
    for ((value, status) in invalid) {
      val fake = Fake().apply { summaries[1] = value }
      assertStatus(status) { fake.api.changes(session, snapshot(1)) }
      assertEquals(listOf("summary"), fake.calls.map { it.kind })
    }
  }

  @Test fun requiredPathsAreBoundedSafeAndComparisonMustMatchRawPath() = runBlocking {
    for (bad in listOf("", "bad\n+++ injected", "bad\rname", "bad\u0000name", "x".repeat(4_097))) {
      val fake = Fake().apply { summaries[1] = summary(file("a").put("display", bad)) }
      assertStatus(502) { fake.api.changes(session, snapshot(1)) }
    }
    for (comparison in listOf(diff("wrong"), diff("a").apply { remove("path") }, diff("a").put("display", 1))) {
      val fake = Fake().apply { summaries[1] = summary(file("a")); diffs[1L to 0] = comparison }
      assertStatus(502) { fake.api.changes(session, snapshot(1)) }
    }
  }

  @Test fun malformedTextFieldsCoordinatesAndLineArraysAreRejected() = runBlocking {
    val invalid = listOf(
      diff("a").put("kind", "unknown"), diff("a").put("before", "true"),
      diff("a").apply { remove("coarse") }, diff("a").put("hunks", JSONObject()),
      diff("a").put("hunks", JSONArray().put(hunk().put("oldStart", -1))),
      diff("a").put("hunks", JSONArray().put(hunk().put("newLines", "1"))),
      diff("a").put("hunks", JSONArray().put(hunk().put("oldLines", 0.5))),
      diff("a").put("hunks", JSONArray().put(hunk().put("lines", JSONArray().put(42)))),
      diff("a").put("hunks", JSONArray().put(hunk().put("lines", JSONArray().put("not-prefixed")))),
      diff("a").put("hunks", JSONArray().put(hunk().put("lines", JSONArray().put("+line\ninjected"))))
    )
    for (comparison in invalid) {
      val fake = Fake().apply { summaries[1] = summary(file("a")); diffs[1L to 0] = comparison }
      assertStatus(502) { fake.api.changes(session, snapshot(1)) }
    }
  }

  @Test fun utf8PerDiffAndCombinedBudgetsRejectRatherThanTruncate() = runBlocking {
    val fake = Fake().apply {
      summaries[1] = summary(file("a"))
      diffs[1L to 0] = diff("a").put("hunks", JSONArray().put(hunk().put("lines", JSONArray().put("+" + "界".repeat(350_000)))))
    }
    assertStatus(413) { fake.api.changes(session, snapshot(1)) }
    val total = Fake().apply {
      summaries[1] = summary(*(1..5).map { file("f$it") }.toTypedArray())
      for (i in 1..5) diffs[1L to i - 1] = diff("f$i").put("hunks",
        JSONArray().put(hunk().put("lines", JSONArray().put("+" + "界".repeat(300_000)))))
    }
    assertStatus(413) { total.api.changes(session, snapshot(1)) }
  }

  @Test fun expiredReadFailuresAndCancellationPropagateWithoutEmptySuccess() = runBlocking {
    for (failure in listOf(ApiException(404, "Expired"), ApiException(500, "Host failure"), CancellationException("cancelled"))) {
      val fake = Fake().apply { this.failure = failure }
      try {
        fake.api.changes(session, snapshot(1))
        fail("Expected original failure")
      } catch (actual: Throwable) { assertSame(failure, actual) }
    }
    val expired = ApiException(404, "Comparison expired")
    val api = DshChangesReadApi { kind, _, _, _ -> if (kind == "summary") summary(file("a")) else throw expired }
    try { api.changes(session, snapshot(1)); fail("Expected expired diff") }
    catch (actual: Throwable) { assertSame(expired, actual) }
  }

  private fun snapshot(vararg sequences: Any) = JSONObject().put("type", "snapshot").put("records", JSONArray().apply {
    for (seq in sequences) put(JSONObject().put("type", "event")
      .put("event", JSONObject().put("type", "workspace/changes").put("seq", seq).put("data", JSONObject().put("turn", 1))))
  })
  private fun file(path: String, display: String = path, added: Int = 0, deleted: Int = 0) =
    JSONObject().put("path", path).put("display", display).put("added", added).put("deleted", deleted)
  private fun summary(vararg files: JSONObject) = JSONObject().put("turn", 1).put("files", JSONArray(files.toList()))
    .put("total", files.size).put("added", 0).put("deleted", 0)
  private fun diff(path: String, kind: String = "text") = JSONObject().put("kind", kind).put("path", path)
    .put("display", path).put("before", true).put("after", true).put("coarse", false).put("hunks", JSONArray())
  private fun hunk() = JSONObject().put("oldStart", 1).put("oldLines", 1).put("newStart", 1).put("newLines", 1)
    .put("lines", JSONArray().put("+new"))
  private suspend fun assertStatus(expected: Int, block: suspend () -> Unit) {
    try { block(); fail("Expected ApiException $expected") }
    catch (error: ApiException) { assertEquals(expected, error.status) }
  }
}
