package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/**
 * Local-only device grouping. The groups never leave this installation; a corrupt or missing
 * record must not affect saved servers or credentials.
 *
 * Version 2 changed the home model from “every ungrouped profile is a tab” to “全部 + explicit
 * groups”. Existing v1 groups are therefore discarded exactly once and replaced by a v2 empty
 * document; profiles, credentials and caches are untouched.
 */
internal class DeviceGroupStore internal constructor(private val preferences: SharedPreferences) {
  constructor(context: Context) : this(context.getSharedPreferences("device-groups", Context.MODE_PRIVATE))

  data class Loaded(val state: DeviceGroupState, val resetLegacy: Boolean)

  fun load(): Loaded {
    val text = preferences.getString(KEY, null) ?: return Loaded(DeviceGroupState(), resetLegacy = false)
    val version = DeviceGroupCodec.version(text)
    // Only v1 changed the home model; v2 membership stays valid and is upgraded in place.
    if (version == 1) {
      // Persist the reset immediately so a process kill during upgrade cannot wipe a second time.
      runCatching { save(DeviceGroupState()) }
        .onFailure { Diagnostics.warn("DeviceGroupStore", "旧版设备分组重置写入失败", it) }
      return Loaded(DeviceGroupState(), resetLegacy = true)
    }
    return try {
      Loaded(DeviceGroupCodec.decode(text), resetLegacy = false)
    } catch (error: Exception) {
      Diagnostics.warn("DeviceGroupStore", "设备分组读取失败，已忽略本机分组", error)
      Loaded(DeviceGroupState(), resetLegacy = false)
    }
  }

  fun save(state: DeviceGroupState) {
    val encoded = DeviceGroupCodec.encode(state)
    if (!preferences.edit().putString(KEY, encoded).commit()) throw IOException("设备分组保存失败")
  }

  private companion object { const val KEY = "state" }
}
