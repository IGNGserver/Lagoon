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
  private data class Active(
    val connection: RemoteConnection,
    val gateway: RemoteGateway,
    val capabilities: RemoteCapabilities,
    val nativeServices: RemoteNativeServices,
    val codex: RemoteCodexRegistry,
    val agy: RemoteAgyRegistry,
    val openCodeAcp: RemoteAgyRegistry
  ) {
    @Volatile var idleProbeRunning: Boolean = false
    val idleProbeStrikes = java.util.concurrent.atomic.AtomicInteger(0)
  }

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
  /** Lease a Codex carrier bound to the exact current authenticated connection. */
  internal suspend fun codexTransport(): CodexTransport {
    val current = ensure()
    if (!current.capabilities.supportsWorkspace("codex", "codex.appserver.v1"))
      throw RemoteFailureException(RemoteFailureKind.INCOMPATIBLE, "Host 未启用或不支持 Codex", retryable = false)
    return current.codex.acquire()
  }
  internal suspend fun agyTransport(): AgyTransport {
    val current = ensure()
    if (!current.capabilities.hasAgy) throw RemoteFailureException(RemoteFailureKind.INCOMPATIBLE,
      "Host 未启用支持原生 ACP 审批的 Antigravity，请升级并启用 Host 连接器", retryable = false)
    return current.agy.acquire()
  }

  internal suspend fun openCodeAcpTransport(): AgyTransport {
    val current = ensure()
    if (!current.capabilities.hasOpenCodeAcp) throw RemoteFailureException(RemoteFailureKind.INCOMPATIBLE,
      "Host 未启用 OpenCode ACP 连接器", retryable = false)
    return current.openCodeAcp.acquire()
  }

  suspend fun supportsSessionEvents(): Boolean = ensure().capabilities.hasSessionEvents

  suspend fun supportsAsyncCompaction(): Boolean = ensure().capabilities.hasAsyncCompaction

  suspend fun compactSessionAsync(sessionId: String) {
    if (sessionId.isBlank()) throw RemoteFailureException(RemoteFailureKind.REJECTED, "会话无效")
    val current = ensure()
    if (!current.capabilities.hasAsyncCompaction) {
      throw RemoteFailureException(RemoteFailureKind.INCOMPATIBLE, "此主机不支持异步压缩", retryable = false)
    }
    val requestId = java.util.UUID.randomUUID().toString()
    val submitted = current.connection.call(
      "harness.remote.compaction.submit",
      JSONObject().put("sessionId", sessionId).put("requestId", requestId),
      30_000
    ) as? JSONObject ?: throw RemoteFailureException(RemoteFailureKind.NETWORK, "异步压缩提交响应无效")
    val operationId = submitted.optString("operationId").takeIf { it.isNotBlank() }
      ?: throw RemoteFailureException(RemoteFailureKind.NETWORK, "异步压缩缺少操作 ID")
    try {
      var state = submitted.optString("state")
      var message = submitted.optString("message")
      while (true) {
        when (state) {
          "succeeded", "unconfirmed" -> return
          "failed", "cancelled", "interrupted" -> {
            throw RemoteFailureException(RemoteFailureKind.REJECTED, message.ifBlank { "会话压缩未完成（$state）" }, retryable = false)
          }
          "accepted", "running", "cancelling" -> Unit
          else -> throw RemoteFailureException(RemoteFailureKind.NETWORK, "异步压缩状态未知：$state")
        }
        kotlinx.coroutines.delay(COMPACTION_POLL_INTERVAL_MILLIS)
        val status = current.connection.call(
          "harness.remote.compaction.status",
          JSONObject().put("sessionId", sessionId).put("operationId", operationId),
          30_000
        ) as? JSONObject ?: throw RemoteFailureException(RemoteFailureKind.NETWORK, "异步压缩状态响应无效")
        state = status.optString("state")
        message = status.optString("message")
      }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
      kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        runCatching {
          current.connection.call(
            "harness.remote.compaction.cancel",
            JSONObject().put("sessionId", sessionId).put("operationId", operationId),
            5_000
          )
        }
      }
      throw cancel
    }
  }
  /** Synchronous UI projection only; actual terminal requests recheck their pinned carrier. */
  internal fun terminalAvailable(): Boolean = active?.let { !closed && !it.connection.isClosed && it.capabilities.hasTerminal } == true
  internal suspend fun terminalGateway(): RemoteGateway {
    val current = ensure()
    if (!current.capabilities.hasTerminal) throw IOException("Host 未声明 harness.terminal.v1；请在 Host 确认远程终端能力")
    return current.gateway
  }
  internal fun isCurrentGateway(gateway: RemoteGateway): Boolean = active?.let {
    !closed && !it.connection.isClosed && it.gateway === gateway
  } == true
  internal fun changesAvailable(): Boolean = active?.let { !closed && !it.connection.isClosed && it.capabilities.hasChanges } == true
  /** Capture the exact authenticated connection for a multi-file read, never retarget mid-result. */
  internal suspend fun changesReader(): suspend (String, String, Long, Int?) -> JSONObject {
    val current = ensure()
    if (!current.capabilities.hasChanges) throw ApiException(501, "Remote Host 未声明受限改动读取能力，请升级并启用 Host 连接器")
    return { kind, sessionId, seq, index ->
      check(!closed && !current.connection.isClosed && active === current) { "改动读取所属的加密连接已变化，请重新读取" }
      require(kind in setOf("summary", "diff") && sessionId.isNotBlank() && sessionId.length <= 512 && sessionId.none(Char::isISOControl) && seq in 1..9_007_199_254_740_991L)
      require(if (kind == "summary") index == null else index != null && index in 0..4999)
      val params = JSONObject().put("kind", kind).put("sessionId", sessionId).put("seq", seq)
      index?.let { params.put("index", it) }
      val response = current.connection.call("harness.changes.read", params) as? JSONObject
        ?: throw ApiException(502, "Remote 改动读取返回无效响应")
      check(!closed && !current.connection.isClosed && active === current) { "改动读取返回时连接已变化，结果已丢弃" }
      val status = response.opt("status") as? Int ?: throw ApiException(502, "Remote 改动读取状态无效")
      when (status) {
        404 -> throw ApiException(404, "该改动摘要或比较已不在 Host 进程中，未将缺失结果当作空改动")
        200 -> response.opt("value") as? JSONObject ?: throw ApiException(502, "Remote 改动读取缺少 JSON 对象")
        else -> throw ApiException(status, "Remote Host 拒绝读取改动（$status）")
      }
    }
  }

  internal suspend fun nativeServiceCatalog(): NativeServiceCatalog? {
    val current = ensure()
    if (!current.capabilities.hasNativeServices) return null
    return try { current.nativeServices.describe() }
    catch (error: NativeServiceUpgradeRequiredException) {
      throw RemoteFailureException(RemoteFailureKind.INCOMPATIBLE, error.message ?: "Remote Host 需要升级", retryable = false, cause = error)
    }
  }
  internal suspend fun nativeServiceTransport(serviceId: String): NativeHttpTransport {
    val current = ensure()
    if (!current.capabilities.hasNativeServices) throw NativeServiceUpgradeRequiredException()
    val catalog = current.nativeServices.describe()
    val descriptor = catalog.services.firstOrNull { it.id == serviceId }
      ?: throw NativeRemoteException("SERVICE_NOT_FOUND", "OpenCode 服务不存在")
    return current.nativeServices.transport(serviceId, descriptor.generation)
  }

  /**
   * 传输级 RPC：`harness.transport.describe` 和能力协商走的是连接上的方法名，
   * **不是** `harness.remote.call` 的网关端点。走 [gateway] 会被端点白名单和
   * `ns/method` 形式校验一起拒绝，所以延迟探测必须走这里。
   */
  suspend fun transportCall(method: String, params: JSONObject = JSONObject()): Any? =
    ensure().connection.call(method, params)

  private fun closeActiveBundle(bundle: Active) {
    bundle.openCodeAcp.close()
    bundle.agy.close()
    bundle.codex.close()
    bundle.nativeServices.close()
    bundle.gateway.close()
    bundle.connection.close()
  }

  private suspend fun ensure(): Active = mutex.withLock {
    if (closed) { publishClosed(); throw IOException("Remote 会话已关闭") }
    active?.takeUnless { it.connection.isClosed || it.idleProbeStrikes.get() >= 2 }?.let { current ->
      val idleMillis = System.currentTimeMillis() - current.connection.lastTrafficAt
      if (idleMillis <= IDLE_PROBE_AFTER_MILLIS) {
        current.idleProbeStrikes.set(0)
        return@withLock current
      }
      if (!current.idleProbeRunning && !closed && !current.connection.isClosed) {
        current.idleProbeRunning = true
        SharedHttp.ioScope.launch {
          try {
            val healthy = try {
              kotlinx.coroutines.withTimeout(IDLE_PROBE_TIMEOUT_MILLIS) {
                current.connection.call("harness.transport.describe", JSONObject(), IDLE_PROBE_TIMEOUT_MILLIS)
              }
              true
            } catch (cancel: kotlinx.coroutines.CancellationException) {
              throw cancel
            } catch (_: Exception) {
              false
            }
            if (healthy) {
              current.idleProbeStrikes.set(0)
            } else if (current.idleProbeStrikes.incrementAndGet() >= 2) {
              closeActiveBundle(current)
            }
          } finally {
            current.idleProbeRunning = false
          }
        }
      }
      return@withLock current
    }
    active?.let(::closeActiveBundle)
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

  private suspend fun refreshRejectedOrThrow(
    api: RemoteServerApi,
    rejectedToken: String,
    originalError: Throwable
  ): RemoteDeviceRecord {
    return try {
      devices.refreshAfterRejected(rejectedToken) { id, refresh -> api.refresh(id, refresh) }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
      throw cancel
    } catch (refreshError: RemoteServerException) {
      throw remoteServerFailure(refreshError)
    } catch (refreshError: Exception) {
      val message = refreshError.message?.takeIf { it.isNotBlank() }
        ?: originalError.message?.takeIf { it.isNotBlank() }
        ?: "DSH Remote 登录已失效，请重新登录"
      val requiresLogin = message.contains("重新登录") || message.contains("尚未注册") ||
        message.contains("已过期") || message.contains("设备资料损坏")
      throw RemoteFailureException(
        if (requiresLogin) RemoteFailureKind.AUTH_REQUIRED else RemoteFailureKind.NETWORK,
        message, retryable = !requiresLogin, cause = refreshError
      )
    }
  }

  private suspend fun connectLocked(attempt: Int): Active {
    val api = RemoteServerApi(address, allowCleartext)
    var device = try { devices.access { id, token -> api.refresh(id, token) } }
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
    var token = device.tokens?.accessToken ?: throw RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "Remote 设备尚未注册", retryable = false)
    var reactiveRefreshUsed = false
    statusFlow.value = statusFlow.value.copy(phase = RemotePhase.CONNECTING)
    val host = try {
      api.host(token, hostId)
    } catch (error: RemoteServerException) {
      if (!error.isRecoverableTokenRejection) throw remoteServerFailure(error)
      reactiveRefreshUsed = true
      device = refreshRejectedOrThrow(api, token, error)
      token = device.tokens?.accessToken ?: throw RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "Remote 设备尚未注册", retryable = false)
      try { api.host(token, hostId) }
      catch (retryError: RemoteServerException) { throw remoteServerFailure(retryError) }
    }
    val hostOnline = if (host.online) true else {
      runCatching { api.hostPresence(token, hostId) }.getOrNull() ?: false
    }
    if (!hostOnline) throw RemoteFailureException(RemoteFailureKind.OFFLINE, "主机现在不在线", retryable = true)
    val scope = RemotePeerScope(canonicalRemoteOrigin(address), account, hostId)
    val key = try { trust.verify(scope, host) }
    catch (error: RemoteTrustException) {
      throw if (error.changed) RemoteFailureException(RemoteFailureKind.TRUST_CHANGED, error.message ?: "主机身份指纹已变化", retryable = false, cause = error)
      else RemoteFailureException(RemoteFailureKind.TRUST_REQUIRED, error.message ?: "尚未确认主机指纹", retryable = false, cause = error)
    }
    var gateway: RemoteGateway? = null
    var nativeServices: RemoteNativeServices? = null
    var codex: RemoteCodexRegistry? = null
    var agy: RemoteAgyRegistry? = null
    var openCodeAcp: RemoteAgyRegistry? = null
    val dispatchEvent: (JSONObject) -> Unit = {
      if (it.optString("event").startsWith("codex.app.")) {
        (codex ?: throw IOException("Codex 事件早于能力协商")).event(it)
      } else if (it.optString("event").startsWith("agent.acp.")) {
        val currentAgy = agy
        val currentOpenCodeAcp = openCodeAcp
        if (currentAgy == null && currentOpenCodeAcp == null) throw IOException("ACP 事件早于能力协商")
        currentAgy?.event(it)
        currentOpenCodeAcp?.event(it)
      } else if (it.optString("event") == "harness.services.frame") {
        (nativeServices ?: throw IOException("原生服务事件早于能力协商")).event(it)
      } else (gateway ?: throw IOException("Remote 事件早于能力协商")).event(it)
    }
    statusFlow.value = statusFlow.value.copy(phase = RemotePhase.HANDSHAKING)
    var activeToken = token
    val connection = try {
      RemoteConnection.connect(address, device.identity, activeToken, host, key, allowCleartext, clientVersion, dispatchEvent)
    } catch (error: RemoteControlException) {
      if (reactiveRefreshUsed || error.code !in setOf("TOKEN_EXPIRED", "AUTH_INVALID")) {
        throw remoteControlFailure(error)
      }
      reactiveRefreshUsed = true
      device = refreshRejectedOrThrow(api, activeToken, error)
      activeToken = device.tokens?.accessToken ?: throw RemoteFailureException(RemoteFailureKind.AUTH_REQUIRED, "Remote 设备尚未注册", retryable = false)
      try {
        RemoteConnection.connect(address, device.identity, activeToken, host, key, allowCleartext, clientVersion, dispatchEvent)
      } catch (retryError: RemoteControlException) {
        throw remoteControlFailure(retryError)
      } catch (retryError: Exception) {
        throw RemoteFailureException(RemoteFailureKind.NETWORK, "没能建立加密中继：${detail(retryError)}", retryable = true, cause = retryError)
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
      val nextNative = RemoteNativeServices({ method, params -> connection.call(method, params) })
      nativeServices = nextNative
      val nextCodex = RemoteCodexRegistry("codex.appserver.transfer.v1" in capabilities.capabilities) { method, params -> connection.call(method, params) }
      codex = nextCodex
      val nextAgy = RemoteAgyRegistry(
        "agent.acp.transfer.v1" in capabilities.capabilities,
        "agent.acp.workspace-files.v1" in capabilities.capabilities,
        "agent.acp.terminal.v1" in capabilities.capabilities,
        backendId = "antigravity"
      ) { method, params -> connection.call(method, params) }
      val nextOpenCodeAcp = RemoteAgyRegistry(
        "agent.acp.transfer.v1" in capabilities.capabilities,
        "agent.acp.workspace-files.v1" in capabilities.capabilities,
        "agent.acp.terminal.v1" in capabilities.capabilities,
        backendId = "opencode"
      ) { method, params -> connection.call(method, params) }
      agy = nextAgy
      openCodeAcp = nextOpenCodeAcp
      Active(connection, nextGateway, capabilities, nextNative, nextCodex, nextAgy, nextOpenCodeAcp).also { current ->
        if (closed) { closeActiveBundle(current); throw IOException("Remote 会话已关闭") }
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
          val closeCause = connection.awaitClosed()
          nextOpenCodeAcp.close()
          nextAgy.close()
          nextCodex.close()
          nextNative.close()
          nextGateway.close()
          if (!closed && active?.connection === connection) {
            val snapshot = when {
              closeCause is RemoteControlException && closeCause.code in setOf("TOKEN_EXPIRED", "AUTH_INVALID") -> {
                val refreshed = runCatching {
                  devices.refreshAfterRejected(activeToken) { id, rt -> api.refresh(id, rt) }
                }.isSuccess
                if (refreshed) {
                  RemoteFailureSnapshot(RemoteFailureKind.NETWORK, "访问令牌已自动续期，正在重连", retryable = true, closeCause.code)
                } else {
                  RemoteFailureSnapshot(RemoteFailureKind.AUTH_REQUIRED, "DSH Remote 凭据已失效，请重新登录", retryable = false, closeCause.code)
                }
              }
              closeCause is RemoteControlException -> {
                val mapped = remoteControlFailure(closeCause)
                RemoteFailureSnapshot(mapped.kind, mapped.message ?: "加密中继已断开", mapped.retryable, closeCause.code)
              }
              else -> RemoteFailureSnapshot(RemoteFailureKind.NETWORK, "加密中继已断开", retryable = true)
            }
            statusFlow.value = statusFlow.value.copy(phase = RemotePhase.FAILED, readyAt = 0, lastError = snapshot)
          }
        }
      }
    } catch (error: Exception) { connection.close(); throw error }
  }

  private fun recordFailure(error: Throwable, attempt: Int) {
    if (statusFlow.value.generation != attempt) return
    val failure = error as? RemoteFailureException
      ?: (error as? RemoteServerException)?.let(::remoteServerFailure)
      ?: (error as? RemoteControlException)?.let(::remoteControlFailure)
    val kind = failure?.kind ?: RemoteFailureKind.NETWORK
    val retryable = failure?.retryable ?: true
    val message = failure?.message?.takeIf { it.isNotBlank() }
      ?: error.message?.takeIf { it.isNotBlank() }
      ?: error.javaClass.simpleName
    val code = (error as? RemoteRpcException)?.code ?: (error as? RemoteGatewayException)?.code
      ?: (error as? RemoteControlException)?.code
    statusFlow.value = statusFlow.value.copy(phase = if (closed) RemotePhase.CLOSED else RemotePhase.FAILED,
      readyAt = 0, lastError = RemoteFailureSnapshot(kind, message, retryable, code))
  }

  private companion object {
    /** Reuse one failed connect for this long instead of starting another handshake per caller. */
    const val FAILURE_REUSE_MILLIS = 2_000L
    const val IDLE_PROBE_AFTER_MILLIS = 45_000L
    const val IDLE_PROBE_TIMEOUT_MILLIS = 5_000L
    const val COMPACTION_POLL_INTERVAL_MILLIS = 1_000L
    const val RELAY_WORKSPACE_UPLOAD_LIMIT_BYTES = 20 * 1024 * 1024
    const val LEGACY_UPLOAD_CHUNK_BYTES = 65_536
    const val BINARY_UPLOAD_CHUNK_BYTES = 256 * 1024
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
    if (current.connection.transport.lowercase() !in setOf("lan", "p2p") && bytes.size > RELAY_WORKSPACE_UPLOAD_LIMIT_BYTES)
      throw RemoteFailureException(RemoteFailureKind.REJECTED, "经由 Relay 中继单次最多上传 20 MiB 文件")
    val binary = current.capabilities.supportsBinaryWorkspaceUpload
    val uploadId = java.util.UUID.randomUUID().toString()
    val scope = JSONObject().put("sessionId", sessionId).put("uploadId", uploadId)
    var committed = false
    try {
      val openParams = JSONObject(scope.toString()).put("name", name).put("totalBytes", bytes.size)
      if (binary) openParams.put("encoding", "binary-v1")
      val opened = current.connection.call("workspace.upload.open", openParams, 60_000) as? JSONObject
        ?: throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传打开响应无效")
      if (opened.optString("uploadId") != uploadId) throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传打开响应无效")
      if (binary && opened.optString("encoding") != "binary-v1")
        throw RemoteFailureException(RemoteFailureKind.NETWORK, "二进制上传协商失败")
      val maxChunkBytes = if (binary) BINARY_UPLOAD_CHUNK_BYTES else LEGACY_UPLOAD_CHUNK_BYTES
      val chunkBytes = opened.optInt("chunkBytes", 0)
      if (chunkBytes !in 1..maxChunkBytes) throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传块大小无效")
      var offset = 0
      while (offset < bytes.size) {
        val end = minOf(offset + chunkBytes, bytes.size)
        val chunk = bytes.copyOfRange(offset, end)
        val ack = if (binary) {
          current.connection.callBinaryUploadChunk(
            JSONObject(scope.toString()).put("offset", offset),
            chunk,
            60_000
          ) as? JSONObject
        } else {
          val data = java.util.Base64.getEncoder().encodeToString(chunk)
          current.connection.call(
            "workspace.upload.chunk",
            JSONObject(scope.toString()).put("offset", offset).put("data", data),
            60_000
          ) as? JSONObject
        } ?: throw RemoteFailureException(RemoteFailureKind.NETWORK, "上传响应无效")
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
    active?.let(::closeActiveBundle)
    active = null
  }
}
