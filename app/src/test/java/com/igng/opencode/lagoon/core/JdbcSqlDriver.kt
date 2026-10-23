package com.igng.opencode.lagoon.core

import java.sql.Connection
import java.sql.DriverManager
import javax.crypto.spec.SecretKeySpec

/** [SqlDriver] over an in-memory SQLite (xerial), so [TranscriptStore]'s SQL is exercised on the JVM. */
internal class JdbcSqlDriver(url: String = "jdbc:sqlite::memory:") : SqlDriver {
  private val connection: Connection = DriverManager.getConnection(url)
  private var depth = 0

  @Synchronized override fun execute(sql: String, args: Array<Any?>) {
    connection.prepareStatement(sql).use { statement ->
      args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
      statement.execute()
    }
  }

  @Synchronized override fun <T> query(sql: String, args: Array<Any?>, row: (SqlRow) -> T): List<T> =
    connection.prepareStatement(sql).use { statement ->
      args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
      statement.executeQuery().use { result ->
        val reader = object : SqlRow {
          override fun string(index: Int): String? = result.getString(index + 1)
          override fun long(index: Int): Long = result.getLong(index + 1)
          override fun blob(index: Int): ByteArray? = result.getBytes(index + 1)
        }
        buildList { while (result.next()) add(row(reader)) }
      }
    }

  @Synchronized override fun <T> transaction(block: () -> T): T {
    if (depth++ > 0) try { return block() } finally { depth-- }
    connection.autoCommit = false
    try {
      return block().also { connection.commit() }
    } catch (error: Throwable) {
      connection.rollback(); throw error
    } finally { depth--; connection.autoCommit = true }
  }
}

internal fun testStore(driver: SqlDriver = JdbcSqlDriver(), clock: () -> Long = { 0L }, budget: Long = TranscriptStore.DEFAULT_BUDGET_BYTES) =
  TranscriptStore(driver, AesGcmPayloadCipher(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")), clock, budget)

internal fun record(raw: org.json.JSONObject): CacheRecord = cacheRecord(raw)!!
