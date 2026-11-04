package com.igng.opencode.lagoon.core

import org.json.JSONObject
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
    val device = try { devices.access { id, token -> api.refresh(id, token) } }
    catch (error: RemoteFailureException) { throw error }
    catch (error: RemoteServerException) { throw remoteServerFailure(error) }
    catch (error: Exception) {
      throw RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "DSH Remote 登录已失效，请重新登录", retryable = false, cause = error)
    }
    val token = device.tokens?.accessToken ?: throw RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "Remote 设备尚未注册", retryable = false)
    val host = try { api.host(token, hostId) }
    catch (error: RemoteServerException) { throw remoteServerFailure(error) }
    if (!host.online) throw RemoteFailureException(RemoteFailureKind.OFFLINE, "主机现在不在线", retryable = true)
    val scope = RemotePeerScope(canonicalRemoteOrigin(address), account, hostId)
    val key = try { trust.verify(scope, host) }
    catch (error: RemoteTrustException) {
      throw if (error.changed) RemoteFailureException(RemoteFailureKind.TRUST_CHANGED, error.message ?: "主机身份指纹已变化", retryable = false, cause = error)
      else RemoteFailureException(RemoteFailureKind.TRUST_REQUIRED, error.message ?: "尚未确认主机指纹", retryable = false, cause = error)
    }
    var gateway: RemoteGateway? = null
    val connection = try {
      RemoteConnection.connect(address, device.identity, token, host, key, allowCleartext) {
        (gateway ?: throw IOException("Remote 事件早于能力协商")).event(it)
      }
    } catch (error: Exception) {
      throw RemoteFailureException(RemoteFailureKind.NETWORK, "没能建立加密中继", retryable = true, cause = error)
    }
    try {
      val capabilities = try { connection.capabilities() }
      catch (error: Exception) {
        throw RemoteFailureException(RemoteFailureKind.INCOMPATIBLE, "主机或远程插件版本不兼容", retryable = false, cause = error)
      }
      val nextGateway = RemoteGateway("harness.remote.stream.transfer.v1" in capabilities.capabilities) { method, params -> connection.call(method, params) }
      gateway = nextGateway
      Active(connection, nextGateway, capabilities).also { current ->
        if (closed) { nextGateway.close(); connection.close(); throw IOException("Remote 会话已关闭") }
        active = current
        SharedHttp.ioScope.launch { connection.awaitClosed(); nextGateway.close() }
      }
    } catch (error: Exception) { connection.close(); throw error }
  }

  /** Top-level workspace.upload.* RPCs; not Gateway endpoints and never wrapped in harness.remote.call. */
  suspend fun uploadWorkspaceFile(sessionId: String, bytes: ByteArray, name: String): RemoteUploadedFile {
    if (sessionId.isBlank() || sessionId.length > 512) throw RemoteFailureException(RemoteFailureKind.REJECTED, "工作区上传的会话无效")
    if (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 220 || name == "." || name == ".." ||
      name.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) throw RemoteFailureException(RemoteFailureKind.REJECTED, "文件名无效")
    if (bytes.size > 1024 * 1024 * 1024) throw RemoteFailureException(RemoteFailureKind.REJECTED, "文件超过 1 GiB 协议上限")
    val current = ensure()
    if (!current.capabilities.supportsWorkspaceUpload)
      throw RemoteFailureException(RemoteFailureKind.INCOMPATIBLE, "此主机的远程插件不支持上传文件")
    val uploadId = java.util.UUID.randomUUID().toString()
    val scope = JSONObject().put("sessionId", sessionId).put("uploadId", uploadId)
    var committed = false
    try {
      val opened = current.connection.call("workspace.upload.open",
        JSONObject(scope.toString()).put("name", name).put("totalBytes", bytes.size), 60_000) as? JSONObject
        ?: throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传打开响应无效")
      if (opened.optString("uploadId") != uploadId) throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传打开响应无效")
      val chunkBytes = opened.optInt("chunkBytes", 0)
      if (chunkBytes !in 1..65536) throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传块大小无效")
      var offset = 0
      while (offset < bytes.size) {
        val end = minOf(offset + chunkBytes, bytes.size)
        val data = java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))
        val ack = current.connection.call("workspace.upload.chunk",
          JSONObject(scope.toString()).put("offset", offset).put("data", data), 60_000) as? JSONObject
          ?: throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传响应无效")
        if (ack.optString("uploadId") != uploadId || ack.optInt("receivedBytes", -1) != end)
          throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传确认不一致")
        offset = end
      }
      val result = current.connection.call("workspace.upload.commit", JSONObject(scope.toString()), 60_000) as? JSONObject
        ?: throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传提交响应无效")
      val finalName = result.optString("name")
      if (finalName.isBlank() || result.optString("path") != "./$finalName" || result.optLong("bytes", -1) != bytes.size.toLong())
        throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传提交结果无效")
      committed = true
      return RemoteUploadedFile(finalName, "./$finalName", bytes.size.toLong())
    } catch (error: RemoteFailureException) {
      throw error
    } catch (error: Exception) {
      throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传文件失败", retryable = false, cause = error)
    } finally {
      if (!committed) {
        runCatching { current.connection.call("workspace.upload.abort", JSONObject(scope.toString()), 5_000) }
      }
    }
  }

  override fun close() {
    closed = true
    active?.gateway?.close()
    active?.connection?.close()
    active = null
  }
}
