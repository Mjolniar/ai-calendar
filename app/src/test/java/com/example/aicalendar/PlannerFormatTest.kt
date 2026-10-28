package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PlannerFormatTest {
    // Thursday; the week ends Sunday, October 4.
    private val today = LocalDate.of(2026, 10, 1)
    private val zone = ZoneId.of("America/Denver")

    private fun event(id: String, date: LocalDate, hour: Int = 9, title: String = "Item $id") = PlannerEvent(
        id = id,
        title = title,
        startAtMillis = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
    )

    @Test
    fun dueGroupsFollowTheCalendarWeek() {
        assertEquals("Today", dueGroupFor(today, today).label)
        assertEquals("Tomorrow", dueGroupFor(today.plusDays(1), today).label)
        assertEquals("This week", dueGroupFor(LocalDate.of(2026, 10, 3), today).label)
        // Sunday still belongs to this week; next week starts on Monday and runs through Sunday.
        assertEquals("This week", dueGroupFor(LocalDate.of(2026, 10, 4), today).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 10, 5), today).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 10, 11), today).label)
        assertEquals("Later this month", dueGroupFor(LocalDate.of(2026, 10, 12), today).label)
        assertEquals("November", dueGroupFor(LocalDate.of(2026, 11, 2), today).label)
        assertEquals("January 2027", dueGroupFor(LocalDate.of(2027, 1, 5), today).label)
        assertEquals("Earlier", dueGroupFor(today.minusDays(1), today).label)
    }

    @Test
    fun onTheWeekendNextWeekStillStartsOnMonday() {
        val saturday = LocalDate.of(2026, 10, 3)
        assertEquals("Tomorrow", dueGroupFor(saturday.plusDays(1), saturday).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 10, 5), saturday).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 10, 11), saturday).label)
        assertEquals("Later this month", dueGroupFor(LocalDate.of(2026, 10, 12), saturday).label)

        // Sunday is the last day of its week: nothing is left for "This week".
        val sunday = LocalDate.of(2026, 10, 4)
        assertEquals("Today", dueGroupFor(sunday, sunday).label)
        assertEquals("Tomorrow", dueGroupFor(LocalDate.of(2026, 10, 5), sunday).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 10, 6), sunday).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 10, 11), sunday).label)
        assertEquals("Later this month", dueGroupFor(LocalDate.of(2026, 10, 12), sunday).label)
    }

    @Test
    fun onMondayTheWholeWeekIsThisWeek() {
        val monday = LocalDate.of(2026, 10, 5)
        assertEquals("Tomorrow", dueGroupFor(LocalDate.of(2026, 10, 6), monday).label)
        assertEquals("This week", dueGroupFor(LocalDate.of(2026, 10, 7), monday).label)
        assertEquals("This week", dueGroupFor(LocalDate.of(2026, 10, 11), monday).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 10, 12), monday).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 10, 18), monday).label)
        assertEquals("Later this month", dueGroupFor(LocalDate.of(2026, 10, 19), monday).label)
    }

    @Test
    fun monthGridStartsItsWeeksOnMonday() {
        // June 2026 starts on a Monday, November 2026 on a Sunday, October 2026 on a Thursday.
        assertEquals(0, leadingBlankDays(java.time.YearMonth.of(2026, 6)))
        assertEquals(6, leadingBlankDays(java.time.YearMonth.of(2026, 11)))
        assertEquals(3, leadingBlankDays(java.time.YearMonth.of(2026, 10)))
    }

    @Test
    fun nextWeekCanReachIntoTheNextMonth() {
        val lateOctober = LocalDate.of(2026, 10, 28)
        assertEquals("This week", dueGroupFor(LocalDate.of(2026, 10, 31), lateOctober).label)
        // The week of Wednesday, October 28 ends on Sunday, November 1.
        assertEquals("This week", dueGroupFor(LocalDate.of(2026, 11, 1), lateOctober).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 11, 2), lateOctober).label)
        assertEquals("Next week", dueGroupFor(LocalDate.of(2026, 11, 8), lateOctober).label)
        assertEquals("November", dueGroupFor(LocalDate.of(2026, 11, 9), lateOctober).label)
    }

    @Test
    fun listEntriesPutOneHeaderAboveEachRun() {
        val events = listOf(
            event("a", today),
            event("b", today, hour = 15),
            event("c", today.plusDays(1)),
            event("d", LocalDate.of(2026, 10, 20)),
        )
        val entries = buildListEntries(events, today, archived = false, zone = zone)
        assertEquals(
            listOf("Today (2)", "a", "b", "Tomorrow (1)", "c", "Later this month (1)", "d"),
            entries.map {
                when (it) {
                    is PlannerListEntry.Header -> "${it.group.label} (${it.count})"
                    is PlannerListEntry.Item -> it.event.id
                }
            },
        )
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun archiveGroupsCountBackFromToday() {
        // Today is Thursday, October 1; its week began on Monday, September 28.
        assertEquals("Today", archiveGroupFor(today, today).label)
        assertEquals("Yesterday", archiveGroupFor(LocalDate.of(2026, 9, 30), today).label)
        assertEquals("Earlier this week", archiveGroupFor(LocalDate.of(2026, 9, 29), today).label)
        assertEquals("Earlier this week", archiveGroupFor(LocalDate.of(2026, 9, 28), today).label)
        assertEquals("Last week", archiveGroupFor(LocalDate.of(2026, 9, 27), today).label)
        assertEquals("Last week", archiveGroupFor(LocalDate.of(2026, 9, 21), today).label)
        assertEquals("September", archiveGroupFor(LocalDate.of(2026, 9, 20), today).label)
        assertEquals("August", archiveGroupFor(LocalDate.of(2026, 8, 30), today).label)
        assertEquals("December 2025", archiveGroupFor(LocalDate.of(2025, 12, 31), today).label)
        // Filed before its date: not history yet.
        assertEquals("Not due yet", archiveGroupFor(today.plusDays(1), today).label)
        assertEquals("Not due yet", archiveGroupFor(LocalDate.of(2026, 12, 13), today).label)
    }

    @Test
    fun archiveHasEarlierThisMonthOnceTheMonthIsOldEnough() {
        // Thursday, October 22: this week began on October 19, last week on October 12.
        val late = LocalDate.of(2026, 10, 22)
        assertEquals("Earlier this week", archiveGroupFor(LocalDate.of(2026, 10, 19), late).label)
        assertEquals("Last week", archiveGroupFor(LocalDate.of(2026, 10, 12), late).label)
        assertEquals("Earlier this month", archiveGroupFor(LocalDate.of(2026, 10, 11), late).label)
        assertEquals("Earlier this month", archiveGroupFor(LocalDate.of(2026, 10, 1), late).label)
        assertEquals("September", archiveGroupFor(LocalDate.of(2026, 9, 30), late).label)
    }

    @Test
    fun onMondayTheArchiveHasNoEarlierThisWeek() {
        val monday = LocalDate.of(2026, 10, 5)
        assertEquals("Yesterday", archiveGroupFor(LocalDate.of(2026, 10, 4), monday).label)
        assertEquals("Last week", archiveGroupFor(LocalDate.of(2026, 10, 3), monday).label)
        assertEquals("Last week", archiveGroupFor(LocalDate.of(2026, 9, 28), monday).label)
        assertEquals("September", archiveGroupFor(LocalDate.of(2026, 9, 27), monday).label)
    }

    @Test
    fun archivedEntriesRunFromTheLatestBackwardsWithEarlyOnesLast() {
        val filed = listOf(
            event("exam", LocalDate.of(2026, 12, 13)).copy(done = true),
            event("class", LocalDate.of(2026, 10, 6), hour = 15).copy(done = true),
            event("lunch", today, hour = 13),
            event("morning", today, hour = 9),
            event("yesterday", LocalDate.of(2026, 9, 30)),
            event("monday", LocalDate.of(2026, 9, 28)),
            event("last-week", LocalDate.of(2026, 9, 24)),
            event("old", LocalDate.of(2026, 9, 2)),
            event("ahead", LocalDate.of(2026, 10, 20)),
        )
        val now = today.atTime(14, 5).atZone(zone).toInstant().toEpochMilli()
        // Whatever order they are stored in, the archive comes out the same.
        val buckets = bucketEvents(filed.reversed(), nowMillis = now, zone = zone)
        assertEquals(listOf("ahead"), buckets.current.map { it.id })
        val entries = buildListEntries(buckets.archived, today, archived = true, zone = zone)
        assertEquals(
            listOf(
                "Today (2)", "lunch", "morning",
                "Yesterday (1)", "yesterday",
                "Earlier this week (1)", "monday",
                "Last week (1)", "last-week",
                "September (1)", "old",
                "Not due yet (2)", "class", "exam",
            ),
            entries.map {
                when (it) {
                    is PlannerListEntry.Header -> "${it.group.label} (${it.count})"
                    is PlannerListEntry.Item -> it.event.id
                }
            },
        )
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun unsortedInputStillYieldsUniqueKeys() {
        val events = listOf(event("a", today), event("b", today.plusDays(1)), event("c", today))
        val entries = buildListEntries(events, today, archived = false, zone = zone)
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun relativeDueReadsNaturally() {
        val now = 1_000_000_000_000L
        val minute = 60_000L
        assertEquals("now", relativeDue(now + 20_000, now))
        assertEquals("in 45 min", relativeDue(now + 45 * minute, now))
        assertEquals("in 2 h", relativeDue(now + 90 * minute, now))
        assertEquals("in 11 h", relativeDue(now + 11 * 60 * minute, now))
        assertEquals("in 3 days", relativeDue(now + 3 * 1440 * minute, now))
        assertEquals("30 min ago", relativeDue(now - 30 * minute, now))
        assertEquals("5 days ago", relativeDue(now - 5 * 1440 * minute, now))
    }

    @Test
    fun searchMatchesEveryTermAcrossFields() {
        val item = PlannerEvent(
            title = "Lab 5 Quiz - Gas Laws",
            startAtMillis = 1L,
            location = "Science Building 204",
            notes = "Bring goggles",
            courseCode = "CHM110L 4001",
        )
        assertTrue(item.matchesQuery(""))
        assertTrue(item.matchesQuery("  QUIZ "))
        assertTrue(item.matchesQuery("chm110l gas"))
        assertTrue(item.matchesQuery("goggles science"))
        assertFalse(item.matchesQuery("quiz biology"))
    }

    @Test
    fun crossListedSectionsCollapseToTheCourseCode() {
        assertEquals("CHM110L", compactCourseLabel("CHM110L 4001 CHM110L 4002 CHM110L 4201"))
        assertEquals("ETH110", compactCourseLabel("ETH110 001"))
        assertEquals("NUTR150", compactCourseLabel("NUTR150 4002 NUTR150 4006 NUTR150 4007"))
    }

    @Test
    fun otherCourseLabelsAreLeftAlone() {
        assertEquals("ENGL 102", compactCourseLabel("ENGL 102"))
        assertEquals("English Composition II", compactCourseLabel("English Composition II"))
        assertEquals("MATH143 001 MATH144 002", compactCourseLabel("MATH143 001 MATH144 002"))
        assertEquals("CHM110", compactCourseLabel(" CHM110 "))
        assertEquals("", compactCourseLabel(""))
    }

    @Test
    fun reminderOffsetsReadLongestFirst() {
        assertEquals("1 week before, 1 day before, at event time", formatReminderOffsets(listOf(0, 10080, 1440)))
        assertEquals("3 days before", describeReminderOffset(4320))
        assertEquals("2 hours before", describeReminderOffset(120))
        assertEquals("45 minutes before", describeReminderOffset(45))
    }
}
