package com.igng.lagoon.hub

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

/**
 * Serves the built web client. Hashed assets are immutable; `index.html`, the service worker and the
 * manifest are always revalidated so a new release is picked up on the next load. Any other path that
 * is not a file is a client-side route and gets `index.html`.
 */
class StaticFiles(webRoot: Path) {
  private val root: Path = webRoot.toAbsolutePath().normalize()
  private class Cached(val modified: Long, val bytes: ByteArray, val gzip: ByteArray?)

  private val cache = ConcurrentHashMap<Path, Cached>()

  val available: Boolean get() = Files.isRegularFile(root.resolve("index.html"))

  fun serve(call: Call) {
    val requested = call.path.trimStart('/')
    val normalized = root.resolve(requested).normalize()
    val file = if (normalized.startsWith(root) && requested.isNotEmpty() && Files.isRegularFile(normalized)) normalized else null
    // Unknown files under the asset directory are real 404s; everything else is an app route.
    if (file == null && (requested.startsWith("assets/") || requested.contains('.'))) {
      throw HttpError(404, "not-found", "文件不存在")
    }
    val target = file ?: root.resolve("index.html")
    if (!Files.isRegularFile(target)) throw HttpError(503, "web-missing", "网页文件缺失，请检查镜像是否完整")
    val name = target.fileName.toString()
    val cached = load(target)
    val type = contentType(name)
    val headers = mutableMapOf("Vary" to "Accept-Encoding", "X-Content-Type-Options" to "nosniff")
    headers["Cache-Control"] = when {
      requested.startsWith("assets/") -> "public, max-age=31536000, immutable"
      name == "sw.js" -> "no-cache"
      else -> "no-cache"
    }
    if (name == "sw.js") headers["Service-Worker-Allowed"] = "/"
    if (name == "index.html") securityHeaders(headers)
    val acceptsGzip = call.header("Accept-Encoding")?.contains("gzip") == true
    if (acceptsGzip && cached.gzip != null) {
      headers["Content-Encoding"] = "gzip"
      call.respondBytes(200, cached.gzip, type, headers)
    } else {
      call.respondBytes(200, cached.bytes, type, headers)
    }
  }

  private fun load(path: Path): Cached {
    val modified = Files.getLastModifiedTime(path).toMillis()
    cache[path]?.takeIf { it.modified == modified }?.let { return it }
    val bytes = Files.readAllBytes(path)
    val name = path.fileName.toString()
    val gzip = if (bytes.size > 1024 && compressible(name)) gzip(bytes) else null
    return Cached(modified, bytes, gzip).also { cache[path] = it }
  }

  private fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
  }

  companion object {
    private val types = mapOf(
      "html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8", "mjs" to "text/javascript; charset=utf-8",
      "css" to "text/css; charset=utf-8", "json" to "application/json; charset=utf-8", "webmanifest" to "application/manifest+json; charset=utf-8",
      "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "ico" to "image/x-icon",
      "woff2" to "font/woff2", "woff" to "font/woff", "txt" to "text/plain; charset=utf-8", "map" to "application/json; charset=utf-8"
    )

    fun contentType(name: String): String = types[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    private fun compressible(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in
      setOf("html", "js", "mjs", "css", "json", "webmanifest", "svg", "txt", "map")

    /** The page itself only runs its own code and talks to its own origin. */
    fun securityHeaders(headers: MutableMap<String, String>) {
      headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; " +
        "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'"
      headers["Referrer-Policy"] = "no-referrer"
      headers["X-Frame-Options"] = "DENY"
      headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=(), payment=()"
    }
  }
}
