package com.igng.opencode.lagoon.core

import java.util.concurrent.ConcurrentHashMap

/** A submitted operation is retained until the Host confirms its outcome. */
internal data class RemoteCompactionRecord(
  val scope: String,
  val sessionId: String,
  val requestId: String,
  val operationId: String?,
  val submittedAt: Long,
  val ownerDeviceId: String,
  val terminalState: String? = null,
  val terminalMessage: String? = null
)

/** The scope identifies one Remote Host, not a display name or a Harness leaf. */
internal interface RemoteCompactionStore {
  fun load(scope: String, sessionId: String): RemoteCompactionRecord?
  fun list(scope: String): List<RemoteCompactionRecord>
  fun save(record: RemoteCompactionRecord)
  fun remove(scope: String, sessionId: String)
  fun clearScope(scope: String)
  fun updateIfCurrent(record: RemoteCompactionRecord): Boolean
}

internal fun remoteCompactionScope(origin: String, account: String, hostId: String): String =
  org.json.JSONArray(listOf(canonicalRemoteOrigin(origin), account.trim(), hostId)).toString()

/** Nonpersistent default for isolated clients and tests; Android supplies the encrypted cache. */
internal class MemoryRemoteCompactionStore : RemoteCompactionStore {
  private val entries = ConcurrentHashMap<Pair<String, String>, RemoteCompactionRecord>()
  override fun load(scope: String, sessionId: String) = entries[scope to sessionId]
  override fun list(scope: String) = entries.values.filter { it.scope == scope }
  override fun save(record: RemoteCompactionRecord) { entries[record.scope to record.sessionId] = record }
  override fun remove(scope: String, sessionId: String) { entries.remove(scope to sessionId) }
  override fun clearScope(scope: String) { entries.keys.removeIf { it.first == scope } }
  override fun updateIfCurrent(record: RemoteCompactionRecord): Boolean {
    var updated = false
    entries.computeIfPresent(record.scope to record.sessionId) { _, current ->
      if (current.requestId == record.requestId && current.ownerDeviceId == record.ownerDeviceId) {
        updated = true; record
      } else current
    }
    return updated
  }
}
