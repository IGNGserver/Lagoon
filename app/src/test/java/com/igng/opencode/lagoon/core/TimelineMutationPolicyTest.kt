package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class TimelineMutationPolicyTest {
  private val human = Message("msg_2", "user", 1, listOf(MessagePart("part", "text", text = "Task")))
  private val session = Session("session", "/repo", "Task", 1)
  private fun state(profile: ServerProfile = ServerProfile("leaf", "Direct", "https://native.example/")) = LagoonState(
    profiles = listOf(profile), serverId = profile.id, sessionId = session.id, sessions = listOf(session),
    connected = true, capabilities = ApiCapabilities.BASELINE, messages = listOf(human))

  @Test fun nativeTimelinePolicyIsCarrierIndependent() {
    val direct = ServerProfile("leaf", "Direct", "https://native.example/")
    val ssh = direct.copy(url = "ssh://user@host:22", sshHostId = "ssh", sshServicePath = "/private/service.json")
    val remote = direct.copy(url = "https://hub.example/", username = "account", remoteHostId = "host", remoteServiceId = "service")
    for (profile in listOf(direct, ssh, remote)) assertTrue(state(profile).canRevertMessage(human.id))
  }

  @Test fun absentNativeCapabilityNeverOffersRevertOrRestore() {
    val unsupported = state().copy(capabilities = ApiCapabilities(actions = emptyMap()),
      sessions = listOf(session.copy(revertMessageId = human.id)))
    assertFalse(unsupported.canRevertMessage(human.id)); assertFalse(unsupported.canRestoreTimeline())
  }

  @Test fun offlineCacheBusyAndDisabledParentsCannotChangeHistory() {
    val normal = state()
    val blocked = listOf(normal.copy(connected = false), normal.copy(cached = true), normal.copy(degraded = true),
      normal.copy(tasks = mapOf(session.id to TaskState(session.id, TaskPhase.THINKING))),
      normal.copy(profiles = normal.profiles.map { it.copy(enabled = false) }),
      normal.copy(profiles = normal.profiles.map { it.copy(parentEnabled = false) }),
      normal.copy(pendingOperations = setOf(operationKey("leaf", session.id, null, "send"))),
      normal.copy(pendingOperations = setOf(operationKey("leaf", session.id, null, "unrevert"))))
    blocked.forEach { assertFalse(it.canRevertMessage(human.id)) }
  }

  @Test fun onlyDurableHumanInputsBeforeVisibleBoundaryAreEligible() {
    val normal = state()
    for (message in listOf(human.copy(role = "assistant"), human.copy(queued = true),
      human.copy(id = "$PENDING_PREFIX-local"), human.copy(sourceKind = "agent-message"))) {
      assertFalse(normal.copy(messages = listOf(message)).canRevertMessage(message.id))
    }
    assertFalse(normal.canRevertMessage("missing"))
    assertFalse(normal.copy(sessions = listOf(session.copy(revertMessageId = "msg_1"))).canRevertMessage(human.id))
    assertTrue(normal.copy(sessions = listOf(session.copy(revertMessageId = "msg_3"))).canRevertMessage(human.id))
  }

  @Test fun restoreNeedsExistingBoundaryAndItsOwnDocumentedAction() {
    val normal = state()
    assertFalse(normal.canRestoreTimeline())
    val staged = normal.copy(sessions = listOf(session.copy(revertMessageId = "msg_2")))
    assertTrue(staged.canRestoreTimeline())
    assertFalse(staged.copy(degraded = true, tasks = emptyMap()).canRestoreTimeline())
    assertFalse(staged.copy(capabilities = ApiCapabilities(actions = mapOf(SessionAction.REVERT to
      ActionEndpoint("POST", "api/session/{id}/revert/stage")))).canRestoreTimeline())
  }

  @Test fun confirmationFencesSessionRouteAndObservedBoundaryButNotCosmeticName() {
    val normal = state(); val target = TimelineMutationTarget(normal.server!!, session.id)
    assertTrue(target.matches(normal))
    assertTrue(target.matches(normal.copy(profiles = normal.profiles.map { it.copy(name = "Renamed") })))
    assertFalse(target.matches(normal.copy(sessionId = "other")))
    assertFalse(target.matches(normal.copy(profiles = normal.profiles.map { it.copy(url = "https://replacement.example/") })))
    assertFalse(target.matches(normal.copy(sessions = listOf(session.copy(revertMessageId = "changed")))))
  }
}
