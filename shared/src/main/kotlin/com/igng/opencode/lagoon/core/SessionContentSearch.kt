package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/** A bounded, Host-authorized literal content match; never a local transcript index. */
data class SessionContentHit(val sessionId: String, val snippet: String)

data class SessionContentSearchResult(
  val query: String,
  val items: List<SessionContentHit>,
  val hasMore: Boolean
)

/** DSH rc.5 limits: query uses UTF-16 units, snippet uses Unicode code points. */
internal object SessionContentSearchCodec {
  fun normalizeQuery(query: String): String {
    val normalized = query.trim()
    if (normalized.isEmpty() || '\u0000' in normalized)
      throw ApiException(400, "搜索内容不能为空或包含 NUL", "session-search/query-invalid")
    if (normalized.length > 500)
      throw ApiException(413, "搜索内容不能超过 500 个 UTF-16 字符", "session-search/query-too-large")
    return normalized
  }

  fun validSessionId(id: String): Boolean = id.isNotEmpty() && id.length <= 512 && id.none(Char::isISOControl)

  fun decode(query: String, value: Any?): SessionContentSearchResult {
    val root = value as? JSONObject ?: invalid()
    val rows = root.opt("items") as? JSONArray ?: invalid()
    val hasMore = root.opt("hasMore") as? Boolean ?: invalid()
    if (rows.length() > 20) tooLarge()
    val ids = HashSet<String>()
    val items = (0 until rows.length()).map { index ->
      val row = rows.opt(index) as? JSONObject ?: invalid()
      val id = row.opt("sessionId") as? String ?: invalid()
      val snippet = row.opt("snippet") as? String ?: invalid()
      if (!validSessionId(id) || !ids.add(id) || !validUnicode(snippet)) invalid()
      if (snippet.codePointCount(0, snippet.length) > 240) tooLarge()
      SessionContentHit(id, snippet)
    }
    // hasMore is measured before remote-access filtering; empty items with true is valid.
    return SessionContentSearchResult(query, items, hasMore)
  }

  private fun validUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
      val char = value[index++]
      if (Character.isHighSurrogate(char)) {
        if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
      } else if (Character.isLowSurrogate(char)) return false
    }
    return true
  }

  private fun invalid(): Nothing = throw ApiException(502, "主机返回了无效的会话搜索结果", "session-search/response-invalid")
  private fun tooLarge(): Nothing = throw ApiException(413, "会话搜索结果超过协议上限", "session-search/response-too-large")
}
