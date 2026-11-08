package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshAddress
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

  @Test fun addressFieldSplitsUserPortAndKeepsOddInput() {
    // The first screen only asks for an address, so pasted ssh forms must land in the right fields.
    val userAtHost = SshAddress.parse("lvziw@192.168.1.20")
    assertEquals("192.168.1.20", userAtHost.host)
    assertEquals("lvziw", userAtHost.user)
    assertEquals(null, userAtHost.port)

    val hostPort = SshAddress.parse("nas.local:2222")
    assertEquals("nas.local", hostPort.host)
    assertEquals(null, hostPort.user)
    assertEquals(2222, hostPort.port)

    val everything = SshAddress.parse("  me@nas.local:2200  ")
    assertEquals("nas.local", everything.host)
    assertEquals("me", everything.user)
    assertEquals(2200, everything.port)

    val command = SshAddress.parse("ssh -p 2022 me@[fe80::1]")
    assertEquals("fe80::1", command.host)
    assertEquals("me", command.user)
    assertEquals(2022, command.port)

    val bareIpv6 = SshAddress.parse("fe80::1")
    assertEquals("fe80::1", bareIpv6.host)
    assertEquals(null, bareIpv6.port)

    // Out-of-range ports and half-typed addresses stay verbatim instead of silently losing the host.
    assertEquals("host:99999", SshAddress.parse("host:99999").host)
    assertEquals(null, SshAddress.parse("host:99999").port)
    assertEquals("me@", SshAddress.parse("me@").host)
    assertEquals("", SshAddress.parse("   ").host)
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

  @Test fun missingWorkingDirectoryAbortsBeforeLaunch() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-ssh-nocwd").toFile()
    val home = File(root, "home").apply { mkdirs() }
    val bin = File(root, "bin").apply { mkdirs() }
    val dsh = File(bin, "dsh").apply {
      writeText("#!/bin/sh\necho should-not-start\n")
      setExecutable(true)
    }
    val transport = LocalShellTransport(home, "${bin.absolutePath}:/usr/bin:/bin")
    val bootstrap = RemoteDshBootstrap(transport, workingDirectory = "/no/such/dir", startTimeoutSeconds = 5)
    try {
      val failure = runCatching { bootstrap.start() }.exceptionOrNull() as? SshFailure
      assertEquals(SshFailureKind.DSH_START, failure?.kind)
      assertTrue(failure?.message.orEmpty().contains("/no/such/dir"))
      assertTrue("dsh must not have been started", !File(home, ".dsh-app-remote/web.pid").exists())
    } finally {
      dsh.delete()
      root.deleteRecursively()
    }
  }

  @Test fun quotedLaunchCommandKeepsItsOwnArguments() {
    val bootstrap = RemoteDshBootstrap(
      transport = object : SshTransport {
        override val isClosed = false
        override fun exec(script: String, timeoutMillis: Long) = error("unused")
        override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream) = error("unused")
        override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = false
        override fun ping(timeoutMillis: Long) = false
        override fun close() = Unit
      },
      launchCommand = "dsh web --foo 'a b'",
      workingDirectory = "/nope"
    )
    val script = bootstrap.startScript()
    val probe = bootstrap.probeScript()
    assertTrue(script.contains("LAUNCH=${SshShell.quote("dsh web --foo 'a b' --host 127.0.0.1 --port 0 --no-open")}"))
    assertTrue(script.contains("eval \"\$1\" >>\"\$D/web.log\" 2>&1 & echo \$! >\"\$D/web.pid\"; wait"))
    assertTrue(script.contains("cd '/nope' || { printf 'LAGOON nocwd"))
    // 只匹配当前用户的进程：共享主机上别人的 dsh web 不该让本设备连接失败。
    assertTrue(probe.contains("pgrep -u \"\$(id -u)\" -f '[d]sh web'"))
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

  @Test fun bootstrapReusesAndStopsProcessEvenWhenStartTimeFileIsEmpty() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-ssh-empty-st").toFile()
    val home = File(root, "home").apply { mkdirs() }
    val bin = File(root, "bin").apply { mkdirs() }
    val dsh = File(bin, "dsh").apply {
      writeText(
        """
        #!/bin/sh
        echo "dsh web: http://127.0.0.1:45679/?token=test-token-2"
        sleep 60
        """.trimIndent()
      )
      setExecutable(true)
    }
    val transport = LocalShellTransport(home, "${bin.absolutePath}:/usr/bin:/bin")
    val bootstrap = RemoteDshBootstrap(transport, launchCommand = "dsh web", startTimeoutSeconds = 10)
    try {
      val first = bootstrap.start()
      assertEquals(45679, first.port)
      // 模拟旧版或非 /proc 环境留下 0 字节 web.st：仍应回退到命令行参数校验并正常复用/停止，不泄漏孤儿进程
      File(home, ".dsh-app-remote/web.st").writeText("")
      val second = bootstrap.ensureRunning()
      assertTrue("empty web.st must fall back to ps args check and reuse process", second.reused)
      assertEquals(first.pid, second.pid)
    } finally {
      bootstrap.stop()
      dsh.delete()
      root.deleteRecursively()
    }
  }

  @Test fun sshHostManagerRefCountsSharedLinkAcrossMultipleClientHandles() {
    val prefs = com.igng.opencode.lagoon.core.FakeSharedPreferences()
    val secretPrefs = com.igng.opencode.lagoon.core.FakeSharedPreferences()
    val store = com.igng.opencode.lagoon.core.SshHostStore(prefs, secretPrefs, { it }, { it })
    val config = SshHostConfig(
      id = "ssh-ref", name = "DevBox", host = "127.0.0.1", port = 22, user = "dev",
      auth = com.igng.opencode.lagoon.core.SshAuthMethod.PASSWORD,
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:test"
    )
    store.save(config, com.igng.opencode.lagoon.core.SshSecrets(password = "pw"))
    val profile = com.igng.opencode.lagoon.core.ServerProfile(
      id = "p-ssh", name = "DevBox", url = config.profileUrl(), username = "dev",
      kind = com.igng.opencode.lagoon.core.ServerKind.DSH, sshHostId = config.id
    )
    val manager = SshHostManager(store)
    try {
      val monitorClient = manager.client(profile)
      val tempClient = manager.client(profile)
      // 关闭临时客户端不得销毁监控仍持有的共享 SshHostLink
      tempClient.close()
      assertTrue(tempClient.isClosed)
      org.junit.Assert.assertFalse("监控持有的客户端不应被临时客户端连带关闭", monitorClient.isClosed)
      org.junit.Assert.assertNotNull(manager.status(config.id))
      // 最后一个持有者关闭后才真正回收底层连接
      monitorClient.close()
      assertTrue(monitorClient.isClosed)
      org.junit.Assert.assertNull(manager.status(config.id))
    } finally {
      manager.closeAll()
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
