package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.RemoteDeviceCodec
import com.igng.opencode.lagoon.core.RemoteDeviceRecord
import com.igng.opencode.lagoon.core.RemoteDeviceStore
import java.io.IOException
import java.nio.file.Path

/** Hub-owned identities, sealed separately from profiles and never included in client exports. */
internal class HubRemoteDeviceStore(private val dataDir: Path, private val box: SecretBox) : RemoteDeviceStore {
  override fun load(slot: String): RemoteDeviceRecord? = synchronized(lock) {
    val text = DataFiles.read(path(slot)) ?: return null
    try { RemoteDeviceCodec.decode(box.open(text)) }
    catch (error: Exception) { throw IOException("Remote 设备资料无法解密，未自动重建身份", error) }
  }

  override fun save(slot: String, record: RemoteDeviceRecord) = synchronized(lock) {
    DataFiles.write(path(slot), box.seal(RemoteDeviceCodec.encode(record)))
  }

  private fun path(slot: String): Path {
    require(slot.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    return dataDir.resolve("remote-devices").resolve("$slot.json")
  }

  companion object {
    internal fun slot(origin: String, account: String): String = "hub-${java.security.MessageDigest.getInstance("SHA-256")
      .digest("${origin.trimEnd('/')}\u0000${account.trim()}".toByteArray()).take(12).joinToString("") { "%02x".format(it) }}"
    private val lock = Any()
  }
}
