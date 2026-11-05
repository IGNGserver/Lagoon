package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files

/** Opt-in live check that the local forwarder actually carries bytes through direct-tcpip. */
class SshForwarderIT {
  @Test fun forwardsLocalSocketToRemoteLoopback() {
    val host = System.getenv("LAGOON_SSH_IT_HOST")
    val user = System.getenv("LAGOON_SSH_IT_USER")
    val keyPath = System.getenv("LAGOON_SSH_IT_KEY")
    assumeTrue(host != null && user != null && keyPath != null)
    val key = String(Files.readAllBytes(File(keyPath!!).toPath()))
    val config = SshHostConfig(id = "ssh-it-fwd", name = "it", host = host!!, user = user!!, auth = SshAuthMethod.PRIVATE_KEY)
    val factory = JschSshTransportFactory()
    val keyInfo = factory.probeHostKey(config)
    val transport = factory.connect(config, SshSecrets(privateKey = key)) { it.fingerprint == keyInfo.fingerprint }
    val forwarder = SshForwarder { input, output -> transport.openTunnel("127.0.0.1", 22, input, output) }
    try {
      Socket("127.0.0.1", forwarder.port).use { socket ->
        socket.soTimeout = 5_000
        val buffer = ByteArray(128)
        val read = socket.getInputStream().read(buffer)
        assertTrue("expected an SSH banner through the tunnel", read > 0)
        assertTrue(String(buffer, 0, read).startsWith("SSH-"))
      }
    } finally {
      forwarder.close()
      transport.close()
    }
  }
}
