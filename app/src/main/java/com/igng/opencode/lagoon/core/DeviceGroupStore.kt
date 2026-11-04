package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/**
 * Local-only device grouping. The groups never leave this installation; a corrupt or missing
 * record must not affect saved servers or credentials.
 */
internal class DeviceGroupStore internal constructor(private val preferences: SharedPreferences) {
  constructor(context: Context) : this(context.getSharedPreferences("device-groups", Context.MODE_PRIVATE))

  fun load(): DeviceGroupState {
    val text = preferences.getString(KEY, null) ?: return DeviceGroupState()
    return try {
      DeviceGroupCodec.decode(text)
    } catch (error: Exception) {
      Diagnostics.warn("DeviceGroupStore", "设备分组读取失败，已忽略本机分组", error)
      DeviceGroupState()
    }
  }

  fun save(state: DeviceGroupState) {
    val encoded = DeviceGroupCodec.encode(state)
    if (!preferences.edit().putString(KEY, encoded).commit()) throw IOException("设备分组保存失败")
  }

  private companion object { const val KEY = "state" }
}
