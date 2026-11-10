package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.security.MessageDigest

/**
 * Versioned, local persistence for the unified connection graph.
 *
 * This store deliberately has no authority over [ServerStore].  The legacy profile reader is
 * injected so migration can only read profiles; the graph is written to this store's preferences.
 * A graph leaf keeps the exact legacy profile id and a [ConnectionCredentialRefs] record, while the
 * profile itself remains available through [HarnessBinding.legacyProfile].
 *
 * SharedPreferences does not offer a transaction spanning multiple keys.  We therefore use a
 * small journal protocol: write a validated pending document and marker, commit the old document
 * to a backup, commit the new primary, then remove the marker.  On the next read an unfinished
 * transaction is completed (or restored from the validated backup).  A failed or unknown primary
 * is never replaced by a migration or an ordinary save.
 */
class ConnectionGraphStore internal constructor(
  private val preferences: SharedPreferences,
  private val readProfiles: () -> List<ServerProfile>,
  private val readCompositions: () -> List<BindingComposition> = { emptyList() },
  private val resolvedRemoteSlots: () -> Map<String, String> = { emptyMap() }
) {
  constructor(
    context: Context,
    readProfiles: () -> List<ServerProfile>,
    readCompositions: () -> List<BindingComposition> = { emptyList() },
    resolvedRemoteSlots: () -> Map<String, String> = { emptyMap() }
  ) : this(
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    readProfiles, readCompositions, resolvedRemoteSlots
  )

  /** A caller can distinguish a successfully loaded/migrated graph from a future schema. */
  sealed interface LoadResult {
    val graph: ConnectionGraph?

    data class Loaded(
      override val graph: ConnectionGraph,
      val migrated: Boolean
    ) : LoadResult

    /** The primary document is valid JSON but belongs to a newer graph schema. */
    data class Unsupported(val version: Long) : LoadResult {
      override val graph: ConnectionGraph? = null
    }
  }

  /** A graph document was damaged; callers should preserve the legacy profile store unchanged. */
  class CorruptGraphException(message: String, cause: Throwable? = null) : IOException(message, cause)

  /** A document belongs to a newer app and must not be overwritten by this app. */
  class UnsupportedGraphVersionException(val version: Long) : IOException(
    "Connection graph version $version is newer than supported version ${ConnectionGraphCodec.VERSION}"
  )

  /**
   * Load the saved graph, or perform the one-time legacy projection when no graph exists.
   *
   * Once the graph key exists, the injected profile reader is not consulted.  This is intentional:
   * editing a legacy profile after migration must not silently rewrite graph state or its bytes.
   */
  @Synchronized
  fun loadOrMigrate(): LoadResult {
    recoverInterruptedWrite()
    val raw = preferences.getString(GRAPH_KEY, null)
      ?: return migrateFromProfiles()
    return when (val version = documentVersion(raw)) {
      null -> throw CorruptGraphException("Connection graph is not a versioned JSON document")
      in (ConnectionGraphCodec.VERSION + 1).toLong()..Long.MAX_VALUE -> LoadResult.Unsupported(version)
      else -> LoadResult.Loaded(decodePrimary(raw), migrated = false)
    }
  }

  /** Convenience for code that wants a graph and treats a future schema as an unavailable graph. */
  @Synchronized
  fun requireGraph(): ConnectionGraph = when (val result = loadOrMigrate()) {
    is LoadResult.Loaded -> result.graph
    is LoadResult.Unsupported -> throw UnsupportedGraphVersionException(result.version)
  }

  /**
   * Replace the graph atomically with [graph].  The candidate is encoded and decoded before any
   * preference is changed.  An unreadable or future primary blocks this method, preserving the raw
   * document and all legacy profile data.
   */
  @Synchronized
  fun save(graph: ConnectionGraph) {
    val encoded = try {
      // encode validates the in-memory graph; decode validates the complete wire representation.
      ConnectionGraphCodec.encode(graph).also(ConnectionGraphCodec::decode)
    } catch (error: Exception) {
      throw IOException("Connection graph candidate is invalid; nothing was written", error)
    }

    recoverInterruptedWrite()
    val current = preferences.getString(GRAPH_KEY, null)
    if (current != null) {
      when (val version = documentVersion(current)) {
        null -> throw CorruptGraphException("Connection graph is corrupted; refusing to overwrite it")
        in (ConnectionGraphCodec.VERSION + 1).toLong()..Long.MAX_VALUE ->
          throw UnsupportedGraphVersionException(version)
        else -> decodePrimary(current)
      }
      // Avoid even a preference write for an idempotent save. This also preserves exact bytes.
      if (current == encoded) return
    }
    writeAtomically(encoded, current)
  }

  /** Clears only graph/journal keys. Legacy profiles, credentials, caches and groups are untouched. */
  @Synchronized
  fun clear() {
    if (!preferences.edit().remove(GRAPH_KEY).remove(BACKUP_KEY).remove(PENDING_KEY)
        .remove(MARKER_KEY).commit()) {
      throw IOException("Unable to clear connection graph")
    }
  }

  private fun migrateFromProfiles(): LoadResult {
    val graph = try {
      MigrationProjection.fromProfiles(
        readProfiles(),
        resolvedRemoteSlots = resolvedRemoteSlots(),
        compositions = readCompositions()
      )
    } catch (error: Exception) {
      throw IOException("Legacy connection profiles cannot be migrated; nothing was written", error)
    }
    val encoded = try {
      ConnectionGraphCodec.encode(graph).also(ConnectionGraphCodec::decode)
    } catch (error: Exception) {
      throw IOException("Migrated connection graph failed validation; nothing was written", error)
    }
    // No primary exists here, so the journal still protects a process killed during first write.
    writeAtomically(encoded, previous = null)
    return LoadResult.Loaded(graph = ConnectionGraphCodec.decode(encoded), migrated = true)
  }

  private fun decodePrimary(raw: String): ConnectionGraph = try {
    ConnectionGraphCodec.decode(raw)
  } catch (error: Exception) {
    throw CorruptGraphException("Connection graph is corrupted; refusing to overwrite it", error)
  }

  private fun writeAtomically(encoded: String, previous: String?) {
    val marker = JSONObject()
      .put("format", JOURNAL_FORMAT_VERSION)
      .put("phase", "prepared")
      .put("checksum", checksum(encoded))
      .toString()
    val prepare = preferences.edit()
      .putString(PENDING_KEY, encoded)
      .putString(MARKER_KEY, marker)
    if (previous == null) prepare.remove(BACKUP_KEY) else prepare.putString(BACKUP_KEY, previous)
    commitOrThrow(prepare, "prepare connection graph write")

    // The primary is written only after both candidate encoding and candidate decoding succeeded.
    commitOrThrow(preferences.edit().putString(GRAPH_KEY, encoded), "commit connection graph")

    // If this final cleanup is interrupted, recoverInterruptedWrite sees the same valid pending
    // document and simply finishes cleanup on the next operation.
    commitOrThrow(preferences.edit().remove(PENDING_KEY).remove(MARKER_KEY), "finish connection graph write")
  }

  /** Recover only transactions bearing our marker and a checksum-valid, decodable candidate. */
  private fun recoverInterruptedWrite() {
    val markerRaw = preferences.getString(MARKER_KEY, null) ?: return
    val marker = try {
      JSONObject(markerRaw)
    } catch (error: Exception) {
      throw CorruptGraphException("Connection graph journal is corrupted; refusing to overwrite it", error)
    }
    if (marker.optInt("format", -1) != JOURNAL_FORMAT_VERSION || marker.optString("phase") != "prepared") {
      throw CorruptGraphException("Connection graph journal version is unsupported; refusing to overwrite it")
    }
    val pending = preferences.getString(PENDING_KEY, null)
      ?: throw CorruptGraphException("Connection graph journal has no pending document")
    val expectedChecksum = marker.optString("checksum").takeIf(String::isNotBlank)
      ?: throw CorruptGraphException("Connection graph journal has no checksum")
    if (checksum(pending) != expectedChecksum) {
      throw CorruptGraphException("Connection graph pending document checksum mismatch")
    }
    val pendingVersion = documentVersion(pending)
      ?: throw CorruptGraphException("Connection graph pending document is unreadable")
    if (pendingVersion > ConnectionGraphCodec.VERSION) throw UnsupportedGraphVersionException(pendingVersion)
    val pendingGraph = try { ConnectionGraphCodec.decode(pending) } catch (error: Exception) {
      throw CorruptGraphException("Connection graph pending document is unreadable", error)
    }

    // A marker proves that this candidate was prepared by this store. Promoting it is safe even
    // when the process died before the primary commit; profile data is in a separate key/store.
    val current = preferences.getString(GRAPH_KEY, null)
    if (current != pending) {
      // If a previous primary exists, validate it before replacing it. An invalid primary is
      // recoverable because the pending candidate has already passed strict decode above.
      if (current != null) {
        val currentVersion = documentVersion(current)
        if (currentVersion != null && currentVersion > ConnectionGraphCodec.VERSION) {
          throw UnsupportedGraphVersionException(currentVersion)
        }
        runCatching { ConnectionGraphCodec.decode(current) }
      }
      commitOrThrow(preferences.edit().putString(GRAPH_KEY, ConnectionGraphCodec.encode(pendingGraph)), "recover connection graph")
    }
    commitOrThrow(preferences.edit().remove(PENDING_KEY).remove(MARKER_KEY), "finish recovered connection graph write")
  }

  private fun commitOrThrow(editor: SharedPreferences.Editor, operation: String) {
    if (!editor.commit()) throw IOException("Unable to $operation")
  }

  private fun documentVersion(raw: String): Long? {
    return try {
      val tokener = JSONTokener(raw)
      val root = tokener.nextValue() as? JSONObject ?: return null
      if (tokener.nextClean() != '\u0000') return null
      val value = root.get("version")
      when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        else -> null
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

  private companion object {
    const val PREFERENCES_NAME = "connection-graph"
    const val GRAPH_KEY = "graph"
    const val BACKUP_KEY = "graph.backup"
    const val PENDING_KEY = "graph.pending"
    const val MARKER_KEY = "graph.marker"
    const val JOURNAL_FORMAT_VERSION = 1
  }
}
