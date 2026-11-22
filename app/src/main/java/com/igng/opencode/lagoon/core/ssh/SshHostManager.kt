package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.AgyApi
import com.igng.opencode.lagoon.core.CodexApi
import com.igng.opencode.lagoon.core.DeviceStore
import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.DiscoveryCompleteness
import com.igng.opencode.lagoon.core.GuardedHarnessClient
import com.igng.opencode.lagoon.core.HarnessClient
import com.igng.opencode.lagoon.core.HarnessConfig
import com.igng.opencode.lagoon.core.HarnessLeaseAccess
import com.igng.opencode.lagoon.core.HarnessProtocol
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshDiscoveryIssue
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHarnessDiscoveryResult
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshKeyStore
import com.igng.opencode.lagoon.core.SshLinkPhase
import com.igng.opencode.lagoon.core.SshLinkStatus
import com.igng.opencode.lagoon.core.SshPublicKeyFormat
import com.igng.opencode.lagoon.core.SshSecrets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/**
 * 进程级 SSH 运行时管理器：统一管理全部 5 种 Harness 协议的连接与底层物理 SSH 连接池。
 *
 * - 隧道协议（DSH / OpenCode Native）由 [SshHostLink] 维护本地前向隧道与重连；
 * - Stdio RPC 协议（Codex / Antigravity ACP / OpenCode ACP）由 [SshStdioRpcTransport] 维护；
 * - 同一台 [SshDevice] 上的多个 Harness 共享一条底层 SSH 连接（按 `transportKey` 复用），
 *   修改单个 Harness 的专属配置（如 DSH 启动命令）只重建该 Harness，绝不踢掉同设备上其他会话。
 */
internal class SshHostManager(
  private val store: DeviceStore,
  private val transportFactory: SshTransportFactory = JschSshTransportFactory(),
  private val keyStore: SshKeyStore? = null
) {
  private sealed class SharedRuntime(val deviceId: String, val harnessId: String) {
    var refs: Int = 0
    abstract val isClosed: Boolean
    abstract val status: StateFlow<SshLinkStatus>
    abstract fun retry()
    abstract fun kick()
    abstract fun forceReconnect(reason: String)
    abstract fun close()
  }

  private class TunnelRuntime(
    deviceId: String,
    harnessId: String,
    val link: SshHostLink
  ) : SharedRuntime(deviceId, harnessId) {
    override val isClosed: Boolean get() = link.isClosed
    override val status: StateFlow<SshLinkStatus> get() = link.status
    override fun retry() = link.retry()
    override fun kick() = link.kick()
    override fun forceReconnect(reason: String) = link.forceReconnect(reason)
    override fun close() = link.close()
  }

  private class StdioRuntime(
    deviceId: String,
    harnessId: String,
    val client: HarnessClient,
    override val status: StateFlow<SshLinkStatus>
  ) : SharedRuntime(deviceId, harnessId) {
    override val isClosed: Boolean get() = client.isClosed
    override fun retry() { /* stdio transports are lazy: callers evict closed runtimes so next call reconnects */ }
    override fun kick() { /* handled in manager.kickAll() by evicting closed runtimes */ }
    override fun forceReconnect(reason: String) = client.close()
    override fun close() = client.close()
  }

  private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
      if (error !is CancellationException) Diagnostics.warn("SshHostManager", "SSH 后台任务异常", error)
    }
  )

  private val lock = Any()
  private val runtimes = HashMap<String, SharedRuntime>()
  private val mutableStatusVersion = MutableStateFlow(0L)
  /** Bumped whenever any Harness link status transitions or opens/closes. */
  val statusVersion: StateFlow<Long> = mutableStatusVersion.asStateFlow()

  private val transports = SshTransportPool(transportFactory) { cfg ->
    cfg.keyId?.let { keyStore?.credentialFingerprint(it) }.orEmpty()
  }

  private fun notifyStatusChanged() {
    mutableStatusVersion.value = mutableStatusVersion.value + 1L
  }

  fun client(profile: ServerProfile): HarnessClient {
    val deviceId = profile.deviceId?.takeIf(String::isNotBlank)
      ?: throw SshFailure(SshFailureKind.OTHER, "不是 SSH 设备资料", retryable = false)
    val device = store.findDevice(deviceId)
      ?: throw SshFailure(SshFailureKind.OTHER, "SSH 设备配置已删除", retryable = false)
    val harness = device.harnesses.firstOrNull { it.id == profile.id }
    // Typed, not IllegalArgumentException: callers (overview/notification) only recognize SshFailure.
    if (profile.url != device.profileUrl() || profile.username != device.user) {
      throw SshFailure(SshFailureKind.OTHER, "SSH 保存配置已变化，请重新保存设备", retryable = false)
    }
    val dshCfg = (harness?.config as? HarnessConfig.Dsh) ?: profile.dshConfig
    val config = device.toHostConfig(dshCfg)
    val secrets = store.secrets(device.id)

    if (profile.protocol.isStdioRpc) {
      val entry = synchronized(lock) {
        val current = (runtimes[profile.id] as? StdioRuntime)?.takeUnless { it.isClosed }
        val active = current ?: createStdioRuntime(device.id, profile, config, secrets).also {
          runtimes[profile.id] = it
        }
        active.refs++
        active
      }
      notifyStatusChanged()
      return GuardedHarnessClient(object : HarnessLeaseAccess {
        private val released = AtomicBoolean(false)
        override val isClosed: Boolean get() = released.get() || entry.isClosed
        private fun checkOpen() { check(!isClosed) { "SSH Harness 租约已关闭" } }
        override fun <T> use(block: (HarnessClient) -> T): T { checkOpen(); return block(entry.client).also { checkOpen() } }
        override suspend fun <T> useSuspending(block: suspend (HarnessClient) -> T): T {
          checkOpen(); return block(entry.client).also { checkOpen() }
        }
        override fun <T> stream(block: (HarnessClient) -> Flow<T>): Flow<T> = flow {
          checkOpen()
          block(entry.client).collect { value -> checkOpen(); emit(value) }
        }
        override suspend fun awaitClosed() { if (entry.client.isClosed) entry.client.awaitClosed() }
        override fun setContentWatching(enabled: Boolean) = use { it.setContentWatching(enabled) }
        override fun questionPresentation(sessionId: String?, foreground: Boolean) = use { it.questionPresentation(sessionId, foreground) }
        override fun questionFocus(id: String, focused: Boolean) = use { it.questionFocus(id, focused) }
        override fun questionEngage(id: String) = use { it.questionEngage(id) }
        override fun questionTakeTime(id: String) = use { it.questionTakeTime(id) }
        override fun close() {
          if (!released.compareAndSet(false, true)) return
          val last = synchronized(lock) {
            entry.refs--
            if (entry.refs <= 0 && runtimes[profile.id] === entry) {
              runtimes.remove(profile.id)
              true
            } else false
          }
          if (last) {
            entry.close()
            notifyStatusChanged()
          }
        }
      })
    }

    val entry = synchronized(lock) {
      val current = (runtimes[profile.id] as? TunnelRuntime)?.takeUnless { it.isClosed }
      val active = current ?: TunnelRuntime(
        device.id,
        profile.id,
        SshHostLink(scope, profile, config, secrets, transports) { notifyStatusChanged() }
      ).also { runtimes[profile.id] = it }
      active.refs++
      active
    }
    notifyStatusChanged()
    return SshDshClient(entry.link) {
      val toClose = synchronized(lock) {
        entry.refs--
        if (entry.refs <= 0 && runtimes[profile.id] === entry) {
          runtimes.remove(profile.id)
          entry.link
        } else null
      }
      if (toClose != null) {
        toClose.close()
        notifyStatusChanged()
      }
    }
  }

  private fun createStdioRuntime(
    deviceId: String,
    profile: ServerProfile,
    config: SshHostConfig,
    secrets: SshSecrets
  ): StdioRuntime {
    val onStatus: (SshLinkStatus) -> Unit = { notifyStatusChanged() }
    return when (profile.protocol) {
      HarnessProtocol.ANTIGRAVITY_ACP -> {
        val transport = SshAgyTransport(
          config = config,
          secrets = secrets,
          factory = transports,
          command = "agy acp",
          label = "Antigravity ACP",
          catalog = true,
          onStatusChanged = onStatus
        )
        StdioRuntime(deviceId, profile.id, AgyApi(transport, "antigravity", "Antigravity ACP"), transport.status)
      }
      HarnessProtocol.OPENCODE_ACP -> {
        val transport = SshAgyTransport(
          config = config,
          secrets = secrets,
          factory = transports,
          command = "opencode acp",
          label = "OpenCode ACP",
          catalog = false,
          onStatusChanged = onStatus
        )
        StdioRuntime(deviceId, profile.id, AgyApi(transport, "opencode", "OpenCode ACP", "opencode"), transport.status)
      }
      else -> {
        val transport = SshCodexTransport(
          config = config,
          secrets = secrets,
          factory = transports,
          onStatusChanged = onStatus
        )
        StdioRuntime(deviceId, profile.id, CodexApi(transport), transport.status)
      }
    }
  }

  fun harnessStatus(harnessId: String): StateFlow<SshLinkStatus>? = synchronized(lock) {
    runtimes[harnessId]?.status
  }

  fun deviceStatuses(deviceId: String): List<SshLinkStatus> = synchronized(lock) {
    runtimes.values.filter { it.deviceId == deviceId }.map { it.status.value }
  }

  fun harnessStatusSnapshot(): Map<String, SshLinkStatus> = synchronized(lock) {
    runtimes.mapValues { (_, entry) -> entry.status.value }
  }

  /** 返回设备当前最值得关注的链接状态流（失败 > 连接中/重连中 > 已连接 > 其他）。 */
  fun status(deviceId: String): StateFlow<SshLinkStatus>? = synchronized(lock) {
    val list = runtimes.values.filter { it.deviceId == deviceId }
    list.firstOrNull { it.status.value.phase == SshLinkPhase.FAILED }?.status
      ?: list.firstOrNull { it.status.value.phase == SshLinkPhase.CONNECTING || it.status.value.phase == SshLinkPhase.RECONNECTING }?.status
      ?: list.firstOrNull { it.status.value.phase == SshLinkPhase.CONNECTED }?.status
      ?: list.firstOrNull()?.status
  }

  /** 关闭单个 Harness 运行时，不破坏同一台设备上其他 Harness 复用的底层 SSH 物理连接。 */
  fun closeHarness(harnessId: String) {
    val entry = synchronized(lock) { runtimes.remove(harnessId) }
    if (entry != null) {
      runCatching { entry.close() }
      notifyStatusChanged()
    }
  }

  /** 仅重建单个 Harness（例如修改 DSH 启动命令或工作目录），保持同设备其他会话存活。 */
  fun replaceHarness(harnessId: String) = closeHarness(harnessId)

  /** 关闭整台设备的全部 Harness 并废止其底层 SSH 物理连接。 */
  fun closeDevice(deviceId: String) {
    val active = synchronized(lock) {
      runtimes.values.filter { it.deviceId == deviceId }.also {
        runtimes.entries.removeAll { entry -> entry.value.deviceId == deviceId }
      }
    }
    active.forEach { runCatching { it.close() } }
    transports.invalidate(deviceId)
    notifyStatusChanged()
  }

  fun replaceDevice(deviceId: String) = closeDevice(deviceId)

  fun retryDevice(deviceId: String) {
    val (tunnels, staleStdio) = synchronized(lock) {
      val t = runtimes.values.filterIsInstance<TunnelRuntime>().filter { it.deviceId == deviceId }
      val deadIds = runtimes.values.filterIsInstance<StdioRuntime>()
        .filter { it.deviceId == deviceId && it.isClosed }
        .map { it.harnessId }
      val s = deadIds.mapNotNull { runtimes.remove(it) }
      t to s
    }
    tunnels.forEach { it.retry() }
    staleStdio.forEach { runCatching { it.close() } }
    if (staleStdio.isNotEmpty()) notifyStatusChanged()
  }

  fun retryHarness(harnessId: String) {
    val entry = synchronized(lock) {
      val current = runtimes[harnessId]
      if (current is StdioRuntime && current.isClosed) {
        runtimes.remove(harnessId)
        current
      } else current
    } ?: return
    if (entry is TunnelRuntime) entry.retry()
    else if (entry.isClosed) {
      runCatching { entry.close() }
      notifyStatusChanged()
    }
  }

  /** 即使当前处于 CONNECTED 也强制重连（如远端 DSH 升级完成后立即切到新代次）。 */
  fun forceReconnectHarness(harnessId: String, reason: String = "正在重新连接 SSH 服务") {
    val entry = synchronized(lock) {
      val current = runtimes[harnessId]
      if (current is StdioRuntime) runtimes.remove(harnessId)
      current
    } ?: return
    entry.forceReconnect(reason)
    notifyStatusChanged()
  }

  fun closeAll() {
    val active = synchronized(lock) {
      runtimes.values.toList().also { runtimes.clear() }
    }
    active.forEach { runCatching { it.close() } }
    transports.close()
    notifyStatusChanged()
  }

  /** 网络切换或回到前台：探活全部隧道连接，并淘汰已断开的 Stdio RPC 实例以便下次读自动重建。 */
  fun kickAll() {
    val (tunnels, deadStdio) = synchronized(lock) {
      val t = runtimes.values.filterIsInstance<TunnelRuntime>()
      val deadIds = runtimes.values.filterIsInstance<StdioRuntime>()
        .filter { it.isClosed }
        .map { it.harnessId }
      val s = deadIds.mapNotNull { runtimes.remove(it) }
      t to s
    }
    tunnels.forEach { runCatching { it.kick() } }
    deadStdio.forEach { runCatching { it.close() } }
    if (deadStdio.isNotEmpty()) notifyStatusChanged()
  }

  /** 一次性测试连接（设备表单「测试连接」使用），调用方负责 close。 */
  fun testLink(
    config: SshHostConfig,
    secrets: SshSecrets,
    onStatus: (SshLinkStatus) -> Unit,
    protocol: HarnessProtocol = HarnessProtocol.DSH,
    registrationPath: String? = null
  ): SshHostLink {
    val harnessConfig = when (protocol) {
      HarnessProtocol.OPENCODE_NATIVE -> HarnessConfig.OpenCodeNative(registrationPath)
      else -> HarnessConfig.Dsh(config.launchCommand, config.workingDirectory)
    }
    val profile = ServerProfile(
      id = config.id,
      name = config.name,
      url = config.profileUrl(),
      username = config.user,
      protocol = protocol,
      deviceId = config.id,
      deviceName = config.name,
      config = harnessConfig
    )
    return SshHostLink(scope, profile, config, secrets, transportFactory, onStatus)
  }

  /** 所有探测共享同一条已验证 SSH 连接；单项探测失败仍显式保留为 unknown。 */
  suspend fun discoverDevice(config: SshHostConfig, secrets: SshSecrets, path: String? = null): SshDeviceDiscovery =
    runInterruptible(Dispatchers.IO) {
      val now = System.currentTimeMillis()
      val pin = config.hostKeyFingerprint?.takeIf(String::isNotBlank)
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin || (!config.hostKeyType.isNullOrBlank() && !key.type.equals(config.hostKeyType, ignoreCase = true))) {
          throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹或密钥类型已变化", retryable = false, presentedKey = key)
        }
        true
      }).use { connection ->
        val environment = try {
          val result = connection.exec("printf 'LAGOON_OS %s\\n' \"$(uname -s 2>/dev/null)\"", 10_000)
          val tagged = result.stdout.lineSequence().filter { it.startsWith("LAGOON_OS ") }.toList()
          when {
            result.exitCode != 0 || tagged.size != 1 -> "system_detection_failed"
            tagged.single().removePrefix("LAGOON_OS ") !in setOf("Linux", "Darwin") -> "unsupported_system"
            else -> null
          }
        } catch (error: InterruptedException) {
          throw error
        } catch (_: Exception) {
          "system_detection_failed"
        }
        if (environment != null) {
          SshDeviceDiscovery(
            SshHarnessProbe.unknown(now, SshDiscoveryIssue.EXEC_FAILED),
            SshDshObservation(null, environment, !config.usesDefaultCommand),
            SshCodexObservation(null, issue = environment),
            SshAgyObservation(null, issue = environment),
            now,
            DiscoveryCompleteness.PARTIAL,
            environment
          )
        } else {
          val files = SshHarnessProbe(connection).discover(path)
          val defaultPath = files.openCode.registrationPath
          val localPath = if (path == null && defaultPath?.endsWith("/service.json") == true)
            defaultPath.removeSuffix("service.json") + "service-local.json" else null
          val local = localPath?.let { SshHarnessProbe(connection).discover(it) }
          val dsh = SshDshProbe(connection).discover(!config.usesDefaultCommand)
          val codex = SshCodexProbe(connection).discover()
          val agy = SshAgyProbe(connection).discover()
          val openCodeAcp = SshOpenCodeAcpProbe(connection).discover()
          val partial = files.completeness != DiscoveryCompleteness.COMPLETE ||
            (local != null && local.completeness != DiscoveryCompleteness.COMPLETE) ||
            dsh.issue != null || codex.issue != null || agy.issue != null || openCodeAcp.issue != null
          SshDeviceDiscovery(
            files,
            dsh,
            codex,
            agy,
            now,
            if (partial) DiscoveryCompleteness.PARTIAL else DiscoveryCompleteness.COMPLETE,
            if (files.openCode.issue == SshDiscoveryIssue.PYTHON_UNAVAILABLE) "python_unavailable" else null,
            local,
            openCodeAcp
          )
        }
      }
    }

  /** 只读探测 OpenCode 注册文件，绝不启动 DSH。 */
  suspend fun discover(config: SshHostConfig, secrets: SshSecrets, path: String? = null): SshHarnessDiscoveryResult =
    runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { SshHarnessProbe(it).discover(path) }
    }

  /** 显式启动或重启远端 OpenCode 后台服务（仅用户手动触发，发现流程永不调用）。 */
  suspend fun startOpenCodeService(config: SshHostConfig, secrets: SshSecrets): OpenCodeServiceResult =
    runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf(String::isNotBlank)
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { SshOpenCodeService(it).restart() }
    }

  suspend fun discoverCodex(config: SshHostConfig, secrets: SshSecrets): SshCodexObservation =
    runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { SshCodexProbe(it).discover() }
    }

  suspend fun discoverAgy(config: SshHostConfig, secrets: SshSecrets): SshAgyObservation =
    runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { SshAgyProbe(it).discover() }
    }

  suspend fun testCodex(config: SshHostConfig, secrets: SshSecrets): String =
    SshCodexTransport(config, secrets, transports).use { transport ->
      val result = transport.call("account/read", JSONObject().put("refreshToken", false))
      val requiresAuth = result.opt("requiresOpenaiAuth") as? Boolean ?: error("Codex 登录状态格式错误")
      check(!requiresAuth || result.optJSONObject("account") != null) { "开发机上的 Codex 尚未登录，请先在开发机完成登录" }
      if (result.optJSONObject("account") == null) "Codex 已连接 · 未检测到登录账户" else "Codex 已连接 · 已检测到账户"
    }

  /** 幂等追加公钥到远端 `~/.ssh/authorized_keys`（0700/0600）。 */
  suspend fun deployPublicKey(config: SshHostConfig, secrets: SshSecrets, publicKeyOpenSsh: String) {
    runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      val script = SshPublicKeyFormat.authorizedKeysInstallScript(publicKeyOpenSsh)
      val result = transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { it.exec(script, 15_000) }
      if (result.exitCode != 0) {
        val detail = result.stderr.ifBlank { result.stdout }.trim()
        throw SshFailure(SshFailureKind.OTHER, "写入服务器 authorized_keys 失败", detail, retryable = false)
      }
    }
  }

  /** 仅探测服务器出示的主机密钥，不认证也不信任。 */
  suspend fun probeHostKey(config: SshHostConfig): PresentedSshHostKey =
    runInterruptible(Dispatchers.IO) { transportFactory.probeHostKey(config) }

  fun pinHostKey(deviceId: String, key: PresentedSshHostKey) {
    try {
      store.pinHostKey(deviceId, key)
    } catch (error: Exception) {
      Diagnostics.warn("SshHostManager", "主机指纹保存失败", error)
      throw SshFailure(SshFailureKind.OTHER, "主机指纹没有保存成功，请重试", error.message.orEmpty(), retryable = true)
    }
  }
}

internal data class SshDeviceDiscovery(
  val files: SshHarnessDiscoveryResult,
  val dsh: SshDshObservation,
  val codex: SshCodexObservation,
  val agy: SshAgyObservation,
  val observedAtMs: Long,
  val completeness: DiscoveryCompleteness,
  val environmentIssue: String? = null,
  val localOpenCode: SshHarnessDiscoveryResult? = null,
  val openCodeAcp: SshOpenCodeAcpObservation? = null
)
