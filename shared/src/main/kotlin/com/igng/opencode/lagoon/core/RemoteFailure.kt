package com.igng.opencode.lagoon.core

import java.io.IOException

/** UI-level recovery affordance; safe to expose in state. */
enum class RemoteRecoveryKind(val label: String) {
  LOGIN("重新登录"),
  TRUST("核对指纹"),
  HOST("刷新主机"),
  RECONNECT("重新连接")
}

internal fun RemoteFailureKind.recovery(): RemoteRecoveryKind? = when (this) {
  RemoteFailureKind.AUTH_REQUIRED, RemoteFailureKind.DEVICE_REVOKED -> RemoteRecoveryKind.LOGIN
  RemoteFailureKind.TRUST_REQUIRED, RemoteFailureKind.TRUST_CHANGED -> RemoteRecoveryKind.TRUST
  RemoteFailureKind.HOST_NOT_FOUND -> RemoteRecoveryKind.HOST
  RemoteFailureKind.CONNECTION_REPLACED -> RemoteRecoveryKind.RECONNECT
  RemoteFailureKind.SLOW_CONSUMER -> RemoteRecoveryKind.RECONNECT
  else -> null
}

/** User-actionable Remote failure. UI may key recovery on [kind], never on a message string. */
enum class RemoteFailureKind {
  OFFLINE, AUTH_REQUIRED, DEVICE_REVOKED, HOST_NOT_FOUND,
  TRUST_REQUIRED, TRUST_CHANGED, CONNECTION_REPLACED, SLOW_CONSUMER,
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
      RemoteFailureKind.SLOW_CONSUMER -> "降低流量后重连"
      RemoteFailureKind.INCOMPATIBLE -> "查看更新方法"
      RemoteFailureKind.RATE_LIMITED -> "稍后重试"
      RemoteFailureKind.REJECTED -> "查看主机"
      RemoteFailureKind.NETWORK -> "立即重试"
    }
}

internal fun remoteFailure(error: Throwable): RemoteFailureException? =
  generateSequence(error) { it.cause }.filterIsInstance<RemoteFailureException>().firstOrNull()

internal fun remoteControlFailure(error: RemoteControlException): RemoteFailureException = when (error.code) {
  "CONNECTION_REPLACED" -> RemoteFailureException(RemoteFailureKind.CONNECTION_REPLACED, "另一实例正在使用这台设备身份", retryable = false, cause = error)
  "SLOW_CONSUMER" -> RemoteFailureException(RemoteFailureKind.SLOW_CONSUMER, "Relay 消费过慢，正在降低流量并重连", retryable = true, cause = error)
  "DEVICE_REVOKED" -> RemoteFailureException(RemoteFailureKind.DEVICE_REVOKED, "此手机的远程设备授权已被撤销，请重新登录或重新配对", retryable = false, cause = error)
  "SERVER_SHUTDOWN" -> RemoteFailureException(RemoteFailureKind.NETWORK, "DSH 远程中枢正在重启，正在重连", retryable = true, cause = error)
  "HEARTBEAT_TIMEOUT" -> RemoteFailureException(RemoteFailureKind.NETWORK, "DSH 远程中枢心跳超时，正在重连", retryable = true, cause = error)
  "TOKEN_EXPIRED", "AUTH_INVALID" -> RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "DSH Remote 凭据已失效，请重新登录", retryable = false, cause = error)
  else -> RemoteFailureException(RemoteFailureKind.NETWORK, error.message ?: "DSH Remote 连接失败", retryable = error.retryable, cause = error)
}

internal fun remoteServerFailure(error: RemoteServerException): RemoteFailureException = when {
  error.isDeviceRevoked -> RemoteFailureException(RemoteFailureKind.DEVICE_REVOKED, "此手机的远程设备授权已被撤销，请重新登录或重新配对", retryable = false, cause = error)
  error.isCredentialRejected -> RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "DSH Remote 登录已失效", retryable = false, cause = error)
  error.status == 401 || (error.status == 403 && error.code == null) -> RemoteFailureException(RemoteFailureKind.NETWORK, "DSH Remote 授权服务暂时不可用，已保留凭据", retryable = true, cause = error)
  error.status == 403 -> RemoteFailureException(RemoteFailureKind.REJECTED, "主机拒绝了这台手机", retryable = false, cause = error)
  error.status == 404 -> RemoteFailureException(RemoteFailureKind.HOST_NOT_FOUND, "账号下找不到这台主机", retryable = false, cause = error)
  error.status == 429 -> RemoteFailureException(RemoteFailureKind.RATE_LIMITED, "请求过于频繁，请稍后重试", retryable = true, cause = error)
  error.status >= 500 -> RemoteFailureException(RemoteFailureKind.NETWORK, "DSH Remote 服务暂时不可用", retryable = true, cause = error)
  else -> RemoteFailureException(RemoteFailureKind.NETWORK, "DSH Remote 请求失败", retryable = error.retryable, cause = error)
}
