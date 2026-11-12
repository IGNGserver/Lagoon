package com.igng.opencode.lagoon.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteCodexOfficialSmokeTest {
  private class Store(private var record: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String) = record
    override fun save(slot: String, record: RemoteDeviceRecord) { this.record = record }
    override fun delete(slot: String) = Unit
  }
  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }
  private fun ProcessBuilder.isolate(base: File, binary: File) = apply {
    environment()["HOME"] = File(base, "home").absolutePath
    environment()["CODEX_HOME"] = File(base, "codex-home").absolutePath
    environment()["XDG_CONFIG_HOME"] = File(base, "xdg-config").absolutePath
    environment()["XDG_CACHE_HOME"] = File(base, "xdg-cache").absolutePath
    environment()["XDG_STATE_HOME"] = File(base, "xdg-state").absolutePath
    environment()["LAGOON_CODEX_E2E_BINARY"] = binary.absolutePath
    listOf("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL", "OPENAI_ORGANIZATION", "OPENAI_PROJECT").forEach { environment().remove(it) }
  }
  private inner class Node(private val bundle: File, private val base: File, binary: File) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val process = ProcessBuilder("node", File(bundle, "stdio-codex-host.mjs").absolutePath).isolate(base, binary).start()
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val seq = AtomicLong()
    val ready = CompletableDeferred<JSONObject>()
    @Volatile var publish: (String, JSONObject) -> Unit = { _, _ -> }
    init {
      scope.launch {
        try {
          BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
            lines.forEach { text ->
              val value = JSONObject(text)
              when (value.optString("type")) {
                "ready" -> ready.complete(value)
                "event" -> publish(value.getString("event"), value.getJSONObject("data"))
                "response", "error" -> pending.remove(value.getString("id"))?.complete(value)
                else -> ready.completeExceptionally(IllegalStateException("Host unavailable: ${value.optString("type")}"))
              }
            }
          }
        } finally {
          val failure = IOException("Codex test Host stopped")
          ready.completeExceptionally(failure)
          pending.values.forEach { it.completeExceptionally(failure) }
        }
      }
      scope.launch { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { } } }
    }
    suspend fun call(method: String, params: JSONObject): JSONObject {
      val id = "kt-${seq.incrementAndGet()}"
      val response = CompletableDeferred<JSONObject>(); pending[id] = response
      try {
        synchronized(writer) {
          writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString())
          writer.newLine(); writer.flush()
        }
        val result = withTimeout(20_000) { response.await() }
        if (result.optString("type") == "error") throw NativeRemoteException(result.optString("code"), result.optString("message"))
        return result.getJSONObject("result")
      } finally { pending.remove(id) }
    }
    override fun close() {
      runCatching { synchronized(writer) { writer.close() } }
      if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
      scope.cancel()
    }
  }
  @Test fun noiseCarriesActualBb33RouterAndIsolatedCodexWithoutModelTurn() = runBlocking {
    val bundleEnv = System.getenv("LAGOON_CODEX_E2E_ROOT")
    val binaryEnv = System.getenv("LAGOON_CODEX_E2E_BINARY")
    assumeTrue("Opt in with LAGOON_CODEX_E2E_ROOT and LAGOON_CODEX_E2E_BINARY", bundleEnv != null || binaryEnv != null)
    require(!bundleEnv.isNullOrBlank() && !binaryEnv.isNullOrBlank()) { "Both Codex smoke environment variables are required" }
    val scratch = File(System.getenv("BUILD_SCRATCH_ROOT") ?: File(System.getProperty("user.home"), ".build").path).canonicalFile
    val bundle = File(bundleEnv).canonicalFile
    require(bundle.toPath().startsWith(scratch.toPath()) && File(bundle, "codex-router.mjs").isFile && File(bundle, "stdio-codex-host.mjs").isFile) { "Build the pinned smoke fixture under BUILD_SCRATCH_ROOT first" }
    val binary = File(binaryEnv).canonicalFile
    require(binary.isFile && binary.canExecute()) { "LAGOON_CODEX_E2E_BINARY must be an executable file" }
    val parent = File(scratch, "Lagoon/codex-smoke").apply { mkdirs() }.canonicalFile
    require(parent.toPath().startsWith(scratch.toPath()) && parent != scratch)
    val base = Files.createTempDirectory(parent.toPath(), "case-").toFile()
    try {
      listOf("home", "codex-home", "workspace", "xdg-config", "xdg-cache", "xdg-state").forEach { check(File(base, it).mkdir()) }
      val login = ProcessBuilder(binary.absolutePath, "login", "--with-api-key").isolate(base, binary).redirectErrorStream(true).start()
      login.outputStream.bufferedWriter().use { it.write("sk-fixture-${UUID.randomUUID()}\n") }
      check(login.waitFor(20, TimeUnit.SECONDS)) { login.destroyForcibly(); "Isolated Codex login timed out" }
      check(login.exitValue() == 0) { "Isolated Codex login failed" }
      val workspace = File(base, "workspace").canonicalPath
      Node(bundle, base, binary).use { node ->
        assertEquals("bb33-codex-domain", withTimeout(20_000) { node.ready.await() }.getString("host"))
        FakeRemoteHost(capabilities = listOf("codex.appserver.v1", "codex.appserver.transfer.v1"),
          codexRpc = { method, params, _ -> runBlocking(Dispatchers.IO) { node.call(method, params) } }).use { host ->
          node.publish = host::publishNativeEvent
          host.start()
          val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
          val account = "stdio-user@example.test"
          val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), account, RemoteIdentity.generate(),
            RemoteTokenPair(token, Long.MAX_VALUE / 2, token, Long.MAX_VALUE / 2))
          val devices = RemoteDeviceManager(Store(record), "codex-${UUID.randomUUID()}", host.url, account)
          val session = RemoteSession(host.url, true, host.hostId, devices, RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account)
          try {
            val carrier = session.codexTransport()
            try {
              val accountState = carrier.call("account/read", JSONObject().put("refreshToken", false))
              assertEquals("apiKey", accountState.getJSONObject("account").getString("type"))
              assertTrue(carrier.call("model/list", JSONObject().put("limit", 1)).has("data"))
              val project = carrier.call("project/create", JSONObject().put("name", "isolated-noise-e2e")
                .put("roots", JSONArray().put(JSONObject().put("path", workspace)))
                .put("idempotencyKey", UUID.randomUUID().toString()))
              assertTrue(project.getJSONObject("project").getString("id").isNotBlank())
              val created = carrier.call("thread/start", JSONObject().put("cwd", workspace))
              val threadId = created.getJSONObject("thread").getString("id")
              carrier.subscribe(threadId)
              val next = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) { carrier.events().first { it.optString("method") == "thread/name/updated" } }
              }
              carrier.call("thread/name/set", JSONObject().put("threadId", threadId).put("name", "Noise interoperability"))
              assertEquals(threadId, next.await().getJSONObject("params").getString("threadId"))
              val history = carrier.call("dsh/sessionHistory", JSONObject().put("threadId", threadId).put("maxMessages", 20))
              assertEquals("codex:$threadId", history.getJSONObject("header").getString("id"))
              assertTrue(history.has("records"))
              assertTrue(synchronized(host.rpcCalls) { host.rpcCalls.any { it.optString("method") == "codex.app.transfer.commit" } })
              carrier.unsubscribe(threadId)
            } finally { carrier.close() }
          } finally { session.close() }
        }
      }
    } finally { base.deleteRecursively() }
    Unit
  }
}
