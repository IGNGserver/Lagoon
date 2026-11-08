package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Checks the host key presented during the SSH handshake. Throw [SshFailure] to reject with detail. */
internal fun interface SshHostKeyVerifier {
  fun verify(key: PresentedSshHostKey): Boolean
}

/** Blocking SSH transport. All calls run on a background dispatcher; this interface stays plain so
 * it can be driven by JSch in production and by a fake in unit tests. */
internal interface SshTransport : Closeable {
  val isClosed: Boolean
  fun exec(script: String, timeoutMillis: Long): RemoteExecResult
  fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection
  fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean
  fun ping(timeoutMillis: Long): Boolean
}

/** One live direct-tcpip channel. [isOpen] lets the local forwarder prune finished pairs.
 *  [onClosed] fires when the channel stops, so the local socket does not wait on a poll. */
internal interface TunnelConnection : Closeable {
  val isOpen: Boolean
  fun onClosed(listener: () -> Unit) {}
}

internal interface SshTransportFactory {
  fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport

  /** Reads the presented host key without authenticating and without trusting it. */
  fun probeHostKey(config: SshHostConfig): PresentedSshHostKey
}

/** JSch-backed transport. JSch is BSD-licensed; the host key is verified by our own TOFU pin. */
internal class JschSshTransportFactory : SshTransportFactory {
  override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
    val repository = VerifyingHostKeyRepository(verifier, config.hostKeyType?.trim()?.takeIf { it.isNotEmpty() })
    val session = try {
      openSession(config, secrets, repository)
    } catch (error: Throwable) {
      throw describe(error, repository)
    }
    return JschSshTransport(session)
  }

  override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey {
    val repository = VerifyingHostKeyRepository({ false })
    val session = try {
      // The host key is presented before authentication; use a throwaway password auth so a
      // private-key-only config can still be probed without asking for the key again.
      openSession(config.copyWith(auth = SshAuthMethod.PASSWORD), SshSecrets(password = "probe"), repository)
    } catch (error: Throwable) {
      repository.presented?.let { return it }
      throw describe(error, repository)
    } finally {
      // openSession throws before returning when the key is untrusted, so there is no session to close.
    }
    session.disconnect()
    return repository.presented
      ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "服务器没有提供主机指纹")
  }

  private fun openSession(config: SshHostConfig, secrets: SshSecrets, repository: HostKeyRepository): Session {
    val jsch = JSch()
    if (config.auth == SshAuthMethod.PRIVATE_KEY) {
      val key = secrets.privateKey?.trim().orEmpty()
      if (key.isEmpty()) throw SshFailure(SshFailureKind.BAD_KEY, "请填写 SSH 私钥", retryable = false)
      val passphrase = secrets.passphrase?.takeIf(String::isNotEmpty)?.toByteArray(Charsets.UTF_8)
      try {
        jsch.addIdentity("lagoon-${config.id}", key.toByteArray(Charsets.UTF_8), null, passphrase)
      } catch (error: Exception) {
        throw SshFailure(SshFailureKind.BAD_KEY, "SSH 私钥无法读取，请检查格式和私钥密码", error.message.orEmpty(), retryable = false)
      }
    } else if (secrets.password.isNullOrEmpty()) {
      throw SshFailure(SshFailureKind.AUTH, "请填写 SSH 密码，或改用私钥", retryable = false)
    }
    val session = jsch.getSession(config.user.trim(), config.host.trim(), config.port)
    session.setHostKeyRepository(repository)
    session.setConfig("StrictHostKeyChecking", "yes")
    session.setConfig("PreferredAuthentications",
      if (config.auth == SshAuthMethod.PRIVATE_KEY) "publickey" else "password,keyboard-interactive")
    session.userInfo = object : UserInfo, UIKeyboardInteractive {
      override fun getPassphrase(): String? = secrets.passphrase
      override fun getPassword(): String? = secrets.password
      override fun promptPassword(message: String?): Boolean = true
      override fun promptPassphrase(message: String?): Boolean = true
      override fun promptYesNo(message: String?): Boolean = false
      override fun showMessage(message: String?) = Unit
      override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?
      ): Array<String> {
        val answer = secrets.password ?: secrets.passphrase ?: ""
        return Array(prompt?.size ?: 0) { answer }
      }
    }
    if (config.auth == SshAuthMethod.PASSWORD) session.setPassword(secrets.password)
    // Do not set a socket read timeout: a bootstrap script can be silent for a minute while it
    // waits for dsh web's token. The link owns keepalive/probes and enforces command deadlines.
    session.setTimeout(0)
    session.connect(12_000)
    return session
  }

  private fun describe(error: Throwable, repository: VerifyingHostKeyRepository): SshFailure {
    if (error is SshFailure) return error
    repository.failure?.let { return it }
    repository.presented?.let { key ->
      return SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "还没有确认主机指纹",
        "${key.shortType} ${key.fingerprint}", retryable = false, presentedKey = key)
    }
    val text = error.message.orEmpty()
    return when {
      error is JSchUnknownHostKeyException -> SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "还没有确认主机指纹", retryable = false)
      error is JSchChangedHostKeyException -> SshFailure(SshFailureKind.HOST_KEY_CHANGED, "主机指纹和上次不一致，已断开")
      "Auth fail" in text || "auth cancel" in text || "Auth cancel" in text ->
        SshFailure(SshFailureKind.AUTH, "SSH 认证失败，请检查用户名、密码或私钥", retryable = false)
      "timeout" in text.lowercase() || "timed out" in text.lowercase() ->
        SshFailure(SshFailureKind.UNREACHABLE, "SSH 连接超时，请检查地址、端口和网络")
      "refused" in text.lowercase() ->
        SshFailure(SshFailureKind.UNREACHABLE, "SSH 服务器拒绝连接，请检查端口和 SSH 服务")
      "unknown host" in text.lowercase() || "name or service not known" in text.lowercase() ->
        SshFailure(SshFailureKind.UNREACHABLE, "找不到这台主机，请检查地址")
      else -> SshFailure(SshFailureKind.UNREACHABLE, "SSH 连接失败", text, retryable = true)
    }
  }
}

private class VerifyingHostKeyRepository(
  private val verifier: SshHostKeyVerifier,
  private val expectedType: String? = null
) : HostKeyRepository {
  @Volatile var presented: PresentedSshHostKey? = null
  @Volatile var failure: SshFailure? = null

  override fun check(host: String, key: ByteArray): Int {
    val hostKey = try {
      HostKey(host, key)
    } catch (_: Exception) {
      return HostKeyRepository.NOT_INCLUDED
    }
    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
      .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    val presented = PresentedSshHostKey(hostKey.type, fingerprint)
    this.presented = presented
    val pinnedType = expectedType
    if (pinnedType != null && !pinnedType.equals(hostKey.type, ignoreCase = true)) {
      val failure = SshFailure(
        SshFailureKind.HOST_KEY_CHANGED,
        "主机密钥类型和上次不一致，已拒绝连接",
        "已记住：$pinnedType\n这次收到：${presented.shortType} ${presented.fingerprint}",
        retryable = false,
        presentedKey = presented
      )
      this.failure = failure
      return HostKeyRepository.NOT_INCLUDED
    }
    return try {
      if (verifier.verify(presented)) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED
    } catch (failure: SshFailure) {
      this.failure = failure
      HostKeyRepository.NOT_INCLUDED
    } catch (_: Exception) {
      HostKeyRepository.NOT_INCLUDED
    }
  }

  override fun add(key: HostKey?, userinfo: UserInfo?) = Unit
  override fun remove(host: String?, type: String?) = Unit
  override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
  override fun getKnownHostsRepositoryID(): String = "lagoon-ssh"
  override fun getHostKey(): Array<HostKey> = emptyArray()
  override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private class JschSshTransport(private val session: Session) : SshTransport {
  override val isClosed: Boolean get() = !session.isConnected

  override fun exec(script: String, timeoutMillis: Long): RemoteExecResult {
    val channel = session.openChannel("exec") as ChannelExec
    return try {
      channel.setCommand(SshShell.loginShell(script))
      val stdout = channel.inputStream
      val stderr = channel.extInputStream
      channel.connect(15_000)
      val pool = Executors.newFixedThreadPool(2)
      try {
        val out = pool.submit<String> { readLimited(stdout, MAX_OUTPUT_BYTES) }
        val err = pool.submit<String> { readLimited(stderr, MAX_OUTPUT_BYTES) }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(40)
        val exit = if (channel.isClosed) channel.exitStatus else null
        if (!channel.isClosed) {
          channel.disconnect()
          throw SshFailure(SshFailureKind.DSH_TIMEOUT, "远程命令超时")
        }
        RemoteExecResult(exit, out.get(2, TimeUnit.SECONDS), err.get(2, TimeUnit.SECONDS))
      } finally {
        pool.shutdownNow()
      }
    } catch (failure: SshFailure) {
      throw failure
    } catch (error: TimeoutException) {
      throw SshFailure(SshFailureKind.DSH_TIMEOUT, "读取远程命令输出超时", error.message.orEmpty())
    } catch (error: Exception) {
      throw SshFailure(SshFailureKind.DSH_START, "远程命令执行失败", error.message.orEmpty())
    } finally {
      runCatching { channel.disconnect() }
    }
  }

  override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection {
    val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
    return try {
      channel.setHost(host)
      channel.setPort(port)
      channel.setInputStream(input)
      channel.setOutputStream(output)
      channel.connect(15_000)
      object : TunnelConnection {
        private val closedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        @Volatile private var notified = false
        override val isOpen: Boolean get() = channel.isConnected
        override fun onClosed(listener: () -> Unit) {
          closedListeners += listener
          if (!isOpen) fireClosed()
        }
        override fun close() {
          runCatching { channel.disconnect() }
          fireClosed()
        }
        private fun fireClosed() {
          if (notified) return
          notified = true
          closedListeners.forEach { runCatching { it() } }
        }
      }
    } catch (error: Exception) {
      runCatching { channel.disconnect() }
      throw SshFailure(SshFailureKind.TUNNEL, "无法建立 SSH 隧道", error.message.orEmpty())
    }
  }

  override fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean {
    val request = "GET / HTTP/1.0\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val captured = ByteArrayOutputStream()
    val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
    return try {
      channel.setHost(host)
      channel.setPort(port)
      channel.setInputStream(java.io.ByteArrayInputStream(request))
      channel.setOutputStream(captured)
      channel.connect(timeoutMillis.toInt().coerceAtLeast(1_000))
      val deadline = System.nanoTime() + timeoutMillis.coerceIn(1_000, 30_000) * 1_000_000L
      while (captured.size() < 12 && System.nanoTime() < deadline) Thread.sleep(25)
      val head = captured.toString(Charsets.US_ASCII.name())
      head.startsWith("HTTP/")
    } catch (_: Exception) {
      false
    } finally {
      runCatching { channel.disconnect() }
    }
  }

  override fun ping(timeoutMillis: Long): Boolean {
    if (!session.isConnected) return false
    val channel = try { session.openChannel("exec") as ChannelExec } catch (_: Exception) { return false }
    return try {
      // JSch sendKeepAliveMsg() only writes a request and returns before any SSH response. A half-open
      // Wi-Fi/cellular socket therefore looked healthy forever. A no-op exec requires a server round
      // trip and has its own channel-open/command-completion deadlines.
      channel.setCommand(":")
      channel.connect(timeoutMillis.toInt().coerceIn(1_000, 30_000))
      val deadline = System.nanoTime() + timeoutMillis.coerceIn(1_000, 30_000) * 1_000_000L
      while (!channel.isClosed && System.nanoTime() < deadline) Thread.sleep(25)
      channel.isClosed && channel.exitStatus == 0
    } catch (_: Exception) {
      false
    } finally {
      runCatching { channel.disconnect() }
    }
  }

  override fun close() {
    runCatching { session.disconnect() }
  }

  private fun readLimited(stream: InputStream, limit: Int): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
      val read = stream.read(buffer)
      if (read < 0) break
      if (read == 0) continue
      val keep = minOf(read, limit - total)
      if (keep > 0) {
        out.write(buffer, 0, keep)
        total += keep
      }
      if (total >= limit) break
    }
    return out.toString(Charsets.UTF_8.name())
  }

  private companion object {
    const val MAX_OUTPUT_BYTES = 512 * 1024
  }
}

/** Maps a generic exception to a person-readable SSH failure (used by the link and form). */
internal fun sshFailure(error: Throwable, fallback: String = "SSH 连接失败"): SshFailure =
  when (error) {
    is SshFailure -> error
    else -> SshFailure(SshFailureKind.OTHER, fallback, error.message.orEmpty(), retryable = true)
  }
