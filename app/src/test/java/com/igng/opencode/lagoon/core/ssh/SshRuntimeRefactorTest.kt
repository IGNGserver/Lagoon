package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.HarnessConfig
import com.igng.opencode.lagoon.core.HarnessProtocol
import com.igng.opencode.lagoon.core.OpenCodeSshRegistration
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshDevice
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshLinkPhase
import com.igng.opencode.lagoon.core.SshLinkStatus
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRuntimeRefactorTest {
  @Test fun stdioRpcTransportEmitsLiveStatusTransitionsAndUsesDynamicLabel() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val statuses = CopyOnWriteArrayList<SshLinkStatus>()
    val config = SshHostConfig(
      id = "dev-1", name = "Dev", host = "dev.local", user = "alice",
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:pinned"
    )
    val transport = SshStdioRpcTransport(
      config = config,
      secrets = SshSecrets(password = "pw"),
      factory = FakeFactory(ssh, PresentedSshHostKey("ssh-ed25519", "SHA256:pinned")),
      command = "opencode acp",
      label = "OpenCode ACP",
      onStatusChanged = { statuses += it },
      onEvent = { _, _ -> }
    )
    try {
      assertEquals(SshLinkPhase.IDLE, transport.status.value.phase)
      val ready = async(Dispatchers.IO) {
        transport.ensureReady {
          val reply = transport.request({ id -> JSONObject().put("id", id).put("method", "initialize") })
          assertEquals(1, reply.getInt("protocolVersion"))
        }
      }
      val init = wire.next()
      wire.write(JSONObject().put("id", init.get("id")).put("result", JSONObject().put("protocolVersion", 1)))
      withTimeout(3_000) { ready.await() }

      assertEquals(SshLinkPhase.CONNECTED, transport.status.value.phase)
      assertTrue(transport.status.value.detail.contains("OpenCode ACP"))
      assertFalse("OpenCode ACP status must never hardcode AGY", transport.status.value.detail.contains("AGY"))
      assertTrue(statuses.any { it.phase == SshLinkPhase.CONNECTING })
      assertTrue(statuses.any { it.phase == SshLinkPhase.CONNECTED })
    } finally {
      transport.close()
    }
  }

  @Test fun stdioRpcTransportSurfacesHostKeyChangedAsTypedFailedStatus() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val config = SshHostConfig(
      id = "dev-1", name = "Dev", host = "dev.local", user = "alice",
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:expected"
    )
    val presented = PresentedSshHostKey("ssh-ed25519", "SHA256:changed")
    val transport = SshStdioRpcTransport(
      config = config,
      secrets = SshSecrets(password = "pw"),
      factory = FakeFactory(ssh, presented),
      command = "opencode acp",
      label = "OpenCode ACP",
      onEvent = { _, _ -> }
    )
    try {
      val failure = runCatching { transport.ensureReady {} }.exceptionOrNull() as SshFailure
      assertEquals(SshFailureKind.HOST_KEY_CHANGED, failure.kind)
      assertEquals(presented, failure.presentedKey)
      assertEquals(SshLinkPhase.FAILED, transport.status.value.phase)
      assertEquals(SshFailureKind.HOST_KEY_CHANGED, transport.status.value.error?.kind)
      assertEquals(presented, transport.status.value.error?.presentedKey)
      assertFalse(transport.status.value.detail.contains("AGY"))
    } finally {
      transport.close()
    }
  }

  @Test fun transportKeyDecouplesDshLaunchSettingsFromUnderlyingSshSession() {
    val base = SshHostConfig(
      id = "dev-1", name = "Dev", host = "dev.local", port = 22, user = "alice",
      launchCommand = "dsh web", workingDirectory = "/repo-a", keyId = "key-1"
    )
    val editedDsh = base.copyWith(launchCommand = "dsh web --port 9999", workingDirectory = "/repo-b")
    assertEquals("DSH launch tweaks must keep the pooled SSH transportKey identical", base.transportKey, editedDsh.transportKey)
    assertNotEquals(base.connectionKey, editedDsh.connectionKey)

    val device = SshDevice("dev-1", "Dev", "dev.local", 22, "alice", keyId = "key-1")
    assertEquals(device.transportKey, base.transportKey)
    assertNotEquals(device.transportKey, device.copyEndpoint(port = 2222).transportKey)
    assertNotEquals(device.transportKey, device.copyEndpoint(keyId = "key-2").transportKey)
  }

  @Test fun kickAndForceReconnectWakeSleepingBackoffImmediately() = runBlocking {
    val attempts = AtomicInteger()
    val factory = object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
        attempts.incrementAndGet()
        throw SshFailure(SshFailureKind.UNREACHABLE, "offline", retryable = true)
      }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
    }
    val config = SshHostConfig(
      id = "dev-1", name = "Dev", host = "dev.local", user = "alice",
      hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:pinned"
    )
    val profile = ServerProfile("h-dsh", "DSH", config.profileUrl(), username = "alice",
      protocol = HarnessProtocol.DSH, deviceId = config.id, config = HarnessConfig.Dsh())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val link = SshHostLink(scope, profile, config, SshSecrets(password = "pw"), factory)
    try {
      link.start()
      withTimeout(3_000) { link.status.first { it.phase == SshLinkPhase.RECONNECTING && attempts.get() >= 1 } }
      val afterFirst = attempts.get()
      // Backoff after first failure is 2000ms+; kick() must wake immediately.
      val started = System.currentTimeMillis()
      link.kick()
      withTimeout(1_200) { while (attempts.get() <= afterFirst) delay(15) }
      val elapsedKick = System.currentTimeMillis() - started
      assertTrue("kick() should wake backoff quickly, took ${elapsedKick}ms", elapsedKick < 1_200)

      val afterKick = attempts.get()
      link.forceReconnect("manual")
      withTimeout(1_200) { while (attempts.get() <= afterKick) delay(15) }
      assertTrue(attempts.get() > afterKick)
    } finally {
      link.close()
      scope.cancel()
    }
  }

  @Test fun verifiedTunnelPreservesIpv6LoopbackAndNormalizesLocalhost() {
    val ipv6Target = SshVerifiedTunnel.Target(
      host = "::1",
      port = 4096,
      pid = 1234,
      registrationPath = "/home/dev/.opencode/service.json",
      registrationVersion = "2.5.0",
      registrationId = "gen-1"
    )
    assertEquals("::1", ipv6Target.host)

    val openedHosts = mutableListOf<String>()
    val transport = object : SshTransport {
      override val isClosed = false
      override fun exec(script: String, timeoutMillis: Long): RemoteExecResult = error("unused")
      override fun openExecChannel(script: String): SshExecChannel = error("unused")
      override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection {
        openedHosts += host
        return object : TunnelConnection {
          override val isOpen = false
          override fun onClosed(listener: () -> Unit) = listener()
          override fun close() = Unit
        }
      }
      override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = true
      override fun ping(timeoutMillis: Long) = true
      override fun close() = Unit
    }

    SshVerifiedTunnel.openDirect(transport, ipv6Target, ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
    val localhostTarget = ipv6Target.copy(host = "localhost")
    SshVerifiedTunnel.openDirect(transport, localhostTarget, ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
    assertEquals(listOf("::1", "127.0.0.1"), openedHosts)
  }

  private class FakeFactory(
    private val ssh: FakeSsh,
    private val key: PresentedSshHostKey
  ) : SshTransportFactory {
    override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
      check(verifier.verify(key))
      return ssh
    }
    override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = key
  }

  private class FakeSsh(private val wire: FakeChannel) : SshTransport {
    override var isClosed = false
    override fun openExecChannel(script: String): SshExecChannel = wire
    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult = error("unused")
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection = error("unused")
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = false
    override fun ping(timeoutMillis: Long) = !isClosed
    override fun close() { isClosed = true }
  }

  private class FakeChannel : SshExecChannel {
    private val outgoingInput = PipedInputStream(64 * 1024)
    override val stdin: OutputStream = PipedOutputStream(outgoingInput)
    private val source = PipedOutputStream()
    override val stdout: InputStream = PipedInputStream(source, 64 * 1024)
    private val errors = PipedOutputStream()
    override val stderr: InputStream = PipedInputStream(errors, 1024)
    private val closed = AtomicBoolean(false)
    override val isOpen get() = !closed.get()
    private val lines = LinkedBlockingQueue<String>()

    init {
      Thread { runCatching { outgoingInput.bufferedReader().forEachLine { lines.put(it) } } }
        .apply { isDaemon = true; start() }
    }

    fun next() = JSONObject(lines.poll(3, TimeUnit.SECONDS) ?: error("timed out waiting for frame"))
    fun write(value: JSONObject) {
      source.write((value.toString() + "\n").toByteArray())
      source.flush()
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        runCatching { stdin.close() }; runCatching { outgoingInput.close() }
        runCatching { source.close() }; runCatching { stdout.close() }
        runCatching { errors.close() }; runCatching { stderr.close() }
      }
    }
  }
}
