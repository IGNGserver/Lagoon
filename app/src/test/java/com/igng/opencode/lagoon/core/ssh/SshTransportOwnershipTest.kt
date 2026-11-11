package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class SshTransportOwnershipTest {
  private class Transport : SshTransport {
    var closes = 0
    var healthy = true
    override val isClosed get() = closes > 0
    override fun close() { closes++ }
    override fun exec(script: String, timeoutMillis: Long) = RemoteExecResult(0, "ok", "")
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection = error("unused")
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = !isClosed
    override fun ping(timeoutMillis: Long) = !isClosed && healthy
  }
  private class Channel : SshRuntimeChannel {
    var closes = 0
    override val isClosed get() = closes > 0
    override fun close() { closes++ }
  }
  private val config = SshHostConfig("source", "host", "example.test", user = "user", hostKeyFingerprint = "pin")

  @Test fun poolSharesPhysicalTransportButReleasesChildrenIndependently() {
    val physical = Transport()
    var connects = 0
    val factory = object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
        connects++; return physical
      }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
    }
    val pool = SshTransportPool(factory)
    val dsh = pool.connect(config, SshSecrets(), SshHostKeyVerifier { true })
    val openCode = pool.connect(config, SshSecrets(), SshHostKeyVerifier { true })
    assertEquals(1, connects)
    dsh.close(); dsh.close()
    assertEquals(0, physical.closes)
    assertTrue(openCode.ping(100))
    openCode.close()
    assertEquals(1, physical.closes)
  }

  @Test fun invalidationClosesAllOldReferencesAndDoesNotCloseReplacement() {
    val connections = mutableListOf<Transport>()
    val pool = SshTransportPool(object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier) =
        Transport().also { connections += it }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
    })
    val old = pool.connect(config, SshSecrets(), SshHostKeyVerifier { true })
    pool.invalidate(config.id)
    val next = pool.connect(config, SshSecrets(), SshHostKeyVerifier { true })
    old.close()
    assertFalse(next.isClosed)
    assertEquals(2, connections.size)
    next.close()
    assertEquals(1, connections.last().closes)
  }

  @Test fun halfOpenTransportIsRetiredDespiteSiblingReferences() {
    val connections = mutableListOf<Transport>()
    val pool = SshTransportPool(object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier) =
        Transport().also { connections += it }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
    })
    val first = pool.connect(config, SshSecrets(), SshHostKeyVerifier { true })
    val sibling = pool.connect(config, SshSecrets(), SshHostKeyVerifier { true })
    connections[0].healthy = false
    assertFalse(first.ping(100))
    assertTrue(sibling.isClosed)
    val replacement = pool.connect(config, SshSecrets(), SshHostKeyVerifier { true })
    assertEquals(2, connections.size)
    first.close(); sibling.close()
    assertFalse(replacement.isClosed)
    assertTrue(replacement.ping(100))
    assertEquals(1, connections[0].closes)
    replacement.close()
  }

  @Test fun runtimeSiblingsAndRepeatedLeasesAreIndependent() {
    val transport = Transport()
    val session = SshTransportSession(transport)
    val dshChannel = Channel()
    val opChannel = Channel()
    val dsh = session.acquire(SshServiceKey("dsh"), SshServiceLifecycleOwner.MANAGED,
      factory = SshRuntimeChannelFactory { _, _ -> dshChannel })
    val op = session.acquire(SshServiceKey("opencode"), SshServiceLifecycleOwner.EXTERNAL,
      factory = SshRuntimeChannelFactory { _, _ -> opChannel })
    val op2 = session.acquire(SshServiceKey("opencode"), SshServiceLifecycleOwner.EXTERNAL,
      factory = SshRuntimeChannelFactory { _, _ -> error("should share existing") })
    dsh.close(); dsh.close()
    assertEquals(1, dshChannel.closes)
    assertEquals(0, opChannel.closes)
    assertTrue(op.isCurrent)
    op.close()
    assertEquals(0, opChannel.closes)
    op2.close()
    assertEquals(1, opChannel.closes)
    assertEquals(1, transport.closes)
  }

  @Test fun reconnectFencesLateReleaseAndAllowsSameServiceToReattach() {
    val first = Transport()
    val second = Transport()
    val session = SshTransportSession(first)
    val oldChannel = Channel()
    val old = session.acquire(SshServiceKey("dsh"), SshServiceLifecycleOwner.MANAGED,
      factory = SshRuntimeChannelFactory { _, _ -> oldChannel })
    session.replaceTransport(second)
    assertEquals(1, first.closes)
    assertEquals(1, oldChannel.closes)
    assertFalse(old.isCurrent)
    assertTrue(runCatching { old.requireCurrent() }.isFailure)
    val channel = Channel()
    val next = session.acquire(SshServiceKey("dsh"), SshServiceLifecycleOwner.MANAGED,
      factory = SshRuntimeChannelFactory { _, _ -> channel })
    old.close()
    assertTrue(next.isCurrent)
    assertEquals(0, channel.closes)
    assertEquals(0, second.closes)
    next.close()
    assertEquals(1, second.closes)
  }
}
