package com.igng.opencode.lagoon.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Identity of a credential target: scheme + host + port. Two URLs with equal [HttpOrigin] may share
 * one set of credentials; any difference (HTTP→HTTPS, port change, host change) is a different
 * target and must not receive them implicitly.
 */
internal data class HttpOrigin(val scheme: String, val host: String, val port: Int) {
  companion object {
    fun of(url: HttpUrl): HttpOrigin = HttpOrigin(url.scheme.lowercase(), url.host.lowercase(), url.port)
    fun parse(url: String): HttpOrigin? = extractFlexibleHttpOriginParts(url)?.let { (s, h, p) -> HttpOrigin(s, h, p) }
    /** The cleartext target restriction used by pairing. */
    fun allowsCleartext(host: String): Boolean = host in setOf("localhost", "127.0.0.1", "::1")
  }
}

/** The same resolved credentials must be used for both probing and saving an edited profile. */
internal fun profileCredentials(previousUrl: String?, nextUrl: String, previous: ServerCredentials,
  username: String, password: String?, cookie: String? = null): ServerCredentials {
  val sameOrigin = previousUrl != null && run {
    val prev = HttpOrigin.parse(previousUrl)
    val next = HttpOrigin.parse(nextUrl)
    prev != null && prev == next
  }
  return ServerCredentials(username,
    password ?: previous.password.takeIf { sameOrigin }.orEmpty(),
    cookie ?: previous.cookie.takeIf { sameOrigin }.orEmpty())
}

internal fun credentialOrigin(url: String): String =
  requireNotNull(HttpOrigin.parse(url)) { "服务器地址无效" }.toString()
