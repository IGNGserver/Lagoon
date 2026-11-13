package com.igng.opencode.lagoon.core.ssh

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshKeyProfile
import com.igng.opencode.lagoon.core.SshKeySource
import com.igng.opencode.lagoon.core.SshPassphrasePolicy
import com.igng.opencode.lagoon.core.SshPublicKeyFormat
import com.igng.opencode.lagoon.core.SshWireFormat
import com.jcraft.jsch.Identity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

internal interface HardwareSshSigner {
  fun generateKey(keyId: String, name: String, requireBiometric: Boolean): SshKeyProfile
  fun createIdentity(profile: SshKeyProfile): Identity
  fun deleteKey(keyId: String)
}

/**
 * Hardware-backed SSH identity stored in AndroidKeyStore (TEE / StrongBox).
 * The ECDSA P-256 private key never leaves the secure hardware; only the OpenSSH public key
 * and SSH wire-format signatures cross into JVM memory.
 */
internal class AndroidKeystoreSshSigner : HardwareSshSigner {
  override fun generateKey(keyId: String, name: String, requireBiometric: Boolean): SshKeyProfile {
    val alias = aliasFor(keyId)
    val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
    val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
    if (requireBiometric) {
      builder.setUserAuthenticationRequired(true)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setUserAuthenticationParameters(
          BIOMETRIC_WINDOW_SECONDS,
          KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        )
      } else {
        @Suppress("DEPRECATION")
        builder.setUserAuthenticationValidityDurationSeconds(BIOMETRIC_WINDOW_SECONDS)
      }
    }
    generator.initialize(builder.build())
    val pair = generator.generateKeyPair()
    val ecPublic = pair.public as? ECPublicKey ?: error("AndroidKeyStore 未返回 EC 公钥")
    val blob = SshWireFormat.encodeEcPublicKeyBlob(ecPublic)
    val fingerprint = SshPublicKeyFormat.fingerprintSha256(blob)
    val publicLine = SshWireFormat.encodeOpenSshLine(ALG_ECDSA_P256, blob, name.ifBlank { "lagoon-hw" })
    return SshKeyProfile(
      id = keyId,
      name = name.trim().ifBlank { "硬件密钥" },
      algorithm = "ECDSA",
      fingerprint = fingerprint,
      publicKeyOpenSsh = publicLine,
      encrypted = false,
      passphrasePolicy = if (requireBiometric) SshPassphrasePolicy.BIOMETRIC else SshPassphrasePolicy.NONE,
      source = SshKeySource.HARDWARE_KEYSTORE,
      biometricProtected = requireBiometric,
      createdAt = System.currentTimeMillis()
    )
  }

  override fun createIdentity(profile: SshKeyProfile): Identity {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    val alias = aliasFor(profile.id)
    val cert = store.getCertificate(alias)
      ?: throw SshFailure(SshFailureKind.BAD_KEY, "硬件密钥「${profile.name}」在设备中不存在", retryable = false)
    val ecPublic = cert.publicKey as? ECPublicKey
      ?: throw SshFailure(SshFailureKind.BAD_KEY, "硬件密钥类型不支持", retryable = false)
    val blob = SshWireFormat.encodeEcPublicKeyBlob(ecPublic)
    return HardwareKeystoreIdentity(
      name = "lagoon-hw-${profile.id}",
      alias = alias,
      publicKeyBlob = blob,
      keyDisplayName = profile.name
    )
  }

  override fun deleteKey(keyId: String) {
    runCatching {
      val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
      val alias = aliasFor(keyId)
      if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
  }

  private fun aliasFor(keyId: String) = "lagoon-ssh-hw-$keyId"

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val ALG_ECDSA_P256 = "ecdsa-sha2-nistp256"
    const val BIOMETRIC_WINDOW_SECONDS = 30
  }
}

internal class HardwareKeystoreIdentity(
  private val name: String,
  private val alias: String,
  private val publicKeyBlob: ByteArray,
  private val keyDisplayName: String,
  private val signerProvider: (String, ByteArray) -> ByteArray = ::signWithAndroidKeyStore
) : Identity {
  override fun setPassphrase(passphrase: ByteArray?): Boolean = true
  override fun decrypt(): Boolean = true
  override fun getPublicKeyBlob(): ByteArray = publicKeyBlob
  override fun getAlgName(): String = "ecdsa-sha2-nistp256"
  override fun getName(): String = name
  override fun isEncrypted(): Boolean = false
  override fun clear() = Unit

  override fun getSignature(data: ByteArray): ByteArray = getSignature(data, algName)

  override fun getSignature(data: ByteArray, alg: String): ByteArray {
    val derSignature = try {
      signerProvider(alias, data)
    } catch (error: UserNotAuthenticatedException) {
      throw SshFailure(
        SshFailureKind.BIOMETRIC_REQUIRED,
        "需要生物识别或设备锁屏解锁硬件密钥「$keyDisplayName」",
        retryable = false
      )
    } catch (failure: SshFailure) {
      throw failure
    } catch (error: Exception) {
      if (error.cause is UserNotAuthenticatedException) {
        throw SshFailure(
          SshFailureKind.BIOMETRIC_REQUIRED,
          "需要生物识别或设备锁屏解锁硬件密钥「$keyDisplayName」",
          retryable = false
        )
      }
      throw SshFailure(SshFailureKind.BAD_KEY, "硬件密钥签名失败", error.message.orEmpty(), retryable = false)
    }
    return SshWireFormat.encodeEcdsaDerSignatureToSsh(derSignature, "ecdsa-sha2-nistp256")
  }

  companion object {
    private fun signWithAndroidKeyStore(alias: String, data: ByteArray): ByteArray {
      val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      val privateKey = store.getKey(alias, null) as? PrivateKey
        ?: throw SshFailure(SshFailureKind.BAD_KEY, "硬件私钥不可用", retryable = false)
      return Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(data)
        sign()
      }
    }
  }
}
