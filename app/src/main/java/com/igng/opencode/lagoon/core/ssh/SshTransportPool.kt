package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.PresentedSshHostKey
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
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
  private val lock = Any()
  private val entries = HashMap<ConfigKey, Entry>()

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
    synchronized(lock) {
      val entry = entries[key]?.takeUnless { it.transport.isClosed }
        ?: Entry(delegate.connect(config, secrets, verifier)).also { entries[key] = it }
      entry.references++
      return Lease(key, entry)
    }
  }

  override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey = delegate.probeHostKey(config)

  fun invalidate(hostId: String) {
    val old = synchronized(lock) {
      entries.filterKeys { it.id == hostId }.values.toList().also { entries.keys.removeAll { it.id == hostId } }
    }
    old.forEach { runCatching { it.close() } }
  }

  override fun close() {
    val old = synchronized(lock) { entries.values.toList().also { entries.clear() } }
    old.forEach { runCatching { it.close() } }
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
