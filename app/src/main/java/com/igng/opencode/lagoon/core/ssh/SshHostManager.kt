package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.CodexApi
import com.igng.opencode.lagoon.core.AgyApi
import com.igng.opencode.lagoon.core.HarnessProtocolId
import com.igng.opencode.lagoon.core.GuardedHarnessClient
import com.igng.opencode.lagoon.core.HarnessLeaseAccess
import com.igng.opencode.lagoon.core.HarnessClient
import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshLinkStatus
import com.igng.opencode.lagoon.core.SshSecrets
import com.igng.opencode.lagoon.core.SshHostStore
import com.igng.opencode.lagoon.core.SshKeyStore
import com.igng.opencode.lagoon.core.SshPublicKeyFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide SSH host links, one per saved host. The controller asks for a client when a profile
 * is connected; the manager reuses the link so AllServersOverview's client cache and the SSH
 * forwarder share one connection. Test flows use a temporary link that is never cached.
 */
internal class SshHostManager(
  private val store: SshHostStore,
  private val transportFactory: SshTransportFactory = JschSshTransportFactory(),
  private val keyStore: SshKeyStore? = null
) {
  private class SharedLink(val hostId: String, val link: SshHostLink, val refs: AtomicInteger = AtomicInteger(0))

  /**
   * Link/keepalive/reconnect work is background maintenance. A failure there is reported to the
   * link's status and retried; it must never reach Android's default handler and kill the app.
   */
  private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
      if (error !is CancellationException) Diagnostics.warn("SshHostManager", "SSH 后台任务异常", error)
    }
  )
  private val links = ConcurrentHashMap<String, SharedLink>()
  private class SharedCodex(val hostId: String, val client: HarnessClient, var refs: Int = 0)
  private val codexLinks = mutableMapOf<String, SharedCodex>()
  private val lock = Any()
  private val transports = SshTransportPool(transportFactory) { cfg ->
    cfg.keyId?.let { keyStore?.credentialFingerprint(it) }.orEmpty()
  }

  fun client(profile: ServerProfile): HarnessClient {
    val hostId = profile.sshHostId ?: throw SshFailure(SshFailureKind.OTHER, "不是 SSH 主机资料", retryable = false)
    val config = store.load().firstOrNull { it.id == hostId }
      ?: throw SshFailure(SshFailureKind.OTHER, "SSH 主机配置已删除", retryable = false)
    // Typed, not IllegalArgumentException: callers (overview/notification) only recognize SshFailure
    // and would otherwise swallow a stale route as an anonymous crash.
    if (profile.url != config.profileUrl() || profile.username != config.user) {
      throw SshFailure(SshFailureKind.OTHER, "SSH 保存配置已变化，请重新保存绑定路由", retryable = false)
    }
    if (profile.protocol in setOf(HarnessProtocolId.CODEX_APP_SERVER, HarnessProtocolId.ANTIGRAVITY_ACP,
        HarnessProtocolId.OPENCODE_ACP)) {
      val entry = synchronized(lock) {
        val current = codexLinks[profile.id]?.takeUnless { it.client.isClosed }
        (current ?: SharedCodex(hostId, when (profile.protocol) {
          HarnessProtocolId.ANTIGRAVITY_ACP ->
            AgyApi(SshAgyTransport(config, store.secrets(hostId), transports), "antigravity", "Antigravity ACP")
          HarnessProtocolId.OPENCODE_ACP ->
            AgyApi(SshAgyTransport(config, store.secrets(hostId), transports,
              command = "opencode acp", label = "OpenCode ACP", catalog = false), "opencode", "OpenCode ACP", "opencode")
          else -> CodexApi(SshCodexTransport(config, store.secrets(hostId), transports))
        }).also { codexLinks[profile.id] = it }).also { it.refs++ }
      }
      return GuardedHarnessClient(object : HarnessLeaseAccess {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        override val isClosed: Boolean get() = released.get() || entry.client.isClosed
        private fun checkOpen() { check(!isClosed) { "SSH Harness 租约已关闭" } }
        override fun <T> use(block: (HarnessClient) -> T): T { checkOpen(); return block(entry.client).also { checkOpen() } }
        override suspend fun <T> useSuspending(block: suspend (HarnessClient) -> T): T {
          checkOpen(); return block(entry.client).also { checkOpen() }
        }
        override fun <T> stream(block: (HarnessClient) -> kotlinx.coroutines.flow.Flow<T>): kotlinx.coroutines.flow.Flow<T> =
          kotlinx.coroutines.flow.flow {
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
            if (entry.refs == 0 && codexLinks[profile.id] === entry) { codexLinks.remove(profile.id); true } else false
          }
          if (last) entry.client.close()
        }
      })
    }
    val entry = synchronized(lock) {
      val current = links[profile.id]?.takeUnless { it.link.isClosed }
      val active = current ?: SharedLink(hostId,
        SshHostLink(scope, profile, config, store.secrets(hostId), transports)
      ).also { links[profile.id] = it }
      active.refs.incrementAndGet()
      active
    }
    return SshDshClient(entry.link) {
      val toClose = synchronized(lock) {
        if (entry.refs.decrementAndGet() <= 0 && links[profile.id] === entry) {
          links.remove(profile.id)
          entry.link
        } else null
      }
      toClose?.close()
    }
  }

  fun status(hostId: String): StateFlow<SshLinkStatus>? = links.values.firstOrNull { it.hostId == hostId }?.link?.status

  fun closeBinding(bindingId: String) {
    val entry = synchronized(lock) { links.remove(bindingId) }
    val codex = synchronized(lock) { codexLinks.remove(bindingId) }
    entry?.link?.close()
    codex?.client?.close()
  }

  fun close(hostId: String) {
    val active = synchronized(lock) {
      links.filterValues { it.hostId == hostId }.values.toList().also {
        links.entries.removeAll { entry -> entry.value.hostId == hostId }
      }
    }
    active.forEach { it.link.close() }
    closeCodexHost(hostId)
    transports.invalidate(hostId)
  }

  /**
   * Closes the live link even if a client handle is still counted. Saving a host must not leave the
   * previous address, key or launch command running because a monitor raced a new `client()` call in
   * between `invalidate` and `close`.
   */
  fun replace(hostId: String) {
    val active = synchronized(lock) {
      links.filterValues { it.hostId == hostId }.values.toList().also {
        links.entries.removeAll { entry -> entry.value.hostId == hostId }
      }
    }
    active.forEach { it.link.close() }
    closeCodexHost(hostId)
    transports.invalidate(hostId)
  }

  fun retry(hostId: String) {
    links.values.filter { it.hostId == hostId }.forEach { it.link.retry() }
    val stale = synchronized(lock) {
      codexLinks.filterValues { it.hostId == hostId && it.client.isClosed }.keys.toList().mapNotNull { codexLinks.remove(it) }
    }
    stale.forEach { it.client.close() }
  }

  fun closeAll() {
    val active = synchronized(lock) {
      val all = links.values.toList()
      links.clear()
      all
    }
    active.forEach { runCatching { it.link.close() } }
    val codex = synchronized(lock) { codexLinks.values.toList().also { codexLinks.clear() } }
    codex.forEach { runCatching { it.client.close() } }
    transports.close()
  }

  private fun closeCodexHost(hostId: String) {
    val active = synchronized(lock) {
      codexLinks.filterValues { it.hostId == hostId }.values.toList().also {
        codexLinks.entries.removeAll { entry -> entry.value.hostId == hostId }
      }
    }
    active.forEach { it.client.close() }
  }

  fun kickAll() {
    links.values.forEach { runCatching { it.link.kick() } }
  }

  /** Starts a one-off link for "测试连接"; the caller must close it. */
  fun testLink(config: SshHostConfig, secrets: SshSecrets, onStatus: (SshLinkStatus) -> Unit,
    harnessKind: ServerKind = ServerKind.DSH, registrationPath: String? = null): SshHostLink {
    require(harnessKind != ServerKind.DSH_REMOTE)
    val profile = ServerProfile(
      id = config.id,
      name = config.name,
      url = config.profileUrl(),
      username = config.user,
      kind = harnessKind,
      sshHostId = config.id,
      sshServicePath = registrationPath
    )
    return SshHostLink(scope, profile, config, secrets, transportFactory, onStatus)
  }

  /** All probes share one verified lease; individual failures remain explicitly unknown. */
  suspend fun discoverDevice(config: SshHostConfig, secrets: SshSecrets, path: String? = null): SshDeviceDiscovery =
    kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
      val now = System.currentTimeMillis()
      val pin = config.hostKeyFingerprint?.takeIf(String::isNotBlank)
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin || (!config.hostKeyType.isNullOrBlank() && !key.type.equals(config.hostKeyType, ignoreCase = true)))
          throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹或密钥类型已变化", retryable = false, presentedKey = key)
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
        } catch (error: InterruptedException) { throw error }
        catch (_: Exception) { "system_detection_failed" }
        if (environment != null) {
          SshDeviceDiscovery(SshHarnessProbe.unknown(now, com.igng.opencode.lagoon.core.SshDiscoveryIssue.EXEC_FAILED),
            SshDshObservation(null, environment, !config.usesDefaultCommand), SshCodexObservation(null, issue = environment),
            SshAgyObservation(null, issue = environment), now, com.igng.opencode.lagoon.core.DiscoveryCompleteness.PARTIAL, environment)
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
          val partial = files.completeness != com.igng.opencode.lagoon.core.DiscoveryCompleteness.COMPLETE ||
            (local != null && local.completeness != com.igng.opencode.lagoon.core.DiscoveryCompleteness.COMPLETE) ||
            dsh.issue != null || codex.issue != null || agy.issue != null || openCodeAcp.issue != null
          SshDeviceDiscovery(files, dsh, codex, agy, now,
            if (partial) com.igng.opencode.lagoon.core.DiscoveryCompleteness.PARTIAL else com.igng.opencode.lagoon.core.DiscoveryCompleteness.COMPLETE,
            if (files.openCode.issue == com.igng.opencode.lagoon.core.SshDiscoveryIssue.PYTHON_UNAVAILABLE) "python_unavailable" else null,
            local, openCodeAcp)
        }
      }
    }

  /** A discovery scan only authenticates SSH and reads known registration files. Never bootstraps DSH. */
  suspend fun discover(config: SshHostConfig, secrets: SshSecrets, path: String? = null): com.igng.opencode.lagoon.core.SshHarnessDiscoveryResult =
    kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { SshHarnessProbe(it).discover(path) }
    }

  /**
   * Starts or restarts the user's OpenCode background service. Explicit action only: discovery
   * never runs this, and the profile is not touched until the caller re-reads the registration.
   */
  suspend fun startOpenCodeService(config: SshHostConfig, secrets: SshSecrets): OpenCodeServiceResult =
    kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf(String::isNotBlank)
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { SshOpenCodeService(it).restart() }
    }

  suspend fun discoverCodex(config: SshHostConfig, secrets: SshSecrets): SshCodexObservation =
    kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { SshCodexProbe(it).discover() }
    }

  suspend fun discoverAgy(config: SshHostConfig, secrets: SshSecrets): SshAgyObservation =
    kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      transports.connect(config, secrets, SshHostKeyVerifier { key ->
        if (key.fingerprint != pin) throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "SSH 主机指纹已变化", retryable = false, presentedKey = key)
        true
      }).use { SshAgyProbe(it).discover() }
    }

  suspend fun testCodex(config: SshHostConfig, secrets: SshSecrets): String =
    SshCodexTransport(config, secrets, transports).use { transport ->
      val result = transport.call("account/read", org.json.JSONObject().put("refreshToken", false))
      val requiresAuth = result.opt("requiresOpenaiAuth") as? Boolean ?: error("Codex 登录状态格式错误")
      check(!requiresAuth || result.optJSONObject("account") != null) { "开发机上的 Codex 尚未登录，请先在开发机完成登录" }
      if (result.optJSONObject("account") == null) "Codex 已连接 · 未检测到登录账户" else "Codex 已连接 · 已检测到账户"
    }

  /** Appends [publicKeyOpenSsh] idempotently to remote `~/.ssh/authorized_keys` (0700/0600). */
  suspend fun deployPublicKey(config: SshHostConfig, secrets: SshSecrets, publicKeyOpenSsh: String) {
    kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
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

  /** Reads the presented host key without trusting it; used before pinning a new host. */
  suspend fun probeHostKey(config: SshHostConfig): PresentedSshHostKey =
    kotlinx.coroutines.runInterruptible(Dispatchers.IO) { transportFactory.probeHostKey(config) }

  fun pinHostKey(hostId: String, key: PresentedSshHostKey) {
    try {
      store.pinHostKey(hostId, key)
    } catch (error: Exception) {
      Diagnostics.warn("SshHostManager", "主机指纹保存失败", error)
      throw SshFailure(SshFailureKind.OTHER, "主机指纹没有保存成功，请重试", error.message.orEmpty(), retryable = true)
    }
  }
}

internal data class SshDeviceDiscovery(
  val files: com.igng.opencode.lagoon.core.SshHarnessDiscoveryResult,
  val dsh: SshDshObservation,
  val codex: SshCodexObservation,
  val agy: SshAgyObservation,
  val observedAtMs: Long,
  val completeness: com.igng.opencode.lagoon.core.DiscoveryCompleteness,
  val environmentIssue: String? = null,
  val localOpenCode: com.igng.opencode.lagoon.core.SshHarnessDiscoveryResult? = null,
  val openCodeAcp: SshOpenCodeAcpObservation? = null
)
