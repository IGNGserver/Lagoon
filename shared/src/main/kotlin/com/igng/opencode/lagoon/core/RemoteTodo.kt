package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of the Host's current whole todo list (the `todos` session projection).
 *
 * DSH replaces the whole list on every `todo_write` call, keeps it visible after `turn/end`, and
 * clears it when the next `turn/start` lands — so a list read here always belongs to the turn that
 * is running or just finished.
 */
data class RemoteTodo(val content: String, val status: String) {
  val done: Boolean get() = status == STATUS_DONE
  val active: Boolean get() = status == STATUS_ACTIVE

  companion object {
    const val STATUS_PENDING = "pending"
    const val STATUS_ACTIVE = "in_progress"
    const val STATUS_DONE = "completed"

    private val STATUSES = setOf(STATUS_PENDING, STATUS_ACTIVE, STATUS_DONE)

    /** A Host row whose content is blank cannot be shown; an unknown status reads as not started. */
    internal fun from(json: JSONObject): RemoteTodo? {
      val content = json.str("content").trim()
      if (content.isEmpty()) return null
      return RemoteTodo(content, json.str("status").takeIf { it in STATUSES } ?: STATUS_PENDING)
    }
  }
}

/** Completed/total counts behind the compact progress capsule. */
data class TodoProgress(val done: Int, val total: Int) {
  val complete: Boolean get() = total > 0 && done == total
}

fun List<RemoteTodo>.todoProgress(): TodoProgress = TodoProgress(count { it.done }, size)

/**
 * The `todos` projection value. A missing key means this Host exposes no todo list (capability
 * absent, e.g. an older Host or another harness); an explicit `null` means the Host cleared it.
 */
internal fun JSONObject.todoProjection(key: String = "todos"): List<RemoteTodo>? {
  if (!has(key)) return null
  val value = opt(key)
  if (value == null || value == JSONObject.NULL) return emptyList()
  return (value as? JSONArray)?.toRemoteTodos().orEmpty()
}

internal fun JSONArray.toRemoteTodos(): List<RemoteTodo> = objects().mapNotNull(RemoteTodo::from)
