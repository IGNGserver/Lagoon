package com.igng.opencode.lagoon.core

import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * A connected Codex app-server channel. Calls return the JSON-RPC result object (not the
 * envelope). Server-initiated requests retain their original string or numeric id in events.
 * A carrier must never retry a call whose outcome is unknown.
 */
interface CodexTransport : Closeable {
  /** Remote Host exposes a restricted method set and its own history projection. */
  val remote: Boolean get() = false
  /** Version reported by this initialized server process, or null when unknown or closed. */
  val serverVersion: String? get() = null
  /** True after the channel has terminated; it must not be reused for new calls. */
  val isClosed: Boolean get() = false
  suspend fun call(method: String, params: JSONObject): JSONObject
  suspend fun respond(requestId: Any, result: JSONObject)
  /** Reject a server request; restricted transports may not expose this operation. */
  suspend fun respondError(requestId: Any, code: Int, message: String): Unit =
    throw ApiException(501, "Codex server request errors are not supported by this transport")
  /** Subscribe to a thread's notifications without owning or stopping the remote thread. */
  suspend fun subscribe(threadId: String)
  suspend fun unsubscribe(threadId: String)
  /** JSON-RPC notifications and server requests, normalized to {method, params, id?}. */
  fun events(): Flow<JSONObject>
}
