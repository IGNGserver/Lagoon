package com.igng.opencode.lagoon.core

/**
 * 一台 Host 只保留一条加密连接。
 *
 * 首页监控（[AllServersOverview]）、控制器打开的会话、以及「测试连接」以前各自
 * `RemoteSession(...)`，同一 Host 会同时存在多条 Noise 隧道：中枢/Host 侧按设备身份
 * 互相替换，表现为“某次读取突然失败、整台设备的会话全空”。这里按
 * `(Hub, 账号, hostId)` 共享同一个会话，并用引用计数决定何时真正关闭。
 */
internal object RemoteConnectionRegistry {
  private class Entry(val session: RemoteSession, var refs: Int)

  private val entries = HashMap<String, Entry>()

  @Synchronized fun acquire(key: String, create: () -> RemoteSession): RemoteSession {
    entries[key]?.let { it.refs++; return it.session }
    val session = create()
    entries[key] = Entry(session, 1)
    return session
  }

  @Synchronized fun release(key: String) {
    val entry = entries[key] ?: return
    entry.refs -= 1
    if (entry.refs > 0) return
    entries.remove(key)
    entry.session.close()
  }

  fun evictHost(origin: String, account: String, hostId: String) {
    val canonical = runCatching { canonicalRemoteOrigin(origin) }.getOrElse { origin.trim() }
    val key = "remote|$canonical|${account.trim()}|$hostId"
    val removed = synchronized(this) { entries.remove(key)?.session }
    removed?.close()
  }

  fun evictAccount(origin: String, account: String) {
    val canonical = runCatching { canonicalRemoteOrigin(origin) }.getOrElse { origin.trim() }
    val prefix = "remote|$canonical|${account.trim()}|"
    val removed = synchronized(this) {
      val keys = entries.keys.filter { it.startsWith(prefix) }
      keys.mapNotNull { entries.remove(it)?.session }
    }
    removed.forEach { it.close() }
  }

  /** 测试用：当前存活的连接数。 */
  @Synchronized internal fun size(): Int = entries.size

  /** 测试用：清空（关闭会由持有者负责）。 */
  @Synchronized internal fun clearForTest() { entries.clear() }
}
