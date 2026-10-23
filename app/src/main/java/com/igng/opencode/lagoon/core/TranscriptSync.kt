package com.igng.opencode.lagoon.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Keeps [TranscriptStore] in step with the server for harnesses whose transcripts are cacheable
 * ([HarnessClient.cacheableTranscripts]) and serves conversations from it.
 *
 * Opening a stored conversation reads only the newest page (more only when many messages arrived
 * meanwhile) and checks it against the stored run before trusting the run: the newest stored message
 * must still be on the server, every stored message the page overlaps must still be there in the same
 * order, and each must render identically. Matching pages extend the run; anything else rebuilds it
 * from the page. Only server reads are written, so the store never holds a message the server lacks.
 *
 * This relies on how OpenCode keeps a timeline (`packages/core/src/session/store.ts`): messages are
 * ordered by an insertion sequence, so new ones only append, and a revert removes a suffix, which
 * always takes the watermark with it. Older stored rows outside the checked page are re-compared in
 * full only by [verifyAll]; a server that rewrote old history is caught there, not on every open.
 */
internal class TranscriptSync(
  private val store: TranscriptStore,
  /** Where blocking store calls run; serial in the app so a bind always precedes reads. */
  private val io: CoroutineContext = Dispatchers.IO,
  private val clock: () -> Long = System::currentTimeMillis
) {
  enum class Outcome {
    /** Nothing was stored; the window came from the server and was stored. */
    FRESH,
    /** The stored run matched the server and was extended with what is new. */
    VERIFIED,
    /** The stored run matched in structure; messages that render differently now were rewritten. */
    CORRECTED,
    /** The stored run disagreed with the server (deleted, reordered or unreachable) and was rebuilt. */
    REBUILT
  }

  /** A conversation window ready to show, oldest first. [olderCursor] may be a [localCursor]. */
  data class Window(val messages: List<Message>, val olderCursor: String?, val outcome: Outcome, val requests: Int, val added: Int)

  /** An older page for [loadOlder]: oldest first, then where to continue (null once the session start is shown). */
  data class Older(val messages: List<Message>, val next: String?)

  /** The newest stored messages of one conversation, oldest first; empty when nothing is stored. */
  suspend fun paint(server: String, session: String, limit: Int = PAINT_WINDOW): List<Message> = withContext(io) {
    val messages = store.newest(server, session, limit)
    if (messages.isNotEmpty()) store.touch(server, session)
    widenToTurn(server, session, messages)
  }

  /**
   * [paint] plus where older history continues: stored rows first (readable offline), then the
   * stored server cursor once the run is exhausted.
   */
  suspend fun paintWindow(server: String, session: String): Pair<List<Message>, String?> = withContext(io) {
    val messages = paint(server, session)
    val first = messages.firstOrNull() ?: return@withContext messages to null
    val info = store.info(server, session)
    messages to when {
      store.hasOlder(server, session, first.id) -> localCursor(first.id)
      info == null || info.historyComplete -> null
      else -> info.olderCursor
    }
  }

  /**
   * Prepends stored rows until [messages] starts exactly on a turn's prompt, as the official initial
   * window does, instead of in the middle of a reply. Runs on [io].
   */
  private fun widenToTurn(server: String, session: String, messages: List<Message>): List<Message> {
    var shown = messages
    var widened = 0
    while (shown.isNotEmpty() && needsOlderTurnRoot(shown) && widened < MAX_TURN_WIDEN) {
      val more = store.older(server, session, shown.first().id, HarnessClient.MESSAGE_PAGE)
      if (more.isEmpty()) break
      val root = more.indexOfLast(::isTurnRoot)
      if (root >= 0) return more.subList(root, more.size) + shown
      shown = more + shown
      widened++
    }
    return shown
  }

  /**
   * What to show of a window read from the server (oldest first) whose first [stored] messages are now
   * in the store: about [PAINT_WINDOW] of the newest, starting on a prompt, with older ones continued
   * from the store. Shows the whole window when its older part is not stored.
   */
  private fun present(window: List<Message>, stored: Int, olderCursor: String?): Pair<List<Message>, String?> {
    val keepFrom = (window.size - PAINT_WINDOW).coerceAtLeast(0)
    if (keepFrom == 0) return window to olderCursor
    val start = (keepFrom downTo 0).firstOrNull { isTurnRoot(window[it]) } ?: return window to olderCursor
    if (start == 0 || stored <= start) return window to olderCursor
    return window.subList(start, window.size) to localCursor(window[start].id)
  }

  fun epoch(server: String, session: String): Long = store.epoch(server, session)

  /**
   * Reads the conversation for display: verified against and merged with the stored run when there is
   * one, otherwise the official initial window. Everything settled that was read is stored.
   */
  suspend fun open(client: HarnessClient, server: String, session: Session): Window {
    val epoch = store.epoch(server, session.id)
    val (info, stored) = withContext(io) { store.info(server, session.id).let { it to if (it == null) emptyList() else store.fingerprints(server, session.id) } }
    if (info == null || stored.isEmpty()) return fresh(client, server, session, epoch)
    var requests = 0
    // An unchanged `time.updated` suggests nothing new: a short page is enough to confirm it.
    val headSize = if (session.updated > 0 && session.updated == info.sessionUpdated) HEAD_UNCHANGED else HEAD_CHANGED
    val head = client.messageRecords(session.id, null, headSize).also { requests++ }
    var window = head.items
    var cursor = head.next
    var reachedStart = head.items.size < headSize
    var walked = 0
    while (window.none { it.message.id == info.watermark } && !reachedStart && cursor != null && walked < MAX_WALK_PAGES && !walkedPast(window, info.watermark)) {
      val older = client.messageRecords(session.id, cursor, HarnessClient.HISTORY_PAGE).also { requests++ }
      window = older.items + window
      cursor = older.next
      reachedStart = older.items.size < HarnessClient.HISTORY_PAGE
      walked++
    }
    val olderCursor = cursor.takeUnless { reachedStart }
    val plan = reconcile(window, olderCursor, reachedStart, info, stored, mayGap = false)
    return withContext(io) { apply(server, session, epoch, plan, window, olderCursor, requests) }
  }

  /**
   * Folds a tail refresh (the newest page, oldest first) into the stored run. A page that does not
   * reach back to the stored watermark leaves the store as it is; the next [open] bridges the gap.
   */
  suspend fun absorb(server: String, session: Session, epoch: Long, window: List<MessageRecord>, olderCursor: String?, reachedStart: Boolean) {
    if (window.isEmpty()) return
    withContext(io) {
      val info = store.info(server, session.id)
      if (info == null) {
        TranscriptSync.settledPrefix(window)?.let { store.replace(server, session.id, epoch, it, olderCursor, reachedStart, session.updated) }
        return@withContext
      }
      val plan = reconcile(window, olderCursor, reachedStart, info, store.fingerprints(server, session.id), mayGap = true)
      if (plan !is Plan.Gap) apply(server, session, epoch, plan, window, olderCursor, 0)
    }
  }

  /**
   * Compares every stored row of one conversation with the server, newest to oldest in large pages,
   * and drops the run on any difference (the next open reads a fresh window). True when the run
   * matched, false when it was dropped, null when it is too long to finish within [MAX_VERIFY_PAGES].
   */
  suspend fun verifyAll(client: HarnessClient, server: String, session: Session): Boolean? {
    val epoch = store.epoch(server, session.id)
    val stored = withContext(io) { store.fingerprints(server, session.id) }
    if (stored.isEmpty()) return true
    val oldest = stored.first().first
    var window = emptyList<MessageRecord>()
    var cursor: String? = null
    var reachedStart = false
    var pages = 0
    do {
      val page = client.messageRecords(session.id, cursor, HarnessClient.HISTORY_PAGE)
      window = page.items + window
      cursor = page.next
      reachedStart = page.items.size < HarnessClient.HISTORY_PAGE
      pages++
    } while (window.none { it.message.id == oldest } && !reachedStart && cursor != null && pages < MAX_VERIFY_PAGES)
    val start = window.indexOfFirst { it.message.id == oldest }
    if (start < 0 && !reachedStart && cursor != null) return null
    val segment = if (start < 0) emptyList() else window.subList(start, minOf(start + stored.size, window.size))
    val matches = segment.size == stored.size && segment.zip(stored).all { (remote, local) ->
      remote.message.id == local.first && remote.raw?.let(::cacheRecord)?.fingerprint == local.second
    }
    return withContext(io) {
      if (store.epoch(server, session.id) != epoch) return@withContext null
      if (matches) { store.markVerified(server, session.id, clock()); true }
      else {
        Diagnostics.warn("TranscriptSync", "完整校验发现缓存与服务器不一致，已丢弃该会话缓存")
        store.delete(server, session.id)
        false
      }
    }
  }

  /** Continues history older than the shown window: stored rows first (no network needed), then the server. */
  suspend fun loadOlder(client: HarnessClient?, server: String, session: Session, cursor: String): Older {
    val localId = localId(cursor)
    if (localId != null) return withContext(io) {
      // The run was rebuilt under the open transcript: its stored cursor no longer continues from
      // what is shown, so stop here rather than skip messages; reopening reads a fresh window.
      if (!store.contains(server, session.id, localId)) return@withContext Older(emptyList(), null)
      val rows = store.older(server, session.id, localId, HarnessClient.HISTORY_PAGE)
      val oldest = rows.firstOrNull()?.id ?: localId
      val info = store.info(server, session.id)
      val next = when {
        store.hasOlder(server, session.id, oldest) -> localCursor(oldest)
        info == null || info.historyComplete -> null
        else -> info.olderCursor
      }
      Older(rows, next)
    }
    val api = client ?: throw IllegalStateException("请先连接服务器")
    val epoch = store.epoch(server, session.id)
    val page = api.messageRecords(session.id, cursor, HarnessClient.HISTORY_PAGE)
    val reachedStart = page.items.size < HarnessClient.HISTORY_PAGE
    val next = page.next.takeUnless { reachedStart }
    check(next != cursor) { "服务器返回了重复分页游标" }
    // Contiguous with the stored run only when read from the stored cursor; a page with an unsettled
    // message cannot be stored without breaking the run.
    val records = TranscriptSync.settledPrefix(page.items)?.takeIf { it.size == page.items.size }
    if (records != null && records.isNotEmpty()) withContext(io) {
      store.prepend(server, session.id, epoch, cursor, records, next, reachedStart)
    }
    return Older(page.items.map { it.message }, next)
  }

  private sealed interface Plan {
    /** The watermark was found and the overlap matched: rewrite [corrections], append [added]. */
    data class Extend(val corrections: List<CacheRecord>, val added: List<CacheRecord>, val watermark: String) : Plan
    /** Replace the stored run with the window's settled prefix. */
    data class Replace(val outcome: Outcome) : Plan
    /** The window is newer than the watermark with messages in between that were not read. */
    data object Gap : Plan
  }

  private fun reconcile(window: List<MessageRecord>, olderCursor: String?, reachedStart: Boolean, info: SyncInfo,
    stored: List<Pair<String, String>>, mayGap: Boolean): Plan {
    val end = window.indexOfFirst { it.message.id == info.watermark }
    if (end < 0) {
      // The watermark is gone if the read reached the session start or went older than it; otherwise
      // a tail refresh simply did not read far enough.
      return if (mayGap && !reachedStart && olderCursor != null && !walkedPast(window, info.watermark)) Plan.Gap else Plan.Replace(Outcome.REBUILT)
    }
    val overlap = window.subList(0, end + 1)
    val start = stored.indexOfFirst { it.first == overlap.first().message.id }
    val storedSegment = if (start >= 0) stored.subList(start, stored.size) else {
      // The window starts older than the stored run: the whole run must be the overlap's tail.
      if (overlap.size < stored.size) return Plan.Replace(Outcome.REBUILT)
      stored
    }
    val serverSegment = overlap.takeLast(storedSegment.size)
    if (serverSegment.map { it.message.id } != storedSegment.map { it.first }) {
      Diagnostics.warn("TranscriptSync", "缓存与服务器的消息序列不一致，已重建")
      return Plan.Replace(Outcome.REBUILT)
    }
    if (start < 0 && overlap.size > stored.size) {
      // Verified, and the window also holds older messages than the store: store the larger run.
      return Plan.Replace(Outcome.VERIFIED)
    }
    val corrections = ArrayList<CacheRecord>()
    for ((server, local) in serverSegment.zip(storedSegment)) {
      val raw = server.raw ?: return Plan.Replace(Outcome.REBUILT)
      if (!isSettledMessage(raw)) return Plan.Replace(Outcome.REBUILT)
      val record = cacheRecord(raw) ?: return Plan.Replace(Outcome.REBUILT)
      if (record.fingerprint != local.second) corrections += record
    }
    if (corrections.isNotEmpty()) Diagnostics.warn("TranscriptSync", "${corrections.size} 条缓存消息与服务器显示不同，已按服务器更正")
    val added = TranscriptSync.settledPrefix(window.subList(end + 1, window.size)).orEmpty()
    // Here the window starts inside the stored run: a window starting older was handled above.
    return Plan.Extend(corrections, added, info.watermark)
  }

  /** Runs on [io]. */
  private fun apply(server: String, session: Session, epoch: Long, plan: Plan, window: List<MessageRecord>, olderCursor: String?, requests: Int): Window {
    val fromServer = window.map { it.message }
    when (plan) {
      is Plan.Replace -> {
        val stored = TranscriptSync.settledPrefix(window)
          ?.takeIf { store.replace(server, session.id, epoch, it, olderCursor, olderCursor == null, session.updated, clock()) }?.size ?: 0
        val (shown, older) = present(fromServer, stored, olderCursor)
        return Window(shown, older, plan.outcome, requests, 0)
      }
      is Plan.Extend -> {
        store.append(server, session.id, epoch, plan.watermark, plan.added, plan.corrections, session.updated)
        store.touch(server, session.id)
        // Server copies for everything read, stored rows (already verified as a run) before them.
        val first = window.first().message.id
        val local = if (fromServer.size >= PAINT_WINDOW) emptyList() else store.older(server, session.id, first, PAINT_WINDOW - fromServer.size)
        val shown = widenToTurn(server, session.id, local + fromServer)
        val oldest = shown.first().id
        val info = store.info(server, session.id)
        val older = when {
          store.hasOlder(server, session.id, oldest) -> localCursor(oldest)
          // The append lost a race (the run changed under it): fall back to what this read knows.
          info == null -> olderCursor
          info.historyComplete -> null
          else -> info.olderCursor
        }
        return Window(shown, older, if (plan.corrections.isEmpty()) Outcome.VERIFIED else Outcome.CORRECTED, requests, plan.added.size)
      }
      Plan.Gap -> error("gap is not applied")
    }
  }

  /**
   * No stored run: the official initial window, the newest page widened until a prompt starts it, then
   * stored. Widening reads pages of [TURN_PAGE]: fewer round trips than the official 20 without paying
   * for a 200-message page on a slow link.
   */
  private suspend fun fresh(client: HarnessClient, server: String, session: Session, epoch: Long): Window {
    var requests = 0
    val head = client.messageRecords(session.id, null, HarnessClient.MESSAGE_PAGE).also { requests++ }
    var window = head.items
    var cursor = head.next
    var reachedStart = head.items.size < HarnessClient.MESSAGE_PAGE
    var extra = 0
    fun startsOnTurn() = !needsOlderTurnRoot(window.map { it.message }) ||
      window.subList(0, window.size - head.items.size + 1).any { isTurnRoot(it.message) }
    while (!reachedStart && cursor != null && extra < MAX_INITIAL_PAGES && window.isNotEmpty() && !startsOnTurn()) {
      val older = client.messageRecords(session.id, cursor, TURN_PAGE).also { requests++ }
      window = older.items + window
      cursor = older.next
      reachedStart = older.items.size < TURN_PAGE
      extra++
    }
    val olderCursor = cursor.takeUnless { reachedStart }
    val messages = window.map { it.message }
    // Start on the newest prompt at or before the head page, when one was read.
    val boundary = (window.size - head.items.size).coerceAtLeast(0)
    val start = if (needsOlderTurnRoot(messages)) (boundary downTo 0).firstOrNull { isTurnRoot(messages[it]) } ?: 0 else 0
    val stored = withContext(io) {
      TranscriptSync.settledPrefix(window)
        ?.takeIf { store.replace(server, session.id, epoch, it, olderCursor, olderCursor == null, session.updated, clock()) }?.size ?: 0
    }
    return if (start > 0 && stored > start) Window(messages.subList(start, messages.size), localCursor(messages[start].id), Outcome.FRESH, requests, 0)
      else Window(messages, olderCursor, Outcome.FRESH, requests, 0)
  }

  companion object {
    /** Messages shown when a conversation opens; older ones come from the store page by page. */
    const val PAINT_WINDOW = 120
    /** Newest page read to confirm an apparently unchanged conversation. */
    const val HEAD_UNCHANGED = 10
    /** Newest page read when the conversation changed since it was stored. */
    const val HEAD_CHANGED = 50
    /** Older pages (of [HarnessClient.HISTORY_PAGE]) read to reach the stored watermark before rebuilding instead. */
    const val MAX_WALK_PAGES = 4
    /** Extra pages the initial window may pull so it starts on a whole turn. */
    const val MAX_INITIAL_PAGES = 3
    /** Page size used to find the start of the newest turn. */
    const val TURN_PAGE = 50
    /** Stored pages added to the painted window so it starts on a whole turn. */
    const val MAX_TURN_WIDEN = 10
    /** Pages of [HarnessClient.HISTORY_PAGE] one full comparison may read. */
    const val MAX_VERIFY_PAGES = 10

    /** A prompt or a shell command: the first row of a turn in the official timeline. */
    fun isTurnRoot(message: Message) = message.role == "user" || message.role == "shell"

    private const val LOCAL_PREFIX = "lagoon-local:"
    /** A [Window.olderCursor] that continues from stored rows older than [id]. */
    fun localCursor(id: String) = LOCAL_PREFIX + id
    fun localId(cursor: String?): String? = cursor?.takeIf { it.startsWith(LOCAL_PREFIX) }?.removePrefix(LOCAL_PREFIX)

    /**
     * True when [window] already reaches older than [watermark] by id. Message ids sort by creation
     * time, so the watermark should have appeared; a skewed client clock can only make this report a
     * rebuild that was not needed, never keep a stored message the server lacks.
     */
    fun walkedPast(window: List<MessageRecord>, watermark: String): Boolean = window.firstOrNull()?.message?.id?.let { it < watermark } == true

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
