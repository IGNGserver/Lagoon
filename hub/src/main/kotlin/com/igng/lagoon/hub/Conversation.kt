package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.HarnessClient
import com.igng.opencode.lagoon.core.Message
import com.igng.opencode.lagoon.core.MessagePart
import com.igng.opencode.lagoon.core.ServerEvent
import com.igng.opencode.lagoon.core.TranscriptProjection
import com.igng.opencode.lagoon.core.mergeTranscript
import com.igng.opencode.lagoon.core.needsOlderTurnRoot
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One conversation that at least one browser has open. The hub reads its newest window (widened to a
 * whole turn, plus undelivered inbox input), then applies every event of the session with the same
 * [TranscriptProjection] the phone uses, re-reading the tail where the projection asks to reconcile.
 * Browsers receive the window once ([resetFrame]) and afterwards only what changed ([TranscriptDiff]),
 * at most every [FLUSH_MS].
 *
 * Confined to the runtime's single thread: every method is called from it.
 */
internal class ConversationView(
  val serverId: String,
  val sessionId: String,
  private val scope: CoroutineScope,
  private val client: () -> HarnessClient,
  private val broadcast: (ConversationView, String, JSONObject) -> Unit
) {
  val watchers = mutableSetOf<String>()
  var releaseJob: Job? = null

  var messages: List<Message> = emptyList()
    private set
  private var sent: List<Message> = emptyList()
  private var cursor: String? = null
  private var loading = true
  private var error: String? = null
  private var loadJob: Job? = null
  private var flushJob: Job? = null
  private var reloadJob: Job? = null
  /** An event of this session arrived while a read was in flight: the read may predate it. */
  private var eventsDuringLoad = false

  fun start() { load(initial = true) }

  fun stop() {
    loadJob?.cancel(); flushJob?.cancel(); reloadJob?.cancel(); releaseJob?.cancel()
  }

  /** Everything a newly watching browser needs: the loaded window and where older history continues. */
  fun resetFrame(): JSONObject = JSONObject()
    .put("serverId", serverId).put("sessionId", sessionId)
    .put("messages", Codec.array(sent.map(Codec::message)))
    .put("cursor", cursor ?: JSONObject.NULL).put("loading", loading).put("error", error ?: JSONObject.NULL)

  fun onEvent(event: ServerEvent) {
    if (loading) eventsDuringLoad = true
    val projected = TranscriptProjection.apply(messages, event) ?: return
    if (projected.messages !== messages) { messages = projected.messages; scheduleFlush() }
    if (projected.reconcile) scheduleReload()
  }

  /** The live stream reconnected: events in between may be lost, so the tail is read again. */
  fun onReconnected() = scheduleReload()

  /** A prompt the hub just sent: shown at once, replaced by the server's copy with the same id. */
  fun optimistic(message: Message) {
    if (messages.any { it.id == message.id }) return
    messages = messages + message
    scheduleFlush()
  }

  fun dropOptimistic(id: String) {
    val next = messages.filterNot { it.id == id }
    if (next.size != messages.size) { messages = next; scheduleFlush() }
  }

  fun scheduleReload(delayMillis: Long = RELOAD_DEBOUNCE_MS) {
    if (reloadJob?.isActive == true || loadJob?.isActive == true) { eventsDuringLoad = true; return }
    reloadJob = scope.launch {
      delay(delayMillis)
      load(initial = false)
    }
  }

  private fun load(initial: Boolean) {
    loadJob?.cancel()
    eventsDuringLoad = false
    loadJob = scope.launch {
      try {
        val window = read(client())
        messages = mergeTranscript(if (initial) emptyList() else messages, window.messages, window.pending)
        if (initial) cursor = window.cursor
        error = null
        if (loading) {
          loading = false
          sent = messages
          watchers.forEach { broadcast(this@ConversationView, it, resetFrame()) }
        } else scheduleFlush()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (failure: Exception) {
        Diagnostics.warn("Conversation", "会话消息读取失败", failure)
        error = userFacingError(failure, "读取会话失败")
        if (loading) {
          loading = false
          sent = messages
          watchers.forEach { broadcast(this@ConversationView, it, resetFrame()) }
        }
      }
      // Events seen during the read may be older than what it returned; one more read settles it.
      if (eventsDuringLoad) { eventsDuringLoad = false; scheduleReload() }
    }
  }

  private class Window(val messages: List<Message>, val cursor: String?, val pending: List<Message>)

  private suspend fun read(client: HarnessClient): Window = coroutineScope {
    val page = async { client.messagesPage(sessionId) }
    val inbox = async { runCatching { client.inbox(sessionId) }.getOrDefault(emptyList()) }
    val first = page.await()
    // Official initial window: keep pulling older pages until the oldest boundary is a whole turn.
    val merged = first.items.toMutableList()
    var next = first.next
    var extra = 0
    while (next != null && extra < MAX_INITIAL_PAGES && needsOlderTurnRoot(merged)) {
      val older = client.messagesPage(sessionId, next, HarnessClient.MESSAGE_PAGE)
      if (older.items.isEmpty()) { next = older.next; break }
      merged.addAll(0, older.items)
      next = older.next
      extra++
    }
    Window(merged, next, inbox.await())
  }

  private fun scheduleFlush() {
    if (loading || flushJob?.isActive == true) return
    flushJob = scope.launch {
      delay(FLUSH_MS)
      val batch = TranscriptDiff.ops(sent, messages)
      sent = messages
      if (batch != null) watchers.forEach { broadcast(this@ConversationView, it, batch.put("serverId", serverId).put("sessionId", sessionId)) }
    }
  }

  companion object {
    const val FLUSH_MS = 100L
    const val RELOAD_DEBOUNCE_MS = 150L
    const val MAX_INITIAL_PAGES = 5
    /** A conversation nobody watches is kept this long, so switching back is instant. */
    const val RELEASE_AFTER_MS = 60_000L
  }
}

/**
 * The smallest set of operations that turns one transcript into the next, for the browser to apply:
 * `put` (new or restructured message), `part` (one changed part, by position), `append` (a part's text,
 * input or output grew — only the suffix travels, which keeps token streaming cheap), `del`, and `order` when
 * the id sequence changed in a way the operations alone do not express.
 */
object TranscriptDiff {
  private val appendable = listOf<Pair<String, (MessagePart) -> String>>("text" to { it.text }, "input" to { it.input }, "output" to { it.output })

  fun ops(old: List<Message>, next: List<Message>): JSONObject? {
    if (old === next) return null
    val ops = JSONArray()
    val oldById = old.associateBy { it.id }
    val nextIds = next.mapTo(LinkedHashSet()) { it.id }
    next.forEach { message ->
      val previous = oldById[message.id]
      when {
        previous === message || previous == message -> Unit
        previous == null -> ops.put(JSONObject().put("op", "put").put("message", Codec.message(message)))
        previous.copy(parts = emptyList()) == message.copy(parts = emptyList()) && previous.parts.map { it.id } == message.parts.map { it.id } ->
          message.parts.forEachIndexed { index, part ->
            val before = previous.parts[index]
            if (before != part) ops.put(partOp(message.id, index, before, part))
          }
        else -> ops.put(JSONObject().put("op", "put").put("message", Codec.message(message)))
      }
    }
    old.filter { it.id !in nextIds }.forEach { ops.put(JSONObject().put("op", "del").put("id", it.id)) }
    // What the browser ends up with after applying the ops: survivors in place, new ids appended.
    val implied = old.map { it.id }.filter { it in nextIds } + next.map { it.id }.filter { it !in oldById }
    val order = next.map { it.id }
    if (ops.length() == 0 && implied == order) return null
    return JSONObject().put("ops", ops).apply { if (implied != order) put("order", Codec.array(order)) }
  }

  private fun partOp(messageId: String, index: Int, before: MessagePart, after: MessagePart): JSONObject {
    for ((field, read) in appendable) {
      val old = read(before)
      val new = read(after)
      if (old == new || !new.startsWith(old)) continue
      // Only this field grew and nothing else about the part changed.
      val restored = when (field) {
        "text" -> after.copy(text = old)
        "input" -> after.copy(input = old)
        else -> after.copy(output = old)
      }
      if (restored == before) return JSONObject().put("op", "append").put("messageId", messageId).put("index", index)
        .put("field", field).put("text", new.substring(old.length))
    }
    return JSONObject().put("op", "part").put("messageId", messageId).put("index", index).put("part", Codec.part(after))
  }
}
