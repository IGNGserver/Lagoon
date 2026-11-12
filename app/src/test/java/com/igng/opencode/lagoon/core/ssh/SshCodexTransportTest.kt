package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshCodexTransportTest {
  private val config = SshHostConfig(id = "host", name = "host", host = "localhost", user = "dev",
    hostKeyFingerprint = "SHA256:test")

  @Test fun handshakeCallsAndServerRequestsShareOneChannel() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val transport = SshCodexTransport(config, SshSecrets(password = "secret"), FakeFactory(ssh))
    val incoming = async { withTimeout(3_000) { transport.events().first() } }
    val request = async(Dispatchers.IO) { transport.call("account/read", JSONObject()) }
    val init = wire.next()
    assertEquals("initialize", init.getString("method"))
    assertNull(transport.serverVersion)
    wire.write(JSONObject().put("id", init.get("id")).put("result", JSONObject().put("userAgent", "lagoon/0.167.2 (private details)")))
    assertEquals("initialized", wire.next().getString("method"))
    val account = wire.next()
    assertEquals("account/read", account.getString("method"))
    wire.write(JSONObject().put("method", "item/commandExecution/requestApproval").put("id", "approval-1")
      .put("params", JSONObject().put("threadId", "t")))
    wire.write(JSONObject().put("id", account.get("id")).put("result", JSONObject().put("ok", true)))
    assertEquals("approval-1", incoming.await().getString("id"))
    assertTrue(request.await().getBoolean("ok"))
    assertEquals("0.167.2", transport.serverVersion)
    transport.respond("approval-1", JSONObject().put("decision", "decline"))
    val response = wire.next()
    assertEquals("approval-1", response.getString("id"))
    assertEquals("decline", response.getJSONObject("result").getString("decision"))
    transport.respondError(42L, -32601, "Unsupported server request")
    val rejected = wire.next()
    assertEquals(42L, rejected.getLong("id"))
    assertEquals(-32601, rejected.getJSONObject("error").getInt("code"))
    assertEquals("Unsupported server request", rejected.getJSONObject("error").getString("message"))
    assertFalse(rejected.has("result"))
    assertTrue(runCatching { transport.respondError(42.0, -32601, "bad") }.isFailure)
    assertEquals("exec codex app-server --listen stdio://", ssh.script)
    transport.close()
    assertTrue(wire.closed.get())
    assertTrue(ssh.isClosed)
    assertNull(transport.serverVersion)
  }

  @Test fun versionParserRejectsUnknownOrAmbiguousUserAgents() {
    assertEquals("0.167.2", parseServerVersion("lagoon/0.167.2"))
    assertEquals("0.167.2", parseServerVersion("lagoon/0.167.2 (details)"))
    listOf(null, "", "other/0.167.2", "lagoon/0.167", "lagoon/0.167.2-dev",
      "lagoon/0.167.2.1", "lagoon/00.167.2", "lagoon/0.167.2x",
      "lagoon/0.167.2" + "x".repeat(1024)).forEach { assertNull(parseServerVersion(it)) }
  }

  @Test fun malformedStdoutFailsPendingCallAndReleasesLease() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val transport = SshCodexTransport(config, SshSecrets(password = "secret"), FakeFactory(ssh))
    val pending = async(Dispatchers.IO) { runCatching { transport.call("account/read", JSONObject()) } }
    wire.next() // initialize is pending
    wire.writeRaw("a shell banner\n")
    assertTrue(withTimeout(3_000) { pending.await() }.isFailure)
    assertTrue(ssh.isClosed)
    assertTrue(wire.closed.get())
  }

  @Test fun concurrentCallsMatchOutOfOrderRepliesAndEventsAreBroadcast() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val transport = SshCodexTransport(config, SshSecrets(password = "secret"), FakeFactory(ssh))
    val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { transport.events().first() }
    val secondEvent = async(start = CoroutineStart.UNDISPATCHED) { transport.events().first() }
    val first = async(Dispatchers.IO) { transport.call("one", JSONObject()) }
    val second = async(Dispatchers.IO) { transport.call("two", JSONObject()) }
    val init = wire.next()
    wire.write(JSONObject().put("id", init.get("id")).put("result", JSONObject()))
    assertEquals("initialized", wire.next().getString("method"))
    val a = wire.next()
    val b = wire.next()
    wire.write(JSONObject().put("method", "turn/started").put("params", JSONObject().put("threadId", "t")))
    wire.write(JSONObject().put("id", b.get("id")).put("result", JSONObject().put("method", b.getString("method"))))
    wire.write(JSONObject().put("id", a.get("id")).put("result", JSONObject().put("method", a.getString("method"))))
    assertEquals("one", withTimeout(3_000) { first.await() }.getString("method"))
    assertEquals("two", withTimeout(3_000) { second.await() }.getString("method"))
    assertEquals("turn/started", withTimeout(3_000) { firstEvent.await() }.getString("method"))
    assertEquals("turn/started", withTimeout(3_000) { secondEvent.await() }.getString("method"))
    transport.close()
  }

  @Test fun probeDistinguishesInstallationFromAvailability() {
    val ssh = FakeSsh(FakeChannel())
    ssh.probeOutput = "welcome\nLAGOON_CODEX_DISCOVERY version:codex-cli 0.167.2\n"
    val observation = SshCodexProbe(ssh).discover()
    assertEquals(true, observation.installed)
    assertEquals("codex-cli 0.167.2", observation.version)
    assertFalse(ssh.script.orEmpty().contains("app-server"))
    ssh.probeOutput = "LAGOON_CODEX_DISCOVERY absent\n"
    assertEquals(false, SshCodexProbe(ssh).discover().installed)
    ssh.probeOutput = "LAGOON_CODEX_DISCOVERY absent\nLAGOON_CODEX_DISCOVERY absent\n"
    assertNull(SshCodexProbe(ssh).discover().installed)
    ssh.close()
  }

  @Test fun closeWhileConnectingReleasesLateAcquiredLease() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val factory = object : SshTransportFactory {
      override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
        entered.countDown()
        check(resume.await(3, TimeUnit.SECONDS))
        return ssh
      }
      override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
    }
    val transport = SshCodexTransport(config, SshSecrets(password = "secret"), factory)
    val pending = async(Dispatchers.IO) { runCatching { transport.call("account/read", JSONObject()) } }
    assertTrue(entered.await(3, TimeUnit.SECONDS))
    transport.close()
    resume.countDown()
    assertTrue(withTimeout(3_000) { pending.await() }.isFailure)
    assertTrue(ssh.isClosed)
    assertTrue(transport.isClosed)
  }

  @Test fun blockedWriteTimesOutAndClosesOnlyItsChannelAndLease() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val transport = SshCodexTransport(config, SshSecrets(password = "secret"), FakeFactory(ssh), timeoutMillis = 500)
    val first = async(Dispatchers.IO) { transport.call("account/read", JSONObject()) }
    val init = wire.next()
    wire.write(JSONObject().put("id", init.get("id")).put("result", JSONObject()))
    assertEquals("initialized", wire.next().getString("method"))
    val account = wire.next()
    wire.write(JSONObject().put("id", account.get("id")).put("result", JSONObject()))
    first.await()
    wire.blockWrites = true
    val blocked = async(Dispatchers.IO) { runCatching { transport.call("turn/start", JSONObject()) } }
    assertTrue(wire.writeStarted.await(2, TimeUnit.SECONDS))
    assertTrue(withTimeout(3_000) { blocked.await() }.isFailure)
    assertTrue(transport.isClosed)
    assertTrue(wire.closed.get())
    assertTrue(ssh.isClosed)
    assertNull(wire.nextOrNull()) // no retry after the outcome becomes unknown
  }

  private class FakeFactory(private val ssh: FakeSsh) : SshTransportFactory {
    override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
      check(verifier.verify(PresentedSshHostKey("ssh-ed25519", "SHA256:test")))
      return ssh
    }
    override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = error("unused")
  }

  private class FakeSsh(private val wire: FakeChannel) : SshTransport {
    override var isClosed = false
    var script: String? = null
    var probeOutput = ""
    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
      this.script = script
      return RemoteExecResult(0, probeOutput, "")
    }
    override fun openExecChannel(script: String): SshExecChannel { this.script = script; return wire }
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection = error("unused")
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = false
    override fun ping(timeoutMillis: Long) = !isClosed
    override fun close() { isClosed = true }
  }

  private class FakeChannel : SshExecChannel {
    private val outgoingInput = PipedInputStream(64 * 1024)
    private val outgoing = PipedOutputStream(outgoingInput)
    @Volatile var blockWrites = false
    val writeStarted = CountDownLatch(1)
    private val releaseWrites = CountDownLatch(1)
    override val stdin: OutputStream = object : OutputStream() {
      override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
      override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (blockWrites) {
          writeStarted.countDown()
          if (!releaseWrites.await(5, TimeUnit.SECONDS)) throw IOException("test write did not unblock")
          if (closed.get()) throw IOException("channel closed")
        }
        outgoing.write(bytes, offset, length)
      }
      override fun flush() = outgoing.flush()
      override fun close() = outgoing.close()
    }
    private val source = PipedOutputStream()
    override val stdout: InputStream = PipedInputStream(source, 64 * 1024)
    private val errors = PipedOutputStream()
    override val stderr: InputStream = PipedInputStream(errors, 1024)
    val closed = AtomicBoolean(false)
    override val isOpen get() = !closed.get()
    private val lines = LinkedBlockingQueue<String>()
    init {
      Thread {
        runCatching { outgoingInput.bufferedReader().forEachLine { lines.put(it) } }
      }.apply { isDaemon = true; start() }
    }
    fun next(): JSONObject = JSONObject(lines.poll(3, TimeUnit.SECONDS) ?: error("timed out waiting for client frame"))
    fun nextOrNull(): JSONObject? = lines.poll(100, TimeUnit.MILLISECONDS)?.let(::JSONObject)
    fun write(value: JSONObject) = writeRaw(value.toString() + "\n")
    fun writeRaw(value: String) { source.write(value.toByteArray()); source.flush() }
    override fun close() {
      if (closed.compareAndSet(false, true)) {
        releaseWrites.countDown()
        runCatching { stdin.close() }; runCatching { outgoingInput.close() }
        runCatching { source.close() }; runCatching { stdout.close() }
        runCatching { errors.close() }; runCatching { stderr.close() }
      }
    }
  }
}
