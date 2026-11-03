package com.igng.opencode.lagoon.core

import org.json.JSONObject
import java.io.IOException

/** Authenticated Relay RPC connection. It has no implicit reconnect or mutation retry. */
internal class RemoteConnection private constructor(
  address: String,
  identity: RemoteIdentity,
  token: String,
  host: RemoteHost,
  trustedKey: String,
  allowCleartext: Boolean,
  onEvent: (JSONObject) -> Unit
) : AutoCloseable {
  private val relay = RemoteRelay(address, identity, token, host.deviceId, trustedKey, allowCleartext,
    onMessage = { bytes ->
      try { rpc.receive(JSONObject(String(bytes, Charsets.UTF_8))) }
      catch (_: Exception) { close() }
    }, onClose = { rpc.close(); closed.complete(Unit) })
  private val rpc: RemoteRpc = RemoteRpc(relay, onEvent = onEvent)
  private val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
  val isClosed: Boolean get() = closed.isCompleted

  suspend fun call(method: String, params: JSONObject, timeoutMillis: Long = 30_000): Any? = rpc.call(method, params, timeoutMillis)

  suspend fun capabilities(): RemoteCapabilities {
    val description = call("harness.transport.describe", JSONObject()) as? JSONObject
      ?: throw IOException("Host 返回无效能力描述")
    return RemoteCapabilities.parse(description)
  }

  override fun close() = rpc.close()
  suspend fun awaitClosed() = closed.await()

  companion object {
    suspend fun connect(address: String, identity: RemoteIdentity, token: String, host: RemoteHost,
      trustedKey: String, allowCleartext: Boolean = false, onEvent: (JSONObject) -> Unit = {}): RemoteConnection {
      require(host.publicKey == trustedKey) { "Host 公钥未获信任" }
      val connection = RemoteConnection(address, identity, token, host, trustedKey, allowCleartext, onEvent)
      try { connection.relay.connect() } catch (error: Exception) { connection.close(); throw error }
      return connection
    }
  }
}
