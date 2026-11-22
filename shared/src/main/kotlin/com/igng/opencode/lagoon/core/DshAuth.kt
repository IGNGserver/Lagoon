package com.igng.opencode.lagoon.core

import org.json.JSONObject
import java.util.Base64

/**
 * DSH 的认证 Cookie（`dsh-auth-<sha256(authority)>=v<n>.<payload>.<sig>`）。
 *
 * - `v1` 是浏览器 Cookie：在 `cookieMaxAgeDays` 内跨 dsh 重启有效。
 * - `v2` 是 `/connect` 的 API Peer Cookie：绑定进程内的 Peer，dsh 重启即失效，只放内存。
 */
object DshCookie {
  private const val PREFIX = "dsh-auth-"
  private val urlDecoder = Base64.getUrlDecoder()

  /** Cookie 的签发与过期时间（毫秒）；只解码不验签，验签由 dsh 负责。 */
  data class Lifetime(val version: Int, val issuedAt: Long, val expiresAt: Long)

  fun isAuthCookie(pair: String): Boolean = pair.trim().startsWith(PREFIX) && pair.contains('=')

  fun lifetime(pair: String): Lifetime? = runCatching {
    val value = pair.substringAfter('=', "").trim()
    val parts = value.split('.')
    if (parts.size != 3) return null
    val version = parts[0].removePrefix("v").toIntOrNull() ?: return null
    val json = JSONObject(String(urlDecoder.decode(parts[1]), Charsets.UTF_8))
    Lifetime(version, json.optLong("issuedAt"), json.optLong("expiresAt"))
  }.getOrNull()

  /** 浏览器 Cookie（v1）跨 dsh 重启仍有效。 */
  fun isDurable(pair: String): Boolean = isAuthCookie(pair) && lifetime(pair)?.version == 1

  /** 仍在有效期内（留一分钟余量）；解不出有效期的 Cookie 交给服务端判断。 */
  fun isFresh(pair: String, now: Long): Boolean {
    if (!isAuthCookie(pair)) return false
    val lifetime = lifetime(pair) ?: return true
    return lifetime.expiresAt <= 0 || lifetime.expiresAt - 60_000 > now
  }
}

/** 认证失效的错误码；与网络失败区分，控制器据此放缓重试并提示用户。 */
const val DSH_AUTH_EXPIRED = "auth/expired"

/** 这次失败是凭据失效而不是网络抖动：重试不会自愈。 */
val Throwable.isAuthFailure: Boolean get() = this is ApiException && code == DSH_AUTH_EXPIRED
