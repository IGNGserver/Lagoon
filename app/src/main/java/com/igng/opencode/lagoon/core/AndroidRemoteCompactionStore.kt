package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Operation metadata lives in the existing encrypted offline cache, not in exported profiles. */
internal class AndroidRemoteCompactionStore internal constructor(
  private val preferences: SharedPreferences,
  private val seal: (String) -> String,
  private val open: (String) -> String
) : RemoteCompactionStore {
  constructor(context: Context) : this(
    context.getSharedPreferences("offline_cache", Context.MODE_PRIVATE),
    KeystoreCipher.of("lagoon-offline-cache", legacyAlias = "opencode-lagoon-offline-cache")::encrypt,
    KeystoreCipher.of("lagoon-offline-cache", legacyAlias = "opencode-lagoon-offline-cache")::decrypt
  )

  override fun load(scope: String, sessionId: String): RemoteCompactionRecord? = synchronized(lock) {
    read().singleOrNull { it.scope == scope && it.sessionId == sessionId }
  }

  override fun list(scope: String): List<RemoteCompactionRecord> = synchronized(lock) {
    read().filter { it.scope == scope }
  }

  override fun save(record: RemoteCompactionRecord) = synchronized(lock) {
    val records = read().filterNot { it.scope == record.scope && it.sessionId == record.sessionId } + record
    if (records.size > MAX_RECORDS) throw IOException("待确认的压缩操作过多，请先检查之前的会话")
    write(records)
  }

  override fun remove(scope: String, sessionId: String) = synchronized(lock) {
    write(read().filterNot { it.scope == scope && it.sessionId == sessionId })
  }

  override fun clearScope(scope: String) = synchronized(lock) {
    write(read().filterNot { it.scope == scope })
  }

  override fun updateIfCurrent(record: RemoteCompactionRecord): Boolean = synchronized(lock) {
    val records = read()
    val current = records.firstOrNull { it.scope == record.scope && it.sessionId == record.sessionId }
    if (current == null || current.requestId != record.requestId || current.ownerDeviceId != record.ownerDeviceId) return false
    write(records.map { if (it.scope == record.scope && it.sessionId == record.sessionId) record else it })
    true
  }

  private fun read(): List<RemoteCompactionRecord> {
    val encoded = preferences.getString(KEY, null) ?: return emptyList()
    return try {
      val root = JSONObject(open(encoded))
      check(root.getInt("version") == 1)
      val items = root.getJSONArray("items")
      check(items.length() <= MAX_RECORDS)
      val records = (0 until items.length()).map { index ->
        val item = items.getJSONObject(index)
        val scope = item.getString("scope").also { check(it.isNotBlank() && it.length <= 8192) }
        val session = item.getString("sessionId").also { check(it.isNotBlank() && it.length <= 4096) }
        val request = item.getString("requestId").also { check(it.isNotBlank() && it.length <= 256) }
        val operation = if (item.isNull("operationId")) null else item.getString("operationId")
          .also { check(it.isNotBlank() && it.length <= 256) }
        val submittedAt = item.getLong("submittedAt").also { check(it > 0) }
        val owner = item.getString("ownerDeviceId").also { check(RemoteServerApi.validDeviceId(it)) }
        val terminal = (item.opt("terminalState") as? String)?.also {
          check(it in setOf("unconfirmed", "failed", "cancelled", "interrupted"))
        }
        val message = (item.opt("terminalMessage") as? String)?.also { check(it.length <= 1024) }
        RemoteCompactionRecord(scope, session, request, operation, submittedAt, owner, terminal, message)
      }
      check(records.distinctBy { it.scope to it.sessionId }.size == records.size)
      records
    } catch (error: Exception) {
      throw IOException("待确认的压缩记录无法读取，已停止提交以免重复执行", error)
    }
  }

  private fun write(records: List<RemoteCompactionRecord>) {
    val editor = preferences.edit()
    if (records.isEmpty()) editor.remove(KEY) else {
      val items = JSONArray()
      records.forEach { record -> items.put(JSONObject().put("scope", record.scope).put("sessionId", record.sessionId)
        .put("requestId", record.requestId).put("operationId", record.operationId ?: JSONObject.NULL)
        .put("submittedAt", record.submittedAt).put("ownerDeviceId", record.ownerDeviceId)
        .put("terminalState", record.terminalState ?: JSONObject.NULL)
        .put("terminalMessage", record.terminalMessage ?: JSONObject.NULL)) }
      editor.putString(KEY, seal(JSONObject().put("version", 1).put("items", items).toString()))
    }
    if (!editor.commit()) throw IOException("待确认的压缩记录保存失败")
  }

  private companion object {
    const val KEY = "remote-compactions-v1"
    const val MAX_RECORDS = 128
    val lock = Any()
  }
}
