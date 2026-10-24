package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptScrollTest {
  @Test fun atBottomOnlyWhenTheNewestItemIsWithinTheSlop() {
    assertTrue(TranscriptScroll.atBottom(firstVisibleIndex = 0, firstVisibleOffset = 0, slopPx = 48))
    assertTrue(TranscriptScroll.atBottom(0, 48, 48))
    assertFalse("scrolled up within the newest item", TranscriptScroll.atBottom(0, 49, 48))
    assertFalse("the newest item is below the viewport", TranscriptScroll.atBottom(1, 0, 48))
  }

  @Test fun nearOldestPrefetchesAboutOneScreenAhead() {
    // 10 items visible out of 100: the oldest visible must be within 10 of the last index (99).
    assertFalse(TranscriptScroll.nearOldest(lastVisibleIndex = 88, visibleCount = 10, totalItems = 100))
    assertTrue(TranscriptScroll.nearOldest(89, 10, 100))
    assertTrue(TranscriptScroll.nearOldest(99, 10, 100))
    assertFalse("nothing laid out yet", TranscriptScroll.nearOldest(0, 0, 0))
  }

  /** A short conversation that does not fill the screen sees every item, including the far end. */
  @Test fun aShortConversationIsAlwaysNearItsOldestItem() {
    assertTrue(TranscriptScroll.nearOldest(lastVisibleIndex = 4, visibleCount = 5, totalItems = 5))
  }

  @Test fun olderPagesLoadOncePerCursorAndOnlyWhenReadable() {
    fun load(near: Boolean = true, readable: Boolean = true, loading: Boolean = false, cursor: String? = "c1", attempted: String? = null, rows: Boolean = true) =
      TranscriptScroll.shouldLoadOlder(near, readable, loading, cursor, attempted, rows)
    assertTrue(load())
    assertFalse("not near the far end", load(near = false))
    assertFalse("offline without stored history", load(readable = false))
    assertFalse("a page is already loading", load(loading = true))
    assertFalse("history fully shown", load(cursor = null))
    assertFalse("rows not laid out yet", load(rows = false))
    assertFalse("a failed cursor is not retried in a loop", load(attempted = "c1"))
    assertTrue("the next page after a success", load(cursor = "c2", attempted = "c1"))
  }
}
