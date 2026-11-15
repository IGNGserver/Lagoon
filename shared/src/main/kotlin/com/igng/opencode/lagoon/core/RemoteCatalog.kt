package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Display-only trust state for one Host; the cryptographic pin remains in RemotePeerTrust. */
internal enum class RemoteTrustState { UNKNOWN, PINNED, CHANGED }

/** A Host in the account catalog. Secrets/public keys are never persisted here. */
internal data class RemoteHostMeta(
  val hostId: String,
  val name: String,
  val platform: String,
  val online: Boolean,
  val lastSeenAt: Long?,
  val harnessVersion: String?,
  val pluginVersion: String?,
  val fingerprint: String?,
  val trust: RemoteTrustState = RemoteTrustState.UNKNOWN,
  /** 手机本地显示名；只影响本机 UI，不回写中枢，也不参与主机身份。 */
  val alias: String? = null
) {
  /** Host 自报名不可用（空或全问号）时仍保留原始字段供诊断，但界面不应直接展示。 */
  val nameUnavailable: Boolean get() = name.isBlank() || name.all { it == '?' }

  val displayName: String
    get() = alias?.takeIf { it.isNotBlank() }
      ?: name.takeUnless { it.isBlank() || it.all { c -> c == '?' } }
      ?: "未命名主机"
}

/** 保留本地可读名称和别名：一次坏上报（例如 Windows 的 `???`）不应把已显示的设备名改坏。 */
internal fun RemoteHostMeta.withPreviousDisplay(previous: RemoteHostMeta?): RemoteHostMeta {
  if (previous == null) return this
  return copy(
    name = if (nameUnavailable && !previous.nameUnavailable) previous.name else name,
    alias = alias ?: previous.alias,
  )
}

/** 本地显示名清理：去控制字符、折叠空白、限长；空串表示恢复 Host 自报名。 */
internal fun sanitizeRemoteAlias(value: String?): String? {
  val cleaned = sanitizeRemoteDisplayName(value.orEmpty(), fallback = "", maxChars = 128)
  return cleaned.takeIf { it.isNotBlank() }
}

internal data class RemoteCatalog(
  val origin: String,
  val account: String,
  val provider: String?,
  val deviceLabel: String,
  val hosts: List<RemoteHostMeta>,
  val updatedAt: Long,
  val allowCleartext: Boolean = false,
  /** Source/Host metadata remains available for local hierarchy when account credentials are signed out. */
  val signedOut: Boolean = false,
  val adoptedHostIds: Set<String>? = null,
  /** Explicit user pause toggle for HUB ConnectionSource; null preserves legacy projection fallback. */
  val userPaused: Boolean? = null
) {
  val key: String get() = "$origin\u0000$account"
  val requiresCleartextConsent: Boolean
    get() = origin.trim().startsWith("http://", ignoreCase = true) && !allowCleartext

  fun effectiveAdoptedHostIds(): Set<String> = adoptedHostIds ?: hosts.map { it.hostId }.toSet()
}

/** Authenticated protocol readiness, not guessed from Host name or advertised app version. */
internal data class RemoteHostServices(val dshAvailable: Boolean, val native: NativeServiceCatalog?, val codexAvailable: Boolean = false, val agyAvailable: Boolean = false, val openCodeAcpAvailable: Boolean = false)

/** One local record per origin+account. Implementations must replace atomically. */
internal interface RemoteCatalogStore {
  fun load(): List<RemoteCatalog>
  fun save(catalog: RemoteCatalog)
  fun clear(origin: String, account: String)
}

internal object RemoteCatalogCodec {
  fun encode(catalog: RemoteCatalog): String = JSONObject()
    .put("version", 1).put("origin", catalog.origin).put("account", catalog.account)
    .put("provider", catalog.provider ?: JSONObject.NULL).put("deviceLabel", catalog.deviceLabel)
    .put("updatedAt", catalog.updatedAt)
    .put("allowCleartext", catalog.allowCleartext).put("signedOut", catalog.signedOut)
    .apply {
      if (catalog.adoptedHostIds != null) {
        put("adoptedHostIds", JSONArray(catalog.adoptedHostIds.toList()))
      }
      if (catalog.userPaused != null) {
        put("userPaused", catalog.userPaused)
      }
    }
    .put("hosts", JSONArray().apply {
      catalog.hosts.forEach { host ->
        put(JSONObject().put("hostId", host.hostId).put("name", host.name).put("platform", host.platform)
          .put("online", host.online).put("lastSeenAt", host.lastSeenAt ?: JSONObject.NULL)
          .put("harnessVersion", host.harnessVersion ?: JSONObject.NULL)
          .put("pluginVersion", host.pluginVersion ?: JSONObject.NULL)
          .put("fingerprint", host.fingerprint ?: JSONObject.NULL)
          .put("trust", host.trust.name).putOpt("alias", host.alias))
      }
    }).toString()

  fun decode(text: String): RemoteCatalog {
    try {
      val json = JSONObject(text)
      if (json.optInt("version") != 1) throw IOException("Remote 主机目录版本无效")
      val origin = canonicalRemoteOrigin(json.getString("origin"))
      val account = json.getString("account").also {
        if (it.isBlank() || it.length > 320 || '\u0000' in it) throw IOException("Remote 主机目录账号无效")
      }
      val provider = (json.opt("provider") as? String)?.takeIf { it.length <= 64 }
      val label = sanitizeRemoteDisplayName(json.getString("deviceLabel"), fallback = "", maxChars = 128).also {
        if (it.isBlank()) throw IOException("Remote 设备名称无效")
      }
      val updated = json.optLong("updatedAt", 0).takeIf { it > 0 } ?: System.currentTimeMillis()
      val rawHosts = json.optJSONArray("hosts") ?: throw IOException("Remote 主机目录无效")
      if (rawHosts.length() > 64) throw IOException("Remote 主机目录过大")
      val hosts = (0 until rawHosts.length()).map { index ->
        val item = rawHosts.optJSONObject(index) ?: throw IOException("Remote 主机目录无效")
        val hostId = item.getString("hostId")
        if (!RemoteServerApi.validDeviceId(hostId)) throw IOException("Remote 主机 ID 无效")
        val fingerprint = (item.opt("fingerprint") as? String)?.takeIf { it.matches(Regex("(?i)[0-9a-f]{12}")) }
        RemoteHostMeta(
          hostId = hostId,
          name = sanitizeRemoteDisplayName(item.getString("name"), fallback = hostId, maxChars = 128),
          platform = truncateByCodePointsSafe(item.getString("platform"), 64),
          online = item.optBoolean("online"),
          lastSeenAt = item.optLong("lastSeenAt", 0).takeIf { it > 0 },
          harnessVersion = (item.opt("harnessVersion") as? String)?.let { truncateByCodePointsSafe(it, 64) },
          pluginVersion = (item.opt("pluginVersion") as? String)?.let { truncateByCodePointsSafe(it, 64) },
          fingerprint = fingerprint,
          trust = runCatching { RemoteTrustState.valueOf(item.optString("trust")) }.getOrDefault(RemoteTrustState.UNKNOWN),
          alias = sanitizeRemoteAlias(item.opt("alias") as? String)
        )
      }
      if (hosts.distinctBy { it.hostId }.size != hosts.size) throw IOException("Remote 主机目录重复")
      val adoptedHostIds = json.optJSONArray("adoptedHostIds")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> (arr.opt(i) as? String)?.takeIf(RemoteServerApi::validDeviceId) }.toSet()
      }
      val userPaused = if (json.has("userPaused") && !json.isNull("userPaused")) {
        json.optBoolean("userPaused")
      } else null
      return RemoteCatalog(
        origin, account, provider, label, hosts, updated,
        json.optBoolean("allowCleartext", false),
        json.optBoolean("signedOut", false),
        adoptedHostIds,
        userPaused
      )
    } catch (error: IOException) { throw error }
    catch (error: Exception) { throw IOException("Remote 主机目录损坏", error) }
  }
}
