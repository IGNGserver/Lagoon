package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Account/Host metadata only; device keys and tokens remain in AndroidRemoteDeviceStore. */
internal class AndroidRemoteCatalogStore internal constructor(
  private val preferences: SharedPreferences
) : RemoteCatalogStore {
  constructor(context: Context) : this(context.getSharedPreferences("remote-catalog", Context.MODE_PRIVATE))

  override fun load(): List<RemoteCatalog> = synchronized(lock) {
    val raw = preferences.getString(KEY, null) ?: return emptyList()
    try {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { index ->
        array.optString(index).takeIf(String::isNotBlank)?.let { RemoteCatalogCodec.decode(it) }
      }
    } catch (_: Exception) { emptyList() }
  }

  override fun save(catalog: RemoteCatalog) = synchronized(lock) {
    val current = load().filterNot { it.key == catalog.key } + catalog
    val encoded = JSONArray().apply { current.forEach { put(RemoteCatalogCodec.encode(it)) } }
    if (!preferences.edit().putString(KEY, encoded.toString()).commit()) throw java.io.IOException("Remote 主机目录保存失败")
  }

  override fun clear(origin: String, account: String) = synchronized(lock) {
    val remaining = load().filterNot { it.origin == origin && it.account == account }
    val encoded = JSONArray().apply { remaining.forEach { put(RemoteCatalogCodec.encode(it)) } }
    if (!preferences.edit().putString(KEY, encoded.toString()).commit()) throw java.io.IOException("Remote 主机目录保存失败")
  }

  private companion object {
    const val KEY = "catalogs"
    val lock = Any()
  }
}
