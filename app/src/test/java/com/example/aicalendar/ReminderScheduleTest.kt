package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderScheduleTest {
    private val denver = ZoneId.of("America/Denver")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(denver).toInstant().toEpochMilli()

    /** "kind@local time" for each planned alarm, in firing order. */
    private fun plan(start: String, vararg offsets: Int): List<String> =
        ReminderSchedule.plan(at(start), offsets.toList(), denver).map {
            "${it.kind}@${LocalDateTime.ofInstant(Instant.ofEpochMilli(it.triggerAtMillis), denver)}"
        }

    @Test
    fun assignmentDueAtEndOfDayAlertsInTheEvening() {
        // A Canvas quiz due Friday 11:59 PM used to alert at 11:59 PM on Tuesday, Thursday and Friday.
        assertEquals(
            listOf("offset_4320@2026-10-06T20:00", "offset_1440@2026-10-08T20:00", "tonight@2026-10-09T20:00"),
            plan("2026-10-09T23:59:59", 4320, 1440, 120, 60, 0),
        )
    }

    @Test
    fun midnightDeadlineBelongsToTheDayThatIsEnding() {
        assertEquals(
            listOf("offset_1440@2026-10-08T20:00", "tonight@2026-10-09T20:00"),
            plan("2026-10-10T00:00", 1440, 0),
        )
    }

    @Test
    fun daytimeEventsAreLeftAlone() {
        assertEquals(
            listOf("offset_1440@2026-10-07T13:00", "offset_60@2026-10-08T12:00", "offset_0@2026-10-08T13:00"),
            plan("2026-10-08T13:00", 1440, 60, 0),
        )
        assertNull(ReminderSchedule.movedNote(at("2026-10-08T13:00"), listOf(1440, 60, 0), denver))
    }

    @Test
    fun earlyFlightKeepsItsWakeUpAlertsButNotTheDayBeforeOne() {
        assertEquals(
            listOf("offset_1440@2026-10-25T08:00", "offset_180@2026-10-26T03:15", "offset_0@2026-10-26T06:15"),
            plan("2026-10-26T06:15", 1440, 180, 0),
        )
    }

    @Test
    fun lateEventThatIsNotADeadlineKeepsItsShortAlerts() {
        assertEquals(
            listOf("offset_1440@2026-10-01T20:00", "offset_60@2026-10-02T22:30", "offset_0@2026-10-02T23:30"),
            plan("2026-10-02T23:30", 1440, 60, 0),
        )
    }

    @Test
    fun nightBoundariesAreNinePmAndEightAm() {
        assertEquals(listOf("offset_1440@2026-10-07T20:59"), plan("2026-10-08T20:59", 1440))
        assertEquals(listOf("offset_1440@2026-10-07T20:00"), plan("2026-10-08T21:00", 1440))
        assertEquals(listOf("offset_1440@2026-10-07T08:00"), plan("2026-10-08T07:59", 1440))
        assertEquals(listOf("offset_1440@2026-10-07T08:00"), plan("2026-10-08T08:00", 1440))
    }

    @Test
    fun halfDayNoticeCountsAsAdvanceNotice() {
        // 12 hours before a 10 AM event is 10 PM the evening before.
        assertEquals(listOf("offset_720@2026-10-07T20:00"), plan("2026-10-08T10:00", 720))
        // A shorter lead on a daytime event is a last-minute alert and stays put.
        assertEquals(listOf("offset_600@2026-10-08T00:30"), plan("2026-10-08T10:30", 600))
    }

    @Test
    fun endOfDayDeadlineKeepsEarlierSameDayAlerts() {
        assertEquals(
            listOf("offset_360@2026-10-09T17:59", "tonight@2026-10-09T20:00"),
            plan("2026-10-09T23:59", 360, 60, 0),
        )
    }

    @Test
    fun daysBeforeKeepsTheClockTimeAcrossDaylightSaving() {
        // Clocks go back on Nov 1, 2026: a day before Sunday 11:59 PM is still Saturday evening.
        assertEquals(listOf("offset_1440@2026-10-31T20:00"), plan("2026-11-01T23:59", 1440))
        assertEquals(listOf("offset_1440@2026-10-31T13:00"), plan("2026-11-01T13:00", 1440))
    }

    @Test
    fun noteNamesTheTimesAlertsMoveTo() {
        assertEquals(
            "Night-time alerts arrive at 8:00 PM.",
            ReminderSchedule.movedNote(at("2026-10-09T23:59"), listOf(4320, 1440, 0), denver),
        )
        assertEquals(
            "Night-time alerts arrive at 8:00 AM.",
            ReminderSchedule.movedNote(at("2026-10-26T06:15"), listOf(1440, 0), denver),
        )
        assertNull(ReminderSchedule.movedNote(at("2026-10-09T23:59"), emptyList(), denver))
    }

    @Test
    fun duplicateOffsetsAndNoOffsets() {
        assertEquals(listOf("offset_1440@2026-10-07T13:00"), plan("2026-10-08T13:00", 1440, 1440))
        assertEquals(emptyList<String>(), plan("2026-10-08T13:00"))
    }
}
