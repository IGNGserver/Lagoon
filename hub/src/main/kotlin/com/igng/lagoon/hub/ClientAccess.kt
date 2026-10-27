package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.ExportedServer
import com.igng.opencode.lagoon.core.HubAccessKey
import com.igng.opencode.lagoon.core.HubExport
import com.igng.opencode.lagoon.core.HubExportCodec
import com.igng.opencode.lagoon.core.HubExportCrypto
import com.igng.opencode.lagoon.core.credentialOrigin
import com.igng.opencode.lagoon.core.isLocalOnlyAddress
import org.json.JSONObject
import kotlin.math.abs

/**
 * Client access: the web page issues and revokes access keys, and other clients (the Android app)
 * use one to export the hub's servers with their credentials. The export route sits outside the
 * session cookie; it is authorized by the key's signature and its answer is encrypted with the key,
 * see [HubExportCrypto] and docs/HUB_DESIGN.md §7.
 */
class ClientAccess(private val store: HubStore, private val auth: Authenticator, private val clock: () -> Long = System::currentTimeMillis) {
  private val nonces = LinkedHashMap<String, Long>()

  fun register(router: Router) {
    router.get("/api/v1/client-keys") { call ->
      call.respond(JSONObject().put("keys", Codec.array(store.clientKeys().map(::keyJson))))
    }
    router.post("/api/v1/client-keys") { call ->
      val label = call.json().optString("label").trim()
      if (label.isEmpty()) throw HttpError(400, "label-empty", "请为接入密钥填写备注，例如设备名")
      val key = HubAccessKey.generate()
      val record = store.addClientKey(key, label)
      // The only time the full key leaves the hub; the page shows it once.
      call.respond(JSONObject().put("key", keyJson(record)).put("secret", key.encoded))
    }
    router.delete("/api/v1/client-keys/{id}") { call ->
      if (!store.removeClientKey(call.param("id"))) throw HttpError(404, "key-not-found", "接入密钥不存在")
      call.ok()
    }
    router.post(EXPORT_PATH) { call -> export(call) }
  }

  private fun keyJson(key: ClientKey) = JSONObject().put("id", key.id).put("label", key.label).put("created", key.created)
    .put("lastUsed", key.lastUsed).put("lastAddress", key.lastAddress)

  private fun export(call: Call) {
    val body = call.json(limit = 4096)
    val wait = auth.throttle.retryAfter(call.clientAddress)
    if (wait > 0) {
      val seconds = (wait + 999) / 1000
      throw HttpError(429, "throttled", "尝试次数过多，请 $seconds 秒后再试", seconds)
    }
    val keyId = body.optString("keyId")
    val ts = body.optLong("ts")
    val nonce = body.optString("nonce")
    val key = store.clientKey(keyId)
    if (key == null || nonce.length !in 16..64 || !HubExportCrypto.verify(key, ts, nonce, body.optString("mac"))) {
      auth.throttle.failed(call.clientAddress)
      throw HttpError(401, "bad-key", "接入密钥无效或已被吊销")
    }
    auth.throttle.succeeded(call.clientAddress)
    val now = clock()
    if (abs(now - ts) > HubExportCrypto.MAX_SKEW_MS) throw HttpError(400, "clock-skew", "设备时间与中枢相差超过 10 分钟，请校准时间后重试")
    if (!rememberNonce("$keyId:$nonce", now)) throw HttpError(409, "replayed", "该请求已处理过，请重新导入")
    store.touchClientKey(keyId, now, call.clientAddress)
    call.respond(HubExportCrypto.seal(key, nonce, HubExportCodec.encode(snapshot(now)).toString()))
  }

  /** The servers as another device should see them: the client address when one is set, credentials bound to it. */
  internal fun snapshot(now: Long): HubExport = HubExport(store.hubId, now, store.profiles().map { profile ->
    val credentials = store.credentials(profile.id)
    val url = store.clientUrl(profile.id).ifBlank { profile.url }
    // Cookies are bound to the address that issued them; another address gets the password/token/secret only.
    val sameOrigin = runCatching { credentialOrigin(url) == credentialOrigin(profile.url) }.getOrDefault(false)
    ExportedServer(profile.copy(url = url, allowCleartext = url.startsWith("http://", true)),
      credentials.copy(cookie = credentials.cookie.takeIf { sameOrigin }.orEmpty()), isLocalOnlyAddress(url))
  })

  /** False when [nonce] was already used inside the clock window. */
  private fun rememberNonce(nonce: String, now: Long): Boolean = synchronized(nonces) {
    val iterator = nonces.entries.iterator()
    while (iterator.hasNext()) { if (iterator.next().value < now - 2 * HubExportCrypto.MAX_SKEW_MS) iterator.remove() else break }
    if (nonces.containsKey(nonce)) return false
    nonces[nonce] = now
    while (nonces.size > MAX_NONCES) nonces.remove(nonces.keys.first())
    true
  }

  companion object {
    const val EXPORT_PATH = "/api/v1/client/export"
    private const val MAX_NONCES = 10_000
  }
}
