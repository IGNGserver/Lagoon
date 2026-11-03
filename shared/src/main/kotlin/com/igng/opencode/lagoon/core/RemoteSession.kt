package com.igng.opencode.lagoon.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.io.IOException

/** Connection owner: reconnects only before a new operation, never replays an operation. */
internal class RemoteSession(
  private val address: String,
  private val allowCleartext: Boolean,
  private val hostId: String,
  private val devices: RemoteDeviceManager,
  private val trust: RemotePeerTrust,
  private val account: String
) : AutoCloseable {
  private val mutex = Mutex()
  @Volatile private var active: Active? = null
  @Volatile private var closed = false
  private data class Active(val connection: RemoteConnection, val gateway: RemoteGateway, val capabilities: RemoteCapabilities)

  suspend fun gateway(): RemoteGateway = ensure().gateway
  suspend fun capabilities(): RemoteCapabilities = ensure().capabilities

  private suspend fun ensure(): Active = mutex.withLock {
    if (closed) throw IOException("Remote 会话已关闭")
    active?.takeUnless { it.connection.isClosed }?.let { return@withLock it }
    active?.gateway?.close()
    active = null
    val api = RemoteServerApi(address, allowCleartext)
    val device = devices.access { id, token -> api.refresh(id, token) }
    val token = device.tokens?.accessToken ?: throw IOException("Remote 设备尚未注册")
    val host = api.host(token, hostId)
    if (!host.online) throw IOException("Remote Host 离线")
    val scope = RemotePeerScope(canonicalRemoteOrigin(address), account, hostId)
    val key = trust.verify(scope, host)
    var gateway: RemoteGateway? = null
    val connection = RemoteConnection.connect(address, device.identity, token, host, key, allowCleartext) {
      (gateway ?: throw IOException("Remote 事件早于能力协商")).event(it)
    }
    try {
      val capabilities = connection.capabilities()
      val nextGateway = RemoteGateway("harness.remote.stream.transfer.v1" in capabilities.capabilities) { method, params -> connection.call(method, params) }
      gateway = nextGateway
      Active(connection, nextGateway, capabilities).also { current ->
        if (closed) { nextGateway.close(); connection.close(); throw IOException("Remote 会话已关闭") }
        active = current
        SharedHttp.ioScope.launch { connection.awaitClosed(); nextGateway.close() }
      }
    } catch (error: Exception) { connection.close(); throw error }
  }

  override fun close() {
    closed = true
    active?.gateway?.close()
    active?.connection?.close()
    active = null
  }
}
