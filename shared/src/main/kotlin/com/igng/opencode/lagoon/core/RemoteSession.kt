package com.igng.opencode.lagoon.core

import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
  private val account: String,
  private val clientVersion: String = ""
) : AutoCloseable {
  private val mutex = Mutex()
  @Volatile private var active: Active? = null
  @Volatile private var closed = false
  @Volatile private var generation = 0
  /** A failed connect is shared for a short window so N callers cannot start N handshakes. */
  @Volatile private var failedAt = 0L
  @Volatile private var lastFailure: RemoteFailureException? = null
  private val statusFlow = MutableStateFlow(RemoteConnectionStatus(hostId = hostId))
  private data class Active(val connection: RemoteConnection, val gateway: RemoteGateway, val capabilities: RemoteCapabilities)

  /** Observable connection state; UI and the doctor report read this instead of inferring from data. */
  internal val status: StateFlow<RemoteConnectionStatus> = statusFlow.asStateFlow()
  internal val hub: String get() = address
  internal val host: String get() = hostId
  internal val isClosed: Boolean get() = closed
  internal val accountId: String get() = account
  internal fun credentials(): RemoteCredentialSummary = devices.status()

  /** Doctor snapshot of the current gateway budget; null before the first connection. */
  internal fun streamStats(): RemoteStreamStats? = active?.let { current ->
    RemoteStreamStats(current.gateway.activeStreamCount, current.gateway.maxStreams,
      current.gateway.streamCounts(), current.gateway.lastError)
  }

  suspend fun gateway(): RemoteGateway = ensure().gateway
  suspend fun capabilities(): RemoteCapabilities = ensure().capabilities

  private suspend fun ensure(): Active = mutex.withLock {
    if (closed) { publishClosed(); throw IOException("Remote 会话已关闭") }
    active?.takeUnless { it.connection.isClosed }?.let { return@withLock it }
    active?.gateway?.close()
    active = null
    val recent = lastFailure
    if (recent != null && System.currentTimeMillis() - failedAt < FAILURE_REUSE_MILLIS) throw recent
    val attempt = ++generation
    statusFlow.value = statusFlow.value.copy(phase = RemotePhase.CREDENTIALS, generation = attempt,
      startedAt = System.currentTimeMillis(), readyAt = 0, lastError = null, capabilities = emptySet())
    return try {
      connectLocked(attempt)
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (error: Throwable) {
      recordFailure(error, attempt)
      (error as? RemoteFailureException)?.let { lastFailure = it; failedAt = System.currentTimeMillis() }
      throw error
    }
  }

  private suspend fun connectLocked(attempt: Int): Active {
    val api = RemoteServerApi(address, allowCleartext)
    val device = try { devices.access { id, token -> api.refresh(id, token) } }
    catch (error: RemoteFailureException) { throw error }
    catch (error: RemoteServerException) { throw remoteServerFailure(error) }
    catch (error: Exception) {
      // 保留自愈层给出的原因，并区分“需要重新登录”和“刷新暂时失败”：后者不能把用户永久推去重登。
      val message = error.message?.takeIf { it.isNotBlank() } ?: "DSH Remote 登录已失效，请重新登录"
      val requiresLogin = message.contains("重新登录") || message.contains("尚未注册") ||
        message.contains("已过期") || message.contains("设备资料损坏")
      throw RemoteFailureException(
        if (requiresLogin) RemoteFailureKind.AUTH_REQUIRED else RemoteFailureKind.NETWORK,
        message, retryable = !requiresLogin, cause = error)
    }
    val token = device.tokens?.accessToken ?: throw RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "Remote 设备尚未注册", retryable = false)
    statusFlow.value = statusFlow.value.copy(phase = RemotePhase.CONNECTING)
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
    statusFlow.value = statusFlow.value.copy(phase = RemotePhase.HANDSHAKING)
    val connection = try {
      RemoteConnection.connect(address, device.identity, token, host, key, allowCleartext, clientVersion) {
        (gateway ?: throw IOException("Remote 事件早于能力协商")).event(it)
      }
    } catch (error: Exception) {
      throw RemoteFailureException(RemoteFailureKind.NETWORK, "没能建立加密中继：${detail(error)}", retryable = true, cause = error)
    }
    return try {
      val capabilities = try { connection.capabilities() }
      catch (error: Exception) {
        throw RemoteFailureException(RemoteFailureKind.INCOMPATIBLE,
          "主机或远程插件版本不兼容：${detail(error)}", retryable = false, cause = error)
      }
      val nextGateway = RemoteGateway("harness.remote.stream.transfer.v1" in capabilities.capabilities) { method, params -> connection.call(method, params) }
      gateway = nextGateway
      Active(connection, nextGateway, capabilities).also { current ->
        if (closed) { nextGateway.close(); connection.close(); throw IOException("Remote 会话已关闭") }
        active = current
        lastFailure = null
        failedAt = 0L
        statusFlow.value = statusFlow.value.copy(phase = RemotePhase.READY, generation = attempt,
          readyAt = System.currentTimeMillis(), hostId = host.deviceId, hostName = host.name,
          serverVersion = connection.serverVersion, pluginVersion = host.clientVersion,
          harnessVersion = host.harnessVersion, transport = connection.transport,
          relayAssignment = connection.relayAttached, capabilities = capabilities.capabilities,
          lastError = null)
        SharedHttp.ioScope.launch {
          connection.awaitClosed()
          nextGateway.close()
          if (!closed && active?.connection === connection) {
            statusFlow.value = statusFlow.value.copy(phase = RemotePhase.FAILED, readyAt = 0,
              lastError = RemoteFailureSnapshot(RemoteFailureKind.NETWORK, "加密中继已断开", retryable = true))
          }
        }
      }
    } catch (error: Exception) { connection.close(); throw error }
  }

  private fun recordFailure(error: Throwable, attempt: Int) {
    if (statusFlow.value.generation != attempt) return
    val failure = error as? RemoteFailureException
      ?: (error as? RemoteServerException)?.let(::remoteServerFailure)
    val kind = failure?.kind ?: RemoteFailureKind.NETWORK
    val retryable = failure?.retryable ?: true
    val message = failure?.message?.takeIf { it.isNotBlank() }
      ?: error.message?.takeIf { it.isNotBlank() }
      ?: error.javaClass.simpleName
    val code = (error as? RemoteRpcException)?.code ?: (error as? RemoteGatewayException)?.code
    statusFlow.value = statusFlow.value.copy(phase = if (closed) RemotePhase.CLOSED else RemotePhase.FAILED,
      readyAt = 0, lastError = RemoteFailureSnapshot(kind, message, retryable, code))
  }

  private companion object {
    /** Reuse one failed connect for this long instead of starting another handshake per caller. */
    const val FAILURE_REUSE_MILLIS = 2_000L
  }

  private fun publishClosed() {
    if (statusFlow.value.phase != RemotePhase.CLOSED)
      statusFlow.value = statusFlow.value.copy(phase = RemotePhase.CLOSED, readyAt = 0)
  }

  /** 把底层原因压成一句可读文案：链路故障最怕只剩一句“连接失败”。 */
  private fun detail(error: Throwable): String {
    val parts = generateSequence(error) { it.cause }.mapNotNull { it.message?.takeIf(String::isNotBlank) }.distinct().take(3).toList()
    return parts.joinToString(" / ").ifBlank { error.javaClass.simpleName }
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
    publishClosed()
    active?.gateway?.close()
    active?.connection?.close()
    active = null
  }
}
