package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.OpenCodeApi
import com.igng.opencode.lagoon.core.RemoteExecResult
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SessionAction
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshLinkPhase
import com.igng.opencode.lagoon.core.SshLinkStep
import com.igng.opencode.lagoon.core.SshSecrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Concrete SSH carrier coverage without an SSH daemon or any remote process/bootstrap. */
class SshOpenCodeLinkTest {
  @Test fun attachesOpenCodeThroughTheTunnelAndCloseCancelsApiWithoutStoppingRemoteService() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = openCodeDispatcher()
      server.start(InetAddress.getByName(LOOPBACK), 0)
      LinkFixture(server).use { fixture ->
        val client = fixture.link.awaitHarness(TIMEOUT_MILLIS)
        assertTrue("OpenCode bindings must not create a DSH client", client is OpenCodeApi)
        assertEquals(SshLinkPhase.CONNECTED, fixture.link.status.value.phase)
        assertEquals(1L, fixture.link.generationId)
        assertTrue(client.capabilities().documented)
        assertTrue(client.capabilities().supports(SessionAction.RENAME))
        assertFalse(fixture.steps.contains(SshLinkStep.START_DSH))
        assertTrue(fixture.factory.pinAccepted)
        assertEquals(1, fixture.factory.connects.get())
        assertEquals(0, fixture.factory.keyProbes.get())

        // Readiness includes both process identity validation and a protected native route.
        assertRequest(server, "GET", "/api/info")
        assertRequest(server, "GET", "/api/project")
        assertRequest(server, "GET", "/openapi.json")
        assertEquals(VERSION, withTimeout(TIMEOUT_MILLIS) { client.health() })
        assertRequest(server, "GET", "/api/info")
        assertSame(client, fixture.link.awaitHarness(TIMEOUT_MILLIS))

        val session = Session("ses_tunnel", "/repo", "Before", 1)
        withTimeout(TIMEOUT_MILLIS) { client.renameSession(session, "Renamed through SSH") }
        val rename = assertRequest(server, "PATCH", "/api/session/ses_tunnel")
        assertEquals("Renamed through SSH", JSONObject(rename.body.readUtf8()).getString("title"))
        assertTrue(fixture.transport.tunnels.isNotEmpty())
        assertTrue(fixture.transport.tunnels.all { it.host == LOOPBACK && it.port == server.port })
        fixture.assertOnlyReadOnlyDiscovery()

        // NO_RESPONSE keeps a concrete HTTP call pending; receipt is the synchronization barrier.
        // Capturing its failure inside async prevents cancellation from cancelling the test parent.
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { client.session("pending") }
        }
        try {
          assertRequest(server, "GET", "/api/session/pending")
          assertFalse(pending.isCompleted)
          fixture.link.close()
          fixture.link.close()
          val failure = withTimeout(TIMEOUT_MILLIS) { pending.await() }.exceptionOrNull()
          assertTrue("Closing the link must cancel the API, not just break its socket: $failure",
            failure is CancellationException)
          assertTrue(client.isClosed)
          assertTrue(fixture.link.isClosed)
          assertTrue(fixture.transport.isClosed)
          assertEquals(1, fixture.transport.closes.get())
          assertTrue(fixture.transport.tunnels.all { !it.isOpen })
          val afterClose = runCatching { fixture.link.awaitHarness(TIMEOUT_MILLIS) }.exceptionOrNull()
          assertTrue(afterClose is SshFailure)
          assertFalse((afterClose as SshFailure).retryable)
          assertEquals(SshFailureKind.DISCONNECTED, afterClose.kind)
          fixture.assertOnlyReadOnlyDiscovery()

          // The external OpenCode process is not owned by this link. It still serves new requests
          // directly after every forwarded channel has been released, with the same PID/version.
          val direct = OkHttpClient.Builder().callTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build()
          try {
            direct.newCall(Request.Builder().url(server.url("/api/info"))
              .header("Authorization", BASIC_AUTH).build()).execute().use { response ->
              assertEquals(200, response.code)
              val info = JSONObject(response.body!!.string())
              assertEquals(PID, info.getInt("pid"))
              assertEquals(VERSION, info.getString("version"))
            }
            assertRequest(server, "GET", "/api/info")
          } finally {
            direct.connectionPool.evictAll()
            direct.dispatcher.executorService.shutdownNow()
          }
        } finally {
          pending.cancel()
          withTimeout(TIMEOUT_MILLIS) { pending.join() }
        }
        Unit
      }
    }
  }

  @Test fun mismatchedInfoPidFailsPermanentlyBeforeExposingAnOpenCodeClient() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = openCodeDispatcher(infoPid = PID + 1)
      server.start(InetAddress.getByName(LOOPBACK), 0)
      LinkFixture(server).use { fixture ->
        val failure = runCatching { fixture.link.awaitHarness(TIMEOUT_MILLIS) }.exceptionOrNull()
        assertTrue("PID mismatch must be reported as an SSH failure: $failure", failure is SshFailure)
        val sshFailure = failure as SshFailure
        assertEquals(SshFailureKind.OTHER, sshFailure.kind)
        assertFalse(sshFailure.retryable)
        assertEquals(SshLinkPhase.FAILED, fixture.link.status.value.phase)
        assertSame(sshFailure, fixture.link.status.value.error)
        assertEquals(0L, fixture.link.generationId)
        assertTrue(fixture.factory.pinAccepted)
        assertRequest(server, "GET", "/api/info")
        assertEquals("A mismatched process must not receive authenticated project or schema requests",
          1, server.requestCount)
        assertTrue(fixture.transport.isClosed)
        assertTrue(fixture.transport.tunnels.all { !it.isOpen })
        fixture.assertOnlyReadOnlyDiscovery()

        // Asking again must surface the permanent failure, not silently bootstrap or retry.
        assertSame(sshFailure, runCatching { fixture.link.awaitHarness(TIMEOUT_MILLIS) }.exceptionOrNull())
        assertEquals(1, fixture.factory.connects.get())
        assertEquals(1, server.requestCount)
        fixture.link.close()
        fixture.assertOnlyReadOnlyDiscovery()
      }
    }
  }

  @Test fun normalEventStreamEndReopensOnTheSameLiveSshGeneration() = runBlocking {
    MockWebServer().use { server ->
      val native = openCodeDispatcher()
      val events = AtomicInteger()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.path == "/api/event") {
            val number = events.incrementAndGet()
            return MockResponse().setHeader("Content-Type", "text/event-stream")
              .setBody("data: {\"id\":\"evt_$number\",\"type\":\"server.connected\",\"location\":{\"directory\":\"/repo\"},\"data\":{}}\n\n")
          }
          return native.dispatch(request)
        }
      }
      server.start(InetAddress.getByName(LOOPBACK), 0)
      LinkFixture(server).use { fixture ->
        fixture.link.awaitHarness(TIMEOUT_MILLIS)
        val wrapper = SshDshClient(fixture.link)
        try {
          val received = withTimeout(TIMEOUT_MILLIS) { wrapper.events().take(2).toList() }
          assertEquals(listOf("evt_1", "evt_2"), received.map { it.id })
          assertEquals(1, fixture.factory.connects.get())
          assertEquals(1L, fixture.link.generationId)
          assertFalse(fixture.link.isClosed)
        } finally { wrapper.close() }
      }
    }
  }

  @Test fun waitingForANewerRuntimeGenerationTimesOutWithoutRecursiveReentry() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = openCodeDispatcher()
      server.start(InetAddress.getByName(LOOPBACK), 0)
      LinkFixture(server).use { fixture ->
        val current = fixture.link.awaitHarness(TIMEOUT_MILLIS)
        val failure = runCatching { fixture.link.awaitHarness(80, fixture.link.generationId) }.exceptionOrNull()
        assertTrue(failure is SshFailure)
        assertEquals(SshFailureKind.UNREACHABLE, (failure as SshFailure).kind)
        assertSame(current, fixture.link.awaitHarness(TIMEOUT_MILLIS))
      }
    }
  }

  private fun openCodeDispatcher(infoPid: Int = PID): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      if (request.getHeader("Authorization") != BASIC_AUTH) return MockResponse().setResponseCode(401)
      return when (request.method to request.path) {
        "GET" to "/api/info" -> json("""{"pid":$infoPid,"version":"$VERSION"}""")
        "GET" to "/api/project" -> json("""[{"id":"project_tunnel","worktree":"/repo"}]""")
        "GET" to "/openapi.json" -> json("""
          {
            "openapi": "3.1.0",
            "paths": {
              "/api/session/{id}": {
                "patch": {
                  "requestBody": {
                    "content": {
                      "application/json": {
                        "schema": {"type": "object", "properties": {"title": {"type": "string"}}}
                      }
                    }
                  }
                }
              }
            }
          }
        """.trimIndent())
        "PATCH" to "/api/session/ses_tunnel" -> json("{}")
        "GET" to "/api/session/pending" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        else -> MockResponse().setResponseCode(404)
      }
    }
  }

  private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

  private fun assertRequest(server: MockWebServer, method: String, path: String): RecordedRequest {
    val request = server.takeRequest(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    assertNotNull("Expected $method $path through the SSH tunnel", request)
    request!!
    assertEquals(method, request.method)
    assertEquals(path, request.path)
    assertEquals(BASIC_AUTH, request.getHeader("Authorization"))
    return request
  }

  private class LinkFixture(server: MockWebServer) : Closeable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    val steps = CopyOnWriteArrayList<SshLinkStep>()
    val transport = LoopbackTransport(server.port)
    val factory = PinnedFactory(transport)
    private val config = SshHostConfig("ssh-host", "SSH fixture", "ssh.example.test", user = "ssh-user",
      // OpenCode must ignore the saved host's DSH launch command even when one is present.
      launchCommand = "dsh web --must-not-run", hostKeyType = KEY.type, hostKeyFingerprint = KEY.fingerprint)
    private val profile = ServerProfile("ssh-opencode", "OpenCode", config.profileUrl(),
      username = "not-the-native-user", kind = ServerKind.OPENCODE, sshHostId = config.id,
      sshServicePath = REGISTRATION_PATH)
    val link = SshHostLink(scope, profile, config, SshSecrets(password = "ssh-fixture-password"), factory,
      onStatus = { steps += it.step })

    fun assertOnlyReadOnlyDiscovery() {
      assertEquals(listOf(SshHarnessProbe.probeScript(REGISTRATION_PATH)), transport.scripts.toList())
      assertEquals(listOf(20_000L), transport.execTimeouts.toList())
      transport.scripts.forEach { script ->
        assertTrue(script.contains("os.O_RDONLY"))
        listOf("nohup", "mkdir ", "chmod ", "os.kill", "subprocess", "kill ", "dsh web", "opencode serve")
          .forEach { forbidden -> assertFalse("Discovery executed a process mutation: $forbidden", script.contains(forbidden)) }
      }
    }

    override fun close() {
      link.close()
      scope.cancel()
      try {
        transport.close()
        transport.awaitWorkersStopped()
      } finally {
        runBlocking { withTimeout(TIMEOUT_MILLIS) { job.join() } }
      }
    }
  }

  private class PinnedFactory(private val transport: LoopbackTransport) : SshTransportFactory {
    val connects = AtomicInteger()
    val keyProbes = AtomicInteger()
    @Volatile var pinAccepted = false
      private set

    override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
      connects.incrementAndGet()
      pinAccepted = verifier.verify(KEY)
      check(pinAccepted) { "The saved SSH host key must be accepted" }
      return transport
    }

    override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey {
      keyProbes.incrementAndGet()
      error("A pinned host must not require discovery of its key")
    }
  }

  /** Emulates direct-tcpip by relaying the real forwarder's streams to MockWebServer sockets. */
  private class LoopbackTransport(private val serverPort: Int) : SshTransport {
    private val closed = AtomicBoolean()
    private val workers = Executors.newCachedThreadPool { task ->
      Thread(task, "lagoon-opencode-test-pump").apply { isDaemon = true }
    }
    val scripts = CopyOnWriteArrayList<String>()
    val execTimeouts = CopyOnWriteArrayList<Long>()
    val tunnels = CopyOnWriteArrayList<SocketTunnel>()
    val closes = AtomicInteger()
    override val isClosed: Boolean get() = closed.get()

    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
      check(!isClosed)
      scripts += script
      execTimeouts += timeoutMillis
      val raw = JSONObject().put("id", "external-opencode-generation").put("version", VERSION)
        .put("url", "http://$LOOPBACK:$serverPort/").put("pid", PID).put("password", PASSWORD).toString()
      val observation = JSONObject().put("state", "present").put("path", REGISTRATION_PATH)
        .put("rawBase64", Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8)))
      val envelope = JSONObject().put("python", true).put("opencode", observation)
        // A legacy DSH marker must not cause a DSH bootstrap for an OpenCode binding.
        .put("dshLegacy", JSONObject().put("state", "present").put("path", "/home/fixture/.dsh-app-remote"))
      return RemoteExecResult(0, "SSH fixture banner\nLAGOON_SSH_DISCOVERY $envelope\n", "")
    }

    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection = synchronized(this) {
      check(!isClosed)
      check(host == LOOPBACK && port == serverPort) { "Unexpected SSH destination: $host:$port" }
      val socket = Socket()
      try {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), TIMEOUT_MILLIS.toInt())
        val remoteInput = socket.getInputStream()
        val remoteOutput = socket.getOutputStream()
        SocketTunnel(host, port, socket, input, output).also { tunnel ->
          tunnels += tunnel
          workers.execute { tunnel.pump(input, remoteOutput) }
          workers.execute { tunnel.pump(remoteInput, output) }
        }
      } catch (error: Throwable) {
        runCatching { socket.close() }
        throw error
      }
    }

    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean =
      !isClosed && host == LOOPBACK && port == serverPort
    override fun ping(timeoutMillis: Long): Boolean = !isClosed

    override fun close() {
      synchronized(this) {
        if (closed.compareAndSet(false, true)) {
          closes.incrementAndGet()
          tunnels.forEach { it.close() }
          workers.shutdownNow()
        }
      }
    }

    fun awaitWorkersStopped() {
      assertTrue("Socket relay workers must stop after closing their streams",
        workers.awaitTermination(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }
  }

  private class SocketTunnel(
    val host: String,
    val port: Int,
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream
  ) : TunnelConnection {
    private val gate = Any()
    private var closed = false
    private val listeners = mutableListOf<() -> Unit>()
    override val isOpen: Boolean get() = synchronized(gate) { !closed }

    override fun onClosed(listener: () -> Unit) {
      val alreadyClosed = synchronized(gate) {
        if (closed) true else { listeners += listener; false }
      }
      if (alreadyClosed) listener()
    }

    fun pump(source: InputStream, destination: OutputStream) {
      try { source.copyTo(destination) } catch (_: Exception) {
        // Closing either endpoint is expected when the API, forwarder or transport is released.
      } finally { close() }
    }

    override fun close() {
      val callbacks = synchronized(gate) {
        if (closed) return
        closed = true
        listeners.toList().also { listeners.clear() }
      }
      runCatching { socket.close() }
      runCatching { input.close() }
      runCatching { output.close() }
      callbacks.forEach { it() }
    }
  }

  private companion object {
    const val LOOPBACK = "127.0.0.1"
    const val REGISTRATION_PATH = "/home/fixture/state with 'quote/opencode/service.json"
    const val VERSION = "2.2.6"
    const val PID = 4217
    const val PASSWORD = "open-code-fixture-password"
    const val TIMEOUT_MILLIS = 5_000L
    val BASIC_AUTH: String = Credentials.basic("opencode", PASSWORD)
    val KEY = PresentedSshHostKey("ssh-ed25519", "SHA256:opencode-link-fixture")
  }
}
