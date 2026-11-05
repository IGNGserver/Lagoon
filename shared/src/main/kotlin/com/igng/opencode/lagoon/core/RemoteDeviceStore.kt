package com.igng.opencode.lagoon.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

/** A record is local to one installation. It must never be included in server-profile exports. */
internal class RemoteDeviceRecord(
  val origin: String,
  val account: String,
  val identity: RemoteIdentity,
  val tokens: RemoteTokenPair?,
  val transition: Transition = Transition.NONE,
  /** 进入当前 transition 的时间；用来区分“正在进行的刷新”和“进程被杀留下的残局”。 */
  val transitionAt: Long = 0
) {
  enum class Transition { NONE, REGISTERING, REFRESHING }
  override fun toString(): String = "RemoteDeviceRecord(origin=$origin, account=$account, identity=$identity, tokens=<redacted>, transition=$transition)"
}

/** Implementations must durably replace the whole record before returning, and fail on corruption. */
internal interface RemoteDeviceStore {
  fun load(slot: String): RemoteDeviceRecord?
  fun save(slot: String, record: RemoteDeviceRecord)
  fun delete(slot: String)
}

internal object RemoteDeviceCodec {
  fun encode(record: RemoteDeviceRecord): String = JSONObject().put("version", 1)
    .put("origin", record.origin).put("account", record.account)
    .put("deviceId", record.identity.deviceId).put("publicKey", record.identity.publicKey)
    .put("privateKey", record.identity.privateKey).put("transition", record.transition.name)
    .put("transitionAt", record.transitionAt)
    .put("tokens", record.tokens?.let { JSONObject().put("accessToken", it.accessToken).put("accessTokenExpiresAt", it.accessTokenExpiresAt)
      .put("refreshToken", it.refreshToken).put("refreshTokenExpiresAt", it.refreshTokenExpiresAt) } ?: JSONObject.NULL).toString()

  fun decode(text: String): RemoteDeviceRecord {
    try {
      val json = JSONObject(text)
      require(json.getInt("version") == 1)
      val origin = canonicalRemoteOrigin(json.getString("origin"))
      val account = json.getString("account").also { require(it.isNotBlank() && it.length <= 254 && '\u0000' !in it) }
      val id = json.getString("deviceId").also { require(RemoteServerApi.validDeviceId(it)) }
      val publicKey = json.getString("publicKey").also { require(RemoteServerApi.validPublicKey(it)) }
      val privateKey = json.getString("privateKey").also { require(RemoteServerApi.validPublicKey(it)) }
      val transition = RemoteDeviceRecord.Transition.valueOf(json.getString("transition"))
      val transitionAt = if (json.has("transitionAt")) json.optLong("transitionAt", 0L) else 0L
      val tokens = if (json.isNull("tokens")) null else json.getJSONObject("tokens").let {
        val access = it.getString("accessToken").also { value -> require(RemoteServerApi.validToken(value)) }
        val refresh = it.getString("refreshToken").also { value -> require(RemoteServerApi.validToken(value)) }
        val accessExpiry = it.getLong("accessTokenExpiresAt").also { value -> require(value > 0) }
        val refreshExpiry = it.getLong("refreshTokenExpiresAt").also { value -> require(value > 0) }
        RemoteTokenPair(access, accessExpiry, refresh, refreshExpiry)
      }
      require(transition != RemoteDeviceRecord.Transition.REFRESHING || tokens != null)
      return RemoteDeviceRecord(origin, account, RemoteIdentity(id, publicKey, privateKey), tokens, transition, transitionAt)
    } catch (error: Exception) { throw IOException("Remote 设备资料损坏，未自动重建身份", error) }
  }
}

internal fun canonicalRemoteOrigin(address: String): String {
  val url = requireNotNull(address.trim().toHttpUrlOrNull()) { "DSH Hub 地址无效" }
  require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null)
  return url.toString()
}

/**
 * Serializes token rotation, and marks in-flight mutations on disk first. A crash or network error
 * after refresh leaves the record uncertain: replaying the old refresh token may revoke the device.
 * Recovery requires explicit account reauthentication and registration of the same identity.
 */
internal class RemoteDeviceManager(private val store: RemoteDeviceStore, private val slot: String, address: String, account: String,
  private val registerer: suspend (RemoteClientDescriptor, String) -> RemoteTokenPair = { _, _ -> throw IOException("Remote 设备未注册") },
  private val refresher: suspend (String, String) -> RemoteTokenPair = { _, _ -> throw IOException("Remote refresh 未配置") }) {
  private val origin = canonicalRemoteOrigin(address)
  private val account = account.trim().also { require(it.isNotBlank() && it.length <= 254 && '\u0000' !in it) }
  private val lock = locks.computeIfAbsent(slot) { Mutex() }

  init { require(slot.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }

  suspend fun register(name: String, platform: String, version: String, accountToken: String,
    register: suspend (RemoteClientDescriptor, String) -> RemoteTokenPair = registerer): RemoteDeviceRecord = lock.withLock {
    val current = loadBound() ?: RemoteDeviceRecord(origin, account, RemoteIdentity.generate(), null)
    val descriptor = RemoteClientDescriptor(current.identity.deviceId, name, platform, current.identity.publicKey, version)
    descriptor.validate()
    val pending = RemoteDeviceRecord(origin, account, current.identity, current.tokens,
      RemoteDeviceRecord.Transition.REGISTERING, System.currentTimeMillis())
    store.save(slot, pending)
    try {
      val pair = register(descriptor, accountToken)
      RemoteDeviceRecord(origin, account, current.identity, pair).also { store.save(slot, it) }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
      throw cancel
    } catch (error: Exception) {
      // 注册失败必须把记录恢复成可重试状态；同一 identity 重新注册是幂等的，不能把人锁死在 REGISTERING。
      store.save(slot, current)
      throw error
    }
  }

  suspend fun access(now: Long = System.currentTimeMillis(),
    refresh: suspend (String, String) -> RemoteTokenPair = refresher): RemoteDeviceRecord = lock.withLock {
    val current = loadBound() ?: throw IOException("Remote 设备尚未注册")
    val pair = current.tokens ?: run {
      if (current.transition == RemoteDeviceRecord.Transition.REGISTERING && stale(current, now)) {
        store.save(slot, RemoteDeviceRecord(origin, account, current.identity, null))
        throw IOException("Remote 设备注册尚未完成，请重新登录")
      }
      throw IOException("Remote 设备尚未注册")
    }
    if (current.transition == RemoteDeviceRecord.Transition.REFRESHING) {
      // 上一次刷新没有结论：进程被杀/断网/超时。短时间内先等它；超过阈值就重试一次，
      // 而不是像以前那样永久报“结果未知”把人锁死。
      if (!stale(current, now)) throw IOException("Remote 凭据正在刷新，请稍后重试")
      return@withLock refreshLocked(current, pair, now, refresh)
    }
    if (pair.accessTokenExpiresAt > now + 60_000) return@withLock current
    if (pair.refreshTokenExpiresAt <= now) throw IOException("Remote 设备凭据已过期，请重新登录")
    refreshLocked(current, pair, now, refresh)
  }

  /**
   * 刷新令牌。失败一律把记录恢复成“可重试”，只有服务端明确拒绝（401/403）才判定需要重新登录；
   * 结果未知时保留旧令牌，下一次以 401 收场后同样落到重新登录，不会出现无解的死锁状态。
   */
  private suspend fun refreshLocked(current: RemoteDeviceRecord, pair: RemoteTokenPair, now: Long,
    refresh: suspend (String, String) -> RemoteTokenPair): RemoteDeviceRecord {
    store.save(slot, RemoteDeviceRecord(origin, account, current.identity, pair,
      RemoteDeviceRecord.Transition.REFRESHING, now))
    return try {
      val next = refresh(current.identity.deviceId, pair.refreshToken)
      RemoteDeviceRecord(origin, account, current.identity, next).also { store.save(slot, it) }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
      throw cancel
    } catch (error: RemoteServerException) {
      // 明确被拒 = 刷新令牌已失效：恢复记录并要求重新登录。
      if (error.status == 401 || error.status == 403) {
        store.save(slot, RemoteDeviceRecord(origin, account, current.identity, pair))
        throw IOException("Remote 登录已失效，请重新登录", error)
      }
      // 5xx/429 等：结果未知，保留 REFRESHING 与时间戳，冷却后自动重试。
      throw IOException("Remote 凭据刷新失败，请稍后重试", error)
    } catch (error: Exception) {
      // 网络错误同样可能是“请求已送达但响应丢失”，因此保留不确定标记；由 transitionAt 决定何时重试，
      // 不会像旧实现那样永久卡死。
      throw IOException("Remote 凭据刷新失败，请稍后重试", error)
    }
  }

  private fun stale(record: RemoteDeviceRecord, now: Long): Boolean =
    record.transitionAt <= 0 || now - record.transitionAt >= TRANSITION_STALE_MILLIS

  private fun loadBound(): RemoteDeviceRecord? = store.load(slot)?.also {
    if (it.origin != origin || it.account != account) throw IOException("Remote 设备身份属于其他 Hub 或账号")
  }

  private companion object {
    /** 超过这个时长仍停在“进行中”，按进程被杀/断网处理，允许重试。 */
    const val TRANSITION_STALE_MILLIS = 90_000L
    val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
  }
}

/** Profile-keyed local storage can back multiple Remote Hub accounts without sharing credentials. */
