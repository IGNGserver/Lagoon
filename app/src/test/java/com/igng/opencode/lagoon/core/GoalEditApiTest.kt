package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Official rc.5 goal wire contract, including exact-ref activation and mutation-view fences. */
class GoalEditApiTest {
  private fun client(server: MockWebServer) = DshApi(
    ServerProfile("goal-test", "DSH", server.url("/").toString().trimEnd('/'),
      allowCleartext = true, kind = ServerKind.DSH),
    ServerCredentials("dsh", cookie = "dsh-auth-goal=test")
  )

  private fun response(value: Any) = MockResponse().setBody(JSONObject()
    .put("type", "server-response").put("rpcId", "goal-test")
    .put("result", JSONObject().put("ok", true).put("value", value)).toString())

  private fun view(
    id: String = "g1", revision: Int = 3, objective: String = "edited", phase: String = "active",
    activation: String = "disarmed", cap: Long = MAX_GOAL_ROUNDS, rounds: Long = 4
  ) = JSONObject().put("id", id).put("revision", revision).put("objective", objective)
    .put("phase", phase).put("activation", activation).put("maxGoalRounds", cap)
    .put("roundsStarted", rounds).put("createdAt", 1).put("updatedAt", 2)

  private fun projection(goal: JSONObject?): JSONObject {
    val value = if (goal == null) JSONObject.NULL else JSONObject()
      .put("goal", JSONObject(goal.toString()).apply { remove("activation"); remove("roundsStarted") })
      .put("roundsStarted", goal.getLong("roundsStarted")).put("createdAt", 1).put("updatedAt", 2)
    return JSONObject().put("values", JSONObject().put("goal", value))
  }

  private fun emit(api: DshApi, sessionId: String, goal: JSONObject?) {
    val payload = JSONObject().put("sessionId", sessionId)
    if (goal != null) payload.put("goal", goal)
    val method = DshApi::class.java.getDeclaredMethod("handleEmit", JSONObject::class.java, Function1::class.java)
      .apply { isAccessible = true }
    method.invoke(api, JSONObject().put("event", "goal/activation-changed").put("args", JSONArray().put(payload)),
      { _: ServerEvent -> Unit })
  }

  @Test fun editUsesStrictNamedArgsAndExactLongCap() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(view()))
      val api = client(server)
      try {
        val result = api.editGoal("s1", "g1", 2, "  edited  ", MAX_GOAL_ROUNDS)!!
        assertEquals(3, result.revision)
        assertEquals(MAX_GOAL_ROUNDS, result.maxGoalRounds)
        assertEquals(4L, result.roundsStarted)
        val request = server.takeRequest()
        assertEquals("/api/goals/edit", request.path)
        val payload = JSONObject(request.body.readUtf8()).getJSONObject("payload")
        assertEquals(setOf("args"), payload.keys().asSequence().toSet())
        val args = payload.getJSONObject("args")
        assertEquals(setOf("agentId", "ref", "request"), args.keys().asSequence().toSet())
        assertEquals("s1", args.getString("agentId"))
        assertEquals(setOf("id", "revision"), args.getJSONObject("ref").keys().asSequence().toSet())
        assertEquals("g1", args.getJSONObject("ref").getString("id"))
        assertEquals(2, args.getJSONObject("ref").getInt("revision"))
        val edit = args.getJSONObject("request")
        assertEquals(setOf("objective", "maxGoalRounds"), edit.keys().asSequence().toSet())
        assertEquals("edited", edit.getString("objective"))
        assertEquals(MAX_GOAL_ROUNDS, edit.getLong("maxGoalRounds"))
        assertEquals(1, server.requestCount) // no implicit resume/get
      } finally { api.close() }
    }
  }

  @Test fun oneFieldEditsOmitRatherThanSendNull() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(view()))
      server.enqueue(response(view(revision = 4)))
      val api = client(server)
      try {
        api.editGoal("s1", "g1", 2, objective = "edited")
        api.editGoal("s1", "g1", 3, maxRounds = 2147483648L)
        val objective = JSONObject(server.takeRequest().body.readUtf8()).obj("payload").obj("args").obj("request")
        val cap = JSONObject(server.takeRequest().body.readUtf8()).obj("payload").obj("args").obj("request")
        assertEquals(setOf("objective"), objective.keys().asSequence().toSet())
        assertEquals(setOf("maxGoalRounds"), cap.keys().asSequence().toSet())
        assertEquals(2147483648L, cap.getLong("maxGoalRounds"))
      } finally { api.close() }
    }
  }

  @Test fun editPreservesEveryReturnedPhaseAndActivationWithoutResuming() = runBlocking {
    MockWebServer().use { server ->
      val cases = listOf("active" to "armed", "active" to "disarmed", "paused" to "disarmed",
        "blocked" to "disarmed", "complete" to "disarmed")
      cases.forEachIndexed { index, (phase, activation) ->
        server.enqueue(response(view(revision = index + 2, phase = phase, activation = activation)
          .apply { if (phase == "blocked") put("blockedReason", JSONObject().put("message", "needs input")) }))
      }
      val api = client(server)
      try {
        cases.forEachIndexed { index, (phase, activation) ->
          val result = api.editGoal("s1", "g1", index + 1, objective = "edited")!!
          assertEquals(phase, result.phase)
          assertEquals(activation, result.activation)
          if (phase == "blocked") assertEquals("needs input", result.blockedReason)
          assertEquals("/api/goals/edit", server.takeRequest().path)
        }
        assertEquals(cases.size, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun invalidEditsAndCreatesFailBeforeAnyIo() = runBlocking {
    MockWebServer().use { server ->
      val api = client(server)
      suspend fun invalid(block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("invalid goal input must fail locally", rejected)
      }
      try {
        invalid { api.editGoal("s1", "g1", 1) }
        invalid { api.editGoal("s1", "g1", 1, objective = " \n ") }
        invalid { api.editGoal("s1", "g1", 1, maxRounds = 0) }
        invalid { api.editGoal("s1", "g1", 1, maxRounds = -1) }
        invalid { api.editGoal("s1", "g1", 1, maxRounds = MAX_GOAL_ROUNDS + 1) }
        invalid { api.editGoal("s1", " ", 1, objective = "x") }
        invalid { api.editGoal("s1", "g1", 0, objective = "x") }
        invalid { api.editGoal("", "g1", 1, objective = "x") }
        invalid { api.createGoalWithLimit("s1", " ") }
        invalid { api.createGoalWithLimit("s1", "x", MAX_GOAL_ROUNDS + 1) }
        assertEquals(0, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun createSupportsLongCapsAndRefOnlyAckNeverInventsActivation() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(JSONObject().put("ref", JSONObject().put("id", "g-new").put("revision", 1))))
      server.enqueue(response(projection(view(id = "g-new", revision = 1))))
      server.enqueue(response(JSONObject().put("ref", JSONObject().put("id", "g-next").put("revision", 1))))
      val api = client(server)
      try {
        emit(api, "s1", view(id = "g-old", activation = "armed"))
        assertTrue(api.createGoalWithLimit("s1", "  new goal  ", MAX_GOAL_ROUNDS))
        val created = api.composerContext("s1").goal!!
        assertEquals("g-new", created.id)
        assertNull(created.activation)
        val request = JSONObject(server.takeRequest().body.readUtf8()).obj("payload").obj("args").obj("request")
        assertEquals("new goal", request.getString("objective"))
        assertEquals(MAX_GOAL_ROUNDS, request.getLong("maxGoalRounds"))
        assertEquals("/api/session/projections", server.takeRequest().path)
        assertTrue(api.createGoal("s1", "legacy", 7))
        val legacy = JSONObject(server.takeRequest().body.readUtf8()).obj("payload").obj("args").obj("request")
        assertEquals(7L, legacy.getLong("maxGoalRounds"))
      } finally { api.close() }
    }
  }

  @Test fun staleProjectionCannotRollbackMutationButNewerReadAndTombstoneWin() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(view(revision = 3)))
      server.enqueue(response(projection(view(revision = 2, objective = "old"))))
      server.enqueue(response(projection(view(revision = 4, objective = "newer", rounds = 5))))
      server.enqueue(response(projection(null)))
      server.enqueue(response(projection(view(revision = 3))))
      val api = client(server)
      try {
        api.editGoal("s1", "g1", 2, objective = "edited")
        val fenced = api.composerContext("s1").goal!!
        assertEquals("edited", fenced.objective)
        assertEquals(3, fenced.revision)
        assertEquals("disarmed", fenced.activation)
        val newer = api.composerContext("s1").goal!!
        assertEquals(4, newer.revision)
        assertEquals("newer", newer.objective)
        assertEquals(5L, newer.roundsStarted)
        assertNull(newer.activation) // revision 3 activation cannot authorize revision 4
        assertNull(api.composerContext("s1").goal)
        assertNull(api.composerContext("s1").goal!!.activation)
      } finally { api.close() }
    }
  }

  @Test fun activationRequiresExactSessionGoalAndRevisionAndRejectsDelayedEvents() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(view(revision = 3, activation = "disarmed")))
      repeat(4) { server.enqueue(response(projection(view(revision = 3)))) }
      server.enqueue(response(projection(view(id = "other", revision = 3))))
      server.enqueue(response(projection(view(revision = 4))))
      val api = client(server)
      try {
        api.editGoal("s1", "g1", 2, objective = "edited")
        emit(api, "s1", view(revision = 3, activation = "armed"))
        emit(api, "s1", view(revision = 2, activation = "disarmed"))
        assertEquals("armed", api.composerContext("s1").goal!!.activation)
        assertNull(api.composerContext("s2").goal!!.activation)
        emit(api, "s1", view(id = "other", revision = 3, activation = "armed"))
        assertEquals("armed", api.composerContext("s1").goal!!.activation) // latest full view remains exact g1
        emit(api, "s1", null) // clear event retains a tombstone against stale same-goal projections
        assertNull(api.composerContext("s1").goal)
        assertNull(api.composerContext("s1").goal!!.activation)
        emit(api, "s1", view(revision = 3, activation = "armed"))
        assertNull(api.composerContext("s1").goal!!.activation)
      } finally { api.close() }
    }
  }

  @Test fun directReadWinsAtCurrentRevisionAndClearActionDropsCachedView() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(view(revision = 3, activation = "armed")))
      server.enqueue(response(view(revision = 3, activation = "disarmed")))
      server.enqueue(response(view(revision = 4, phase = "complete", activation = "disarmed")))
      server.enqueue(response(JSONObject().put("id", "g1").put("revision", 5)))
      server.enqueue(response(projection(view(revision = 3))))
      val api = client(server)
      try {
        api.editGoal("s1", "g1", 2, objective = "edited")
        assertEquals("disarmed", api.goal("s1")!!.activation)
        assertEquals(4, api.goal("s1")!!.revision)
        assertTrue(api.goalAction("s1", "g1", 4, "clear"))
        assertNull(api.composerContext("s1").goal)
      } finally { api.close() }
    }
  }

  @Test fun readStartedBeforeEditCannotClearItsAcknowledgedViewWithLateNull() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(JSONObject.NULL).setBodyDelay(250, TimeUnit.MILLISECONDS))
      server.enqueue(response(view(revision = 3)))
      server.enqueue(response(projection(view(revision = 2, objective = "old"))))
      val api = client(server)
      try {
        val reading = async(Dispatchers.IO) { api.goal("s1") }
        assertEquals("/api/goals/get", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        api.editGoal("s1", "g1", 2, objective = "edited")
        assertEquals(3, reading.await()!!.revision)
        assertEquals("edited", api.composerContext("s1").goal!!.objective)
      } finally { api.close() }
    }
  }

  @Test fun projectionStartedBeforeClearCannotReviveOldGoalOrActivation() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(view(revision = 3, activation = "armed")))
      server.enqueue(response(projection(view(revision = 3))).setBodyDelay(250, TimeUnit.MILLISECONDS))
      server.enqueue(response(JSONObject().put("id", "g1").put("revision", 4)))
      val api = client(server)
      try {
        api.editGoal("s1", "g1", 2, objective = "edited"); server.takeRequest()
        val reading = async(Dispatchers.IO) { api.composerContext("s1").goal }
        assertEquals("/api/session/projections", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        api.goalAction("s1", "g1", 3, "clear")
        assertNull(reading.await())
      } finally { api.close() }
    }
  }

  @Test fun createAckPreservesMatchingNewGoalEventReceivedWhileAwaitingResponse() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(JSONObject().put("ref", JSONObject().put("id", "created").put("revision", 1)))
        .setBodyDelay(250, TimeUnit.MILLISECONDS))
      server.enqueue(response(projection(view(id = "created", revision = 1))))
      val api = client(server)
      try {
        val creating = async(Dispatchers.IO) { api.createGoalWithLimit("s1", "new goal", 20) }
        assertEquals("/api/goals/create", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        emit(api, "s1", view(id = "created", revision = 1, activation = "armed"))
        assertTrue(creating.await())
        assertEquals("armed", api.composerContext("s1").goal!!.activation)
      } finally { api.close() }
    }
  }

  @Test fun sameRevisionActivationEventFencesAnOlderDirectReadWithoutFullMutationCache() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(view(revision = 3, activation = "armed")).setBodyDelay(250, TimeUnit.MILLISECONDS))
      server.enqueue(response(projection(view(revision = 3))))
      val api = client(server)
      try {
        val reading = async(Dispatchers.IO) { api.goal("s1") }
        assertEquals("/api/goals/get", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        emit(api, "s1", view(revision = 3, activation = "disarmed"))
        assertEquals("disarmed", reading.await()!!.activation)
        assertEquals("disarmed", api.composerContext("s1").goal!!.activation)
      } finally { api.close() }
    }
  }

  @Test fun wrongOrUnchangedEditRefCannotPolluteGoalCache() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(view(id = "unexpected", revision = 8, activation = "armed")))
      server.enqueue(response(view(revision = 2)))
      server.enqueue(response(projection(view(revision = 2))))
      val api = client(server)
      try {
        assertTrue(runCatching { api.editGoal("s1", "g1", 2, "edited") }.isFailure)
        assertTrue(runCatching { api.editGoal("s1", "g1", 2, "edited") }.isFailure)
        val unchanged = api.composerContext("s1").goal!!
        assertEquals("g1", unchanged.id); assertEquals(2, unchanged.revision); assertNull(unchanged.activation)
        assertEquals(3, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun malformedGoalRevisionsAreNotTruncatedIntoMutationReferences() {
    for (revision in listOf<Any>(1.5, "3", -1, 0, 2147483648L)) {
      val malformed = view().put("revision", revision)
      assertNull(malformed.goalView())
      assertNull(projection(malformed).obj("values").goalProjection())
    }
  }

  @Test fun projectionNumbersStayLongAndMissingCountersStayUnknown() {
    val known = projection(view(cap = MAX_GOAL_ROUNDS, rounds = 2147483648L)).obj("values").goalProjection()!!
    assertEquals(MAX_GOAL_ROUNDS, known.maxGoalRounds)
    assertEquals(2147483648L, known.roundsStarted)
    val missing = JSONObject("""{"goal":{"goal":{"id":"g1","revision":1,"objective":"x","phase":"active"}}}""")
      .goalProjection()!!
    assertNull(missing.maxGoalRounds)
    assertNull(missing.roundsStarted)
    assertEquals("reason", RemoteGoal("g1", 1, "x", "blocked", "disarmed", "reason").blockedReason)
    val invalid = projection(view()).obj("values").apply {
      obj("goal").put("roundsStarted", 1.5).obj("goal").put("maxGoalRounds", MAX_GOAL_ROUNDS + 1)
    }.goalProjection()!!
    assertNull(invalid.maxGoalRounds)
    assertNull(invalid.roundsStarted)
    assertFalse(known.complete)
  }
}
