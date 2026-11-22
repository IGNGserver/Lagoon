package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import okhttp3.Credentials
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

// Covers SSH setup, bounded DSH probe/start, socket ownership proof and its 45s health check.
// This is an upper bound for one caller; callers may cancel without closing sibling leases.
internal const val HARNESS_START_BUDGET_MILLIS = 240_000L

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
  @Volatile private var endpoint: SshDshEndpoint? = null
  @Volatile private var openCodeEndpoint: OpenCodeSshRegistration? = null
  /** Browser cookie minted for the current remote dsh. Kept on the link, never in the server
   *  credential store: a new dsh process invalidates the launch token but this cookie still works. */
  @Volatile private var rememberedCookie: String = ""
  val remoteHome: String? get() = endpoint?.home
  val workingDirectory: String? get() = config.workingDirectory
  @Volatile private var forwarder: SshForwarder? = null
  /** The bootstrap of the current generation, reused by keepalive to detect a replaced process. */
  @Volatile private var bootstrap: SshDshBootstrap? = null
  /**
   * Connections for this binding only. The forwarder port is reused by the OS across generations,
   * and OkHttp does not compare the `SocketFactory` when deciding whether a pooled socket is
   * eligible, so a process-wide pool could serve a dead or foreign tunnel socket.
   */
  private val forwarderPool = okhttp3.ConnectionPool(16, 5, java.util.concurrent.TimeUnit.MINUTES)
  /** The SSH generation whose listener ownership was proved; later sockets on it skip the helper. */
  @Volatile private var provenTransport: SshTransport? = null
  /** Closing DSH drains unanswered waterfalls; retain that exact generation for new RPC sockets. */
  @Volatile private var drainingTransport: SshTransport? = null
  @Volatile private var drainingEndpoint: SshDshEndpoint? = null
  @Volatile private var client: HarnessClient? = null
  @Volatile private var contentWatching = false
  @Volatile private var visibleQuestionSession: String? = null
  @Volatile private var questionForeground = false
  @Volatile private var ready = CompletableDeferred<HarnessClient>()
  /** Wakes [connectLoop] immediately out of exponential backoff sleep on explicit retry or kick. */
  private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
  /** Bumped every time the authenticated DshApi is replaced, including a failed exchange. */
  private val generation = java.util.concurrent.atomic.AtomicLong()
  val generationId: Long get() = generation.get()
  /** Stable across DSH generations; mirror of the current client's workspace stream. */
  private val workspacePublisher = MutableSharedFlow<List<Project>>(replay = 1, extraBufferCapacity = 1)
  private var workspaceCollector: Job? = null
  @Volatile private var connectJob: Job? = null
  @Volatile private var keepAliveJob: Job? = null
  @Volatile private var attempt = 0
  /** First-ever attach failure, surfaced to readers instead of blocking the whole start budget. */
  @Volatile private var firstAttachFailure: SshFailure? = null

  /** Waits until this link has an authenticated DshApi; starts the link on first use.
   *  [sinceGeneration] waits for a client newer than that generation, so a caller that already holds
   *  a completed generation is not handed the same instance while a reconnect is in flight. */
  suspend fun awaitClient(timeoutMillis: Long = HARNESS_START_BUDGET_MILLIS, sinceGeneration: Long = -1): DshApi =
    awaitHarness(timeoutMillis, sinceGeneration) as? DshApi
      ?: throw SshFailure(SshFailureKind.OTHER, "此 SSH binding 不是 DSH", retryable = false)

  suspend fun awaitHarness(timeoutMillis: Long = HARNESS_START_BUDGET_MILLIS, sinceGeneration: Long = -1): HarnessClient {
    if (closed) throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)
    client?.takeIf { !it.isClosed && mutableStatus.value.isConnected && generation.get() > sinceGeneration }?.let { return it }
    // A binding that never connected reports the attempt's typed failure instead of holding the
    // reader for the whole 4-minute start budget. The background loop keeps retrying, and the next
    // read picks up the client as soon as the service answers.
    firstAttachFailure?.let { failure -> if (generation.get() == 0L && !closed) throw failure }
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
          val api = try {
            pending.await()
          } catch (error: SshFailure) {
            // A blip only failed the generation that was in flight. `lost()` installed a fresh
            // gate, so wait for it instead of surfacing a transient disconnect to every caller.
            if (!closed && error.kind == SshFailureKind.DISCONNECTED && error.retryable && ready !== pending) continue
            throw error
          }
          if (generation.get() > sinceGeneration && !api.isClosed && mutableStatus.value.isConnected) return@withTimeout api
          if (api.isClosed && mutableStatus.value.isConnected) {
            lost(SshFailure(SshFailureKind.DISCONNECTED, "Harness 客户端已关闭，正在重新附着"), transport)
            continue
          }
          // A completed ready from the previous generation must not recurse or spin. Await an
          // actual state transition under the original deadline before reading the new gate.
          mutableStatus.first { closed || ready !== pending || generation.get() > sinceGeneration ||
            it.phase == SshLinkPhase.FAILED || (generation.get() == 0L && firstAttachFailure != null) }
          val first = firstAttachFailure
          if (generation.get() == 0L && first != null) throw first
          val failure = mutableStatus.value.error
          if (mutableStatus.value.phase == SshLinkPhase.FAILED && failure != null) throw failure
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
      }
    } catch (timeout: TimeoutCancellationException) {
      val detail = when (mutableStatus.value.step) {
        SshLinkStep.START_DSH -> "远端 DSH 启动超时，请检查开发机上的服务状态"
        SshLinkStep.LOGIN -> "Harness 登录超时，请检查开发机上的登录状态"
        SshLinkStep.TUNNEL -> "SSH 服务安全检查或隧道连接超时"
        else -> "SSH 连接超时，请检查网络和主机状态"
      }
      throw SshFailure(SshFailureKind.UNREACHABLE, detail, retryable = true)
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

  val isOpenCode: Boolean get() = profile.protocol == HarnessProtocol.OPENCODE_NATIVE
  fun capabilities(): ApiCapabilities = client?.capabilities()
    ?: if (isOpenCode) ApiCapabilities.BASELINE else DshApi.TUNNEL_CAPABILITIES

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
      when (mutableStatus.value.phase) {
        SshLinkPhase.FAILED, SshLinkPhase.IDLE -> {
          attempt = 0
          firstAttachFailure = null
          if (ready.isCompleted) ready = CompletableDeferred()
          if (connectJob?.isActive != true) connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
          else wakeSignal.trySend(Unit)
        }
        SshLinkPhase.RECONNECTING -> {
          attempt = 0
          firstAttachFailure = null
          wakeSignal.trySend(Unit)
        }
        SshLinkPhase.CONNECTING, SshLinkPhase.CONNECTED -> Unit
      }
    }
  }

  /** Forces a fresh generation even while currently CONNECTED (e.g. after remote DSH upgrade). */
  fun forceReconnect(reason: String = "正在重新连接 SSH 服务") {
    synchronized(this) {
      if (closed) return
      val failure = SshFailure(SshFailureKind.DISCONNECTED, reason, retryable = true)
      attempt = 0
      firstAttachFailure = null
      publish(SshLinkPhase.RECONNECTING, SshLinkStep.CONNECT, reason, failure, 0)
      val pending = ready
      if (!pending.isCompleted) pending.completeExceptionally(failure)
      ready = CompletableDeferred()
      keepAliveJob?.cancel()
      keepAliveJob = null
      runCatching { client?.close() }
      client = null
      runCatching { transport?.close() }
      transport = null
      endpoint = null
      openCodeEndpoint = null
      provenTransport = null
      forwarder?.dropConnections()
      if (connectJob?.isActive == true) wakeSignal.trySend(Unit)
      else connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }
  }

  /** Network changed / app resumed: probe a connected link and immediately wake or retry failed/reconnecting ones. */
  fun kick() {
    if (closed) return
    val current = transport
    if (mutableStatus.value.isConnected && current != null) {
      scope.launch(Dispatchers.IO) {
        if (!current.ping(8_000) || current.isClosed) lost(SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接已断开"), current)
      }
    } else if (mutableStatus.value.phase == SshLinkPhase.RECONNECTING) {
      wakeSignal.trySend(Unit)
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
        if (generation.get() == 0L) {
          // A first-ever attach must not hold its reader for the whole start budget. Fail the
          // current gate now, keep retrying in the background, and let the next read pick up the
          // client as soon as the service answers.
          firstAttachFailure = failure
          val pending = ready
          if (!pending.isCompleted) {
            pending.completeExceptionally(failure)
            ready = CompletableDeferred()
          }
        }
        if (!failure.retryable) {
          fail(failure)
          return
        }
        publish(SshLinkPhase.RECONNECTING, mutableStatus.value.step, failure.display(), failure, attempt)
        val woken = withTimeoutOrNull(backoff) { wakeSignal.receive() } != null
        backoff = if (woken) 1_000L else (backoff * 2).coerceAtMost(30_000L)
      }
    }
  }

  private suspend fun connectOnce() {
    val pinned = config.hostKeyFingerprint?.trim().orEmpty()
    if (pinned.isEmpty()) {
      publish(SshLinkPhase.CONNECTING, SshLinkStep.VERIFY_HOST_KEY, "正在读取主机指纹", attempt = attempt)
      val key = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { transportFactory.probeHostKey(config) }
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
    val nextTransport = transportFactory.connectInterruptibly(config, secrets, verifier)
    if (closed) {
      nextTransport.close()
      throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)
    }
    transport = nextTransport
    try {
      if (isOpenCode) {
        attachOpenCode(nextTransport)
        return
      }
      publish(SshLinkPhase.CONNECTING, SshLinkStep.START_DSH, "正在检查远端的 dsh web", attempt = attempt)
      val bootstrap = SshDshBootstrap(nextTransport, config.launchCommand, config.workingDirectory).also { this.bootstrap = it }
      val nextEndpoint = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        bootstrap.ensureRunning { detail -> publish(SshLinkPhase.CONNECTING, SshLinkStep.START_DSH, detail, attempt = attempt) }
      }
      if (closed) throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已移除", retryable = false)

      publish(SshLinkPhase.CONNECTING, SshLinkStep.TUNNEL, "正在建立本地隧道", attempt = attempt)
      // The proof below opens the remote socket and sends one anonymous request to it, so a
      // separate ad-hoc HTTP/1.0 probe was both redundant and fragile: a just-started or busy dsh
      // could miss it and the whole generation was rejected before the real client ever tried.
      // A listener that is not reachable now surfaces as a typed TUNNEL failure instead.
      val dshProof = try {
        withContext(Dispatchers.IO) { SshVerifiedTunnel.verify(nextTransport, SshVerifiedTunnel.dsh(nextEndpoint)) }
      } catch (error: SshFailure) {
        if (error.kind == SshFailureKind.UNRESPONSIVE) throw SshFailure(
          SshFailureKind.TUNNEL,
          "无法建立到远端 dsh web 的隧道",
          "远端 127.0.0.1:${nextEndpoint.port} 未响应安全检查；dsh web 可能刚启动、已退出或拒绝 loopback 访问。",
          retryable = true
        )
        throw error
      }
      endpoint = nextEndpoint
      provenTransport = nextTransport
      if (dshProof == SshVerifiedTunnel.Proof.DEGRADED)
        Diagnostics.warn("SshHostLink", "远端缺少 python3 或 lsof，本次连接只按启动记录校验")
      if (forwarder == null) {
        forwarder = SshForwarder { input, output ->
          val activeTransport = (if (closed) drainingTransport else transport)
            ?: throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接已断开")
          val activeEndpoint = (if (closed) drainingEndpoint else endpoint)
            ?: throw SshFailure(SshFailureKind.DISCONNECTED, "dsh web 尚未就绪")
          if (activeTransport === provenTransport)
            SshVerifiedTunnel.openDirect(activeTransport, SshVerifiedTunnel.dsh(activeEndpoint), input, output)
          else SshVerifiedTunnel.open(activeTransport, SshVerifiedTunnel.dsh(activeEndpoint), input, output)
        }
      }

      publish(SshLinkPhase.CONNECTING, SshLinkStep.LOGIN, "正在登录 DSH", attempt = attempt)
      val api = createClient(nextEndpoint)
      try {
        // Exchanging the launch token is the login check; `health()` is a full `session/list` and
        // the first catalog read follows immediately, so it must not run here as well.
        withTimeout(45_000) { api.probeAuth() }
      } catch (error: Throwable) {
        runCatching { api.close() }
        throw dshLoginFailure(error)
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

  /**
   * Attach-only OpenCode: registration discovery never launches or terminates a process.
   *
   * The registration is the service's own statement of where it listens and who it is. A service
   * that restarted or was upgraded rewrites it, so a mismatch is retried once against a fresh read
   * instead of parking the binding on a stale pid. A registration that is present while nothing
   * answers is reported as "not running" before the tunnel proof would time out.
   */
  private suspend fun attachOpenCode(nextTransport: SshTransport) {
    publish(SshLinkPhase.CONNECTING, SshLinkStep.TUNNEL, "正在读取 OpenCode 服务注册", attempt = attempt)
    var observation = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
      SshHarnessProbe(nextTransport).discover(profile.sshServicePath).openCode
    }
    if (observation.listening == false) {
      throw SshFailure(SshFailureKind.UNRESPONSIVE,
        "OpenCode 服务没有在运行（注册可能已过期）；请在设备上运行 opencode service start", retryable = true)
    }
    var registration = observation.registration ?: throw SshFailure(SshFailureKind.OTHER,
      "OpenCode 注册文件不可用（${observation.issue.name.lowercase()}）",
      retryable = observation.state == SshDiscoveryState.UNKNOWN)
    var reRead = true
    while (true) {
      try {
        attachOpenCodeGeneration(nextTransport, registration)
        return
      } catch (stale: OpenCodeRegistrationStale) {
        // One fresh read per attach: the service may have restarted between discovery and attach.
        if (!reRead) throw stale.failure
        reRead = false
        publish(SshLinkPhase.CONNECTING, SshLinkStep.TUNNEL, "OpenCode 服务已重启，正在重新读取注册", attempt = attempt)
        observation = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
          SshHarnessProbe(nextTransport).discover(profile.sshServicePath).openCode
        }
        val fresh = observation.registration
        if (fresh == null || fresh.pid == registration.pid && fresh.port == registration.port) throw stale.failure
        registration = fresh
      }
    }
  }

  /** Signalled when the running service no longer matches the registration that was just read. */
  private class OpenCodeRegistrationStale(val failure: SshFailure) : Exception() {
    override fun fillInStackTrace(): Throwable = this
  }

  private suspend fun attachOpenCodeGeneration(nextTransport: SshTransport, registration: OpenCodeSshRegistration) {
    val target = SshVerifiedTunnel.openCode(registration)
    val proof = withContext(Dispatchers.IO) {
      try {
        SshVerifiedTunnel.verify(nextTransport, target)
      } catch (error: SshFailure) {
        // The registered pid no longer owns the socket: re-read before blaming the network.
        if (error.kind == SshFailureKind.UNRESPONSIVE) throw OpenCodeRegistrationStale(error)
        throw error
      }
    }
    // The registration carries a credential, so an unproved listener is never used. DSH may fall
    // back to its managed start record; an external OpenCode service has no such record.
    if (proof != SshVerifiedTunnel.Proof.VERIFIED) throw SshFailure(SshFailureKind.OTHER,
      "OpenCode 需要设备上的 Python 3（macOS 还需 lsof）才能验证服务身份，未发送任何凭据", retryable = false)
    forwarder?.close()
    val local = SshForwarder { input, output ->
      val active = transport ?: throw SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接已断开")
      // The generation was proved once above; later sockets reuse it instead of spawning a helper.
      SshVerifiedTunnel.openDirect(active, target, input, output)
    }
    forwarder = local
    openCodeEndpoint = registration
    val runtime = profile.copy(url = "http://127.0.0.1:${local.port}", username = "opencode", allowCleartext = true)
    val native = NativeOkHttpTransport(
      NativeHttpEndpoint(runtime.url),
      socketFactory = local.socketFactory,
      connectionPool = forwarderPool
    )
    val api = OpenCodeApi(runtime, ServerCredentials("opencode", registration.password.orEmpty()), transport = native)
    try {
      withTimeout(45_000) {
        val headers = registration.password?.let { mapOf("Authorization" to Credentials.basic("opencode", it, Charsets.UTF_8)) }.orEmpty()
        val response = native.request("GET", "api/info", headers)
        val info = runCatching { JSONObject(response.body.toString(Charsets.UTF_8)) }.getOrNull()
        val status = registration.validateInfo(response.statusCode, info)
        if (response.statusCode == 503) throw SshFailure(SshFailureKind.UNRESPONSIVE, "OpenCode 服务正在启动或停止")
        if (response.statusCode in setOf(401, 403)) throw SshFailure(SshFailureKind.LOGIN, "OpenCode 服务认证失败", retryable = false)
        if (status.compatible != DiscoveryValue.YES || status.listening != DiscoveryValue.YES)
          throw OpenCodeRegistrationStale(SshFailure(SshFailureKind.OTHER,
            "OpenCode 注册与运行进程不匹配，请重新发现服务", retryable = false))
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
      if (error is OpenCodeRegistrationStale) throw error
      if (error is ApiException && error.status in setOf(401, 403)) {
        throw OpenCodeRegistrationStale(SshFailure(SshFailureKind.LOGIN, "OpenCode 服务认证失败，请重新发现注册文件", retryable = false))
      }
      throw error
    }
  }

  private fun createClient(endpoint: SshDshEndpoint): DshApi {
    val local = forwarder ?: throw SshFailure(SshFailureKind.TUNNEL, "本地隧道尚未建立")
    val runtime = profile.copy(
      url = "http://127.0.0.1:${local.port}",
      username = "dsh",
      protocol = HarnessProtocol.DSH,
      allowCleartext = true
    )
    return DshApi(
      runtime,
      ServerCredentials("dsh", endpoint.token, rememberedCookie),
      hostHeader = endpoint.authority,
      socketFactory = local.socketFactory,
      // One pool per SSH binding: OkHttp's reuse key ignores the socket factory, so a shared pool
      // could hand this tunnel's authenticated socket to another loopback listener (and back).
      connectionPool = forwarderPool,
      // A cold tunnel can spend the whole first-frame window on the handshake before `open`.
      eventsReadyTimeoutMillis = 45_000L,
      onCookie = { value -> rememberedCookie = value },
      // The recorded process is replaced: drop this generation and let the bootstrap hand out a
      // fresh launch token. Keeping the dead token would 401 on every later call.
      onAuthTokenRotated = {
        lost(SshFailure(SshFailureKind.LOGIN, "远端 dsh web 已重启，正在重新读取启动令牌"))
      }
    )
  }

  private fun startKeepAlive() {
    keepAliveJob?.cancel()
    keepAliveJob = scope.launch(Dispatchers.IO) {
      var ticks = 0
      while (!closed && mutableStatus.value.isConnected) {
        delay(PING_INTERVAL_MILLIS)
        var active: SshTransport? = null
        try {
          active = transport ?: break
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
            // A restarted dsh can bind the same port; the port probe alone cannot tell. Once a
            // minute compare the recorded generation and re-read the launch token when it changed.
            ticks += 1
            if (ticks % GENERATION_CHECK_TICKS == 0 && active === transport) {
              val expected = dsh.pid
              val observed = runCatching { bootstrap?.probe() }.getOrNull()?.pid
              if (observed != null && expected != null && observed != expected) {
                lost(SshFailure(SshFailureKind.DSH_START, "远端 dsh web 已更换，正在重新读取启动令牌"), active)
                break
              }
            }
          }
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (error: Throwable) {
          // A close/reconnect can invalidate this generation between the liveness checks. Health
          // work must report "lost", never escape the supervisor as an uncaught process crash.
          Diagnostics.warn("SshHostLink", "SSH 保活检查失败", error)
          lost(SshFailure(SshFailureKind.DISCONNECTED, "SSH 保活检查失败，正在重连"), active)
          break
        }
      }
    }
  }

  /**
   * A login failure through the forwarder is either a credential problem or a tunnel that never
   * reached dsh web. Keep the two apart: "无法建立隧道" must name the transport, not send the user
   * looking for a token again.
   */
  private fun dshLoginFailure(error: Throwable): SshFailure {
    val chain = generateSequence(error) { it.cause }.toList()
    val text = chain.mapNotNull { it.message?.takeIf(String::isNotBlank) }.joinToString(" · ").take(600)
    val authFailure = chain.filterIsInstance<ApiException>().firstOrNull { it.status in setOf(401, 403) }
    if (authFailure != null) {
      // A rotated launch token is not a wrong credential: the owner already dropped this generation
      // and the same loop must re-read the token, not park the binding on "login failed".
      val rotated = authFailure.code == DshApi.AUTH_TOKEN_ROTATED || text.contains("重新获取启动令牌")
      return SshFailure(SshFailureKind.LOGIN, "DSH 登录失败", text, retryable = rotated)
    }
    val transportFailure = chain.any {
      it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
        it is java.net.NoRouteToHostException || it is java.net.UnknownHostException
    } || listOf("failed to connect", "connection refused", "connection reset", "timeout", "timed out")
      .any { text.contains(it, ignoreCase = true) }
    if (transportFailure)
      return SshFailure(SshFailureKind.TUNNEL, "无法建立到远端 dsh web 的隧道", text, retryable = true)
    return sshFailure(error, "DSH 登录失败")
  }

  private fun lost(failure: SshFailure, expectedTransport: SshTransport? = null) {
    synchronized(this) {
      if (closed || mutableStatus.value.phase == SshLinkPhase.RECONNECTING || mutableStatus.value.phase == SshLinkPhase.FAILED) return
      if (expectedTransport != null && transport !== expectedTransport) return
      publish(SshLinkPhase.RECONNECTING, mutableStatus.value.step, failure.display(), failure, attempt)
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
      provenTransport = null
      forwarder?.dropConnections()
      if (connectJob?.isActive != true) connectJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }
  }

  private fun fail(failure: SshFailure) {
    publish(SshLinkPhase.FAILED, mutableStatus.value.step, failure.display(), failure, attempt)
    val pending = ready
    if (!pending.isCompleted) pending.completeExceptionally(failure)
    keepAliveJob?.cancel(); keepAliveJob = null
    runCatching { client?.close() }
    client = null
    runCatching { transport?.close() }
    transport = null
    endpoint = null
    openCodeEndpoint = null
    provenTransport = null
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
    // The forwarded DshApi may still drain waterfall handoffs; keep this generation's tunnel.
    drainingTransport = transport.takeIf { client is DshApi }
    drainingEndpoint = endpoint.takeIf { client is DshApi }
    closed = true
    wakeSignal.close()
    connectJob?.cancel()
    keepAliveJob?.cancel()
    workspaceCollector?.cancel()
    workspaceCollector = null
    val pending = ready
    if (!pending.isCompleted) pending.completeExceptionally(SshFailure(SshFailureKind.DISCONNECTED, "SSH 主机已关闭", retryable = false))
    val closingClient = client
    val closingTransport = transport
    val closingForwarder = forwarder
    client = null
    transport = null
    endpoint = null
    openCodeEndpoint = null
    provenTransport = null
    bootstrap = null
    forwarder = null
    runCatching { closingClient?.close() }
    runCatching { forwarderPool.evictAll() }
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
    /** 15 s keepalive ticks: compare the recorded process generation about once a minute. */
    const val GENERATION_CHECK_TICKS = 4
  }
}
