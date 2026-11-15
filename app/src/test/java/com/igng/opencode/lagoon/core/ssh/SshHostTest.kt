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

  @Test fun sshCommandDoesNotMistakeIdentityFileForDestination() {
    val parsed = SshAddress.parse("ssh -i ~/.ssh/id_ed25519 -p 2222 me@example.test")
    assertEquals("example.test", parsed.host)
    assertEquals("me", parsed.user)
    assertEquals(2222, parsed.port)
    assertTrue(parsed.error.orEmpty().contains("私钥"))
    assertTrue(SshAddress.parse("ssh -o ProxyJump=other me@example.test").error != null)
    assertTrue(SshAddress.parse("example.test:99999").error != null)
  }

  @Test fun executableSearchHandlesHomeDirectoriesWithSpaces() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon path fixture").toFile()
    try {
      val binary = File(root, ".local/bin/codex").apply {
        parentFile!!.mkdirs(); writeText("#!/bin/sh\nexit 0\n"); setExecutable(true)
      }
      val result = LocalShellTransport(root, "/usr/bin:/bin").exec(
        SshExecutableSearch.prologue + "\ncommand -v codex", 5_000)
      assertEquals(0, result.exitCode)
      assertEquals(binary.absolutePath, result.stdout.trim())
    } finally { root.deleteRecursively() }
  }

  @Test fun bootstrapStartsAndReusesManagedDsh() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-ssh-test").toFile()
    val home = File(root, "home").apply { mkdirs() }
    val bin = File(root, "bin").apply { mkdirs() }
    val dsh = File(bin, "dsh").apply {
      writeText(
        """
        #!/bin/sh
        echo "dsh web: http://127.0.0.1:45678/?token=test-token"
        exec sleep 60
        """.trimIndent()
      )
      setExecutable(true)
    }
    val transport = LocalShellTransport(home, "${bin.absolutePath}:/usr/bin:/bin")
    val bootstrap = RemoteDshBootstrap(transport, launchCommand = "dsh web", startTimeoutSeconds = 10)
    var launched: FixturePid? = null
    try {
      val first = bootstrap.start()
      launched = first.pid?.let(::captureFixturePid)
      assertEquals(45678, first.port)
      assertEquals("test-token", first.token)
      val second = bootstrap.ensureRunning()
      assertTrue("second start should reuse the running instance", second.reused)
      assertEquals(45678, second.port)
      assertTrue(File(home, ".lagoon-ssh-dsh/web.pid").isFile)
      assertTrue(File(home, ".lagoon-ssh-dsh/web.log").readText().contains("test-token"))
    } finally {
      launched?.let(::stopFixturePid)
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
      assertTrue("dsh must not have been started", !File(home, ".lagoon-ssh-dsh/web.pid").exists())
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
    assertTrue(script.contains("LAUNCH=${SshShell.quote("dsh web --foo 'a b' --host 127.0.0.1 --port 0 --no-open")}"))
    assertTrue(script.contains("eval \"\$1\" >>\"\$D/web.log\" 2>&1 & echo \$! >\"\$D/web.pid\"; wait"))
    assertTrue(script.contains("cd '/nope' || { printf 'LAGOON nocwd"))
    // 只匹配当前用户的进程：共享主机上别人的 dsh web 不该让本设备连接失败。
    assertTrue(script.contains("pgrep -u \"\$(id -u)\""))
    assertTrue("bootstrap must not signal an unknown parent", !script.contains("kill \"\$PARENT\""))
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

  @Test fun bootstrapRefusesToReuseLiveProcessWithoutStartTime() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-ssh-empty-st").toFile()
    val home = File(root, "home").apply { mkdirs() }
    val bin = File(root, "bin").apply { mkdirs() }
    val dsh = File(bin, "dsh").apply {
      writeText(
        """
        #!/bin/sh
        echo "dsh web: http://127.0.0.1:45679/?token=test-token-2"
        exec sleep 60
        """.trimIndent()
      )
      setExecutable(true)
    }
    val transport = LocalShellTransport(home, "${bin.absolutePath}:/usr/bin:/bin")
    val bootstrap = RemoteDshBootstrap(transport, launchCommand = "dsh web", startTimeoutSeconds = 10)
    var launched: FixturePid? = null
    try {
      val first = bootstrap.start()
      launched = first.pid?.let(::captureFixturePid)
      assertEquals(45679, first.port)
      // A live PID without its recorded generation is not safe to reuse or signal.
      File(home, ".lagoon-ssh-dsh/web.st").writeText("")
      val failure = runCatching { bootstrap.ensureRunning() }.exceptionOrNull() as? SshFailure
      assertEquals(SshFailureKind.DSH_START, failure?.kind)
      assertTrue("an unverifiable process must remain untouched", launched?.let(::fixturePidAlive) == true)
    } finally {
      launched?.let(::stopFixturePid)
      dsh.delete()
      root.deleteRecursively()
    }
  }

  @Test fun legacyProcessWithMismatchedGenerationRemainsUntouched() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-dsh-legacy").toFile()
    val fixtureHome = File(root, "home").apply { mkdirs() }
    val legacy = File(fixtureHome, ".dsh-app-remote").apply { mkdirs() }
    val (unrelated, pid) = startSleepFixture()
    val originals = mapOf(
      "web.pid" to "$pid\n", "web.st" to "different-start-time\n", "web.cmd" to "dsh web",
      "web.log" to "dsh web: http://127.0.0.1:45680/?token=fixture-token\n"
    )
    originals.forEach { (name, text) -> File(legacy, name).writeText(text) }
    try {
      val bootstrap = RemoteDshBootstrap(LocalShellTransport(fixtureHome, "/usr/bin:/bin"), startTimeoutSeconds = 5)
      val failure = runCatching { bootstrap.ensureRunning() }.exceptionOrNull() as? SshFailure
      assertEquals(SshFailureKind.DSH_START, failure?.kind)
      assertTrue("a process with mismatched generation must remain alive", unrelated.isAlive)
      originals.forEach { (name, text) -> assertEquals("legacy $name must remain unchanged", text, File(legacy, name).readText()) }
    } finally {
      unrelated.destroyForcibly(); unrelated.waitFor(5, TimeUnit.SECONDS)
      root.deleteRecursively()
    }
  }

  @Test fun liveLegacyProcessWithDifferentCommandIsNeverStopped() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-dsh-legacy-cmd").toFile()
    val fixtureHome = File(root, "home").apply { mkdirs() }
    val legacy = File(fixtureHome, ".dsh-app-remote").apply { mkdirs() }
    val (unrelated, pid) = startSleepFixture()
    File(legacy, "web.pid").writeText("$pid\n")
    File(legacy, "web.st").writeText(processStartStamp(pid) + "\n")
    File(legacy, "web.cmd").writeText("some-other-command")
    File(legacy, "web.log").writeText("dsh web: http://127.0.0.1:45681/?token=fixture-token\n")
    try {
      val bootstrap = RemoteDshBootstrap(LocalShellTransport(fixtureHome, "/usr/bin:/bin"), startTimeoutSeconds = 5)
      val failure = runCatching { bootstrap.ensureRunning() }.exceptionOrNull() as? SshFailure
      assertEquals(SshFailureKind.DSH_START, failure?.kind)
      assertTrue("a shared process with different command must remain alive", unrelated.isAlive)
      assertEquals("some-other-command", File(legacy, "web.cmd").readText())
      assertEquals("$pid\n", File(legacy, "web.pid").readText())
    } finally {
      unrelated.destroyForcibly(); unrelated.waitFor(5, TimeUnit.SECONDS)
      root.deleteRecursively()
    }
  }

  @Test fun validatedLegacyRecordIsReadOnlyAndRetainsItsDirectory() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-dsh-legacy-reuse").toFile()
    val fixtureHome = File(root, "home").apply { mkdirs() }
    val legacy = File(fixtureHome, ".dsh-app-remote").apply { mkdirs() }
    val (fixture, pid) = startSleepFixture()
    val originals = mapOf(
      "web.pid" to "$pid\n", "web.st" to processStartStamp(pid) + "\n", "web.cmd" to "dsh web",
      "web.log" to "dsh web: http://127.0.0.1:45682/?token=fixture-token\n"
    )
    originals.forEach { (name, text) -> File(legacy, name).writeText(text) }
    try {
      val bootstrap = RemoteDshBootstrap(LocalShellTransport(fixtureHome, "/usr/bin:/bin"), startTimeoutSeconds = 5)
      val endpoint = bootstrap.ensureRunning()
      assertTrue(endpoint.reused)
      assertEquals(pid.toInt(), endpoint.pid)
      assertEquals(".dsh-app-remote", endpoint.ownershipStateDirectory)
      originals.forEach { (name, text) -> assertEquals(text, File(legacy, name).readText()) }
      assertTrue(fixture.isAlive)
    } finally {
      fixture.destroyForcibly(); fixture.waitFor(5, TimeUnit.SECONDS)
      root.deleteRecursively()
    }
  }

  @Test fun customLaunchCommandDoesNotRequireAGlobalDshExecutable() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-dsh-custom").toFile()
    val fixtureHome = File(root, "home").apply { mkdirs() }
    val bin = File(root, "bin").apply { mkdirs() }
    File(bin, "pgrep").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
    val wrapper = File(bin, "my-wrapper").apply {
      writeText("#!/bin/sh\necho 'dsh web: http://127.0.0.1:45683/?token=fixture-token'\nexec sleep 60\n")
      setExecutable(true)
    }
    var launched: FixturePid? = null
    try {
      val bootstrap = RemoteDshBootstrap(LocalShellTransport(fixtureHome, "${bin.absolutePath}:/usr/bin:/bin"),
        launchCommand = wrapper.absolutePath, startTimeoutSeconds = 5)
      val endpoint = bootstrap.ensureRunning()
      launched = endpoint.pid?.let(::captureFixturePid)
      assertEquals(45683, endpoint.port)
      assertEquals(".lagoon-ssh-dsh", endpoint.ownershipStateDirectory)
    } finally {
      launched?.let(::stopFixturePid)
      root.deleteRecursively()
    }
  }

  @Test fun concurrentLagoonClientsStartOnlyOneManagedProcess() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-dsh-race").toFile()
    val fixtureHome = File(root, "home").apply { mkdirs() }
    val bin = File(root, "bin").apply { mkdirs() }
    File(bin, "pgrep").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
    // Model mkdir implementations that report success for both racing creators. A correct lock
    // must still admit only one client; the delayed fixture keeps the attempts concurrent.
    File(bin, "mkdir").apply {
      writeText("#!/bin/sh\ncase \"\$*\" in *'.bootstrap.lock'*) exec /bin/mkdir -p \"\$@\";; *) exec /bin/mkdir \"\$@\";; esac\n")
      setExecutable(true)
    }
    File(bin, "dsh").apply {
      writeText("#!/bin/sh\nprintf 'launch\\n' >> \"\$HOME/launches\"\nprintf '%s\\n' \"\$\$\" >> \"\$HOME/fixture-pids\"\nsleep 1\necho 'dsh web: http://127.0.0.1:45684/?token=fixture-token'\nexec sleep 60\n")
      setExecutable(true)
    }
    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    val gate = java.util.concurrent.CountDownLatch(1)
    var launched: FixturePid? = null
    try {
      fun submit() = executor.submit<com.igng.opencode.lagoon.core.RemoteDshEndpoint> {
        gate.await()
        RemoteDshBootstrap(LocalShellTransport(fixtureHome, "${bin.absolutePath}:/usr/bin:/bin"),
          startTimeoutSeconds = 5).ensureRunning()
      }
      val first = submit(); val second = submit(); gate.countDown()
      val a = first.get(30, TimeUnit.SECONDS); val b = second.get(30, TimeUnit.SECONDS)
      launched = a.pid?.let(::captureFixturePid)
      assertEquals(a.pid, b.pid)
      assertTrue("exactly one client must reuse the other's process", a.reused != b.reused)
      assertEquals(listOf("launch"), File(fixtureHome, "launches").readLines())
    } finally {
      launched?.let(::stopFixturePid)
      File(fixtureHome, "fixture-pids").takeIf(File::isFile)?.readLines().orEmpty()
        .mapNotNull(String::toIntOrNull).forEach { pid -> runCatching { stopFixturePid(captureFixturePid(pid)) } }
      executor.shutdownNow()
      root.deleteRecursively()
    }
  }

  @Test fun interruptedBootstrapReleasesLockWithoutStoppingService() {
    assumeTrue(File("/bin/sh").exists())
    val root = Files.createTempDirectory("lagoon-dsh-interrupted").toFile()
    val fixtureHome = File(root, "home").apply { mkdirs() }
    val bin = File(root, "bin").apply { mkdirs() }
    File(bin, "pgrep").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
    File(bin, "dsh").apply {
      writeText("#!/bin/sh\nsleep 3\necho 'dsh web: http://127.0.0.1:45685/?token=fixture-token'\nexec sleep 60\n")
      setExecutable(true)
    }
    val path = "${bin.absolutePath}:/usr/bin:/bin"
    val bootstrap = RemoteDshBootstrap(LocalShellTransport(fixtureHome, path), startTimeoutSeconds = 8)
    val state = File(fixtureHome, ".lagoon-ssh-dsh")
    val process = ProcessBuilder("/bin/sh", "-c", bootstrap.startScript()).apply {
      environment()["HOME"] = fixtureHome.absolutePath
      environment()["PATH"] = path
      redirectErrorStream(true)
    }.start()
    var launched: FixturePid? = null
    try {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while ((!File(state, "web.st").isFile || !File(state, "web.st").readText().isNotBlank()) && System.nanoTime() < deadline) {
        assertTrue("bootstrap exited before recording the process", process.isAlive)
        Thread.sleep(50)
      }
      assertTrue("bootstrap did not record process identity", File(state, "web.st").isFile)
      val pid = File(state, "web.pid").readText().trim().toInt()
      launched = captureFixturePid(pid)
      process.destroyForcibly()
      assertTrue(process.waitFor(5, TimeUnit.SECONDS))
      while (!File(state, "web.log").readText().contains("token=") && System.nanoTime() < deadline) Thread.sleep(50)
      val resumed = bootstrap.ensureRunning()
      assertTrue(resumed.reused)
      assertEquals(pid, resumed.pid)
      assertTrue(fixturePidAlive(launched))
    } finally {
      if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
      launched?.let(::stopFixturePid)
      root.deleteRecursively()
    }
  }

  private data class FixturePid(val pid: Int, val start: String)
  private fun captureFixturePid(pid: Int) = FixturePid(pid, processStartStamp(pid.toLong()))
  private fun fixturePidAlive(target: FixturePid) =
    runCatching { processStartStamp(target.pid.toLong()) == target.start }.getOrDefault(false)
  private fun stopFixturePid(target: FixturePid) {
    // Only signal the exact generation launched by this test, never a reused PID.
    if (fixturePidAlive(target)) ProcessBuilder("/bin/kill", "-KILL", target.pid.toString()).start().waitFor(5, TimeUnit.SECONDS)
  }
  private fun startSleepFixture(): Pair<Process, Long> {
    val process = ProcessBuilder("/bin/sh", "-c", "echo \$\$; exec sleep 60").start()
    val pid = process.inputStream.bufferedReader().readLine().trim().toLong()
    return process to pid
  }

  private fun processStartStamp(pid: Long): String {
    val proc = File("/proc/$pid/stat")
    if (proc.isFile) return proc.readText().substringAfterLast(") ").trim().split(Regex("\\s+"))[19]
    val command = ProcessBuilder("/bin/ps", "-p", pid.toString(), "-o", "lstart=").apply {
      environment()["LC_ALL"] = "C"
    }.start()
    val stamp = command.inputStream.bufferedReader().use { it.readText() }.trimEnd()
    assertTrue(command.waitFor(5, TimeUnit.SECONDS) && command.exitValue() == 0 && stamp.isNotBlank())
    return stamp
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
    // Never let this isolated fixture discover or inspect a real user's dsh web process.
    private val fixtureBin = File(home, ".fixture-bin").apply {
      mkdirs()
      File(this, "pgrep").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
    }

    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
      val process = ProcessBuilder("/bin/sh", "-c", script).apply {
        environment()["HOME"] = home.absolutePath
        environment()["PATH"] = "${fixtureBin.absolutePath}:$path"
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
