package com.igng.opencode.lagoon.core

/** An attachment ready for the wire: [uri] is a `data:` URI. */
data class InlineFile(val name: String, val mime: String, val uri: String)

object AttachmentPolicy {
  /** OpenCode V2 rejects decoded attachments over 20 MiB; keep one message within the same budget. */
  const val MAX_BYTES = 20L * 1024 * 1024
  /** Images are scaled to the server's default `media.image` limit before upload. */
  const val MAX_IMAGE_SIDE = 2000
  /** Image formats the model actually receives (see OpenCode "Attachments"). Others are re-encoded. */
  val MODEL_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
  private val TEXT_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-sh",
    "application/x-yaml", "application/yaml", "application/toml", "application/sql", "image/svg+xml")
  private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "json", "jsonc", "yaml", "yml", "toml", "xml", "html", "htm",
    "css", "scss", "js", "jsx", "ts", "tsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "cc", "cpp", "h", "hpp",
    "sh", "bash", "sql", "swift", "php", "ini", "cfg", "conf", "properties", "env", "log", "csv", "tsv", "gradle", "svg", "diff", "patch")

  enum class Kind { IMAGE, TEXT }

  /** Only images and text reach the model; PDF, audio, video and other binaries are dropped by OpenCode. */
  fun kind(mime: String, name: String): Kind? {
    val type = mime.substringBefore(';').trim().lowercase()
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
      type == "image/svg+xml" || extension == "svg" -> Kind.TEXT
      type.startsWith("image/") -> Kind.IMAGE
      type.startsWith("text/") || type in TEXT_TYPES || extension in TEXT_EXTENSIONS -> Kind.TEXT
      else -> null
    }
  }

  fun rejection(mime: String, name: String, size: Long, pendingBytes: Long): String? = when {
    kind(mime, name) == null -> "模型读不到「$name」这类文件，只能发送图片或文本文件"
    size > MAX_BYTES -> "「$name」超过 20 MB，无法发送"
    pendingBytes + size > MAX_BYTES -> "附件合计超过 20 MB，请分几条消息发送"
    else -> null
  }

  fun dataUri(mime: String, bytes: ByteArray): String =
    "data:${mime.substringBefore(';').trim().ifBlank { "application/octet-stream" }};base64," +
      java.util.Base64.getEncoder().encodeToString(bytes)

  /** Text files are sent as UTF-8 text so the model gets the decoded content and filename. */
  fun wireMime(kind: Kind, mime: String): String = if (kind == Kind.TEXT) "text/plain" else mime
}
