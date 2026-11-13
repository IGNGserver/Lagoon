package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshKeyAlgorithm
import com.igng.opencode.lagoon.core.SshPublicKeyFormat
import com.igng.opencode.lagoon.core.SshWireFormat
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.jbcrypt.BCrypt
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ParsedSshPrivateKey(
  val algorithm: String,
  val fingerprint: String,
  val publicKeyOpenSsh: String,
  val encrypted: Boolean,
  val decrypted: Boolean
)

internal data class GeneratedSshKey(
  val privateKeyPem: String,
  val publicKeyOpenSsh: String,
  val fingerprint: String,
  val algorithm: String
)

internal data class ParsedSshCertificate(
  val algorithm: String,
  val blob: ByteArray,
  val normalizedLine: String,
  val fingerprint: String
)

/**
 * JSch + BouncyCastle backed parser, validator, generator and OpenSSH certificate wrapper.
 * Supports OpenSSH v1, PEM, PKCS#8 and PuTTY (.ppk) private keys without extra crypto libraries.
 */
internal object SshKeyCodec {
  fun inspectPrivateKey(privateKeyText: String, passphrase: String? = null): ParsedSshPrivateKey {
    val trimmed = privateKeyText.trim()
    if (trimmed.isEmpty()) {
      throw SshFailure(SshFailureKind.BAD_KEY, "请填写或导入 SSH 私钥", retryable = false)
    }
    if (SshPublicKeyFormat.looksLikePublicKey(trimmed)) {
      throw SshFailure(
        SshFailureKind.BAD_KEY,
        "这是 SSH 公钥，不是私钥",
        "请导入私钥文件，或在本机生成新密钥后再把公钥添加到服务器。",
        retryable = false
      )
    }
    val jsch = JSch()
    val pair = try {
      KeyPair.load(jsch, trimmed.toByteArray(Charsets.UTF_8), null)
    } catch (error: Exception) {
      throw SshFailure(
        SshFailureKind.BAD_KEY,
        "SSH 私钥格式无法识别",
        error.message.orEmpty(),
        retryable = false
      )
    }
    try {
      val encrypted = pair.isEncrypted
      var decrypted = !encrypted
      if (encrypted && !passphrase.isNullOrEmpty()) {
        if (!pair.decrypt(passphrase.toByteArray(Charsets.UTF_8))) {
          throw SshFailure(SshFailureKind.BAD_KEY, "私钥密码不正确", retryable = false)
        }
        decrypted = true
      }
      val algorithm = mapAlgorithm(pair)
      val blob = runCatching { pair.publicKeyBlob }.getOrNull()
      val fingerprint = if (blob != null && blob.isNotEmpty()) {
        SshPublicKeyFormat.fingerprintSha256(blob)
      } else {
        ""
      }
      val publicKeyOpenSsh = if (blob != null && blob.isNotEmpty()) {
        val pubOut = ByteArrayOutputStream()
        runCatching { pair.writePublicKey(pubOut, "lagoon") }
        pubOut.toString(Charsets.UTF_8.name()).trim()
      } else {
        ""
      }
      return ParsedSshPrivateKey(
        algorithm = algorithm,
        fingerprint = fingerprint,
        publicKeyOpenSsh = publicKeyOpenSsh,
        encrypted = encrypted,
        decrypted = decrypted
      )
    } finally {
      pair.dispose()
    }
  }

  fun generateKeyPair(
    algorithm: SshKeyAlgorithm,
    comment: String = "lagoon",
    passphrase: String? = null
  ): GeneratedSshKey {
    val cleanComment = comment.trim().ifBlank { "lagoon" }
    val passBytes = passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
    if (algorithm == SshKeyAlgorithm.ED25519) {
      return generateEd25519OpenSsh(cleanComment, passBytes)
    }
    val jsch = JSch()
    val pair = when (algorithm) {
      SshKeyAlgorithm.ECDSA_P256 -> KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
      SshKeyAlgorithm.RSA_4096 -> KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
      SshKeyAlgorithm.ED25519 -> error("unreachable")
    }
    try {
      pair.setPublicKeyComment(cleanComment)
      val prvOut = ByteArrayOutputStream()
      if (passBytes != null) {
        pair.writePrivateKey(prvOut, passBytes)
      } else {
        pair.writePrivateKey(prvOut)
      }
      val pubOut = ByteArrayOutputStream()
      pair.writePublicKey(pubOut, cleanComment)
      val blob = pair.publicKeyBlob ?: error("无法读取生成的公钥")
      return GeneratedSshKey(
        privateKeyPem = prvOut.toString(Charsets.UTF_8.name()).trim(),
        publicKeyOpenSsh = pubOut.toString(Charsets.UTF_8.name()).trim(),
        fingerprint = SshPublicKeyFormat.fingerprintSha256(blob),
        algorithm = mapAlgorithm(pair)
      )
    } finally {
      pair.dispose()
    }
  }

  /**
   * Emits a standard `openssh-key-v1` PEM Ed25519 key (using `aes256-ctr` + `bcrypt` when a
   * passphrase is provided), working around JSch 0.2.25's `KeyPairEdDSA.getPrivateKey()` gap.
   */
  private fun generateEd25519OpenSsh(comment: String, passphrase: ByteArray?): GeneratedSshKey {
    val random = SecureRandom()
    val generator = Ed25519KeyPairGenerator()
    generator.init(Ed25519KeyGenerationParameters(random))
    val pair = generator.generateKeyPair()
    val priv = pair.private as Ed25519PrivateKeyParameters
    val pub = pair.public as Ed25519PublicKeyParameters
    val priv32 = priv.encoded
    val pub32 = pub.encoded
    val pubBlob = ByteArrayOutputStream().apply {
      DataOutputStream(this).apply {
        writeSshString("ssh-ed25519".toByteArray(Charsets.US_ASCII))
        writeSshString(pub32)
      }
    }.toByteArray()

    val blockSize = if (passphrase != null) 16 else 8
    val checkInt = random.nextInt()
    val privateBlockOut = ByteArrayOutputStream()
    DataOutputStream(privateBlockOut).apply {
      writeInt(checkInt)
      writeInt(checkInt)
      writeSshString("ssh-ed25519".toByteArray(Charsets.US_ASCII))
      writeSshString(pub32)
      writeSshString(priv32 + pub32)
      writeSshString(comment.toByteArray(Charsets.UTF_8))
    }
    var pad = 1
    while (privateBlockOut.size() % blockSize != 0) {
      privateBlockOut.write(pad++)
    }
    val plainPrivateBlock = privateBlockOut.toByteArray()

    val cipherName: String
    val kdfName: String
    val kdfOptions: ByteArray
    val sealedPrivateBlock: ByteArray
    if (passphrase != null) {
      val salt = ByteArray(16).also(random::nextBytes)
      val rounds = 16
      val derived = ByteArray(48)
      BCrypt().pbkdf(passphrase, salt, rounds, derived)
      val cipher = Cipher.getInstance("AES/CTR/NoPadding")
      cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(derived, 0, 32, "AES"),
        IvParameterSpec(derived, 32, 16)
      )
      sealedPrivateBlock = cipher.doFinal(plainPrivateBlock)
      derived.fill(0)
      cipherName = "aes256-ctr"
      kdfName = "bcrypt"
      kdfOptions = ByteArrayOutputStream().apply {
        DataOutputStream(this).apply {
          writeSshString(salt)
          writeInt(rounds)
        }
      }.toByteArray()
    } else {
      cipherName = "none"
      kdfName = "none"
      kdfOptions = ByteArray(0)
      sealedPrivateBlock = plainPrivateBlock
    }

    val openSshBytes = ByteArrayOutputStream().apply {
      write("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
      DataOutputStream(this).apply {
        writeSshString(cipherName.toByteArray(Charsets.US_ASCII))
        writeSshString(kdfName.toByteArray(Charsets.US_ASCII))
        writeSshString(kdfOptions)
        writeInt(1)
        writeSshString(pubBlob)
        writeSshString(sealedPrivateBlock)
      }
    }.toByteArray()

    priv32.fill(0)
    plainPrivateBlock.fill(0)
    val base64 = Base64.getMimeEncoder(70, "\n".toByteArray(Charsets.US_ASCII)).encodeToString(openSshBytes)
    val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$base64\n-----END OPENSSH PRIVATE KEY-----"
    return GeneratedSshKey(
      privateKeyPem = pem,
      publicKeyOpenSsh = SshWireFormat.encodeOpenSshLine("ssh-ed25519", pubBlob, comment),
      fingerprint = SshPublicKeyFormat.fingerprintSha256(pubBlob),
      algorithm = "ED25519"
    )
  }

  private fun DataOutputStream.writeSshString(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
  }

  fun parseCertificate(certificateText: String): ParsedSshCertificate {
    val trimmed = certificateText.trim()
    val firstLine = trimmed.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
      ?: throw SshFailure(SshFailureKind.BAD_KEY, "请填写 OpenSSH 证书内容", retryable = false)
    val parsed = SshPublicKeyFormat.extractPublicKeyBlob(firstLine)
      ?: throw SshFailure(SshFailureKind.BAD_KEY, "OpenSSH 证书格式无效", retryable = false)
    val (type, blob) = parsed
    if (!type.contains("-cert-v01@openssh.com")) {
      throw SshFailure(
        SshFailureKind.BAD_KEY,
        "这不是 OpenSSH 证书（需以 -cert-v01@openssh.com 开头）",
        retryable = false
      )
    }
    val effectiveAlg = when {
      type == "ssh-rsa-cert-v01@openssh.com" -> "rsa-sha2-512-cert-v01@openssh.com"
      else -> type
    }
    return ParsedSshCertificate(
      algorithm = effectiveAlg,
      blob = blob,
      normalizedLine = firstLine,
      fingerprint = SshPublicKeyFormat.fingerprintSha256(blob)
    )
  }

  private fun mapAlgorithm(pair: KeyPair): String = when (pair.keyType) {
    KeyPair.ED25519 -> "ED25519"
    KeyPair.ED448 -> "ED448"
    KeyPair.ECDSA -> "ECDSA"
    KeyPair.RSA -> "RSA"
    KeyPair.DSA -> "DSA"
    else -> SshPublicKeyFormat.algorithmLabel(pair.keyTypeString.orEmpty())
  }
}

/** Wraps an unlocked [Identity] to present an OpenSSH user certificate (`-cert.pub`) during auth. */
internal class CertifiedSshIdentity(
  private val delegate: Identity,
  private val certAlg: String,
  private val certBlob: ByteArray
) : Identity {
  override fun setPassphrase(passphrase: ByteArray?): Boolean = delegate.setPassphrase(passphrase)
  override fun decrypt(): Boolean = delegate.decrypt()
  override fun getPublicKeyBlob(): ByteArray = certBlob
  override fun getSignature(data: ByteArray): ByteArray = delegate.getSignature(data)
  override fun getSignature(data: ByteArray, alg: String): ByteArray {
    val signingAlg = alg.removeSuffix("-cert-v01@openssh.com")
    return delegate.getSignature(data, signingAlg)
  }
  override fun getAlgName(): String = certAlg
  override fun getName(): String = "${delegate.name}-cert"
  override fun isEncrypted(): Boolean = delegate.isEncrypted
  override fun clear() = delegate.clear()
}
