package com.jcraft.jsch

import com.igng.opencode.lagoon.core.ssh.SshTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSch's session constructor is package-private, so these transport-only fakes live in its package. */
class JschPingTest {
  private class FakeExec(
    private var closed: Boolean,
    private val status: Int = 0,
    private val failConnect: Boolean = false,
  ) : ChannelExec() {
    var observedCommand: String? = null
      private set
    var observedConnectTimeout: Int? = null
      private set
    var disconnected = false
      private set

    override fun setCommand(command: String) {
      observedCommand = command
      super.setCommand(command)
    }

    override fun connect(timeoutMillis: Int) {
      observedConnectTimeout = timeoutMillis
      if (failConnect) throw JSchException("channel open failed")
    }

    override fun isClosed(): Boolean = closed
    override fun getExitStatus(): Int = status
    override fun disconnect() {
      disconnected = true
      closed = true
    }
  }

  private class FakeSession(private val channel: ChannelExec, private val connected: Boolean = true) :
    Session(JSch(), "user", "localhost", 22) {
    override fun isConnected(): Boolean = connected
    override fun openChannel(type: String): Channel {
      assertEquals("exec", type)
      return channel
    }
  }

  private fun transport(channel: ChannelExec, connected: Boolean = true): SshTransport {
    val type = Class.forName("com.igng.opencode.lagoon.core.ssh.JschSshTransport")
    return type.getDeclaredConstructor(Session::class.java).apply { isAccessible = true }
      .newInstance(FakeSession(channel, connected)) as SshTransport
  }

  @Test fun successfulNoopRequiresAnActualCommandRoundTrip() {
    val channel = FakeExec(closed = true, status = 0)
    assertTrue(transport(channel).ping(10))
    assertEquals(":", channel.observedCommand)
    assertEquals(1_000, channel.observedConnectTimeout)
    assertTrue(channel.disconnected)
  }

  @Test fun nonzeroExitOpenTimeoutAndDisconnectedSessionAreNotHealthy() {
    assertFalse(transport(FakeExec(closed = true, status = 1)).ping(1_000))

    val hanging = FakeExec(closed = false)
    val started = System.nanoTime()
    assertFalse(transport(hanging).ping(1))
    assertTrue("the command must have a bounded deadline", (System.nanoTime() - started) / 1_000_000 < 3_000)
    assertTrue(hanging.disconnected)

    val refused = FakeExec(closed = false, failConnect = true)
    assertFalse(transport(refused).ping(1_000))
    assertTrue(refused.disconnected)
    assertFalse(transport(FakeExec(closed = true), connected = false).ping(1_000))
  }
}
