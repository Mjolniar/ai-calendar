package com.example.aicalendar

import java.time.Instant
import java.time.ZoneId

data class EventBuckets(
    val current: List<PlannerEvent>,
    val archived: List<PlannerEvent>,
)

private val birthdayTitle = Regex("(?<![a-z0-9])(?:birthdays?|b-?days?)(?![a-z0-9])", RegexOption.IGNORE_CASE)

/** A birthday, going by its title: "Megan's birthday", "Dad's 60th birthday dinner". */
fun isBirthdayTitle(title: String): Boolean = birthdayTitle.containsMatchIn(title)

fun PlannerEvent.isBirthday(): Boolean = isBirthdayTitle(title)

/**
 * True when the reminder's time is only a stand-in for "that day", so it is kept on the list until
 * that day is over: a delivery (the time is when it is expected) and a birthday (it lasts all day).
 */
fun PlannerEvent.isListedAllDay(): Boolean = isDelivery() || isBirthday()

/**
 * The last moment an open reminder counts as current: its own time, or for one that is listed all
 * day ([allDay]) the end of that day, so that it is filed under Archived the day after.
 */
fun currentUntilMillis(startAtMillis: Long, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long =
    if (!allDay) {
        startAtMillis
    } else {
        Instant.ofEpochMilli(startAtMillis).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }

fun PlannerEvent.currentUntilMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    currentUntilMillis(startAtMillis, isListedAllDay(), zone)

/** Filed under Archived: completed, archived by hand, or no longer current. */
fun PlannerEvent.isFiledAt(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean = when {
    archived || done -> true
    // Still ahead, whatever kind it is.
    startAtMillis >= nowMillis -> false
    // Past its time: only one that is listed all day is kept, and only until that day ends.
    else -> currentUntilMillis(zone) < nowMillis
}

/**
 * The order of the Archived list. What has passed comes first, most recent first, so the top is
 * always the latest thing to be filed. Reminders filed ahead of their date (done early, or archived
 * by hand) are not history yet: they follow at the end, soonest first, and move up into the list
 * when their day comes. Reminders at the same moment are in title order.
 */
fun archiveOrder(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Comparator<PlannerEvent> {
    val startOfTomorrow = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return compareBy<PlannerEvent> { it.startAtMillis >= startOfTomorrow }
        .thenBy { if (it.startAtMillis >= startOfTomorrow) it.startAtMillis else -it.startAtMillis }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        .thenBy { it.id }
}

fun bucketEvents(events: List<PlannerEvent>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): EventBuckets {
    val (filed, current) = events.partition { it.isFiledAt(nowMillis, zone) }
    return EventBuckets(
        current = current.sortedWith(compareBy<PlannerEvent> { it.startAtMillis }.thenBy { it.id }),
        archived = filed.sortedWith(archiveOrder(nowMillis, zone)),
    )
}
