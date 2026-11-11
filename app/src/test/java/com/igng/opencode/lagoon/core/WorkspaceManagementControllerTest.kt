package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class WorkspaceManagementControllerTest {
  private val profile = ServerProfile("host", "Host", "https://host.example/", kind = ServerKind.DSH)
  private val item = ServerWorkspace("workspace-id", "/repo", "Workspace", listOf("retained-session"), "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z")
  private class Fixture(val client: HarnessClient, profile: ServerProfile) {
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val controller = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LagoonController::class.java) as LagoonController
    val state = MutableStateFlow(LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
      capabilities = ApiCapabilities(workspaceManagement = true)))
    val plain = FakeSharedPreferences(); val secrets = FakeSharedPreferences()
    val store = ServerStore(plain, secrets, { it }, { it })
    init {
      store.save(profile, "saved-password", "saved-cookie")
      store.rememberDraft(profile.id, "session", "keep unsent")
      fun set(name: String, value: Any?) { LagoonController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value) }
      set("mutable", state); set("state", state); set("store", store); set("generation", 1); set("api", client)
      set("workspaceManagementGate", WorkspaceManagementGate())
    }
  }
  private fun base(): HarnessClient = OpenCodeApi(profile, "fixture")

  @Test fun staleSameIdRouteCannotReadOrMutateNativeWorkspaceBeforeClientDispatch() = runBlocking {
    var calls = 0
    val client = object : HarnessClient by base() {
      override suspend fun serverWorkspaces(): List<ServerWorkspace> { calls++; return listOf(item) }
      override suspend fun deleteWorkspace(expected: ServerWorkspace): Boolean { calls++; return true }
    }
    val f = Fixture(client, profile); val target = WorkspaceManagementTarget(profile)
    f.store.save(profile.copy(url = "https://replacement.example/"), "new-password", "")
    assertTrue(runCatching { f.controller.serverWorkspaces(target) }.isFailure)
    assertTrue(runCatching { f.controller.deleteWorkspace(target, item) }.isFailure)
    assertEquals(0, calls)
    assertEquals("keep unsent", f.store.draft(profile.id, "session"))
    client.close()
  }

  @Test fun lateWorkspaceReadCannotPopulateANewSameIdHost() = runBlocking {
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val client = object : HarnessClient by base() {
      override suspend fun serverWorkspaces(): List<ServerWorkspace> { entered.complete(Unit); release.await(); return listOf(item) }
    }
    val f = Fixture(client, profile); val reading = async { runCatching { f.controller.serverWorkspaces(WorkspaceManagementTarget(profile)) } }
    entered.await(); f.store.save(profile.copy(url = "https://replacement.example/"), "new-password", ""); release.complete(Unit)
    assertTrue(reading.await().isFailure)
    assertTrue(f.state.value.workspaceCatalog.isEmpty())
    client.close()
  }

  @Test fun unknownDeleteSendsOnceAndOnlyExplicitSuccessfulReadUnlocksAnotherConfirmedAction() = runBlocking {
    var deletes = 0; var reads = 0
    val client = object : HarnessClient by base() {
      override suspend fun serverWorkspaces(): List<ServerWorkspace> { reads++; return listOf(item) }
      override suspend fun deleteWorkspace(expected: ServerWorkspace): Boolean { deletes++; throw java.io.IOException("Unknown delivery") }
    }
    val f = Fixture(client, profile); val target = WorkspaceManagementTarget(profile)
    val oldPlain = f.plain.all.toMap(); val oldSecrets = f.secrets.all.toMap()
    assertTrue(runCatching { f.controller.deleteWorkspace(target, item) }.isFailure)
    assertTrue(runCatching { f.controller.deleteWorkspace(target, item) }.isFailure)
    assertEquals(1, deletes)
    assertEquals(listOf(item), f.controller.serverWorkspaces(target)); assertEquals(1, reads)
    assertTrue(runCatching { f.controller.deleteWorkspace(target, item) }.isFailure); assertEquals(2, deletes)
    assertEquals(oldPlain, f.plain.all); assertEquals(oldSecrets, f.secrets.all)
    client.close()
  }

  @Test fun acknowledgedRegistrationDoesNotChangeLocalProjectsDraftsSelectionOrSessions() = runBlocking {
    val client = object : HarnessClient by base() {
      override suspend fun registerWorkspace(path: String) = WorkspaceRegistration(item, true)
    }
    val f = Fixture(client, profile); val target = WorkspaceManagementTarget(profile)
    val oldPlain = f.plain.all.toMap(); val oldSecrets = f.secrets.all.toMap(); val before = f.state.value
    assertEquals(item, f.controller.registerWorkspace(target, item.path).workspace)
    assertEquals(before, f.state.value); assertEquals(oldPlain, f.plain.all); assertEquals(oldSecrets, f.secrets.all)
    client.close()
  }
}
