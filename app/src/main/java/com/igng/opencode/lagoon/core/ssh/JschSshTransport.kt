package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.Identity
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
  /** Open a long-lived, non-PTY exec channel. stdout and stderr remain separate. */
  fun openExecChannel(script: String): SshExecChannel = throw UnsupportedOperationException("SSH streaming exec unavailable")
  fun verifyWorkspace(root: String): String = throw UnsupportedOperationException("SSH SFTP unavailable")
  fun listWorkspace(root: String, path: String): List<FileNode> = throw UnsupportedOperationException("SSH SFTP unavailable")
  fun readWorkspace(root: String, path: String): FileContent = throw UnsupportedOperationException("SSH SFTP unavailable")
  fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection
  fun probeTunnel(host: String, port: Int, timeoutMillis: Long): Boolean
  fun ping(timeoutMillis: Long): Boolean
}

internal interface SshExecChannel : Closeable {
  val stdin: OutputStream
  val stdout: InputStream
  val stderr: InputStream
  val isOpen: Boolean
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
internal class JschSshTransportFactory(
  private val keyStore: SshKeyStore? = null,
  private val hardwareSigner: HardwareSshSigner? = null
) : SshTransportFactory {
  override fun connect(config: SshHostConfig, secrets: SshSecrets, verifier: SshHostKeyVerifier): SshTransport {
    val repository = VerifyingHostKeyRepository(verifier, config.hostKeyType?.trim()?.takeIf { it.isNotEmpty() })
    val session = try {
      openSession(config, secrets, repository)
    } catch (error: Throwable) {
      val failure = describe(error, repository)
      if (failure.kind == SshFailureKind.BIOMETRIC_REQUIRED && !config.keyId.isNullOrBlank()) {
        keyStore?.lockRuntimeSecret(config.keyId)
      }
      throw failure
    }
    return JschSshTransport(session)
  }

  override fun probeHostKey(config: SshHostConfig): PresentedSshHostKey {
    val repository = VerifyingHostKeyRepository({ false })
    val session = try {
      // The host key is presented before authentication; use a throwaway password auth so a
      // private-key-only config can still be probed without asking for the key again.
      openSession(
        config.copyWith(auth = SshAuthMethod.PASSWORD, clearKeyId = true),
        SshSecrets(password = "probe"),
        repository
      )
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
    var certAlgorithm: String? = null
    var effectivePassphrase: String? = secrets.passphrase
    val fallbackPassword = secrets.password?.takeIf(String::isNotEmpty)

    when (config.auth) {
      SshAuthMethod.PASSWORD -> {
        if (secrets.password.isNullOrEmpty()) {
          throw SshFailure(SshFailureKind.AUTH, "请填写 SSH 密码，或改用私钥", retryable = false)
        }
      }
      SshAuthMethod.PRIVATE_KEY, SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK -> {
        val inlineKey = secrets.privateKey?.trim().orEmpty()
        if (inlineKey.isNotEmpty()) {
          certAlgorithm = attachPrivateKeyIdentity(
            jsch = jsch,
            name = "lagoon-${config.id}",
            privateKey = inlineKey,
            passphrase = secrets.passphrase,
            certificateText = secrets.certificate
          )
        } else if (!config.keyId.isNullOrBlank()) {
          val store = keyStore ?: throw SshFailure(SshFailureKind.BAD_KEY, "未配置 SSH 密钥库", retryable = false)
          val profile = store.find(config.keyId)
            ?: throw SshFailure(SshFailureKind.BAD_KEY, "引用的 SSH 密钥已不存在，请重新选择或绑定", retryable = false)
          val stored = store.secrets(profile.id)
          val runtime = store.runtimeSecret(profile.id)
          val certText = secrets.certificate?.trim()?.takeIf(String::isNotEmpty) ?: profile.certificateOpenSsh
          when (profile.source) {
            SshKeySource.HARDWARE_KEYSTORE -> {
              if (profile.biometricProtected && runtime == null) {
                throw SshFailure(
                  SshFailureKind.BIOMETRIC_REQUIRED,
                  "需要生物识别解锁硬件密钥「${profile.name}」",
                  retryable = false
                )
              }
              val signer = hardwareSigner
                ?: throw SshFailure(SshFailureKind.BAD_KEY, "本机硬件密钥签名器不可用", retryable = false)
              val hwIdentity = signer.createIdentity(profile)
              if (certText != null) {
                val cert = SshKeyCodec.parseCertificate(certText)
                jsch.addIdentity(CertifiedSshIdentity(hwIdentity, cert.algorithm, cert.blob), null)
                certAlgorithm = cert.algorithm
              } else {
                jsch.addIdentity(hwIdentity, null)
              }
            }
            SshKeySource.GENERATED, SshKeySource.IMPORTED -> {
              val (resolvedKey, resolvedPass) = resolveSoftwareKeyMaterial(profile, stored, runtime, secrets.passphrase)
              effectivePassphrase = resolvedPass
              certAlgorithm = attachPrivateKeyIdentity(
                jsch = jsch,
                name = "lagoon-${profile.id}",
                privateKey = resolvedKey,
                passphrase = resolvedPass,
                certificateText = certText
              )
            }
          }
        } else {
          throw SshFailure(SshFailureKind.BAD_KEY, "请选择或填写 SSH 私钥", retryable = false)
        }
      }
    }

    val session = jsch.getSession(config.user.trim(), config.host.trim(), config.port)
    session.setHostKeyRepository(repository)
    session.setConfig("StrictHostKeyChecking", "yes")
    val preferredAuth = when {
      config.auth == SshAuthMethod.PASSWORD -> "password,keyboard-interactive"
      config.auth == SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK && fallbackPassword != null ->
        "publickey,keyboard-interactive,password"
      else -> "publickey"
    }
    session.setConfig("PreferredAuthentications", preferredAuth)
    if (certAlgorithm != null) {
      val defaults = JSch.getConfig("PubkeyAcceptedAlgorithms").orEmpty()
      val certAlgs = listOf(
        certAlgorithm,
        "ssh-ed25519-cert-v01@openssh.com",
        "ecdsa-sha2-nistp256-cert-v01@openssh.com",
        "ecdsa-sha2-nistp384-cert-v01@openssh.com",
        "ecdsa-sha2-nistp521-cert-v01@openssh.com",
        "rsa-sha2-512-cert-v01@openssh.com",
        "rsa-sha2-256-cert-v01@openssh.com",
        "ssh-rsa-cert-v01@openssh.com"
      ).distinct().joinToString(",")
      session.setConfig("PubkeyAcceptedAlgorithms", if (defaults.isBlank()) certAlgs else "$certAlgs,$defaults")
    }
    val sessionPassword = when (config.auth) {
      SshAuthMethod.PASSWORD -> secrets.password
      SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK -> fallbackPassword
      SshAuthMethod.PRIVATE_KEY -> null
    }
    session.userInfo = object : UserInfo, UIKeyboardInteractive {
      override fun getPassphrase(): String? = effectivePassphrase
      override fun getPassword(): String? = sessionPassword
      override fun promptPassword(message: String?): Boolean = sessionPassword != null
      override fun promptPassphrase(message: String?): Boolean = effectivePassphrase != null
      override fun promptYesNo(message: String?): Boolean = false
      override fun showMessage(message: String?) = Unit
      override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?
      ): Array<String> {
        val answer = sessionPassword ?: effectivePassphrase ?: ""
        return Array(prompt?.size ?: 0) { answer }
      }
    }
    if (sessionPassword != null) session.setPassword(sessionPassword)
    // Do not set a socket read timeout: a bootstrap script can be silent for a minute while it
    // waits for dsh web's token. The link owns keepalive/probes and enforces command deadlines.
    session.setTimeout(0)
    session.connect(12_000)
    return session
  }

  private fun resolveSoftwareKeyMaterial(
    profile: SshKeyProfile,
    stored: SshKeySecrets,
    runtime: String?,
    overridePassphrase: String?
  ): Pair<String, String?> {
    if (profile.passphrasePolicy == SshPassphrasePolicy.BIOMETRIC && !profile.encrypted) {
      val unlockedKey = runtime ?: throw SshFailure(
        SshFailureKind.BIOMETRIC_REQUIRED,
        "需要生物识别解锁私钥「${profile.name}」",
        retryable = false
      )
      return unlockedKey to null
    }
    val key = stored.privateKey?.trim()?.takeIf(String::isNotEmpty) ?: throw SshFailure(
      SshFailureKind.BAD_KEY,
      "私钥内容缺失，请重新导入「${profile.name}」",
      retryable = false
    )
    val passphrase = when {
      !overridePassphrase.isNullOrEmpty() -> overridePassphrase
      profile.passphrasePolicy == SshPassphrasePolicy.BIOMETRIC -> runtime ?: throw SshFailure(
        SshFailureKind.BIOMETRIC_REQUIRED,
        "需要生物识别解锁私钥「${profile.name}」",
        retryable = false
      )
      profile.passphrasePolicy == SshPassphrasePolicy.PROMPT && profile.encrypted -> runtime ?: throw SshFailure(
        SshFailureKind.PASSPHRASE_REQUIRED,
        "需要输入私钥口令解锁「${profile.name}」",
        retryable = false
      )
      else -> stored.passphrase
    }
    return key to passphrase
  }

  private fun attachPrivateKeyIdentity(
    jsch: JSch,
    name: String,
    privateKey: String,
    passphrase: String?,
    certificateText: String?
  ): String? {
    val parsed = SshKeyCodec.inspectPrivateKey(privateKey, passphrase)
    if (parsed.encrypted && !parsed.decrypted) {
      throw SshFailure(SshFailureKind.PASSPHRASE_REQUIRED, "此私钥已加密，请输入私钥密码", retryable = false)
    }
    val keyBytes = privateKey.trim().toByteArray(Charsets.UTF_8)
    val passBytes = passphrase?.takeIf(String::isNotEmpty)?.toByteArray(Charsets.UTF_8)
    val cert = certificateText?.trim()?.takeIf(String::isNotEmpty)?.let(SshKeyCodec::parseCertificate)
    try {
      if (cert == null) {
        jsch.addIdentity(name, keyBytes, null, passBytes)
        return null
      }
      val temp = JSch()
      temp.addIdentity(name, keyBytes, null, passBytes)
      val delegate = temp.identityRepository.identities.firstElement() as Identity
      jsch.addIdentity(CertifiedSshIdentity(delegate, cert.algorithm, cert.blob), passBytes)
      return cert.algorithm
    } catch (error: SshFailure) {
      throw error
    } catch (error: Exception) {
      throw SshFailure(SshFailureKind.BAD_KEY, "SSH 私钥无法读取，请检查格式和私钥密码", error.message.orEmpty(), retryable = false)
    }
  }

  private fun describe(error: Throwable, repository: VerifyingHostKeyRepository): SshFailure {
    var cur: Throwable? = error
    while (cur != null) {
      if (cur is SshFailure) return cur
      cur = cur.cause
    }
    repository.failure?.let { return it }
    val text = error.message.orEmpty()
    // JSch exposes the server key before authentication. Do not let that diagnostic
    // mask a real password/private-key failure when the key was already accepted.
    when {
      error is JSchUnknownHostKeyException ->
        return SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "还没有确认主机指纹", retryable = false, presentedKey = repository.presented)
      error is JSchChangedHostKeyException ->
        return SshFailure(SshFailureKind.HOST_KEY_CHANGED, "主机指纹和上次不一致，已断开", retryable = false, presentedKey = repository.presented)
      "User not authenticated" in text ->
        return SshFailure(SshFailureKind.BIOMETRIC_REQUIRED, "需要生物识别解锁硬件密钥", text, retryable = false)
      "Auth fail" in text || "auth cancel" in text || "Auth cancel" in text ->
        return SshFailure(SshFailureKind.AUTH, "SSH 认证失败，请检查用户名、密码或私钥", retryable = false)
    }
    if (!repository.accepted) repository.presented?.let { key ->
      return SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "还没有确认主机指纹",
        "${key.shortType} ${key.fingerprint}", retryable = false, presentedKey = key)
    }
    return when {
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
  @Volatile var accepted: Boolean = false
  @Volatile var presented: PresentedSshHostKey? = null
  @Volatile var failure: SshFailure? = null

  override fun check(host: String, key: ByteArray): Int {
    accepted = false
    failure = null
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
      if (verifier.verify(presented)) {
        accepted = true
        HostKeyRepository.OK
      } else HostKeyRepository.NOT_INCLUDED
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

  private fun <T> withWorkspaceFile(root: String, path: String, block: (ChannelSftp, String) -> T): T {
    require(root.isNotBlank() && root.none(Char::isISOControl) && path.none(Char::isISOControl)) { "工作区路径无效" }
    val relative = path.trim().ifBlank { "." }
    require(!relative.startsWith('/') && !relative.startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(relative)) { "文件路径必须位于工作区内" }
    val channel = session.openChannel("sftp") as ChannelSftp
    return try {
      channel.connect(15_000)
      val canonicalRoot = channel.realpath(root).trimEnd('/').ifBlank { "/" }
      val target = channel.realpath(if (relative == ".") canonicalRoot else "${canonicalRoot.trimEnd('/')}/$relative")
      require(target == canonicalRoot || target.startsWith(if (canonicalRoot == "/") "/" else "$canonicalRoot/")) { "文件路径超出工作区" }
      block(channel, target)
    } finally { channel.disconnect() }
  }
  override fun verifyWorkspace(root: String): String = withWorkspaceFile(root, ".") { channel, target ->
    require(channel.stat(target).isDir) { "路径不是目录" }
    target
  }
  override fun listWorkspace(root: String, path: String): List<FileNode> = withWorkspaceFile(root, path) { channel, target ->
    val relative = path.trim().trim('/').takeUnless { it.isEmpty() || it == "." }.orEmpty()
    @Suppress("UNCHECKED_CAST")
    val entries = channel.ls(target) as java.util.Vector<ChannelSftp.LsEntry>
    if (entries.size > 10_000) throw java.io.IOException("目录条目超过上限")
    entries.asSequence().filter { it.filename !in setOf(".", "..") && it.filename.isNotBlank() && '/' !in it.filename }
      .map { item -> FileNode(if (relative.isBlank()) item.filename else "$relative/${item.filename}", if (item.attrs.isDir) "directory" else "file") }.toList()
  }
  override fun readWorkspace(root: String, path: String): FileContent = withWorkspaceFile(root, path) { channel, target ->
    val limit = 2 * 1024 * 1024
    val bytes = ByteArrayOutputStream()
    channel.get(target).use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer); if (count < 0) break
        if (bytes.size() + count > limit) throw java.io.IOException("文件超过 2 MiB 预览上限")
        bytes.write(buffer, 0, count)
      }
    }
    val data = bytes.toByteArray()
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
      .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString() }
      catch (_: Exception) { return@withWorkspaceFile FileContent("binary", "") }
    if ('\u0000' in text) FileContent("binary", "") else FileContent("text", text)
  }

  override fun openExecChannel(script: String): SshExecChannel {
    val channel = session.openChannel("exec") as ChannelExec
    return try {
      channel.setPty(false)
      channel.setCommand(SshShell.loginShell(script))
      val stdin = channel.outputStream
      val stdout = channel.inputStream
      val stderr = channel.extInputStream
      channel.connect(15_000)
      object : SshExecChannel {
        override val stdin: OutputStream = stdin
        override val stdout: InputStream = stdout
        override val stderr: InputStream = stderr
        override val isOpen: Boolean get() = channel.isConnected && !channel.isClosed
        override fun close() { channel.disconnect() }
      }
    } catch (error: Exception) {
      runCatching { channel.disconnect() }
      throw SshFailure(SshFailureKind.OTHER, "无法打开 SSH 双向通道", retryable = true)
    }
  }

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
      // JSch DirectTCPIP.connect starts a worker when an input stream is set; it returns BEFORE
      // SSH_MSG_CHANNEL_OPEN_CONFIRMATION. Do not expose that opening state as a closed tunnel.
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
      channel.connect(15_000)
      while (!channel.isConnected && !channel.isClosed && session.isConnected && System.nanoTime() < deadline) {
        Thread.sleep(10)
      }
      if (!channel.isConnected) throw SshFailure(SshFailureKind.TUNNEL, "SSH 隧道未能完成打开确认")
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
