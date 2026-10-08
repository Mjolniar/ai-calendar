package com.example.aicalendar

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One alarm to arm for an event. [kind] identifies it (and picks the notification wording). */
data class PlannedAlarm(val kind: String, val triggerAtMillis: Long)

/**
 * Decides when each reminder of an event is delivered.
 *
 * A reminder is set as "N minutes before the start". Taken literally, anything that ends at midnight
 * (an assignment due 11:59 PM) alerts at midnight three days out, at midnight the night before, and
 * at the deadline itself: asleep or missed every time. So:
 *
 *  - An advance notice (half a day or more ahead) never arrives at night. One that would land
 *    between 9 PM and 8 AM moves to 8 PM that evening, or to 8 AM that morning.
 *  - A deadline at the very end of a day (11:59 PM or midnight) gets its late last-minute alerts as
 *    a single "Tonight" alert at 8 PM that evening.
 *  - Short alerts for anything else (a 6 AM flight, a late shift) stay exactly where they were set.
 *
 * "N days before" means the same clock time N days earlier, also across a daylight-saving change.
 */
object ReminderSchedule {
    /** The one evening alert that replaces the late alerts of an end-of-day deadline. */
    const val KIND_TONIGHT = "tonight"

    val NIGHT_START: LocalTime = LocalTime.of(21, 0)
    val NIGHT_END: LocalTime = LocalTime.of(8, 0)
    val EVENING: LocalTime = LocalTime.of(20, 0)
    val MORNING: LocalTime = LocalTime.of(8, 0)

    /** Lead time from which a reminder counts as an advance notice rather than a last-minute alert. */
    const val ADVANCE_MINUTES = 720

    private const val MINUTES_PER_DAY = 1440
    private val clockFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun kindFor(offsetMinutes: Int): String = "offset_$offsetMinutes"

    /** The alarms for an event, earliest first. Alarms already in the past are included. */
    fun plan(
        startAtMillis: Long,
        offsetsMinutes: List<Int>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PlannedAlarm> {
        val start = Instant.ofEpochMilli(startAtMillis).atZone(zone)
        val planned = LinkedHashMap<String, ZonedDateTime>()
        offsetsMinutes.distinct().forEach { offset ->
            val nominal = nominalTime(start, offset)
            when {
                !isNight(nominal.toLocalTime()) -> planned[kindFor(offset)] = nominal
                offset >= ADVANCE_MINUTES -> planned[kindFor(offset)] = moveToDaytime(nominal)
                endsTheDay(start) -> planned[KIND_TONIGHT] = lastEvening(start)
                else -> planned[kindFor(offset)] = nominal
            }
        }
        return planned
            .map { (kind, time) -> PlannedAlarm(kind, time.toInstant().toEpochMilli()) }
            .sortedBy { it.triggerAtMillis }
    }

    /**
     * One sentence for the reminder's details when [plan] moves an alert, e.g.
     * "Night-time alerts arrive at 8:00 PM." Null when every alert fires exactly as set.
     */
    fun movedNote(
        startAtMillis: Long,
        offsetsMinutes: List<Int>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val start = Instant.ofEpochMilli(startAtMillis).atZone(zone)
        val nominal = offsetsMinutes.distinct().associate { kindFor(it) to nominalTime(start, it).toInstant().toEpochMilli() }
        val movedTo = plan(startAtMillis, offsetsMinutes, zone)
            .filter { nominal[it.kind] != it.triggerAtMillis }
            .map { Instant.ofEpochMilli(it.triggerAtMillis).atZone(zone).toLocalTime() }
            .distinct()
            .sorted()
        if (movedTo.isEmpty()) return null
        return "Night-time alerts arrive at ${movedTo.joinToString(" or ") { it.format(clockFormatter) }}."
    }

    private fun nominalTime(start: ZonedDateTime, offsetMinutes: Int): ZonedDateTime =
        start.minusDays((offsetMinutes / MINUTES_PER_DAY).toLong()).minusMinutes((offsetMinutes % MINUTES_PER_DAY).toLong())

    private fun isNight(time: LocalTime): Boolean = time >= NIGHT_START || time < NIGHT_END

    /**
     * True for the two clock times that mean "by the end of the day" rather than a real late event:
     * 11:59 PM (Canvas sends 23:59:59) and midnight.
     */
    private fun endsTheDay(start: ZonedDateTime): Boolean =
        (start.hour == 23 && start.minute == 59) || isMidnight(start.toLocalTime())

    private fun isMidnight(time: LocalTime): Boolean = time.hour == 0 && time.minute == 0

    /** 8 PM on the evening an end-of-day deadline runs out (for midnight: the evening before). */
    private fun lastEvening(start: ZonedDateTime): ZonedDateTime =
        start.minusMinutes(1).toLocalDate().atTime(EVENING).atZone(start.zone)

    private fun moveToDaytime(time: ZonedDateTime): ZonedDateTime {
        val clock = time.toLocalTime()
        return when {
            clock >= NIGHT_START -> time.toLocalDate().atTime(EVENING).atZone(time.zone)
            // Midnight on the dot closes the day before; anything later belongs to the new morning.
            isMidnight(clock) -> time.toLocalDate().minusDays(1).atTime(EVENING).atZone(time.zone)
            else -> time.toLocalDate().atTime(MORNING).atZone(time.zone)
        }
    }
}
