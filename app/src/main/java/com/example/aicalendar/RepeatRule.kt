package com.example.aicalendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** How often a repeating reminder comes round. [wire] is the bridge's name for it. */
enum class RepeatFrequency(val wire: String, val displayName: String, val unit: String) {
    DAILY("daily", "Daily", "day"),
    WEEKLY("weekly", "Weekly", "week"),
    MONTHLY("monthly", "Monthly", "month"),
    YEARLY("yearly", "Yearly", "year");

    companion object {
        fun fromWire(value: String?): RepeatFrequency? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * When a repeating reminder happens, as the bridge stores it (`SeriesRule`). Dates and the time are
 * local to the series' zone. [weekdays] are ISO numbers, 1 = Monday to 7 = Sunday.
 */
data class RepeatRule(
    val frequency: RepeatFrequency,
    val interval: Int = 1,
    val weekdays: List<Int> = emptyList(),
    val startDate: LocalDate,
    val time: LocalTime,
    val until: LocalDate? = null,
    val count: Int? = null,
    val notBefore: LocalDate? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("freq", frequency.wire)
        .put("interval", interval)
        .put("startDate", startDate.toString())
        .put("time", time.format(WIRE_TIME))
        .apply {
            if (frequency == RepeatFrequency.WEEKLY && weekdays.isNotEmpty()) put("weekdays", JSONArray(weekdays))
            until?.let { put("until", it.toString()) }
            count?.let { put("count", it) }
            notBefore?.let { put("notBefore", it.toString()) }
        }

    companion object {
        val WIRE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

        fun fromJson(json: JSONObject): RepeatRule? = runCatching {
            val weekdays = json.optJSONArray("weekdays")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }.filter { it in 1..7 }.distinct().sorted()
            }.orEmpty()
            RepeatRule(
                frequency = RepeatFrequency.fromWire(json.optString("freq")) ?: return null,
                interval = json.optInt("interval", 1).coerceAtLeast(1),
                weekdays = weekdays,
                startDate = LocalDate.parse(json.getString("startDate")),
                time = LocalTime.parse(json.getString("time"), WIRE_TIME),
                until = json.optString("until").takeIf(String::isNotBlank)?.let(LocalDate::parse),
                count = if (json.has("count") && !json.isNull("count")) json.optInt("count") else null,
                notBefore = json.optString("notBefore").takeIf(String::isNotBlank)?.let(LocalDate::parse),
            )
        }.getOrNull()
    }
}

/** A repeating reminder as `/v1/sync` lists it. Its occurrences are ordinary reminders with its id. */
data class ReminderSeries(
    val id: String,
    val title: String,
    val notes: String = "",
    val location: String = "",
    val domain: EventDomain = EventDomain.LIFE,
    val category: EventCategory = EventCategory.AUTO,
    val critical: Boolean = false,
    val reminderOffsetsMinutes: List<Int> = defaultReminderOffsets(critical),
    val timeZone: String,
    val rule: RepeatRule,
    val skippedDates: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("notes", notes)
        .put("location", location)
        .put("domain", domain.name.lowercase())
        .put("category", category.name.lowercase())
        .put("critical", critical)
        .put("reminderOffsetsMinutes", JSONArray(reminderOffsetsMinutes))
        .put("timeZone", timeZone)
        .put("rule", rule.toJson())
        .put("skippedDates", JSONArray(skippedDates))

    companion object {
        fun fromJson(json: JSONObject): ReminderSeries? = runCatching {
            val id = json.getString("id").trim()
            require(id.isNotBlank())
            val critical = json.optBoolean("critical", false)
            ReminderSeries(
                id = id,
                title = json.optString("title"),
                notes = json.optString("notes"),
                location = json.optString("location"),
                domain = EventDomain.fromStored(json.optString("domain")),
                category = EventCategory.fromStored(json.optString("category")),
                critical = critical,
                reminderOffsetsMinutes = json.optJSONArray("reminderOffsetsMinutes")?.let(::parseReminderOffsets)
                    ?: defaultReminderOffsets(critical),
                timeZone = json.optString("timeZone").ifBlank { "UTC" },
                rule = RepeatRule.fromJson(json.getJSONObject("rule")) ?: return null,
                skippedDates = json.optJSONArray("skippedDates")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
                }.orEmpty(),
            )
        }.getOrNull()

        /** The `series` list of a sync response, or null when the bridge did not send one. */
        fun parseList(response: JSONObject): List<ReminderSeries>? {
            val array = response.optJSONArray("series") ?: return null
            return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::fromJson) }
        }
    }
}

/**
 * The rule in words, as details show it: "Every week on Friday at 8:00 AM, until Dec 4, 2026".
 * `ReminderText.cs` (`RepeatText`) words it the same way on Windows.
 */
object RepeatText {
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val untilFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun describe(rule: RepeatRule): String = buildString {
        append("Every ")
        append(if (rule.interval == 1) rule.frequency.unit else "${rule.interval} ${rule.frequency.unit}s")
        when (rule.frequency) {
            RepeatFrequency.DAILY -> Unit
            RepeatFrequency.WEEKLY -> {
                val days = rule.weekdays.ifEmpty { listOf(rule.startDate.dayOfWeek.value) }
                append(" on ").append(joinWords(days.sorted().map(::weekdayName)))
            }
            RepeatFrequency.MONTHLY -> append(" on the ").append(ordinal(rule.startDate.dayOfMonth))
            RepeatFrequency.YEARLY -> append(" on ")
                .append(rule.startDate.month.getDisplayName(TextStyle.FULL, Locale.US))
                .append(' ').append(rule.startDate.dayOfMonth)
        }
        append(" at ").append(rule.time.format(timeFormat))
        when {
            rule.until != null -> append(", until ").append(rule.until.format(untilFormat))
            rule.count != null -> append(", ").append(rule.count).append(if (rule.count == 1) " time" else " times")
        }
    }

    fun weekdayName(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, Locale.US)

    fun ordinal(day: Int): String = day.toString() + when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun joinWords(words: List<String>): String = when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
    }
}

/** The series the phone last received, kept beside the reminders so details can show the rule. */
class SeriesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): Map<String, ReminderSeries> = synchronized(LocalStoreLock) {
        val raw = prefs.getString(KEY_SERIES, null) ?: return@synchronized emptyMap()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@synchronized emptyMap()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(ReminderSeries::fromJson) }.associateBy { it.id }
    }

    fun replace(series: List<ReminderSeries>) = synchronized(LocalStoreLock) {
        val array = JSONArray()
        series.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SERIES, array.toString()).persist()
    }

    companion object {
        const val FILE = "planner_series"
        private const val KEY_SERIES = "series_json"
    }
}

/** How a repeating reminder ends, as the editor offers it. */
enum class RepeatEnd { NEVER, ON_DATE, AFTER }

/** Where the editor's Repeat box is offered. */
enum class RepeatEditMode {
    /** No Repeat box: a phone-only or single reminder, or "this one" of a series. */
    NONE,
    /** A new reminder: "Does not repeat" or a pattern. */
    NEW,
    /** "This and all future ones" of a series: a pattern must stay chosen. */
    SERIES,
}

/** The pattern chosen in the editor. The first date and the time come from the editor's own fields. */
data class RepeatChoice(
    val frequency: RepeatFrequency,
    val interval: Int = 1,
    val weekdays: List<Int> = emptyList(),
    val end: RepeatEnd = RepeatEnd.NEVER,
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    fun rule(startDate: LocalDate, time: LocalTime): RepeatRule = RepeatRule(
        frequency = frequency,
        interval = interval,
        weekdays = if (frequency == RepeatFrequency.WEEKLY) weekdays.ifEmpty { listOf(startDate.dayOfWeek.value) }.distinct().sorted() else emptyList(),
        startDate = startDate,
        time = time.withSecond(0).withNano(0),
        until = until.takeIf { end == RepeatEnd.ON_DATE },
        count = count.takeIf { end == RepeatEnd.AFTER },
    )

    /** A user-facing problem with the pattern, or null. Mirrors the bridge's rule validation. */
    fun validationError(startDate: LocalDate): String? = when {
        interval !in 1..999 -> "Repeat every 1 to 999 ${frequency.unit}s."
        frequency == RepeatFrequency.WEEKLY && weekdays.any { it !in 1..7 } -> "Choose the days of the week."
        end == RepeatEnd.ON_DATE && until == null -> "Choose the date it ends."
        end == RepeatEnd.ON_DATE && until!!.isBefore(startDate) -> "It cannot end before its first date."
        end == RepeatEnd.AFTER && (count == null || count !in 1..1000) -> "End after 1 to 1000 times."
        else -> null
    }

    companion object {
        fun from(rule: RepeatRule): RepeatChoice = RepeatChoice(
            frequency = rule.frequency,
            interval = rule.interval,
            weekdays = rule.weekdays.ifEmpty { listOf(rule.startDate.dayOfWeek.value) },
            end = when {
                rule.until != null -> RepeatEnd.ON_DATE
                rule.count != null -> RepeatEnd.AFTER
                else -> RepeatEnd.NEVER
            },
            until = rule.until,
            count = rule.count,
        )
    }
}

/** Bodies of the bridge's series requests, built from what the editor saved. */
object SeriesRequests {
    /** `POST /v1/series`: a new repeating reminder starting at [event]'s date and time. */
    fun create(event: PlannerEvent, choice: RepeatChoice, zone: ZoneId, idempotencyKey: String): JSONObject {
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.startAtMillis), zone)
        return fields(event, zone)
            .put("rule", choice.rule(start.toLocalDate(), start.toLocalTime()).toJson())
            .put("idempotencyKey", idempotencyKey)
    }

    /**
     * `PATCH /v1/series/:id` for "this and all future ones" from [occurrence]. The rule keeps its
     * first date unless the editor moved the occurrence to another day; then the pattern starts there.
     */
    fun update(event: PlannerEvent, occurrence: PlannerEvent, choice: RepeatChoice, zone: ZoneId, idempotencyKey: String): JSONObject {
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.startAtMillis), zone)
        val from = occurrence.occurrenceDate.ifBlank {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(occurrence.startAtMillis), zone).toLocalDate().toString()
        }
        val rule = choice.rule(start.toLocalDate(), start.toLocalTime())
        val ruleJson = JSONObject()
            .put("freq", rule.frequency.wire)
            .put("interval", rule.interval)
            .put("time", rule.time.format(RepeatRule.WIRE_TIME))
            .put("until", rule.until?.toString() ?: JSONObject.NULL)
            .put("count", rule.count ?: JSONObject.NULL)
        if (rule.frequency == RepeatFrequency.WEEKLY) ruleJson.put("weekdays", JSONArray(rule.weekdays))
        if (start.toLocalDate().toString() != from) ruleJson.put("startDate", start.toLocalDate().toString())
        return fields(event, zone)
            .put("rule", ruleJson)
            .put("from", from)
            .put("idempotencyKey", idempotencyKey)
    }

    private fun fields(event: PlannerEvent, zone: ZoneId): JSONObject = JSONObject()
        .put("title", event.title.trim())
        .put("timeZone", zone.id)
        .put("location", event.location)
        .put("notes", event.notes)
        .put("critical", event.critical)
        .put("domain", event.resolvedDomain().name.lowercase())
        .put("category", event.resolvedCategory().name.lowercase())
        .put("reminderOffsetsMinutes", JSONArray(event.reminderOffsetsMinutes))
}
