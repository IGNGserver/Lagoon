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

/**
 * Process-wide SSH host links, one per saved host. The controller asks for a client when a profile
 * is connected; the manager reuses the link so AllServersOverview's client cache and the SSH
 * forwarder share one connection. Test flows use a temporary link that is never cached.
 */
internal class SshHostManager(
  private val store: SshHostStore,
  private val transportFactory: SshTransportFactory = JschSshTransportFactory()
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val links = ConcurrentHashMap<String, SshHostLink>()

  fun client(profile: ServerProfile): HarnessClient {
    val hostId = profile.sshHostId ?: throw SshFailure(SshFailureKind.OTHER, "不是 SSH 主机资料", retryable = false)
    val config = store.load().firstOrNull { it.id == hostId }
      ?: throw SshFailure(SshFailureKind.OTHER, "SSH 主机配置已删除", retryable = false)
    val link = links.computeIfAbsent(hostId) {
      SshHostLink(scope, profile, config, store.secrets(hostId), transportFactory)
    }
    return SshDshClient(link) { links.remove(hostId, link) }
  }

  fun status(hostId: String): StateFlow<SshLinkStatus>? = links[hostId]?.status

  fun close(hostId: String) {
    links.remove(hostId)?.close()
  }

  fun retry(hostId: String) {
    links[hostId]?.retry()
  }

  fun closeAll() {
    links.values.toList().forEach { runCatching { it.close() } }
    links.clear()
  }

  fun kickAll() {
    links.values.forEach { runCatching { it.kick() } }
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
    runCatching { store.pinHostKey(hostId, key) }
      .onFailure { Diagnostics.warn("SshHostManager", "主机指纹保存失败", it) }
  }
}
