package com.igng.opencode.lagoon.core

import android.content.SharedPreferences
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Durable profile/secret boundary failures, including commit(false) after an in-memory update. */
class ServerStoreSaveFaultTest {
  private enum class Mode(val writes: Boolean, val throws: Boolean) {
    FALSE_BEFORE(false, false), FALSE_AFTER(true, false), THROW_BEFORE(false, true), THROW_AFTER(true, true)
  }
  private class Faults(val backing: FakeSharedPreferences = FakeSharedPreferences()) : SharedPreferences by backing {
    var commits = 0
    var at = -1
    var mode = Mode.FALSE_BEFORE
    fun arm(offset: Int, failure: Mode) { at = commits + offset; mode = failure }
    override fun edit(): SharedPreferences.Editor {
      val editor = backing.edit()
      return object : SharedPreferences.Editor by editor {
        override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
        override fun remove(key: String) = apply { editor.remove(key) }
        override fun commit(): Boolean {
          if (++commits != at) return editor.commit()
          if (mode.writes) editor.commit()
          if (mode.throws) throw IOException("Injected durable boundary failure")
          return false
        }
        override fun apply() { commit() }
      }
    }
  }
  private class Fixture {
    val plain = Faults(); val secret = Faults()
    val old = ServerProfile("saved", "Original", "https://old.example/", kind = ServerKind.DSH, username = "dsh")
    var graph = MigrationProjection.fromProfiles(listOf(old))
    val store = reopen()
    init {
      store.save(old, "old-password", "old-cookie")
      store.rememberDraft(old.id, "session", "unsent draft")
      plain.backing.values["unrelated"] = "plain sentinel"
      secret.backing.values["unrelated"] = "encrypted sentinel"
    }
    fun reopen() = ServerStore(plain, secret, { "cipher:$it" }, { value ->
      check(value.startsWith("cipher:")); value.removePrefix("cipher:")
    }, readConnectionGraph = { graph })
  }
  private val journalKey = "configuration-save-journal-v1"

  @Test fun allSaveBoundariesRecoverExactOldOrCommittedNewWithoutCredentialRetargeting() {
    for (mode in Mode.entries) for (boundary in 1..3) {
      val f = Fixture(); val updated = f.old.copy(url = "https://new.example/", name = "Updated")
      val rawBefore = f.plain.getString("profiles", null)
      val oldSecret = f.secret.getString(f.old.id, null)
      if (boundary == 2) f.plain.arm(1, mode) else f.secret.arm(if (boundary == 1) 1 else 2, mode)
      assertTrue("$mode/$boundary must report an unconfirmed save", runCatching { f.store.save(updated, "new-password", "new-cookie") }.isFailure)
      val primaryUpdated = boundary == 3 || boundary == 2 && mode.writes
      assertFalse(f.store.profiles().single().effectiveEnabled)
      assertEquals("", f.store.credentials(f.old.id).password)
      assertTrue(runCatching { f.store.runtimeRoute(if (primaryUpdated) updated else f.old) }.isFailure)
      assertTrue(runCatching { f.store.requireCurrentAuthentication(f.old.id, ServerCredentials("dsh")) }.isFailure)
      if (boundary != 3 || !mode.writes) assertEquals(oldSecret, f.secret.getString(f.old.id, null))
      val restored = f.reopen()
      assertFalse(f.secret.contains(journalKey))
      assertEquals(if (primaryUpdated) updated else f.old, restored.readProfiles().single())
      assertEquals(if (primaryUpdated) "new-password" else "old-password", restored.credentials(f.old.id).password)
      assertEquals(if (primaryUpdated) "new-cookie" else "old-cookie", restored.credentials(f.old.id).cookie)
      if (!primaryUpdated) assertEquals(rawBefore, f.plain.getString("profiles", null))
      assertEquals("unsent draft", restored.draft(f.old.id, "session"))
      assertEquals("plain sentinel", f.plain.getString("unrelated", null))
      assertEquals("encrypted sentinel", f.secret.getString("unrelated", null))
      // A committed new profile still cannot execute through the old connection graph.
      if (primaryUpdated) assertTrue(runCatching { restored.runtimeRoute(updated) }.isFailure)
    }
  }

  @Test fun passwordOnlySaveUsesTransactionMarkerInsteadOfGuessingFromIdenticalProfileBytes() {
    for (mode in Mode.entries) {
      val f = Fixture(); f.plain.arm(1, mode)
      assertTrue(runCatching { f.store.save(f.old, "changed-password", "changed-cookie") }.isFailure)
      val recovered = f.reopen()
      assertEquals(if (mode.writes) "changed-password" else "old-password", recovered.credentials(f.old.id).password)
      assertEquals(if (mode.writes) "changed-cookie" else "old-cookie", recovered.credentials(f.old.id).cookie)
    }
  }

  @Test fun corruptFutureOrUnavailableJournalPreservesAllRecordsAndRejectsConfigurationWriters() {
    for (raw in listOf("unreadable ciphertext", "cipher:{bad", "cipher:{\"version\":99,\"opaque\":\"keep\"}")) {
      val f = Fixture(); f.secret.backing.values[journalKey] = raw
      val plain = f.plain.all.toMap(); val secrets = f.secret.all.toMap()
      val blocked = f.reopen()
      assertFalse(blocked.profiles().single().effectiveEnabled)
      assertEquals("", blocked.credentials(f.old.id).password)
      for (write in listOf<() -> Unit>({ blocked.save(f.old, "overwrite") }, { blocked.rename(f.old.id, "Overwrite") },
        { blocked.setProfileEnabled(f.old.id, false); Unit }, { blocked.delete(f.old.id) })) assertTrue(runCatching(write).isFailure)
      blocked.rememberCookie(f.old.id, f.old.url, "late-cookie")
      assertEquals(plain, f.plain.all); assertEquals(secrets, f.secret.all)
    }
  }

  @Test fun unrelatedProfileOrSecretEditsDuringInterruptedSaveAreNotSilentlyOverwritten() {
    for (alterSecret in listOf(false, true)) {
      val f = Fixture(); f.secret.arm(2, Mode.FALSE_BEFORE)
      assertTrue(runCatching { f.store.save(f.old.copy(name = "Committed"), "new-password", "new-cookie") }.isFailure)
      if (alterSecret) f.secret.backing.values[f.old.id] = "cipher:foreign newer value"
      else f.plain.backing.values["profiles"] = "[]"
      val plain = f.plain.all.toMap(); val secret = f.secret.all.toMap()
      val blocked = f.reopen()
      assertTrue(f.secret.contains(journalKey))
      assertTrue(runCatching { blocked.save(f.old, "cannot-overwrite") }.isFailure)
      assertEquals(plain, f.plain.all); assertEquals(secret, f.secret.all)
    }
  }

  @Test fun recoveryCommitFailureRemainsFailClosedAndCanBeRetriedLocally() {
    val f = Fixture(); f.secret.arm(2, Mode.FALSE_BEFORE)
    assertTrue(runCatching { f.store.save(f.old.copy(name = "New"), "new-password", "new-cookie") }.isFailure)
    f.secret.arm(1, Mode.FALSE_BEFORE)
    val blocked = f.reopen()
    assertTrue(f.secret.contains(journalKey)); assertFalse(blocked.profiles().single().effectiveEnabled)
    assertEquals("", blocked.credentials(f.old.id).password)
    val recovered = f.reopen()
    assertFalse(f.secret.contains(journalKey)); assertEquals("new-password", recovered.credentials(f.old.id).password)
  }

  @Test fun encryptionAndUnreadableExistingSecretsFailBeforeAnyConfigurationMutation() {
    val f = Fixture(); val previous = f.plain.all.toMap(); val secrets = f.secret.all.toMap()
    val encryptionFails = ServerStore(f.plain, f.secret, { throw IOException("Keystore unavailable") }, { it.removePrefix("cipher:") })
    assertTrue(runCatching { encryptionFails.save(f.old, "new-password", "new-cookie") }.isFailure)
    assertEquals(previous, f.plain.all); assertEquals(secrets, f.secret.all)
    f.secret.backing.values[f.old.id] = "ciphertext cannot decrypt"
    val unreadable = f.reopen(); val raw = f.secret.all.toMap()
    assertTrue(runCatching { unreadable.save(f.old.copy(name = "Display rename"), null) }.isFailure)
    assertEquals(previous, f.plain.all); assertEquals(raw, f.secret.all)
  }

  @Test fun malformedDecryptedCredentialCannotBeWipedByImplicitSaveOrLateCookieCallback() {
    for (value in listOf("{broken", "{\"password\":12}", "{\"origin\":true}", "{} trailing")) {
      val f = Fixture(); f.secret.backing.values[f.old.id] = "cipher:$value"
      val plain = f.plain.all.toMap(); val secrets = f.secret.all.toMap()
      assertTrue(runCatching { f.store.save(f.old.copy(name = "Rename"), null) }.isFailure)
      f.store.rememberCookie(f.old.id, f.old.url, "late-cookie")
      assertEquals(plain, f.plain.all); assertEquals(secrets, f.secret.all)
    }
  }

  @Test fun failedProfileDeletionNeverStartsDestructiveSecretOrDraftCleanup() {
    for (mode in Mode.entries) {
      val f = Fixture(); val secrets = f.secret.all.toMap()
      f.plain.arm(1, mode)
      assertTrue(runCatching { f.store.delete(f.old.id) }.isFailure)
      assertEquals(secrets, f.secret.all)
      assertEquals("unsent draft", f.store.draft(f.old.id, "session"))
      if (!mode.writes) assertEquals(f.old, f.store.readProfiles().single())
    }
  }

  @Test fun failedSecretCleanupIsReportedAfterExplicitMetadataDeletion() {
    val f = Fixture(); val secrets = f.secret.all.toMap(); f.secret.arm(1, Mode.FALSE_BEFORE)
    assertTrue(runCatching { f.store.delete(f.old.id) }.isFailure)
    assertTrue(f.store.readProfiles().isEmpty()); assertEquals(secrets, f.secret.all)
    assertEquals("unsent draft", f.store.draft(f.old.id, "session"))
    assertEquals("", f.store.credentials(f.old.id).password) // An orphan secret cannot authenticate a missing leaf.
  }

  @Test fun legacyCredentialReadHasNoWriteSideEffectBeforeJournaledSave() {
    val f = Fixture(); f.secret.backing.values[f.old.id] = "cipher:legacy-password"
    val before = f.secret.all.toMap(); val commits = f.secret.commits
    assertEquals("legacy-password", f.store.credentials(f.old.id).password)
    assertEquals(before, f.secret.all); assertEquals(commits, f.secret.commits)
    f.store.save(f.old.copy(name = "Renamed explicitly"), null)
    assertEquals("legacy-password", f.store.credentials(f.old.id).password)
    assertFalse(f.secret.contains(journalKey))
  }
}
