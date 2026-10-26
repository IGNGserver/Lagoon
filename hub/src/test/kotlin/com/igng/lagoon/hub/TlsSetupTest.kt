package com.igng.lagoon.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class TlsSetupTest {
  private fun config(dir: java.nio.file.Path, hosts: String) = HubConfig.fromEnv(mapOf("LAGOON_DATA_DIR" to dir.toString(),
    "LAGOON_TLS" to "self-signed", "LAGOON_TLS_HOSTS" to hosts))

  @Test fun selfSignedModeCreatesOneAuthorityAndReissuesForNewHosts() {
    val dir = Files.createTempDirectory("hub-tls")
    val first = TlsSetup.create(config(dir, "192.168.1.10,hub.lan"))!!
    val authority = first.authorityCertificate()!!
    val ca = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(authority)) as X509Certificate
    // A CA the user can install and trust.
    assertTrue(ca.basicConstraints >= 0)
    assertNotNull(first.context.socketFactory)
    val hostsFile = dir.resolve("tls/hosts")
    assertTrue(Files.readString(hostsFile).contains("192.168.1.10"))

    val second = TlsSetup.create(config(dir, "192.168.1.11"))!!
    // The authority survives; only the server certificate follows the host list.
    assertArrayEquals(authority, second.authorityCertificate())
    assertTrue(Files.readString(hostsFile).contains("192.168.1.11"))
  }

  @Test fun noTlsWithoutConfiguration() {
    assertNull(TlsSetup.create(HubConfig.fromEnv(mapOf("LAGOON_DATA_DIR" to Files.createTempDirectory("hub-plain").toString()))))
  }
}
