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
  val trust: RemoteTrustState = RemoteTrustState.UNKNOWN
)

internal data class RemoteCatalog(
  val origin: String,
  val account: String,
  val provider: String?,
  val deviceLabel: String,
  val hosts: List<RemoteHostMeta>,
  val updatedAt: Long,
  val allowCleartext: Boolean = false,
  /** Source/Host metadata remains available for local hierarchy when account credentials are signed out. */
  val signedOut: Boolean = false
) {
  val key: String get() = "$origin\u0000$account"
  val requiresCleartextConsent: Boolean
    get() = origin.trim().startsWith("http://", ignoreCase = true) && !allowCleartext
}

/** Authenticated protocol readiness, not guessed from Host name or advertised app version. */
internal data class RemoteHostServices(val dshAvailable: Boolean, val native: NativeServiceCatalog?, val codexAvailable: Boolean = false)

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
    .put("hosts", JSONArray().apply {
      catalog.hosts.forEach { host ->
        put(JSONObject().put("hostId", host.hostId).put("name", host.name).put("platform", host.platform)
          .put("online", host.online).put("lastSeenAt", host.lastSeenAt ?: JSONObject.NULL)
          .put("harnessVersion", host.harnessVersion ?: JSONObject.NULL)
          .put("pluginVersion", host.pluginVersion ?: JSONObject.NULL)
          .put("fingerprint", host.fingerprint ?: JSONObject.NULL)
          .put("trust", host.trust.name))
      }
    }).toString()

  fun decode(text: String): RemoteCatalog {
    try {
      val json = JSONObject(text)
      if (json.optInt("version") != 1) throw IOException("Remote 主机目录版本无效")
      val origin = canonicalRemoteOrigin(json.getString("origin"))
      val account = json.getString("account").also {
        if (it.isBlank() || it.length > 254 || '\u0000' in it) throw IOException("Remote 主机目录账号无效")
      }
      val provider = (json.opt("provider") as? String)?.takeIf { it.length <= 64 }
      val label = json.getString("deviceLabel").also { if (it.isBlank() || it.length > 128) throw IOException("Remote 设备名称无效") }
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
          name = item.getString("name").take(128),
          platform = item.getString("platform").take(64),
          online = item.optBoolean("online"),
          lastSeenAt = item.optLong("lastSeenAt", 0).takeIf { it > 0 },
          harnessVersion = (item.opt("harnessVersion") as? String)?.take(64),
          pluginVersion = (item.opt("pluginVersion") as? String)?.take(64),
          fingerprint = fingerprint,
          trust = runCatching { RemoteTrustState.valueOf(item.optString("trust")) }.getOrDefault(RemoteTrustState.UNKNOWN)
        )
      }
      if (hosts.distinctBy { it.hostId }.size != hosts.size) throw IOException("Remote 主机目录重复")
      return RemoteCatalog(origin, account, provider, label, hosts, updated, json.optBoolean("allowCleartext", false), json.optBoolean("signedOut", false))
    } catch (error: IOException) { throw error }
    catch (error: Exception) { throw IOException("Remote 主机目录损坏", error) }
  }
}
