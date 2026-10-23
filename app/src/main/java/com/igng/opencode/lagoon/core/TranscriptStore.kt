package com.igng.opencode.lagoon.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The on-device copy of OpenCode transcripts.
 *
 * What is stored is exactly what the server returned: each row is one `Session.Message.Info` as wire
 * JSON (inline `data:` payloads omitted), read back through the same [toMessage] the network path
 * uses, so a cached row renders identically to a fresh one. Per session the rows form one contiguous
 * run of the server timeline, oldest to [SyncInfo.watermark], and every row in it is settled
 * ([isSettledMessage]): messages that can still change are never stored, they are always re-read.
 *
 * Writes carry the session's [epoch] taken before the network read; a deletion bumps it, so a read
 * that finishes after the user deleted the session (or the server) cannot write the rows back.
 * Payloads are compressed and sealed with a data key that only the Android Keystore can unwrap.
 */
internal class TranscriptStore internal constructor(
  private val open: () -> Backend,
  private val clock: () -> Long = System::currentTimeMillis,
  private val budgetBytes: Long = DEFAULT_BUDGET_BYTES
) {
  internal constructor(db: SqlDriver, cipher: PayloadCipher, clock: () -> Long = System::currentTimeMillis, budgetBytes: Long = DEFAULT_BUDGET_BYTES) :
    this({ Backend(db, cipher) }, clock, budgetBytes)

  internal class Backend(val db: SqlDriver, val cipher: PayloadCipher)

  /**
   * Opened on first use, which is always off the main thread (the controller's store dispatcher):
   * creating the database and unwrapping the data key must not delay app start.
   */
  private val backend: Backend? by lazy {
    runCatching { open().also { prepare(it.db) } }
      .onFailure { Diagnostics.warn("TranscriptStore", "会话缓存数据库无法打开，将只读服务器", it) }
      .getOrNull()
  }
  private val db: SqlDriver get() = backend?.db ?: throw IllegalStateException("会话缓存不可用")
  private val cipher: PayloadCipher get() = backend?.cipher ?: throw IllegalStateException("会话缓存不可用")
  private val lock = Any()
  private val epochs = HashMap<String, Long>()
  private var globalEpoch = 0L
  /** The open conversation; eviction never removes it. */
  @Volatile var protectedSession: Pair<String, String>? = null

  private fun prepare(db: SqlDriver) {
    db.transaction {
      SCHEMA.forEach { db.execute(it) }
      val format = db.query("SELECT value FROM meta WHERE key = 'format'") { it.string(0) }.firstOrNull()
      if (format != FORMAT_VERSION.toString()) {
        // A different parser, fingerprint or omission rule: nothing written by it can be trusted.
        db.execute("DELETE FROM message"); db.execute("DELETE FROM sync")
        db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES('format', ?)", arrayOf(FORMAT_VERSION.toString()))
      }
    }
  }

  private fun key(server: String, session: String) = "$server\u0000$session"

  /** Write token for one session; compare-and-write happens under [lock]. */
  fun epoch(server: String, session: String): Long = synchronized(lock) { globalEpoch * 1_000_000 + (epochs[key(server, session)] ?: 0L) }

  private fun bump(server: String, session: String) { epochs[key(server, session)] = (epochs[key(server, session)] ?: 0L) + 1 }

  /**
   * Binds [server] to the identity it is reached as (address and account). Rows written for another
   * identity of the same saved server are dropped: a profile edited to point elsewhere must never show
   * the previous server's conversations.
   */
  fun bindServer(server: String, identity: String) = synchronized(lock) {
    val known = db.query("SELECT identity FROM server WHERE id = ?", arrayOf(server)) { it.string(0) }.firstOrNull()
    if (known == identity) return@synchronized
    // Reads still in flight against the previous identity must not land under the new one.
    if (known != null) globalEpoch += 1
    db.transaction {
      if (known != null) deleteServerRows(server)
      db.execute("INSERT OR REPLACE INTO server(id, identity) VALUES(?, ?)", arrayOf(server, identity))
    }
  }

  fun info(server: String, session: String): SyncInfo? = db.query(
    "SELECT watermark, older_cursor, history_complete, session_updated, verified_at, accessed_at, bytes, count FROM sync WHERE server = ? AND session = ?",
    arrayOf(server, session)) { row ->
    SyncInfo(row.string(0)!!, row.string(1), row.long(2) != 0L, row.long(3), row.long(4), row.long(5), row.long(6), row.long(7).toInt())
  }.firstOrNull()

  /** Ids and fingerprints of the stored run, oldest first; cheap (no payloads). */
  fun fingerprints(server: String, session: String): List<Pair<String, String>> =
    db.query("SELECT id, fingerprint FROM message WHERE server = ? AND session = ? ORDER BY pos", arrayOf(server, session)) { it.string(0)!! to it.string(1)!! }

  /** The newest [limit] stored messages, oldest first. */
  fun newest(server: String, session: String, limit: Int): List<Message> =
    // Limits are inlined: Android's rawQuery binds every argument as text.
    decode(db.query("SELECT id, payload FROM message WHERE server = ? AND session = ? ORDER BY pos DESC LIMIT ${limit.coerceAtLeast(0)}",
      arrayOf(server, session)) { it.string(0)!! to it.blob(1)!! }).asReversed()

  /** Up to [limit] stored messages immediately older than [id], oldest first; empty when [id] is not stored. */
  fun older(server: String, session: String, id: String, limit: Int): List<Message> {
    val pos = position(server, session, id) ?: return emptyList()
    return decode(db.query("SELECT id, payload FROM message WHERE server = ? AND session = ? AND pos < ? ORDER BY pos DESC LIMIT ${limit.coerceAtLeast(0)}",
      arrayOf(server, session, pos)) { it.string(0)!! to it.blob(1)!! }).asReversed()
  }

  /** True when a stored message is older than [id]. */
  fun hasOlder(server: String, session: String, id: String): Boolean {
    val pos = position(server, session, id) ?: return false
    return db.query("SELECT 1 FROM message WHERE server = ? AND session = ? AND pos < ? LIMIT 1", arrayOf(server, session, pos)) { 1 }.isNotEmpty()
  }

  private fun position(server: String, session: String, id: String): Long? =
    db.query("SELECT pos FROM message WHERE server = ? AND session = ? AND id = ?", arrayOf(server, session, id)) { it.long(0) }.firstOrNull()

  private fun decode(rows: List<Pair<String, ByteArray>>): List<Message> = rows.mapNotNull { (id, payload) ->
    try { JSONObject(unpack(payload)).toMessage() } catch (error: Exception) {
      // A row that cannot be opened (lost key, corruption) is a cache miss, never a wrong message.
      Diagnostics.warn("TranscriptStore", "缓存消息 $id 无法读取", error)
      null
    }
  }

  /**
   * Replaces the session's stored run with [records] (oldest first). [olderCursor] is the server cursor
   * that continues older than the first record; [complete] means the first record starts the session.
   */
  fun replace(server: String, session: String, epoch: Long, records: List<CacheRecord>, olderCursor: String?, complete: Boolean,
    sessionUpdated: Long, verifiedAt: Long = 0): Boolean = write(server, session, epoch) {
    db.execute("DELETE FROM message WHERE server = ? AND session = ?", arrayOf(server, session))
    if (records.isEmpty()) { db.execute("DELETE FROM sync WHERE server = ? AND session = ?", arrayOf(server, session)); return@write }
    records.forEachIndexed { index, record -> insert(server, session, record, index.toLong()) }
    upsertSync(server, session, records.last().id, olderCursor, complete, sessionUpdated, verifiedAt)
  }

  /**
   * Appends [records] after the stored watermark, only if it is still [expectedWatermark]; [corrections]
   * overwrite rows whose content the server now renders differently.
   */
  fun append(server: String, session: String, epoch: Long, expectedWatermark: String, records: List<CacheRecord>,
    corrections: List<CacheRecord> = emptyList(), sessionUpdated: Long? = null, verifiedAt: Long? = null): Boolean = write(server, session, epoch) {
    val info = info(server, session) ?: throw StaleWrite
    if (info.watermark != expectedWatermark) throw StaleWrite
    corrections.forEach { record ->
      val pos = position(server, session, record.id) ?: throw StaleWrite
      insert(server, session, record, pos)
    }
    var pos = db.query("SELECT MAX(pos) FROM message WHERE server = ? AND session = ?", arrayOf(server, session)) { it.long(0) }.first()
    records.forEach { record -> insert(server, session, record, ++pos) }
    upsertSync(server, session, records.lastOrNull()?.id ?: info.watermark, info.olderCursor, info.historyComplete,
      sessionUpdated ?: info.sessionUpdated, verifiedAt ?: info.verifiedAt)
  }

  /** Prepends an older page fetched with the stored [expectedCursor]; [records] oldest first. */
  fun prepend(server: String, session: String, epoch: Long, expectedCursor: String, records: List<CacheRecord>,
    olderCursor: String?, complete: Boolean): Boolean = write(server, session, epoch) {
    val info = info(server, session) ?: throw StaleWrite
    if (info.olderCursor != expectedCursor || info.historyComplete) throw StaleWrite
    var pos = db.query("SELECT MIN(pos) FROM message WHERE server = ? AND session = ?", arrayOf(server, session)) { it.long(0) }.first()
    records.asReversed().forEach { record -> if (position(server, session, record.id) == null) insert(server, session, record, --pos) }
    upsertSync(server, session, info.watermark, olderCursor, complete, info.sessionUpdated, info.verifiedAt)
  }

  fun markVerified(server: String, session: String, at: Long = clock()) {
    db.execute("UPDATE sync SET verified_at = ? WHERE server = ? AND session = ?", arrayOf(at, server, session))
  }

  /** Records a read so eviction keeps conversations the user returns to. */
  fun touch(server: String, session: String) {
    db.execute("UPDATE sync SET accessed_at = ? WHERE server = ? AND session = ?", arrayOf(clock(), server, session))
  }

  fun delete(server: String, session: String) = synchronized(lock) {
    bump(server, session)
    db.transaction {
      db.execute("DELETE FROM message WHERE server = ? AND session = ?", arrayOf(server, session))
      db.execute("DELETE FROM sync WHERE server = ? AND session = ?", arrayOf(server, session))
    }
  }

  fun deleteServer(server: String) = synchronized(lock) {
    globalEpoch += 1
    db.transaction { deleteServerRows(server); db.execute("DELETE FROM server WHERE id = ?", arrayOf(server)) }
  }

  fun clear() = synchronized(lock) {
    globalEpoch += 1
    db.transaction { db.execute("DELETE FROM message"); db.execute("DELETE FROM sync") }
  }

  fun usage(): Usage = db.query("SELECT COALESCE(SUM(bytes), 0), COUNT(*) FROM sync") { Usage(it.long(0), it.long(1).toInt()) }.first()

  private fun deleteServerRows(server: String) {
    db.execute("DELETE FROM message WHERE server = ?", arrayOf(server))
    db.execute("DELETE FROM sync WHERE server = ?", arrayOf(server))
  }

  private object StaleWrite : RuntimeException() { private fun readResolve(): Any = StaleWrite }

  private fun write(server: String, session: String, epoch: Long, block: () -> Unit): Boolean = synchronized(lock) {
    if (epoch(server, session) != epoch) return@synchronized false
    val written = try { db.transaction { block() }; true } catch (stale: StaleWrite) { false } catch (large: TooLarge) {
      Diagnostics.warn("TranscriptStore", "会话 $session 有超大消息，本次未写入缓存")
      false
    }
    if (written) evict()
    written
  }

  private object TooLarge : RuntimeException() { private fun readResolve(): Any = TooLarge }

  private fun insert(server: String, session: String, record: CacheRecord, pos: Long) {
    val payload = pack(record.raw)
    if (payload.size > MAX_ROW_BYTES) throw TooLarge
    db.execute("INSERT OR REPLACE INTO message(server, session, id, pos, fingerprint, bytes, payload) VALUES(?, ?, ?, ?, ?, ?, ?)",
      arrayOf(server, session, record.id, pos, record.fingerprint, payload.size.toLong(), payload))
  }

  private fun upsertSync(server: String, session: String, watermark: String, olderCursor: String?, complete: Boolean, sessionUpdated: Long, verifiedAt: Long) {
    val totals = db.query("SELECT COALESCE(SUM(bytes), 0), COUNT(*) FROM message WHERE server = ? AND session = ?", arrayOf(server, session)) { it.long(0) to it.long(1) }.first()
    db.execute("INSERT OR REPLACE INTO sync(server, session, watermark, older_cursor, history_complete, session_updated, verified_at, accessed_at, bytes, count) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
      arrayOf(server, session, watermark, olderCursor, if (complete) 1L else 0L, sessionUpdated, verifiedAt, clock(), totals.first, totals.second))
  }

  /** Least recently opened conversations go first; the open one is kept even if it alone is over budget. */
  private fun evict() {
    var total = usage().bytes
    if (total <= budgetBytes) return
    val candidates = db.query("SELECT server, session, bytes FROM sync ORDER BY accessed_at ASC") { Triple(it.string(0)!!, it.string(1)!!, it.long(2)) }
    for ((server, session, bytes) in candidates) {
      if (total <= budgetBytes * 9 / 10) break
      if (protectedSession == (server to session)) continue
      bump(server, session)
      db.transaction {
        db.execute("DELETE FROM message WHERE server = ? AND session = ?", arrayOf(server, session))
        db.execute("DELETE FROM sync WHERE server = ? AND session = ?", arrayOf(server, session))
      }
      total -= bytes
    }
  }

  private fun pack(raw: String): ByteArray {
    val input = raw.toByteArray(Charsets.UTF_8)
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    val compressed = try {
      deflater.setInput(input); deflater.finish()
      val out = ByteArrayOutputStream(input.size / 4 + 64)
      val buffer = ByteArray(16_384)
      while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
      out.toByteArray()
    } finally { deflater.end() }
    return cipher.seal(compressed)
  }

  private fun unpack(payload: ByteArray): String {
    val compressed = cipher.open(payload)
    val inflater = Inflater()
    return try {
      inflater.setInput(compressed)
      val out = ByteArrayOutputStream(compressed.size * 4)
      val buffer = ByteArray(16_384)
      while (!inflater.finished()) {
        val read = inflater.inflate(buffer)
        if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("截断的缓存数据")
        out.write(buffer, 0, read)
      }
      out.toString(Charsets.UTF_8.name())
    } finally { inflater.end() }
  }

  data class Usage(val bytes: Long, val sessions: Int)

  companion object {
    /** Bump whenever [toMessage], [cacheRecord] or [isSettledMessage] change meaning. */
    const val FORMAT_VERSION = 1
    const val DEFAULT_BUDGET_BYTES = 64L * 1024 * 1024
    /** Sealed rows above this are not stored: Android cursor windows cannot read rows near 2 MB. */
    const val MAX_ROW_BYTES = 1_500_000

    private val SCHEMA = listOf(
      "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS server(id TEXT PRIMARY KEY, identity TEXT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS sync(server TEXT NOT NULL, session TEXT NOT NULL, watermark TEXT NOT NULL, older_cursor TEXT, " +
        "history_complete INTEGER NOT NULL, session_updated INTEGER NOT NULL, verified_at INTEGER NOT NULL, accessed_at INTEGER NOT NULL, " +
        "bytes INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(server, session))",
      "CREATE TABLE IF NOT EXISTS message(server TEXT NOT NULL, session TEXT NOT NULL, id TEXT NOT NULL, pos INTEGER NOT NULL, " +
        "fingerprint TEXT NOT NULL, bytes INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(server, session, id))",
      "CREATE INDEX IF NOT EXISTS message_pos ON message(server, session, pos)"
    )

    /** The process-wide store. Its database opens lazily; if it cannot, every call fails and the app reads the network. */
    @Volatile private var instance: TranscriptStore? = null
    fun get(context: Context): TranscriptStore = instance ?: synchronized(this) {
      instance ?: TranscriptStore({ openBackend(context.applicationContext) }).also { instance = it }
    }

    private fun openBackend(context: Context): Backend {
      val driver = AndroidSqlDriver(context, "transcripts.db")
      val wrapper = KeystoreCipher("lagoon-transcript-key")
      fun freshKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes).also { key ->
        driver.execute("DELETE FROM message"); driver.execute("DELETE FROM sync")
        driver.execute("INSERT OR REPLACE INTO meta(key, value) VALUES('dek', ?)", arrayOf(wrapper.encrypt(android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))))
      }
      SCHEMA.forEach(driver::execute)
      // A data key the Keystore can no longer unwrap (restored backup, cleared credentials) makes every
      // row unreadable: start over rather than fail each read.
      val wrapped = driver.query("SELECT value FROM meta WHERE key = 'dek'") { it.string(0) }.firstOrNull()
      val key = wrapped?.let { runCatching { android.util.Base64.decode(wrapper.decrypt(it), android.util.Base64.NO_WRAP) }.getOrNull() } ?: freshKey()
      return Backend(driver, AesGcmPayloadCipher(SecretKeySpec(key, "AES")))
    }
  }
}

internal data class SyncInfo(
  /** Id of the newest stored message. */
  val watermark: String,
  /** Server cursor continuing older than the oldest stored message; null when unknown or complete. */
  val olderCursor: String?,
  val historyComplete: Boolean,
  /** The session's `time.updated` when the run was last extended; a hint, never trusted on its own. */
  val sessionUpdated: Long,
  /** When every stored row was last compared with the server. */
  val verifiedAt: Long,
  val accessedAt: Long,
  val bytes: Long,
  val count: Int
)

/** One message prepared for the store: wire JSON without inline payloads, and its rendered fingerprint. */
internal data class CacheRecord(val id: String, val raw: String, val fingerprint: String)

/** Messages longer than this (as JSON text) are not stored; compressed they could exceed [TranscriptStore.MAX_ROW_BYTES]. */
private const val MAX_RECORD_CHARS = 1_000_000

/** A `data:` payload left out of the cache; the transcript shows a placeholder for it. */
internal const val OMITTED_INLINE_DATA = "lagoon-cache:omitted"

/**
 * The cacheable form of one server message, or null when it is too large to keep. The fingerprint is
 * taken over what the transcript renders, so two copies with equal fingerprints look identical.
 */
internal fun cacheRecord(raw: JSONObject): CacheRecord? {
  val stripped = omitInlineData(JSONObject(raw.toString())) as JSONObject
  val text = stripped.toString()
  if (text.length > MAX_RECORD_CHARS) return null
  return CacheRecord(stripped.str("id"), text, renderedFingerprint(stripped.toMessage()))
}

internal fun renderedFingerprint(message: Message): String =
  MessageDigest.getInstance("SHA-256").digest(message.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun omitInlineData(value: Any?): Any? = when (value) {
  is JSONObject -> value.apply { keys().asSequence().toList().forEach { key -> put(key, omitInlineData(opt(key))) } }
  is JSONArray -> value.apply { for (index in 0 until length()) put(index, omitInlineData(opt(index))) }
  is String -> if (value.startsWith("data:") && value.length > 256) OMITTED_INLINE_DATA else value
  else -> value
}

/**
 * True when the server will not change this message any more. Only such messages are stored: an
 * assistant step until it completes and all its tools settle, a running shell or compaction, and
 * background tool work still reporting `running` are always re-read from the server.
 */
internal fun isSettledMessage(raw: JSONObject): Boolean = when (raw.str("type")) {
  "assistant" -> raw.longPath("time", "completed") > 0 && raw.arr("content").objects().all { content ->
    content.str("type") != "tool" || content.obj("state").let { state ->
      state.str("status") in setOf("completed", "error") && state.obj("metadata").str("status") != "running"
    }
  }
  "shell" -> raw.longPath("time", "completed") > 0 || raw.str("status").let { it.isNotBlank() && it != "running" }
  "compaction" -> raw.str("status").let { it.isNotBlank() && it != "running" }
  "synthetic" -> raw.obj("metadata").str("state") !in setOf("running", "pending", "queued")
  else -> raw.str("id").isNotBlank()
}

/** Seals cache payloads. */
internal interface PayloadCipher {
  fun seal(plain: ByteArray): ByteArray
  fun open(sealed: ByteArray): ByteArray
}

/** Software AES-GCM under one data key (`iv || ciphertext`); the key itself is wrapped by the Keystore. */
internal class AesGcmPayloadCipher(private val key: SecretKey) : PayloadCipher {
  private val random = SecureRandom()
  override fun seal(plain: ByteArray): ByteArray {
    val iv = ByteArray(12).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    return iv + cipher.doFinal(plain)
  }
  override fun open(sealed: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
    return cipher.doFinal(sealed, 12, sealed.size - 12)
  }
}

/** The few SQL operations the store needs, so its queries also run in JVM tests. */
internal interface SqlDriver {
  fun execute(sql: String, args: Array<Any?> = emptyArray())
  fun <T> query(sql: String, args: Array<Any?> = emptyArray(), row: (SqlRow) -> T): List<T>
  fun <T> transaction(block: () -> T): T
}

internal interface SqlRow {
  fun string(index: Int): String?
  fun long(index: Int): Long
  fun blob(index: Int): ByteArray?
}

internal class AndroidSqlDriver(context: Context, name: String) : SqlDriver {
  private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) = Unit
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      // The cache is disposable: a new layout starts empty.
      listOf("message", "sync", "server", "meta").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
    }
  }
  private val db: SQLiteDatabase get() = helper.writableDatabase

  override fun execute(sql: String, args: Array<Any?>) { if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args) }

  override fun <T> query(sql: String, args: Array<Any?>, row: (SqlRow) -> T): List<T> =
    db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
      val reader = object : SqlRow {
        override fun string(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)
        override fun long(index: Int): Long = if (cursor.isNull(index)) 0L else cursor.getLong(index)
        override fun blob(index: Int): ByteArray? = if (cursor.isNull(index)) null else cursor.getBlob(index)
      }
      buildList { while (cursor.moveToNext()) add(row(reader)) }
    }

  override fun <T> transaction(block: () -> T): T {
    val database = db
    database.beginTransaction()
    try { return block().also { database.setTransactionSuccessful() } } finally { database.endTransaction() }
  }
}
