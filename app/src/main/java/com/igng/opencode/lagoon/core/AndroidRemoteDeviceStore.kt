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
    if (!preferences.edit().remove(slot).commit()) throw IOException("Remote 设备资料删除失败")
  }

  private companion object { val lock = Any() }
}
