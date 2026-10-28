package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class EventImportParserTest {
    private val denver = ZoneId.of("America/Denver")
    private val now = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()

    private fun ics(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT") + lines + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n")

    private fun parse(content: String) = EventImportParser.parseDetailed(content, denver, now)

    private fun instant(event: PlannerEvent) = Instant.ofEpochMilli(event.startAtMillis).toString()

    @Test
    fun honoursTzidParameter() {
        val result = parse(ics("SUMMARY:Visit", "DTSTART;TZID=America/New_York:20261001T090000"))
        assertEquals("2026-10-01T13:00:00Z", instant(result.events.single()))
    }

    @Test
    fun honoursQuotedAndWindowsTzids() {
        val quoted = parse(ics("SUMMARY:Visit", "DTSTART;TZID=\"America/New_York\":20261001T090000"))
        assertEquals("2026-10-01T13:00:00Z", instant(quoted.events.single()))
        val windows = parse(ics("SUMMARY:Visit", "DTSTART;TZID=Eastern Standard Time:20261001T090000"))
        assertEquals("2026-10-01T13:00:00Z", instant(windows.events.single()))
        val vendor = parse(ics("SUMMARY:Visit", "DTSTART;TZID=/mozilla.org/20050126_1/America/New_York:20261001T090000"))
        assertEquals("2026-10-01T13:00:00Z", instant(vendor.events.single()))
    }

    @Test
    fun utcSuffixIsUtc() {
        val result = parse(ics("SUMMARY:Flight", "DTSTART:20261001T150000Z"))
        assertEquals("2026-10-01T15:00:00Z", instant(result.events.single()))
    }

    @Test
    fun floatingTimeUsesDeviceZone() {
        val result = parse(ics("SUMMARY:Class", "DTSTART:20261001T090000"))
        assertEquals("2026-10-01T15:00:00Z", instant(result.events.single()))
    }

    @Test
    fun dateOnlyUsesNineAmPolicy() {
        val result = parse(ics("SUMMARY:Due date", "DTSTART;VALUE=DATE:20261001"))
        assertEquals("2026-10-01T15:00:00Z", instant(result.events.single()))
    }

    @Test
    fun impossibleDateIsRejectedWithWarning() {
        val result = parse(ics("SUMMARY:Impossible", "DTSTART:20260931T090000"))
        assertTrue(result.events.isEmpty())
        assertTrue(result.warnings.single().contains("Impossible"))
    }

    @Test
    fun dailyCountExpandsWithStableIds() {
        val result = parse(ics("UID:stable-class", "SUMMARY:Class", "DTSTART:20261001T090000", "RRULE:FREQ=DAILY;COUNT=3"))
        assertEquals(3, result.events.size)
        assertEquals(
            listOf("2026-10-01T15:00:00Z", "2026-10-02T15:00:00Z", "2026-10-03T15:00:00Z"),
            result.events.map(::instant),
        )
        assertEquals(3, result.events.map { it.id }.toSet().size)
        assertTrue(result.events.all { it.id.startsWith("ics-stable-class-") })
        // Re-import yields the same ids, so an update replaces instead of duplicating.
        val again = parse(ics("UID:stable-class", "SUMMARY:Class", "DTSTART:20261001T090000", "RRULE:FREQ=DAILY;COUNT=3"))
        assertEquals(result.events.map { it.id }, again.events.map { it.id })
    }

    @Test
    fun weeklyByDayUntilKeepsLocalTimeAcrossDst() {
        val result = parse(
            ics(
                "UID:bio",
                "SUMMARY:Biology lecture",
                "DTSTART;TZID=America/Denver:20261026T100000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261104T235959Z",
                "EXDATE;TZID=America/Denver:20261028T100000",
            ),
        )
        // Oct 26 (MDT), [Oct 28 excluded], Nov 2 and Nov 4 (MST): still 10:00 local.
        assertEquals(
            listOf("2026-10-26T16:00:00Z", "2026-11-02T17:00:00Z", "2026-11-04T17:00:00Z"),
            result.events.map(::instant),
        )
    }

    @Test
    fun unsupportedRuleImportsFirstOccurrenceWithWarning() {
        val result = parse(ics("SUMMARY:Club", "DTSTART:20261001T090000", "RRULE:FREQ=MONTHLY;BYDAY=1TH"))
        assertEquals(1, result.events.size)
        assertTrue(result.warnings.any { it.contains("unsupported") })
    }

    @Test
    fun alarmComponentDoesNotOverwriteEventFields() {
        val result = parse(
            ics(
                "SUMMARY:Dentist",
                "DESCRIPTION:Bring insurance card",
                "DTSTART:20261001T090000",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "DESCRIPTION:Reminder",
                "END:VALARM",
                "LOCATION:Main St",
            ),
        )
        val event = result.events.single()
        assertEquals("Bring insurance card", event.notes)
        assertEquals("Main St", event.location)
    }

    @Test
    fun recurrenceIdOverridesOneOccurrence() {
        val content = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT", "UID:lab", "SUMMARY:Lab", "DTSTART:20261001T090000", "RRULE:FREQ=DAILY;COUNT=2", "END:VEVENT",
            "BEGIN:VEVENT", "UID:lab", "RECURRENCE-ID:20261002T090000", "SUMMARY:Lab (moved)", "DTSTART:20261002T130000", "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\n")
        val result = parse(content)
        assertEquals(listOf("Lab", "Lab (moved)"), result.events.map { it.title })
        assertEquals("2026-10-02T19:00:00Z", instant(result.events[1]))
    }

    @Test
    fun cancelledEventsAreSkipped() {
        val result = parse(ics("SUMMARY:Cancelled", "STATUS:CANCELLED", "DTSTART:20261001T090000"))
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun jsonImportPreservesOffsetsIncludingExplicitEmpty() {
        val json = """
            [
              {"title":"Quiet","startAt":"2026-10-01T09:00","reminderOffsetsMinutes":[]},
              {"title":"Custom","startAt":"2026-10-01T09:00","reminderOffsetsMinutes":[60, "oops", -5]},
              {"title":"Default","startAt":"2026-10-01","critical":true},
              {"title":"Bad date","startAt":"2026-09-31T09:00"},
              {"title":null,"startAt":"2026-10-01T09:00"}
            ]
        """.trimIndent()
        val result = parse(json)
        assertEquals(listOf("Quiet", "Custom", "Default"), result.events.map { it.title })
        assertEquals(emptyList<Int>(), result.events[0].reminderOffsetsMinutes)
        assertEquals(listOf(60), result.events[1].reminderOffsetsMinutes)
        assertEquals(listOf(1440, 0), result.events[2].reminderOffsetsMinutes)
        assertEquals("2026-10-01T15:00:00Z", instant(result.events[2]))
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun strictEditorFormat() {
        assertEquals(null, EventImportParser.parseDateTime("2026-02-30 09:00", denver))
        assertEquals(
            Instant.parse("2026-10-01T15:00:00Z").toEpochMilli(),
            EventImportParser.parseDateTime("2026-10-01 09:00", denver),
        )
    }

    @Test
    fun garbageDoesNotThrow() {
        val result = parse("{ not json")
        assertTrue(result.events.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }
}
