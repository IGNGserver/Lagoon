package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A record is local to one installation. It must never be included in server-profile exports. */
internal class RemoteDeviceRecord(
  val origin: String,
  val account: String,
  val identity: RemoteIdentity,
  val tokens: RemoteTokenPair?,
  val transition: Transition = Transition.NONE,
  /** 进入当前 transition 的时间；用来区分“正在进行的刷新”和“进程被杀留下的残局”。 */
  val transitionAt: Long = 0,
  /** Marked true once `/auth/refresh` returns a definitive server credential rejection so reconnect loops stop hammering the Hub. */
  val invalidated: Boolean = false
) {
  enum class Transition { NONE, REGISTERING, REFRESHING }
  override fun toString(): String = "RemoteDeviceRecord(origin=$origin, account=$account, identity=$identity, tokens=<redacted>, transition=$transition, invalidated=$invalidated)"
}

internal data class PendingRemoteRevocation(
  val id: String,
  val origin: String,
  val account: String,
  val peerHostId: String,
  val clientDeviceId: String,
  val accessToken: String,
  val refreshToken: String,
  val allowCleartext: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val attempts: Int = 0,
  val lastAttemptAt: Long = 0L
) {
  override fun toString(): String =
    "PendingRemoteRevocation(id=$id, origin=$origin, account=$account, peerHostId=$peerHostId, clientDeviceId=$clientDeviceId, attempts=$attempts)"
}

internal object PendingRemoteRevocationCodec {
  fun encodeList(items: List<PendingRemoteRevocation>): String = org.json.JSONArray().apply {
    items.forEach { item ->
      put(JSONObject()
        .put("id", item.id)
        .put("origin", item.origin)
        .put("account", item.account)
        .put("peerHostId", item.peerHostId)
        .put("clientDeviceId", item.clientDeviceId)
        .put("accessToken", item.accessToken)
        .put("refreshToken", item.refreshToken)
        .put("allowCleartext", item.allowCleartext)
        .put("createdAt", item.createdAt)
        .put("attempts", item.attempts)
        .put("lastAttemptAt", item.lastAttemptAt))
    }
  }.toString()

  fun decodeList(text: String): List<PendingRemoteRevocation> {
    if (text.isBlank()) return emptyList()
    val array = org.json.JSONArray(text)
    return (0 until array.length()).mapNotNull { i ->
      val obj = array.optJSONObject(i) ?: return@mapNotNull null
      val id = obj.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
      val origin = runCatching { canonicalRemoteOrigin(obj.optString("origin")) }.getOrNull() ?: return@mapNotNull null
      val account = obj.optString("account").takeIf(String::isNotBlank) ?: return@mapNotNull null
      val peerHostId = obj.optString("peerHostId").takeIf(String::isNotBlank) ?: return@mapNotNull null
      val clientDeviceId = obj.optString("clientDeviceId").takeIf(RemoteServerApi::validDeviceId) ?: return@mapNotNull null
      val accessToken = obj.optString("accessToken").takeIf(RemoteServerApi::validToken) ?: return@mapNotNull null
      val refreshToken = obj.optString("refreshToken").takeIf(RemoteServerApi::validToken) ?: return@mapNotNull null
      PendingRemoteRevocation(
        id = id,
        origin = origin,
        account = account,
        peerHostId = peerHostId,
        clientDeviceId = clientDeviceId,
        accessToken = accessToken,
        refreshToken = refreshToken,
        allowCleartext = obj.optBoolean("allowCleartext", false),
        createdAt = obj.optLong("createdAt", 0L),
        attempts = obj.optInt("attempts", 0).coerceAtLeast(0),
        lastAttemptAt = obj.optLong("lastAttemptAt", 0L).coerceAtLeast(0L)
      )
    }
  }
}

/** Implementations must durably replace the whole record before returning, and fail on corruption. */
internal interface RemoteDeviceStore {
  fun load(slot: String): RemoteDeviceRecord?
  fun save(slot: String, record: RemoteDeviceRecord)
  fun delete(slot: String)
  fun listSlots(): Set<String> = emptySet()
  fun pendingRevocations(): List<PendingRemoteRevocation> = emptyList()
  fun enqueueRevocation(item: PendingRemoteRevocation) {}
  fun updateRevocation(item: PendingRemoteRevocation) {}
  fun removeRevocation(id: String) {}
}

internal object RemoteDeviceCodec {
  fun encode(record: RemoteDeviceRecord): String = JSONObject().put("version", 1)
    .put("origin", record.origin).put("account", record.account)
    .put("deviceId", record.identity.deviceId).put("publicKey", record.identity.publicKey)
    .put("privateKey", record.identity.privateKey).put("transition", record.transition.name)
    .put("transitionAt", record.transitionAt)
    .put("invalidated", record.invalidated)
    .put("tokens", record.tokens?.let { JSONObject().put("accessToken", it.accessToken).put("accessTokenExpiresAt", it.accessTokenExpiresAt)
      .put("refreshToken", it.refreshToken).put("refreshTokenExpiresAt", it.refreshTokenExpiresAt) } ?: JSONObject.NULL).toString()

  fun decode(text: String): RemoteDeviceRecord {
    try {
      val json = JSONObject(text)
      require(json.getInt("version") == 1)
      val origin = canonicalRemoteOrigin(json.getString("origin"))
      val account = json.getString("account").also { require(it.isNotBlank() && it.length <= 320 && '\u0000' !in it) }
      val id = json.getString("deviceId").also { require(RemoteServerApi.validDeviceId(it)) }
      val publicKey = json.getString("publicKey").also { require(RemoteServerApi.validPublicKey(it)) }
      val privateKey = json.getString("privateKey").also { require(RemoteServerApi.validPublicKey(it)) }
      val transition = RemoteDeviceRecord.Transition.valueOf(json.getString("transition"))
      val transitionAt = if (json.has("transitionAt")) json.optLong("transitionAt", 0L) else 0L
      val invalidated = json.optBoolean("invalidated", false)
      val tokens = if (json.isNull("tokens")) null else json.getJSONObject("tokens").let {
        val access = it.getString("accessToken").also { value -> require(RemoteServerApi.validToken(value)) }
        val refresh = it.getString("refreshToken").also { value -> require(RemoteServerApi.validToken(value)) }
        val accessExpiry = it.getLong("accessTokenExpiresAt").also { value -> require(value > 0) }
        val refreshExpiry = it.getLong("refreshTokenExpiresAt").also { value -> require(value > 0) }
        RemoteTokenPair(access, accessExpiry, refresh, refreshExpiry)
      }
      require(transition != RemoteDeviceRecord.Transition.REFRESHING || tokens != null)
      return RemoteDeviceRecord(origin, account, RemoteIdentity(id, publicKey, privateKey), tokens, transition, transitionAt, invalidated)
    } catch (error: Exception) { throw IOException("Remote 设备资料损坏，未自动重建身份", error) }
  }
}

internal fun canonicalRemoteOrigin(address: String): String {
  val trimmed = address.trim()
  val url = parseFlexibleHttpUrl(trimmed)
  if (url != null) {
    require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null)
    return url.toString()
  }
  val match = Regex("^(https?)://([^/?#]+)(/)?$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
    ?: throw IllegalArgumentException("DSH Hub 地址无效")
  val origin = canonicalizeFlexibleHttpOrigin(trimmed)
    ?: throw IllegalArgumentException("DSH Hub 地址无效")
  require(!match.groupValues[2].contains('@')) { "DSH Hub 地址无效" }
  return "$origin/"
}

/**
 * Serializes token rotation across the entire process per credential slot using an uncancelled
 * singleflight refresh task and monotonic slot epochs. Caller coroutine cancellations (UI/timeout/
 * network switches) abort only the caller's await, never abandoning a rotated one-time refreshToken
 * before it is durably saved.
 */
internal class RemoteDeviceManager(
  private val store: RemoteDeviceStore,
  private val slot: String,
  address: String,
  account: String,
  private val registerer: suspend (RemoteClientDescriptor, String) -> RemoteTokenPair = { _, _ -> throw IOException("Remote 设备未注册") },
  private val refresher: suspend (String, String) -> RemoteTokenPair = { _, _ -> throw IOException("Remote refresh 未配置") }
) {
  private val origin = canonicalRemoteOrigin(address)
  private val account = account.trim().also { require(it.isNotBlank() && it.length <= 320 && '\u0000' !in it) }
  private val lock = locks.computeIfAbsent(slot) { Mutex() }

  init { require(slot.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }

  suspend fun register(
    name: String,
    platform: String,
    version: String,
    accountToken: String,
    register: suspend (RemoteClientDescriptor, String) -> RemoteTokenPair = registerer
  ): RemoteDeviceRecord {
    // Wait for or cancel any in-flight refresh on this slot and advance its epoch so late
    // completions from an older registration cannot resurrect a stale identity or token pair.
    invalidateSlot(slot)
    return lock.withLock {
      val epoch = epochOf(slot).incrementAndGet()
      val existing = loadBound()
      val initialIdentity = existing?.identity ?: RemoteIdentity.generate()
      val initialRecord = existing ?: RemoteDeviceRecord(origin, account, initialIdentity, null)
      val descriptor = RemoteClientDescriptor(initialIdentity.deviceId, name, platform, initialIdentity.publicKey, version)
      descriptor.validate()
      val pending = RemoteDeviceRecord(origin, account, initialIdentity, initialRecord.tokens,
        RemoteDeviceRecord.Transition.REGISTERING, System.currentTimeMillis(), invalidated = false)
      store.save(slot, pending)
      try {
        val pair = try {
          register(descriptor, accountToken)
        } catch (error: RemoteServerException) {
          val canRotateIdentity = existing != null && (
            (error.status == 403 && error.code == "DEVICE_REVOKED") ||
              (error.status == 409 && error.code in setOf("PEER_IDENTITY_MISMATCH", "DEVICE_CONFLICT"))
            )
          if (!canRotateIdentity) throw error
          val freshIdentity = RemoteIdentity.generate()
          val freshDescriptor = RemoteClientDescriptor(freshIdentity.deviceId, name, platform, freshIdentity.publicKey, version)
          freshDescriptor.validate()
          store.save(slot, RemoteDeviceRecord(origin, account, freshIdentity, null,
            RemoteDeviceRecord.Transition.REGISTERING, System.currentTimeMillis(), invalidated = false))
          val rotatedPair = register(freshDescriptor, accountToken)
          val record = RemoteDeviceRecord(origin, account, freshIdentity, rotatedPair, invalidated = false)
          if (epochOf(slot).get() == epoch) store.save(slot, record)
          return@withLock record
        }
        val record = RemoteDeviceRecord(origin, account, initialIdentity, pair, invalidated = false)
        if (epochOf(slot).get() == epoch) store.save(slot, record)
        record
      } catch (cancel: CancellationException) {
        // Restore prior recoverable state if registration was interrupted before receiving tokens.
        withContext(NonCancellable) {
          if (epochOf(slot).get() == epoch) store.save(slot, initialRecord)
        }
        throw cancel
      } catch (error: Exception) {
        // 注册失败必须把记录恢复成可重试状态；同一 identity 重新注册是幂等的，不能把人锁死在 REGISTERING。
        store.save(slot, initialRecord)
        throw error
      }
    }
  }

  /** Persist credentials minted via Scan-to-Pair v1 atomically, advancing slot epoch first. */
  suspend fun savePaired(identity: RemoteIdentity, tokens: RemoteTokenPair): RemoteDeviceRecord {
    invalidateSlot(slot)
    return lock.withLock {
      epochOf(slot).incrementAndGet()
      val record = RemoteDeviceRecord(origin, account, identity, tokens, invalidated = false)
      store.save(slot, record)
      record
    }
  }

  suspend fun access(
    now: Long = System.currentTimeMillis(),
    refresh: suspend (String, String) -> RemoteTokenPair = refresher
  ): RemoteDeviceRecord = obtainAccess(rejectedAccessToken = null, now = now, refresh = refresh)

  /**
   * Forces reactive rotation after REST/WebSocket rejects [rejectedAccessToken] with 401/TOKEN_EXPIRED/
   * AUTH_INVALID. Deduplicates concurrent callers so an already-rotated bearer is returned without
   * re-consuming the one-time refreshToken.
   */
  suspend fun refreshAfterRejected(
    rejectedAccessToken: String,
    now: Long = System.currentTimeMillis(),
    refresh: suspend (String, String) -> RemoteTokenPair = refresher
  ): RemoteDeviceRecord {
    require(rejectedAccessToken.isNotBlank()) { "rejectedAccessToken 不能为空" }
    return obtainAccess(rejectedAccessToken = rejectedAccessToken, now = now, refresh = refresh)
  }

  private suspend fun obtainAccess(
    rejectedAccessToken: String?,
    now: Long,
    refresh: suspend (String, String) -> RemoteTokenPair
  ): RemoteDeviceRecord {
    inFlightRefreshes[slot]?.takeIf { it.isActive }?.let { flight ->
      val updated = flight.await()
      val token = updated.tokens?.accessToken
      if (rejectedAccessToken == null || (token != null && token != rejectedAccessToken)) {
        return updated
      }
    }
    var joinedFlight: Deferred<RemoteDeviceRecord>? = null
    val createdFlight = lock.withLock {
      inFlightRefreshes[slot]?.takeIf { it.isActive }?.let { existing ->
        joinedFlight = existing
        return@withLock null
      }
      val current = loadBound() ?: throw IOException("Remote 设备尚未注册")
      if (current.invalidated) throw IOException("Remote 登录已失效，请重新登录")
      val pair = current.tokens ?: run {
        if (current.transition == RemoteDeviceRecord.Transition.REGISTERING && stale(current, now)) {
          store.save(slot, RemoteDeviceRecord(origin, account, current.identity, null))
          throw IOException("Remote 设备注册尚未完成，请重新登录")
        }
        throw IOException("Remote 设备尚未注册")
      }
      if (current.transition == RemoteDeviceRecord.Transition.REFRESHING && !stale(current, now)) {
        // Previous process died mid-refresh within cooldown window: wait rather than double-spending.
        throw IOException("Remote 凭据正在刷新，请稍后重试")
      }
      val tokenWasAlreadyRotated = rejectedAccessToken != null &&
        pair.accessToken != rejectedAccessToken &&
        pair.accessTokenExpiresAt > now + REACTIVE_MIN_REMAINING_MILLIS
      if (tokenWasAlreadyRotated && current.transition == RemoteDeviceRecord.Transition.NONE) {
        return@withLock null to current
      }
      if (rejectedAccessToken == null &&
        current.transition == RemoteDeviceRecord.Transition.NONE &&
        pair.accessTokenExpiresAt > now + PROACTIVE_REFRESH_WINDOW_MILLIS
      ) {
        return@withLock null to current
      }
      if (pair.refreshTokenExpiresAt <= now) {
        throw IOException("Remote 30 天设备授权已到期，请重新登录")
      }
      val epoch = epochOf(slot).get()
      val deferred = launchSingleflightRefresh(current, pair, now, epoch, refresh)
      inFlightRefreshes[slot] = deferred
      deferred to null
    }
    joinedFlight?.let { flight ->
      val updated = flight.await()
      if (rejectedAccessToken == null || updated.tokens?.accessToken != rejectedAccessToken) {
        return updated
      }
      return obtainAccess(rejectedAccessToken, now, refresh)
    }
    val (flight, cached) = createdFlight ?: return obtainAccess(rejectedAccessToken, now, refresh)
    if (cached != null) return cached
    return flight!!.await()
  }

  private fun launchSingleflightRefresh(
    current: RemoteDeviceRecord,
    pair: RemoteTokenPair,
    now: Long,
    epoch: Long,
    refresh: suspend (String, String) -> RemoteTokenPair
  ): Deferred<RemoteDeviceRecord> {
    store.save(slot, RemoteDeviceRecord(origin, account, current.identity, pair,
      RemoteDeviceRecord.Transition.REFRESHING, now, invalidated = false))
    return refreshScope.async {
      try {
        var next = refresh(current.identity.deviceId, pair.refreshToken)
        var persisted = RemoteDeviceRecord(origin, account, current.identity, next, invalidated = false)
        var abortedByEpoch = false
        withContext(NonCancellable) {
          lock.withLock {
            if (!canCommit(epoch, current.identity.deviceId)) {
              abortedByEpoch = true
              return@withLock
            }
            store.save(slot, persisted)
          }
        }
        if (abortedByEpoch) throw IOException("Remote 设备身份已变更，请重新连接")
        // Self-hosted Server recovery (PR #340) can return the direct successor with its original
        // (already expired) accessTokenExpiresAt while its refreshToken is still valid. Rotate once
        // more immediately so callers never receive an already-expired accessToken.
        val afterFirst = System.currentTimeMillis().coerceAtLeast(now)
        if (next.accessTokenExpiresAt <= afterFirst + PROACTIVE_REFRESH_WINDOW_MILLIS &&
          next.refreshToken != pair.refreshToken &&
          next.refreshTokenExpiresAt > afterFirst
        ) {
          withContext(NonCancellable) {
            lock.withLock {
              if (!canCommit(epoch, current.identity.deviceId)) {
                abortedByEpoch = true
                return@withLock
              }
              store.save(slot, RemoteDeviceRecord(origin, account, current.identity, next,
                RemoteDeviceRecord.Transition.REFRESHING, afterFirst, invalidated = false))
            }
          }
          if (abortedByEpoch) throw IOException("Remote 设备身份已变更，请重新连接")
          next = refresh(current.identity.deviceId, next.refreshToken)
          persisted = RemoteDeviceRecord(origin, account, current.identity, next, invalidated = false)
          withContext(NonCancellable) {
            lock.withLock {
              if (!canCommit(epoch, current.identity.deviceId)) {
                abortedByEpoch = true
                return@withLock
              }
              store.save(slot, persisted)
            }
          }
          if (abortedByEpoch) throw IOException("Remote 设备身份已变更，请重新连接")
        }
        persisted
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: RemoteServerException) {
        // Only a structured protocol rejection invalidates authorization. A proxy's empty 401/403
        // is an unknown refresh result and must preserve the credential/transition for backoff.
        if (error.isCredentialRejected) {
          withContext(NonCancellable) {
            lock.withLock {
              if (canCommit(epoch, current.identity.deviceId)) {
                store.save(slot, RemoteDeviceRecord(origin, account, current.identity, pair,
                  transition = RemoteDeviceRecord.Transition.NONE, transitionAt = 0, invalidated = true))
              }
            }
          }
          throw IOException("Remote 登录已失效，请重新登录", error)
        }
        throw IOException("Remote 凭据刷新失败，请稍后重试", error)
      } catch (error: Exception) {
        if (error is IOException && error.message?.contains("身份已变更") == true) throw error
        throw IOException("Remote 凭据刷新失败，请稍后重试", error)
      } finally {
        inFlightRefreshes.computeIfPresent(slot) { _, existing -> if (existing.isCompleted) null else existing }
      }
    }.also { deferred ->
      deferred.invokeOnCompletion { inFlightRefreshes.remove(slot, deferred) }
    }
  }

  private fun canCommit(expectedEpoch: Long, expectedDeviceId: String): Boolean {
    if (epochOf(slot).get() != expectedEpoch) return false
    val disk = runCatching { store.load(slot) }.getOrNull() ?: return false
    return disk.origin == origin && disk.account == account && disk.identity.deviceId == expectedDeviceId
  }

  private fun stale(record: RemoteDeviceRecord, now: Long): Boolean =
    record.transitionAt <= 0 || now - record.transitionAt >= TRANSITION_STALE_MILLIS

  /** Safe summary for the doctor dialog; never exposes token or key material. */
  fun status(now: Long = System.currentTimeMillis()): RemoteCredentialSummary {
    val current = try { store.load(slot) } catch (_: Exception) {
      return RemoteCredentialSummary("设备资料损坏")
    } ?: return RemoteCredentialSummary("尚未注册")
    val tokens = current.tokens
    val state = when {
      current.invalidated -> "凭据已失效"
      current.transition == RemoteDeviceRecord.Transition.REGISTERING -> "正在注册"
      current.transition == RemoteDeviceRecord.Transition.REFRESHING ->
        if (current.transitionAt > 0 && now - current.transitionAt <= TRANSITION_STALE_MILLIS) "正在刷新" else "刷新待重试"
      tokens == null -> "尚未注册"
      tokens.refreshTokenExpiresAt <= now -> "凭据已过期"
      tokens.accessTokenExpiresAt <= now -> "正在刷新"
      else -> "正常"
    }
    return RemoteCredentialSummary(state, tokens?.accessTokenExpiresAt ?: 0,
      tokens?.refreshTokenExpiresAt ?: 0, current.transitionAt)
  }

  private fun loadBound(): RemoteDeviceRecord? = store.load(slot)?.also {
    if (it.origin != origin || it.account != account) throw IOException("Remote 设备身份属于其他 Hub 或账号")
  }

  companion object {
    /** 超过这个时长仍停在“进行中”，按进程被杀/断网处理，允许重试。 */
    private const val TRANSITION_STALE_MILLIS = 90_000L
    private const val PROACTIVE_REFRESH_WINDOW_MILLIS = 60_000L
    private const val REACTIVE_MIN_REMAINING_MILLIS = 5_000L
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val inFlightRefreshes = ConcurrentHashMap<String, Deferred<RemoteDeviceRecord>>()
    private val slotEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun epochOf(slot: String): AtomicLong = slotEpochs.computeIfAbsent(slot) { AtomicLong(0L) }

    /** Advances [slot]'s generation and detaches any running refresh so sign-out/delete cannot race. */
    fun invalidateSlot(slot: String) {
      epochOf(slot).incrementAndGet()
      inFlightRefreshes.remove(slot)?.cancel()
    }
  }
}

/** Profile-keyed local storage can back multiple Remote Hub accounts without sharing credentials. */
