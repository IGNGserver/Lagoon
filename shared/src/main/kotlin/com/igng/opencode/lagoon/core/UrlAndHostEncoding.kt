package com.igng.opencode.lagoon.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.util.Locale

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

private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
