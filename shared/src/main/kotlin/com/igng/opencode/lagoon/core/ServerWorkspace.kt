package com.igng.opencode.lagoon.core

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Host-owned registration; title is presentation only, never a routing identity. */
data class ServerWorkspace(
  val id: String,
  val path: String,
  val title: String,
  val sessionIds: List<String>,
  val createdAt: String,
  val updatedAt: String
)

data class WorkspaceRegistration(val workspace: ServerWorkspace, val created: Boolean)

/** Strict decoding is deliberately separate from the legacy best-effort project-title projection. */
internal object ServerWorkspaceCodec {
  const val MAX_WORKSPACES = 5_000
  const val MAX_SESSION_IDS = 50_000

  fun invalid(): Nothing = throw ApiException(502,
    "工作区响应不完整或格式无效，请刷新核实；不要重复提交操作", "workspace/response-invalid")

  fun validPath(value: String): Boolean = value.isNotBlank() && value == value.trim() &&
    value.none { it.isISOControl() } && (value.startsWith('/') ||
      Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value) ||
      Regex("^\\\\\\\\[^\\\\/]+[\\\\/][^\\\\/]+").containsMatchIn(value))

  fun title(value: String): String {
    val clean = value.trim()
    if (clean.isBlank() || clean.length > 1_000 || clean.any { it.isISOControl() })
      throw ApiException(400, "工作区标题不能为空、包含控制字符或超过 1000 字符", "workspace/title-invalid")
    return clean
  }

  private fun string(value: JSONObject, key: String): String =
    (value.opt(key) as? String)?.takeIf { it.isNotBlank() && it == it.trim() && it.none(Char::isISOControl) } ?: invalid()

  fun ids(value: Any?, limit: Int): List<String> {
    val array = value as? JSONArray ?: invalid()
    if (array.length() > limit) invalid()
    val seen = HashSet<String>()
    return (0 until array.length()).map { index ->
      val id = (array.opt(index) as? String)?.takeIf { it.isNotBlank() && it == it.trim() && it.none(Char::isISOControl) } ?: invalid()
      if (!seen.add(id)) invalid()
      id
    }
  }

  fun workspace(raw: Any?): ServerWorkspace {
    val value = raw as? JSONObject ?: invalid()
    val id = string(value, "workspaceId")
    val path = string(value, "path").takeIf(::validPath) ?: invalid()
    val title = string(value, "title").takeIf { it.length <= 1_000 } ?: invalid()
    val createdAt = string(value, "createdAt")
    val updatedAt = string(value, "updatedAt")
    val created = runCatching { Instant.parse(createdAt) }.getOrNull() ?: invalid()
    val updated = runCatching { Instant.parse(updatedAt) }.getOrNull() ?: invalid()
    if (updated < created) invalid()
    return ServerWorkspace(id, path, title, ids(value.opt("sessionIds"), MAX_SESSION_IDS), createdAt, updatedAt)
  }

  fun catalog(raw: Any?): List<ServerWorkspace> {
    val items = raw as? JSONArray ?: invalid()
    if (items.length() > MAX_WORKSPACES) invalid()
    val seen = HashSet<String>()
    var sessions = 0L
    return (0 until items.length()).map { index ->
      val item = workspace(items.opt(index))
      if (!seen.add(item.id)) invalid()
      sessions += item.sessionIds.size
      if (sessions > MAX_SESSION_IDS) invalid()
      item
    }
  }
}
