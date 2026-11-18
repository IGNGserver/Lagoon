package com.igng.opencode.lagoon.core

import org.json.JSONObject

/**
 * Order-preserving micro-batch coalescer for high-frequency streaming delta events.
 *
 * Adjacent deltas targeting the exact same `(serverId, type, directory, sessionID, assistantMessageID, toolId)`
 * within a single ~144Hz frame window are concatenated once via [StringBuilder], turning bursts of
 * 10–30 SSE chunks into a single transcript projection and state emission while keeping non-delta
 * events as strict ordering barriers.
 */
internal object StreamingEventCoalescer {
  val DELTA_TYPES = setOf(
    "session.text.delta",
    "session.reasoning.delta",
    "session.tool.input.delta"
  )

  fun coalesce(events: List<Pair<String, ServerEvent>>): List<Pair<String, ServerEvent>> {
    if (events.size <= 1) return events
    val out = ArrayList<Pair<String, ServerEvent>>(events.size)
    var index = 0
    while (index < events.size) {
      val first = events[index]
      val (serverId, head) = first
      if (head.type !in DELTA_TYPES) {
        out += first
        index++
        continue
      }
      val headSession = head.properties.str("sessionID")
      val headAssistant = head.properties.str("assistantMessageID")
      val headTool = if (head.type == "session.tool.input.delta") head.properties.str("id") else ""
      var end = index + 1
      while (end < events.size) {
        val (nextServer, next) = events[end]
        if (nextServer != serverId || next.type != head.type || next.directory != head.directory) break
        if (next.properties.str("sessionID") != headSession) break
        if (next.properties.str("assistantMessageID") != headAssistant) break
        if (head.type == "session.tool.input.delta" && next.properties.str("id") != headTool) break
        end++
      }
      if (end == index + 1) {
        out += first
      } else {
        val mergedDelta = StringBuilder()
        var latestCreated = head.created
        for (k in index until end) {
          val ev = events[k].second
          mergedDelta.append(ev.properties.str("delta"))
          if (ev.created > latestCreated) latestCreated = ev.created
        }
        val mergedProps = JSONObject(head.properties.toString()).put("delta", mergedDelta.toString())
        out += serverId to head.copy(
          id = events[end - 1].second.id.ifBlank { head.id },
          properties = mergedProps,
          created = latestCreated
        )
      }
      index = end
    }
    return out
  }
}
