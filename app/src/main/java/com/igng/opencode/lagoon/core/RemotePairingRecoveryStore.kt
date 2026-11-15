package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

// A sheet can be closed and reopened while its previous synchronous HTTP call is still finishing.
internal val remotePairingOperationMutex = Mutex()

/** The original mobile identity and exact claim bytes, never the returned tokens or verification code. */
internal data class PendingRemotePairing(
  val offer: RemotePairingOffer,
  val identity: RemoteIdentity,
  val device: RemotePairingDevice,
  val claimBody: String,
  val allowCleartext: Boolean,
  val recoverUntilMillis: Long? = null,
  val cancelRequested: Boolean = false,
) {
  override fun toString(): String = "PendingRemotePairing(sessionId=${offer.sessionId}, secrets=<redacted>)"
}

internal data class RemotePairingRecoveryAttempt(
  val claim: RemotePairingClaim?,
  val claimError: Exception?,
  val status: RemotePairingStatus,
)

/** Do not send a ticket until the user has confirmed the exact parsed origin. */
internal suspend fun resolveConfirmedRemotePairing(
  raw: String,
  allowCleartext: Boolean,
  confirmedOrigin: String?,
  resolve: suspend (RemotePairingScan) -> RemotePairingOffer,
): RemotePairingOffer {
  val scan = parseRemotePairingScan(raw, allowCleartext)
  if (confirmedOrigin == null || scan.origin != confirmedOrigin) throw IOException("请先确认配对服务器地址")
  return resolve(scan)
}

/** An already-completed session cannot return its code; a failed claim must not prevent status recovery. */
internal suspend fun recoverRemotePairingStatus(
  pending: PendingRemotePairing,
  recoverCode: Boolean,
  claim: suspend (PendingRemotePairing) -> RemotePairingClaim,
  status: suspend (PendingRemotePairing) -> RemotePairingStatus,
): RemotePairingRecoveryAttempt {
  var result: RemotePairingClaim? = null
  var error: Exception? = null
  if (recoverCode && !pending.cancelRequested) {
    try { result = claim(pending) }
    catch (cancel: CancellationException) { throw cancel }
    catch (failure: Exception) { error = failure }
  }
  return RemotePairingRecoveryAttempt(result, error, status(pending))
}

/** One recoverable pairing, encrypted with the existing device identity key and excluded from backup. */
internal class RemotePairingRecoveryStore internal constructor(
  private val preferences: SharedPreferences,
  private val seal: (String) -> String,
  private val open: (String) -> String,
) {
  constructor(context: Context) : this(
    context.getSharedPreferences("remote-device-identities", Context.MODE_PRIVATE),
    KeystoreCipher.of("lagoon-remote-device-identities")::encrypt,
    KeystoreCipher.of("lagoon-remote-device-identities")::decrypt,
  )

  fun load(): PendingRemotePairing? = synchronized(lock) {
    val encrypted = preferences.getString(KEY, null) ?: return null
    try { decode(open(encrypted)) }
    catch (e: Exception) { throw IOException("未完成的配对资料无法读取，原记录已保留", e) }
  }

  fun save(pending: PendingRemotePairing) = synchronized(lock) {
    val existing = load()
    if (pending.cancelRequested && existing == null) throw IOException("配对已结束，无法再取消")
    if (existing != null && (existing.offer != pending.offer || existing.device != pending.device ||
        existing.allowCleartext != pending.allowCleartext || existing.identity.deviceId != pending.identity.deviceId ||
        existing.identity.publicKey != pending.identity.publicKey || existing.identity.privateKey != pending.identity.privateKey ||
        existing.claimBody != pending.claimBody || (existing.cancelRequested && !pending.cancelRequested))) {
      throw IOException("已有未完成的配对，请先恢复或取消")
    }
    val serialized = encode(pending)
    decode(serialized) // Validate before replacing even an existing record.
    val encrypted = seal(serialized)
    if (!preferences.edit().putString(KEY, encrypted).commit()) throw IOException("配对恢复资料保存失败")
  }

  fun clear(sessionId: String) = synchronized(lock) {
    if (load()?.offer?.sessionId != sessionId) return
    if (!preferences.edit().remove(KEY).commit()) throw IOException("配对恢复资料清理失败")
  }

  private fun encode(value: PendingRemotePairing): String = JSONObject()
    .put("version", 1)
    .put("origin", value.offer.origin)
    .put("allowCleartext", value.allowCleartext)
    .put("offer", JSONObject().put("v", 1).put("sessionId", value.offer.sessionId)
      .put("expiresAt", value.offer.expiresAtMillis).put("serverPublicKey", value.offer.serverPublicKey)
      .put("scanSecret", value.offer.scanSecret).put("host", value.offer.host.toJson()))
    .put("identity", JSONObject().put("deviceId", value.identity.deviceId)
      .put("publicKey", value.identity.publicKey).put("privateKey", value.identity.privateKey))
    .put("device", value.device.toJson())
    .put("claimBody", value.claimBody)
    .put("cancelRequested", value.cancelRequested)
    .apply { value.recoverUntilMillis?.let { put("recoverUntil", it) } }
    .toString()

  private fun decode(text: String): PendingRemotePairing {
    val json = JSONObject(text)
    require(json.getInt("version") == 1)
    val origin = canonicalRemoteOrigin(json.getString("origin"))
    val allowCleartext = json.getBoolean("allowCleartext")
    require(allowCleartext || !origin.startsWith("http://"))
    val offerJson = json.getJSONObject("offer")
    val expiresAt = offerJson.getLong("expiresAt")
    // A completed session can be recovered after the original offer's expiration.
    val offer = RemotePairingOffer.parse(offerJson, origin, nowMillis = expiresAt)
    val identityJson = json.getJSONObject("identity")
    val identity = RemoteIdentity(identityJson.getString("deviceId"), identityJson.getString("publicKey"), identityJson.getString("privateKey"))
    val device = requireNotNull(RemotePairingDevice.fromJson(json.getJSONObject("device")))
    require(device.role == "client" && device.deviceId == identity.deviceId && device.identityKey == identity.publicKey)
    require(RemoteServerApi.validPublicKey(identity.privateKey) && RemoteServerApi.validPublicKey(identity.publicKey))
    val privateBytes = Base64.getUrlDecoder().decode(identity.privateKey)
    try {
      val derivedPublic = ByteArray(32)
      X25519.scalarMultBase(privateBytes, 0, derivedPublic, 0)
      require(MessageDigest.isEqual(derivedPublic, Base64.getUrlDecoder().decode(identity.publicKey)))
    } finally { privateBytes.fill(0) }
    val claimBody = json.getString("claimBody")
    val claimJson = JSONObject(claimBody)
    require(claimJson.length() == 2 && claimJson.getString("scanSecret") == offer.scanSecret &&
      RemotePairingDevice.fromJson(claimJson.getJSONObject("device")) == device)
    val recoverUntil = if (json.has("recoverUntil")) json.getLong("recoverUntil").also { require(it > 0L) } else null
    val cancelRequested = if (json.has("cancelRequested")) json.getBoolean("cancelRequested") else false
    return PendingRemotePairing(offer, identity, device, claimBody, allowCleartext, recoverUntil, cancelRequested)
  }

  companion object {
    internal const val KEY = "__pending_remote_pairing_v1__"
    private val lock = Any()
  }
}
