package com.igng.opencode.lagoon.core

/**
 * Keeps [TranscriptStore] in step with the server for harnesses whose transcripts are cacheable
 * ([HarnessClient.cacheableTranscripts]). The controller paints from here and hands every
 * authoritative server read back, so the store only ever holds what the server returned.
 */
internal class TranscriptSync(private val store: TranscriptStore, private val clock: () -> Long = System::currentTimeMillis) {
  /** The newest stored messages of one conversation, oldest first; empty when nothing is stored. */
  fun paint(server: String, session: String, limit: Int = PAINT_WINDOW): List<Message> {
    val messages = store.newest(server, session, limit)
    if (messages.isNotEmpty()) store.touch(server, session)
    return messages
  }

  /**
   * Stores a contiguous window read from the server (oldest first) in place of what was stored:
   * its settled prefix, with [olderCursor] continuing older than its first message and [complete]
   * when that message starts the session. Returns how many messages were stored.
   */
  fun saveWindow(server: String, session: String, epoch: Long, window: List<MessageRecord>, olderCursor: String?, complete: Boolean, sessionUpdated: Long): Int {
    val records = settledPrefix(window) ?: return 0
    return if (store.replace(server, session, epoch, records, olderCursor, complete, sessionUpdated)) records.size else 0
  }

  fun epoch(server: String, session: String): Long = store.epoch(server, session)

  companion object {
    /** Messages painted when a conversation opens; older ones come from the store page by page. */
    const val PAINT_WINDOW = 120

    /**
     * The leading run of [window] the store may keep: every message settled, carrying wire JSON and
     * small enough to store. Null when a message lacks wire JSON (the protocol is not cacheable).
     */
    fun settledPrefix(window: List<MessageRecord>): List<CacheRecord>? {
      val records = ArrayList<CacheRecord>(window.size)
      for (item in window) {
        val raw = item.raw ?: return null
        if (!isSettledMessage(raw)) break
        records += cacheRecord(raw) ?: break
      }
      return records
    }
  }
}
