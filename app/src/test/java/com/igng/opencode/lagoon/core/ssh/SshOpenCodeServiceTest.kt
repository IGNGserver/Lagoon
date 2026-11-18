package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.SshFailure
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

/** The start/restart action is user-triggered; these tests pin its parsing and its command shape. */
class SshOpenCodeServiceTest {

  @Test fun parsesTaggedOutputAndPrefersRestartWhenTheServiceIsAlreadyRunning() {
    val transport = FakeTransport(
      """
      LAGOON_OC_INSTALLED 1
      LAGOON_OC_VERSION 2.0.18
      LAGOON_OC_STATE running
      LAGOON_OC_ACTION restart
      LAGOON_OC_EXIT 0
      LAGOON_OC_LINE http://127.0.0.1:49374
      """.trimIndent() + "\n"
    )
    val result = SshOpenCodeService(transport).restart()
    assertTrue(result.ok)
    assertEquals("restart", result.action)
    assertEquals("2.0.18", result.version)
    assertEquals("running", result.previousState)
    assertEquals(listOf("http://127.0.0.1:49374"), result.output)
    assertEquals("OpenCode 已重启 · 2.0.18", result.summary())
  }

  @Test fun missingBinaryAndNonZeroExitAreNeverReportedAsSuccess() {
    val missing = SshOpenCodeService(FakeTransport("LAGOON_OC_INSTALLED 0\n")).restart()
    assertFalse(missing.ok)
    assertTrue(missing.summary().contains("没有找到 opencode"))

    val failed = SshOpenCodeService(FakeTransport(
      "LAGOON_OC_INSTALLED 1\nLAGOON_OC_VERSION 2.0.18\nLAGOON_OC_ACTION start\nLAGOON_OC_EXIT 1\nLAGOON_OC_LINE boom\n"
    )).restart()
    assertFalse(failed.ok)
    assertTrue(failed.summary().contains("失败"))
    assertTrue(failed.summary().contains("boom"))
  }

  @Test fun anOldBinaryIsRejectedBeforeItIsUsedAsANativeService() {
    val result = SshOpenCodeService(FakeTransport(
      "LAGOON_OC_INSTALLED 1\nLAGOON_OC_VERSION 1.18.18\nLAGOON_OC_ACTION start\nLAGOON_OC_EXIT 0\n"
    )).restart()
    assertTrue(result.ok)
    assertFalse(result.versionSupported)
    assertTrue(result.summary().contains("需要 2.x"))
  }

  @Test fun commandUsesTheSharedSearchPathAndNoProfileSuppliedText() {
    val transport = FakeTransport("LAGOON_OC_INSTALLED 0\n")
    SshOpenCodeService(transport).restart()
    val script = transport.script
    assertTrue(script.contains("opencode service status"))
    assertTrue(script.contains("opencode service \"\$__lagoon_action\""))
    assertTrue(script.contains(".opencode/bin"))
    // Fixed commands only: no shell interpolation of a path, command or credential from the profile.
    listOf("user@", "password", "sshServicePath", "eval ", "bash -c").forEach {
      assertFalse("Script must not contain $it", script.contains(it))
    }
  }

  private class FakeTransport(private val output: String, private val exit: Int = 0) : SshTransport {
    var script = ""
    override val isClosed = false
    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
      this.script = script
      return RemoteExecResult(exit, output, "")
    }
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection =
      throw UnsupportedOperationException()
    override fun openExecChannel(script: String): SshExecChannel = throw UnsupportedOperationException()
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = false
    override fun ping(timeoutMillis: Long) = false
    override fun close() = Unit
  }
}
