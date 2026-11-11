package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class GoalMutationPolicyTest {
  private val profile = ServerProfile("leaf", "DSH", "https://dsh.example/", kind = ServerKind.DSH)
  private val goal = RemoteGoal("g", 2, "Objective", "active", "disarmed", maxGoalRounds = 10L, roundsStarted = 3L)
  private fun state() = LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
    sessionId = "session", sessions = listOf(Session("session", "/repo", "Task", 1)), goal = goal)

  @Test fun goalTargetFencesRouteSessionAndExactRefNotCosmeticName() {
    val target = GoalMutationTarget(profile, "session", goal.id, goal.revision)
    assertTrue(target.matches(state()))
    assertTrue(target.matches(state().copy(profiles = listOf(profile.copy(name = "Renamed")))))
    assertFalse(target.matches(state().copy(sessionId = "other")))
    assertFalse(target.matches(state().copy(goal = goal.copy(revision = 3))))
    assertFalse(target.matches(state().copy(goal = goal.copy(id = "replacement"))))
    assertFalse(target.matches(state().copy(profiles = listOf(profile.copy(url = "https://other.example/")))))
  }

  @Test fun resumeRequiresExplicitStoppedStateAndKnownAvailableBudget() {
    assertTrue(goal.canResumeGoal())
    assertTrue(goal.copy(phase = "paused", activation = null).canResumeGoal())
    assertTrue(goal.copy(phase = "blocked", activation = "disarmed").canResumeGoal())
    for (g in listOf(goal.copy(activation = "armed"), goal.copy(activation = null), goal.copy(phase = "complete"),
      goal.copy(roundsStarted = 10), goal.copy(roundsStarted = null), goal.copy(maxGoalRounds = null))) {
      assertFalse(g.canResumeGoal())
    }
  }

  @Test fun managementSupportIsNativeProtocolAndRuntimeGateNotCarrier() {
    val direct = state()
    assertTrue(direct.canManageGoal())
    assertTrue(direct.copy(profiles = listOf(profile.copy(sshHostId = "ssh", url = "ssh://user@host:22"))).canManageGoal())
    assertTrue(direct.copy(profiles = listOf(profile.copy(kind = ServerKind.DSH_REMOTE, remoteHostId = "host"))).canManageGoal())
    assertFalse(direct.copy(connected = false).canManageGoal())
    assertFalse(direct.copy(cached = true).canManageGoal())
    assertFalse(direct.copy(profiles = listOf(profile.copy(parentEnabled = false))).canManageGoal())
    assertFalse(direct.copy(profiles = listOf(profile.copy(kind = ServerKind.OPENCODE))).canManageGoal())
  }

  @Test fun oldReadCannotRollbackAnAcknowledgedEdit() {
    val edited = goal.copy(revision = 3, objective = "Edited")
    assertSame(edited, reconcileGoalRead(edited, goal, goal))
    assertSame(edited, reconcileGoalRead(edited, goal, null))
    val newer = edited.copy(revision = 4, activation = "armed")
    assertSame(newer, reconcileGoalRead(edited, edited, newer))
  }

  @Test fun currentAuthoritativeReadCanClearOrReplaceButLateOneCannot() {
    assertNull(reconcileGoalRead(goal, goal, null))
    val replacement = goal.copy(id = "new", revision = 1)
    assertSame(replacement, reconcileGoalRead(goal, goal, replacement))
    assertSame(replacement, reconcileGoalRead(replacement, goal, null))
    assertSame(replacement, reconcileGoalRead(replacement, goal, goal))
  }

  @Test fun mutationResultCannotReviveClearedGoalOrOverwriteNewerRevision() {
    val target = GoalMutationTarget(profile, "session", goal.id, goal.revision)
    val edited = goal.copy(revision = 3)
    assertSame(edited, reconcileGoalMutation(goal, target, edited))
    assertNull(reconcileGoalMutation(null, target, edited))
    val replacement = goal.copy(id = "new", revision = 1)
    assertSame(replacement, reconcileGoalMutation(replacement, target, edited))
    val newer = goal.copy(revision = 4)
    assertSame(newer, reconcileGoalMutation(newer, target, edited))
  }
}
