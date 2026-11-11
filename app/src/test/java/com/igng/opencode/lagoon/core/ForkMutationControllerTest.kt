package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ForkMutationControllerTest {
  private val profile = ServerProfile("host", "Host", "https://host.example/", kind = ServerKind.DSH)
  private val source = Session("source", "/repo", "Source", 1)
  private class Fixture(val client: HarnessClient, profile: ServerProfile, source: Session) {
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val controller = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LagoonController::class.java) as LagoonController
    val state = MutableStateFlow(LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
      sessionId = source.id, sessions = listOf(source), capabilities = ApiCapabilities(fork = true, historicalFork = true),
      messages = listOf(Message("input", "user", 1, emptyList(), sequence = 7))))
    val store = ServerStore(FakeSharedPreferences(), FakeSharedPreferences(), { it }, { it })
    init {
      store.save(profile, "password", "cookie"); store.rememberDraft(profile.id, source.id, "unsent")
      fun set(name: String, value: Any?) { LagoonController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value) }
      set("mutable", state); set("state", state); set("store", store); set("api", client); set("generation", 1)
      set("operationScope", CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }
  }
  private fun base(): HarnessClient = OpenCodeApi(profile, "fixture")

  @Test fun originalNativeBoundaryIsSentOnceAndUnknownCreationRequiresReadReceiptBeforeReconfirmation() = runBlocking {
    var writes = 0; var childrenRead = 0; var recentRead = 0
    val child = Session("possibly-created", "/repo", "Child", 2, parentId = source.id)
    val client = object : HarnessClient by base() {
      override suspend fun forkSession(session: Session, boundary: ForkBoundary): Session {
        assertEquals("source", session.id); assertEquals(ForkBoundary.DshEvent(7), boundary)
        writes++; throw java.io.IOException("Response lost after creation")
      }
      override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int): ApiPage<Session> { recentRead++; return ApiPage(emptyList(), "more") }
      override suspend fun children(session: Session): List<Session> { childrenRead++; return listOf(child) }
    }
    val f = Fixture(client, profile, source); val target = f.state.value.forkTarget("input")!!
    f.controller.forkAt(target).join(); f.controller.forkAt(target).join()
    assertEquals(1, writes); assertFalse(f.state.value.canForkSession())
    assertEquals("unsent", f.store.draft(profile.id, source.id))
    val receipt = f.controller.inspectForkResults(target)
    assertEquals(listOf("possibly-created"), receipt.page.items.map { it.id })
    assertEquals(1, childrenRead); assertEquals(1, recentRead); assertEquals(1, writes)
    f.controller.acknowledgeForkInspection(receipt)
    assertTrue(f.state.value.canConfirmFork(target)); assertEquals(1, writes)
    assertTrue(runCatching { f.controller.acknowledgeForkInspection(receipt) }.isFailure)
    client.close()
  }

  @Test fun staleSameIdHostOrChangedNativeRowCannotDispatchFork() = runBlocking {
    var writes = 0
    val client = object : HarnessClient by base() {
      override suspend fun forkSession(session: Session, boundary: ForkBoundary): Session { writes++; error("Must not dispatch") }
    }
    val f = Fixture(client, profile, source); val target = f.state.value.forkTarget("input")!!
    f.state.value = f.state.value.copy(messages = listOf(f.state.value.messages.single().copy(sequence = 8)))
    f.controller.forkAt(target).join(); assertEquals(0, writes)
    f.store.save(profile.copy(url = "https://other.example/"), "other", "")
    f.controller.forkAt(target).join(); assertEquals(0, writes)
    client.close()
  }

  @Test fun lateAcknowledgementDoesNotInsertOrSelectChildInReplacementContext() = runBlocking {
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var writes = 0
    val client = object : HarnessClient by base() {
      override suspend fun forkSession(session: Session, boundary: ForkBoundary): Session {
        writes++; entered.complete(Unit); release.await(); return Session("created", "/repo", "New", 2)
      }
    }
    val f = Fixture(client, profile, source); val target = f.state.value.forkTarget("input")!!
    val job = f.controller.forkAt(target); entered.await()
    f.store.save(profile.copy(url = "https://replacement.example/"), "new", ""); release.complete(Unit); job.join()
    assertEquals(1, writes); assertEquals(source.id, f.state.value.sessionId)
    assertEquals(listOf(source.id), f.state.value.sessions.map { it.id })
    assertEquals(listOf(target), f.state.value.uncertainForks)
    client.close()
  }

  @Test fun inspectionReceiptCannotBeUsedAfterChangingOriginalRoute() = runBlocking {
    var writes = 0
    val client = object : HarnessClient by base() {
      override suspend fun forkSession(session: Session, boundary: ForkBoundary): Session { writes++; throw java.io.IOException("Unknown") }
      override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int) = ApiPage<Session>(emptyList(), null)
      override suspend fun children(session: Session) = emptyList<Session>()
    }
    val f = Fixture(client, profile, source); val target = f.state.value.forkTarget("input")!!
    f.controller.forkAt(target).join(); val receipt = f.controller.inspectForkResults(target)
    f.store.save(profile.copy(url = "https://replacement.example/"), "new", "")
    assertTrue(runCatching { f.controller.acknowledgeForkInspection(receipt) }.isFailure)
    assertEquals(1, writes); assertEquals(listOf(target), f.state.value.uncertainForks)
    client.close()
  }
}
