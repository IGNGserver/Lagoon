package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.security.MessageDigest

internal class AndroidRemotePeerTrustStore(private val preferences: SharedPreferences) : RemotePeerTrustStore {
  constructor(context: Context) : this(context.getSharedPreferences("remote-host-pins", Context.MODE_PRIVATE))

  override fun pinned(scope: RemotePeerScope): String? = synchronized(lock) { preferences.getString(key(scope), null) }
  override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = synchronized(lock) {
    val key = key(scope)
    preferences.getString(key, null)?.let { return it }
    if (!preferences.edit().putString(key, publicKey).commit()) throw IOException("Remote Host 身份保存失败")
    publicKey
  }
  private fun key(scope: RemotePeerScope): String = MessageDigest.getInstance("SHA-256")
    .digest("${scope.origin}\u0000${scope.account}\u0000${scope.hostId}".toByteArray()).joinToString("") { "%02x".format(it) }
  private companion object { val lock = Any() }
}
