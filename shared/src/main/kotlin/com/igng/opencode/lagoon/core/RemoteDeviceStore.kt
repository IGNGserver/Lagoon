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
  val transition: Transition = Transition.NONE
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
      val tokens = if (json.isNull("tokens")) null else json.getJSONObject("tokens").let {
        val access = it.getString("accessToken").also { value -> require(RemoteServerApi.validToken(value)) }
        val refresh = it.getString("refreshToken").also { value -> require(RemoteServerApi.validToken(value)) }
        val accessExpiry = it.getLong("accessTokenExpiresAt").also { value -> require(value > 0) }
        val refreshExpiry = it.getLong("refreshTokenExpiresAt").also { value -> require(value > 0) }
        RemoteTokenPair(access, accessExpiry, refresh, refreshExpiry)
      }
      require(transition != RemoteDeviceRecord.Transition.REFRESHING || tokens != null)
      return RemoteDeviceRecord(origin, account, RemoteIdentity(id, publicKey, privateKey), tokens, transition)
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
    val pending = RemoteDeviceRecord(origin, account, current.identity, current.tokens, RemoteDeviceRecord.Transition.REGISTERING)
    store.save(slot, pending)
    val pair = register(descriptor, accountToken)
    RemoteDeviceRecord(origin, account, current.identity, pair).also { store.save(slot, it) }
  }

  suspend fun access(now: Long = System.currentTimeMillis(),
    refresh: suspend (String, String) -> RemoteTokenPair = refresher): RemoteDeviceRecord = lock.withLock {
    val current = loadBound() ?: throw IOException("Remote 设备尚未注册")
    if (current.transition != RemoteDeviceRecord.Transition.NONE) throw IOException("Remote 凭据操作结果未知，请重新登录并注册原设备")
    val pair = current.tokens ?: throw IOException("Remote 设备尚未注册")
    if (pair.accessTokenExpiresAt > now + 60_000) return@withLock current
    if (pair.refreshTokenExpiresAt <= now) throw IOException("Remote 设备凭据已过期，请重新登录")
    store.save(slot, RemoteDeviceRecord(origin, account, current.identity, pair, RemoteDeviceRecord.Transition.REFRESHING))
    val next = refresh(current.identity.deviceId, pair.refreshToken)
    RemoteDeviceRecord(origin, account, current.identity, next).also { store.save(slot, it) }
  }

  private fun loadBound(): RemoteDeviceRecord? = store.load(slot)?.also {
    if (it.origin != origin || it.account != account) throw IOException("Remote 设备身份属于其他 Hub 或账号")
  }

  private companion object { val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>() }
}

/** Profile-keyed local storage can back multiple Remote Hub accounts without sharing credentials. */
