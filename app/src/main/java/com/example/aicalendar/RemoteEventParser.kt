package com.example.aicalendar

import org.json.JSONObject
import java.time.Instant

/**
 * Parsed `/v1/sync` response.
 *
 * [unparsedIds] are server records that were present but malformed. The local copy of those events
 * is kept rather than treated as deleted, so one bad record can never wipe reminders on the phone.
 */
data class RemoteSnapshot(
    val events: List<PlannerEvent>,
    val unparsedIds: Set<String>,
    val skippedCount: Int,
    /** The repeating reminders, or null when the bridge sent none (an older bridge). */
    val series: List<ReminderSeries>? = null,
)

class MalformedSnapshotException(message: String) : IllegalStateException(message)

object RemoteEventParser {
    fun parseSnapshot(response: JSONObject): RemoteSnapshot {
        // A response without an events array (proxy error page, schema change) must not be read as
        // "the server has zero events": that would delete every remote reminder on the phone.
        val array = response.optJSONArray("events")
            ?: throw MalformedSnapshotException("Sync response did not include an events list")
        val events = mutableListOf<PlannerEvent>()
        val unparsed = mutableSetOf<String>()
        var skipped = 0
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index)
            if (json == null) {
                skipped++
                continue
            }
            runCatching { parseEvent(json) }
                .onSuccess { events += it }
                .onFailure {
                    skipped++
                    json.cleanString("id").trim().takeIf(String::isNotBlank)?.let(unparsed::add)
                }
        }
        // Duplicate ids: keep the last occurrence, like a map write would.
        val deduped = events.associateBy { it.id }.values.toList()
        return RemoteSnapshot(deduped, unparsed, skipped, ReminderSeries.parseList(response))
    }

    fun parseEvent(json: JSONObject): PlannerEvent {
        val id = json.cleanString("id").trim()
        require(id.isNotBlank()) { "missing id" }
        val title = json.cleanString("title").trim()
        require(title.isNotBlank()) { "missing title" }
        val startAtMillis = Instant.parse(json.cleanString("startAt").trim()).toEpochMilli()
        require(startAtMillis > 0L) { "invalid startAt" }
        val critical = json.optBoolean("critical", false)
        return PlannerEvent(
            id = id,
            title = title,
            startAtMillis = startAtMillis,
            location = json.cleanString("location"),
            notes = json.cleanString("notes"),
            critical = critical,
            done = json.optBoolean("done", false),
            domain = EventDomain.fromStored(json.cleanString("domain")),
            category = EventCategory.fromStored(json.cleanString("category")),
            source = json.cleanString("source").ifBlank { "chatgpt" },
            sourceUrl = json.cleanString("sourceUrl"),
            courseId = json.cleanString("courseId"),
            courseName = json.cleanString("courseName"),
            courseCode = json.cleanString("courseCode"),
            courseColorKey = json.cleanString("courseColorKey"),
            schoolItemType = json.cleanString("schoolItemType"),
            details = json.cleanString("details"),
            pointsPossible = if (!json.has("pointsPossible") || json.isNull("pointsPossible")) {
                null
            } else {
                json.optDouble("pointsPossible").takeUnless { it.isNaN() || it.isInfinite() }
            },
            submissionTypes = json.optJSONArray("submissionTypes")?.let { values ->
                buildList {
                    for (valueIndex in 0 until values.length()) {
                        if (!values.isNull(valueIndex)) values.optString(valueIndex).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.orEmpty(),
            unlockAt = json.cleanString("unlockAt"),
            lockAt = json.cleanString("lockAt"),
            allowedAttempts = if (!json.has("allowedAttempts") || json.isNull("allowedAttempts")) {
                null
            } else {
                json.optString("allowedAttempts").toDoubleOrNull()?.toInt()
            },
            // Absent -> defaults. Present but empty -> explicitly no reminders.
            reminderOffsetsMinutes = json.optJSONArray("reminderOffsetsMinutes")
                ?.let(::parseReminderOffsets)
                ?: defaultReminderOffsets(critical),
            remote = true,
            seriesId = json.cleanString("seriesId").trim(),
            occurrenceDate = json.cleanString("occurrenceDate").trim(),
            detached = json.optBoolean("detached", false),
            revision = json.optLong("revision", 0L),
        )
    }
}
