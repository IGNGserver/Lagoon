package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Collections

/** One Host-side automation reminder bound to its original Session. */
data class RemoteSchedule(
  val id: String,
  val kind: String,
  val title: String,
  val prompt: String,
  val scheduledAt: String,
  val detail: String,
  val sessionId: String? = null,
  val status: String? = null,
  val record: ScheduleRecord? = null,
  val lastDelivery: ScheduleDelivery? = null
)

/** Exact CAS record, not a reconstruction from the display detail or catalog metadata. */
data class ScheduleRecord(
  val id: String,
  val kind: String,
  val title: String,
  val prompt: String,
  val scheduledAt: String,
  val afterSeconds: Long? = null,
  val everySeconds: Long? = null,
  val time: String? = null,
  val timeZone: String? = null,
  val weekdays: List<Int> = emptyList(),
  val expression: String? = null
) {
  /** Closed official projection; all strings and counters retain their observed values. */
  internal fun toJson(): JSONObject {
    require(id.isNotBlank() && scheduledAt.isNotBlank()) { "Schedule identity/target is missing" }
    require(title.trim().length in 1..120 && prompt.isNotBlank()) { "Schedule content is invalid" }
    require(kind in setOf("after", "at", "every", "daily", "weekly", "cron")) { "Unknown schedule kind" }
    require((kind == "after") == (afterSeconds != null)) { "Invalid afterSeconds" }
    require((kind == "every") == (everySeconds != null)) { "Invalid everySeconds" }
    require((kind in setOf("daily", "weekly")) == (time != null)) { "Invalid schedule time" }
    require((kind in setOf("daily", "weekly", "cron")) == (timeZone != null)) { "Invalid time zone" }
    require((kind == "cron") == (expression != null)) { "Invalid cron expression" }
    require(kind == "weekly" || weekdays.isEmpty()) { "Unexpected weekdays" }
    afterSeconds?.let { require(it in 1..MAX_SCHEDULE_INTEGER) { "Invalid delay" } }
    everySeconds?.let { require(it in 60..MAX_SCHEDULE_INTEGER) { "Invalid interval" } }
    time?.let { require(it.isNotBlank()) { "Missing time" } }
    timeZone?.let { require(it.isNotBlank()) { "Missing time zone" } }
    expression?.let { require(it.isNotBlank()) { "Missing cron expression" } }
    if (kind == "weekly") validateScheduleWeekdays(weekdays)
    return JSONObject().put("id", id).put("kind", kind).put("title", title).put("prompt", prompt)
      .put("scheduledAt", scheduledAt).apply {
        afterSeconds?.let { put("afterSeconds", it) }
        everySeconds?.let { put("everySeconds", it) }
        time?.let { put("time", it) }
        timeZone?.let { put("timeZone", it) }
        if (kind == "weekly") put("weekdays", JSONArray(weekdays))
        expression?.let { put("expression", it) }
      }
  }

  internal companion object {
    fun parse(value: JSONObject): ScheduleRecord {
      val kind = scheduleString(value, "kind")
      val kindFields = when (kind) {
        "after" -> setOf("afterSeconds")
        "at" -> emptySet()
        "every" -> setOf("everySeconds")
        "daily" -> setOf("time", "timeZone")
        "weekly" -> setOf("time", "timeZone", "weekdays")
        "cron" -> setOf("expression", "timeZone")
        else -> throw IllegalArgumentException("Unknown schedule kind")
      }
      setOf("afterSeconds", "everySeconds", "time", "timeZone", "weekdays", "expression").forEach {
        require(it in kindFields || !value.has(it)) { "Unexpected schedule field $it" }
      }
      val weekdays = if (value.has("weekdays")) {
        val array = value.opt("weekdays") as? JSONArray ?: throw IllegalArgumentException("Invalid weekdays")
        scheduleImmutable((0 until array.length()).map {
          val day = scheduleLong(array.opt(it))
          require(day in 1..7) { "Invalid weekday" }
          day.toInt()
        })
      } else emptyList()
      return ScheduleRecord(
        scheduleString(value, "id"), scheduleString(value, "kind"), scheduleString(value, "title"),
        scheduleString(value, "prompt"), scheduleString(value, "scheduledAt"),
        scheduleOptionalLong(value, "afterSeconds"), scheduleOptionalLong(value, "everySeconds"),
        scheduleOptionalString(value, "time"), scheduleOptionalString(value, "timeZone"), weekdays,
        scheduleOptionalString(value, "expression")
      ).also { it.toJson() }
    }
  }
}

data class ScheduleDelivery(
  val scheduledAt: String,
  val deliveredAt: String,
  val messageId: String,
  val prompt: String? = null
) {
  internal companion object {
    fun parse(value: JSONObject): ScheduleDelivery = ScheduleDelivery(
      scheduleString(value, "scheduledAt"), scheduleString(value, "deliveredAt"),
      scheduleString(value, "messageId"), scheduleOptionalString(value, "prompt")
    ).also { require(it.scheduledAt.isNotBlank() && it.deliveredAt.isNotBlank() && it.messageId.isNotBlank()) }
  }
}

data class ScheduleHistoryPage(
  val id: String,
  val records: List<ScheduleDelivery>,
  val earlierRecordsUnavailable: Boolean,
  val earlierRecordsPruned: Boolean,
  val retentionDays: Long,
  val retentionRecords: Long,
  val nextBefore: String? = null
)

data class ScheduleDeleteResult(val id: String, val deleted: Boolean, val code: String? = null)

data class ScheduleUpdateResult(
  val id: String?,
  val updated: Boolean,
  val record: ScheduleRecord? = null,
  val code: String? = null,
  val message: String? = null
)

/** Only the five public update selectors; delayed one-shots keep timing by omitting change. */
sealed class ScheduleTimingChange {
  data class At(val at: String) : ScheduleTimingChange()
  data class Every(val seconds: Long) : ScheduleTimingChange()
  data class Daily(val time: String, val timeZone: String) : ScheduleTimingChange()
  data class Weekly(val time: String, val timeZone: String, val weekdays: List<Int>) : ScheduleTimingChange()
  data class Cron(val expression: String, val timeZone: String) : ScheduleTimingChange()

  internal fun toJson(): JSONObject = when (this) {
    is At -> { require(at.isNotBlank()) { "Missing target" }; JSONObject().put("kind", "at").put("at", at) }
    is Every -> {
      require(seconds in 60..MAX_SCHEDULE_INTEGER) { "Interval must be a safe integer of at least 60 seconds" }
      JSONObject().put("kind", "every").put("every_seconds", seconds)
    }
    is Daily -> JSONObject().put("kind", "daily").put("daily", wallClock(time, timeZone))
    is Weekly -> {
      validateScheduleWeekdays(weekdays)
      JSONObject().put("kind", "weekly").put("weekly", wallClock(time, timeZone).put("weekdays", JSONArray(weekdays)))
    }
    is Cron -> {
      require(expression.isNotBlank() && timeZone.isNotBlank()) { "Missing cron expression/time zone" }
      JSONObject().put("kind", "cron").put("cron", JSONObject().put("expression", expression).put("time_zone", timeZone))
    }
  }

  private fun wallClock(time: String, zone: String): JSONObject {
    require(time.isNotBlank() && zone.isNotBlank()) { "Missing time/time zone" }
    return JSONObject().put("time", time).put("time_zone", zone)
  }
}

/** Shared display projection for catalog/list and successful CAS acknowledgments. */
internal fun ScheduleRecord.toRemoteSchedule(
  sessionId: String?, status: String?, lastDelivery: ScheduleDelivery? = null
): RemoteSchedule {
  val detail = when (kind) {
    "after" -> "$afterSeconds 秒后"
    "every" -> "$everySeconds 秒"
    "daily" -> "$time $timeZone"
    "weekly" -> "$time $timeZone · ${weekdays.joinToString(",")}"
    "cron" -> "$expression $timeZone"
    "at" -> scheduledAt
    else -> kind
  }
  return RemoteSchedule(id, kind, title, prompt, scheduledAt, detail, sessionId, status, this, lastDelivery)
}

internal const val MAX_SCHEDULE_INTEGER = 9007199254740991L

internal fun scheduleString(value: JSONObject, key: String): String =
  value.opt(key) as? String ?: throw IllegalArgumentException("Missing/invalid $key")

internal fun scheduleOptionalString(value: JSONObject, key: String): String? =
  if (value.has(key)) scheduleString(value, key) else null

internal fun scheduleBoolean(value: JSONObject, key: String): Boolean =
  value.opt(key) as? Boolean ?: throw IllegalArgumentException("Missing/invalid $key")

internal fun scheduleLong(value: Any?): Long {
  require(value is Number) { "Invalid schedule counter" }
  val result = try { BigDecimal(value.toString()).longValueExact() } catch (_: ArithmeticException) {
    throw IllegalArgumentException("Invalid schedule counter")
  } catch (_: NumberFormatException) { throw IllegalArgumentException("Invalid schedule counter") }
  require(result in 0..MAX_SCHEDULE_INTEGER) { "Unsafe schedule counter" }
  return result
}

private fun scheduleOptionalLong(value: JSONObject, key: String): Long? =
  if (value.has(key)) scheduleLong(value.opt(key)) else null

private fun validateScheduleWeekdays(days: List<Int>) {
  require(days.isNotEmpty() && days.all { it in 1..7 } && days.distinct().size == days.size) { "Invalid weekdays" }
}

internal fun <T> scheduleImmutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
