package com.igng.opencode.lagoon.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

internal fun truncateByCodePointsSafe(value: String, maxCodeUnits: Int): String {
  if (maxCodeUnits <= 0 || value.isEmpty()) return ""
  if (value.length <= maxCodeUnits) return value
  var end = maxCodeUnits
  if (end > 0 && Character.isHighSurrogate(value[end - 1])) {
    end -= 1
  }
  while (end > 0 && value[end - 1] == '\u200D') {
    end -= 1
  }
  return value.substring(0, end)
}

internal fun truncateUtf8Bytes(value: String, maxBytes: Int): String {
  if (maxBytes <= 0 || value.isEmpty()) return ""
  var usedBytes = 0
  var index = 0
  while (index < value.length) {
    val cp = value.codePointAt(index)
    val charCount = Character.charCount(cp)
    val byteLen = when {
      cp <= 0x7F -> 1
      cp <= 0x7FF -> 2
      cp <= 0xFFFF -> 3
      else -> 4
    }
    if (usedBytes + byteLen > maxBytes) break
    usedBytes += byteLen
    index += charCount
  }
  return value.substring(0, index)
}

internal fun sanitizeRemoteDisplayName(
  raw: String,
  fallback: String = "",
  maxChars: Int = 128
): String {
  if (raw.isEmpty()) return fallback
  val builder = StringBuilder(raw.length)
  var i = 0
  while (i < raw.length) {
    val cp = raw.codePointAt(i)
    val count = Character.charCount(cp)
    val isBidiControl = cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F
    val isDisallowedControl =
      cp == 0x0000 ||
        (cp in 0x0001..0x001F && cp != '\t'.code && cp != '\n'.code && cp != '\r'.code) ||
        cp in 0x007F..0x009F ||
        isBidiControl
    when {
      cp == '\t'.code || cp == '\n'.code || cp == '\r'.code || cp == 0x000B || cp == 0x000C -> builder.append(' ')
      !isDisallowedControl -> builder.appendCodePoint(cp)
    }
    i += count
  }
  val collapsed = builder.toString().replace(Regex("[ \\u00A0\\u3000]+"), " ").trim()
  if (collapsed.isEmpty()) return fallback
  val truncated = truncateByCodePointsSafe(collapsed, maxChars).trimEnd()
  return truncated.ifEmpty { fallback }
}

internal fun normalizeSshHost(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.isEmpty()) return ""
  val unbracketed = if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length > 2) {
    trimmed.substring(1, trimmed.length - 1)
  } else {
    trimmed
  }
  if (unbracketed.contains(':')) {
    return unbracketed.lowercase(Locale.ROOT)
  }
  return punycodeHostnamePreserveUnderscore(unbracketed)
}

internal fun punycodeHostnamePreserveUnderscore(rawHost: String): String {
  val trimmed = rawHost.trim()
  if (trimmed.isEmpty()) return ""
  if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
    return trimmed.lowercase(Locale.ROOT)
  }
  if (trimmed.contains(':')) {
    return "[${trimmed.lowercase(Locale.ROOT)}]"
  }
  val labels = trimmed.split('.')
  val converted = labels.mapIndexed { idx, label ->
    if (label.isEmpty()) {
      if (idx == labels.lastIndex) "" else label
    } else if (label.any { it.code >= 0x80 }) {
      val ascii = runCatching { IDN.toASCII(label, 0) }.getOrNull() ?: return trimmed.lowercase(Locale.ROOT)
      ascii.lowercase(Locale.ROOT)
    } else {
      label.lowercase(Locale.ROOT)
    }
  }
  return converted.joinToString(".")
}

internal fun isPrivateOrLoopbackHost(rawHost: String): Boolean {
  val host = rawHost.trim().removePrefix("[").removeSuffix("]").substringBefore('%').lowercase(Locale.ROOT)
  if (host.isEmpty()) return false
  if (host == "localhost" || host.endsWith(".localhost") ||
    host.endsWith(".local") || host.endsWith(".lan") ||
    host.endsWith(".internal") || host.endsWith(".home.arpa")
  ) {
    return true
  }
  if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
  val bareHostnameChars = host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
  if (bareHostnameChars && !host.contains('.')) return true
  val numeric = isNumericIpLiteral(host)
  if (!numeric) return false
  val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
  return when (addr) {
    is Inet4Address -> {
      val b = addr.address
      val b0 = b[0].toInt() and 0xFF
      val b1 = b[1].toInt() and 0xFF
      b0 == 127 ||
        b0 == 10 ||
        (b0 == 172 && b1 in 16..31) ||
        (b0 == 192 && b1 == 168) ||
        (b0 == 100 && b1 in 64..127) ||
        (b0 == 169 && b1 == 254) ||
        b0 == 0
    }
    is Inet6Address -> {
      addr.isLoopbackAddress ||
        addr.isLinkLocalAddress ||
        addr.isSiteLocalAddress ||
        ((addr.address[0].toInt() and 0xFE) == 0xFC)
    }
    else -> false
  }
}

private fun isNumericIpLiteral(host: String): Boolean {
  if (host.contains(':')) return host.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }
  val parts = host.split('.')
  if (parts.size != 4) return false
  return parts.all { part ->
    part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && (part.toIntOrNull() ?: 999) <= 255
  }
}

internal fun punycodeUrlAuthorityPreservingPath(raw: String): String {
  val match = Regex("^(https?)://([^/?#]+)(.*)$", RegexOption.IGNORE_CASE).matchEntire(raw) ?: return raw
  val authority = match.groupValues[2]
  if (authority.contains('@')) return raw
  val (hostPart, portPart) = splitAuthorityHostAndPort(authority) ?: return raw
  if (hostPart.none { it.code >= 0x80 }) return raw
  val punyHost = punycodeHostnamePreserveUnderscore(hostPart)
  val portSuffix = if (portPart != null) ":$portPart" else ""
  return "${match.groupValues[1]}://$punyHost$portSuffix${match.groupValues[3]}"
}

internal fun parseFlexibleHttpUrl(raw: String): HttpUrl? {
  val trimmed = trimNetworkInput(raw)
  if (trimmed.isEmpty()) return null
  trimmed.toHttpUrlOrNull()?.let { return it }
  val match = Regex("^(https?)://([^/?#]+)(.*)$", RegexOption.IGNORE_CASE).matchEntire(trimmed) ?: return null
  val scheme = match.groupValues[1].lowercase(Locale.ROOT)
  val authority = match.groupValues[2]
  val tail = match.groupValues[3]
  if (authority.contains('@')) return null
  val (hostPart, portPart) = splitAuthorityHostAndPort(authority) ?: return null
  val normalizedHost = punycodeHostnamePreserveUnderscore(hostPart)
  val portSuffix = if (portPart != null) ":$portPart" else ""
  return "$scheme://$normalizedHost$portSuffix$tail".toHttpUrlOrNull()
}

internal fun extractFlexibleHttpOriginParts(raw: String): Triple<String, String, Int>? {
  parseFlexibleHttpUrl(raw)?.let { url ->
    return Triple(url.scheme.lowercase(Locale.ROOT), url.host.lowercase(Locale.ROOT), url.port)
  }
  val trimmed = trimNetworkInput(raw)
  val match = Regex("^(https?)://([^/?#]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE).matchEntire(trimmed) ?: return null
  val scheme = match.groupValues[1].lowercase(Locale.ROOT)
  val authority = match.groupValues[2]
  if (authority.contains('@')) return null
  val (hostPart, portPart) = splitAuthorityHostAndPort(authority) ?: return null
  if (hostPart.isBlank() || hostPart.any { it.isWhitespace() || it in setOf('/', '?', '#', '\\', '%') }) return null
  val port = if (portPart != null) {
    val parsed = portPart.toIntOrNull() ?: return null
    if (parsed !in 1..65535) return null
    parsed
  } else {
    if (scheme == "https") 443 else 80
  }
  val normalizedHost = punycodeHostnamePreserveUnderscore(hostPart)
  return Triple(scheme, normalizedHost, port)
}

internal fun normalizeRemoteHubAddressInput(raw: String): String {
  val trimmed = trimNetworkInput(raw)
  if (trimmed.isEmpty()) return ""
  if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) {
    return trimmed
  }
  if (trimmed.startsWith("//")) return "https:$trimmed"
  val authority = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
  val host = splitAuthorityHostAndPort(authority)?.first ?: authority
  val scheme = if (isPrivateOrLoopbackHost(host)) "http" else "https"
  return "$scheme://$trimmed"
}

internal fun canonicalizeFlexibleHttpOrigin(raw: String): String? {
  val (scheme, host, port) = extractFlexibleHttpOriginParts(raw) ?: return null
  val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
  val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
  return if (defaultPort) "$scheme://$formattedHost" else "$scheme://$formattedHost:$port"
}

internal fun encodeUrlPathPreservingEscapes(rawPath: String): String {
  val stripped = rawPath.trimStart('/')
  if (stripped.isEmpty()) return ""
  return stripped.split('/').joinToString("/") { encodePathSegmentPreservingEscapes(it) }
}

internal fun encodePathSegmentPreservingEscapes(segment: String): String {
  if (segment.isEmpty()) return ""
  val out = StringBuilder(segment.length)
  var i = 0
  while (i < segment.length) {
    val ch = segment[i]
    if (ch == '%' && i + 2 < segment.length && isHexDigit(segment[i + 1]) && isHexDigit(segment[i + 2])) {
      out.append(segment, i, i + 3)
      i += 3
      continue
    }
    val cp = segment.codePointAt(i)
    val count = Character.charCount(cp)
    if (isRfc3986PathSegmentChar(cp)) {
      out.appendCodePoint(cp)
    } else {
      val utf8 = segment.substring(i, i + count).toByteArray(Charsets.UTF_8)
      for (b in utf8) {
        val v = b.toInt() and 0xFF
        out.append('%')
        out.append(HEX_CHARS[v ushr 4])
        out.append(HEX_CHARS[v and 0x0F])
      }
    }
    i += count
  }
  return out.toString()
}

private fun splitAuthorityHostAndPort(authority: String): Pair<String, String?>? {
  if (authority.isEmpty()) return null
  if (authority.startsWith("[")) {
    val closeIdx = authority.indexOf(']')
    if (closeIdx <= 1) return null
    val host = authority.substring(0, closeIdx + 1)
    val rest = authority.substring(closeIdx + 1)
    return when {
      rest.isEmpty() -> host to null
      rest.startsWith(":") -> {
        val port = rest.substring(1)
        if (port.isEmpty() || !port.all { it in '0'..'9' }) null else host to port
      }
      else -> null
    }
  }
  val firstColon = authority.indexOf(':')
  val lastColon = authority.lastIndexOf(':')
  if (firstColon < 0) return authority to null
  if (firstColon != lastColon) {
    // Bare unbracketed IPv6 without explicit port.
    return authority to null
  }
  val host = authority.substring(0, lastColon)
  val port = authority.substring(lastColon + 1)
  if (host.isEmpty() || port.isEmpty() || !port.all { it in '0'..'9' }) return null
  return host to port
}

private fun trimNetworkInput(raw: String): String =
  raw.trim { it.isWhitespace() || it == '\u200B' || it == '\uFEFF' || it == '\u3000' }

private fun isRfc3986PathSegmentChar(cp: Int): Boolean =
  cp in 'A'.code..'Z'.code ||
    cp in 'a'.code..'z'.code ||
    cp in '0'.code..'9'.code ||
    cp == '-'.code || cp == '.'.code || cp == '_'.code || cp == '~'.code ||
    cp == '!'.code || cp == '$'.code || cp == '&'.code || cp == '\''.code ||
    cp == '('.code || cp == ')'.code || cp == '*'.code || cp == '+'.code ||
    cp == ','.code || cp == ';'.code || cp == '='.code || cp == ':'.code ||
    cp == '@'.code

private fun isHexDigit(ch: Char): Boolean =
  ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

internal fun decodeUtf8OrNull(bytes: ByteArray): String? = runCatching {
  val out = ByteArrayOutputStream(bytes.size)
  out.write(bytes)
  out.toString(Charsets.UTF_8.name())
}.getOrNull()

private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
