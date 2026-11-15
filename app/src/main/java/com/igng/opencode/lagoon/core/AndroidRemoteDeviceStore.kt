package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/** Android-only device identities are sealed by an AndroidKeyStore key and excluded from backup. */
internal class AndroidRemoteDeviceStore internal constructor(
  private val preferences: SharedPreferences,
  private val seal: (String) -> String,
  private val open: (String) -> String
) : RemoteDeviceStore {
  constructor(context: Context) : this(
    context.getSharedPreferences("remote-device-identities", Context.MODE_PRIVATE),
    KeystoreCipher.of("lagoon-remote-device-identities")::encrypt,
    KeystoreCipher.of("lagoon-remote-device-identities")::decrypt
  )

  override fun load(slot: String): RemoteDeviceRecord? = synchronized(lock) {
    val sealed = preferences.getString(slot, null) ?: return null
    try { RemoteDeviceCodec.decode(open(sealed)) }
    catch (error: Exception) { throw IOException("Remote 设备资料无法解密，未自动重建身份", error) }
  }

  override fun save(slot: String, record: RemoteDeviceRecord) = synchronized(lock) {
    val encrypted = seal(RemoteDeviceCodec.encode(record))
    if (!preferences.edit().putString(slot, encrypted).commit()) throw IOException("Remote 设备资料保存失败")
  }

  override fun delete(slot: String) {
    // Invalidate before taking the store lock: writers take the per-slot epoch before this lock.
    RemoteDeviceManager.invalidateSlot(slot)
    synchronized(lock) {
      if (!preferences.edit().remove(slot).commit()) throw IOException("Remote 设备资料删除失败")
    }
  }

  override fun listSlots(): Set<String> = synchronized(lock) {
    preferences.all.keys.filter { it != REVOCATION_QUEUE_KEY && it != PAIRING_RECOVERY_KEY }.toSet()
  }

  override fun pendingRevocations(): List<PendingRemoteRevocation> = synchronized(lock) {
    val sealed = preferences.getString(REVOCATION_QUEUE_KEY, null) ?: return emptyList()
    try { PendingRemoteRevocationCodec.decodeList(open(sealed)) }
    catch (error: Exception) { throw IOException("待撤销授权资料损坏，原记录已保留", error) }
  }

  override fun enqueueRevocation(item: PendingRemoteRevocation) = synchronized(lock) {
    val current = pendingRevocations()
    fun sameDevice(other: PendingRemoteRevocation) = other.origin == item.origin && other.account == item.account &&
      other.clientDeviceId == item.clientDeviceId
    // A self-revoke covers all memberships. Preserve an already queued self-revoke and atomically
    // replace obsolete membership-only tasks when the last Host using an identity is removed.
    if (current.any { sameDevice(it) && (!it.membershipOnly || item.membershipOnly && it.peerHostId == item.peerHostId) }) return
    val retained = if (item.membershipOnly) current else current.filterNot { sameDevice(it) && it.membershipOnly }
    if (retained.size >= MAX_PENDING_REVOCATIONS) throw IOException("待撤销授权过多，请先处理已有任务")
    saveRevocations(retained + item)
  }

  override fun updateRevocation(item: PendingRemoteRevocation) = synchronized(lock) {
    val current = pendingRevocations()
    if (current.none { it.id == item.id }) return
    saveRevocations(current.map { if (it.id == item.id) item else it })
  }

  override fun removeRevocation(id: String) = synchronized(lock) {
    val next = pendingRevocations().filterNot { it.id == id }
    saveRevocations(next)
  }

  private fun saveRevocations(items: List<PendingRemoteRevocation>) {
    val editor = preferences.edit()
    if (items.isEmpty()) editor.remove(REVOCATION_QUEUE_KEY)
    else editor.putString(REVOCATION_QUEUE_KEY, seal(PendingRemoteRevocationCodec.encodeList(items)))
    if (!editor.commit()) throw IOException("待撤销授权保存失败，原设备身份已保留")
  }

  private companion object {
    val lock = Any()
    const val REVOCATION_QUEUE_KEY = "__pending_remote_revocations_v1__"
    const val PAIRING_RECOVERY_KEY = "__pending_remote_pairing_v1__"
    const val MAX_PENDING_REVOCATIONS = 64
  }
}
