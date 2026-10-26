package com.igng.opencode.lagoon.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A file picked on this phone. It is copied into the app cache when picked (content URIs can lose
 * their grant) and sent inline as a `data:` URI with the prompt — OpenCode reads attachments from
 * the request itself, so nothing is written into the user's project on the server.
 */
data class LocalAttachment(val id: String, val name: String, val mime: String, val size: Long, val path: String) {
  val image: Boolean get() = mime.startsWith("image/")
}

class AttachmentImporter(private val context: Context) {
  private val directory: File get() = File(context.cacheDir, "attachments").apply { mkdirs() }

  fun import(uri: Uri, pendingBytes: Long): LocalAttachment {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "附件"
    var declaredSize = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { cursor.getString(it) }?.let { name = it }
        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
      }
    }
    val mime = resolver.getType(uri) ?: referenceMime(name)
    val kind = AttachmentPolicy.kind(mime, name) ?: throw IOException(AttachmentPolicy.rejection(mime, name, 0, 0))
    val target = File(directory, UUID.randomUUID().toString())
    try {
      if (kind == AttachmentPolicy.Kind.IMAGE) return importImage(uri, name, mime, target, pendingBytes)
      copyBounded(uri, target, AttachmentPolicy.MAX_BYTES)
      AttachmentPolicy.rejection(mime, name, target.length(), pendingBytes)?.let { throw IOException(it) }
      if (declaredSize > 0 && target.length() != declaredSize) Diagnostics.warn("Attachment", "文件大小与声明不一致：$name")
      return LocalAttachment(target.name, name, AttachmentPolicy.wireMime(kind, mime), target.length(), target.absolutePath)
    } catch (error: Exception) {
      target.delete()
      throw error
    }
  }

  private fun importImage(uri: Uri, name: String, mime: String, target: File, pendingBytes: Long): LocalAttachment {
    val source = File(directory, "${target.name}.source")
    try {
      copyBounded(uri, source, 64L * 1024 * 1024)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(source.path, bounds)
      val longSide = maxOf(bounds.outWidth, bounds.outHeight)
      val type = mime.substringBefore(';').lowercase()
      val keep = type in AttachmentPolicy.MODEL_IMAGE_TYPES && longSide in 1..AttachmentPolicy.MAX_IMAGE_SIDE && source.length() <= 4L * 1024 * 1024
      if (keep) {
        check(source.renameTo(target)) { "无法缓存图片" }
        AttachmentPolicy.rejection(type, name, target.length(), pendingBytes)?.let { throw IOException(it) }
        return LocalAttachment(target.name, name, type, target.length(), target.absolutePath)
      }
      if (longSide <= 0) throw IOException("无法读取图片「$name」")
      var sample = 1
      while (longSide / (sample * 2) >= AttachmentPolicy.MAX_IMAGE_SIDE) sample *= 2
      val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IOException("无法读取图片「$name」")
      val oriented = orient(decoded, source)
      val scale = AttachmentPolicy.MAX_IMAGE_SIDE.toFloat() / maxOf(oriented.width, oriented.height)
      val scaled = if (scale < 1f) Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1), (oriented.height * scale).toInt().coerceAtLeast(1), true) else oriented
      target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
      val jpegName = name.substringBeforeLast('.', name) + ".jpg"
      AttachmentPolicy.rejection("image/jpeg", jpegName, target.length(), pendingBytes)?.let { throw IOException(it) }
      return LocalAttachment(target.name, jpegName, "image/jpeg", target.length(), target.absolutePath)
    } finally {
      source.delete()
    }
  }

  /** Camera photos store rotation in EXIF; re-encoding would otherwise send them sideways. */
  private fun orient(bitmap: Bitmap, file: File): Bitmap {
    val degrees = runCatching {
      when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
      }
    }.getOrDefault(0f)
    if (degrees == 0f) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
  }

  private fun copyBounded(uri: Uri, target: File, limit: Long) {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
    input.use { stream ->
      target.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val read = stream.read(buffer)
          if (read < 0) break
          total += read
          if (total > limit) throw IOException("文件超过 ${limit / 1024 / 1024} MB，无法发送")
          output.write(buffer, 0, read)
        }
      }
    }
  }

  fun inline(attachment: LocalAttachment): InlineFile {
    val file = File(attachment.path)
    if (!file.isFile) throw IOException("附件「${attachment.name}」已失效，请重新选择")
    return InlineFile(attachment.name, attachment.mime, AttachmentPolicy.dataUri(attachment.mime, file.readBytes()))
  }

  fun discard(attachments: List<LocalAttachment>) {
    attachments.forEach { File(it.path).takeIf { file -> file.parentFile == directory }?.delete() }
  }
}
