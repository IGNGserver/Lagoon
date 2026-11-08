package com.igng.opencode.lagoon.core

import java.io.IOException

/**
 * Transport-level failure detection.
 *
 * A relay/RPC/WebSocket drop is not a host contract error: the server did not reject the operation,
 * the path to it disappeared. Those failures are safe to retry for reads and must not be painted as
 * a permanent “partial sync” state. The raw upstream text (“transfer closed”, WebRTC/RPC internals)
 * is also kept out of user-facing toasts by [userFacingError].
 */
private val TRANSPORT_NOISE = Regex(
  """(?i)(transfer\s+closed|unexpected end of stream|websocket|webrtc|(?<![a-z])rtc(?![a-z])|""" +
    """rpc[ _-]?timeout|transport[ _-]?closed|connection reset|broken pipe|""" +
    """连接中断|连接已关闭|中继连接中断|加密中继已断开|实时通道中断)"""
)

/** Raw transport text that should be translated before it reaches a toast. */
internal fun Throwable.looksLikeTransportFailureText(): Boolean =
  generateSequence(this) { it.cause }.any { cause ->
    cause.message?.let(TRANSPORT_NOISE::containsMatchIn) == true ||
      (cause is ApiException && cause.code?.let(TRANSPORT_NOISE::containsMatchIn) == true)
  }

/** True when retrying the operation can succeed without any user action. */
internal fun Throwable.isRetryableTransportFailure(): Boolean =
  generateSequence(this) { it.cause }.any { cause ->
    when (cause) {
      is RemoteFailureException -> cause.retryable &&
        cause.kind in setOf(RemoteFailureKind.OFFLINE, RemoteFailureKind.NETWORK, RemoteFailureKind.SLOW_CONSUMER)
      is RemoteRpcException -> true
      is RemoteGatewayException -> cause.code.isTransportCode() || cause.message?.let(TRANSPORT_NOISE::containsMatchIn) == true
      // DshApi wraps a dead gateway in a 502; a direct transport failure uses status 0. Other 5xx
      // responses are server-side and also recover on their own, unlike a 4xx contract rejection.
      is ApiException -> cause.status == 0 ||
        (cause.status >= 500 && cause.code?.startsWith("gateway/") != true) ||
        cause.code.isTransportCode()
      else -> cause is IOException && cause.message?.let(TRANSPORT_NOISE::containsMatchIn) == true
    }
  }

private fun String?.isTransportCode(): Boolean {
  val value = this?.trim()?.lowercase().orEmpty()
  if (value.isEmpty()) return false
  if (value.startsWith("gateway/")) return false
  return value in setOf("transport_closed", "rpc_timeout", "connection_failed", "connection_replaced", "slow_consumer", "stream/closed") ||
    value.contains("rtc") || value.contains("transport")
}
