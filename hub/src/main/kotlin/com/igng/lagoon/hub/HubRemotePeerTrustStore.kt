package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.RemotePeerScope
import com.igng.opencode.lagoon.core.RemotePeerTrustStore
import java.nio.file.Path
import java.security.MessageDigest

internal class HubRemotePeerTrustStore(private val dataDir: Path) : RemotePeerTrustStore {
  override fun pinned(scope: RemotePeerScope): String? = synchronized(lock) { DataFiles.read(path(scope)) }
  override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = synchronized(lock) {
    val path = path(scope)
    DataFiles.read(path)?.let { return it }
    DataFiles.write(path, publicKey)
    publicKey
  }
  private fun path(scope: RemotePeerScope): Path {
    val key = MessageDigest.getInstance("SHA-256")
      .digest("${scope.origin}\u0000${scope.account}\u0000${scope.hostId}".toByteArray()).joinToString("") { "%02x".format(it) }
    return dataDir.resolve("remote-host-pins").resolve(key)
  }
  private companion object { val lock = Any() }
}
