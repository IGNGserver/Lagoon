package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** How one saved SSH host authenticates. Secrets live in the app's encrypted SSH store, never here. */
enum class SshAuthMethod(val label: String) {
  PASSWORD("密码"),
  PRIVATE_KEY("密钥"),
  KEY_WITH_PASSWORD_FALLBACK("密钥优先")
}

const val SSH_DEFAULT_PORT = 22
const val DEFAULT_DSH_LAUNCH_COMMAND = "dsh web"
const val SSH_STATE_DIR = "\$HOME/.lagoon-ssh-dsh"
const val SSH_LEGACY_STATE_DIR = "\$HOME/.dsh-app-remote"

/** Result of reading one address field, so the form can keep user/port out of the first screen. */
data class SshAddressParts(val host: String, val user: String? = null, val port: Int? = null, val error: String? = null)

/**
 * What the address field accepts. People copy `user@host`, `host:2222` or a whole
 * `ssh -p 2222 user@host` line out of a terminal or a chat message, so the form splits that instead
 * of asking everyone for three separate fields. Unsupported command options are never silently
 * discarded: [SshAddressParts.error] must be resolved before the address can be used.
 */
object SshAddress {
  fun parse(raw: String): SshAddressParts {
    val text = raw.trim()
    if (text.isEmpty()) return SshAddressParts("")
    var rest = text
    var flagPort: Int? = null
    var flagUser: String? = null
    var unsupported: String? = null
    if (text.length > 3 && text.startsWith("ssh") && text[3].isWhitespace()) {
      val tokens = tokenize(text)?.drop(1) ?: return SshAddressParts(text, error = "SSH 命令格式无法识别，请只填写地址或简单的 ssh -p 命令")
      var destination: String? = null
      var index = 0
      var endOptions = false
      while (index < tokens.size) {
        val token = tokens[index]
        when {
          destination != null -> return SshAddressParts(text, error = "不支持 SSH 命令里的远程命令或额外参数，请只填写连接地址")
          !endOptions && token == "--" -> { endOptions = true; index++ }
          !endOptions && (token == "-p" || token.startsWith("-p") && token.length > 2) -> {
            val value = if (token == "-p") tokens.getOrNull(++index) else token.substring(2)
            val parsed = value?.toPortOrNull() ?: return SshAddressParts(text, error = "SSH 端口需在 1–65535")
            if (flagPort != null && flagPort != parsed) return SshAddressParts(text, error = "SSH 命令中包含不同的端口，请保留一个")
            flagPort = parsed
            index++
          }
          !endOptions && (token == "-l" || token.startsWith("-l") && token.length > 2) -> {
            val value = if (token == "-l") tokens.getOrNull(++index) else token.substring(2)
            if (value.isNullOrBlank() || value.startsWith('-')) return SshAddressParts(text, error = "SSH 命令缺少用户名")
            if (flagUser != null && flagUser != value) return SshAddressParts(text, error = "SSH 命令中包含不同的用户名，请保留一个")
            flagUser = value
            index++
          }
          !endOptions && (token == "-i" || token.startsWith("-i") && token.length > 2) -> {
            val value = if (token == "-i") tokens.getOrNull(++index) else token.substring(2)
            if (value.isNullOrBlank() || value.startsWith('-')) return SshAddressParts(text, error = "SSH 命令的 -i 缺少私钥文件")
            unsupported = "命令使用了 -i 私钥路径，手机无法使用电脑上的文件。请只填连接地址，并在验证方式中导入密钥"
            index++
          }
          token.startsWith("-") -> return SshAddressParts(text, error = "不支持 SSH 命令选项 $token，请去掉该选项后重试")
          else -> {
            destination = token
            index++
          }
        }
      }
      if (destination == null) return SshAddressParts(text, error = "SSH 命令缺少主机地址")
      rest = destination
    }
    if (rest.any(Char::isWhitespace) || rest.any(Char::isISOControl))
      return SshAddressParts(text, error = "地址不能包含空格或换行")
    val at = rest.lastIndexOf('@')
    if (at == 0 || at == rest.lastIndex || (at >= 0 && rest.indexOf('@') != at))
      return SshAddressParts(text, error = "请填写完整的用户名和主机地址")
    val user = if (at > 0) rest.substring(0, at).takeIf(String::isNotBlank) else null
    if (flagUser != null && user != null && flagUser != user)
      return SshAddressParts(text, error = "SSH 命令中包含不同的用户名，请保留一个")
    val hostPort = if (user != null) rest.substring(at + 1) else rest
    val split = splitHostPort(hostPort)
    if (split.error != null) return SshAddressParts(text, error = split.error)
    if (split.host.isBlank()) return SshAddressParts(text, error = "SSH 命令缺少主机地址")
    if (flagPort != null && split.port != null && flagPort != split.port)
      return SshAddressParts(text, error = "SSH 命令和地址中包含不同的端口，请保留一个")
    return SshAddressParts(split.host, user ?: flagUser, split.port ?: flagPort, unsupported)
  }

  private fun splitHostPort(value: String): SshAddressParts {
    if (value.startsWith("[")) {
      val close = value.indexOf(']')
      if (close <= 1) return SshAddressParts(value, error = "IPv6 地址的方括号不完整")
      val tail = value.substring(close + 1)
      if (tail.isEmpty()) return SshAddressParts(value.substring(1, close))
      if (!tail.startsWith(":")) return SshAddressParts(value, error = "IPv6 地址格式不正确")
      val port = tail.substring(1).toPortOrNull() ?: return SshAddressParts(value, error = "SSH 端口需在 1–65535")
      return SshAddressParts(value.substring(1, close), port = port)
    }
    if ('[' in value || ']' in value) return SshAddressParts(value, error = "IPv6 地址的方括号不完整")
    val first = value.indexOf(':')
    // More than one colon without brackets is a bare IPv6 address, never a port.
    if (first != value.lastIndexOf(':') || first < 0) return SshAddressParts(value)
    if (first == 0) return SshAddressParts(value, error = "SSH 地址缺少主机名")
    val port = value.substring(first + 1).toPortOrNull() ?: return SshAddressParts(value, error = "SSH 端口需在 1–65535")
    return SshAddressParts(value.substring(0, first), port = port)
  }

  /** Tokenize, never evaluate, a small shell-like command; reject expansions and operators. */
  private fun tokenize(text: String): List<String>? {
    val tokens = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var started = false
    for (char in text) {
      if (char.isISOControl() || char == '$' || char == '`') return null
      if (escaped) { token.append(char); escaped = false; started = true; continue }
      if (char == '\\' && quote != '\'') { escaped = true; started = true; continue }
      if (quote != null) {
        if (char == quote) quote = null else token.append(char)
        started = true
      } else when {
        char == '\'' || char == '"' -> { quote = char; started = true }
        char.isWhitespace() -> if (started) { tokens.add(token.toString()); token.clear(); started = false }
        char in ";|&<>" -> return null
        else -> { token.append(char); started = true }
      }
    }
    if (quote != null || escaped) return null
    if (started) tokens.add(token.toString())
    return tokens
  }

  private fun String.toPortOrNull(): Int? = trim().toIntOrNull()?.takeIf { it in 1..65535 }
}

/**
 * A saved SSH host. Behaviour and fields mirror dsh-Go's `SshHostConfig`: address + port + user,
 * password or private key, the remote `dsh web` launch command, an optional working directory and
 * the host key pinned on first use. The config contains no secret material.
 */
data class SshHostConfig(
  val id: String,
  val name: String,
  val host: String,
  val port: Int = SSH_DEFAULT_PORT,
  val user: String,
  val auth: SshAuthMethod = SshAuthMethod.PASSWORD,
  val launchCommand: String = DEFAULT_DSH_LAUNCH_COMMAND,
  val workingDirectory: String? = null,
  val hostKeyType: String? = null,
  val hostKeyFingerprint: String? = null,
  val keyId: String? = null
) {
  private val displayHost: String
    get() = host.trim().let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }

  val address: String
    get() = if (port == SSH_DEFAULT_PORT) "${user.trim()}@$displayHost" else "${user.trim()}@$displayHost:$port"

  /** Stable display identity stored in the ServerProfile URL; never used as an HTTP origin. */
  fun profileUrl(): String = "ssh://${user.trim()}@$displayHost:$port"

  val usesDefaultCommand: Boolean get() = launchCommand.trim() == DEFAULT_DSH_LAUNCH_COMMAND

  /** Fields that need a fresh SSH session. Renaming and re-pinning the key do not move the link. */
  val connectionKey: String
    get() = listOf(host.trim(), port, user.trim(), auth.name, keyId.orEmpty(), launchCommand.trim(), workingDirectory.orEmpty())
      .joinToString("\u0000")

  fun copyWith(
    name: String? = null,
    host: String? = null,
    port: Int? = null,
    user: String? = null,
    auth: SshAuthMethod? = null,
    launchCommand: String? = null,
    workingDirectory: String? = this.workingDirectory,
    clearWorkingDirectory: Boolean = false,
    hostKeyType: String? = this.hostKeyType,
    hostKeyFingerprint: String? = this.hostKeyFingerprint,
    clearHostKey: Boolean = false,
    keyId: String? = this.keyId,
    clearKeyId: Boolean = false
  ): SshHostConfig {
    val moved = (host != null && host != this.host) || (port != null && port != this.port)
    return copy(
      name = name ?: this.name,
      host = host ?: this.host,
      port = port ?: this.port,
      user = user ?: this.user,
      auth = auth ?: this.auth,
      launchCommand = launchCommand ?: this.launchCommand,
      workingDirectory = when {
        clearWorkingDirectory -> null
        workingDirectory != null -> workingDirectory
        else -> this.workingDirectory
      },
      hostKeyType = if (clearHostKey || moved) null else hostKeyType,
      hostKeyFingerprint = if (clearHostKey || moved) null else hostKeyFingerprint,
      keyId = if (clearKeyId) null else keyId
    )
  }

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("host", host)
    .put("port", port)
    .put("user", user)
    .put("auth", auth.name)
    .put("launchCommand", launchCommand)
    .putOpt("workingDirectory", workingDirectory)
    .putOpt("hostKeyType", hostKeyType)
    .putOpt("hostKeyFingerprint", hostKeyFingerprint)
    .putOpt("keyId", keyId)

  companion object {
    fun newId(): String = "ssh-${UUID.randomUUID()}"

    fun fromJson(raw: JSONObject): SshHostConfig? {
      val id = raw.optString("id").takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
      val host = raw.optString("host").trim().takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
      val user = raw.optString("user").trim().takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
      val auth = SshAuthMethod.entries.firstOrNull { it.name == raw.optString("auth") } ?: SshAuthMethod.PASSWORD
      val port = raw.optInt("port", SSH_DEFAULT_PORT).takeIf { it in 1..65535 } ?: SSH_DEFAULT_PORT
      val command = raw.optString("launchCommand").trim().takeIf { it.isNotBlank() && it.length <= 512 }
        ?: DEFAULT_DSH_LAUNCH_COMMAND
      val name = raw.optString("name").trim().takeIf { it.isNotBlank() && it.length <= 128 } ?: host
      return SshHostConfig(
        id = id,
        name = name,
        host = host,
        port = port,
        user = user,
        auth = auth,
        launchCommand = command,
        workingDirectory = raw.optString("workingDirectory").trim().takeIf { it.isNotBlank() && it.length <= 1024 },
        hostKeyType = raw.optString("hostKeyType").trim().takeIf { it.isNotBlank() && it.length <= 64 },
        hostKeyFingerprint = raw.optString("hostKeyFingerprint").trim()
          .takeIf { it.isNotBlank() && it.length <= 128 },
        keyId = raw.optString("keyId").trim().takeIf { it.isNotBlank() && it.length <= 128 }
      )
    }
  }
}

/** Secret material for one SSH host. Never written to the plaintext config document. */
data class SshSecrets(
  val password: String? = null,
  val privateKey: String? = null,
  val passphrase: String? = null,
  val certificate: String? = null
) {
  val isEmpty: Boolean
    get() = password.isNullOrEmpty() && privateKey.isNullOrBlank() && certificate.isNullOrBlank()
}

internal object SshHostCodec {
  const val VERSION = 1

  fun encode(hosts: List<SshHostConfig>): String = JSONObject()
    .put("version", VERSION)
    .put("hosts", JSONArray().apply { hosts.forEach { put(it.toJson()) } })
    .toString()

  fun version(text: String): Int? = runCatching { JSONObject(text).optInt("version", 0) }.getOrNull()

  fun decode(text: String): List<SshHostConfig> {
    try {
      val json = JSONObject(text)
      if (json.optInt("version") != VERSION) throw IOException("SSH 主机配置版本无效")
      val array = json.optJSONArray("hosts") ?: JSONArray()
      if (array.length() > 256) throw IOException("SSH 主机配置过多")
      val hosts = (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let(SshHostConfig::fromJson)
      }
      if (hosts.distinctBy { it.id }.size != hosts.size) throw IOException("SSH 主机 ID 重复")
      return hosts
    } catch (error: IOException) {
      throw error
    } catch (error: Exception) {
      throw IOException("SSH 主机配置损坏", error)
    }
  }
}

/** A host key as presented during the SSH handshake, in OpenSSH `SHA256:…` form. */
data class PresentedSshHostKey(val type: String, val fingerprint: String) {
  val shortType: String
    get() {
      val lower = type.lowercase(Locale.ROOT)
      return when {
        "ed25519" in lower -> "ED25519"
        "rsa" in lower -> "RSA"
        "ecdsa" in lower -> "ECDSA"
        "ed448" in lower -> "ED448"
        else -> type
      }
    }
}

enum class SshLinkPhase { IDLE, CONNECTING, RECONNECTING, CONNECTED, FAILED }

enum class SshLinkStep { CONNECT, VERIFY_HOST_KEY, START_DSH, TUNNEL, LOGIN, CONNECTED }

/** User-facing progress of one SSH link. [detail] is short and never contains a secret. */
data class SshLinkStatus(
  val phase: SshLinkPhase = SshLinkPhase.IDLE,
  val step: SshLinkStep = SshLinkStep.CONNECT,
  val detail: String = "",
  val error: SshFailure? = null,
  val attempt: Int = 0
) {
  val isBusy: Boolean get() = phase == SshLinkPhase.CONNECTING || phase == SshLinkPhase.RECONNECTING
  val isConnected: Boolean get() = phase == SshLinkPhase.CONNECTED
}

enum class SshFailureKind {
  UNREACHABLE, AUTH, AUTH_INTERACTION_REQUIRED, HOST_KEY_UNKNOWN, HOST_KEY_CHANGED, NO_SHELL, DSH_NOT_FOUND,
  DSH_START, DSH_TIMEOUT, TUNNEL, LOGIN, DISCONNECTED, UNRESPONSIVE, BAD_KEY,
  PASSPHRASE_REQUIRED, BIOMETRIC_REQUIRED, OTHER
}

/** A typed SSH failure. The message is safe to show; [detail] is an optional redacted log tail. */
data class SshFailure(
  val kind: SshFailureKind,
  override val message: String,
  val detail: String = "",
  val retryable: Boolean = true,
  /** Set for host-key failures so the UI can show the fingerprint the server presented. */
  val presentedKey: PresentedSshHostKey? = null
) : Exception(message) {
  fun display(): String = if (detail.isBlank()) message else "$message\n$detail"
}

/** One non-interactive remote command result. */
data class RemoteExecResult(val exitCode: Int?, val stdout: String, val stderr: String)

/** A remote `dsh web` reachable through the local forwarder. */
data class RemoteDshEndpoint(
  val port: Int,
  val token: String,
  val pid: Int? = null,
  val reused: Boolean = false,
  val home: String? = null,
  val dshPath: String? = null,
  /** Trusted state-directory basename for proving the managed process generation. */
  val ownershipStateDirectory: String? = null
) {
  /** The Host header DSH's loopback API fence expects through the tunnel. */
  val authority: String get() = "127.0.0.1:$port"
}

/** POSIX shell quoting and remote-path helpers shared by the bootstrap and its tests. */
object SshShell {
  fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

  fun loginShell(script: String): String = "sh -lc ${quote(script)}"

  /** Shell expression for a remote path with `~` expanded by the remote shell. */
  fun remotePathExpr(path: String): String {
    val trimmed = path.trim()
    if (trimmed == "~") return "\"\$HOME\""
    if (trimmed.startsWith("~/")) return "\"\$HOME\"/${quote(trimmed.substring(2))}"
    return quote(trimmed)
  }

  /** [path] with `~` resolved against the remote [home], for `session/create` defaults. */
  fun absolutePath(path: String?, home: String?): String? {
    val trimmed = path?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (home.isNullOrEmpty()) return trimmed
    if (trimmed == "~") return home
    if (trimmed.startsWith("~/")) {
      return "${home.trimEnd('/')}/${trimmed.substring(2)}"
    }
    return trimmed
  }

  /** `token=…` values are redacted before a remote log tail reaches the UI or diagnostics. */
  fun redactTokens(text: String): String = text.replace(Regex("token=[A-Za-z0-9._~%+-]+"), "token=•••")
}
