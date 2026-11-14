package com.igng.opencode.lagoon.core

import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val MAX_PAIRING_SCAN_BYTES = 4096
private const val OFFER_PAST_SKEW_MS = 120_000L
private const val OFFER_FUTURE_WINDOW_MS = 600_000L
private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val TICKET_FRAGMENT_REGEX = Regex("^v1\\.([A-Za-z0-9_-]{43})$")
private val CODE_8_DIGITS_REGEX = Regex("^[0-9]{8}$")

internal enum class RemotePairingState {
  CREATED,
  CLAIMED,
  APPROVED,
  COMPLETED,
  FAILED,
  CANCELLED,
  EXPIRED;

  val isTerminal: Boolean
    get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED

  companion object {
    fun fromWire(value: Any?): RemotePairingState? = when (value) {
      "created" -> CREATED
      "claimed" -> CLAIMED
      "approved" -> APPROVED
      "completed" -> COMPLETED
      "failed" -> FAILED
      "cancelled" -> CANCELLED
      "expired" -> EXPIRED
      else -> null
    }
  }
}

internal class RemotePairingException(
  val code: String?,
  message: String,
  val retryAfterMs: Long? = null,
  cause: Throwable? = null,
) : IOException(message, cause)

internal data class RemotePairingDevice(
  val deviceId: String,
  val name: String,
  val role: String,
  val platform: String,
  val identityKey: String,
  val clientVersion: String? = null,
  val harnessVersion: String? = null,
) {
  /** Full 64-char lowercase SHA-256 hex of the decoded X25519 identity key. */
  val fingerprint: String
    get() = remotePairingFingerprint(identityKey)

  fun sameIdentity(other: RemotePairingDevice): Boolean =
    deviceId == other.deviceId && identityKey == other.identityKey && role == other.role

  fun toJson(): JSONObject = JSONObject()
    .put("deviceId", deviceId)
    .put("name", name)
    .put("role", role)
    .put("platform", platform)
    .put("identityKey", identityKey)
    .apply {
      clientVersion?.let { put("clientVersion", it) }
      harnessVersion?.let { put("harnessVersion", it) }
    }

  companion object {
    fun fromJson(obj: JSONObject?): RemotePairingDevice? {
      if (obj == null) return null
      val id = (obj.opt("deviceId") as? String)?.takeIf { it.isNotEmpty() && it.length <= 128 && RemoteServerApi.validDeviceId(it) }
        ?: return null
      val name = (obj.opt("name") as? String)?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
      val role = (obj.opt("role") as? String)?.takeIf { it == "host" || it == "client" } ?: return null
      val platform = (obj.opt("platform") as? String)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
      val key = (obj.opt("identityKey") as? String)?.takeIf(RemoteServerApi::validPublicKey) ?: return null
      val clientVersion = (obj.opt("clientVersion") as? String)?.takeIf { it.isNotBlank() && it.length <= 64 }
      val harnessVersion = (obj.opt("harnessVersion") as? String)?.takeIf { it.isNotBlank() && it.length <= 64 }
      return RemotePairingDevice(id, name, role, platform, key, clientVersion, harnessVersion)
    }
  }
}

internal sealed class RemotePairingScan {
  abstract val origin: String
  abstract val isCleartext: Boolean

  data class Ticket(
    override val origin: String,
    val ticket: String,
    override val isCleartext: Boolean,
  ) : RemotePairingScan() {
    override fun toString(): String = "RemotePairingScan.Ticket(origin=$origin, ticket=<redacted>, isCleartext=$isCleartext)"
  }

  data class Legacy(
    override val origin: String,
    val offer: RemotePairingOffer,
    override val isCleartext: Boolean,
  ) : RemotePairingScan()
}

internal data class RemotePairingOffer(
  val origin: String,
  val sessionId: String,
  val expiresAtMillis: Long,
  val serverPublicKey: String,
  val scanSecret: String,
  val host: RemotePairingDevice,
) {
  override fun toString(): String =
    "RemotePairingOffer(origin=$origin, sessionId=$sessionId, expiresAtMillis=$expiresAtMillis, serverPublicKey=$serverPublicKey, scanSecret=<redacted>, host=$host)"

  companion object {
    fun parse(
      json: JSONObject?,
      origin: String,
      ticket: String? = null,
      nowMillis: Long = System.currentTimeMillis(),
    ): RemotePairingOffer {
      if (json == null || json.optInt("v", -1) != 1) {
        throw RemotePairingException("PAIRING_SCAN_INVALID", "配对会话版本不受支持")
      }
      val sessionId = (json.opt("sessionId") as? String)?.takeIf { UUID_REGEX.matches(it) }
        ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "配对会话 ID 无效")
      val expiresAtRaw = json.opt("expiresAt") as? Number
        ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "配对会话有效期无效")
      val expiresDouble = expiresAtRaw.toDouble()
      if (!expiresDouble.isFinite() || expiresDouble % 1.0 != 0.0 || expiresDouble <= 0) {
        throw RemotePairingException("PAIRING_SCAN_INVALID", "配对会话有效期无效")
      }
      val expiresAtMillis = expiresAtRaw.toLong()
      val serverKey = (json.opt("serverPublicKey") as? String)?.takeIf(RemoteServerApi::validPublicKey)
        ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "服务端临时公钥无效")
      val scanSecret = (json.opt("scanSecret") as? String)?.takeIf(RemoteServerApi::validPublicKey)
        ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "配对凭据格式无效")
      if (ticket != null && scanSecret != ticket) {
        throw RemotePairingException("PAIRING_SCAN_INVALID", "配对凭据与二维码不匹配")
      }
      val host = RemotePairingDevice.fromJson(json.optJSONObject("host"))
        ?.takeIf { it.role == "host" && it.identityKey != serverKey }
        ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "Host 身份信息无效")
      if (expiresAtMillis < nowMillis - OFFER_PAST_SKEW_MS || expiresAtMillis > nowMillis + OFFER_FUTURE_WINDOW_MS) {
        throw RemotePairingException("PAIRING_EXPIRED", "配对码已过期，请在电脑上重新生成")
      }
      return RemotePairingOffer(origin, sessionId, expiresAtMillis, serverKey, scanSecret, host)
    }
  }
}

internal data class RemotePairingClaim(
  val verificationCode: String,
  val expiresAtMillis: Long,
  val frozenRequestBody: String,
) {
  override fun toString(): String =
    "RemotePairingClaim(verificationCode=<redacted>, expiresAtMillis=$expiresAtMillis, frozenRequestBody=<redacted>)"
}

internal data class RemotePairingCredentials(
  val account: String,
  val accountKind: String,
  val device: RemotePairingDevice,
  val tokens: RemoteTokenPair,
  val pairingRestricted: Boolean,
) {
  override fun toString(): String =
    "RemotePairingCredentials(account=$account, accountKind=$accountKind, device=$device, tokens=<redacted>, pairingRestricted=$pairingRestricted)"
}

internal data class RemotePairingStatus(
  val state: RemotePairingState,
  val recoverUntilMillis: Long?,
  val failureReason: String?,
  val credentials: RemotePairingCredentials?,
)

/** Computes `lowercaseHex(SHA256(decodedIdentityKey))` per `device-pairing-v1.md`. */
internal fun remotePairingFingerprint(identityKey: String): String {
  require(RemoteServerApi.validPublicKey(identityKey)) { "配对设备公钥无效" }
  val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(identityKey))
  return digest.joinToString("") { "%02x".format(it) }
}

/** Formats 64-char hex into 16 readable groups of 4 (`1a2b 3c4d ...`). */
internal fun formatRemotePairingFingerprint(fingerprint: String): String =
  fingerprint.lowercase().chunked(4).joinToString(" ")

/** Parses and strictly validates either a `fragment-ticket-v1` URL or a legacy `dsh.remote.pairing` JSON QR. */
internal fun parseRemotePairingScan(
  raw: String,
  allowCleartext: Boolean = false,
  nowMillis: Long = System.currentTimeMillis(),
): RemotePairingScan {
  if (raw.toByteArray(Charsets.UTF_8).size > MAX_PAIRING_SCAN_BYTES) {
    throw RemotePairingException("PAIRING_SCAN_INVALID", "扫描内容过长")
  }
  val value = raw.trim()
  if (value.isEmpty()) throw RemotePairingException("PAIRING_SCAN_INVALID", "配对二维码为空")
  if (value.startsWith("{")) {
    val json = runCatching { JSONObject(value) }.getOrNull()
      ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "这不是有效的 DSH 配对二维码")
    if (json.opt("type") != "dsh.remote.pairing") {
      throw RemotePairingException("PAIRING_SCAN_INVALID", "这不是 DSH 设备配对二维码")
    }
    val serverUrl = (json.opt("serverUrl") as? String)?.takeIf { it.isNotEmpty() }
      ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "配对码缺少服务器地址")
    val origin = runCatching { canonicalRemoteOrigin(serverUrl) }.getOrNull()
      ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "配对码中的服务器地址无效")
    if (origin.removeSuffix("/") != serverUrl) {
      throw RemotePairingException("PAIRING_SCAN_INVALID", "配对码中的服务器地址不规范")
    }
    val isCleartext = origin.startsWith("http://")
    if (isCleartext && !allowCleartext) {
      throw RemotePairingException("CLEARTEXT_HTTP_DISABLED", "配对码使用不安全的 HTTP 地址，需先显式允许明文连接")
    }
    val offer = RemotePairingOffer.parse(json, origin = origin, ticket = null, nowMillis = nowMillis)
    return RemotePairingScan.Legacy(origin, offer, isCleartext)
  }

  val uri = runCatching { URI(value) }.getOrNull()
    ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "这不是 DSH 配对链接或二维码")
  val scheme = uri.scheme?.lowercase()
  val authority = uri.rawAuthority
  val fragmentMatch = uri.rawFragment?.let { TICKET_FRAGMENT_REGEX.matchEntire(it) }
  val ticket = fragmentMatch?.groupValues?.getOrNull(1)
  if (
    (scheme != "https" && scheme != "http") ||
    authority.isNullOrEmpty() ||
    uri.host.isNullOrEmpty() ||
    uri.rawUserInfo != null ||
    uri.rawQuery != null ||
    uri.rawPath != "/pair" ||
    ticket == null ||
    !RemoteServerApi.validPublicKey(ticket)
  ) {
    throw RemotePairingException("PAIRING_SCAN_INVALID", "这不是有效的 DSH 扫码配对凭据")
  }
  val origin = runCatching { canonicalRemoteOrigin("$scheme://$authority") }.getOrNull()
    ?: throw RemotePairingException("PAIRING_SCAN_INVALID", "配对码中的服务器地址无效")
  val isCleartext = origin.startsWith("http://")
  if (isCleartext && !allowCleartext) {
    throw RemotePairingException("CLEARTEXT_HTTP_DISABLED", "配对码使用不安全的 HTTP 地址，需先显式允许明文连接")
  }
  return RemotePairingScan.Ticket(origin, ticket, isCleartext)
}

/**
 * RFC 5869 HKDF-SHA256. Does not mutate caller buffers.
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
  require(length in 1..(255 * 32)) { "HKDF 输出长度无效" }
  val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
  val prk = hmacSha256(actualSalt, ikm)
  val output = ByteArray(length)
  var previous = ByteArray(0)
  var offset = 0
  var counter = 1
  return try {
    while (offset < length) {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(prk, "HmacSHA256"))
      mac.update(previous)
      mac.update(info)
      mac.update(counter.toByte())
      previous = mac.doFinal()
      val copyLen = minOf(previous.size, length - offset)
      System.arraycopy(previous, 0, output, offset, copyLen)
      offset += copyLen
      counter++
    }
    output
  } finally {
    prk.fill(0)
    previous.fill(0)
  }
}

/** Derives the 32-byte HKDF-SHA256 `X-DSH-Pairing-Proof` MAC key (`side` is `"mobile"` or `"host"`). */
internal fun remotePairingProofKey(
  devicePrivateKey: ByteArray,
  serverPublicKey: String,
  sessionId: String,
  side: String,
): ByteArray {
  require(side == "mobile" || side == "host") { "配对角色必须是 mobile 或 host" }
  if (devicePrivateKey.size != 32 || !RemoteServerApi.validPublicKey(serverPublicKey)) {
    throw RemotePairingException("PAIRING_PROOF_INVALID", "配对密钥格式无效")
  }
  val privCopy = devicePrivateKey.copyOf()
  val peerPub = Base64.getUrlDecoder().decode(serverPublicKey)
  val shared = ByteArray(32)
  return try {
    val ok = X25519.calculateAgreement(privCopy, 0, peerPub, 0, shared, 0)
    if (!ok || shared.all { it == 0.toByte() }) {
      throw RemotePairingException("PAIRING_PROOF_INVALID", "配对会话密钥为无效低阶点")
    }
    hkdfSha256(
      ikm = shared,
      salt = sessionId.toByteArray(Charsets.UTF_8),
      info = "dsh-pairing-v1/$side".toByteArray(Charsets.UTF_8),
      length = 32,
    )
  } finally {
    privCopy.fill(0)
    shared.fill(0)
  }
}

/** Computes `X-DSH-Pairing-Proof` over `"POST\n$path\n${sha256Hex(rawJson)}"`. */
internal fun remotePairingProof(
  devicePrivateKey: ByteArray,
  serverPublicKey: String,
  sessionId: String,
  side: String,
  path: String,
  rawJson: String,
): String {
  val proofKey = remotePairingProofKey(devicePrivateKey, serverPublicKey, sessionId, side)
  return try {
    val bodyHash = MessageDigest.getInstance("SHA-256")
      .digest(rawJson.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    val message = "POST\n$path\n$bodyHash".toByteArray(Charsets.UTF_8)
    val mac = hmacSha256(proofKey, message)
    Base64.getUrlEncoder().withoutPadding().encodeToString(mac)
  } finally {
    proofKey.fill(0)
  }
}

internal fun pairingClaimBody(
  scanSecret: String,
  device: RemotePairingDevice,
  deepseekUserId: String? = null,
): String {
  val root = JSONObject()
    .put("scanSecret", scanSecret)
    .put("device", device.toJson())
  if (deepseekUserId != null) {
    require(deepseekUserId.isNotEmpty() && deepseekUserId.length <= 256) { "deepseekUserId 无效" }
    root.put("deepseekUserId", deepseekUserId)
  }
  return root.toString()
}

private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(key, "HmacSHA256"))
  return mac.doFinal(message)
}

internal class RemotePairingClient(private val api: RemoteServerApi) {
  suspend fun resolve(
    scan: RemotePairingScan,
    nowMillis: Long = System.currentTimeMillis(),
  ): RemotePairingOffer = when (scan) {
    is RemotePairingScan.Legacy -> {
      if (scan.offer.expiresAtMillis < nowMillis - OFFER_PAST_SKEW_MS ||
        scan.offer.expiresAtMillis > nowMillis + OFFER_FUTURE_WINDOW_MS
      ) {
        throw RemotePairingException("PAIRING_EXPIRED", "配对码已过期，请在电脑上重新生成")
      }
      scan.offer
    }
    is RemotePairingScan.Ticket -> {
      val body = JSONObject().put("ticket", scan.ticket).toString()
      val response = executePairingPost("/api/v1/device-pairing/resolve", body, isResolve = true)
      RemotePairingOffer.parse(response, origin = scan.origin, ticket = scan.ticket, nowMillis = nowMillis)
    }
  }

  suspend fun claim(
    offer: RemotePairingOffer,
    mobileIdentity: RemoteIdentity,
    device: RemotePairingDevice,
    frozenRequestBody: String? = null,
  ): RemotePairingClaim {
    val rawBody = frozenRequestBody ?: pairingClaimBody(offer.scanSecret, device)
    val path = "/api/v1/device-pairing/sessions/${offer.sessionId}/claim"
    val proof = signProof(mobileIdentity, offer, path, rawBody)
    val response = executePairingPost(path, rawBody, mapOf("X-DSH-Pairing-Proof" to proof))
    val state = RemotePairingState.fromWire(response.opt("state"))
    val code = (response.opt("verificationCode") as? String)?.takeIf { CODE_8_DIGITS_REGEX.matches(it) }
    val expires = (response.opt("expiresAt") as? Number)?.toLong()
    if (state != RemotePairingState.CLAIMED || code == null || expires == null || expires <= 0L) {
      throw RemotePairingException("PAIRING_FAILED", "服务端返回了无效的验证码响应")
    }
    return RemotePairingClaim(code, expires, rawBody)
  }

  suspend fun pollStatus(
    offer: RemotePairingOffer,
    mobileIdentity: RemoteIdentity,
    mobileDevice: RemotePairingDevice,
    nowMillis: Long = System.currentTimeMillis(),
  ): RemotePairingStatus {
    val path = "/api/v1/device-pairing/sessions/${offer.sessionId}/mobile/status"
    val rawBody = "{}"
    val proof = signProof(mobileIdentity, offer, path, rawBody)
    val response = executePairingPost(path, rawBody, mapOf("X-DSH-Pairing-Proof" to proof))
    val state = RemotePairingState.fromWire(response.opt("state"))
      ?: throw RemotePairingException("PAIRING_FAILED", "服务端返回了无效的配对状态")
    val sessionId = response.opt("sessionId") as? String
    val host = RemotePairingDevice.fromJson(response.optJSONObject("host"))
    val phone = RemotePairingDevice.fromJson(response.optJSONObject("mobile"))
    if (
      response.optInt("v", -1) != 1 ||
      sessionId != offer.sessionId ||
      host == null ||
      !host.sameIdentity(offer.host) ||
      phone == null ||
      !phone.sameIdentity(mobileDevice)
    ) {
      throw RemotePairingException("PAIRING_IDENTITY_MISMATCH", "配对会话与当前电脑或本机身份不一致，已中止")
    }
    val recoverUntil = (response.opt("recoverUntil") as? Number)?.toLong()?.takeIf { it > 0L }
    val failureReason = (response.opt("failureReason") as? String)?.takeIf { it.isNotEmpty() && it.length <= 128 }
    val credentials = if (state == RemotePairingState.COMPLETED) {
      parseCredentials(response.optJSONObject("credentials"), mobileDevice, nowMillis)
    } else null
    return RemotePairingStatus(state, recoverUntil, failureReason, credentials)
  }

  suspend fun cancel(offer: RemotePairingOffer, mobileIdentity: RemoteIdentity) {
    val path = "/api/v1/device-pairing/sessions/${offer.sessionId}/mobile/cancel"
    val rawBody = "{}"
    val proof = signProof(mobileIdentity, offer, path, rawBody)
    executePairingPost(path, rawBody, mapOf("X-DSH-Pairing-Proof" to proof))
  }

  private fun parseCredentials(
    obj: JSONObject?,
    mobileDevice: RemotePairingDevice,
    nowMillis: Long,
  ): RemotePairingCredentials {
    if (obj == null) throw RemotePairingException("PAIRING_IDENTITY_MISMATCH", "服务端未返回设备凭据")
    val account = (obj.opt("account") as? String)?.takeIf { it.isNotBlank() && it.length <= 320 && '\u0000' !in it }
    val kind = (obj.opt("accountKind") as? String)?.takeIf { it == "device_only" || it == "standard" }
    val device = RemotePairingDevice.fromJson(obj.optJSONObject("device"))
    val inboundOnly = obj.opt("inboundOnly")
    val restricted = obj.opt("pairingRestricted")
    val tokensObj = obj.optJSONObject("tokens")
    val tokens = tokensObj?.let { runCatching { api.tokenPair(it) }.getOrNull() }
    if (
      account == null ||
      kind == null ||
      device == null ||
      !device.sameIdentity(mobileDevice) ||
      device.role != "client" ||
      inboundOnly != false ||
      restricted !is Boolean ||
      tokens == null ||
      tokens.accessTokenExpiresAt <= nowMillis ||
      tokens.refreshTokenExpiresAt <= nowMillis
    ) {
      throw RemotePairingException("PAIRING_IDENTITY_MISMATCH", "服务端返回的设备凭据未通过校验")
    }
    return RemotePairingCredentials(account, kind, device, tokens, restricted)
  }

  private fun signProof(
    identity: RemoteIdentity,
    offer: RemotePairingOffer,
    path: String,
    rawJson: String,
  ): String {
    require(RemoteServerApi.validPublicKey(identity.privateKey)) { "本机配对私钥无效" }
    val privateBytes = Base64.getUrlDecoder().decode(identity.privateKey)
    return try {
      remotePairingProof(
        devicePrivateKey = privateBytes,
        serverPublicKey = offer.serverPublicKey,
        sessionId = offer.sessionId,
        side = "mobile",
        path = path,
        rawJson = rawJson,
      )
    } finally {
      privateBytes.fill(0)
    }
  }

  private suspend fun executePairingPost(
    path: String,
    rawJson: String,
    headers: Map<String, String> = emptyMap(),
    isResolve: Boolean = false,
  ): JSONObject = try {
    api.postRawJson(path, rawJson, headers)
  } catch (error: RemoteServerException) {
    throw mapServerException(error, isResolve)
  }

  private fun mapServerException(error: RemoteServerException, isResolve: Boolean): RemotePairingException {
    val code = error.code
    val retryAfterMs = when {
      error.status == 429 || code in setOf("RATE_LIMITED", "PAIRING_BUSY", "PAIRING_REQUEST_RATE_LIMIT") -> 5_000L
      error.status == 503 || code == "AUTHORIZATION_SYNC_PENDING" -> 3_000L
      else -> null
    }
    val message = when {
      code == "PAIRING_NOT_FOUND" || (error.status == 404 && code == null) ->
        if (isResolve) "服务端不支持扫码设备配对，或配对码已过期" else "配对会话已过期或不存在，请重新扫码"
      code == "PAIRING_EXPIRED" -> "配对码已过期，请在电脑上重新生成"
      code in setOf("PAIRING_PROOF_INVALID", "PAIRING_SCAN_INVALID") -> "配对安全校验未通过，请重新扫码"
      code in setOf("PAIRING_NOT_CLAIMED", "PAIRING_CLOSED") -> "配对会话已关闭，请重新扫码"
      code == "PAIRING_DEVICE_CONFLICT" -> "设备身份冲突或已被撤销，无法完成配对"
      code == "PAIRING_ACCOUNT_CONFLICT" -> "电脑与当前手机属于不同账号，未覆盖现有凭据"
      error.status == 429 || code in setOf("RATE_LIMITED", "PAIRING_BUSY", "PAIRING_REQUEST_RATE_LIMIT") ->
        "请求过于频繁，请稍候"
      error.status == 503 || code == "AUTHORIZATION_SYNC_PENDING" ->
        "中枢正在同步授权状态，请稍候重试"
      error.status == 403 -> "当前中枢拒绝了此配对操作"
      else -> userFacingError(error, "设备配对请求失败")
    }
    return RemotePairingException(code ?: "HTTP_${error.status}", message, retryAfterMs, error)
  }
}
