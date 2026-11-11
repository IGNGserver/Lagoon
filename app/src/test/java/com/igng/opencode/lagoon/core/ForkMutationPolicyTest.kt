package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class ForkMutationPolicyTest {
  private val profile = ServerProfile("host", "Host", "https://host.example/", kind = ServerKind.DSH)
  private fun state(kind: ServerKind = ServerKind.DSH) = LagoonState(profiles = listOf(profile.copy(kind = kind)), serverId = profile.id,
    connected = true, sessionId = "source", sessions = listOf(Session("source", "/repo", "Source", 1)),
    capabilities = ApiCapabilities(fork = true, historicalFork = true), messages = listOf(Message("native-user-id", "user", 1,
      listOf(MessagePart("part", "text", text = "hello")), sequence = 7)))

  @Test fun protocolsProduceDifferentTypedBoundariesFromNativeIdentityNotRowIndex() {
    assertEquals(ForkBoundary.DshEvent(7), state().forkTarget("native-user-id")!!.boundary)
    assertEquals(ForkBoundary.DshEvent(7), state(ServerKind.DSH_REMOTE).forkTarget("native-user-id")!!.boundary)
    assertEquals(ForkBoundary.OpenCodeBefore("native-user-id"), state(ServerKind.OPENCODE).forkTarget("native-user-id")!!.boundary)
    assertNull(state().forkTarget("0")); assertNull(state().forkTarget("7"))
  }

  @Test fun unverifiedPendingInjectedOrAmbiguousRowsHaveNoBoundaryAction() {
    val base = state(); val original = base.messages.single()
    for (message in listOf(original.copy(sequence = null), original.copy(sequence = 0), original.copy(sequence = 9_007_199_254_740_992L),
      original.copy(queued = true), original.copy(sourceKind = "goal"), original.copy(role = "assistant"), original.copy(id = PENDING_PREFIX + "x"))) {
      assertFalse(base.copy(messages = listOf(message)).canForkAt(message.id))
    }
    assertFalse(base.copy(messages = listOf(original, original)).canForkAt(original.id))
    assertFalse(base.copy(cached = true).canForkAt(original.id)); assertFalse(base.copy(degraded = true).canForkSession())
    assertFalse(base.copy(capabilities = ApiCapabilities(fork = true)).canForkAt(original.id))
    assertFalse(base.copy(tasks = mapOf("source" to TaskState("source", TaskPhase.THINKING))).canForkSession())
  }

  @Test fun reconnectAndAggregateStateReplacementCannotAcknowledgeUnknownCreation() {
    val base = state(); val target = base.forkTarget("native-user-id")!!
    val locked = base.copy(uncertainForks = listOf(target))
    val aggregate = LagoonState(profiles = base.profiles, allServers = true).withForkUncertaintyFrom(locked)
    assertEquals(listOf(target), aggregate.uncertainForks)
    val reconnected = state().withForkUncertaintyFrom(aggregate)
    assertFalse(reconnected.canForkSession())
    assertTrue(reconnected.copy(profiles = listOf(profile.copy(url = "https://another-host.example/"))).canForkSession())
    assertFalse(state().withForkUncertaintyFrom(reconnected).canForkSession())
  }

  @Test fun capturedBoundaryRejectsChangedRowSessionAndSameIdRoute() {
    val base = state(); val target = base.forkTarget("native-user-id")!!
    assertTrue(target.matches(base)); assertTrue(base.canConfirmFork(target))
    assertFalse(target.matches(base.copy(messages = listOf(base.messages.single().copy(sequence = 8)))))
    assertFalse(target.matches(base.copy(sessionId = "other")))
    assertFalse(target.matches(base.copy(profiles = listOf(profile.copy(url = "https://replacement.example/")))))
    assertTrue(target.matches(base.copy(profiles = listOf(profile.copy(name = "Renamed")))))
    assertFalse(base.copy(uncertainForks = listOf(target)).canForkSession())
  }
}
