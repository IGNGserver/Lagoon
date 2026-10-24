package com.igng.opencode.lagoon.ui

/**
 * Scroll rules of the conversation list. The list is laid out reversed: index 0 is the newest item and
 * sits at the bottom edge, older history extends towards the end of the list (the top of the screen).
 * Kept free of Compose so the rules are unit-tested; [Conversation] feeds them from its LazyListState.
 */
internal object TranscriptScroll {
  /** Minimum interval between two transcript row rebuilds while messages stream in. */
  const val ROWS_THROTTLE_MS = 50L

  /**
   * At the newest message: the bottom-most item is the newest one and at most [slopPx] of it is
   * scrolled out below the viewport. In a reversed list a growing newest item keeps its bottom edge
   * pinned, so a streaming reply stays in view without any scroll call.
   */
  fun atBottom(firstVisibleIndex: Int, firstVisibleOffset: Int, slopPx: Int): Boolean =
    firstVisibleIndex == 0 && firstVisibleOffset <= slopPx

  /**
   * The oldest visible item is within about one screen (the number of items now visible) of the far
   * end of the list, so the next older page should already be requested. True as well when the
   * content does not fill the screen, which a "reached the very top" rule could never detect.
   */
  fun nearOldest(lastVisibleIndex: Int, visibleCount: Int, totalItems: Int): Boolean =
    totalItems > 0 && lastVisibleIndex >= totalItems - 1 - visibleCount.coerceAtLeast(1)

  /**
   * Whether to request the page continuing at [cursor]. [attemptedCursor] is the cursor already
   * requested while the reader stayed near the oldest item: a page that failed is not retried in a
   * loop, only after the reader scrolls away and back. Rows must be on screen first, so opening a
   * conversation does not fetch history before its newest page has even been laid out.
   */
  fun shouldLoadOlder(nearOldest: Boolean, readable: Boolean, loading: Boolean, cursor: String?, attemptedCursor: String?, hasRows: Boolean): Boolean =
    nearOldest && readable && !loading && hasRows && cursor != null && cursor != attemptedCursor
}
