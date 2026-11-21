package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * One place that turns a thrown error into something worth showing a phone user.
 *
 * Cancellation is never a user-visible failure. Callers rethrow [CancellationException] before
 * reaching here, and this function refuses to render its message: a superseded refresh used to leak
 * the raw `StandaloneCoroutine was cancelled` into a toast.
 *
 * Messages written by this app (they contain CJK text, including the translated HTTP errors from
 * [OpenCodeApi]) and server contract messages ([ApiException]) pass through unchanged; platform
 * exceptions are mapped to short Chinese text and the raw message stays in diagnostics.
 */
internal fun userFacingError(error: Throwable, fallback: String = "操作失败"): String {
  if (error is CancellationException) return fallback
  val message = error.message?.trim().orEmpty()
  // A dead relay/handshake leaks library phrases such as “transfer closed” or “WebSocket” into the
  // message. They describe the path, not the operation, and are retried by the connection layer.
  if (error.looksLikeTransportFailureText()) return fallback
  // A session log written by a newer DSH is a version problem, not a corrupt conversation: the fix
  // is to reconnect the device so the managed instance restarts, not to retry the same read.
  if (error.isNewerDshSessionLog()) return NEWER_DSH_SESSION_LOG_TEXT
  if (error is ApiException && message.isNotBlank()) return message
  if (message.isNotBlank() && message.any(::isCjk)) return message
  return when (error) {
    is UnknownHostException -> "找不到服务器地址，请检查网络或地址"
    is ConnectException -> "无法连接到服务器，请检查地址与网络"
    is SocketTimeoutException -> "连接服务器超时，请稍后重试"
    is TimeoutException -> "操作超时，请稍后重试"
    is SSLException -> "TLS 连接失败，请检查证书或改用 HTTPS"
    else -> fallback
  }
}

/**
 * DSH refuses to interpret a session log that contains an event type it does not know (for example
 * `team/mode` written by a newer harness). Lagoon cannot repair a foreign log, but it can restart the
 * `dsh web` instance it manages after an upgrade; that is the only action offered to the user.
 */
internal fun Throwable.isNewerDshSessionLog(): Boolean =
  generateSequence(this) { it.cause }.any { cause ->
    val message = cause.message.orEmpty()
    message.contains("unknown to this harness") ||
      (message.contains("refusing to interpret the log") && message.contains("newer harness"))
  }

internal const val NEWER_DSH_SESSION_LOG_TEXT =
  "设备上的 DSH 实例版本落后于该会话，读不了这段日志；重新连接设备后会自动重启 Lagoon 托管的 DSH 实例"

/** True for the scripts used by this app's own strings (CJK ideographs and full-width forms). */
private fun isCjk(character: Char): Boolean =
  character.code in 0x2E80..0x9FFF || character.code in 0xF900..0xFAFF || character.code in 0xFF00..0xFFEF
