package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.GoalMutationTarget
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.RemoteGoal
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.operationKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalEditorPolicyTest {
  private val profile = ServerProfile("server", "Host", "https://host.example", kind = ServerKind.DSH)
  private val goal = RemoteGoal("goal", 4, "Original\nfull objective", "paused")
  private val target = GoalMutationTarget(profile, "session", goal.id, goal.revision)
  private fun state() = LagoonState(profiles = listOf(profile), serverId = profile.id,
    connected = true, sessions = listOf(Session("session", "/repo", "Session", 1)), sessionId = "session", goal = goal)
  private fun canSave(state: LagoonState = state(), objective: String = "My retained\ndraft", cap: String = "") =
    goalEditorCanSave(state, target, objective, cap)

  @Test fun blankKeepsHostCapAndSafeLongBoundariesAreAccepted() {
    assertNull(parseGoalRoundCap(" \n ").getOrThrow())
    for (value in listOf(1L, 2147483648L, MAX_GOAL_ROUND_CAP)) {
      assertEquals(value, parseGoalRoundCap(" $value ").getOrThrow())
    }
  }

  @Test fun onlyPositiveDecimalIntegerTextIsAccepted() {
    for (invalid in listOf("0", "-1", "+1", "1.5", "1e3", "1 0", "１２", "NaN")) {
      assertTrue(invalid, parseGoalRoundCap(invalid).isFailure)
    }
  }

  @Test fun unsafeAndOverflowingCapsNeverTruncateOrClamp() {
    for (invalid in listOf("9007199254740992", "9223372036854775807", "9223372036854775808", "999999999999999999999999")) {
      assertTrue(invalid, parseGoalRoundCap(invalid).isFailure)
    }
  }

  @Test fun exactLiveTargetAcceptsFullObjectiveAndHonestUnknownBudget() {
    assertTrue(canSave())
    assertTrue(canSave(cap = MAX_GOAL_ROUND_CAP.toString()))
    assertFalse(canSave(objective = " \n "))
    assertFalse(canSave(cap = "1.2"))
    assertEquals("自动续跑轮次：未知 / 上限 未知", goalRoundSummary(goal))
    assertFalse(goalEditorCanSave(state(), null, "draft", ""))
  }

  @Test fun staleRevisionReplacementSessionAndRouteDoNotRebindDraft() {
    val objective = "My retained\ndraft"
    val cap = "99"
    val initial = state()
    val staleStates = listOf(initial.copy(goal = goal.copy(revision = 5)), initial.copy(goal = goal.copy(id = "replacement")),
      initial.copy(goal = null), initial.copy(sessionId = "different"),
      initial.copy(profiles = listOf(profile.copy(url = "https://different.example"))))
    staleStates.forEach { assertFalse(goalEditorCanSave(it, target, objective, cap)) }
    assertEquals("My retained\ndraft", objective)
    assertEquals("99", cap)
    assertEquals(4, target.revision)
  }

  @Test fun pendingCachedDisconnectedAndParentGatedStatesDisableSave() {
    val initial = state()
    for (action in listOf("goal", "goal-refresh")) {
      assertFalse(canSave(initial.copy(pendingOperations = setOf(operationKey(profile.id, "session", null, action)))))
    }
    assertFalse(canSave(initial.copy(cached = true)))
    assertFalse(canSave(initial.copy(connected = false)))
    assertFalse(canSave(initial.copy(profiles = listOf(profile.copy(parentEnabled = false)))))
    assertFalse(canSave(initial.copy(profiles = listOf(profile.copy(enabled = false)))))
  }
}
