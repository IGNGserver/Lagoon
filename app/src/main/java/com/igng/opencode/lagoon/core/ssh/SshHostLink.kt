package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.DshApi
import com.igng.opencode.lagoon.core.DshAuthMode
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.Project
import com.igng.opencode.lagoon.core.RemoteDshEndpoint
import com.igng.opencode.lagoon.core.ServerCredentials
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshLinkPhase
import com.igng.opencode.lagoon.core.SshLinkStatus
import com.igng.opencode.lagoon.core.SshLinkStep
import com.igng.opencode.lagoon.core.SshSecrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * One SSH host's link: SSH handshake + host-key pinning, remote `dsh web` bootstrap, a stable local
 * forwarder, a tunneled [DshApi], keepalive and reconnect. The local forwarder port survives SSH
 * reconnects, so the DshApi base URL stays stable; when the remote dsh process is replaced, a new
 * DshApi generation is created with the new token and Host authority.
 */
internal class SshHostLink(
  private val scope: CoroutineScope,
  private val profile: ServerProfile,
  private val config: SshHostConfig,
  private val secrets: SshSecrets,
  private val transportFactory: SshTransportFactory = JschSshTransportFactory(),
  private val onStatus: (SshLinkStatus) -> Unit = {}
) : Closeable {
  private val mutableStatus = MutableStateFlow(SshLinkStatus())
  val status: StateFlow<SshLinkStatus> = mutableStatus

  @Volatile private var closed = false
  @Volatile private var transport: SshTransport? = null
  @Volatile private var endpoint: RemoteDshEndpoint? = null
  @Volatile private var forwarder: SshForwarder? = null
  @Volatile private var client: DshApi? = null
  @Volatile private var contentWatching = false
  @Volatile private var ready = CompletableDeferred<DshApi>()
  /** Stable across DSH generations; mirror of the current client's workspace stream. */
  private val workspacePublisher = MutableSharedFlow<List<Project>>(replay = 1, extraBufferCapacity = 1)
  private var workspaceCollector: Job? = null
  @Volatile private var connectJob: Job? = null
  @Volatile private var keepAliveJob: Job? = null
  @Volatile private var attempt = 0

  /** Waits until this link has an authenticated DshApi; starts the link on first use. */
  suspend fun awaitClient(timeoutMillis: Long = 20_000): DshApi {
    if (closed) throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)
    client?.takeIf { mutableStatus.value.isConnected }?.let { return it }
    val failed = mutableStatus.value
    if (failed.phase == SshLinkPhase.FAILED) {
      val error = failed.error
      if (error != null && !error.retryable) throw error
      retry()
    }
    start()
    val pending = ready
    return try {
      withTimeout(timeoutMillis) { pending.await() }
    } catch (timeout: TimeoutCancellationException) {
      throw SshFailure(SshFailureKind.UNREACHABLE, "SSH 连接超时，请检查网络和主机状态", retryable = true)
    } catch (cancel: CancellationException) {
      if (closed) throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)
      throw cancel
    }
  }

  /** Keeps the expensive content subscription off for background monitoring. */
  fun setContentWatching(enabled: Boolean) {
    contentWatching = enabled
    client?.setContentWatching(enabled)
  }

  /** Stable across reconnects: the current DSH generation's workspace titles. */
  val workspaceUpdates: Flow<List<Project>> get() = workspacePublisher.asSharedFlow()

  /** Starts connecting in the background; safe to call repeatedly. */
  fun start() {
    synchronized(this) {
      if (closed || mutableStatus.value.isConnected || connectJob?.isActive == true) return
      connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }
  }

  fun retry() {
    if (closed) return
    if (mutableStatus.value.phase == SshLinkPhase.FAILED || mutableStatus.value.phase == SshLinkPhase.IDLE) {
      attempt = 0
      ready = CompletableDeferred()
      synchronized(this) {
        if (connectJob?.isActive != true) connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
      }
    }
  }

  /** Network changed / app resumed: probe a connected link and reconnect a failed one. */
  fun kick() {
    if (closed) return
    val current = transport
    if (mutableStatus.value.isConnected && current != null) {
      scope.launch(Dispatchers.IO) {
        if (!current.ping(8_000) || current.isClosed) lost(SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接已断开"))
      }
    } else if (mutableStatus.value.phase == SshLinkPhase.FAILED) {
      retry()
    }
  }

  private suspend fun connectLoop() {
    var backoff = 1_000L
    while (!closed && scope.isActive) {
      attempt += 1
      publish(
        if (attempt == 1) SshLinkPhase.CONNECTING else SshLinkPhase.RECONNECTING,
        SshLinkStep.CONNECT, config.address, attempt = attempt
      )
      try {
        connectOnce()
        return
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Throwable) {
        if (closed) return
        val failure = sshFailure(error, "SSH 连接失败")
        if (!failure.retryable) {
          fail(failure)
          return
        }
        publish(SshLinkPhase.RECONNECTING, mutableStatus.value.step, failure.message, failure, attempt)
        delay(backoff)
        backoff = (backoff * 2).coerceAtMost(30_000L)
      }
    }
  }

  private suspend fun connectOnce() {
    val pinned = config.hostKeyFingerprint?.trim().orEmpty()
    if (pinned.isEmpty()) {
      publish(SshLinkPhase.CONNECTING, SshLinkStep.VERIFY_HOST_KEY, "正在读取主机指纹", attempt = attempt)
      val key = withContext(Dispatchers.IO) { transportFactory.probeHostKey(config) }
      throw SshFailure(
        SshFailureKind.HOST_KEY_UNKNOWN,
        "需要确认主机指纹后才能连接",
        "${key.shortType} ${key.fingerprint}",
        retryable = false,
        presentedKey = key
      )
    }
    publish(SshLinkPhase.CONNECTING, SshLinkStep.CONNECT, config.address, attempt = attempt)
    val verifier = SshHostKeyVerifier { presented ->
      if (presented.fingerprint == pinned) true
      else throw SshFailure(
        SshFailureKind.HOST_KEY_CHANGED,
        "主机指纹和上次不一致，已拒绝连接",
        "已记住：$pinned\n这次收到：${presented.fingerprint}（${presented.shortType}）",
        retryable = false,
        presentedKey = presented
      )
    }
    val nextTransport = withContext(Dispatchers.IO) { transportFactory.connect(config, secrets, verifier) }
    if (closed) {
      nextTransport.close()
      throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)
    }
    transport = nextTransport
    try {
      publish(SshLinkPhase.CONNECTING, SshLinkStep.START_DSH, "正在检查远端的 dsh web", attempt = attempt)
      val bootstrap = RemoteDshBootstrap(nextTransport, config.launchCommand, config.workingDirectory)
      val nextEndpoint = withContext(Dispatchers.IO) {
        bootstrap.ensureRunning { detail -> publish(SshLinkPhase.CONNECTING, SshLinkStep.START_DSH, detail, attempt = attempt) }
      }
      if (closed) throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)

      publish(SshLinkPhase.CONNECTING, SshLinkStep.TUNNEL, "正在建立本地隧道", attempt = attempt)
      val tunnelOpen = withContext(Dispatchers.IO) {
        nextTransport.probeTunnel("127.0.0.1", nextEndpoint.port, 10_000)
      }
      if (!tunnelOpen) throw SshFailure(SshFailureKind.TUNNEL, "无法建立到远端 dsh web 的隧道")
      endpoint = nextEndpoint
      if (forwarder == null) {
        forwarder = SshForwarder { input, output ->
          val activeTransport = transport ?: throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接已断开")
          val activeEndpoint = endpoint ?: throw SshFailure(SshFailureKind.DISCONNECTED, "dsh web 尚未就绪")
          activeTransport.openTunnel("127.0.0.1", activeEndpoint.port, input, output)
        }
      }

      publish(SshLinkPhase.CONNECTING, SshLinkStep.LOGIN, "正在登录 DSH", attempt = attempt)
      val api = createClient(nextEndpoint)
      try {
        withTimeout(45_000) { api.health() }
      } catch (error: Throwable) {
        runCatching { api.close() }
        throw sshFailure(error, "DSH 登录失败")
      }
      if (closed) {
        runCatching { api.close() }
        throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)
      }
      api.setContentWatching(contentWatching)
      client = api
      workspaceCollector?.cancel()
      workspaceCollector = api.workspaceUpdates?.let { updates ->
        scope.launch { updates.collect { workspacePublisher.emit(it) } }
      }
      attempt = 0
      publish(SshLinkPhase.CONNECTED, SshLinkStep.CONNECTED, "已连接 · SSH ${config.address}")
      ready.complete(api)
      startKeepAlive()
    } catch (error: Throwable) {
      runCatching { nextTransport.close() }
      if (transport === nextTransport) transport = null
      throw error
    }
  }

  private fun createClient(endpoint: RemoteDshEndpoint): DshApi {
    val local = forwarder ?: throw SshFailure(SshFailureKind.TUNNEL, "本地隧道尚未建立")
    val runtime = profile.copy(
      url = "http://127.0.0.1:${local.port}",
      username = "dsh",
      kind = com.igng.opencode.lagoon.core.ServerKind.DSH,
      dshAuth = DshAuthMode.TOKEN,
      allowCleartext = true,
      sshHostId = null,
      remoteHostId = null,
      remoteHostFingerprint = null
    )
    return DshApi(runtime, ServerCredentials("dsh", endpoint.token), hostHeader = endpoint.authority)
  }

  private fun startKeepAlive() {
    keepAliveJob?.cancel()
    keepAliveJob = scope.launch(Dispatchers.IO) {
      while (!closed && mutableStatus.value.isConnected) {
        delay(PING_INTERVAL_MILLIS)
        val active = transport ?: break
        if (active.isClosed || !active.ping(PING_TIMEOUT_MILLIS)) {
          lost(SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接没有响应，正在重连"))
          break
        }
        val dsh = endpoint
        if (dsh != null) {
          // SSH can stay up while the remote dsh web process dies. Probe its loopback port so the
          // bootstrap can restart it and hand out a new token before the next control-plane read.
          if (!active.probeTunnel("127.0.0.1", dsh.port, 8_000)) {
            lost(SshFailure(SshFailureKind.DSH_START, "远端的 dsh web 已停止，正在重启"))
            break
          }
        }
      }
    }
  }

  private fun lost(failure: SshFailure) {
    synchronized(this) {
      if (closed || mutableStatus.value.phase == SshLinkPhase.RECONNECTING || mutableStatus.value.phase == SshLinkPhase.FAILED) return
      publish(SshLinkPhase.RECONNECTING, mutableStatus.value.step, failure.message, failure, attempt)
      val pending = ready
      if (!pending.isCompleted) pending.completeExceptionally(failure)
      ready = CompletableDeferred()
      keepAliveJob?.cancel(); keepAliveJob = null
      runCatching { client?.close() }
      client = null
      runCatching { transport?.close() }
      transport = null
      endpoint = null
      forwarder?.dropConnections()
      if (connectJob?.isActive != true) connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }
  }

  private fun fail(failure: SshFailure) {
    publish(SshLinkPhase.FAILED, mutableStatus.value.step, failure.message, failure, attempt)
    val pending = ready
    if (!pending.isCompleted) pending.completeExceptionally(failure)
    keepAliveJob?.cancel(); keepAliveJob = null
    runCatching { client?.close() }
    client = null
    runCatching { transport?.close() }
    transport = null
    endpoint = null
    runCatching { forwarder?.close() }
    forwarder = null
  }

  private fun publish(
    phase: SshLinkPhase,
    step: SshLinkStep,
    detail: String,
    error: SshFailure? = null,
    attempt: Int = 0
  ) {
    val next = SshLinkStatus(phase, step, detail, error, attempt)
    mutableStatus.value = next
    onStatus(next)
  }

  override fun close() {
    if (closed) return
    closed = true
    connectJob?.cancel()
    keepAliveJob?.cancel()
    workspaceCollector?.cancel()
    workspaceCollector = null
    val pending = ready
    if (!pending.isCompleted) pending.completeExceptionally(SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已关闭", retryable = false))
    runCatching { client?.close() }
    client = null
    runCatching { transport?.close() }
    transport = null
    endpoint = null
    runCatching { forwarder?.close() }
    forwarder = null
    publish(SshLinkPhase.IDLE, SshLinkStep.CONNECT, "")
  }

  private companion object {
    const val PING_INTERVAL_MILLIS = 15_000L
    const val PING_TIMEOUT_MILLIS = 10_000L
  }
}
