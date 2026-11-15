package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SshDeviceDiscoveryTest {
  private val key = PresentedSshHostKey("ssh-ed25519", "SHA256:fixture")
  private val config = SshHostConfig("host", "Host", "localhost", user = "me",
    hostKeyType = key.type, hostKeyFingerprint = key.fingerprint)

  @Test fun allProbesShareOneConnectionAndKeepIndividualFailures() = runBlocking {
    val factory = FakeFactory("Linux", codexFailure = true)
    val store = SshHostStore(FakeSharedPreferences(), FakeSharedPreferences(), { it }, { it })
    val manager = SshHostManager(store, factory)
    try {
      val result = manager.discoverDevice(config, SshSecrets(password = "fixture"))
      assertEquals(1, factory.connects)
      assertEquals(true, result.dsh.installed)
      assertNull(result.codex.installed)
      assertEquals("probe_failed", result.codex.issue)
      assertEquals(false, result.agy.installed)
      assertEquals(DiscoveryCompleteness.PARTIAL, result.completeness)
      assertEquals(6, factory.commands)
      assertTrue(factory.closed)
    } finally { manager.closeAll() }
  }

  @Test fun unsupportedSystemDoesNotInventMissingHarnesses() = runBlocking {
    val factory = FakeFactory("Windows_NT")
    val store = SshHostStore(FakeSharedPreferences(), FakeSharedPreferences(), { it }, { it })
    val manager = SshHostManager(store, factory)
    try {
      val result = manager.discoverDevice(config, SshSecrets(password = "fixture"))
      assertEquals("unsupported_system", result.environmentIssue)
      assertNull(result.dsh.installed)
      assertNull(result.codex.installed)
      assertNull(result.agy.installed)
      assertEquals(1, factory.commands)
    } finally { manager.closeAll() }
  }

  private inner class FakeFactory(private val os: String, private val codexFailure: Boolean = false) : SshTransportFactory {
    var connects = 0
    var commands = 0
    var closed = false
    override fun probeHostKey(config: SshHostConfig) = key
    override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
      connects++
      check(verifier.verify(key))
      return object : SshTransport {
        override val isClosed get() = closed
        override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
          commands++
          return when {
            script.contains("LAGOON_OS") -> RemoteExecResult(0, "LAGOON_OS $os\n", "")
            script.contains("LAGOON_SSH_DISCOVERY") -> RemoteExecResult(0,
              """LAGOON_SSH_DISCOVERY {"python":true,"opencode":{"state":"absent","issue":"not_found","path":"/home/me/.local/state/opencode/service.json"},"dshLegacy":{"state":"absent","issue":"not_found","path":"/home/me/.dsh-app-remote"}}""", "")
            script.contains("LAGOON_DSH_DISCOVERY") -> RemoteExecResult(0, "LAGOON_DSH_DISCOVERY present\n", "")
            script.contains("LAGOON_CODEX_DISCOVERY") -> if (codexFailure) RemoteExecResult(1, "", "")
              else RemoteExecResult(0, "LAGOON_CODEX_DISCOVERY version:codex 1\n", "")
            script.contains("LAGOON_AGY_DISCOVERY") -> RemoteExecResult(0, "LAGOON_AGY_DISCOVERY absent\n", "")
            else -> error("Unexpected command")
          }
        }
        override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection = error("unused")
        override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = false
        override fun ping(timeoutMillis: Long) = true
        override fun close() { closed = true }
      }
    }
  }
}
