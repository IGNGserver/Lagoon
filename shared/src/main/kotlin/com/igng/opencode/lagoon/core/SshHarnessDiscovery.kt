package com.igng.opencode.lagoon.core

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** File evidence is not proof of a listening, authenticated, compatible service. */
enum class SshDiscoveryState { PRESENT, ABSENT, CORRUPT, UNKNOWN }
enum class SshDiscoveryIssue { NONE, NOT_FOUND, UNREADABLE, TOO_LARGE, INVALID_DATA, PYTHON_UNAVAILABLE, EXEC_FAILED, INVALID_OUTPUT }

/** Stable route locator, NOT an authenticated service identity. A registration id is a generation. */
fun sshHarnessLocator(sshHostId: String, registrationPath: String): String {
  require(sshHostId.isNotBlank() && '\u0000' !in sshHostId) { "Invalid SSH host ID" }
  require(registrationPath.startsWith('/') && '\u0000' !in registrationPath) { "Invalid registration path" }
  val bytes = MessageDigest.getInstance("SHA-256").digest("$sshHostId\u0000$registrationPath".toByteArray(Charsets.UTF_8))
  return "ssh-harness-" + bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Runtime-only credential. Intentionally no data-class copy/component functions or JSON codec. */
class OpenCodeSshRegistration private constructor(
  val registrationPath: String,
  val url: String,
  val host: String,
  val port: Int,
  val pid: Int,
  val version: String?,
  val generationId: String?,
  val password: String?
) {
  fun stableLocator(sshHostId: String): String = sshHarnessLocator(sshHostId, registrationPath)
  override fun toString(): String = "OpenCodeSshRegistration(path=$registrationPath, host=$host, port=$port, pid=$pid, password=<redacted>)"

  /**
   * Validate a GET /api/info received ONLY over this registration's loopback SSH forwarder.
   * Do not follow redirects or dial the URLs advertised by /api/info. That endpoint is public:
   * a successful response does not prove the registration password works on protected routes.
   * Pass [authenticated] only after a separate protected request (or other auth evidence).
   */
  fun validateInfo(
    httpStatus: Int?, info: JSONObject?, authenticated: Boolean? = null,
    observedAtMs: Long = System.currentTimeMillis(), freshnessMillis: Long = 30_000
  ): HarnessDiscoveryStatus {
    val listening = if (httpStatus == null) DiscoveryValue.UNKNOWN else DiscoveryValue.YES
    val auth = when {
      httpStatus == 401 || httpStatus == 403 || authenticated == false -> DiscoveryValue.NO
      authenticated == true && httpStatus == 200 -> DiscoveryValue.YES
      else -> DiscoveryValue.UNKNOWN
    }
    val actualPid = info?.opt("pid")
    val actualVersion = info?.opt("version") as? String
    val validInfo = httpStatus == 200 && (actualPid is Int || actualPid is Long) &&
      (actualPid as Number).toLong() == pid.toLong() && !actualVersion.isNullOrBlank() &&
      (version == null || actualVersion == version)
    val compatible = when {
      httpStatus == null || httpStatus == 401 || httpStatus == 403 -> DiscoveryValue.UNKNOWN
      validInfo -> DiscoveryValue.YES
      else -> DiscoveryValue.NO
    }
    return discoveryStatus(
      observedAtMs, freshnessMillis, configured = DiscoveryValue.YES, listening = listening,
      authenticated = auth, compatible = compatible,
      available = when {
        validInfo && auth == DiscoveryValue.YES -> DiscoveryValue.YES
        compatible == DiscoveryValue.NO || auth == DiscoveryValue.NO -> DiscoveryValue.NO
        else -> DiscoveryValue.UNKNOWN
      },
      completeness = if (httpStatus == null) DiscoveryCompleteness.PARTIAL else DiscoveryCompleteness.COMPLETE,
      reason = when {
        httpStatus == null -> "info_unreachable"
        auth == DiscoveryValue.NO -> "authentication_failed"
        !validInfo -> "info_registration_mismatch"
        auth != DiscoveryValue.YES -> "authentication_unverified"
        else -> null
      }
    )
  }

  companion object {
    const val MAX_BYTES = 16 * 1024

    /** Official flat registration shape only; never coerces strings/numbers/nulls. No input in errors. */
    fun parse(registrationPath: String, text: String): OpenCodeSshRegistration? = try {
      require(validSshDiscoveryPath(registrationPath))
      require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
      val value = RegistrationJson(text).parse()
      require(value.keys.all { it in setOf("id", "version", "url", "pid", "password") })
      val url = value["url"] as? String ?: error("url")
      val uri = URI(url)
      require(uri.scheme == "http" && !uri.isOpaque && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/"))
      // Exact literals only: no DNS lookup, alternate IPv4 spellings, wildcards or public targets.
      require(uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1") && uri.port in 1..65535)
      val pid = (value["pid"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: error("pid")
      fun optional(key: String, max: Int): String? = if (key !in value) null else
        (value[key] as? String)?.takeIf { it.isNotBlank() && it.length <= max && it.none(Char::isISOControl) }
          ?: error("string")
      OpenCodeSshRegistration(registrationPath, url, uri.host.removePrefix("[").removeSuffix("]"), uri.port,
        pid, optional("version", 128), optional("id", 512), optional("password", 4096))
    } catch (_: Exception) { null }
  }
}

/** Legacy .dsh-app-remote is a marker descriptor, never a desktop profile/runtime equivalence. */
data class DshLegacySshDescriptor(val markerPath: String) {
  val protocolId: HarnessProtocolId get() = HarnessProtocolId.DSH_TYPERT
  fun stableLocator(sshHostId: String): String = sshHarnessLocator(sshHostId, markerPath)
}

data class SshOpenCodeObservation(
  val state: SshDiscoveryState,
  val registration: OpenCodeSshRegistration? = null,
  val registrationPath: String? = null,
  val issue: SshDiscoveryIssue = SshDiscoveryIssue.NONE
)
data class SshDshLegacyObservation(
  val state: SshDiscoveryState,
  val descriptor: DshLegacySshDescriptor? = null,
  val issue: SshDiscoveryIssue = SshDiscoveryIssue.NONE
)
data class SshHarnessDiscoveryResult(
  val openCode: SshOpenCodeObservation,
  val dshLegacy: SshDshLegacyObservation,
  val observedAtMs: Long,
  val completeness: DiscoveryCompleteness
) {
  fun openCodeStatus(freshnessMillis: Long = 30_000): HarnessDiscoveryStatus = discoveryStatus(
    observedAtMs, freshnessMillis,
    configured = when (openCode.state) {
      SshDiscoveryState.PRESENT -> DiscoveryValue.YES
      SshDiscoveryState.ABSENT, SshDiscoveryState.CORRUPT -> DiscoveryValue.NO
      SshDiscoveryState.UNKNOWN -> DiscoveryValue.UNKNOWN
    },
    available = if (openCode.state in setOf(SshDiscoveryState.ABSENT, SshDiscoveryState.CORRUPT)) DiscoveryValue.NO else DiscoveryValue.UNKNOWN,
    completeness = completeness, reason = openCode.issue.takeUnless { it == SshDiscoveryIssue.NONE }?.name?.lowercase()
  )
}

internal fun validSshDiscoveryPath(path: String): Boolean = path.startsWith('/') && path.length <= 4096 && path.none(Char::isISOControl)

private fun discoveryStatus(
  now: Long, ttl: Long, configured: DiscoveryValue, listening: DiscoveryValue = DiscoveryValue.UNKNOWN,
  authenticated: DiscoveryValue = DiscoveryValue.UNKNOWN, compatible: DiscoveryValue = DiscoveryValue.UNKNOWN,
  available: DiscoveryValue, completeness: DiscoveryCompleteness, reason: String?
): HarnessDiscoveryStatus {
  require(now >= 0 && ttl >= 0)
  return HarnessDiscoveryStatus(configured = configured, listening = listening, authenticated = authenticated,
    compatible = compatible, available = available, observedAtMs = now,
    freshUntilMs = if (Long.MAX_VALUE - now < ttl) Long.MAX_VALUE else now + ttl,
    completeness = completeness, reason = reason)
}

/** Deliberately small strict JSON grammar for the flat official registration. org.json accepts
 * single quotes, unquoted keys and other non-JSON forms on Android, so don't use its coercing API. */
private class RegistrationJson(private val text: String) {
  private var at = 0
  fun parse(): Map<String, Any> {
    val values = linkedMapOf<String, Any>()
    expect('{')
    if (!take('}')) {
      do {
        val key = string()
        require(key !in values)
        expect(':')
        values[key] = if (peek() == '"') string() else number()
      } while (take(','))
      expect('}')
    }
    require(peek() == null)
    return values
  }
  private fun number(): Long {
    whitespace()
    val start = at
    if (at < text.length && text[at] == '-') at++
    require(at < text.length && text[at] in '0'..'9')
    if (text[at] == '0') at++ else while (at < text.length && text[at] in '0'..'9') at++
    return text.substring(start, at).toLong()
  }
  private fun string(): String {
    expect('"')
    val out = StringBuilder()
    while (at < text.length) {
      val c = text[at++]
      if (c == '"') return out.toString()
      require(c >= ' ')
      if (c != '\\') { out.append(c); continue }
      require(at < text.length)
      out.append(when (val escape = text[at++]) {
        '"', '\\', '/' -> escape
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
          require(at + 4 <= text.length)
          val hex = text.substring(at, at + 4)
          require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
          at += 4
          hex.toInt(16).toChar()
        }
        else -> error("escape")
      })
    }
    error("string")
  }
  private fun peek(): Char? { whitespace(); return text.getOrNull(at) }
  private fun take(c: Char): Boolean = if (peek() == c) { at++; true } else false
  private fun expect(c: Char) { require(take(c)) }
  private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
}
