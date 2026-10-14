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

/** True for the scripts used by this app's own strings (CJK ideographs and full-width forms). */
private fun isCjk(character: Char): Boolean =
  character.code in 0x2E80..0x9FFF || character.code in 0xF900..0xFAFF || character.code in 0xFF00..0xFFEF
