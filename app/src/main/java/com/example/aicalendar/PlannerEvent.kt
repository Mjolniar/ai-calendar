package com.example.aicalendar

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

data class PlannerEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startAtMillis: Long,
    val location: String = "",
    val notes: String = "",
    val critical: Boolean = false,
    val done: Boolean = false,
    val archived: Boolean = false,
    val category: EventCategory = EventCategory.AUTO,
    val domain: EventDomain = EventDomain.AUTO,
    val source: String = "manual",
    val sourceUrl: String = "",
    val courseId: String = "",
    val courseName: String = "",
    val courseCode: String = "",
    val courseColorKey: String = "",
    val schoolItemType: String = "",
    val details: String = "",
    val pointsPossible: Double? = null,
    val submissionTypes: List<String> = emptyList(),
    val unlockAt: String = "",
    val lockAt: String = "",
    val allowedAttempts: Int? = null,
    val reminderOffsetsMinutes: List<Int> = defaultReminderOffsets(critical),
    val remote: Boolean = false,
    /** Set on an occurrence of a repeating reminder: the series it belongs to. */
    val seriesId: String = "",
    /** The occurrence's date within its series (YYYY-MM-DD). It identifies the occurrence; it never moves. */
    val occurrenceDate: String = "",
    /** An occurrence that was changed on its own ("this one"). */
    val detached: Boolean = false,
    /** The server's revision of this copy (0 for a phone-only reminder): edits send it, so a stale copy is refused. */
    val revision: Long = 0L,
) {
    val isOccurrence: Boolean get() = seriesId.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("startAtMillis", startAtMillis)
        .put("location", location)
        .put("notes", notes)
        .put("critical", critical)
        .put("done", done)
        .put("archived", archived)
        .put("category", category.name)
        .put("domain", domain.name)
        .put("source", source)
        .put("sourceUrl", sourceUrl)
        .put("courseId", courseId)
        .put("courseName", courseName)
        .put("courseCode", courseCode)
        .put("courseColorKey", courseColorKey)
        .put("schoolItemType", schoolItemType)
        .put("details", details)
        .put("pointsPossible", pointsPossible ?: JSONObject.NULL)
        .put("submissionTypes", JSONArray(submissionTypes))
        .put("unlockAt", unlockAt)
        .put("lockAt", lockAt)
        .put("allowedAttempts", allowedAttempts ?: JSONObject.NULL)
        .put("reminderOffsetsMinutes", JSONArray(reminderOffsetsMinutes))
        .put("remote", remote)
        .put("seriesId", seriesId)
        .put("occurrenceDate", occurrenceDate)
        .put("detached", detached)
        .put("revision", revision)

    companion object {
        fun fromJson(json: JSONObject): PlannerEvent = PlannerEvent(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = json.optString("title"),
            startAtMillis = json.optLong("startAtMillis"),
            location = json.optString("location"),
            notes = json.optString("notes"),
            critical = json.optBoolean("critical", false),
            done = json.optBoolean("done", false),
            archived = json.optBoolean("archived", false),
            category = EventCategory.fromStored(json.optString("category")),
            domain = EventDomain.fromStored(json.optString("domain")),
            source = json.optString("source", if (json.optBoolean("remote", false)) "chatgpt" else "manual"),
            sourceUrl = json.optString("sourceUrl"),
            courseId = json.optString("courseId"),
            courseName = json.optString("courseName"),
            courseCode = json.optString("courseCode"),
            courseColorKey = json.optString("courseColorKey"),
            schoolItemType = json.optString("schoolItemType"),
            details = json.optString("details"),
            pointsPossible = if (json.isNull("pointsPossible")) null else json.optDouble("pointsPossible").takeUnless { it.isNaN() },
            submissionTypes = json.optJSONArray("submissionTypes")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }.orEmpty(),
            unlockAt = json.optString("unlockAt"),
            lockAt = json.optString("lockAt"),
            allowedAttempts = if (json.isNull("allowedAttempts")) null else json.optInt("allowedAttempts"),
            // An explicitly empty array means "no reminders" and must survive reload; only a
            // missing/invalid field falls back to the defaults.
            reminderOffsetsMinutes = json.optJSONArray("reminderOffsetsMinutes")
                ?.let(::parseReminderOffsets)
                ?: defaultReminderOffsets(json.optBoolean("critical", false)),
            remote = json.optBoolean("remote", false),
            seriesId = json.optString("seriesId"),
            occurrenceDate = json.optString("occurrenceDate"),
            detached = json.optBoolean("detached", false),
            revision = json.optLong("revision", 0L),
        )
    }
}

/** Largest reminder lead time accepted from storage, imports, or the server (one year, as the bridge). */
const val MAX_REMINDER_OFFSET_MINUTES = 525_600
private const val MAX_REMINDER_OFFSETS = 20

fun defaultReminderOffsets(critical: Boolean): List<Int> = if (critical) listOf(1440, 0) else listOf(1440)

/** Parses a JSON offsets array, dropping malformed/out-of-range entries. Empty input stays empty. */
fun parseReminderOffsets(array: JSONArray): List<Int> = buildList {
    for (index in 0 until array.length()) {
        val value = array.opt(index)
        val minutes = when (value) {
            is Number -> value.toDouble().takeIf { it == Math.floor(it) }?.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
        if (minutes != null && minutes in 0..MAX_REMINDER_OFFSET_MINUTES) add(minutes)
    }
}.distinct().take(MAX_REMINDER_OFFSETS)

/**
 * Mirrors the bridge's device-edit validation so the editor can explain a problem before sending.
 * Returns a user-facing message, or null when the event is acceptable.
 */
fun PlannerEvent.validationError(): String? = when {
    title.isBlank() -> "Title is required."
    title.trim().length > 500 -> "Title must be at most 500 characters."
    startAtMillis <= 0L -> "Use date format YYYY-MM-DD HH:MM."
    location.length > 1000 -> "Location must be at most 1000 characters."
    notes.length > 10_000 -> "Notes must be at most 10,000 characters."
    reminderOffsetsMinutes.size > MAX_REMINDER_OFFSETS ||
        reminderOffsetsMinutes.any { it !in 0..MAX_REMINDER_OFFSET_MINUTES } -> "Too many or invalid reminder times."
    else -> null
}

/**
 * Reminder offsets after the Critical switch changes in the editor. Turning Critical on adds the
 * event-time alarm (and the day-before alarm when the event was on the standard default); turning it
 * off only removes the event-time alarm when the offsets are exactly the critical default.
 */
fun offsetsAfterCriticalChange(current: List<Int>?, wasCritical: Boolean, nowCritical: Boolean): List<Int> {
    if (current == null) return defaultReminderOffsets(nowCritical)
    if (wasCritical == nowCritical) return current
    return if (nowCritical) {
        when {
            current.isEmpty() || current.toSet() == setOf(1440) -> listOf(1440, 0)
            0 in current -> current
            else -> current + 0
        }
    } else {
        if (current.toSet() == setOf(1440, 0)) listOf(1440) else current
    }
}

/** Fields that determine which alarms an event needs; used to reschedule only changed events. */
fun PlannerEvent.alarmSignature(): String =
    listOf(startAtMillis, alertOffsets().sorted().joinToString(","), critical, done, archived).joinToString("|")
