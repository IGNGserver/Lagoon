package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Transport-free question lifecycle. Execute returned actions; open attachWait only when needsClaim
 * and feed its opening remainingMs to acceptClaim. Tick even without a mounted composer. A failed
 * live answer must not be blindly retried as a continued answer: reconcile the projection first.
 */
internal class DshQuestionRegistry(private val maxEntries: Int = 512) {
  enum class Route { LIVE, CONTINUED, UNAVAILABLE }
  enum class ProjectionRead { MISSING, APPLIED }
  enum class ActionKind { RELEASE_CLAIM, DELEGATE, REJECT }
  data class Action(val kind: ActionKind, val sessionId: String, val eventId: String,
    val callId: String?, val code: String? = null)
  data class Snapshot(val id: String, val sessionId: String, val callId: String?,
    val eventId: String?, val questions: JSONArray, val state: String, val timed: Boolean,
    val visible: Boolean, val foreground: Boolean, val focused: Boolean, val engaged: Boolean,
    val held: Boolean, val claimed: Boolean, val remainingMs: Long?, val countdownRunning: Boolean,
    val queued: Boolean, val route: Route, val needsClaim: Boolean)
  private data class Entry(val id: String, val sessionId: String, val callId: String?,
    var questions: JSONArray, var eventId: String? = null, var timed: Boolean = false,
    var state: String = "open", var projected: Boolean = false, var queued: Boolean = false,
    var visible: Boolean = false, var foreground: Boolean = false, var focused: Boolean = false,
    var engaged: Boolean = false, var held: Boolean = false, var claimed: Boolean = false,
    var deadline: Long? = null, var remaining: Long? = null)
  private data class Row(val callId: String, val questions: JSONArray, val state: String)
  private val entries = LinkedHashMap<String, Entry>()
  init { require(maxEntries > 0) }

  /** Local card ID, never a wire eventId or callId. Length prefixes avoid delimiter collisions. */
  fun idFor(sessionId: String, callId: String): String =
    "dsh:question:${sessionId.length}:$sessionId:${callId.length}:$callId"
  private fun legacyId(sessionId: String, eventId: String) =
    "dsh:question-event:${sessionId.length}:$sessionId:${eventId.length}:$eventId"

  /** Store an actual forwarded event, without claiming or pausing the Host timer. */
  @Synchronized fun receiveWaterfall(sessionId: String, eventId: String, request: JSONObject): String {
    require(sessionId.isNotBlank() && eventId.isNotBlank())
    val questions = questions(request.opt("questions"))
    val waitValue = request.opt("wait")
    require(waitValue == null || waitValue is JSONObject) { "wait must be an object" }
    val wait = waitValue as? JSONObject
    val callId = wait?.let { string(it, "callId").also { value -> require(value.isNotBlank()) } }
    val timed = wait?.let {
      require(!it.has("timed") || it.opt("timed") is Boolean) { "wait.timed must be boolean" }
      it.opt("timed") == true
    } ?: false
    val id = if (callId == null) legacyId(sessionId, eventId) else idFor(sessionId, callId)
    val current = entries[id]
    require(current?.eventId == null || current.eventId == eventId) { "call already has a live event" }
    require(entries.values.none { it.eventId == eventId && it.id != id }) { "event identity conflict" }
    if (current == null) require(entries.size < maxEntries) { "question registry is full" }
    val entry = current ?: Entry(id, sessionId, callId, questions).also { entries[id] = it }
    entry.questions = questions
    entry.eventId = eventId
    entry.timed = timed
    return id
  }

  /**
   * Null is not loaded, NOT authoritative empty. Malformed snapshots fail before mutation.
   * Empty active prunes detached cards, never a live waterfall. Null inbox preserves queue knowledge.
   */
  @Synchronized fun reconcile(sessionId: String, projection: JSONObject?, inbox: JSONObject? = null): ProjectionRead {
    require(sessionId.isNotBlank())
    if (projection == null) return ProjectionRead.MISSING
    val active = projection.opt("active") as? JSONArray
      ?: throw IllegalArgumentException("userQuestions.active must be an array")
    val settled = projection.opt("settled") as? JSONArray
      ?: throw IllegalArgumentException("userQuestions.settled must be an array")
    for (index in 0 until settled.length()) {
      val row = settled.opt(index) as? JSONObject ?: throw IllegalArgumentException("settled row must be an object")
      require(string(row, "callId").isNotBlank())
      val answers = row.opt("answers") as? JSONArray ?: throw IllegalArgumentException("answers must be an array")
      for (answerIndex in 0 until answers.length()) {
        val answer = answers.opt(answerIndex) as? JSONObject ?: throw IllegalArgumentException("answer must be an object")
        string(answer, "id")
        val selected = answer.opt("selected") as? JSONArray ?: throw IllegalArgumentException("selected must be an array")
        for (selectedIndex in 0 until selected.length()) require(selected.opt(selectedIndex) is String)
        if (answer.has("custom")) string(answer, "custom")
      }
    }
    val rows = (0 until active.length()).map { index ->
      val row = active.opt(index) as? JSONObject ?: throw IllegalArgumentException("active row must be an object")
      val callId = string(row, "callId").also { require(it.isNotBlank()) }
      val state = string(row, "state").also { require(it == "open" || it == "continued") { "unknown question state: $it" } }
      Row(callId, questions(row.opt("questions")), state)
    }
    require(rows.map { it.callId }.toSet().size == rows.size) { "duplicate question callId" }
    val queued = inbox?.let(::queuedReplies)
    val ids = rows.map { idFor(sessionId, it.callId) }.toSet()
    val removable = entries.values.filter { it.sessionId == sessionId && it.id !in ids && it.eventId == null }.map { it.id }
    require(entries.size - removable.size + ids.count { it !in entries } <= maxEntries) { "question registry is full" }
    removable.forEach(entries::remove)
    entries.values.filter { it.sessionId == sessionId }.forEach { it.projected = false }
    for (row in rows) {
      val id = idFor(sessionId, row.callId)
      val entry = entries.getOrPut(id) { Entry(id, sessionId, row.callId, row.questions) }
      entry.questions = row.questions
      entry.state = row.state
      entry.projected = true
      if (queued != null) entry.queued = row.callId in queued
    }
    return ProjectionRead.APPLIED
  }

  /** Losing presentation ownership delegates the waterfall and releases any Host claim. */
  @Synchronized fun setPresentation(id: String, visible: Boolean, foreground: Boolean): List<Action> {
    val entry = entries[id] ?: return emptyList()
    entry.visible = visible
    entry.foreground = foreground
    if (visible && foreground) return emptyList()
    entry.focused = false
    return detach(entry, delegate = true)
  }

  /** A stale/inactive opening returns false: close that stream, do not hold the Host. */
  @Synchronized fun acceptClaim(id: String, eventId: String, remainingMs: Long, now: Long): Boolean {
    require(remainingMs >= 0) { "remainingMs must be nonnegative" }
    val entry = entries[id] ?: return false
    if (entry.eventId != eventId || !entry.timed || !entry.visible || !entry.foreground || entry.claimed) return false
    entry.claimed = true
    entry.remaining = if (entry.held) null else remainingMs
    entry.deadline = if (entry.focused || entry.engaged || entry.held) null else deadline(now, remainingMs)
    return true
  }

  @Synchronized fun focus(id: String, focused: Boolean, now: Long) {
    val entry = entries[id] ?: return
    if (entry.held || entry.engaged || ((!entry.visible || !entry.foreground) && focused)) return
    if (entry.focused == focused) return
    if (focused) {
      entry.remaining = remaining(entry, now)
      entry.deadline = null
    } else if (entry.claimed && entry.remaining != null) {
      entry.deadline = deadline(now, entry.remaining!!)
    }
    entry.focused = focused
  }

  @Synchronized fun engage(id: String, now: Long) {
    val entry = entries[id] ?: return
    if (!entry.visible || !entry.foreground) return
    entry.remaining = remaining(entry, now)
    entry.engaged = true
    entry.focused = false
    entry.deadline = null
  }

  @Synchronized fun takeTime(id: String) {
    val entry = entries[id] ?: return
    if (!entry.visible || !entry.foreground) return
    entry.held = true
    entry.focused = false
    entry.remaining = null
    entry.deadline = null
  }

  /** One-shot business timeout rejection, never a successful empty answer. */
  @Synchronized fun tick(now: Long): List<Action> {
    val actions = mutableListOf<Action>()
    entries.values.toList().forEach { entry ->
      val end = entry.deadline
      if (end != null && now >= end && !entry.focused && !entry.engaged && !entry.held) {
        entry.eventId?.let { actions += Action(ActionKind.REJECT, entry.sessionId, it, entry.callId, "ASK_TIMED_OUT") }
        actions += detach(entry, delegate = false)
      }
    }
    return actions
  }

  /** Late cancellation cannot erase an authoritative durable continued channel. */
  @Synchronized fun cancelWaterfall(eventId: String): List<Action> {
    val entry = entries.values.firstOrNull { it.eventId == eventId } ?: return emptyList()
    return detach(entry, delegate = false)
  }

  @Synchronized fun snapshot(id: String, now: Long): Snapshot? = entries[id]?.let { entry ->
    val route = when {
      entry.queued && entry.state == "continued" -> Route.UNAVAILABLE
      entry.eventId != null -> Route.LIVE
      entry.projected && entry.state == "continued" -> Route.CONTINUED
      else -> Route.UNAVAILABLE
    }
    Snapshot(entry.id, entry.sessionId, entry.callId, entry.eventId, JSONArray(entry.questions.toString()),
      entry.state, entry.timed, entry.visible, entry.foreground, entry.focused, entry.engaged,
      entry.held, entry.claimed, remaining(entry, now), entry.deadline != null, entry.queued, route,
      entry.timed && entry.eventId != null && entry.visible && entry.foreground && !entry.claimed && route == Route.LIVE)
  }

  @Synchronized fun snapshots(now: Long): List<Snapshot> = entries.keys.mapNotNull { snapshot(it, now) }
    .filterNot { it.queued && it.state == "continued" }

  @Synchronized fun removeSession(sessionId: String): List<Action> {
    val removed = entries.values.filter { it.sessionId == sessionId }
    val actions = removed.flatMap { detach(it, delegate = true) }
    removed.forEach { entries.remove(it.id) }
    return actions
  }

  /** Execute release/delegate actions before dropping transport ownership. */
  @Synchronized fun close(): List<Action> {
    val actions = entries.values.toList().flatMap { detach(it, delegate = true) }
    entries.clear()
    return actions
  }

  private fun detach(entry: Entry, delegate: Boolean): List<Action> {
    val eventId = entry.eventId ?: return emptyList()
    val actions = mutableListOf<Action>()
    if (delegate) actions += Action(ActionKind.DELEGATE, entry.sessionId, eventId, entry.callId)
    if (entry.claimed) actions += Action(ActionKind.RELEASE_CLAIM, entry.sessionId, eventId, entry.callId)
    entry.eventId = null
    entry.claimed = false
    entry.timed = false
    entry.deadline = null
    entry.remaining = null
    if (entry.callId == null) entries.remove(entry.id)
    return actions
  }

  private fun remaining(entry: Entry, now: Long): Long? = entry.deadline?.let {
    if (now >= it) 0L else if (now < 0 && it > Long.MAX_VALUE + now) Long.MAX_VALUE else it - now
  } ?: entry.remaining
  private fun deadline(now: Long, duration: Long) = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
  private fun string(value: JSONObject, key: String): String = value.opt(key) as? String
    ?: throw IllegalArgumentException("$key must be a string")
  private fun questions(value: Any?): JSONArray {
    val array = value as? JSONArray ?: throw IllegalArgumentException("questions must be an array")
    require(array.length() > 0) { "questions must not be empty" }
    for (index in 0 until array.length()) {
      val row = array.opt(index) as? JSONObject ?: throw IllegalArgumentException("question must be an object")
      string(row, "id"); string(row, "question")
      for (field in listOf("header", "detail")) if (row.has(field)) string(row, field)
      if (row.has("multiSelect")) require(row.opt("multiSelect") is Boolean)
      if (row.has("options")) {
        val options = row.opt("options") as? JSONArray ?: throw IllegalArgumentException("options must be an array")
        for (optionIndex in 0 until options.length()) {
          val option = options.opt(optionIndex) as? JSONObject ?: throw IllegalArgumentException("option must be an object")
          string(option, "label")
          if (option.has("description")) string(option, "description")
        }
      }
    }
    return JSONArray(array.toString())
  }
  private fun queuedReplies(inbox: JSONObject): Set<String> {
    val queued = mutableSetOf<String>()
    for (target in listOf("next-step", "next-turn")) {
      if (!inbox.has(target)) continue
      val messages = inbox.opt(target) as? JSONArray ?: throw IllegalArgumentException("inbox.$target must be an array")
      for (index in 0 until messages.length()) {
        val source = messages.optJSONObject(index)?.optJSONObject("source") ?: continue
        if (source.opt("kind") == "user-question-reply") queued += string(source, "callId")
      }
    }
    return queued
  }
}
