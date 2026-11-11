package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Official rc.5 schedule catalog/list/history/delete/CAS-update, with no invented write APIs. */
class ScheduleApiTest {
  private fun client(server: MockWebServer) = DshApi(
    ServerProfile("schedule-test", "DSH", server.url("/").toString().trimEnd('/'),
      allowCleartext = true, kind = ServerKind.DSH),
    ServerCredentials("dsh", cookie = "dsh-auth-schedule=test")
  )

  private fun response(value: Any) = MockResponse().setBody(JSONObject()
    .put("type", "server-response").put("rpcId", "schedule-test")
    .put("result", JSONObject().put("ok", true).put("value", value)).toString())

  private fun record(kind: String = "at", id: String = "task") = JSONObject()
    .put("id", id).put("kind", kind).put("title", "Stored title").put("prompt", "Stored prompt")
    .put("scheduledAt", "2030-01-02T03:04:05.006Z").apply {
      when (kind) {
        "after" -> put("afterSeconds", 2147483648L)
        "every" -> put("everySeconds", MAX_SCHEDULE_INTEGER)
        "daily", "weekly" -> { put("time", "22:00:00.123"); put("timeZone", "Asia/Calcutta") }
        "cron" -> { put("expression", "0 22 * * 1-5"); put("timeZone", "Etc/UTC") }
      }
      if (kind == "weekly") put("weekdays", JSONArray(listOf(1, 3, 7)))
    }

  private fun receipt(id: String = "message") = JSONObject()
    .put("scheduledAt", "2030-01-02T03:04:05.006Z").put("deliveredAt", "2030-01-02T03:04:06.007Z")
    .put("messageId", id)

  private fun history() = JSONObject().put("id", "task")
    .put("records", JSONArray().put(receipt("new").put("prompt", "actual delivered prompt")).put(receipt("old")))
    .put("earlierRecordsUnavailable", true).put("earlierRecordsPruned", false)
    .put("retention", JSONObject().put("days", 2147483648L).put("records", MAX_SCHEDULE_INTEGER))
    .put("nextBefore", "old")

  private fun args(server: MockWebServer, method: String): JSONObject {
    val request = server.takeRequest()
    assertEquals("/api/$method", request.path)
    val envelope = JSONObject(request.body.readUtf8())
    assertEquals(method, envelope.getString("method"))
    val payload = envelope.getJSONObject("payload")
    assertEquals(setOf("args"), keys(payload))
    return payload.getJSONObject("args")
  }

  private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
  private fun assertJsonEquals(expected: Any?, actual: Any?) {
    when {
      expected is JSONObject && actual is JSONObject -> {
        assertEquals(keys(expected), keys(actual))
        keys(expected).forEach { assertJsonEquals(expected.opt(it), actual.opt(it)) }
      }
      expected is JSONArray && actual is JSONArray -> {
        assertEquals(expected.length(), actual.length())
        repeat(expected.length()) { assertJsonEquals(expected.opt(it), actual.opt(it)) }
      }
      expected is Number && actual is Number -> assertEquals(expected.toDouble(), actual.toDouble(), 0.0)
      else -> assertEquals(expected, actual)
    }
  }
  private suspend fun malformed(block: suspend () -> Unit) {
    try { block(); fail("malformed response must fail") } catch (error: ApiException) {
      assertEquals(502, error.status)
      assertEquals("schedule/response-invalid", error.code)
    }
  }

  @Test fun catalogIncludesAllSixKindsExactOwnersStatusAndClosedRecords() = runBlocking {
    MockWebServer().use { server ->
      val kinds = listOf("after", "at", "every", "daily", "weekly", "cron")
      server.enqueue(response(JSONArray().apply { kinds.forEachIndexed { index, kind ->
        put(record(kind, kind).put("sessionId", "owner-$index").put("status", if (index % 2 == 0) "active" else "inactive")
          .put("lastDelivery", receipt()))
      } }))
      val api = client(server)
      try {
        val rows = api.scheduleCatalog()!!
        assertEquals(kinds, rows.map { it.kind })
        rows.forEachIndexed { index, row ->
          assertEquals("owner-$index", row.sessionId)
          assertEquals(if (index % 2 == 0) "active" else "inactive", row.status)
          assertEquals("message", row.lastDelivery!!.messageId)
          assertEquals(keys(record(row.kind, row.id)), keys(row.record!!.toJson()))
          assertFalse(row.record!!.toJson().has("sessionId"))
        }
        assertEquals(2147483648L, rows[0].record!!.afterSeconds)
        assertEquals(MAX_SCHEDULE_INTEGER, rows[2].record!!.everySeconds)
        assertEquals("Asia/Calcutta", rows[3].record!!.timeZone)
        assertEquals(emptySet<String>(), keys(args(server, "schedule/catalog")))
        try { (rows[4].record!!.weekdays as MutableList<Int>).add(2); fail("mutable parsed weekdays") }
        catch (_: UnsupportedOperationException) { }
      } finally { api.close() }
    }
  }

  @Test fun legacyListIsActiveOnlyWithSuppliedOwnerAndLongDisplay() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(JSONArray().put(record("after")).put(record("every", "interval"))))
      val api = client(server)
      try {
        val rows = api.schedules("original-owner")
        assertTrue(rows.all { it.sessionId == "original-owner" && it.status == "active" && it.record != null })
        assertEquals("2147483648 秒后", rows.first().detail)
        assertEquals("$MAX_SCHEDULE_INTEGER 秒", rows.last().detail)
        val request = args(server, "schedule/list").getJSONObject("request")
        assertEquals(setOf("sessionId"), keys(request))
        assertEquals("original-owner", request.getString("sessionId"))
      } finally { api.close() }
    }
  }

  @Test fun catalogAndListRejectMalformedRatherThanPartialEmptyOrCoercedRecords() = runBlocking {
    MockWebServer().use { server ->
      val broken = listOf(
        JSONObject(), JSONArray().put(record()).put("bad row"),
        JSONArray().put(record("unknown")), JSONArray().put(record("every").put("everySeconds", "60")),
        JSONArray().put(record("every").put("everySeconds", 60.5)),
        JSONArray().put(record("weekly").put("weekdays", JSONArray(listOf(1, 1)))),
        JSONArray().put(record("daily").apply { remove("timeZone") })
      )
      broken.forEach { server.enqueue(response(it)) }
      server.enqueue(response(JSONArray().put(record().put("sessionId", "owner").put("status", "enabled"))))
      server.enqueue(MockResponse().setBody(JSONObject().put("result", JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", "gateway/method-unavailable").put("message", "unavailable"))).toString()))
      val api = client(server)
      try {
        broken.forEach { malformed { api.schedules("owner") } }
        malformed { api.scheduleCatalog() }
        assertNull(api.scheduleCatalog()) // only explicit capability absence permits legacy fallback
        assertEquals(broken.size + 2, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun historyPreservesExclusiveCursorReceiptsFlagsAndLongRetention() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(history()))
      server.enqueue(response(history().put("records", JSONArray()).apply { remove("nextBefore") }))
      val api = client(server)
      try {
        val page = api.scheduleHistory("original-owner", "task", "newest", 7)
        assertEquals(listOf("new", "old"), page.records.map { it.messageId })
        assertEquals("actual delivered prompt", page.records.first().prompt)
        assertNull(page.records.last().prompt)
        assertTrue(page.earlierRecordsUnavailable)
        assertFalse(page.earlierRecordsPruned)
        assertEquals(2147483648L, page.retentionDays)
        assertEquals(MAX_SCHEDULE_INTEGER, page.retentionRecords)
        assertEquals("old", page.nextBefore)
        val request = args(server, "schedule/history").getJSONObject("request")
        assertEquals(setOf("sessionId", "id", "before", "limit"), keys(request))
        assertEquals("original-owner", request.getString("sessionId"))
        assertEquals("newest", request.getString("before"))
        assertEquals(7, request.getInt("limit"))
        assertTrue(api.scheduleHistory("original-owner", "task").records.isEmpty())
        assertFalse(args(server, "schedule/history").getJSONObject("request").has("before"))
      } finally { api.close() }
    }
  }

  @Test fun historyErrorsRemainTypedAndMalformedHistoryNeverBecomesEmptyPage() = runBlocking {
    MockWebServer().use { server ->
      listOf("schedule_not_found", "delivery_cursor_not_found").forEach {
        server.enqueue(response(JSONObject().put("id", "task").put("code", it)))
      }
      server.enqueue(response(history().put("id", "wrong")))
      server.enqueue(response(history().put("earlierRecordsPruned", "false")))
      val api = client(server)
      try {
        listOf("schedule_not_found", "delivery_cursor_not_found").forEach { code ->
          try { api.scheduleHistory("owner", "task"); fail("expected history error") }
          catch (error: ApiException) { assertEquals(code, error.code); assertTrue(error.message!!.isNotBlank()) }
        }
        malformed { api.scheduleHistory("owner", "task") }
        malformed { api.scheduleHistory("owner", "task") }
        assertEquals(4, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun deleteUsesOriginalOwnerAndReturnsNotFoundWithoutRetry() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(JSONObject().put("id", "task").put("deleted", true)))
      server.enqueue(response(JSONObject().put("id", "task").put("deleted", false).put("code", "schedule_not_found")))
      val api = client(server)
      try {
        assertTrue(api.deleteSchedule("original-owner", "task").deleted)
        assertEquals("schedule_not_found", api.deleteSchedule("original-owner", "task").code)
        repeat(2) {
          val request = args(server, "schedule/delete").getJSONObject("request")
          assertEquals(setOf("sessionId", "id"), keys(request))
          assertEquals("original-owner", request.getString("sessionId"))
          assertEquals("task", request.getString("id"))
        }
        server.enqueue(MockResponse().setResponseCode(401))
        try { api.deleteSchedule("original-owner", "task"); fail("expected authentication failure") }
        catch (error: ApiException) { assertEquals(401, error.status) }
        assertEquals(3, server.requestCount) // never replay a mutation after 401
      } finally { api.close() }
    }
  }

  @Test fun deleteAndUpdateRequireTaggedResultsAndMatchingIds() = runBlocking {
    MockWebServer().use { server ->
      listOf(JSONObject(), JSONObject().put("id", "wrong").put("deleted", true),
        JSONObject().put("id", "task").put("deleted", false)).forEach { server.enqueue(response(it)) }
      listOf(JSONObject(), JSONObject().put("id", "task").put("updated", true),
        JSONObject().put("id", "wrong").put("updated", true).put("record", record()),
        JSONObject().put("id", "task").put("updated", true).put("record", record(id = "wrong")))
        .forEach { server.enqueue(response(it)) }
      val api = client(server)
      try {
        repeat(3) { malformed { api.deleteSchedule("owner", "task") } }
        repeat(4) { malformed { api.updateSchedule("owner", "task", ScheduleRecord.parse(record()), title = "x") } }
        assertEquals(7, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun contentOnlyUpdateSendsExactClosedExpectedAndOmitsTiming() = runBlocking {
    MockWebServer().use { server ->
      val observed = record("weekly").put("sessionId", "owner").put("status", "active").put("lastDelivery", receipt())
      server.enqueue(response(JSONObject().put("id", "task").put("updated", true).put("record", record("weekly"))))
      val api = client(server)
      try {
        val expected = ScheduleRecord.parse(observed)
        val result = api.updateSchedule("original-owner", "task", expected, "  New title  ", "  New prompt  ")
        assertTrue(result.updated)
        assertEquals("Asia/Calcutta", result.record!!.timeZone)
        val request = args(server, "schedule/update").getJSONObject("request")
        assertEquals(setOf("id", "sessionId", "expected", "title", "prompt"), keys(request))
        assertEquals("original-owner", request.getString("sessionId"))
        assertEquals("New title", request.getString("title"))
        assertEquals("New prompt", request.getString("prompt"))
        assertJsonEquals(record("weekly"), request.getJSONObject("expected"))
        assertEquals("2030-01-02T03:04:05.006Z", request.getJSONObject("expected").getString("scheduledAt"))
        assertEquals(1, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun updateAcceptsNoOpWithRecordAndReturnsConflictWithoutRetry() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(response(JSONObject().put("id", "task").put("updated", false).put("record", record("after"))))
      listOf("schedule_conflict", "schedule_ended", "schedule_not_found").forEach {
        server.enqueue(response(JSONObject().put("id", "task").put("updated", false).put("code", it)))
      }
      val api = client(server)
      try {
        val expected = ScheduleRecord.parse(record("after"))
        val noop = api.updateSchedule("owner", "task", expected)
        assertFalse(noop.updated)
        assertEquals(expected, noop.record)
        assertFalse(args(server, "schedule/update").getJSONObject("request").has("change"))
        listOf("schedule_conflict", "schedule_ended", "schedule_not_found").forEach { code ->
          val result = api.updateSchedule("owner", "task", expected, title = "changed")
          assertFalse(result.updated); assertEquals(code, result.code); assertNull(result.record)
        }
        assertEquals(4, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun everyPublicTimingChangeUsesExactSelectorAndZoneKeys() = runBlocking {
    MockWebServer().use { server ->
      val cases = listOf(
        ScheduleTimingChange.At("2031-02-03T04:05:06+08:00") to """{"kind":"at","at":"2031-02-03T04:05:06+08:00"}""",
        ScheduleTimingChange.Every(MAX_SCHEDULE_INTEGER) to """{"kind":"every","every_seconds":9007199254740991}""",
        ScheduleTimingChange.Daily("22:00:00.123", "Asia/Calcutta") to """{"kind":"daily","daily":{"time":"22:00:00.123","time_zone":"Asia/Calcutta"}}""",
        ScheduleTimingChange.Weekly("22:00:00", "UTC", listOf(7, 1)) to """{"kind":"weekly","weekly":{"time":"22:00:00","time_zone":"UTC","weekdays":[7,1]}}""",
        ScheduleTimingChange.Cron("0 22 * * 1-5", "Etc/UTC") to """{"kind":"cron","cron":{"expression":"0 22 * * 1-5","time_zone":"Etc/UTC"}}"""
      )
      repeat(cases.size) { server.enqueue(response(JSONObject().put("id", "task").put("updated", true).put("record", record()))) }
      val api = client(server)
      try {
        cases.forEach { (change, json) ->
          api.updateSchedule("owner", "task", ScheduleRecord.parse(record()), change = change)
          val request = args(server, "schedule/update").getJSONObject("request")
          assertEquals(setOf("sessionId", "id", "expected", "change"), keys(request))
          assertJsonEquals(JSONObject(json), request.getJSONObject("change"))
        }
        assertEquals(5, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun updateReturnsEveryOfficialServerErrorUnionWithoutThrowing() = runBlocking {
    MockWebServer().use { server ->
      val codes = listOf("invalid_prompt", "invalid_selector", "invalid_rule", "invalid_time_zone", "not_future",
        "time_out_of_range", "frequency_too_high", "subagent_session", "internal_error")
      codes.forEach { server.enqueue(response(JSONObject().put("code", it).put("message", "server explains $it"))) }
      val api = client(server)
      try {
        codes.forEach { code ->
          val result = api.updateSchedule("owner", "task", ScheduleRecord.parse(record()), title = "x")
          assertFalse(result.updated); assertNull(result.id); assertEquals(code, result.code)
          assertEquals("server explains $code", result.message)
        }
        assertEquals(codes.size, server.requestCount)
      } finally { api.close() }
    }
  }

  @Test fun invalidInputsAndExpectedKindsFailBeforeIo() = runBlocking {
    MockWebServer().use { server ->
      val api = client(server)
      val expected = ScheduleRecord.parse(record())
      suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("invalid input accepted") } catch (_: IllegalArgumentException) { }
      }
      try {
        invalid { api.updateSchedule("owner", "wrong", expected, title = "x") }
        invalid { api.updateSchedule("owner", "task", expected, title = "   ") }
        invalid { api.updateSchedule("owner", "task", expected, title = "x".repeat(121)) }
        invalid { api.updateSchedule("owner", "task", expected, prompt = "\n ") }
        invalid { api.updateSchedule("owner", "task", expected.copy(kind = "unknown"), title = "x") }
        invalid { api.updateSchedule("owner", "task", expected.copy(kind = "every"), title = "x") }
        invalid { api.updateSchedule("owner", "task", expected, change = ScheduleTimingChange.Every(59)) }
        invalid { api.updateSchedule("owner", "task", expected, change = ScheduleTimingChange.Every(MAX_SCHEDULE_INTEGER + 1)) }
        invalid { api.updateSchedule("owner", "task", expected, change = ScheduleTimingChange.Weekly("22:00:00", "UTC", listOf(1, 1))) }
        invalid { api.updateSchedule("owner", "task", expected, change = ScheduleTimingChange.Weekly("22:00:00", "UTC", listOf(0))) }
        invalid { api.updateSchedule("owner", "task", expected, change = ScheduleTimingChange.Daily("", "UTC")) }
        invalid { api.updateSchedule("owner", "task", expected, change = ScheduleTimingChange.Cron("* * * * *", "")) }
        invalid { api.scheduleHistory("owner", "task", limit = 101) }
        invalid { api.deleteSchedule("", "task") }
        assertEquals(0, server.requestCount)
      } finally { api.close() }
    }
  }
}
