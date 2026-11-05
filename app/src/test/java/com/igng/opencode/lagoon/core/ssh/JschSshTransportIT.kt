package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.SshAuthMethod
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Opt-in live check of the real JSch transport. Run with:
 *
 *   LAGOON_SSH_IT_HOST=192.168.5.28 LAGOON_SSH_IT_USER=lvziw \
 *   LAGOON_SSH_IT_KEY=$HOME/.ssh/id_ed25519 ./gradlew :app:testDebugUnitTest --tests '*JschSshTransportIT'
 *
 * It is skipped in the normal suite so a developer without that host still gets a green build.
 */
class JschSshTransportIT {
  @Test fun connectsExecutesAndForwards() {
    val host = System.getenv("LAGOON_SSH_IT_HOST")
    val user = System.getenv("LAGOON_SSH_IT_USER")
    val keyPath = System.getenv("LAGOON_SSH_IT_KEY")
    assumeTrue(host != null && user != null && keyPath != null)
    val key = String(Files.readAllBytes(File(keyPath!!).toPath()))
    val config = SshHostConfig(
      id = "ssh-it", name = "it", host = host!!, user = user!!, auth = SshAuthMethod.PRIVATE_KEY
    )
    val factory = JschSshTransportFactory()
    val presented = factory.probeHostKey(config)
    assertTrue(presented.fingerprint.startsWith("SHA256:"))
    val transport = factory.connect(config, SshSecrets(privateKey = key)) { it.fingerprint == presented.fingerprint }
    try {
      val result = transport.exec("printf 'lagoon-ssh-ok\\n'", 15_000)
      assertTrue(result.stdout.contains("lagoon-ssh-ok"))
      val output = ByteArrayOutputStream()
      val inputWriter = java.io.PipedOutputStream()
      val input = java.io.PipedInputStream(inputWriter)
      val channel = transport.openTunnel("127.0.0.1", 22, input, output)
      Thread.sleep(500)
      channel.close()
      inputWriter.close()
      assertTrue(output.size() > 0)
    } finally {
      transport.close()
    }
  }
}
