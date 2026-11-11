package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/** Parses native durable announcements; transport, authentication and snapshot ownership stay with DshApi. */
internal class DshChangesReadApi(
  private val read: suspend (kind: String, sessionId: String, seq: Long, index: Int?) -> JSONObject
) {
  private data class Changed(val path: String, val display: String, val added: Int, val deleted: Int,
    val status: String, val seq: Long, val index: Int)

  suspend fun changes(session: Session, snapshot: JSONObject): List<FileChange> {
    if (snapshot.opt("type") != "snapshot") malformed("snapshot.type")
    val records = snapshot.opt("records") as? JSONArray ?: malformed("snapshot.records")
    val announced = sortedSetOf<Long>()
    for (i in 0 until records.length()) {
      val record = records.opt(i) as? JSONObject ?: malformed("record")
      if (record.opt("type") != "event") continue
      val event = record.opt("event") as? JSONObject ?: malformed("record.event")
      if (event.opt("type") != "workspace/changes") continue
      announced += integer(event.opt("seq"), "event.seq", min = 1)
    }
    val merged = LinkedHashMap<String, Changed>()
    // Match the existing recent-turn view; never silently truncate files within those turns.
    for (seq in announced.toList().takeLast(MAX_CHANGE_TURNS)) {
      val summary = read("summary", session.id, seq, null)
      integer(summary.opt("turn"), "summary.turn", min = 1)
      integer(summary.opt("added"), "summary.added")
      integer(summary.opt("deleted"), "summary.deleted")
      val total = integer(summary.opt("total"), "summary.total")
      val files = summary.opt("files") as? JSONArray ?: malformed("summary.files")
      if (files.length() > MAX_SUMMARY_FILES) tooLarge("改动摘要包含过多文件")
      if (total < files.length()) malformed("summary.total")
      if (total > files.length()) tooLarge("Host 的改动摘要不完整，无法展示完整文件列表")
      for (index in 0 until files.length()) {
        val file = files.opt(index) as? JSONObject ?: malformed("summary.files[$index]")
        val path = path(file, "path")
        val display = path(file, "display")
        val added = integer(file.opt("added"), "file.added", max = Int.MAX_VALUE.toLong()).toInt()
        val deleted = integer(file.opt("deleted"), "file.deleted", max = Int.MAX_VALUE.toLong()).toInt()
        merged[path] = Changed(path, display, added, deleted,
          file.opt("kind") as? String ?: "", seq, index)
      }
      if (merged.size > MAX_CHANGED_FILES) tooLarge("最近改动超过 $MAX_CHANGED_FILES 个文件，请在 Host 查看")
    }
    var totalBytes = 0L
    return merged.values.sortedBy { it.display }.map { file ->
      val diff = read("diff", session.id, file.seq, file.index)
      if (path(diff, "path") != file.path) malformed("diff.path does not match summary")
      path(diff, "display")
      val kind = diff.opt("kind") as? String ?: malformed("diff.kind")
      val after = when (kind) {
        "binary" -> "二进制文件，无法预览对比"
        "oversized" -> "文件过大，无法预览对比"
        "text" -> ""
        else -> malformed("diff.kind")
      }
      val patch = if (kind == "text") renderPatch(file.display, diff) else ""
      totalBytes += patch.toByteArray(Charsets.UTF_8).size
      if (totalBytes > MAX_TOTAL_BYTES) tooLarge("改动对比总量过大，请在 Host 查看")
      FileChange(file.display, after, file.added, file.deleted, patch, file.status)
    }
  }

  private fun renderPatch(display: String, diff: JSONObject): String {
    for (field in listOf("before", "after", "coarse")) {
      if (diff.opt(field) !is Boolean) malformed("diff.$field")
    }
    val hunks = diff.opt("hunks") as? JSONArray ?: malformed("diff.hunks")
    val out = StringBuilder()
    var bytes = 0L
    fun append(text: String) {
      bytes += text.toByteArray(Charsets.UTF_8).size.toLong() + 1
      if (bytes > MAX_DIFF_BYTES) tooLarge("单个文件的改动对比过大，请在 Host 查看")
      out.append(text).append('\n')
    }
    if (hunks.length() > 0) {
      // Display paths are validated to contain no control characters before entering patch headers.
      append("--- a/$display")
      append("+++ b/$display")
    }
    for (i in 0 until hunks.length()) {
      val hunk = hunks.opt(i) as? JSONObject ?: malformed("diff.hunks[$i]")
      val oldStart = integer(hunk.opt("oldStart"), "hunk.oldStart")
      val oldLines = integer(hunk.opt("oldLines"), "hunk.oldLines")
      val newStart = integer(hunk.opt("newStart"), "hunk.newStart")
      val newLines = integer(hunk.opt("newLines"), "hunk.newLines")
      val lines = hunk.opt("lines") as? JSONArray ?: malformed("hunk.lines")
      append("@@ -$oldStart,$oldLines +$newStart,$newLines @@")
      for (j in 0 until lines.length()) {
        val line = lines.opt(j) as? String ?: malformed("hunk.lines[$j]")
        if (line.firstOrNull() !in listOf('+', '-', ' ') || '\n' in line || '\u0000' in line) {
          malformed("hunk.lines[$j]")
        }
        append(line)
      }
    }
    return out.toString()
  }

  private fun path(value: JSONObject, field: String): String {
    val text = value.opt(field) as? String ?: malformed(field)
    if (text.isBlank() || text.length > MAX_PATH_LENGTH || text.any { it.isISOControl() }) malformed(field)
    return text
  }

  private fun integer(value: Any?, field: String, min: Long = 0, max: Long = MAX_SAFE_INTEGER): Long {
    if (value !is Number) malformed(field)
    val number = value.toString().toBigDecimalOrNull() ?: malformed(field)
    if (number < min.toBigDecimal() || number > max.toBigDecimal() || number.stripTrailingZeros().scale() > 0) malformed(field)
    return number.toLong()
  }

  private fun malformed(field: String): Nothing = throw ApiException(502, "DeepSeek Harness 改动数据格式无效：$field")
  private fun tooLarge(message: String): Nothing = throw ApiException(413, message)

  private companion object {
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    const val MAX_CHANGE_TURNS = 8
    const val MAX_CHANGED_FILES = 60
    const val MAX_SUMMARY_FILES = 5_000
    const val MAX_PATH_LENGTH = 4_096
    const val MAX_DIFF_BYTES = 1_048_576L
    const val MAX_TOTAL_BYTES = 4_194_304L
  }
}
