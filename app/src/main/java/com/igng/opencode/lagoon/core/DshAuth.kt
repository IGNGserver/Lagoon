package com.igng.opencode.lagoon.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** `dsh web` 打印的启动链接拆成的服务器地址与启动令牌。 */
data class DshLaunchLink(val serverUrl: String, val token: String) {
  companion object {
    /**
     * 解析 `http(s)://host:port[/mount]/?token=…`。只认唯一的 `token` 参数且不带其他参数，
     * 不是启动链接时返回 null（普通地址照常按服务器地址处理）。
     */
    fun parse(text: String): DshLaunchLink? {
      val url = text.trim().toHttpUrlOrNull() ?: return null
      val tokens = url.queryParameterValues("token")
      val token = tokens.singleOrNull()?.trim().orEmpty()
      if (token.isEmpty() || url.querySize != 1 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
      val root = url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
      return DshLaunchLink(root, token)
    }
  }
}

/** DSH 的两种配对凭据：每次进程启动都会变的启动令牌，或持久的 Cookie 签名密钥。 */
enum class DshAuthMode(val wire: String, val label: String) {
  TOKEN("token", "启动令牌"),
  SECRET("secret", "签名密钥");

  companion object {
    fun fromWire(value: String?): DshAuthMode = entries.firstOrNull { it.wire == value } ?: TOKEN
  }
}

/**
 * DSH 的认证 Cookie（`dsh-auth-<sha256(authority)>=v<n>.<payload>.<sig>`，见 dsh
 * `packages/client/connection/src/browser-auth.ts`）。
 *
 * - `v1` 是浏览器 Cookie：签名密钥持久化在 `$DSH_HOME/.credentials.yaml`，跨 dsh 重启有效，
 *   在绝对有效期（`cookieMaxAgeDays`，默认 30 天）内可以落盘复用。
 * - `v2` 是 `/connect` 的 API Peer Cookie：绑定进程内的 Peer，dsh 重启即失效，只放内存。
 */
object DshCookie {
  private const val PREFIX = "dsh-auth-"
  private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
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

  /** 浏览器 Cookie（v1）才值得落盘：API Peer Cookie 在 dsh 重启后必然失效。 */
  fun isDurable(pair: String): Boolean = isAuthCookie(pair) && lifetime(pair)?.version == 1

  /** 仍在有效期内（留一分钟余量）；解不出有效期的 Cookie 交给服务端判断。 */
  fun isFresh(pair: String, now: Long): Boolean {
    if (!isAuthCookie(pair)) return false
    val lifetime = lifetime(pair) ?: return true
    return lifetime.expiresAt <= 0 || lifetime.expiresAt - 60_000 > now
  }

  /**
   * Cookie 的 audience：HTTP 是 `host[:port]`（默认端口省略），HTTPS 是完整 origin。
   * 与 dsh 的 `requestAudience` 一致：它读请求的 Host 头，OkHttp 发出的 Host 同样省略默认端口。
   */
  fun authority(url: HttpUrl, secureListener: Boolean = url.isHttps): String {
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val hostPort = if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
    return if (secureListener) "https://$hostPort" else hostPort
  }

  fun name(authority: String): String =
    PREFIX + urlEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(authority.toByteArray(Charsets.UTF_8)))

  /** 签名密钥必须是 32 字节的 base64url（`client-connection/browser-session` 记录的 `secret`）。 */
  fun decodeSecret(secret: String): ByteArray? = runCatching {
    val text = secret.trim()
    if (text.isEmpty() || !text.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
    urlDecoder.decode(text).takeIf { it.size == 32 }
  }.getOrNull()

  /**
   * 用签名密钥在本机签发浏览器 Cookie（密钥模式）。dsh 校验 `issuedAt <= now < expiresAt` 且
   * 有效期不超过 `cookieMaxAgeDays`（至少 1 天），因此签发时间回拨几分钟吸收手机与电脑的时钟偏差，
   * 有效期取不到一天，过期前重新签发。
   *
   * [secureListener] 是 dsh 监听器自身是否为 TLS：HTTPS 反向代理后面的 dsh 仍是 HTTP 监听，audience 不带 scheme。
   */
  fun mint(secret: ByteArray, url: HttpUrl, now: Long, secureListener: Boolean = url.isHttps): String {
    val audience = authority(url, secureListener)
    val issuedAt = now - CLOCK_SKEW_MILLIS
    val payload = JSONObject()
      .put("version", 1)
      .put("authority", audience)
      .put("issuedAt", issuedAt)
      .put("expiresAt", issuedAt + MINTED_LIFETIME_MILLIS)
    val body = urlEncoder.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
    val signature = urlEncoder.encodeToString(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
    return "${name(audience)}=v1.$body.$signature"
  }

  const val CLOCK_SKEW_MILLIS = 10 * 60_000L
  const val MINTED_LIFETIME_MILLIS = 20 * 60 * 60_000L
}

/** 认证失效（需要重新配对）的错误码；与网络失败区分，控制器据此放缓重试并提示用户。 */
const val DSH_AUTH_EXPIRED = "auth/expired"

/** 这次失败是凭据失效而不是网络抖动：重试不会自愈，需要用户重新配对。 */
val Throwable.isAuthFailure: Boolean get() = this is ApiException && code == DSH_AUTH_EXPIRED
