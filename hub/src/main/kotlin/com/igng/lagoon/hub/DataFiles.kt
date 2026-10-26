package com.igng.lagoon.hub

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Small files in the data volume: written whole through a temporary file and an atomic rename. */
object DataFiles {
  private val ownerOnly = runCatching { PosixFilePermissions.fromString("rw-------") }.getOrNull()

  fun read(path: Path): String? = if (Files.isRegularFile(path)) Files.readString(path) else null

  fun write(path: Path, text: String) {
    Files.createDirectories(path.parent)
    val temp = path.resolveSibling(".${path.fileName}.tmp")
    Files.writeString(temp, text)
    restrict(temp)
    try {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  fun restrict(path: Path) {
    if (ownerOnly != null) runCatching { Files.setPosixFilePermissions(path, ownerOnly) }
  }
}

/**
 * AES-256-GCM for the secrets kept in the data volume (harness passwords, cookies, signing secrets,
 * the VAPID private key). The key lives next to them in `secret.key`: this protects a leaked state
 * file, not a leaked volume.
 */
class SecretBox(private val key: ByteArray) {
  init { require(key.size == 32) { "secret.key 必须是 32 字节" } }

  fun seal(plain: String): String {
    val nonce = ByteArray(12).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return "v1:" + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
  }

  fun open(sealed: String): String {
    require(sealed.startsWith("v1:")) { "未知的密文格式" }
    val bytes = Base64.getDecoder().decode(sealed.removePrefix("v1:"))
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
    return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
  }

  companion object {
    private val random = SecureRandom()

    /** Loads `secret.key`, creating it with fresh random bytes on first start. */
    fun load(dataDir: Path): SecretBox {
      val path = dataDir.resolve("secret.key")
      if (!Files.isRegularFile(path)) {
        Files.createDirectories(dataDir)
        val key = ByteArray(32).also(random::nextBytes)
        val temp = path.resolveSibling(".secret.key.tmp")
        Files.write(temp, key)
        DataFiles.restrict(temp)
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE)
      }
      return SecretBox(Files.readAllBytes(path))
    }
  }
}
