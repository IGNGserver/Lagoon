package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.Diagnostics
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The optional HTTPS listener. PWA installation, service workers and Web Push only work in a secure
 * context, so besides a reverse proxy the hub can terminate TLS itself:
 * - with the user's own certificate (`LAGOON_TLS_CERT` / `LAGOON_TLS_KEY`, PEM, PKCS#8 key);
 * - with a local certificate authority it creates once (`LAGOON_TLS=self-signed`), whose certificate
 *   the user installs on each device; the server certificate is reissued when the host list changes.
 */
class TlsSetup private constructor(val context: SSLContext, val port: Int, val selfSigned: Boolean, private val authority: Path?) {
  fun authorityCertificate(): ByteArray? = authority?.takeIf(Files::isRegularFile)?.let(Files::readAllBytes)

  companion object {
    fun create(config: HubConfig): TlsSetup? = when {
      config.tlsCert != null && config.tlsKey != null -> TlsSetup(fromPem(config.tlsCert, config.tlsKey), config.tlsPort, false, null)
      config.tlsSelfSigned -> {
        val dir = config.dataDir.resolve("tls")
        TlsSetup(selfSigned(dir, config.tlsHosts), config.tlsPort, true, dir.resolve("ca.crt"))
      }
      else -> null
    }

    private fun fromPem(certPath: Path, keyPath: Path): SSLContext {
      val chain = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(Files.readAllBytes(certPath)))
        .map { it as X509Certificate }
      require(chain.isNotEmpty()) { "LAGOON_TLS_CERT 中没有证书" }
      val pem = Files.readString(keyPath)
      require("BEGIN PRIVATE KEY" in pem) { "LAGOON_TLS_KEY 必须是 PKCS#8 格式（-----BEGIN PRIVATE KEY-----）" } // gitleaks:allow (PEM header marker, not a key)
      val der = Base64.getMimeDecoder().decode(pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----")) // gitleaks:allow (PEM header marker, not a key)
      val key: PrivateKey = listOf("EC", "RSA", "Ed25519").firstNotNullOfOrNull { algorithm ->
        runCatching { KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der)) }.getOrNull()
      } ?: error("无法解析 LAGOON_TLS_KEY 私钥")
      val password = CharArray(0)
      val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("server", key, password, chain.toTypedArray()) }
      return context(store, password)
    }

    private fun context(store: KeyStore, password: CharArray): SSLContext {
      val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }.keyManagers
      return SSLContext.getInstance("TLS").apply { init(managers, null, SecureRandom()) }
    }

    /** Issues certificates with the JDK's own `keytool`, so the image needs nothing else. */
    private fun selfSigned(dir: Path, hosts: List<String>): SSLContext {
      Files.createDirectories(dir)
      val passwordFile = dir.resolve("password")
      if (!Files.isRegularFile(passwordFile)) DataFiles.write(passwordFile, ByteArray(24).also(SecureRandom()::nextBytes).let { B64.url(it) })
      val password = Files.readString(passwordFile).trim()
      val ca = dir.resolve("ca.p12")
      val server = dir.resolve("server.p12")
      val hostsFile = dir.resolve("hosts")
      val names = (hosts + listOf("localhost", "127.0.0.1")).distinct()
      if (!Files.isRegularFile(ca)) {
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
          "-dname", "CN=Lagoon Hub Local CA", "-ext", "bc:c", "-ext", "ku:c=keyCertSign,cRLSign", "-validity", "3650",
          "-keystore", ca.toString(), "-storetype", "PKCS12", "-storepass", password)
        keytool("-exportcert", "-alias", "ca", "-rfc", "-keystore", ca.toString(), "-storepass", password, "-file", dir.resolve("ca.crt").toString())
        Files.deleteIfExists(server)
      }
      val current = if (Files.isRegularFile(hostsFile)) Files.readString(hostsFile).trim() else ""
      if (!Files.isRegularFile(server) || current != names.joinToString(",") || expiresSoon(server, password)) {
        Files.deleteIfExists(server)
        val request = dir.resolve("server.csr")
        val signed = dir.resolve("server.crt")
        keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
          "-dname", "CN=Lagoon Hub", "-validity", "825", "-keystore", server.toString(), "-storetype", "PKCS12", "-storepass", password)
        keytool("-certreq", "-alias", "server", "-keystore", server.toString(), "-storepass", password, "-file", request.toString())
        val san = names.joinToString(",") { if (it.matches(Regex("[0-9.]+")) || ':' in it) "ip:$it" else "dns:$it" }
        // Apple caps trusted server certificates at 825 days and requires serverAuth plus a SAN.
        keytool("-gencert", "-alias", "ca", "-keystore", ca.toString(), "-storepass", password, "-infile", request.toString(),
          "-outfile", signed.toString(), "-rfc", "-validity", "825", "-sigalg", "SHA256withECDSA",
          "-ext", "san=$san", "-ext", "eku=serverAuth", "-ext", "ku:c=digitalSignature,keyAgreement", "-ext", "bc=ca:false")
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", dir.resolve("ca.crt").toString(), "-keystore", server.toString(), "-storepass", password)
        keytool("-importcert", "-alias", "server", "-file", signed.toString(), "-keystore", server.toString(), "-storepass", password)
        Files.deleteIfExists(request)
        DataFiles.write(hostsFile, names.joinToString(","))
        Diagnostics.info("Tls", "已签发自签名证书：${names.joinToString(", ")}")
      }
      val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(server).use { load(it, password.toCharArray()) } }
      // The CA entry is only needed to build the chain on import; serve the server key alone.
      store.deleteEntry("ca")
      return context(store, password.toCharArray())
    }

    private fun expiresSoon(path: Path, password: String): Boolean = runCatching {
      val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, password.toCharArray()) } }
      val certificate = store.getCertificate("server") as X509Certificate
      certificate.notAfter.time - System.currentTimeMillis() < TimeUnit.DAYS.toMillis(30)
    }.getOrDefault(true)

    private fun keytool(vararg args: String) {
      val binary = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
      val process = ProcessBuilder(listOf(binary) + args).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().readText()
      if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) error("keytool 失败：${output.take(500)}")
    }
  }
}
