package com.example.aicalendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToLong

// Display logic for the reminder list. Pure JVM code (no Android or Compose) so it is unit-tested.

/** A date bucket shown as a group header in the reminder list. */
data class DueGroup(val key: String, val label: String)

sealed interface PlannerListEntry {
    val key: String

    data class Header(val group: DueGroup, val count: Int) : PlannerListEntry {
        override val key: String get() = "h:${group.key}"
    }

    data class Item(val event: PlannerEvent) : PlannerListEntry {
        override val key: String get() = "e:${event.id}"
    }
}

private val monthOnlyFormatter = DateTimeFormatter.ofPattern("MMMM")
private val monthYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val shortDateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
private val shortDateYearFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val fullDateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a")
private val eventDateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a")

/** Group for an upcoming reminder, like a file list sorted by date. Weeks run Monday to Sunday. */
fun dueGroupFor(date: LocalDate, today: LocalDate): DueGroup {
    val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    return when {
        date.isBefore(today) -> DueGroup("earlier", "Earlier")
        date == today -> DueGroup("today", "Today")
        date == today.plusDays(1) -> DueGroup("tomorrow", "Tomorrow")
        !date.isAfter(endOfWeek) -> DueGroup("this-week", "This week")
        !date.isAfter(endOfWeek.plusDays(7)) -> DueGroup("next-week", "Next week")
        YearMonth.from(date) == YearMonth.from(today) -> DueGroup("this-month", "Later this month")
        date.year == today.year -> DueGroup("m:${YearMonth.from(date)}", date.format(monthOnlyFormatter))
        else -> DueGroup("m:${YearMonth.from(date)}", date.format(monthYearFormatter))
    }
}

/**
 * Group for a reminder in the Archived list, counted back from today. Weeks run Monday to Sunday.
 * A reminder filed ahead of its date (done early, or archived by hand) is "Not due yet".
 */
fun archiveGroupFor(date: LocalDate, today: LocalDate): DueGroup {
    val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return when {
        date.isAfter(today) -> DueGroup("ahead", "Not due yet")
        date == today -> DueGroup("today", "Today")
        date == today.minusDays(1) -> DueGroup("yesterday", "Yesterday")
        !date.isBefore(startOfWeek) -> DueGroup("this-week", "Earlier this week")
        !date.isBefore(startOfWeek.minusDays(7)) -> DueGroup("last-week", "Last week")
        YearMonth.from(date) == YearMonth.from(today) -> DueGroup("this-month", "Earlier this month")
        date.year == today.year -> DueGroup("m:${YearMonth.from(date)}", date.format(monthOnlyFormatter))
        else -> DueGroup("m:${YearMonth.from(date)}", date.format(monthYearFormatter))
    }
}

/**
 * Flattens an already-sorted event list into header + item rows. Consecutive events that share a
 * group are placed under one header, so the result keeps the order the list came in.
 */
fun buildListEntries(
    events: List<PlannerEvent>,
    today: LocalDate,
    archived: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): List<PlannerListEntry> {
    val entries = ArrayList<PlannerListEntry>(events.size + 8)
    val seen = HashSet<String>()
    var index = 0
    while (index < events.size) {
        val group = groupOf(events[index], today, archived, zone)
        var end = index
        while (end < events.size && groupOf(events[end], today, archived, zone) == group) end++
        // A repeated key can only come from an unsorted list; keep keys unique for the lazy list.
        val unique = if (seen.add(group.key)) group else group.copy(key = "${group.key}#$index")
        entries += PlannerListEntry.Header(unique, end - index)
        for (position in index until end) entries += PlannerListEntry.Item(events[position])
        index = end
    }
    return entries
}

private fun groupOf(event: PlannerEvent, today: LocalDate, archived: Boolean, zone: ZoneId): DueGroup {
    val date = Instant.ofEpochMilli(event.startAtMillis).atZone(zone).toLocalDate()
    return if (archived) archiveGroupFor(date, today) else dueGroupFor(date, today)
}

/** "in 45 min", "in 3 h", "in 5 days", "2 h ago". */
fun relativeDue(startMillis: Long, nowMillis: Long): String {
    val diff = startMillis - nowMillis
    val minutes = abs(diff) / 60_000L
    val amount = when {
        minutes < 1 -> return "now"
        minutes < 90 -> "$minutes min"
        minutes < 48 * 60 -> "${(minutes / 60.0).roundToLong()} h"
        else -> "${(minutes / 1440.0).roundToLong()} days"
    }
    return if (diff >= 0) "in $amount" else "$amount ago"
}

/** Every whitespace-separated term must appear somewhere in the reminder's text (case-insensitive). */
fun PlannerEvent.matchesQuery(query: String): Boolean {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return true
    val haystack = listOf(
        title, location, notes, details, courseCode, courseName,
        resolvedCategory().displayName, schoolItemType,
    ).joinToString("\n").lowercase()
    return terms.all { it in haystack }
}

private val sectionNumber = Regex("[0-9]{2,5}[A-Za-z]?")

/**
 * Canvas reports cross-listed sections as "CHM110L 4001 CHM110L 4002 CHM110L 4201". When the
 * label is only one course code repeated with section numbers, show the code once.
 */
fun compactCourseLabel(raw: String): String {
    val tokens = raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (tokens.size < 2 || tokens.size % 2 != 0) return raw.trim()
    val code = tokens[0]
    if (code.none(Char::isDigit) || code.none(Char::isLetter)) return raw.trim()
    val repeatsCode = tokens.filterIndexed { index, _ -> index % 2 == 0 }.all { it.equals(code, ignoreCase = true) }
    val sectionsOnly = tokens.filterIndexed { index, _ -> index % 2 == 1 }.all(sectionNumber::matches)
    return if (repeatsCode && sectionsOnly) code else raw.trim()
}

fun PlannerEvent.courseKey(): String = courseColorKey.ifBlank {
    courseId.ifBlank { courseName.ifBlank { courseCode.ifBlank { "school" } } }
}

fun PlannerEvent.courseDisplayName(): String = courseCode.ifBlank {
    courseName.ifBlank { "Canvas course" }
}

fun PlannerEvent.isCanvas(): Boolean = source.equals("canvas", ignoreCase = true)

/** "10 points | File upload | 2 attempts", or blank when Canvas sent none of these. */
fun PlannerEvent.assignmentSummary(): String = buildList {
    pointsPossible?.let { add("${formatPoints(it)} points") }
    if (submissionTypes.isNotEmpty()) add(submissionTypes.joinToString(", ") { it.displaySubmissionType() })
    allowedAttempts?.let { add(if (it < 0) "Unlimited attempts" else "$it attempt${if (it == 1) "" else "s"}") }
}.joinToString(" | ")

/** Where the reminder came from, in plain words. */
fun PlannerEvent.sourceLabel(): String = when {
    isCanvas() -> "Canvas"
    remote -> "Cloud"
    else -> "This phone"
}

fun String.displaySchoolItemType(): String = when (lowercase()) {
    "quiz" -> "Quiz"
    "discussion" -> "Discussion"
    "peer_review" -> "Peer review"
    "assignment" -> "Assignment"
    else -> "Coursework"
}

fun String.displaySubmissionType(): String = when (lowercase()) {
    "online_upload" -> "File upload"
    "online_text_entry" -> "Text entry"
    "online_quiz" -> "Canvas quiz"
    "discussion_topic" -> "Discussion"
    "external_tool" -> "External tool"
    "media_recording" -> "Media recording"
    "on_paper" -> "In person"
    "none" -> "No submission"
    else -> replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun formatPoints(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

fun formatCanvasDate(value: String): String = runCatching {
    formatEventDate(Instant.parse(value).toEpochMilli())
}.getOrDefault(value)

private fun localDateTime(millis: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

fun formatEventDate(millis: Long): String = localDateTime(millis).format(eventDateFormatter)

fun formatFullDateTime(millis: Long): String = localDateTime(millis).format(fullDateTimeFormatter)

fun formatTime(millis: Long): String = localDateTime(millis).format(timeFormatter)

/** "Fri, Oct 2", or "Oct 2, 2027" when the year differs from today's. */
fun formatShortDate(date: LocalDate, today: LocalDate): String =
    date.format(if (date.year == today.year) shortDateFormatter else shortDateYearFormatter)

fun eventDate(event: PlannerEvent): LocalDate =
    Instant.ofEpochMilli(event.startAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()

fun describeReminderOffset(minutes: Int): String = when {
    minutes == 0 -> "at event time"
    minutes % 10080 == 0 -> "${minutes / 10080} week${if (minutes == 10080) "" else "s"} before"
    minutes % 1440 == 0 -> "${minutes / 1440} day${if (minutes == 1440) "" else "s"} before"
    minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes == 60) "" else "s"} before"
    else -> "$minutes minutes before"
}

fun formatReminderOffsets(offsets: List<Int>): String =
    offsets.sortedDescending().joinToString { describeReminderOffset(it) }
