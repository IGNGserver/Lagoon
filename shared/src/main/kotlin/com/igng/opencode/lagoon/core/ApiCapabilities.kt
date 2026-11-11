package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** Session actions gated by compatible routes in the instance's OpenAPI document. */
enum class SessionAction { RENAME, UNREVERT, REVERT }
data class ActionEndpoint(val method: String, val path: String)
/**
 * The session update that accepts a top-level `archived` boolean (`PATCH /api/session/{id}`, OpenCode
 * ≥ 2.1.0). [restorable] is true when the same field accepts `false`, which is how the server restores a
 * session — never by sending `null`.
 */
data class ArchiveEndpoint(val endpoint: ActionEndpoint, val restorable: Boolean = true)

/**
 * Per-instance contract read from the server's `/openapi.json`. Routes that are stable across every
 * 2.x release (prompt, delete, interrupt, compact, inbox, permission) are called directly;
 * discoverable actions are gated by the instance's documented compatible routes:
 * - rename: `POST /rename` (2.0.0–2.0.x) → `PATCH /api/session/{id}` with `title`;
 * - unrevert: `POST /revert/clear` → `DELETE /revert`;
 * - revert: `POST /revert/stage` with `messageID`;
 * - fork: `POST /fork` accepting an empty object (optional `before` selects the history boundary);
 * - diff and view only exist on newer releases;
 * - archiving was added in 2.1.0 as a top-level `archived` boolean on the session update body. It is
 *   offered only when the instance documents that field ([ArchiveEndpoint]); 2.0.x has no way to archive.
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
  /** Native whole-history fork; OpenCode documents a POST `/fork` accepting an empty JSON object. */
  val fork: Boolean = false,
  /** DSH job/follow, independent of direct/SSH/Remote transport. */
  val jobOutputStream: Boolean = false,
  /** The protocol can terminate a background job; independent of the connection carrier. */
  val stopShell: Boolean = false,
  /** Native PTY routes plus the documented single-use WebSocket ticket handshake. Never assumed. */
  val terminal: Boolean = false,
  /** Native Host workspace registration management, independent of direct/SSH/Remote carrier. */
  val workspaceManagement: Boolean = false,
  /** Native authorized Session content search; no OpenCode route is assumed. */
  val sessionContentSearch: Boolean = false
) {
  fun supports(action: SessionAction) = action in actions
  companion object {
    /** The current 2.x contract, used when the document cannot be read. */
    val BASELINE = ApiCapabilities(
      actions = mapOf(
        SessionAction.RENAME to ActionEndpoint("PATCH", "api/session/{id}"),
        SessionAction.UNREVERT to ActionEndpoint("DELETE", "api/session/{id}/revert"),
        SessionAction.REVERT to ActionEndpoint("POST", "api/session/{id}/revert/stage")
      ),
      diff = true, sessionView = ActionEndpoint("POST", "api/session/{id}/view"),
      archive = ArchiveEndpoint(ActionEndpoint("PATCH", "api/session/{id}")),
      fork = true, stopShell = true
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
      endpoint("/revert/stage", "POST", setOf("messageID"))?.let { actions[SessionAction.REVERT] = it }
      /*
       * Archiving is a plain boolean on the session update body (`archived: true` archives, `false`
       * restores). The body may only ever need that one field from us, so any other required sibling
       * means the instance expects a shape we do not write.
       */
      val archive = paths.keys().asSequence().firstOrNull { Regex("^/api/session/\\{[^/}]+\\}$").matches(it) && paths.obj(it).has("patch") }?.let { path ->
        val input = body(paths.obj(path).obj("patch"))
        val required = input.arr("required")
        if ((0 until required.length()).any { required.optString(it) != "archived" }) return@let null
        val raw = input.obj("properties").optJSONObject("archived") ?: return@let null
        ArchiveEndpoint(ActionEndpoint("PATCH", path.removePrefix("/")), restorable = acceptsFalse(raw))
      }
      val stopShell = paths.keys().asSequence().any { path ->
        Regex("^/api/shell/\\{[^/}]+\\}$").matches(path) && paths.obj(path).has("delete")
      }
      fun compatibleBody(op: JSONObject, fields: Map<String, String>): Boolean {
        val input = body(op)
        val required = input.arr("required")
        // Rename and resize are separate writes; neither may require its absent sibling.
        if (required.length() != 0) return false
        return fields.all { (name, type) ->
          val raw = input.obj("properties").optJSONObject(name) ?: return@all false
          schema(raw).str("type") == type
        }
      }
      fun emptyBody(op: JSONObject): Boolean {
        val required = body(op).arr("required")
        return required.length() == 0
      }
      val pty = paths.optJSONObject("/api/pty")
      val terminal = pty?.optJSONObject("get") != null && pty.optJSONObject("post")?.let(::emptyBody) == true &&
        paths.keys().asSequence().filter { Regex("^/api/pty/\\{[^/}]+\\}$").matches(it) }.any { path ->
          val item = paths.obj(path)
          val put = item.optJSONObject("put")
          val tokenPath = paths.optJSONObject("$path/connect-token")
          val token = tokenPath?.optJSONObject("post")
          val connect = paths.obj("$path/connect").optJSONObject("get")
          val parameters = listOfNotNull(tokenPath, token).flatMap { it.arr("parameters").objects() }.map(::schema)
          val ticketHeader = parameters.any {
            it.str("in") == "header" && it.str("name").equals("x-opencode-ticket", ignoreCase = true)
          }
          val size = put?.let { schema(body(it).obj("properties").obj("size")) }
          item.optJSONObject("get") != null && item.optJSONObject("delete") != null && put != null &&
            compatibleBody(put, mapOf("title" to "string", "size" to "object")) &&
            size != null && listOf("cols", "rows").all { schema(size.obj("properties").obj(it)).str("type") in setOf("number", "integer") } &&
            token != null && emptyBody(token) && ticketHeader && connect != null
        }
      return ApiCapabilities(actions, endpoint("/diff", "GET") != null,
        endpoint("/view", "POST", setOf("idle")), archive, documented = true,
        fork = endpoint("/fork", "POST") != null, stopShell = stopShell, terminal = terminal)
    }

    /**
     * Whether a property schema admits `false`, which is how the server restores a session. Unwrapping the
     * nullable/union branches matters: the published schema is `boolean | null`, never a bare boolean.
     */
    private fun acceptsFalse(raw: JSONObject): Boolean {
      val type = raw.opt("type")
      val types = when (type) {
        is String -> listOf(type)
        is org.json.JSONArray -> (0 until type.length()).map { type.optString(it) }
        else -> emptyList()
      }
      if ("boolean" in types) return true
      raw.optJSONArray("enum")?.let { values -> return (0 until values.length()).any { values.opt(it) == false } }
      return listOf("anyOf", "oneOf").any { key -> raw.arr(key).objects().any(::acceptsFalse) }
    }
  }
}
