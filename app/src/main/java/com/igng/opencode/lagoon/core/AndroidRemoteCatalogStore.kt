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
    try { readCatalogs() } catch (error: java.io.IOException) {
      Diagnostics.warn("RemoteCatalogStore", "Remote 主机目录损坏，未覆盖原记录", error)
      emptyList()
    }
  }

  /** Writers must never turn an unreadable multi-account document into a one-account document. */
  private fun readCatalogs(): List<RemoteCatalog> {
    val raw = preferences.getString(KEY, null) ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      check(array.length() <= 64) { "Remote 账号目录过多" }
      val catalogs = (0 until array.length()).map { index ->
        val value = array.get(index) as? String ?: error("Remote 账号目录条目无效")
        RemoteCatalogCodec.decode(value)
      }
      check(catalogs.distinctBy { it.key }.size == catalogs.size) { "Remote 账号目录重复" }
      catalogs
    } catch (error: Exception) {
      throw java.io.IOException("Remote 主机目录损坏，已停止写入以保护原记录", error)
    }
  }

  override fun save(catalog: RemoteCatalog) = synchronized(lock) {
    val current = readCatalogs().filterNot { it.key == catalog.key } + catalog
    val encoded = JSONArray().apply { current.forEach { put(RemoteCatalogCodec.encode(it)) } }
    if (!preferences.edit().putString(KEY, encoded.toString()).commit()) throw java.io.IOException("Remote 主机目录保存失败")
  }

  override fun clear(origin: String, account: String) = synchronized(lock) {
    val remaining = readCatalogs().filterNot { it.origin == origin && it.account == account }
    val encoded = JSONArray().apply { remaining.forEach { put(RemoteCatalogCodec.encode(it)) } }
    if (!preferences.edit().putString(KEY, encoded.toString()).commit()) throw java.io.IOException("Remote 主机目录保存失败")
  }

  private companion object {
    const val KEY = "catalogs"
    val lock = Any()
  }
}
