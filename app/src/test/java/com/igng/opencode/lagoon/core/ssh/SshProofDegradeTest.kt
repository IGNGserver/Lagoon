package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

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

  @Test fun commandCarriesTheInterpreterPreflight() {
    val command = SshVerifiedTunnel.command(target)
    assertTrue(command.contains("command -v python3"))
    assertTrue(command.contains("LAGOON_SOCKET_UNSUPPORTED_V1"))
    assertTrue(command.contains("/usr/sbin/lsof"))
  }

  @Test fun realShellWithoutPythonDegradesInsteadOfReportingAnInvalidResult() {
    assumeTrue(File("/bin/sh").isFile)
    val home = Files.createTempDirectory("lagoon-proof-degrade").toFile()
    try {
      val proof = SshVerifiedTunnel.verify(ShellTransport(home, "/nonexistent"), target)
      assertEquals(SshVerifiedTunnel.Proof.DEGRADED, proof)
    } finally { home.deleteRecursively() }
  }

  /** Runs the production command through a real shell with a PATH that has no python3. */
  private class ShellTransport(private val home: File, private val path: String) : SshTransport {
    override val isClosed: Boolean = false
    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult = error("unused")
    override fun openExecChannel(script: String): SshExecChannel {
      val process = ProcessBuilder("/bin/sh", "-lc", script).apply {
        environment()["HOME"] = home.absolutePath
        environment()["PATH"] = path
      }.start()
      return object : SshExecChannel {
        override val stdin: OutputStream = process.outputStream
        override val stdout: InputStream = process.inputStream
        override val stderr: InputStream = process.errorStream
        override val isOpen: Boolean get() = process.isAlive
        override fun close() { process.destroyForcibly() }
      }
    }
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection =
      error("unused")
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean = false
    override fun ping(timeoutMillis: Long): Boolean = false
    override fun close() = Unit
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
