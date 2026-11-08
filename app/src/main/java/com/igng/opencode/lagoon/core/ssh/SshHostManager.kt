package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.Diagnostics
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide SSH host links, one per saved host. The controller asks for a client when a profile
 * is connected; the manager reuses the link so AllServersOverview's client cache and the SSH
 * forwarder share one connection. Test flows use a temporary link that is never cached.
 */
internal class SshHostManager(
  private val store: SshHostStore,
  private val transportFactory: SshTransportFactory = JschSshTransportFactory()
) {
  private class SharedLink(val link: SshHostLink, val refs: AtomicInteger = AtomicInteger(0))

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val links = ConcurrentHashMap<String, SharedLink>()
  private val lock = Any()

  fun client(profile: ServerProfile): HarnessClient {
    val hostId = profile.sshHostId ?: throw SshFailure(SshFailureKind.OTHER, "不是 SSH 主机资料", retryable = false)
    val config = store.load().firstOrNull { it.id == hostId }
      ?: throw SshFailure(SshFailureKind.OTHER, "SSH 主机配置已删除", retryable = false)
    val entry = synchronized(lock) {
      val current = links[hostId]?.takeUnless { it.link.isClosed }
      val active = current ?: SharedLink(
        SshHostLink(scope, profile, config, store.secrets(hostId), transportFactory)
      ).also { links[hostId] = it }
      active.refs.incrementAndGet()
      active
    }
    return SshDshClient(entry.link) {
      val toClose = synchronized(lock) {
        if (entry.refs.decrementAndGet() <= 0 && links[hostId] === entry) {
          links.remove(hostId)
          entry.link
        } else null
      }
      toClose?.close()
    }
  }

  fun status(hostId: String): StateFlow<SshLinkStatus>? = links[hostId]?.link?.status

  fun close(hostId: String) {
    val entry = synchronized(lock) { links.remove(hostId) }
    entry?.link?.close()
  }

  /**
   * Closes the live link even if a client handle is still counted. Saving a host must not leave the
   * previous address, key or launch command running because a monitor raced a new `client()` call in
   * between `invalidate` and `close`.
   */
  fun replace(hostId: String) {
    val entry = synchronized(lock) { links.remove(hostId) }
    entry?.link?.close()
  }

  fun retry(hostId: String) {
    links[hostId]?.link?.retry()
  }

  fun closeAll() {
    val active = synchronized(lock) {
      val all = links.values.toList()
      links.clear()
      all
    }
    active.forEach { runCatching { it.link.close() } }
  }

  fun kickAll() {
    links.values.forEach { runCatching { it.link.kick() } }
  }

  /** Starts a one-off link for "测试连接"; the caller must close it. */
  fun testLink(config: SshHostConfig, secrets: SshSecrets, onStatus: (SshLinkStatus) -> Unit): SshHostLink {
    val profile = ServerProfile(
      id = config.id,
      name = config.name,
      url = config.profileUrl(),
      username = config.user,
      kind = ServerKind.DSH,
      sshHostId = config.id
    )
    return SshHostLink(scope, profile, config, secrets, transportFactory, onStatus)
  }

  /** Reads the presented host key without trusting it; used before pinning a new host. */
  suspend fun probeHostKey(config: SshHostConfig): PresentedSshHostKey =
    kotlinx.coroutines.withContext(Dispatchers.IO) { transportFactory.probeHostKey(config) }

  fun pinHostKey(hostId: String, key: PresentedSshHostKey) {
    try {
      store.pinHostKey(hostId, key)
    } catch (error: Exception) {
      Diagnostics.warn("SshHostManager", "主机指纹保存失败", error)
      throw SshFailure(SshFailureKind.OTHER, "主机指纹没有保存成功，请重试", error.message.orEmpty(), retryable = true)
    }
  }
}
