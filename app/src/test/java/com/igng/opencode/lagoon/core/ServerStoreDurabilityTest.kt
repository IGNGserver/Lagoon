package com.igng.opencode.lagoon.core

import android.content.SharedPreferences
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Android commit(false) may publish the editor to RAM while durable disk still has the old map. */
class ServerStoreDurabilityTest {
  private class SplitPreferences(initialDisk: Map<String, Any?> = emptyMap()) : SharedPreferences by FakeSharedPreferences() {
    private val memory = FakeSharedPreferences().also { it.values.putAll(initialDisk) }
    val disk = LinkedHashMap(initialDisk)
    var commits = 0; var failAt = -1; var throwFailure = false
    fun failNext(offset: Int, throws: Boolean = false) { failAt = commits + offset; throwFailure = throws }
    fun restart() = SplitPreferences(disk)
    override fun getAll() = memory.all
    override fun getString(key: String, defValue: String?) = memory.getString(key, defValue)
    override fun getInt(key: String, defValue: Int) = memory.getInt(key, defValue)
    override fun contains(key: String) = memory.contains(key)
    override fun edit(): SharedPreferences.Editor {
      val editor = memory.edit()
      return object : SharedPreferences.Editor by editor {
        override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
        override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
        override fun remove(key: String) = apply { editor.remove(key) }
        override fun commit(): Boolean {
          editor.commit() // Exactly the dangerous Android behavior: memory changes first.
          if (++commits == failAt) {
            if (throwFailure) throw IOException("Injected disk failure after memory commit")
            return false
          }
          disk.clear(); disk.putAll(memory.all)
          return true
        }
        override fun apply() { commit() }
      }
    }
  }
  private class Fixture {
    var plain = SplitPreferences(); var secret = SplitPreferences()
    val old = ServerProfile("saved", "Old", "https://old.example/", kind = ServerKind.DSH, username = "dsh")
    val next = old.copy(name = "New", url = "https://new.example/")
    fun open() = ServerStore(plain, secret, { "encrypted:$it" }, { value -> check(value.startsWith("encrypted:")); value.removePrefix("encrypted:") })
    val initial = open()
    init { initial.save(old, "old-password", "old-cookie"); initial.rememberDraft(old.id, "draft", "keep unsent") }
    fun restart(): ServerStore { plain = plain.restart(); secret = secret.restart(); return open() }
    fun blocked(store: ServerStore) {
      assertFalse(store.profiles().single().effectiveEnabled)
      assertEquals("", store.credentials(old.id).password)
      assertTrue(runCatching { store.runtimeRoute(store.readProfiles().single()) }.isFailure)
    }
  }
  private val journal = "configuration-save-journal-v1"

  @Test fun failedPlainCommitCannotRetireOldCredentialsUntilChosenProfileIsDurable() {
    for (throws in listOf(false, true)) {
      val f = Fixture(); val oldDisk = f.plain.disk["profiles"]
      f.plain.failNext(1, throws)
      assertTrue(runCatching { f.initial.save(f.next, "new-password", "new-cookie") }.isFailure)
      assertEquals(oldDisk, f.plain.disk["profiles"])
      assertNotEquals(oldDisk, f.plain.getString("profiles", null))
      f.blocked(f.initial)
      // Immediate in-process recovery sees AFTER in RAM. It must durably commit it before cleanup.
      val recovered = f.open()
      assertEquals("new-password", recovered.credentials(f.old.id).password)
      assertEquals(f.next, recovered.readProfiles().single())
      val restarted = f.restart()
      assertEquals(f.next, restarted.readProfiles().single())
      assertEquals("new-password", restarted.credentials(f.old.id).password)
      assertEquals("keep unsent", restarted.draft(f.old.id, "draft"))
    }
  }

  @Test fun failedReaffirmationKeepsDurableJournalAndOldCredentialsForDiskRestart() {
    val f = Fixture(); val oldSecret = f.secret.disk[f.old.id]
    f.plain.failNext(1)
    assertTrue(runCatching { f.initial.save(f.next, "new-password", "new-cookie") }.isFailure)
    f.plain.failNext(1)
    val failedRecovery = f.open()
    f.blocked(failedRecovery)
    assertTrue(f.secret.disk.containsKey(journal))
    assertEquals(oldSecret, f.secret.disk[f.old.id])
    val restarted = f.restart() // Disk still BEFORE; recovery must roll back, not guess from former RAM.
    assertEquals(f.old, restarted.readProfiles().single())
    assertEquals("old-password", restarted.credentials(f.old.id).password)
    assertFalse(f.secret.disk.containsKey(journal))
  }

  @Test fun failedFinalSecretCommitBlocksEvenWhenItsJournalAlreadyDisappearedFromMemory() {
    for (throws in listOf(false, true)) {
      val f = Fixture(); f.secret.failNext(2, throws)
      assertTrue(runCatching { f.initial.save(f.next, "new-password", "new-cookie") }.isFailure)
      assertFalse(f.secret.contains(journal)) // Memory cleanup happened, disk cleanup did not.
      assertTrue(f.secret.disk.containsKey(journal))
      f.blocked(f.initial)
      val recovered = f.open() // Same preference handles, cached encrypted journal must still gate.
      assertEquals(f.next, recovered.readProfiles().single())
      assertEquals("new-password", recovered.credentials(f.old.id).password)
      val restarted = f.restart()
      assertEquals(f.next, restarted.readProfiles().single())
      assertEquals("new-password", restarted.credentials(f.old.id).password)
    }
  }

  @Test fun failedRecoveryFinalCommitAlsoKeepsIndependentAdmissionGate() {
    val f = Fixture(); f.plain.failNext(1)
    assertTrue(runCatching { f.initial.save(f.next, "new-password", "new-cookie") }.isFailure)
    f.secret.failNext(2) // Recovery re-prepare succeeds; final credential cleanup updates only RAM.
    val failed = f.open()
    assertFalse(f.secret.contains(journal)); assertTrue(f.secret.disk.containsKey(journal)); f.blocked(failed)
    val restarted = f.restart()
    assertEquals(f.next, restarted.readProfiles().single())
    assertEquals("new-password", restarted.credentials(f.old.id).password)
  }

  @Test fun failedPrepareInMemoryNeverChangesDurableProfileOrCredentials() {
    val f = Fixture(); f.secret.failNext(1)
    assertTrue(runCatching { f.initial.save(f.next, "new-password", "new-cookie") }.isFailure)
    f.blocked(f.initial)
    assertFalse(f.secret.disk.containsKey(journal))
    val restarted = f.restart()
    assertEquals(f.old, restarted.readProfiles().single())
    assertEquals("old-password", restarted.credentials(f.old.id).password)
  }
}
