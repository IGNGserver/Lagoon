package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class OfflineCache internal constructor(
  private val preferences: SharedPreferences,
  private val encrypt: (String) -> String,
  private val decrypt: (String) -> String,
  private val writes: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
  constructor(context: Context) : this(context.getSharedPreferences("offline_cache", Context.MODE_PRIVATE),
    KeystoreCipher("lagoon-offline-cache", legacyAlias = "opencode-lagoon-offline-cache")::encrypt,
    KeystoreCipher("lagoon-offline-cache", legacyAlias = "opencode-lagoon-offline-cache")::decrypt)
  private val pendingLock = Any()
  private val pending = LinkedHashMap<String, () -> String>()
  // Monotonic sequence per key. A write is committed only while it is still the newest intent for
  // that key, so a batch that was dequeued before a delete (or a newer write) is discarded instead of
  // resurrecting data the user removed (A14).
  private val nameSequence = HashMap<String, Long>()
  private val inFlight = mutableSetOf<String>()
  private var sequence = 0L
  private val index = ArrayList<String>()
  private var writer: Job? = null

  private fun nextSequence(name: String): Long {
    val value = ++sequence
    nameSequence[name] = value
    return value
  }
  private fun touchIndex(name: String) {
    index.remove(name)
    index.add(name)
    while (index.isNotEmpty() && (index.size > MAX_ENTRIES || cacheBytes() > MAX_TOTAL_BYTES)) evict(index.first())
    pruneSequences()
    persistIndex()
  }
  /** Keeps [nameSequence] from growing with every session ever opened. */
  private fun cacheBytes(): Long = preferences.all.filterKeys { it.startsWith(CATALOG_PREFIX) || it.startsWith(MESSAGES_PREFIX) }
    .values.sumOf { (it as? String)?.length?.times(2L) ?: 0L }
  private fun pruneSequences() {
    nameSequence.keys.retainAll(index.toSet() + pending.keys + inFlight)
  }
  private fun evict(name: String) {
    index.remove(name)
    synchronized(pendingLock) {
      pending.remove(name)
      nextSequence(name)
    }
    preferences.edit().remove(name).apply()
  }
  private fun persistIndex() {
    preferences.edit().putString(INDEX_KEY, JSONArray(index).toString()).apply()
  }
  private fun loadIndex() {
    index += runCatching { JSONArray(preferences.getString(INDEX_KEY, "[]")).let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } } }.getOrDefault(emptyList())
  }
  init {
    loadIndex()
    // Keys written before this version (or evicted from the index) are adopted so the bound still
    // applies to an upgraded install.
    preferences.all.keys.filter { it.startsWith(CATALOG_PREFIX) || it.startsWith(MESSAGES_PREFIX) }
      .filterNot(index::contains).forEach { index.add(it) }
    while (index.isNotEmpty() && (index.size > MAX_ENTRIES || cacheBytes() > MAX_TOTAL_BYTES)) evict(index.first())
    if (index.isNotEmpty()) persistIndex()
  }
  // Serialization and AES-GCM encryption of large transcripts are CPU-bound. Queue them on a
  // dedicated IO scope so refreshing a session never blocks the UI thread. Draining a snapshot of
  // the pending map means a fast stream cannot grow an unbounded encryption backlog and the latest
  // value for a key always wins, while writes stay ordered.
  private fun write(name: String, produce: () -> String) {
    synchronized(pendingLock) {
      pending[name] = produce
      nextSequence(name)
      if (writer == null) writer = writes.launch { drainWrites() }
    }
  }
  private suspend fun drainWrites() {
    while (true) {
      val batch = synchronized(pendingLock) {
        if (pending.isEmpty()) { writer = null; return }
        pending.mapValues { (name, produce) -> produce to nameSequence.getValue(name) }.also {
          inFlight.addAll(it.keys); pending.clear()
        }
      }
      for ((name, entry) in batch) {
        try {
          val (produce, captured) = entry
          val encoded = encrypt(produce())
          synchronized(pendingLock) {
            // Validation and commit share the delete lock. Encryption may finish after a deletion;
            // only the exact still-live write intent can reach preferences.
            if (nameSequence[name] == captured && encoded.length * 2L <= MAX_ENTRY_BYTES) {
              preferences.edit().putString(name, encoded).apply()
              touchIndex(name)
            }
          }
        } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Diagnostics.warn("OfflineCache", "缓存写入失败", error) }
        finally { synchronized(pendingLock) { inFlight.remove(name); pruneSequences() } }
      }
    }
  }
  internal suspend fun awaitWrites() { while (true) { val job = synchronized(pendingLock) { writer } ?: return; job.join() } }
  private fun read(name: String): String? = try {
    val encoded = preferences.getString(name, null) ?: return null
    decrypt(encoded)
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (error: Exception) {
    Diagnostics.warn("OfflineCache", "读取 $name 失败", error)
    null
  }
  fun saveCatalog(serverId: String, projects: List<Project>, sessions: List<Session>, complete: Boolean = true) {
    write("catalog:$serverId") {
      JSONObject().put("complete", complete && sessions.size <= 300).put("projects", JSONArray().apply { projects.forEach { put(JSONObject().put("id", it.id).put("directory", it.directory).put("name", it.name).put("sandboxes", JSONArray(it.sandboxes))) } })
        .put("sessions", JSONArray().apply { sessions.take(300).forEach { put(JSONObject().put("id", it.id).put("directory", it.directory).put("revertMessageId", it.revertMessageId)
          .put("title", it.title).put("updated", it.updated).put("parentId", it.parentId)
          .put("projectId", it.projectId).put("created", it.created).put("archived", it.archived).put("agent", it.agent)
          .put("viewed", it.viewed).put("idle", it.idle).put("outcome", it.outcome)
          .put("model", it.model?.let { model -> JSONObject().put("providerID", model.providerId).put("modelID", model.modelId).put("name", model.label) })) } })
        .toString()
    }
  }
  fun catalog(serverId: String): Pair<List<Project>, List<Session>>? {
    val raw = read("catalog:$serverId") ?: return null
    return try {
    val data = JSONObject(raw)
    data.arr("projects").objects().map { it.toProject() } to
      data.arr("sessions").objects().map { Session(it.str("id"), it.str("directory"), it.str("title"), it.optLong("updated"), it.str("parentId").ifBlank { null }, it.str("projectId").ifBlank { null }, it.optLong("created"), it.optBoolean("archived"),
        it.str("agent").ifBlank { null }, it.obj("model").toModelChoice(), it.optLong("viewed"), it.optLong("idle"), it.str("outcome").ifBlank { null },
        it.str("revertMessageId").ifBlank { null }) }
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (error: Exception) {
    Diagnostics.warn("OfflineCache", "catalog 解析失败", error)
    null
  }
  }
  fun saveMessages(serverId: String, sessionId: String, messages: List<Message>, complete: Boolean = true) {
    write("messages:$serverId:$sessionId") {
      val clipped = messages.size > 100 || messages.any { message -> message.parts.any { part ->
        part.text.length > 20_000 || part.input.length > 4_000 || part.output.length > 4_000 || part.error.length > 4_000 || part.patch.length > 20_000 ||
          part.path.startsWith("data:") || part.attachments.any { it.url.startsWith("data:") }
      } }
      // Inline `data:` payloads (screenshots, phone files) are never persisted: they can be megabytes each.
      fun url(value: String) = value.takeUnless { it.startsWith("data:") }.orEmpty()
      fun items(list: List<Message>) = JSONArray().apply { list.forEach { message ->
        put(JSONObject().put("id", message.id).put("role", message.role).put("type", message.type).put("created", message.created).put("error", message.error)
          .put("errorType", message.errorType).put("completedAt", message.completedAt).put("finish", message.finish).put("agent", message.agent)
          .put("retry", message.retry).put("queued", message.queued)
          .put("model", message.model?.let { model -> JSONObject().put("providerID", model.providerId).put("id", model.modelId).put("name", model.label).put("variant", model.variant) })
          .put("notice", message.notice?.let { notice -> JSONObject().put("label", notice.label).put("detail", notice.detail).put("items", JSONArray(notice.items))
            .put("target", notice.target).put("error", notice.error).put("running", notice.running) })
          .put("parts", JSONArray().apply { message.parts.forEach { part ->
            put(JSONObject().put("id", part.id).put("type", part.type).put("text", part.text.take(20_000))
              .put("tool", part.tool).put("title", part.title).put("status", part.status).put("input", part.input.take(4_000))
              .put("output", part.output.take(4_000)).put("path", url(part.path)).put("error", part.error.take(4_000))
              .put("patch", part.patch.take(20_000)).put("files", JSONArray(part.files)).put("mime", part.mime).put("target", part.target)
              .put("background", part.background)
              .put("attachments", JSONArray().apply { part.attachments.filterNot { it.url.startsWith("data:") }.forEach { put(JSONObject().put("uri", it.url).put("mime", it.mime).put("name", it.name)) } }))
          } }))
      } }
      fun payload(list: List<Message>, flag: Boolean) = JSONObject().put("messages", items(list)).put("complete", flag).toString()
      // 单条缓存有硬上限：超限时保留尽量新的后缀并标记为不完整，而不是整条丢弃、让旧缓存继续
      // 以 complete=true 冒充完整会话。
      var kept = messages.takeLast(100)
      var text = payload(kept, complete && !clipped)
      while (text.length > MAX_MESSAGES_CHARS && kept.size > 1) {
        kept = kept.subList(kept.size / 2, kept.size)
        text = payload(kept, false)
      }
      if (text.length > MAX_MESSAGES_CHARS) { kept = emptyList(); text = payload(kept, false) }
      text
    }
  }
  fun messages(serverId: String, sessionId: String): List<Message> {
    val raw = read("messages:$serverId:$sessionId") ?: return emptyList()
    return try {
    val items = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).arr("messages")
    items.objects().map { item ->
      val notice = item.optJSONObject("notice")?.let { Notice(it.str("label"), it.str("detail"), it.arr("items").strings(), it.str("target").ifBlank { null }, it.optBoolean("error"), it.optBoolean("running")) }
      val role = item.str("role")
      Message(item.str("id"), role, item.optLong("created"), item.arr("parts").objects().map { part ->
        MessagePart(part.str("id"), part.str("type"), part.str("text"), part.str("tool"), part.str("title"), part.str("status"), part.str("input"), part.str("output"), part.str("path"), part.str("error"), part.str("patch"),
          part.arr("files").strings(), part.str("mime"), part.arr("attachments").toAttachments(), part.str("target").ifBlank { null }, part.optBoolean("background"))
      }, item.str("error").ifBlank { null }, item.str("agent").ifBlank { null }, item.obj("model").toModelChoice(),
        item.optLong("completedAt").takeIf { it > 0 }, item.str("finish").ifBlank { null }, item.str("errorType").ifBlank { null },
        item.str("retry").ifBlank { null }, notice = notice, queued = item.optBoolean("queued"), type = item.str("type").ifBlank { role })
    }
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (error: Exception) {
    Diagnostics.warn("OfflineCache", "messages 解析失败", error)
    emptyList()
  }
  }
  fun messagesComplete(serverId: String, sessionId: String): Boolean = runCatching {
    JSONObject(read("messages:$serverId:$sessionId") ?: return false).optBoolean("complete", false)
  }.getOrDefault(false)
  fun catalogComplete(serverId: String): Boolean = runCatching {
    JSONObject(read("catalog:$serverId") ?: return false).optBoolean("complete", false)
  }.getOrDefault(false)
  fun delete(serverId: String) {
    synchronized(pendingLock) {
      val matches = (index + preferences.all.keys + pending.keys + inFlight + nameSequence.keys).filter { it == "catalog:$serverId" || it.startsWith("messages:$serverId:") }.toSet()
      matches.forEach { name ->
        pending.remove(name)
        nextSequence(name)
        preferences.edit().remove(name).apply()
      }
      index.removeAll(matches)
      persistIndex()
    }
  }

  /** Drops the cached transcript for one session, used when the session is deleted server-side. */
  fun deleteMessages(serverId: String, sessionId: String) {
    val name = "messages:$serverId:$sessionId"
    synchronized(pendingLock) {
      pending.remove(name)
      nextSequence(name)
      preferences.edit().remove(name).apply()
      index.remove(name)
      persistIndex()
    }
  }

  private companion object {
    const val INDEX_KEY = "__index"
    const val CATALOG_PREFIX = "catalog:"
    const val MESSAGES_PREFIX = "messages:"
    const val MAX_ENTRIES = 150
    const val MAX_TOTAL_BYTES = 16_000_000L
    const val MAX_ENTRY_BYTES = 2_000_000L
    /** 明文上限（加密 + Base64 后仍须低于 MAX_ENTRY_BYTES/2）；超出时截断为最新后缀并标记不完整。 */
    const val MAX_MESSAGES_CHARS = 700_000
  }
}
