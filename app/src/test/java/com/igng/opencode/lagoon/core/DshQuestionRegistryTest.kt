package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshQuestionRegistryTest {
  private fun questions() = JSONArray().put(JSONObject().put("id", "q").put("question", "Choose")
    .put("options", JSONArray().put(JSONObject().put("label", "yes"))))
  private fun request(callId: String = "call", timed: Boolean = true) = JSONObject()
    .put("questions", questions()).put("wait", JSONObject().put("callId", callId).put("timed", timed))
  private fun row(callId: String = "call", state: String = "continued") = JSONObject()
    .put("callId", callId).put("questions", questions()).put("state", state)
  private fun view(vararg rows: JSONObject) = JSONObject().put("active", JSONArray(rows.toList()))
    .put("settled", JSONArray())
  private fun inbox(target: String = "next-turn", callId: String = "call") = JSONObject().put(target,
    JSONArray().put(JSONObject().put("source", JSONObject().put("kind", "user-question-reply").put("callId", callId))))
  private fun active(registry: DshQuestionRegistry, eventId: String = "event", callId: String = "call"): String {
    val id = registry.receiveWaterfall("session", eventId, request(callId))
    registry.setPresentation(id, visible = true, foreground = true)
    assertTrue(registry.acceptClaim(id, eventId, 1_000, 100))
    return id
  }
  private fun invalid(block: () -> Unit) {
    try { block(); fail("expected malformed input to fail") } catch (_: IllegalArgumentException) { }
  }

  @Test fun defaultIsPassiveAndRemainingDurationDrivesOneShotTimeout() {
    val registry = DshQuestionRegistry()
    val id = registry.receiveWaterfall("session", "event", request())
    assertFalse(registry.snapshot(id, 0)!!.needsClaim)
    assertFalse(registry.acceptClaim(id, "event", 1000, 0))
    assertTrue(registry.tick(10_000).isEmpty())
    registry.setPresentation(id, true, true)
    assertTrue(registry.snapshot(id, 0)!!.needsClaim)
    assertTrue(registry.acceptClaim(id, "event", 42, 100))
    assertFalse(registry.snapshot(id, 100)!!.needsClaim)
    assertEquals(1L, registry.snapshot(id, 141)!!.remainingMs)
    assertTrue(registry.tick(141).isEmpty())
    val actions = registry.tick(142)
    assertEquals(listOf(DshQuestionRegistry.ActionKind.REJECT, DshQuestionRegistry.ActionKind.RELEASE_CLAIM), actions.map { it.kind })
    assertEquals("ASK_TIMED_OUT", actions[0].code)
    assertEquals("event", actions[0].eventId)
    assertEquals("call", actions[0].callId)
    assertTrue(registry.tick(200).isEmpty())
    assertEquals(DshQuestionRegistry.Route.UNAVAILABLE, registry.snapshot(id, 200)!!.route)
  }

  @Test fun pristineFocusPausesThenResumesExactlyTheRemainingDuration() {
    val registry = DshQuestionRegistry()
    val id = active(registry)
    registry.focus(id, true, 300)
    assertEquals(800L, registry.snapshot(id, 9_000)!!.remainingMs)
    assertFalse(registry.snapshot(id, 9_000)!!.countdownRunning)
    assertTrue(registry.tick(9_000).isEmpty())
    registry.focus(id, false, 10_000)
    assertEquals(800L, registry.snapshot(id, 10_000)!!.remainingMs)
    assertTrue(registry.tick(10_799).isEmpty())
    assertEquals("ASK_TIMED_OUT", registry.tick(10_800).first().code)
  }

  @Test fun focusBeforeClaimPausesTheOpeningCountdown() {
    val registry = DshQuestionRegistry()
    val id = registry.receiveWaterfall("session", "event", request())
    registry.setPresentation(id, true, true)
    registry.focus(id, true, 10)
    assertTrue(registry.acceptClaim(id, "event", 500, 100))
    assertTrue(registry.tick(100_000).isEmpty())
    assertEquals(500L, registry.snapshot(id, 100_000)!!.remainingMs)
    registry.focus(id, false, 100_000)
    assertEquals("ASK_TIMED_OUT", registry.tick(100_500).first().code)
  }

  @Test fun editingAndTakeTimePauseButBackgroundAlwaysReleasesTheClaim() {
    for (edit in listOf(true, false)) {
      val registry = DshQuestionRegistry()
      val id = active(registry)
      if (edit) registry.engage(id, 300) else registry.takeTime(id)
      registry.focus(id, false, 500)
      assertTrue(registry.tick(10_000).isEmpty())
      val actions = registry.setPresentation(id, true, false)
      assertEquals(listOf(DshQuestionRegistry.ActionKind.DELEGATE, DshQuestionRegistry.ActionKind.RELEASE_CLAIM), actions.map { it.kind })
      assertFalse(registry.snapshot(id, 20_000)!!.claimed)
      assertFalse(registry.snapshot(id, 20_000)!!.needsClaim)
      assertFalse(registry.acceptClaim(id, "event", 1_000, 20_000))
      assertTrue(registry.tick(20_000).isEmpty())
    }
  }

  @Test fun continuedRecoveryUsesCallIdNotEventIdAndSurvivesLateCancellation() {
    val registry = DshQuestionRegistry()
    val id = registry.receiveWaterfall("session", "event", request())
    registry.reconcile("session", view(row()))
    assertEquals(DshQuestionRegistry.Route.LIVE, registry.snapshot(id, 0)!!.route)
    registry.cancelWaterfall("event")
    val continued = registry.snapshot(id, 0)!!
    assertEquals(DshQuestionRegistry.Route.CONTINUED, continued.route)
    assertEquals("call", continued.callId)
    assertNull(continued.eventId)
    registry.cancelWaterfall("event")
    assertEquals(DshQuestionRegistry.Route.CONTINUED, registry.snapshot(id, 0)!!.route)
    registry.reconcile("session", view())
    assertNull(registry.snapshot(id, 0))
  }

  @Test fun missingProjectionDoesNotPruneButEmptyDoesAndOpenHasNoRpc() {
    val registry = DshQuestionRegistry()
    registry.reconcile("session", view(row()))
    val id = registry.idFor("session", "call")
    assertEquals(DshQuestionRegistry.ProjectionRead.MISSING, registry.reconcile("session", null))
    assertEquals(DshQuestionRegistry.Route.CONTINUED, registry.snapshot(id, 0)!!.route)
    registry.reconcile("session", view(row(state = "open")))
    assertEquals(DshQuestionRegistry.Route.UNAVAILABLE, registry.snapshot(id, 0)!!.route)
    registry.reconcile("session", view())
    assertNull(registry.snapshot(id, 0))
  }

  @Test fun queuedRepliesHideOnlyContinuedCardsForBothInboxTargets() {
    for (target in listOf("next-turn", "next-step")) {
      val registry = DshQuestionRegistry()
      registry.reconcile("session", view(row()), inbox(target))
      val id = registry.idFor("session", "call")
      assertTrue(registry.snapshots(0).isEmpty())
      assertEquals(DshQuestionRegistry.Route.UNAVAILABLE, registry.snapshot(id, 0)!!.route)
      registry.reconcile("session", view(row())) // inbox not loaded: do not forget known queue
      assertTrue(registry.snapshots(0).isEmpty())
      registry.reconcile("session", view(row()), JSONObject())
      assertEquals(DshQuestionRegistry.Route.CONTINUED, registry.snapshots(0).single().route)
      registry.reconcile("session", view(row(state = "open")), inbox(target))
      assertEquals(1, registry.snapshots(0).size)
    }
  }

  @Test fun sessionScopedIdsAreStableAndCannotCollideOnDelimiters() {
    val registry = DshQuestionRegistry()
    registry.reconcile("one", view(row()))
    registry.reconcile("two", view(row()))
    assertEquals(2, registry.snapshots(0).size)
    assertNotEquals(registry.idFor("a:b", "c"), registry.idFor("a", "b:c"))
    val id = registry.idFor("one", "call")
    registry.reconcile("one", view(row()))
    assertEquals(id, registry.snapshot(id, 0)!!.id)
    registry.removeSession("one")
    assertEquals("two", registry.snapshots(0).single().sessionId)
  }

  @Test fun malformedViewsFailAtomicallyRatherThanLookingEmpty() {
    val registry = DshQuestionRegistry()
    registry.reconcile("session", view(row()))
    invalid { registry.reconcile("session", JSONObject()) }
    invalid { registry.reconcile("session", view(row(state = "future"))) }
    invalid { registry.reconcile("session", view(row(), row())) }
    invalid { registry.reconcile("session", view(row().put("questions", JSONArray()))) }
    invalid { registry.reconcile("session", view().put("settled", JSONArray().put(JSONObject().put("callId", "c")))) }
    invalid { registry.reconcile("session", view(), JSONObject().put("next-turn", 2)) }
    assertEquals(DshQuestionRegistry.Route.CONTINUED, registry.snapshots(0).single().route)
  }

  @Test fun emptyViewCannotCancelStillLiveWaterfallAndOldEventCannotCancelReplacement() {
    val registry = DshQuestionRegistry()
    val id = active(registry)
    registry.reconcile("session", view())
    assertEquals(DshQuestionRegistry.Route.LIVE, registry.snapshot(id, 0)!!.route)
    registry.cancelWaterfall("event")
    registry.receiveWaterfall("session", "replacement", request())
    registry.cancelWaterfall("event")
    assertEquals("replacement", registry.snapshot(id, 0)!!.eventId)
  }

  @Test fun nonTimedAndLegacyQuestionsNeverNeedAClaim() {
    val registry = DshQuestionRegistry()
    val named = registry.receiveWaterfall("session", "named-event", request(timed = false).also {
      it.getJSONObject("wait").remove("timed") // official indefinite wait omits this optional field
    })
    val legacy = registry.receiveWaterfall("session", "legacy-event", JSONObject().put("questions", questions()))
    for (id in listOf(named, legacy)) {
      registry.setPresentation(id, true, true)
      assertFalse(registry.snapshot(id, 0)!!.needsClaim)
      assertEquals(DshQuestionRegistry.Route.LIVE, registry.snapshot(id, 0)!!.route)
    }
    registry.cancelWaterfall("legacy-event")
    assertNull(registry.snapshot(legacy, 0))
  }

  @Test fun removeAndCloseReturnCleanupActionsOnlyOnce() {
    val registry = DshQuestionRegistry()
    active(registry)
    assertEquals(2, registry.removeSession("session").size)
    assertTrue(registry.removeSession("session").isEmpty())
    active(registry, eventId = "event2")
    assertEquals(2, registry.close().size)
    assertTrue(registry.close().isEmpty())
    assertTrue(registry.snapshots(0).isEmpty())
  }

  @Test fun boundedRegistryFailsWithoutEvictingLiveClaimsAndSnapshotsAreDetached() {
    val registry = DshQuestionRegistry(maxEntries = 1)
    val id = active(registry)
    invalid { registry.reconcile("other", view(row())) }
    assertTrue(registry.snapshot(id, 0)!!.claimed)
    registry.snapshot(id, 0)!!.questions.getJSONObject(0).put("question", "changed")
    assertEquals("Choose", registry.snapshot(id, 0)!!.questions.getJSONObject(0).getString("question"))
    registry.close()
    registry.reconcile("other", view(row()))
    assertEquals(1, registry.snapshots(0).size)
  }

  @Test fun invalidClaimsFailAndOverflowDoesNotCreateImmediateTimeout() {
    val registry = DshQuestionRegistry()
    val id = registry.receiveWaterfall("session", "event", request())
    registry.setPresentation(id, true, true)
    invalid { registry.acceptClaim(id, "event", -1, 0) }
    assertFalse(registry.acceptClaim(id, "wrong-event", 1_000, 0))
    assertTrue(registry.acceptClaim(id, "event", 100, Long.MAX_VALUE - 10))
    assertEquals(10L, registry.snapshot(id, Long.MAX_VALUE - 10)!!.remainingMs)
    assertTrue(registry.tick(Long.MAX_VALUE - 1).isEmpty())
    assertEquals("ASK_TIMED_OUT", registry.tick(Long.MAX_VALUE).first().code)
  }
}
