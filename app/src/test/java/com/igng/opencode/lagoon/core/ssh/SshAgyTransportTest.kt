package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshAgyTransportTest {
  private val config = SshHostConfig(id = "host", name = "host", host = "localhost", user = "dev", hostKeyFingerprint = "SHA256:test")

  @Test fun nativePromptIsAdmittedBeforeItsResultAndCancelIsANotification() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val transport = SshAgyTransport(config, SshSecrets(password = "secret"), FakeFactory(ssh))
    try {
      val call = async(Dispatchers.IO) { transport.call("session/list", JSONObject()) }
      val init = wire.next()
      assertEquals("initialize", init.getString("method"))
      assertEquals(1, init.getJSONObject("params").getInt("protocolVersion"))
      wire.write(JSONObject().put("id", init.get("id")).put("result", JSONObject().put("protocolVersion", 1)))
      val list = wire.next()
      assertEquals("session/list", list.getString("method"))
      wire.write(JSONObject().put("id", list.get("id")).put("result", JSONObject().put("sessions", org.json.JSONArray())))
      assertEquals(0, withTimeout(3_000) { call.await() }.getJSONArray("sessions").length())
      assertEquals("exec agy acp", ssh.script)

      val prompt = withTimeout(3_000) { transport.beginPrompt(JSONObject().put("sessionId", "one").put("prompt", org.json.JSONArray())) }
      val sent = wire.next()
      assertEquals("session/prompt", sent.getString("method"))
      assertFalse(prompt.isCompleted)
      transport.notify("session/cancel", JSONObject().put("sessionId", "one"))
      val cancel = wire.next()
      assertEquals("session/cancel", cancel.getString("method"))
      assertFalse(cancel.has("id"))
      wire.write(JSONObject().put("id", sent.get("id")).put("result", JSONObject().put("stopReason", "cancelled")))
      assertEquals("cancelled", withTimeout(3_000) { prompt.await() }.getString("stopReason"))
    } finally { transport.close() }
    assertTrue(wire.closed.get())
    assertTrue(ssh.isClosed)
  }

  @Test fun malformedProtocolClosesTheConnection() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val transport = SshAgyTransport(config, SshSecrets(password = "secret"), FakeFactory(ssh))
    try {
      val call = async(Dispatchers.IO) { runCatching { transport.call("session/list", JSONObject()) } }
      wire.next()
      wire.writeRaw("a shell banner\n")
      assertTrue(withTimeout(3_000) { call.await() }.isFailure)
      assertTrue(transport.isClosed)
      assertTrue(ssh.isClosed)
    } finally { transport.close() }
  }

  @Test fun hostManagerRoutesTheAgyLeafToAgyRatherThanCodex() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val store = SshHostStore(FakeSharedPreferences(), FakeSharedPreferences(), { it }, { it })
    store.save(config, SshSecrets(password = "secret"))
    val manager = SshHostManager(store, FakeFactory(ssh))
    val profile = ServerProfile("agy-leaf", "AGY", config.profileUrl(), username = config.user,
      kind = ServerKind.CODEX, sshHostId = config.id, protocolId = HarnessProtocolId.ANTIGRAVITY_ACP)
    val client = manager.client(profile)
    try {
      val health = async(Dispatchers.IO) { client.health() }
      val init = wire.next()
      assertEquals("initialize", init.getString("method"))
      assertEquals("exec agy acp", ssh.script)
      wire.write(JSONObject().put("id", init.get("id")).put("result", JSONObject().put("protocolVersion", 1)))
      val listing = wire.next()
      assertEquals("session/list", listing.getString("method"))
      wire.write(JSONObject().put("id", listing.get("id")).put("result", JSONObject().put("sessions", org.json.JSONArray())))
      assertEquals("Antigravity ACP", withTimeout(3_000) { health.await() })
    } finally { client.close(); manager.closeAll() }
  }

  @Test fun replayBurstsApplyBackpressureInsteadOfClosingTheSshChannel() = runBlocking {
    val wire = FakeChannel()
    val ssh = FakeSsh(wire)
    val transport = SshAgyTransport(config, SshSecrets(password = "secret"), FakeFactory(ssh))
    try {
      val events = async(start = CoroutineStart.UNDISPATCHED) {
        val seen = mutableListOf<JSONObject>()
        transport.events().take(300).collect { delay(1); seen += it }
        seen
      }
      val request = async(Dispatchers.IO) { transport.call("session/load", JSONObject().put("sessionId", "one")) }
      val init = wire.next()
      wire.write(JSONObject().put("id", init.get("id")).put("result", JSONObject().put("protocolVersion", 1)))
      val load = wire.next()
      withTimeout(5_000) {
        repeat(300) { index ->
          wire.write(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", "one")
            .put("update", JSONObject().put("sessionUpdate", "agent_message_chunk").put("text", index.toString()))))
        }
        wire.write(JSONObject().put("id", load.get("id")).put("result", JSONObject()))
        request.await()
        assertEquals(300, events.await().size)
      }
      assertFalse(transport.isClosed)
    } finally { transport.close() }
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
    override fun openExecChannel(script: String): SshExecChannel { this.script = script; return wire }
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
    val closed = AtomicBoolean(false)
    override val isOpen get() = !closed.get()
    private val lines = LinkedBlockingQueue<String>()
    init { Thread { runCatching { outgoingInput.bufferedReader().forEachLine { lines.put(it) } } }.apply { isDaemon = true; start() } }
    fun next() = JSONObject(lines.poll(3, TimeUnit.SECONDS) ?: error("timed out waiting for client frame"))
    fun write(value: JSONObject) = writeRaw(value.toString() + "\n")
    fun writeRaw(value: String) { source.write(value.toByteArray()); source.flush() }
    override fun close() {
      if (closed.compareAndSet(false, true)) {
        runCatching { stdin.close() }; runCatching { outgoingInput.close() }
        runCatching { source.close() }; runCatching { stdout.close() }
        runCatching { errors.close() }; runCatching { stderr.close() }
      }
    }
  }
}
