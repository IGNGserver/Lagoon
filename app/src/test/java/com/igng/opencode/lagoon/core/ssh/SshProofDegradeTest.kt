package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A remote without python3 (or a macOS without lsof) cannot prove socket ownership. The tunnel
 * must then fall back to the record-level check the bootstrap already did instead of making the
 * whole SSH binding unusable, and it must still refuse a socket that the helper explicitly denied.
 */
class SshProofDegradeTest {
  private val target = SshVerifiedTunnel.Target(
    "127.0.0.1", 4321, 4242, dshStateDirectory = ".lagoon-ssh-dsh"
  )

  @Test fun missingPythonDegradesInsteadOfFailingTheBinding() {
    val proof = SshVerifiedTunnel.verify(ReplyTransport("LAGOON_SOCKET_UNSUPPORTED_V1"), target)
    assertEquals(SshVerifiedTunnel.Proof.DEGRADED, proof)
  }

  @Test fun provenSocketIsReportedAsVerified() {
    val proof = SshVerifiedTunnel.verify(ReplyTransport("LAGOON_SOCKET_READY_V1"), target)
    assertEquals(SshVerifiedTunnel.Proof.VERIFIED, proof)
  }

  @Test fun foreignSocketOwnerIsStillRefused() {
    val failure = runCatching { SshVerifiedTunnel.verify(ReplyTransport("LAGOON_SOCKET_UNVERIFIED_V1"), target) }
      .exceptionOrNull()
    assertTrue("an unverified owner must not be trusted", failure is SshFailure)
    assertTrue(!(failure as SshFailure).retryable)
  }

  private class ReplyTransport(private val reply: String) : SshTransport {
    override val isClosed: Boolean = false
    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult = error("unused")
    override fun openExecChannel(script: String): SshExecChannel = object : SshExecChannel {
      override val stdin: OutputStream = OutputStream.nullOutputStream()
      override val stdout: InputStream = ByteArrayInputStream("$reply\n".toByteArray())
      override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
      override val isOpen: Boolean = true
      override fun close() = Unit
    }
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection =
      error("unused")
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean = true
    override fun ping(timeoutMillis: Long): Boolean = true
    override fun close() = Unit
  }
}
