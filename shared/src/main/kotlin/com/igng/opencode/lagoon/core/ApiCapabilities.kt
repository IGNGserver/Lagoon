package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** Session operations whose route changed between OpenCode 2.0.x releases. */
enum class SessionAction { RENAME, UNREVERT }
data class ActionEndpoint(val method: String, val path: String)
/** Session update that accepts `{time: {archived}}`; [restorable] when `archived` may be sent as null to un-archive. */
data class ArchiveEndpoint(val endpoint: ActionEndpoint, val restorable: Boolean)

/**
 * Per-instance contract read from the server's `/openapi.json`. Routes that are stable across every
 * 2.x release (prompt, fork, delete, interrupt, compact, revert/stage, inbox, permission) are called
 * directly; only what moved or was added later is resolved here:
 * - rename: `POST /rename` (2.0.0–2.0.x) → `PATCH /api/session/{id}` with `title`;
 * - unrevert: `POST /revert/clear` → `DELETE /revert`;
 * - diff and view only exist on newer releases;
 * - archiving is not part of the published 2.x contract. It is offered only when the instance documents
 *   `time.archived` on its session update body ([ArchiveEndpoint]); otherwise archived sessions are only
 *   listed, never written.
 */
data class ApiCapabilities(
  val actions: Map<SessionAction, ActionEndpoint> = emptyMap(),
  val diff: Boolean = false,
  val sessionView: ActionEndpoint? = null,
  val archive: ArchiveEndpoint? = null,
  /** True when [fromDocument] read the instance's own OpenAPI document. */
  val documented: Boolean = false,
  /** Sessions can be permanently deleted. DeepSeek Harness only archives. */
  val deleteSession: Boolean = true,
  /**
   * Forking is offered on the phone. OpenCode keeps it off (revert covers "go back"); DeepSeek Harness
   * has no message-level revert, so a fork is its way to branch off a session.
   */
  val fork: Boolean = false
) {
  fun supports(action: SessionAction) = action in actions
  companion object {
    /** The current 2.x contract, used when the document cannot be read. */
    val BASELINE = ApiCapabilities(
      actions = mapOf(
        SessionAction.RENAME to ActionEndpoint("PATCH", "api/session/{id}"),
        SessionAction.UNREVERT to ActionEndpoint("DELETE", "api/session/{id}/revert")
      ),
      diff = true, sessionView = ActionEndpoint("POST", "api/session/{id}/view")
    )

    fun fromDocument(document: JSONObject): ApiCapabilities {
      val paths = document.optJSONObject("paths") ?: return BASELINE
      fun schema(value: JSONObject, depth: Int = 0): JSONObject {
        if (depth >= 15) return JSONObject()
        val ref = value.str("$" + "ref")
        if (ref.startsWith("#/")) {
          var resolved: Any = document
          for (key in ref.removePrefix("#/").split('/')) resolved = (resolved as? JSONObject)?.opt(key.replace("~1", "/").replace("~0", "~")) ?: return JSONObject()
          return schema(resolved as? JSONObject ?: JSONObject(), depth + 1)
        }
        // `str` reads a literal "null" as empty, so the null branch is matched on the raw string.
        val branch = value.arr("anyOf").objects().firstOrNull { it.optString("type") != "null" }
          ?: value.arr("oneOf").objects().firstOrNull { it.optString("type") != "null" }
        return if (branch != null) schema(branch, depth + 1) else value
      }
      fun body(op: JSONObject) = schema(op.obj("requestBody").obj("content").obj("application/json").obj("schema"))
      /** A session route `/api/session/{…}<suffix>` with [method] whose body accepts [fields] and requires nothing else. */
      fun endpoint(suffix: String, method: String, fields: Set<String> = emptySet()): ActionEndpoint? {
        val regex = Regex("^/api/session/\\{[^/}]+\\}" + Regex.escape(suffix) + "$")
        val path = paths.keys().asSequence().firstOrNull { regex.matches(it) && paths.obj(it).has(method.lowercase()) } ?: return null
        val input = body(paths.obj(path).obj(method.lowercase()))
        val required = input.arr("required")
        if ((0 until required.length()).any { required.optString(it) !in fields }) return null
        if (fields.any { !input.obj("properties").has(it) }) return null
        return ActionEndpoint(method, path.removePrefix("/"))
      }
      val actions = mutableMapOf<SessionAction, ActionEndpoint>()
      (endpoint("", "PATCH", setOf("title")) ?: endpoint("/rename", "POST", setOf("title")))?.let { actions[SessionAction.RENAME] = it }
      (endpoint("/revert", "DELETE") ?: endpoint("/revert/clear", "POST"))?.let { actions[SessionAction.UNREVERT] = it }
      val archive = paths.keys().asSequence().firstOrNull { Regex("^/api/session/\\{[^/}]+\\}$").matches(it) && paths.obj(it).has("patch") }?.let { path ->
        val input = body(paths.obj(path).obj("patch"))
        val required = input.arr("required")
        val raw = schema(input.obj("properties").optJSONObject("time") ?: return@let null).obj("properties").optJSONObject("archived") ?: return@let null
        if ((0 until required.length()).any { required.optString(it) != "time" }) return@let null
        val restorable = raw.optBoolean("nullable") || listOf("anyOf", "oneOf").any { key -> raw.arr(key).objects().any { it.optString("type") == "null" } } ||
          raw.optJSONArray("type")?.let { types -> (0 until types.length()).any { types.optString(it) == "null" } } == true
        ArchiveEndpoint(ActionEndpoint("PATCH", path.removePrefix("/")), restorable)
      }
      return ApiCapabilities(actions, endpoint("/diff", "GET") != null,
        endpoint("/view", "POST", setOf("idle")), archive, documented = true)
    }
  }
}
