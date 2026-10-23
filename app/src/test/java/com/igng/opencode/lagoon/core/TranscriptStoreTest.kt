package com.igng.opencode.lagoon.core

import com.igng.opencode.lagoon.core.FakeTimelineServer.Companion.assistant
import com.igng.opencode.lagoon.core.FakeTimelineServer.Companion.user
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class TranscriptStoreTest {
  private val store = testStore()
  private fun records(vararg raw: JSONObject) = raw.map(::record)

  @Test fun storedMessagesRenderExactlyLikeTheServerCopy() {
    val raw = listOf(user("hi"), assistant("hello", toolStatus = "completed"), assistant("done"))
    store.replace("s", "a", store.epoch("s", "a"), raw.map(::record), olderCursor = "older", complete = false, sessionUpdated = 7)
    assertEquals(raw.map { it.toMessage() }, store.newest("s", "a", 10))
    val info = store.info("s", "a")!!
    assertEquals(raw.last().getString("id"), info.watermark)
    assertEquals("older", info.olderCursor)
    assertFalse(info.historyComplete)
    assertEquals(7L, info.sessionUpdated)
    assertEquals(3, info.count)
    assertEquals(raw.map { it.getString("id") }, store.fingerprints("s", "a").map { it.first })
  }

  @Test fun inlineDataIsOmittedAndMarkedInsteadOfStored() {
    val image = "data:image/png;base64," + "A".repeat(10_000)
    val raw = user("look").put("files", JSONArray().put(JSONObject().put("uri", image).put("mime", "image/png").put("name", "shot.png")))
    store.replace("s", "a", store.epoch("s", "a"), records(raw), null, true, 0)
    val part = store.newest("s", "a", 1).single().parts.single { it.type == "file" }
    assertEquals(OMITTED_INLINE_DATA, part.path)
    assertEquals("shot.png", part.title)
    assertTrue(store.info("s", "a")!!.bytes < 2_000)
  }

  @Test fun appendOnlyExtendsTheWatermarkItWasComputedAgainst() {
    val first = user("one")
    store.replace("s", "a", store.epoch("s", "a"), records(first), null, true, 0)
    val second = assistant("two")
    assertTrue(store.append("s", "a", store.epoch("s", "a"), first.getString("id"), records(second)))
    // A second sync that read the same old watermark concurrently must not append again.
    assertFalse(store.append("s", "a", store.epoch("s", "a"), first.getString("id"), records(assistant("three"))))
    assertEquals(listOf("one", "two"), store.newest("s", "a", 10).map { it.parts.first().text })
  }

  @Test fun aReadThatFinishesAfterDeletionCannotResurrectTheSession() {
    val epoch = store.epoch("s", "a")
    store.delete("s", "a")
    assertFalse(store.replace("s", "a", epoch, records(user("ghost")), null, true, 0))
    assertNull(store.info("s", "a"))
    store.replace("s", "b", store.epoch("s", "b"), records(user("kept")), null, true, 0)
    val serverEpoch = store.epoch("s", "b")
    store.deleteServer("s")
    assertFalse(store.append("s", "b", serverEpoch, "x", records(user("ghost"))))
    assertEquals(0, store.usage().sessions)
  }

  @Test fun olderPagesArePrependedOnlyFromTheStoredCursor() {
    val newest = records(user("3"), assistant("4"))
    store.replace("s", "a", store.epoch("s", "a"), newest, olderCursor = "c1", complete = false, sessionUpdated = 0)
    val older = records(user("1"), assistant("2"))
    assertFalse(store.prepend("s", "a", store.epoch("s", "a"), "other", older, null, true))
    assertTrue(store.prepend("s", "a", store.epoch("s", "a"), "c1", older, null, true))
    assertEquals(listOf("1", "2", "3", "4"), store.newest("s", "a", 10).map { it.parts.first().text })
    assertTrue(store.info("s", "a")!!.historyComplete)
    val third = newest.first().id
    assertEquals(listOf("1", "2"), store.older("s", "a", third, 10).map { it.parts.first().text })
    assertEquals(listOf("2"), store.older("s", "a", third, 1).map { it.parts.first().text })
    assertTrue(store.hasOlder("s", "a", third))
    assertFalse(store.hasOlder("s", "a", store.newest("s", "a", 10).first().id))
    assertTrue(store.older("s", "a", "unknown", 10).isEmpty())
  }

  @Test fun correctionsOverwriteRowsInPlace() {
    val step = assistant("draft")
    store.replace("s", "a", store.epoch("s", "a"), records(user("q"), step), null, true, 0)
    val fixed = JSONObject(step.toString()).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "final")))
    assertTrue(store.append("s", "a", store.epoch("s", "a"), step.getString("id"), emptyList(), corrections = records(fixed)))
    assertEquals(listOf("q", "final"), store.newest("s", "a", 10).map { it.parts.first().text })
  }

  @Test fun aDifferentServerIdentityDropsThePreviousConversations() {
    store.bindServer("s", "https://a.example|opencode")
    store.replace("s", "a", store.epoch("s", "a"), records(user("from a")), null, true, 0)
    store.bindServer("s", "https://a.example|opencode")
    assertTrue(store.info("s", "a") != null)
    store.bindServer("s", "https://b.example|opencode")
    assertNull(store.info("s", "a"))
  }

  @Test fun aReadInFlightAcrossAnIdentityChangeIsNotWritten() {
    store.bindServer("s", "https://a.example|opencode")
    val epoch = store.epoch("s", "a")
    store.bindServer("s", "https://b.example|opencode")
    assertFalse(store.replace("s", "a", epoch, records(user("from a")), null, true, 0))
    assertNull(store.info("s", "a"))
  }

  /** Android binds query arguments as text; position comparisons must still be numeric. */
  @Test fun positionsCompareNumericallyWhenBoundAsText() {
    val inner = JdbcSqlDriver()
    val textDriver = object : SqlDriver {
      override fun execute(sql: String, args: Array<Any?>) = inner.execute(sql, args)
      override fun <T> query(sql: String, args: Array<Any?>, row: (SqlRow) -> T): List<T> = inner.query(sql, args.map { it?.toString() }.toTypedArray(), row)
      override fun <T> transaction(block: () -> T): T = inner.transaction(block)
    }
    val textStore = testStore(textDriver)
    val newest = records(user("10"), assistant("11"))
    textStore.replace("s", "a", textStore.epoch("s", "a"), newest, "c", false, 0)
    // Prepending moves positions below zero: "-1" < "0" must not be compared as text.
    val older = (1..9).map { record(assistant("$it")) }
    textStore.prepend("s", "a", textStore.epoch("s", "a"), "c", older, null, true)
    assertEquals((1..9).map { "$it" }, textStore.older("s", "a", newest.first().id, 100).map { it.parts.first().text })
    assertEquals(listOf("9", "10", "11"), textStore.newest("s", "a", 3).map { it.parts.first().text })
  }

  @Test fun aFormatChangeDiscardsEverythingWrittenByTheOldParser() {
    val driver = JdbcSqlDriver()
    val old = testStore(driver)
    old.replace("s", "a", old.epoch("s", "a"), records(user("old")), null, true, 0)
    driver.execute("UPDATE meta SET value = '0' WHERE key = 'format'")
    val reopened = testStore(driver)
    assertNull(reopened.info("s", "a"))
    assertEquals(0, reopened.usage().sessions)
  }

  @Test fun rowsSealedWithAnotherKeyAreMissesNotGarbage() {
    val driver = JdbcSqlDriver()
    val first = testStore(driver)
    first.replace("s", "a", first.epoch("s", "a"), records(user("secret")), null, true, 0)
    val other = TranscriptStore(driver, AesGcmPayloadCipher(SecretKeySpec(ByteArray(32) { 9 }, "AES")))
    assertTrue(other.newest("s", "a", 10).isEmpty())
  }

  @Test fun evictionDropsLeastRecentlyOpenedButNeverTheOpenConversation() {
    var now = 0L
    val small = testStore(clock = { now }, budget = 6_000)
    fun fill(session: String) {
      now += 1
      small.replace("s", session, small.epoch("s", session), (0 until 4).map { record(assistant("$session $it " + randomText(600))) }, null, true, 0)
    }
    fill("a"); fill("b")
    small.protectedSession = "s" to "a"
    fill("c")
    assertTrue("open conversation kept", small.info("s", "a") != null)
    assertNull("least recently opened evicted", small.info("s", "b"))
    assertTrue(small.info("s", "c") != null)
  }

  @Test fun payloadsAreCompressed() {
    store.replace("s", "a", store.epoch("s", "a"), records(assistant("x".repeat(50_000))), null, true, 0)
    assertTrue(store.info("s", "a")!!.bytes < 5_000)
  }

  @Test fun onlySettledMessagesQualify() {
    assertTrue(isSettledMessage(user("q")))
    assertTrue(isSettledMessage(assistant("a", toolStatus = "completed")))
    assertFalse("still streaming", isSettledMessage(assistant("a", completed = false)))
    assertFalse("tool still running", isSettledMessage(assistant("a", toolStatus = "running")))
    val background = assistant("a", toolStatus = "completed")
    background.getJSONArray("content").getJSONObject(1).getJSONObject("state").put("metadata", JSONObject().put("status", "running"))
    assertFalse("background work still reporting", isSettledMessage(background))
    assertFalse(isSettledMessage(JSONObject().put("id", "msg_1").put("type", "compaction").put("status", "running")))
    assertTrue(isSettledMessage(JSONObject().put("id", "msg_1").put("type", "compaction").put("status", "completed")))
    assertFalse(isSettledMessage(JSONObject().put("id", "msg_1").put("type", "shell").put("status", "running").put("time", JSONObject().put("created", 1))))
    assertTrue(isSettledMessage(JSONObject().put("id", "msg_1").put("type", "shell").put("status", "exited").put("time", JSONObject().put("created", 1).put("completed", 2))))
  }

  @Test fun fingerprintsFollowWhatIsRenderedNotWireNoise() {
    val a = assistant("same")
    val b = JSONObject(a.toString()).put("cost", 0.5).put("tokens", JSONObject().put("input", 10))
    assertEquals(record(a).fingerprint, record(b).fingerprint)
    val c = JSONObject(a.toString()).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "different")))
    assertTrue(record(a).fingerprint != record(c).fingerprint)
  }

  private fun randomText(length: Int): String { val random = java.util.Random(length.toLong()); return (0 until length).map { 'a' + random.nextInt(26) }.joinToString("") }
}
