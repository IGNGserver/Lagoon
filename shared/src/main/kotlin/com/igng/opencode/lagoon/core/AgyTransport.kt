package com.igng.opencode.lagoon.core

import java.io.Closeable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import okhttp3.WebSocket
import org.json.JSONObject

/** AGY operations over a carrier; calls never replay an uncertain write. */
interface AgyTransport : Closeable {
  val remote: Boolean get() = false
  val isClosed: Boolean get() = false
  /** Raw capability response from initialize, after the first call; null before initialization. */
  val initialization: JSONObject? get() = null
  suspend fun call(method: String, params: JSONObject): JSONObject
  /** Returns after the prompt frame is written; its result arrives after the turn on native ACP. */
  suspend fun beginPrompt(params: JSONObject): Deferred<JSONObject>
  val promptCompletesOnResponse: Boolean get() = true
  val filesAvailable: Boolean get() = false
  val terminalAvailable: Boolean get() = false
  suspend fun discoverCapabilities() = Unit
  suspend fun health() { call("session/list", JSONObject()) }
  suspend fun terminalCall(endpoint: String, args: JSONObject): Any? = throw ApiException(501, "此 AGY 连接不支持终端")
  suspend fun terminalOpen(endpoint: String, args: JSONObject, onItem: (JSONObject) -> Unit, onClosed: (Throwable?) -> Unit): WebSocket =
    throw ApiException(501, "此 AGY 连接不支持终端")
  suspend fun verifyDirectory(path: String): String = throw ApiException(501, "此 AGY 连接不支持目录验证")
  suspend fun files(session: Session, path: String): List<FileNode> = throw ApiException(501, "此 AGY 连接不支持文件浏览")
  suspend fun fileContent(session: Session, path: String): FileContent = throw ApiException(501, "此 AGY 连接不支持文件读取")
  suspend fun notify(method: String, params: JSONObject)
  suspend fun respond(requestId: Any, result: JSONObject)
  suspend fun respondError(requestId: Any, code: Int, message: String)
  suspend fun subscribe(sessionId: String)
  suspend fun unsubscribe(sessionId: String)
  /** Wait until already-delivered session updates have been projected by the sole event owner. */
  suspend fun syncEvents() = Unit
  fun events(): Flow<JSONObject>
}
