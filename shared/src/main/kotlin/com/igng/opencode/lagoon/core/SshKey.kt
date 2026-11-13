package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID

/** Origin of a saved SSH identity in the local keychain. */
enum class SshKeySource(val label: String) {
  GENERATED("本机生成"),
  IMPORTED("导入"),
  HARDWARE_KEYSTORE("硬件密钥库")
}

/** How an encrypted key or protected identity is unlocked at connect time. */
enum class SshPassphrasePolicy(val label: String) {
  NONE("无口令"),
  SAVED("加密保存"),
  PROMPT("每次连接询问"),
  BIOMETRIC("生物识别解锁")
}

/** Algorithms supported for in-app SSH key generation. */
enum class SshKeyAlgorithm(val label: String, val sshType: String) {
  ED25519("Ed25519（推荐）", "ssh-ed25519"),
  ECDSA_P256("ECDSA P-256", "ecdsa-sha2-nistp256"),
  RSA_4096("RSA 4096", "ssh-rsa")
}

/**
 * Non-secret metadata for one SSH key in the app's global keychain.
 * Private keys and passphrases live exclusively in [SshKeyStore]'s Keystore-encrypted prefs,
 * or inside AndroidKeyStore hardware for [SshKeySource.HARDWARE_KEYSTORE].
 */
data class SshKeyProfile(
  val id: String,
  val name: String,
  val algorithm: String,
  val fingerprint: String,
  val publicKeyOpenSsh: String,
  val certificateOpenSsh: String? = null,
  val encrypted: Boolean = false,
  val passphrasePolicy: SshPassphrasePolicy = SshPassphrasePolicy.NONE,
  val source: SshKeySource = SshKeySource.IMPORTED,
  val biometricProtected: Boolean = false,
  val createdAt: Long = 0L
) {
  val hasCertificate: Boolean get() = !certificateOpenSsh.isNullOrBlank()

  val shortFingerprint: String
    get() = if (fingerprint.length <= 22) fingerprint else "${fingerprint.take(18)}…"

  val summary: String
    get() = buildString {
      append(algorithm)
      append(" · ")
      append(shortFingerprint)
      if (source == SshKeySource.HARDWARE_KEYSTORE) append(" · 硬件密钥")
      if (hasCertificate) append(" · 含证书")
      when {
        biometricProtected || passphrasePolicy == SshPassphrasePolicy.BIOMETRIC -> append(" · 生物识别")
        passphrasePolicy == SshPassphrasePolicy.PROMPT -> append(" · 连接时输入口令")
        encrypted -> append(" · 已加密")
      }
    }

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("algorithm", algorithm)
    .put("fingerprint", fingerprint)
    .put("publicKeyOpenSsh", publicKeyOpenSsh)
    .putOpt("certificateOpenSsh", certificateOpenSsh)
    .put("encrypted", encrypted)
    .put("passphrasePolicy", passphrasePolicy.name)
    .put("source", source.name)
    .put("biometricProtected", biometricProtected)
    .put("createdAt", createdAt)

  companion object {
    fun newId(): String = "key-${UUID.randomUUID()}"

    fun fromJson(raw: JSONObject): SshKeyProfile? {
      val id = raw.optString("id").trim().takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
      val name = raw.optString("name").trim().takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
      val algorithm = raw.optString("algorithm").trim().takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
      val fingerprint = raw.optString("fingerprint").trim().takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
      val publicKey = raw.optString("publicKeyOpenSsh").trim().takeIf { it.isNotBlank() && it.length <= 16_384 } ?: return null
      val cert = raw.optString("certificateOpenSsh").trim().takeIf { it.isNotBlank() && it.length <= 32_768 }
      val encrypted = raw.optBoolean("encrypted", false)
      val policy = SshPassphrasePolicy.entries.firstOrNull { it.name == raw.optString("passphrasePolicy") }
        ?: if (encrypted) SshPassphrasePolicy.SAVED else SshPassphrasePolicy.NONE
      val source = SshKeySource.entries.firstOrNull { it.name == raw.optString("source") }
        ?: SshKeySource.IMPORTED
      return SshKeyProfile(
        id = id,
        name = name,
        algorithm = algorithm,
        fingerprint = fingerprint,
        publicKeyOpenSsh = publicKey,
        certificateOpenSsh = cert,
        encrypted = encrypted,
        passphrasePolicy = policy,
        source = source,
        biometricProtected = raw.optBoolean("biometricProtected", false),
        createdAt = raw.optLong("createdAt", 0L)
      )
    }
  }
}

/** Secret material for one key in [SshKeyStore]. Never written to plaintext config. */
data class SshKeySecrets(
  val privateKey: String? = null,
  val passphrase: String? = null,
  val biometricCiphertext: String? = null,
  val biometricIv: String? = null
) {
  val hasBiometricBlob: Boolean get() = !biometricCiphertext.isNullOrBlank() && !biometricIv.isNullOrBlank()
}

internal object SshKeyCodec {
  const val VERSION = 1

  fun encode(keys: List<SshKeyProfile>): String = JSONObject()
    .put("version", VERSION)
    .put("keys", JSONArray().apply { keys.forEach { put(it.toJson()) } })
    .toString()

  fun decode(text: String): List<SshKeyProfile> {
    try {
      val json = JSONObject(text)
      if (json.optInt("version") != VERSION) throw IOException("SSH 密钥库版本无效")
      val array = json.optJSONArray("keys") ?: JSONArray()
      if (array.length() > 256) throw IOException("SSH 密钥数量过多")
      val keys = (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let(SshKeyProfile::fromJson)
      }
      if (keys.distinctBy { it.id }.size != keys.size) throw IOException("SSH 密钥 ID 重复")
      return keys
    } catch (error: IOException) {
      throw error
    } catch (error: Exception) {
      throw IOException("SSH 密钥库损坏", error)
    }
  }
}

/** Pure-Kotlin OpenSSH public key, certificate, and authorized_keys helpers. */
object SshPublicKeyFormat {
  private val PUBLIC_KEY_PREFIXES = listOf(
    "ssh-ed25519 ",
    "ssh-ed448 ",
    "ssh-rsa ",
    "ssh-dss ",
    "ecdsa-sha2-",
    "sk-ssh-ed25519@",
    "sk-ecdsa-sha2-"
  )

  fun looksLikePublicKey(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.startsWith("-----BEGIN PUBLIC KEY-----")) return true
    if (trimmed.contains("PRIVATE KEY-----")) return false
    return PUBLIC_KEY_PREFIXES.any { trimmed.startsWith(it) }
  }

  fun looksLikePuttyKey(text: String): Boolean =
    text.trimStart().startsWith("PuTTY-User-Key-File-")

  fun looksLikeCertificate(text: String): Boolean {
    val parts = extractPublicKeyBlob(text) ?: return false
    return parts.first.contains("-cert-v01@openssh.com")
  }

  fun extractPublicKeyBlob(line: String): Pair<String, ByteArray>? {
    val firstLine = line.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
      ?: return null
    val tokens = firstLine.split(Regex("\\s+"))
    if (tokens.size < 2) return null
    val type = tokens[0]
    if ('-' !in type && '@' !in type) return null
    val blob = runCatching { Base64.getDecoder().decode(tokens[1]) }.getOrNull() ?: return null
    if (blob.size < 16) return null
    return type to blob
  }

  fun fingerprintSha256(blob: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding()
      .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

  fun fingerprintSha256FromLine(line: String): String? =
    extractPublicKeyBlob(line)?.second?.let(::fingerprintSha256)

  fun algorithmLabel(sshType: String): String = when {
    "ed25519" in sshType.lowercase() -> "ED25519"
    "ed448" in sshType.lowercase() -> "ED448"
    "ecdsa" in sshType.lowercase() -> "ECDSA"
    "rsa" in sshType.lowercase() -> "RSA"
    "dss" in sshType.lowercase() -> "DSA"
    else -> sshType.uppercase()
  }

  /** Idempotent remote shell script to install a single-line OpenSSH public key into `~/.ssh/authorized_keys`. */
  fun authorizedKeysInstallScript(publicKeyOpenSsh: String): String {
    val singleLine = publicKeyOpenSsh.trim().lineSequence().first().trim()
    require(singleLine.isNotEmpty() && '\n' !in singleLine && '\r' !in singleLine) { "公钥格式无效" }
    val quotedKey = SshShell.quote(singleLine)
    return """
      set -eu
      PUB=$quotedKey
      mkdir -p "${'$'}HOME/.ssh"
      chmod 700 "${'$'}HOME/.ssh"
      AUTH="${'$'}HOME/.ssh/authorized_keys"
      touch "${'$'}AUTH"
      chmod 600 "${'$'}AUTH"
      if grep -qxF "${'$'}PUB" "${'$'}AUTH" 2>/dev/null; then
        printf 'LAGOON_KEY_EXISTS\n'
      else
        if [ -s "${'$'}AUTH" ] && [ -n "${'$'}(tail -c 1 "${'$'}AUTH" 2>/dev/null || true)" ]; then
          printf '\n' >> "${'$'}AUTH"
        fi
        printf '%s\n' "${'$'}PUB" >> "${'$'}AUTH"
        printf 'LAGOON_KEY_ADDED\n'
      fi
    """.trimIndent()
  }
}

/** SSH wire format encoder (RFC 4251 / RFC 4253 / RFC 5656 / RFC 8332) for hardware-backed keys. */
object SshWireFormat {
  fun writeUint32(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 24) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
  }

  fun writeString(out: ByteArrayOutputStream, bytes: ByteArray) {
    writeUint32(out, bytes.size)
    out.write(bytes)
  }

  fun writeString(out: ByteArrayOutputStream, text: String) {
    writeString(out, text.toByteArray(Charsets.UTF_8))
  }

  fun writeMpint(out: ByteArrayOutputStream, value: BigInteger) {
    if (value == BigInteger.ZERO) {
      writeUint32(out, 0)
      return
    }
    writeString(out, value.toByteArray())
  }

  fun toFixedUnsigned(value: BigInteger, byteLength: Int): ByteArray {
    val raw = value.toByteArray()
    val out = ByteArray(byteLength)
    val srcOffset = if (raw.size > byteLength) raw.size - byteLength else 0
    val copyLen = minOf(raw.size, byteLength)
    System.arraycopy(raw, srcOffset, out, byteLength - copyLen, copyLen)
    return out
  }

  fun encodeEcPublicKeyBlob(publicKey: ECPublicKey): ByteArray {
    val point = publicKey.w
    val coordSize = (publicKey.params.curve.field.fieldSize + 7) / 8
    val curveName = when (coordSize) {
      32 -> "nistp256"
      48 -> "nistp384"
      66 -> "nistp521"
      else -> throw IllegalArgumentException("不支持的 ECDSA 曲线位数: ${publicKey.params.curve.field.fieldSize}")
    }
    val algName = "ecdsa-sha2-$curveName"
    val q = ByteArray(1 + coordSize * 2)
    q[0] = 0x04
    val xBytes = toFixedUnsigned(point.affineX, coordSize)
    val yBytes = toFixedUnsigned(point.affineY, coordSize)
    System.arraycopy(xBytes, 0, q, 1, coordSize)
    System.arraycopy(yBytes, 0, q, 1 + coordSize, coordSize)
    return ByteArrayOutputStream().apply {
      writeString(this, algName)
      writeString(this, curveName)
      writeString(this, q)
    }.toByteArray()
  }

  fun encodeRsaPublicKeyBlob(publicKey: RSAPublicKey): ByteArray =
    ByteArrayOutputStream().apply {
      writeString(this, "ssh-rsa")
      writeMpint(this, publicKey.publicExponent)
      writeMpint(this, publicKey.modulus)
    }.toByteArray()

  fun encodeOpenSshLine(sshType: String, blob: ByteArray, comment: String = "lagoon"): String {
    val encoded = Base64.getEncoder().encodeToString(blob)
    val cleanComment = comment.trim().replace(Regex("\\s+"), "_")
    return if (cleanComment.isEmpty()) "$sshType $encoded" else "$sshType $encoded $cleanComment"
  }

  /** Converts an ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }` ECDSA signature into SSH wire format. */
  fun encodeEcdsaDerSignatureToSsh(derSignature: ByteArray, algName: String = "ecdsa-sha2-nistp256"): ByteArray {
    var pos = 0
    require(derSignature.size >= 8 && derSignature[pos++] == 0x30.toByte()) { "无效的 ECDSA DER 签名" }
    val seqLen = readDerLength(derSignature, pos).also { pos = it.second }.first
    require(pos + seqLen <= derSignature.size) { "ECDSA DER 签名长度越界" }
    require(derSignature[pos++] == 0x02.toByte()) { "ECDSA DER 缺少 r 整数" }
    val (rLen, posAfterRLen) = readDerLength(derSignature, pos)
    val r = BigInteger(1, derSignature.copyOfRange(posAfterRLen, posAfterRLen + rLen))
    pos = posAfterRLen + rLen
    require(derSignature[pos++] == 0x02.toByte()) { "ECDSA DER 缺少 s 整数" }
    val (sLen, posAfterSLen) = readDerLength(derSignature, pos)
    val s = BigInteger(1, derSignature.copyOfRange(posAfterSLen, posAfterSLen + sLen))

    val sigPayload = ByteArrayOutputStream().apply {
      writeMpint(this, r)
      writeMpint(this, s)
    }.toByteArray()

    return ByteArrayOutputStream().apply {
      writeString(this, algName)
      writeString(this, sigPayload)
    }.toByteArray()
  }

  fun encodeRsaSignatureToSsh(algName: String, rawSignature: ByteArray): ByteArray =
    ByteArrayOutputStream().apply {
      writeString(this, algName)
      writeString(this, rawSignature)
    }.toByteArray()

  private fun readDerLength(bytes: ByteArray, start: Int): Pair<Int, Int> {
    val first = bytes[start].toInt() and 0xFF
    if ((first and 0x80) == 0) return first to (start + 1)
    val count = first and 0x7F
    require(count in 1..4 && start + 1 + count <= bytes.size) { "无效的 DER 长度" }
    var length = 0
    for (i in 0 until count) {
      length = (length shl 8) or (bytes[start + 1 + i].toInt() and 0xFF)
    }
    return length to (start + 1 + count)
  }
}
