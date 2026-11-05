package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** How one saved SSH host authenticates. Secrets live in the app's encrypted SSH store, never here. */
enum class SshAuthMethod(val label: String) { PASSWORD("密码"), PRIVATE_KEY("私钥") }

const val SSH_DEFAULT_PORT = 22
const val DEFAULT_DSH_LAUNCH_COMMAND = "dsh web"
const val SSH_STATE_DIR = "\$HOME/.dsh-app-remote"

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
  val hostKeyFingerprint: String? = null
) {
  private val displayHost: String
    get() = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

  val address: String
    get() = if (port == SSH_DEFAULT_PORT) "$user@$displayHost" else "$user@$displayHost:$port"

  /** Stable display identity stored in the ServerProfile URL; never used as an HTTP origin. */
  fun profileUrl(): String = "ssh://${user}@$displayHost:$port"

  val usesDefaultCommand: Boolean get() = launchCommand.trim() == DEFAULT_DSH_LAUNCH_COMMAND

  /** Fields that need a fresh SSH session. Renaming and re-pinning the key do not move the link. */
  val connectionKey: String
    get() = listOf(host.trim(), port, user.trim(), auth.name, launchCommand.trim(), workingDirectory.orEmpty())
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
    clearHostKey: Boolean = false
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
      hostKeyFingerprint = if (clearHostKey || moved) null else hostKeyFingerprint
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
          .takeIf { it.isNotBlank() && it.length <= 128 }
      )
    }
  }
}

/** Secret material for one SSH host. Never written to the plaintext config document. */
data class SshSecrets(
  val password: String? = null,
  val privateKey: String? = null,
  val passphrase: String? = null
) {
  val isEmpty: Boolean
    get() = password.isNullOrEmpty() && privateKey.isNullOrBlank()
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
  UNREACHABLE, AUTH, HOST_KEY_UNKNOWN, HOST_KEY_CHANGED, NO_SHELL, DSH_NOT_FOUND,
  DSH_START, DSH_TIMEOUT, TUNNEL, LOGIN, DISCONNECTED, UNRESPONSIVE, BAD_KEY, OTHER
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
  val dshPath: String? = null
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
