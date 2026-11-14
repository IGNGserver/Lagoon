package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.io.IOException
import java.security.MessageDigest

internal class AndroidRemotePeerTrustStore(private val preferences: SharedPreferences) : RemotePeerTrustStore {
  constructor(context: Context) : this(context.getSharedPreferences("remote-host-pins", Context.MODE_PRIVATE))

  override fun pinned(scope: RemotePeerScope): String? = synchronized(lock) {
    preferences.getString(key(normalized(scope)), null)
      ?: preferences.getString(key(scope), null)
  }

  override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = synchronized(lock) {
    val norm = normalized(scope)
    val normKey = key(norm)
    val rawKey = key(scope)
    preferences.getString(normKey, null)?.let { return it }
    if (rawKey != normKey) preferences.getString(rawKey, null)?.let { return it }
    val editor = preferences.edit().putString(normKey, publicKey)
    recordIndex(editor, norm, normKey, add = true)
    if (!editor.commit()) throw IOException("Remote Host 身份保存失败")
    publicKey
  }

  override fun replaceIfMatches(scope: RemotePeerScope, expectedPublicKey: String, replacementPublicKey: String): Boolean = synchronized(lock) {
    val norm = normalized(scope)
    val normKey = key(norm)
    val rawKey = key(scope)
    val targetKey = when {
      preferences.getString(normKey, null) != null -> normKey
      rawKey != normKey && preferences.getString(rawKey, null) != null -> rawKey
      else -> return false
    }
    val current = preferences.getString(targetKey, null) ?: return false
    if (!sameKey(current, expectedPublicKey)) return false
    val editor = preferences.edit()
      .remove(rawKey)
      .putString(normKey, replacementPublicKey)
    recordIndex(editor, norm, normKey, add = true)
    if (!editor.commit()) throw IOException("Remote Host 身份保存失败")
    true
  }

  override fun remove(scope: RemotePeerScope) = synchronized(lock) {
    val norm = normalized(scope)
    val normKey = key(norm)
    val rawKey = key(scope)
    val editor = preferences.edit().remove(normKey)
    if (rawKey != normKey) editor.remove(rawKey)
    recordIndex(editor, norm, normKey, add = false)
    if (!editor.commit()) throw IOException("Remote Host 身份删除失败")
  }

  override fun removeAllForAccount(origin: String, account: String) = synchronized(lock) {
    val canonical = runCatching { canonicalRemoteOrigin(origin) }.getOrElse { origin.trim() }
    val cleanAccount = account.trim()
    val idxKey = indexKey(canonical, cleanAccount)
    val existing = runCatching { JSONArray(preferences.getString(idxKey, "[]") ?: "[]") }.getOrNull() ?: JSONArray()
    val editor = preferences.edit().remove(idxKey)
    for (i in 0 until existing.length()) {
      existing.optString(i).takeIf(String::isNotBlank)?.let(editor::remove)
    }
    if (!editor.commit()) throw IOException("Remote Host 账号信任清理失败")
  }

  private fun normalized(scope: RemotePeerScope): RemotePeerScope {
    val canonical = runCatching { canonicalRemoteOrigin(scope.origin) }.getOrElse { scope.origin.trim() }
    return RemotePeerScope(canonical, scope.account.trim(), scope.hostId.trim())
  }

  private fun recordIndex(editor: SharedPreferences.Editor, scope: RemotePeerScope, hashedKey: String, add: Boolean) {
    val idxKey = indexKey(scope.origin, scope.account)
    val arr = runCatching { JSONArray(preferences.getString(idxKey, "[]") ?: "[]") }.getOrNull() ?: JSONArray()
    val items = linkedSetOf<String>()
    for (i in 0 until arr.length()) {
      arr.optString(i).takeIf(String::isNotBlank)?.let(items::add)
    }
    if (add) items.add(hashedKey) else items.remove(hashedKey)
    if (items.isEmpty()) editor.remove(idxKey) else editor.putString(idxKey, JSONArray(items.toList()).toString())
  }

  private fun indexKey(origin: String, account: String): String =
    INDEX_PREFIX + MessageDigest.getInstance("SHA-256")
      .digest("$origin\u0000$account".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun sameKey(left: String, right: String): Boolean = runCatching {
    MessageDigest.isEqual(
      java.util.Base64.getUrlDecoder().decode(left),
      java.util.Base64.getUrlDecoder().decode(right)
    )
  }.getOrDefault(false)
  private fun key(scope: RemotePeerScope): String = MessageDigest.getInstance("SHA-256")
    .digest("${scope.origin}\u0000${scope.account}\u0000${scope.hostId}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  private companion object {
    val lock = Any()
    const val INDEX_PREFIX = "account-index:"
  }
}
