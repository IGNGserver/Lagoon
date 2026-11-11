package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class OpenCodeManagementControllerTest {
  private val profile = ServerProfile("host", "Host", "https://host.example/")
  private val project = OpenCodeProjectInfo("project", "/repo", "Name", "git", null, null, null, "setup command", 1, 2, 3, null, listOf("/repo"))
  private val worktree = OpenCodeWorktree("/repo/work", "git")
  private class Fixture(client: HarnessClient, profile: ServerProfile) {
    private val type = Class.forName("sun.misc.Unsafe")
    private val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val controller = type.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LagoonController::class.java) as LagoonController
    val state = MutableStateFlow(LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
      capabilities = ApiCapabilities(openCodeProjectManagement = true, openCodeWorktreeManagement = true)))
    val plain = FakeSharedPreferences(); val secrets = FakeSharedPreferences()
    val store = ServerStore(plain, secrets, { it }, { it })
    init {
      store.save(profile, "password", "cookie"); store.rememberDraft(profile.id, "session", "unsent")
      fun set(name: String, value: Any?) { LagoonController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value) }
      set("mutable", state); set("state", state); set("store", store); set("api", client); set("generation", 1)
      set("openCodeManagementGate", OpenCodeManagementGate())
    }
  }
  private fun base(): HarnessClient = OpenCodeApi(profile, "fixture")

  @Test fun staleSameIdRouteCannotReadOrDeleteBeforeClientDispatch() = runBlocking {
    var calls = 0
    val client = object : HarnessClient by base() {
      override suspend fun openCodeProjects(): List<OpenCodeProjectInfo> { calls++; return listOf(project) }
      override suspend fun removeOpenCodeWorktree(expected: OpenCodeProjectInfo, worktree: OpenCodeWorktree, force: Boolean): Boolean { calls++; return true }
    }
    val f = Fixture(client, profile); val target = OpenCodeManagementTarget(profile)
    f.store.save(profile.copy(url = "https://replacement.example/"), "new", "")
    assertTrue(runCatching { f.controller.openCodeProjects(target) }.isFailure)
    assertTrue(runCatching { f.controller.removeOpenCodeWorktree(target, project, worktree, false) }.isFailure)
    assertEquals(0, calls); assertEquals("unsent", f.store.draft(profile.id, "session")); client.close()
  }

  @Test fun unknownCreationRequiresItsOwnInventoryNotOnlyMetadataBeforeAnotherWrite() = runBlocking {
    var writes = 0
    val client = object : HarnessClient by base() {
      override suspend fun openCodeProjects() = listOf(project)
      override suspend fun openCodeWorktrees(expected: OpenCodeProjectInfo) = listOf(worktree)
      override suspend fun createOpenCodeWorktree(expected: OpenCodeProjectInfo, input: OpenCodeWorktreeCreate): OpenCodeWorktree { writes++; throw java.io.IOException("Created; setup failed") }
    }
    val f = Fixture(client, profile); val target = OpenCodeManagementTarget(profile)
    assertTrue(runCatching { f.controller.createOpenCodeWorktree(target, project, OpenCodeWorktreeCreate()) }.isFailure)
    f.controller.openCodeProjects(target)
    assertTrue(runCatching { f.controller.createOpenCodeWorktree(target, project, OpenCodeWorktreeCreate()) }.isFailure)
    assertEquals(1, writes)
    f.controller.openCodeWorktrees(target, project)
    assertTrue(runCatching { f.controller.createOpenCodeWorktree(target, project, OpenCodeWorktreeCreate()) }.isFailure)
    assertEquals(2, writes); client.close()
  }

  @Test fun forceRequiredNeverAutomaticallyChangesForceOrCleansLocalRecords() = runBlocking {
    val flags = mutableListOf<Boolean>()
    val client = object : HarnessClient by base() {
      override suspend fun removeOpenCodeWorktree(expected: OpenCodeProjectInfo, worktree: OpenCodeWorktree, force: Boolean): Boolean {
        flags += force; throw ApiException(400, "Manual confirmation required", "WorktreeError", org.json.JSONObject().put("forceRequired", true))
      }
    }
    val f = Fixture(client, profile); val before = f.plain.all.toMap(); val secrets = f.secrets.all.toMap()
    val target = OpenCodeManagementTarget(profile)
    assertTrue(runCatching { f.controller.removeOpenCodeWorktree(target, project, worktree, false) }.isFailure)
    assertTrue(runCatching { f.controller.removeOpenCodeWorktree(target, project, worktree, true) }.isFailure)
    assertEquals(listOf(false), flags)
    assertEquals(before, f.plain.all); assertEquals(secrets, f.secrets.all); client.close()
  }

  @Test fun lateInventoryFromOldRouteIsDiscardedWithoutChangingSelectionOrProjects() = runBlocking {
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val client = object : HarnessClient by base() {
      override suspend fun openCodeWorktrees(expected: OpenCodeProjectInfo): List<OpenCodeWorktree> { entered.complete(Unit); release.await(); return listOf(worktree) }
    }
    val f = Fixture(client, profile); val before = f.state.value
    val job = async { runCatching { f.controller.openCodeWorktrees(OpenCodeManagementTarget(profile), project) } }
    entered.await(); f.store.save(profile.copy(url = "https://replacement.example/"), "new", ""); release.complete(Unit)
    assertTrue(job.await().isFailure); assertEquals(before, f.state.value); client.close()
  }
}
