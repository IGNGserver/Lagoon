package com.igng.opencode.lagoon.core

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class ConnectionGraphFaultMatrixTest {
  private enum class Mode { BEFORE_WRITE, AFTER_WRITE, THROW_BEFORE, THROW_AFTER;
    val writes get() = this == AFTER_WRITE || this == THROW_AFTER
  }
  private class Faults(val backing: FakeSharedPreferences = FakeSharedPreferences()) : SharedPreferences by backing {
    var ordinal = 0
    private var failAt: Int? = null
    private var mode = Mode.BEFORE_WRITE
    fun failNext(n: Int, mode: Mode) { failAt = ordinal + n; this.mode = mode }
    override fun edit(): SharedPreferences.Editor {
      val delegate = backing.edit()
      return object : SharedPreferences.Editor by delegate {
        override fun putString(key: String, value: String?) = apply { delegate.putString(key, value) }
        override fun remove(key: String) = apply { delegate.remove(key) }
        override fun commit(): Boolean {
          if (++ordinal != failAt) return delegate.commit()
          if (mode.writes) delegate.commit()
          if (mode == Mode.THROW_BEFORE || mode == Mode.THROW_AFTER) throw IOException("graph-fault: injected commit failure")
          return false
        }
        override fun apply() { commit() }
      }
    }
  }
  private class Fixture {
    val graphPrefs = Faults()
    val plain = FakeSharedPreferences()
    val secrets = FakeSharedPreferences()
    val profiles = listOf(
      ServerProfile("direct", "Direct", "https://direct.example.test/"),
      ServerProfile("ssh", "SSH", "ssh://alice@example.test:22", username = "alice", kind = ServerKind.DSH, sshHostId = "saved-ssh"),
      ServerProfile("hub", "Hub", "https://hub.example.test/", username = "alice@example.test", kind = ServerKind.DSH_REMOTE,
        remoteHostId = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a", remoteHostFingerprint = "retained-fingerprint")
    )
    val runtime = ServerStore(plain, secrets, { it }, { it }, readConnectionGraph = {
      graphPrefs.getString("graph", null)?.let(ConnectionGraphCodec::decode)
    })
    var reads = 0
    val store = ConnectionGraphStore(graphPrefs, { reads++; runtime.readProfiles() })
    val old: ConnectionGraph
    val next: ConnectionGraph
    init {
      profiles.forEach { runtime.save(it, null) }
      plain.values["draft:direct:session"] = "draft-sentinel"
      plain.values["cache:direct"] = "cache-sentinel"
      secrets.values["unrelated"] = "opaque-sentinel"
      graphPrefs.backing.values["unrelated"] = "graph-sentinel"
      graphPrefs.backing.values["graph.extension"] = "extension-sentinel"
      old = store.requireGraph()
      val source = old.hosts.single { it.id == old.bindings.first().hostBindingId }.sourceId
      next = old.copy(sources = old.sources.map { if (it.id == source) it.copy(enabled = false) else it })
    }
    fun snapshot() = listOf(plain.all.toMap(), secrets.all.toMap(), graphPrefs.all.filterKeys { it !in setOf("graph", "graph.backup", "graph.pending", "graph.marker") })
    fun primary() = graphPrefs.getString("graph", null)?.let(ConnectionGraphCodec::decode)
    fun pending(raw: String = ConnectionGraphCodec.encode(next), checksum: String = sha(raw)) {
      graphPrefs.backing.values["graph.pending"] = raw
      graphPrefs.backing.values["graph.marker"] = JSONObject().put("format", 1).put("phase", "prepared").put("checksum", checksum).toString()
    }
    fun runtimeAllows(expected: Boolean, label: String) {
      val before = graphPrefs.all.toMap(); val commits = graphPrefs.ordinal
      val projected = runtime.profiles()
      assertEquals(label, profiles.map { it.id }, projected.map { it.id })
      assertEquals(label, expected, projected.first().effectiveEnabled)
      assertEquals(label, expected, runCatching { runtime.runtimeRoute(profiles.first()) }.isSuccess)
      assertEquals(label, expected, runCatching { runtime.runtimeProfile(profiles.first()) }.isSuccess)
      val otherParentsReadable = runCatching { primary() }.isSuccess
      projected.drop(1).forEach { assertEquals(label, otherParentsReadable, it.effectiveEnabled) }
      assertEquals(label, before, graphPrefs.all)
      assertEquals(label, commits, graphPrefs.ordinal)
    }
  }

  @Test fun saveFailureMatrixKeepsCommittedPrimaryAuthoritativeUntilExplicitRecovery() {
    Mode.entries.forEach { mode -> (1..3).forEach { checkpoint ->
      val label = "graph-fault: save $mode/$checkpoint"; val f = Fixture(); val before = f.snapshot()
      f.graphPrefs.failNext(checkpoint, mode)
      assertTrue(label, runCatching { f.store.setSourceEnabled(f.old.sources.first().id, false) }.exceptionOrNull() is IOException)
      val committed = checkpoint > 2 || checkpoint == 2 && mode.writes
      assertEquals(label, if (committed) f.next else f.old, f.primary())
      val pending = !(checkpoint == 1 && !mode.writes || checkpoint == 3 && mode.writes)
      assertEquals(label, pending, f.graphPrefs.contains("graph.marker"))
      assertEquals(label, pending, f.graphPrefs.contains("graph.pending"))
      f.runtimeAllows(!committed, label)
      assertEquals(label, if (checkpoint == 1 && !mode.writes) f.old else f.next, f.store.requireGraph())
      assertEquals(label, 1, f.reads)
      assertFalse(label, f.graphPrefs.contains("graph.marker"))
      assertFalse(label, f.graphPrefs.contains("graph.pending"))
      assertEquals(label, before, f.snapshot())
    } }
  }

  @Test fun recoveryCommitFailuresAreRetryableWithoutLegacyRereads() {
    Mode.entries.forEach { mode -> (1..2).forEach { checkpoint ->
      val label = "graph-fault: recovery $mode/$checkpoint"; val f = Fixture(); val before = f.snapshot()
      f.pending(); f.graphPrefs.failNext(checkpoint, mode)
      assertTrue(label, runCatching { f.store.requireGraph() }.exceptionOrNull() is IOException)
      val committed = checkpoint > 1 || mode.writes
      assertEquals(label, if (committed) f.next else f.old, f.primary())
      f.runtimeAllows(!committed, label)
      assertEquals(label, f.next, f.store.requireGraph())
      val completed = f.graphPrefs.all.toMap(); val commits = f.graphPrefs.ordinal
      assertEquals(label, f.next, f.store.requireGraph())
      assertEquals(label, completed, f.graphPrefs.all)
      assertEquals(label, commits, f.graphPrefs.ordinal)
      assertEquals(label, 1, f.reads)
      assertEquals(label, before, f.snapshot())
    } }
  }

  @Test fun interruptedPrimarySnapshotsRecoverFromAbsentOldOrAlreadyPromotedPrimary() {
    (0..2).forEach { state ->
      val label = "graph-fault: primary snapshot $state"; val f = Fixture(); val before = f.snapshot()
      f.pending()
      when (state) { 0 -> f.graphPrefs.backing.values.remove("graph"); 2 -> f.graphPrefs.backing.values["graph"] = ConnectionGraphCodec.encode(f.next) }
      f.runtimeAllows(state != 2, label)
      assertEquals(label, f.next, f.store.requireGraph())
      f.runtimeAllows(false, label)
      assertEquals(label, f.old.bindings, f.next.bindings)
      assertEquals(label, 1, f.reads)
      assertEquals(label, before, f.snapshot())
    }
  }

  @Test fun futurePrimaryOrPendingBlocksPromotionAndPreservesExactJournalBytes() {
    listOf(false, true).forEach { futurePrimary ->
      val label = "graph-fault: future primary=$futurePrimary"; val f = Fixture(); val before = f.snapshot()
      val future = JSONObject().put("version", ConnectionGraphCodec.VERSION + 1).put("opaque", "retain").toString()
      f.pending(if (futurePrimary) ConnectionGraphCodec.encode(f.next) else future)
      if (futurePrimary) f.graphPrefs.backing.values["graph"] = future
      val bytes = f.graphPrefs.all.toMap(); val commits = f.graphPrefs.ordinal
      assertTrue(label, runCatching { f.store.requireGraph() }.exceptionOrNull() is ConnectionGraphStore.UnsupportedGraphVersionException)
      assertTrue(label, runCatching { f.store.save(f.next) }.exceptionOrNull() is ConnectionGraphStore.UnsupportedGraphVersionException)
      f.runtimeAllows(!futurePrimary, label)
      assertEquals(label, bytes, f.graphPrefs.all)
      assertEquals(label, commits, f.graphPrefs.ordinal)
      assertEquals(label, before, f.snapshot())
    }
  }

  @Test fun corruptPendingChecksumOrPrimaryNeverChangesCurrentBytesDuringRuntime() {
    (0..2).forEach { state ->
      val label = "graph-fault: corruption $state"; val f = Fixture(); val before = f.snapshot()
      when (state) { 0 -> f.pending("{broken"); 1 -> f.pending(checksum = "incorrect"); 2 -> f.graphPrefs.backing.values["graph"] = "{broken" }
      val bytes = f.graphPrefs.all.toMap(); val commits = f.graphPrefs.ordinal
      assertTrue(label, runCatching { f.store.requireGraph() }.exceptionOrNull() is ConnectionGraphStore.CorruptGraphException)
      assertTrue(label, runCatching { f.store.save(f.next) }.exceptionOrNull() is ConnectionGraphStore.CorruptGraphException)
      f.runtimeAllows(state != 2, label)
      assertEquals(label, bytes, f.graphPrefs.all)
      assertEquals(label, commits, f.graphPrefs.ordinal)
      assertEquals(label, before, f.snapshot())
    }
  }

  @Test fun clearFailuresNeverTouchProfilesCachesDraftsSecretsOrUnrelatedKeys() {
    Mode.entries.forEach { mode ->
      val label = "graph-fault: clear $mode"; val f = Fixture(); val before = f.snapshot(); f.pending()
      val bytes = f.graphPrefs.all.toMap(); f.graphPrefs.failNext(1, mode)
      assertTrue(label, runCatching { f.store.clear() }.exceptionOrNull() is IOException)
      if (!mode.writes) assertEquals(label, bytes, f.graphPrefs.all)
      else assertTrue(label, listOf("graph", "graph.backup", "graph.pending", "graph.marker").none(f.graphPrefs::contains))
      assertEquals(label, before, f.snapshot())
    }
  }

  private companion object {
    fun sha(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
  }
}
