package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ScheduleMutationTarget
import com.igng.opencode.lagoon.core.ScheduleRecord
import com.igng.opencode.lagoon.core.ScheduleTimingChange
import com.igng.opencode.lagoon.core.canManageSchedules
import java.time.OffsetDateTime
import java.time.ZoneId

internal const val MAX_SCHEDULE_SECONDS = 9007199254740991L

/** Raw server strings stay raw: opening or editing content must never reinterpret a device zone. */
internal data class ScheduleTimingDraft(
  val kind: String,
  val at: String = "",
  val seconds: String = "",
  val time: String = "",
  val timeZone: String = "",
  val weekdays: String = "",
  val expression: String = ""
) {
  companion object {
    fun from(record: ScheduleRecord) = ScheduleTimingDraft(
      kind = if (record.kind == "after") "at" else record.kind,
      at = record.scheduledAt,
      seconds = record.everySeconds?.toString().orEmpty(),
      time = record.time.orEmpty(), timeZone = record.timeZone.orEmpty(),
      weekdays = record.weekdays.joinToString(","), expression = record.expression.orEmpty()
    )
  }
}

/** null means omit timing entirely, even when the server's original timing is unfamiliar. */
internal fun parseScheduleTiming(changeTiming: Boolean, draft: ScheduleTimingDraft): Result<ScheduleTimingChange?> = runCatching {
  if (!changeTiming) return@runCatching null
  fun zone(): String {
    require(draft.timeZone in ZoneId.getAvailableZoneIds()) { "请填写明确的 IANA 时区，如 Asia/Shanghai；不会使用手机时区" }
    return draft.timeZone
  }
  fun time(): String {
    require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\\.[0-9]{1,3})?)?").matches(draft.time)) {
      "时间须为 HH:mm 或 HH:mm:ss（24 小时制）"
    }
    return draft.time
  }
  when (draft.kind) {
    "at", "after" -> {
      require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})").matches(draft.at)) {
        "请填写带 Z 或 UTC 偏移的 RFC 3339 时间，不会推测时区"
      }
      OffsetDateTime.parse(draft.at)
      ScheduleTimingChange.At(draft.at)
    }
    "every" -> {
      require(draft.seconds.isNotEmpty() && draft.seconds.all { it in '0'..'9' }) { "间隔须为正整数秒" }
      val seconds = draft.seconds.toLongOrNull()
      require(seconds != null && seconds in 60..MAX_SCHEDULE_SECONDS) { "间隔须在 60–$MAX_SCHEDULE_SECONDS 秒之间" }
      ScheduleTimingChange.Every(seconds)
    }
    "daily" -> ScheduleTimingChange.Daily(time(), zone())
    "weekly" -> {
      val days = draft.weekdays.split(Regex("[,，\\s]+")).filter(String::isNotEmpty).map {
        it.toIntOrNull()?.takeIf { day -> day in 1..7 } ?: error("星期须为 1–7，以逗号分隔，周一为 1")
      }
      require(days.isNotEmpty() && days.distinct().size == days.size) { "请选择至少一天，且星期不能重复" }
      ScheduleTimingChange.Weekly(time(), zone(), days)
    }
    "cron" -> {
      require(draft.expression.trim().split(Regex("\\s+")).size == 5) { "Cron 须包含五个字段；具体表达式由服务器校验" }
      ScheduleTimingChange.Cron(draft.expression, zone())
    }
    else -> error("当前定时类型不支持修改；取消勾选可保留原定时")
  }
}

internal data class RebasedScheduleDraft(val title: String, val prompt: String, val timing: ScheduleTimingDraft)

/** Preserve only actual edits; untouched stale fields must not become writes after an explicit CAS refresh. */
internal fun rebaseScheduleDraft(previous: ScheduleRecord, latest: ScheduleRecord, title: String, prompt: String,
  timing: ScheduleTimingDraft): RebasedScheduleDraft = RebasedScheduleDraft(
  title = if (title == previous.title) latest.title else title,
  prompt = if (prompt == previous.prompt) latest.prompt else prompt,
  timing = rebaseScheduleTimingDraft(ScheduleTimingDraft.from(previous), ScheduleTimingDraft.from(latest), timing)
)

private fun rebaseScheduleTimingDraft(previous: ScheduleTimingDraft, latest: ScheduleTimingDraft,
  edited: ScheduleTimingDraft): ScheduleTimingDraft {
  // A type change is a different rule. Keep an explicitly edited rule together for user review;
  // within the same kind, preserve only fields the reader actually changed.
  if (previous.kind != latest.kind) return if (edited == previous) latest else edited
  return edited.copy(
    at = if (edited.at == previous.at) latest.at else edited.at,
    seconds = if (edited.seconds == previous.seconds) latest.seconds else edited.seconds,
    time = if (edited.time == previous.time) latest.time else edited.time,
    timeZone = if (edited.timeZone == previous.timeZone) latest.timeZone else edited.timeZone,
    weekdays = if (edited.weekdays == previous.weekdays) latest.weekdays else edited.weekdays,
    expression = if (edited.expression == previous.expression) latest.expression else edited.expression
  )
}

internal fun scheduleEditorCanSave(state: LagoonState, expected: ScheduleMutationTarget?, title: String, prompt: String,
  changeTiming: Boolean, draft: ScheduleTimingDraft): Boolean = state.canManageSchedules() &&
  !state.pending("schedule-mutation") && !state.schedulesLoading && expected?.matches(state) == true &&
  state.schedules.any { it.id == expected.scheduleId && it.sessionId == expected.sessionId && it.status == "active" } &&
  title.trim().length in 1..120 && prompt.isNotBlank() && parseScheduleTiming(changeTiming, draft).isSuccess
