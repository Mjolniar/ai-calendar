package com.example.aicalendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class ImportResult(
    val events: List<PlannerEvent>,
    val warnings: List<String>,
)

/**
 * JSON / iCalendar import.
 *
 * - Dates are parsed strictly (September 31 is rejected, not rolled back to September 30).
 * - ICS `TZID=` parameters and UTC `Z` suffixes are honoured; floating times use the device zone.
 * - Date-only values follow the approved date-only policy: 09:00 in the device zone.
 * - ICS `UID` gives stable ids so re-importing a file updates earlier imports instead of duplicating.
 * - Simple `RRULE`s (DAILY/WEEKLY[BYDAY]/MONTHLY/YEARLY with INTERVAL, COUNT, UNTIL, EXDATE) are
 *   expanded; anything else imports the first occurrence and reports a warning.
 */
object EventImportParser {
    const val DATE_ONLY_HOUR = 9
    private const val BYTE_ORDER_MARK = '\uFEFF'
    private const val MAX_OCCURRENCES = 366
    private const val MAX_WARNINGS = 8

    private val strictLocalMinute = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val icsDateTime = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val icsDateTimeNoSeconds = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmm").withResolverStyle(ResolverStyle.STRICT)
    private val icsDate = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
    private val occurrenceStamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmm")

    fun parse(content: String): List<PlannerEvent> = parseDetailed(content).events

    fun parseDetailed(
        content: String,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): ImportResult {
        val trimmed = content.trim().trimStart(BYTE_ORDER_MARK)
        val warnings = mutableListOf<String>()
        val events = runCatching {
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                parseJson(trimmed, zone, warnings)
            } else {
                parseIcs(trimmed, zone, nowMillis, warnings)
            }
        }.getOrElse {
            warnings += "File could not be read: ${it.message ?: it.javaClass.simpleName}"
            emptyList()
        }
        val distinctWarnings = warnings.distinct()
        val shown = distinctWarnings.take(MAX_WARNINGS) +
            if (distinctWarnings.size > MAX_WARNINGS) listOf("${distinctWarnings.size - MAX_WARNINGS} more warnings") else emptyList()
        return ImportResult(events, shown)
    }

    // ---------------------------------------------------------------- JSON

    private fun parseJson(content: String, zone: ZoneId, warnings: MutableList<String>): List<PlannerEvent> {
        val array = if (content.startsWith("{")) {
            JSONObject(content).optJSONArray("events") ?: JSONArray()
        } else {
            JSONArray(content)
        }
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index)
                if (json == null) {
                    warnings += "Skipped entry ${index + 1}: not an object"
                    continue
                }
                val title = json.cleanString("title").ifBlank { json.cleanString("summary") }.trim()
                val start = json.cleanString("startAt")
                    .ifBlank { json.cleanString("datetime") }
                    .ifBlank { json.cleanString("start") }
                val recordZone = json.cleanString("timeZone").takeIf(String::isNotBlank)?.let(::resolveZone) ?: zone
                val startMillis = when {
                    json.has("startAtMillis") && !json.isNull("startAtMillis") -> json.optLong("startAtMillis")
                    start.isNotBlank() -> parseDateTime(start, recordZone) ?: 0L
                    else -> 0L
                }
                when {
                    title.isBlank() -> warnings += "Skipped entry ${index + 1}: missing title"
                    startMillis <= 0L -> warnings += "Skipped \"$title\": invalid or missing date \"$start\""
                    else -> {
                        val critical = json.optBoolean("critical", false)
                        add(
                            PlannerEvent(
                                id = json.cleanString("id").ifBlank { UUID.randomUUID().toString() },
                                title = title,
                                startAtMillis = startMillis,
                                location = json.cleanString("location"),
                                notes = json.cleanString("notes").ifBlank { json.cleanString("description") },
                                critical = critical,
                                category = EventCategory.fromStored(json.cleanString("category")),
                                domain = EventDomain.fromStored(json.cleanString("domain")),
                                reminderOffsetsMinutes = json.optJSONArray("reminderOffsetsMinutes")
                                    ?.let(::parseReminderOffsets)
                                    ?: defaultReminderOffsets(critical),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Strict parse of the date formats accepted by JSON import and the editor. Returns null when invalid. */
    fun parseDateTime(value: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(text, strictLocalMinute).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { dateOnlyMillis(LocalDate.parse(text), zone) }
            .recoverCatching {
                val compact = text.removeSuffix("Z").removeSuffix("z")
                val local = parseIcsLocalDateTime(compact) ?: error("not ICS")
                val eventZone = if (compact.length != text.length) ZoneOffset.UTC else zone
                local.atZone(eventZone).toInstant().toEpochMilli()
            }
            .recoverCatching { dateOnlyMillis(LocalDate.parse(text, icsDate), zone) }
            .getOrNull()
    }

    private fun dateOnlyMillis(date: LocalDate, zone: ZoneId): Long =
        date.atTime(DATE_ONLY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    // ---------------------------------------------------------------- ICS

    private data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

    private class IcsEvent {
        val properties = mutableMapOf<String, IcsProperty>()
        val exDates = mutableListOf<IcsProperty>()
    }

    private data class IcsStart(val local: LocalDateTime, val zone: ZoneId, val dateOnly: Boolean)

    private data class Occurrence(val start: ZonedDateTime, val recurrenceKey: Long?)

    private fun parseIcs(content: String, zone: ZoneId, nowMillis: Long, warnings: MutableList<String>): List<PlannerEvent> {
        val vevents = mutableListOf<IcsEvent>()
        var current: IcsEvent? = null
        var nestedDepth = 0
        for (line in unfoldIcsLines(content)) {
            if (line.isBlank()) continue
            val property = parseProperty(line) ?: continue
            val event = current
            when {
                property.name == "BEGIN" && property.value.equals("VEVENT", ignoreCase = true) && event == null -> {
                    current = IcsEvent()
                    nestedDepth = 0
                }
                property.name == "END" && property.value.equals("VEVENT", ignoreCase = true) && nestedDepth == 0 -> {
                    event?.let(vevents::add)
                    current = null
                }
                event == null -> Unit
                // Nested components such as VALARM carry their own DESCRIPTION/SUMMARY; ignore them.
                property.name == "BEGIN" -> nestedDepth++
                property.name == "END" -> nestedDepth = (nestedDepth - 1).coerceAtLeast(0)
                nestedDepth > 0 -> Unit
                property.name == "EXDATE" -> event.exDates += property
                else -> event.properties.putIfAbsent(property.name, property)
            }
        }

        // Modified instances (RECURRENCE-ID) replace the matching generated occurrence.
        val overridesByUid = vevents
            .filter { it.properties["RECURRENCE-ID"] != null && it.properties["UID"] != null }
            .groupBy { it.properties.getValue("UID").value }
        val masters = vevents.filter { it.properties["RECURRENCE-ID"] == null }

        val results = mutableListOf<PlannerEvent>()
        masters.forEach { event ->
            val overrides = overridesByUid[event.properties["UID"]?.value].orEmpty()
            val overriddenKeys = overrides.mapNotNull { override ->
                resolveStart(override.properties.getValue("RECURRENCE-ID"), zone, warnings)?.let(::startMillis)
            }.toSet()
            results += buildIcsEvents(event, zone, nowMillis, warnings, overriddenKeys)
            overrides.forEach { override -> results += buildIcsEvents(override, zone, nowMillis, warnings, emptySet()) }
        }
        // Overrides whose master is missing from the file are still imported on their own.
        overridesByUid.filterKeys { uid -> masters.none { it.properties["UID"]?.value == uid } }
            .values.flatten()
            .forEach { results += buildIcsEvents(it, zone, nowMillis, warnings, emptySet()) }
        return results
    }

    private fun buildIcsEvents(
        event: IcsEvent,
        zone: ZoneId,
        nowMillis: Long,
        warnings: MutableList<String>,
        overriddenKeys: Set<Long>,
    ): List<PlannerEvent> {
        val title = event.properties["SUMMARY"]?.value?.let(::unescapeIcs)?.trim().orEmpty()
        val startProperty = event.properties["DTSTART"]
        if (title.isBlank()) {
            warnings += "Skipped an event with no title"
            return emptyList()
        }
        if (event.properties["STATUS"]?.value.equals("CANCELLED", ignoreCase = true)) {
            warnings += "Skipped cancelled event \"$title\""
            return emptyList()
        }
        if (startProperty == null) {
            warnings += "Skipped \"$title\": no start date"
            return emptyList()
        }
        val start = resolveStart(startProperty, zone, warnings)
        if (start == null) {
            warnings += "Skipped \"$title\": invalid start date \"${startProperty.value}\""
            return emptyList()
        }
        val location = event.properties["LOCATION"]?.value?.let(::unescapeIcs).orEmpty()
        val notes = event.properties["DESCRIPTION"]?.value?.let(::unescapeIcs).orEmpty()
        val uid = event.properties["UID"]?.value?.trim()?.takeIf(String::isNotBlank)
        val recurrenceId = event.properties["RECURRENCE-ID"]
        val baseId = uid?.let { "ics-" + it.replace(Regex("[^A-Za-z0-9@._-]"), "_") }

        val rule = event.properties["RRULE"]?.value
        val occurrences: List<Occurrence> = if (rule == null || recurrenceId != null) {
            listOf(Occurrence(start.local.atZone(start.zone), null))
        } else {
            val excluded = event.exDates.flatMap { exdate ->
                exdate.value.split(',').mapNotNull { part ->
                    resolveStart(exdate.copy(value = part.trim()), start.zone, warnings)?.let(::startMillis)
                }
            }.toSet()
            expandRule(start, rule, title, nowMillis, warnings)
                .filter { it.toInstant().toEpochMilli() !in excluded && it.toInstant().toEpochMilli() !in overriddenKeys }
                .map { Occurrence(it, it.toInstant().toEpochMilli()) }
        }

        return occurrences.map { occurrence ->
            val id = when {
                baseId == null -> UUID.randomUUID().toString()
                recurrenceId != null -> resolveStart(recurrenceId, zone, warnings)
                    ?.let { "$baseId-${it.local.format(occurrenceStamp)}" } ?: baseId
                occurrence.recurrenceKey != null -> "$baseId-${occurrence.start.toLocalDateTime().format(occurrenceStamp)}"
                else -> baseId
            }
            PlannerEvent(
                id = id,
                title = title,
                startAtMillis = occurrence.start.toInstant().toEpochMilli(),
                location = location,
                notes = notes,
            )
        }
    }

    private fun startMillis(start: IcsStart): Long = start.local.atZone(start.zone).toInstant().toEpochMilli()

    private fun resolveStart(property: IcsProperty, defaultZone: ZoneId, warnings: MutableList<String>): IcsStart? {
        val value = property.value.trim()
        val valueType = property.params["VALUE"]?.uppercase()
        if (valueType == "DATE" || (valueType == null && value.length == 8 && value.all(Char::isDigit))) {
            val date = runCatching { LocalDate.parse(value, icsDate) }.getOrNull() ?: return null
            return IcsStart(date.atTime(DATE_ONLY_HOUR, 0), defaultZone, dateOnly = true)
        }
        if (value.endsWith("Z", ignoreCase = true)) {
            val local = parseIcsLocalDateTime(value.dropLast(1)) ?: return null
            return IcsStart(local, ZoneOffset.UTC, dateOnly = false)
        }
        val local = parseIcsLocalDateTime(value) ?: return null
        val tzid = property.params["TZID"]
        val zone = if (tzid.isNullOrBlank()) {
            defaultZone
        } else {
            resolveZone(tzid) ?: defaultZone.also { warnings += "Unknown time zone \"$tzid\"; used ${defaultZone.id}" }
        }
        return IcsStart(local, zone, dateOnly = false)
    }

    private fun parseIcsLocalDateTime(value: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(value, icsDateTime) }
            .recoverCatching { LocalDateTime.parse(value, icsDateTimeNoSeconds) }
            .recoverCatching { LocalDateTime.parse(value) }
            .getOrNull()

    private val windowsZones = mapOf(
        "eastern standard time" to "America/New_York",
        "central standard time" to "America/Chicago",
        "mountain standard time" to "America/Denver",
        "us mountain standard time" to "America/Phoenix",
        "pacific standard time" to "America/Los_Angeles",
        "alaskan standard time" to "America/Anchorage",
        "hawaiian standard time" to "Pacific/Honolulu",
        "atlantic standard time" to "America/Halifax",
        "gmt standard time" to "Europe/London",
        "greenwich standard time" to "Atlantic/Reykjavik",
        "w. europe standard time" to "Europe/Berlin",
        "central europe standard time" to "Europe/Budapest",
        "romance standard time" to "Europe/Paris",
        "tokyo standard time" to "Asia/Tokyo",
        "utc" to "UTC",
        "coordinated universal time" to "UTC",
    )

    fun resolveZone(raw: String): ZoneId? {
        val tzid = raw.trim().trim('"')
        if (tzid.isEmpty()) return null
        zoneOrNull(tzid)?.let { return it }
        windowsZones[tzid.lowercase()]?.let { return ZoneId.of(it) }
        // Vendor-prefixed ids such as "/mozilla.org/20050126_1/America/New_York".
        val segments = tzid.split('/').filter(String::isNotBlank)
        for (count in 3 downTo 1) {
            if (segments.size >= count) zoneOrNull(segments.takeLast(count).joinToString("/"))?.let { return it }
        }
        return null
    }

    private fun zoneOrNull(id: String): ZoneId? = runCatching { ZoneId.of(id) }.getOrNull()

    private fun expandRule(
        start: IcsStart,
        rule: String,
        title: String,
        nowMillis: Long,
        warnings: MutableList<String>,
    ): List<ZonedDateTime> {
        val parts = rule.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim().uppercase() to part.substring(index + 1).trim()
        }.toMap()
        val first = start.local.atZone(start.zone)
        val freq = parts["FREQ"]?.uppercase()
        val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
        val until = parts["UNTIL"]?.let { value ->
            resolveStart(IcsProperty("UNTIL", emptyMap(), value), start.zone, warnings)?.let { untilStart ->
                // A date-only UNTIL includes that whole day.
                if (untilStart.dateOnly) untilStart.local.toLocalDate().atTime(LocalTime.MAX).atZone(start.zone)
                else untilStart.local.atZone(untilStart.zone)
            }
        }
        val weekdays = parts["BYDAY"]?.split(',')?.map { weekdayCodes[it.trim().uppercase()] }
        val unsupported = parts.keys - setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "WKST")
        val byDayUnsupported = weekdays != null && (freq != "WEEKLY" || weekdays.any { it == null })
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY") || unsupported.isNotEmpty() || byDayUnsupported) {
            warnings += "\"$title\" repeats with an unsupported rule ($rule); imported the first occurrence only"
            return listOf(first)
        }
        val horizon = Instant.ofEpochMilli(nowMillis).plusSeconds(366L * 24 * 3600)
        if (count == null && until == null) {
            warnings += "\"$title\" repeats forever; imported occurrences through the next year"
        }

        val results = mutableListOf<ZonedDateTime>()
        fun accept(local: LocalDateTime): Boolean {
            val candidate = local.atZone(start.zone)
            if (count != null && results.size >= count) return false
            if (until != null && candidate.isAfter(until)) return false
            if (count == null && until == null && candidate.toInstant().isAfter(horizon)) return false
            if (results.size >= MAX_OCCURRENCES) return false
            if (!candidate.isBefore(first)) results += candidate
            return true
        }

        val weeklyDays = weekdays?.filterNotNull()?.distinct()?.sortedBy { it.value } ?: listOf(start.local.dayOfWeek)
        var step = 0L
        loop@ while (step < 5000) {
            when (freq) {
                "DAILY" -> if (!accept(start.local.plusDays(step * interval))) break@loop
                "WEEKLY" -> {
                    val weekStart = start.local.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusWeeks(step * interval)
                    for (day in weeklyDays) {
                        val date = weekStart.plusDays((day.value - 1).toLong())
                        if (date.isBefore(start.local.toLocalDate())) continue
                        if (!accept(date.atTime(start.local.toLocalTime()))) break@loop
                    }
                }
                "MONTHLY" -> {
                    val candidate = start.local.plusMonths(step * interval)
                    // RFC 5545: skip months without that day (e.g. the 31st) instead of clamping.
                    if (candidate.dayOfMonth == start.local.dayOfMonth && !accept(candidate)) break@loop
                }
                "YEARLY" -> {
                    val candidate = start.local.plusYears(step * interval)
                    if (candidate.dayOfMonth == start.local.dayOfMonth && !accept(candidate)) break@loop
                }
            }
            step++
        }
        if (results.size >= MAX_OCCURRENCES) {
            warnings += "\"$title\" was limited to $MAX_OCCURRENCES occurrences"
        }
        return results.ifEmpty { listOf(first) }
    }

    private val weekdayCodes = mapOf(
        "MO" to DayOfWeek.MONDAY,
        "TU" to DayOfWeek.TUESDAY,
        "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY,
        "SA" to DayOfWeek.SATURDAY,
        "SU" to DayOfWeek.SUNDAY,
    )

    /** Splits `NAME;PARAM=value;PARAM="quoted:value":VALUE`, honouring quoted parameter values. */
    private fun parseProperty(line: String): IcsProperty? {
        var inQuotes = false
        var separator = -1
        for (index in line.indices) {
            val char = line[index]
            if (char == '"') inQuotes = !inQuotes
            if (char == ':' && !inQuotes) {
                separator = index
                break
            }
        }
        if (separator <= 0) return null
        val head = line.substring(0, separator)
        val value = line.substring(separator + 1)
        val segments = splitOutsideQuotes(head, ';')
        val name = segments.first().trim().uppercase()
        val params = segments.drop(1).mapNotNull { param ->
            val eq = param.indexOf('=')
            if (eq <= 0) null else param.substring(0, eq).trim().uppercase() to param.substring(eq + 1).trim().trim('"')
        }.toMap()
        return IcsProperty(name, params, value)
    }

    private fun splitOutsideQuotes(value: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        value.forEach { char ->
            when {
                char == '"' -> {
                    inQuotes = !inQuotes
                    current.append(char)
                }
                char == delimiter && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        result += current.toString()
        return result
    }

    private fun unfoldIcsLines(content: String): List<String> {
        val result = mutableListOf<String>()
        content.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) {
                result[result.lastIndex] = result.last() + line.drop(1)
            } else {
                result.add(line.trimEnd())
            }
        }
        return result
    }

    private fun unescapeIcs(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(next)
                }
                index += 2
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }
}

/** org.json's optString returns the literal "null" for JSON null; treat that as empty. */
internal fun JSONObject.cleanString(name: String): String =
    if (!has(name) || isNull(name)) "" else optString(name)
