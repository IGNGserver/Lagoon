package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceManagementPolicyTest {
  private val profile = ServerProfile("host", "Host", "https://host.example/", kind = ServerKind.DSH)
  private fun state() = LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
    capabilities = ApiCapabilities(workspaceManagement = true))

  @Test fun managementRequiresSynchronizedSingleNativeHostNotCarrierOrDisplayName() {
    assertTrue(state().canManageServerWorkspaces())
    assertFalse(state().copy(cached = true).canManageServerWorkspaces())
    assertFalse(state().copy(degraded = true).canManageServerWorkspaces())
    assertFalse(state().copy(connected = false).canManageServerWorkspaces())
    assertFalse(state().copy(allServers = true).canManageServerWorkspaces())
    assertFalse(state().copy(capabilities = ApiCapabilities()).canManageServerWorkspaces())
    assertFalse(state().copy(profiles = listOf(profile.copy(kind = ServerKind.OPENCODE))).canManageServerWorkspaces())
    val target = WorkspaceManagementTarget(profile)
    assertTrue(target.matches(state().copy(sessionId = "other-session")))
    assertTrue(target.matches(state().copy(profiles = listOf(profile.copy(name = "Renamed")))))
    assertFalse(target.matches(state().copy(profiles = listOf(profile.copy(url = "https://other.example/")))))
    assertFalse(target.matches(state().copy(profiles = listOf(profile.copy(remoteHostFingerprint = "changed")))))
  }

  @Test fun unknownMutationCannotBeRepeatedBeforeSuccessfulExplicitRead() = runBlocking {
    val gate = WorkspaceManagementGate(); var writes = 0
    assertTrue(runCatching { gate.write(profile) { writes++; throw java.io.IOException("Unknown delivery") } }.isFailure)
    assertTrue(runCatching { gate.write(profile) { writes++ } }.isFailure)
    assertEquals(1, writes)
    assertTrue(runCatching { gate.write(profile.copy(name = "Display rename cannot clear uncertainty")) { writes++ } }.isFailure)
    assertTrue(runCatching { gate.read(profile) { throw java.io.IOException("Read failed") } }.isFailure)
    assertTrue(runCatching { gate.write(profile) { writes++ } }.isFailure)
    gate.read(profile) { "fresh confirmed catalog" }
    gate.write(profile) { writes++ }
    assertEquals(2, writes)
  }

  @Test fun serializationKeepsAReadFromAcknowledgingAnEarlierStateDuringAWrite() = runBlocking {
    val gate = WorkspaceManagementGate(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    var read = false
    val write = async { runCatching { gate.write(profile) { entered.complete(Unit); release.await(); error("uncertain") } } }
    entered.await()
    val refreshing = async { gate.read(profile) { read = true } }
    yield(); assertFalse(read)
    release.complete(Unit); assertTrue(write.await().isFailure)
    refreshing.await(); assertTrue(read)
    gate.write(profile) { /* explicit new mutation after completed read */ }
  }

  @Test fun cancelledMutationAlsoRequiresReconciliationAndDoesNotBlockOtherRoutes() = runBlocking {
    val gate = WorkspaceManagementGate()
    assertTrue(runCatching { gate.write(profile) { throw CancellationException("View closed after send") } }.isFailure)
    assertTrue(runCatching { gate.write(profile) { } }.isFailure)
    gate.write(profile.copy(url = "https://other.example/")) { }
    gate.read(profile) { }
    gate.write(profile) { }
  }
}
