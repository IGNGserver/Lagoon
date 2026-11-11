package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OpenCodeManagementPolicyTest {
  private val profile = ServerProfile("host", "Host", "https://host.example/")
  @Test fun nativeManagementIsOnlyOfferedForOneSynchronizedOpenCodeHost() {
    val state = LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
      capabilities = ApiCapabilities(openCodeProjectManagement = true))
    assertTrue(state.canManageOpenCodeProjects())
    assertFalse(state.copy(cached = true).canManageOpenCodeProjects())
    assertFalse(state.copy(degraded = true).canManageOpenCodeProjects())
    assertFalse(state.copy(allServers = true).canManageOpenCodeProjects())
    assertFalse(state.copy(capabilities = ApiCapabilities()).canManageOpenCodeProjects())
    assertFalse(state.copy(profiles = listOf(profile.copy(kind = ServerKind.DSH))).canManageOpenCodeProjects())
    assertTrue(OpenCodeManagementTarget(profile).matches(state.copy(sessionId = "another")))
    assertFalse(OpenCodeManagementTarget(profile).matches(state.copy(profiles = listOf(profile.copy(url = "https://other.example/")))))
  }
  @Test fun aProjectCatalogCannotUnlockUnknownWorktreeCreation() = runBlocking {
    val gate = OpenCodeManagementGate(); var writes = 0
    assertTrue(runCatching { gate.write(profile, "project", true) { writes++; error("Created but setup failed") } }.isFailure)
    gate.catalog(profile) { "fresh metadata" }
    assertTrue(runCatching { gate.write(profile, "project", true) { writes++ } }.isFailure)
    assertTrue(runCatching { gate.inventory(profile, "project") { error("inventory unavailable") } }.isFailure)
    assertTrue(runCatching { gate.write(profile.copy(name = "renamed"), "project", true) { writes++ } }.isFailure)
    gate.inventory(profile, "another") { }
    assertTrue(runCatching { gate.write(profile, "project", true) { writes++ } }.isFailure)
    gate.inventory(profile, "project") { }
    gate.write(profile, "project", true) { writes++ }; assertEquals(2, writes)
  }
  @Test fun unknownMetadataWriteRequiresSuccessfulReadButNeverBlocksDifferentNativeProject() = runBlocking {
    val gate = OpenCodeManagementGate()
    assertTrue(runCatching { gate.write(profile, "project", false) { error("Unknown response") } }.isFailure)
    gate.write(profile, "other", false) { }
    gate.write(profile.copy(url = "https://other.example/"), "project", false) { }
    assertTrue(runCatching { gate.write(profile, "project", false) { } }.isFailure)
    gate.catalog(profile) { }
    gate.write(profile, "project", false) { }
  }
  @Test fun readReconciliationCannotRaceAheadOfTheOutstandingMutation() = runBlocking {
    val gate = OpenCodeManagementGate(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var read = false
    val pending = async { runCatching { gate.write(profile, "project", true) { entered.complete(Unit); release.await(); throw CancellationException("unknown") } } }
    entered.await(); val reconciliation = async { gate.inventory(profile, "project") { read = true } }
    yield(); assertFalse(read); release.complete(Unit); assertTrue(pending.await().isFailure)
    reconciliation.await(); assertTrue(read); gate.write(profile, "project", true) { }
  }
}
