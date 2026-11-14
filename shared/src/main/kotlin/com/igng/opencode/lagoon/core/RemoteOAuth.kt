package com.igng.opencode.lagoon.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** One browser/QR sign-in provider advertised by a DSH Remote Server. */
internal data class RemoteOAuthProvider(
  val id: String,
  val displayName: String,
  val startUrl: String,
  val qrStartUrl: String?
)

/** Capabilities + browser/QR providers discovered from `GET /api/v1/auth/oauth/providers`. */
internal data class RemoteAuthCapabilities(
  val providers: List<RemoteOAuthProvider>,
  val devicePairing: Boolean = false,
  val fragmentTicketQr: Boolean = false
)

/** A browser/QR sign-in attempt. The URL must be opened outside the app. */
internal data class RemoteBrowserLogin(
  val qrId: String,
  val loginUrl: String,
  val expiresAt: Long,
  val provider: String
)

internal sealed interface RemoteQrPoll {
  data object Pending : RemoteQrPoll
  data object Expired : RemoteQrPoll
  data class Complete(val accountToken: String) : RemoteQrPoll
}

/** The account name for a web/account token; never logged. */
internal data class RemoteAccountIdentity(val account: String)

internal fun requireAuthEntryPath(value: String): String {
  if (!value.startsWith("/api/v1/auth/") ||
    value.any { it.code < 0x20 || it.code == 0x7f || it.isWhitespace() || it == '\\' || it == '#' } ||
    value.split('?').first().split('/').any { it == "." || it == ".." || it.contains('%') })
    throw IOException("DSH Remote 登录入口无效")
  return value
}

internal fun requireQrLoginUrl(value: String, server: HttpUrl): String {
  if (value.any { it.code < 0x20 || it.code == 0x7f }) throw IOException("DSH Remote 登录链接无效")
  val url = value.toHttpUrlOrNull() ?: throw IOException("DSH Remote 登录链接无效")
  if (url.scheme != server.scheme || url.host != server.host || url.port != server.port ||
    url.username.isNotEmpty() || url.password.isNotEmpty())
    throw IOException("DSH Remote 登录链接不是同一服务")
  return value
}
