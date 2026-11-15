package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One authenticated SSH connection per saved source/configuration. Runtime links retain independent
 * leases: closing an OpenCode forwarder must not disconnect DSH. The delegate owns host verification;
 * a changed key/configuration cannot reuse a previous connection. No command is retried here.
 */
internal class SshTransportPool(
  private val delegate: SshTransportFactory,
  private val keyRevisionProvider: ((SshHostConfig) -> String)? = null
) : SshTransportFactory, Closeable {
  private data class ConfigKey(val id: String, val connection: String, val keyType: String?, val fingerprint: String?, val credentialDigest: String)
  private class Entry(val transport: SshTransport, var references: Int = 0) {
    private val closed = AtomicBoolean(false)
    fun close() { if (closed.compareAndSet(false, true)) transport.close() }
  }
  private class Connecting {
    val completed = CountDownLatch(1)
    @Volatile var failure: Throwable? = null
  }
  private val lock = Any()
  private val entries = HashMap<ConfigKey, Entry>()
  private val connecting = HashMap<ConfigKey, Connecting>()

  override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
    // Credential revision is memory-only: do not reuse an authenticated socket for a different
    // password/key during an unsaved discovery test. Never log this map or persist the digest.
    val material = listOf(
      secrets.password,
      secrets.privateKey,
      secrets.passphrase,
      secrets.certificate,
      keyRevisionProvider?.invoke(config)
    ).joinToString("") { value ->
      if (value == null) "-1:" else "${value.length}:$value"
    }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
      .joinToString("") { "%02x".format(it.toInt() and 255) }
    val key = ConfigKey(config.id, config.connectionKey, config.hostKeyType, config.hostKeyFingerprint, digest)
    while (true) {
      val (attempt, owner) = synchronized(lock) {
        val entry = entries[key]
        if (entry != null && !entry.transport.isClosed) {
          entry.references++
          return Lease(key, entry)
        }
        if (entry != null) entries.remove(key, entry)
        val active = connecting[key]
        if (active != null) active to false
        else Connecting().also { connecting[key] = it } to true
      }
      if (!owner) {
        try { attempt.completed.await() }
        catch (error: InterruptedException) {
          Thread.currentThread().interrupt()
          throw java.util.concurrent.CancellationException("等待 SSH 连接时已取消").apply { initCause(error) }
        }
        attempt.failure?.let { throw it }
        // The owner may already have released its lease. Recheck under the lock instead of
        // resurrecting a transport that reached zero references while this caller was waiting.
        continue
      }

      try {
        // A handshake can block for seconds. It must not hold the pool-wide monitor: unrelated
        // devices, invalidation and UI-triggered close must remain able to proceed.
        val physical = delegate.connect(config, secrets, verifier)
        val lease = synchronized(lock) {
          if (connecting[key] !== attempt) null else {
            val entry = Entry(physical, references = 1)
            entries[key] = entry
            connecting.remove(key)
            attempt.completed.countDown()
            Lease(key, entry)
          }
        }
        if (lease != null) return lease
        // An invalidation or close won the race while the socket was being established. Never
        // publish the now-stale authenticated connection, even if its handshake succeeded.
        runCatching { physical.close() }
        throw attempt.failure ?: SshFailure(SshFailureKind.DISCONNECTED, "SSH 配置已变化，请重试")
      } catch (error: Throwable) {
        synchronized(lock) {
          if (connecting[key] === attempt) {
            connecting.remove(key)
            attempt.failure = error
            attempt.completed.countDown()
          }
        }
        throw error
      }
    }
  }

  override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = delegate.probeHostKey(config)

  fun invalidate(hostId: String) {
    val old = synchronized(lock) {
      cancelConnectingLocked { it.id == hostId }
      entries.filterKeys { it.id == hostId }.values.toList().also { entries.keys.removeAll { it.id == hostId } }
    }
    old.forEach { runCatching { it.close() } }
  }

  override fun close() {
    val old = synchronized(lock) {
      cancelConnectingLocked { true }
      entries.values.toList().also { entries.clear() }
    }
    old.forEach { runCatching { it.close() } }
  }

  /** Called only under [lock]. A late successful handshake will see that its slot was removed. */
  private fun cancelConnectingLocked(matches: (ConfigKey) -> Boolean) {
    val stale = connecting.filterKeys(matches)
    stale.forEach { (key, attempt) ->
      connecting.remove(key, attempt)
      attempt.failure = SshFailure(SshFailureKind.DISCONNECTED, "SSH 连接配置已变化，请重试")
      attempt.completed.countDown()
    }
  }

  private inner class Lease(private val key: ConfigKey, private val entry: Entry) : SshTransport {
    private val released = AtomicBoolean(false)
    override val isClosed: Boolean get() = released.get() || entry.transport.isClosed
    private fun active(): SshTransport { check(!isClosed) { "SSH transport lease closed" }; return entry.transport }
    override fun exec(script: String, timeoutMillis: Long) = active().exec(script, timeoutMillis)
    override fun openExecChannel(script: String) = active().openExecChannel(script)
    override fun verifyWorkspace(root: String) = active().verifyWorkspace(root)
    override fun listWorkspace(root: String, path: String) = active().listWorkspace(root, path)
    override fun readWorkspace(root: String, path: String) = active().readWorkspace(root, path)
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream) =
      active().openTunnel(host, port, input, output)
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = active().probeTunnel(host, port, timeoutMillis)
    override fun ping(timeoutMillis: Long): Boolean {
      if (isClosed) return false
      val healthy = try { entry.transport.ping(timeoutMillis) } catch (_: Exception) { false }
      if (!healthy) {
        // Retire this exact physical connection, even if a sibling still holds a lease. A late
        // failure may not evict/close a replacement connected under the same source key.
        synchronized(lock) { entries.remove(key, entry) }
        entry.close()
      }
      return healthy
    }
    override fun close() {
      if (!released.compareAndSet(false, true)) return
      val last = synchronized(lock) {
        entry.references--
        if (entry.references == 0) {
          entries.remove(key, entry)
          true
        } else false
      }
      if (last) entry.close()
    }
  }
}

/** A cancelled handshake must not leak a successfully opened transport across the suspend boundary. */
internal suspend fun SshTransportFactory.connectInterruptibly(
  config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier
): SshTransport {
  val opened = java.util.concurrent.atomic.AtomicReference<SshTransport?>()
  return try {
    kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
      connect(config, secrets, verifier).also { opened.set(it) }
    }
  } catch (error: Throwable) {
    opened.getAndSet(null)?.let { runCatching { it.close() } }
    throw error
  }
}
