package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/** The follow's initial snapshot is immutable; retain only the newest native change announcements. */
internal class DshChangeAnnouncements {
  private val sessions = object : LinkedHashMap<String, java.util.TreeMap<Long, JSONObject>>(16, 0.75f, true) {}
  @Synchronized fun record(sessionId: String, event: JSONObject) {
    if (event.opt("type") != "workspace/changes") return
    val number = event.opt("seq") as? Number ?: return
    val sequence = try { java.math.BigDecimal(number.toString()).longValueExact() } catch (_: Exception) { return }
    if (sequence !in 1..9_007_199_254_740_991L) return
    val recent = sessions.getOrPut(sessionId) { java.util.TreeMap() }
    recent[sequence] = JSONObject(event.toString())
    while (recent.size > 8) recent.pollFirstEntry()
    while (sessions.size > 64) sessions.remove(sessions.keys.first())
  }
  @Synchronized fun snapshot(sessionId: String, initial: JSONObject): JSONObject {
    val result = JSONObject(initial.toString())
    val original = result.opt("records") as? JSONArray ?: return result
    val sequences = mutableSetOf<Long>()
    for (i in 0 until original.length()) {
      val event = (original.opt(i) as? JSONObject)?.opt("event") as? JSONObject ?: continue
      if (event.opt("type") == "workspace/changes") {
        // Leave malformed originals untouched so the shared parser still rejects them.
        record(sessionId, event)
        (event.opt("seq") as? Number)?.toLong()?.let(sequences::add)
      }
    }
    sessions[sessionId]?.forEach { (sequence, event) ->
      if (sequence !in sequences) original.put(JSONObject().put("type", "event").put("event", JSONObject(event.toString())))
    }
    return result
  }
  @Synchronized fun clear() { sessions.clear() }
}
