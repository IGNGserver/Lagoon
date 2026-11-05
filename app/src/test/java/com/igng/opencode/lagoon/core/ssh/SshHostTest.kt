package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshHostCodec
import com.igng.opencode.lagoon.core.SshShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SshHostTest {
  @Test fun configRoundTripsWithoutSecrets() {
    val config = SshHostConfig(
      id = "ssh-1", name = "工作站", host = "192.168.1.10", port = 2222, user = "me",
      auth = com.igng.opencode.lagoon.core.SshAuthMethod.PRIVATE_KEY,
      launchCommand = "dsh web --foo", workingDirectory = "~/code",
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:abc"
    )
    val decoded = SshHostCodec.decode(SshHostCodec.encode(listOf(config)))
    assertEquals(listOf(config), decoded)
    assertTrue(config.profileUrl().startsWith("ssh://me@"))
    assertTrue(config.address.contains(":2222"))
  }

  @Test fun shellQuotingAndPaths() {
    assertEquals("'a'\\''b'", SshShell.quote("a'b"))
    assertEquals("\"\$HOME\"/'code lab'", SshShell.remotePathExpr("~/code lab"))
    assertEquals("/home/me/code lab", SshShell.absolutePath("~/code lab", "/home/me"))
    assertEquals("token=•••&a=b", SshShell.redactTokens("http://x/?token=secret&a=b").substringAfter("?"))
  }

  @Test fun bootstrapStartsReusesAndStopsManagedDsh() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-ssh-test").toFile()
    val home = File(root, "home").apply { mkdirs() }
    val bin = File(root, "bin").apply { mkdirs() }
    val dsh = File(bin, "dsh").apply {
      writeText(
        """
        #!/bin/sh
        echo "dsh web: http://127.0.0.1:45678/?token=test-token"
        sleep 60
        """.trimIndent()
      )
      setExecutable(true)
    }
    val transport = LocalShellTransport(home, "${bin.absolutePath}:/usr/bin:/bin")
    val bootstrap = RemoteDshBootstrap(transport, launchCommand = "dsh web", startTimeoutSeconds = 10)
    try {
      val first = bootstrap.start()
      assertEquals(45678, first.port)
      assertEquals("test-token", first.token)
      val second = bootstrap.ensureRunning()
      assertTrue("second start should reuse the running instance", second.reused)
      assertEquals(45678, second.port)
      assertTrue(File(home, ".dsh-app-remote/web.pid").isFile)
      assertTrue(File(home, ".dsh-app-remote/web.log").readText().contains("test-token"))
    } finally {
      bootstrap.stop()
      dsh.delete()
      root.deleteRecursively()
    }
  }

  @Test fun bootstrapReportsMissingDsh() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-ssh-test").toFile()
    val home = File(root, "home").apply { mkdirs() }
    val transport = LocalShellTransport(home, "/usr/bin:/bin")
    val bootstrap = RemoteDshBootstrap(transport, startTimeoutSeconds = 5)
    try {
      val failure = runCatching { bootstrap.start() }.exceptionOrNull() as? SshFailure
      assertEquals(SshFailureKind.DSH_NOT_FOUND, failure?.kind)
      assertTrue(failure?.detail.orEmpty().contains("npm i -g @deepseek-ai/dsh"))
    } finally {
      root.deleteRecursively()
    }
  }

  private class LocalShellTransport(private val home: File, private val path: String) : SshTransport {
    override val isClosed: Boolean = false

    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
      val process = ProcessBuilder("/bin/sh", "-c", script).apply {
        environment()["HOME"] = home.absolutePath
        environment()["PATH"] = path
        directory(home)
      }.start()
      val stdout = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
      val stderr = process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) }
      val finished = process.waitFor(timeoutMillis.coerceAtMost(20_000), TimeUnit.MILLISECONDS)
      if (!finished) process.destroyForcibly()
      return RemoteExecResult(if (finished) process.exitValue() else -1, stdout, stderr)
    }

    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection =
      error("not used")

    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean = true

    override fun ping(timeoutMillis: Long): Boolean = true
    override fun close() = Unit
  }
}
