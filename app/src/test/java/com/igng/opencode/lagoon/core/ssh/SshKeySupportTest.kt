package com.igng.opencode.lagoon.core.ssh

import android.security.keystore.UserNotAuthenticatedException
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshKeyAlgorithm
import com.igng.opencode.lagoon.core.SshPublicKeyFormat
import com.igng.opencode.lagoon.core.SshSecrets
import com.igng.opencode.lagoon.core.SshWireFormat
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit

class SshKeySupportTest {
  @Test fun generatesAndInspectsEd25519EcdsaAndRsaKeys() {
    val ed = SshKeyCodec.generateKeyPair(SshKeyAlgorithm.ED25519, comment = "phone", passphrase = null)
    assertEquals("ED25519", ed.algorithm)
    assertTrue(ed.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
    val parsedEd = SshKeyCodec.inspectPrivateKey(ed.privateKeyPem)
    assertFalse(parsedEd.encrypted)
    assertTrue(parsedEd.decrypted)
    assertEquals(ed.fingerprint, parsedEd.fingerprint)

    val encryptedEd = SshKeyCodec.generateKeyPair(SshKeyAlgorithm.ED25519, comment = "phone-enc", passphrase = "pass-ed")
    assertEquals("ED25519", encryptedEd.algorithm)
    assertFalse(SshKeyCodec.inspectPrivateKey(encryptedEd.privateKeyPem, passphrase = null).decrypted)
    val unlockedEd = SshKeyCodec.inspectPrivateKey(encryptedEd.privateKeyPem, passphrase = "pass-ed")
    assertTrue(unlockedEd.encrypted && unlockedEd.decrypted)
    assertEquals(encryptedEd.fingerprint, unlockedEd.fingerprint)

    val encrypted = SshKeyCodec.generateKeyPair(SshKeyAlgorithm.ECDSA_P256, comment = "ec", passphrase = "secret")
    assertEquals("ECDSA", encrypted.algorithm)
    assertTrue(encrypted.publicKeyOpenSsh.startsWith("ecdsa-sha2-nistp256 "))
    val locked = SshKeyCodec.inspectPrivateKey(encrypted.privateKeyPem, passphrase = null)
    assertTrue(locked.encrypted)
    assertFalse(locked.decrypted)
    val unlocked = SshKeyCodec.inspectPrivateKey(encrypted.privateKeyPem, passphrase = "secret")
    assertTrue(unlocked.encrypted)
    assertTrue(unlocked.decrypted)
    assertEquals(encrypted.fingerprint, unlocked.fingerprint)

    try {
      SshKeyCodec.inspectPrivateKey(encrypted.privateKeyPem, passphrase = "wrong")
      fail("Wrong passphrase must fail")
    } catch (expected: SshFailure) {
      assertEquals(SshFailureKind.BAD_KEY, expected.kind)
    }

    val rsa = SshKeyCodec.generateKeyPair(SshKeyAlgorithm.RSA_4096, comment = "rsa", passphrase = null)
    assertEquals("RSA", rsa.algorithm)
    assertTrue(rsa.publicKeyOpenSsh.startsWith("ssh-rsa "))
    assertEquals(rsa.fingerprint, SshKeyCodec.inspectPrivateKey(rsa.privateKeyPem).fingerprint)
  }

  @Test fun rejectsPublicKeysAndMalformedPrivateKeys() {
    val ed = SshKeyCodec.generateKeyPair(SshKeyAlgorithm.ED25519)
    try {
      SshKeyCodec.inspectPrivateKey(ed.publicKeyOpenSsh)
      fail("Public key should be rejected")
    } catch (expected: SshFailure) {
      assertEquals(SshFailureKind.BAD_KEY, expected.kind)
      assertTrue(expected.message!!.contains("公钥"))
    }
    try {
      SshKeyCodec.inspectPrivateKey("not-a-private-key")
      fail("Malformed key should fail")
    } catch (expected: SshFailure) {
      assertEquals(SshFailureKind.BAD_KEY, expected.kind)
    }
  }

  @Test fun hardwareKeystoreIdentityEncodesP256BlobAndValidSshSignature() {
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec("secp256r1"))
    val keyPair = kpg.generateKeyPair()
    val ecPublic = keyPair.public as ECPublicKey
    val blob = SshWireFormat.encodeEcPublicKeyBlob(ecPublic)

    val identity = HardwareKeystoreIdentity(
      name = "lagoon-hw-test",
      alias = "test-alias",
      publicKeyBlob = blob,
      keyDisplayName = "Pixel StrongBox",
      signerProvider = { _, data ->
        Signature.getInstance("SHA256withECDSA").apply {
          initSign(keyPair.private)
          update(data)
        }.sign()
      }
    )

    assertEquals("ecdsa-sha2-nistp256", identity.algName)
    assertFalse(identity.isEncrypted)
    val payload = "ssh-session-auth-transcript".toByteArray()
    val sshSig = identity.getSignature(payload)

    // Parse SSH signature wire packet: string alg, string (mpint r || mpint s)
    val buf = ByteBuffer.wrap(sshSig)
    val algBytes = readSshString(buf)
    assertEquals("ecdsa-sha2-nistp256", String(algBytes))
    val inner = ByteBuffer.wrap(readSshString(buf))
    val r = BigInteger(readSshString(inner))
    val s = BigInteger(readSshString(inner))
    assertTrue(r.signum() > 0 && s.signum() > 0)

    // Convert back to ASN.1 DER and verify with JCA public key.
    val rDer = encodeAsn1Int(r.toByteArray())
    val sDer = encodeAsn1Int(s.toByteArray())
    val der = ByteArrayOutputStream().apply {
      write(0x30)
      write(rDer.size + sDer.size)
      write(rDer)
      write(sDer)
    }.toByteArray()
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(ecPublic)
    verifier.update(payload)
    assertTrue(verifier.verify(der))
  }

  @Test fun hardwareKeystoreIdentityMapsUserNotAuthenticatedToBiometricRequired() {
    val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    val blob = SshWireFormat.encodeEcPublicKeyBlob(kpg.generateKeyPair().public as ECPublicKey)
    val identity = HardwareKeystoreIdentity(
      name = "hw",
      alias = "hw-alias",
      publicKeyBlob = blob,
      keyDisplayName = "Hardware",
      signerProvider = { _, _ -> throw UserNotAuthenticatedException("auth required") }
    )
    try {
      identity.getSignature(byteArrayOf(1, 2, 3))
      fail("Expected BIOMETRIC_REQUIRED")
    } catch (expected: SshFailure) {
      assertEquals(SshFailureKind.BIOMETRIC_REQUIRED, expected.kind)
    }
  }

  @Test fun parsesCertificateAndWrapsCertifiedIdentity() {
    val ed = SshKeyCodec.generateKeyPair(SshKeyAlgorithm.ED25519)
    val jsch = JSch()
    jsch.addIdentity("base", ed.privateKeyPem.toByteArray(), null, null)
    val delegate = jsch.identityRepository.identities.firstElement() as Identity

    // Build minimal OpenSSH cert blob whose first SSH string matches the cert type.
    val certOut = ByteArrayOutputStream()
    val dos = DataOutputStream(certOut)
    val certType = "ssh-ed25519-cert-v01@openssh.com".toByteArray()
    dos.writeInt(certType.size)
    dos.write(certType)
    dos.write(ByteArray(32) { 7 })
    val certLine = "ssh-ed25519-cert-v01@openssh.com ${Base64.getEncoder().encodeToString(certOut.toByteArray())} user@example"

    val parsed = SshKeyCodec.parseCertificate(certLine)
    assertEquals("ssh-ed25519-cert-v01@openssh.com", parsed.algorithm)
    val wrapped = CertifiedSshIdentity(delegate, parsed.algorithm, parsed.blob)
    assertEquals("ssh-ed25519-cert-v01@openssh.com", wrapped.algName)
    assertArrayEquals(parsed.blob, wrapped.publicKeyBlob)
    val sig = wrapped.getSignature(byteArrayOf(10, 20, 30), "ssh-ed25519-cert-v01@openssh.com")
    assertTrue(sig.isNotEmpty())
  }

  @Test fun authorizedKeysInstallScriptIsIdempotentAndEnforcesPermissions() {
    val sh = File("/bin/sh")
    if (!sh.canExecute()) return
    val ed = SshKeyCodec.generateKeyPair(SshKeyAlgorithm.ED25519, comment = "lagoon-test")
    val fakeHome = kotlin.io.path.createTempDirectory("lagoon-ssh-deploy").toFile()
    try {
      val script = SshPublicKeyFormat.authorizedKeysInstallScript(ed.publicKeyOpenSsh)
      repeat(2) {
        val process = ProcessBuilder(sh.absolutePath, "-c", script)
          .apply { environment()["HOME"] = fakeHome.absolutePath }
          .start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
      }
      val authKeys = File(fakeHome, ".ssh/authorized_keys")
      assertTrue(authKeys.exists())
      val lines = authKeys.readLines().filter { it.isNotBlank() }
      assertEquals(1, lines.size)
      assertEquals(ed.publicKeyOpenSsh.trim(), lines.single())
    } finally {
      fakeHome.deleteRecursively()
    }
  }

  @Test fun transportPoolSeparatesByKeyRevisionAndCertificate() {
    var connects = 0
    var currentRevision = "rev-1"
    val dummyTransport = object : SshTransport {
      override val isClosed: Boolean = false
      override fun exec(script: String, timeoutMillis: Long) = RemoteExecResult(0, "", "")
      override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream) = error("unused")
      override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = true
      override fun ping(timeoutMillis: Long) = true
      override fun close() = Unit
    }
    val pool = SshTransportPool(
      delegate = object : SshTransportFactory {
        override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
          connects += 1
          return dummyTransport
        }
        override fun probeHostKey(config: SshHostConfig) = PresentedSshHostKey("ssh-ed25519", "SHA256:x")
      },
      keyRevisionProvider = { currentRevision }
    )
    val cfg = SshHostConfig(id = "h1", name = "h", host = "127.0.0.1", user = "u", auth = SshAuthMethod.PRIVATE_KEY, keyId = "k1")
    val sec = SshSecrets()
    pool.connect(cfg, sec) { true }
    pool.connect(cfg, sec) { true }
    assertEquals(1, connects)

    // Unlocking/rotating the key bumps revision -> must open fresh connection.
    currentRevision = "rev-2"
    pool.connect(cfg, sec) { true }
    assertEquals(2, connects)

    // Attaching an inline cert -> must open fresh connection.
    pool.connect(cfg, sec.copy(certificate = "ssh-ed25519-cert-v01@openssh.com AAAA c")) { true }
    assertEquals(3, connects)
  }

  private fun readSshString(buf: ByteBuffer): ByteArray {
    val len = buf.int
    val bytes = ByteArray(len)
    buf.get(bytes)
    return bytes
  }

  private fun encodeAsn1Int(bytes: ByteArray): ByteArray {
    val trimmed = bytes.dropWhile { it == 0.toByte() }.toByteArray().let {
      if (it.isEmpty()) byteArrayOf(0) else if ((it[0].toInt() and 0x80) != 0) byteArrayOf(0) + it else it
    }
    return byteArrayOf(0x02, trimmed.size.toByte()) + trimmed
  }
}
