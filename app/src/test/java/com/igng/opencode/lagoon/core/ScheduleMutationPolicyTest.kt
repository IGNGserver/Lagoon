package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class ScheduleMutationPolicyTest {
  private val profile = ServerProfile("host", "DSH", "https://dsh.example/", kind = ServerKind.DSH)
  private val record = ScheduleRecord("task", "daily", "Task", "Review", "2099-01-01T15:00:00.000Z",
    time = "23:00:00.000", timeZone = "Asia/Shanghai")
  private val target = ScheduleMutationTarget(profile, "owner", record.id, record)
  private fun state() = LagoonState(profiles = listOf(profile), serverId = profile.id, connected = true,
    sessionId = "owner", sessions = listOf(Session("owner", "/repo", "Owner", 1)), schedules = listOf(record.toRemoteSchedule("owner", "active")))

  @Test fun mutationTargetFencesExactOwnerRouteAndCompleteRecord() {
    val state = state()
    assertTrue(target.matches(state))
    assertTrue(target.matches(state.copy(profiles = listOf(profile.copy(name = "Renamed")))))
    assertFalse(target.matches(state.copy(sessionId = "other")))
    assertFalse(target.matches(state.copy(profiles = listOf(profile.copy(url = "https://different.example/")))))
    assertFalse(target.matches(state.copy(schedules = listOf(record.copy(scheduledAt = "2099-01-02T15:00:00.000Z").toRemoteSchedule("owner", "active")))))
    assertFalse(target.matches(state.copy(schedules = listOf(record.toRemoteSchedule("different-owner", "active")))))
  }

  @Test fun onlyActiveRecordsMayBeEditedButInactiveIdentityRemainsManageable() {
    assertTrue(target.canEdit(state()))
    val inactive = state().copy(schedules = listOf(record.toRemoteSchedule("owner", "inactive")))
    assertTrue(target.matches(inactive)); assertFalse(target.canEdit(inactive))
    assertTrue(target.routeMatches(inactive))
  }

  @Test fun nativeDshManagementIsCarrierIndependentAndParentGated() {
    val normal = state()
    assertTrue(normal.canManageSchedules())
    assertTrue(normal.copy(profiles = listOf(profile.copy(sshHostId = "ssh", url = "ssh://user@host:22"))).canManageSchedules())
    assertTrue(normal.copy(profiles = listOf(profile.copy(kind = ServerKind.DSH_REMOTE, remoteHostId = "remote"))).canManageSchedules())
    assertFalse(normal.copy(connected = false).canManageSchedules())
    assertFalse(normal.copy(cached = true).canManageSchedules())
    assertFalse(normal.copy(profiles = listOf(profile.copy(parentEnabled = false))).canManageSchedules())
    assertFalse(normal.copy(profiles = listOf(profile.copy(kind = ServerKind.OPENCODE))).canManageSchedules())
  }

  @Test fun catalogMetadataNeverEntersTheClosedCasRecord() {
    val catalog = record.toRemoteSchedule("owner", "inactive", ScheduleDelivery(record.scheduledAt, record.scheduledAt, "message"))
    val expected = catalog.record!!.toJson()
    assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt", "time", "timeZone"), expected.keys().asSequence().toSet())
    assertEquals("Asia/Shanghai", expected.getString("timeZone"))
    assertFalse(expected.has("status")); assertFalse(expected.has("sessionId")); assertFalse(expected.has("lastDelivery"))
  }
}
