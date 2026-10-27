package com.igng.opencode.lagoon.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A client access key issued by the hub so another client can import its servers: `lgk1.<id>.<secret>`.
 * The id names the key on the hub; the 32-byte secret never travels — requests are signed with it and
 * the export is encrypted with it (see [HubExportCrypto]), so a plain-HTTP hub on the LAN does not leak
 * harness credentials to whoever watches or intercepts the traffic.
 */
class HubAccessKey(val id: String, secret: ByteArray) {
  private val bytes = secret.copyOf()

  init {
    require(ID.matches(id)) { "接入密钥标识无效" }
    require(bytes.size == SECRET_BYTES) { "接入密钥长度无效" }
  }

  val secret: ByteArray get() = bytes.copyOf()
  val encoded: String get() = "$PREFIX.$id.${b64(bytes)}"

  override fun equals(other: Any?): Boolean = other is HubAccessKey && other.id == id && MessageDigest.isEqual(other.bytes, bytes)
  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "HubAccessKey($id)"

  companion object {
    const val PREFIX = "lgk1"
    const val SECRET_BYTES = 32
    private val ID = Regex("[a-z0-9]{8}")
    private const val ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    fun generate(random: SecureRandom = SecureRandom()): HubAccessKey =
      HubAccessKey((1..8).map { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] }.joinToString(""), ByteArray(SECRET_BYTES).also(random::nextBytes))

    /** The key in [text], tolerating surrounding whitespace; null when it is not a well-formed key. */
    fun parse(text: String): HubAccessKey? {
      val parts = text.trim().split('.')
      if (parts.size != 3 || parts[0] != PREFIX || !ID.matches(parts[1])) return null
      val secret = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull() ?: return null
      return if (secret.size == SECRET_BYTES) HubAccessKey(parts[1], secret) else null
    }
  }
}

/** Where a client imports from: the hub's address and the access key that authorizes it. */
data class HubImportTarget(val hubUrl: String, val key: HubAccessKey)

/**
 * The single string the hub's web page hands out: `<hub address>/#key=lgk1.…`. The key sits in the
 * fragment, which browsers never send, so opening the link by mistake only shows the hub's login page.
 */
object HubImportLink {
  fun build(hubUrl: String, key: HubAccessKey): String = hubUrl.trim().trimEnd('/') + "/#key=" + key.encoded

  fun parse(text: String): HubImportTarget? {
    val value = text.trim()
    val fragment = value.substringAfter('#', "")
    val key = fragment.split('&').firstOrNull { it.startsWith("key=") }?.removePrefix("key=")?.let(HubAccessKey::parse) ?: return null
    val hub = hubAddress(value.substringBefore('#')) ?: return null
    return HubImportTarget(hub, key)
  }

  /** The hub's base address in [text] (scheme, host, port, mount path), without query or fragment. */
  fun hubAddress(text: String): String? {
    val url = text.trim().toHttpUrlOrNull() ?: return null
    return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
  }
}

/** One server as the hub exports it. [profile]'s id is the hub's own id for it ([hubServerId]). */
data class ExportedServer(val profile: ServerProfile, val credentials: ServerCredentials,
  /** The address only works from the hub's own machine or container (loopback, `host.docker.internal`, a bare container name). */
  val localOnly: Boolean = false) {
  val hubServerId: String get() = profile.id
}

/** Everything a client imports from one hub at one moment. */
data class HubExport(val hubId: String, val exportedAt: Long, val servers: List<ExportedServer>)

/** The decrypted export document. Unknown fields are ignored so the hub can add more later. */
object HubExportCodec {
  const val VERSION = 1

  fun encode(export: HubExport): JSONObject = JSONObject()
    .put("version", VERSION).put("hubId", export.hubId).put("exportedAt", export.exportedAt)
    .put("servers", JSONArray().apply {
      export.servers.forEach { server ->
        val profile = server.profile
        put(JSONObject().put("hubServerId", profile.id).put("name", profile.name).put("url", profile.url).put("username", profile.username)
          .put("kind", profile.kind.wire).put("dshAuth", profile.dshAuth.wire).put("allowCleartext", profile.allowCleartext)
          .put("autoConnect", profile.autoConnect).put("notifications", profile.notifications)
          .put("credentialUsername", server.credentials.username).put("secret", server.credentials.password)
          .put("cookie", server.credentials.cookie).put("localOnly", server.localOnly))
      }
    })

  fun decode(json: JSONObject): HubExport {
    val version = json.optInt("version", 0)
    if (version > VERSION) throw IllegalStateException("中枢版本较新，请先更新 Lagoon 再导入")
    if (version < 1) throw IllegalStateException("中枢返回的配置格式无效")
    val hubId = json.optString("hubId").trim()
    if (hubId.isEmpty()) throw IllegalStateException("中枢返回的配置缺少中枢标识")
    val list = json.optJSONArray("servers") ?: JSONArray()
    val servers = (0 until list.length()).mapNotNull(list::optJSONObject).mapNotNull { item ->
      val id = item.optString("hubServerId").trim()
      val url = item.optString("url").trim().trimEnd('/')
      if (id.isEmpty() || url.toHttpUrlOrNull() == null) return@mapNotNull null
      val kind = ServerKind.fromWire(item.optString("kind"))
      val username = item.optString("username").trim().ifBlank { if (kind == ServerKind.DSH) "dsh" else "opencode" }
      val profile = ServerProfile(id, item.optString("name").trim().ifBlank { kind.label }, url, username,
        item.optBoolean("autoConnect", true), item.optBoolean("notifications", true),
        url.startsWith("http://", true) && item.optBoolean("allowCleartext", false), kind,
        if (kind == ServerKind.DSH) DshAuthMode.fromWire(item.optString("dshAuth")) else DshAuthMode.TOKEN)
      ExportedServer(profile, ServerCredentials(item.optString("credentialUsername").ifBlank { username },
        item.optString("secret"), item.optString("cookie")), item.optBoolean("localOnly"))
    }
    return HubExport(hubId, json.optLong("exportedAt"), servers)
  }
}

/**
 * The export handshake. The client sends `{keyId, ts, nonce, mac}` where `mac` signs the key id, time
 * and nonce with a key derived from the secret; the hub answers `{version, iv, data}`, the export
 * document sealed with AES-256-GCM under a second derived key, bound to the key id and the nonce.
 */
object HubExportCrypto {
  /** How far the client's clock may be from the hub's. */
  const val MAX_SKEW_MS = 10 * 60_000L
  private const val LABEL = "lagoon-export-v1"
  private val SALT = "lagoon-hub-export".toByteArray()

  fun nonce(random: SecureRandom = SecureRandom()): String = b64(ByteArray(18).also(random::nextBytes))

  fun mac(key: HubAccessKey, ts: Long, nonce: String): String =
    b64(hmac(derive(key, "auth"), "$LABEL\n${key.id}\n$ts\n$nonce".toByteArray()))

  fun verify(key: HubAccessKey, ts: Long, nonce: String, mac: String): Boolean =
    MessageDigest.isEqual(mac(key, ts, nonce).toByteArray(), mac.toByteArray())

  /** The request body for one export. */
  fun request(key: HubAccessKey, now: Long, nonce: String = nonce()): JSONObject =
    JSONObject().put("keyId", key.id).put("ts", now).put("nonce", nonce).put("mac", mac(key, now, nonce))

  fun seal(key: HubAccessKey, nonce: String, plain: String, random: SecureRandom = SecureRandom()): JSONObject {
    val iv = ByteArray(12).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(key, "enc"), "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(aad(key, nonce))
    return JSONObject().put("version", HubExportCodec.VERSION).put("iv", b64(iv)).put("data", b64(cipher.doFinal(plain.toByteArray(Charsets.UTF_8))))
  }

  /** Opens what [seal] produced for the request carrying [nonce]; throws when anything was altered. */
  fun open(key: HubAccessKey, nonce: String, sealed: JSONObject): String {
    val iv = Base64.getUrlDecoder().decode(sealed.optString("iv"))
    val data = Base64.getUrlDecoder().decode(sealed.optString("data"))
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(key, "enc"), "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(aad(key, nonce))
    return String(cipher.doFinal(data), Charsets.UTF_8)
  }

  private fun aad(key: HubAccessKey, nonce: String) = "$LABEL\n${key.id}\n$nonce".toByteArray()

  private fun derive(key: HubAccessKey, purpose: String): ByteArray = hkdf(key.secret, SALT, "$LABEL $purpose".toByteArray())

  /** HKDF-SHA256 (RFC 5869) for one 32-byte output block. */
  internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
    val prk = hmac(salt, ikm)
    return hmac(prk, info + byteArrayOf(1))
  }

  private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}

/**
 * Whether [url] names the hub's own machine or container network rather than an address another
 * device can reach: loopback, Docker's host aliases, or a single-label (container) name.
 */
fun isLocalOnlyAddress(url: String): Boolean {
  val host = url.trim().toHttpUrlOrNull()?.host?.lowercase() ?: return false
  if (host == "localhost" || host == "0.0.0.0" || host == "::1" || host.startsWith("127.")) return true
  if (host == "host.docker.internal" || host == "gateway.docker.internal") return true
  return '.' !in host && ':' !in host
}

private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
