package com.igng.opencode.lagoon.core

import okhttp3.HttpUrl

/**
 * Identity of a local loopback HTTP target: scheme + host + port.
 */
internal data class HttpOrigin(val scheme: String, val host: String, val port: Int) {
  companion object {
    fun of(url: HttpUrl): HttpOrigin = HttpOrigin(url.scheme.lowercase(), url.host.lowercase(), url.port)
    fun parse(url: String): HttpOrigin? = extractFlexibleHttpOriginParts(url)?.let { (s, h, p) -> HttpOrigin(s, h, p) }
  }
}
