package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class SshHarnessProbeTest {
  private val path = "/home/dev/.local/state/opencode/service.json"
  private val registration = """{"id":"generation","version":"2.2.6","url":"http://127.0.0.1:5000/","pid":12,"password":"discovery-secret"}"""

  @Test fun usesOneBoundedExecAndDoesNotOpenChannelsOrOwnTransport() {
    val transport = FakeTransport(envelope(registration))
    val probe = SshHarnessProbe(transport) { 100 }
    val result = probe.discover()
    assertEquals(1, transport.execCount)
    assertEquals(20_000L, transport.timeout)
    assertEquals(SshDiscoveryState.PRESENT, result.openCode.state)
    assertEquals(DiscoveryCompleteness.COMPLETE, result.completeness)
    assertEquals("discovery-secret", result.openCode.registration?.password)
    assertEquals(DiscoveryValue.UNKNOWN, result.openCodeStatus().available)
    assertEquals(SshDiscoveryState.PRESENT, result.dshLegacy.state)
    assertEquals("/home/dev/.dsh-app-remote", result.dshLegacy.descriptor?.markerPath)
    assertFalse(result.toString().contains("discovery-secret"))
    assertEquals(0, transport.channelCount)
    assertFalse(transport.isClosed)
  }

  @Test fun missingPythonAndExecFailureRemainExplicitPartialUnknown() {
    val missing = SshHarnessProbe(FakeTransport("LAGOON_SSH_DISCOVERY {\"python\":false}\n")).discover()
    assertEquals(DiscoveryCompleteness.PARTIAL, missing.completeness)
    assertEquals(SshDiscoveryState.UNKNOWN, missing.openCode.state)
    assertEquals(SshDiscoveryIssue.PYTHON_UNAVAILABLE, missing.openCode.issue)
    assertEquals(DiscoveryValue.UNKNOWN, missing.openCodeStatus().available)
    val transport = FakeTransport("discovery-secret", exit = 1)
    val failed = SshHarnessProbe(transport).discover()
    assertEquals(SshDiscoveryIssue.EXEC_FAILED, failed.openCode.issue)
    assertFalse(failed.toString().contains("discovery-secret"))
    transport.failure = IllegalStateException("discovery-secret")
    val exception = runCatching { SshHarnessProbe(transport).openCodeRegistration() }.exceptionOrNull()
    assertTrue(exception is SshFailure)
    assertFalse(exception.toString().contains("discovery-secret"))
  }

  @Test fun absentCorruptAndUnreadableFilesRemainDistinctAndCannotClaimAvailable() {
    val absent = SshHarnessProbe.parseOutput(envelope(null, "absent", "not_found"), 100)
    assertEquals(SshDiscoveryState.ABSENT, absent.openCode.state)
    assertEquals(DiscoveryCompleteness.COMPLETE, absent.completeness)
    assertEquals(DiscoveryValue.NO, absent.openCodeStatus().available)
    val corrupt = SshHarnessProbe.parseOutput(envelope("not-json discovery-secret"), 100)
    assertEquals(SshDiscoveryState.CORRUPT, corrupt.openCode.state)
    assertEquals(SshDiscoveryIssue.INVALID_DATA, corrupt.openCode.issue)
    assertEquals(SshDiscoveryState.PRESENT, corrupt.dshLegacy.state)
    assertFalse(corrupt.toString().contains("discovery-secret"))
    val unreadable = SshHarnessProbe.parseOutput(envelope(null, "unknown", "unreadable"), 100)
    assertEquals(SshDiscoveryState.UNKNOWN, unreadable.openCode.state)
    assertEquals(DiscoveryCompleteness.PARTIAL, unreadable.completeness)
    assertEquals(SshDiscoveryIssue.UNREADABLE, unreadable.openCode.issue)
    val oversize = SshHarnessProbe.parseOutput(envelope(null, "corrupt", "too_large"), 100)
    assertEquals(SshDiscoveryIssue.TOO_LARGE, oversize.openCode.issue)
  }

  @Test fun malformedRawBytesAreCorruptWithoutErasingIndependentDshObservation() {
    val invalidUtf8 = JSONObject(envelope(registration).substringAfter("LAGOON_SSH_DISCOVERY "))
    invalidUtf8.getJSONObject("opencode").put("rawBase64", Base64.getEncoder().encodeToString(byteArrayOf(-1)))
    val result = SshHarnessProbe.parseOutput("LAGOON_SSH_DISCOVERY $invalidUtf8", 100)
    assertEquals(SshDiscoveryState.CORRUPT, result.openCode.state)
    assertEquals(SshDiscoveryState.PRESENT, result.dshLegacy.state)
  }

  @Test fun truncatedOrDuplicateEnvelopeIsNotReportedAsAbsent() {
    listOf("", "LAGOON_SSH_DISCOVERY {", envelope(registration) + "\n" + envelope(registration),
      "LAGOON_SSH_DISCOVERY {\"python\":true}", "x".repeat(65537)).forEach { output ->
      val result = SshHarnessProbe.parseOutput(output, 100)
      assertEquals(SshDiscoveryState.UNKNOWN, result.openCode.state)
      assertEquals(SshDiscoveryIssue.INVALID_OUTPUT, result.openCode.issue)
      assertEquals(DiscoveryCompleteness.PARTIAL, result.completeness)
    }
    assertEquals(SshDiscoveryState.PRESENT,
      SshHarnessProbe.parseOutput("welcome\n" + envelope(registration), 100).openCode.state)
  }

  @Test fun customPathIsQuotedAndNeverEvaluated() {
    val custom = "/home/dev/o'c/\$(touch forbidden);service.json"
    val script = SshHarnessProbe.probeScript(custom)
    assertTrue(script.contains("python3 - ${SshShell.quote(custom)} <<'LAGOON_SSH_DISCOVERY_PY'"))
    listOf("relative/service.json", "/tmp/x\ncommand", "/tmp/x\u0000").forEach { invalid ->
      assertTrue(runCatching { SshHarnessProbe.probeScript(invalid) }.isFailure)
    }
    listOf("nohup", "mkdir ", "chmod ", "unlink(", "remove(", "os.kill", "subprocess", "urlopen", "curl ").forEach { forbidden ->
      assertFalse("Unexpected side effect: $forbidden", script.contains(forbidden))
    }
    assertTrue(script.contains("stream.read(16385)"))
    assertTrue(script.contains("os.O_RDONLY"))
  }

  @Test fun realReadOnlyCommandHonorsXdgAndLeavesLegacyMarkerContentsUntouched() {
    assumeTrue(File("/usr/bin/python3").canExecute() && File("/bin/sh").canExecute())
    val root = Files.createTempDirectory("lagoon-discovery-").toFile()
    try {
      val home = File(root, "home").apply { mkdirs() }
      val state = File(root, "state with 'quote").apply { mkdirs() }
      val file = File(state, "opencode/service.json").apply { parentFile.mkdirs(); writeText(registration) }
      val legacy = File(home, ".dsh-app-remote").apply { mkdirs() }
      val stalePid = File(legacy, "web.pid").apply { writeText("999999999\n") }
      val log = File(legacy, "web.log").apply { writeText("private token=not-for-discovery\n") }
      val transport = LocalReadTransport(home, state)
      val result = SshHarnessProbe(transport).discover()
      assertEquals(SshDiscoveryState.PRESENT, result.openCode.state)
      assertEquals(file.absolutePath, result.openCode.registration?.registrationPath)
      assertEquals("999999999\n", stalePid.readText())
      assertEquals("private token=not-for-discovery\n", log.readText())
      assertFalse(result.toString().contains("not-for-discovery"))
      assertEquals(registration, file.readText())
      val custom = File(root, "explicit 'file.json").apply { writeText(registration) }
      assertEquals(custom.absolutePath, SshHarnessProbe(transport).openCodeRegistration(custom.absolutePath).registrationPath)
      file.delete()
      assertEquals(SshDiscoveryState.ABSENT, SshHarnessProbe(transport).discover().openCode.state)
      val fallback = File(home, ".local/state/opencode/service.json").apply { parentFile.mkdirs(); writeText(registration) }
      assertEquals(fallback.absolutePath,
        SshHarnessProbe(LocalReadTransport(home, null)).openCodeRegistration().registrationPath)
    } finally { root.deleteRecursively() }
  }

  private fun envelope(raw: String?, state: String = "present", issue: String? = null): String {
    val oc = JSONObject().put("path", path).put("state", state)
    if (raw != null) oc.put("rawBase64", Base64.getEncoder().encodeToString(raw.toByteArray()))
    if (issue != null) oc.put("issue", issue)
    return "LAGOON_SSH_DISCOVERY " + JSONObject().put("python", true).put("opencode", oc)
      .put("dshLegacy", JSONObject().put("path", "/home/dev/.dsh-app-remote").put("state", "present"))
  }

  private class FakeTransport(private val output: String, private val exit: Int = 0) : SshTransport {
    var execCount = 0
    var timeout = 0L
    var channelCount = 0
    var failure: Exception? = null
    override var isClosed = false
    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
      execCount++; timeout = timeoutMillis; failure?.let { throw it }
      return RemoteExecResult(exit, output, "discovery-secret")
    }
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection {
      channelCount++; error("Discovery must not open a tunnel")
    }
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean { channelCount++; return false }
    override fun ping(timeoutMillis: Long): Boolean { channelCount++; return false }
    override fun close() { isClosed = true }
  }

  private class LocalReadTransport(private val home: File, private val state: File?) : SshTransport {
    override val isClosed = false
    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
      val process = ProcessBuilder("/bin/sh", "-c", script).apply {
        environment()["HOME"] = home.absolutePath
        environment()["PATH"] = "/usr/bin:/bin"
        if (state == null) environment().remove("XDG_STATE_HOME") else environment()["XDG_STATE_HOME"] = state.absolutePath
      }.start()
      assertTrue(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS))
      return RemoteExecResult(process.exitValue(), process.inputStream.bufferedReader().readText(),
        process.errorStream.bufferedReader().readText())
    }
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection = error("unused")
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean = error("unused")
    override fun ping(timeoutMillis: Long): Boolean = error("unused")
    override fun close() = Unit
  }
}
