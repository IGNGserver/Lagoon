package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.*
import org.junit.Assert.*
import org.junit.Test

class ScheduleEditorPolicyTest {
  private val profile = ServerProfile("server", "Host", "https://host.example", kind = ServerKind.DSH)
  private val record = ScheduleRecord("schedule", "daily", "Original", "Original\nfull prompt", "2026-11-12T09:00:00+08:00", time = "09:00:00", timeZone = "Asia/Shanghai")
  private val target = ScheduleMutationTarget(profile, "session", record.id, record)
  private val draft = ScheduleTimingDraft.from(record)
  private fun state() = LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
    sessions = listOf(Session("session", "/repo", "Session", 1)), sessionId = "session",
    schedules = listOf(RemoteSchedule(record.id, record.kind, record.title, record.prompt, record.scheduledAt, "daily",
      sessionId = "session", status = "active", record = record)))
  private fun canSave(state: LagoonState = state(), title: String = "Retained draft", prompt: String = "Full\nretained prompt") =
    scheduleEditorCanSave(state, target, title, prompt, false, draft)

  @Test fun contentOnlyEditsOmitTimingEvenForUnknownOrInvalidOriginalTiming() {
    assertNull(parseScheduleTiming(false, ScheduleTimingDraft("unknown", timeZone = "")).getOrThrow())
    assertNull(parseScheduleTiming(false, ScheduleTimingDraft("after", at = "bad")).getOrThrow())
    val after = record.copy(kind = "after", time = null, timeZone = null, afterSeconds = 60)
    assertEquals("at", ScheduleTimingDraft.from(after).kind)
    assertEquals(after.scheduledAt, ScheduleTimingDraft.from(after).at)
  }

  @Test fun everyRequiresAtLeastSixtySafeIntegerSecondsWithoutClamping() {
    for (invalid in listOf("", "0", "1", "59", "-60", "+60", "1.5", "1e3", "60 0", "６０", "9007199254740992", "9223372036854775808")) {
      assertTrue(invalid, parseScheduleTiming(true, ScheduleTimingDraft("every", seconds = invalid)).isFailure)
    }
    for (valid in listOf(60L, 2147483648L, MAX_SCHEDULE_SECONDS)) {
      assertEquals(ScheduleTimingChange.Every(valid), parseScheduleTiming(true, ScheduleTimingDraft("every", seconds = valid.toString())).getOrThrow())
    }
  }

  @Test fun absoluteTimeNeedsExplicitOffsetAndPreservesOriginalTextWithoutZoneConversion() {
    val raw = "2026-12-03T09:10:11.123+05:45"
    assertEquals(ScheduleTimingChange.At(raw), parseScheduleTiming(true, ScheduleTimingDraft("at", at = raw)).getOrThrow())
    assertEquals(ScheduleTimingChange.At(raw), parseScheduleTiming(true, ScheduleTimingDraft("after", at = raw)).getOrThrow())
    for (invalid in listOf("2026-12-03T09:10:11", "2026-12-03 09:10:11", "2026-02-30T09:10:11Z")) {
      assertTrue(invalid, parseScheduleTiming(true, ScheduleTimingDraft("at", at = invalid)).isFailure)
    }
  }

  @Test fun wallClockZoneIsExplicitIanaAndNeverUsesOrConvertsDeviceZone() {
    val raw = ScheduleTimingDraft("daily", time = "09:10:11.123", timeZone = "America/St_Johns")
    assertEquals(ScheduleTimingChange.Daily(raw.time, raw.timeZone), parseScheduleTiming(true, raw).getOrThrow())
    for (zone in listOf("", "local", "+08:00", " Asia/Shanghai", "Not/AZone")) {
      assertTrue(zone, parseScheduleTiming(true, raw.copy(timeZone = zone)).isFailure)
    }
    assertTrue(parseScheduleTiming(true, raw.copy(time = "24:00")).isFailure)
    assertEquals(ScheduleTimingChange.Weekly("09:10", "Asia/Shanghai", listOf(7, 1)),
      parseScheduleTiming(true, ScheduleTimingDraft("weekly", time = "09:10", timeZone = "Asia/Shanghai", weekdays = "7,1")).getOrThrow())
    assertTrue(parseScheduleTiming(true, ScheduleTimingDraft("weekly", time = "09:10", timeZone = "Asia/Shanghai", weekdays = "1,1")).isFailure)
    val expression = "*/15 9-17 * * 1-5"
    assertEquals(ScheduleTimingChange.Cron(expression, "Etc/UTC"),
      parseScheduleTiming(true, ScheduleTimingDraft("cron", timeZone = "Etc/UTC", expression = expression)).getOrThrow())
  }

  @Test fun exactRecordOwnerAndRouteAreRequiredAndStaleExpectedNeverRebasesDraft() {
    assertTrue(canSave())
    val initial = state()
    val stale = listOf(initial.copy(sessionId = "different"), initial.copy(schedules = emptyList()),
      initial.copy(profiles = listOf(profile.copy(url = "https://changed.example"))),
      initial.copy(schedules = initial.schedules.map { it.copy(sessionId = "another-owner") }),
      initial.copy(schedules = initial.schedules.map { it.copy(record = record.copy(prompt = "changed on server")) }),
      initial.copy(schedules = initial.schedules.map { it.copy(record = record.copy(timeZone = "Etc/UTC")) }))
    stale.forEach { assertFalse(canSave(it)) }
    assertEquals("Original\nfull prompt", target.record.prompt)
    assertEquals("Asia/Shanghai", target.record.timeZone)
    assertFalse(scheduleEditorCanSave(initial, null, "Draft", "Prompt", false, draft))
  }

  @Test fun metadataRebasePreservesDirtyFieldsAndRefreshesUntouchedFields() {
    val latest = record.copy(title = "Server title", prompt = "Updated externally", timeZone = "America/New_York")
    val originalTiming = ScheduleTimingDraft.from(record)
    val titleOnly = rebaseScheduleDraft(record, latest, "My title", record.prompt, originalTiming)
    assertEquals("My title", titleOnly.title)
    assertEquals(latest.prompt, titleOnly.prompt)
    assertEquals("America/New_York", titleOnly.timing.timeZone)
    val dirtyTiming = originalTiming.copy(time = "10:11:12")
    val allDirty = rebaseScheduleDraft(record, latest, "My title", "My prompt", dirtyTiming)
    assertEquals("My prompt", allDirty.prompt)
    assertEquals("10:11:12", allDirty.timing.time)
    assertEquals("America/New_York", allDirty.timing.timeZone) // Untouched zone follows the new server record.
    val second = rebaseScheduleDraft(latest, latest.copy(prompt = "Newer"), titleOnly.title, titleOnly.prompt, titleOnly.timing)
    assertEquals("Newer", second.prompt); assertEquals("My title", second.title)
  }

  @Test fun partialTimingRebaseDoesNotOverwriteUntouchedExternalZoneOrWeekdays() {
    val previous = record.copy(kind = "weekly", weekdays = listOf(1, 3))
    val latest = previous.copy(time = "12:00:00", timeZone = "America/St_Johns", weekdays = listOf(2, 4))
    val edited = ScheduleTimingDraft.from(previous).copy(time = "10:00:00")
    val rebased = rebaseScheduleDraft(previous, latest, previous.title, previous.prompt, edited)
    assertEquals("10:00:00", rebased.timing.time)
    assertEquals("America/St_Johns", rebased.timing.timeZone)
    assertEquals("2,4", rebased.timing.weekdays)
    assertEquals(ScheduleTimingChange.Weekly("10:00:00", "America/St_Johns", listOf(2, 4)),
      parseScheduleTiming(true, rebased.timing).getOrThrow())
    val changedZone = edited.copy(timeZone = "Etc/UTC")
    assertEquals("Etc/UTC", rebaseScheduleDraft(previous, latest, previous.title, previous.prompt, changedZone).timing.timeZone)
  }

  @Test fun inactiveBusyOfflineCachedAndInvalidContentDisableEdit() {
    val initial = state()
    assertFalse(canSave(initial.copy(schedules = initial.schedules.map { it.copy(status = "inactive") })))
    assertFalse(canSave(initial.copy(pendingOperations = setOf(operationKey(profile.id, "session", null, "schedule-mutation")))))
    assertFalse(canSave(initial.copy(schedulesLoading = true)))
    assertFalse(canSave(initial.copy(connected = false)))
    assertFalse(canSave(initial.copy(cached = true)))
    assertFalse(canSave(initial.copy(profiles = listOf(profile.copy(parentEnabled = false)))))
    assertFalse(canSave(title = " ", prompt = "prompt"))
    assertFalse(canSave(title = "x".repeat(121)))
    assertTrue(canSave(title = " x "))
    assertFalse(canSave(prompt = "\n "))
  }
}
