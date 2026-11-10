package com.igng.opencode.lagoon.core

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshQuestionCoordinatorTest {
  private fun questions() = JSONArray().put(JSONObject().put("id", "q").put("question", "Choose")
    .put("detail", "Details").put("options", JSONArray().put(JSONObject().put("label", "yes"))))
  private fun request(timed: Boolean = true) = JSONObject().put("questions", questions())
    .put("wait", JSONObject().put("callId", "call").put("timed", timed))
  private fun values(state: String? = null, queued: Boolean = false, includeInbox: Boolean = true): JSONObject {
    val active = JSONArray()
    if (state != null) active.put(JSONObject().put("callId", "call").put("questions", questions()).put("state", state))
    return JSONObject().put("userQuestions", JSONObject().put("active", active).put("settled", JSONArray())).apply {
      if (includeInbox) put("inbox", JSONObject().apply {
        if (queued) put("next-turn", JSONArray().put(JSONObject().put("source",
          JSONObject().put("kind", "user-question-reply").put("callId", "call"))))
      })
    }
  }
  private class Handle : Closeable { var closes = 0; override fun close() { closes++ } }
  private inner class Fixture : Closeable {
    var now = 0L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val handles = mutableListOf<Handle>()
    val outcomes = mutableListOf<Pair<String, JSONObject>>()
    val replies = mutableListOf<Triple<String, String, JSONObject>>()
    val publications = mutableListOf<List<DshQuestionRegistry.Snapshot>>()
    val errors = mutableListOf<Throwable>()
    var remaining: (Long) -> Unit = {}
    var ended: () -> Unit = {}
    var refreshes = 0
    var opening: suspend (Handle) -> Unit = {}
    var settlement: suspend () -> Unit = {}
    var refreshing: suspend () -> Unit = {}
    var replying: suspend () -> Unit = {}
    val coordinator = DshQuestionCoordinator(scope, { now }, { _, _, _, onRemaining, onClosed ->
      val handle = Handle().also(handles::add)
      remaining = onRemaining; ended = onClosed
      onRemaining(1000)
      opening(handle)
      handle
    }, { event, outcome -> outcomes += event to outcome; settlement() },
      { session, call, answer -> replies += Triple(session, call, answer); replying() },
      { refreshes++; refreshing() }, { publications += it }, { errors += it })
    fun active(): String {
      coordinator.presentation("session", true)
      return coordinator.receive("session", "event", request())
    }
    override fun close() { coordinator.close(); scope.cancel() }
  }

  @Test fun backgroundNeverClaimsAndForegroundLossDelegatesOnce() {
    Fixture().use { f ->
      f.coordinator.receive("session", "background", request())
      assertTrue(f.handles.isEmpty())
      assertEquals("next", f.outcomes.single().second.getString("kind"))
      val id = f.active()
      assertEquals(1, f.handles.size)
      f.coordinator.takeTime(id)
      f.coordinator.presentation("session", false)
      f.coordinator.presentation("session", false)
      assertEquals(1, f.handles.single().closes)
      assertEquals(1, f.outcomes.count { it.first == "event" })
      assertEquals("next", f.outcomes.last().second.getString("kind"))
      assertTrue(f.coordinator.snapshots().isEmpty())
      f.coordinator.presentation("session", true)
      assertEquals(1, f.handles.size)
    }
  }

  @Test fun onlyFirstRemainingFrameSetsDeadlineAndTimeoutRejectsExactlyOnce() {
    Fixture().use { f ->
      f.active()
      f.now = 250
      f.remaining(90_000)
      assertEquals(750L, f.coordinator.snapshots().single().remainingMs)
      f.now = 999; f.coordinator.tick()
      assertTrue(f.outcomes.isEmpty())
      f.now = 1000; f.coordinator.tick(); f.coordinator.tick()
      val outcome = f.outcomes.single().second
      assertEquals("rejected", outcome.getString("kind"))
      assertEquals("UserQuestionError", outcome.getJSONObject("error").getString("name"))
      assertEquals("ASK_TIMED_OUT", outcome.getJSONObject("error").getString("code"))
      assertEquals(1, f.handles.single().closes)
    }
  }

  @Test fun timeoutKeepsItsBusinessClaimUntilTheRejectionWasDelivered() = runBlocking {
    Fixture().use { f ->
      val delivered = CompletableDeferred<Unit>()
      f.active()
      f.settlement = { delivered.await() }
      f.now = 1000
      f.coordinator.tick()
      assertEquals(1, f.outcomes.size)
      assertEquals(0, f.handles.single().closes)
      f.coordinator.tick()
      assertEquals(1, f.outcomes.size)
      delivered.complete(Unit)
      assertEquals(1, f.handles.single().closes)
    }
  }

  @Test fun focusPausesPristineCountdownAndEngagementHoldsIt() {
    Fixture().use { f ->
      val id = f.active()
      f.now = 200; f.coordinator.focus(id, true)
      f.now = 2000; f.coordinator.tick()
      assertEquals(800L, f.coordinator.snapshots().single().remainingMs)
      f.coordinator.focus(id, false)
      f.now = 2100; f.coordinator.engage(id)
      f.now = 9000; f.coordinator.tick()
      assertTrue(f.outcomes.isEmpty())
      assertFalse(f.coordinator.snapshots().single().countdownRunning)
      f.coordinator.takeTime(id)
      assertTrue(f.coordinator.snapshots().single().held)
    }
  }

  @Test fun lateOpeningHandleClosesAfterCancelAndCannotRestoreTimer() = runBlocking {
    Fixture().use { f ->
      val gate = CompletableDeferred<Unit>()
      f.opening = { withContext(NonCancellable) { gate.await() } }
      f.active()
      f.coordinator.cancelled("event")
      gate.complete(Unit)
      assertEquals(1, f.handles.single().closes)
      f.remaining(10)
      assertTrue(f.coordinator.snapshots().isEmpty())
      assertTrue(f.outcomes.isEmpty())
    }
  }

  @Test fun closeIsTerminalAndClosesLateHandleOnce() = runBlocking {
    Fixture().use { f ->
      val gate = CompletableDeferred<Unit>()
      f.opening = { withContext(NonCancellable) { gate.await() } }
      f.active()
      f.coordinator.close(); f.coordinator.close()
      gate.complete(Unit)
      assertEquals(1, f.handles.single().closes)
      assertEquals("next", f.outcomes.single().second.getString("kind"))
      assertTrue(f.coordinator.snapshots().isEmpty())
      assertTrue(f.publications.last().isEmpty())
    }
  }

  @Test fun claimStreamCloseReleasesHandleAndDoesNotAutomaticallyReopen() {
    Fixture().use { f ->
      f.active(); f.ended(); f.ended()
      f.coordinator.presentation("session", true)
      assertEquals(1, f.handles.single().closes)
      assertTrue(f.coordinator.snapshots().isEmpty())
    }
  }

  @Test fun failedLiveSettlePropagatesWithoutContinuedFallbackOrRetry() = runBlocking {
    Fixture().use { f ->
      val id = f.active()
      val failure = IOException("unknown delivery")
      f.settlement = { throw failure }
      f.refreshing = { f.coordinator.reconcile("session", values("continued")) }
      val actual = runCatching { f.coordinator.answer(id, JSONObject().put("answers", JSONArray())) }.exceptionOrNull()
      assertSame(failure, actual)
      assertEquals(1, f.outcomes.size)
      assertTrue(f.replies.isEmpty())
      assertEquals(DshQuestionRegistry.Route.CONTINUED, f.coordinator.snapshots().single().route)
      f.settlement = {}
      f.coordinator.answer(id, JSONObject()) // Explicit second user action may use the reconciled route.
      assertEquals("call", f.replies.single().second)
      assertTrue(f.coordinator.snapshots().isEmpty())
    }
  }

  @Test fun liveSuccessThatBecameContinuedRequiresExplicitResubmit() = runBlocking {
    Fixture().use { f ->
      val id = f.active()
      f.refreshing = { f.coordinator.reconcile("session", values("continued")) }
      val error = runCatching { f.coordinator.answer(id, JSONObject()) }.exceptionOrNull()
      assertEquals(409, (error as ApiException).status)
      assertTrue(f.replies.isEmpty())
      assertEquals(1, f.refreshes)
      assertEquals(1, f.outcomes.size)
    }
  }

  @Test fun continuedRequiresAuthoritativeStateAndKnownUnqueuedInbox() = runBlocking {
    Fixture().use { f ->
      f.coordinator.reconcile("session", values("open"))
      assertTrue(f.coordinator.snapshots().isEmpty())
      f.coordinator.reconcile("session", values("continued", includeInbox = false))
      assertTrue(f.coordinator.snapshots().isEmpty())
      f.coordinator.reconcile("session", values("continued", queued = true))
      assertTrue(f.coordinator.snapshots().isEmpty())
      f.coordinator.reconcile("session", values("continued"))
      val id = f.coordinator.snapshots().single().id
      f.refreshing = { f.coordinator.reconcile("session", values()) }
      f.coordinator.answer(id, JSONObject())
      assertEquals(1, f.replies.size)
      assertTrue(f.outcomes.isEmpty())
      assertTrue(f.coordinator.snapshots().isEmpty())
    }
  }

  @Test fun concurrentAnswerCannotSettleSameLiveEventTwice() = runBlocking {
    Fixture().use { f ->
      val gate = CompletableDeferred<Unit>()
      val id = f.active()
      f.settlement = { gate.await() }
      val first = f.scope.async { f.coordinator.answer(id, JSONObject()) }
      val second = runCatching { f.coordinator.answer(id, JSONObject()) }.exceptionOrNull()
      assertEquals(409, (second as ApiException).status)
      assertEquals(1, f.outcomes.size)
      assertEquals(0, f.handles.single().closes)
      gate.complete(Unit); first.await()
      assertEquals(1, f.handles.single().closes)
      assertTrue(f.coordinator.snapshots().isEmpty())
    }
  }

  @Test fun legacyAnswerSkipsProjectionRefreshAndFailedSettleRetainsCard() = runBlocking {
    Fixture().use { f ->
      f.coordinator.presentation("session", true)
      val id = f.coordinator.receive("session", "legacy", JSONObject().put("questions", questions()))
      f.coordinator.answer(id, JSONObject())
      assertEquals(0, f.refreshes)
      assertTrue(f.coordinator.snapshots().isEmpty())
    }
    Fixture().use { f ->
      f.coordinator.presentation("session", true)
      val id = f.coordinator.receive("session", "legacy", JSONObject().put("questions", questions()))
      f.settlement = { throw IOException("unknown") }
      assertTrue(runCatching { f.coordinator.answer(id, JSONObject()) }.isFailure)
      assertEquals(1, f.coordinator.snapshots().size)
      assertTrue(runCatching { f.coordinator.answer(id, JSONObject()) }.isFailure)
      assertEquals(1, f.outcomes.size)
      assertEquals(0, f.refreshes)
    }
  }

  @Test fun discardedQueuedContinuedReplyCanBeAnsweredAgainInTheSameClient() = runBlocking {
    Fixture().use { f ->
      f.coordinator.reconcile("session", values("continued"))
      val id = f.coordinator.snapshots().single().id
      f.refreshing = { f.coordinator.reconcile("session", values("continued", queued = true)) }
      f.coordinator.answer(id, JSONObject())
      assertTrue(f.coordinator.snapshots().isEmpty())
      f.coordinator.reconcile("session", values("continued"))
      assertEquals(id, f.coordinator.snapshots().single().id)
      f.refreshing = { f.coordinator.reconcile("session", values()) }
      f.coordinator.answer(id, JSONObject())
      assertEquals(2, f.replies.size)
      assertTrue(f.outcomes.isEmpty())
    }
  }

  @Test fun oldSuccessfulAnswerCannotCompleteReplacementDeliveryForTheSameCall() = runBlocking {
    Fixture().use { f ->
      val gate = CompletableDeferred<Unit>()
      val id = f.active()
      f.settlement = { gate.await() }
      f.refreshing = { f.coordinator.reconcile("session", values("open")) }
      val oldAnswer = f.scope.async { runCatching { f.coordinator.answer(id, JSONObject()) } }
      assertEquals("event", f.outcomes.single().first)
      f.coordinator.cancelled("event")
      assertEquals(id, f.coordinator.receive("session", "replacement", request()))
      assertTrue(f.coordinator.snapshots().isEmpty())
      assertEquals(1, f.handles.size)
      gate.complete(Unit)
      assertTrue(oldAnswer.await().isSuccess)
      val replacement = f.coordinator.snapshots().single()
      assertEquals(id, replacement.id)
      assertEquals("replacement", replacement.eventId)
      assertEquals(DshQuestionRegistry.Route.LIVE, replacement.route)
      assertTrue(replacement.claimed)
      assertTrue(replacement.countdownRunning)
      assertEquals(2, f.handles.size)
      assertEquals(1, f.handles.first().closes)
      assertEquals(0, f.handles.last().closes)
      assertEquals("replacement", f.publications.last().single().eventId)
      assertEquals(1, f.refreshes)
      assertEquals(1, f.outcomes.size)
      assertTrue(f.replies.isEmpty())
    }
  }

  @Test fun oldFailedAnswerCannotHideOrPreventClaimingReplacementDeliveryForTheSameCall() = runBlocking {
    Fixture().use { f ->
      val gate = CompletableDeferred<Unit>()
      val failure = IOException("old delivery result unknown")
      val id = f.active()
      f.settlement = { gate.await(); throw failure }
      f.refreshing = { f.coordinator.reconcile("session", values("open")) }
      val oldAnswer = f.scope.async { runCatching { f.coordinator.answer(id, JSONObject()) } }
      assertEquals("event", f.outcomes.single().first)
      f.coordinator.cancelled("event")
      assertEquals(id, f.coordinator.receive("session", "replacement", request()))
      assertTrue(f.coordinator.snapshots().isEmpty())
      assertEquals(1, f.handles.size)
      gate.complete(Unit)
      assertSame(failure, oldAnswer.await().exceptionOrNull())
      val replacement = f.coordinator.snapshots().single()
      assertEquals(id, replacement.id)
      assertEquals("replacement", replacement.eventId)
      assertEquals(DshQuestionRegistry.Route.LIVE, replacement.route)
      assertTrue(replacement.claimed)
      assertTrue(replacement.countdownRunning)
      assertEquals(2, f.handles.size)
      assertEquals(1, f.handles.first().closes)
      assertEquals(0, f.handles.last().closes)
      assertEquals("replacement", f.publications.last().single().eventId)
      assertEquals(1, f.refreshes)
      assertEquals(1, f.outcomes.size)
      assertTrue(f.replies.isEmpty())
    }
  }

  @Test fun closeSettlementDrainWaitsForDeliveryAndRepeatedCloseDoesNotDuplicateIt() = runBlocking {
    Fixture().use { f ->
      val gate = CompletableDeferred<Unit>()
      var delivered = false
      f.active()
      f.settlement = { gate.await(); delivered = true }
      f.coordinator.close(); f.coordinator.close()
      assertEquals("next", f.outcomes.single().second.getString("kind"))
      assertTrue(f.coordinator.hasPendingSettlements())
      val drain = f.scope.async { f.coordinator.awaitSettlements() }
      assertFalse(drain.isCompleted)
      assertFalse(delivered)
      gate.complete(Unit)
      drain.await()
      assertTrue(delivered)
      assertFalse(f.coordinator.hasPendingSettlements())
      f.coordinator.close()
      assertEquals(1, f.outcomes.size)
      assertEquals(1, f.handles.single().closes)
      assertTrue(f.coordinator.snapshots().isEmpty())
      assertTrue(f.publications.last().isEmpty())
    }
  }

  @Test fun fieldsPreserveStableIdentityAndSuccessfulDismissRejects() = runBlocking {
    Fixture().use { f ->
      val id = f.active()
      val form = f.coordinator.forms { "/repo" }.single()
      assertEquals(id, form.id)
      assertEquals("/repo", form.directory)
      assertEquals("Choose\nDetails", form.questions.single().title)
      assertEquals("q", JSONObject(form.questions.single().field).getString("key"))
      assertEquals("yes", form.questions.single().options.single().value)
      f.coordinator.dismiss(id)
      assertEquals("ASK_CANCELLED", f.outcomes.single().second.getJSONObject("error").getString("code"))
      assertTrue(f.replies.isEmpty())
    }
  }
}
