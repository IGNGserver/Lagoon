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

  override fun delete(slot: String) = synchronized(lock) {
    RemoteDeviceManager.invalidateSlot(slot)
    if (!preferences.edit().remove(slot).commit()) throw IOException("Remote 设备资料删除失败")
  }

  override fun listSlots(): Set<String> = synchronized(lock) {
    preferences.all.keys.filter { it != REVOCATION_QUEUE_KEY }.toSet()
  }

  override fun pendingRevocations(): List<PendingRemoteRevocation> = synchronized(lock) {
    val sealed = preferences.getString(REVOCATION_QUEUE_KEY, null) ?: return emptyList()
    runCatching { PendingRemoteRevocationCodec.decodeList(open(sealed)) }.getOrDefault(emptyList())
  }

  override fun enqueueRevocation(item: PendingRemoteRevocation) = synchronized(lock) {
    val current = pendingRevocations().filterNot { it.id == item.id }
    saveRevocations((current + item).takeLast(64))
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
    editor.commit()
  }

  private companion object {
    val lock = Any()
    const val REVOCATION_QUEUE_KEY = "__pending_remote_revocations_v1__"
  }
}
