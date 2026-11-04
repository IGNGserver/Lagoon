package com.igng.opencode.lagoon.core

import java.io.IOException

/** User-actionable Remote failure. UI may key recovery on [kind], never on a message string. */
internal enum class RemoteFailureKind {
  OFFLINE, AUTH_REQUIRED, DEVICE_REVOKED, HOST_NOT_FOUND,
  TRUST_REQUIRED, TRUST_CHANGED, CONNECTION_REPLACED,
  INCOMPATIBLE, REJECTED, RATE_LIMITED, NETWORK
}

internal class RemoteFailureException(
  val kind: RemoteFailureKind,
  message: String,
  val retryable: Boolean = false,
  cause: Throwable? = null
) : IOException(message, cause) {
  val actionLabel: String
    get() = when (kind) {
      RemoteFailureKind.OFFLINE -> "重试"
      RemoteFailureKind.AUTH_REQUIRED, RemoteFailureKind.DEVICE_REVOKED -> "重新登录"
      RemoteFailureKind.HOST_NOT_FOUND -> "刷新主机"
      RemoteFailureKind.TRUST_REQUIRED, RemoteFailureKind.TRUST_CHANGED -> "核对指纹"
      RemoteFailureKind.CONNECTION_REPLACED -> "重新连接"
      RemoteFailureKind.INCOMPATIBLE -> "查看更新方法"
      RemoteFailureKind.RATE_LIMITED -> "稍后重试"
      RemoteFailureKind.REJECTED -> "查看主机"
      RemoteFailureKind.NETWORK -> "立即重试"
    }
}

internal fun remoteFailure(error: Throwable): RemoteFailureException? =
  generateSequence(error) { it.cause }.filterIsInstance<RemoteFailureException>().firstOrNull()

/** Maps the control-plane REST status/code without guessing from a version string. */
internal fun remoteServerFailure(error: RemoteServerException): RemoteFailureException = when {
  error.status == 401 -> RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "DSH Remote 登录已失效", retryable = false, cause = error)
  error.status == 403 -> RemoteFailureException(RemoteFailureKind.REJECTED, "主机拒绝了这台手机", retryable = false, cause = error)
  error.status == 404 -> RemoteFailureException(RemoteFailureKind.HOST_NOT_FOUND, "账号下找不到这台主机", retryable = false, cause = error)
  error.status == 429 -> RemoteFailureException(RemoteFailureKind.RATE_LIMITED, "请求过于频繁，请稍后重试", retryable = true, cause = error)
  error.status >= 500 -> RemoteFailureException(RemoteFailureKind.NETWORK, "DSH Remote 服务暂时不可用", retryable = true, cause = error)
  else -> RemoteFailureException(RemoteFailureKind.NETWORK, "DSH Remote 请求失败", retryable = error.retryable, cause = error)
}
