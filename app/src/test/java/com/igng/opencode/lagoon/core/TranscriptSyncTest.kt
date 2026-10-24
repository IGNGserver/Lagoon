package com.igng.opencode.lagoon.core

import com.igng.opencode.lagoon.core.FakeTimelineServer.Companion.assistant
import com.igng.opencode.lagoon.core.FakeTimelineServer.Companion.conversation
import com.igng.opencode.lagoon.core.FakeTimelineServer.Companion.user
import com.igng.opencode.lagoon.core.TranscriptSync.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TranscriptSyncTest {
  private val store = testStore()
  private val sync = TranscriptSync(store, Dispatchers.Unconfined)
  private fun session(updated: Long = 1) = Session("ses", "/repo", "Task", updated)
  private fun FakeTimelineServer.truth() = timeline.map { it.toMessage() }
  private fun storedIds() = store.fingerprints("s", "ses").map { it.first }

  /** Shown window plus every older page the reader can still load, oldest first. */
  private suspend fun everything(server: FakeTimelineServer, window: TranscriptSync.Window): List<Message> {
    var messages = window.messages
    var cursor = window.olderCursor
    var guard = 0
    while (cursor != null && guard++ < 100) {
      val page = sync.loadOlder(server.api(), "s", session(), cursor)
      messages = page.messages + messages
      cursor = page.next
    }
    return messages
  }

  /**
   * Older pages – read from the server, or from the store after a reopen – are widened to whole turns
   * like the initial window: a page starting mid-turn would re-key the split turn's rows once its prompt
   * arrives, right where the reader is scrolling.
   */
  @Test fun olderPagesNeverStartMidTurn() = runBlocking {
    FakeTimelineServer().use { server ->
      // 38 messages per turn: neither 200-message pages nor the painted window line up with a prompt.
      server.timeline += conversation(turns = 14, steps = 37)
      repeat(2) { round ->
        val window = sync.open(server.api(), "s", session())
        assertFalse(needsOlderTurnRoot(window.messages))
        var shown = window.messages
        var cursor = window.olderCursor
        var pages = 0
        while (cursor != null && pages++ < 20) {
          val page = sync.loadOlder(server.api(), "s", session(), cursor)
          assertFalse("round $round page $pages starts mid-turn", needsOlderTurnRoot(page.messages))
          shown = page.messages + shown
          cursor = page.next
        }
        assertTrue("round $round paged at least once", pages > 0)
        assertEquals(server.truth(), shown)
      }
    }
  }

  @Test fun firstOpenReadsTheInitialWindowAndStoresItsSettledPart() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 3, steps = 4)
      server.timeline += assistant("working", completed = false)
      val window = sync.open(server.api(), "s", session())
      assertEquals(Outcome.FRESH, window.outcome)
      assertEquals(server.truth(), window.messages)
      assertEquals(server.timeline.dropLast(1).map { it.getString("id") }, storedIds())
      assertNull("a short window reached the session start", window.olderCursor)
    }
  }

  @Test fun reopeningAnUnchangedConversationCostsOneSmallRead() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 4, steps = 5)
      sync.open(server.api(), "s", session(updated = 9))
      server.queries.clear()
      val window = sync.open(server.api(), "s", session(updated = 9))
      assertEquals(Outcome.VERIFIED, window.outcome)
      assertEquals(1, window.requests)
      assertEquals("10", server.queries.single()["limit"])
      assertEquals(server.truth(), window.messages)
    }
  }

  @Test fun newMessagesAreAppendedWithoutRereadingHistory() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 10, steps = 10)
      sync.open(server.api(), "s", session(updated = 1))
      server.timeline += conversation(turns = 1, steps = 6)
      val window = sync.open(server.api(), "s", session(updated = 2))
      assertEquals(Outcome.VERIFIED, window.outcome)
      assertEquals(1, window.requests)
      assertEquals(7, window.added)
      assertEquals(server.timeline.map { it.getString("id") }.takeLast(storedIds().size), storedIds())
      assertEquals(server.truth(), everything(server, window))
    }
  }

  @Test fun aLongAbsenceWalksBackToTheWatermarkInLargePages() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 2, steps = 5)
      sync.open(server.api(), "s", session(updated = 1))
      server.timeline += conversation(turns = 30, steps = 9)
      val window = sync.open(server.api(), "s", session(updated = 2))
      assertEquals(Outcome.VERIFIED, window.outcome)
      assertEquals(3, window.requests)
      assertEquals(server.truth(), everything(server, window))
    }
  }

  @Test fun tooManyNewMessagesRebuildFromTheNewestWindowInstead() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 2, steps = 5)
      sync.open(server.api(), "s", session(updated = 1))
      server.timeline += conversation(turns = 100, steps = 9)
      val window = sync.open(server.api(), "s", session(updated = 2))
      assertEquals(Outcome.REBUILT, window.outcome)
      assertEquals(server.truth(), everything(server, window))
    }
  }

  @Test fun aRevertThatDeletedStoredMessagesRebuildsTheRun() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 5, steps = 4)
      sync.open(server.api(), "s", session(updated = 1))
      val removed = server.timeline.takeLast(5).map { it.getString("id") }
      repeat(5) { server.timeline.removeAt(server.timeline.size - 1) }
      server.timeline += conversation(turns = 1, steps = 1)
      val window = sync.open(server.api(), "s", session(updated = 2))
      assertEquals(Outcome.REBUILT, window.outcome)
      assertTrue(storedIds().none { it in removed })
      assertEquals(server.truth(), everything(server, window))
    }
  }

  @Test fun aStoredMessageTheServerNowRendersDifferentlyIsCorrected() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 3, steps = 3)
      sync.open(server.api(), "s", session(updated = 1))
      val index = server.timeline.size - 2
      server.timeline[index] = JSONObject(server.timeline[index].toString())
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "rewritten")))
      val window = sync.open(server.api(), "s", session(updated = 2))
      assertEquals(Outcome.CORRECTED, window.outcome)
      assertEquals(server.truth(), window.messages)
      assertEquals(server.truth(), store.newest("s", "ses", 100))
    }
  }

  @Test fun aMessageInsertedInsideTheStoredRunRebuildsIt() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 3, steps = 3)
      sync.open(server.api(), "s", session(updated = 1))
      server.timeline.add(server.timeline.size - 3, user("imported"))
      val window = sync.open(server.api(), "s", session(updated = 2))
      assertEquals(Outcome.REBUILT, window.outcome)
      assertEquals(server.truth(), everything(server, window))
    }
  }

  @Test fun aRunningStepIsStoredOnlyOnceItSettles() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 2, steps = 2)
      val step = assistant("partial", completed = false, toolStatus = "running")
      server.timeline += step
      sync.open(server.api(), "s", session(updated = 1))
      assertFalse(step.getString("id") in storedIds())
      server.timeline[server.timeline.size - 1] = assistant("final", id = step.getString("id"), toolStatus = "completed")
      val window = sync.open(server.api(), "s", session(updated = 2))
      assertEquals(Outcome.VERIFIED, window.outcome)
      assertEquals(step.getString("id"), storedIds().last())
      assertEquals("final", store.newest("s", "ses", 1).single().parts.first().text)
      assertEquals(server.truth(), window.messages)
    }
  }

  @Test fun olderHistoryComesFromTheStoreBeforeTheNetwork() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 60, steps = 9)
      // Store the whole history by paging it in once.
      var window = sync.open(server.api(), "s", session(updated = 1))
      everything(server, window)
      assertEquals(server.timeline.size, storedIds().size)
      assertTrue(store.info("s", "ses")!!.historyComplete)
      window = sync.open(server.api(), "s", session(updated = 1))
      val before = server.messageRequests.get()
      assertEquals(server.truth(), everything(server, window))
      assertEquals("history is served from the device", before, server.messageRequests.get())
    }
  }

  @Test fun tailRefreshesExtendTheRunButNeverBridgeAGap() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 2, steps = 3)
      sync.open(server.api(), "s", session())
      val api = server.api()
      server.timeline += conversation(turns = 1, steps = 2)
      var head = api.messageRecords("ses")
      sync.absorb("s", session(), sync.epoch("s", "ses"), head.items, head.next, head.items.size < 20)
      assertEquals(server.timeline.last().getString("id"), storedIds().last())
      val watermark = storedIds().last()
      server.timeline += conversation(turns = 3, steps = 9)
      head = api.messageRecords("ses")
      sync.absorb("s", session(), sync.epoch("s", "ses"), head.items, head.next, false)
      assertEquals("a page that does not reach the watermark is not stored", watermark, storedIds().last())
    }
  }

  /**
   * The changes an OpenCode server can make (`packages/core/src/session/store.ts` orders by an
   * insertion sequence): turns append, a live step settles in place, a revert removes a suffix of any
   * length, and a recent step may be re-rendered. After each, what the reader can see must be exactly
   * the server's timeline and the stored run a contiguous piece of it.
   */
  @Test fun theRunFollowsTheServerThroughRandomChanges() = runBlocking {
    val random = Random(20261023)
    repeat(25) { round ->
      val local = testStore()
      val localSync = TranscriptSync(local, Dispatchers.Unconfined)
      FakeTimelineServer().use { server ->
        server.timeline += conversation(turns = random.nextInt(1, 6), steps = random.nextInt(1, 8))
        repeat(12) { step ->
          when (random.nextInt(6)) {
            0 -> server.timeline += conversation(turns = random.nextInt(1, 4), steps = random.nextInt(0, 12))
            1 -> server.timeline += assistant("live", completed = false, toolStatus = "running")
            2 -> {
              // A live step settles in place.
              val last = server.timeline.lastIndex
              if (last >= 0 && server.timeline[last].optJSONObject("time")?.has("completed") == false)
                server.timeline[last] = assistant("settled", id = server.timeline[last].getString("id"), toolStatus = "completed")
            }
            3 -> repeat(minOf(random.nextInt(1, 6), server.timeline.size - 1)) { server.timeline.removeAt(server.timeline.lastIndex) }
            4 -> if (server.timeline.size > 2) {
              val index = server.timeline.size - 1 - random.nextInt(minOf(5, server.timeline.size - 1))
              val edited = JSONObject(server.timeline[index].toString())
              if (edited.getString("type") == "assistant") server.timeline[index] = edited.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "edit $round.$step")))
            }
            // A revert back to any earlier prompt.
            else -> if (server.timeline.size > 1) repeat(random.nextInt(1, server.timeline.size)) { server.timeline.removeAt(server.timeline.lastIndex) }
          }
          val window = localSync.open(server.api(), "s", session(updated = step.toLong()))
          var all = window.messages
          var cursor = window.olderCursor
          var guard = 0
          while (cursor != null && guard++ < 100) {
            val page = localSync.loadOlder(server.api(), "s", session(), cursor)
            all = page.messages + all
            cursor = page.next
          }
          val truth = server.truth()
          if (truth != all) {
            val ids = truth.map { it.id.takeLast(5) }
            val shown = all.map { it.id.takeLast(5) }
            val firstDiff = truth.indices.firstOrNull { it >= all.size || truth[it] != all[it] }
            throw AssertionError("round $round step $step (${window.outcome}, older=${window.olderCursor?.take(20)}): server=${ids} shown=${shown} firstDiff=$firstDiff " +
              "server@diff=${firstDiff?.let { truth.getOrNull(it)?.toString()?.take(300) }} shown@diff=${firstDiff?.let { all.getOrNull(it)?.toString()?.take(300) }}")
          }
          val stored = local.fingerprints("s", "ses").map { it.first }
          val serverIds = server.timeline.map { it.getString("id") }
          if (stored.isNotEmpty()) {
            val at = serverIds.indexOf(stored.first())
            assertTrue("stored run must be a contiguous run of the server timeline", at >= 0 && serverIds.subList(at, at + stored.size) == stored)
          }
        }
      }
    }
  }

  @Test fun aFullComparisonCatchesOldHistoryTheServerRewrote() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 40, steps = 9)
      everything(server, sync.open(server.api(), "s", session(updated = 1)))
      assertEquals(true, sync.verifyAll(server.api(), "s", session()))
      assertTrue(store.info("s", "ses")!!.verifiedAt > 0)
      // Rewriting a message far older than any page an open reads is invisible to opening...
      server.timeline[3] = JSONObject(server.timeline[3].toString()).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "rewritten")))
      assertEquals(Outcome.VERIFIED, sync.open(server.api(), "s", session(updated = 1)).outcome)
      // ...but not to the full comparison, which drops the run so the next open starts fresh.
      assertEquals(false, sync.verifyAll(server.api(), "s", session()))
      assertNull(store.info("s", "ses"))
      val window = sync.open(server.api(), "s", session(updated = 1))
      assertEquals(Outcome.FRESH, window.outcome)
      assertEquals(server.truth(), everything(server, window))
    }
  }

  @Test fun outcomesAndFullChecksAreCountedAndScheduled() = runBlocking {
    var now = 1_000L
    val clocked = TranscriptSync(store, Dispatchers.Unconfined, clock = { now })
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 2, steps = 2)
      clocked.open(server.api(), "s", session())
      clocked.open(server.api(), "s", session())
      assertFalse("just read in full", clocked.dueForFullCheck("s", "ses"))
      now += TranscriptSync.FULL_CHECK_INTERVAL_MS + 1
      assertTrue(clocked.dueForFullCheck("s", "ses"))
      assertEquals(true, clocked.verifyAll(server.api(), "s", session()))
      assertFalse(clocked.dueForFullCheck("s", "ses"))
      val line = clocked.statsLine()
      assertTrue(line, line.contains("首次 1") && line.contains("校验通过 1") && line.contains("完整校验 1（不一致 0）"))
    }
  }

  @Test fun aSessionDeletedMidReadIsNotStored() = runBlocking {
    FakeTimelineServer().use { server ->
      server.timeline += conversation(turns = 2, steps = 2)
      val epoch = sync.epoch("s", "ses")
      store.delete("s", "ses")
      val head = server.api().messageRecords("ses")
      sync.absorb("s", session(), epoch, head.items, null, true)
      assertNull(store.info("s", "ses"))
      assertNotNull(head)
    }
  }
}
