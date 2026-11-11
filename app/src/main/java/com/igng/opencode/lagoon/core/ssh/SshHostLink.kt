package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import okhttp3.Credentials
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import com.igng.opencode.lagoon.core.Diagnostics
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
import kotlinx.coroutines.flow.first
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
  val isClosed: Boolean get() = closed
  @Volatile private var transport: SshTransport? = null
  @Volatile private var endpoint: RemoteDshEndpoint? = null
  @Volatile private var openCodeEndpoint: OpenCodeSshRegistration? = null
  /** Browser cookie minted for the current remote dsh. Kept on the link, never in the server
   *  credential store: a new dsh process invalidates the launch token but this cookie still works. */
  @Volatile private var rememberedCookie: String = ""
  val remoteHome: String? get() = endpoint?.home
  val workingDirectory: String? get() = config.workingDirectory
  @Volatile private var forwarder: SshForwarder? = null
  /** Closing DSH drains unanswered waterfalls; retain that exact generation for new RPC sockets. */
  @Volatile private var drainingTransport: SshTransport? = null
  @Volatile private var drainingEndpoint: RemoteDshEndpoint? = null
  @Volatile private var client: HarnessClient? = null
  @Volatile private var contentWatching = false
  @Volatile private var visibleQuestionSession: String? = null
  @Volatile private var questionForeground = false
  @Volatile private var ready = CompletableDeferred<HarnessClient>()
  /** Bumped every time the authenticated DshApi is replaced, including a failed exchange. */
  private val generation = java.util.concurrent.atomic.AtomicLong()
  val generationId: Long get() = generation.get()
  /** Stable across DSH generations; mirror of the current client's workspace stream. */
  private val workspacePublisher = MutableSharedFlow<List<Project>>(replay = 1, extraBufferCapacity = 1)
  private var workspaceCollector: Job? = null
  @Volatile private var connectJob: Job? = null
  @Volatile private var keepAliveJob: Job? = null
  @Volatile private var attempt = 0

  /** Waits until this link has an authenticated DshApi; starts the link on first use.
   *  [sinceGeneration] waits for a client newer than that generation, so a caller that already holds
   *  a completed generation is not handed the same instance while a reconnect is in flight. */
  suspend fun awaitClient(timeoutMillis: Long = 20_000, sinceGeneration: Long = -1): DshApi =
    awaitHarness(timeoutMillis, sinceGeneration) as? DshApi
      ?: throw SshFailure(SshFailureKind.OTHER, "此 SSH binding 不是 DSH", retryable = false)

  suspend fun awaitHarness(timeoutMillis: Long = 20_000, sinceGeneration: Long = -1): HarnessClient {
    if (closed) throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)
    client?.takeIf { !it.isClosed && mutableStatus.value.isConnected && generation.get() > sinceGeneration }?.let { return it }
    val failed = mutableStatus.value
    if (failed.phase == SshLinkPhase.FAILED) {
      val error = failed.error
      if (error != null && !error.retryable) throw error
      retry()
    }
    start()
    return try {
      withTimeout(timeoutMillis) {
        while (true) {
          if (closed) throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)
          val pending = ready
          val api = pending.await()
          if (generation.get() > sinceGeneration && !api.isClosed && mutableStatus.value.isConnected) return@withTimeout api
          if (api.isClosed && mutableStatus.value.isConnected) {
            lost(SshFailure(SshFailureKind.DISCONNECTED, "Harness 客户端已关闭，正在重新附着"), transport)
            continue
          }
          // A completed ready from the previous generation must not recurse or spin. Await an
          // actual state transition under the original deadline before reading the new gate.
          mutableStatus.first { closed || ready !== pending || generation.get() > sinceGeneration ||
            it.phase == SshLinkPhase.FAILED }
          val failure = mutableStatus.value.error
          if (mutableStatus.value.phase == SshLinkPhase.FAILED && failure != null) throw failure
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
      }
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

  fun questionPresentation(sessionId: String?, foreground: Boolean) {
    visibleQuestionSession = sessionId
    questionForeground = foreground
    client?.questionPresentation(sessionId, foreground)
  }
  fun questionFocus(id: String, focused: Boolean) { client?.questionFocus(id, focused) }
  fun questionEngage(id: String) { client?.questionEngage(id) }
  fun questionTakeTime(id: String) { client?.questionTakeTime(id) }

  /** Stable across reconnects: the current DSH generation's workspace titles. */
  val workspaceUpdates: Flow<List<Project>> get() = workspacePublisher.asSharedFlow()

  val isOpenCode: Boolean get() = profile.kind == ServerKind.OPENCODE
  fun capabilities(): ApiCapabilities = client?.capabilities()
    ?: if (profile.kind == ServerKind.OPENCODE) ApiCapabilities.BASELINE else DshApi.TUNNEL_CAPABILITIES

  /** Starts connecting in the background; safe to call repeatedly. */
  fun start() {
    synchronized(this) {
      if (closed || mutableStatus.value.isConnected || connectJob?.isActive == true) return
      connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }
  }

  fun retry() {
    synchronized(this) {
      if (closed) return
      if (mutableStatus.value.phase == SshLinkPhase.FAILED || mutableStatus.value.phase == SshLinkPhase.IDLE) {
        attempt = 0
        if (ready.isCompleted) ready = CompletableDeferred()
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
        if (!current.ping(8_000) || current.isClosed) lost(SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接已断开"), current)
      }
    } else if (mutableStatus.value.phase == SshLinkPhase.FAILED && mutableStatus.value.error?.retryable != false) {
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
      if (profile.kind == ServerKind.OPENCODE) {
        attachOpenCode(nextTransport)
        return
      }
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
          val activeTransport = (if (closed) drainingTransport else transport)
            ?: throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接已断开")
          val activeEndpoint = (if (closed) drainingEndpoint else endpoint)
            ?: throw SshFailure(SshFailureKind.DISCONNECTED, "dsh web 尚未就绪")
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
      api.questionPresentation(visibleQuestionSession, questionForeground)
      val previousClient = synchronized(this) {
        val old = client
        client = api
        old
      }
      if (previousClient !== null && previousClient !== api) runCatching { previousClient.close() }
      workspaceCollector?.cancel()
      workspaceCollector = api.workspaceUpdates?.let { updates ->
        scope.launch {
          try {
            updates.collect { workspacePublisher.emit(it) }
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (error: Exception) {
            Diagnostics.warn("SshHostLink", "工作区流结束", error)
          }
        }
      }
      attempt = 0
      generation.incrementAndGet()
      publish(SshLinkPhase.CONNECTED, SshLinkStep.CONNECTED, "已连接 · SSH ${config.address}")
      ready.complete(api)
      startKeepAlive()
    } catch (error: Throwable) {
      runCatching { nextTransport.close() }
      if (transport === nextTransport) transport = null
      throw error
    }
  }

  /** Attach-only OpenCode: registration discovery never launches or terminates a process. */
  private suspend fun attachOpenCode(nextTransport: SshTransport) {
    publish(SshLinkPhase.CONNECTING, SshLinkStep.TUNNEL, "正在读取 OpenCode 服务注册", attempt = attempt)
    val registration = withContext(Dispatchers.IO) {
      SshHarnessProbe(nextTransport).openCodeRegistration(profile.sshServicePath)
    }
    forwarder?.close()
    val local = SshForwarder { input, output ->
      val active = transport ?: throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接已断开")
      active.openTunnel(registration.host, registration.port, input, output)
    }
    forwarder = local
    openCodeEndpoint = registration
    val runtime = profile.copy(url = "http://127.0.0.1:${local.port}", username = "opencode",
      allowCleartext = true, sshHostId = null, remoteHostId = null, remoteHostFingerprint = null)
    val native = NativeOkHttpTransport(NativeHttpEndpoint(runtime.url))
    val api = OpenCodeApi(runtime, ServerCredentials("opencode", registration.password.orEmpty()), transport = native)
    try {
      withTimeout(45_000) {
        val headers = registration.password?.let { mapOf("Authorization" to Credentials.basic("opencode", it)) }.orEmpty()
        val response = native.request("GET", "api/info", headers)
        val info = runCatching { JSONObject(response.body.toString(Charsets.UTF_8)) }.getOrNull()
        val status = registration.validateInfo(response.statusCode, info)
        if (response.statusCode == 503) throw SshFailure(SshFailureKind.UNRESPONSIVE, "OpenCode 服务正在启动或停止")
        if (response.statusCode in setOf(401, 403)) throw SshFailure(SshFailureKind.LOGIN, "OpenCode 服务认证失败", retryable = false)
        if (status.compatible != DiscoveryValue.YES || status.listening != DiscoveryValue.YES)
          throw SshFailure(SshFailureKind.OTHER, "OpenCode 注册与运行进程不匹配，请重新发现服务", retryable = false)
        // /api/info can be public. Verify a protected native route before declaring auth ready.
        api.projects()
        api.discoverCapabilities()
      }
      if (closed) throw SshFailure(SshFailureKind.DISCONNECTED, "SSH binding 已关闭", retryable = false)
      val previous = synchronized(this) { client.also { client = api } }
      previous?.close()
      generation.incrementAndGet()
      attempt = 0
      publish(SshLinkPhase.CONNECTED, SshLinkStep.CONNECTED, "OpenCode 已连接 · SSH ${config.address}")
      ready.complete(api)
      startKeepAlive()
    } catch (error: Throwable) {
      api.close()
      if (error is ApiException && error.status in setOf(401, 403))
        throw SshFailure(SshFailureKind.LOGIN, "OpenCode 服务认证失败，请重新发现注册文件", retryable = false)
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
    return DshApi(
      runtime,
      ServerCredentials("dsh", endpoint.token, rememberedCookie),
      hostHeader = endpoint.authority,
      onCookie = { value -> rememberedCookie = value }
    )
  }

  private fun startKeepAlive() {
    keepAliveJob?.cancel()
    keepAliveJob = scope.launch(Dispatchers.IO) {
      while (!closed && mutableStatus.value.isConnected) {
        delay(PING_INTERVAL_MILLIS)
        val active = transport ?: break
        if (active.isClosed || !active.ping(PING_TIMEOUT_MILLIS)) {
          lost(SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接没有响应，正在重连"), active)
          break
        }
        val openCode = openCodeEndpoint
        if (openCode != null && !active.probeTunnel(openCode.host, openCode.port, 8_000)) {
          lost(SshFailure(SshFailureKind.DISCONNECTED, "OpenCode 服务已停止，正在重新发现"), active)
          break
        }
        val dsh = endpoint
        if (dsh != null) {
          // SSH can stay up while the remote dsh web process dies. Probe its loopback port so the
          // bootstrap can restart it and hand out a new token before the next control-plane read.
          if (!active.probeTunnel("127.0.0.1", dsh.port, 8_000)) {
            lost(SshFailure(SshFailureKind.DSH_START, "远端的 dsh web 已停止，正在重启"), active)
            break
          }
        }
      }
    }
  }

  private fun lost(failure: SshFailure, expectedTransport: SshTransport? = null) {
    synchronized(this) {
      if (closed || mutableStatus.value.phase == SshLinkPhase.RECONNECTING || mutableStatus.value.phase == SshLinkPhase.FAILED) return
      if (expectedTransport != null && transport !== expectedTransport) return
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
      openCodeEndpoint = null
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
    openCodeEndpoint = null
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
    if (client is DshApi) {
      drainingTransport = transport
      drainingEndpoint = endpoint
    }
    closed = true
    connectJob?.cancel()
    keepAliveJob?.cancel()
    workspaceCollector?.cancel()
    workspaceCollector = null
    val pending = ready
    if (!pending.isCompleted) pending.completeExceptionally(SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已关闭", retryable = false))
    val closingClient = client
    val closingTransport = transport
    val closingForwarder = forwarder
    if (closingClient is DshApi) {
      drainingTransport = closingTransport
      drainingEndpoint = endpoint
    }
    client = null
    transport = null
    endpoint = null
    openCodeEndpoint = null
    forwarder = null
    runCatching { closingClient?.close() }
    if (closingClient is DshApi) SharedHttp.ioScope.launch {
      // attachWait/legacy waterfall handoffs need the SSH tunnel until the API's bounded drain ends.
      try { closingClient.awaitClosed() } finally {
        runCatching { closingForwarder?.close() }
        runCatching { closingTransport?.close() }
        drainingTransport = null
        drainingEndpoint = null
      }
    } else {
      runCatching { closingForwarder?.close() }
      runCatching { closingTransport?.close() }
    }
    publish(SshLinkPhase.IDLE, SshLinkStep.CONNECT, "")
  }

  private companion object {
    const val PING_INTERVAL_MILLIS = 15_000L
    const val PING_TIMEOUT_MILLIS = 10_000L
  }
}
