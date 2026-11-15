package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tracks one Host's operations without replaying a submission whose delivery is unknown. */
internal class RemoteCompactionTracker(
  private val scope: String,
  private val ownerDeviceId: String,
  private val store: RemoteCompactionStore,
  private val call: suspend (String, JSONObject) -> Any?,
  private val now: () -> Long = System::currentTimeMillis,
  private val pause: suspend (Long) -> Unit = { delay(it) }
) {
  private val polling = Semaphore(4)

  suspend fun compact(sessionId: String) = withContext(Dispatchers.IO) {
    require(sessionId.isNotBlank() && sessionId.length <= 4096) { "会话无效" }
    exclusive(sessionId) {
      val existing = store.load(scope, sessionId)
      if (existing != null && (existing.ownerDeviceId != ownerDeviceId || existing.operationId == null || existing.terminalState != null)) {
        // This is an explicit user action: show the previous uncertain result once before allowing
        // another deliberate submission. Background recovery never removes this evidence.
        store.remove(scope, sessionId)
        throw unresolved(existing)
      }
      val record = existing ?: RemoteCompactionRecord(scope, sessionId, UUID.randomUUID().toString(),
        null, now(), ownerDeviceId).also(store::save)
      observe(record, fresh = existing == null)
    }
  }

  /** Recover known operations concurrently. Unknown submissions and other owners are never replayed. */
  suspend fun resumePending() = supervisorScope {
    store.list(scope).filter { it.ownerDeviceId == ownerDeviceId && it.operationId != null && it.terminalState == null }
      .map { record -> launch {
        try {
          exclusive(record.sessionId, wait = true) {
            store.load(scope, record.sessionId)?.let { current ->
              if (current.ownerDeviceId == ownerDeviceId && current.operationId != null && current.terminalState == null)
                observe(current, fresh = false)
            }
          }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
          Diagnostics.warn("RemoteCompaction", "恢复压缩状态失败：${error.message.orEmpty().take(160)}")
        }
      } }.joinAll()
  }

  /** An explicit stop is distinct from abandoning the local observer. */
  suspend fun cancel(sessionId: String): Boolean = withContext(Dispatchers.IO) {
    val record = store.load(scope, sessionId) ?: return@withContext false
    if (record.ownerDeviceId != ownerDeviceId || record.operationId == null || record.terminalState != null)
      throw unresolved(record)
    val operationId = record.operationId
    var response = call("harness.remote.compaction.cancel", JSONObject().put("sessionId", sessionId)
      .put("operationId", operationId)) as? JSONObject ?: throw IOException("停止压缩的响应无效")
    if (response.optString("operationId") != operationId || response.optString("sessionId") != sessionId)
      throw IOException("停止压缩的响应不属于当前操作")
    while (true) {
      val state = response.optString("state")
      when (state) {
        "cancelled" -> { store.remove(scope, sessionId); return@withContext true }
        "succeeded" -> {
          store.remove(scope, sessionId)
          throw RemoteFailureException(RemoteFailureKind.REJECTED, "压缩已完成，无需停止", retryable = false)
        }
        "failed", "interrupted", "unconfirmed" -> {
          retainTerminal(record, state, response.optString("message"))
          throw unresolved(record.copy(terminalState = state, terminalMessage = response.optString("message")))
        }
        "accepted", "running", "cancelling" -> Unit
        else -> throw IOException("停止压缩的状态无效")
      }
      pause(POLL_INTERVAL_MILLIS)
      response = status(record)
    }
    @Suppress("UNREACHABLE_CODE") false
  }

  private suspend fun observe(initial: RemoteCompactionRecord, fresh: Boolean) {
    var record = initial
    if (record.operationId == null) {
      check(fresh) { "不能自动重发结果未知的压缩请求" }
      val response = try {
        call("harness.remote.compaction.submit", JSONObject().put("sessionId", record.sessionId)
          .put("requestId", record.requestId)) as? JSONObject ?: throw IOException("压缩提交响应无效")
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        if (error is RemoteRpcException && error.code in PERMANENT_ERRORS) {
          store.remove(scope, record.sessionId)
          throw error
        }
        throw RemoteFailureException(RemoteFailureKind.NETWORK,
          "压缩提交结果未知，请先检查电脑上的会话；不会自动重复提交", retryable = false, cause = error)
      }
      val operationId = response.optString("operationId").takeIf { it.isNotBlank() && it.length <= 256 }
        ?: throw IOException("压缩提交结果未知：响应缺少操作 ID")
      record = record.copy(operationId = operationId)
      if (!store.updateIfCurrent(record)) throw IOException("此设备的压缩记录已移除，无法继续跟踪")
      if (finishIfTerminal(record, response.optString("state"), response.optString("message"))) return
    }
    while (true) {
      pause(POLL_INTERVAL_MILLIS)
      val response = status(record)
      if (finishIfTerminal(record, response.optString("state"), response.optString("message"))) return
    }
  }

  private suspend fun status(record: RemoteCompactionRecord): JSONObject = retryObservation {
    polling.withPermit {
      val response = call("harness.remote.compaction.status", JSONObject().put("sessionId", record.sessionId)
        .put("operationId", record.operationId)) as? JSONObject ?: throw IOException("压缩状态响应无效")
      if (response.optString("operationId") != record.operationId || response.optString("sessionId") != record.sessionId)
        throw IOException("压缩状态不属于当前操作")
      response
    }
  }

  private fun finishIfTerminal(record: RemoteCompactionRecord, state: String, message: String): Boolean = when (state) {
    "accepted", "running", "cancelling" -> false
    "succeeded" -> { store.remove(scope, record.sessionId); true }
    "unconfirmed", "failed", "cancelled", "interrupted" -> {
      retainTerminal(record, state, message)
      throw unresolved(record.copy(terminalState = state, terminalMessage = message))
    }
    else -> throw IOException("压缩状态无效：${state.take(40)}")
  }

  private fun retainTerminal(record: RemoteCompactionRecord, state: String, message: String) {
    store.updateIfCurrent(record.copy(terminalState = state, terminalMessage = message.take(1024)))
  }

  private fun unresolved(record: RemoteCompactionRecord): RemoteFailureException {
    val message = when {
      record.ownerDeviceId != ownerDeviceId -> "手机身份已更换，之前的压缩结果无法确认，请先检查电脑上的会话"
      record.operationId == null -> "上次压缩提交结果未知，请先检查电脑上的会话；不会自动重复提交"
      record.terminalState == "unconfirmed" -> "压缩已结束，但最终结果无法确认；请检查会话后再操作"
      record.terminalState != null -> record.terminalMessage?.takeIf(String::isNotBlank)
        ?: "上次会话压缩未完成（${record.terminalState}）"
      else -> "压缩仍在处理中"
    }
    return RemoteFailureException(RemoteFailureKind.REJECTED, message, retryable = false)
  }

  private suspend fun <T> retryObservation(block: suspend () -> T): T {
    var last: Exception? = null
    repeat(MAX_ATTEMPTS) { attempt ->
      try { return block() }
      catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) {
        if (error is RemoteRpcException && error.code in PERMANENT_ERRORS) throw error
        if (error is RemoteFailureException && !error.retryable) throw error
        last = error
        if (attempt + 1 < MAX_ATTEMPTS) pause(500L * (attempt + 1))
      }
    }
    throw RemoteFailureException(RemoteFailureKind.NETWORK,
      "暂时无法确认压缩状态；重连后会查询原操作", retryable = true, cause = last)
  }

  private suspend fun <T> exclusive(sessionId: String, wait: Boolean = false, block: suspend () -> T): T {
    val key = scope to sessionId
    val entry = synchronized(locks) { locks.getOrPut(key) { LockEntry() }.also { it.users++ } }
    try {
      if (wait) entry.mutex.lock()
      else if (!entry.mutex.tryLock()) throw RemoteFailureException(RemoteFailureKind.REJECTED, "此会话已有压缩操作", retryable = false)
      try { return block() } finally { entry.mutex.unlock() }
    } finally {
      synchronized(locks) {
        entry.users--
        if (entry.users == 0) locks.remove(key, entry)
      }
    }
  }

  private companion object {
    class LockEntry(val mutex: Mutex = Mutex(), var users: Int = 0)
    val locks = ConcurrentHashMap<Pair<String, String>, LockEntry>()
    const val POLL_INTERVAL_MILLIS = 1_000L
    const val MAX_ATTEMPTS = 3
    val PERMANENT_ERRORS = setOf("FEATURE_NOT_SUPPORTED", "METHOD_NOT_FOUND", "PERMISSION_DENIED",
      "INVALID_MESSAGE", "SESSION_NOT_FOUND", "AGENT_BUSY", "UNSUPPORTED")
  }
}
